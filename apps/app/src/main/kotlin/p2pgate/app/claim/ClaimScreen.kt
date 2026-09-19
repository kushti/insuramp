package p2pgate.app.claim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import p2pgate.app.AppContainer
import p2pgate.app.R

/**
 * The claim path (`specs/android-app.md` §3.4): calm, first-class, never a
 * support ticket. Available once cash is collected; the expected wait
 * (CLAIM_MATURATION, ~12h) is disclosed up front; the guide from POST /claim
 * explains the on-chain steps.
 */
@Composable
fun ClaimScreen(dealId: String, container: AppContainer) {
    val viewModel: ClaimViewModel = viewModel(key = "claim-$dealId") {
        ClaimViewModel(dealId, container.dealRepository, container.backendClient)
    }
    val ui by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.claim_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.claim_intro))

        if (!ui.claimable && ui.guide == null) {
            // ui.dealState is the canonical protocol state name — never translated.
            Text(stringResource(R.string.claim_not_available, ui.dealState))
        }

        ui.maturesAtEpochMs?.let { Text(stringResource(R.string.claim_maturation_deadline, it)) }
        if (ui.contested) {
            Text(stringResource(R.string.claim_contested))
        }

        Button(
            onClick = viewModel::startClaim,
            enabled = ui.claimable && !ui.loading,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (ui.loading) R.string.claim_opening else R.string.action_start_claim)) }

        ui.error?.let { Text(stringResource(R.string.claim_error, it), color = MaterialTheme.colorScheme.error) }

        ui.guide?.let { guide ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.claim_guide_title), style = MaterialTheme.typography.titleMedium)
                    guide.instructions.forEach { Text("• $it") }
                }
            }
        }
    }
}
