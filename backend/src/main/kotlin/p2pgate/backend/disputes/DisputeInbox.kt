package p2pgate.backend.disputes

import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import p2pgate.backend.oracle.OracleClient
import p2pgate.backend.store.DealStore
import p2pgate.backend.vault.VaultManager
import p2pgate.dealprotocol.DealState
import p2pgate.dealprotocol.ProtocolConstants
import java.time.Duration
import java.time.Instant

/**
 * One dispute-inbox row, `specs/operator-backend.md` §7 — one per open claim
 * (deals in CLAIM_OPENED / CLAIMABLE), with both sides of the story and the
 * deadline countdown as the primary sort key.
 */
data class DisputeRow(
    val dealId: String,
    val state: DealState,
    /** When the claim landed (the path-B observation instant). */
    val openedAt: Instant,
    /** `openedAt + CLAIM_MATURATION` — when path D becomes spendable by the buyer. */
    val maturesAt: Instant,
    /** A PaymentConfirmed landed while the claim was open — the claim is without cause. */
    val contested: Boolean,
    /** Seller-signed "P2PH" record reference (hex), if the handoff upload has it on file. */
    val handoffRecordRef: String?,
    /** GPS / override record reference from the meeting. */
    val geoRef: String?,
    /** Whether the oracle has confirmed the seller's USDT transfer. */
    val oracleConfirmed: Boolean,
    /** `blake2b256(attestation)` — the digest reference, once attested. */
    val attestationDigest: String?,
    val actioned: Boolean,
    val action: String?,
    /** The escalation hook already fired for this claim. */
    val escalated: Boolean = false,
)

/** Fail-loud escalation sink (§7): dashboard alert + configurable webhook. */
fun interface EscalationHook {
    fun escalate(row: DisputeRow)
}

/** Default no-op hook (tests/dev); the Ktor module wires [WebhookEscalation] when a URL is configured. */
object NoOpEscalation : EscalationHook {
    override fun escalate(row: DisputeRow) = Unit
}

/**
 * Webhook escalation: POSTs a minimal JSON row to the configured URL. The
 * transport is injected for the same testability reason as the rest of the
 * HTTP seams in this module.
 */
class WebhookEscalation(
    private val url: String,
    private val transport: (url: String, body: String) -> Unit,
) : EscalationHook {
    override fun escalate(row: DisputeRow) {
        transport(
            url,
            """{"dealId":"${row.dealId}","state":"${row.state}","maturesAt":"${row.maturesAt}","contested":${row.contested}}""",
        )
    }
}

/**
 * Dispute inbox, `specs/operator-backend.md` §7. Exactly three actions —
 * contest, accept, investigate — and nothing else. Contest is mechanical
 * whenever the oracle digest exists (path C′); accept concedes and records
 * the loss; investigate routes the deal to internal review of the handoff
 * evidence, changing nothing on-chain. The inbox is fail-loud: any claim
 * approaching maturation unactioned escalates through [escalation].
 */
class DisputeInbox(
    private val store: DealStore,
    private val vaultManager: VaultManager,
    private val oracle: OracleClient,
    private val escalation: EscalationHook = NoOpEscalation,
    private val bus: EventBus,
    private val escalationLeadTime: Duration = Duration.ofHours(2),
) {
    sealed interface ActionOutcome {
        data object Ok : ActionOutcome
        data class Rejected(val reason: String) : ActionOutcome
    }

    /** All open claims, sorted by maturation deadline (the primary sort key, §7). */
    fun rows(): List<DisputeRow> = store.openDeals()
        .filter { it.state == DealState.CLAIM_OPENED || it.state == DealState.CLAIMABLE }
        .map { row(it.dealId) }
        .sortedBy { it.maturesAt }

    fun row(dealId: String): DisputeRow {
        val deal = store.getDeal(dealId) ?: throw IllegalArgumentException("unknown deal $dealId")
        val attestation = try {
            oracle.attestationFor(dealId)
        } catch (e: p2pgate.backend.oracle.OracleUnavailableException) {
            null
        }
        return DisputeRow(
            dealId = deal.dealId,
            state = deal.state,
            openedAt = deal.proofTimestamp ?: deal.createdAt,
            maturesAt = (deal.proofTimestamp ?: deal.createdAt).plus(ProtocolConstants.CLAIM_MATURATION),
            contested = deal.contested,
            handoffRecordRef = deal.handoffRecordHex,
            geoRef = deal.handoffGpsRef,
            oracleConfirmed = attestation != null,
            attestationDigest = attestation?.let { p2pgate.backend.util.Hex.encode(it.digest()) },
            actioned = deal.claimAction != null,
            action = deal.claimAction,
            escalated = deal.escalated,
        )
    }

    /**
     * Contest: present the oracle digest of the seller's USDT transfer (vault
     * path C′). Rejected without a digest on file — with one, it is mechanical.
     */
    fun contest(dealId: String, at: Instant = Instant.now()): ActionOutcome {
        val deal = store.getDeal(dealId) ?: return ActionOutcome.Rejected("unknown deal $dealId")
        if (deal.state != DealState.CLAIM_OPENED && deal.state != DealState.CLAIMABLE) {
            return ActionOutcome.Rejected("deal is ${deal.state} — no open claim")
        }
        return when (val outcome = vaultManager.contestDeal(dealId, at)) {
            is VaultManager.Outcome.Submitted -> mark(dealId, "contest")
            is VaultManager.Outcome.Rejected -> ActionOutcome.Rejected(outcome.reason)
        }
    }

    /**
     * Accept: concede the claim, take no on-chain action; the claim matures
     * and path D pays the buyer. The loss is recorded against the deal.
     */
    fun accept(dealId: String): ActionOutcome {
        val deal = store.getDeal(dealId) ?: return ActionOutcome.Rejected("unknown deal $dealId")
        if (deal.state != DealState.CLAIM_OPENED && deal.state != DealState.CLAIMABLE) {
            return ActionOutcome.Rejected("deal is ${deal.state} — no open claim")
        }
        store.updateDeal(dealId) { it.copy(lossRecorded = true) }
        return mark(dealId, "accept")
    }

    /**
     * Investigate: the record verifies but the operator's evidence says the
     * cash was never collected (or the signature is disputed). Routes the
     * deal to internal review of the handoff evidence — nothing changes
     * on-chain.
     */
    fun investigate(dealId: String): ActionOutcome {
        val deal = store.getDeal(dealId) ?: return ActionOutcome.Rejected("unknown deal $dealId")
        if (deal.state != DealState.CLAIM_OPENED && deal.state != DealState.CLAIMABLE) {
            return ActionOutcome.Rejected("deal is ${deal.state} — no open claim")
        }
        return mark(dealId, "investigate")
    }

    private fun mark(dealId: String, action: String): ActionOutcome {
        store.updateDeal(dealId) { it.copy(claimAction = action) }
        return ActionOutcome.Ok
    }

    /**
     * Fail-loud sweep: escalate every unactioned claim inside
     * [escalationLeadTime] of maturation, at most once per claim. A claim that
     * matures unactioned is collateral donated — the hook is the last alarm.
     */
    fun tick(now: Instant = Instant.now()) {
        for (r in rows()) {
            if (r.actioned || r.escalated) continue
            if (Duration.between(now, r.maturesAt) <= escalationLeadTime) {
                store.updateDeal(r.dealId) { it.copy(escalated = true) }
                escalation.escalate(r)
                bus.publish(BackendEvent.Escalated(r.dealId, "claim unactioned, maturing at ${r.maturesAt}", now))
            }
        }
    }
}
