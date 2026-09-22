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
 * box, dev-mode attestations, the attestation box the release paths take as a
 * DATA INPUT, and the oracle box's self-reproduction (its attestation-posting
 * rotation) proven by the offline prover.
 *
 * ## The oracle.es box as the release data input
 *
 * The vault contracts read the attestation from `CONTEXT.dataInputs(0).R4` and
 * authenticate it by the NFT on that box — the data input's script never
 * executes, so no oracle signature rides in the release. The oracle's on-chain
 * involvement is posting the attestation: an `oracle.es` rotation spend that
 * recreates the singleton box with R4 = the 32-byte dealId (its
 * self-reproduction pins NFT + value at `OUTPUTS(0)`; registers are
 * unconstrained). The suite pins both halves: the standalone rotation prover
 * run passes, and a release carrying the attestation box as a data input is
 * prover-accepted with the seller payout at `OUTPUTS(0)`.
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
    fun `attestationBox exposes the NFT dealId box the release takes as data input`() {
        val terms = f.dealTerms()
        val box = oracle.attestationBox(terms.dealId)
        assertEquals(Base16.encode(oracle.oracleNftId), box.tokens[0].tokenId)
        assertEquals(1L, box.tokens[0].amount)
        assertEquals(ErgoValues.treeHex(oracle.tree), box.ergoTreeHex)
        assertTrue(box.registerBytes(4)!!.contentEquals(terms.dealId))
        assertEquals(32, box.registerBytes(4)!!.size)
        // Slots R5..R9 stay empty — the attestation box carries only R4.
        assertTrue(box.registers.drop(1).all { it == null })
    }

    @Test
    fun `attest returns the deal terms' dealId`() {
        val terms = f.dealTerms()
        val att = oracle.attest(terms)
        assertTrue(att.contentEquals(terms.dealId))
        assertEquals(32, att.size)
    }

    @Test
    fun `oracle box self-reproduction passes the prover`() {
        // Attestation-posting rotation spend of the oracle.es box into its
        // reproduction (same tree, NFT preserved, value >=) — O1/O2 of the
        // contracts suite, here proven by the full offline prover run.
        val box = oracle.oracleChainBox()
        val fee = f.feeChainBox()
        val signer = ErgoTestFixtures.ProverSigner(f.oracleKeys.secret, f.dealKeys.secret)
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
            changeAddress = f.dealKeysAddress,
            networkType = f.networkType,
            signer = signer,
        )
        assertTrue(signed.id.isNotBlank())
    }

    @Test
    fun `oracle box rotation draining value below SELF value fails the prover`() {
        val box = oracle.oracleChainBox()
        val fee = f.feeChainBox()
        val signer = ErgoTestFixtures.ProverSigner(f.oracleKeys.secret, f.dealKeys.secret)
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
                changeAddress = f.dealKeysAddress,
                networkType = f.networkType,
                signer = signer,
            )
        }
    }

    @Test
    fun `release with the attestation box as oracle data input prover-signs and pays seller at OUTPUTS 0`() {
        // The release carries the oracle box as a DATA INPUT (no oracle
        // signature — its script never executes); the seller payout sits at
        // OUTPUTS(0), paid in full.
        val terms = f.dealTerms()
        val builder = OperatorTxBuilder(f.trees)
        val dataInput = oracle.attestationBox(terms.dealId)
        val recording = RecordingSigner(ErgoTestFixtures.ProverSigner(f.dealKeys.secret))
        val signed = builder.buildRelease(
            fundedBox = f.fundedChainBox(terms),
            oracleDataInput = dataInput,
            feeInputs = listOf(f.feeChainBox()),
            currentHeight = 1500,
            changeAddress = f.dealKeysAddress,
            signer = recording,
        )
        assertTrue(signed.id.isNotBlank())

        val tx = recording.lastUnsigned!!
        val impl = tx as org.ergoplatform.appkit.impl.UnsignedTransactionImpl
        // The oracle attestation box rides as the tx's data input (index 0),
        // carrying the NFT (its tokens map decodes the token id to a base16 string).
        val dataBoxes = impl.dataBoxes
        assertEquals(1, dataBoxes.size)
        assertEquals(Base16.encode(oracle.oracleNftId), dataBoxes[0].tokens().head()._1().lowercase())
        // No context extension on the vault input — path C supplies no vars.
        assertTrue(impl.tx.inputs().apply(0).extension().values().isEmpty)
        // Seller payout at OUTPUTS(0) — the full collateral.
        val sellerOut = tx.outputs[0] as org.ergoplatform.appkit.impl.OutBoxImpl
        assertEquals(f.DEAL_AMOUNT, sellerOut.tokens[0].value)
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.sellerKeys.pubKeyCompressed)),
            ErgoValues.treeHex(sellerOut.ergoTree),
        )
    }

    /** Wraps a [DealTxSigner] and records the unsigned transaction for assertions. */
    private class RecordingSigner(private val delegate: DealTxSigner) : DealTxSigner {
        var lastUnsigned: UnsignedTransaction? = null
            private set

        override fun sign(tx: UnsignedTransaction): SignedTransaction {
            lastUnsigned = tx
            return delegate.sign(tx)
        }
    }
}
