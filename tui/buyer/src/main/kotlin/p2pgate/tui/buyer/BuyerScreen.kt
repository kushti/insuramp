package p2pgate.tui.buyer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.ui.Spacer
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import p2pgate.tui.common.Format
import p2pgate.tui.common.wire.QuoteDto

/**
 * The buyer console: quotes, the deal it is following, the meeting gate, and the
 * claim actions.
 *
 * A pure function of [BuyerState] for the same reason as the seller console's
 * screen — the composable keeps no state of its own, so `BuyerScreenSpec` can
 * render it headless and assert on the text. The one piece of local state is the
 * captured handoff payload, because it arrives as a paste rather than a keystroke.
 */
@Composable
fun BuyerScreen(controller: BuyerController) {
    val state by controller.state.collectAsState()
    var handoffDraft by remember { mutableStateOf(HandoffDraft()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { controller.start() }

    Column(
        modifier = Modifier.padding(horizontal = 0).onKeyEvent { event ->
            // Digits pick a quote, Enter takes it. These were on screen before
            // they were bound here -- the console advertised an affordance it did
            // not have, which is worse than not showing it.
            event.key.toIntOrNull()?.let { digit ->
                if (state.deal == null && digit >= 1 && digit <= state.quotes.size) {
                    controller.selectQuote(digit - 1)
                    return@onKeyEvent true
                }
            }
            when (event.key) {
                // 'r' refreshes both the backend view and the chain facts.
                "r" -> { scope.launch { controller.refreshAll() }; true }
                "k" -> { controller.createKeyInteractively(); true }
                "f" -> { controller.forgetDeal(); true }
                "x" -> { controller.forgetHandoff(); true }
                "Enter" -> {
                    if (state.deal == null) scope.launch { controller.takeSelectedQuote() }
                    else scope.launch { controller.uploadHandoff() }
                    true
                }
                else -> false
            }
        },
    ) {
        Header(state)
        if (state.deal == null) {
            Quotes(state)
        } else {
            Deal(state)
            Meeting(state, handoffDraft) { draft ->
                handoffDraft = draft
                controller.captureHandoffInteractively(draft)
            }
            Claim(state, controller)
        }
        Key(state)
        Events(state)
        Spacer()
        StatusLine(state, handoffDraft)
        Keys(state, handoffDraft)
    }
}

@Composable
private fun Header(state: BuyerState) {
    Text("p2pgate buyer console", textStyle = TextStyle.Bold)
    val height = state.chainHeight?.let { " · chain height $it" }.orEmpty()
    val attestation = state.attestation?.status?.let { " · oracle $it" }.orEmpty()
    Text(
        when {
            state.busy -> "working…"
            state.deal != null -> "deal ${Format.shortId(state.deal.dealId)} — ${state.state?.name ?: "?"}$height$attestation"
            else -> "no deal — pick a quote below"
        },
    )
}

/**
 * The quote feed. The cash leg is shown as *derived from the rate*, because that
 * is what the backend re-derives and checks — the operator's `spreadBps` is
 * metadata and is applied nowhere.
 */
@Composable
private fun Quotes(state: BuyerState) {
    Text("")
    Text("quotes", textStyle = TextStyle.Bold)
    // The USDT address is a TRON address and is not the Ergo key -- showing both
    // side by side is the cheapest way to stop the two being confused again.
    val payout = state.payoutAddress
    Text(
        when {
            payout == null -> "  USDT goes to: NOT SET — set P2P_PAYOUT_ADDRESS (a TRON 'T…' address)"
            !p2pgate.dealprotocol.TronAddress.isValid(payout) -> "  USDT goes to: $payout  (not a valid TRON address)"
            else -> "  USDT goes to: $payout"
        },
        color = when {
            payout == null || !p2pgate.dealprotocol.TronAddress.isValid(payout) -> WARN
            else -> DIM
        },
    )
    if (state.quotes.isEmpty()) {
        Text("  (no quotes published — press r to refresh)", color = DIM)
        return
    }
    state.quotes.forEachIndexed { index, quote ->
        val cursor = if (index == state.selectedQuote) ">" else " "
        val rate = Format.rate(quote.fiatPerUsdtMicros, quote.fiatCurrency)
        Text("  $cursor ${index + 1}. ${quote.amountWindow()} · $rate · ${quote.etaMinutes} min")
    }
    // The cash leg is shown as derived from the rate, because that is exactly
    // what the backend re-derives and checks; the operator's spread is metadata.
    state.quotes.getOrNull(state.selectedQuote)?.let { quote ->
        Text("  enter · take ${quote.amountWindow()} at ${Format.rate(quote.fiatPerUsdtMicros, quote.fiatCurrency)}")
    }
}

@Composable
private fun Deal(state: BuyerState) {
    val deal = state.deal ?: return
    Text("")
    Text("deal", textStyle = TextStyle.Bold)
    Text("  you get   ${Format.usdt(deal.amount)}")
    Text("  you pay   ${Format.fiat(deal.fiatAmount, deal.fiatCurrency)} in cash")
    Text("  insured   ${Format.usdt(deal.insuredAmount)}")
    deal.vaultBoxId?.let { Text("  vault box ${Format.shortId(it)}", color = DIM) }
    deal.reclaimDeadlineEpochMs?.let { Text("  reclaim deadline ${stamp(it)}", color = DIM) }
    deal.claimMaturesAtEpochMs?.let { Text("  claim matures ${stamp(it)}", color = DIM) }
    if (deal.contested) Text("  CONTESTED by the seller", color = WARN)
    state.vaultSellerPubKey?.let { Text("  seller key (from the vault box): ${Format.shortId(it)}", color = DIM) }
    state.blocksRemaining?.let { blocks ->
        Text(
            when (blocks) {
                0 -> "  payout UNLOCKED — press p"
                else -> "  payout in $blocks blocks"
            },
            color = if (blocks == 0) GOOD else DIM,
        )
    }
}

/**
 * The meeting panel — the one hard moment (`onramp-ux.md` §2.3). The buyer pastes
 * the `p2pgate://handoff?m=…` payload and the seller's two signature halves; the
 * console verifies them against the vault box read from the chain and says
 * plainly whether it is safe to leave.
 */
@Composable
private fun Meeting(state: BuyerState, draft: HandoffDraft, onSubmit: (HandoffDraft) -> Unit) {
    Text("")
    Text("meeting", textStyle = TextStyle.Bold)
    val captured = state.handoff
    when {
        captured != null -> {
            Text("  record verified against the vault's seller key", color = GOOD)
            Text("  it is safe to leave — the record is your evidence if the USDT never arrives")
        }
        draft.payload.isBlank() -> {
            Text("  paste the seller's handoff link and press enter", color = DIM)
            Text("  payload > ", color = DIM)
            Text("")
        }
        else -> {
            Text("  payload: ${Format.shortId(draft.payload)}")
            Text("  a (66 hex) > ${draft.a}")
            Text("  z (64 hex) > ${draft.z}")
            Text("  enter to verify against the chain · esc to clear", color = DIM)
        }
    }
}

/** The paste buffer for the meeting payload — three fields, no widgets needed. */
data class HandoffDraft(
    val payload: String = "",
    val a: String = "",
    val z: String = "",
) {
    val complete: Boolean get() = payload.isNotBlank() && a.length == 66 && z.length == 64
}

@Composable
private fun Claim(state: BuyerState, controller: BuyerController) {
    Text("")
    Text("claim", textStyle = TextStyle.Bold)
    val facts = state.facts
    if (BuyerFlow.actionsFor(facts).none { it.key in setOf("o", "p", "u") }) {
        Text("  nothing to do here yet", color = DIM)
    }
    BuyerFlow.actionsFor(facts).forEach { action ->
        Text("  ${action.key} · ${action.label}", color = if (action.needsKey) DIM else Color.Unspecified)
    }
    if (!BuyerFlow.safeToLeave(facts) && state.deal?.state == "FUNDED") {
        Text("  do not leave the meeting without a verified record", color = WARN)
    }
}

@Composable
private fun Key(state: BuyerState) {
    Text("")
    Text(state.key.line, color = if (state.key.present) DIM else WARN)
    if (state.key.present && state.key.address != null && state.deal != null) {
        Text("  this key must match the vault box's buyer key (R6) to take a payout", color = DIM)
    }
}

@Composable
private fun Events(state: BuyerState) {
    if (state.events.isEmpty()) return
    Text("")
    state.events.take(5).forEach { Text("  · $it", color = DIM) }
}

@Composable
private fun StatusLine(state: BuyerState, draft: HandoffDraft) {
    val message = if (draft.payload.isNotBlank() && !draft.complete && !draft.a.isBlank()) {
        "the a half is ${draft.a.length} chars, expected 66"
    } else {
        state.status
    }
    Text("> $message", color = if (message.contains("do not") || message.contains("does not")) WARN else Color.Unspecified)
}

@Composable
private fun Keys(state: BuyerState, draft: HandoffDraft) {
    Row {
        if (state.deal == null && state.quotes.isNotEmpty()) {
            Text(" 1-${state.quotes.size} pick · enter take", color = DIM)
        }
        Text(" r refresh", color = DIM)
        Text(" k key", color = DIM)
        Text(" o open claim", color = DIM)
        Text(" p payout", color = DIM)
        Text(" f forget deal", color = DIM)
        Text(" x drop record", color = DIM)
        Text(" q quit", color = DIM)
    }
    if (draft.payload.isNotBlank()) Text(" paste mode: type the payload, a and z · enter submits", color = DIM)
}

// ------------------------------------------------------------------ helpers

/** The quote's tradeable window, already formatted — "500" or "500–2000". */
private fun QuoteDto.amountWindow(): String =
    if (minAmount == maxAmount) Format.usdt(minAmount)
    else "${Format.usdt(minAmount)}–${Format.usdt(maxAmount)}"

private fun stamp(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

private val DIM = Color(0x88, 0x88, 0x88)
private val WARN = Color(0xE0, 0x90, 0x40)
private val GOOD = Color(0x60, 0xC0, 0x60)