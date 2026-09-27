package dev.jellystructure.bazarr

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.ops.WebhookStatus
import dev.jellystructure.subtitles.SubtitleCheckService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dev review item 1 — Jellyfin learns about one changed file, under the path Jellyfin itself sees (the library's
 * `jellyfin_path` mapping, as the *arr webhook does), and validates its one item. Never Bazarr's own Jellyfin
 * integration, which rescans whole libraries for an item it cannot find by id.
 */
internal suspend fun tellJellyfin(configStore: ConfigStore, jellyfinClient: JellyfinClient, localPath: String, updateType: String, jellyfinId: String?) {
    val cfg = configStore.current
    val k = cfg.apiKeys
    if (k.jellyfinUrl.isBlank() || k.jellyfinToken.isBlank()) return
    val lib = cfg.libraries.firstOrNull { !it.skip && it.localPath.isNotBlank() && localPath.startsWith(it.localPath) }
    val seen = if (lib == null || lib.jellyfinPath.isBlank()) localPath else localPath.replaceFirst(lib.localPath, lib.jellyfinPath)
    runCatching { jellyfinClient.notifyLibraryMediaUpdated(k.jellyfinUrl, k.jellyfinToken, seen, updateType) }
    if (!jellyfinId.isNullOrBlank()) runCatching { jellyfinClient.refreshItem(k.jellyfinUrl, k.jellyfinToken, jellyfinId, full = false) }
}

/**
 * Phase 273 (§B) — hearing about a subtitle the minute Bazarr places it. Bazarr's *Custom Post-Processing* runs a
 * command after every download, upgrade, manual download and upload; the command jellystructure generates posts
 * the file's ids to `/api/webhooks/bazarr`, which queues the call here and answers at once (Bazarr runs the command
 * inside its download worker). Bazarr's history, polled every 15 minutes, catches what the hook missed (FR-273-10).
 *
 * Items are found by Bazarr's ids — its episode record, then the series' TVDB id or the film's IMDb id — never by
 * rewriting paths: Bazarr, Jellyfin and jellystructure see the media under different roots.
 */
class SubtitleHook(
    private val store: MediaStore,
    private val configStore: ConfigStore,
    private val client: BazarrOps,
    private val steering: BazarrSteering,
    private val checks: SubtitleCheckService,
    private val jellyfinClient: JellyfinClient,
    private val scope: CoroutineScope,
) {
    /** What Bazarr's post-processing command sends (its `{{…}}` variables). */
    data class Call(
        val subtitles: String?,
        val language: String?,
        val provider: String?,
        val subtitleId: String?,
        val seriesId: String?,
        val episodeId: String?,
    )

    private val calls = Channel<Call>(Channel.UNLIMITED)
    private val seen = LinkedHashSet<String>()

    companion object {
        const val POLL_MS = 15 * 60_000L
        var current: SubtitleHook? = null
    }

    fun start() {
        current = this
        scope.launch { for (c in calls) runCatching { handle(c) }.onFailure { Logger.warn("Bazarr hook: ${it.message}", "subtitles") } }
        scope.launch {
            delay(60_000)
            primeSeen()
            while (true) {
                delay(POLL_MS)
                runCatching { poll() }.onFailure { Logger.warn("Bazarr history poll: ${it.message}", "subtitles") }
            }
        }
    }

    /** FR-273-9 — the route's half: record the call and queue it. */
    fun accept(call: Call) {
        WebhookStatus.recordArrHit("bazarr")
        calls.trySend(call)
    }

    fun lastCalledAtMs(): Long? = WebhookStatus.arrLastHit("bazarr")

    private suspend fun handle(c: Call) {
        val episodeId = c.episodeId?.trim()?.toIntOrNull() ?: return
        val (item, videoPath) = (if (c.seriesId.isNullOrBlank()) filmFor(episodeId) else episodeFor(episodeId)) ?: run {
            Logger.info("Bazarr hook: no title here for Bazarr id $episodeId", "subtitles"); return
        }
        val name = c.subtitles?.substringAfterLast('/') ?: return
        val sidecar = videoPath.substringBeforeLast('/') + "/" + name
        rememberSeen(sidecar, c.subtitleId)
        check(item, videoPath, mapOf(sidecar to SubtitleCheckService.Source(c.provider, c.subtitleId)))
    }

    private suspend fun check(item: MediaItem, videoPath: String, sources: Map<String, SubtitleCheckService.Source>) {
        val jfId = if (item.kind == MediaKind.TV_SHOW) item.episodes.firstOrNull { it.path == videoPath }?.jellyfinId else item.jellyfinId
        for (sidecar in sources.keys) tellJellyfin(configStore, jellyfinClient, sidecar, "Created", jfId)
        val out = checks.checkVideo(item, videoPath, SubtitleCheckService.Mode.INLINE, sources)
        if (out.needsJob) steering.enqueueCheck(item, videoPath)
    }

    private suspend fun episodeFor(sonarrEpisodeId: Int): Pair<MediaItem, String>? {
        val cfg = steering.service.config() ?: return null
        val ep = client.episodeById(cfg.url, cfg.apiKey, sonarrEpisodeId) ?: return null
        val series = steering.service.seriesList().firstOrNull { it.sonarrSeriesId == ep.sonarrSeriesId } ?: return null
        val items = store.allItems().filter { it.kind == MediaKind.TV_SHOW }
        val item = items.firstOrNull { series.tvdbId != null && it.tvdbId == series.tvdbId }
            ?: items.firstOrNull { it.path.trimEnd('/').substringAfterLast('/') == series.path.trimEnd('/').substringAfterLast('/') }
            ?: return null
        val file = ep.path.substringAfterLast('/')
        val unit = item.episodes.firstOrNull { it.filename == file || it.path.substringAfterLast('/') == file }
            ?: item.episodes.firstOrNull { it.seasonNumber == ep.season && it.episodeNumber == ep.episode }
            ?: return null
        return item to unit.path
    }

    private suspend fun filmFor(radarrId: Int): Pair<MediaItem, String>? {
        val cfg = steering.service.config() ?: return null
        val movie = client.movieById(cfg.url, cfg.apiKey, radarrId) ?: return null
        val items = store.allItems().filter { it.kind == MediaKind.MOVIE }
        val file = movie.path.substringAfterLast('/')
        val item = items.firstOrNull { movie.imdbId != null && it.imdbId == movie.imdbId }
            ?: items.firstOrNull { it.path.substringAfterLast('/') == file }
            ?: return null
        return item to item.path
    }

    // ── FR-273-10: the history poll ──────────────────────────────────────────────────────────

    private fun key(path: String?, subsId: String?) = "${path?.substringAfterLast('/')}|$subsId"

    private fun rememberSeen(path: String, subsId: String?) {
        seen += key(path, subsId)
        while (seen.size > 2_000) seen.remove(seen.first())
    }

    private suspend fun primeSeen() {
        val cfg = steering.service.config() ?: return
        for (r in client.episodeHistoryRows(cfg.url, cfg.apiKey, length = 100) + client.movieHistoryRows(cfg.url, cfg.apiKey, length = 100))
            seen += key(r.subtitlesPath, r.subsId)
    }

    private suspend fun poll() {
        val cfg = steering.service.config() ?: return
        for (r in client.episodeHistoryRows(cfg.url, cfg.apiKey, length = 100)) {
            if (r.action !in BazarrHistoryRow.PLACED || !seen.add(key(r.subtitlesPath, r.subsId))) continue
            calls.trySend(Call(r.subtitlesPath, r.language?.code2, r.provider, r.subsId, r.sonarrSeriesId?.toString() ?: "0", r.sonarrEpisodeId?.toString()))
        }
        for (r in client.movieHistoryRows(cfg.url, cfg.apiKey, length = 100)) {
            if (r.action !in BazarrHistoryRow.PLACED || !seen.add(key(r.subtitlesPath, r.subsId))) continue
            calls.trySend(Call(r.subtitlesPath, r.language?.code2, r.provider, r.subsId, null, r.radarrId?.toString()))
        }
    }

    /** FR-273-9 — the command for Bazarr's *Custom Post-Processing* field, with this install's address and secret. */
    fun command(): String? {
        val cfg = configStore.current
        val base = cfg.subtitleCheck.bazarrReachUrl.ifBlank { cfg.ingest.jellyfinReachUrl }.trim().trimEnd('/')
        val secret = cfg.ingest.webhookSecret
        if (base.isBlank() || secret.isBlank()) return null
        return "curl -fsS -m 5 --data-urlencode subtitles={{subtitles}} --data-urlencode language={{subtitles_language_code2}} " +
            "--data-urlencode provider={{provider}} --data-urlencode subtitle_id={{subtitle_id}} --data-urlencode series_id={{series_id}} " +
            "--data-urlencode episode_id={{episode_id}} $base/api/webhooks/bazarr?secret=$secret"
    }
}
