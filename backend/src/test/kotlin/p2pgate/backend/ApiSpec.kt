package p2pgate.backend

import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.api.CreateDealOutcome
import p2pgate.backend.api.module
import p2pgate.backend.util.Hex
import java.time.Duration

/**
 * The `/v1` HTTP surface (`specs/operator-backend.md` §9) against a test
 * application over fakes. Auth scopes, canonical state names and the
 * buyer-facing flows are exercised end-to-end over JSON.
 */
class ApiSpec {

    private val json = Json { ignoreUnknownKeys = true }

    private fun env() = TestEnv(operatorKey = "op-secret")

    private fun ApplicationTestBuilder.setup(env: TestEnv) {
        application { module(env.app) }
    }

    private suspend fun ApplicationTestBuilder.publishQuote(
        env: TestEnv,
        maxAmount: Long = 1_000_000_000L,
    ): String {
        // Real clock, not T0: these quotes are read back through the HTTP
        // routes, which evaluate `active()` against wall-clock now — a quote
        // published at T0 is already TTL-expired by the time the suite runs
        // in the afternoon.
        env.quotes.publish(50, 60, maxAmount, java.time.Instant.now())
        return env.store.currentQuote()!!.id
    }

    private fun dealJson(env: TestEnv, quoteId: String) = """
        {"quoteId":"$quoteId","amount":${Fx.AMOUNT},"receiveAddress":"${Hex.encode(Fx.recipientRaw)}","buyerPubKey":"${Hex.encode(Fx.buyer.pubKeyCompressed)}"}
    """.trimIndent()

    // ---------------------------------------------------------------- quotes
    @Test
    fun `quote feed snapshot and operator publish`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        assertEquals("null", json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject["quote"].toString())
        val id = publishQuote(env)
        val body = json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject
        assertEquals(id, body["quote"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("50", body["quote"]!!.jsonObject["spreadBps"]!!.jsonPrimitive.content)
    }

    @Test
    fun `quote publishing requires the operator key and enforces capacity`() = testApplication {
        val env = TestEnv(operatorKey = "op-secret", mixReady = 100_000_000L)
        setup(env)
        val client = createClient { }
        val put = client.put("/v1/quotes/current") {
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"maxAmount":500000000}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, put.status) // no key
        val forbidden = client.put("/v1/quotes/current") {
            header(HttpHeaders.Authorization, "Bearer wrong")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"maxAmount":50000000}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, forbidden.status)
        val overCapacity = client.put("/v1/quotes/current") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"maxAmount":500000000}""")
        }
        assertEquals(HttpStatusCode.Conflict, overCapacity.status)
        val ok = client.put("/v1/quotes/current") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"maxAmount":50000000}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `paused infra withdraws the quote feed`() = testApplication {
        val env = env()
        setup(env)
        publishQuote(env)
        val client = createClient { }
        env.infra.report(p2pgate.backend.infra.InfraSignal.ORACLE_LAG, false, "oracle down")
        val body = json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject
        assertEquals("null", body["quote"].toString())
    }

    // ----------------------------------------------------------------- deals
    @Test
    fun `deal creation mints a deal token and the status endpoint enforces it`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val created = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealJson(env, id))
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val dealId = json.parseToJsonElement(created.bodyAsText()).jsonObject["dealId"]!!.jsonPrimitive.content
        val token = json.parseToJsonElement(created.bodyAsText()).jsonObject["dealToken"]!!.jsonPrimitive.content

        val noAuth = client.get("/v1/deals/$dealId")
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
        val badAuth = client.get("/v1/deals/$dealId") { header(HttpHeaders.Authorization, "Bearer deadbeef") }
        assertEquals(HttpStatusCode.Forbidden, badAuth.status)
        val ok = client.get("/v1/deals/$dealId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, ok.status)
        val state = json.parseToJsonElement(ok.bodyAsText()).jsonObject["state"]!!.jsonPrimitive.content
        assertEquals("QUOTED", state) // canonical names verbatim
    }

    @Test
    fun `deal creation fails without an active quote`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val r = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealJson(env, "quote-missing"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
    }

    @Test
    fun `deal token dies at close`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val created = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealJson(env, id))
        }
        val obj = json.parseToJsonElement(created.bodyAsText()).jsonObject
        val dealId = obj["dealId"]!!.jsonPrimitive.content
        val token = obj["dealToken"]!!.jsonPrimitive.content
        val deal = env.store.getDeal(dealId)!!
        env.forceFund(deal)
        env.engine.apply(dealId, p2pgate.dealprotocol.DealEvent.ReclaimTimeoutElapsed(T0.plusSeconds(25 * 3600)), T0.plusSeconds(25 * 3600))
        val after = client.get("/v1/deals/$dealId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, after.status)
    }

    @Test
    fun `attestation proxy tracks the oracle`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val created = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealJson(env, id))
        }
        val obj = json.parseToJsonElement(created.bodyAsText()).jsonObject
        val dealId = obj["dealId"]!!.jsonPrimitive.content
        val token = obj["dealToken"]!!.jsonPrimitive.content
        val before = client.get("/v1/deals/$dealId/attestation") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals("UNCONFIRMED", json.parseToJsonElement(before.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)
        env.attestPayment(env.store.getDeal(dealId)!!)
        val after = client.get("/v1/deals/$dealId/attestation") { header(HttpHeaders.Authorization, "Bearer $token") }
        val body = json.parseToJsonElement(after.bodyAsText()).jsonObject
        assertEquals("CONFIRMED", body["status"]!!.jsonPrimitive.content)
        assertTrue(body["digest"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `handoff upload by the buyer records the meeting and is idempotent`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed)),
            T0,
        ) as CreateDealOutcome.Created
        val dealId = outcome.deal.dealId
        val dealToken = outcome.dealToken
        env.forceFund(outcome.deal)

        // No auth: rejected.
        val noAuth = client.post("/v1/deals/$dealId/handoff") {
            contentType(ContentType.Application.Json)
            setBody("""{"recordHex":"00"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)

        // The buyer uploads the seller-signed record from the meeting.
        val record = env.handoffRecordFor(outcome.deal, java.time.Instant.now())
        val submit = client.post("/v1/deals/$dealId/handoff") {
            header(HttpHeaders.Authorization, "Bearer $dealToken")
            contentType(ContentType.Application.Json)
            setBody("""{"recordHex":"${Hex.encode(record.encode())}","gps":"30.04,31.24"}""")
        }
        assertEquals(HttpStatusCode.OK, submit.status)
        assertEquals(DealStateName.PAYMENT_PENDING, env.store.getDeal(dealId)!!.state.name)
        assertTrue(env.store.getDeal(dealId)!!.handoffGpsRef != null)

        // A duplicate upload is recognized.
        val again = client.post("/v1/deals/$dealId/handoff") {
            header(HttpHeaders.Authorization, "Bearer $dealToken")
            contentType(ContentType.Application.Json)
            setBody("""{"recordHex":"${Hex.encode(record.encode())}"}""")
        }
        assertEquals(HttpStatusCode.OK, again.status)

        // A record that does not decode is rejected.
        val bad = client.post("/v1/deals/$dealId/handoff") {
            header(HttpHeaders.Authorization, "Bearer $dealToken")
            contentType(ContentType.Application.Json)
            setBody("""{"recordHex":"deadbeef"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
    }

    @Test
    fun `claim guide hands the buyer everything for the on-chain claim tx`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed)),
            T0,
        ) as CreateDealOutcome.Created
        val dealId = outcome.deal.dealId
        val dealToken = outcome.dealToken
        env.forceFund(outcome.deal)
        env.collectCash(dealId)

        val r = client.post("/v1/deals/$dealId/claim") { header(HttpHeaders.Authorization, "Bearer $dealToken") }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(dealId, body["dealId"]!!.jsonPrimitive.content)
        assertTrue(body["handoffRecordHex"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(body["fundedBox"]!!.jsonObject["ergoTreeHex"]!!.jsonPrimitive.content.isNotEmpty())
        assertTrue(body["instructions"].toString().contains("ClaimTxBuilder"))
    }

    // ------------------------------------------------------------- dashboard
    @Test
    fun `lane and pool views`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed)),
            T0,
        ) as CreateDealOutcome.Created
        env.forceFund(outcome.deal)
        val lane = client.get("/v1/lane") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        val lanes = json.parseToJsonElement(lane.bodyAsText()).jsonObject["lanes"]!!.jsonObject
        assertTrue(lanes.containsKey("FUNDED"))
        assertTrue(lanes["FUNDED"].toString().contains(outcome.deal.dealId))
        assertTrue(lanes["FUNDED"].toString().contains("timeout reclaim"))
        val pool = client.get("/v1/pool") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        val poolObj = json.parseToJsonElement(pool.bodyAsText()).jsonObject
        assertEquals("${Fx.AMOUNT}", poolObj["locked"]!!.jsonPrimitive.content)
    }

    @Test
    fun `manual reclaim is state-aware`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed)),
            T0,
        ) as CreateDealOutcome.Created
        env.forceFund(outcome.deal)
        val tooEarly = client.post("/v1/vaults/${outcome.deal.dealId}/reclaim") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        assertEquals(HttpStatusCode.Conflict, tooEarly.status)
    }

    @Test
    fun `aml check endpoint is decision-only`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val r = client.post("/v1/aml/check") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"address":"${Hex.encode(Fx.recipientRaw)}","chainId":1}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("ACCEPT", body["decision"]!!.jsonPrimitive.content)
        assertEquals("test-scorer", body["scorerId"]!!.jsonPrimitive.content)
        assertTrue(!body.containsKey("report"))
    }

    @Test
    fun `dispute endpoints accept and investigate`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed)),
            T0,
        ) as CreateDealOutcome.Created
        val dealId = outcome.deal.dealId
        env.forceFund(outcome.deal)
        env.collectCash(dealId)
        env.engine.apply(dealId, p2pgate.dealprotocol.DealEvent.ClaimOpened(T0.plusSeconds(600)), T0.plusSeconds(600))

        val disputes = client.get("/v1/disputes") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        assertTrue(disputes.bodyAsText().contains(dealId))

        val accept = client.post("/v1/disputes/$dealId/accept") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        assertEquals(HttpStatusCode.OK, accept.status)
        assertTrue(env.store.getDeal(dealId)!!.lossRecorded)

        val investigate = client.post("/v1/disputes/$dealId/investigate") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        assertEquals(HttpStatusCode.OK, investigate.status)
        assertEquals("investigate", env.store.getDeal(dealId)!!.claimAction)

        val infra = client.get("/v1/infra") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        assertEquals("false", json.parseToJsonElement(infra.bodyAsText()).jsonObject["paused"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dashboard requires the operator key`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/lane").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/pool").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/disputes").status)
    }

    // ------------------------------------------------------------ websockets
    @Test
    fun `events stream pushes live events to the dashboard`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { install(WebSockets) }
        client.webSocket("/v1/events?token=op-secret") {
            env.quotes.publish(50, 60, 100_000_000L)
            val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
            assertTrue(frame.readText().contains("quote.published"))
        }
    }

    @Test
    fun `deal stream pushes state changes to the buyer`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { install(WebSockets) }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed)),
            T0,
        ) as CreateDealOutcome.Created
        client.webSocket("/v1/deals/${outcome.deal.dealId}/stream?token=${outcome.dealToken}") {
            env.forceFund(outcome.deal)
            val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
            assertTrue(frame.readText().contains("deal.transitioned"))
        }
    }
}

private object DealStateName {
    const val PAYMENT_PENDING = "PAYMENT_PENDING"
}
