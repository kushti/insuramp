package p2pgate.e2e

import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.SignedTransaction
import org.ergoplatform.appkit.impl.OutBoxImpl
import org.ergoplatform.sdk.JavaHelpers
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.Blake2b256
import p2pgate.dealprotocol.DealTerms
import p2pgate.dealprotocol.HandoffRecord
import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainRegister
import p2pgate.ergo.ChainToken
import p2pgate.ergo.ClaimTxBuilder
import p2pgate.ergo.DevOracle
import p2pgate.ergo.ErgoContracts
import p2pgate.ergo.OperatorTxBuilder
import p2pgate.ergo.OracleSigner
import p2pgate.ergo.PaymentAttestation
import sigma.ast.ErgoTree
import java.math.BigInteger

/**
 * The end-to-end gate (milestone M3-C): runs the full on-chain flow
 * against Ergo mainnet (the default target since 2026-09-17; testnet stays
 * selectable via `E2E_EXPLORER_URL`/`E2E_FAUCET_URL`) with fast contracts
 * ([ErgoContracts.compileFast]):
 *
 *  1. preflight (explorer reachable) → exit 2 with manual instructions if not;
 *  2. per-run keypairs (operator/seller, buyer, oracle, treasury);
 *  3. funding of the operator: manual by default (the gate prints the address
 *     + needed nanoERG and polls the balance; exit 2 with instructions when
 *     the `E2E_FUNDING_TIMEOUT_MS` deadline passes), or via the testnet faucet
 *     when `E2E_FAUCET_URL` is set;
 *  4. setup txs: mint the dev oracle NFT (an output under the compiled
 *     `oracle.es` tree carrying the fresh token — the token id IS the mint
 *     tx's first input box id), mint the collateral ("USE") token, fund the
 *     buyer address for claim fees;
 *  5. Flow A (release): fund → seller-signed 52-byte P2PH handoff record →
 *     oracle attestation → release (the oracle.es box as the full oracle
 *     input, recreated per its `OUTPUTS.exists` condition) → assert seller
 *     paid minus fee and the NFT preserved;
 *  6. Flow B (dispute): fund → record → claim-open → wait maturation (3
 *     blocks, fast) → claim-payout → assert buyer payout minus fee;
 *  7. Flow C (reclaim): fund → wait the short timeout (6 blocks) → reclaim →
 *     assert seller refund.
 *
 * Every vault tx is prover-verified against the compiled scripts by the
 * builders themselves; the on-chain assertions then check the spend outputs.
 * All steps are logged; the run ends with a JSON summary and exit 0. In
 * `--dry-run` mode every tx of all three flows is built (and prover-verified)
 * against current chain state but nothing is broadcast — the harness
 * synthesizes successor boxes from the built outputs to keep chaining.
 *
 * Expected wall time [approx]: Ergo blocks are ~2 min on both networks; each
 * flow needs ~4-10 confirmations plus Flow B's 3-block maturation and Flow C's
 * 6-block timeout — all three flows together run roughly 45-75 minutes.
 */
class E2eFlow(
    private val config: E2eConfig,
    private val gateway: ChainGateway,
    private val faucet: Faucet,
    private val networkType: NetworkType = NetworkType.MAINNET,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = ::println,
) {
    private val summary = LinkedHashMap<String, Any?>()
    private var boxCounter = 0
    private val networkPrefix: Byte =
        if (networkType == NetworkType.MAINNET) ContractParams.NETWORK_PREFIX_MAINNET else ContractParams.NETWORK_PREFIX_TESTNET

    // Deal parameters: the protocol fee is a compile-time contract constant
    // (ContractParams.PROTOCOL_FEE_BPS); small collateral per deal (the contract
    // only requires A token with the pinned id — the gate mints a dev token;
    // there is no real USE on either network).
    private val dealAmount = 100_000_000L
    private val collateralTotal = 10 * dealAmount

    private lateinit var keys: RunKeys
    private lateinit var recipient: ByteArray
    private lateinit var nftIdHex: String
    private lateinit var collateralTokenIdHex: String
    private val dryOutputs = HashMap<String, List<ChainBox>>()

    /**
     * Dry-run simulated height: waits are skipped (nothing is broadcast), so
     * maturation/timeout advancement is applied to the sim height instead.
     * Live mode always re-reads the chain.
     */
    private var simHeight = 0
    private fun buildHeight(): Int = if (config.dryRun) simHeight else gateway.getCurrentHeight()
    private fun advanceSimHeight(height: Int) {
        if (config.dryRun) {
            simHeight = maxOf(simHeight, height)
            log("dry-run: sim height advanced to $simHeight")
        } else {
            waitForHeight(height, "wait for height $height")
        }
    }

    private class RunKeys(
        val operator: Keys,
        val buyer: Keys,
        val oracle: Keys,
        val treasury: Keys,
    )

    fun run(): Int {
        val runStart = nowMillis()
        log("== p2pgate e2e gate (network=$networkType, dryRun=${config.dryRun}) ==")

        // ------------------------------------------------ 1. preflight
        val height0 = try {
            gateway.getCurrentHeight()
        } catch (e: Exception) {
            log("PREFLIGHT FAIL: explorer ${config.explorerBaseUrl} unreachable: ${e.javaClass.simpleName}: ${e.message}")
            log(MANUAL_INSTRUCTIONS)
            return 2
        }
        log("preflight ok: height=$height0 explorer=${config.explorerBaseUrl}")
        simHeight = height0

        // ------------------------------------------------ 2. keys (per run, no secrets stored)
        keys = RunKeys(Keys.random(), Keys.random(), Keys.random(), Keys.random())
        recipient = ByteArray(21) { (it * 31 + 5).toByte() }
        val operatorAddress = p2pkAddress(keys.operator)
        log("operator (seller): $operatorAddress")

        // ------------------------------------------------ 3. compile (dummy NFT first to measure dust)
        val treasuryTree = Wire.p2pkTree(keys.treasury.pubKeyCompressed)
        val treasuryHash = Blake2b256.digest(treasuryTree.bytes())
        val dummyNft = ByteArray(32) { 7 }
        val probeTrees = ErgoContracts.compileFast(oracleNftId = dummyNft, treasuryScriptHash = treasuryHash)
        val probeOracle = DevOracle(keys.oracle.secret, dummyNft, networkPrefix, boxValueNanoErg = 1_000_000L)
        val autoFundedValue = maxOf(2_000_000L, probeTrees.fundedTree.bytes().size.toLong() * DUST_PER_BYTE)
        val fundedValue = if (config.fundedBoxValueNanoErg != E2eConfig.AUTO_FUNDED_VALUE) {
            config.fundedBoxValueNanoErg
        } else {
            autoFundedValue
        }
        val oracleBoxValue = maxOf(1_000_000L, probeOracle.tree.bytes().size.toLong() * DUST_PER_BYTE)
        // Covers the release's miner fee + treasury fee box + change dust.
        val oracleFeeBoxValue = 5_000_000L
        val collateralBoxValue = 2_000_000L
        val buyerBoxValue = 5_000_000L
        log(
            "compiled fast trees: funded=${probeTrees.fundedTree.bytes().size}B " +
                "proven=${probeTrees.provenTree.bytes().size}B oracle=${probeOracle.tree.bytes().size}B; " +
                "funded box value=$fundedValue nanoERG, oracle box value=$oracleBoxValue",
        )

        // ------------------------------------------------ 4. funding
        val neededErg = 3 * fundedValue + oracleBoxValue + oracleFeeBoxValue + collateralBoxValue + buyerBoxValue +
            10 * config.minerFeeNanoErg + 10_000_000L
        summary["operatorAddress"] = operatorAddress
        summary["buyerAddress"] = p2pkAddress(keys.buyer)
        summary["oracleAddress"] = p2pkAddress(keys.oracle)
        summary["neededErgNano"] = neededErg
        if (!config.dryRun) {
            ensureFunded(operatorAddress, neededErg)?.let { return it }
        } else {
            log("dry-run: skipping funding (synthetic boxes stand in)")
        }

        // ------------------------------------------------ 5. setup txs
        val setup = setup(oracleBoxValue, oracleFeeBoxValue, collateralBoxValue, buyerBoxValue)
        // Recompile against the REAL NFT id (the mint tx's first input box id).
        nftIdHex = setup.nftIdHex
        collateralTokenIdHex = setup.collateralTokenIdHex
        val trees = ErgoContracts.compileFast(oracleNftId = Hex.decode(nftIdHex), treasuryScriptHash = treasuryHash)
        val devOracle = DevOracle(
            keys.oracle.secret, Hex.decode(nftIdHex), networkPrefix,
            boxValueNanoErg = setup.oracleBox.value,
            boxId = setup.oracleBox.boxId, transactionId = setup.oracleBox.transactionId,
        )
        require(Wire.treeHex(devOracle.tree) == setup.oracleBox.ergoTreeHex.lowercase()) {
            "recompiled oracle tree does not match the minted oracle box"
        }
        fundedValueForFund = fundedValue
        val operatorBuilder = newOperatorBuilder(trees, treasuryTree, fundedValue)
        val claimBuilder = newClaimBuilder(trees, treasuryTree)
        val oracle = OracleState(devOracle, setup.oracleBox, setup.oracleFeeBox)
        summary["nftId"] = nftIdHex
        summary["collateralTokenId"] = collateralTokenIdHex
        summary["setupTxIds"] = setup.txIds.toMap()

        // ------------------------------------------------ flows
        val collateralAfterA = flowRelease(operatorBuilder, oracle, setup.collateralBox, trees)
        val collateralAfterB = flowDispute(operatorBuilder, claimBuilder, collateralAfterA, trees)
        flowReclaim(operatorBuilder, collateralAfterB, trees)

        summary["wallTimeMs"] = nowMillis() - runStart
        log("")
        log("== e2e gate PASSED ==")
        log("summary: " + jsonOf(summary))
        return 0
    }

    // ---------------------------------------------------------------- setup

    private class Setup(
        val nftIdHex: String,
        val collateralTokenIdHex: String,
        val oracleBox: ChainBox,
        val oracleFeeBox: ChainBox,
        val collateralBox: ChainBox,
        val txIds: Map<String, String>,
    )

    /** The oracle co-signs through the REAL explorer-parsed box — ids and heights must match the network. */
    private class OracleState(val devOracle: DevOracle, var box: ChainBox, var feeBox: ChainBox) {
        fun signer(): OracleSigner {
            val delegate = devOracle.signer()
            val input = box
            return object : OracleSigner {
                override val oracleNftId: ByteArray get() = delegate.oracleNftId
                override fun oracleInputBox(): ChainBox = input
                override fun sign(tx: org.ergoplatform.appkit.UnsignedTransaction) = delegate.sign(tx)
            }
        }
    }

    private fun setup(
        oracleBoxValue: Long,
        oracleFeeBoxValue: Long,
        collateralBoxValue: Long,
        buyerBoxValue: Long,
    ): Setup {
        val height = buildHeight()
        val txIds = LinkedHashMap<String, String>()
        val operatorTree = Wire.p2pkTree(keys.operator.pubKeyCompressed)
        val operatorAddress = p2pkAddress(keys.operator)

        // Mint 1: the oracle NFT — token id = first input box id (Ergo mint rule).
        val mint1Need = oracleBoxValue + oracleFeeBoxValue + config.minerFeeNanoErg + 1_000_000L
        val mint1Inputs = if (config.dryRun) listOf(syntheticBox(operatorTree, mint1Need, emptyList(), "m1in"))
        else selectInputs(operatorAddress, mint1Need)
        val nftId = Hex.encode(RawTx.toErgoBox(mint1Inputs[0], RawTx.decodeTree(mint1Inputs[0])).id())
        log("mint 1: oracle NFT will be $nftId (first-input-id rule)")
        val probeOracle = DevOracle(keys.oracle.secret, Hex.decode(nftId), networkPrefix, boxValueNanoErg = oracleBoxValue)
        val mint1 = assemble(
            mint1Inputs,
            listOf(
                RawTx.candidate(oracleBoxValue, probeOracle.tree, listOf(ChainToken(nftId, 1L)), height),
                RawTx.candidate(oracleFeeBoxValue, Wire.p2pkTree(keys.oracle.pubKeyCompressed), emptyList(), height),
            ),
            height,
        )
        val mint1TxId = broadcastAndConfirm("setup.mintOracleNft", mint1, txIds)
        val mint1Outputs = txOutputs(mint1TxId)
        val oracleBox = mint1Outputs.first { it.tokens.any { t -> t.tokenId == nftId } }
        val oracleFeeBox = mint1Outputs.first {
            it.tokens.isEmpty() &&
                it.ergoTreeHex.equals(Wire.treeHex(Wire.p2pkTree(keys.oracle.pubKeyCompressed)), ignoreCase = true)
        }

        // Mint 2: the collateral token — same mint rule on this tx's first input.
        val mint2Need = collateralBoxValue + config.minerFeeNanoErg + 1_000_000L
        val mint2Inputs = if (config.dryRun) listOf(syntheticBox(operatorTree, mint2Need, emptyList(), "m2in"))
        else selectInputs(operatorAddress, mint2Need)
        val collId = Hex.encode(RawTx.toErgoBox(mint2Inputs[0], RawTx.decodeTree(mint2Inputs[0])).id())
        log("mint 2: collateral token will be $collId")
        val mint2 = assemble(
            mint2Inputs,
            listOf(RawTx.candidate(collateralBoxValue, operatorTree, listOf(ChainToken(collId, collateralTotal)), height)),
            height,
        )
        val mint2TxId = broadcastAndConfirm("setup.mintCollateral", mint2, txIds)
        val collateralBox = txOutputs(mint2TxId).first { it.tokenAmount(collId) > 0 }

        // Fund the buyer address (claim txs are buyer-signed; their fee inputs must be buyer-key boxes).
        val buyerTree = Wire.p2pkTree(keys.buyer.pubKeyCompressed)
        val payNeed = buyerBoxValue + config.minerFeeNanoErg + 1_000_000L
        val payInputs = if (config.dryRun) listOf(syntheticBox(operatorTree, payNeed, emptyList(), "payin"))
        else selectInputs(operatorAddress, payNeed)
        val pay = assemble(payInputs, listOf(RawTx.candidate(buyerBoxValue, buyerTree, emptyList(), height)), height)
        broadcastAndConfirm("setup.fundBuyer", pay, txIds)

        return Setup(nftId, collId, oracleBox, oracleFeeBox, collateralBox, txIds)
    }

    // ---------------------------------------------------------------- flow A (release, path C)

    private fun flowRelease(
        operatorBuilder: OperatorTxBuilder,
        oracle: OracleState,
        collateralBox: ChainBox,
        trees: ErgoContracts.VaultTrees,
    ): ChainBox {
        log("")
        log("-- Flow A: oracle release (path C) --")
        val terms = dealTerms(nonce = 1)
        val height = buildHeight()
        val fundTx = operatorBuilder.buildFund(
            dealTerms = terms,
            recipientAddr = recipient,
            collateralTokenId = Hex.decode(collateralTokenIdHex),
            timeoutHeight = height + config.reclaimTimeoutBlocks,
            fundingInputs = listOf(collateralBox) + operatorErgInputs(fundedValueForFund + config.feeBoxValueNanoErg + config.minerFeeNanoErg + 1_000_000L),
            currentHeight = height,
            changeAddress = p2pkAddress(keys.operator),
            signer = proverSigner(keys.operator.secret),
        )
        val fundTxId = broadcastAndConfirm("flowA.fund", fundTx)
        val fundedBox = txOutputs(fundTxId)[0]

        // The seller signs the 52-byte P2PH handoff record at the meeting (t/3407).
        val record = handoffRecord(terms)
        val sig = Schnorr.sign(keys.operator.secret, record.encode(), keys.operator.pubKeyCompressed)

        // Dev attestation: the phase-1 oracle is trusted by design; a fabricated srcTxId is fine.
        val attestation = PaymentAttestation.build(
            terms, recipient, srcTxId = Blake2b256.digest("e2e-flowA".encodeToByteArray()),
            srcBlockHeight = height.toLong(), srcBlockTime = nowSec(),
        )

        val releaseTx = operatorBuilder.buildRelease(
            fundedBox = fundedBox,
            oracle = oracle.signer(),
            attestation = attestation,
            feeInputs = listOf(oracle.feeBox),
            currentHeight = buildHeight(),
            changeAddress = p2pkAddress(keys.oracle),
        )
        val releaseTxId = broadcastAndConfirm("flowA.release", releaseTx)

        // Live: discover the spend from the chain; dry-run: the release tx IS the spend.
        val spendTxId = if (config.dryRun) releaseTxId else pollSpent(fundedBox.boxId, "flowA: vault spend")
        val outs = txOutputs(spendTxId)
        val sellerOut = requirePayout(outs, collateralTokenIdHex, netAmount(terms), Wire.p2pkTree(keys.operator.pubKeyCompressed), "seller")
        val nftOut = outs.firstOrNull { out -> out.tokens.any { it.tokenId == nftIdHex && it.amount == 1L } }
            ?: fail("flowA: oracle NFT not preserved in the release outputs")
        log("flowA ok: seller paid ${netAmount(terms)} (net of ${ContractParams.PROTOCOL_FEE_BPS} bps fee), oracle NFT preserved in box ${nftOut.boxId}")

        summary["flowA"] = mapOf(
            "fundTxId" to fundTxId, "releaseTxId" to releaseTxId, "spendTxId" to spendTxId,
            "fundedBoxId" to fundedBox.boxId, "sellerPayoutBoxId" to sellerOut.boxId,
        )
        // The release spends the oracle box + fee box and recreates both — refresh for any later use.
        oracle.box = outs.first { it.tokens.any { t -> t.tokenId == nftIdHex } }
        oracle.feeBox = outs.first {
            it.tokens.isEmpty() &&
                it.ergoTreeHex.equals(Wire.treeHex(Wire.p2pkTree(keys.oracle.pubKeyCompressed)), ignoreCase = true)
        }
        // The fund tx change carries the remaining collateral back to the operator.
        return txOutputs(fundTxId).first { it.tokenAmount(collateralTokenIdHex) > 0 && it.boxId != fundedBox.boxId }
    }

    // ---------------------------------------------------------------- flow B (dispute: claim + payout)

    private fun flowDispute(
        operatorBuilder: OperatorTxBuilder,
        claimBuilder: ClaimTxBuilder,
        collateralBox: ChainBox,
        trees: ErgoContracts.VaultTrees,
    ): ChainBox {
        log("")
        log("-- Flow B: dispute (path B → path D) --")
        val terms = dealTerms(nonce = 2)
        val height = buildHeight()
        val fundTx = operatorBuilder.buildFund(
            dealTerms = terms,
            recipientAddr = recipient,
            collateralTokenId = Hex.decode(collateralTokenIdHex),
            timeoutHeight = height + config.reclaimTimeoutBlocks,
            fundingInputs = listOf(collateralBox) + operatorErgInputs(fundedValueForFund + config.feeBoxValueNanoErg + config.minerFeeNanoErg + 1_000_000L),
            currentHeight = height,
            changeAddress = p2pkAddress(keys.operator),
            signer = proverSigner(keys.operator.secret),
        )
        val fundTxId = broadcastAndConfirm("flowB.fund", fundTx)
        val fundedBox = txOutputs(fundTxId)[0]

        val record = handoffRecord(terms)
        val sig = Schnorr.sign(keys.operator.secret, record.encode(), keys.operator.pubKeyCompressed)
        val openTx = claimBuilder.buildClaimOpen(
            fundedBox = fundedBox,
            feeInputs = feeInputsFor(keys.buyer),
            record = record,
            a = sig.a,
            z = sig.z,
            currentHeight = buildHeight(),
            txTimestampMs = record.timestamp * 1000L,
            changeAddress = p2pkAddress(keys.buyer),
            signer = proverSigner(keys.buyer.secret),
        )
        val openTxId = broadcastAndConfirm("flowB.claimOpen", openTx)
        val provenBox = txOutputs(openTxId)[0]
        log("claim opened: proven box ${provenBox.boxId}; waiting ${ErgoContracts.Fast.CLAIM_MATURATION_BLOCKS} blocks maturation")

        val proofHeight = provenBox.registerLong(7)!!.toInt()
        advanceSimHeight(proofHeight + ErgoContracts.Fast.CLAIM_MATURATION_BLOCKS + 1)

        val payoutTx = claimBuilder.buildClaimPayout(
            provenBox = provenBox,
            feeInputs = feeInputsFor(keys.buyer),
            currentHeight = buildHeight(),
            buyerPayoutAddress = p2pkAddress(keys.buyer),
            changeAddress = p2pkAddress(keys.buyer),
            signer = proverSigner(keys.buyer.secret),
        )
        val payoutTxId = broadcastAndConfirm("flowB.claimPayout", payoutTx)

        val spendTxId = if (config.dryRun) payoutTxId else pollSpent(provenBox.boxId, "flowB: proven box spend")
        val outs = txOutputs(spendTxId)
        val buyerOut = requirePayout(outs, collateralTokenIdHex, netAmount(terms), Wire.p2pkTree(keys.buyer.pubKeyCompressed), "buyer")
        log("flowB ok: buyer paid ${netAmount(terms)} (net of ${ContractParams.PROTOCOL_FEE_BPS} bps fee) after dispute")

        summary["flowB"] = mapOf(
            "fundTxId" to fundTxId, "claimOpenTxId" to openTxId, "claimPayoutTxId" to payoutTxId,
            "spendTxId" to spendTxId, "provenBoxId" to provenBox.boxId, "buyerPayoutBoxId" to buyerOut.boxId,
        )
        return txOutputs(fundTxId).first { it.tokenAmount(collateralTokenIdHex) > 0 && it.boxId != fundedBox.boxId }
    }

    // ---------------------------------------------------------------- flow C (reclaim, path A)

    private fun flowReclaim(
        operatorBuilder: OperatorTxBuilder,
        collateralBox: ChainBox,
        trees: ErgoContracts.VaultTrees,
    ) {
        log("")
        log("-- Flow C: seller reclaim (path A) --")
        val terms = dealTerms(nonce = 3)
        val height = buildHeight()
        val timeoutHeight = height + config.reclaimTimeoutBlocks
        val fundTx = operatorBuilder.buildFund(
            dealTerms = terms,
            recipientAddr = recipient,
            collateralTokenId = Hex.decode(collateralTokenIdHex),
            timeoutHeight = timeoutHeight,
            fundingInputs = listOf(collateralBox) + operatorErgInputs(fundedValueForFund + config.feeBoxValueNanoErg + config.minerFeeNanoErg + 1_000_000L),
            currentHeight = height,
            changeAddress = p2pkAddress(keys.operator),
            signer = proverSigner(keys.operator.secret),
        )
        val fundTxId = broadcastAndConfirm("flowC.fund", fundTx)
        val fundedBox = txOutputs(fundTxId)[0]
        log("flowC: waiting past timeout height $timeoutHeight (+${config.reclaimTimeoutBlocks} blocks)")
        advanceSimHeight(timeoutHeight + 1)

        val reclaimTx = operatorBuilder.buildReclaim(
            fundedBox = fundedBox,
            feeInputs = feeInputsFor(keys.operator),
            currentHeight = buildHeight(),
            changeAddress = p2pkAddress(keys.operator),
            signer = proverSigner(keys.operator.secret),
        )
        val reclaimTxId = broadcastAndConfirm("flowC.reclaim", reclaimTx)

        val spendTxId = if (config.dryRun) reclaimTxId else pollSpent(fundedBox.boxId, "flowC: vault spend")
        val outs = txOutputs(spendTxId)
        val sellerOut = requirePayout(outs, collateralTokenIdHex, netAmount(terms), Wire.p2pkTree(keys.operator.pubKeyCompressed), "seller")
        log("flowC ok: seller refunded ${netAmount(terms)} (net of ${ContractParams.PROTOCOL_FEE_BPS} bps fee) after timeout")

        summary["flowC"] = mapOf(
            "fundTxId" to fundTxId, "reclaimTxId" to reclaimTxId, "spendTxId" to spendTxId,
            "fundedBoxId" to fundedBox.boxId, "sellerRefundBoxId" to sellerOut.boxId,
        )
    }

    // ---------------------------------------------------------------- funding

    private fun ensureFunded(operatorAddress: String, neededNano: Long): Int? {
        fun balance(): Long = gateway.getUnspentBoxes(operatorAddress).sumOf { it.value }
        var bal = balance()
        log("operator $operatorAddress balance: $bal nanoERG (needed: $neededNano)")
        if (bal >= neededNano) return null

        if (!config.faucetEnabled) {
            // Default on mainnet (no faucet exists): print the address + amount
            // and poll the balance while the operator funds it manually.
            log("operator underfunded and the faucet is disabled (E2E_FAUCET_URL=off)")
            log(MANUAL_INSTRUCTIONS)
            val deadline = nowMillis() + config.fundingTimeoutMs
            var backoff = 10_000L
            while (bal < neededNano && nowMillis() < deadline) {
                sleeper(backoff)
                bal = balance()
                if (bal < neededNano) log("balance: $bal nanoERG (needed: $neededNano) — still waiting for manual funding")
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
            if (bal < neededNano) {
                log("operator still underfunded after the funding deadline ($bal < $neededNano)")
                log(MANUAL_INSTRUCTIONS)
                return 2
            }
            log("operator funded: $bal nanoERG")
            return null
        }

        var attempts = 0
        while (bal < neededNano && attempts < config.faucetAttempts) {
            attempts++
            log("faucet request #$attempts for $operatorAddress")
            when (val r = faucet.request(operatorAddress)) {
                is FaucetResult.Unavailable -> {
                    log("faucet unavailable: ${r.reason}")
                    log(MANUAL_INSTRUCTIONS)
                    return 2
                }
                FaucetResult.Accepted -> Unit
            }
            val deadline = nowMillis() + config.fundingTimeoutMs
            var backoff = 5_000L
            while (nowMillis() < deadline) {
                sleeper(backoff)
                bal = balance()
                if (bal >= neededNano) break
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
            log("balance after attempt #$attempts: $bal nanoERG")
        }
        if (bal < neededNano) {
            log("operator still underfunded after $attempts faucet attempts ($bal < $neededNano)")
            log(MANUAL_INSTRUCTIONS)
            return 2
        }
        log("operator funded: $bal nanoERG")
        return null
    }

    // ---------------------------------------------------------------- helpers

    /** The vault box value the current operatorBuilder was configured with. */
    private var fundedValueForFund: Long = 0L

    private fun netAmount(terms: DealTerms): Long {
        val fee = terms.amount * ContractParams.PROTOCOL_FEE_BPS / ContractParams.FEE_DENOMINATOR
        return terms.amount - fee
    }

    private fun dealTerms(nonce: Int): DealTerms = DealTerms(
        dealNonce = ByteArray(16) { (it + nonce * 16).toByte() },
        asset = 1,
        srcChainId = 1,
        amount = dealAmount,
        fiatAmount = 250_000L,
        fiatCurrency = "EGP".encodeToByteArray(),
        buyerPubKey = keys.buyer.pubKeyCompressed,
        sellerPubKey = keys.operator.pubKeyCompressed,
        quoteExpiry = nowSec() + 3600,
    )

    private fun handoffRecord(terms: DealTerms): HandoffRecord = HandoffRecord(
        dealId = terms.dealId,
        amount = terms.fiatAmount,
        fiatCurrency = terms.fiatCurrency,
        timestamp = nowSec(),
    )

    private fun nowSec(): Long = nowMillis() / 1000L

    private fun p2pkAddress(keys: Keys): String =
        org.ergoplatform.appkit.Address.fromSigmaBoolean(
            sigma.data.ProveDlog.apply(Wire.decodePoint(keys.pubKeyCompressed)), networkType,
        ).toString()

    private fun newOperatorBuilder(trees: ErgoContracts.VaultTrees, treasuryTree: ErgoTree, fundedValue: Long) =
        OperatorTxBuilder(
            trees, treasuryTree,
            minerFeeNanoErg = config.minerFeeNanoErg,
            feeBoxValueNanoErg = config.feeBoxValueNanoErg,
            fundedBoxValueNanoErg = fundedValue,
        )

    private fun newClaimBuilder(trees: ErgoContracts.VaultTrees, treasuryTree: ErgoTree) =
        ClaimTxBuilder(
            trees, treasuryTree,
            minerFeeNanoErg = config.minerFeeNanoErg,
            feeBoxValueNanoErg = config.feeBoxValueNanoErg,
            claimMaturationBlocks = ErgoContracts.Fast.CLAIM_MATURATION_BLOCKS,
            handoffRecordMaxAgeMs = ErgoContracts.Fast.HANDOFF_RECORD_MAX_AGE_MS,
        )

    private fun proverSigner(secret: BigInteger): p2pgate.ergo.DealTxSigner = p2pgate.ergo.DealTxSigner { tx ->
        val client = org.ergoplatform.appkit.ColdErgoClient(networkType, RawTx.coldParameters(networkType))
        client.execute { ctx ->
            ctx.newProverBuilder().withDLogSecret(secret).build().sign(tx)
        }
    }

    /** Live: real wallet UTXOs covering [minTotal]; the caller substitutes synthetic boxes in dry-run. */
    private fun selectInputs(address: String, minTotal: Long): List<ChainBox> {
        val boxes = gateway.getUnspentBoxes(address).sortedByDescending { it.value }
        val picked = mutableListOf<ChainBox>()
        var sum = 0L
        for (b in boxes) {
            if (sum >= minTotal) break
            picked += b
            sum += b.value
        }
        require(picked.isNotEmpty() && sum >= minTotal) {
            "not enough spendable ERG at $address: have $sum, need $minTotal"
        }
        return picked
    }

    private fun assemble(
        inputs: List<ChainBox>,
        candidates: List<org.ergoplatform.ErgoBoxCandidate>,
        height: Int,
    ): SignedTransaction = RawTx.assemble(
        inputs = inputs.map { RawTx.toErgoBox(it, RawTx.decodeTree(it)) },
        candidates = candidates,
        minerFeeNanoErg = config.minerFeeNanoErg,
        minChangeNanoErg = 1_000_000L,
        currentHeight = height,
        txTimestampMs = nowMillis(),
        changeTree = Wire.p2pkTree(keys.operator.pubKeyCompressed),
        networkType = networkType,
        secrets = listOf(keys.operator.secret),
    )

    /**
     * Extra operator ERG for fund txs (the vault box value + treasury fee box
     * + miner fee + change dust ride on top of the collateral box's ERG):
     * live — real selected UTXOs; dry-run — a synthetic operator-keyed box.
     */
    private fun operatorErgInputs(minTotal: Long): List<ChainBox> = if (config.dryRun) {
        listOf(syntheticBox(Wire.p2pkTree(keys.operator.pubKeyCompressed), minTotal, emptyList(), "erg:$minTotal:$boxCounter"))
    } else {
        selectInputs(p2pkAddress(keys.operator), minTotal)
    }

    /**
     * Fee inputs must be provable by the tx's signer: live — the largest
     * ERG-only box at the key's address; dry-run — a synthetic box under the
     * signer's own key (the offline prover must prove it).
     */
    private fun feeInputsFor(holder: Keys): List<ChainBox> = if (config.dryRun) {
        listOf(syntheticBox(Wire.p2pkTree(holder.pubKeyCompressed), 5_000_000L, emptyList(), "fee:${holder.pubKeyCompressed.contentHashCode()}"))
    } else {
        val address = p2pkAddress(holder)
        val boxes = gateway.getUnspentBoxes(address).filter { it.tokens.isEmpty() }.sortedByDescending { it.value }
        require(boxes.isNotEmpty()) { "no ERG-only fee boxes at $address" }
        listOf(boxes[0])
    }

    /** Deterministic synthetic box for dry-run builds (never broadcast). */
    private fun syntheticBox(tree: ErgoTree, value: Long, tokens: List<ChainToken>, tag: String): ChainBox {
        val txId = Hex.encode(Blake2b256.digest(tag.encodeToByteArray()))
        return ChainBox(
            boxId = Hex.encode(Blake2b256.digest(("$tag:box").encodeToByteArray())),
            transactionId = txId,
            index = 0,
            value = value,
            creationHeight = 1,
            ergoTreeHex = Wire.treeHex(tree),
            address = "",
            tokens = tokens,
            registers = List(6) { null },
        )
    }

    private fun broadcastAndConfirm(label: String, tx: SignedTransaction, txIds: MutableMap<String, String>? = null): String {
        val txId = if (config.dryRun) {
            // Full 64-hex synthetic id: it doubles as the boxes' transactionId,
            // which ErgoBox derivation base16-decodes.
            val id = Hex.encode(Blake2b256.digest(tx.toJson(false).encodeToByteArray()))
            log("$label: built (prover-verified), dry-run — not broadcasting (synthetic id ${id.take(16)}...)")
            dryOutputs[id] = tx.outputs.mapIndexed { i, out -> outBoxToChainBox(out, id, i) }
            id
        } else {
            val id = gateway.submitTransaction(tx.toJson(false))
            log("$label: broadcast $id")
            id
        }
        txIds?.put(label, txId)
        if (!config.dryRun) waitForTx(txId, label)
        return txId
    }

    /** Converts a built output into a [ChainBox] for dry-run chaining (live runs re-read boxes from the explorer). */
    private fun outBoxToChainBox(out: org.ergoplatform.appkit.OutBox, txId: String, index: Int): ChainBox {
        val impl = out as OutBoxImpl
        val candidate = impl.ergoBoxCandidate
        val registers = MutableList<ChainRegister?>(6) { null }
        val regsMap = candidate.additionalRegisters()
        val iter = regsMap.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            val idx = e._1().toString().removePrefix("R").toInt()
            val value = e._2()
            registers[idx - 4] = when (val v = value.value()) {
                is java.lang.Long -> ChainRegister.Int64(v.toLong())
                is sigma.Coll<*> -> ChainRegister.CollBytes(JavaHelpers.collToByteArray(v as sigma.Coll<Any>))
                else -> throw IllegalArgumentException("unsupported register constant: ${v?.javaClass}")
            }
        }
        boxCounter++
        return ChainBox(
            boxId = Hex.encode(Blake2b256.digest(("$txId:$index:$boxCounter").encodeToByteArray())),
            transactionId = txId,
            index = index,
            value = candidate.value(),
            creationHeight = candidate.creationHeight(),
            ergoTreeHex = Wire.treeHex(impl.ergoTree),
            address = "",
            tokens = impl.tokens.map { t -> ChainToken(Hex.encode(t.id.bytes), t.value) },
            registers = registers,
        )
    }

    private fun waitForTx(txId: String, label: String): List<ChainBox> {
        val deadline = nowMillis() + config.stepTimeoutMs
        var backoff = 10_000L
        while (true) {
            try {
                val outs = gateway.getTransactionOutputs(txId)
                log("$label: confirmed with ${outs.size} outputs")
                return outs
            } catch (e: Exception) {
                if (nowMillis() > deadline) fail("$label: tx $txId not confirmed within ${config.stepTimeoutMs} ms: ${e.message}")
                sleeper(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun txOutputs(txId: String): List<ChainBox> = if (config.dryRun) {
        dryOutputs[txId] ?: fail("dry-run: no outputs recorded for $txId")
    } else {
        gateway.getTransactionOutputs(txId)
    }

    private fun pollSpent(boxId: String, what: String): String {
        val deadline = nowMillis() + config.stepTimeoutMs
        var backoff = 10_000L
        while (true) {
            val spent = gateway.getBox(boxId)?.spentTransactionId
            if (spent != null) {
                log("$what: box $boxId spent by $spent")
                return spent
            }
            if (nowMillis() > deadline) fail("$what: box $boxId not spent within ${config.stepTimeoutMs} ms")
            sleeper(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun waitForHeight(height: Int, what: String) {
        val deadline = nowMillis() + config.stepTimeoutMs
        var backoff = 15_000L
        while (true) {
            val h = gateway.getCurrentHeight()
            if (h >= height) {
                log("$what: height $h reached (needed $height)")
                return
            }
            if (nowMillis() > deadline) fail("$what: height $height not reached within ${config.stepTimeoutMs} ms (at $h)")
            sleeper(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun requirePayout(
        outs: List<ChainBox>,
        tokenId: String,
        expectedAmount: Long,
        tree: ErgoTree,
        who: String,
    ): ChainBox = outs.firstOrNull { out ->
        out.ergoTreeHex.equals(Wire.treeHex(tree), ignoreCase = true) &&
            out.tokens.any { it.tokenId.equals(tokenId, ignoreCase = true) && it.amount == expectedAmount }
    } ?: fail(
        "no $who payout output ($expectedAmount of $tokenId) among the spend outputs: " +
            outs.joinToString { it.toString() },
    )

    private fun fail(message: String): Nothing {
        log("E2E FAIL: $message")
        throw E2eException(message)
    }

    class E2eException(message: String) : RuntimeException(message)

    companion object {
        /** nanoERG per tree byte for the auto box values — 2× the 360 reference for headroom. */
        private const val DUST_PER_BYTE = 720L

        const val MANUAL_INSTRUCTIONS = """
MANUAL RUN (the gate did not fail — it needs a funded operator to proceed):
  1. Send at least the "needed" nanoERG to the operator address printed above
     (both are in the "operator ... balance" / summary lines, and in the JSON
     summary's operatorAddress / neededErgNano fields).
     Mainnet is the default target since 2026-09-17 — there is no mainnet
     faucet. For testnet instead: E2E_EXPLORER_URL=https://api-testnet.ergoplatform.com
     and E2E_FAUCET_URL=https://testnet.ergofaucet.org.
  2. The gate polls the balance while you fund it (E2E_FUNDING_TIMEOUT_MS).
     If it exits 2 on the deadline, fund the printed address and re-run —
     with the default E2E_FAUCET_URL=off no faucet is ever contacted.
"""
    }
}
