package dev.jellystructure.music

import dev.jellystructure.log.Logger
import dev.jellystructure.arr.LidarrClient
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.io.FileIo
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicFileCell
import dev.jellystructure.model.MusicFileColumn
import dev.jellystructure.model.MusicFileColumnGroup
import dev.jellystructure.model.MusicFileRow
import dev.jellystructure.model.MusicFilesDto
import dev.jellystructure.model.MusicFormats
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.originalDate
import dev.jellystructure.nowEpochSec
import dev.jellystructure.torrent.SeedingCheckResult
import dev.jellystructure.torrent.SeedingGuard
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import kotlin.math.roundToInt

/**
 * Phase 284 — for music the file is the record. jellystructure writes standard tags — Picard's vocabulary — through
 * one script in the image (`scripts/tagwrite.py`, python3 + mutagen): the same reader/writer serves an album's songs
 * and a book's parts (FR-284-6). Every write is **copy → save → verify → rename** (FR-284-3), never in place: an
 * in-place save would also rewrite a hardlinked seed the guard cannot see (it matches paths, not inodes). The
 * seeding guard runs first, per file; a blocked or unreachable file is skipped and says so. Five moments write
 * (FR-284-2); a scan on its own rewrites nothing it already wrote.
 */
class MusicTagWriter(
    private val store: MusicStore,
    private val configStore: ConfigStore,
    private val seedingGuard: SeedingGuard?,
    private val jellyfin: JellyfinClient,
    private val history: MediaHistory?,
    private val lidarr: LidarrClient? = null,
) {
    @Serializable
    data class FileTags(
        val path: String, val format: String = "", @SerialName("id3_version") val id3Version: String? = null,
        val tags: Map<String, String> = emptyMap(), val junk: List<String> = emptyList(),
        val cover: Boolean = false, val lyrics: Boolean = false, val error: String? = null,
    )

    @Serializable
    private data class WriteResult(val path: String, val written: Boolean = false, val error: String? = null)

    @Serializable
    private data class PlanFile(val path: String, val tags: Map<String, String?>, @SerialName("drop_unmanaged") val dropUnmanaged: Boolean = false, val cover: String? = null)

    @Serializable
    private data class Plan(@SerialName("keep_id3_version") val keepId3Version: Boolean, @SerialName("keep_unmanaged") val keepUnmanaged: Boolean, val files: List<PlanFile>)

    data class Outcome(val written: Int, val seeding: Int, val failed: List<String>, val wma: Int, val skipped: Int = 0) {
        fun sentence(noun: String = "file"): String = buildString {
            append(if (written > 0) "Tags written into $written $noun${if (written == 1) "" else "s"}" else "Nothing written")
            if (seeding > 0) append(" · $seeding seeding, left alone")
            if (wma > 0) append(" · $wma WMA (ids not readable by Jellyfin)")
            if (failed.isNotEmpty()) append(" · ${failed.size} left as ${if (failed.size == 1) "it was" else "they were"}")
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var availableCached: Boolean? = null

    /** The script in the image, or the checkout's copy when running from the repo. */
    fun script(): String? = listOf(SCRIPT_IMAGE, SCRIPT_DEV).firstOrNull { SystemFileSystem.exists(Path(it)) }

    /** Whether this server can write tags at all: the script and python3 with mutagen (281's check, moved here). */
    fun available(): Boolean = availableCached ?: (script() != null && platform.posix.system("python3 -c 'import mutagen' >/dev/null 2>&1") == 0).also { availableCached = it }

    fun enabled(): Boolean = configStore.current.music.writeTags

    // ── read ──

    /** What the files say, one entry per path that answered. Through the shared ProcessGate; the reader is one process. */
    suspend fun read(paths: List<String>): Map<String, FileTags> {
        val s = script() ?: return emptyMap()
        if (paths.isEmpty()) return emptyMap()
        val out = HashMap<String, FileTags>()
        for (chunk in paths.chunked(40)) {
            val cmd = "python3 ${q(s)} read " + chunk.joinToString(" ") { q(it) } + " 2>/dev/null"
            val text = capture(cmd) ?: continue
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                runCatching { json.decodeFromString(FileTags.serializer(), line) }.getOrNull()?.let { out[it.path] = it }
            }
        }
        return out
    }

    // ── what this page states, per song (FR-284-1) ──

    fun wanted(album: MusicAlbum, track: MusicTrack, tracks: List<MusicTrack>): Map<String, String?> {
        val snap = store.snapshot()
        val matched = album.matchState == MusicMatch.MATCHED
        val discs = tracks.mapNotNull { it.disc }.distinct().size > 1
        val onDisc = tracks.filter { (it.disc ?: 1) == (track.disc ?: 1) }
        val m = LinkedHashMap<String, String?>()
        m["title"] = track.title.takeIf { it.isNotBlank() }
        m["artist"] = track.artists.map { it.name }.filter { it.isNotBlank() }.joinToString("; ").ifBlank { null }
        m["album"] = album.title.takeIf { it.isNotBlank() }
        m["albumartist"] = album.albumArtists.map { it.name }.filter { it.isNotBlank() }.joinToString("; ").ifBlank { null }
        m["tracknumber"] = track.position?.toString()
        m["tracktotal"] = onDisc.size.takeIf { it > 0 }?.toString()
        if (discs) { m["discnumber"] = (track.disc ?: 1).toString(); m["disctotal"] = tracks.mapNotNull { it.disc }.maxOrNull()?.toString() }
        // Phase 290 (FR-290-3) — DATE is what Jellyfin reads, so both dates say when the album first came out.
        val original = album.originalDate()
        m["date"] = original
        m["originaldate"] = original
        m["genre"] = album.genres.firstOrNull()?.takeIf { it.isNotBlank() }
        if (matched) {
            m["mb_recording"] = track.recordingMbid
            m["mb_track"] = track.releaseTrackMbid
            m["mb_release"] = album.releaseMbid
            m["mb_releasegroup"] = album.releaseGroupMbid
            m["mb_artist"] = track.artists.mapNotNull { snap.artists[it.artistId]?.mbid }.filter { it.isNotBlank() }.joinToString("; ").ifBlank { null }
            m["mb_albumartist"] = album.albumArtists.mapNotNull { snap.artists[it.artistId]?.mbid }.filter { it.isNotBlank() }.joinToString("; ").ifBlank { null }
        }
        // FR-284-1 / dev review 3 — Jellyfin's numbers are dB gains; it holds no peak, so no PEAK is written.
        m["rg_track_gain"] = track.trackGainDb?.let { gain(it) }
        m["rg_album_gain"] = (album.albumGainDb ?: track.albumGainDb)?.let { gain(it) }
        return m
    }

    private fun gain(db: Double): String { val r = (db * 100).roundToInt() / 100.0; return "${if (r >= 0) "+" else ""}$r dB" }

    // ── the Files tab (FR-284-5) ──

    suspend fun filesFor(albumId: String): MusicFilesDto? {
        val album = store.album(albumId) ?: return null
        val tracks = store.snapshot().tracksByAlbum[albumId].orEmpty().filter { it.missingSince == null && it.path != null }.sortedWith(compareBy({ it.disc ?: 1 }, { it.position ?: 0 }))
        val cfg = configStore.current
        val tags = read(tracks.mapNotNull { it.path })
        val rows = ArrayList<MusicFileRow>()
        var differ = 0; var seeding = 0; var wma = 0
        val junkAll = LinkedHashMap<String, Int>()
        for (t in tracks) {
            val path = t.path!!
            val f = tags[path]
            val isWma = MusicFormats.reencodesOnPhone(t.container, t.codec) && (t.container?.contains("asf", true) == true || path.endsWith(".wma", true))
            val guard = seedingGuard?.check(path, cfg, inPlace = false)
            val seed = guard is SeedingCheckResult.Blocked || guard is SeedingCheckResult.Unreachable
            val want = wanted(album, t, tracks)
            val cells = LinkedHashMap<String, MusicFileCell>()
            var rowDiffers = false
            fun cell(key: String, fileValue: String?, pageValue: String?, idKey: Boolean = false) {
                val fv = fileValue?.takeIf { it.isNotBlank() }; val pv = pageValue?.takeIf { it.isNotBlank() }
                val c = when {
                    fv == null && pv == null -> MusicFileCell(null, null, "empty")
                    pv == null -> MusicFileCell(fv, null, "fileonly")
                    fv == pv -> MusicFileCell(fv, null, "same")
                    seed -> MusicFileCell(fv, pv, "seed")
                    album.matchLocked && fv != null -> MusicFileCell(fv, pv, "hold")
                    isWma && idKey -> MusicFileCell(fv, pv, "noreach")
                    else -> MusicFileCell(fv, pv, "write")
                }
                if (c.mark in setOf("write", "noreach", "hold")) rowDiffers = true
                cells[key] = c
            }
            val ft = f?.tags.orEmpty()
            cell("title", ft["title"], want["title"]); cell("artist", ft["artist"], want["artist"]); cell("albumartist", ft["albumartist"], want["albumartist"])
            val fileTrack = ft["tracknumber"]?.let { n -> ft["tracktotal"]?.let { "$n/$it" } ?: n }
            // ASF holds a track number but no total (WM/TrackNumber is one number), so a WMA file is compared on what it can say.
            val pageTrack = want["tracknumber"]?.let { n -> want["tracktotal"]?.takeIf { f?.format != "asf" }?.let { "$n/$it" } ?: n }
            cell("track", fileTrack, pageTrack)
            // Phase 290 (FR-290-4) — ASF's WM/Year holds a year, so a WMA file is compared on the year; and a file that
            // already says more (1999-09-06 where the page knows 1999) agrees — the writer never makes a date less precise.
            val fileDate = ft["date"]
            val pageDate = want["date"]?.let { if (f?.format == "asf") it.take(4) else it }
            cell("date", fileDate, if (pageDate != null && fileDate != null && fileDate.startsWith(pageDate)) fileDate else pageDate)
            cell("rec", ft["mb_recording"], want["mb_recording"], idKey = true); cell("rel", ft["mb_release"], want["mb_release"], idKey = true)
            cell("gain", ft["rg_track_gain"], want["rg_track_gain"])
            cells["cover"] = when {
                f?.cover == true -> MusicFileCell("embedded", null, "side")
                album.coverState == MusicArt.FILE && album.embedCover -> MusicFileCell(null, "embed cover.jpg", "write").also { rowDiffers = true }
                album.coverState == MusicArt.FILE -> MusicFileCell("cover.jpg beside", null, "side")
                else -> MusicFileCell(null, null, "empty")
            }
            cells["lyrics"] = when {
                t.lyricsState == "synced" || t.lyricsState == "plain" -> MusicFileCell(".lrc beside", null, "side")
                f?.lyrics == true -> MusicFileCell("embedded", null, "side")
                else -> MusicFileCell(null, null, "empty")
            }
            val junk = f?.junk.orEmpty()
            junk.forEach { j -> val k = j.substringBefore(" ×"); val n = j.substringAfter(" ×", "1").toIntOrNull() ?: 1; junkAll[k] = (junkAll[k] ?: 0) + n }
            cells["junk"] = if (junk.isEmpty()) MusicFileCell(null, null, "empty") else MusicFileCell(junk.joinToString(" · "), null, "junk")
            if (seed) seeding++; if (isWma) wma++; if (rowDiffers) differ++
            val fmt = when {
                f == null -> "?"
                f.format == "id3" -> "MP3 · ID3v" + (f.id3Version ?: "2")
                f.format == "asf" -> "WMA · ASF"
                f.format == "flac" -> "FLAC"
                f.format == "mp4" -> "MP4"
                else -> f.format.uppercase()
            }
            rows += MusicFileRow(id = t.id, file = path.substringAfterLast('/'), format = fmt, seeding = seed, wma = isWma, differs = rowDiffers, cells = cells, error = f?.error)
        }
        val tagger = available()
        val reason = when {
            !cfg.music.writeTags -> "Tag writing is off in Settings → Music providers"
            !tagger -> "This server has no tagger — the image is missing python3-mutagen"
            rows.isNotEmpty() && seeding == rows.size -> "Every file is seeding"
            differ == 0 -> "Nothing differs"
            else -> null
        }
        val lidarrLine = lidarrLine(album)
        return MusicFilesDto(
            kind = "album", rows = rows, columns = ALBUM_COLUMNS, writtenAt = album.tagsWrittenAt, differCount = differ, seedingCount = seeding, wmaCount = wma,
            taggerAvailable = tagger, writeEnabled = cfg.music.writeTags, locked = album.matchLocked, hasCover = album.coverState == MusicArt.FILE, embedCover = album.embedCover,
            junk = junkAll.entries.map { (k, n) -> if (n > 1) "$k ×$n" else k }, reason = reason, lidarr = lidarrLine,
        )
    }

    /** Dev review 4 — *Lidarr manages this album · matched to {release}*, read from Lidarr when it is connected. */
    private suspend fun lidarrLine(album: MusicAlbum): String? {
        val c = configStore.current.lidarr?.takeIf { it.enabled && it.url.isNotBlank() } ?: return null
        val rg = album.releaseGroupMbid ?: return null
        val managed = lidarr?.album(c.url, c.apiKey, rg) ?: return null
        return "Lidarr manages this album" + (managed.releaseTitle?.let { " · it matched $it" } ?: "")
    }

    // ── write (FR-284-3) ──

    /** Writes the album's songs; [embedCover] null keeps the album's own choice. Returns the outcome or null for an unknown album. */
    suspend fun writeAlbum(albumId: String, embedCover: Boolean? = null, removeJunk: Boolean = false, take: String? = null, onFile: suspend (Int) -> Unit = {}, cancelled: () -> Boolean = { false }): Outcome? {
        var album = store.album(albumId) ?: run { Logger.warn("write_tags: album $albumId is not in the store (${store.snapshot().albums.size} albums, ${store.snapshot().tracks.size} tracks)", "music"); return null }
        if (embedCover != null && embedCover != album.embedCover) { album = album.copy(embedCover = embedCover); store.putAlbum(album) }
        if (take == "file" && album.matchLocked) {
            // FR-284-4 — *Take the file's*: the lock stays, our facts become the files' (the ingest keeps ours while locked).
            takeFilesFacts(album)
            album = store.album(albumId) ?: return null
        }
        val tracks = store.snapshot().tracksByAlbum[albumId].orEmpty().filter { it.missingSince == null && it.path != null }.sortedWith(compareBy({ it.disc ?: 1 }, { it.position ?: 0 }))
        val cfg = configStore.current
        val cover = if (album.embedCover && album.coverState == MusicArt.FILE) album.path?.let { p -> listOf("cover.jpg", "cover.png", "folder.jpg").map { "$p/$it" }.firstOrNull { SystemFileSystem.exists(Path(it)) } } else null
        val plan = ArrayList<PlanFile>()
        var seeding = 0; var wma = 0
        for (t in tracks) {
            val path = t.path!!
            val guard = seedingGuard?.check(path, cfg, inPlace = false)
            if (guard is SeedingCheckResult.Blocked || guard is SeedingCheckResult.Unreachable) { seeding++; continue }
            if (path.endsWith(".wma", true)) wma++
            plan += PlanFile(path, wanted(album, t, tracks), dropUnmanaged = removeJunk, cover = cover)
        }
        val outcome = run(plan, cfg, onFile, cancelled).let { it.copy(seeding = seeding, wma = wma) }
        if (outcome.written > 0) {
            store.putAlbum((store.album(albumId) ?: album).copy(tagsWrittenAt = nowEpochSec(), updatedAt = nowEpochSec()))
            val fields = listOfNotNull("title", "artist", "album artist", "track numbers", album.originalDate()?.let { "year" }, if (album.matchState == MusicMatch.MATCHED) "6 MusicBrainz ids" else null, if (tracks.any { it.trackGainDb != null }) "loudness" else null)
            runCatching { history?.record(albumId, "music_tags_written", "Tags written into ${outcome.written} file${if (outcome.written == 1) "" else "s"}: ${fields.joinToString(", ")}" + (if (seeding > 0) " · $seeding seeding, left alone" else "") + (if (outcome.failed.isNotEmpty()) " · ${outcome.failed.size} left as they were" else "")) }
            if (cfg.apiKeys.jellyfinUrl.isNotBlank() && cfg.apiKeys.jellyfinToken.isNotBlank())
                runCatching { jellyfin.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, albumId, full = false, recursive = true) }
            cfg.lidarr?.takeIf { it.enabled && it.url.isNotBlank() && it.rescanAfterWrite }?.let { l -> album.path?.let { p -> runCatching { lidarr?.rescanFolders(l.url, l.apiKey, listOf(p)) } } }
        } else if (outcome.failed.isNotEmpty()) {
            runCatching { history?.record(albumId, "music_tags_failed", outcome.failed.joinToString(" · ")) }
        }
        return outcome
    }

    /** Moment C (FR-284-2) — a song just converted: the full set into the new file. */
    suspend fun writeConverted(newPath: String, originalPath: String) {
        if (!enabled() || !available()) return
        val snap = store.snapshot()
        val t = snap.tracks.values.firstOrNull { it.path == originalPath || it.path == newPath } ?: return
        val album = t.albumId?.let { snap.albums[it] } ?: return
        val tracks = snap.tracksByAlbum[album.id].orEmpty()
        run(listOf(PlanFile(newPath, wanted(album, t, tracks))), configStore.current, {}, { false })
    }

    /** FR-284-6 — a book's parts through the same writer: 281's field set, the same copy-verify-swap. */
    suspend fun writeFiles(files: List<Pair<String, Map<String, String?>>>, onFile: suspend (Int) -> Unit = {}): Outcome {
        val cfg = configStore.current
        var seeding = 0
        val plan = ArrayList<PlanFile>()
        for ((path, tags) in files) {
            val guard = seedingGuard?.check(path, cfg, inPlace = false)
            if (guard is SeedingCheckResult.Blocked || guard is SeedingCheckResult.Unreachable) { seeding++; continue }
            plan += PlanFile(path, tags)
        }
        return run(plan, cfg, onFile, { false }).copy(seeding = seeding)
    }

    private suspend fun run(plan: List<PlanFile>, cfg: dev.jellystructure.config.AppConfig, onFile: suspend (Int) -> Unit, cancelled: () -> Boolean): Outcome {
        if (plan.isEmpty()) return Outcome(0, 0, emptyList(), 0)
        val s = script() ?: return Outcome(0, 0, plan.map { "${it.path.substringAfterLast('/')}: no tagger" }, 0)
        var written = 0
        val failed = ArrayList<String>()
        // One process per album-sized batch: the plan goes through a temp file, the results come back one line per file.
        for ((bi, batch) in plan.chunked(60).withIndex()) {
            if (cancelled()) break
            val tmp = "/tmp/js-tagplan-${nowEpochSec()}-$bi.json"
            FileIo.writeText(Path(tmp), json.encodeToString(Plan.serializer(), Plan(cfg.music.keepId3Version, cfg.music.keepUnmanagedFrames, batch)))
            val text = try { capture("python3 ${q(s)} write < ${q(tmp)} 2>/dev/null") } finally { platform.posix.remove(tmp) }
            val results = text?.lineSequence()?.filter { it.isNotBlank() }?.mapNotNull { runCatching { json.decodeFromString(WriteResult.serializer(), it) }.getOrNull() }?.associateBy { it.path }.orEmpty()
            for ((i, f) in batch.withIndex()) {
                val r = results[f.path]
                if (r?.written == true) written++ else failed += "${f.path.substringAfterLast('/')}: ${r?.error ?: "no answer from the tagger"}"
                onFile(bi * 60 + i + 1)
            }
        }
        if (failed.isNotEmpty()) Logger.warn("write_tags: ${failed.size} file(s) left as they were — ${failed.take(3).joinToString("; ")}", "music")
        return Outcome(written, 0, failed, 0)
    }

    /** FR-284-4 — a locked album whose files disagree: *Take the file's* makes the files' identity ours. */
    private suspend fun takeFilesFacts(album: MusicAlbum) {
        val tracks = store.snapshot().tracksByAlbum[album.id].orEmpty().filter { it.path != null }
        val tags = read(tracks.mapNotNull { it.path })
        val first = tags.values.firstOrNull() ?: return
        val title = first.tags["album"]?.takeIf { it.isNotBlank() } ?: album.title
        val year = first.tags["date"]?.take(4)?.toIntOrNull() ?: album.year
        store.putAlbum(album.copy(title = title, year = year, updatedAt = nowEpochSec()))
        runCatching { history?.record(album.id, "music_tags_taken", "Took the files' album title and year on a locked album") }
    }

    // ── the process ──

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun capture(command: String): String? = dev.jellystructure.ops.ProcessGate.withPermit {
        memScoped {
            val pipe = popen(command, "r")
            if (pipe == null) null else {
                val sb = StringBuilder()
                val buf = allocArray<ByteVar>(16384)
                try { while (fgets(buf, 16384, pipe) != null) sb.append(buf.toKString()) } finally { pclose(pipe) }
                sb.toString().takeIf { it.isNotBlank() }
            }
        }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    companion object {
        const val SCRIPT_IMAGE = "/app/scripts/tagwrite.py"
        const val SCRIPT_DEV = "scripts/tagwrite.py"
        val ALBUM_COLUMNS = listOf(
            MusicFileColumnGroup("Identity", listOf(MusicFileColumn("title", "Title"), MusicFileColumn("artist", "Artist"), MusicFileColumn("albumartist", "Album artist"), MusicFileColumn("track", "Track"), MusicFileColumn("date", "Year"))),
            MusicFileColumnGroup("Ids", listOf(MusicFileColumn("rec", "Recording"), MusicFileColumn("rel", "Release"))),
            MusicFileColumnGroup("Loudness", listOf(MusicFileColumn("gain", "Track gain"))),
            MusicFileColumnGroup("Extras", listOf(MusicFileColumn("cover", "Cover"), MusicFileColumn("lyrics", "Lyrics"), MusicFileColumn("junk", "Other frames"))),
        )
        val BOOK_COLUMNS = listOf(
            MusicFileColumnGroup("Identity", listOf(MusicFileColumn("title", "Title"), MusicFileColumn("album", "Album"), MusicFileColumn("artist", "Author"), MusicFileColumn("composer", "Narrator"), MusicFileColumn("track", "Part"))),
            MusicFileColumnGroup("About the book", listOf(MusicFileColumn("comment", "Description"), MusicFileColumn("publisher", "Publisher"), MusicFileColumn("genre", "Genre"))),
            MusicFileColumnGroup("Extras", listOf(MusicFileColumn("cover", "Cover"), MusicFileColumn("junk", "Other frames"))),
        )
    }
}
