package p2pgate.tui.common

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `/v1` client against a mock transport: endpoint paths, the `Bearer`
 * credential each surface needs, and the error wording an operator reads.
 *
 * Two classes of bug this file is here to catch, both invisible to a fake
 * [p2pgate.tui.common.BackendClient]:
 *  - **drift** — a DTO field or a path that no longer matches the backend (the
 *    DTOs are hand-mirrored, per `specs/operator-backend.md` §9);
 *  - **credential mix-ups** — an operator endpoint reached with a deal token, or
 *    a buyer endpoint reached without one.
 *
 * WebSocket transports stay uncovered (Ktor's `MockEngine` cannot host a socket
 * session); the frame *shapes* are pinned as literals instead, mirroring the
 * Android client's approach.
 *
 * These tests use [runBlocking], not `runTest`: the mock engine dispatches on a
 * real dispatcher, and a virtual-time clock can finish a test while a response is
 * still in flight — which leaks that response into the next test and makes the
 * suite non-deterministic (observed: two runs disagreeing on which cases failed).
 */
class KtorBackendClientSpec {

    private val config = TuiConfig(baseUrl = "https://operator.example", operatorKey = "op-key")

    private fun client(engine: MockEngine) =
        KtorBackendClient(config, HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })

    /** Records what was sent and answers with a canned body. */
    private fun recordingEngine(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: (HttpRequestData) -> Unit = {},
    ): Pair<MockEngine, MutableList<HttpRequestData>> {
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            onRequest(request)
            respond(
                content = body,
                status = status,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        return engine to seen
    }

    // ---------------------------------------------------------------- operator reads

    @Test
    fun `lane is read from the operator path with the operator key`() = runBlocking {
        val (engine, seen) = recordingEngine("""{"lanes":{"FUNDED":[]}}""")
        val lane = client(engine).lane()
        assertEquals(1, seen.size)
        assertEquals("https://operator.example/v1/lane", seen[0].url.toString())
        assertEquals("Bearer op-key", seen[0].headers["Authorization"])
        assertEquals(emptyList(), lane.lanes.getValue("FUNDED"))
    }

    @Test
    fun `a lane card decodes the fields the console reads`() = runBlocking {
        val body = """
        {"lanes":{"FUNDED":[{
          "dealId":"d1","amount":500000000,"fiatCurrency":"INR",
          "collateralTokenId":"abab","createdAtEpochMs":1700000000000,
          "fundedAtEpochMs":1700000000000,
          "reclaimDeadlineEpochMs":1700086400000,
          "claimMaturesAtEpochMs":null,"contested":false,
          "hasHandoffRecord":true,"vaultBoxId":"cdcd","exitPath":"release"
        }]}}
        """.trimIndent()
        val (engine, _) = recordingEngine(body)
        val card = client(engine).lane().lanes.getValue("FUNDED").single()
        assertEquals("d1", card.dealId)
        assertEquals(500_000_000L, card.amount)
        assertEquals("INR", card.fiatCurrency)
        assertTrue(card.hasHandoffRecord)
        assertEquals("cdcd", card.vaultBoxId)
        assertEquals("release", card.exitPath)
    }

    @Test
    fun `unknown fields in a response are ignored so a backend can add any`() = runBlocking {
        // Forward compatibility is a documented property of these DTOs
        // (unknown fields are ignored on read), so it is pinned rather than assumed.
        val body = """{"mixReady":1,"locked":2,"free":3,"utilizationPct":33.3,"openDeals":1,"futureField":true}"""
        val (engine, _) = recordingEngine(body)
        val pool = client(engine).pool()
        assertEquals(2L, pool.locked)
        assertEquals(33.3, pool.utilizationPct)
    }

    @Test
    fun `disputes is a bare JSON array, not an object`() = runBlocking {
        // /v1/disputes answers a bare array (the inbox rows mapped directly) — a
        // client that expects a wrapper fails here and nowhere else, so the shape
        // is pinned.
        val body = """[{"dealId":"d2","state":"CLAIM_OPENED","openedAtEpochMs":1,"maturesAtEpochMs":2,"contested":true,"oracleConfirmed":false,"actioned":false}]"""
        val (engine, seen) = recordingEngine(body)
        val rows = client(engine).disputes()
        assertEquals("https://operator.example/v1/disputes", seen[0].url.toString())
        assertEquals(1, rows.size)
        assertEquals("d2", rows.single().dealId)
        assertTrue(rows.single().contested)
    }

    @Test
    fun `an empty dispute inbox decodes to no rows`() = runBlocking {
        val (engine, _) = recordingEngine("[]")
        assertEquals(emptyList(), client(engine).disputes())
    }

    // ---------------------------------------------------------------- actions

    @Test
    fun `accept posts to the offer path with the operator key`() = runBlocking {
        val (engine, seen) = recordingEngine("""{"dealId":"d1","state":"FUNDED","fundTxId":"tx1","vaultBoxId":"b1"}""")
        val funded = client(engine).acceptOffer("d1")
        assertEquals("https://operator.example/v1/dashboard/deals/d1/accept", seen[0].url.toString())
        assertEquals("tx1", funded.fundTxId)
        assertEquals("Bearer op-key", seen[0].headers["Authorization"])
    }

    @Test
    fun `a dispute action carries the action in the path`() = runBlocking {
        val (engine, seen) = recordingEngine("""{"error":"action recorded"}""")
        client(engine).disputeAction("d9", "contest")
        assertEquals("https://operator.example/v1/disputes/d9/contest", seen[0].url.toString())
    }

    @Test
    fun `a dispute rejection surfaces the inbox's own wording`() = runBlocking {
        val (engine, _) = recordingEngine(
            """{"error":"the claim is not actionable without an attestation"}""",
            HttpStatusCode.Conflict,
        )
        val error = runCatching { client(engine).disputeAction("d9", "contest") }.exceptionOrNull()
        assertTrue(error is IllegalStateException, error.toString())
        assertTrue(error.message!!.contains("not actionable"), error.message)
    }

    // ---------------------------------------------------------------- buyer surface

    @Test
    fun `a deal read carries the deal token as a bearer`() = runBlocking {
        val body = """{"dealId":"d1","state":"FUNDED","amount":1,"fiatAmount":1,"fiatCurrency":"INR","insuredAmount":1,"sellerPubKey":"02ab","createdAtEpochMs":1}"""
        val (engine, seen) = recordingEngine(body)
        client(engine).deal("d1", "deal-token")
        assertEquals("https://operator.example/v1/deals/d1", seen[0].url.toString())
        assertEquals("Bearer deal-token", seen[0].headers["Authorization"])
    }

    @Test
    fun `a buyer rejection surfaces the deal engine wording`() = runBlocking {
        val (engine, _) = recordingEngine(
            """{"error":"fiatAmount 9200 does not match the quote's rate"}""",
            HttpStatusCode.UnprocessableEntity,
        )
        val error = runCatching { client(engine).createDeal(request()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException, error.toString())
        assertTrue(error.message!!.contains("does not match the quote"), error.message)
    }

    @Test
    fun `an unconfigured operator key sends no Authorization header at all`() = runBlocking {
        // The backend treats a missing key as demo-open, so the header must be
        // absent rather than an empty bearer — "Bearer " would be a different
        // request, and a demo-open backend would reject it.
        val seen = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            seen += request
            respond("""{"lanes":{}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val anonymous = KtorBackendClient(
            TuiConfig("https://operator.example", operatorKey = null),
            HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
        )
        anonymous.lane()
        assertEquals(1, seen.size)
        assertNull(seen.single().headers["Authorization"])
    }

    // ---------------------------------------------------------------- transport

    @Test
    fun `a non-json error body still reports the status`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        val error = runCatching { client(engine).lane() }.exceptionOrNull()
        assertTrue(error is IllegalStateException, error.toString())
        assertTrue(error.message!!.contains("502"), error.message)
    }

    @Test
    fun `the QR endpoint is asked for as image bytes`() = runBlocking {
        val engine = MockEngine { respond(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) }
        val png = client(engine).handoffQrPng("d1")
        assertEquals(4, png.size)
        assertEquals(0x89.toByte(), png[0], "PNG magic byte")
    }

    @Test
    fun `a quote publish decodes the published quote`() = runBlocking {
        val body = """{"published":true,"quote":{"id":"q1","version":1,"spreadBps":100,"etaMinutes":30,"minAmount":1,"maxAmount":2,"fiatCurrency":"INR","fiatPerUsdtMicros":92000000,"createdAtEpochMs":1,"expiresAtEpochMs":2},"reason":null}"""
        val (engine, seen) = recordingEngine(body)
        val result = client(engine).publishQuote(putQuote())
        assertTrue(result.published)
        assertEquals(92_000_000L, result.quote!!.fiatPerUsdtMicros)
        assertEquals("PUT", seen.single().method.value, "quotes are published with PUT")
    }

    @Test
    fun `a refused quote publish keeps the reason the operator needs`() = runBlocking {
        val (engine, _) = recordingEngine(
            """{"published":false,"quote":null,"reason":"capacity is exhausted"}""",
            HttpStatusCode.Conflict,
        )
        val error = runCatching { client(engine).publishQuote(putQuote()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException, error.toString())
        assertTrue(error.message!!.contains("capacity is exhausted"), error.message)
    }

    private fun putQuote() = p2pgate.tui.common.wire.PutQuoteRequest(
        spreadBps = 100,
        etaMinutes = 30,
        minAmount = 1_000_000,
        maxAmount = 500_000_000,
        fiatCurrency = "INR",
        fiatPerUsdtMicros = 92_000_000,
    )

    private fun request() = p2pgate.tui.common.wire.CreateDealRequest(
        quoteId = "q1",
        amount = 500_000_000,
        receiveAddress = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
        buyerPubKey = "02ab",
        fiatCurrency = "INR",
        fiatAmount = 46_000,
    )
}
