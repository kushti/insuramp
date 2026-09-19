package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.api.CreateDealOutcome
import p2pgate.backend.api.CreateDealRequest
import p2pgate.backend.bus.TxKind
import p2pgate.backend.vault.VaultManager
import p2pgate.dealprotocol.DealState
import p2pgate.ergo.ChainSpend
import java.time.Duration

/**
 * End-to-end lifecycles at the component level (no HTTP): the deal runs the
 * full machine with the real tx builders, the fake chain and the dev oracle.
 */
class LifecycleSpec {

    private fun env() = TestEnv(vaultSigner = Fx.RealSigner())

    /** quote → deal → fund → handoff → oracle confirmation → automatic release */
    @Test
    fun `full happy path releases automatically on oracle confirmation`() {
        val env = env()
        env.quotes.publish(50, 60, Fx.AMOUNT, T0)
        val created = env.app.createDeal(dealRequest(), T0)
        val deal = (created as CreateDealOutcome.Created).deal

        // fund: the vault manager builds the real fund tx and anchors FUNDED
        val fundOutcome = env.vaultManager.fundDeal(deal.dealId, T0)
        assertInstanceOf(VaultManager.Outcome.Submitted::class.java, fundOutcome)
        val funded = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.FUNDED, funded.state)
        env.chain.boxes[funded.vaultBoxId!!] = Fx.fundedBox(funded, funded.vaultBoxId!!)

        // handoff record on file (buyer upload)
        env.collectCash(deal.dealId)
        assertEquals(DealState.PAYMENT_PENDING, env.store.getDeal(deal.dealId)!!.state)

        // oracle confirms: poll drives PaymentConfirmed + the automatic release
        env.attestPayment(env.store.getDeal(deal.dealId)!!)
        env.app.pollOracle(T0.plusSeconds(600))
        val final = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.RELEASED, final.state)
        assertTrue(final.contested.not())
        assertEquals(
            listOf(TxKind.FUND, TxKind.RELEASE),
            env.events.filterIsInstance<p2pgate.backend.bus.BackendEvent.TxSubmitted>().map { it.kind },
        )
    }

    /** fund → handoff → claim → oracle confirmation → contest → RELEASED with contested flag */
    @Test
    fun `dispute lifecycle contests with the oracle digest and records the contested flag`() {
        val env = env()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.collectCash(deal.dealId)

        // The buyer opens the claim on-chain: the watcher sees path B land.
        val fundedId = env.store.getDeal(deal.dealId)!!.vaultBoxId!!
        val proven = Fx.provenBox(deal, boxId = "bb".repeat(32))
        env.chain.boxes[proven.boxId] = proven
        env.chain.spend(fundedId, "b1".repeat(32), ChainSpend("b1".repeat(32), 1500, listOf(proven)))
        env.watcher.tick(T0.plusSeconds(3600))
        assertEquals(DealState.CLAIM_OPENED, env.store.getDeal(deal.dealId)!!.state)

        // Oracle confirmation lands during the claim: contested, never rejected.
        env.attestPayment(env.store.getDeal(deal.dealId)!!)
        env.app.pollOracle(T0.plusSeconds(3700))
        val contested = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.CLAIM_OPENED, contested.state)
        assertTrue(contested.contested)

        // The dispute inbox contests mechanically; path C′ wins the claim.
        assertEquals(p2pgate.backend.disputes.DisputeInbox.ActionOutcome.Ok, env.inbox.contest(deal.dealId, T0.plusSeconds(3800)))
        val final = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.RELEASED, final.state)
        assertTrue(final.contested)
        assertEquals("contest", final.claimAction)
    }

    /** fund → handoff → claim → maturation → buyer path-D payout → CLAIMED */
    @Test
    fun `an unactioned claim matures and pays out to the buyer`() {
        val env = env()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.collectCash(deal.dealId)

        // Claim opens on-chain at +10 min.
        val fundedId = env.store.getDeal(deal.dealId)!!.vaultBoxId!!
        val proven = Fx.provenBox(deal, boxId = "bb".repeat(32))
        env.chain.spend(fundedId, "b1".repeat(32), ChainSpend("b1".repeat(32), 1500, listOf(proven)))
        val claimAt = T0.plusSeconds(600)
        env.chain.boxes[proven.boxId] = proven
        env.watcher.tick(claimAt)
        assertEquals(DealState.CLAIM_OPENED, env.store.getDeal(deal.dealId)!!.state)

        // 12h maturation: CLAIMABLE, no operator action (an unforced loss path).
        env.watcher.tick(claimAt.plus(Duration.ofHours(12)))
        assertEquals(DealState.CLAIMABLE, env.store.getDeal(deal.dealId)!!.state)

        // The buyer's path-D spend lands: the full collateral pays the buyer.
        val provenId = env.store.getDeal(deal.dealId)!!.provenBoxId!!
        val payout = Fx.payoutBox(Fx.buyer.pubKeyCompressed, Fx.useTokenIdHex, deal.amount, "c1".repeat(32))
        env.chain.spend(provenId, "b2".repeat(32), ChainSpend("b2".repeat(32), 1600, listOf(payout)))
        env.watcher.tick(claimAt.plus(Duration.ofHours(12)).plusSeconds(60))
        val final = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.CLAIMED, final.state)
        assertTrue(env.submitter.submitted.isEmpty()) // every tx here was buyer-side
    }

    @Test
    fun `an expired quote abandons the deal with no on-chain footprint`() {
        val env = env()
        val deal = env.quotedDeal()
        val r = env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.QuoteExpired(T0.plusSeconds(3600)), T0.plusSeconds(3600))
        assertEquals(p2pgate.backend.engine.DealEngine.Result.Aborted, r)
        assertTrue(env.store.openDeals().isEmpty())
        assertTrue(env.app.lane().values.all { it.isEmpty() })
    }

    @Test
    fun `reclaim timeout reclaims a no-show deal automatically`() {
        val env = env()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.chain.height = 1000 + p2pgate.contracts.ContractParams.RECLAIM_TIMEOUT_BLOCKS + 1
        env.app.tick(T0.plus(Duration.ofHours(25)))
        val final = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.RECLAIMED, final.state)
        assertEquals(1, env.submitter.submitted.size)
    }

    private fun dealRequest() = CreateDealRequest(
        quoteId = "quote-1",
        amount = Fx.AMOUNT,
        receiveAddress = p2pgate.backend.util.Hex.encode(Fx.recipientRaw),
        buyerPubKey = p2pgate.backend.util.Hex.encode(Fx.buyer.pubKeyCompressed),
    )
}
