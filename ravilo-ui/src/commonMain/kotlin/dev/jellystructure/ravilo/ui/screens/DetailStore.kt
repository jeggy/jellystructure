package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.CardPlayState
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
    /** R84: phase-2 overlay — empty until /api/tv/playstate returns after the catalog paint. */
    private val _playstateOverlay = MutableStateFlow<Map<String, CardPlayState>>(emptyMap())
    val playstateOverlay: StateFlow<Map<String, CardPlayState>> = _playstateOverlay.asStateFlow()
    private var loadJob: Job? = null
    private var currentId: String? = null
    // R207 — a cancelled loadJob's `runCatching` catches its own CancellationException and keeps
    // running, so without this guard a stale job's Error/Loaded write can land AFTER a newer job's
    // and silently clobber it. Bump on every load(); a coroutine only writes _state if its own
    // generation is still current when it resumes.
    private var loadGen = 0

    /** R40/R84: when this id is already loaded (retained store, re-entry), keep it on screen and
     *  refresh silently — no Loading flash. First load (or a new id) shows Loading normally. */
    fun load(id: String) {
        if (currentId == id && _state.value is MovieDetailState.Loaded) { refreshSilent(id); return }
        currentId = id
        loadJob?.cancel()
        val gen = ++loadGen
        _playstateOverlay.value = emptyMap()
        _state.value = MovieDetailState.Loading
        loadJob = scope.launch {
            val result = runCatching { apiClient.getMovie(id) }
            if (gen != loadGen) return@launch
            val detail = result.getOrNull()
            if (detail != null) {
                _state.value = MovieDetailState.Loaded(detail)
                // Phase 2: fetch per-user playstate after catalog paints (fast, single item)
                runCatching { apiClient.getPlaystate(listOf(id)) }.getOrNull()
                    ?.let { _playstateOverlay.value = it }
            } else {
                _state.value = MovieDetailState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }

    /** R207 — retry from the Error state's Retry button. */
    fun retry() { currentId?.let { load(it) } }

    private fun refreshSilent(id: String) {
        loadJob?.cancel()
        _playstateOverlay.value = emptyMap()
        loadJob = scope.launch {
            val detail = runCatching { apiClient.getMovie(id) }.getOrNull() ?: return@launch
            _state.value = MovieDetailState.Loaded(detail)
            runCatching { apiClient.getPlaystate(listOf(id)) }.getOrNull()
                ?.let { _playstateOverlay.value = it }
        }
    }

    /** R142: mark this movie played/unplayed; patch the overlay from the server-returned authoritative state. */
    fun setPlayed(played: Boolean) {
        val id = currentId ?: return
        scope.launch {
            runCatching { apiClient.setPlayed(id, played) }.getOrNull()?.let {
                _playstateOverlay.value += it
                WatchedBus.publish(it)  // R147: flip this title's tiles on Home/Browse/Search instantly
            }
        }
    }

    /** Bug fix — "My List": the button existed with no write-through behind it at all. Same
     *  read-after-write pattern as [setPlayed]. */
    fun setFavorite(favorite: Boolean) {
        val id = currentId ?: return
        scope.launch {
            runCatching { apiClient.setFavorite(id, favorite) }.getOrNull()?.let { _playstateOverlay.value += (id to it) }
        }
    }
}

class SeriesDetailStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SeriesDetailState>(SeriesDetailState.Loading)
    val state: StateFlow<SeriesDetailState> = _state.asStateFlow()
    /** R84: phase-2 overlay — empty until /api/tv/playstate returns after the catalog paint. */
    private val _playstateOverlay = MutableStateFlow<Map<String, CardPlayState>>(emptyMap())
    val playstateOverlay: StateFlow<Map<String, CardPlayState>> = _playstateOverlay.asStateFlow()
    private var loadJob: Job? = null
    private var currentId: String? = null
    // R207 — see MovieDetailStore's identical doc comment: guards against a stale cancelled job's
    // write landing after a newer job's and clobbering it.
    private var loadGen = 0

    /** R40/R84: re-entry with the same id keeps the cached detail and refreshes silently (no flash). */
    fun load(id: String) {
        if (currentId == id && _state.value is SeriesDetailState.Loaded) { refreshSilent(id); return }
        currentId = id
        loadJob?.cancel()
        val gen = ++loadGen
        _playstateOverlay.value = emptyMap()
        _state.value = SeriesDetailState.Loading
        loadJob = scope.launch {
            val result = runCatching { apiClient.getSeries(id) }
            if (gen != loadGen) return@launch
            val detail = result.getOrNull()
            if (detail != null) {
                _state.value = SeriesDetailState.Loaded(detail)
                // Phase 2: fetch per-episode playstate after catalog paints; keyed by episode Jellyfin id.
                // Bug fix: the series' OWN id was never included here, so its series-level `favorite` flag
                // (My List) had nowhere to come from — added alongside the episodes in the same bulk call.
                val epIds = detail.seasons.flatMap { it.episodes }.map { it.id } + detail.card.id
                if (epIds.isNotEmpty()) {
                    runCatching { apiClient.getPlaystate(epIds) }.getOrNull()
                        ?.let { _playstateOverlay.value = it }
                }
            } else {
                _state.value = SeriesDetailState.Error(result.exceptionOrNull()?.message ?: "Unknown error")
            }
        }
    }

    /** R207 — retry from the Error state's Retry button. */
    fun retry() { currentId?.let { load(it) } }

    private fun refreshSilent(id: String) {
        loadJob?.cancel()
        _playstateOverlay.value = emptyMap()
        loadJob = scope.launch {
            val detail = runCatching { apiClient.getSeries(id) }.getOrNull() ?: return@launch
            _state.value = SeriesDetailState.Loaded(detail)
            val epIds = detail.seasons.flatMap { it.episodes }.map { it.id } + detail.card.id
            if (epIds.isNotEmpty()) {
                runCatching { apiClient.getPlaystate(epIds) }.getOrNull()
                    ?.let { _playstateOverlay.value = it }
            }
        }
    }

    /** R142: toggle one episode's played state; patch the overlay from the server result. */
    fun setEpisodePlayed(episodeId: String, played: Boolean) {
        scope.launch {
            runCatching { apiClient.setPlayed(episodeId, played) }.getOrNull()?.let {
                _playstateOverlay.value += it
                WatchedBus.publish(it)  // R147: also flips the series tile if this completes/uncompletes it
            }
        }
    }

    /** Bug fix — "My List": My List/Favorite is series-level, not per-episode — always writes
     *  [currentId] (the series' own id), same read-after-write pattern as [setEpisodePlayed]. */
    fun setFavorite(favorite: Boolean) {
        val id = currentId ?: return
        scope.launch {
            runCatching { apiClient.setFavorite(id, favorite) }.getOrNull()?.let { _playstateOverlay.value += (id to it) }
        }
    }
}
