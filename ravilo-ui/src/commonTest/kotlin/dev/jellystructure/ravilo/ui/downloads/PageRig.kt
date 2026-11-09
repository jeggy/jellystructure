package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOptions
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.DownloadTicket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonNull

/**
 * DEB-4071 — a [DownloadsController] over in-memory fakes, for the Downloads page's tests (common and desktop). [add] puts a download of a
 * viewer on the phone in the phase it should be in; the engine records what the page asked it to remove.
 */
internal class PageRig(var user: String? = "alice") {
    class Engine : DownloadEngine {
        private val p = MutableStateFlow<Map<String, DownloadPhase>>(emptyMap())
        override val phases: StateFlow<Map<String, DownloadPhase>> = p.asStateFlow()
        val removed = mutableListOf<String>()
        var free = 41_200_000_000L
        override fun enqueue(job: DownloadJob) { p.value = p.value + (job.id to DownloadPhase.Running(0)) }
        override fun pause(id: String) { p.value = p.value + (id to DownloadPhase.Paused(0)) }
        override fun resume(id: String) { p.value = p.value + (id to DownloadPhase.Running(0)) }
        override fun remove(id: String) { removed += id; p.value = p.value - id }
        override fun freeBytes() = free
        override fun totalBytes() = 128_000_000_000L
        override fun busy() = p.value.values.any { it is DownloadPhase.Running || it == DownloadPhase.Queued || it == DownloadPhase.WaitingServer }
        fun set(id: String, phase: DownloadPhase) { p.value = p.value + (id to phase) }
    }

    class Api : DownloadApi {
        override suspend fun options(itemId: String, mediaSourceId: String?) = DownloadOptions(emptyList())
        override suspend fun start(request: DownloadStartRequest) = DownloadTicket(
            kind = "file", quality = request.quality, url = "/u/${request.itemId}", bytes = 1L, expiresAt = 0L, itemKind = "movie", item = JsonNull,
        )
    }

    val engine = Engine()
    val library = DownloadLibrary(InMemoryDownloadIndexStorage())
    val controller = DownloadsController(
        userId = { user }, api = Api(), engine = engine, library = library, prefs = InMemoryDownloadPrefs(), baseUrl = { "https://srv" },
        scope = CoroutineScope(Dispatchers.Unconfined), now = { 1L },
    )
    private var order = 0L

    /** One download on the phone. A [DownloadPhase.Queued] one has no ticket yet; every other phase is the engine's. */
    fun add(
        viewer: String, id: String, phase: DownloadPhase, bytes: Long, title: String = "T$id", kind: String = "movie", quality: String = "original",
        height: Int? = null, seriesId: String? = null, seriesTitle: String? = null, season: Int? = null, episode: Int? = null, watched: Boolean = false,
    ) {
        val queued = phase == DownloadPhase.Queued
        library.upsert(
            viewer,
            DownloadRecord(
                itemId = id, kind = kind, title = title, seriesId = seriesId, seriesTitle = seriesTitle, season = season, episode = episode, quality = quality,
                height = height, bytes = bytes, queuedAt = order++, state = if (queued) RecordState.QUEUED else RecordState.STARTED, watched = watched,
            ),
        )
        if (!queued) engine.set(downloadId(viewer, id), phase)
        controller.refresh()
    }
}
