package p2pgate.app.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The buyer `/v1` client: endpoint paths, the `Bearer` deal token, and the
 * error-message path the buyer screen surfaces.
 *
 * The frame-shape case is the regression this file exists for: the quote feed
 * socket pushes a **full snapshot** (`{"quotes":[...]}`) while the app used to
 * decode those frames as single events, so the live feed died silently. The
 * snapshot shape is pinned here as a literal frame (Ktor's `MockEngine` cannot
 * host a WebSocket session, so the socket *transport* stays uncovered).
 */
class BackendClientSpec {

    private fun client(engine: MockEngine) =
        KtorBackendClient("https://operator.example", HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        })

    private fun jsonEngine(body: String, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine {
        respond(
            content = body,
            status = status,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }

    private fun request() = CreateDealRequest(
        quoteId = "q1",
        amount = 500_000_000L,
        receiveAddress = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
        buyerPubKey = "02ab",
        fiatCurrency = "INR",
        fiatAmount = 46_000L,
    )

    @Test
    fun `deal creation surfaces the backend rejection reason`() = runTest {
        val engine = jsonEngine(
            """{"error":"receiveAddress is not a valid TRON address"}""",
            HttpStatusCode.UnprocessableEntity,
        )
        val error = runCatching { client(engine).createDeal(request()) }.exceptionOrNull()
        // The engine's own message, not a serialization failure — the rejection
        // reason is the part the buyer can act on.
        assertTrue(error is IllegalStateException, error.toString())
        assertTrue(error.message!!.contains("not a valid TRON address"), error.message)
    }

    @Test
    fun `a non-json error body still reports the status`() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        val error = runCatching { client(engine).createDeal(request()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException, error.toString())
        assertTrue(error.message!!.contains("502"), error.message)
    }

    @Test
    fun `the quote feed snapshot decodes including the rate`() = runTest {
        val engine = jsonEngine(QUOTE_FEED_FRAME)
        val feed = client(engine).quotes()
        assertEquals(1, feed.quotes.size)
        assertEquals("q1", feed.quotes.single().id)
        assertEquals(92_000_000L, feed.quotes.single().fiatPerUsdtMicros)
    }

    @Test
    fun `a deal status body decodes with its optional fields`() = runTest {
        val engine = jsonEngine(
            """{"dealId":"d1","state":"FUNDED","amount":500000000,"fiatAmount":46000,
               "fiatCurrency":"INR","insuredAmount":500000000,"sellerPubKey":"aabb",
               "vaultBoxId":"box-1","createdAtEpochMs":1,"reclaimDeadlineEpochMs":2}""",
        )
        val dto = client(engine).deal("d1", "tok")
        assertEquals("d1", dto.dealId)
        assertEquals("box-1", dto.vaultBoxId)
        assertEquals(2L, dto.reclaimDeadlineEpochMs)
        // §6.4: the contested flag is optional on the wire and defaults false.
        assertEquals(false, dto.contested)
    }

    @Test
    fun `the deal token travels as a bearer header`() = runTest {
        var auth: String? = null
        val engine = MockEngine { request ->
            auth = request.headers["Authorization"]
            respond(
                content = """{"dealId":"d1","state":"FUNDED","amount":500000000,"fiatAmount":46000,
                    "fiatCurrency":"INR","insuredAmount":500000000,"sellerPubKey":"aabb",
                    "vaultBoxId":"box-1","createdAtEpochMs":1}""",
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        client(engine).deal("d1", "tok-9")
        assertEquals("Bearer tok-9", auth)
    }

    private companion object {
        /** The exact frame `WS /v1/quotes/stream` pushes per publish/withdraw/expire. */
        const val QUOTE_FEED_FRAME =
            """{"quotes":[{"id":"q1","version":1,"spreadBps":120,"etaMinutes":30,""" +
                """"fiatCurrency":"INR","minAmount":1000000,"maxAmount":500000000,""" +
                """"fiatPerUsdtMicros":92000000,"createdAtEpochMs":1,"expiresAtEpochMs":2}]}"""
    }
}
