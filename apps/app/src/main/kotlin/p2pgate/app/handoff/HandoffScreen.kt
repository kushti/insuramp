package p2pgate.app.handoff

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import p2pgate.app.AppContainer
import p2pgate.app.R
import p2pgate.app.scan.ScanQr
import p2pgate.app.verify.Hex
import p2pgate.dealprotocol.HandoffRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The meeting screen (`onramp-ux.md` §2.3) — the one hard moment. The buyer
 * scans the seller's `p2pgate://handoff?m=...` QR, watches the seller count
 * the cash, then the seller signs and the buyer enters the signature halves
 * the seller's screen shows. The "safe to leave" banner appears ONLY after
 * the record verified AND was persisted AND uploaded — until then the screen
 * says plainly that leaving means no verified dispute record.
 */
@Composable
fun HandoffScreen(dealId: String, container: AppContainer) {
    val viewModel: HandoffViewModel = viewModel(key = "handoff-$dealId") {
        HandoffViewModel(dealId, container.dealRepository, container.backendClient)
    }
    val ui by viewModel.uiState.collectAsState()

    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }
    val scanLauncher = rememberLauncherForActivityResult(ScanQr) { payload ->
        payload?.let(viewModel::onQrScanned)
    }

    var sigA by remember { mutableStateOf("") }
    var sigZ by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.handoff_title), style = MaterialTheme.typography.headlineSmall)

        Button(
            onClick = {
                if (cameraGranted) scanLauncher.launch(Unit)
                else permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.handoff_scan_qr)) }

        ui.record?.let { record ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plainRecordText(record), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.handoff_watch_count),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(
                value = sigA,
                onValueChange = { sigA = it; viewModel.onSignature(it, sigZ) },
                label = { Text(stringResource(R.string.handoff_sig_a_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = sigZ,
                onValueChange = { sigZ = it; viewModel.onSignature(sigA, it) },
                label = { Text(stringResource(R.string.handoff_sig_z_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        when (val gate = ui.gate) {
            is GateOutcome.Rejected -> WarningCard(
                stringResource(R.string.handoff_rejected, stringResource(gate.reasonRes)),
            )
            GateOutcome.MissingSellerKey -> WarningCard(
                stringResource(R.string.handoff_missing_seller_key),
            )
            GateOutcome.Pending -> if (ui.record != null) {
                WarningCard(stringResource(R.string.handoff_waiting_signature))
            } else {
                WarningCard(stringResource(R.string.handoff_scan_to_begin))
            }
            GateOutcome.Verified -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.handoff_verified), fontWeight = FontWeight.Bold)
                        Text(
                            if (ui.uploaded) stringResource(R.string.handoff_saved_relayed)
                            else ui.uploadError?.let { stringResource(R.string.handoff_saved_relay_failed, it) }
                                ?: stringResource(R.string.handoff_saved_local),
                        )
                        if (ui.safeToLeave) {
                            Text(
                                stringResource(R.string.handoff_safe_to_leave),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (!ui.safeToLeave) {
            Text(
                stringResource(R.string.handoff_not_safe),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Plain-language rendering: "seller collected X EGP for deal #…, 14:32". */
@Composable
private fun plainRecordText(record: HandoffRecord): String {
    val shortId = Hex.encode(record.dealId).take(4).uppercase()
    val time = DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(record.timestamp))
    val currency = record.fiatCurrency.toString(Charsets.US_ASCII)
    return stringResource(R.string.handoff_record_plain, record.amount, currency, shortId, time)
}

@Composable
private fun WarningCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, Modifier.padding(16.dp), fontWeight = FontWeight.SemiBold)
    }
}
