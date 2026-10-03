package p2pgate.tui.common

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import p2pgate.tui.common.wire.AmlCheckRequest
import p2pgate.tui.common.wire.AmlCheckResponse
import p2pgate.tui.common.wire.AttestationDto
import p2pgate.tui.common.wire.ClaimGuideDto
import p2pgate.tui.common.wire.CreateDealRequest
import p2pgate.tui.common.wire.CreateDealResponse
import p2pgate.tui.common.wire.DealDto
import p2pgate.tui.common.wire.DisputeRowDto
import p2pgate.tui.common.wire.ErrorDto
import p2pgate.tui.common.wire.EventDto
import p2pgate.tui.common.wire.FundDealResponse
import p2pgate.tui.common.wire.HandoffSignResponse
import p2pgate.tui.common.wire.HandoffSubmitRequest
import p2pgate.tui.common.wire.InfraDto
import p2pgate.tui.common.wire.LaneDto
import p2pgate.tui.common.wire.MessageDto
import p2pgate.tui.common.wire.PoolDto
import p2pgate.tui.common.wire.PublishQuoteResponse
import p2pgate.tui.common.wire.PutQuoteRequest
import p2pgate.tui.common.wire.QuoteFeedDto
import p2pgate.tui.common.wire.ReclaimResponse

/**
 * Ktor implementation of [BackendClient] — the same HTTP surface the Android
 * app speaks (`specs/android-app.md` §4.1), so a console drives a running
 * operator backend with no extra service.
 *
 * Errors are surfaced deliberately: a non-2xx response carries the backend's
 * own `{"error": …}` wording (the deal engine's message is the useful part), so
 * that text becomes the exception message instead of a decode failure.
 */
class KtorBackendClient(
    private val config: TuiConfig,
    private val client: HttpClient = defaultClient(),
) : BackendClient {

    private val base = config.baseUrl.trimEnd('/')
    private val ws = config.wsBaseUrl
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val operatorKey = config.operatorKey

    // ---------------------------------------------------------------- buyer-facing

    override suspend fun quotes(): QuoteFeedDto = client.get("$base/v1/quotes").ok()

    override suspend fun createDeal(request: CreateDealRequest): CreateDealResponse =
        client.post("$base/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.ok()

    override suspend fun deal(dealId: String, dealToken: String): DealDto =
        client.get("$base/v1/deals/$dealId") { bearer(dealToken) }.ok()

    override suspend fun submitHandoff(dealId: String, dealToken: String, request: HandoffSubmitRequest) {
        client.post("$base/v1/deals/$dealId/handoff") {
            bearer(dealToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.expectSuccess()
    }

    override suspend fun attestation(dealId: String, dealToken: String): AttestationDto =
        client.get("$base/v1/deals/$dealId/attestation") { bearer(dealToken) }.ok()

    override suspend fun claimGuide(dealId: String, dealToken: String): ClaimGuideDto =
        client.post("$base/v1/deals/$dealId/claim") { bearer(dealToken) }.ok()

    override fun quoteFeedStream(): Flow<QuoteFeedDto> = stream("$ws/v1/quotes/stream")

    override fun dealStream(dealId: String, dealToken: String): Flow<EventDto> =
        stream("$ws/v1/deals/$dealId/stream?token=$dealToken")

    // ---------------------------------------------------------------- operator

    override suspend fun lane(): LaneDto = client.get("$base/v1/lane") { operator() }.ok()

    override suspend fun pool(): PoolDto = client.get("$base/v1/pool") { operator() }.ok()

    override suspend fun infra(): InfraDto = client.get("$base/v1/infra") { operator() }.ok()

    override suspend fun disputes(): List<DisputeRowDto> =
        client.get("$base/v1/disputes") { operator() }.ok()

    override suspend fun disputeAction(dealId: String, action: String) {
        // The inbox answers 200 with ErrorDto("action recorded") and 409 with the
        // rejection reason, so only the failure carries anything to read.
        client.post("$base/v1/disputes/$dealId/$action") { operator() }.expectSuccess()
    }

    override suspend fun acceptOffer(dealId: String): FundDealResponse =
        client.post("$base/v1/dashboard/deals/$dealId/accept") { operator() }.ok()

    override suspend fun declineOffer(dealId: String): MessageDto =
        client.post("$base/v1/dashboard/deals/$dealId/decline") { operator() }.ok()

    override suspend fun signHandoff(dealId: String): HandoffSignResponse =
        client.post("$base/v1/dashboard/deals/$dealId/handoff/sign") { operator() }.ok()

    override suspend fun handoffQrPng(dealId: String): ByteArray =
        client.get("$base/v1/dashboard/deals/$dealId/handoff/qr.png") { operator() }
            .expectSuccess()
            .readBytes()

    override suspend fun reclaim(dealId: String): ReclaimResponse =
        client.post("$base/v1/vaults/$dealId/reclaim") { operator() }.ok()

    override suspend fun publishQuote(request: PutQuoteRequest): PublishQuoteResponse =
        client.put("$base/v1/dashboard/quotes") {
            operator()
            contentType(ContentType.Application.Json)
            setBody(request)
        }.ok()

    override suspend fun withdrawQuote(quoteId: String): MessageDto =
        client.post("$base/v1/dashboard/quotes/$quoteId/withdraw") { operator() }.ok()

    override suspend fun amlCheck(address: String, chainId: Int): AmlCheckResponse =
        client.post("$base/v1/aml/check") {
            operator()
            contentType(ContentType.Application.Json)
            setBody(AmlCheckRequest(address, chainId))
        }.ok()

    override fun operatorEvents(): Flow<EventDto> = stream("$ws/v1/events")

    // ---------------------------------------------------------------- plumbing

    private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.operator() {
        if (!operatorKey.isNullOrBlank()) header(HttpHeaders.Authorization, "Bearer $operatorKey")
    }

    /** Throws with the backend's own wording on a non-2xx, otherwise decodes [T]. */
    private suspend inline fun <reified T> HttpResponse.ok(): T =
        expectSuccess().body()

    private suspend fun HttpResponse.expectSuccess(): HttpResponse {
        if (!status.isSuccess()) throw IllegalStateException(errorMessage())
        return this
    }

    private suspend fun HttpResponse.errorMessage(): String {
        val text = runCatching { bodyAsText() }.getOrDefault("")
        return decodeMessage(text) ?: "HTTP ${status.value}"
    }

    private fun decodeMessage(text: String): String? {
        if (text.isBlank()) return null
        runCatching { json.decodeFromString<ErrorDto>(text).error }
            .getOrNull()
            ?.let { return it.takeIf { m -> m.isNotBlank() } }
        return runCatching { json.decodeFromString<MessageDto>(text).message }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { json.decodeFromString<PublishQuoteResponse>(text).reason }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
    }

    /**
     * Reconnecting text-frame stream. A console's socket drops for ordinary
     * reasons (laptop sleep, network change); ending the flow for good would
     * freeze a stale board on screen, so failures are swallowed and retried.
     */
    private inline fun <reified T> stream(url: String): Flow<T> = flow {
        while (true) {
            try {
                client.webSocket(urlString = url) {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        runCatching { json.decodeFromString<T>(frame.readText()) }
                            .getOrNull()
                            ?.let { emit(it) }
                    }
                }
            } catch (_: Exception) {
                // Console shows "reconnecting" from the gap in frames.
            }
            delay(RECONNECT_DELAY_MS)
        }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 2_000L

        fun defaultClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
    }
}
