package p2pgate.app.deals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import p2pgate.app.AppContainer
import p2pgate.app.R
import p2pgate.app.data.DealDataController
import p2pgate.app.deal.stateLabel
import p2pgate.app.ui.copyToClipboard
import p2pgate.dealprotocol.FiatAmounts

/**
 * "Your deals" (`specs/android-app.md` §5): every deal held on this device,
 * open ones first. The app keeps no account, so this local index is the only
 * way back into a deal after a restart — each row also carries the recovery
 * link, which is the only credential that survives losing the app.
 */
@Composable
fun DealListScreen(
    container: AppContainer,
    onOpenDeal: (String) -> Unit,
) {
    val viewModel: DealListViewModel = viewModel {
        DealListViewModel(DealDataController(container.dealRepository, container.dealKeyStore))
    }
    val ui by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.deals_title), style = MaterialTheme.typography.headlineSmall)
        if (ui.items.isEmpty()) {
            Text(stringResource(R.string.deals_empty))
        }
        ui.items.forEach { item ->
            DealRow(
                item = item,
                onOpen = { onOpenDeal(item.snapshot.dealId) },
                onCopyRecovery = { link ->
                    copyToClipboard(
                        context,
                        link,
                        context.getString(R.string.deal_recovery_title),
                    )
                },
                onDelete = { pendingDelete = item.snapshot.dealId },
            )
        }
    }

    val dealId = pendingDelete
    if (dealId != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.deals_delete_confirm)) },
            text = { Text(stringResource(R.string.deals_delete_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(dealId)
                    pendingDelete = null
                }) { Text(stringResource(R.string.deals_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DealRow(
    item: DealListItem,
    onOpen: () -> Unit,
    onCopyRecovery: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    // Both legs: the buyer typed the USDT, the rate produced the cash.
                    stringResource(
                        R.string.deals_row_amounts,
                        FiatAmounts.formatUsdt(item.snapshot.amount),
                        item.snapshot.fiatAmount,
                        item.snapshot.fiatCurrency,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                val state = item.canonicalState
                if (state != null) {
                    Text(
                        stateLabel(state),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val link = item.snapshot.recoveryLink
            if (link != null) {
                TextButton(onClick = { onCopyRecovery(link) }) {
                    Text(stringResource(R.string.deal_recovery_copy))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen) { Text(stringResource(R.string.deals_open)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.deals_delete)) }
            }
        }
    }
}
