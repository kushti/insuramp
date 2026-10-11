package p2pgate.backend

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import p2pgate.backend.api.DemoChainSource
import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainSource
import p2pgate.ergo.ChainSpend

/**
 * Demo-mode chain resilience (`P2P_DEMO_QUOTES=true`): height reads retry and
 * fall back to the last-known height so a flaky public explorer cannot hang
 * the demo's fund path; box/spend reads delegate unchanged.
 */
class DemoChainSourceSpec {

    private class FlakyChain(
        private val failuresBeforeSuccess: Int,
        private val height: Int = 1000,
    ) : ChainSource {
        var heightCalls = 0
        var boxCalls = 0
        override fun getBox(boxId: String): ChainBox? {
            boxCalls++
            return null
        }
        override fun getSpendingTransaction(boxId: String): ChainSpend? = null
        override fun getCurrentHeight(): Int {
            heightCalls++
            if (heightCalls <= failuresBeforeSuccess) throw RuntimeException("explorer timed out")
            return height
        }
        override fun getUnspentBoxes(address: String): List<ChainBox> = emptyList()
    }

    @Test
    fun `a transient failure is retried and the height returned`() {
        val inner = FlakyChain(failuresBeforeSuccess = 1)
        val chain = DemoChainSource(inner)
        assertEquals(1000, chain.getCurrentHeight())
        assertEquals(2, inner.heightCalls)
    }

    @Test
    fun `after a success, later failures fall back to the last known height`() {
        val inner = FlakyChain(failuresBeforeSuccess = 0)
        val chain = DemoChainSource(inner)
        chain.getCurrentHeight() // seed the cache
        inner.heightCalls = Int.MAX_VALUE // every further call fails
        assertEquals(1000, chain.getCurrentHeight())
    }

    @Test
    fun `with no height ever seen the failure propagates`() {
        val chain = DemoChainSource(FlakyChain(failuresBeforeSuccess = Int.MAX_VALUE))
        assertThrows(RuntimeException::class.java) { chain.getCurrentHeight() }
    }

    @Test
    fun `box reads delegate unchanged`() {
        val inner = FlakyChain(failuresBeforeSuccess = 0)
        val chain = DemoChainSource(inner)
        chain.getBox("ab".repeat(32))
        assertEquals(1, inner.boxCalls)
    }
}
