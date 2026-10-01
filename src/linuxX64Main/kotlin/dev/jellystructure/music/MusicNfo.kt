package dev.jellystructure.music

import dev.jellystructure.io.FileIo
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.effectiveGenres
import dev.jellystructure.model.originalDate
import dev.jellystructure.model.originalYear
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 277 (FR-277-4) — `album.nfo` in the album folder and `artist.nfo` in the artist folder, in Kodi's music
 * schema, read by Jellyfin's `AlbumNfoProvider` / `ArtistNfoProvider` (confirmed in its source, research §2.3).
 * Provider ids are written in the lowercase element names Jellyfin's parser keys on (`musicbrainzalbumid`,
 * `musicbrainzreleasegroupid`, `musicbrainzartistid`, `musicbrainzalbumartistid`); credits and tracks in Kodi's own
 * camelCase shape, so a Kodi install reads the same files. Pure builders; [write] is the only I/O.
 */
object MusicNfo {
    private fun String.x(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun StringBuilder.el(indent: String, name: String, value: String?) {
        if (!value.isNullOrBlank()) append("$indent<$name>${value.x()}</$name>\n")
    }

    fun albumPath(a: MusicAlbum): String? = a.path?.let { "${it.trimEnd('/')}/album.nfo" }
    fun artistPath(a: MusicArtist): String? = a.path?.let { "${it.trimEnd('/')}/artist.nfo" }

    private fun duration(ms: Long?): String? = ms?.takeIf { it > 0 }?.let { val s = it / 1000; "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    fun albumXml(album: MusicAlbum, tracks: List<MusicTrack>, artists: Map<String, MusicArtist>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n<album>\n")
        el("  ", "title", album.title)
        el("  ", "musicbrainzalbumid", album.releaseMbid)
        el("  ", "musicbrainzreleasegroupid", album.releaseGroupMbid)
        val credit = album.mbArtists.joinToString("") { it.name + it.joinPhrase }.trim().ifBlank { album.albumArtists.joinToString(" & ") { it.name } }
        el("  ", "artistdesc", credit)
        album.albumArtists.forEach { el("  ", "artist", it.name); el("  ", "albumartist", it.name) }
        album.albumArtists.firstOrNull()?.let { artists[it.artistId]?.mbid }?.let { el("  ", "musicbrainzalbumartistid", it) }
        album.effectiveGenres().forEach { el("  ", "genre", it) }
        el("  ", "releasetype", album.primaryType?.lowercase())
        if ("Compilation" in album.secondaryTypes) el("  ", "compilation", "true")
        el("  ", "releasedate", album.release?.date)
        // Phase 290 (FR-290-2) — the year the album first came out, the files' year when that is earlier.
        el("  ", "originalreleasedate", album.originalDate())
        el("  ", "year", album.originalYear()?.toString())
        el("  ", "label", album.release?.label)
        for (a in album.albumArtists) {
            val mbid = artists[a.artistId]?.mbid ?: album.mbArtists.firstOrNull { MusicScoring.pairCredit(a.name, listOf(it)) != null }?.mbid
            append("  <albumArtistCredits>\n")
            el("    ", "artist", a.name)
            el("    ", "musicBrainzArtistID", mbid)
            append("  </albumArtistCredits>\n")
        }
        // Only the tracks on disk (acceptance 2: a partial album lists what it holds).
        for (t in tracks.filter { it.missingSince == null }.sortedWith(compareBy({ it.disc ?: 1 }, { it.position ?: Int.MAX_VALUE }))) {
            append("  <track>\n")
            if ((t.disc ?: 1) > 1) el("    ", "disc", t.disc.toString())
            el("    ", "position", t.position?.toString())
            el("    ", "title", t.title)
            el("    ", "duration", duration(t.durationMs))
            el("    ", "musicBrainzTrackID", t.releaseTrackMbid)
            append("  </track>\n")
        }
        append("</album>\n")
    }

    fun artistXml(artist: MusicArtist, albums: List<MusicAlbum>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>\n<artist>\n")
        el("  ", "name", artist.name)
        el("  ", "musicBrainzArtistID", artist.mbid)
        el("  ", "musicbrainzartistid", artist.mbid)
        el("  ", "sortname", artist.mbSortName ?: artist.sortName)
        el("  ", "type", artist.type)
        el("  ", "disambiguation", artist.disambiguation)
        dev.jellystructure.model.MusicGenrePick.pick(artist.mbGenres).forEach { el("  ", "genre", it) }
        val begin = artist.lifeSpan?.substringBefore('–')?.takeIf { it.isNotBlank() && it != "?" }
        val end = artist.lifeSpan?.substringAfter('–', "")?.takeIf { it.isNotBlank() && it != "?" }
        if (artist.type.equals("Person", ignoreCase = true)) { el("  ", "born", begin); el("  ", "died", end) }
        else { el("  ", "formed", begin); el("  ", "disbanded", end) }
        el("  ", "biography", artist.biographyEdited ?: artist.biographies["en"] ?: artist.biographies.values.firstOrNull())
        for (a in albums.filter { it.missingSince == null }.sortedBy { it.originalDate() ?: "9999" }) {
            append("  <album>\n")
            el("    ", "title", a.title)
            el("    ", "year", a.originalYear()?.toString())
            el("    ", "musicbrainzreleasegroupid", a.releaseGroupMbid)
            append("  </album>\n")
        }
        // FR-277-1 — a Commons picture's licence travels with the artist.
        artist.imageCredit?.let { append("  <!-- Picture: ${it.replace("--", "–")} -->\n") }
        append("</artist>\n")
    }

    fun hash(text: String): String = text.hashCode().toString()

    /** How many of the lines in [onDisk] are not in [ours] — the drift banner's *n fields differ*. */
    fun differingFields(onDisk: String, ours: String): Int {
        fun lines(s: String) = s.lines().map { it.trim() }.filter { it.startsWith("<") && !it.startsWith("<?") && !it.startsWith("<!--") }
        val mine = lines(ours).toHashSet()
        return lines(onDisk).count { it !in mine }
    }

    enum class Outcome { WRITTEN, UNCHANGED, FOREIGN_SKIPPED, NO_FOLDER, FAILED }
    data class Result(val outcome: Outcome, val hash: String?, val driftFields: Int = 0)

    fun read(path: String): String? = runCatching { FileIo.readText(Path(path)) }.getOrNull()

    /**
     * Write [xml] to [path] atomically (`.tmp` + rename). Unchanged content is a no-op. A file on disk that is not the
     * one jellystructure last wrote ([storedHash]) — Jellyfin's own saver, another tool — is replaced only when
     * [overwrite] (`behavior.overwrite_nfo`, 175's rule); either way the result says how many fields differed.
     */
    suspend fun write(path: String?, xml: String, storedHash: String?, overwrite: Boolean): Result {
        path ?: return Result(Outcome.NO_FOLDER, null)
        val newHash = hash(xml)
        val onDisk = read(path)
        if (onDisk != null && hash(onDisk) == newHash) return Result(Outcome.UNCHANGED, newHash)
        val foreign = onDisk != null && (storedHash == null || hash(onDisk) != storedHash)
        val drift = if (foreign) differingFields(onDisk!!, xml) else 0
        if (foreign && !overwrite) return Result(Outcome.FOREIGN_SKIPPED, storedHash, drift)
        val ok = runCatching {
            val tmp = "$path.tmp"
            FileIo.writeText(Path(tmp), xml)
            platform.posix.rename(tmp, path) == 0
        }.getOrDefault(false)
        if (!ok) runCatching { SystemFileSystem.delete(Path("$path.tmp")) }
        return if (ok) Result(Outcome.WRITTEN, newHash, drift) else Result(Outcome.FAILED, storedHash, drift)
    }
}
