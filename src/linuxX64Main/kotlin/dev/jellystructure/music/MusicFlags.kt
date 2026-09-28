package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicFlagDto
import dev.jellystructure.model.MusicFlagOther

/**
 * Phase 283 (FR-283-1) — the two things an album's folder can tell the admin that its tags do not: several folders
 * claiming one album, and a folder whose name and the songs' tags name different things. Pure, and computed on read
 * from the stored albums: nothing here guesses which side is right — that is the admin's knowledge — it only says
 * where they disagree.
 */
object MusicFlags {
    const val SHARED = "shared_album"
    const val FOLDER = "folder_disagrees"

    private val DISC = Regex("^(cd|disc|disk)\\s*\\d+$", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^]]*]|\\{[^}]*}")
    private val LEADING_YEAR = Regex("^\\s*(19|20)\\d{2}\\s*[-–.]\\s*")
    private val YEAR = Regex("\\b(19|20)\\d{2}\\b")

    /** The music libraries' own folders: an album directly in one has no artist folder. */
    fun roots(cfg: dev.jellystructure.config.AppConfig): Set<String> =
        MusicScanner.musicLibraries(cfg).map { it.localPath.trimEnd('/') }.filter { it.isNotBlank() }.toSet()

    /** Case-, punctuation- and space-blind: what two names are compared on. */
    fun key(s: String?): String = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /** A folder name without what folder names carry and tags do not: `(Single)`, `[FLAC]`, `(2004)`, a year, and a
     *  leading `<Artist> - ` or `<Year> - `. */
    fun cleanFolder(name: String, artist: String?): String {
        var s = BRACKETS.replace(name, " ")
        s = LEADING_YEAR.replace(s, " ")
        val a = artist?.trim().orEmpty()
        if (a.isNotEmpty()) {
            val t = s.trim()
            val sep = Regex("^" + Regex.escape(a) + "\\s*[-–]\\s*", RegexOption.IGNORE_CASE)
            s = sep.replace(t, "")
        }
        s = YEAR.replace(s, " ")
        return s.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '.', '_')
    }

    /** Two names agree when either contains the other; an empty side says nothing, so it agrees. */
    fun agree(a: String?, b: String?): Boolean {
        val x = key(a); val y = key(b)
        if (x.isEmpty() || y.isEmpty()) return true
        return x.contains(y) || y.contains(x)
    }

    /** The album folder's name and the artist folder's above it; a disc folder counts as its parent, and an album
     *  directly in a library folder has no artist folder. Null without a path. */
    data class Folders(val album: String, val artist: String?)

    /** The album's own folder: a disc folder (`CD2`) belongs to the album folder above it. */
    fun albumDir(path: String?): String? {
        val clean = path?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        return if (DISC.matches(clean.substringAfterLast('/'))) clean.substringBeforeLast('/', "") else clean
    }

    fun folders(path: String?, roots: Set<String>): Folders? {
        val clean = path?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        var dir = clean
        if (DISC.matches(dir.substringAfterLast('/'))) dir = dir.substringBeforeLast('/', "")
        val album = dir.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        val parent = dir.substringBeforeLast('/', "")
        val artist = if (parent.isEmpty() || parent in roots) null else parent.substringAfterLast('/').takeIf { it.isNotBlank() }
        return Folders(album, artist)
    }

    /** What an album's `shared_album` or `folder_disagrees` was dismissed for: the flag comes back when it changes. */
    fun fingerprint(kind: String, album: MusicAlbum, others: List<MusicAlbum>, roots: Set<String>): String = when (kind) {
        SHARED -> others.map { it.id }.sorted().joinToString(",")
        else -> {
            val f = folders(album.path, roots)
            listOf(f?.album, f?.artist, album.title, album.albumArtists.firstOrNull()?.name).joinToString("|") { it.orEmpty() }
        }
    }

    private fun artistName(a: MusicAlbum) = a.albumArtists.joinToString(" & ") { it.name }

    /** The flags per album id, dismissed ones left out. [roots] are the music libraries' own folders. */
    fun of(albums: Collection<MusicAlbum>, roots: Set<String>): Map<String, List<MusicFlagDto>> = raw(albums, roots, withDismissed = false)

    /** Every flag, dismissed or not (the Album page offers *Show it again*). */
    fun raw(albums: Collection<MusicAlbum>, roots: Set<String>, withDismissed: Boolean): Map<String, List<MusicFlagDto>> {
        val live = albums.filter { it.missingSince == null }
        val byName = live.groupBy { key(artistName(it)) + "\u0000" + key(it.title) }.filterKeys { !it.endsWith("\u0000") }
        val byGroup = live.filter { it.releaseGroupMbid != null }.groupBy { it.releaseGroupMbid!! }
        val out = LinkedHashMap<String, MutableList<MusicFlagDto>>()
        for (a in live.sortedBy { it.id }) {
            val others = ((byName[key(artistName(a)) + "\u0000" + key(a.title)].orEmpty()) + (a.releaseGroupMbid?.let { byGroup[it] }.orEmpty()))
                .filter { it.id != a.id && albumDir(it.path) != albumDir(a.path) }.distinctBy { it.id }
                .sortedBy { (folders(it.path, roots)?.album ?: it.title).lowercase() }
            val f = folders(a.path, roots)
            if (others.isNotEmpty() && (withDismissed || a.flagsDismissed[SHARED] != fingerprint(SHARED, a, others, roots))) {
                val n = others.size + 1
                out.getOrPut(a.id) { mutableListOf() } += MusicFlagDto(
                    kind = SHARED,
                    sentence = "$n folders say they are this album",
                    folder = f?.album, folderArtist = f?.artist, filesTitle = a.title, filesArtist = artistName(a).ifBlank { null },
                    search = f?.let { cleanFolder(it.album, a.albumArtists.firstOrNull()?.name) }?.takeIf { it.isNotBlank() && !agree(it, a.title) },
                    others = others.map { MusicFlagOther(it.id, it.title, folders(it.path, roots)?.album) },
                    writtenFromMatch = a.releaseGroupMbid != null && (a.nfoWrittenAt != null || a.coverSource == "caa"),
                )
            }
            if (f != null) {
                val cleaned = cleanFolder(f.album, a.albumArtists.firstOrNull()?.name)
                val titleOff = !agree(cleaned, a.title)
                val names = a.albumArtists.map { it.name }
                val artistOff = f.artist != null && names.isNotEmpty() && names.none { agree(f.artist, it) }
                if ((titleOff || artistOff) && (withDismissed || a.flagsDismissed[FOLDER] != fingerprint(FOLDER, a, emptyList(), roots))) {
                    out.getOrPut(a.id) { mutableListOf() } += MusicFlagDto(
                        kind = FOLDER,
                        sentence = "The folder and the songs disagree",
                        folder = f.album, folderArtist = f.artist, filesTitle = a.title, filesArtist = artistName(a).ifBlank { null },
                        search = cleaned.takeIf { titleOff && it.isNotBlank() },
                        writtenFromMatch = a.releaseGroupMbid != null && (a.nfoWrittenAt != null || a.coverSource == "caa"),
                    )
                }
            }
        }
        return out
    }
}
