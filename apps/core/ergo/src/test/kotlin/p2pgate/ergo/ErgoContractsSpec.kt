package p2pgate.ergo

import org.junit.jupiter.api.Test
import p2pgate.contracts.ContractParams
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ErgoContracts.compile] — the (single) vault parameter set. Mainnet is the
 * default prefix since 2026-09-17; testnet stays selectable via
 * [ContractParams.NETWORK_PREFIX_TESTNET]. There is no fast variant anymore:
 * `compileFast` died with the handoff-record freshness window (2026-10-04) —
 * its 10-minute freshness override was the last remaining fast-compile
 * parameter, and the claim maturation has been a hardcoded literal since
 * 2026-10-03.
 */
class ErgoContractsSpec {

    private val f = ErgoTestFixtures

    @Test
    fun `compile defaults to mainnet and testnet stays selectable`() {
        val mainnetTrees = ErgoContracts.compile()
        assertEquals(org.ergoplatform.appkit.NetworkType.MAINNET, mainnetTrees.networkType)
        assertTrue(mainnetTrees.fundedPropositionHex.isNotBlank())
        assertTrue(mainnetTrees.provenPropositionHex.isNotBlank())
        // The oracle NFT is a deployment descriptor, not compiled into the trees.
        assertTrue(mainnetTrees.oracleNftId.contentEquals(f.trees.oracleNftId))
        // Testnet remains selectable via the networkPrefix parameter: same
        // trees, testnet rendering — the P2S addresses differ from the
        // mainnet bundle.
        val testnetTrees = ErgoContracts.compile(
            networkPrefix = ContractParams.NETWORK_PREFIX_TESTNET,
        )
        assertEquals(org.ergoplatform.appkit.NetworkType.TESTNET, testnetTrees.networkType)
        assertEquals(mainnetTrees.fundedPropositionHex, testnetTrees.fundedPropositionHex)
        assertNotEquals(mainnetTrees.fundedAddress.toString(), testnetTrees.fundedAddress.toString())
        assertNotEquals(mainnetTrees.provenAddress.toString(), testnetTrees.provenAddress.toString())
    }
}
