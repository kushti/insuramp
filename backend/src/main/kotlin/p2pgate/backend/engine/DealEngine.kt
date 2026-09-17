package p2pgate.backend.engine

import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import p2pgate.backend.store.DealRecord
import p2pgate.backend.store.DealStore
import p2pgate.backend.store.StoredEvent
import p2pgate.dealprotocol.DealEvent
import p2pgate.dealprotocol.DealState
import p2pgate.dealprotocol.DealStateMachine
import p2pgate.dealprotocol.TransitionOutcome
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ring buffer of invariant violations (spec §2: invalid transitions are
 * "rejected and logged as an invariant violation"). Surfaced on the dashboard
 * and in the event stream; the reclaim-on-PAYMENT_PENDING refusal is the
 * canonical entry.
 */
class ViolationLog {
    data class Entry(val dealId: String?, val event: String, val reason: String, val at: Instant)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun log(dealId: String?, event: String, reason: String, at: Instant) {
        entries += Entry(dealId, event, reason, at)
    }

    fun entries(): List<Entry> = entries.toList()

    fun forDeal(dealId: String): List<Entry> = entries.filter { it.dealId == dealId }
}

/**
 * The only place deal transitions happen (`specs/operator-backend.md` §2).
 * Wraps the canonical [DealStateMachine]: every event is validated by the
 * machine, every accepted outcome is **committed to the store before any bus
 * dispatch** (persist-before-dispatch), and every rejected event is recorded
 * in the store's append-only log, the [ViolationLog], and the event bus.
 */
class DealEngine(
    private val store: DealStore,
    private val bus: EventBus,
    val violations: ViolationLog = ViolationLog(),
    private val clock: () -> Instant = Instant::now,
) {

    sealed interface Result {
        data class Advanced(val to: DealState) : Result
        data object Aborted : Result
        data class Violation(val reason: String) : Result
    }

    /** Registers a fresh QUOTED deal; duplicate ids are an invariant violation. */
    fun createDeal(record: DealRecord, at: Instant = clock()): Result {
        if (store.getDeal(record.dealId) != null) {
            return violation(record.dealId, "DEAL_CREATED", "deal already exists", at)
        }
        store.createDeal(record)
        store.appendEvent(StoredEvent(record.dealId, "DEAL_CREATED", record.state.name, at))
        return Result.Advanced(DealState.QUOTED)
    }

    /**
     * Validates [event] against the deal's current machine and, on acceptance,
     * persists the new row (state + anchors + contested flag), appends the
     * event log, and only then publishes. Rejections never mutate the row.
     */
    fun apply(dealId: String, event: DealEvent, at: Instant = clock()): Result {
        val record = store.getDeal(dealId)
            ?: return violation(null, eventKind(event), "unknown deal $dealId", at)
        val machine = DealStateMachine(
            state = record.state,
            fundedTimestamp = record.fundedAt,
            proofTimestamp = record.proofTimestamp,
            claimContested = record.contested,
        )
        return when (val outcome = machine.transition(event)) {
            is TransitionOutcome.Advanced -> {
                store.updateDeal(dealId) {
                    it.copy(
                        state = outcome.to,
                        fundedAt = outcome.machine.fundedTimestamp,
                        proofTimestamp = outcome.machine.proofTimestamp,
                        contested = outcome.machine.claimContested,
                    )
                }
                store.appendEvent(StoredEvent(dealId, eventKind(event), "→ ${outcome.to}", at))
                bus.publish(BackendEvent.DealTransitioned(dealId, record.state, outcome.to, at))
                Result.Advanced(outcome.to)
            }

            TransitionOutcome.Aborted -> {
                store.updateDeal(dealId) { it.copy(abandoned = true) }
                store.appendEvent(StoredEvent(dealId, eventKind(event), "abandoned (quote expired)", at))
                bus.publish(BackendEvent.DealAbandoned(dealId, at))
                Result.Aborted
            }

            is TransitionOutcome.Invalid -> violation(dealId, eventKind(event), outcome.reason, at)
        }
    }

    private fun violation(dealId: String?, event: String, reason: String, at: Instant): Result.Violation {
        store.appendEvent(StoredEvent(dealId, "VIOLATION", "$event: $reason", at))
        violations.log(dealId, event, reason, at)
        bus.publish(BackendEvent.InvariantViolation(dealId, event, reason, at))
        return Result.Violation(reason)
    }

    private fun eventKind(event: DealEvent): String = event::class.simpleName ?: event.toString()
}
