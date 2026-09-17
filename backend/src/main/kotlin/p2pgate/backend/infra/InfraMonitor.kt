package p2pgate.backend.infra

import p2pgate.backend.bus.BackendEvent
import p2pgate.backend.bus.EventBus
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Monitored infrastructure signals, `specs/operator-backend.md` §8. */
enum class InfraSignal {
    /** Observer lag over threshold, event stream stall, or signer unreachable. */
    ORACLE_LAG,

    /** Chain tip stale beyond threshold (explorer or node source). */
    EXPLORER_SYNC,

    /** Signing/broadcast failing (vault manager heartbeat). */
    WALLET_HEALTH,

    /** AML scorer unreachable — funding halts, existing deals unaffected. */
    SCORER_REACHABLE,
}

data class SignalState(val signal: InfraSignal, val healthy: Boolean, val detail: String = "")

/** One auto-pause episode, recorded with cause and duration (§8). */
data class PauseRecord(val cause: String, val startedAt: Instant, val endedAt: Instant? = null) {
    fun duration(now: Instant): Duration? = endedAt?.let { Duration.between(startedAt, it) }
}

/**
 * Infrastructure monitor + auto-pause (`specs/operator-backend.md` §8). Verbatim
 * rule: *"never sell insurance you can't currently verify."* Any degraded
 * signal pauses the operator: the quote publisher withdraws all quotes (the
 * user feed shows none, not stale ones) and no new vaults are funded.
 * In-flight deals are unaffected — confirmations and releases queue and apply
 * on recovery. Every pause episode is recorded with cause and duration.
 */
class InfraMonitor(
    private val bus: EventBus,
    private val clock: () -> Instant = Instant::now,
) {
    private val signals = ConcurrentHashMap<InfraSignal, SignalState>()

    var paused: Boolean = false
        private set

    val pauseHistory = CopyOnWriteArrayList<PauseRecord>()

    /** Reports a signal reading and re-evaluates the pause state. */
    fun report(signal: InfraSignal, healthy: Boolean, detail: String = "") {
        signals[signal] = SignalState(signal, healthy, detail)
        reevaluate()
    }

    fun signal(signal: InfraSignal): SignalState? = signals[signal]

    fun allSignals(): List<SignalState> = InfraSignal.entries.mapNotNull { signals[it] }

    fun healthy(): Boolean = !paused

    private fun reevaluate() {
        val degraded = allSignals().filter { !it.healthy }
        if (degraded.isNotEmpty() && !paused) {
            paused = true
            val cause = degraded.joinToString(", ") { it.signal.name }
            pauseHistory += PauseRecord(cause, clock())
            bus.publish(BackendEvent.PauseChanged(true, cause, clock()))
        } else if (degraded.isEmpty() && paused) {
            paused = false
            val last = pauseHistory.lastOrNull()
            if (last != null && last.endedAt == null) {
                pauseHistory[pauseHistory.size - 1] = last.copy(endedAt = clock())
            }
            bus.publish(BackendEvent.PauseChanged(false, "", clock()))
        }
    }
}
