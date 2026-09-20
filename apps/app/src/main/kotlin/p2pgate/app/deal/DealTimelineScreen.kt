package p2pgate.app.deal

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import p2pgate.app.AppContainer
import p2pgate.app.R
import p2pgate.app.work.DealPollWorker
import p2pgate.dealprotocol.DealState

/**
 * The deal timeline (`onramp-ux.md` §2.2) — the core screen. One vertical
 * timeline per deal, canonical state names, live via the deal WS with the
 * WorkManager polling fallback, the permanent calm dispute button, and the
 * reclaim countdown under the meeting state.
 */
@Composable
fun DealTimelineScreen(
    dealId: String,
    container: AppContainer,
    onOpenHandoff: () -> Unit,
    onOpenClaim: () -> Unit,
    onBackToQuotes: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: DealViewModel = viewModel(key = "deal-$dealId") {
        DealViewModel(
            dealId,
            container.dealRepository,
            container.backendClient,
            onStreamDrop = { DealPollWorker.schedule(context, dealId) },
        )
    }
    val ui by viewModel.uiState.collectAsState()
    val snapshot = ui?.snapshot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.deal_title, dealId.take(8)), style = MaterialTheme.typography.headlineSmall)
        if (ui?.stale == true) {
            Text(
                stringResource(R.string.deal_stale),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (snapshot == null) {
            Text(stringResource(R.string.deal_loading))
            return@Column
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.deal_fiat_amount, snapshot.fiatAmount, snapshot.fiatCurrency),
                        fontWeight = FontWeight.Bold,
                    )
                    // Localized human label, canonical protocol name in small print.
                    val canonical = ui?.canonicalState
                    if (canonical != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                stateLabel(canonical),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                snapshot.state,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            snapshot.state,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                // Collateral is locked only from FUNDED on — a pending offer
                // (QUOTED) has nothing on-chain yet.
                if (snapshot.state != "QUOTED") {
                    Text(
                        stringResource(R.string.deal_collateral_locked, snapshot.insuredAmount),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                snapshot.reclaimDeadlineEpochMs?.let { deadline ->
                    Countdown(
                        deadline,
                        runningRes = R.string.deal_timeout_running,
                        expiredRes = R.string.deal_timeout_expired,
                    )
                }
                snapshot.claimMaturesAtEpochMs?.let { matures ->
                    Countdown(
                        matures,
                        runningRes = R.string.deal_maturation_running,
                        expiredRes = R.string.deal_maturation_expired,
                    )
                }
                if (snapshot.contested) {
                    Text(stringResource(R.string.deal_contested), color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }

        ui?.timeline?.forEach { row ->
            TimelineRowView(row, snapshot.fiatAmount, snapshot.fiatCurrency, snapshot.amount)
        }

        val state = ui?.canonicalState

        // QUOTED is the pending offer: nothing is on-chain, nothing for the
        // buyer to do — show the acceptance window (the quote's expiry) and
        // say so plainly.
        if (state == DealState.QUOTED && !snapshot.terminal) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val expiry = snapshot.offerExpiresAtEpochMs
                    if (expiry != null) {
                        val minutesLeft = ((expiry - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)
                        Text(stringResource(R.string.offer_pending, minutesLeft), fontWeight = FontWeight.Bold)
                        Countdown(
                            expiry,
                            runningRes = R.string.offer_countdown_running,
                            expiredRes = R.string.offer_countdown_expired,
                        )
                    } else {
                        Text(stringResource(R.string.offer_pending_unknown), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        stringResource(R.string.offer_pending_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Declined or expired unfunded: the offer was never taken.
        if (offerNotTaken(state, snapshot.terminal)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.offer_not_taken), fontWeight = FontWeight.Bold)
                    Button(onClick = onBackToQuotes, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.offer_back_to_quotes))
                    }
                }
            }
        }

        if (state == DealState.PAYMENT_PENDING && snapshot.verifiedRecordHex == null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.deal_record_warning_title),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.deal_record_warning_body))
                    Button(onClick = onOpenHandoff, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.handoff_scan_qr))
                    }
                }
            }
        }
        if (snapshot.verifiedRecordHex != null && state == DealState.PAYMENT_PENDING) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.deal_record_verified), fontWeight = FontWeight.Bold)
                }
            }
        }

        // The permanent, calm dispute button (§3.4).
        if (state != null && DealTimeline.claimAvailable(state)) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.deal_claim_offer, snapshot.insuredAmount))
            Text(
                stringResource(R.string.deal_claim_payout_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenClaim, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_start_claim))
            }
        }

        TextButton(onClick = { DealPollWorker.cancel(context, dealId) }) {
            Text(stringResource(R.string.deal_stop_polling))
        }
    }
}

@Composable
private fun TimelineRowView(
    row: TimelineRow,
    fiatAmount: Long,
    fiatCurrency: String,
    assetAmount: Long,
) {
    val (mark, weight, color) = when (row.status) {
        RowStatus.DONE -> Triple("✓", FontWeight.Bold, MaterialTheme.colorScheme.primary)
        RowStatus.ACTIVE -> Triple("●", FontWeight.Bold, MaterialTheme.colorScheme.onSurface)
        RowStatus.PENDING -> Triple("○", FontWeight.Normal, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val display = when (row.state) {
        DealState.PAYMENT_PENDING -> stringResource(R.string.timeline_hand_cash, fiatAmount, fiatCurrency)
        DealState.PAYMENT_CONFIRMED -> stringResource(R.string.timeline_wait_usdt, assetAmount)
        else -> stringResource(row.labelRes)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(mark, color = color, fontWeight = weight)
        Spacer(Modifier.padding(4.dp))
        Text(display, fontWeight = weight, color = color)
    }
    // The canonical protocol state name is never translated.
    Text(
        row.state.name,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp),
    )
}

@Composable
private fun Countdown(deadlineEpochMs: Long, @StringRes runningRes: Int, @StringRes expiredRes: Int) {
    val parts = countdownParts(System.currentTimeMillis(), deadlineEpochMs)
    Text(
        if (parts == null) stringResource(expiredRes)
        else stringResource(runningRes, parts.hours, parts.minutes),
    )
}
