package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import dev.jellystructure.shared.tv.HomeFeed
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

private fun HomeFeed.deduped() = copy(rows = rows.map { r -> r.copy(items = r.items.distinctBy { it.id }) })

sealed class HomeState {
    data object Loading : HomeState()
    data class Loaded(val feed: HomeFeed) : HomeState()
    data class Error(val message: String) : HomeState()
}

class HomeStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()
    // R137: scroll state lives in the retained store (not remembered per-composition), so navigate→back
    // restores the feed's scroll position instead of resetting to the top.
    val listState = LazyListState()
    // R139: identity of the tile the user last navigated from, so Back re-focuses that exact tile.
    var focusRowKey: String? = null
    var focusItemKey: String? = null
    // R49/Phase 136 — server-decided Request-segment gating (now: is Seerr connected/enabled).
    private val _discoverAvailable = MutableStateFlow(false)
    val discoverAvailable: StateFlow<Boolean> = _discoverAvailable.asStateFlow()
    // R160 — server-decided Coming-Soon-segment gating ([sonarr]/[radarr] presence). R170 merges both
    // this and [discoverAvailable] into the single Discover tab — see raviloNavItems/DiscoverSegment.
    private val _upcomingAvailable = MutableStateFlow(false)
    val upcomingAvailable: StateFlow<Boolean> = _upcomingAvailable.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
        // R147: patch tiles in place when a watched-state change is broadcast (instant, no re-fetch).
        scope.launch {
            WatchedBus.patches.collect { patch ->
                val s = _state.value as? HomeState.Loaded ?: return@collect
                _state.value = HomeState.Loaded(s.feed.copy(
                    rows = s.feed.rows.map { r -> r.copy(items = r.items.map { it.applyWatchedPatch(patch) }.distinctBy { it.id }) }
                ))
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        _state.value = HomeState.Loading
        loadJob = scope.launch {
            // Retry up to 3 times (1 s, 2 s, 4 s) before emitting Error — survives cold-start
            // network not-yet-warm and first-launch activeUserId race.
            var lastErr = "Unknown error"
            val delays = longArrayOf(1_000L, 2_000L, 4_000L)
            for (i in 0..3) {
                val result = runCatching { apiClient.getHome() }
                if (result.isSuccess) {
                    _state.value = HomeState.Loaded(result.getOrThrow().deduped())
                    return@launch
                }
                lastErr = result.exceptionOrNull()?.message ?: "Unknown error"
                if (i < 3) delay(delays[i])
            }
            _state.value = HomeState.Error(lastErr)
        }
        refreshDiscoverAvailable()
        refreshUpcomingAvailable()
    }

    private fun refreshDiscoverAvailable() {
        scope.launch { _discoverAvailable.value = runCatching { apiClient.getDiscover().available }.getOrDefault(false) }
    }

    private fun refreshUpcomingAvailable() {
        scope.launch { _upcomingAvailable.value = runCatching { apiClient.getUpcoming().enabled }.getOrDefault(false) }
    }

    /**
     * [silent] = true keeps the current Loaded feed on screen and swaps in the new one when it
     * arrives (no Loading flash, scroll/focus preserved) — used for R33 live config push.
     */
    fun refresh(silent: Boolean = false) {
        if (!silent) { load(); return }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getHome() }.getOrNull()?.let { _state.value = HomeState.Loaded(it.deduped()) }
        }
        refreshDiscoverAvailable()
        refreshUpcomingAvailable()
    }
}
