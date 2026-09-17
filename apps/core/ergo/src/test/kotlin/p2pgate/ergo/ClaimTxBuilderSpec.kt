package p2pgate.ergo

import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.appkit.impl.OutBoxImpl
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl
import org.ergoplatform.sdk.JavaHelpers
import org.junit.jupiter.api.Test
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.HandoffRecord
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two user-side transactions (`specs/android-app.md` §4.3): claim-open
 * (path B) and claim payout (path D). Asserts context-var bytes exactly,
 * output-0 register-by-register contents, the R8 record id, fee math at
 * boundary feeBps values (0, 1, 25, 100, 10000), change correctness, and the
 * rejection cases. Every successful build is also *signed* by the offline
 * prover, which runs the real contract scripts during reduction — so a
 * passing build proves the tx satisfies the vault contract, not just the
 * builder's own math.
 */
class ClaimTxBuilderSpec {

    private val f = ErgoTestFixtures
    private val builder = ClaimTxBuilder(f.trees, f.treasuryTree)

    // ---------------------------------------------------------------- helpers

    private fun openTx(
        feeBps: Int = 0,
        feeInputValue: Long = 5_000_000L,
        currentHeight: Int = 1500,
        tsSec: Long = 1_700_000_000L,
    ): Triple<UnsignedTransaction, HandoffRecord, RefSchnorr.Signature> {
        val terms = f.dealTerms()
        val record = f.handoffRecord(terms, tsSec = tsSec)
        val sig = f.courierSign(record)
        val signer = ErgoTestFixtures.RecordingSigner(ErgoTestFixtures.ProverSigner(f.dealKeys.secret))
        builder.buildClaimOpen(
            fundedBox = f.fundedChainBox(terms, feeBps = feeBps),
            feeInputs = listOf(f.feeChainBox(value = feeInputValue)),
            record = record,
            a = sig.a,
            z = sig.z,
            currentHeight = currentHeight,
            txTimestampMs = tsSec * 1000,
            changeAddress = f.dealKeysAddress,
            signer = signer,
        )
        return Triple(signer.lastUnsigned!!, record, sig)
    }

    private fun rawExtension(tx: UnsignedTransaction): scala.collection.Map<Any, sigma.ast.EvaluatedValue<out sigma.ast.SType>> =
        (tx as UnsignedTransactionImpl).tx.inputs().apply(0).extension().values()

    private fun contextVarBytes(tx: UnsignedTransaction, id: Int): ByteArray {
        val v = rawExtension(tx).apply(id.toByte()).value()
        return JavaHelpers.collToByteArray(v as sigma.Coll<Any>)
    }

    private fun contextVarLong(tx: UnsignedTransaction, id: Int): Long {
        return (rawExtension(tx).apply(id.toByte()).value() as java.lang.Long).toLong()
    }

    private fun outBytes(out: OutBoxImpl, r: Int): ByteArray =
        JavaHelpers.collToByteArray(out.registers[r - 4].value as sigma.Coll<Any>)

    private fun outLong(out: OutBoxImpl, r: Int): Long =
        (out.registers[r - 4].value as java.lang.Long).toLong()

    // ---------------------------------------------------------------- claim-open (path B)

    @Test
    fun `claim-open carries the record and Schnorr half as context vars 0-3`() {
        val (tx, record, sig) = openTx()
        assertTrue(contextVarBytes(tx, 0).contentEquals(record.encode()))
        assertTrue(contextVarBytes(tx, 1).contentEquals(sig.a))
        assertTrue(contextVarBytes(tx, 2).contentEquals(sig.z))
        assertEquals(record.timestamp * 1000, contextVarLong(tx, 3))
    }

    @Test
    fun `claim-open output 0 is the PAYMENT_PROVEN box with register-exact contents`() {
        val terms = f.dealTerms()
        val (tx, record, sig) = openTx(feeBps = 25)
        val out = tx.outputs[0] as OutBoxImpl

        assertEquals(f.trees.provenPropositionHex, ErgoValues.treeHex(out.ergoTree))
        assertEquals(f.BOX_VALUE_NANO_ERG, out.value)
        assertEquals(1, out.tokens.size)
        assertEquals(f.useTokenIdHex, Base16.encode(out.tokens[0].id.getBytes()))
        assertEquals(f.DEAL_AMOUNT, out.tokens[0].value)
        assertEquals(1500, out.creationHeight)

        assertTrue(outBytes(out, 4).contentEquals(terms.dealId))
        assertTrue(outBytes(out, 5).contentEquals(f.sellerKeys.pubKeyCompressed))
        assertTrue(outBytes(out, 6).contentEquals(f.userKeys.pubKeyCompressed))
        assertEquals(f.packInts(1500, 25), outLong(out, 7)) // (proofHeight << 32) | feeBps
        assertTrue(outBytes(out, 8).contentEquals(SchnorrVerifier.blake2b256(sig.a, sig.z, record.encode())))
        assertTrue(outBytes(out, 9).contentEquals(f.fundingBinding()))
    }

    @Test
    fun `claim-open R8 equals the contract's record id`() {
        val (tx, record, sig) = openTx()
        val out = tx.outputs[0] as OutBoxImpl
        val expectedId = SchnorrVerifier.blake2b256(sig.a + sig.z + record.encode())
        assertTrue(outBytes(out, 8).contentEquals(expectedId))
    }

    @Test
    fun `claim-open change returns the fee surplus to the deal key`() {
        val (tx, _, _) = openTx(feeInputValue = 5_000_000L)
        assertEquals(2, tx.outputs.size)
        val change = tx.outputs[1] as OutBoxImpl
        val minerFee = 1_000_000L
        assertEquals(5_000_000L - minerFee, change.value)
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.dealKeys.pubKeyCompressed)),
            ErgoValues.treeHex(change.ergoTree),
        )
        // Exact balance: inputs = outputs + miner fee.
        val inSum = tx.inputs.sumOf { it.value }
        val outSum = tx.outputs.sumOf { it.value }
        assertEquals(inSum, outSum + minerFee)
    }

    @Test
    fun `claim-open signs successfully against the live vault script`() {
        // The RecordingSigner signed during openTx; a non-null id means the
        // full reduction (path B checks incl. in-script Schnorr) succeeded.
        val terms = f.dealTerms()
        val record = f.handoffRecord(terms)
        val sig = f.courierSign(record)
        val signed = builder.buildClaimOpen(
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
        assertTrue(signed.id.isNotBlank())
        assertEquals(2, signed.signedInputs.size)
    }

    // ---------------------------------------------------------------- claim-open rejections

    @Test
    fun `claim-open rejects a non-FUNDED input box`() {
        val terms = f.dealTerms()
        val record = f.handoffRecord(terms)
        val sig = f.courierSign(record)
        assertFailsWith<IllegalArgumentException> {
            builder.buildClaimOpen(
                fundedBox = f.provenChainBox(terms),
                feeInputs = listOf(f.feeChainBox()),
                record = record, a = sig.a, z = sig.z,
                currentHeight = 1500, txTimestampMs = record.timestamp * 1000,
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.dealKeys.secret),
            )
        }
    }

    @Test
    fun `claim-open rejects a record bound to a different dealId`() {
        val terms = f.dealTerms()
        val foreignRecord = HandoffRecord(
            dealId = ByteArray(32) { 9 },
            amount = terms.fiatAmount,
            fiatCurrency = terms.fiatCurrency,
            timestamp = 1_700_000_000L,
            courierIdHash = HandoffRecord.courierIdHash("x".encodeToByteArray()),
        )
        val sig = f.courierSign(foreignRecord)
        assertFailsWith<IllegalArgumentException> {
            builder.buildClaimOpen(
                fundedBox = f.fundedChainBox(terms),
                feeInputs = listOf(f.feeChainBox()),
                record = foreignRecord, a = sig.a, z = sig.z,
                currentHeight = 1500, txTimestampMs = foreignRecord.timestamp * 1000,
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.dealKeys.secret),
            )
        }
    }

    @Test
    fun `claim-open rejects a stale record timestamp`() {
        val terms = f.dealTerms()
        val record = f.handoffRecord(terms, tsSec = 1_700_000_000L)
        val sig = f.courierSign(record)
        val txTs = (1_700_000_000L + ContractParams.HANDOFF_RECORD_MAX_AGE_MS / 1000 + 60) * 1000
        assertFailsWith<IllegalArgumentException> {
            builder.buildClaimOpen(
                fundedBox = f.fundedChainBox(terms),
                feeInputs = listOf(f.feeChainBox()),
                record = record, a = sig.a, z = sig.z,
                currentHeight = 1500, txTimestampMs = txTs,
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.dealKeys.secret),
            )
        }
    }

    @Test
    fun `claim-open rejects a future record timestamp`() {
        val terms = f.dealTerms()
        val record = f.handoffRecord(terms, tsSec = 1_700_000_000L)
        val sig = f.courierSign(record)
        assertFailsWith<IllegalArgumentException> {
            builder.buildClaimOpen(
                fundedBox = f.fundedChainBox(terms),
                feeInputs = listOf(f.feeChainBox()),
                record = record, a = sig.a, z = sig.z,
                currentHeight = 1500, txTimestampMs = record.timestamp * 1000 - 60_000,
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.dealKeys.secret),
            )
        }
    }

    // ---------------------------------------------------------------- claim payout (path D)

    private fun payoutTx(
        feeBps: Int,
        proofHeight: Int = 1500,
        currentHeight: Int = 1500 + ContractParams.CLAIM_MATURATION_BLOCKS + 1,
        feeInputValue: Long = 5_000_000L,
    ): UnsignedTransaction {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.RecordingSigner(
            ErgoTestFixtures.ProverSigner(f.userKeys.secret, f.dealKeys.secret),
        )
        builder.buildClaimPayout(
            provenBox = f.provenChainBox(terms, feeBps = feeBps, proofHeight = proofHeight),
            feeInputs = listOf(f.feeChainBox(value = feeInputValue)),
            currentHeight = currentHeight,
            userPayoutAddress = f.p2pkAddress(f.userKeys.pubKeyCompressed),
            changeAddress = f.dealKeysAddress,
            signer = signer,
        )
        return signer.lastUnsigned!!
    }

    @Test
    fun `claim payout pays the user collateral minus fee and fees the treasury`() {
        val tx = payoutTx(feeBps = 25)
        val userOut = tx.outputs[0] as OutBoxImpl
        val fee = f.DEAL_AMOUNT * 25 / ContractParams.FEE_DENOMINATOR
        assertEquals(f.DEAL_AMOUNT - fee, userOut.tokens[0].value)
        assertEquals(f.useTokenIdHex, Base16.encode(userOut.tokens[0].id.getBytes()))
        assertEquals(
            ErgoValues.treeHex(ErgoValues.p2pkTree(f.userKeys.pubKeyCompressed)),
            ErgoValues.treeHex(userOut.ergoTree),
        )
        val feeOut = tx.outputs[1] as OutBoxImpl
        assertEquals(fee, feeOut.tokens[0].value)
        assertEquals(f.treasuryTree.bytesHex(), feeOut.ergoTree.bytesHex())
        assertEquals(f.BOX_VALUE_NANO_ERG, userOut.value)
    }

    @Test
    fun `fee math at boundary feeBps values`() {
        // The §6 formula at the boundaries.
        assertEquals(ClaimTxBuilder.FeeBreakdown(0, f.DEAL_AMOUNT), builder.feeBreakdown(f.DEAL_AMOUNT, 0))
        assertEquals(ClaimTxBuilder.FeeBreakdown(50_000, f.DEAL_AMOUNT - 50_000), builder.feeBreakdown(f.DEAL_AMOUNT, 1))
        assertEquals(ClaimTxBuilder.FeeBreakdown(1_250_000, f.DEAL_AMOUNT - 1_250_000), builder.feeBreakdown(f.DEAL_AMOUNT, 25))
        assertEquals(ClaimTxBuilder.FeeBreakdown(5_000_000, f.DEAL_AMOUNT - 5_000_000), builder.feeBreakdown(f.DEAL_AMOUNT, 100))
        assertEquals(ClaimTxBuilder.FeeBreakdown(f.DEAL_AMOUNT, 0), builder.feeBreakdown(f.DEAL_AMOUNT, 10000))

        // 0: no fee output, user takes everything.
        val tx0 = payoutTx(feeBps = 0)
        assertEquals(2, tx0.outputs.size) // user + change
        assertEquals(f.DEAL_AMOUNT, tx0.outputs[0].tokens[0].value)

        // 1 bp: fee = collateral / 10000 = 50_000, user gets the rest.
        val tx1 = payoutTx(feeBps = 1)
        assertEquals(3, tx1.outputs.size)
        assertEquals(f.DEAL_AMOUNT - 50_000L, tx1.outputs[0].tokens[0].value)
        assertEquals(50_000L, tx1.outputs[1].tokens[0].value)

        // 100 bps: fee = 1% of collateral.
        val tx100 = payoutTx(feeBps = 100)
        assertEquals(f.DEAL_AMOUNT - 5_000_000L, tx100.outputs[0].tokens[0].value)
        assertEquals(5_000_000L, tx100.outputs[1].tokens[0].value)

        // 10000 bps consumes the whole collateral — no positive user token is
        // possible, so the builder rejects the tx (formula above still applies).
        val terms = f.dealTerms()
        assertFailsWith<IllegalArgumentException> {
            builder.buildClaimPayout(
                provenBox = f.provenChainBox(terms, feeBps = 10000),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 2000,
                userPayoutAddress = f.p2pkAddress(f.userKeys.pubKeyCompressed),
                changeAddress = f.dealKeysAddress,
                signer = ErgoTestFixtures.ProverSigner(f.userKeys.secret, f.dealKeys.secret),
            )
        }
    }

    @Test
    fun `claim payout change and balance are exact`() {
        val tx = payoutTx(feeBps = 25)
        val change = tx.outputs.last() as OutBoxImpl
        val expectedChange = 5_000_000L - 1_000_000L /* miner fee */ - 100_000L /* fee box ERG */
        assertEquals(expectedChange, change.value)
        val inSum = tx.inputs.sumOf { it.value }
        val outSum = tx.outputs.sumOf { it.value }
        assertEquals(inSum, outSum + 1_000_000L)
    }

    @Test
    fun `claim payout rejects an immature claim`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.ProverSigner(f.userKeys.secret, f.dealKeys.secret)
        assertFailsWith<IllegalArgumentException> {
            builder.buildClaimPayout(
                provenBox = f.provenChainBox(terms, proofHeight = 1500),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 1500 + ContractParams.CLAIM_MATURATION_BLOCKS, // not strictly greater
                userPayoutAddress = f.p2pkAddress(f.userKeys.pubKeyCompressed),
                changeAddress = f.dealKeysAddress,
                signer = signer,
            )
        }
    }

    @Test
    fun `claim payout rejects a non-PAYMENT_PROVEN input box`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.ProverSigner(f.userKeys.secret, f.dealKeys.secret)
        assertFailsWith<IllegalArgumentException> {
            builder.buildClaimPayout(
                provenBox = f.fundedChainBox(terms),
                feeInputs = listOf(f.feeChainBox()),
                currentHeight = 2000,
                userPayoutAddress = f.p2pkAddress(f.userKeys.pubKeyCompressed),
                changeAddress = f.dealKeysAddress,
                signer = signer,
            )
        }
    }

    @Test
    fun `claim payout rejects insufficient fee ERG`() {
        val terms = f.dealTerms()
        val signer = ErgoTestFixtures.ProverSigner(f.userKeys.secret, f.dealKeys.secret)
        assertFailsWith<IllegalArgumentException> {
            builder.buildClaimPayout(
                provenBox = f.provenChainBox(terms),
                feeInputs = listOf(f.feeChainBox(value = 500_000L)), // below miner fee
                currentHeight = 2000,
                userPayoutAddress = f.p2pkAddress(f.userKeys.pubKeyCompressed),
                changeAddress = f.dealKeysAddress,
                signer = signer,
            )
        }
    }

    @Test
    fun `builder rejects a treasury tree that does not match the compiled hash`() {
        val otherTree = ErgoValues.p2pkTree(TestKeys.of(0x7777).pubKeyCompressed)
        assertFailsWith<IllegalArgumentException> {
            ClaimTxBuilder(f.trees, otherTree)
        }
    }
}
