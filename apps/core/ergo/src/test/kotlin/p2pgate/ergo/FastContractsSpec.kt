package p2pgate.ergo

import org.junit.jupiter.api.Test
import p2pgate.contracts.ContractParams
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ErgoContracts.compileFast] — the vault trees compiled with the small
 * [ErgoContracts.Fast] constants for the e2e gate. Mainnet is the default
 * prefix since 2026-09-17; testnet stays selectable via [ContractParams]
 * [ContractParams.NETWORK_PREFIX_TESTNET]. The bundle shape is identical to
 * the normal compile; the suite proves the fast PAYMENT_PROVEN tree accepts a
 * payout 4 blocks after a claim at a low height (the canonical trees would
 * need 361).
 */
class FastContractsSpec {

    private val f = ErgoTestFixtures

    private val fastTrees: ErgoContracts.VaultTrees =
        ErgoContracts.compileFast(treasuryScriptHash = f.treasuryHash)

    private val fastBuilder: ClaimTxBuilder = ClaimTxBuilder(
        fastTrees,
        f.treasuryTree,
        claimMaturationBlocks = ErgoContracts.Fast.CLAIM_MATURATION_BLOCKS,
        handoffRecordMaxAgeMs = ErgoContracts.Fast.HANDOFF_RECORD_MAX_AGE_MS,
    )

    @Test
    fun `compileFast defaults to mainnet and testnet stays selectable`() {
        assertEquals(org.ergoplatform.appkit.NetworkType.MAINNET, fastTrees.networkType)
        assertTrue(fastTrees.fundedPropositionHex.isNotBlank())
        assertTrue(fastTrees.provenPropositionHex.isNotBlank())
        // The treasury pin and oracle NFT carry over.
        assertTrue(fastTrees.treasuryScriptHash.contentEquals(f.treasuryHash))
        assertTrue(fastTrees.oracleNftId.contentEquals(f.trees.oracleNftId))
        // Testnet remains selectable via the networkPrefix parameter: same
        // trees, testnet rendering — the P2S addresses differ from the
        // mainnet bundle.
        val testnetTrees = ErgoContracts.compileFast(
            treasuryScriptHash = f.treasuryHash,
            networkPrefix = ContractParams.NETWORK_PREFIX_TESTNET,
        )
        assertEquals(org.ergoplatform.appkit.NetworkType.TESTNET, testnetTrees.networkType)
        assertEquals(fastTrees.fundedPropositionHex, testnetTrees.fundedPropositionHex)
        assertNotEquals(fastTrees.fundedAddress.toString(), testnetTrees.fundedAddress.toString())
        assertNotEquals(fastTrees.provenAddress.toString(), testnetTrees.provenAddress.toString())
    }

    @Test
    fun `fast tree accepts a payout 4 blocks after the claim at a low height`() {
        val terms = f.dealTerms()
        val proofHeight = 100
        val proven = f.provenChainBox(terms, proofHeight = proofHeight, trees = fastTrees)
        val signed = fastBuilder.buildClaimPayout(
            provenBox = proven,
            feeInputs = listOf(f.feeChainBox()),
            currentHeight = proofHeight + ErgoContracts.Fast.CLAIM_MATURATION_BLOCKS + 1, // 104
            buyerPayoutAddress = f.p2pkAddress(f.buyerKeys.pubKeyCompressed),
            changeAddress = f.dealKeysAddress,
            signer = ErgoTestFixtures.ProverSigner(f.buyerKeys.secret, f.dealKeys.secret),
        )
        // The offline prover ran the fast compiled script: 104 > 100 + 3.
        assertTrue(signed.id.isNotBlank())
    }

    @Test
    fun `canonical builder still enforces the canonical maturation at the same height`() {
        val terms = f.dealTerms()
        val canonical = ClaimTxBuilder(f.trees, f.treasuryTree)
        assertFailsWith<IllegalArgumentException> {
            canonical.buildClaimPayout(
                provenBox = f.provenChainBox(terms, proofHeight = 100),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 104, // canonical: needs > 100 + 360
                buyerPayoutAddress = f.p2pkAddress(f.buyerKeys.pubKeyCompressed),
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.buyerKeys.secret, f.dealKeys.secret),
            )
        }
    }

    @Test
    fun `fast constants are small and the reclaim timeout is funding-time`() {
        assertEquals(3, ErgoContracts.Fast.CLAIM_MATURATION_BLOCKS)
        assertEquals(600_000L, ErgoContracts.Fast.HANDOFF_RECORD_MAX_AGE_MS)
        assertEquals(6, ErgoContracts.Fast.RECLAIM_TIMEOUT_BLOCKS)
        // RECLAIM_TIMEOUT is not a compiled constant — the canonical value is untouched.
        assertEquals(720, ContractParams.RECLAIM_TIMEOUT_BLOCKS)
    }
}
