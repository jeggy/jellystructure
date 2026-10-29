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
)

/** [message] is why a download failed; on a success it is the note that ends the run line (`no subtitle: …`, FR-6.4), or empty. */
data class ToolResult(val ok: Boolean, val message: String = "")

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
    suspend fun probeManifest(url: String, text: String, kind: String): ManifestFacts?
    /** FR-6.3/6.4 — highest variant and best audio into one MKV, audio tagged at the mux, subtitles embedded when the source has them. */
    suspend fun download(spec: DownloadSpec, stop: () -> Boolean): ToolResult
    suspend fun inspect(path: String): FileFacts?
    /** FR-6.5 — the first top-level element after the segment header, `Tracks` or `Cluster`; null when unreadable. */
    suspend fun firstTopLevel(path: String): String?
    /** FR-9.4/15.7 — one frame picked by the fixed rule, written as a JPEG at [out]. */
    suspend fun thumbnail(path: String, durationSec: Double, out: String): Boolean
}

enum class TmdbKind { SERIES, FILM, UNKNOWN, UNREACHABLE }

data class TmdbEpisodeFact(val season: Int, val episode: Int, val name: String, val airDate: String?)

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
}
