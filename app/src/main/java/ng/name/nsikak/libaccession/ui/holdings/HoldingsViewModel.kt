package ng.name.nsikak.libaccession.ui.holdings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ng.name.nsikak.libaccession.data.api.Holding
import ng.name.nsikak.libaccession.data.api.describeError
import ng.name.nsikak.libaccession.data.repo.HoldingsRepository
import ng.name.nsikak.libaccession.domain.Period
import java.util.TimeZone

/** The four statuses the server accepts. */
val HOLDING_STATUSES = listOf("in", "out", "lost", "repair")

data class HoldingsUiState(
    val query: String = "",
    val period: Period = Period.TODAY,
    val status: String? = null,
    val items: List<Holding> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** True when there may be more items than shown (the list is capped). */
    val truncated: Boolean = false,
    val loaded: Boolean = false,
)

class HoldingsViewModel(private val repo: HoldingsRepository) : ViewModel() {
    private val _ui = MutableStateFlow(HoldingsUiState())
    val ui: StateFlow<HoldingsUiState> = _ui.asStateFlow()

    private var job: Job? = null

    fun onQuery(text: String) {
        _ui.update { it.copy(query = text) }
        load(delayMs = 300) // wait for the typing to pause
    }

    fun onPeriod(period: Period) {
        _ui.update { it.copy(period = period) }
        load(0)
    }

    fun onStatus(status: String?) {
        _ui.update { it.copy(status = status) }
        load(0)
    }

    /** Fetch again with the current period, search and status (also called when the screen comes back). */
    fun reload() = load(0)

    private fun load(delayMs: Long) {
        job?.cancel()
        job = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            _ui.update { it.copy(loading = true, error = null) }
            val s = _ui.value
            try {
                val cutoff = s.period.cutoffUtc(System.currentTimeMillis(), TimeZone.getDefault())
                val window = repo.listSince(
                    cutoffUtc = cutoff,
                    q = s.query.trim().ifBlank { null },
                    status = s.status,
                    maxItems = MAX_ITEMS,
                )
                _ui.update { it.copy(items = window.items, truncated = window.truncated, loading = false, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = describeError(e)) }
            }
        }
    }

    private companion object {
        const val MAX_ITEMS = 600
    }
}
