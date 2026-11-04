package dev.jellystructure.autofetch

import dev.jellystructure.config.LibraryMapping

/**
 * DEB-2476 (DEB-2474) — everything the engine reaches outside itself, as two interfaces: the media tools (ffmpeg, ffprobe,
 * mkvinfo) and the world (config, TMDB, Sonarr, Jellyfin, playback, history). The engine's rules are tested against fakes of
 * these; production binds them in [ProductionPorts].
 */
data class ManifestFacts(val hasVideo: Boolean, val durationSec: Double?)

data class DownloadSpec(
    val manifestUrl: String,
    val manifestText: String,
    val kind: String,
    /** The work file in `<season>/.jellystructure/`, with its real `.mkv` extension. */
    val outPath: String,
    val audioLang: String,
    val subtitleLang: String,
    /** DEB-3613 (FR-3.6) — ffmpeg's `-cookies` value for the site's session, scoped by domain; never printed. */
    val cookies: String? = null,
) {
    override fun toString(): String = "DownloadSpec(manifestUrl=$manifestUrl, kind=$kind, outPath=$outPath, cookies=${if (cookies != null) "set" else "none"})"
}

/**
 * [message] is why a download failed; on a success it is the note that ends the run line (`no subtitle: …`, FR-6.4), or empty.
 * [drm] says the source protects the video (FR-3.3); [status] is the HTTP status yt-dlp named, which the lane judges (FR-2.4).
 */
data class ToolResult(val ok: Boolean, val message: String = "", val drm: Boolean = false, val status: Int? = null, val needsLogin: Boolean = false)

/** What `ffprobe` shows of a finished file (FR-6.5). */
data class FileFacts(
    val durationSec: Double?,
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>,
    val height: Int?,
    val sizeBytes: Long?,
)

interface MediaTools {
    /** Whether a manifest resolves to a video variant `ffprobe` can read, and its duration (FR-4.2). Null when it cannot be read. */
    suspend fun probeManifest(url: String, text: String, kind: String, cookies: String? = null): ManifestFacts?
    /** FR-6.3/6.4 — highest variant and best audio into one MKV, audio tagged at the mux, subtitles embedded when the source has them. */
    suspend fun download(spec: DownloadSpec, stop: () -> Boolean): ToolResult
    suspend fun inspect(path: String): FileFacts?
    /** FR-6.3 — yt-dlp's flat playlist of [url] as JSON. Unavailable until a binding says otherwise, so a fake that does not read a `ytdlp` recipe never has to. */
    suspend fun ytdlpListing(url: String, cookies: String? = null): YtDlpOutput = YtDlpOutput.NOT_INSTALLED
    /** FR-6.3 — yt-dlp's metadata for one item at [url], for the proof's probe (FR-4.2). */
    suspend fun ytdlpInfo(url: String, cookies: String? = null): YtDlpOutput = YtDlpOutput.NOT_INSTALLED
    /** DEB-3407 (FR-4.1) — one item's own date fields at [url], and nothing else; [YtDlpDate.parse] reads them. */
    suspend fun ytdlpDate(url: String, cookies: String? = null): YtDlpOutput = YtDlpOutput.NOT_INSTALLED
    /** FR-6.3 — one item downloaded by yt-dlp straight into [YtDlpDownload.outPath], language-tagged at its own mux. */
    suspend fun ytdlpDownload(d: YtDlpDownload, stop: () -> Boolean): ToolResult = ToolResult(false, YtDlpTexts.NOT_INSTALLED)
    /** FR-6.5 — the first top-level element after the segment header, `Tracks` or `Cluster`; null when unreadable. */
    suspend fun firstTopLevel(path: String): String?
    /** FR-9.4/15.7 — one frame picked by the fixed rule, written as a JPEG at [out]. */
    suspend fun thumbnail(path: String, durationSec: Double, out: String): Boolean
    /** FR-9.4 — one frame at [atSec], 1920 px wide or narrower and never upscaled, written as a JPEG at [out]; false when it could not be cut. */
    suspend fun frameAt(path: String, atSec: Int, out: String): Boolean
    /** FR-9.4 — up to [outFiles].size frames picked by the fixed rule, written to those files in order; the moments (seconds) of the ones that were. */
    suspend fun stills(path: String, durationSec: Double, outFiles: List<String>): List<Int>
}

/** What TMDB and the library say of one episode after an explicit re-sync of its season (FR-9.3). */
data class EpisodeAfterSync(val tmdbHasEpisode: Boolean, val title: String?, val overview: String?)

enum class TmdbKind { SERIES, FILM, UNKNOWN, UNREACHABLE }

data class TmdbEpisodeFact(val season: Int, val episode: Int, val name: String, val airDate: String?, val overview: String? = null)

interface AutofetchWorld {
    /** `[autofetch] enabled` (FR-13). */
    fun enabled(): Boolean
    fun libraries(): List<LibraryMapping>
    fun nowSec(): Long
    /** Someone is watching and the household switch says to wait (FR-262-1). */
    fun waitForViewers(): Boolean
    /** [path] is open in a playback session on any device (FR-16.6). */
    suspend fun episodePlaying(path: String): Boolean
    suspend fun tmdbKind(id: Int): TmdbKind
    suspend fun tmdbSeriesName(id: Int): String?
    suspend fun tmdbEpisodes(id: Int): List<TmdbEpisodeFact>?
    /** FR-3.4 — why Sonarr already has this series (by TMDB id or folder path), or null. */
    suspend fun sonarrReason(tmdbId: Int?, seriesPath: String): String?
    /** FR-6.7 — Jellyfin is told by path: `Created` for a placed file, `Deleted` for a removed one. Never a per-item refresh. */
    suspend fun announce(path: String, updateType: String)
    /** The title's history (as `FileFixService.runRemove` records one) and the Activity log. */
    suspend fun history(path: String, text: String)
    suspend fun activity(level: String, message: String, runId: String? = null)
    /** FR-16.6 — the episode leaves jellystructure's database. */
    suspend fun forgetEpisode(path: String)
    /** FR-15.3 — the page's image, downloaded once from a host the recipe records, through the site's lane; true when written. */
    suspend fun fetchImage(url: String, dest: String, lane: SiteLane): Boolean
    /** FR-2.5 — one notification through the owner's existing webhook. */
    suspend fun notifySiteStopped(site: String, reason: String)

    /**
     * FR-9.3 — asks TMDB explicitly about one season of [seriesPath], by [tmdbId], and writes what it returns into the episodes of that
     * season (a field TMDB leaves blank keeps its text). The answer is per episode path; null when the series is not in the library yet
     * or TMDB could not be reached, and then nothing was changed.
     */
    suspend fun resyncSeason(seriesPath: String, tmdbId: Int, season: Int, episodePaths: List<String>): Map<String, EpisodeAfterSync>?

    /** FR-9.4 — where the stills of the queue live: the application's own data directory, never the library (FR-8.3). */
    fun stillsDir(): String

    /** FR-11 — a run is one entry on Activity ▸ History; its lines are logged under [runId] between these two. */
    suspend fun activityStart(runId: String, trigger: String) {}
    suspend fun activityEnd(runId: String) {}
}
