package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.engine.DealEngine
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.DealState
import java.time.Duration
import java.time.Instant

class DealEngineSpec {

    private fun env() = TestEnv()

    @Test
    fun `vault funded advances quoted to funded and anchors the timestamp`() {
        val env = env()
        val deal = env.quotedDeal()
        val r = env.engine.apply(deal.dealId, DealEvent.VaultFunded(T0), T0)
        assertEquals(DealState.FUNDED, (r as DealEngine.Result.Advanced).to)
        val stored = env.store.getDeal(deal.dealId)!!
        assertEquals(DealState.FUNDED, stored.state)
        assertEquals(T0, stored.fundedAt)
    }

    @Test
    fun `full happy path through the machine`() {
        val env = env()
        val deal = env.quotedDeal()
        env.engine.apply(deal.dealId, DealEvent.VaultFunded(T0), T0)
        env.engine.apply(deal.dealId, DealEvent.CashCollected(T0, T0), T0)
        assertEquals(DealState.PAYMENT_PENDING, env.store.getDeal(deal.dealId)!!.state)
        env.engine.apply(deal.dealId, DealEvent.PaymentConfirmed, T0)
        assertEquals(DealState.PAYMENT_CONFIRMED, env.store.getDeal(deal.dealId)!!.state)
        env.engine.apply(deal.dealId, DealEvent.ReleaseObserved, T0)
        assertEquals(DealState.RELEASED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `dispute path matures and pays out`() {
        val env = env()
        val deal = env.quotedDeal()
        env.engine.apply(deal.dealId, DealEvent.VaultFunded(T0), T0)
        env.engine.apply(deal.dealId, DealEvent.CashCollected(T0, T0), T0)
        env.engine.apply(deal.dealId, DealEvent.ClaimOpened(T0.plusSeconds(600)), T0.plusSeconds(600))
        assertEquals(DealState.CLAIM_OPENED, env.store.getDeal(deal.dealId)!!.state)
        assertEquals(T0.plusSeconds(600), env.store.getDeal(deal.dealId)!!.proofTimestamp)
        // Too early: the machine validates the instant against its anchor.
        val early = env.engine.apply(deal.dealId, DealEvent.ClaimMatured(T0.plusSeconds(600 + 3600)), T0.plusSeconds(600 + 3600))
        assertInstanceOf(DealEngine.Result.Violation::class.java, early)
        env.engine.apply(
            deal.dealId,
            DealEvent.ClaimMatured(T0.plusSeconds(600).plus(Duration.ofHours(12))),
            T0.plusSeconds(600).plus(Duration.ofHours(12)),
        )
        assertEquals(DealState.CLAIMABLE, env.store.getDeal(deal.dealId)!!.state)
        env.engine.apply(deal.dealId, DealEvent.ClaimPaid, T0)
        assertEquals(DealState.CLAIMED, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `payment confirmed during a claim sets the contested flag and is not rejected`() {
        val env = env()
        val deal = env.quotedDeal()
        env.engine.apply(deal.dealId, DealEvent.VaultFunded(T0), T0)
        env.engine.apply(deal.dealId, DealEvent.CashCollected(T0, T0), T0)
        env.engine.apply(deal.dealId, DealEvent.ClaimOpened(T0.plusSeconds(60)), T0.plusSeconds(60))
        val r = env.engine.apply(deal.dealId, DealEvent.PaymentConfirmed, T0.plusSeconds(120))
        val advanced = assertInstanceOf(DealEngine.Result.Advanced::class.java, r)
        assertEquals(DealState.CLAIM_OPENED, advanced.to)
        assertTrue(env.store.getDeal(deal.dealId)!!.contested)
    }

    @Test
    fun `reclaim is rejected from payment pending as a theft path`() {
        val env = env()
        val deal = env.quotedDeal()
        env.engine.apply(deal.dealId, DealEvent.VaultFunded(T0), T0)
        env.engine.apply(deal.dealId, DealEvent.CashCollected(T0, T0), T0)
        val r = env.engine.apply(
            deal.dealId, DealEvent.ReclaimTimeoutElapsed(T0.plus(Duration.ofHours(25))), T0.plus(Duration.ofHours(25)),
        )
        val violation = assertInstanceOf(DealEngine.Result.Violation::class.java, r)
        assertTrue(violation.reason.contains("theft path") || violation.reason.contains("claim"))
        assertEquals(DealState.PAYMENT_PENDING, env.store.getDeal(deal.dealId)!!.state)
    }

    @Test
    fun `reclaim before the timeout is rejected`() {
        val env = env()
        val deal = env.quotedDeal()
        env.engine.apply(deal.dealId, DealEvent.VaultFunded(T0), T0)
        val r = env.engine.apply(deal.dealId, DealEvent.ReclaimTimeoutElapsed(T0.plus(Duration.ofHours(23))), T0)
        assertInstanceOf(DealEngine.Result.Violation::class.java, r)
    }

    @Test
    fun `quote expiry abandons a quoted deal with no state`() {
        val env = env()
        val deal = env.quotedDeal()
        val r = env.engine.apply(deal.dealId, DealEvent.QuoteExpired(T0.plusSeconds(3600)), T0.plusSeconds(3600))
        assertEquals(DealEngine.Result.Aborted, r)
        val stored = env.store.getDeal(deal.dealId)!!
        assertTrue(stored.abandoned)
        assertEquals(DealState.QUOTED, stored.state)
        assertTrue(env.store.openDeals().isEmpty())
    }

    @Test
    fun `invalid transitions are logged as invariant violations in store log violation log and bus`() {
        val env = env()
        val deal = env.quotedDeal()
        env.engine.apply(deal.dealId, DealEvent.ReleaseObserved, T0)
        assertEquals(1, env.engine.violations.entries().size)
        assertEquals(deal.dealId, env.engine.violations.entries().first().dealId)
        assertTrue(env.store.events(deal.dealId).any { it.kind == "VIOLATION" })
        assertTrue(env.events.filterIsInstance<BackendEvent.InvariantViolation>().isNotEmpty())
    }

    @Test
    fun `persist before dispatch - the bus listener sees the committed row`() {
        val env = env()
        val deal = env.quotedDeal()
        val observed = mutableListOf<Pair<DealState, DealState?>>()
        env.bus.subscribe { e ->
            if (e is BackendEvent.DealTransitioned) {
                observed += e.from to env.store.getDeal(e.dealId)?.state
            }
        }
        env.engine.apply(deal.dealId, DealEvent.VaultFunded(T0), T0)
        assertEquals(listOf(DealState.QUOTED to DealState.FUNDED), observed)
    }

    @Test
    fun `duplicate deal creation is a violation`() {
        val env = env()
        val deal = env.quotedDeal()
        val r = env.engine.createDeal(deal, T0)
        assertInstanceOf(DealEngine.Result.Violation::class.java, r)
    }

    @Test
    fun `events on unknown deals are violations with no state change`() {
        val env = env()
        val r = env.engine.apply("ff".repeat(32), DealEvent.VaultFunded(T0), T0)
        assertInstanceOf(DealEngine.Result.Violation::class.java, r)
    }

    @Test
    fun `cash collected with an absurd record timestamp is rejected`() {
        val env = env()
        val deal = env.quotedDeal()
        env.engine.apply(deal.dealId, DealEvent.VaultFunded(T0), T0)
        val r = env.engine.apply(
            deal.dealId,
            DealEvent.CashCollected(T0.minus(Duration.ofHours(3)), Instant.now()),
            Instant.now(),
        )
        assertInstanceOf(DealEngine.Result.Violation::class.java, r)
    }
}
