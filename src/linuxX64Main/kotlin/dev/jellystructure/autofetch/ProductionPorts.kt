package dev.jellystructure.autofetch

import dev.jellystructure.log.Logger
import dev.jellystructure.OutboundHttp
import dev.jellystructure.ServerVersion
import dev.jellystructure.arr.ArrClient
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.filefix.FileFixShell
import dev.jellystructure.filefix.PosixFileFixShell
import dev.jellystructure.filefix.jellyfinViewOf
import dev.jellystructure.media.ActivityLog
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.TrackKind
import dev.jellystructure.nowEpochSec
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** FR-2.1 — every request carries the User-Agent jellystructure already sends. It never pretends to be a browser. */
object FetchIdentity {
    fun userAgent(): String = "jellystructure/${ServerVersion.current.ifBlank { "dev" }} (https://github.com/jeggy/jellystructure)"
}

/**
 * The lane's HTTP: the shared outbound client, our own User-Agent, a bounded body. The shared client follows a redirect, and the
 * reply says where it ended ([HttpReply.finalUrl]); whether that host is trusted is the lane's call (FR-2.7), not the transport's.
 */
object KtorTransport : Transport {
    override suspend fun get(url: String): HttpReply = OutboundHttp.withPermit {
        val r = OutboundHttp.client.get(url) {
            header(HttpHeaders.UserAgent, FetchIdentity.userAgent())
            header(HttpHeaders.Accept, "text/html,application/json,application/vnd.apple.mpegurl,application/dash+xml,*/*;q=0.5")
            timeout { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 30_000 }
        }
        val headers = HashMap<String, String>()
        r.headers.forEach { k, v -> headers[k] = v.joinToString(",") }
        val len = r.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val body = if (len != null && len > LaneRules.MAX_BODY_BYTES) "" else r.bodyAsText().take(LaneRules.MAX_BODY_BYTES)
        HttpReply(r.status.value, headers, body, r.request.url.toString())
    }
}

class ProductionMediaTools(private val shell: FileFixShell = PosixFileFixShell) : MediaTools {
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    private val ytdlp = ProductionYtDlp(shell)

    override suspend fun ytdlpListing(url: String): YtDlpOutput = ytdlp.listing(url, FetchIdentity.userAgent())
    override suspend fun ytdlpInfo(url: String): YtDlpOutput = ytdlp.info(url, FetchIdentity.userAgent())
    override suspend fun ytdlpDownload(d: YtDlpDownload, stop: () -> Boolean): ToolResult = ytdlp.download(d, stop)
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun ffprobeStreams(url: String): JsonObject? {
        val cmd = "ffprobe -v error -protocol_whitelist file,http,https,tcp,tls,crypto -show_format -show_streams -of json ${q(url)} 2>/dev/null"
        val out = shell.run(cmd)
        if (!out.ok) return null
        return runCatching { json.parseToJsonElement(out.output).jsonObject }.getOrNull()
    }

    override suspend fun probeManifest(url: String, text: String, kind: String): ManifestFacts? {
        val target = Manifests.parseHlsMaster(text, url)?.best()?.url ?: url
        val probe = ffprobeStreams(target) ?: return null
        val streams = probe["streams"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val hasVideo = streams.any { it["codec_type"]?.jsonPrimitive?.contentOrNull == "video" }
        val dur = probe["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull
            ?: streams.firstNotNullOfOrNull { it["duration"]?.jsonPrimitive?.doubleOrNull }
        return ManifestFacts(hasVideo, dur)
    }

    override suspend fun download(spec: DownloadSpec, stop: () -> Boolean): ToolResult {
        // FR-6.4 (DEB-2872) — a subtitle is never a reason the episode does not arrive: [withSub] carries it, [withoutSub] is the same mux without it.
        val withSub: String
        val withoutSub: String?
        var note = ""
        when (spec.kind) {
            "hls" -> {
                val master = Manifests.parseHlsMaster(spec.manifestText, spec.manifestUrl)
                if (master != null) {
                    val best = master.best() ?: return ToolResult(false, "the master playlist has no variant")
                    val audio = master.audioFor(best)?.uri
                    // The subtitle rendition is looked for on every episode, and embedded only when its first chunk holds cues.
                    val advertised = master.subtitleFor(best, spec.subtitleLang)?.uri
                    val sub = advertised?.takeIf { subtitleHasCues(it) }
                    if (advertised != null && sub == null) note = "no subtitle: the first chunk holds no cues"
                    withoutSub = FetchCommands.hlsToMkv(best.url, audio, null, spec.outPath, spec.audioLang, spec.subtitleLang)
                    withSub = if (sub != null) FetchCommands.hlsToMkv(best.url, audio, sub, spec.outPath, spec.audioLang, spec.subtitleLang) else withoutSub
                } else {
                    val streams = pickStreams(spec.manifestUrl) ?: return ToolResult(false, "ffprobe could not read the playlist")
                    withSub = FetchCommands.streamsToMkv(spec.manifestUrl, streams.video, streams.audio, spec.outPath, spec.audioLang, spec.subtitleLang, null)
                    withoutSub = null
                }
            }
            "dash" -> {
                val streams = pickStreams(spec.manifestUrl) ?: return ToolResult(false, "ffprobe could not read the manifest")
                withSub = FetchCommands.streamsToMkv(spec.manifestUrl, streams.video, streams.audio, spec.outPath, spec.audioLang, spec.subtitleLang, streams.subtitle)
                withoutSub = if (streams.subtitle != null) FetchCommands.streamsToMkv(spec.manifestUrl, streams.video, streams.audio, spec.outPath, spec.audioLang, spec.subtitleLang, null) else null
            }
            else -> return ToolResult(false, "unsupported media kind ${spec.kind}")
        }
        val first = shell.runStoppable(withSub, spec.outPath, stop) ?: return ToolResult(false, "stopped")
        if (first.ok && shell.exists(spec.outPath)) return ToolResult(true, note)
        val firstWhy = lastLine(first.output)
        if (withoutSub == null || withoutSub == withSub) return ToolResult(false, "ffmpeg exit ${first.exit}: $firstWhy")
        // The mux broke with the subtitle input in it: run it once more without, and the file that comes out is the episode.
        shell.remove(spec.outPath)
        val second = shell.runStoppable(withoutSub, spec.outPath, stop) ?: return ToolResult(false, "stopped")
        return if (second.ok && shell.exists(spec.outPath)) ToolResult(true, "no subtitle: ffmpeg could not mux it: $firstWhy")
        else ToolResult(false, "ffmpeg exit ${second.exit}: ${lastLine(second.output)}")
    }

    private fun lastLine(output: String) = output.lines().lastOrNull { it.isNotBlank() }.orEmpty()

    private data class Picked(val video: Int, val audio: Int?, val subtitle: Int?)

    /** DASH and plain media playlists: the highest video stream (pixels, then bitrate), the highest audio, the first subtitle. */
    private suspend fun pickStreams(url: String): Picked? {
        val probe = ffprobeStreams(url) ?: return null
        val streams = probe["streams"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        fun idx(o: JsonObject) = o["index"]?.jsonPrimitive?.intOrNull ?: 0
        fun num(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val video = streams.filter { it["codec_type"]?.jsonPrimitive?.contentOrNull == "video" }
            .maxWithOrNull(compareBy({ num(it, "width") * num(it, "height") }, { num(it, "bit_rate") })) ?: return null
        val audio = streams.filter { it["codec_type"]?.jsonPrimitive?.contentOrNull == "audio" }.maxByOrNull { num(it, "bit_rate") }
        val sub = streams.firstOrNull { it["codec_type"]?.jsonPrimitive?.contentOrNull == "subtitle" }
        return Picked(idx(video), audio?.let { idx(it) }, sub?.let { idx(it) })
    }

    /** FR-6.4 — not just the playlist: the first chunk must hold cues. */
    private suspend fun subtitleHasCues(playlistUrl: String): Boolean = runCatching {
        OutboundHttp.withPermit {
            val pl = OutboundHttp.client.get(playlistUrl) { header(HttpHeaders.UserAgent, FetchIdentity.userAgent()) }.bodyAsText()
            val seg = pl.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@withPermit false
            val segUrl = Urls.resolve(playlistUrl, seg) ?: return@withPermit false
            OutboundHttp.client.get(segUrl) { header(HttpHeaders.UserAgent, FetchIdentity.userAgent()) }.bodyAsText().contains("-->")
        }
    }.getOrDefault(false)

    override suspend fun inspect(path: String): FileFacts? {
        val tracks = FfprobeRunner.probe(path)
        if (tracks.isEmpty()) return null
        val dur = FfmpegRunner.probeDurationSeconds(path)
        return FileFacts(
            durationSec = dur,
            audioLanguages = tracks.filter { it.kind == TrackKind.AUDIO }.mapNotNull { it.language },
            subtitleLanguages = tracks.filter { it.kind == TrackKind.SUBTITLE }.mapNotNull { it.language },
            height = tracks.firstOrNull { it.kind == TrackKind.VIDEO }?.height,
            sizeBytes = shell.stat(path)?.first,
        )
    }

    override suspend fun firstTopLevel(path: String): String? {
        val out = shell.run(FetchCommands.firstTopLevel(path)).output
        return when {
            out.contains("Tracks") -> "Tracks"
            out.contains("Cluster") -> "Cluster"
            else -> null
        }
    }

    /** FR-9.4's rule for the one frame a page-only episode gets: the middle 80 % of the runtime, away from the first and last minute, not black. */
    override suspend fun thumbnail(path: String, durationSec: Double, out: String): Boolean {
        val (lo, hi) = stillWindow(durationSec)
        for (f in listOf(0.5, 0.35, 0.65, 0.25, 0.75)) {
            if (cut(path, (durationSec * f).coerceIn(lo, hi).toInt(), out)) return true
        }
        return false
    }

    /** The middle 80 % of the runtime, and outside the first and last minute where a title card and the credits sit (FR-9.4). */
    private fun stillWindow(durationSec: Double): Pair<Double, Double> =
        if (durationSec > 150) maxOf(60.0, durationSec * 0.1) to minOf(durationSec - 60.0, durationSec * 0.9) else durationSec * 0.1 to durationSec * 0.9

    /** One frame, kept only when it is not (near) black; a rejected frame is removed. */
    private suspend fun cut(path: String, atSec: Int, out: String): Boolean {
        val r = shell.run(FetchCommands.frame(path, atSec, out))
        val yavg = Regex("""lavfi\.signalstats\.YAVG=([0-9.]+)""").find(r.output)?.groupValues?.get(1)?.toDoubleOrNull()
        if (r.ok && shell.exists(out) && (yavg == null || yavg >= 24.0)) return true
        shell.remove(out)
        return false
    }

    /** FR-9.4 — the admin's own moment gets the same not-black rule as the automatic ones; it is cut aside so a refused frame leaves the slot's old picture. */
    override suspend fun frameAt(path: String, atSec: Int, out: String): Boolean {
        val aside = out.removeSuffix(".jpg") + ".new.jpg"
        if (!cut(path, atSec, aside)) return false
        if (shell.run("mv -f ${q(aside)} ${q(out)}").ok) return true
        shell.remove(aside)
        return false
    }

    /**
     * FR-9.4 — three frames, one in each quarter of the window, spread so that no two sit within an eighth of it of each other (a near
     * duplicate is a frame of the same moment), and none black. A candidate that fails is nudged along the window; fewer than asked is an answer.
     */
    override suspend fun stills(path: String, durationSec: Double, outFiles: List<String>): List<Int> {
        val (lo, hi) = stillWindow(durationSec)
        val span = hi - lo
        if (span <= 0 || outFiles.isEmpty()) return emptyList()
        val gap = span / 8
        val picked = ArrayList<Int>()
        for ((i, out) in outFiles.withIndex()) {
            val base = lo + span * (i + 1.0) / (outFiles.size + 1)
            for (nudge in listOf(0.0, 0.05, -0.05, 0.1, -0.1, 0.15, -0.15)) {
                val at = (base + span * nudge).coerceIn(lo, hi).toInt()
                if (picked.any { kotlin.math.abs(it - at) < gap }) continue
                if (cut(path, at, out)) { picked += at; break }
            }
        }
        return picked
    }
}

/** The household's TMDB, Sonarr, Jellyfin, playback and history, as the engine sees them. */
class ProductionWorld(
    private val configStore: ConfigStore,
    private val db: JellystructureDb,
    private val tmdb: TmdbClient,
    private val arr: ArrClient,
    private val jellyfin: JellyfinClient,
    private val mediaStore: MediaStore,
    private val history: MediaHistory,
    private val activityLog: ActivityLog,
    private val waitForViewers: () -> Boolean,
    private val playbackActive: () -> Boolean,
    private val scanner: dev.jellystructure.media.Scanner,
    private val dataDir: String,
    private val scanRunning: () -> Boolean,
) : AutofetchWorld {
    override fun enabled() = configStore.current.autofetch.enabled
    override fun libraries(): List<LibraryMapping> = configStore.current.libraries
    override fun nowSec() = nowEpochSec()
    override fun waitForViewers() = waitForViewers.invoke()

    override suspend fun episodePlaying(path: String): Boolean {
        val series = mediaStore.allItems().firstOrNull { s -> s.episodes.any { it.path == path } }
        val jfId = series?.episodes?.firstOrNull { it.path == path }?.jellyfinId
        // Unknown to the library: hold the deletion while anything plays rather than guess (FR-16.6, never while it plays).
        if (jfId == null) return playbackActive()
        return runCatching {
            db.playbackSessionQueries.liveOrEndedSince(Long.MAX_VALUE).executeAsList()
                .any { it.ended_at == null && (it.item_id == jfId || it.queue_json.contains(jfId)) }
        }.getOrDefault(playbackActive())
    }

    override suspend fun tmdbKind(id: Int): TmdbKind = when {
        tmdb.getTvDetails(id) != null -> TmdbKind.SERIES
        tmdb.getMovieDetails(id) != null -> TmdbKind.FILM
        else -> TmdbKind.UNKNOWN
    }

    override suspend fun tmdbSeriesName(id: Int): String? = tmdb.getTvDetails(id)?.name?.takeIf { it.isNotBlank() }

    override suspend fun tmdbEpisodes(id: Int): List<TmdbEpisodeFact>? {
        val d = tmdb.getTvDetails(id) ?: return null
        val out = ArrayList<TmdbEpisodeFact>()
        for (s in d.seasons) {
            val season = tmdb.getSeasonDetails(id, s.seasonNumber) ?: continue
            season.episodes.forEach { out += TmdbEpisodeFact(it.seasonNumber.takeIf { n -> n != 0 || s.seasonNumber == 0 } ?: s.seasonNumber, it.episodeNumber, it.name, it.airDate, it.overview.takeIf { o -> o.isNotBlank() }) }
        }
        return out
    }

    override suspend fun sonarrReason(tmdbId: Int?, seriesPath: String): String? {
        val cfg = configStore.current.sonarr ?: return null
        if (!cfg.enabled || cfg.url.isBlank()) return null
        val byId = tmdbId?.let { arr.findSeriesIdByTmdbId(cfg.url, cfg.apiKey, it) }
        val byPath = arr.findSeriesIdByPath(cfg.url, cfg.apiKey, seriesPath)
        return if (byId != null || byPath != null) "Sonarr already downloads this series" else null
    }

    override suspend fun announce(path: String, updateType: String) {
        val k = configStore.current.apiKeys
        if (k.jellyfinUrl.isBlank() || k.jellyfinToken.isBlank()) return
        jellyfin.notifyLibraryMediaUpdated(k.jellyfinUrl.trimEnd('/'), k.jellyfinToken, jellyfinViewOf(path, configStore.current.libraries), updateType)
    }

    override suspend fun history(path: String, text: String) {
        val series = mediaStore.allItems().firstOrNull { s -> s.episodes.any { it.path == path } } ?: return
        runCatching { history.record(series.id, "autofetch", text) }
    }

    override suspend fun activity(level: String, message: String, runId: String?) {
        runCatching { activityLog.log(level, "autofetch", message, null, runId, null) }
    }

    override suspend fun activityStart(runId: String, trigger: String) {
        runCatching { activityLog.startRun(runId, trigger, runScope = "autofetch") }
    }

    override suspend fun activityEnd(runId: String) {
        runCatching { activityLog.finishRun(runId) }
    }

    override fun stillsDir(): String = "$dataDir/autofetch/stills"

    /**
     * FR-9.3 — an explicit season re-sync against TMDB. The scan will not do it: it skips TMDB for an episode that already has a title and
     * an overview, and a queued episode has both, from the page. The sync keeps a field TMDB leaves blank (FR-8.4). The NFO is the next
     * scan's `write_nfo`; no Jellyfin refresh is triggered here (FR-6.7's rule, kept).
     */
    override suspend fun resyncSeason(seriesPath: String, tmdbId: Int, season: Int, episodePaths: List<String>): Map<String, EpisodeAfterSync>? {
        // A scan owns the episode rows while it runs; the check simply waits for the next day's turn rather than race it.
        if (scanRunning()) return null
        val wanted = episodePaths.toSet()
        val series = mediaStore.allItems().firstOrNull { it.kind == dev.jellystructure.model.MediaKind.TV_SHOW && it.episodes.any { e -> e.path in wanted } } ?: return null
        // The fetcher's id is the one the household declared; the sync asks about that series, and the item keeps whatever id it had.
        val (synced, _) = runCatching { scanner.syncSeason(series.copy(tmdbId = tmdbId), season, probeFiles = false) }.getOrNull() ?: return null
        mediaStore.updateOne(synced.copy(tmdbId = series.tmdbId ?: tmdbId))
        return episodePaths.associateWith { p ->
            val ep = synced.episodes.firstOrNull { it.path == p }
            EpisodeAfterSync(ep?.tmdbEpisodeId != null, ep?.title, ep?.overview)
        }
    }

    override suspend fun forgetEpisode(path: String) {
        val series = mediaStore.allItems().firstOrNull { s -> s.episodes.any { it.path == path } } ?: return
        runCatching {
            val rest = series.episodes.filter { it.path != path }
            mediaStore.updateOne(series.copy(episodes = rest))
        }.onFailure { Logger.warn("auto-fetch: could not take $path out of the database: ${it.message}", "autofetch") }
    }

    override suspend fun fetchImage(url: String, dest: String, lane: SiteLane): Boolean {
        val bytes = lane.pacedRaw(url) {
            OutboundHttp.withPermit {
                val r = OutboundHttp.client.get(url) { header(HttpHeaders.UserAgent, FetchIdentity.userAgent()) }
                if (r.status.isSuccess() && r.headers[HttpHeaders.ContentType]?.startsWith("image/") == true) r.bodyAsBytes() else null
            }
        } ?: return false
        if (bytes.isEmpty() || bytes.size > 20_000_000) return false
        return runCatching {
            SystemFileSystem.sink(Path(dest)).buffered().use { it.write(bytes) }
            true
        }.getOrDefault(false)
    }

    override suspend fun notifySiteStopped(site: String, reason: String) {
        val esc = { s: String -> JsonPrimitive(s).toString() }
        runCatching { dev.jellystructure.server.routes.fireWebhook(configStore.current, """{"event":"autofetch_site_stopped","site":${esc(site)},"reason":${esc(reason)}}""") }
        activity("warn", "Auto-fetch · $site stopped · $reason")
    }
}
