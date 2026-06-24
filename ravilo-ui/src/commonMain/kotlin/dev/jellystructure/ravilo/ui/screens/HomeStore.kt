package dev.jellystructure.ravilo.ui.screens

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

sealed class HomeState {
    data object Loading : HomeState()
    data class Loaded(val feed: HomeFeed) : HomeState()
    data class Error(val message: String) : HomeState()
}

class HomeStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state.asStateFlow()
    // R49 — server-decided Top 10 tab gating (Radarr/Sonarr + per-user opt-in + non-empty lists).
    private val _discoverAvailable = MutableStateFlow(false)
    val discoverAvailable: StateFlow<Boolean> = _discoverAvailable.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

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
                    _state.value = HomeState.Loaded(result.getOrThrow())
                    return@launch
                }
                lastErr = result.exceptionOrNull()?.message ?: "Unknown error"
                if (i < 3) delay(delays[i])
            }
            _state.value = HomeState.Error(lastErr)
        }
        refreshDiscoverAvailable()
    }

    private fun refreshDiscoverAvailable() {
        scope.launch { _discoverAvailable.value = runCatching { apiClient.getDiscover().available }.getOrDefault(false) }
    }

    /**
     * [silent] = true keeps the current Loaded feed on screen and swaps in the new one when it
     * arrives (no Loading flash, scroll/focus preserved) — used for R33 live config push.
     */
    fun refresh(silent: Boolean = false) {
        if (!silent) { load(); return }
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getHome() }.getOrNull()?.let { _state.value = HomeState.Loaded(it) }
        }
        refreshDiscoverAvailable()
    }
}
