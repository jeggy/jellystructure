package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.AcquisitionRecord
import dev.jellystructure.shared.tv.DiscoverEntry
import dev.jellystructure.shared.tv.DiscoverResponse
import dev.jellystructure.shared.tv.MediaKind
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

/** R171 — Request (Seerr) store, replacing the retired R49 chart Top 10. Loads the composed feed and
 *  patches tiles live on acquisition events. */
class DiscoverStore(private val apiClient: TvApiClient) {
    /** Row index (0-based within data.rows) of the last tapped entry — used to restore scroll position on Back. */
    var lastSelectedRowIndex: Int = -1
    /** Bug fix: the exact tile (matching `StaticContentRow`'s `itemKey` format, "<feedId>:<tmdbId>") to
     *  re-focus on Back-return, so the user lands back on the tile they opened instead of the nav bar. */
    var lastSelectedItemKey: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<DiscoverState>(DiscoverState.Loading)
    val state: StateFlow<DiscoverState> = _state.asStateFlow()
    // Phase 139 §D.2 — the viewer's own not-yet-available requests ("In progress" rail). Separate flow
    // from `state` since it's a cross-feed concern, not one more configured row.
    private val _myRequests = MutableStateFlow<List<DiscoverEntry>>(emptyList())
    val myRequests: StateFlow<List<DiscoverEntry>> = _myRequests.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        _state.value = DiscoverState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { DiscoverState.Loaded(apiClient.getDiscover()) }
                .getOrElse { DiscoverState.Error(it.message ?: "Unknown error") }
            loadMyRequests()
        }
    }

    /** [silent] keeps the current feed on screen while the new one loads (R33 live refresh). */
    fun refresh(silent: Boolean = false) {
        if (!silent) { load(); return }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getDiscover() }.getOrNull()?.let { _state.value = DiscoverState.Loaded(it) }
            loadMyRequests()
        }
    }

    private suspend fun loadMyRequests() {
        runCatching { apiClient.getMyRequests() }.getOrNull()?.let { _myRequests.value = it }
    }

    /** Patch every tile whose entry matches this record — server-pushed, no re-pull. Also drops a now-
     *  AVAILABLE title off the "In progress" rail (or patches it in place otherwise) without a re-fetch. */
    fun applyAcquisition(rec: AcquisitionRecord) {
        val cur = (_state.value as? DiscoverState.Loaded)?.data ?: return
        val rows = cur.rows.map { row ->
            row.copy(entries = row.entries.map { e ->
                val matches = (rec.tmdbId != null && rec.tmdbId == e.entry.tmdbId) || rec.itemKey == e.acquisition.itemKey
                if (matches) e.copy(acquisition = rec) else e
            })
        }
        _state.value = DiscoverState.Loaded(cur.copy(rows = rows))
        _myRequests.value = _myRequests.value.mapNotNull { e ->
            val matches = (rec.tmdbId != null && rec.tmdbId == e.entry.tmdbId) || rec.itemKey == e.acquisition.itemKey
            if (!matches) e
            else if (rec.status == dev.jellystructure.shared.tv.AcquisitionStatus.AVAILABLE) null
            else e.copy(acquisition = rec)
        }
    }

    /** Request a title; patch the entry from the server's returned status record. */
    fun request(tmdbId: Int, mediaKind: MediaKind, title: String, language: String? = null) {
        val seerrKind = if (mediaKind == MediaKind.SERIES) "tv" else "movie"
        scope.launch {
            runCatching { apiClient.requestDiscover(seerrKind, tmdbId, title, language) }.getOrNull()?.let { applyAcquisition(it) }
            loadMyRequests()
        }
    }
}
