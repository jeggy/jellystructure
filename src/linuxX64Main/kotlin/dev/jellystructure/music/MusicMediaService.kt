package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.io.FileIo
import dev.jellystructure.log.Logger
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.sniffImageSignature
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtCandidate
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicLyrics
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.nowEpochSec
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

data class MusicStepSummary(val parts: List<String>) {
    fun sentence(): String = parts.filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "nothing to do" }
}

/**
 * Phase 277 — the music library's files: covers and artist pictures (FR-277-1/2), biographies (FR-277-6), lyrics
 * sidecars (FR-277-8) and `album.nfo` / `artist.nfo` (FR-277-4), with Jellyfin told about each album that changed
 * (FR-277-5). Every automatic write respects a lock (`.manual`, 133/151's marker) and never replaces a file the
 * folder already has on a `missing`-scope run.
 */
class MusicMediaService(
    private val store: MusicStore,
    private val artwork: ArtworkDownloader,
    private val configStore: ConfigStore,
    private val jellyfin: JellyfinClient,
    val fanart: FanartTvClient,
    private val history: MediaHistory? = null,
) {
    companion object {
        private val COVER_FILES = listOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png", "front.jpg", "front.png")
        private val THUMB_FILES = listOf("folder.jpg", "folder.jpeg", "folder.png", "artist.jpg", "artist.png", "thumb.jpg", "poster.jpg")
        private val BACKDROP_FILES = listOf("backdrop.jpg", "fanart.jpg", "background.jpg")
        private val LOGO_FILES = listOf("logo.png", "clearlogo.png")
        val BIO_LANGUAGES = listOf("en", "da", "fo")
        private const val LYRICS_RETRY_SEC = 30L * 86_400
    }

    private fun exists(p: String) = SystemFileSystem.exists(Path(p))
    private fun dir(p: String) = p.trimEnd('/')

    // ── paths ──

    fun coverPath(a: MusicAlbum): String? = a.path?.let { "${dir(it)}/cover.jpg" }
    fun artistImagePath(a: MusicArtist, kind: String): String? = a.path?.let { p ->
        when (kind) { "thumb" -> "${dir(p)}/folder.jpg"; "background" -> "${dir(p)}/backdrop.jpg"; "logo" -> "${dir(p)}/logo.png"; else -> null }
    }
    /** The file a viewer or the admin is shown: whatever cover the folder has, not only the one written here. */
    fun existingCover(a: MusicAlbum): String? = a.path?.let { p -> COVER_FILES.map { "${dir(p)}/$it" }.firstOrNull { exists(it) } }
    fun existingArtistImage(a: MusicArtist, kind: String): String? = a.path?.let { p ->
        val names = when (kind) { "thumb" -> THUMB_FILES; "background" -> BACKDROP_FILES; "logo" -> LOGO_FILES; else -> emptyList() }
        names.map { "${dir(p)}/$it" }.firstOrNull { exists(it) }
    }
    fun isLocked(path: String?): Boolean = path != null && artwork.isManual(path)

    private fun lyricsSidecar(t: MusicTrack): Pair<String, String>? {
        val base = t.path?.substringBeforeLast('.') ?: return null
        listOf("lrc" to MusicLyrics.SYNCED, "elrc" to MusicLyrics.SYNCED, "txt" to MusicLyrics.PLAIN).forEach { (ext, state) ->
            if (exists("$base.$ext")) return "$base.$ext" to state
        }
        return null
    }

    /** FR-277-8 — where a track's lyrics stand now: a sidecar on disk wins over the last lookup's memory. */
    fun lyricsStateOf(t: MusicTrack): String? = if (t.lyricsState == MusicLyrics.BLOCKED) MusicLyrics.BLOCKED
        else lyricsSidecar(t)?.second ?: t.lyricsState?.takeIf { it == MusicLyrics.INSTRUMENTAL || it == MusicLyrics.NONE }
    fun lyricsFile(t: MusicTrack): String? = lyricsSidecar(t)?.first

    // ── Jellyfin ──

    private suspend fun refreshInJellyfin(albums: Collection<MusicAlbum>, artists: Collection<MusicArtist>) {
        val cfg = configStore.current
        if (cfg.apiKeys.jellyfinUrl.isBlank() || cfg.apiKeys.jellyfinToken.isBlank()) return
        for (a in albums) jellyfin.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, a.id, full = true, recursive = true)
        for (a in artists.filter { it.path != null }) jellyfin.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, a.id, full = true)
    }

    suspend fun syncAlbum(albumId: String) {
        val a = store.album(albumId) ?: return
        refreshInJellyfin(listOf(a), a.albumArtists.mapNotNull { store.artist(it.artistId) })
    }

    // ── fetch_music_artwork (FR-277-1, FR-277-6) ──

    /** Phase 278 — the Library's *Fetch covers* on a selection: those albums and their album artists, re-fetched. */
    suspend fun fetchArtworkFor(albumIds: Set<String>): MusicStepSummary = fetchArtwork(scopeAll = true, albumIds = albumIds)

    suspend fun fetchArtwork(scopeAll: Boolean, albumIds: Set<String>? = null): MusicStepSummary {
        val snap = store.snapshot()
        val artistIds = albumIds?.let { ids -> ids.mapNotNull { snap.albums[it] }.flatMap { a -> a.albumArtists.map { it.artistId } }.toSet() }
        var covers = 0; var noCover = 0; var pictures = 0; var bios = 0
        val changedAlbums = mutableListOf<MusicAlbum>()
        for (a in snap.albums.values.filter { it.missingSince == null && it.matchState == MusicMatch.MATCHED && it.releaseGroupMbid != null && (albumIds == null || it.id in albumIds) }) {
            val dest = coverPath(a) ?: continue
            if (isLocked(dest)) continue
            if (!scopeAll && a.coverState == MusicArt.FILE) continue
            val ok = artwork.downloadTo(CoverArtArchive.frontUrl(a.releaseGroupMbid!!), dest)
            if (ok) {
                covers++
                store.putAlbum(a.copy(coverState = MusicArt.FILE, coverSource = "caa", coverMissingOnCaa = false))
                changedAlbums += a
                history?.record(a.id, "music_cover", "Cover from the Cover Art Archive")
            } else if (CoverArtArchive.images(a.releaseMbid, a.releaseGroupMbid)?.none { it.kind == "front" } == true) {
                noCover++
                if (!a.coverMissingOnCaa) store.putAlbum(a.copy(coverMissingOnCaa = true))
            }
        }
        val changedArtists = mutableListOf<MusicArtist>()
        for (ar in snap.artists.values.filter { it.missingSince == null && it.matchState == MusicMatch.MATCHED && it.mbid != null && (artistIds == null || it.id in artistIds) }) {
            var updated = ar
            if (ar.path != null) {
                val fa = if (fanart.available) fanart.artist(ar.mbid!!) else null
                suspend fun put(kind: String, url: String?, state: String): Boolean {
                    val dest = artistImagePath(ar, kind) ?: return false
                    if (url == null || isLocked(dest) || (!scopeAll && state == MusicArt.FILE)) return false
                    return artwork.downloadTo(url, dest)
                }
                if (fa != null) {
                    if (put("thumb", fa.thumbs.firstOrNull()?.url, ar.imageState)) { updated = updated.copy(imageState = MusicArt.FILE, imageSource = "fanart.tv", imageCredit = null); pictures++ }
                    if (put("background", fa.backgrounds.firstOrNull()?.url, ar.backdropState)) updated = updated.copy(backdropState = MusicArt.FILE)
                    if (put("logo", fa.logos.firstOrNull()?.url, ar.logoState)) updated = updated.copy(logoState = MusicArt.FILE)
                } else if (ar.imageState != MusicArt.FILE || scopeAll) {
                    ar.urls["image"]?.let { WikimediaCommons.picture(it) }?.let { pic ->
                        if (put("thumb", pic.url, ar.imageState)) { updated = updated.copy(imageState = MusicArt.FILE, imageSource = "commons", imageCredit = pic.credit); pictures++ }
                    }
                }
            }
            if (ar.biographyEdited == null && (ar.bioFetchedAt == null || scopeAll)) {
                val (leads, source) = WikipediaBio.leads(ar.urls, BIO_LANGUAGES)
                updated = updated.copy(biographies = leads.ifEmpty { ar.biographies }, biographySource = source ?: ar.biographySource, bioFetchedAt = nowEpochSec())
                if (leads.isNotEmpty()) bios++
            }
            if (updated != ar) { store.putArtist(updated); if (updated.imageState != ar.imageState || updated.backdropState != ar.backdropState || updated.logoState != ar.logoState) changedArtists += updated }
        }
        refreshInJellyfin(changedAlbums, changedArtists)
        return MusicStepSummary(listOf(
            if (covers > 0) "$covers covers fetched" else "",
            if (noCover > 0) "$noCover without a cover on the Cover Art Archive" else "",
            if (pictures > 0) "$pictures artist pictures" else "",
            if (bios > 0) "$bios biographies" else "",
        ))
    }

    // ── fetch_lyrics (FR-277-8) ──

    suspend fun fetchLyrics(scopeAll: Boolean, albumIds: Set<String>? = null, force: Boolean = false): MusicStepSummary {
        val snap = store.snapshot()
        val now = nowEpochSec()
        var found = 0; var none = 0
        val touched = LinkedHashSet<String>()
        val updates = mutableListOf<MusicTrack>()
        var skipped = 0
        for (t in snap.tracks.values.filter { it.missingSince == null && it.path != null && (albumIds == null || it.albumId in albumIds) }) {
            // Phase 292 (dev review 8b/8c) — *Remove the lyrics* is final; a song with no singing is never asked about.
            if (t.lyricsState == MusicLyrics.BLOCKED) continue
            val sidecar = lyricsSidecar(t)
            if (sidecar != null) {
                val (file, state) = sidecar
                // Dev review 8a — backfill: a sidecar no newer than the last lookup was there before we looked.
                val source = t.lyricsSource ?: run {
                    val mtime = dev.jellystructure.media.FileIntegrityService.stampOf(file)?.mtime
                    if (t.lyricsCheckedAt != null && mtime != null && mtime >= t.lyricsCheckedAt) dev.jellystructure.model.MusicLyricsSource.LRCLIB else dev.jellystructure.model.MusicLyricsSource.FOUND
                }
                val hash = t.lyricsHash ?: if (source == dev.jellystructure.model.MusicLyricsSource.LRCLIB) fileHash(file) else null
                if (t.lyricsState != state || t.lyricsSource != source || t.lyricsHash != hash)
                    updates += t.copy(lyricsState = state, lyricsCheckedAt = if (t.lyricsState != state) now else t.lyricsCheckedAt, lyricsSource = source, lyricsHash = hash)
                continue
            }
            if (snap.versions.of(t).blocksLyrics) { skipped++; continue }
            val due = force || scopeAll || t.lyricsCheckedAt == null || now - t.lyricsCheckedAt >= LYRICS_RETRY_SEC
            if (!due) continue
            val artist = t.artists.firstOrNull()?.name ?: continue
            val album = t.albumId?.let { snap.albums[it]?.title }
            val l = Lrclib.find(artist, t.title, album, t.durationMs?.let { (it / 1000).toInt() }) ?: continue   // no answer: next time
            val base = t.path!!.substringBeforeLast('.')
            val written = when {
                l.synced != null -> l.synced
                l.plain != null -> l.plain
                else -> null
            }
            val state = when {
                l.synced != null -> if (writeText("$base.lrc", l.synced)) MusicLyrics.SYNCED else null
                l.plain != null -> if (writeText("$base.txt", l.plain)) MusicLyrics.PLAIN else null
                l.instrumental -> MusicLyrics.INSTRUMENTAL
                else -> MusicLyrics.NONE
            } ?: continue
            if (state == MusicLyrics.SYNCED || state == MusicLyrics.PLAIN) { found++; t.albumId?.let { touched += it } } else none++
            // Dev review 8a — what was written, so only our own file is ever removed.
            updates += if (written != null) t.copy(lyricsState = state, lyricsCheckedAt = now, lyricsSource = dev.jellystructure.model.MusicLyricsSource.LRCLIB, lyricsLrclibId = l.id, lyricsHash = dev.jellystructure.auth.sha256Hex(written))
                else t.copy(lyricsState = state, lyricsCheckedAt = now)
        }
        store.putTracks(updates)
        refreshInJellyfin(touched.mapNotNull { snap.albums[it] }, emptyList())
        touched.forEach { history?.record(it, "music_lyrics", "Lyrics fetched from LRCLIB") }
        return MusicStepSummary(listOf(if (found > 0) "$found songs got lyrics" else "", if (none > 0) "$none without" else "",
            if (skipped > 0) "$skipped not looked up (no singing)" else ""))
    }

    private fun fileHash(path: String): String? = runCatching { FileIo.readText(Path(path)) }.getOrNull()?.let { dev.jellystructure.auth.sha256Hex(it) }

    /**
     * Phase 292 (FR-292-15 action 1, dev review 8) — *Remove the lyrics*, pressed by the admin, never run on its own:
     * every song of the Dashboard row loses the sidecar **jellystructure wrote** (and only while the file is still
     * what was written), keeps any embedded lyric and any file someone else put there, and is marked [MusicLyrics.BLOCKED]
     * so no run gives it lyrics again. The albums are refreshed in Jellyfin.
     */
    suspend fun removeLyricsOnNoSinging(trackIds: Collection<String>? = null): String {
        val snap = store.snapshot()
        val songs = snap.tracks.values.filter { it.missingSince == null && (trackIds == null || it.id in trackIds) && snap.versions.lyricsOnNoSinging(it) }
        if (songs.isEmpty()) return "Nothing to remove"
        var deleted = 0; var kept = 0
        val now = nowEpochSec()
        val updates = songs.map { t ->
            val file = lyricsFile(t)
            if (file != null) {
                val ours = t.lyricsSource == dev.jellystructure.model.MusicLyricsSource.LRCLIB && t.lyricsHash != null && fileHash(file) == t.lyricsHash
                if (ours && platform.posix.remove(file) == 0) deleted++ else kept++
            }
            t.copy(lyricsState = MusicLyrics.BLOCKED, lyricsCheckedAt = now)
        }
        store.putTracks(updates)
        val albums = songs.mapNotNull { it.albumId }.distinct()
        refreshInJellyfin(albums.mapNotNull { snap.albums[it] }, emptyList())
        val sentence = buildString {
            append("Lyrics removed from ${updates.size} song${if (updates.size == 1) "" else "s"} with no singing")
            if (deleted > 0) append(" · $deleted file${if (deleted == 1) "" else "s"} jellystructure wrote deleted")
            if (kept > 0) append(" · $kept file${if (kept == 1) "" else "s"} not ours kept (hidden from viewers)")
        }
        albums.forEach { runCatching { history?.record(it, "music_lyrics", sentence) } }
        return sentence
    }

    /**
     * Phase 292 (FR-292-15 action 2, Q9) — *Tell LRCLIB it is instrumental*, by hand only: one publish per song of
     * the Dashboard row, through [Lrclib.publishInstrumental]. Slow (a proof of work per song), so the caller runs it
     * in the background; the outcome goes to each album's History.
     */
    suspend fun tellLrclibInstrumental(trackIds: Collection<String>? = null): String {
        val snap = store.snapshot()
        val songs = snap.tracks.values.filter { t ->
            t.missingSince == null && (trackIds == null || t.id in trackIds) && snap.versions.of(t).blocksLyrics &&
                (snap.versions.hasLyrics(t) || t.lyricsState == MusicLyrics.BLOCKED)
        }
        var ok = 0
        val failed = mutableListOf<String>()
        for (t in songs) {
            val artist = t.artists.firstOrNull()?.name
            val album = t.albumId?.let { snap.albums[it]?.title }
            val dur = t.durationMs?.let { (it / 1000).toInt() }
            if (artist == null || album == null || dur == null) { failed += "${t.title}: no artist, album or length"; continue }
            val err = Lrclib.publishInstrumental(artist, t.title, album, dur)
            if (err == null) ok++ else failed += "${t.title}: $err"
        }
        val sentence = "Told LRCLIB $ok of ${songs.size} song${if (songs.size == 1) "" else "s"} ${if (ok == 1) "is" else "are"} instrumental" +
            if (failed.isNotEmpty()) " · ${failed.size} failed (${failed.first()})" else ""
        songs.mapNotNull { it.albumId }.distinct().forEach { runCatching { history?.record(it, "music_lyrics", sentence) } }
        Logger.info("lrclib publish: $sentence", "music")
        return sentence
    }

    private suspend fun writeText(path: String, text: String): Boolean = runCatching {
        val tmp = "$path.tmp"; FileIo.writeText(Path(tmp), text); platform.posix.rename(tmp, path) == 0
    }.getOrElse { Logger.warn("Could not write $path: ${it.message}", "music"); false }

    // ── write_music_nfo (FR-277-4, FR-277-7) ──

    data class NfoOutcome(val album: MusicNfo.Outcome?, val artists: Int)

    private suspend fun writeAlbumNfo(a: MusicAlbum, overwrite: Boolean): MusicNfo.Result {
        val snap = store.snapshot()
        val xml = MusicNfo.albumXml(a, snap.tracksByAlbum[a.id].orEmpty(), snap.artists)
        val r = MusicNfo.write(MusicNfo.albumPath(a), xml, a.nfoHash, overwrite)
        val now = nowEpochSec()
        when (r.outcome) {
            MusicNfo.Outcome.WRITTEN -> {
                store.putAlbum(a.copy(nfoHash = r.hash, nfoWrittenAt = now, nfoDriftAt = if (r.driftFields > 0) now else a.nfoDriftAt, nfoDriftFields = if (r.driftFields > 0) r.driftFields else a.nfoDriftFields))
                history?.record(a.id, "music_nfo", if (r.driftFields > 0) "album.nfo re-asserted — ${r.driftFields} fields had been changed by someone else" else "album.nfo written")
            }
            MusicNfo.Outcome.FOREIGN_SKIPPED -> if (a.nfoDriftAt == null || a.nfoDriftFields != r.driftFields)
                store.putAlbum(a.copy(nfoDriftAt = now, nfoDriftFields = r.driftFields))
            else -> Unit
        }
        return r
    }

    private suspend fun writeArtistNfo(ar: MusicArtist, overwrite: Boolean): MusicNfo.Result {
        val albums = store.snapshot().albumsByArtist[ar.id].orEmpty().filter { a -> a.albumArtists.any { it.artistId == ar.id } }
        val r = MusicNfo.write(MusicNfo.artistPath(ar), MusicNfo.artistXml(ar, albums), ar.nfoHash, overwrite)
        if (r.outcome == MusicNfo.Outcome.WRITTEN) store.putArtist(ar.copy(nfoHash = r.hash, nfoWrittenAt = nowEpochSec()))
        return r
    }

    /** The step: every matched album and every artist with a folder and something to say. Unmatched albums get no
     *  NFO (FR-277-4). Jellyfin re-reads each album (and its tracks) and artist that was written. */
    suspend fun writeNfos(): MusicStepSummary {
        val overwrite = configStore.current.behavior.overwriteNfo
        val snap = store.snapshot()
        val albums = snap.albums.values.filter { it.missingSince == null && it.matchState == MusicMatch.MATCHED && it.path != null }
        val written = mutableListOf<MusicAlbum>(); var skipped = 0
        for (a in albums) when (writeAlbumNfo(a, overwrite).outcome) {
            MusicNfo.Outcome.WRITTEN -> written += a
            MusicNfo.Outcome.FOREIGN_SKIPPED -> skipped++
            else -> Unit
        }
        val artists = snap.artists.values.filter {
            it.missingSince == null && it.path != null && (it.matchState == MusicMatch.MATCHED || it.biographyEdited != null)
        }
        val writtenArtists = artists.filter { writeArtistNfo(it, overwrite).outcome == MusicNfo.Outcome.WRITTEN }
        refreshInJellyfin(written, writtenArtists)
        return MusicStepSummary(listOf(
            if (written.isNotEmpty()) "${written.size} album.nfo written" else "",
            if (writtenArtists.isNotEmpty()) "${writtenArtists.size} artist.nfo written" else "",
            if (skipped > 0) "$skipped not overwritten (another tool's NFO; overwrite is off)" else "",
        ))
    }

    /** Save → NFO / Re-assert: one album (and its album artists), overwriting whatever is there — the admin asked. */
    suspend fun saveAlbum(albumId: String, sync: Boolean): MusicNfo.Outcome? {
        val a = store.album(albumId) ?: return null
        if (a.matchState != MusicMatch.MATCHED) return MusicNfo.Outcome.NO_FOLDER
        val r = writeAlbumNfo(a, overwrite = true)
        val arts = a.albumArtists.mapNotNull { store.artist(it.artistId) }.filter { it.path != null }
        arts.forEach { writeArtistNfo(it, overwrite = true) }
        if (sync) refreshInJellyfin(listOfNotNull(store.album(albumId)), arts)
        return r.outcome
    }

    suspend fun saveArtist(artistId: String, sync: Boolean): MusicNfo.Outcome? {
        val ar = store.artist(artistId) ?: return null
        val r = writeArtistNfo(ar, overwrite = true)
        if (sync) refreshInJellyfin(emptyList(), listOfNotNull(store.artist(artistId)))
        return r.outcome
    }

    /** FR-277-7 — the drift banner's question, asked on read: is the NFO on disk still the one written here? */
    fun albumDrift(a: MusicAlbum): Int? {
        val path = MusicNfo.albumPath(a) ?: return null
        val onDisk = MusicNfo.read(path) ?: return null
        if (a.nfoHash == null || MusicNfo.hash(onDisk) == a.nfoHash) return null
        val snap = store.snapshot()
        return MusicNfo.differingFields(onDisk, MusicNfo.albumXml(a, snap.tracksByAlbum[a.id].orEmpty(), snap.artists)).coerceAtLeast(1)
    }

    // ── the admin's own picks (FR-277-2) ──

    suspend fun albumCandidates(albumId: String): List<MusicArtCandidate>? {
        val a = store.album(albumId) ?: return emptyList()
        if (a.releaseGroupMbid == null) return emptyList()
        val caa = CoverArtArchive.images(a.releaseMbid, a.releaseGroupMbid) ?: return null
        val fa = a.albumArtists.firstNotNullOfOrNull { store.artist(it.artistId)?.mbid }?.let { if (fanart.available) fanart.artist(it) else null }
        return caa + fa?.albums?.get(a.releaseGroupMbid).orEmpty()
    }

    suspend fun artistCandidates(artistId: String): List<MusicArtCandidate>? {
        val ar = store.artist(artistId) ?: return emptyList()
        val out = mutableListOf<MusicArtCandidate>()
        ar.mbid?.let { if (fanart.available) fanart.artist(it) else null }?.let { out += it.thumbs + it.backgrounds + it.logos }
        ar.urls["image"]?.let { WikimediaCommons.picture(it) }?.let { out += it }
        return out
    }

    suspend fun useAlbumCover(albumId: String, url: String, source: String): Boolean {
        val a = store.album(albumId) ?: return false
        val dest = coverPath(a) ?: return false
        if (!artwork.downloadTo(url, dest)) return false
        artwork.markManual(dest)
        store.putAlbum(a.copy(coverState = MusicArt.FILE, coverSource = source, coverMissingOnCaa = false))
        history?.record(a.id, "music_cover", "Cover chosen by hand ($source)")
        refreshInJellyfin(listOf(a), emptyList())
        return true
    }

    suspend fun useArtistImage(artistId: String, kind: String, url: String, source: String, credit: String?): Boolean {
        val ar = store.artist(artistId) ?: return false
        val dest = artistImagePath(ar, kind) ?: return false
        if (!artwork.downloadTo(url, dest)) return false
        artwork.markManual(dest)
        store.putArtist(withArt(ar, kind, MusicArt.FILE, source, credit))
        refreshInJellyfin(emptyList(), listOf(ar))
        return true
    }

    private fun withArt(ar: MusicArtist, kind: String, state: String, source: String?, credit: String?) = when (kind) {
        "thumb" -> ar.copy(imageState = state, imageSource = source, imageCredit = credit)
        "background" -> ar.copy(backdropState = state)
        else -> ar.copy(logoState = state)
    }

    /** An upload: validated as an image by its bytes, written as the folder's own file, and locked. */
    suspend fun uploadAlbumCover(albumId: String, bytes: ByteArray): Boolean {
        val a = store.album(albumId) ?: return false
        val dest = coverPath(a) ?: return false
        if (!writeImage(dest, bytes)) return false
        store.putAlbum(a.copy(coverState = MusicArt.FILE, coverSource = "upload", coverMissingOnCaa = false))
        history?.record(a.id, "music_cover", "Cover uploaded")
        refreshInJellyfin(listOf(a), emptyList())
        return true
    }

    suspend fun uploadArtistImage(artistId: String, kind: String, bytes: ByteArray): Boolean {
        val ar = store.artist(artistId) ?: return false
        val dest = artistImagePath(ar, kind) ?: return false
        if (!writeImage(dest, bytes)) return false
        store.putArtist(withArt(ar, kind, MusicArt.FILE, "upload", null))
        refreshInJellyfin(emptyList(), listOf(ar))
        return true
    }

    private suspend fun writeImage(dest: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || sniffImageSignature(bytes) == null) return false
        val ok = runCatching { val tmp = "$dest.tmp"; FileIo.writeBytes(Path(tmp), bytes); platform.posix.rename(tmp, dest) == 0 }.getOrDefault(false)
        if (ok) artwork.markManual(dest)
        return ok
    }

    suspend fun clearAlbumCover(albumId: String): Boolean {
        val a = store.album(albumId) ?: return false
        val dest = coverPath(a) ?: return false
        artwork.removeImage(dest)
        store.putAlbum(a.copy(coverState = if (existingCover(a) != null) MusicArt.FILE else MusicArt.NONE, coverSource = null))
        refreshInJellyfin(listOf(a), emptyList())
        return true
    }

    suspend fun clearArtistImage(artistId: String, kind: String): Boolean {
        val ar = store.artist(artistId) ?: return false
        val dest = artistImagePath(ar, kind) ?: return false
        artwork.removeImage(dest)
        val still = if (existingArtistImage(ar, kind) != null) MusicArt.FILE else MusicArt.NONE
        store.putArtist(withArt(ar, kind, still, null, null))
        refreshInJellyfin(emptyList(), listOf(ar))
        return true
    }

    fun setLock(path: String?, locked: Boolean) {
        path ?: return
        if (locked) artwork.markManual(path) else artwork.unmarkManual(path)
    }

    /** FR-277-6 — the admin's own biography (null = back to the fetched lead). Written into `artist.nfo` next run. */
    suspend fun setBiography(artistId: String, text: String?): MusicArtist? {
        val ar = store.artist(artistId) ?: return null
        return ar.copy(biographyEdited = text?.trim()?.takeIf { it.isNotBlank() }).also { store.putArtist(it) }
    }
}
