package p2pgate.tui.seller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import p2pgate.dealprotocol.DealState
import p2pgate.tui.common.BackendClient
import p2pgate.tui.common.Format
import p2pgate.tui.common.wire.LaneCardDto
import java.time.Instant

/**
 * Drives the operator console: owns the [SellerState], polls the backend, and
 * runs the actions the keys are bound to.
 *
 * No Compose types here on purpose — the screen observes [state] and calls these
 * methods, so every behaviour (including "what is legal in this column") is a
 * plain unit test.
 *
 * The backend stays the authority on what is allowed: `acceptOffer` rejects an
 * offer whose quote expired, `signHandoff` rejects anything past FUNDED, and the
 * reclaim scheduler owns the timeout. The console's only rule is which key does
 * what, and it refuses locally where the answer is knowable without a round trip
 * (e.g. signing a handoff record on a card that has no vault box).
 */
class SellerController(
    private val client: BackendClient,
    private val scope: CoroutineScope,
    /** Poll cadence for the lane/pool/infra/dispute panels. */
    private val pollIntervalMs: Long = 5_000L,
    private val now: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(SellerState())
    val state: StateFlow<SellerState> = _state.asStateFlow()

    private var poller: Job? = null
    private var events: Job? = null

    /** Starts the poll loop and the operator event stream. Idempotent. */
    fun start() {
        if (poller != null) return
        poller = scope.launch {
            while (isActive) {
                refresh()
                delay(pollIntervalMs)
            }
        }
        events = scope.launch {
            client.operatorEvents().collectLatest { event ->
                _state.update { current ->
                    current.copy(
                        // Newest first, capped — this is a "what just happened"
                        // ticker, not an audit log (the dashboard lane is that).
                        liveEvents = (listOf(formatEvent(event.kind, event.dealId, event.detail)) +
                            current.liveEvents).take(MAX_EVENTS),
                    )
                }
            }
        }
    }

    fun stop() {
        poller?.cancel()
        events?.cancel()
        poller = null
        events = null
    }

    /** One full refresh: lane, pool, infra, disputes and the operator's own quotes. */
    suspend fun refresh() {
        // One reading of the clock per refresh, so the recorded sync time and the
        // status-pinning decision cannot disagree by a millisecond.
        val at = now()
        _state.update { it.copy(busy = true) }
        try {
            val lane = client.lane()
            val pool = client.pool()
            val infra = client.infra()
            val disputes = client.disputes()
            val quotes = client.quotes().quotes
            _state.update { current ->
                val columns = Lanes.columns(lane.lanes)
                // On the first load, land on the first lane that actually has work
                // in it: opening a console and pressing a key to find out there is
                // nothing selected is a poor way to learn where the deals are.
                val column = if (current.columns.isEmpty()) {
                    columns.indexOfFirst { it.cards.isNotEmpty() }.coerceAtLeast(0)
                } else {
                    current.selectedColumn.coerceIn(0, (columns.size - 1).coerceAtLeast(0))
                }
                val cards = columns[column].cards
                val live = columns.sumOf { it.cards.size }
                current.copy(
                    columns = columns,
                    pool = pool,
                    infra = infra,
                    disputes = disputes,
                    quotes = quotes,
                    // Keep the cursor inside the board as cards come and go.
                    selectedColumn = column,
                    selectedRow = current.selectedRow.coerceIn(0, (cards.size - 1).coerceAtLeast(0)),
                    busy = false,
                    // A successful refresh says so. Without this the status line
                    // keeps its initial "connecting…" forever, because only the
                    // failure path wrote to it -- so the console's one feedback
                    // channel was dead on the happy path.
                    lastSyncedAt = at.toEpochMilli(),
                    status = if (current.statusPinned(at.toEpochMilli())) current.status
                    else "$live live · ${disputes.size} disputes",
                )
            }
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, status = "refresh failed: ${e.message}", statusFromActionAt = 0L) }
        }
    }

    // ---------------------------------------------------------------- cursor

    fun moveColumn(delta: Int) = _state.update { current ->
        val target = (current.selectedColumn + delta).coerceIn(0, (current.columns.size - 1).coerceAtLeast(0))
        current.copy(selectedColumn = target, selectedRow = 0)
    }

    fun moveRow(delta: Int) = _state.update { current ->
        val size = current.column?.cards?.size ?: 0
        current.copy(selectedRow = (current.selectedRow + delta).coerceIn(0, (size - 1).coerceAtLeast(0)))
    }

    // ---------------------------------------------------------------- actions

    /**
     * The keys each column answers to. Anything not listed is inert, which is
     * the point: an operator pressing `s` on a RELEASED card should see nothing
     * happen rather than a surprise transaction.
     */
    enum class Action(val key: String, val label: String) {
        ACCEPT("a", "accept offer (funds the vault)"),
        DECLINE("d", "decline offer"),
        SIGN("s", "sign handoff record (meeting)"),
        RECLAIM("r", "reclaim vault (after timeout)"),
        CONTEST("c", "contest claim with attestation"),
        INVESTIGATE("i", "mark under investigation"),
        /** Records the loss and stops contesting — the operator gives up the deal. */
        ACCEPT_LOSS("v", "accept the claim (record the loss)"),
    }

    /**
     * Runs [action] against the selected card: the network call lives here so
     * the screen only forwards a key, and a test can drive the whole thing with
     * a fake [BackendClient].
     */
    suspend fun run(action: Action) {
        val card = _state.value.selectedCard
        if (card == null) {
            say("nothing selected")
            return
        }
        val state = _state.value.column?.state
        if (action !in actionsFor(state)) {
            say("${action.label} is not available on a ${state ?: "?"} deal")
            return
        }
        if (action == Action.SIGN && card.vaultBoxId == null) {
            say("no vault box observed yet — nothing to sign against")
            return
        }
        _state.update { it.copy(busy = true, meetingQr = null) }
        val deal = Format.shortId(card.dealId)
        try {
            val message = when (action) {
                Action.ACCEPT -> {
                    val funded = client.acceptOffer(card.dealId)
                    "accepted $deal — vault funded in tx ${funded.fundTxId.take(12)}…"
                }
                Action.DECLINE -> {
                    client.declineOffer(card.dealId)
                    "declined $deal"
                }
                Action.SIGN -> {
                    val signed = client.signHandoff(card.dealId)
                    // The meeting is the one moment the seller and buyer are
                    // looking at the same screen, so the QR goes up immediately —
                    // the buyer must scan it before leaving (onramp-ux.md §2.3).
                    _state.update {
                        it.copy(meetingQr = MeetingQr(deal, signed.qrPayload))
                    }
                    "signed the handoff record for $deal — scan below before the buyer leaves"
                }
                Action.RECLAIM -> {
                    val result = client.reclaim(card.dealId)
                    if (result.reclaimed) "reclaim submitted for $deal" else "reclaim refused for $deal"
                }
                Action.CONTEST -> {
                    client.disputeAction(card.dealId, "contest")
                    "contested $deal with the attestation"
                }
                Action.INVESTIGATE -> {
                    client.disputeAction(card.dealId, "investigate")
                    "marked $deal under investigation"
                }
                Action.ACCEPT_LOSS -> {
                    client.disputeAction(card.dealId, "accept")
                    "accepted the claim on $deal — the loss is recorded"
                }
            }
            say(message)
            refresh()
        } catch (e: Exception) {
            // The backend's own rejection wording is what the operator needs.
            say("${action.label} refused: ${e.message}")
            _state.update { it.copy(busy = false) }
        }
    }

    /** Clears the meeting QR panel (any key dismisses it). */
    fun dismissMeetingQr() = _state.update { it.copy(meetingQr = null) }

    /**
     * Writes the status line. Everything here is an *action* outcome, so the line
     * is pinned against the refresh tick for [ACTION_STATUS_MS].
     */
    private fun say(message: String) = _state.update {
        // The *injected* clock, not System.currentTimeMillis(): the pin is
        // compared against the same clock, and mixing the two makes the
        // difference negative -- so the pin would never expire.
        it.copy(status = message, statusFromActionAt = now().toEpochMilli())
    }

    private fun formatEvent(kind: String, dealId: String?, detail: String): String {
        val who = dealId?.let { " ${it.take(8)}…" } ?: ""
        return "$who $kind — $detail"
    }

    companion object {
        const val MAX_EVENTS = 8

        /** How long an action's status message survives the refresh tick. */
        const val ACTION_STATUS_MS = 8_000L


        /**
         * What the operator may do to a card in [state] — see [Action]. Pure, so
         * the key hints and the legality rules cannot drift apart.
         */
        fun actionsFor(state: DealState?): List<Action> = when (state) {
            DealState.QUOTED -> listOf(Action.ACCEPT, Action.DECLINE)
            DealState.FUNDED -> listOf(Action.SIGN, Action.RECLAIM)
            DealState.CLAIM_OPENED, DealState.CLAIMABLE ->
                listOf(Action.CONTEST, Action.INVESTIGATE, Action.ACCEPT_LOSS)
            else -> emptyList()
        }
    }
}
