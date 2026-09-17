package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.quotes.QuotePublisher
import java.time.Duration

class QuotePublisherSpec {

    private fun env(mixReady: Long = 1_000_000_000L) = TestEnv(mixReady = mixReady)

    @Test
    fun `publish succeeds within capacity and versions increment`() {
        val env = env()
        val first = env.quotes.publish(50, 60, 500_000_000L, T0)
        assertEquals(1L, (first as QuotePublisher.PublishOutcome.Published).quote.version)
        val second = env.quotes.publish(40, 45, 400_000_000L, T0.plusSeconds(60))
        assertEquals(2L, (second as QuotePublisher.PublishOutcome.Published).quote.version)
        assertEquals(400_000_000L, env.quotes.active(T0.plusSeconds(60))!!.maxAmount)
    }

    @Test
    fun `max deal size above free collateral is refused`() {
        val env = env(mixReady = 100_000_000L)
        val outcome = env.quotes.publish(50, 60, 500_000_000L, T0)
        val rejected = assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, outcome)
        assertTrue(rejected.reason.contains("exceeds free collateral"))
        assertNull(env.quotes.active(T0))
    }

    @Test
    fun `no quotes while infra is paused and withdrawal is evented`() {
        val env = env()
        env.quotes.publish(50, 60, 500_000_000L, T0)
        env.infra.report(p2pgate.backend.infra.InfraSignal.EXPLORER_SYNC, false, "tip stale")
        assertTrue(env.infra.paused)
        assertNull(env.quotes.active(T0.plusSeconds(10)))
        assertNull(env.store.currentQuote())
        assertTrue(env.events.filterIsInstance<BackendEvent.QuoteWithdrawn>().isNotEmpty())
        // Publishing while paused is refused.
        val outcome = env.quotes.publish(50, 60, 100_000_000L, T0.plusSeconds(20))
        assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, outcome)
    }

    @Test
    fun `quote expires after its ttl`() {
        val env = env()
        env.quotes.publish(50, 60, 500_000_000L, T0)
        val ttl = Duration.ofMinutes(30)
        assertEquals(T0.plus(ttl), env.quotes.active(T0)!!.expiresAt)
        assertNull(env.quotes.active(T0.plus(ttl).plusSeconds(1)))
        env.quotes.tick(T0.plus(ttl).plusSeconds(1))
        assertNull(env.store.currentQuote())
    }

    @Test
    fun `ttl is hard-capped below the reclaim timeout`() {
        val store = p2pgate.backend.store.InMemoryDealStore()
        val bus = p2pgate.backend.bus.EventBus()
        val infra = p2pgate.backend.infra.InfraMonitor(bus)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            QuotePublisher(store, infra, { 1_000_000_000L }, bus, ttl = Duration.ofHours(25))
        }
    }

    @Test
    fun `spread below protocol fee plus cost floor publishes with a warning`() {
        val env = env()
        val outcome = env.quotes.publish(10, 60, 500_000_000L, T0) // fee floor 25
        assertInstanceOf(QuotePublisher.PublishOutcome.Published::class.java, outcome)
        assertTrue(env.events.filterIsInstance<BackendEvent.QuoteWarning>().isNotEmpty())
    }

    @Test
    fun `withdraw clears the feed with a cause`() {
        val env = env()
        env.quotes.publish(50, 60, 500_000_000L, T0)
        env.quotes.withdraw("operator maintenance", T0.plusSeconds(10))
        assertNull(env.store.currentQuote())
        assertTrue(env.store.events().any { it.kind == "QUOTE_WITHDRAWN" })
    }

    @Test
    fun `locked collateral reduces free capacity`() {
        val env = env(mixReady = 1_000_000_000L)
        val deal = env.quotedDeal(amount = 600_000_000L)
        env.forceFund(deal)
        val outcome = env.quotes.publish(50, 60, 600_000_000L, T0)
        assertTrue((outcome as? QuotePublisher.PublishOutcome.Rejected)?.reason?.contains("exceeds") == true)
        val ok = env.quotes.publish(50, 60, 400_000_000L, T0)
        assertInstanceOf(QuotePublisher.PublishOutcome.Published::class.java, ok)
    }
}
