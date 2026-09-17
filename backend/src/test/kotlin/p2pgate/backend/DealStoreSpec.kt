package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import p2pgate.backend.store.InMemoryDealStore
import p2pgate.backend.store.StoredEvent
import p2pgate.dealprotocol.DealState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DealStoreSpec {

    @Test
    fun `create and get round-trips a deal`() {
        val store = InMemoryDealStore()
        val deal = TestEnv().quotedDeal()
        store.createDeal(deal)
        assertEquals(deal, store.getDeal(deal.dealId))
        assertNull(store.getDeal("ff".repeat(32)))
    }

    @Test
    fun `updateDeal applies the transform atomically`() {
        val store = InMemoryDealStore()
        val deal = TestEnv().quotedDeal()
        store.createDeal(deal)
        val updated = store.updateDeal(deal.dealId) { it.copy(state = DealState.FUNDED) }
        assertEquals(DealState.FUNDED, updated?.state)
        assertEquals(DealState.FUNDED, store.getDeal(deal.dealId)?.state)
        assertNull(store.updateDeal("ff".repeat(32)) { it })
    }

    @Test
    fun `openDeals excludes terminal and abandoned deals`() {
        val env = TestEnv()
        val a = env.quotedDeal()
        val b = env.quotedDeal(nonce = ByteArray(16) { (it + 1).toByte() })
        val c = env.quotedDeal(nonce = ByteArray(16) { (it + 2).toByte() })
        engineToFunded(env, a)
        engineToFunded(env, b)
        env.engine.apply(b.dealId, p2pgate.dealprotocol.DealEvent.ReclaimTimeoutElapsed(T0.plusSeconds(25 * 3600)), T0.plusSeconds(25 * 3600))
        env.engine.apply(c.dealId, p2pgate.dealprotocol.DealEvent.QuoteExpired(T0.plusSeconds(3600)), T0.plusSeconds(3600))
        val open = env.store.openDeals()
        assertEquals(listOf(a.dealId), open.map { it.dealId })
    }

    private fun engineToFunded(env: TestEnv, deal: p2pgate.backend.store.DealRecord) {
        env.engine.apply(deal.dealId, p2pgate.dealprotocol.DealEvent.VaultFunded(T0), T0)
    }

    @Test
    fun `event log is append-only and filterable`() {
        val store = InMemoryDealStore()
        store.appendEvent(StoredEvent("a", "ONE", "", T0))
        store.appendEvent(StoredEvent("b", "TWO", "", T0))
        store.appendEvent(StoredEvent("a", "THREE", "", T0))
        assertEquals(listOf("ONE", "THREE"), store.events("a").map { it.kind })
        assertEquals(3, store.events().size)
        assertEquals(3, store.events(null).size)
    }

    @Test
    fun `quote save clear current`() {
        val store = InMemoryDealStore()
        assertNull(store.currentQuote())
        val q = p2pgate.backend.store.QuoteRecord("q1", 1, 50, 60, 1_000, T0, T0.plusSeconds(1800))
        store.saveQuote(q)
        assertEquals(q, store.currentQuote())
        store.clearQuote()
        assertNull(store.currentQuote())
    }

    @Test
    fun `concurrent updates are thread-safe`() {
        val store = InMemoryDealStore()
        val deal = TestEnv().quotedDeal()
        store.createDeal(deal)
        val threads = 16
        val perThread = 50
        val latch = CountDownLatch(threads)
        val counter = AtomicInteger()
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                repeat(perThread) {
                    store.updateDeal(deal.dealId) { d -> d.copy(fiatAmount = d.fiatAmount + 1).also { counter.incrementAndGet() } }
                }
                latch.countDown()
            }
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(threads * perThread, counter.get())
        assertEquals(250_000L + threads * perThread, store.getDeal(deal.dealId)?.fiatAmount)
    }
}
