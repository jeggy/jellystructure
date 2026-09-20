package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.components.loadErrorKindOf
import dev.jellystructure.shared.tv.BrowseFacets
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class TaxonomyState {
    data object Loading : TaxonomyState()
    data class Loaded(val facets: BrowseFacets) : TaxonomyState()
    data class Error(val message: String, val kind: LoadErrorKind = LoadErrorKind.GENERIC) : TaxonomyState()
}

/**
 * R243 — the Studios / Networks / Genres walls' store. One fetch of `GET /api/tv/facets` (Phase 216:
 * per-viewer, normalised, count-descending, `logoUrl` only where a logo exists) feeds all three
 * segments, so switching tabs is a re-render, not a round trip. Render-never-compute (FR-R243-3):
 * nothing here counts, filters into a count, groups or dedupes — the payload is shown as handed over,
 * because the one defect FR-R243-6 exists to surface (a tile whose grid disagrees with it) would be
 * papered over by any client-side normalisation.
 */
class TaxonomyStore(private val apiClient: TvApiClient) {
    /** The tile last opened (`"<segment>:<name>"`), re-focused on Back-return; null on a fresh entry. */
    var lastSelectedKey: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<TaxonomyState>(TaxonomyState.Loading)
    val state: StateFlow<TaxonomyState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        _state.value = TaxonomyState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { TaxonomyState.Loaded(apiClient.getFacets(null)) }
                .getOrElse { TaxonomyState.Error(it.message ?: "", loadErrorKindOf(it)) }
        }
    }

    /** [silent] keeps the current wall on screen while the new one loads (R33 live refresh). */
    fun refresh(silent: Boolean = false) {
        if (!silent) { load(); return }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getFacets(null) }.getOrNull()?.let { _state.value = TaxonomyState.Loaded(it) }
        }
    }
}
