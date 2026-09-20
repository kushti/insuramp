package p2pgate.app.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * The buyer-side `/v1` API surface (`specs/operator-backend.md` §9). Interface
 * keeps the ViewModels unit-testable (fake in, Ktor out); all deal reads are
 * buyer-token authorized (`Bearer dealToken` from deal creation).
 */
interface BackendClient {
    suspend fun quotes(): QuoteFeedDto
    suspend fun createDeal(request: CreateDealRequest): CreateDealResponse
    suspend fun deal(dealId: String, token: String): DealDto
    suspend fun submitHandoff(dealId: String, token: String, request: HandoffSubmitRequest)
    suspend fun attestation(dealId: String, token: String): AttestationDto
    suspend fun claim(dealId: String, token: String): ClaimGuideDto
    suspend fun infra(): InfraDto

    /** Quote feed updates (published/withdrawn/paused events). */
    fun quotesStream(): Flow<EventDto>

    /** One deal's transition events. */
    fun dealStream(dealId: String, token: String): Flow<EventDto>
}

class KtorBackendClient(
    baseUrl: String,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
    },
) : BackendClient {

    private val httpBase = baseUrl.trimEnd('/')
    private val wsBase = httpBase.replace(Regex("^http"), "ws")

    override suspend fun quotes(): QuoteFeedDto = client.get("$httpBase/v1/quotes").body()

    override suspend fun createDeal(request: CreateDealRequest): CreateDealResponse {
        val response = client.post("$httpBase/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        // Surface the backend's rejection reason ({"error": ...}) instead of a
        // decode exception — the deal engine's messages are the useful part.
        if (!response.status.isSuccess()) {
            val msg = runCatching { Json.decodeFromString<ErrorDto>(response.bodyAsText()).error }
                .getOrElse { "HTTP ${response.status.value}" }
            throw IllegalStateException(msg)
        }
        return response.body()
    }

    override suspend fun deal(dealId: String, token: String): DealDto =
        client.get("$httpBase/v1/deals/$dealId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    override suspend fun submitHandoff(dealId: String, token: String, request: HandoffSubmitRequest) {
        client.post("$httpBase/v1/deals/$dealId/handoff") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun attestation(dealId: String, token: String): AttestationDto =
        client.get("$httpBase/v1/deals/$dealId/attestation") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    override suspend fun claim(dealId: String, token: String): ClaimGuideDto =
        client.post("$httpBase/v1/deals/$dealId/claim") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body()

    override suspend fun infra(): InfraDto = client.get("$httpBase/v1/infra").body()

    override fun quotesStream(): Flow<EventDto> = eventStream("$wsBase/v1/quotes/stream")

    override fun dealStream(dealId: String, token: String): Flow<EventDto> =
        eventStream("$wsBase/v1/deals/$dealId/stream?token=$token")

    private fun eventStream(url: String): Flow<EventDto> = callbackFlow {
        try {
            client.webSocket(url) {
                while (true) {
                    val frame = incoming.receive() as? Frame.Text ?: continue
                    val event = Json.decodeFromString<EventDto>(frame.readText())
                    trySend(event)
                }
            }
        } catch (e: Exception) {
            // Transport drop: collectors fall back to polling; the flow just ends.
        }
        close()
    }
}
