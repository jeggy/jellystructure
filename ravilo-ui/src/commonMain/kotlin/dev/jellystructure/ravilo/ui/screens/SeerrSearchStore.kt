package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.DiscoverEntry
import dev.jellystructure.shared.tv.SeerrSearchResults
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SeerrSearchState {
    data object Loading : SeerrSearchState()
    data class Loaded(val results: SeerrSearchResults) : SeerrSearchState()
    data class Error(val message: String) : SeerrSearchState()
}

/**
 * R171 — search scoped to the Seerr catalogue only (FR-R171-3), a parallel to [SearchStore] rather than
 * an extension of it: results are request tiles (with a live status badge), never local library items,
 * so they need their own state machine and result type ([DiscoverEntry], not `MediaCard`).
 */
class SeerrSearchStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SeerrSearchState>(SeerrSearchState.Loaded(SeerrSearchResults("")))
    val state: StateFlow<SeerrSearchState> = _state.asStateFlow()
    private var debounceJob: Job? = null

    fun onQuery(query: String) {
        debounceJob?.cancel()
        if (query.isBlank()) {
            _state.value = SeerrSearchState.Loaded(SeerrSearchResults(""))
            return
        }
        debounceJob = scope.launch {
            delay(250)
            _state.value = SeerrSearchState.Loading
            _state.value = runCatching { SeerrSearchState.Loaded(apiClient.searchSeerr(query)) }
                .getOrElse { SeerrSearchState.Error(it.message ?: "Error") }
        }
    }

    /** Patch every tile whose entry matches this record — server-pushed, no re-pull (same pattern as
     *  [DiscoverStore.applyAcquisition]; harmless no-op if nothing here matches). */
    fun applyAcquisition(rec: AcquisitionRecord) {
        val cur = (_state.value as? SeerrSearchState.Loaded)?.results ?: return
        val items = cur.items.map { e ->
            val matches = (rec.tmdbId != null && rec.tmdbId == e.entry.tmdbId) || rec.itemKey == e.acquisition.itemKey
            if (matches) e.copy(acquisition = rec) else e
        }
        _state.value = SeerrSearchState.Loaded(cur.copy(items = items))
    }
}
