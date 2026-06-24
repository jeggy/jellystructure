package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DiscoverState {
    data object Loading : DiscoverState()
    data class Loaded(val data: DiscoverResponse) : DiscoverState()
    data class Error(val message: String) : DiscoverState()
}

/** R49 — Top 10 / Discover store. Loads the composed feed and patches tiles live on acquisition events. */
class DiscoverStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<DiscoverState>(DiscoverState.Loading)
    val state: StateFlow<DiscoverState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        _state.value = DiscoverState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { DiscoverState.Loaded(apiClient.getDiscover()) }
                .getOrElse { DiscoverState.Error(it.message ?: "Unknown error") }
        }
    }

    /** [silent] keeps the current feed on screen while the new one loads (R33 live refresh). */
    fun refresh(silent: Boolean = false) {
        if (!silent) { load(); return }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getDiscover() }.getOrNull()?.let { _state.value = DiscoverState.Loaded(it) }
        }
    }

    /** Patch every tile whose entry matches this record — server-pushed, no re-pull. */
    fun applyAcquisition(rec: AcquisitionRecord) {
        val cur = (_state.value as? DiscoverState.Loaded)?.data ?: return
        val rows = cur.rows.map { row ->
            row.copy(entries = row.entries.map { e ->
                val matches = (rec.tmdbId != null && rec.tmdbId == e.entry.tmdbId) || rec.itemKey == e.acquisition.itemKey
                if (matches) e.copy(acquisition = rec) else e
            })
        }
        _state.value = DiscoverState.Loaded(cur.copy(rows = rows))
    }

    /** Request a title; patch the entry from the server's returned status record. */
    fun request(listId: String, rank: Int) {
        scope.launch {
            runCatching { apiClient.requestDiscover(listId, rank) }.getOrNull()?.let { applyAcquisition(it) }
        }
    }
}
