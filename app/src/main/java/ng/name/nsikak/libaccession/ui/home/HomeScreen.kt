package ng.name.nsikak.libaccession.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ng.name.nsikak.libaccession.data.api.ConnectionResult
import ng.name.nsikak.libaccession.data.update.UpdateStatus
import ng.name.nsikak.libaccession.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenHoldings: () -> Unit,
    vm: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tally") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Library server", style = MaterialTheme.typography.labelLarge)
                    Text(
                        ui.config.display,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    StatusRow(ui.status)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::recheck, enabled = ui.status != null) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Check again")
                        }
                        if (ui.status is ConnectionResult.Failed) {
                            Button(onClick = onOpenSettings) { Text("Open settings") }
                        }
                    }
                }
            }

            if (updateStatus is UpdateStatus.UpdateAvailable || updateStatus is UpdateStatus.ReadyToInstall) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("App Update Available", style = MaterialTheme.typography.titleMedium)
                        val subtitle = when (val s = updateStatus) {
                            is UpdateStatus.UpdateAvailable -> "Version ${s.updateInfo.latestVersionName} is available."
                            is UpdateStatus.ReadyToInstall -> "Update is ready to install!"
                            else -> ""
                        }
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onOpenSettings) {
                            Text("View Update in Settings")
                        }
                    }
                }
            }

            Button(onClick = onOpenScanner, modifier = Modifier.fillMaxWidth()) {
                Text("Scan a book")
            }
            Button(onClick = onOpenHoldings, modifier = Modifier.fillMaxWidth()) {
                Text("Library list")
            }
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Search the catalog (Milestone 4)")
            }
        }
    }
}

@Composable
private fun StatusRow(status: ConnectionResult?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            null -> {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Checking…")
            }
            is ConnectionResult.Ok -> {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Connected · ${status.millis} ms")
            }
            is ConnectionResult.Failed -> {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(status.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
