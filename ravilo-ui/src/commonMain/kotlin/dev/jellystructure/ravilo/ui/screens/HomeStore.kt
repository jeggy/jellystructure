package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.LiveTvChannel
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

// Phase R177 — "On now" program info rolls over at each boundary; poll independently of the (coarser,
// R33-pushed) structural home feed cache, same rationale as the Continue-Watching row's own live fetch.
private const val LIVE_TV_REFRESH_MS = 60_000L

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
    // Phase R177 — the Home "On now" row; empty when Live TV is disabled/not placed on Home
    // (HomeFeed.liveTvHome null or showOnNowRow false).
    private val _liveTvChannels = MutableStateFlow<List<LiveTvChannel>>(emptyList())
    val liveTvChannels: StateFlow<List<LiveTvChannel>> = _liveTvChannels.asStateFlow()
    private var loadJob: Job? = null
    private var liveTvPollJob: Job? = null

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
            // Bug fix: the error screen showed up too often for blips that would have cleared on
            // their own (flaky TV wifi, backend mid-restart). Retry with exponential backoff — 1 s,
            // 2 s, 4 s, 8 s, 16 s, ... doubling each time — for up to 10 attempts before giving up and
            // showing the error screen (manual Retry / Sign out from there).
            var lastErr = "Unknown error"
            var delayMs = 1_000L
            repeat(10) { attempt ->
                val result = runCatching { apiClient.getHome() }
                if (result.isSuccess) {
                    val feed = result.getOrThrow().deduped()
                    _state.value = HomeState.Loaded(feed)
                    setUpLiveTvPolling(feed)
                    return@launch
                }
                lastErr = result.exceptionOrNull()?.message ?: "Unknown error"
                if (attempt < 9) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
            _state.value = HomeState.Error(lastErr)
        }
        refreshDiscoverAvailable()
        refreshUpcomingAvailable()
    }

    /** (Re)starts the "On now" poll loop iff [feed] places it on Home; a no-op restart when the
     *  placement is unchanged just keeps the existing loop running instead of resetting its cadence. */
    private fun setUpLiveTvPolling(feed: HomeFeed) {
        val wants = feed.liveTvHome?.showOnNowRow == true
        if (!wants) {
            liveTvPollJob?.cancel(); liveTvPollJob = null
            _liveTvChannels.value = emptyList()
            return
        }
        if (liveTvPollJob?.isActive == true) return
        liveTvPollJob = scope.launch {
            while (true) {
                _liveTvChannels.value = runCatching { apiClient.getLiveTvChannels() }.getOrDefault(_liveTvChannels.value)
                delay(LIVE_TV_REFRESH_MS)
            }
        }
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
     *
     * Bug fix: a silent refresh only makes sense once something has actually loaded — it must never
     * disturb an in-flight initial load or an already-shown Error. The WS reconnect loop emits on
     * every reconnect attempt, including doomed ones from a stale/invalid device token; those used to
     * repeatedly `loadJob?.cancel()` the in-flight bounded retry-then-Error sequence (whether by
     * restarting it via `load()` or by replacing it with a one-shot attempt that does nothing on
     * failure), so the screen either never reached Error or flickered into it for under a second at a
     * time before being reset back to Loading — effectively a permanent blank/loading screen with no
     * stable, readable feedback. A silent refresh while not yet Loaded is now a no-op: the original
     * bounded sequence (or a user-initiated non-silent retry) is the only thing allowed to resolve it.
     */
    fun refresh(silent: Boolean = false) {
        if (!silent) { load(); return }
        if (_state.value !is HomeState.Loaded) return
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getHome() }.getOrNull()?.deduped()?.let {
                _state.value = HomeState.Loaded(it)
                setUpLiveTvPolling(it)
            }
        }
        refreshDiscoverAvailable()
        refreshUpcomingAvailable()
    }
}
