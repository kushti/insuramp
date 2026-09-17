package p2pgate.e2e

import org.junit.jupiter.api.Test
import p2pgate.ergo.ExplorerChainSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [E2eConfig] default wiring — mainnet is the default target since
 * 2026-09-17; testnet stays fully selectable. Env overrides are read at
 * construction time (not settable from a JVM test), so this spec pins the
 * no-env defaults and the testnet-selection values the env vars feed.
 */
class E2eConfigSpec {

    @Test
    fun `defaults target mainnet with the faucet off`() {
        val config = E2eConfig()
        assertEquals(ExplorerChainSource.MAINNET_BASE_URL, config.explorerBaseUrl)
        assertFalse(config.faucetEnabled)
    }

    @Test
    fun `testnet stays selectable via explorer and faucet urls`() {
        val testnet = E2eConfig(
            explorerBaseUrl = ExplorerChainSource.TESTNET_BASE_URL,
            faucetBaseUrl = "https://testnet.ergofaucet.org",
            faucetEnabled = true,
        )
        assertEquals(ExplorerChainSource.TESTNET_BASE_URL, testnet.explorerBaseUrl)
        assertTrue(testnet.faucetEnabled)
    }
}
