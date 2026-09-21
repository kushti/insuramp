package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        val first = env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0)
        assertEquals(1L, (first as QuotePublisher.PublishOutcome.Published).quote.version)
        val second = env.quotes.publish(40, 45, 1L, 400_000_000L, "USD", T0.plusSeconds(60))
        assertEquals(2L, (second as QuotePublisher.PublishOutcome.Published).quote.version)
        val active = env.quotes.active(T0.plusSeconds(60))
        assertEquals(2, active.size)
        assertEquals(listOf("quote-1", "quote-2"), active.map { it.id })
    }

    @Test
    fun `several quotes coexist with their own terms and locations`() {
        val env = env()
        env.quotes.publish(50, 60, 1L, 200_000_000L, "USD", T0, lat = 30.044, lon = 31.235)
        env.quotes.publish(80, 90, 1L, 300_000_000L, "USD", T0, lat = -1.292, lon = 36.821)
        env.quotes.publish(120, 30, 1L, 100_000_000L, "USD", T0)
        val active = env.quotes.active(T0)
        assertEquals(3, active.size)
        assertEquals(listOf(50, 80, 120), active.map { it.spreadBps })
        assertEquals(30.044, active[0].lat)
        assertEquals(36.821, active[1].lon)
        assertNull(active[2].lat)
        // Deal creation resolves a quote by id.
        assertEquals(80, env.quotes.activeQuote("quote-2", T0)!!.spreadBps)
        assertNull(env.quotes.activeQuote("quote-99", T0))
    }

    @Test
    fun `max deal size above free collateral is refused`() {
        val env = env(mixReady = 100_000_000L)
        val outcome = env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0)
        val rejected = assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, outcome)
        assertTrue(rejected.reason.contains("exceeds free collateral"))
        assertTrue(env.quotes.active(T0).isEmpty())
    }

    @Test
    fun `min deal size is validated at publish`() {
        val env = env()
        val zero = env.quotes.publish(50, 60, 0L, 500_000_000L, "USD", T0)
        assertTrue((zero as QuotePublisher.PublishOutcome.Rejected).reason.contains("must be positive"))
        val negative = env.quotes.publish(50, 60, -1L, 500_000_000L, "USD", T0)
        assertTrue((negative as QuotePublisher.PublishOutcome.Rejected).reason.contains("must be positive"))
        val inverted = env.quotes.publish(50, 60, 600_000_000L, 500_000_000L, "USD", T0)
        assertEquals(
            "min deal size 600000000 exceeds max deal size 500000000",
            (inverted as QuotePublisher.PublishOutcome.Rejected).reason,
        )
        assertTrue(env.store.quotes().isEmpty())
        // min == max is a fixed-size quote — fine.
        val exact = env.quotes.publish(50, 60, 500_000_000L, 500_000_000L, "USD", T0)
        assertInstanceOf(QuotePublisher.PublishOutcome.Published::class.java, exact)
        val quote = env.quotes.active(T0).single()
        assertEquals(500_000_000L, quote.minAmount)
        assertEquals(500_000_000L, quote.maxAmount)
    }

    @Test
    fun `capacity accounts for the other active quotes`() {
        val env = env(mixReady = 1_000_000_000L)
        env.quotes.publish(50, 60, 1L, 600_000_000L, "USD", T0)
        // The second quote competes with the 600M the first one already promises.
        val over = env.quotes.publish(50, 60, 1L, 600_000_000L, "USD", T0.plusSeconds(10))
        val rejected = assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, over)
        assertTrue(rejected.reason.contains("exceeds free collateral"))
        assertTrue(rejected.reason.contains("reserved"))
        val ok = env.quotes.publish(50, 60, 1L, 400_000_000L, "USD", T0.plusSeconds(10))
        assertInstanceOf(QuotePublisher.PublishOutcome.Published::class.java, ok)
        assertEquals(2, env.quotes.active(T0.plusSeconds(10)).size)
    }

    @Test
    fun `withdrawing one quote frees its capacity for the next publish`() {
        val env = env(mixReady = 1_000_000_000L)
        env.quotes.publish(50, 60, 1L, 600_000_000L, "USD", T0)
        assertTrue(env.quotes.withdraw("quote-1", "seller left", T0.plusSeconds(5)))
        assertFalse(env.quotes.withdraw("quote-1", null, T0.plusSeconds(6))) // already gone
        assertFalse(env.quotes.withdraw("quote-99", null, T0.plusSeconds(6)))
        assertTrue(env.quotes.active(T0.plusSeconds(6)).isEmpty())
        val ok = env.quotes.publish(50, 60, 1L, 600_000_000L, "USD", T0.plusSeconds(10))
        assertInstanceOf(QuotePublisher.PublishOutcome.Published::class.java, ok)
        assertTrue(env.store.events().any { it.kind == "QUOTE_WITHDRAWN" && it.detail.contains("quote-1") })
    }

    @Test
    fun `fiat currency is validated and normalized at publish`() {
        val env = env()
        val tooShort = env.quotes.publish(50, 60, 1L, 500_000_000L, "US", T0)
        assertTrue((tooShort as QuotePublisher.PublishOutcome.Rejected).reason.contains("3 letters"))
        val tooLong = env.quotes.publish(50, 60, 1L, 500_000_000L, "USDT", T0)
        assertTrue((tooLong as QuotePublisher.PublishOutcome.Rejected).reason.contains("USDT"))
        val digits = env.quotes.publish(50, 60, 1L, 500_000_000L, "U5D", T0)
        assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, digits)
        assertTrue(env.store.quotes().isEmpty())
        // Lowercase input is normalized to the uppercase code.
        val lower = env.quotes.publish(50, 60, 1L, 500_000_000L, "usd", T0)
        assertEquals("USD", (lower as QuotePublisher.PublishOutcome.Published).quote.fiatCurrency)
    }

    @Test
    fun `no quotes while infra is paused and withdrawal is evented`() {
        val env = env()
        env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0)
        env.infra.report(p2pgate.backend.infra.InfraSignal.EXPLORER_SYNC, false, "tip stale")
        assertTrue(env.infra.paused)
        assertTrue(env.quotes.active(T0.plusSeconds(10)).isEmpty())
        assertTrue(env.store.quotes().isEmpty())
        assertTrue(env.events.filterIsInstance<BackendEvent.QuoteWithdrawn>().isNotEmpty())
        // Publishing while paused is refused.
        val outcome = env.quotes.publish(50, 60, 1L, 100_000_000L, "USD", T0.plusSeconds(20))
        assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, outcome)
    }

    @Test
    fun `demo reseed refills an empty feed on read without any scheduler`() {
        val env = env()
        val publisher = QuotePublisher(
            env.store, env.infra, { 1_000_000_000L }, env.bus,
            demoReseed = { q -> q.publish(50, 60, 1L, 500_000_000L, "USD") },
        )
        assertTrue(publisher.active(T0).isEmpty().not())
        assertEquals(1, publisher.active(T0).size)
        // Only refills when empty — the existing feed is untouched.
        assertEquals(1, publisher.active(T0).size)
    }

    @Test
    fun `resume restores the suspended quotes and lapsed ones stay gone`() {
        val env = env()
        env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0)
        env.quotes.publish(80, 90, 1L, 200_000_000L, "INR", T0.plusSeconds(30), lat = 19.076, lon = 72.877)
        // Pause suspends + withdraws the feed (snapshot + withdraw).
        env.quotes.suspendForPause("auto-pause: test", T0.plusSeconds(10))
        assertTrue(env.quotes.active(T0.plusSeconds(20)).isEmpty())
        // Resume: both quotes come back with their ORIGINAL expiry (the pause
        // did not refresh the clock), ids intact.
        env.quotes.resumeFromPause(T0.plusSeconds(20))
        val restored = env.quotes.active(T0.plusSeconds(20))
        assertEquals(listOf("quote-1", "quote-2"), restored.map { it.id })
        assertEquals(T0.plus(QuotePublisher.DEFAULT_TTL), restored[0].expiresAt)
        assertEquals(19.076, restored[1].lat)
        // A quote that lapses mid-pause is not resurrected.
        env.quotes.suspendForPause("auto-pause: test", T0.plusSeconds(30))
        val pastBothExpiries = T0.plus(QuotePublisher.DEFAULT_TTL).plusSeconds(31)
        env.quotes.tick(pastBothExpiries)
        env.quotes.resumeFromPause(pastBothExpiries)
        assertTrue(env.quotes.active(pastBothExpiries).isEmpty())
    }

    @Test
    fun `quote expires after its ttl`() {
        val env = env()
        env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0)
        val ttl = Duration.ofMinutes(30)
        assertEquals(T0.plus(ttl), env.quotes.active(T0).single().expiresAt)
        assertTrue(env.quotes.active(T0.plus(ttl).plusSeconds(1)).isEmpty())
        env.quotes.tick(T0.plus(ttl).plusSeconds(1))
        assertTrue(env.store.quotes().isEmpty())
    }

    @Test
    fun `expiry prunes one quote and keeps the others`() {
        val env = env()
        env.quotes.publish(50, 60, 1L, 200_000_000L, "USD", T0)
        env.quotes.publish(50, 60, 1L, 200_000_000L, "USD", T0.plus(Duration.ofMinutes(20)))
        val ttl = Duration.ofMinutes(30)
        // At T0+40m the first quote (T0+30m) has lapsed, the second (T0+50m) has not.
        val active = env.quotes.active(T0.plus(Duration.ofMinutes(40)))
        assertEquals(listOf("quote-2"), active.map { it.id })
        // The prune is persisted and evented (the WS stream pushes a fresh snapshot).
        assertEquals(listOf("quote-2"), env.store.quotes().map { it.id })
        val withdrawn = env.events.filterIsInstance<BackendEvent.QuoteWithdrawn>()
        assertTrue(withdrawn.any { it.quoteId == "quote-1" && it.cause == "ttl expired" })
        assertEquals(T0.plus(Duration.ofMinutes(50)), active.single().expiresAt)
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
    fun `spread below the cost floor publishes with a warning`() {
        val env = TestEnv()
        val quotes = QuotePublisher(env.store, env.infra, { 1_000_000_000L }, env.bus, costFloorBps = 50)
        val outcome = quotes.publish(10, 60, 1L, 500_000_000L, "USD", T0) // below the 50 bps floor
        assertInstanceOf(QuotePublisher.PublishOutcome.Published::class.java, outcome)
        assertTrue(env.events.filterIsInstance<BackendEvent.QuoteWarning>().isNotEmpty())
    }

    @Test
    fun `publish with a location carries it through the record and the event`() {
        val env = env()
        val outcome = env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0, lat = 55.75, lon = 37.61)
        val quote = (outcome as QuotePublisher.PublishOutcome.Published).quote
        assertEquals(55.75, quote.lat)
        assertEquals(37.61, quote.lon)
        assertEquals(55.75, env.store.getQuote(quote.id)!!.lat)
        val event = env.events.filterIsInstance<BackendEvent.QuotePublished>().last()
        assertEquals(55.75, event.quote.lat)
        assertEquals(37.61, event.quote.lon)
    }

    @Test
    fun `a half location pair is rejected`() {
        val env = env()
        val latOnly = env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0, lat = 55.75)
        assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, latOnly)
        val lonOnly = env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0, lon = 37.61)
        assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, lonOnly)
        assertTrue(env.store.quotes().isEmpty())
    }

    @Test
    fun `an out of range location is rejected`() {
        val env = env()
        val badLat = env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0, lat = 91.0, lon = 0.0)
        assertTrue((badLat as QuotePublisher.PublishOutcome.Rejected).reason.contains("out of range"))
        val badLon = env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0, lat = -90.0, lon = -180.5)
        assertInstanceOf(QuotePublisher.PublishOutcome.Rejected::class.java, badLon)
        assertTrue(env.store.quotes().isEmpty())
    }

    @Test
    fun `publish without a location leaves the quote locationless`() {
        val env = env()
        val outcome = env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0)
        val quote = (outcome as QuotePublisher.PublishOutcome.Published).quote
        assertNull(quote.lat)
        assertNull(quote.lon)
    }

    @Test
    fun `withdraw clears the feed with a cause`() {
        val env = env()
        env.quotes.publish(50, 60, 1L, 500_000_000L, "USD", T0)
        env.quotes.publish(40, 45, 1L, 100_000_000L, "USD", T0)
        env.quotes.withdraw("operator maintenance", T0.plusSeconds(10))
        assertTrue(env.store.quotes().isEmpty())
        assertTrue(env.store.events().any { it.kind == "QUOTE_WITHDRAWN" })
    }

    @Test
    fun `demo seeding publishes one located quote per app currency within capacity`() {
        val env = env()
        val logs = mutableListOf<String>()
        p2pgate.backend.api.seedDemoQuotes(env.quotes, 1_000_000_000L) { logs += it }
        val active = env.quotes.active(java.time.Instant.now())
        assertEquals(4, active.size)
        assertEquals(30.044 to 31.235, active[0].lat!! to active[0].lon!!)   // Cairo
        assertEquals(-1.292 to 36.821, active[1].lat!! to active[1].lon!!)  // Nairobi
        assertEquals(19.076 to 72.877, active[2].lat!! to active[2].lon!!)  // Mumbai
        assertEquals(55.755 to 37.617, active[3].lat!! to active[3].lon!!)  // Moscow
        // Every buyer-app currency is pinned: USD, KSH, INR, RUB.
        assertEquals(listOf("USD", "KSH", "INR", "RUB"), active.map { it.fiatCurrency })
        // 30% + 25% + 20% + 15% of the pool — capacity honesty holds across the feed.
        assertEquals(900_000_000L, active.sumOf { it.maxAmount })
        // Distinct min deal sizes: 5 / 2 / 1 / 3 USDT.
        assertEquals(listOf(5_000_000L, 2_000_000L, 1_000_000L, 3_000_000L), active.map { it.minAmount })
        assertEquals(4, logs.size)
        assertTrue(logs.all { it.startsWith("demo quote seeded") })
    }

    @Test
    fun `demo seeding against an empty pool rejects the seeds without failing`() {
        val env = env(mixReady = 0L)
        val logs = mutableListOf<String>()
        p2pgate.backend.api.seedDemoQuotes(env.quotes, 0L) { logs += it }
        assertTrue(env.quotes.active(java.time.Instant.now()).isEmpty())
        assertEquals(4, logs.size)
        assertTrue(logs.all { it.contains("not seeded") })
    }

    @Test
    fun `locked collateral reduces free capacity`() {
        val env = env(mixReady = 1_000_000_000L)
        val deal = env.quotedDeal(amount = 600_000_000L)
        env.forceFund(deal)
        val outcome = env.quotes.publish(50, 60, 1L, 600_000_000L, "USD", T0)
        assertTrue((outcome as? QuotePublisher.PublishOutcome.Rejected)?.reason?.contains("exceeds") == true)
        val ok = env.quotes.publish(50, 60, 1L, 400_000_000L, "USD", T0)
        assertInstanceOf(QuotePublisher.PublishOutcome.Published::class.java, ok)
    }
}
