package ng.name.nsikak.libaccession.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng.name.nsikak.libaccession.AppContainer
import ng.name.nsikak.libaccession.data.api.ConnectionResult
import ng.name.nsikak.libaccession.data.discovery.DiscoveredServer
import ng.name.nsikak.libaccession.data.settings.ServerConfig
import ng.name.nsikak.libaccession.data.settings.SettingsStore
import ng.name.nsikak.libaccession.data.settings.parseServerAddress
import ng.name.nsikak.libaccession.data.update.UpdateInfoResponse
import ng.name.nsikak.libaccession.data.update.UpdateStatus

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Done(val result: ConnectionResult) : TestState
}

sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data object Running : DiscoveryState
    data class Found(val server: DiscoveredServer) : DiscoveryState
    data object NotFound : DiscoveryState
}

data class SettingsUiState(
    val address: String = "",
    val test: TestState = TestState.Idle,
    val discovery: DiscoveryState = DiscoveryState.Idle,
    val message: String? = null,
    val apiKey: String = "",
    val doubleTapZoom: Float = SettingsStore.DEFAULT_DOUBLE_TAP_ZOOM,
    /** Handwriting model sizes found inside the app, and the one chosen. */
    val installedModels: List<String> = emptyList(),
    val ocrModel: String = SettingsStore.DEFAULT_OCR_MODEL,
    /** Accession number formats, one per line (see AccessionFormat for the format language). */
    val accessionFormats: String = SettingsStore.DEFAULT_ACCESSION_FORMAT,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    val updateStatus: StateFlow<UpdateStatus> = container.updateRepository.updateStatus

    private var testJob: Job? = null
    private var detectJob: Job? = null
    private var updateJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = container.settings.serverConfig.first()
            val key = container.settings.apiKey.first()
            val zoom = container.settings.doubleTapZoom.first()
            val model = container.settings.ocrModel.first()
            val installed = container.paddle.installedTiers()
            _ui.update {
                it.copy(
                    address = saved.display,
                    apiKey = key,
                    doubleTapZoom = zoom,
                    installedModels = installed,
                    // show the model that is really used (the chosen one may not be installed)
                    ocrModel = if (model in installed) model else installed.firstOrNull() ?: model,
                    accessionFormats = container.settings.accessionFormats.first().joinToString("\n"),
                )
            }
        }
    }

    fun onAddressChange(value: String) = _ui.update { it.copy(address = value, test = TestState.Idle) }

    fun onApiKeyChange(value: String) = _ui.update { it.copy(apiKey = value) }

    fun onZoomChange(value: Float) = _ui.update { it.copy(doubleTapZoom = value) }

    fun onModelChange(model: String) = _ui.update { it.copy(ocrModel = model) }

    fun onFormatsChange(value: String) = _ui.update { it.copy(accessionFormats = value) }

    /** The typed address as a config, or null if it doesn't parse. */
    private fun currentConfig(): ServerConfig? = parseServerAddress(_ui.value.address)

    /** Tests the address currently typed in the form, saved or not. */
    fun testConnection() {
        val config = currentConfig() ?: return
        testJob?.cancel()
        testJob = viewModelScope.launch {
            _ui.update { it.copy(test = TestState.Running) }
            val result = container.apiFactory.checkHealth(config)
            _ui.update { it.copy(test = TestState.Done(result)) }
        }
    }

    fun save() {
        val config = currentConfig() ?: return
        viewModelScope.launch {
            container.settings.save(config)
            container.settings.saveApiKey(_ui.value.apiKey)
            container.settings.saveDoubleTapZoom(_ui.value.doubleTapZoom)
            val s = _ui.value
            container.settings.saveOcrModel(s.ocrModel)
            container.settings.saveAccessionFormats(s.accessionFormats)
            _ui.update { it.copy(address = config.display, apiKey = it.apiKey.trim(), message = "Saved") }
        }
    }

    fun resetToDefaults() {
        _ui.update { it.copy(address = ServerConfig.DEFAULT.display, test = TestState.Idle) }
    }

    fun detectServer() {
        detectJob?.cancel()
        detectJob = viewModelScope.launch {
            _ui.update { it.copy(discovery = DiscoveryState.Running) }
            val found = container.discovery.discover()
            _ui.update { s ->
                if (found != null) {
                    s.copy(
                        address = "http://${found.host}:${found.port}",
                        discovery = DiscoveryState.Found(found),
                        test = TestState.Idle,
                    )
                } else {
                    s.copy(discovery = DiscoveryState.NotFound)
                }
            }
        }
    }

    fun messageShown() = _ui.update { it.copy(message = null) }

    fun currentVersionName(): String = container.updateRepository.getCurrentVersionName()
    fun currentVersionCode(): Long = container.updateRepository.getCurrentVersionCode()

    fun checkForUpdates() {
        val config = currentConfig() ?: return
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            container.updateRepository.checkForUpdates(config)
        }
    }

    fun performUpdate(updateAvailable: UpdateStatus.UpdateAvailable) {
        val config = currentConfig() ?: return
        container.updateRepository.startBackgroundDownload(
            serverConfig = config,
            updateInfo = updateAvailable.updateInfo,
            patchInfo = updateAvailable.availablePatch
        )
    }

    fun installPreparedUpdate(ready: UpdateStatus.ReadyToInstall) {
        container.updateRepository.installUpdate(ready.apkPath)
    }

    fun canInstallPackages(): Boolean = container.updateRepository.canInstallPackages()

    fun openInstallPermissionSettings() = container.updateRepository.openInstallPermissionSettings()
}
