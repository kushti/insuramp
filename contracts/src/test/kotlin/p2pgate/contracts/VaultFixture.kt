package p2pgate.contracts

import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoBoxCandidate
import sigma.ast.ErgoTree
import sigma.ast.EvaluatedValue
import sigma.ast.SType
import sigmastate.crypto.DLogProtocol
import sigmastate.crypto.SigmaProtocolPrivateInput
import scala.Tuple2
import scala.collection.immutable.IndexedSeq as SIndexedSeq

private fun <A, B> t(a: A, b: B): Tuple2<A, B> = Tuple2.apply(a, b)

private fun Long.toBe(n: Int): ByteArray = ByteArray(n) { i -> (this shr (8 * (n - 1 - i))).toByte() }

/**
 * Per-deal test fixture: keys, token/deal ids, the three compiled vault
 * contracts, the FUNDED/PAYMENT_PROVEN/oracle boxes, wire-format builders and
 * the prove/verify driver. One fixture instance per deal under test.
 */
class VaultFixture(
    /** R7 substitution for the adversarial tests: a wrong oracle NFT id, or replace R7
     *  wholesale with arbitrary bytes. Only the FUNDED box's R7 is affected. */
    r7OracleNftId: ByteArray? = null,
    r7Bytes: ByteArray? = null,
) {

    // --- deal-scoped keys (compressed secp256k1 points go into registers) ---
    val sellerKey: DLogProtocol.DLogProverInput = SigmaBridge.dlogRandom()
    val buyerKey: DLogProtocol.DLogProverInput = SigmaBridge.dlogRandom()
    val oracleKey: DLogProtocol.DLogProverInput = SigmaBridge.dlogRandom()

    val sellerPk: ByteArray = SigmaBridge.ecpEncoded(SigmaBridge.ecp(sellerKey), true)
    val buyerPk: ByteArray = SigmaBridge.ecpEncoded(SigmaBridge.ecp(buyerKey), true)
    val oraclePk: ByteArray = SigmaBridge.ecpEncoded(SigmaBridge.ecp(oracleKey), true)

    val sellerTree: ErgoTree = SigmaBridge.p2pkTree(sellerKey.publicImage())
    val buyerTree: ErgoTree = SigmaBridge.p2pkTree(buyerKey.publicImage())

    // --- token / deal identifiers (deterministic, distinct per field) ---
    val useTokenId: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }
    val oracleNftId: ByteArray = ByteArray(32) { (it * 5 + 1).toByte() }
    val wrongNftId: ByteArray = ByteArray(32) { (it * 11 + 2).toByte() }
    val dealId: ByteArray = ByteArray(32) { (it * 13 + 4).toByte() }
    val srcTxId: ByteArray = ByteArray(32) { (it * 17 + 5).toByte() }
    val recipientAddr: ByteArray = ByteArray(21) { (it * 19 + 6).toByte() } // the buyer's USDT address (the seller pays the buyer)
    val chainId: Byte = 1 // Tron — registry in specs/deal-protocol.md §3.1
    val tokenId: Byte = 1 // USDT

    /** R7 of the FUNDED box: the 32-byte oracleNftId (release paths only; path B
     *  no longer reads R7 — the record signature verifies under R5's seller key). */
    val fundedR7: ByteArray = r7Bytes ?: (r7OracleNftId ?: oracleNftId)

    val dealAmount: Long = 500_000_000L // 500 USDT, 6 decimals
    val boxValue: Long = 1_000_000L // nanoERG

    // --- heights ---
    val creationHeight: Int = 1000
    val timeoutHeight: Int = creationHeight + ContractParams.RECLAIM_TIMEOUT_BLOCKS

    // --- compiled contracts (order matters: proven feeds funded) ---
    val provenTree: ErgoTree = ContractCompiler.compileResource(
        "vault_payment_proven.es",
        mapOf(
            "ORACLE_NFT_ID" to ConstValue.Bytes(oracleNftId),
            "HANDOFF_RECORD_MAX_AGE_MS" to ConstValue.Raw("${ContractParams.HANDOFF_RECORD_MAX_AGE_MS}L"),
            "CLAIM_MATURATION_BLOCKS" to ConstValue.IntNum(ContractParams.CLAIM_MATURATION_BLOCKS),
        ),
    )

    val fundedTree: ErgoTree = ContractCompiler.compileResource(
        "vault_funded.es",
        mapOf(
            "PAYMENT_PROVEN_SCRIPT" to ConstValue.Bytes(provenTree.bytes()),
            "HANDOFF_RECORD_MAX_AGE_MS" to ConstValue.Raw("${ContractParams.HANDOFF_RECORD_MAX_AGE_MS}L"),
        ),
    )

    val oracleTree: ErgoTree = ContractCompiler.compileResource(
        "oracle.es",
        mapOf(
            "ORACLE_NFT_ID" to ConstValue.Bytes(oracleNftId),
            "ORACLE_KEY" to ConstValue.Bytes(oraclePk),
        ),
    )

    // --- wire formats (specs/deal-protocol.md §3.3 / specs/oracle-integration.md §2.2) ---

    /** 52-byte handoff record ("P2PH", signed by the seller key at the meeting):
     *  magic, version, dealId, fiatAmount, currency, timestamp. */
    fun handoffRecord(
        tsSec: Long = NOW_MS / 1000,
        dealId: ByteArray = this.dealId,
        fiatAmount: Long = 250_000L,
        currency: String = "EGP",
    ): ByteArray {
        require(currency.length == 3)
        return "P2PH".toByteArray() + byteArrayOf(1) + dealId + fiatAmount.toBe(8) +
            currency.toByteArray() + tsSec.toBe(4)
    }

    /** Handoff-record id written to the PAYMENT_PROVEN box's R8: blake2b256(a | z | record). */
    fun recordId(record: ByteArray, sig: Schnorr.Signature): ByteArray =
        SigmaBridge.blake2b256(sig.a + sig.z + record)

    /** 112-byte payment-proof payload: version, dealId, chain/token, recipient, amount, srcTxId, block. */
    fun paymentPayload(
        amount: Long = dealAmount,
        dealId: ByteArray = this.dealId,
        recipientAddr: ByteArray = this.recipientAddr,
        srcTxId: ByteArray = this.srcTxId,
        srcHeight: Long = 12_345L,
        srcTime: Long = 1_700_000_000L,
    ): ByteArray =
        byteArrayOf(1) + dealId + byteArrayOf(chainId, tokenId) + recipientAddr + amount.toBe(8) +
            srcTxId + srcHeight.toBe(8) + srcTime.toBe(8)

    // --- registers ---

    private fun bytesC(b: ByteArray): EvaluatedValue<out SType> = SigmaBridge.bytesConst(b)

    /** The R9 funding binding shared by the FUNDED box and the PAYMENT_PROVEN copy. */
    val fundingBinding: ByteArray = byteArrayOf(chainId, tokenId) + recipientAddr + dealAmount.toBe(8)

    /** The FUNDED box's registers: R8 is the plain `Long` timeoutHeight (§3.1). */
    val fundedRegs = SigmaBridge.regs(
        listOf(
            t(SigmaBridge.regId(4), bytesC(dealId)),
            t(SigmaBridge.regId(5), bytesC(sellerPk)),
            t(SigmaBridge.regId(6), bytesC(buyerPk)),
            t(SigmaBridge.regId(7), bytesC(fundedR7)),
            t(SigmaBridge.regId(8), SigmaBridge.longVal(timeoutHeight.toLong()) as EvaluatedValue<out SType>),
            t(SigmaBridge.regId(9), bytesC(fundingBinding)),
        ),
    )

    /** The PAYMENT_PROVEN registers: R7 is the plain `Long` proofHeight (§4.1). */
    fun provenRegs(proofHeight: Int, recordId: ByteArray) = SigmaBridge.regs(
        listOf(
            t(SigmaBridge.regId(4), bytesC(dealId)),
            t(SigmaBridge.regId(5), bytesC(sellerPk)),
            t(SigmaBridge.regId(6), bytesC(buyerPk)),
            t(SigmaBridge.regId(7), SigmaBridge.longVal(proofHeight.toLong()) as EvaluatedValue<out SType>),
            t(SigmaBridge.regId(8), bytesC(recordId)),
            t(SigmaBridge.regId(9), bytesC(fundingBinding)),
        ),
    )

    // --- boxes ---

    private fun tok(id: ByteArray, amount: Long) = t(id, amount)

    private fun tokens(vararg toks: Tuple2<ByteArray, Long>) =
        SigmaBridge.tokens(toks.toList())

    val fundedBox: ErgoBox = SigmaBridge.box(
        boxValue, fundedTree, tokens(tok(useTokenId, dealAmount)), fundedRegs,
        "ab".repeat(32), 0, creationHeight,
    )

    val oracleBox: ErgoBox = SigmaBridge.box(
        boxValue, oracleTree, tokens(tok(oracleNftId, 1L)), SigmaBridge.emptyRegs(),
        "cd".repeat(32), 0, creationHeight,
    )

    /**
     * The oracle singleton box as the release DATA INPUT: the oracle.es tree
     * (never executed as a data input), the NFT, and R4 = the 112-byte
     * attestation payload (specs/oracle-integration.md §2.2). Parameterize
     * [payload]/[nftId]/[tree] for the adversarial release tests.
     */
    fun oracleDataBox(
        payload: ByteArray = paymentPayload(),
        tree: ErgoTree = oracleTree,
        nftId: ByteArray = oracleNftId,
        boxId: String = "c4".repeat(32),
        transactionId: String = "c5".repeat(32),
    ): ErgoBox = SigmaBridge.box(
        boxValue, tree, tokens(tok(nftId, 1L)),
        SigmaBridge.regs(listOf(t(SigmaBridge.regId(4), bytesC(payload)))),
        transactionId, 0, creationHeight,
    )

    /** An oracle-shaped box carrying a DIFFERENT NFT (payload otherwise valid):
     *  the release path's data-input NFT check must reject it (test 19). */
    val wrongNftBox: ErgoBox = SigmaBridge.box(
        boxValue, oracleTree, tokens(tok(wrongNftId, 1L)),
        SigmaBridge.regs(listOf(t(SigmaBridge.regId(4), bytesC(paymentPayload())))),
        "ce".repeat(32), 0, creationHeight,
    )

    /** A box carrying the oracle NFT under a foreign script (the seller's P2PK, not
     *  oracle.es) with a valid R4 payload: as a DATA INPUT the foreign script never
     *  executes, so the vault's NFT check alone accepts it — NFT custody is the
     *  whole phase-1 trust root (see test 20). */
    val foreignOracleBox: ErgoBox = SigmaBridge.box(
        boxValue, sellerTree, tokens(tok(oracleNftId, 1L)),
        SigmaBridge.regs(listOf(t(SigmaBridge.regId(4), bytesC(paymentPayload())))),
        "cf".repeat(32), 0, creationHeight,
    )

    fun provenBox(proofHeight: Int, recordId: ByteArray = ByteArray(32) { 7 }): ErgoBox = SigmaBridge.box(
        boxValue, provenTree, tokens(tok(useTokenId, dealAmount)), provenRegs(proofHeight, recordId),
        "ef".repeat(32), 0, proofHeight,
    )

    // --- outputs ---

    fun sellerOut(amount: Long = dealAmount): ErgoBoxCandidate =
        SigmaBridge.candidate(boxValue, sellerTree, creationHeight, tokens(tok(useTokenId, amount)), SigmaBridge.emptyRegs())

    fun buyerOut(amount: Long = dealAmount): ErgoBoxCandidate =
        SigmaBridge.candidate(boxValue, buyerTree, creationHeight, tokens(tok(useTokenId, amount)), SigmaBridge.emptyRegs())

    /** Plain change output to the seller (nominal token amount, so tokens(0) evaluates). */
    fun changeOut(): ErgoBoxCandidate =
        SigmaBridge.candidate(boxValue, sellerTree, creationHeight, tokens(tok(useTokenId, 1)), SigmaBridge.emptyRegs())

    fun provenOut(proofHeight: Int, recordId: ByteArray, amount: Long = dealAmount): ErgoBoxCandidate =
        SigmaBridge.candidate(boxValue, provenTree, creationHeight, tokens(tok(useTokenId, amount)), provenRegs(proofHeight, recordId))

    /** Rotation target for the oracle box: same script, NFT preserved, value >=. */
    fun oracleOut(value: Long = boxValue, amount: Long = 1L): ErgoBoxCandidate =
        SigmaBridge.candidate(value, oracleTree, creationHeight, tokens(tok(oracleNftId, amount)), SigmaBridge.emptyRegs())

    // --- context variables ---

    /** record bytes 48..52 as seconds since epoch, in millis (matches the contract's ts context var). */
    fun msgTsMillis(msg: ByteArray): Long {
        var sec = 0L
        for (i in 0..3) sec = (sec shl 8) or (msg[48 + i].toLong() and 0xff)
        return sec * 1000
    }

    /**
     * Copies of an otherwise-honest message / response with one defect injected:
     * [flippedByte] flips one bit at [index] (default sits in the fiat-amount region,
     * outside the dealId/timestamp fields the other checks bind to).
     */
    fun flippedByte(msg: ByteArray, index: Int = 40): ByteArray =
        msg.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }

    /**
     * Handoff-record context vars for path B (vars 0..3): the 52-byte record at 0,
     * the seller's Schnorr signature at 1..2, the record timestamp in millis at 3.
     * Pass [sig] (e.g. from a SignedRecord) to keep the carried (a, z) pair
     * identical to the one an id was computed from; by default the signature is
     * produced here under [signer]. [aOverride]/[zOverride] let a test pair honest
     * signature material with tampered context-var bytes; [tsMsOverride] decouples
     * the Long var from the timestamp embedded in [record] to probe the slice binding.
     */
    fun handoffVars(
        record: ByteArray,
        signer: DLogProtocol.DLogProverInput = sellerKey,
        pub: ByteArray = sellerPk,
        sig: Schnorr.Signature? = null,
        aOverride: ByteArray? = null,
        zOverride: ByteArray? = null,
        tsMsOverride: Long? = null,
    ): Map<Int, EvaluatedValue<out SType>> {
        val s = sig ?: Schnorr.sign(signer.w(), record, pub)
        return mapOf(
            0 to bytesC(record),
            1 to bytesC(aOverride ?: s.a),
            2 to bytesC(zOverride ?: s.z),
            3 to SigmaBridge.longVal(tsMsOverride ?: msgTsMillis(record)) as EvaluatedValue<out SType>,
        )
    }

    // --- prove/verify driver ---

    /** Runs the full prover/interpreter roundtrip for a spend of [selfBox]; returns acceptance. */
    fun verifySpend(
        tree: ErgoTree,
        selfBox: ErgoBox,
        inputs: List<ErgoBox>,
        dataInputs: List<ErgoBox> = emptyList(),
        outputs: List<ErgoBoxCandidate>,
        height: Int,
        timestampMs: Long = NOW_MS,
        vars: Map<Int, EvaluatedValue<out SType>> = emptyMap(),
        secrets: List<SigmaProtocolPrivateInput<*>> = emptyList(),
    ): Boolean {
        val ext = SigmaBridge.contextExtension(vars)
        val extIndex = inputs.indexOf(selfBox)
        val tx = SigmaBridge.unsignedTxWithExt(inputs, dataInputs, outputs, ext, extIndex)
        val miner = SigmaBridge.groupElement(SigmaBridge.ecp(sellerKey))
        val preHeader = TestPreHeader(timestampMs, height, miner)
        val ctx = SigmaBridge.contextWithPreHeader(
            preHeader,
            SigmaBridge.indexedSeq(inputs), SigmaBridge.indexedSeq(dataInputs),
            tx, 0, SigmaBridge.contextExtension(vars), ACTIVATED_VERSION,
        )
        val msg = tx.messageToSign()
        // A spend whose script throws during reduction (e.g. required context variables
        // missing on every viable path) is simply rejected, not an error here.
        val pr0 = try {
            if (secrets.isEmpty()) {
                SigmaBridge.proverResultEmpty()
            } else {
                SigmaBridge.prove(TestProver(secrets), tree, ctx, msg).get()
            }
        } catch (e: Throwable) {
            if (System.getProperty("p2pgate.debug") != null) {
                println("DEBUG prove/reduce failure: ${e.message}")
            }
            return false
        }
        val pr = sigma.interpreter.ProverResult(pr0.proof(), ext)
        val ok = SigmaBridge.verify(tree, ctx, pr, msg)
        if (!ok && System.getProperty("p2pgate.debug") != null) {
            println("DEBUG verify false: ${SigmaBridge.verifyDetail(tree, ctx, pr, msg)}; proofBytes=${pr.proof().size}")
        }
        return ok
    }

    companion object {
        const val NOW_MS: Long = 1_700_000_000_000L
        const val ACTIVATED_VERSION: Byte = 1
        const val PROOF_HEIGHT: Int = 1500
    }
}
