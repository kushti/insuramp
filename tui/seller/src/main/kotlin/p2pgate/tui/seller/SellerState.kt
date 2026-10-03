package p2pgate.tui.seller

import p2pgate.dealprotocol.DealState
import p2pgate.tui.common.wire.DisputeRowDto
import p2pgate.tui.common.wire.InfraDto
import p2pgate.tui.common.wire.LaneCardDto
import p2pgate.tui.common.wire.PoolDto
import p2pgate.tui.common.wire.QuoteDto

/**
 * Everything the operator console draws, as one immutable value. The screen is a
 * pure function of this; nothing else is read while rendering. That is what makes
 * the board snapshot-testable (see `SellerScreenSpec`) and what keeps a slow
 * poll from half-painting a screen.
 */
data class SellerState(
    /** Lane columns in canonical order, each with its cards. */
    val columns: List<LaneColumn> = emptyList(),
    val pool: PoolDto? = null,
    val infra: InfraDto? = null,
    val disputes: List<DisputeRowDto> = emptyList(),
    val quotes: List<QuoteDto> = emptyList(),
    /** Column the cursor is on. */
    val selectedColumn: Int = 0,
    /** Card the cursor is on within [selectedColumn]. */
    val selectedRow: Int = 0,
    /** Transient message line (last action's outcome, or a fetch error). */
    val status: String = "connecting…",
    /**
     * Epoch millis of the last *action's* status message, or 0 when the status
     * line is free for the poll to write to. An action's outcome ("signed the
     * handoff record for abc — scan below") must survive the 5s refresh tick, or
     * the operator never reads it.
     */
    val statusFromActionAt: Long = 0L,
    /**
     * Epoch millis of the last successful refresh, 0 before the first one. The
     * screen renders this as the *age* of the board rather than a wall clock:
     * "synced 4s ago" is what tells an operator whether to trust the lane, and a
     * clock time would not.
     */
    val lastSyncedAt: Long = 0L,
    val liveEvents: List<String> = emptyList(),
    val busy: Boolean = false,
    /** The handoff QR to show at the meeting, right after signing. */
    val meetingQr: MeetingQr? = null,
) {
    val column: LaneColumn? get() = columns.getOrNull(selectedColumn)

    val selectedCard: LaneCardDto? get() = column?.cards?.getOrNull(selectedRow)

    /** True when the cursor sits on a card the operator can act on right now. */
    val hasSelection: Boolean get() = selectedCard != null

    /**
     * True when an action wrote [status] recently enough that the refresh tick
     * must not overwrite it. `at` is the current epoch millis.
     */
    fun statusPinned(at: Long, windowMs: Long = 8_000L): Boolean =
        statusFromActionAt != 0L && at - statusFromActionAt < windowMs
}

/**
 * The meeting panel: a signed handoff record rendered as a scannable QR for the
 * buyer. Held in state rather than drawn modally so the board stays visible
 * behind it — the seller is mid-deal, not in a separate flow.
 */
data class MeetingQr(
    val dealId: String,
    /** The `p2pgate://handoff?m=…` payload the backend returned. */
    val payload: String,
)

/**
 * One kanban column. [state] is the canonical `DealState` name; [title] is the
 * operator-facing label — the only place the two may differ, and only in width.
 */
data class LaneColumn(
    val state: DealState,
    val title: String,
    val cards: List<LaneCardDto> = emptyList(),
)

/**
 * Lane columns in the order an operator works them: the deal flows left to right,
 * with the terminal states last (nothing to do there but read).
 */
object Lanes {

    val ORDER: List<DealState> = listOf(
        DealState.QUOTED,
        DealState.FUNDED,
        DealState.PAYMENT_PENDING,
        DealState.PAYMENT_CONFIRMED,
        DealState.CLAIM_OPENED,
        DealState.CLAIMABLE,
        DealState.RELEASED,
        DealState.RECLAIMED,
        DealState.CLAIMED,
    )

    /** Compact labels — the full names do not fit a narrow column header. */
    val TITLES: Map<DealState, String> = mapOf(
        DealState.QUOTED to "OFFERED",
        DealState.FUNDED to "FUNDED",
        DealState.PAYMENT_PENDING to "CASH IN",
        DealState.PAYMENT_CONFIRMED to "PAID?",
        DealState.CLAIM_OPENED to "CLAIM",
        DealState.CLAIMABLE to "CLAIMABLE",
        DealState.RELEASED to "RELEASED",
        DealState.RECLAIMED to "RECLAIMED",
        DealState.CLAIMED to "CLAIMED",
    )

    /**
     * Folds the backend's `lanes` map (keyed by state name) into ordered columns.
     * Empty columns are kept so the board's shape does not jump as deals move.
     * An unknown state name is dropped rather than rendered as a column — the
     * names are canonical and a surprise one is a backend bug to see, not to
     * paper over.
     */
    fun columns(lanes: Map<String, List<LaneCardDto>>): List<LaneColumn> =
        ORDER.map { state ->
            LaneColumn(
                state = state,
                title = TITLES.getValue(state),
                cards = lanes[state.name].orEmpty(),
            )
        }
}
