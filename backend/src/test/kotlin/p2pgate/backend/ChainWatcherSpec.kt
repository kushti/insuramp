package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.engine.DealEngine
import p2pgate.dealprotocol.DealState
import p2pgate.ergo.ChainSpend
import java.time.Duration

class ChainWatcherSpec {

    @Test
    fun `an unspent funded box inside the timeout produces no events`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        val results = env.watcher.tick(T0.plusSeconds(3600))
        assertTrue(results.isEmpty())
        assertEquals(DealState.FUNDED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `claim maturation is emitted after claim maturation elapses`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.collectCash(deal.dealId)
        val claimAt = T0.plusSeconds(600)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.ClaimOpened(claimAt), claimAt)
        assertTrue(env.watcher.tick(claimAt.plus(Duration.ofHours(11))).isEmpty())
        env.watcher.tick(claimAt.plus(Duration.ofHours(12)))
        assertEquals(DealState.CLAIMABLE, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `funded box spent into a payment proven box opens the claim and repoints the watch`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.collectCash(deal.dealId)
        val fundedId = env.store.getDeal(deal.dealId)!!.vaultBoxId!!
        val proven = Fx.provenBox(deal, boxId = "bb".repeat(32))
        env.chain.spend(
            fundedId,
            "b1".repeat(32),
            ChainSpend("b1".repeat(32), 1500, listOf(proven)),
        )
        env.watcher.tick(T0.plusSeconds(3600))
        val stored = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.CLAIM_OPENED, stored.state)
        assertEquals(proven.boxId, stored.provenBoxId)
        // Re-observing the same fact must not re-dispatch (no violation spam).
        env.watcher.tick(T0.plusSeconds(3700))
        assertTrue(env.engine.violations.entries().isEmpty())
    }

    @Test
    fun `proven box spent paying the user classifies as claim paid`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.collectCash(deal.dealId)
        val claimAt = T0.plusSeconds(600)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.ClaimOpened(claimAt), claimAt)
        val provenId = "bb".repeat(32)
        env.store.updateDeal(deal.dealId) { it.copy(provenBoxId = provenId) }
        env.chain.boxes[provenId] = Fx.provenBox(deal, boxId = provenId)
        val payout = Fx.payoutBox(Fx.user.pubKeyCompressed, Fx.useTokenIdHex, deal.amount, "c1".repeat(32))
        env.chain.spend(provenId, "b2".repeat(32), ChainSpend("b2".repeat(32), 1600, listOf(payout)))
        env.watcher.tick(claimAt.plus(Duration.ofHours(13)))
        assertEquals(DealState.CLAIMED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `seller paid past timeout classifies as reclaim when the oracle nft is absent`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        val fundedId = env.store.getDeal(deal.dealId)!!.vaultBoxId!!
        val sellerPayout = Fx.payoutBox(Fx.seller.pubKeyCompressed, Fx.useTokenIdHex, deal.amount, "c2".repeat(32))
        val timeoutHeight = 1000 + p2pgate.contracts.ContractParams.RECLAIM_TIMEOUT_BLOCKS
        env.chain.spend(fundedId, "b3".repeat(32), ChainSpend("b3".repeat(32), timeoutHeight + 1, listOf(sellerPayout)))
        env.watcher.tick(T0.plus(Duration.ofHours(25)))
        assertEquals(DealState.RECLAIMED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `seller paid with the oracle nft among inputs classifies as release`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.collectCash(deal.dealId)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.PaymentConfirmed, T0.plusSeconds(300))
        val fundedId = env.store.getDeal(deal.dealId)!!.vaultBoxId!!
        val sellerPayout = Fx.payoutBox(Fx.seller.pubKeyCompressed, Fx.useTokenIdHex, deal.amount, "c3".repeat(32))
        env.chain.spend(
            fundedId, "b4".repeat(32),
            ChainSpend(
                "b4".repeat(32), 1400, listOf(sellerPayout),
                inputTokenIds = listOf(p2pgate.backend.util.Hex.encode(Fx.trees.oracleNftId)),
            ),
        )
        env.watcher.tick(T0.plusSeconds(3600))
        assertEquals(DealState.RELEASED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `unknown box stretches are silent`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        env.forceFund(deal, boxId = "ee".repeat(32))
        env.chain.boxes.clear()
        assertTrue(env.watcher.tick(T0.plusSeconds(3600)).isEmpty())
    }

    @Test
    fun `terminal deals are not polled again`() {
        val env = TestEnv()
        val deal = env.quotedDeal()
        env.forceFund(deal)
        env.collectCash(deal.dealId)
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.PaymentConfirmed, T0.plusSeconds(5))
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.ReleaseObserved, T0.plusSeconds(10))
        assertTrue(env.watcher.tick(T0.plusSeconds(20)).isEmpty())
        assertEquals(0, env.engine.violations.entries().size)
    }
}
