package ng.name.nsikak.libaccession.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import ng.name.nsikak.libaccession.data.settings.SettingsStore
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ng.name.nsikak.libaccession.data.api.ConnectionResult
import ng.name.nsikak.libaccession.data.settings.parseServerAddress
import ng.name.nsikak.libaccession.data.update.UpdateStatus
import ng.name.nsikak.libaccession.ui.AppViewModelProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val updateStatus by vm.updateStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val config = parseServerAddress(ui.address)
    val addressInvalid = ui.address.isNotBlank() && config == null
    val blockedByPolicy = config?.cleartextBlocked == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Library server", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = ui.address,
                onValueChange = vm::onAddressChange,
                label = { Text("Server address") },
                singleLine = true,
                isError = addressInvalid,
                supportingText = {
                    Text(
                        if (addressInvalid) {
                            "Not a valid address."
                        } else {
                            "LAN: 192.168.1.117:4321   Tunnel: https://name.trycloudflare.com"
                        },
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            if (config != null) {
                Text(
                    "Will connect to ${config.baseUrl}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (blockedByPolicy) {
                Text(
                    "Plain http:// to “${config?.host}” is blocked by Android because that host is not on the " +
                        "cleartext allow-list. Use an https:// address, or add the host to " +
                        "res/xml/network_security_config.xml and CleartextPolicy.ALLOWED_HOSTS, then rebuild.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Auto-detect
            OutlinedButton(
                onClick = vm::detectServer,
                enabled = ui.discovery != DiscoveryState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (ui.discovery == DiscoveryState.Running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Looking on the network…")
                } else {
                    Text("Detect server on this network")
                }
            }
            when (val d = ui.discovery) {
                is DiscoveryState.Found ->
                    Text(
                        "Found ${d.server.host}:${d.server.port} via ${d.server.via}. Test it, then Save.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                DiscoveryState.NotFound ->
                    Text(
                        "Nothing found. Is this device on the school Wi-Fi, and is Avahi advertising _library._tcp?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                else -> Unit
            }

            // Test connection
            Button(
                onClick = vm::testConnection,
                enabled = config != null && ui.test != TestState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test connection")
            }
            when (val t = ui.test) {
                TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Calling /health…")
                }
                is TestState.Done -> when (val r = t.result) {
                    is ConnectionResult.Ok -> Text(
                        "Connected · ${r.millis} ms",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    is ConnectionResult.Failed -> Text(
                        r.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TestState.Idle -> Unit
            }

            Text("Handwriting reader", style = MaterialTheme.typography.titleMedium)
            if (ui.installedModels.isEmpty()) {
                Text(
                    "No PaddleOCR model is inside this app, so the basic reader (ML Kit) is used. " +
                        "Run scripts/fetch_models.sh and rebuild to add small and medium.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                listOf("small", "medium").forEach { tier ->
                    val installed = tier in ui.installedModels
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = installed) { vm.onModelChange(tier) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = ui.ocrModel == tier,
                            onClick = { vm.onModelChange(tier) },
                            enabled = installed,
                        )
                        Column {
                            Text(
                                if (tier == "small") "Small (about 21 MB): faster" else "Medium (about 73 MB): most accurate",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (!installed) {
                                Text(
                                    "Not in this app: run scripts/fetch_models.sh $tier and rebuild.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                Text(
                    "Takes effect after Save, the next time text is read. Medium is slower to load.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("Finding a book", style = MaterialTheme.typography.titleMedium)
            Text(
                "A scanned ISBN is looked up by the library server: its own catalog first, then online catalogs " +
                    "(Google Books, Library of Congress and others, set up on the server). If nobody knows it, " +
                    "the camera can read the ISBN, title, author and accession number from the book.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Accession number formats", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ui.accessionFormats,
                onValueChange = vm::onFormatsChange,
                minLines = 2,
                label = { Text("One format per line") },
                supportingText = {
                    Text(
                        "X = a digit, @ = a letter, X+ = one or more digits; anything else is typed as it is. " +
                            "X = a digit, X+ = one or more digits. ILS-X+ matches ILS-7 and ILS-0123; ILS-SR-XX-X+ matches ILS-SR-05-123. One format per line; formats made only of digits are offered on the accession page.",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val previews = SettingsStore.parseFormatLines(ui.accessionFormats)
            Text(
                "Formats in use: ${previews.joinToString("   ")}",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Library copies", style = MaterialTheme.typography.titleMedium)
            var showKey by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = ui.apiKey,
                onValueChange = vm::onApiKeyChange,
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
                },
                supportingText = {
                    Text("Needed to add, edit or delete copies. Sent only with those requests, never with reads.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Scanner", style = MaterialTheme.typography.titleMedium)
            Text(
                "Double-tap zoom: ${"%.1f".format(ui.doubleTapZoom)}×",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = ui.doubleTapZoom,
                onValueChange = { vm.onZoomChange((it * 2f).roundToInt() / 2f) },
                valueRange = SettingsStore.MIN_DOUBLE_TAP_ZOOM..SettingsStore.MAX_DOUBLE_TAP_ZOOM,
                steps = 8,
            )
            Text(
                "Double-tap a spot on the camera to zoom to this level and read just that spot. " +
                    "A spot near the edge zooms less, so it stays in view. Double-tap again to zoom back out.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("App Version & Updates", style = MaterialTheme.typography.titleMedium)
            Text(
                "Installed: ${vm.currentVersionName()} (Build ${vm.currentVersionCode()})",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!vm.canInstallPackages()) {
                Text(
                    "Permission required: Tally needs permission to install updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                OutlinedButton(onClick = vm::openInstallPermissionSettings) {
                    Text("Grant Install Unknown Apps Permission")
                }
            }

            when (val status = updateStatus) {
                is UpdateStatus.Idle -> {
                    OutlinedButton(onClick = vm::checkForUpdates) {
                        Text("Check for Updates")
                    }
                }
                is UpdateStatus.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Checking for updates...")
                    }
                }
                is UpdateStatus.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ App is up to date.", color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        TextButton(onClick = vm::checkForUpdates) {
                            Text("Re-check")
                        }
                    }
                }
                is UpdateStatus.UpdateAvailable -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val patch = status.availablePatch
                        val updateType = if (patch != null) "Delta Patch (${patch.patchSizeBytes / 1024} KB)" else "Full APK (${status.updateInfo.fullApkSizeBytes / 1024 / 1024} MB)"
                        Text(
                            "New update available: v${status.updateInfo.latestVersionName} ($updateType)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (status.updateInfo.releaseNotes.isNotBlank()) {
                            Text(
                                status.updateInfo.releaseNotes,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = { vm.performUpdate(status) }) {
                            Text("Download & Install Update")
                        }
                    }
                }
                is UpdateStatus.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Downloading update... ${status.progressPercent}%")
                        LinearProgressIndicator(
                            progress = { status.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is UpdateStatus.Patching -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(status.statusText)
                    }
                }
                is UpdateStatus.ReadyToInstall -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Update ready to install!", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { vm.installPreparedUpdate(status) }) {
                            Text("Install Now")
                        }
                    }
                }
                is UpdateStatus.Error -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = vm::checkForUpdates) {
                            Text("Retry")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = vm::resetToDefaults) { Text("Reset to LAN default") }
                Button(onClick = vm::save, enabled = config != null) { Text("Save") }
            }
        }
    }
}
