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
 *  R49 chart Discover detail. Addressed by mediaType+tmdbId — Request rows have no rank concept.
 *
 *  [onLocalAcquisition]: bug fix — this store only patched its own state on request/language-change, so
 *  the Request tab's grid (or the Seerr search grid) you navigated here from never learned the item
 *  had been requested, and showed no badge at all on Back. Both grids already subscribe to the app's
 *  shared live-acquisition flow for server-pushed updates ([DiscoverStore]/[SeerrSearchStore]
 *  `applyAcquisition`) — forwarding this store's own locally-made changes into that same flow reaches
 *  them for free, regardless of which screen this detail was opened from. */
class DiscoverDetailStore(
    private val apiClient: TvApiClient,
    private val mediaType: String,
    private val tmdbId: Int,
    private val onLocalAcquisition: (AcquisitionRecord) -> Unit = {},
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

    /** Phase 139 — [language] is the viewer's explicit pick from [dev.jellystructure.ravilo.ui.components.RequestLanguagePicker];
     *  null lets the server resolve it (per-viewer default → kids default → catalog default) — the
     *  no-popup path when the catalog has 0-1 languages. */
    fun request(language: String? = null) {
        val cur = (_state.value as? DiscoverDetailState.Loaded)?.detail ?: return
        scope.launch {
            runCatching { apiClient.requestDiscover(mediaType, tmdbId, cur.entry.title, language) }.getOrNull()?.let {
                applyAcquisition(it)
                onLocalAcquisition(it)
            }
        }
    }

    /** Phase 139 §E — switch a still-waiting request to a different language; re-loads on success so
     *  the fresh acquisition record (new flag/waiting-state) replaces the stale one. */
    fun changeLanguage(language: String) {
        scope.launch {
            if (runCatching { apiClient.changeRequestLanguage(mediaType, tmdbId, language) }.getOrDefault(false)) {
                // Not load() — that launches its own coroutine and would race with the read below.
                val loaded = runCatching { DiscoverDetailState.Loaded(apiClient.getDiscoverItem(mediaType, tmdbId)) }
                    .getOrElse { DiscoverDetailState.Error(it.message ?: "Unknown error") }
                _state.value = loaded
                (loaded as? DiscoverDetailState.Loaded)?.detail?.acquisition?.let { onLocalAcquisition(it) }
            }
        }
    }
}
