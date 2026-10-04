package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionStartRequest
import dev.jellystructure.shared.tv.SessionStartResponse
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * R370 (review item 11) — the server's places while a *Play on…* sheet is open: fetched when it opens and again on
 * `targets_changed`. Null = the server cannot say (older than R370, or unreachable): the sheet keeps today's list.
 */
object PlayOnStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var api: TvApiClient? = null
    private val _targets = MutableStateFlow<List<PlaybackTarget>?>(null)
    val targets: StateFlow<List<PlaybackTarget>?> = _targets.asStateFlow()
    private var open = false

    fun opened() { open = true; refresh() }
    fun closed() { open = false }
    /** `targets_changed`, a socket open: read again (the glyph counts the server's places too — R370 review item 2). */
    fun changed() { refresh() }

    private fun refresh() {
        val a = api ?: return
        scope.launch { _targets.value = runCatching { a.playbackTargets()?.targets }.getOrNull() }
    }

    /** FR-R370-3 — start on a place; [onRefused] gets `busy` / `unreachable` / `forbidden` / `failed`. */
    fun start(req: SessionStartRequest, onStarted: (SessionStartResponse) -> Unit, onRefused: (String) -> Unit) {
        val a = api ?: return onRefused("failed")
        scope.launch {
            val result = runCatching { a.startPlaybackSession(req) }
            withContext(Dispatchers.Main) {
                result.onSuccess(onStarted).onFailure { e ->
                    val reason = (e as? TvApiError.Http)?.let { h ->
                        when (h.status) {
                            403 -> "forbidden"
                            409 -> if ("busy" in h.message) "busy" else "unreachable"
                            else -> "failed"
                        }
                    } ?: "failed"
                    onRefused(reason)
                }
            }
        }
    }
}
