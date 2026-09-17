package p2pgate.ergo

import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.UnsignedTransaction
import org.junit.jupiter.api.Test
import sigma.exceptions.InterpreterException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The phase-1 dev oracle ([DevOracle]): NFT pinning of the compiled `oracle.es`
 * box, dev-mode attestations, the [OracleSigner] seam (input box + co-sign),
 * and the oracle box's self-reproduction proven by the offline prover.
 *
 * ## The oracle.es box as the release input
 *
 * `oracle.es` pins its self-reproduction at `OUTPUTS(0)` (NFT id + amount,
 * value ≥ input, same tree); the vault contracts pay the seller at
 * `OUTPUTS(1)` on release paths, so the `oracle.es`-governed box co-signs a
 * vault release/contest in the same transaction (v2, 2026-09-17). The suite
 * pins both halves: the standalone rotation prover run passes, and a release
 * spending the `oracle.es` box as the oracle input is prover-accepted with
 * the reproduction output at index 0.
 */
class DevOracleSpec {

    private val f = ErgoTestFixtures
    private val oracle = f.devOracle()

    @Test
    fun `oracle box carries the NFT under the compiled oracle script`() {
        val box = oracle.oracleChainBox()
        assertEquals(f.trees.oracleNftId.toList(), oracle.oracleNftId.toList())
        assertEquals(1, box.tokens.size)
        assertEquals(Base16.encode(oracle.oracleNftId), box.tokens[0].tokenId)
        assertEquals(1L, box.tokens[0].amount)
        assertEquals(oracle.boxValueNanoErg, box.value)
        assertTrue(box.registers.all { it == null }) // oracle.es requires no registers
        // The box's script is the compiled oracle.es with THIS oracle's NFT + key.
        assertEquals(ErgoValues.treeHex(oracle.tree), box.ergoTreeHex)
        assertNotEquals(ErgoValues.treeHex(oracle.tree), f.trees.fundedPropositionHex)
    }

    @Test
    fun `attest derives the funding-set fields from the deal terms`() {
        val terms = f.dealTerms()
        val att = oracle.attest(terms, f.recipientRaw, ByteArray(32) { 3 }, 45_678L, 1_700_000_000L)
        assertTrue(att.matches(terms, f.recipientRaw))
        assertEquals(112, att.encode().size)
        assertEquals(PaymentAttestation.VERSION, att.version)
    }

    @Test
    fun `signer exposes an NFT-carrying oracle es input and co-signs`() {
        val signer = oracle.signer()
        assertTrue(signer.oracleNftId.contentEquals(oracle.oracleNftId))
        val input = signer.oracleInputBox()
        assertEquals(Base16.encode(oracle.oracleNftId), input.tokens[0].tokenId)
        // The release input is the oracle.es-governed box itself — its script
        // composes with the vault's pinned OUTPUTS(0) (OUTPUTS.exists).
        assertEquals(ErgoValues.treeHex(oracle.tree), input.ergoTreeHex)
    }

    @Test
    fun `oracle box self-reproduction passes the prover`() {
        // Rotation spend of the oracle.es box into its reproduction
        // (same tree, NFT preserved, value >=) — O1/O2 of the contracts suite,
        // here proven by the full offline prover run.
        val box = oracle.oracleChainBox()
        val fee = oracle.feeInputBox()
        val signer = ErgoTestFixtures.ProverSigner(f.oracleKeys.secret)
        val signed = TxAssembly.assemble(
            inputs = listOf(
                TxAssembly.toErgoBox(box, oracle.tree),
                TxAssembly.toErgoBox(fee, TxAssembly.decodeTree(fee)),
            ),
            contextVars = emptyMap(),
            contextVarInputIndex = 0,
            candidates = listOf(
                TxAssembly.candidateRaw(
                    value = box.value,
                    tree = oracle.tree,
                    tokens = box.tokens,
                    registers = emptyList(),
                    creationHeight = 100,
                ),
            ),
            minerFeeNanoErg = 1_000_000L,
            minChangeNanoErg = 1_000_000L,
            currentHeight = 100,
            txTimestampMs = null,
            changeAddress = f.p2pkAddress(oracle.pubKeyCompressed),
            networkType = f.networkType,
            signer = signer,
        )
        assertTrue(signed.id.isNotBlank())
    }

    @Test
    fun `oracle box rotation draining value below SELF value fails the prover`() {
        val box = oracle.oracleChainBox()
        val fee = oracle.feeInputBox()
        val signer = ErgoTestFixtures.ProverSigner(f.oracleKeys.secret)
        assertFailsWith<InterpreterException> {
            TxAssembly.assemble(
                inputs = listOf(
                    TxAssembly.toErgoBox(box, oracle.tree),
                    TxAssembly.toErgoBox(fee, TxAssembly.decodeTree(fee)),
                ),
                contextVars = emptyMap(),
                contextVarInputIndex = 0,
                candidates = listOf(
                    // NFT preserved (token balance holds) but value drained
                    // below SELF value — oracle.es's `out.value >= SELF.value`.
                    TxAssembly.candidateRaw(
                        value = box.value - 1,
                        tree = oracle.tree,
                        tokens = box.tokens,
                        registers = emptyList(),
                        creationHeight = 100,
                    ),
                ),
                minerFeeNanoErg = 1_000_000L,
                minChangeNanoErg = 1_000_000L,
                currentHeight = 100,
                txTimestampMs = null,
                changeAddress = f.p2pkAddress(oracle.pubKeyCompressed),
                networkType = f.networkType,
                signer = signer,
            )
        }
    }

    @Test
    fun `release with the oracle es box as oracle input prover-signs and recreates it at OUTPUTS 0`() {
        // oracle.es pins its reproduction at OUTPUTS(0); the vault pays the
        // seller at OUTPUTS(1) on release paths (v2, 2026-09-17), so the
        // oracle.es-governed box is the release input, recreated by
        // OperatorTxBuilder at output index 0.
        val terms = f.dealTerms()
        val builder = OperatorTxBuilder(f.trees, f.treasuryTree)
        val attestation = oracle.attest(terms, f.recipientRaw, ByteArray(32) { 5 }, 12_345L, 1_700_000_000L)
        val esBoxSigner = oracle.signer()
        assertEquals(ErgoValues.treeHex(oracle.tree), esBoxSigner.oracleInputBox().ergoTreeHex)
        val recording = RecordingOracleSigner(esBoxSigner)
        val signed = builder.buildRelease(
            fundedBox = f.fundedChainBox(terms),
            oracle = recording,
            attestation = attestation,
            feeInputs = listOf(oracle.feeInputBox()),
            currentHeight = 1500,
            changeAddress = f.p2pkAddress(oracle.pubKeyCompressed),
        )
        assertTrue(signed.id.isNotBlank())

        val tx = recording.lastUnsigned!!
        // The oracle reproduction rides OUTPUTS(0) under the oracle.es tree,
        // NFT preserved — exactly the position oracle.es pins.
        val nftHex = Base16.encode(oracle.oracleNftId)
        val repro = tx.outputs.withIndex().first { (_, out) ->
            out.tokens.any { Base16.encode(it.id.getBytes()).equals(nftHex, ignoreCase = true) }
        }
        assertEquals(0, repro.index)
        assertEquals(1L, repro.value.tokens.first { Base16.encode(it.id.getBytes()).equals(nftHex, ignoreCase = true) }.value)
        assertEquals(ErgoValues.treeHex(oracle.tree), ErgoValues.treeHex(repro.value.ergoTree))
        assertTrue(repro.value.value >= oracle.boxValueNanoErg)
    }

    /** Wraps an [OracleSigner] and records the unsigned transaction for assertions. */
    private class RecordingOracleSigner(private val delegate: OracleSigner) : OracleSigner {
        var lastUnsigned: UnsignedTransaction? = null
            private set

        override val oracleNftId: ByteArray get() = delegate.oracleNftId
        override fun oracleInputBox(): ChainBox = delegate.oracleInputBox()
        override fun sign(tx: UnsignedTransaction): SignedTransaction {
            lastUnsigned = tx
            return delegate.sign(tx)
        }
    }
}
