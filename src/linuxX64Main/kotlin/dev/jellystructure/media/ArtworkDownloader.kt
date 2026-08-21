package dev.jellystructure.media

import dev.jellystructure.OutboundHttp
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tmdb.TmdbClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable

private const val TMDB_ORIGINAL = "https://image.tmdb.org/t/p/original"

@Serializable
data class ArtworkStatus(
    val posterExists: Boolean,
    val fanartExists: Boolean,
    val logoExists: Boolean = false,
    // Phase 151: true when the on-disk file was explicitly picked/uploaded by an operator (`.manual`
    // marker sidecar) and is therefore never touched by an automatic download again.
    val posterManual: Boolean = false,
    val fanartManual: Boolean = false,
    val logoManual: Boolean = false,
)

@Serializable
data class EpisodeStillStatus(
    val stillExists: Boolean,
    val stillPath: String,
    val source: String? = null,  // R131: "tmdb" | "screengrab" | "manual" | null — from the .src sidecar
    val manual: Boolean = false, // Phase 151: operator-chosen — never auto-replaced (`.manual` marker)
)

/**
 * Phase 168 follow-up fix: a MOVIE/TV_SHOW keeps a fixed filename (`poster.jpg` etc.) inside the
 * item's own directory — safe, since each normally has its own folder. A MUSIC_VIDEO usually
 * **shares** its folder with sibling files (confirmed against a real library: an artist's folder
 * held 4+ music videos), so a fixed `poster.jpg` there is either nobody's poster or the wrong one.
 * Real pre-existing artwork for these files was already sitting on disk under the same per-basename
 * convention `episodeStillPath`/`NfoWriter.nfoPath` use elsewhere in this codebase (and that this
 * house's own library already had files named exactly this way) — `<video-basename>-poster.jpg` —
 * but nothing in this file ever looked there before this fix, so it was invisible to jellystructure
 * (and to Ravilo, which serves artwork from this exact path via `RaviloArtworkService`).
 */
internal fun assetFilePath(item: MediaItem, filename: String): String = when (item.kind) {
    MediaKind.MUSIC_VIDEO -> "${item.path.substringBeforeLast('.')}-$filename"
    MediaKind.MOVIE -> "${item.path.substringBeforeLast('/')}/$filename"
    MediaKind.TV_SHOW -> "${item.path}/$filename"
}

/** R122: true when a `poster.jpg` artwork file exists on disk for [item] — the real (Jellyfin) poster
 *  image, as opposed to the TMDB `posterPath` metadata. Drives the Library "missing artwork" filter,
 *  so it counts manually-added artwork and excludes TMDB-matched items whose poster never downloaded. */
fun posterArtworkExists(item: MediaItem): Boolean =
    SystemFileSystem.exists(Path(assetFilePath(item, "poster.jpg")))

class ArtworkDownloader(private val tmdbClient: TmdbClient, private val screengrabber: Screengrabber) {
    // Phase 129 (FR-OPS1 §B.1) — shared client, one idle connection pool for all outbound callers.
    private val http = OutboundHttp.client

    fun check(item: MediaItem): ArtworkStatus {
        val poster = assetFilePath(item, "poster.jpg")
        val fanart = assetFilePath(item, "fanart.jpg")
        val logo = assetFilePath(item, "clearlogo.png")
        return ArtworkStatus(
            posterExists = SystemFileSystem.exists(Path(poster)),
            fanartExists = SystemFileSystem.exists(Path(fanart)),
            logoExists = SystemFileSystem.exists(Path(logo)),
            posterManual = isManual(poster),
            fanartManual = isManual(fanart),
            logoManual = isManual(logo),
        )
    }

    // --- Phase 151 (FR-ART2): the manual-artwork marker -------------------------------------------
    //
    // "<image>.manual" written next to an image whenever an operator explicitly picks, uploads or
    // generates it. Every automatic writer (the scan pipeline's download_artwork step, a TMDB re-pull,
    // realtime ingest, the legacy misplaced-artwork cleanup) must leave a marked file alone.
    //
    // A sidecar rather than a MediaItem field because it covers the assets that have no field to lock —
    // clearlogo, season posters and episode stills — and, living with the media, it survives a DB reset.
    // The item-level poster/backdrop additionally carry `MediaItem.lockedArtwork` (Phase 133), which is
    // what keeps the *metadata* path from reverting; this marker is what protects the *file*.

    private fun manualMarkerPath(imagePath: String) = "$imagePath.manual"

    /** True when [imagePath] was explicitly chosen by an operator and must never be auto-overwritten. */
    fun isManual(imagePath: String): Boolean = SystemFileSystem.exists(Path(manualMarkerPath(imagePath)))

    /** Marks [imagePath] as operator-chosen. Idempotent; failures are non-fatal (the image itself is
     *  already written, and skip-if-present still shields it from the ordinary gap-fill download). */
    fun markManual(imagePath: String) {
        runCatching { FileIo.writeText(Path(manualMarkerPath(imagePath)), "1") }
    }

    fun isAssetManual(item: MediaItem, asset: String): Boolean =
        assetPath(item, asset)?.let { isManual(it) } ?: false

    fun markAssetManual(item: MediaItem, asset: String) {
        assetPath(item, asset)?.let { markManual(it) }
    }

    fun isSeasonPosterManual(item: MediaItem, season: Int): Boolean = isManual(seasonPosterPath(item, season))

    fun isEpisodeStillManual(episode: Episode): Boolean = isManual(episodeStillPath(episode))

    suspend fun fetch(item: MediaItem): ArtworkStatus {
        val poster = assetFilePath(item, "poster.jpg")
        val fanart = assetFilePath(item, "fanart.jpg")
        val logo = assetFilePath(item, "clearlogo.png")
        // Skip-if-present is what protects an operator's chosen image here: a file already on disk is
        // never re-downloaded, manual or not (Phase 151 additionally records WHY it must stay).
        val posterExists = SystemFileSystem.exists(Path(poster))
        val fanartExists = SystemFileSystem.exists(Path(fanart))
        val logoExists = SystemFileSystem.exists(Path(logo))

        val posterOk: Boolean
        val fanartOk: Boolean
        coroutineScope {
            val posterJob = if (!posterExists && !item.posterPath.isNullOrBlank()) {
                async { download("$TMDB_ORIGINAL${item.posterPath}", poster) }
            } else null
            val fanartJob = if (!fanartExists && !item.backdropPath.isNullOrBlank()) {
                async { download("$TMDB_ORIGINAL${item.backdropPath}", fanart) }
            } else null
            posterOk = posterJob?.await() ?: posterExists
            fanartOk = fanartJob?.await() ?: fanartExists
        }

        // For TV shows: clean up any artwork that was previously written to the wrong
        // location (parent of the series directory) due to the substringBeforeLast('/') bug.
        // Only delete the old file once the correct-path file is confirmed present.
        if (item.kind == MediaKind.TV_SHOW) {
            val oldDir = item.path.substringBeforeLast('/')
            // Phase 151: never sweep away an image an operator placed/picked deliberately — `oldDir` is
            // the PARENT of the series directory, i.e. usually the library root, where a hand-picked
            // library-level image can legitimately live.
            if (oldDir != item.path) {
                if (posterOk && !isManual("$oldDir/poster.jpg")) deleteIfExists("$oldDir/poster.jpg")
                if (fanartOk && !isManual("$oldDir/fanart.jpg")) deleteIfExists("$oldDir/fanart.jpg")
                if (logoExists && !isManual("$oldDir/clearlogo.png")) deleteIfExists("$oldDir/clearlogo.png")
            }
            // R125: episode stills are part of fetch() now — download any missing (each from the
            // episode's stored stillPath), bounded by the shared download gate. So every fetch()
            // caller (scan-pipeline "Download artwork" + the per-item fetch) populates stills too.
            if (item.episodes.isNotEmpty()) coroutineScope {
                item.episodes.forEach { ep -> launch { runCatching { fetchEpisodeStill(ep) } } }
            }
            // R126: season posters — for each season we actually have on disk, download a missing one.
            // Unlike poster/fanart/stills the chosen season poster isn't stored, so pull the season's
            // TMDB images and pick the best (resolved-language, then highest-voted).
            val tid = item.tmdbId
            if (tid != null) for (season in item.episodes.mapNotNull { it.seasonNumber }.distinct()) {
                if (checkSeasonPoster(item, season)) continue
                val posters = runCatching { tmdbClient.getSeasonImages(tid, season)?.posters }.getOrNull().orEmpty()
                val pick = posters.filter { it.languageCode == item.resolvedLanguage }.ifEmpty { posters }
                    .maxByOrNull { it.voteAverage }
                // Phase 151: manual = false — this is the automatic gap-fill, not an operator's pick.
                if (pick != null) runCatching { saveSeasonPoster(item, season, pick.filePath, manual = false) }
            }
        }

        return ArtworkStatus(
            posterExists = posterOk,
            fanartExists = fanartOk,
            logoExists = logoExists,
            posterManual = isManual(poster),
            fanartManual = isManual(fanart),
            logoManual = isManual(logo),
        )
    }

    /** R126: true if any artwork the "Download artwork" step can fetch is missing on disk — poster/fanart
     *  for everything, plus episode stills + season posters for series. Drives the pipeline "missing" scope. */
    fun isArtworkIncomplete(item: MediaItem): Boolean {
        val st = check(item)
        if (!st.posterExists || !st.fanartExists) return true
        if (item.kind != MediaKind.TV_SHOW) return false
        // R131: a still is "incomplete" when missing OR a screen-grab that TMDB can now upgrade — so the
        // next scheduled "Download artwork (missing)" run re-processes the series and swaps in the real still.
        // Phase 151: a still an operator chose (uploaded/picked, or generated from a frame in the picker)
        // is NOT "incomplete" — without this a locked screen-grab would mark its series incomplete on
        // every run forever, since fetchEpisodeStill now refuses to upgrade it.
        if (item.episodes.any { ep ->
            val st = checkEpisodeStill(ep)
            !st.stillExists || (st.source == "screengrab" && !ep.stillPath.isNullOrBlank() && !st.manual)
        }) return true
        return item.episodes.mapNotNull { it.seasonNumber }.distinct().any { !checkSeasonPoster(item, it) }
    }

    private suspend fun deleteIfExists(path: String) {
        val p = Path(path)
        if (!SystemFileSystem.exists(p)) return
        val result = runCatching { SystemFileSystem.delete(p) }
        if (result.isSuccess) Logger.info("Removed misplaced artwork: $path")
        else Logger.warn("Could not remove misplaced artwork $path: ${result.exceptionOrNull()?.message}")
    }

    private suspend fun download(url: String, destPath: String): Boolean {
        // Security fix (2026-08-02 review, finding M5) — see UrlSafety's doc comment. `source` here can
        // be an admin-pasted URL (the Artwork manager's "pick from URL" flow); without this check the
        // server would fetch and WRITE TO DISK, then re-serve via /api/tv/image/**, whatever an
        // attacker-controlled admin session (or a stolen cookie) pointed it at — a full-read SSRF.
        if (!dev.jellystructure.util.UrlSafety.isSafeExternalUrl(url)) {
            Logger.warn("Refusing to download artwork from disallowed URL: $url", "artwork")
            return false
        }
        return downloadUnchecked(url, destPath)
    }

    private suspend fun downloadUnchecked(url: String, destPath: String): Boolean = OutboundHttp.withPermit {
        val result = runCatching {
            val bytes = http.get(url).readRawBytes()
            if (bytes.isEmpty()) return@withPermit false
            val tmp = "$destPath.tmp"
            FileIo.writeBytes(Path(tmp), bytes)   // Phase 134: use{}-scoped — no FD leak on a mid-write throw
            platform.posix.rename(tmp, destPath)
            Logger.info("Downloaded artwork: $destPath", "artwork")
            true
        }
        if (result.isFailure) Logger.warn("Failed to download $url: ${result.exceptionOrNull()?.message}")
        result.getOrDefault(false)
    }

    fun checkEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val exists = SystemFileSystem.exists(Path(destPath))
        return EpisodeStillStatus(
            stillExists = exists,
            stillPath = destPath,
            source = if (exists) readStillSrc(destPath) else null,
            manual = exists && isManual(destPath),
        )
    }

    suspend fun fetchEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val stillUrl = episode.stillPath
        if (SystemFileSystem.exists(Path(destPath))) {
            val src = readStillSrc(destPath)
            val manual = isManual(destPath)
            // R131: a screen-grab is the lowest priority — once TMDB has a real still, upgrade to it.
            // Phase 151: …unless the operator chose that frame themselves (picker "Generate from frame"),
            // in which case it's locked like any other manual pick.
            if (src == "screengrab" && !stillUrl.isNullOrBlank() && !manual) {
                val ok = download("$TMDB_ORIGINAL$stillUrl", destPath)
                if (ok) writeStillSrc(destPath, "tmdb")
                return EpisodeStillStatus(stillExists = true, stillPath = destPath, source = if (ok) "tmdb" else src)
            }
            return EpisodeStillStatus(stillExists = true, stillPath = destPath, source = src, manual = manual)
        }
        // Nothing on disk: prefer the real TMDB still; otherwise grab a frame as a placeholder.
        if (!stillUrl.isNullOrBlank()) {
            val ok = download("$TMDB_ORIGINAL$stillUrl", destPath)
            if (ok) writeStillSrc(destPath, "tmdb")
            return EpisodeStillStatus(stillExists = ok, stillPath = destPath, source = if (ok) "tmdb" else null)
        }
        val ok = screengrabber.grabEpisodeStill(episode, destPath)
        if (ok) writeStillSrc(destPath, "screengrab")
        return EpisodeStillStatus(stillExists = ok, stillPath = destPath, source = if (ok) "screengrab" else null)
    }

    /** R131: regenerate a screen-grab still on demand (the picker's "Generate from frame" button). Keeps
     *  the `screengrab` source label, but (Phase 151) locks the file — an operator pressing the button is
     *  choosing this frame, so no automatic run may upgrade it to TMDB's still behind their back. */
    suspend fun screengrabEpisodeStill(episode: Episode): EpisodeStillStatus {
        val destPath = episodeStillPath(episode)
        val ok = screengrabber.grabEpisodeStill(episode, destPath)
        if (ok) {
            writeStillSrc(destPath, "screengrab")
            // Phase 151: pressing "Generate from frame" IS an explicit operator choice — keep the source
            // as `screengrab` (so the picker still labels it honestly) but lock the file so the next
            // "Download artwork" run can't silently swap in TMDB's still.
            markManual(destPath)
        }
        return EpisodeStillStatus(
            stillExists = ok || SystemFileSystem.exists(Path(destPath)),
            stillPath = destPath,
            source = if (ok) "screengrab" else readStillSrc(destPath),
            manual = isManual(destPath),
        )
    }

    // R131: provenance sidecar next to each still ("<base>-thumb.jpg.src"), mirroring the image-proxy `.ct`.
    // Phase 134: this was the incident's leak — called per episode on every scan/rescan pass, and the
    // old `source(...).buffered().readString()` never closed the fd.
    private fun readStillSrc(destPath: String): String? = runCatching {
        FileIo.readText(Path("$destPath.src")).trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun writeStillSrc(destPath: String, source: String) {
        runCatching { FileIo.writeText(Path("$destPath.src"), source) }
    }

    /** Phase 121: stamps `Episode.hasStill` from an on-disk check so triage/Library filtering can stay
     *  O(1) in-memory instead of statting the filesystem per request. Call wherever a scan/rescan/sync
     *  (re)builds a TV show's episode list, right before the result is persisted. No-op for movies. */
    fun stampHasStill(item: MediaItem): MediaItem {
        if (item.kind != MediaKind.TV_SHOW || item.episodes.isEmpty()) return item
        return item.copy(episodes = item.episodes.map { ep -> ep.copy(hasStill = checkEpisodeStill(ep).stillExists) })
    }

    fun episodeStillPath(episode: Episode): String {  // R133: public so RaviloArtworkService can resolve stills
        val dir = episode.path.substringBeforeLast('/')
        val baseName = episode.filename.substringBeforeLast('.')
        // Bug fix (Phase 149 dev-review addendum §4): a multi-episode file's N episodes all share
        // path/filename, so keying purely off baseName collided every episode in the group onto the
        // SAME disk path — fetching E02's still silently overwrote E01's. Fold the episode number into
        // the filename whenever the file has more than one contained episode; a normal single-episode
        // file (the overwhelming majority) keeps today's exact path unchanged, so nothing needs
        // migrating for existing libraries.
        val suffix = if (episode.partCount > 1) "-e${episode.episodeNumber ?: episode.partIndex}" else ""
        return "$dir/$baseName-thumb$suffix.jpg"
    }

    private fun mediaDir(item: MediaItem) = when (item.kind) {
        MediaKind.MOVIE, MediaKind.MUSIC_VIDEO -> item.path.substringBeforeLast('/')
        MediaKind.TV_SHOW -> item.path  // item.path IS the series directory
    }

    // --- Phase 47: write a specific chosen candidate (TMDB file_path or full URL) ---

    /** Filename on disk for each rail asset, per the constitution. */
    private fun assetFilename(asset: String): String? = when (asset) {
        "poster" -> "poster.jpg"
        "backdrop" -> "fanart.jpg"
        "clearlogo" -> "clearlogo.png"
        else -> null
    }

    fun assetPath(item: MediaItem, asset: String): String? =
        assetFilename(asset)?.let { assetFilePath(item, it) }

    /** Read the TMDB file_path recorded when a clearlogo candidate was picked, or null. */
    fun readAssetSrc(item: MediaItem, asset: String): String? =
        assetPath(item, asset)?.let { p -> runCatching { FileIo.readText(Path("$p.src")).trim() }.getOrNull()?.takeIf { it.isNotBlank() } }

    /** Record the TMDB file_path for the chosen clearlogo. */
    fun writeAssetSrc(item: MediaItem, asset: String, source: String) {
        assetPath(item, asset)?.let { p -> runCatching { FileIo.writeText(Path("$p.src"), source) } }
    }

    /** `source` is either a TMDB file_path (leading "/") or a full http(s) URL.
     *  Phase 151: [manual] marks the result operator-chosen (the default — every caller is an explicit
     *  pick today), so no automatic path overwrites it afterwards. */
    suspend fun saveAsset(item: MediaItem, asset: String, source: String, manual: Boolean = true): Boolean {
        val dest = assetPath(item, asset) ?: return false
        val ok = download(toUrl(source), dest)
        if (ok && manual) markManual(dest)
        return ok
    }

    /** Jellyfin local naming for a season poster at the series root. R194: `internal`, not `private` —
     *  `RaviloArtworkService` (same module, `dev.jellystructure.tv`) needs it to serve season posters. */
    internal fun seasonPosterPath(item: MediaItem, season: Int): String {
        val name = if (season == 0) "season-specials-poster.jpg"
        else "season${season.toString().padStart(2, '0')}-poster.jpg"
        return "${mediaDir(item)}/$name"
    }

    fun checkSeasonPoster(item: MediaItem, season: Int): Boolean =
        SystemFileSystem.exists(Path(seasonPosterPath(item, season)))

    /** Phase 151: [manual] is false only for the automatic gap-fill inside [fetch]. */
    suspend fun saveSeasonPoster(item: MediaItem, season: Int, source: String, manual: Boolean = true): Boolean {
        val dest = seasonPosterPath(item, season)
        val ok = download(toUrl(source), dest)
        if (ok && manual) markManual(dest)
        return ok
    }

    suspend fun saveEpisodeStill(episode: Episode, source: String): Boolean {
        val dest = episodeStillPath(episode)
        val ok = download(toUrl(source), dest)
        if (ok) {
            writeStillSrc(dest, "manual")  // R131: a manual pick is permanent — never auto-upgraded
            markManual(dest)              // Phase 151: same lock every other manual asset carries
        }
        return ok
    }

    private fun toUrl(source: String): String =
        if (source.startsWith("http://") || source.startsWith("https://")) source else "$TMDB_ORIGINAL$source"
}
