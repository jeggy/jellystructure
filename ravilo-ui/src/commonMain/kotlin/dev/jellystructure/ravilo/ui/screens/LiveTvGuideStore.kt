package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.components.loadErrorKindOf
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvGuideProgram
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LiveTvGuideState {
    data object Loading : LiveTvGuideState()
    data class Loaded(val channels: List<LiveTvChannel>, val programs: List<LiveTvGuideProgram>) : LiveTvGuideState()
    data class Error(val message: String, val kind: LoadErrorKind = LoadErrorKind.GENERIC) : LiveTvGuideState()
}

/** Phase R177 §C — the full EPG guide grid: channels × time. Category filtering is channel-level
 *  (dev-review addendum B — Jellyfin's guide carries no per-program category data). */
class LiveTvGuideStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<LiveTvGuideState>(LiveTvGuideState.Loading)
    val state: StateFlow<LiveTvGuideState> = _state.asStateFlow()

    // User request: the guide should scroll 4h into the past as well as 2 days into the future.
    fun load(days: Int = 2, hoursBack: Int = 4) {
        _state.value = LiveTvGuideState.Loading
        scope.launch {
            // R280 (FR-R280-2) — the failure is classified, not replaced by a sentence this store
            // invented. `getOrNull()` used to throw the cause away before anyone could look at it.
            val attempt = runCatching { apiClient.getLiveTvChannels() }
            val channels = attempt.getOrNull()
            if (channels == null) {
                val cause = attempt.exceptionOrNull()
                _state.value = LiveTvGuideState.Error(cause?.message ?: "", loadErrorKindOf(cause))
                return@launch
            }
            val programs = runCatching { apiClient.getLiveTvGuide(days, hoursBack) }.getOrDefault(emptyList())
            _state.value = LiveTvGuideState.Loaded(channels, programs)
        }
    }
}
