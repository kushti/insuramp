package p2pgate.ergo

import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.UnsignedTransaction
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.HandoffRecord
import sigma.ast.ErgoTree

/**
 * The signer seam for the two user-side transactions: the app supplies whatever
 * holds the deal key (Android Keystore-backed prover in production, a test
 * prover in the suite) — the builder never sees private key material.
 */
fun interface DealTxSigner {
    fun sign(tx: UnsignedTransaction): SignedTransaction
}

/**
 * Builds the only two transactions the user app ever constructs
 * (`specs/android-app.md` §4.3), both spends of vault boxes:
 *
 *  - [buildClaimOpen] — vault path B: spends the FUNDED box carrying the
 *    courier-signed handoff record as context vars 0–3, output 0 the
 *    PAYMENT_PROVEN box (registers copied, R7 `(proofHeight << 32) | feeBps`,
 *    R8 = `blake2b256(a ‖ z ‖ record)`);
 *  - [buildClaimPayout] — vault path D: spends the PAYMENT_PROVEN box after
 *    maturation, paying `collateral − fee` to the user's payout address plus
 *    the §6 treasury fee output when `feeBps > 0`.
 *
 * Miner fees are funded from app-selected fee inputs (`ChainSource.getUnspentBoxes`);
 * change returns to the deal-key address. Transactions are built offline via
 * [TxAssembly] and signed by the supplied [DealTxSigner] — the offline prover
 * run against the real compiled scripts is what proves a build satisfies the
 * contracts (see the suite).
 */
class ClaimTxBuilder(
    /** Compiled vault parameter set the boxes are expected to carry. */
    private val trees: ErgoContracts.VaultTrees,
    /** The treasury proposition whose `blake2b256` equals the compiled `TREASURY_SCRIPT_HASH` (§6). */
    private val treasuryTree: ErgoTree,
    /** Miner fee, nanoERG (default 0.001 ERG — the protocol minimum). */
    private val minerFeeNanoErg: Long = 1_000_000L,
    /** ERG value of the treasury fee output when feeBps > 0. */
    private val feeBoxValueNanoErg: Long = 100_000L,
    /** Change below this is rejected (dust protection); exact-zero change is allowed. */
    private val minChangeNanoErg: Long = 1_000_000L,
    /**
     * Claim-maturation pre-check override — must equal the
     * `CLAIM_MATURATION_BLOCKS` constant the [trees] were compiled with.
     * Defaults to the canonical value; [ErgoContracts.compileFast] sets 3.
     */
    private val claimMaturationBlocks: Int = ContractParams.CLAIM_MATURATION_BLOCKS,
    /**
     * Handoff-record freshness pre-check override — must equal the
     * `HANDOFF_RECORD_MAX_AGE_MS` constant the [trees] were compiled with.
     * Defaults to the canonical value; [ErgoContracts.compileFast] sets 600 000.
     */
    private val handoffRecordMaxAgeMs: Long = ContractParams.HANDOFF_RECORD_MAX_AGE_MS,
) {
    private val networkType: NetworkType = trees.networkType

    /** §6 fee split for a payout: [fee] to the treasury, [userPayout] to the user. */
    data class FeeBreakdown(val fee: Long, val userPayout: Long)

    /**
     * The §6 fee math, exposed for display and tests:
     * `fee = collateral * feeBps / 10000` (rounding down), the user's payout is
     * the remainder.
     */
    fun feeBreakdown(collateral: Long, feeBps: Int): FeeBreakdown {
        require(collateral > 0) { "collateral must be positive, got $collateral" }
        require(feeBps in 0..10_000) { "feeBps must be in 0..10000, got $feeBps" }
        val fee = collateral * feeBps / ContractParams.FEE_DENOMINATOR
        return FeeBreakdown(fee = fee, userPayout = collateral - fee)
    }

    init {
        require(minerFeeNanoErg >= MIN_MINER_FEE_NANO_ERG) {
            "miner fee $minerFeeNanoErg below protocol minimum $MIN_MINER_FEE_NANO_ERG"
        }
        require(feeBoxValueNanoErg > 0) { "fee box value must be positive" }
        val treasuryHash = SchnorrVerifier.blake2b256(treasuryTree.bytes())
        require(treasuryHash.contentEquals(trees.treasuryScriptHash)) {
            "treasuryTree hash does not match the compiled TREASURY_SCRIPT_HASH — fee outputs would be rejected"
        }
    }

    /**
     * Claim-open (path B). [record]/[a]/[z] are the verified courier-signed
     * handoff record (see [CourierRecordVerifier]); [currentHeight] is the
     * chain height the tx is built against (becomes the PAYMENT_PROVEN
     * box's `proofHeight`); [txTimestampMs] the tx timestamp the freshness
     * check runs against.
     */
    fun buildClaimOpen(
        fundedBox: ChainBox,
        feeInputs: List<ChainBox>,
        record: HandoffRecord,
        a: ByteArray,
        z: ByteArray,
        currentHeight: Int,
        txTimestampMs: Long,
        changeAddress: String,
        signer: DealTxSigner,
    ): SignedTransaction {
        require(fundedBox.ergoTreeHex.equals(trees.fundedPropositionHex, ignoreCase = true)) {
            "input box is not a FUNDED vault box of this contract"
        }
        require(feeInputs.isNotEmpty()) { "at least one fee input is required" }
        require(a.size == 33 && z.size == 32) { "courier Schnorr half must be (33, 32) bytes" }

        val recordBytes = record.encode()
        val dealId = fundedBox.registerBytes(4) ?: throw IllegalArgumentException("FUNDED box has no R4 dealId")
        require(record.dealId.contentEquals(dealId)) { "record dealId does not match the vault's R4" }
        val sellerPk = fundedBox.registerBytes(5) ?: throw IllegalArgumentException("FUNDED box has no R5 sellerPubKey")
        val userPk = fundedBox.registerBytes(6) ?: throw IllegalArgumentException("FUNDED box has no R6 userPubKey")
        fundedBox.registerBytes(7) ?: throw IllegalArgumentException("FUNDED box has no R7 oracleNftId||courierPubKey")
        val packed8 = fundedBox.registerLong(8) ?: throw IllegalArgumentException("FUNDED box has no R8 timeoutHeight|feeBps")
        val feeBps = (packed8 and 0xFFFF_FFFFL).toInt()
        val r9 = fundedBox.registerBytes(9) ?: throw IllegalArgumentException("FUNDED box has no R9 funding binding")
        require(fundedBox.tokens.isNotEmpty()) { "FUNDED box carries no collateral tokens" }

        // Pre-check the freshness window the contract enforces in-script against
        // CONTEXT.preHeader.timestamp, so the app fails before broadcasting.
        val tsMs = record.timestamp * 1000L
        require(tsMs <= txTimestampMs) { "record timestamp is in the future" }
        require(tsMs > txTimestampMs - handoffRecordMaxAgeMs) {
            "record is stale: timestamp outside HANDOFF_RECORD_MAX_AGE"
        }

        val recordId = SchnorrVerifier.blake2b256(a, z, recordBytes)

        // Output 0: the PAYMENT_PROVEN box carrying ALL tokens and ERG,
        // registers copied with R7 = (proofHeight << 32) | feeBps and R8 = record id.
        val provenCandidate = TxAssembly.candidate(
            value = fundedBox.value,
            tree = trees.provenTree,
            tokens = fundedBox.tokens,
            registers = listOf(
                4 to ErgoValues.collBytesConstant(dealId),
                5 to ErgoValues.collBytesConstant(sellerPk),
                6 to ErgoValues.collBytesConstant(userPk),
                7 to ErgoValues.longConstant(TxAssembly.packInts(currentHeight, feeBps)),
                8 to ErgoValues.collBytesConstant(recordId),
                9 to ErgoValues.collBytesConstant(r9),
            ),
            creationHeight = currentHeight,
        )

        val contextVars = mapOf(
            0 to ErgoValues.collBytesConstant(recordBytes),
            1 to ErgoValues.collBytesConstant(a),
            2 to ErgoValues.collBytesConstant(z),
            3 to ErgoValues.longConstant(tsMs),
        )

        val inputs = listOf(TxAssembly.toErgoBox(fundedBox, trees.fundedTree)) + feeInputs.map { TxAssembly.toErgoBox(it, TxAssembly.decodeTree(it)) }
        return TxAssembly.assemble(
            inputs = inputs,
            contextVars = contextVars,
            contextVarInputIndex = 0,
            candidates = listOf(provenCandidate),
            minerFeeNanoErg = minerFeeNanoErg,
            minChangeNanoErg = minChangeNanoErg,
            currentHeight = currentHeight,
            txTimestampMs = txTimestampMs,
            changeAddress = changeAddress,
            networkType = networkType,
            signer = signer,
        )
    }

    /**
     * Claim payout (path D). Spends the PAYMENT_PROVEN box once
     * `HEIGHT > proofHeight + CLAIM_MATURATION`; output 0 pays
     * `collateral − fee` to [userPayoutAddress], plus the treasury fee output
     * per §6 when `feeBps > 0`.
     */
    fun buildClaimPayout(
        provenBox: ChainBox,
        feeInputs: List<ChainBox>,
        currentHeight: Int,
        userPayoutAddress: String,
        changeAddress: String,
        signer: DealTxSigner,
    ): SignedTransaction {
        require(provenBox.ergoTreeHex.equals(trees.provenPropositionHex, ignoreCase = true)) {
            "input box is not a PAYMENT_PROVEN vault box of this contract"
        }
        require(feeInputs.isNotEmpty()) { "at least one fee input is required" }

        val dealId = provenBox.registerBytes(4) ?: throw IllegalArgumentException("PAYMENT_PROVEN box has no R4 dealId")
        val userPk = provenBox.registerBytes(6) ?: throw IllegalArgumentException("PAYMENT_PROVEN box has no R6 userPubKey")
        val packed7 = provenBox.registerLong(7) ?: throw IllegalArgumentException("PAYMENT_PROVEN box has no R7 proofHeight|feeBps")
        val proofHeight = (packed7 ushr 32).toInt()
        val feeBps = (packed7 and 0xFFFF_FFFFL).toInt()
        require(provenBox.tokens.isNotEmpty()) { "PAYMENT_PROVEN box carries no collateral tokens" }
        require(currentHeight > proofHeight + claimMaturationBlocks) {
            "claim has not matured: height $currentHeight <= proofHeight $proofHeight + $claimMaturationBlocks"
        }

        val useToken = provenBox.tokens.first()
        val collateral = useToken.amount
        val breakdown = feeBreakdown(collateral, feeBps)
        require(breakdown.userPayout > 0) {
            "feeBps $feeBps leaves no user payout (collateral $collateral, fee ${breakdown.fee}) — the payout tx would carry a zero-amount token"
        }
        val fee = breakdown.fee
        val userAmount = breakdown.userPayout

        val userTree = payoutTree(userPayoutAddress)
        val candidates = mutableListOf(
            TxAssembly.candidate(
                value = provenBox.value,
                tree = userTree,
                tokens = listOf(ChainToken(useToken.tokenId, userAmount)),
                registers = emptyList(),
                creationHeight = currentHeight,
            ),
        )
        if (fee > 0) {
            candidates += TxAssembly.candidate(
                value = feeBoxValueNanoErg,
                tree = treasuryTree,
                tokens = listOf(ChainToken(useToken.tokenId, fee)),
                registers = emptyList(),
                creationHeight = currentHeight,
            )
        }

        val inputs = listOf(TxAssembly.toErgoBox(provenBox, trees.provenTree)) + feeInputs.map { TxAssembly.toErgoBox(it, TxAssembly.decodeTree(it)) }
        return TxAssembly.assemble(
            inputs = inputs,
            contextVars = emptyMap(),
            contextVarInputIndex = 0,
            candidates = candidates,
            minerFeeNanoErg = minerFeeNanoErg,
            minChangeNanoErg = minChangeNanoErg,
            currentHeight = currentHeight,
            txTimestampMs = null,
            changeAddress = changeAddress,
            networkType = networkType,
            signer = signer,
        )
    }

    private fun payoutTree(address: String): ErgoTree = org.ergoplatform.appkit.Address.create(address).ergoAddress.script()

    companion object {
        /** Protocol minimum miner fee: 0.001 ERG. */
        const val MIN_MINER_FEE_NANO_ERG: Long = 1_000_000L
    }
}
