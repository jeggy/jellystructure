package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.DiscoverDetail
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DiscoverDetailState {
    data object Loading : DiscoverDetailState()
    data class Loaded(val detail: DiscoverDetail) : DiscoverDetailState()
    data class Error(val message: String) : DiscoverDetailState()
}

/** R171 — the dedicated Request detail (separate from the R13 library detail), replacing the retired
 *  R49 chart Discover detail. Addressed by mediaType+tmdbId — Request rows have no rank concept. */
class DiscoverDetailStore(
    private val apiClient: TvApiClient,
    private val mediaType: String,
    private val tmdbId: Int,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<DiscoverDetailState>(DiscoverDetailState.Loading)
    val state: StateFlow<DiscoverDetailState> = _state.asStateFlow()

    init { load() }

    fun load() {
        scope.launch {
            _state.value = runCatching { DiscoverDetailState.Loaded(apiClient.getDiscoverItem(mediaType, tmdbId)) }
                .getOrElse { DiscoverDetailState.Error(it.message ?: "Unknown error") }
        }
    }

    fun applyAcquisition(rec: AcquisitionRecord) {
        val cur = (_state.value as? DiscoverDetailState.Loaded)?.detail ?: return
        val matches = (rec.tmdbId != null && rec.tmdbId == cur.entry.tmdbId) || rec.itemKey == cur.acquisition.itemKey
        if (matches) _state.value = DiscoverDetailState.Loaded(cur.copy(acquisition = rec))
    }

    fun request() {
        val cur = (_state.value as? DiscoverDetailState.Loaded)?.detail ?: return
        scope.launch {
            runCatching { apiClient.requestDiscover(mediaType, tmdbId, cur.entry.title) }.getOrNull()?.let { applyAcquisition(it) }
        }
    }
}
