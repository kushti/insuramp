package p2pgate.app.net

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
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

    /**
     * The quote feed, pushed as a **full snapshot** per frame
     * (`specs/operator-backend.md` §9: `WS /v1/quotes/stream` is
     * "full-snapshot pushes on publish/withdraw/expire"). The flow ends when the
     * socket drops; the caller falls back to polling.
     */
    fun quoteFeedStream(): Flow<QuoteFeedDto>

    /** One deal's transition events. */
    fun dealStream(dealId: String, token: String): Flow<EventDto>
}

class KtorBackendClient(
    baseUrl: String,
    private val client: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        // Without these a stalled connection hangs a viewModelScope coroutine
        // for as long as the server (or a captive portal) holds it open.
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
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

    override fun quoteFeedStream(): Flow<QuoteFeedDto> = textStream<QuoteFeedDto>("$wsBase/v1/quotes/stream")

    override fun dealStream(dealId: String, token: String): Flow<EventDto> =
        textStream("$wsBase/v1/deals/$dealId/stream?token=$token")

    /**
     * Reconnecting text-frame stream. A dropped socket is normal (screen off,
     * network change) and used to end the flow for good, leaving the deal screen
     * frozen; the collector is now told about the drop and this side reconnects
     * with capped exponential backoff until the collector cancels.
     */
    private inline fun <reified T> textStream(url: String): Flow<T> = flow {
        var backoffMs = INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                client.webSocket(url) {
                    while (true) {
                        val frame = incoming.receive() as? Frame.Text ?: continue
                        // A frame we cannot read is dropped, not fatal: one
                        // malformed push must not kill the feed.
                        runCatching { Json.decodeFromString<T>(frame.readText()) }
                            .onSuccess { emit(it) }
                    }
                }
                backoffMs = INITIAL_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Transport drop: back off and try again. The ViewModel's
                // onStreamDrop is what schedules the polling fallback.
            }
            if (!currentCoroutineContext().isActive) break
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
