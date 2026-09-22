package p2pgate.ergo

import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.appkit.impl.OutBoxImpl
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.sdk.JavaHelpers
import org.junit.jupiter.api.Test
import p2pgate.contracts.ContractParams
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The operator-side vault transactions (`specs/operator-backend.md` §2 "vault
 * manager"): fund, reclaim (path A), release (path C), contest (path C′).
 * Every successful build is prover-verified against the compiled scripts by
 * the offline prover (the ClaimTxBuilderSpec pattern) — a passing build proves
 * the tx satisfies the vault contract, not just the builder's own math. The
 * release/contest path takes the oracle attestation box (R4 = the vault's
 * dealId, the oracle's per-deal signal) as a DATA INPUT
 * ([DevOracle.attestationBox]) and signs with a plain [DealTxSigner] — no
 * oracle co-signature exists (the data input's script never executes).
 */
class OperatorTxBuilderSpec {

    private val f = ErgoTestFixtures
    private val builder = OperatorTxBuilder(f.trees)
    private val claimBuilder = ClaimTxBuilder(f.trees)
    private val oracle = f.devOracle()

    private fun rawExtension(tx: UnsignedTransaction): scala.collection.Map<Any, sigma.ast.EvaluatedValue<out sigma.ast.SType>> =
        (tx as UnsignedTransactionImpl).tx.inputs().apply(0).extension().values()

    /** Base16 token id of the tx's first (only) data input's first token — the oracle NFT. */
    private fun dataInputNftId(tx: UnsignedTransaction): String {
        val dataBoxes = (tx as UnsignedTransactionImpl).dataBoxes
        assertEquals(1, dataBoxes.size)
        return dataBoxes[0].tokens().head()._1().lowercase()
    }

    private fun outBytes(out: OutBoxImpl, r: Int): ByteArray =
        JavaHelpers.collToByteArray(out.registers[r - 4].value as sigma.Coll<Any>)

    private fun outLong(out: OutBoxImpl, r: Int): Long =
        (out.registers[r - 4].value as java.lang.Long).toLong()

    // ---------------------------------------------------------------- fund

    @Test
    fun `fund creates the FUNDED box register-exact and prover-signs`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.RecordingSigner(ErgoTestFixtures.ProverSigner(f.sellerKeys.secret))
        builder.buildFund(
            dealTerms = terms,
            collateralTokenId = Base16.decode(f.useTokenIdHex),
            timeoutHeight = f.CREATION_HEIGHT + ContractParams.RECLAIM_TIMEOUT_BLOCKS,
            fundingInputs = listOf(f.fundingChainBox()),
            currentHeight = f.CREATION_HEIGHT,
            changeAddress = f.p2pkAddress(f.sellerKeys.pubKeyCompressed),
            signer = signer,
        )
        val tx = signer.lastUnsigned!!
        val out = tx.outputs[0] as OutBoxImpl

        assertEquals(f.trees.fundedPropositionHex, ErgoValues.treeHex(out.ergoTree))
        assertEquals(1_000_000L, out.value)
        assertEquals(1, out.tokens.size)
        assertEquals(f.useTokenIdHex, Base16.encode(out.tokens[0].id.getBytes()))
        assertEquals(f.DEAL_AMOUNT, out.tokens[0].value)
        assertEquals(f.CREATION_HEIGHT, out.creationHeight)

        assertTrue(outBytes(out, 4).contentEquals(terms.dealId))
        assertTrue(outBytes(out, 5).contentEquals(f.sellerKeys.pubKeyCompressed))
        assertTrue(outBytes(out, 6).contentEquals(f.buyerKeys.pubKeyCompressed))
        assertTrue(outBytes(out, 7).contentEquals(f.trees.oracleNftId))
        assertEquals(
            (f.CREATION_HEIGHT + ContractParams.RECLAIM_TIMEOUT_BLOCKS).toLong(),
            outLong(out, 8), // plain Long timeoutHeight
        )
    }

    @Test
    fun `fund returns surplus collateral on the change output`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.RecordingSigner(ErgoTestFixtures.ProverSigner(f.sellerKeys.secret))
        builder.buildFund(
            dealTerms = terms,
            collateralTokenId = Base16.decode(f.useTokenIdHex),
            timeoutHeight = 2000,
            fundingInputs = listOf(f.fundingChainBox(tokens = listOf(ChainToken(f.useTokenIdHex, f.DEAL_AMOUNT + 100_000_000L)))),
            currentHeight = f.CREATION_HEIGHT,
            changeAddress = f.p2pkAddress(f.sellerKeys.pubKeyCompressed),
            signer = signer,
        )
        val tx = signer.lastUnsigned!!
        assertEquals(2, tx.outputs.size)
        val change = tx.outputs[1] as OutBoxImpl
        assertEquals(100_000_000L, change.tokens[0].value)
        assertEquals(f.useTokenIdHex, Base16.encode(change.tokens[0].id.getBytes()))
        assertEquals(20_000_000L - 1_000_000L /* vault */ - 1_000_000L /* miner */, change.value)
        val inSum = tx.inputs.sumOf { it.value }
        val outSum = tx.outputs.sumOf { it.value }
        assertEquals(inSum, outSum + 1_000_000L)
    }

    @Test
    fun `fund rejects insufficient collateral`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildFund(
                dealTerms = terms,
                collateralTokenId = Base16.decode(f.useTokenIdHex),
                timeoutHeight = 2000,
                fundingInputs = listOf(f.fundingChainBox(tokens = listOf(ChainToken(f.useTokenIdHex, f.DEAL_AMOUNT - 1)))),
                currentHeight = f.CREATION_HEIGHT,
                changeAddress = f.p2pkAddress(f.sellerKeys.pubKeyCompressed),
                signer = ErgoTestFixtures.ProverSigner(f.sellerKeys.secret),
            )
        }
    }

    @Test
    fun `fund rejects an expired timeout`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildFund(
                dealTerms = terms,
                collateralTokenId = Base16.decode(f.useTokenIdHex),
                timeoutHeight = f.CREATION_HEIGHT, // timeout must be future
                fundingInputs = listOf(f.fundingChainBox()),
                currentHeight = f.CREATION_HEIGHT,
                changeAddress = f.p2pkAddress(f.sellerKeys.pubKeyCompressed),
                signer = ErgoTestFixtures.ProverSigner(f.sellerKeys.secret),
            )
        }
    }

    // ---------------------------------------------------------------- reclaim (path A)

    @Test
    fun `reclaim after timeout pays the seller in full and prover-signs`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.RecordingSigner(
            ErgoTestFixtures.ProverSigner(f.sellerKeys.secret, f.dealKeys.secret),
        )
        builder.buildReclaim(
            fundedBox = f.fundedChainBox(terms, timeoutHeight = 1500),
            feeInputs = listOf(f.feeChainBox()),
            currentHeight = 1501,
            changeAddress = f.dealKeysAddress,
            signer = signer,
        )
        val tx = signer.lastUnsigned!!
        val sellerOut = tx.outputs[0] as OutBoxImpl
        assertEquals(f.DEAL_AMOUNT, sellerOut.tokens[0].value)
        assertEquals(f.useTokenIdHex, Base16.encode(sellerOut.tokens[0].id.getBytes()))
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.sellerKeys.pubKeyCompressed)),
            ErgoValues.treeHex(sellerOut.ergoTree),
        )
        assertEquals(f.BOX_VALUE_NANO_ERG, sellerOut.value)
        // Exact balance: vault + fee input = seller + change + miner fee.
        val inSum = tx.inputs.sumOf { it.value }
        val outSum = tx.outputs.sumOf { it.value }
        assertEquals(inSum, outSum + 1_000_000L)
    }

    @Test
    fun `reclaim is rejected before the timeout height`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildReclaim(
                fundedBox = f.fundedChainBox(terms, timeoutHeight = 1500),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 1500, // contract requires HEIGHT > timeoutHeight
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.sellerKeys.secret, f.dealKeys.secret),
            )
        }
    }

    @Test
    fun `reclaim rejects a non-FUNDED input box`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildReclaim(
                fundedBox = f.provenChainBox(terms),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 5000,
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.sellerKeys.secret, f.dealKeys.secret),
            )
        }
    }

    // ---------------------------------------------------------------- release (path C)

    private val releaseSigner = ErgoTestFixtures.ProverSigner(f.dealKeys.secret)

    private fun releaseTx(
        terms: p2pgate.dealprotocol.DealTerms,
        dataInput: ChainBox? = null,
        currentHeight: Int = 1500,
    ): SignedTransaction = builder.buildRelease(
        fundedBox = f.fundedChainBox(terms),
        oracleDataInput = dataInput ?: oracle.attestationBox(terms.dealId),
        feeInputs = listOf(f.feeChainBox()),
        currentHeight = currentHeight,
        changeAddress = f.dealKeysAddress,
        signer = releaseSigner,
    )

    @Test
    fun `release with the oracle data input prover-signs`() {
        val terms = f.dealTerms()
        val recording = ErgoTestFixtures.RecordingSigner(releaseSigner)
        val signed = builder.buildRelease(
            fundedBox = f.fundedChainBox(terms),
            oracleDataInput = oracle.attestationBox(terms.dealId),
            feeInputs = listOf(f.feeChainBox()),
            currentHeight = 1500,
            changeAddress = f.dealKeysAddress,
            signer = recording,
        )

        // The prover run passed (the vault's path C gate with the data input).
        assertTrue(signed.id.isNotBlank())
        val tx = recording.lastUnsigned!!

        // The oracle box rides as the tx's DATA INPUT carrying the NFT (its R4
        // is this vault's dealId — the build-time mirror required equality).
        assertEquals(Base16.encode(oracle.oracleNftId), dataInputNftId(tx))

        // No context vars on the vault input — path C supplies none.
        assertTrue(rawExtension(tx).isEmpty)

        // OUTPUTS(0): seller paid the full collateral at the R5 seller key.
        val sellerOut = tx.outputs[0] as OutBoxImpl
        assertEquals(f.DEAL_AMOUNT, sellerOut.tokens[0].value)
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.sellerKeys.pubKeyCompressed)),
            ErgoValues.treeHex(sellerOut.ergoTree),
        )

        // Token conservation: USE pays out in full; the NFT rides the
        // untouched data input (no reproduction output on release anymore).
        val inTokens = tx.inputs.flatMap { it.tokens }.groupBy { Base16.encode(it.id.getBytes()) }.mapValues { e -> e.value.sumOf { it.value } }
        val outTokens = tx.outputs.flatMap { it.tokens }.groupBy { Base16.encode(it.id.getBytes()) }.mapValues { e -> e.value.sumOf { it.value } }
        assertEquals(inTokens, outTokens)
    }

    @Test
    fun `release rejects an attestation for a different deal`() {
        val terms = f.dealTerms()
        // The data input's R4 carries another deal's id — the dealId-equality
        // mirror (and the contract's path C gate) reject it.
        val otherDataInput = oracle.attestationBox(ByteArray(32) { 9 })
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, dataInput = otherDataInput)
        }
    }

    @Test
    fun `release rejects an oracle data input carrying a different NFT`() {
        val terms = f.dealTerms()
        val foreignNft = ByteArray(32) { (it * 11 + 2).toByte() }
        val foreignDataInput = ChainBox(
            boxId = "e3".repeat(32), transactionId = "e4".repeat(32), index = 0,
            value = oracle.boxValueNanoErg, creationHeight = 0,
            ergoTreeHex = ErgoValues.treeHex(oracle.tree),
            address = "", tokens = listOf(ChainToken(Base16.encode(foreignNft), 1L)),
            registers = listOf(ChainRegister.CollBytes(terms.dealId), null, null, null, null, null),
        )
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, dataInput = foreignDataInput)
        }
    }

    @Test
    fun `release rejects an oracle data input carrying no NFT at all`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            // A plain ERG fee box as the data input: no NFT to pin.
            releaseTx(terms, dataInput = f.feeChainBox())
        }
    }

    @Test
    fun `release rejects an oracle data input without the R4 dealId`() {
        val terms = f.dealTerms()
        // The at-rest oracle box (no registers) — the attestation was never posted.
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, dataInput = oracle.oracleChainBox())
        }
    }

    @Test
    fun `release rejects a non-FUNDED input box`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildRelease(
                fundedBox = f.provenChainBox(terms),
                oracleDataInput = oracle.attestationBox(terms.dealId),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 1500,
                changeAddress = f.dealKeysAddress,
                signer = releaseSigner,
            )
        }
    }

    // ---------------------------------------------------------------- contest (path C′)

    @Test
    fun `contest counters a real claim-open with the oracle attestation alone`() {
        val terms = f.dealTerms()

        // 1) The buyer opens a claim on the FUNDED box (ClaimTxBuilder, path B).
        val record = f.handoffRecord(terms)
        val sig = f.sellerSign(record)
        claimBuilder.buildClaimOpen(
            fundedBox = f.fundedChainBox(terms),
            feeInputs = listOf(f.feeChainBox()),
            record = record,
            a = sig.a,
            z = sig.z,
            currentHeight = 1500,
            txTimestampMs = record.timestamp * 1000,
            changeAddress = f.dealKeysAddress,
            signer = ErgoTestFixtures.ProverSigner(f.dealKeys.secret),
        )

        // 2) The honest seller counters from the PAYMENT_PROVEN box with the
        //    oracle attestation alone (OperatorTxBuilder, path C′).
        val proven = f.provenChainBox(
            terms,
            proofHeight = 1500,
            recordId = SchnorrVerifier.blake2b256(sig.a, sig.z, record.encode()),
        )
        val recording = ErgoTestFixtures.RecordingSigner(releaseSigner)
        val signed = builder.buildContest(
            provenBox = proven,
            oracleDataInput = oracle.attestationBox(terms.dealId),
            feeInputs = listOf(f.feeChainBox()),
            currentHeight = 1501, // within maturation — C′ beats path D
            changeAddress = f.dealKeysAddress,
            signer = recording,
        )
        assertTrue(signed.id.isNotBlank())

        val tx = recording.lastUnsigned!!
        assertEquals(Base16.encode(oracle.oracleNftId), dataInputNftId(tx))
        // Seller payout at OUTPUTS(0) — the full collateral.
        val sellerOut = tx.outputs[0] as OutBoxImpl
        assertEquals(f.DEAL_AMOUNT, sellerOut.tokens[0].value)
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.sellerKeys.pubKeyCompressed)),
            ErgoValues.treeHex(sellerOut.ergoTree),
        )
    }

    @Test
    fun `contest rejects a non-PAYMENT_PROVEN input box`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildContest(
                provenBox = f.fundedChainBox(terms),
                oracleDataInput = oracle.attestationBox(terms.dealId),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 1500,
                changeAddress = f.dealKeysAddress,
                signer = releaseSigner,
            )
        }
    }
}
