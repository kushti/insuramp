package p2pgate.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import p2pgate.app.AppContainer
import p2pgate.app.R
import p2pgate.app.data.DealDataController

/**
 * Settings (`specs/android-app.md` §5), currently the data half: the one action
 * a privacy-minded buyer has to be able to take, and the one that has to say
 * what it destroys. Notification and transport preferences land with those
 * features; the screen is the home they go in.
 */
@Composable
fun SettingsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val data = remember(container) {
        DealDataController(container.dealRepository, container.dealKeyStore)
    }
    var confirming by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_data_title),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.settings_delete_all_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { confirming = true }) {
                    Text(stringResource(R.string.settings_delete_all))
                }
                if (done) {
                    Text(
                        stringResource(R.string.settings_delete_all_done),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_delete_all_confirm)) },
            text = { Text(stringResource(R.string.settings_delete_all_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        data.deleteAll()
                        confirming = false
                        done = true
                    }
                }) { Text(stringResource(R.string.settings_delete_all_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
