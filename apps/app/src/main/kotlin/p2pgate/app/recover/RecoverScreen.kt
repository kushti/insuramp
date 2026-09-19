package p2pgate.app.recover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import p2pgate.app.AppContainer
import p2pgate.app.R

/**
 * Deal recovery (`specs/android-app.md` §6.3): paste the deal link
 * (`https://<operator>/deal/<dealId>#<token>`) or the bare `dealId#token` —
 * the app re-presents the token and re-syncs. No server-side account exists
 * to lose.
 */
@Composable
fun RecoverScreen(
    container: AppContainer,
    initialLink: String?,
    onRecovered: (String) -> Unit,
) {
    val viewModel: RecoverViewModel = viewModel { RecoverViewModel(container.dealRepository) }
    val ui by viewModel.uiState.collectAsState()

    if (initialLink != null && ui.input.isEmpty()) viewModel.onInputChange(initialLink)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.recover_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.recover_instructions))

        OutlinedTextField(
            value = ui.input,
            onValueChange = viewModel::onInputChange,
            label = { Text(stringResource(R.string.recover_input_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        when (val error = ui.error) {
            RecoverError.InvalidLink ->
                Text(stringResource(R.string.recover_error_invalid_link), color = MaterialTheme.colorScheme.error)
            is RecoverError.Failed ->
                Text(
                    stringResource(R.string.recover_error_failed, error.detail ?: ""),
                    color = MaterialTheme.colorScheme.error,
                )
            null -> Unit
        }

        Button(
            onClick = viewModel::recover,
            enabled = !ui.recovering && ui.input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (ui.recovering) R.string.recover_recovering else R.string.recover_restore)) }

        ui.recoveredDealId?.let { recovered ->
            Text(stringResource(R.string.recover_restored), color = MaterialTheme.colorScheme.primary)
            LaunchedEffect(recovered) { onRecovered(recovered) }
        }
    }
}
