package p2pgate.ergo

import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.DealTerms
import scala.Tuple2
import sigma.ast.ErgoTree
import sigma.ast.EvaluatedValue
import sigma.ast.SType
import sigma.serialization.ValueSerializer

/**
 * Builds the four vault transactions only the operator backend ever
 * constructs (`specs/operator-backend.md` §2 "vault manager"):
 *
 *  - [buildFund] — create the FUNDED box (R4–R9 per `specs/vault-contract.md`
 *    §3.1), collateral in, deal parameters pinned;
 *  - [buildReclaim] — path A: `HEIGHT > timeoutHeight` (R8), seller-signed,
 *    seller paid minus fee (§6 treasury fee output when `feeBps > 0`);
 *  - [buildRelease] — path C from the FUNDED box: the oracle box as a full
 *    input (NFT pinned in R7 bytes 0..32), the 112-byte attestation as
 *    context var 0, digest field checks vs R4/R9 in-script — nothing else (v2:
 *    the phase-1 oracle's attestation alone releases the vault);
 *  - [buildContest] — path C′: the same from the PAYMENT_PROVEN box (the
 *    oracle NFT id is the compile-time pin of the proven tree, §8.3 item 3).
 *
 * Same style as [ClaimTxBuilder]: plain-JVM [ChainBox] inputs, a signer
 * callback ([DealTxSigner] / [OracleSigner]), offline assembly via
 * [TxAssembly]. Every successful build is prover-verified against the compiled
 * scripts by the suite's offline prover.
 *
 * ## The release's oracle input
 *
 * The vault authenticates the release by NFT presence among the inputs; the
 * input's own script must validate in the same transaction. The
 * `oracle.es`-governed box is that input: its self-reproduction is pinned at
 * `OUTPUTS(0)` (same tree, same NFT id + amount, value ≥ input value), so on
 * joint release spends the vault pays the seller at `OUTPUTS(1)` (v2,
 * 2026-09-17 — see `specs/vault-contract.md` §8.4).
 */
class OperatorTxBuilder(
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
    /** Default ERG value of the FUNDED box the fund tx creates. */
    private val fundedBoxValueNanoErg: Long = 1_000_000L,
) {
    private val networkType: NetworkType = trees.networkType

    /** The parsed R9 funding binding (`specs/vault-contract.md` §3.1, 31 B). */
    private data class FundingBinding(val chainId: Int, val tokenId: Int, val recipient: ByteArray, val amount: Long)

    init {
        require(minerFeeNanoErg >= ClaimTxBuilder.MIN_MINER_FEE_NANO_ERG) {
            "miner fee $minerFeeNanoErg below protocol minimum ${ClaimTxBuilder.MIN_MINER_FEE_NANO_ERG}"
        }
        require(feeBoxValueNanoErg > 0) { "fee box value must be positive" }
        val treasuryHash = SchnorrVerifier.blake2b256(treasuryTree.bytes())
        require(treasuryHash.contentEquals(trees.treasuryScriptHash)) {
            "treasuryTree hash does not match the compiled TREASURY_SCRIPT_HASH — fee outputs would be rejected"
        }
    }

    /**
     * Fund: creates the FUNDED box for [dealTerms]. [recipientAddr] is the
     * user's raw USDT address payload (padded per chain into R9); the deal
     * amount of [collateralTokenId] (USE) is drawn from [fundingInputs]
     * (surplus collateral rides the change output). The box's R8 pins
     * `(timeoutHeight << 32) | feeBps`; R7 packs `oracleNftId ‖ courierPubKey`.
     */
    fun buildFund(
        dealTerms: DealTerms,
        recipientAddr: ByteArray,
        collateralTokenId: ByteArray,
        timeoutHeight: Int,
        feeBps: Int,
        fundingInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
        signer: DealTxSigner,
        boxValueNanoErg: Long = fundedBoxValueNanoErg,
    ): SignedTransaction {
        require(fundingInputs.isNotEmpty()) { "at least one funding input is required" }
        require(collateralTokenId.size == 32) { "collateralTokenId must be 32 bytes, got ${collateralTokenId.size}" }
        require(feeBps in 0..10_000) { "feeBps must be in 0..10000, got $feeBps" }
        require(timeoutHeight > currentHeight) {
            "timeoutHeight $timeoutHeight is not in the future of currentHeight $currentHeight"
        }
        require(boxValueNanoErg > 0) { "box value must be positive" }

        val dealId = dealTerms.dealId // validates the terms via encode()
        val collateralTokenHex = Base16.encode(collateralTokenId)
        val available = fundingInputs.sumOf { it.tokenAmount(collateralTokenHex) }
        require(available >= dealTerms.amount) {
            "funding inputs carry $available of collateral, deal requires ${dealTerms.amount}"
        }
        val surplus = available - dealTerms.amount

        val paddedRecipient = PaymentAttestation.padRecipient(recipientAddr, dealTerms.srcChainId)
        val r9 = PaymentAttestation.fundingBinding(dealTerms.srcChainId, dealTerms.asset, paddedRecipient, dealTerms.amount)

        // R7: 65 B packed oracleNftId(32) | courierPubKey(33) — no R10 exists.
        val r7 = trees.oracleNftId + dealTerms.courierPubKey

        val fundedCandidate = TxAssembly.candidate(
            value = boxValueNanoErg,
            tree = trees.fundedTree,
            tokens = listOf(ChainToken(collateralTokenHex, dealTerms.amount)),
            registers = listOf(
                4 to ErgoValues.collBytesConstant(dealId),
                5 to ErgoValues.collBytesConstant(dealTerms.sellerPubKey),
                6 to ErgoValues.collBytesConstant(dealTerms.userPubKey),
                7 to ErgoValues.collBytesConstant(r7),
                8 to ErgoValues.longConstant(TxAssembly.packInts(timeoutHeight, feeBps)),
                9 to ErgoValues.collBytesConstant(r9),
            ),
            creationHeight = currentHeight,
        )

        val inputs = fundingInputs.map { TxAssembly.toErgoBox(it, TxAssembly.decodeTree(it)) }
        return TxAssembly.assemble(
            inputs = inputs,
            contextVars = emptyMap(),
            contextVarInputIndex = 0,
            candidates = listOf(fundedCandidate),
            changeTokens = if (surplus > 0) listOf(ChainToken(collateralTokenHex, surplus)) else emptyList(),
            minerFeeNanoErg = minerFeeNanoErg,
            minChangeNanoErg = minChangeNanoErg,
            currentHeight = currentHeight,
            txTimestampMs = null,
            changeAddress = changeAddress,
            networkType = networkType,
            signer = signer,
        )
    }

    /**
     * Reclaim (path A): spends the FUNDED box once `HEIGHT > timeoutHeight`
     * (R8), paying `collateral − fee` to the seller's R5 deal key (the contract
     * pins OUTPUTS(0) to `proveDlog(sellerPubKey)`) plus the §6 treasury fee
     * output when `feeBps > 0`. The seller signs.
     */
    fun buildReclaim(
        fundedBox: ChainBox,
        feeInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
        signer: DealTxSigner,
    ): SignedTransaction {
        require(fundedBox.ergoTreeHex.equals(trees.fundedPropositionHex, ignoreCase = true)) {
            "input box is not a FUNDED vault box of this contract"
        }
        require(feeInputs.isNotEmpty()) { "at least one fee input is required" }

        fundedBox.registerBytes(4) ?: throw IllegalArgumentException("FUNDED box has no R4 dealId")
        val sellerPk = fundedBox.registerBytes(5) ?: throw IllegalArgumentException("FUNDED box has no R5 sellerPubKey")
        val packed8 = fundedBox.registerLong(8) ?: throw IllegalArgumentException("FUNDED box has no R8 timeoutHeight|feeBps")
        val timeoutHeight = (packed8 ushr 32).toInt()
        val feeBps = (packed8 and 0xFFFF_FFFFL).toInt()
        require(fundedBox.tokens.isNotEmpty()) { "FUNDED box carries no collateral tokens" }
        require(currentHeight > timeoutHeight) {
            "cannot reclaim before timeout: height $currentHeight <= timeoutHeight $timeoutHeight"
        }

        val useToken = fundedBox.tokens.first()
        val collateral = useToken.amount
        val fee = collateral * feeBps / ContractParams.FEE_DENOMINATOR
        val sellerAmount = collateral - fee
        require(sellerAmount > 0) {
            "feeBps $feeBps leaves no seller payout (collateral $collateral, fee $fee)"
        }

        // The contract pays the R5 seller key — reclaim is seller-signed.
        val sellerTree = ErgoValues.p2pkTree(sellerPk)
        val candidates = mutableListOf(
            TxAssembly.candidate(
                value = fundedBox.value,
                tree = sellerTree,
                tokens = listOf(ChainToken(useToken.tokenId, sellerAmount)),
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

        val inputs = listOf(TxAssembly.toErgoBox(fundedBox, trees.fundedTree)) + feeInputs.map { TxAssembly.toErgoBox(it, TxAssembly.decodeTree(it)) }
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

    /**
     * Release (path C) from the FUNDED box: the oracle box as a full input
     * (NFT == R7 bytes 0..32), the 112-byte [attestation] as context var 0.
     * The oracle co-signs via [oracle]; the tx pays `collateral − fee` to the
     * seller's R5 key plus the §6 fee output, and recreates the oracle input
     * box (see the class doc).
     */
    fun buildRelease(
        fundedBox: ChainBox,
        oracle: OracleSigner,
        attestation: PaymentAttestation,
        feeInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
    ): SignedTransaction {
        require(fundedBox.ergoTreeHex.equals(trees.fundedPropositionHex, ignoreCase = true)) {
            "input box is not a FUNDED vault box of this contract"
        }
        val r7 = fundedBox.registerBytes(7) ?: throw IllegalArgumentException("FUNDED box has no R7 oracleNftId||courierPubKey")
        require(r7.size == 65) { "FUNDED R7 must be the 65-byte packed oracleNftId||courierPubKey, got ${r7.size}" }
        val nftPin = r7.copyOfRange(0, 32)
        return buildOracleRelease(
            vaultBox = fundedBox,
            vaultTree = trees.fundedTree,
            oracle = oracle,
            attestation = attestation,
            feeInputs = feeInputs,
            currentHeight = currentHeight,
            changeAddress = changeAddress,
            nftPin = nftPin,
        )
    }

    /**
     * Contest (path C′) from the PAYMENT_PROVEN box: same gate as
     * [buildRelease] — the oracle digest alone counters any claim. The oracle
     * NFT id is the compile-time pin of the proven tree (§8.3 item 3).
     */
    fun buildContest(
        provenBox: ChainBox,
        oracle: OracleSigner,
        attestation: PaymentAttestation,
        feeInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
    ): SignedTransaction {
        require(provenBox.ergoTreeHex.equals(trees.provenPropositionHex, ignoreCase = true)) {
            "input box is not a PAYMENT_PROVEN vault box of this contract"
        }
        return buildOracleRelease(
            vaultBox = provenBox,
            vaultTree = trees.provenTree,
            oracle = oracle,
            attestation = attestation,
            feeInputs = feeInputs,
            currentHeight = currentHeight,
            changeAddress = changeAddress,
            nftPin = trees.oracleNftId,
        )
    }

    // ---------------------------------------------------------------- oracle release (paths C/C′)

    private fun buildOracleRelease(
        vaultBox: ChainBox,
        vaultTree: ErgoTree,
        oracle: OracleSigner,
        attestation: PaymentAttestation,
        feeInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
        nftPin: ByteArray,
    ): SignedTransaction {
        require(feeInputs.isNotEmpty()) { "at least one fee input is required" }

        val dealId = vaultBox.registerBytes(4) ?: throw IllegalArgumentException("vault box has no R4 dealId")
        val sellerPk = vaultBox.registerBytes(5) ?: throw IllegalArgumentException("vault box has no R5 sellerPubKey")
        val packed = vaultBox.registerLong(if (vaultTree == trees.fundedTree) 8 else 7)
            ?: throw IllegalArgumentException("vault box has no packed timeout/proofHeight|feeBps register")
        val feeBps = (packed and 0xFFFF_FFFFL).toInt()
        val r9 = vaultBox.registerBytes(9) ?: throw IllegalArgumentException("vault box has no R9 funding binding")
        require(vaultBox.tokens.isNotEmpty()) { "vault box carries no collateral tokens" }
        val binding = parseFundingBinding(r9)

        // Build-time mirror of the in-script digest field checks (vault_funded.es
        // path C / vault_payment_proven.es path C′ fieldsOk): reject before
        // broadcasting what the contract would burn a fee rejecting.
        require(attestation.dealId.contentEquals(dealId)) { "attestation dealId does not match the vault's R4" }
        require(attestation.srcChainId == binding.chainId) {
            "attestation srcChainId 0x%02x does not match the R9 binding 0x%02x".format(attestation.srcChainId, binding.chainId)
        }
        require(attestation.tokenId == binding.tokenId) {
            "attestation tokenId 0x%02x does not match the R9 binding 0x%02x".format(attestation.tokenId, binding.tokenId)
        }
        require(attestation.recipient.contentEquals(binding.recipient)) {
            "attestation recipient does not match the R9 binding's recipientAddr"
        }
        require(attestation.amount == binding.amount) {
            "attestation amount ${attestation.amount} does not match the R9 binding's expectedAmount ${binding.amount}"
        }

        // Oracle authentication: a full input carrying the pinned NFT. This is
        // the in-script oracleOk check, enforced before the oracle co-signs.
        val oracleBox = oracle.oracleInputBox()
        val nftHex = Base16.encode(nftPin)
        require(oracleBox.tokens.any { it.tokenId.equals(nftHex, ignoreCase = true) }) {
            "oracle input box carries no oracle NFT (pinned id mismatch)"
        }

        val useToken = vaultBox.tokens.first()
        val collateral = useToken.amount
        val fee = collateral * feeBps / ContractParams.FEE_DENOMINATOR
        val sellerAmount = collateral - fee
        require(sellerAmount > 0) {
            "feeBps $feeBps leaves no seller payout (collateral $collateral, fee $fee)"
        }

        // OUTPUTS(0): the oracle input recreated — oracle.es pins its reproduction
        // at index 0 (same tree, same tokens, NFT id + amount preserved, value ≥
        // input value).
        val candidates = mutableListOf(
            TxAssembly.candidateRaw(
                value = oracleBox.value,
                tree = TxAssembly.decodeTree(oracleBox),
                tokens = oracleBox.tokens,
                registers = oracleBox.registers.mapIndexedNotNull { i, reg ->
                    if (reg == null) null else Tuple2(
                        ErgoBridge.regId(i + 4),
                        ValueSerializer.deserialize(reg.serialized, 0) as EvaluatedValue<out SType>,
                    )
                },
                creationHeight = currentHeight,
            ),
        )
        // OUTPUTS(1): seller payout — the contract pins R5's key and collateral − fee.
        candidates += TxAssembly.candidate(
            value = vaultBox.value,
            tree = ErgoValues.p2pkTree(sellerPk),
            tokens = listOf(ChainToken(useToken.tokenId, sellerAmount)),
            registers = emptyList(),
            creationHeight = currentHeight,
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

        val contextVars = mapOf(0 to ErgoValues.collBytesConstant(attestation.encode()))
        val inputs = listOf(TxAssembly.toErgoBox(vaultBox, vaultTree), TxAssembly.toErgoBox(oracleBox, TxAssembly.decodeTree(oracleBox))) +
            feeInputs.map { TxAssembly.toErgoBox(it, TxAssembly.decodeTree(it)) }
        return TxAssembly.assemble(
            inputs = inputs,
            contextVars = contextVars,
            contextVarInputIndex = 0,
            candidates = candidates,
            minerFeeNanoErg = minerFeeNanoErg,
            minChangeNanoErg = minChangeNanoErg,
            currentHeight = currentHeight,
            txTimestampMs = null,
            changeAddress = changeAddress,
            networkType = networkType,
            signer = oracle,
        )
    }

    /** Slices the 31-byte R9 funding binding exactly as the contracts do. */
    private fun parseFundingBinding(r9: ByteArray): FundingBinding {
        require(r9.size == 31) { "R9 funding binding must be 31 bytes, got ${r9.size}" }
        return FundingBinding(
            chainId = r9[0].toInt() and 0xff,
            tokenId = r9[1].toInt() and 0xff,
            recipient = r9.copyOfRange(2, 23),
            amount = PaymentAttestation.u64(r9, 23),
        )
    }
}
