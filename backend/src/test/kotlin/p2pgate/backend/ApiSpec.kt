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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.api.CreateDealOutcome
import p2pgate.backend.api.module
import p2pgate.backend.util.Hex
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.dealprotocol.QrPayload
import p2pgate.ergo.HandoffRecordVerifier
import p2pgate.ergo.SchnorrVerifier
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO

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
        minAmount: Long = 1L,
    ): String {
        // Real clock, not T0: these quotes are read back through the HTTP
        // routes, which evaluate `active()` against wall-clock now — a quote
        // published at T0 is already TTL-expired by the time the suite runs
        // in the afternoon.
        val outcome = env.quotes.publish(50, 60, minAmount, maxAmount, "USD", java.time.Instant.now())
        return (outcome as p2pgate.backend.quotes.QuotePublisher.PublishOutcome.Published).quote.id
    }

    private fun dealJson(env: TestEnv, quoteId: String) = """
        {"quoteId":"$quoteId","amount":${Fx.AMOUNT},"receiveAddress":"${Hex.encode(Fx.recipientRaw)}","buyerPubKey":"${Hex.encode(Fx.buyer.pubKeyCompressed)}","fiatCurrency":"USD","fiatAmount":1000}
    """.trimIndent()

    // ---------------------------------------------------------------- quotes
    @Test
    fun `quote feed snapshot and operator publish`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        assertEquals("[]", json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject["quotes"].toString())
        val id = publishQuote(env)
        val body = json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject
        val quotes = body["quotes"]!!.jsonArray
        assertEquals(1, quotes.size)
        assertEquals(id, quotes[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("50", quotes[0].jsonObject["spreadBps"]!!.jsonPrimitive.content)
    }

    @Test
    fun `quote publishing requires the operator key and enforces capacity`() = testApplication {
        val env = TestEnv(operatorKey = "op-secret", mixReady = 100_000_000L)
        setup(env)
        val client = createClient { }
        val put = client.put("/v1/dashboard/quotes") {
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"fiatCurrency":"USD","minAmount":1000000,"maxAmount":500000000}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, put.status) // no key
        val forbidden = client.put("/v1/dashboard/quotes") {
            header(HttpHeaders.Authorization, "Bearer wrong")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"fiatCurrency":"USD","minAmount":1000000,"maxAmount":50000000}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, forbidden.status)
        val overCapacity = client.put("/v1/dashboard/quotes") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"fiatCurrency":"USD","minAmount":1000000,"maxAmount":500000000}""")
        }
        assertEquals(HttpStatusCode.Conflict, overCapacity.status)
        val ok = client.put("/v1/dashboard/quotes") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"fiatCurrency":"USD","minAmount":1000000,"maxAmount":50000000}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        // A legacy payload without the required minAmount is a 400, not a 500.
        val legacy = client.put("/v1/dashboard/quotes") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"maxAmount":50000000}""")
        }
        assertEquals(HttpStatusCode.BadRequest, legacy.status)
    }

    @Test
    fun `multiple published quotes round-trip through the feed`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        for (i in 1..3) {
            val put = client.put("/v1/dashboard/quotes") {
                header(HttpHeaders.Authorization, "Bearer op-secret")
                contentType(ContentType.Application.Json)
                setBody("""{"spreadBps":${50 + i},"etaMinutes":${30 * i},"fiatCurrency":"USD","minAmount":1000000,"maxAmount":100000000,"lat":30.044,"lon":31.235}""")
            }
            assertEquals(HttpStatusCode.OK, put.status)
        }
        // The public buyer feed lists all three.
        val feed = json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject["quotes"]!!
            .jsonArray
        assertEquals(3, feed.size)
        assertEquals(listOf("51", "52", "53"), feed.map { it.jsonObject["spreadBps"]!!.jsonPrimitive.content })
        assertEquals("30.044", feed[0].jsonObject["lat"]!!.jsonPrimitive.content)
        // The dashboard view lists the same quotes (operator-authed).
        val dashboard = client.get("/v1/dashboard/quotes") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        assertEquals(HttpStatusCode.OK, dashboard.status)
        assertEquals(3, json.parseToJsonElement(dashboard.bodyAsText()).jsonObject["quotes"]!!
            .jsonArray.size)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/dashboard/quotes").status)
    }

    @Test
    fun `quote withdraw endpoint removes one quote and keeps the rest`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val first = publishQuote(env, maxAmount = 100_000_000L)
        val second = publishQuote(env, maxAmount = 200_000_000L)

        // Auth: the operator key is required.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/dashboard/quotes/$first/withdraw").status)

        // Unknown id: 404.
        val missing = client.post("/v1/dashboard/quotes/quote-99/withdraw") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)

        // Withdrawing the first leaves the second serving.
        val ok = client.post("/v1/dashboard/quotes/$first/withdraw") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val feed = json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject["quotes"]!!
            .jsonArray
        assertEquals(listOf(second), feed.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun `quote publish with location round-trips lat lon through the feed`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val put = client.put("/v1/dashboard/quotes") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"fiatCurrency":"USD","minAmount":1000000,"maxAmount":500000000,"lat":55.75,"lon":37.61}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)
        val body = json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject
        val quotes = body["quotes"]!!.jsonArray
        assertEquals(1, quotes.size)
        assertEquals("55.75", quotes[0].jsonObject["lat"]!!.jsonPrimitive.content)
        assertEquals("37.61", quotes[0].jsonObject["lon"]!!.jsonPrimitive.content)
        assertEquals("1000000", quotes[0].jsonObject["minAmount"]!!.jsonPrimitive.content)
        assertEquals("USD", quotes[0].jsonObject["fiatCurrency"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deal with a mismatched or malformed fiat currency is rejected`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env) // a USD quote
        fun dealWith(currency: String) = """
            {"quoteId":"$id","amount":${Fx.AMOUNT},"receiveAddress":"${Hex.encode(Fx.recipientRaw)}","buyerPubKey":"${Hex.encode(Fx.buyer.pubKeyCompressed)}","fiatCurrency":"$currency","fiatAmount":1000}
        """.trimIndent()
        val mismatch = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealWith("INR"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, mismatch.status)
        assertTrue(mismatch.bodyAsText().contains("does not match"))
        val malformed = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealWith("US"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, malformed.status)
        assertTrue(malformed.bodyAsText().contains("3 letters"))
        assertTrue(env.store.allDeals().isEmpty())
        // Lowercase normalizes to the quote's code and the deal is created.
        val ok = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealWith("usd"))
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val dealId = json.parseToJsonElement(ok.bodyAsText()).jsonObject["dealId"]!!.jsonPrimitive.content
        assertEquals("USD", env.store.getDeal(dealId)!!.fiatCurrency)
    }

    @Test
    fun `deal below the quote minimum is rejected and the minimum itself is accepted`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env, maxAmount = 100_000_000L, minAmount = 10_000_000L)
        fun dealAt(amount: Long) = """
            {"quoteId":"$id","amount":$amount,"receiveAddress":"${Hex.encode(Fx.recipientRaw)}","buyerPubKey":"${Hex.encode(Fx.buyer.pubKeyCompressed)}","fiatCurrency":"USD","fiatAmount":1000}
        """.trimIndent()
        val tooSmall = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealAt(5_000_000L))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, tooSmall.status)
        assertTrue(tooSmall.bodyAsText().contains("min 10000000"))
        assertTrue(env.store.allDeals().isEmpty())
        val atMin = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealAt(10_000_000L))
        }
        assertEquals(HttpStatusCode.OK, atMin.status)
    }

    @Test
    fun `quote publish with a half location pair is a conflict`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val latOnly = client.put("/v1/dashboard/quotes") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
            contentType(ContentType.Application.Json)
            setBody("""{"spreadBps":50,"etaMinutes":60,"fiatCurrency":"USD","minAmount":1000000,"maxAmount":500000000,"lat":55.75}""")
        }
        assertEquals(HttpStatusCode.Conflict, latOnly.status)
        assertTrue(latOnly.bodyAsText().contains("lat/lon pair"))
        assertTrue(env.store.quotes().isEmpty())
    }

    @Test
    fun `paused infra withdraws the quote feed`() = testApplication {
        val env = env()
        setup(env)
        publishQuote(env)
        val client = createClient { }
        env.infra.report(p2pgate.backend.infra.InfraSignal.ORACLE_LAG, false, "oracle down")
        val body = json.parseToJsonElement(client.get("/v1/quotes").bodyAsText()).jsonObject
        assertEquals("[]", body["quotes"].toString())
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
        // The buyer app verifies the handoff record against this key (vault R5).
        val sellerPubKey = json.parseToJsonElement(ok.bodyAsText()).jsonObject["sellerPubKey"]!!.jsonPrimitive.content
        assertEquals(Hex.encode(Fx.seller.pubKeyCompressed), sellerPubKey)
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
    fun `offer accept funds the vault and decline abandons without funding`() = testApplication {
        val env = TestEnv(operatorKey = "op-secret", vaultSigner = Fx.RealSigner())
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)

        // The deal is an offer: QUOTED, no vault, nothing submitted.
        val created = client.post("/v1/deals") {
            contentType(ContentType.Application.Json)
            setBody(dealJson(env, id))
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val dealId = json.parseToJsonElement(created.bodyAsText()).jsonObject["dealId"]!!.jsonPrimitive.content
        assertEquals("QUOTED", env.store.getDeal(dealId)!!.state.name)
        assertNull(env.store.getDeal(dealId)!!.vaultBoxId)
        assertTrue(env.submitter.submitted.isEmpty())

        // Both endpoints are operator-authed.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/dashboard/deals/$dealId/accept").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/dashboard/deals/$dealId/decline").status)
        // Unknown deals 404.
        for (action in listOf("accept", "decline")) {
            val missing = client.post("/v1/dashboard/deals/deadbeef/$action") {
                header(HttpHeaders.Authorization, "Bearer op-secret")
            }
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }

        // The lane card carries the offer's currency and TTL countdown.
        val lane = client.get("/v1/lane") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        val card = json.parseToJsonElement(lane.bodyAsText()).jsonObject["lanes"]!!
            .jsonObject["QUOTED"]!!.jsonArray.single().jsonObject
        assertEquals("USD", card["fiatCurrency"]!!.jsonPrimitive.content)
        assertTrue(card["offerExpiresAtEpochMs"]!!.jsonPrimitive.content.toLong() > 0)

        // Accept funds the vault and drives FUNDED.
        val accept = client.post("/v1/dashboard/deals/$dealId/accept") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.OK, accept.status)
        val body = json.parseToJsonElement(accept.bodyAsText()).jsonObject
        assertEquals("FUNDED", body["state"]!!.jsonPrimitive.content)
        assertTrue(body["fundTxId"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals("FUNDED", env.store.getDeal(dealId)!!.state.name)
        assertTrue(env.store.getDeal(dealId)!!.vaultBoxId != null)
        assertEquals(1, env.submitter.submitted.size) // exactly one FUND tx

        // Accept twice → 409 with the state; decline after accept → 409.
        val again = client.post("/v1/dashboard/deals/$dealId/accept") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertTrue(again.bodyAsText().contains("FUNDED"))
        val tooLate = client.post("/v1/dashboard/deals/$dealId/decline") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.Conflict, tooLate.status)
        assertTrue(tooLate.bodyAsText().contains("already funded"))
    }

    @Test
    fun `offer decline closes without funding kills the deal token and is not repeatable`() = testApplication {
        val env = TestEnv(operatorKey = "op-secret", vaultSigner = Fx.RealSigner())
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

        val decline = client.post("/v1/dashboard/deals/$dealId/decline") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.OK, decline.status)
        assertTrue(env.store.getDeal(dealId)!!.abandoned)
        assertTrue(env.store.openDeals().isEmpty())
        assertTrue(env.submitter.submitted.isEmpty()) // never funded
        assertTrue(env.store.events(dealId).any { it.kind == "OFFER_DECLINED" })
        // The buyer's token dies with the offer.
        val buyerView = client.get("/v1/deals/$dealId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.Forbidden, buyerView.status)
        // Decline is not repeatable; a closed offer cannot be accepted.
        val again = client.post("/v1/dashboard/deals/$dealId/decline") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.Conflict, again.status)
        val accept = client.post("/v1/dashboard/deals/$dealId/accept") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.Conflict, accept.status)
        assertTrue(accept.bodyAsText().contains("closed"))
        assertTrue(env.submitter.submitted.isEmpty())
    }

    @Test
    fun `deal stream pushes offer abandonment to the buyer`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { install(WebSockets) }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
            T0,
        ) as CreateDealOutcome.Created
        client.webSocket("/v1/deals/${outcome.deal.dealId}/stream?token=${outcome.dealToken}") {
            env.engine.apply(
                outcome.deal.dealId,
                p2pgate.dealprotocol.DealEvent.QuoteExpired(java.time.Instant.now()),
                java.time.Instant.now(),
            )
            val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
            assertTrue(frame.readText().contains("deal.abandoned"))
        }
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
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
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
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
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
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
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
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
            T0,
        ) as CreateDealOutcome.Created
        // Fund "now": the HTTP endpoint reclaims against Instant.now(), and a
        // fixed past timestamp would age past RECLAIM_TIMEOUT as wall time
        // advances (the endpoint must answer 409 from the wall-clock guard,
        // not fall through to the chain-height check).
        env.forceFund(outcome.deal, at = Instant.now())
        val tooEarly = client.post("/v1/vaults/${outcome.deal.dealId}/reclaim") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        assertEquals(HttpStatusCode.Conflict, tooEarly.status)
    }

    // -------------------------------------------- seller-meeting handoff (M4)
    @Test
    fun `dashboard handoff sign from funded signs stores and advances to payment pending`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
            T0,
        ) as CreateDealOutcome.Created
        val dealId = outcome.deal.dealId
        env.forceFund(outcome.deal) // FUNDED — the meeting can happen

        // Operator auth, same convention as the rest of the dashboard.
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/dashboard/deals/$dealId/handoff/sign").status)
        val wrongKey = client.post("/v1/dashboard/deals/$dealId/handoff/sign") {
            header(HttpHeaders.Authorization, "Bearer wrong")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongKey.status)

        // The sign IS the cash-collection witness: 200, record on file, and
        // the engine advances FUNDED → PAYMENT_PENDING.
        val r = client.post("/v1/dashboard/deals/$dealId/handoff/sign") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(dealId, body["dealId"]!!.jsonPrimitive.content)
        assertEquals("PAYMENT_PENDING", body["state"]!!.jsonPrimitive.content)
        assertEquals(DealStateName.PAYMENT_PENDING, env.store.getDeal(dealId)!!.state.name)
        assertEquals(Hex.encode(Fx.seller.pubKeyCompressed), body["sellerPubKey"]!!.jsonPrimitive.content)
        val recordBytes = Hex.decode(body["recordHex"]!!.jsonPrimitive.content)
        val a = Hex.decode(body["signatureA"]!!.jsonPrimitive.content)
        val z = Hex.decode(body["signatureZ"]!!.jsonPrimitive.content)
        assertEquals(HandoffRecord.ENCODED_SIZE, recordBytes.size)
        assertEquals(33, a.size)
        assertEquals(32, z.size)
        assertEquals(Hex.encode(recordBytes), env.store.getDeal(dealId)!!.handoffRecordHex)

        // The signature verifies against the deal's seller key under the
        // t/3407 variant (:core:ergo's verifier), and fails under any other key.
        assertTrue(SchnorrVerifier.verify(recordBytes, a, z, Fx.seller.pubKeyCompressed))
        assertTrue(!SchnorrVerifier.verify(recordBytes, a, z, Fx.buyer.pubKeyCompressed))
        // The buyer-facing deal DTO exposes exactly that key — the app pins it
        // from the terms and unblocks the "safe to leave" verification.
        val dealView = client.get("/v1/deals/$dealId") {
            header(HttpHeaders.Authorization, "Bearer ${outcome.dealToken}")
        }
        assertEquals(HttpStatusCode.OK, dealView.status)
        val dtoKey = json.parseToJsonElement(dealView.bodyAsText()).jsonObject["sellerPubKey"]!!.jsonPrimitive.content
        assertEquals(Hex.encode(Fx.seller.pubKeyCompressed), dtoKey)
        assertTrue(SchnorrVerifier.verify(recordBytes, a, z, Hex.decode(dtoKey)))
        // Full buyer-app gate: record matches the terms and the timestamp is fresh.
        assertTrue(
            HandoffRecordVerifier.verify(
                recordBytes, a, z, Fx.seller.pubKeyCompressed,
                env.store.getDeal(dealId)!!.terms(), java.time.Instant.now(),
            ),
        )

        // The QR payload is exactly what :core:dealprotocol's codec parses.
        val decoded = QrPayload.decodeHandoff(body["qrPayload"]!!.jsonPrimitive.content)
        assertTrue(decoded.encode().contentEquals(recordBytes))

        // A second sign is refused — the record exists, the front-end re-displays.
        val again = client.post("/v1/dashboard/deals/$dealId/handoff/sign") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertTrue(again.bodyAsText().contains("PAYMENT_PENDING"))
    }

    @Test
    fun `dashboard handoff sign refuses a claim-opened deal`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
            T0,
        ) as CreateDealOutcome.Created
        val dealId = outcome.deal.dealId
        env.forceFund(outcome.deal)
        client.post("/v1/dashboard/deals/$dealId/handoff/sign") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        env.engine.apply(dealId, p2pgate.dealprotocol.DealEvent.ClaimOpened(T0.plusSeconds(600)), T0.plusSeconds(600))

        val r = client.post("/v1/dashboard/deals/$dealId/handoff/sign") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertTrue(r.bodyAsText().contains("CLAIM_OPENED"))

        val unknown = client.post("/v1/dashboard/deals/deadbeef/handoff/sign") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
    }

    @Test
    fun `dashboard handoff qr png encodes the p2pgate payload and 409s while unsigned`() = testApplication {
        val env = env()
        setup(env)
        val client = createClient { }
        val id = publishQuote(env)
        val outcome = env.app.createDeal(
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
            T0,
        ) as CreateDealOutcome.Created
        val dealId = outcome.deal.dealId
        env.forceFund(outcome.deal)

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/dashboard/deals/$dealId/handoff/qr.png").status)

        // Unsigned deal: 409 (the deal exists; only the record does not).
        val unsigned = client.get("/v1/dashboard/deals/$dealId/handoff/qr.png") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.Conflict, unsigned.status)

        val unknown = client.get("/v1/dashboard/deals/deadbeef/handoff/qr.png") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)

        // Signing at the meeting puts the record on file; the QR follows it.
        client.post("/v1/dashboard/deals/$dealId/handoff/sign") { header(HttpHeaders.Authorization, "Bearer op-secret") }
        val png = client.get("/v1/dashboard/deals/$dealId/handoff/qr.png") {
            header(HttpHeaders.Authorization, "Bearer op-secret")
        }
        assertEquals(HttpStatusCode.OK, png.status)
        assertEquals(ContentType.Image.PNG, png.contentType()?.withoutParameters())
        // The PNG decodes (ZXing) to the exact p2pgate://handoff payload of the
        // record on file.
        val record = HandoffRecord.decode(Hex.decode(env.store.getDeal(dealId)!!.handoffRecordHex!!))
        assertEquals(QrPayload.encodeHandoff(record), decodeQr(png.body<ByteArray>()))
    }    @Test
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
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
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
            env.quotes.publish(50, 60, 1L, 100_000_000L, "USD")
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
            p2pgate.backend.api.CreateDealRequest(id, Fx.AMOUNT, Hex.encode(Fx.recipientRaw), Hex.encode(Fx.buyer.pubKeyCompressed), "USD", Fx.AMOUNT),
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

/** Decodes a PNG's QR content with ZXing (round-trip driver for qr.png tests). */
private fun decodeQr(png: ByteArray): String {
    val image = ImageIO.read(ByteArrayInputStream(png))
    val pixels = IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) }
    val source = com.google.zxing.RGBLuminanceSource(image.width, image.height, pixels)
    return com.google.zxing.qrcode.QRCodeReader()
        .decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
        .text
}
