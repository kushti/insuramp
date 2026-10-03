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
 * the normal compile; since 2026-10-03 the claim maturation is a hardcoded
 * literal in the `.es`, so only the handoff-record freshness differs and the
 * fast PAYMENT_PROVEN tree still waits the canonical 360 blocks for path D.
 */
class FastContractsSpec {

    private val f = ErgoTestFixtures

    private val fastTrees: ErgoContracts.VaultTrees = ErgoContracts.compileFast()

    private val fastBuilder: ClaimTxBuilder = ClaimTxBuilder(
        fastTrees,
        handoffRecordMaxAgeMs = ErgoContracts.Fast.HANDOFF_RECORD_MAX_AGE_MS,
    )

    @Test
    fun `compileFast defaults to mainnet and testnet stays selectable`() {
        assertEquals(org.ergoplatform.appkit.NetworkType.MAINNET, fastTrees.networkType)
        assertTrue(fastTrees.fundedPropositionHex.isNotBlank())
        assertTrue(fastTrees.provenPropositionHex.isNotBlank())
        // The oracle NFT carries over.
        assertTrue(fastTrees.oracleNftId.contentEquals(f.trees.oracleNftId))
        // Testnet remains selectable via the networkPrefix parameter: same
        // trees, testnet rendering — the P2S addresses differ from the
        // mainnet bundle.
        val testnetTrees = ErgoContracts.compileFast(
            networkPrefix = ContractParams.NETWORK_PREFIX_TESTNET,
        )
        assertEquals(org.ergoplatform.appkit.NetworkType.TESTNET, testnetTrees.networkType)
        assertEquals(fastTrees.fundedPropositionHex, testnetTrees.fundedPropositionHex)
        assertNotEquals(fastTrees.fundedAddress.toString(), testnetTrees.fundedAddress.toString())
        assertNotEquals(fastTrees.provenAddress.toString(), testnetTrees.provenAddress.toString())
    }

    @Test
    fun `fast tree accepts a payout once the hardcoded maturation elapses`() {
        val terms = f.dealTerms()
        val proofHeight = 100
        val proven = f.provenChainBox(terms, proofHeight = proofHeight, trees = fastTrees)
        val signed = fastBuilder.buildClaimPayout(
            provenBox = proven,
            feeInputs = listOf(f.feeChainBox()),
            currentHeight = proofHeight + ContractParams.CLAIM_MATURATION_BLOCKS + 1, // 461
            buyerPayoutAddress = f.p2pkAddress(f.buyerKeys.pubKeyCompressed),
            changeAddress = f.dealKeysAddress,
            signer = ErgoTestFixtures.ProverSigner(f.buyerKeys.secret, f.dealKeys.secret),
        )
        // The offline prover ran the fast compiled script: 461 > 100 + 360 — the
        // maturation is the hardcoded literal, no longer a fast override.
        assertTrue(signed.id.isNotBlank())
    }

    @Test
    fun `the fast builder enforces the same maturation as canonical`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            fastBuilder.buildClaimPayout(
                provenBox = f.provenChainBox(terms, proofHeight = 100, trees = fastTrees),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 104, // needs > 100 + 360
                buyerPayoutAddress = f.p2pkAddress(f.buyerKeys.pubKeyCompressed),
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.buyerKeys.secret, f.dealKeys.secret),
            )
        }
    }

    @Test
    fun `fast constants are small and the reclaim timeout is funding-time`() {
        assertEquals(600_000L, ErgoContracts.Fast.HANDOFF_RECORD_MAX_AGE_MS)
        assertEquals(6, ErgoContracts.Fast.RECLAIM_TIMEOUT_BLOCKS)
        // RECLAIM_TIMEOUT is not a compiled constant — the canonical value is untouched.
        assertEquals(720, ContractParams.RECLAIM_TIMEOUT_BLOCKS)
        // The claim maturation is no longer a fast constant either — it is the
        // hardcoded 360L in vault_payment_proven.es.
        assertEquals(360, ContractParams.CLAIM_MATURATION_BLOCKS)
    }
}
