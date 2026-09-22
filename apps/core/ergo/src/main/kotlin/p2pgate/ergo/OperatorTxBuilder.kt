package p2pgate.ergo

import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import p2pgate.dealprotocol.DealTerms
import sigma.ast.ErgoTree

/**
 * Builds the four vault transactions only the operator backend ever
 * constructs (`specs/operator-backend.md` §2 "vault manager"):
 *
 *  - [buildFund] — create the FUNDED box (R4–R8 per `specs/vault-contract.md`
 *    §3.1), collateral in, deal parameters pinned;
 *  - [buildReclaim] — path A: `HEIGHT > timeoutHeight` (R8), seller-signed,
 *    seller paid in full;
 *  - [buildRelease] — path C from the FUNDED box: the oracle box as a DATA
 *    INPUT (NFT pinned in R7), its R4 carrying this vault's dealId — the
 *    oracle's per-deal attestation signal; no oracle signature anywhere
 *    (v2: the phase-1 oracle's attestation alone releases the vault);
 *  - [buildContest] — path C′: the same from the PAYMENT_PROVEN box (the
 *    oracle NFT id is the compile-time pin of the proven tree, §8.3 item 3).
 *
 * Same style as [ClaimTxBuilder]: plain-JVM [ChainBox] inputs, a signer
 * callback ([DealTxSigner]), offline assembly via [TxAssembly]. Every
 * successful build is prover-verified against the compiled scripts by the
 * suite's offline prover.
 *
 * ## The release's oracle data input
 *
 * The vault authenticates the release by NFT presence on
 * `CONTEXT.dataInputs(0)` and checks that box's R4 against its own R4 dealId.
 * A data input's script never executes, so release/contest txs are
 * operator-wallet-only — the oracle does NOT co-sign (the oracle's
 * involvement is posting the attestation box on-chain in the first place,
 * via its own `oracle.es` rotation spend). The seller payout therefore
 * sits at `OUTPUTS(0)`, sharing the slot convention with every other path.
 */
class OperatorTxBuilder(
    /** Compiled vault parameter set the boxes are expected to carry. */
    private val trees: ErgoContracts.VaultTrees,
    /** Miner fee, nanoERG (default 0.001 ERG — the protocol minimum). */
    private val minerFeeNanoErg: Long = 1_000_000L,
    /** Change below this is rejected (dust protection); exact-zero change is allowed. */
    private val minChangeNanoErg: Long = 1_000_000L,
    /** Default ERG value of the FUNDED box the fund tx creates. */
    private val fundedBoxValueNanoErg: Long = 1_000_000L,
) {
    private val networkType: NetworkType = trees.networkType

    init {
        require(minerFeeNanoErg >= ClaimTxBuilder.MIN_MINER_FEE_NANO_ERG) {
            "miner fee $minerFeeNanoErg below protocol minimum ${ClaimTxBuilder.MIN_MINER_FEE_NANO_ERG}"
        }
    }

    /**
     * Fund: creates the FUNDED box for [dealTerms]; the deal amount of
     * [collateralTokenId] (USE) is drawn from [fundingInputs] (surplus
     * collateral rides the change output). The box's R8 pins the plain `Long`
     * `timeoutHeight`; R7 is the 32-byte `oracleNftId`.
     */
    fun buildFund(
        dealTerms: DealTerms,
        collateralTokenId: ByteArray,
        timeoutHeight: Int,
        fundingInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
        signer: DealTxSigner,
        boxValueNanoErg: Long = fundedBoxValueNanoErg,
    ): SignedTransaction {
        require(fundingInputs.isNotEmpty()) { "at least one funding input is required" }
        require(collateralTokenId.size == 32) { "collateralTokenId must be 32 bytes, got ${collateralTokenId.size}" }
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

        // R7: the 32-byte oracleNftId (release paths only — path B verifies the
        // handoff record under R5's seller key and does not read R7).
        val r7 = trees.oracleNftId

        val fundedCandidate = TxAssembly.candidate(
            value = boxValueNanoErg,
            tree = trees.fundedTree,
            tokens = listOf(ChainToken(collateralTokenHex, dealTerms.amount)),
            registers = listOf(
                4 to ErgoValues.collBytesConstant(dealId),
                5 to ErgoValues.collBytesConstant(dealTerms.sellerPubKey),
                6 to ErgoValues.collBytesConstant(dealTerms.buyerPubKey),
                7 to ErgoValues.collBytesConstant(r7),
                8 to ErgoValues.longConstant(timeoutHeight.toLong()),
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
     * (R8), paying the full collateral to the seller's R5 deal key (the
     * contract pins OUTPUTS(0) to `proveDlog(sellerPubKey)`). The seller signs.
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
        val timeoutHeight = fundedBox.registerLong(8)?.toInt()
            ?: throw IllegalArgumentException("FUNDED box has no R8 timeoutHeight")
        require(fundedBox.tokens.isNotEmpty()) { "FUNDED box carries no collateral tokens" }
        require(currentHeight > timeoutHeight) {
            "cannot reclaim before timeout: height $currentHeight <= timeoutHeight $timeoutHeight"
        }

        val useToken = fundedBox.tokens.first()
        val sellerAmount = useToken.amount
        require(sellerAmount > 0) {
            "collateral $sellerAmount — the reclaim tx would carry a zero-amount token"
        }

        // The contract pays the R5 seller key — reclaim is seller-signed.
        val sellerTree = ErgoValues.p2pkTree(sellerPk)
        val candidates = listOf(
            TxAssembly.candidate(
                value = fundedBox.value,
                tree = sellerTree,
                tokens = listOf(ChainToken(useToken.tokenId, sellerAmount)),
                registers = emptyList(),
                creationHeight = currentHeight,
            ),
        )

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
     * Release (path C) from the FUNDED box: [oracleDataInput] is the oracle
     * singleton box attached as a read-only data input — it must carry the
     * NFT pinned in the box's R7 and this vault's dealId in its R4 (build-time
     * mirror checks fail fast, exactly the in-script conditions). No oracle
     * co-signature: the tx is [signer]-signed (operator wallet) and pays the
     * full collateral to the seller's R5 key at OUTPUTS(0).
     */
    fun buildRelease(
        fundedBox: ChainBox,
        oracleDataInput: ChainBox,
        feeInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
        signer: DealTxSigner,
    ): SignedTransaction {
        require(fundedBox.ergoTreeHex.equals(trees.fundedPropositionHex, ignoreCase = true)) {
            "input box is not a FUNDED vault box of this contract"
        }
        val r7 = fundedBox.registerBytes(7) ?: throw IllegalArgumentException("FUNDED box has no R7 oracleNftId")
        require(r7.size == 32) { "FUNDED R7 must be the 32-byte oracleNftId, got ${r7.size}" }
        val nftPin = r7
        return buildOracleRelease(
            vaultBox = fundedBox,
            vaultTree = trees.fundedTree,
            oracleDataInput = oracleDataInput,
            feeInputs = feeInputs,
            currentHeight = currentHeight,
            changeAddress = changeAddress,
            nftPin = nftPin,
            signer = signer,
        )
    }

    /**
     * Contest (path C′) from the PAYMENT_PROVEN box: same gate as
     * [buildRelease] — the oracle attestation alone counters any claim. The
     * oracle NFT id is the compile-time pin of the proven tree (§8.3 item 3).
     */
    fun buildContest(
        provenBox: ChainBox,
        oracleDataInput: ChainBox,
        feeInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
        signer: DealTxSigner,
    ): SignedTransaction {
        require(provenBox.ergoTreeHex.equals(trees.provenPropositionHex, ignoreCase = true)) {
            "input box is not a PAYMENT_PROVEN vault box of this contract"
        }
        return buildOracleRelease(
            vaultBox = provenBox,
            vaultTree = trees.provenTree,
            oracleDataInput = oracleDataInput,
            feeInputs = feeInputs,
            currentHeight = currentHeight,
            changeAddress = changeAddress,
            nftPin = trees.oracleNftId,
            signer = signer,
        )
    }

    // ---------------------------------------------------------------- oracle release (paths C/C′)

    private fun buildOracleRelease(
        vaultBox: ChainBox,
        vaultTree: ErgoTree,
        oracleDataInput: ChainBox,
        feeInputs: List<ChainBox>,
        currentHeight: Int,
        changeAddress: String,
        nftPin: ByteArray,
        signer: DealTxSigner,
    ): SignedTransaction {
        require(feeInputs.isNotEmpty()) { "at least one fee input is required" }

        val dealId = vaultBox.registerBytes(4) ?: throw IllegalArgumentException("vault box has no R4 dealId")
        val sellerPk = vaultBox.registerBytes(5) ?: throw IllegalArgumentException("vault box has no R5 sellerPubKey")
        require(vaultBox.tokens.isNotEmpty()) { "vault box carries no collateral tokens" }

        // Build-time mirror of the in-script release conditions
        // (vault_funded.es path C / vault_payment_proven.es path C′): the data
        // input must carry the pinned NFT and its R4 must hold exactly this
        // vault's dealId — reject before broadcasting what the contract would
        // burn a fee rejecting.
        val nftHex = Base16.encode(nftPin)
        require(oracleDataInput.tokens.any { it.tokenId.equals(nftHex, ignoreCase = true) }) {
            "oracle data input carries no oracle NFT (pinned id mismatch)"
        }
        val payload = oracleDataInput.registerBytes(4)
            ?: throw IllegalArgumentException("oracle data input has no R4 attestation (dealId)")
        require(payload.contentEquals(dealId)) { "oracle data input R4 dealId does not match the vault's R4" }

        val useToken = vaultBox.tokens.first()
        val sellerAmount = useToken.amount
        require(sellerAmount > 0) {
            "collateral $sellerAmount — the release tx would carry a zero-amount token"
        }

        // OUTPUTS(0): seller payout — the contract pins R5's key and the full collateral.
        val candidates = listOf(
            TxAssembly.candidate(
                value = vaultBox.value,
                tree = ErgoValues.p2pkTree(sellerPk),
                tokens = listOf(ChainToken(useToken.tokenId, sellerAmount)),
                registers = emptyList(),
                creationHeight = currentHeight,
            ),
        )

        val inputs = listOf(TxAssembly.toErgoBox(vaultBox, vaultTree)) +
            feeInputs.map { TxAssembly.toErgoBox(it, TxAssembly.decodeTree(it)) }
        return TxAssembly.assemble(
            inputs = inputs,
            dataInputs = listOf(TxAssembly.toErgoBox(oracleDataInput, TxAssembly.decodeTree(oracleDataInput))),
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
}
