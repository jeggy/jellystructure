package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class PlayerSessionState {
    data object Idle : PlayerSessionState()
    data object Loading : PlayerSessionState()
    data class Ready(val ticket: StreamTicket) : PlayerSessionState()
    data class Error(val message: String) : PlayerSessionState()
}

private const val PROGRESS_INTERVAL_MS = 10_000L

class PlayerStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<PlayerSessionState>(PlayerSessionState.Idle)
    val state: StateFlow<PlayerSessionState> = _state.asStateFlow()

    private var progressJob: Job? = null
    private var currentItemId: String? = null

    fun startSession(itemId: String, positionProvider: () -> Long, isPausedProvider: () -> Boolean) {
        currentItemId = itemId
        _state.value = PlayerSessionState.Loading
        scope.launch {
            _state.value = runCatching {
                val ticket = apiClient.startPlayback(
                    itemId = itemId,
                    capabilities = ClientCapabilities(
                        containers = listOf("mkv", "mp4", "avi", "mov"),
                        videoCodecs = listOf("h264", "hevc", "vp9", "av1"),
                        audioCodecs = listOf("aac", "mp3", "flac", "opus", "ac3", "eac3"),
                        maxAudioChannels = 8,
                    ),
                )
                startHeartbeat(itemId, positionProvider, isPausedProvider)
                PlayerSessionState.Ready(ticket)
            }.getOrElse { PlayerSessionState.Error(it.message ?: "Failed to start playback") }
        }
    }

    fun stopSession(positionMs: Long) {
        progressJob?.cancel()
        val itemId = currentItemId ?: return
        scope.launch {
            runCatching { apiClient.stopPlayback(itemId, positionMs) }
        }
        _state.value = PlayerSessionState.Idle
        currentItemId = null
    }

    fun markWatched(itemId: String) {
        scope.launch { runCatching { apiClient.markPlayed(itemId, watched = true) } }
    }

    private fun startHeartbeat(
        itemId: String,
        positionProvider: () -> Long,
        isPausedProvider: () -> Boolean,
    ) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                runCatching {
                    apiClient.reportProgress(itemId, positionProvider(), isPausedProvider())
                }
            }
        }
    }
}
