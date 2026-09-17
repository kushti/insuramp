package p2pgate.backend.vault

import org.ergoplatform.appkit.SignedTransaction
import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import p2pgate.backend.bus.TxKind
import p2pgate.backend.engine.DealEngine
import p2pgate.backend.infra.InfraMonitor
import p2pgate.backend.oracle.OracleClient
import p2pgate.backend.store.DealRecord
import p2pgate.backend.store.DealStore
import p2pgate.backend.store.StoredEvent
import p2pgate.backend.util.Hex
import p2pgate.contracts.ContractParams
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.DealState
import p2pgate.dealprotocol.ProtocolConstants
import p2pgate.ergo.ChainBox
import p2pgate.ergo.ChainSource
import p2pgate.ergo.DealTxSigner
import p2pgate.ergo.ErgoContracts
import p2pgate.ergo.OperatorTxBuilder
import sigma.ast.ErgoTree
import java.math.BigInteger
import java.time.Duration
import java.time.Instant

/**
 * The embedded-wallet seam (`specs/operator-backend.md` §2 "embedded wallet
 * (or external node wallet)"): the vault manager asks for funding/fee inputs
 * and a [DealTxSigner]; whatever sits behind the interface holds the keys.
 * M3 ships [EmbeddedVaultSigner] (config-supplied secret); production swaps in
 * an HSM/node-wallet adapter without touching the vault manager. Keys never
 * leave this seam — the dashboard gets action endpoints, not keys (§9).
 */
interface VaultSigner {
    /** The seller/operator compressed key — pinned as R5 of every funded vault. */
    val publicKeyCompressed: ByteArray

    /** Change/reclaim-return address (the privacy partition conceptually, §4). */
    val changeAddress: String

    /** Funding inputs carrying at least [minAmount] of the collateral token. */
    fun fundingInputs(collateralTokenIdHex: String, minAmount: Long): List<ChainBox>

    /** Operator-wallet boxes for miner-fee funding of seller-signed txs. */
    fun feeInputs(): List<ChainBox>

    /** Signs seller-side txs (fund/reclaim). */
    fun signer(): DealTxSigner
}

/**
 * Broadcast seam. The default is a no-op fake (returns the tx id) so tests
 * and dev wiring run without a node; `NodeTxSubmitter` is the explorer/node
 * adapter shape for production.
 */
fun interface TxSubmitter {
    /** Broadcasts [tx]; returns the transaction id. */
    fun submit(tx: SignedTransaction): String
}

private typealias Rejected = VaultManager.Outcome.Rejected
private typealias Submitted = VaultManager.Outcome.Submitted

/** Test/dev default — never touches the network. */
class NoOpTxSubmitter : TxSubmitter {
    override fun submit(tx: SignedTransaction): String = tx.id
}

/**
 * Explorer/node transaction submission adapter (production seam): POSTs the
 * signed tx JSON to `/transactions`. Transport-injected like
 * `ExplorerChainSource`, so it is testable without a network.
 */
class NodeTxSubmitter(
    private val baseUrl: String,
    private val transport: (path: String, body: String) -> String,
) : TxSubmitter {
    override fun submit(tx: SignedTransaction): String {
        val body = transport("$baseUrl/api/v1/transactions", tx.toJson(true))
        // Explorers echo the tx id as a JSON string; accept any 2xx echo.
        return body.trim().removeSurrounding("\"").ifEmpty { tx.id }
    }
}

/**
 * M3 embedded wallet: a config-supplied secp256k1 secret behind [VaultSigner],
 * signing offline via the same cold-client pattern as `DevOracle`. Funding
 * and fee boxes are operator-supplied `ChainBox`es (drawn from the privacy
 * partition in production; in dev they are whatever the operator loads).
 */
class EmbeddedVaultSigner(
    val secret: BigInteger,
    private val networkType: org.ergoplatform.appkit.NetworkType,
    override val changeAddress: String,
    fundingBoxes: List<ChainBox> = emptyList(),
    private val feeBoxes: List<ChainBox> = emptyList(),
) : VaultSigner {
    override val publicKeyCompressed: ByteArray = p2pgate.backend.util.Secp256k1.publicKeyCompressed(secret)

    private val fundingBoxes = java.util.concurrent.CopyOnWriteArrayList(fundingBoxes)

    fun addFundingBox(box: ChainBox) {
        fundingBoxes += box
    }

    override fun fundingInputs(collateralTokenIdHex: String, minAmount: Long): List<ChainBox> {
        val selected = fundingBoxes.filter { it.tokenAmount(collateralTokenIdHex) > 0 }
        return if (selected.sumOf { it.tokenAmount(collateralTokenIdHex) } >= minAmount) {
            fundingBoxes.removeAll(selected.toSet())
            selected
        } else {
            emptyList()
        }
    }

    override fun feeInputs(): List<ChainBox> = feeBoxes.toList()

    override fun signer(): DealTxSigner = DealTxSigner { tx ->
        org.ergoplatform.appkit.ColdErgoClient(networkType, DevOracleCold.parameters(networkType))
            .execute { ctx ->
                ctx.newProverBuilder().withDLogSecret(secret).build().sign(tx)
            }
    }

    /** Cold-client parameters (same block as `DevOracle.coldParameters`). */
    private object DevOracleCold {
        fun parameters(networkType: org.ergoplatform.appkit.NetworkType) =
            p2pgate.ergo.DevOracle.coldParameters(networkType)
    }
}

/**
 * Vault manager, `specs/operator-backend.md` §2: builds and submits the four
 * operator-side vault txs (fund / reclaim / release / contest) and owns the
 * two schedulers that drive them.
 *
 * The **reclaim invariant (§1, hard rule)** lives here: reclaim is valid only
 * from FUNDED and PAYMENT_CONFIRMED — never from PAYMENT_PENDING (cash has
 * changed hands, no payment proof exists — the theft path). The scheduler is
 * state-aware: it proposes [DealEvent.ReclaimTimeoutElapsed] to the engine and
 * builds a tx **only when the machine accepts**; a PAYMENT_PENDING proposal is
 * rejected by the machine, logged as an invariant violation, and no tx is
 * built. (On-chain a FUNDED box cannot tell these states apart — the
 * exclusion is deliberately off-chain.)
 *
 * Release is automatic on [DealEvent.PaymentConfirmed] (path C, oracle
 * attestation alone — v2; no receipt signature exists) and contest (path C′)
 * is triggered by the dispute inbox.
 */
class VaultManager(
    private val trees: ErgoContracts.VaultTrees,
    treasuryTree: ErgoTree,
    val signer: VaultSigner,
    private val chain: ChainSource,
    private val submitter: TxSubmitter,
    private val engine: DealEngine,
    private val store: DealStore,
    private val bus: EventBus,
    private val infra: InfraMonitor,
    private val oracle: OracleClient,
    private val riskScorer: p2pgate.backend.aml.RiskScorer,
    private val feeBps: Int = 0,
    private val reclaimTimeoutBlocks: Int = ContractParams.RECLAIM_TIMEOUT_BLOCKS,
    private val builder: OperatorTxBuilder = OperatorTxBuilder(trees, treasuryTree),
) {
    sealed interface Outcome {
        data class Submitted(val kind: TxKind, val txId: String, val boxId: String? = null) : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    /**
     * Builds, signs and submits the fund tx (creates the FUNDED box).
     * Hard gates, in order: infra healthy (§8 halts funding), deal still
     * QUOTED, AML accept on file for the current receive address — with the
     * address-swap re-check: if the address seen now differs from the scored
     * one, the scorer runs again, fail-closed (an unreachable scorer means no
     * funding). The deal becomes FUNDED only when the chain watcher observes
     * the box (the backend mirrors, never invents).
     */
    fun fundDeal(dealId: String, at: Instant = Instant.now()): Outcome {
        val deal = store.getDeal(dealId) ?: return Rejected("unknown deal $dealId")
        if (!infra.healthy()) return Rejected("funding halted: infra paused")
        if (deal.state != DealState.QUOTED) return Rejected("deal is ${deal.state}, expected QUOTED")

        val recheck = ensureAmlCurrent(deal, at)
        if (recheck != null) return recheck

        val terms = deal.terms()
        val fundingInputs = signer.fundingInputs(deal.collateralTokenIdHex, deal.amount)
        if (fundingInputs.isEmpty()) {
            return Rejected("no funding inputs carry ${deal.amount} of ${deal.collateralTokenIdHex}")
        }
        val currentHeight = chain.getCurrentHeight()
        val tx = builder.buildFund(
            dealTerms = terms,
            recipientAddr = Hex.decode(deal.recipientAddrHex),
            collateralTokenId = Hex.decode(deal.collateralTokenIdHex),
            timeoutHeight = currentHeight + reclaimTimeoutBlocks,
            feeBps = feeBps,
            fundingInputs = fundingInputs,
            currentHeight = currentHeight,
            changeAddress = signer.changeAddress,
            signer = signer.signer(),
        )
        val outcome = submit(dealId, TxKind.FUND, tx, at) { boxId ->
            store.updateDeal(dealId) { it.copy(vaultBoxId = boxId) }
        }
        // The operator is the funder: a successful submission IS the funding
        // fact (VaultBoxTracker never emits VaultFunded — it classifies facts
        // about boxes that already exist). The engine anchors fundedAt here.
        if (outcome is Submitted) {
            engine.apply(dealId, DealEvent.VaultFunded(at), at)
        }
        return outcome
    }

    /**
     * The reclaim scheduler sweep (state-aware, §1). For every open deal past
     * `fundedAt + RECLAIM_TIMEOUT` the event is proposed to the engine; only
     * an accepted transition (FUNDED or PAYMENT_CONFIRMED) builds a tx. From
     * PAYMENT_CONFIRMED the release is preferred and normally already
     * automatic — the reclaim remains the fallback for an attested-but-
     * unreleased vault (e.g. the oracle input was unspendable for a while).
     */
    fun tickReclaims(now: Instant = Instant.now()): List<Outcome> {
        val outcomes = mutableListOf<Outcome>()
        for (deal in store.openDeals()) {
            if (deal.state !in RECLAIMABLE_STATES) continue
            val fundedAt = deal.fundedAt ?: continue
            if (Duration.between(fundedAt, now) < ProtocolConstants.RECLAIM_TIMEOUT) continue
            when (val r = engine.apply(deal.dealId, DealEvent.ReclaimTimeoutElapsed(now), now)) {
                is DealEngine.Result.Advanced -> outcomes += submitReclaimTx(deal.dealId, now)
                is DealEngine.Result.Violation -> outcomes += Rejected(r.reason)
                DealEngine.Result.Aborted -> Unit
            }
        }
        return outcomes
    }

    /** Manual reclaim trigger (dashboard `POST /v1/vaults/{id}/reclaim`) — same guard path. */
    fun reclaimDeal(dealId: String, at: Instant = Instant.now()): Outcome {
        val deal = store.getDeal(dealId) ?: return Rejected("unknown deal $dealId")
        val fundedAt = deal.fundedAt ?: return Rejected("deal has no funding timestamp")
        if (Duration.between(fundedAt, at) < ProtocolConstants.RECLAIM_TIMEOUT) {
            return Rejected("reclaim timeout has not elapsed yet")
        }
        return when (val r = engine.apply(dealId, DealEvent.ReclaimTimeoutElapsed(at), at)) {
            is DealEngine.Result.Advanced -> submitReclaimTx(dealId, at)
            is DealEngine.Result.Violation -> Rejected(r.reason)
            DealEngine.Result.Aborted -> Rejected("deal abandoned")
        }
    }

    /**
     * Automatic release on payment confirmation (path C). The oracle
     * attestation alone gates the spend (v2); the tx carries the oracle box
     * as a full input and the 112-byte attestation as context var 0.
     */
    fun releaseIfConfirmed(dealId: String, at: Instant = Instant.now()): Outcome {
        val deal = store.getDeal(dealId) ?: return Rejected("unknown deal $dealId")
        if (deal.state != DealState.PAYMENT_CONFIRMED) {
            return Rejected("deal is ${deal.state}, expected PAYMENT_CONFIRMED")
        }
        val attestation = try {
            oracle.attestationFor(dealId)
        } catch (e: p2pgate.backend.oracle.OracleUnavailableException) {
            return Rejected("oracle unavailable: ${e.message}")
        } ?: return Rejected("no attestation on file")
        val fundedBox = chain.getBox(deal.vaultBoxId ?: return Rejected("deal has no vault box"))
            ?.takeIf { it.spentTransactionId == null }
            ?: return Rejected("vault box spent or unknown")
        val currentHeight = chain.getCurrentHeight()
        val tx = builder.buildRelease(
            fundedBox = fundedBox,
            oracle = oracle.signer,
            attestation = attestation,
            feeInputs = oracle.feeInputs(),
            currentHeight = currentHeight,
            changeAddress = signer.changeAddress,
        )
        val outcome = submit(dealId, TxKind.RELEASE, tx, at)
        if (outcome is Outcome.Submitted) {
            engine.apply(dealId, DealEvent.ReleaseObserved, at)
        }
        return outcome
    }

    /**
     * Contest (path C′) from the dispute inbox: the same oracle digest, spent
     * from the PAYMENT_PROVEN box. Mechanical whenever the digest exists — an
     * honest seller always counters a false claim (v2: no withheld-signature
     * corner).
     */
    fun contestDeal(dealId: String, at: Instant = Instant.now()): Outcome {
        val deal = store.getDeal(dealId) ?: return Rejected("unknown deal $dealId")
        if (deal.state != DealState.CLAIM_OPENED && deal.state != DealState.CLAIMABLE) {
            return Rejected("deal is ${deal.state}, no open claim to contest")
        }
        val attestation = try {
            oracle.attestationFor(dealId)
        } catch (e: p2pgate.backend.oracle.OracleUnavailableException) {
            return Rejected("oracle unavailable: ${e.message}")
        } ?: return Rejected("no attestation on file — contest is mechanical only when the digest exists")
        val provenBoxId = deal.provenBoxId ?: return Rejected("no PAYMENT_PROVEN box tracked for this deal")
        val provenBox = chain.getBox(provenBoxId)?.takeIf { it.spentTransactionId == null }
            ?: return Rejected("PAYMENT_PROVEN box spent or unknown")
        val tx = builder.buildContest(
            provenBox = provenBox,
            oracle = oracle.signer,
            attestation = attestation,
            feeInputs = oracle.feeInputs(),
            currentHeight = chain.getCurrentHeight(),
            changeAddress = signer.changeAddress,
        )
        val outcome = submit(dealId, TxKind.CONTEST, tx, at)
        if (outcome is Outcome.Submitted) {
            engine.apply(dealId, DealEvent.ReleaseObserved, at)
        }
        return outcome
    }

    private fun submitReclaimTx(dealId: String, at: Instant): Outcome {
        val deal = store.getDeal(dealId) ?: return Rejected("unknown deal $dealId")
        val fundedBox = chain.getBox(deal.vaultBoxId ?: return Rejected("deal has no vault box"))
            ?.takeIf { it.spentTransactionId == null }
            ?: return Rejected("vault box spent or unknown")
        val tx = builder.buildReclaim(
            fundedBox = fundedBox,
            feeInputs = signer.feeInputs(),
            currentHeight = chain.getCurrentHeight(),
            changeAddress = signer.changeAddress,
            signer = signer.signer(),
        )
        return submit(dealId, TxKind.RECLAIM, tx, at)
    }

    /**
     * AML gate + address-swap re-check. Returns a [Outcome.Rejected] when
     * funding must not proceed, `null` when an ACCEPT for the current address
     * is on file. A scorer error is a rejection (fail-closed, §6).
     */
    private fun ensureAmlCurrent(deal: DealRecord, at: Instant): Outcome.Rejected? {
        val latest = deal.latestAml()
        if (latest != null && latest.checkedAddressHex.equals(deal.recipientAddrHex, ignoreCase = true)) {
            // Decision on file for exactly this address: honor it as-is.
            return if (latest.decision == p2pgate.backend.aml.AmlDecision.ACCEPT) {
                null
            } else {
                Rejected("AML decision is ${latest.decision}")
            }
        }
        // No decision, or the address changed since the check (address-swap
        // defense) — re-score now; a scorer error means no funding (§6).
        val decision = try {
            riskScorer.score(Hex.decode(deal.recipientAddrHex), deal.srcChainId)
        } catch (e: p2pgate.backend.aml.RiskScorerException) {
            return Rejected("AML scorer unreachable — funding halted (fail-closed)")
        }
        store.updateDeal(deal.dealId) {
            it.copy(
                amlRecords = it.amlRecords + p2pgate.backend.store.AmlRecord(
                    decision = decision,
                    scorerId = riskScorer.scorerId,
                    checkedAddressHex = deal.recipientAddrHex,
                    at = at,
                ),
            )
        }
        return if (decision == p2pgate.backend.aml.AmlDecision.ACCEPT) {
            null
        } else {
            Rejected("AML decision is $decision")
        }
    }

    private fun submit(
        dealId: String,
        kind: TxKind,
        tx: SignedTransaction,
        at: Instant,
        onBox: (String) -> Unit = {},
    ): Outcome {
        val txId = try {
            submitter.submit(tx)
        } catch (e: Exception) {
            infra.report(p2pgate.backend.infra.InfraSignal.WALLET_HEALTH, false, "broadcast failed: ${e.message}")
            return Rejected("broadcast failed: ${e.message}")
        }
        // The first output of an operator tx is always the vault/oracle box of
        // interest; appkit exposes the post-tx boxes via getOutputsToSpend.
        val boxId = tx.outputsToSpend.firstOrNull()?.id?.toString()
        if (boxId != null) onBox(boxId)
        store.appendEvent(StoredEvent(dealId, kind.name, txId, at))
        bus.publish(BackendEvent.TxSubmitted(kind, dealId, txId, at))
        return Outcome.Submitted(kind, txId, boxId)
    }

    companion object {
        /** States from which the reclaim scheduler even proposes a reclaim (§1). */
        val RECLAIMABLE_STATES: Set<DealState> = setOf(
            DealState.FUNDED,
            DealState.PAYMENT_PENDING,
            DealState.PAYMENT_CONFIRMED,
        )
    }
}
