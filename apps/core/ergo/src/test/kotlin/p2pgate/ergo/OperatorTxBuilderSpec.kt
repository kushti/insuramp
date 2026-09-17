package p2pgate.ergo

import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.appkit.impl.OutBoxImpl
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.sdk.JavaHelpers
import org.junit.jupiter.api.Test
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.SourceChainId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The operator-side vault transactions (`specs/operator-backend.md` §2 "vault
 * manager"): fund, reclaim (path A), release (path C), contest (path C′).
 * Every successful build is prover-verified against the compiled scripts by
 * the offline prover (the ClaimTxBuilderSpec pattern) — a passing build proves
 * the tx satisfies the vault contract, not just the builder's own math. The
 * release/contest path uses [DevOracle] as the [OracleSigner]; the oracle
 * input is the `oracle.es`-governed box itself (joint spend proven by the
 * DevOracleSpec rotation + release tests).
 */
class OperatorTxBuilderSpec {

    private val f = ErgoTestFixtures
    private val builder = OperatorTxBuilder(f.trees, f.treasuryTree)
    private val claimBuilder = ClaimTxBuilder(f.trees, f.treasuryTree)
    private val oracle = f.devOracle()
    private val oracleAddress: String = f.p2pkAddress(oracle.pubKeyCompressed)

    // ---------------------------------------------------------------- helpers

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

    private fun rawExtension(tx: UnsignedTransaction): scala.collection.Map<Any, sigma.ast.EvaluatedValue<out sigma.ast.SType>> =
        (tx as UnsignedTransactionImpl).tx.inputs().apply(0).extension().values()

    private fun contextVarBytes(tx: UnsignedTransaction, id: Int): ByteArray {
        val v = rawExtension(tx).apply(id.toByte()).value()
        return JavaHelpers.collToByteArray(v as sigma.Coll<Any>)
    }

    private fun outBytes(out: OutBoxImpl, r: Int): ByteArray =
        JavaHelpers.collToByteArray(out.registers[r - 4].value as sigma.Coll<Any>)

    private fun outLong(out: OutBoxImpl, r: Int): Long =
        (out.registers[r - 4].value as java.lang.Long).toLong()

    /** An attestation over one field tampered away from the deal/R9 binding. */
    private fun tampered(
        terms: p2pgate.dealprotocol.DealTerms,
        dealId: ByteArray = terms.dealId,
        srcChainId: Int = terms.srcChainId,
        tokenId: Int = terms.asset,
        recipient: ByteArray = f.recipientRaw,
        amount: Long = terms.amount,
    ): PaymentAttestation = PaymentAttestation(
        dealId = dealId,
        srcChainId = srcChainId,
        tokenId = tokenId,
        recipient = recipient,
        amount = amount,
        srcTxId = ByteArray(32) { 5 },
        srcBlockHeight = 12_345L,
        srcBlockTime = 1_700_000_000L,
    )

    private fun honestAttestation(terms: p2pgate.dealprotocol.DealTerms): PaymentAttestation =
        tampered(terms)

    // ---------------------------------------------------------------- fund

    @Test
    fun `fund creates the FUNDED box register-exact and prover-signs`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.RecordingSigner(ErgoTestFixtures.ProverSigner(f.sellerKeys.secret))
        builder.buildFund(
            dealTerms = terms,
            recipientAddr = f.recipientRaw,
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
        assertTrue(outBytes(out, 9).contentEquals(f.fundingBinding()))
    }

    @Test
    fun `fund returns surplus collateral on the change output`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.RecordingSigner(ErgoTestFixtures.ProverSigner(f.sellerKeys.secret))
        builder.buildFund(
            dealTerms = terms,
            recipientAddr = f.recipientRaw,
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
                recipientAddr = f.recipientRaw,
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
    fun `fund rejects a mis-sized recipient and an expired timeout`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.ProverSigner(f.sellerKeys.secret)
        val fund = { recipient: ByteArray, timeout: Int ->
            builder.buildFund(
                dealTerms = terms,
                recipientAddr = recipient,
                collateralTokenId = Base16.decode(f.useTokenIdHex),
                timeoutHeight = timeout,
                fundingInputs = listOf(f.fundingChainBox()),
                currentHeight = f.CREATION_HEIGHT,
                changeAddress = f.p2pkAddress(f.sellerKeys.pubKeyCompressed),
                signer = signer,
            )
        }
        assertFailsWith<IllegalArgumentException> { fund(ByteArray(20), 2000) } // Tron payload is 21 B
        assertFailsWith<IllegalArgumentException> { fund(f.recipientRaw, f.CREATION_HEIGHT) } // timeout must be future
    }

    // ---------------------------------------------------------------- reclaim (path A)

    @Test
    fun `reclaim after timeout pays the seller minus fee and prover-signs`() {
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
        val fee = f.DEAL_AMOUNT * ContractParams.PROTOCOL_FEE_BPS / ContractParams.FEE_DENOMINATOR
        assertEquals(f.DEAL_AMOUNT - fee, sellerOut.tokens[0].value)
        assertEquals(f.useTokenIdHex, Base16.encode(sellerOut.tokens[0].id.getBytes()))
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.sellerKeys.pubKeyCompressed)),
            ErgoValues.treeHex(sellerOut.ergoTree),
        )
        assertEquals(f.BOX_VALUE_NANO_ERG, sellerOut.value)
        val feeOut = tx.outputs[1] as OutBoxImpl
        assertEquals(fee, feeOut.tokens[0].value)
        assertEquals(f.treasuryTree.bytesHex(), feeOut.ergoTree.bytesHex())
        // Exact balance: vault + fee input = seller + fee box + change + miner fee.
        val inSum = tx.inputs.sumOf { it.value }
        val outSum = tx.outputs.sumOf { it.value }
        assertEquals(inSum, outSum + 1_000_000L)
    }

    @Test
    fun `reclaim rounds the protocol fee down`() {
        // The §6 formula rounds down: a collateral of DEAL_AMOUNT + 1 yields the
        // same fee as DEAL_AMOUNT (1_250_000 at 25 bps), the seller takes the odd
        // unit, and the prover still verifies against the always-fee contract.
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.RecordingSigner(
            ErgoTestFixtures.ProverSigner(f.sellerKeys.secret, f.dealKeys.secret),
        )
        val collateral = f.DEAL_AMOUNT + 1
        builder.buildReclaim(
            fundedBox = f.fundedChainBox(
                terms,
                timeoutHeight = 1500,
                tokens = listOf(ChainToken(f.useTokenIdHex, collateral)),
            ),
            feeInputs = listOf(f.feeChainBox()),
            currentHeight = 1501,
            changeAddress = f.dealKeysAddress,
            signer = signer,
        )
        val tx = signer.lastUnsigned!!
        val fee = collateral * ContractParams.PROTOCOL_FEE_BPS / ContractParams.FEE_DENOMINATOR
        assertEquals(1_250_000L, fee)
        assertEquals(collateral - fee, tx.outputs[0].tokens[0].value)
        assertEquals(fee, tx.outputs[1].tokens[0].value)
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

    private fun releaseTx(
        terms: p2pgate.dealprotocol.DealTerms,
        attestation: PaymentAttestation,
        oracleSigner: OracleSigner = oracle.signer(),
        currentHeight: Int = 1500,
    ): SignedTransaction = builder.buildRelease(
        fundedBox = f.fundedChainBox(terms),
        oracle = oracleSigner,
        attestation = attestation,
        feeInputs = listOf(oracle.feeInputBox()),
        currentHeight = currentHeight,
        changeAddress = oracleAddress,
    )

    @Test
    fun `release with oracle co-sign and valid attestation prover-signs`() {
        val terms = f.dealTerms()
        val recording = RecordingOracleSigner(oracle.signer())
        val attestation = honestAttestation(terms)
        val signed = releaseTx(terms, attestation = attestation, oracleSigner = recording)

        // The prover run passed (the vault's path C gate + the oracle input).
        assertTrue(signed.id.isNotBlank())

        val tx = recording.lastUnsigned!!
        // Context var 0 is the exact 112-byte payload.
        assertTrue(contextVarBytes(tx, 0).contentEquals(attestation.encode()))

        // OUTPUTS(0): the oracle input recreated under the oracle.es tree
        // (NFT preserved, value >=) — oracle.es pins the reproduction at index 0.
        val repro = tx.outputs[0] as OutBoxImpl
        assertEquals(1, repro.tokens.size)
        assertEquals(Base16.encode(oracle.oracleNftId), Base16.encode(repro.tokens[0].id.getBytes()))
        assertEquals(1L, repro.tokens[0].value)
        assertEquals(ErgoValues.treeHex(oracle.tree), ErgoValues.treeHex(repro.ergoTree))
        assertEquals(oracle.boxValueNanoErg, repro.value)

        // OUTPUTS(1): seller paid collateral minus the protocol fee (always
        // charged — a compile-time constant) at the R5 seller key.
        val sellerOut = tx.outputs[1] as OutBoxImpl
        val fee = f.DEAL_AMOUNT * ContractParams.PROTOCOL_FEE_BPS / ContractParams.FEE_DENOMINATOR
        assertEquals(f.DEAL_AMOUNT - fee, sellerOut.tokens[0].value)
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.sellerKeys.pubKeyCompressed)),
            ErgoValues.treeHex(sellerOut.ergoTree),
        )
        // OUTPUTS(2): the treasury fee output.
        val feeOut = tx.outputs[2] as OutBoxImpl
        assertEquals(fee, feeOut.tokens[0].value)
        assertEquals(f.treasuryTree.bytesHex(), feeOut.ergoTree.bytesHex())

        // Token conservation: USE split seller(+fee) exactly, NFT into the recreation.
        val inTokens = tx.inputs.flatMap { it.tokens }.groupBy { Base16.encode(it.id.getBytes()) }.mapValues { e -> e.value.sumOf { it.value } }
        val outTokens = tx.outputs.flatMap { it.tokens }.groupBy { Base16.encode(it.id.getBytes()) }.mapValues { e -> e.value.sumOf { it.value } }
        assertEquals(inTokens, outTokens)
    }

    @Test
    fun `release deducts the protocol fee into the treasury output`() {
        val terms = f.dealTerms()
        val recording = RecordingOracleSigner(oracle.signer())
        releaseTx(terms, attestation = honestAttestation(terms), oracleSigner = recording)
        val tx = recording.lastUnsigned!!
        val fee = f.DEAL_AMOUNT * ContractParams.PROTOCOL_FEE_BPS / ContractParams.FEE_DENOMINATOR
        // OUTPUTS(0) is the oracle reproduction; seller payout at OUTPUTS(1),
        // treasury fee output at OUTPUTS(2).
        val sellerOut = tx.outputs[1] as OutBoxImpl
        assertEquals(f.DEAL_AMOUNT - fee, sellerOut.tokens[0].value)
        val feeOut = tx.outputs[2] as OutBoxImpl
        assertEquals(fee, feeOut.tokens[0].value)
        assertEquals(f.treasuryTree.bytesHex(), feeOut.ergoTree.bytesHex())
    }

    @Test
    fun `release rejects a tampered attestation dealId`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, attestation = tampered(terms, dealId = ByteArray(32) { 9 }))
        }
    }

    @Test
    fun `release rejects a tampered attestation recipient`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, attestation = tampered(terms, recipient = ByteArray(21) { (it * 23 + 7).toByte() }))
        }
    }

    @Test
    fun `release rejects a tampered attestation amount`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, attestation = tampered(terms, amount = terms.amount + 1))
        }
    }

    @Test
    fun `release rejects a tampered attestation chain id`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, attestation = tampered(terms, srcChainId = SourceChainId.ETHEREUM.wireId))
        }
    }

    @Test
    fun `release rejects a tampered attestation token id`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, attestation = tampered(terms, tokenId = 2))
        }
    }

    @Test
    fun `release rejects a foreign oracle box with a different NFT`() {
        val terms = f.dealTerms()
        val foreignNft = ByteArray(32) { (it * 11 + 2).toByte() }
        val foreignSigner = object : OracleSigner {
            override val oracleNftId: ByteArray get() = foreignNft
            override fun oracleInputBox(): ChainBox = ChainBox(
                boxId = "e3".repeat(32), transactionId = "e4".repeat(32), index = 0,
                value = oracle.boxValueNanoErg, creationHeight = 0,
                ergoTreeHex = ErgoValues.treeHex(ErgoValues.p2pkTree(oracle.pubKeyCompressed)),
                address = "", tokens = listOf(ChainToken(Base16.encode(foreignNft), 1L)), registers = List(6) { null },
            )

            override fun sign(tx: UnsignedTransaction): SignedTransaction = oracle.signer().sign(tx)
        }
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, attestation = honestAttestation(terms), oracleSigner = foreignSigner)
        }
    }

    @Test
    fun `release rejects an oracle input carrying no NFT at all`() {
        val terms = f.dealTerms()
        val noNftSigner = object : OracleSigner {
            override val oracleNftId: ByteArray get() = oracle.oracleNftId
            override fun oracleInputBox(): ChainBox = oracle.feeInputBox() // plain ERG box
            override fun sign(tx: UnsignedTransaction): SignedTransaction = oracle.signer().sign(tx)
        }
        assertFailsWith<IllegalArgumentException> {
            releaseTx(terms, attestation = honestAttestation(terms), oracleSigner = noNftSigner)
        }
    }

    @Test
    fun `release rejects a non-FUNDED input box`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildRelease(
                fundedBox = f.provenChainBox(terms),
                oracle = oracle.signer(),
                attestation = honestAttestation(terms),
                feeInputs = listOf(oracle.feeInputBox()),
                currentHeight = 1500,
                changeAddress = oracleAddress,
            )
        }
    }

    // ---------------------------------------------------------------- contest (path C′)

    @Test
    fun `contest counters a real claim-open with the oracle digest alone`() {
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
        //    oracle digest alone (OperatorTxBuilder, path C′).
        val proven = f.provenChainBox(
            terms,
            proofHeight = 1500,
            recordId = SchnorrVerifier.blake2b256(sig.a, sig.z, record.encode()),
        )
        val recording = RecordingOracleSigner(oracle.signer())
        val signed = builder.buildContest(
            provenBox = proven,
            oracle = recording,
            attestation = honestAttestation(terms),
            feeInputs = listOf(oracle.feeInputBox()),
            currentHeight = 1501, // within maturation — C′ beats path D
            changeAddress = oracleAddress,
        )
        assertTrue(signed.id.isNotBlank())

        val tx = recording.lastUnsigned!!
        assertTrue(contextVarBytes(tx, 0).contentEquals(honestAttestation(terms).encode()))
        // OUTPUTS(0) is the oracle reproduction; seller payout at OUTPUTS(1),
        // collateral minus the always-on protocol fee.
        val sellerOut = tx.outputs[1] as OutBoxImpl
        val fee = f.DEAL_AMOUNT * ContractParams.PROTOCOL_FEE_BPS / ContractParams.FEE_DENOMINATOR
        assertEquals(f.DEAL_AMOUNT - fee, sellerOut.tokens[0].value)
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.sellerKeys.pubKeyCompressed)),
            ErgoValues.treeHex(sellerOut.ergoTree),
        )
        val repro = tx.outputs[0] as OutBoxImpl
        assertEquals(Base16.encode(oracle.oracleNftId), Base16.encode(repro.tokens[0].id.getBytes()))
    }

    @Test
    fun `contest rejects a non-PAYMENT_PROVEN input box`() {
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildContest(
                provenBox = f.fundedChainBox(terms),
                oracle = oracle.signer(),
                attestation = honestAttestation(terms),
                feeInputs = listOf(oracle.feeInputBox()),
                currentHeight = 1500,
                changeAddress = oracleAddress,
            )
        }
    }
}
