package p2pgate.e2e

import org.junit.jupiter.api.Test
import p2pgate.ergo.ChainBox
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline tests for the e2e flow orchestration with a fake [ChainGateway]
 * (canned chain state) and a fake [Faucet] — no network, no broadcasts.
 */
class E2eFlowSpec {

    /** Canned chain: fixed height, canned unspent boxes, nothing else. */
    private class FakeGateway(
        var height: Int = 100_000,
        var unspent: Map<String, List<ChainBox>> = emptyMap(),
        var failHeight: Boolean = false,
    ) : ChainGateway {
        var submitted = 0

        override fun getCurrentHeight(): Int {
            if (failHeight) throw IllegalStateException("network unreachable (canned)")
            return height
        }

        override fun getBox(boxId: String): ChainBox? = null
        override fun getUnspentBoxes(address: String): List<ChainBox> = unspent[address] ?: emptyList()
        override fun getTransactionOutputs(txId: String): List<ChainBox> =
            throw UnsupportedOperationException("no network in fake")

        override fun submitTransaction(signedTxJson: String): String {
            submitted++
            throw UnsupportedOperationException("no network in fake")
        }
    }

    private class FakeFaucet(var result: FaucetResult) : Faucet {
        var requests = 0
        override fun request(address: String): FaucetResult {
            requests++
            return result
        }
    }

    /** Virtual clock: the sleeper advances it, so poll loops terminate instantly. */
    private class VirtualClock(var now: Long = 1_700_000_000_000L) {
        val sleeper: (Long) -> Unit = { now += it }
        val nowMillis: () -> Long = { now }
    }

    private fun config(dryRun: Boolean = false, fundingTimeoutMs: Long = 60_000L, faucetEnabled: Boolean = false) = E2eConfig(
        dryRun = dryRun,
        fundingTimeoutMs = fundingTimeoutMs,
        faucetAttempts = 2,
        faucetEnabled = faucetEnabled,
    )

    private fun newFlow(
        gateway: FakeGateway,
        faucet: Faucet,
        clock: VirtualClock,
        logs: MutableList<String>,
        dryRun: Boolean = false,
        faucetEnabled: Boolean = false,
    ) = E2eFlow(
        config = config(dryRun = dryRun, faucetEnabled = faucetEnabled),
        gateway = gateway,
        faucet = faucet,
        sleeper = clock.sleeper,
        nowMillis = clock.nowMillis,
        log = { logs += it },
    )

    @Test
    fun `preflight failure prints manual instructions and exits 2`() {
        val gateway = FakeGateway(failHeight = true)
        val logs = mutableListOf<String>()
        val code = newFlow(gateway, FakeFaucet(FaucetResult.Accepted), VirtualClock(), logs).run()
        assertEquals(2, code)
        assertTrue(logs.any { it.contains("PREFLIGHT FAIL") })
        assertTrue(logs.any { it.contains("MANUAL RUN") })
    }

    @Test
    fun `faucet unavailable prints instructions and exits 2`() {
        val gateway = FakeGateway() // height ok, no unspent boxes anywhere
        val faucet = FakeFaucet(FaucetResult.Unavailable("faucet returned HTTP 404"))
        val logs = mutableListOf<String>()
        val code = newFlow(gateway, faucet, VirtualClock(), logs, faucetEnabled = true).run()
        assertEquals(2, code)
        assertTrue(logs.any { it.contains("faucet unavailable") })
        assertTrue(logs.any { it.contains("MANUAL RUN") })
        assertEquals(1, faucet.requests)
    }

    @Test
    fun `faucet disabled prints the operator address and needed amount then exits 2 on the deadline`() {
        // The mainnet default (E2E_FAUCET_URL=off): no faucet is ever contacted;
        // the gate prints the address + amount and polls until the deadline.
        val gateway = FakeGateway() // balance stays 0 forever
        val faucet = FakeFaucet(FaucetResult.Accepted)
        val clock = VirtualClock()
        val logs = mutableListOf<String>()
        val code = newFlow(gateway, faucet, clock, logs, dryRun = false).run()
        assertEquals(2, code)
        assertTrue(logs.any { it.contains("faucet is disabled") })
        assertTrue(logs.any { it.contains("still underfunded") })
        assertTrue(logs.any { it.contains("MANUAL RUN") })
        // The operator address and the needed nanoERG were printed for manual funding.
        assertTrue(logs.any { it.contains("operator ") && it.contains("needed: ") })
        // The faucet was never asked; the virtual clock advanced past the deadline.
        assertEquals(0, faucet.requests)
    }

    @Test
    fun `faucet enabled but balance never arrives exits 2 after the poll deadline`() {
        val gateway = FakeGateway() // balance stays 0 forever
        val faucet = FakeFaucet(FaucetResult.Accepted)
        val clock = VirtualClock()
        val logs = mutableListOf<String>()
        val code = newFlow(gateway, faucet, clock, logs, dryRun = false, faucetEnabled = true).run()
        assertEquals(2, code)
        assertTrue(logs.any { it.contains("still underfunded") })
        assertTrue(logs.any { it.contains("MANUAL RUN") })
        // Both attempts were made; the virtual clock advanced past the deadline.
        assertEquals(2, faucet.requests)
    }

    @Test
    fun `dry-run builds all three flows against canned chain state without broadcasting`() {
        val gateway = FakeGateway(height = 547_400)
        val faucet = FakeFaucet(FaucetResult.Unavailable("unused in dry-run"))
        val logs = mutableListOf<String>()
        val code = newFlow(gateway, faucet, VirtualClock(), logs, dryRun = true).run()
        assertEquals(0, code)
        assertTrue(logs.any { it.contains("flowA ok") }, logs.joinToString("\n"))
        assertTrue(logs.any { it.contains("flowB ok") }, logs.joinToString("\n"))
        assertTrue(logs.any { it.contains("flowC ok") }, logs.joinToString("\n"))
        assertTrue(logs.any { it.contains("e2e gate PASSED") })
        // Nothing was submitted and the faucet was never asked.
        assertEquals(0, gateway.submitted)
        assertEquals(0, faucet.requests)
    }
}
