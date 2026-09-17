package p2pgate.backend.bus

import p2pgate.backend.store.QuoteRecord
import p2pgate.dealprotocol.DealState
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Vault transaction kinds the vault manager builds and submits. */
enum class TxKind { FUND, RECLAIM, RELEASE, CONTEST }

/**
 * Internal event bus, `specs/operator-backend.md` §2 ("Modules communicate
 * over an internal event bus; deal state transitions are the only events that
 * matter and are all persisted before dispatch"). Publication is synchronous:
 * the engine commits the store row and the event-log entry *before* publishing,
 * so a listener that dies mid-callback never leaves the store behind it.
 */
sealed interface BackendEvent {
    /** A validated, persisted state transition (terminal or not). */
    data class DealTransitioned(val dealId: String, val from: DealState, val to: DealState, val at: Instant) :
        BackendEvent

    /** QUOTED deal abandoned (quote expired / buyer ghosted) — no canonical state. */
    data class DealAbandoned(val dealId: String, val at: Instant) : BackendEvent

    /** An event the state machine rejected — logged as an invariant violation. */
    data class InvariantViolation(val dealId: String?, val event: String, val reason: String, val at: Instant) :
        BackendEvent

    data class QuotePublished(val quote: QuoteRecord, val at: Instant) : BackendEvent
    data class QuoteWithdrawn(val cause: String?, val at: Instant) : BackendEvent
    data class QuoteRejected(val reason: String, val at: Instant) : BackendEvent
    data class QuoteWarning(val reason: String, val at: Instant) : BackendEvent

    /** Auto-pause state flip (§8); [cause] names the degraded signals. */
    data class PauseChanged(val paused: Boolean, val cause: String, val at: Instant) : BackendEvent

    /** A vault tx was built and handed to the [p2pgate.backend.vault.TxSubmitter]. */
    data class TxSubmitted(val kind: TxKind, val dealId: String, val txId: String, val at: Instant) : BackendEvent

    /** Fail-loud escalation (§7). */
    data class Escalated(val dealId: String, val reason: String, val at: Instant) : BackendEvent
}

/** Synchronous in-process pub/sub. Listeners are invoked in subscription order. */
class EventBus {
    private val listeners = CopyOnWriteArrayList<(BackendEvent) -> Unit>()

    /** Subscribes [listener]; returns an unsubscribe handle. */
    fun subscribe(listener: (BackendEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun publish(event: BackendEvent) {
        listeners.forEach { it(event) }
    }
}
