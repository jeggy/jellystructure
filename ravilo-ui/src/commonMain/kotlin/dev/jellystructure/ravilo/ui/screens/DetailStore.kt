package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.MovieDetail
import dev.jellystructure.shared.tv.SeriesDetail
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class MovieDetailState {
    data object Loading : MovieDetailState()
    data class Loaded(val detail: MovieDetail) : MovieDetailState()
    data class Error(val message: String) : MovieDetailState()
}

sealed class SeriesDetailState {
    data object Loading : SeriesDetailState()
    data class Loaded(val detail: SeriesDetail) : SeriesDetailState()
    data class Error(val message: String) : SeriesDetailState()
}

class MovieDetailStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<MovieDetailState>(MovieDetailState.Loading)
    val state: StateFlow<MovieDetailState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var currentId: String? = null

    /** R40: when this id is already loaded (retained store, re-entry), keep it on screen and refresh
     *  silently — no Loading flash. First load (or a new id) shows Loading normally. */
    fun load(id: String) {
        if (currentId == id && _state.value is MovieDetailState.Loaded) { refreshSilent(id); return }
        currentId = id
        loadJob?.cancel()
        _state.value = MovieDetailState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { MovieDetailState.Loaded(apiClient.getMovie(id)) }
                .getOrElse { MovieDetailState.Error(it.message ?: "Unknown error") }
        }
    }

    private fun refreshSilent(id: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getMovie(id) }.getOrNull()?.let { _state.value = MovieDetailState.Loaded(it) }
        }
    }
}

class SeriesDetailStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SeriesDetailState>(SeriesDetailState.Loading)
    val state: StateFlow<SeriesDetailState> = _state.asStateFlow()
    private var loadJob: Job? = null
    private var currentId: String? = null

    /** R40: re-entry with the same id keeps the cached detail and refreshes silently (no flash). */
    fun load(id: String) {
        if (currentId == id && _state.value is SeriesDetailState.Loaded) { refreshSilent(id); return }
        currentId = id
        loadJob?.cancel()
        _state.value = SeriesDetailState.Loading
        loadJob = scope.launch {
            _state.value = runCatching { SeriesDetailState.Loaded(apiClient.getSeries(id)) }
                .getOrElse { SeriesDetailState.Error(it.message ?: "Unknown error") }
        }
    }

    private fun refreshSilent(id: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { apiClient.getSeries(id) }.getOrNull()?.let { _state.value = SeriesDetailState.Loaded(it) }
        }
    }
}
