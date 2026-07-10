package dev.jellystructure.ravilo.ui.screens

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
    data class Error(val message: String) : LiveTvGuideState()
}

/** Phase R177 §C — the full EPG guide grid: channels × time. Category filtering is channel-level
 *  (dev-review addendum B — Jellyfin's guide carries no per-program category data). */
class LiveTvGuideStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<LiveTvGuideState>(LiveTvGuideState.Loading)
    val state: StateFlow<LiveTvGuideState> = _state.asStateFlow()

    fun load(days: Int = 2) {
        _state.value = LiveTvGuideState.Loading
        scope.launch {
            val channels = runCatching { apiClient.getLiveTvChannels() }.getOrNull()
            if (channels == null) {
                _state.value = LiveTvGuideState.Error("Couldn't load channels")
                return@launch
            }
            val programs = runCatching { apiClient.getLiveTvGuide(days) }.getOrDefault(emptyList())
            _state.value = LiveTvGuideState.Loaded(channels, programs)
        }
    }
}
