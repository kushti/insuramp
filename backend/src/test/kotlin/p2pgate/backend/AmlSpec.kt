package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.aml.AmlDecision
import p2pgate.backend.aml.ConfigRiskScorer
import p2pgate.backend.aml.HttpRiskScorer
import p2pgate.backend.aml.RiskScorer
import p2pgate.backend.aml.RiskScorerException
import p2pgate.backend.api.CreateDealRequest
import p2pgate.backend.api.CreateDealOutcome
import p2pgate.backend.util.Hex

class AmlSpec {

    private val address = ByteArray(21) { it.toByte() }

    @Test
    fun `config stub accepts by default and honors exact addresses`() {
        val scorer = ConfigRiskScorer(
            decisions = mapOf(Hex.encode(address).lowercase() to AmlDecision.REJECT),
        )
        assertEquals(AmlDecision.REJECT, scorer.score(address, 1))
        assertEquals(AmlDecision.ACCEPT, scorer.score(ByteArray(21) { 7 }, 1))
    }

    @Test
    fun `http adapter parses accept and reject`() {
        val accept = HttpRiskScorer("https://provider.example", { _ -> """{"decision":"ACCEPT","score":7}"""  })
        val reject = HttpRiskScorer("https://provider.example", { _ -> """{"decision":"REJECT"}"""  })
        assertEquals(AmlDecision.ACCEPT, accept.score(address, 1))
        assertEquals(AmlDecision.REJECT, reject.score(address, 1))
    }

    @Test
    fun `http adapter is fail-closed on transport failure`() {
        val scorer = HttpRiskScorer("https://provider.example", { _ -> throw java.io.IOException("connection refused")  })
        assertThrows(RiskScorerException::class.java) { scorer.score(address, 1) }
    }

    @Test
    fun `http adapter rejects unreadable and unknown responses`() {
        val noField = HttpRiskScorer("https://provider.example", { _ -> """{"score":3}"""  })
        val weird = HttpRiskScorer("https://provider.example", { _ -> """{"decision":"MAYBE"}"""  })
        assertThrows(RiskScorerException::class.java) { noField.score(address, 1) }
        assertThrows(RiskScorerException::class.java) { weird.score(address, 1) }
    }

    @Test
    fun `a rejecting scorer means no deal is created`() {
        val env = TestEnv(
            riskScorer = ConfigRiskScorer(
                decisions = mapOf(Hex.encode(Fx.recipientRaw).lowercase() to AmlDecision.REJECT),
            ),
        )
        env.quotes.publish(50, 60, Fx.AMOUNT, T0)
        val outcome = env.app.createDeal(dealRequest(), T0)
        val rejected = assertInstanceOf(CreateDealOutcome.Rejected::class.java, outcome)
        assertTrue(rejected.reason.contains("REJECT"))
        assertTrue(env.store.allDeals().isEmpty())
    }

    @Test
    fun `an unreachable scorer means no deal is created`() {
        val env = TestEnv(riskScorer = object : RiskScorer {
            override val scorerId: String = "down"
            override fun score(address: ByteArray, chainId: Int): AmlDecision =
                throw RiskScorerException("unreachable")
        })
        env.quotes.publish(50, 60, Fx.AMOUNT, T0)
        val outcome = env.app.createDeal(dealRequest(), T0)
        assertTrue((outcome as CreateDealOutcome.Rejected).reason.contains("fail-closed"))
        assertTrue(env.store.allDeals().isEmpty())
    }

    @Test
    fun `an accepting scorer creates the deal with a decision-only record`() {
        val env = TestEnv()
        env.quotes.publish(50, 60, Fx.AMOUNT, T0)
        val outcome = env.app.createDeal(dealRequest(), T0)
        val created = assertInstanceOf(CreateDealOutcome.Created::class.java, outcome)
        val stored = env.store.getDeal(created.deal.dealId)!!
        assertEquals(1, stored.amlRecords.size)
        assertEquals(AmlDecision.ACCEPT, stored.amlRecords.single().decision)
        assertEquals("test-scorer", stored.amlRecords.single().scorerId)
        assertEquals(Hex.encode(Fx.recipientRaw), stored.amlRecords.single().checkedAddressHex)
    }

    private fun dealRequest() = CreateDealRequest(
        quoteId = envQuoteId(),
        amount = Fx.AMOUNT,
        receiveAddress = Hex.encode(Fx.recipientRaw),
        userPubKey = Hex.encode(Fx.user.pubKeyCompressed),
    )

    private fun envQuoteId() = "quote-1"
}
