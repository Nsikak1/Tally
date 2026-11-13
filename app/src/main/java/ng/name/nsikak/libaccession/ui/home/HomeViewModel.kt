package ng.name.nsikak.libaccession.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ng.name.nsikak.libaccession.AppContainer
import ng.name.nsikak.libaccession.data.api.ConnectionResult
import ng.name.nsikak.libaccession.data.settings.ServerConfig
import ng.name.nsikak.libaccession.data.update.UpdateStatus

/** [status] is null while a check is running. */
data class HomeUiState(
    val config: ServerConfig = ServerConfig.DEFAULT,
    val status: ConnectionResult? = null,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    val updateStatus: StateFlow<UpdateStatus> = container.updateRepository.updateStatus

    private var recheckJob: Job? = null

    init {
        // Re-check automatically whenever the saved server settings change.
        viewModelScope.launch {
            container.settings.serverConfig.collectLatest { config -> runCheck(config) }
        }
    }

    fun recheck() {
        recheckJob?.cancel()
        recheckJob = viewModelScope.launch { runCheck(_ui.value.config) }
    }

    private suspend fun runCheck(config: ServerConfig) {
        _ui.value = HomeUiState(config, status = null)
        val health = container.apiFactory.checkHealth(config)
        _ui.value = HomeUiState(config, status = health)
        if (health is ConnectionResult.Ok) {
            container.updateRepository.checkForUpdates(config)
        }
    }
}
