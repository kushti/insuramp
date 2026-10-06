package p2pgate.tui.seller

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.coroutines.launch
import p2pgate.dealprotocol.DealState
import p2pgate.tui.common.Format
import p2pgate.tui.common.wire.InfraDto
import p2pgate.tui.common.wire.LaneCardDto
import p2pgate.tui.common.wire.PoolDto
import p2pgate.tui.common.wire.QuoteDto
import java.time.Duration
import java.time.Instant

/**
 * The operator console: a kanban of the vault lane, a status strip (pool, infra,
 * quote count), the dispute inbox, the live event ticker and a status line.
 *
 * A pure function of [SellerState] — the composable keeps no state of its own, so
 * `SellerScreenSpec` renders it headless through Mosaic's test harness and
 * asserts on the text. Keys are the only input; every action goes through
 * [SellerController].
 */
@Composable
fun SellerScreen(controller: SellerController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { controller.start() }

    Column(
        modifier = Modifier.onKeyEvent { event ->
            // Modifier combos are not actions: Mosaic delivers ctrl+c as
            // key "c" with ctrl set, and without this guard an operator's
            // quit attempt would CONTEST the selected claim — or ctrl+a
            // accept an offer and fund a vault.
            if (event.ctrl || event.alt) return@onKeyEvent false
            when {
                // Any key clears the meeting QR first: the buyer has scanned it
                // (or given up), so the board comes back.
                state.meetingQr != null -> { controller.dismissMeetingQr(); true }
                // The new-ad form owns the keyboard while it is open: typing
                // must not fall through to board keys.
                state.quotePanel?.form != null -> quoteFormKey(controller, scope, event.key)
                // The quote panel's own keys, while it is open.
                state.quotePanel != null -> quotePanelKey(controller, scope, event.key)
                event.key == "q" -> { controller.toggleQuotePanel(); true }
                event.key == "Left" || event.key == "h" -> { controller.moveColumn(-1); true }
                event.key == "Right" || event.key == "l" -> { controller.moveColumn(1); true }
                event.key == "Up" || event.key == "k" -> { controller.moveRow(-1); true }
                event.key == "Down" || event.key == "j" -> { controller.moveRow(1); true }
                else -> {
                    val action = SellerController.Action.entries.firstOrNull { it.key == event.key }
                        ?: return@onKeyEvent false
                    scope.launch { controller.run(action) }
                    true
                }
            }
        },
    ) {
        Title(state)
        StatusStrip(state.pool, state.infra, state.quotes.size)
        state.meetingQr?.let { MeetingPanel(it) }
        state.quotePanel?.let { QuotePanelSection(it, state.quotes) }
        LaneBoard(state)
        Disputes(state)
        LiveEvents(state)
        StatusLine(state)
        Keys(state)
    }
}

/** Keys while the quote panel is open (no form): navigation, new, withdraw, close. */
private fun quotePanelKey(controller: SellerController, scope: kotlinx.coroutines.CoroutineScope, key: String): Boolean =
    when (key) {
        "q", "Escape" -> { controller.toggleQuotePanel(); true }
        "n" -> { controller.openQuoteForm(); true }
        "x" -> { scope.launch { controller.withdrawSelectedQuote() }; true }
        "Up", "k" -> { controller.moveQuoteCursor(-1); true }
        "Down", "j" -> { controller.moveQuoteCursor(1); true }
        else -> false
    }

/** Keys while the new-ad form is open: it owns the whole keyboard. */
private fun quoteFormKey(controller: SellerController, scope: kotlinx.coroutines.CoroutineScope, key: String): Boolean =
    when (key) {
        "Escape" -> { controller.cancelQuoteForm(); true }
        "Enter" -> { scope.launch { controller.submitQuoteForm() }; true }
        "Tab", "Down" -> { controller.formFocus(1); true }
        "Up" -> { controller.formFocus(-1); true }
        "Backspace" -> { controller.formBackspace(); true }
        else -> {
            if (key.length == 1) {
                controller.formType(key)
                true
            } else {
                false
            }
        }
    }

/**
 * The quote-ad panel: the operator's live quotes with a cursor (withdraw under
 * it), and the new-ad form when open. A section of the board, not a screen —
 * the lane keeps painting behind it.
 */
@Composable
private fun QuotePanelSection(panel: QuotePanel, quotes: List<QuoteDto>) {
    Text("QUOTES", textStyle = TextStyle.Bold)
    if (quotes.isEmpty()) Text("  none live — press n to publish one", color = Color(0x88, 0x88, 0x88))
    for ((index, quote) in quotes.withIndex()) {
        val cursor = index == panel.selected && panel.form == null
        val ttl = Format.countdown(java.time.Instant.ofEpochMilli(quote.expiresAtEpochMs))
        Text(
            value = "${if (cursor) "›" else " "}${Format.rate(quote.fiatPerUsdtMicros, quote.fiatCurrency)} " +
                "· ${Format.usdt(quote.minAmount)}–${Format.usdt(quote.maxAmount)} " +
                "· eta ${quote.etaMinutes}m · $ttl left" +
                (if (quote.lat != null) " · loc" else ""),
            textStyle = if (cursor) TextStyle.Bold else TextStyle.Empty,
        )
    }
    val form = panel.form
    if (form != null) {
        Text("  new ad:", textStyle = TextStyle.Bold)
        for ((index, name) in QuoteForm.FIELDS.withIndex()) {
            val focused = index == form.focus
            val value = form.value(index)
            Text(
                value = "  ${if (focused) "›" else " "}$name: ${if (value.isEmpty()) "·" else value}",
                textStyle = if (focused) TextStyle.Bold else TextStyle.Empty,
            )
        }
        Text("  tab/↑↓ fields · type to edit · enter publish · esc cancel", color = Color(0x88, 0x88, 0x88))
    } else {
        Text("  n new ad · x withdraw selected · j/k move · q close", color = Color(0x88, 0x88, 0x88))
    }
}

/**
 * The meeting panel. The buyer's app must hold a *verified* seller-signed record
 * before they leave with the cash (`onramp-ux.md` §2.3), so this panel is the
 * gate: it stays up until the seller dismisses it, and the record's id is shown
 * so the two sides can be shown to be talking about the same bytes.
 */
@Composable
private fun MeetingPanel(qr: MeetingQr) {
    Text("MEETING — ${Format.shortId(qr.dealId)}  ·  the buyer must scan this before leaving", textStyle = TextStyle.Bold)
    for (line in TerminalQr.renderPayload(qr.payload)) {
        Text(line)
    }
    Text("any key to dismiss", color = Color(0x88, 0x88, 0x88))
}

@Composable
private fun Title(state: SellerState) {
    Row {
        Text("P2PGATE operator", textStyle = TextStyle.Bold)
        Text("  —  vault lane")
        if (state.busy) Text("  (syncing…)", color = Color(0x88, 0x88, 0x88))
    }
}

@Composable
private fun StatusStrip(pool: PoolDto?, infra: InfraDto?, quoteCount: Int) {
    Row {
        val paused = infra?.paused == true
        Text(
            value = if (paused) " PAUSED " else " OK ",
            color = if (paused) Color(0xFF, 0x55, 0x55) else Color(0x55, 0xBB, 0x55),
            textStyle = TextStyle.Bold,
        )
        Text("  free ${pool?.free ?: "—"}   locked ${pool?.locked ?: "—"}   open ${pool?.openDeals ?: "—"}   quotes $quoteCount")
        val degraded = infra?.signals?.filter { !it.healthy }?.map { it.signal } ?: emptyList()
        if (degraded.isNotEmpty()) {
            Text("   degraded: ${degraded.joinToString(", ")}", color = Color(0xFF, 0xAA, 0x44))
        }
    }
}

@Composable
private fun LaneBoard(state: SellerState) {
    Row(modifier = Modifier.padding(vertical = 1)) {
        for ((index, column) in state.columns.withIndex()) {
            val selected = index == state.selectedColumn
            Column(modifier = Modifier.padding(horizontal = 1)) {
                Text(
                    value = "${column.title} (${column.cards.size})",
                    textStyle = if (selected) TextStyle.Bold else TextStyle.Empty,
                    color = if (selected) Color(0x9F, 0xD7, 0xFF) else Color.Unspecified,
                )
                for ((row, card) in column.cards.withIndex()) {
                    val cursor = selected && row == state.selectedRow
                    Text(
                        value = "${if (cursor) "›" else " "}${Format.usdt(card.amount)} ${card.fiatCurrency} " +
                            Format.shortId(card.dealId) + flags(card),
                        textStyle = if (cursor) TextStyle.Bold else TextStyle.Empty,
                    )
                    if (cursor) {
                        Text("   " + cardLine(card, column.state))
                    }
                }
                if (column.cards.isEmpty()) {
                    Text("  ·")
                }
            }
        }
    }
}

/** Compact state flags — what an operator needs before opening the card. */
internal fun flags(card: LaneCardDto): String {
    val out = buildList {
        if (card.contested) add("contested")
        if (card.hasHandoffRecord) add("cash-in")
        if (card.vaultBoxId == null) add("no-box")
    }
    return if (out.isEmpty()) "" else " [" + out.joinToString(",") + "]"
}

/**
 * The line under the cursor: the one deadline that matters in this column
 * (`specs/vault-contract.md` §2 owns the numbers). Pure, so the wording is
 * snapshot-tested rather than eyeballed.
 */
internal fun cardLine(card: LaneCardDto, state: DealState, now: Instant = Instant.now()): String =
    when (state) {
        DealState.QUOTED ->
            "offer expires in ${Format.countdown(at(card.offerExpiresAtEpochMs), now)}"
        DealState.FUNDED ->
            "funded ${Format.elapsed(at(card.fundedAtEpochMs))} ago · " +
                "reclaim in ${Format.countdown(at(card.reclaimDeadlineEpochMs), now)}"
        DealState.PAYMENT_PENDING ->
            "cash collected${if (card.hasHandoffRecord) "" else " (no record yet)"} · " +
                "reclaim in ${Format.countdown(at(card.reclaimDeadlineEpochMs), now)}"
        DealState.PAYMENT_CONFIRMED ->
            "usdt transfer seen — the release follows the oracle's attestation"
        DealState.CLAIM_OPENED, DealState.CLAIMABLE ->
            "claim matures in ${Format.countdown(at(card.claimMaturesAtEpochMs), now)}" +
                if (card.contested) " · contested" else ""
        else -> "closed · exit ${card.exitPath}"
    }

private fun at(epochMs: Long?): Instant? = epochMs?.let { Instant.ofEpochMilli(it) }

@Composable
private fun Disputes(state: SellerState) {
    if (state.disputes.isEmpty()) {
        Text("disputes: none", color = Color(0x88, 0x88, 0x88))
        return
    }
    Text("disputes (${state.disputes.size})", textStyle = TextStyle.Bold)
    for (row in state.disputes.take(3)) {
        val marks = buildList {
            if (row.contested) add("contested")
            if (row.oracleConfirmed) add("oracle-confirmed")
            if (row.actioned) add("actioned:${row.action}")
        }
        Text(
            value = "  ${Format.shortId(row.dealId)} ${row.state} matures in " +
                Format.countdown(Instant.ofEpochMilli(row.maturesAtEpochMs)) +
                if (marks.isEmpty()) "" else " [${marks.joinToString(",")}]",
        )
    }
}

@Composable
private fun LiveEvents(state: SellerState) {
    if (state.liveEvents.isEmpty()) return
    Spacer()
    for (line in state.liveEvents.take(3)) {
        Text("  $line", color = Color(0x88, 0xCC, 0x88))
    }
}

@Composable
private fun StatusLine(state: SellerState, now: Instant = Instant.now()) {
    Text("│ ${state.status}", textStyle = TextStyle.Bold)
    // How old the board is. An operator watching a lane needs to know whether
    // what they see is current; "synced 4s ago" answers that and a wall clock
    // does not. Staleness beyond a few poll intervals is worth flagging, since it
    // usually means the chain watcher behind the backend has fallen behind.
    val syncedAt = state.lastSyncedAt.takeIf { it != 0L }?.let { Instant.ofEpochMilli(it) }
    val age = Format.elapsed(syncedAt, now)
    val stale = syncedAt != null && Format.elapsed(syncedAt, now).let {
        Duration.between(syncedAt, now).seconds > STALE_AFTER_SECONDS
    }
    Text("  synced $age ago", color = if (stale) Color(0xE0, 0x90, 0x40) else Color(0x88, 0x88, 0x88))
}

/** Board data older than this is worth showing in amber. */
private const val STALE_AFTER_SECONDS = 30L

@Composable
private fun Keys(state: SellerState) {
    val available = SellerController.actionsFor(state.column?.state)
        .joinToString("  ") { "${it.key}=${it.label}" }
    Text("←→ columns  ↑↓ cards  $available  q=quotes", color = Color(0x88, 0x88, 0x88))
}
