package dev.jellystructure.music

import dev.jellystructure.auth.JellyfinMusicItem
import dev.jellystructure.auth.JellyfinMusicLibrary
import dev.jellystructure.auth.JellyfinNameId
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicCredit
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.util.isoToEpochSeconds

/** One music library's rows after a scan — present and missing alike (a missing row is kept, flagged). */
data class MusicLibraryRows(
    val libraryId: String,
    val artists: List<MusicArtist>,
    val albums: List<MusicAlbum>,
    val tracks: List<MusicTrack>,
) {
    val missingCount: Int get() =
        artists.count { it.missingSince != null } + albums.count { it.missingSince != null } + tracks.count { it.missingSince != null }
}

/**
 * Phase 275 (FR-275-2) — Jellyfin's view of one music library, turned into jellystructure's rows. Pure: the
 * filesystem is asked through [exists], the clock is [now] (epoch seconds), so the rules can be tested.
 *
 * - **Jellyfin fills; everything else is carried.** A scan rewrites only what Jellyfin reports (names, paths,
 *   formats, gains, credits, genres) on top of the previous row, so match state, MusicBrainz ids, locks and
 *   anything a later phase adds survive untouched — a scan never undoes a match or an admin's decision.
 * - **Absent is flagged, never deleted.** A row of this library that Jellyfin no longer reports keeps its data
 *   and gets `missingSince` (the first scan that missed it); reappearing clears it. The caller only calls this
 *   with a complete fetch ([dev.jellystructure.auth.JellyfinClient.getMusicLibrary] is all-or-nothing).
 * - **Credit-only artists.** `/Artists?ParentId=` lists folder-backed artists; an artist Jellyfin knows only
 *   from a track's `ArtistItems` (a guest, a compilation's performer) is still an artist, with no folder.
 */
object MusicIngest {
    private val COVER_FILES = listOf("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png", "front.jpg", "front.png")
    private val ARTIST_FILES = listOf("folder.jpg", "folder.jpeg", "folder.png", "artist.jpg", "artist.png", "thumb.jpg", "poster.jpg")
    private val BACKDROP_FILES = listOf("backdrop.jpg", "fanart.jpg", "background.jpg")
    private val LOGO_FILES = listOf("logo.png", "clearlogo.png")

    private fun fileState(folder: String?, names: List<String>, exists: (String) -> Boolean): String =
        if (folder != null && names.any { exists("${folder.trimEnd('/')}/$it") }) MusicArt.FILE else MusicArt.NONE

    /** Jellyfin's path → jellystructure's (the same prefix swap the film scanner does). */
    fun localPath(lib: LibraryMapping, jellyfinPath: String?): String? {
        val p = jellyfinPath?.takeIf { it.isNotBlank() } ?: return null
        return if (lib.jellyfinPath.isNotBlank() && lib.localPath.isNotBlank() && p.startsWith(lib.jellyfinPath))
            lib.localPath + p.removePrefix(lib.jellyfinPath) else p
    }

    fun build(
        lib: LibraryMapping,
        jf: JellyfinMusicLibrary,
        previousArtists: Map<String, MusicArtist>,
        previousAlbums: Map<String, MusicAlbum>,
        previousTracks: Map<String, MusicTrack>,
        now: Long,
        exists: (String) -> Boolean,
    ): MusicLibraryRows {
        val libId = lib.jellyfinId
        fun credits(list: List<JellyfinNameId>) = list.filter { it.id.isNotBlank() }.map { MusicCredit(it.id, it.name) }

        // ── tracks ──
        val tracks = jf.tracks.map { t ->
            val prev = previousTracks[t.id]
            val stream = t.mediaStreams.firstOrNull { it.type.equals("Audio", ignoreCase = true) }
            val fresh = MusicTrack(
                id = t.id,
                albumId = t.albumId ?: t.parentId,
                libraryId = libId,
                title = t.name,
                sortName = t.sortName,
                disc = t.parentIndexNumber,
                position = t.indexNumber,
                durationMs = t.runTimeTicks?.let { it / 10_000 },
                year = t.year,
                path = localPath(lib, t.path),
                container = t.container?.lowercase(),
                codec = stream?.codec?.lowercase(),
                bitrate = stream?.bitRate,
                sampleRate = stream?.sampleRate,
                channels = stream?.channels,
                artists = credits(t.artistItems).ifEmpty { credits(t.albumArtists) },
                genres = t.genres.filter { it.isNotBlank() }.distinct(),
                trackGainDb = t.normalizationGain,
                albumGainDb = t.albumNormalizationGain,
                hasLyrics = t.hasLyrics == true,
                jellyfinProviderIds = t.providerIds.ids(),
                addedAt = t.dateCreated?.let { isoToEpochSeconds(it) },
            )
            carry(fresh, prev, now)
        }
        val tracksByAlbum = tracks.groupBy { it.albumId }

        // ── albums ──
        val albums = jf.albums.map { a ->
            val prev = previousAlbums[a.id]
            val own = tracksByAlbum[a.id].orEmpty()
            val path = localPath(lib, a.path)
            val fresh = MusicAlbum(
                id = a.id,
                libraryId = libId,
                title = a.name,
                sortName = a.sortName,
                year = a.year ?: own.mapNotNull { it.year }.minOrNull(),
                path = path,
                albumArtists = credits(a.albumArtists).ifEmpty { credits(a.artistItems) },
                genres = a.genres.filter { it.isNotBlank() }.ifEmpty { own.flatMap { it.genres } }.distinct(),
                trackCount = own.size,
                durationMs = own.sumOf { it.durationMs ?: 0L },
                albumGainDb = a.normalizationGain ?: own.firstNotNullOfOrNull { it.albumGainDb },
                coverState = artState(path, COVER_FILES, a, exists),
                jellyfinProviderIds = a.providerIds.ids(),
                addedAt = a.dateCreated?.let { isoToEpochSeconds(it) },
            )
            carry(fresh, prev, now)
        }

        // ── artists: folder-backed, then every credit that names someone else ──
        val folderArtists = jf.artists.associateBy { it.id }
        val creditNames = LinkedHashMap<String, String>()
        (jf.albums.flatMap { it.albumArtists + it.artistItems } + jf.tracks.flatMap { it.artistItems + it.albumArtists })
            .filter { it.id.isNotBlank() }
            .forEach { if (it.id !in creditNames) creditNames[it.id] = it.name }
        val artistIds = LinkedHashSet<String>().apply { addAll(folderArtists.keys); addAll(creditNames.keys) }
        val artists = artistIds.map { id ->
            val a = folderArtists[id]
            val prev = previousArtists[id]
            val path = a?.let { localPath(lib, it.path) }
            val fresh = MusicArtist(
                id = id,
                libraryId = libId,
                name = a?.name?.takeIf { it.isNotBlank() } ?: creditNames[id].orEmpty(),
                sortName = a?.sortName,
                path = path,
                jellyfinProviderIds = a?.providerIds?.ids() ?: emptyMap(),
                imageState = if (a == null) MusicArt.NONE else artState(path, ARTIST_FILES, a, exists),
                backdropState = fileState(path, BACKDROP_FILES, exists),
                logoState = fileState(path, LOGO_FILES, exists),
                addedAt = a?.dateCreated?.let { isoToEpochSeconds(it) },
            )
            carry(fresh, prev, now)
        }

        // ── what this library had and Jellyfin no longer reports ──
        fun <T> gone(prev: Map<String, T>, seen: Set<String>, libOf: (T) -> String?, flag: (T) -> T): List<T> =
            prev.filter { (id, row) -> id !in seen && libOf(row) == libId }.values.map(flag)
        val goneTracks = gone(previousTracks, tracks.mapTo(HashSet()) { it.id }, { it.libraryId }) { it.copy(missingSince = it.missingSince ?: now) }
        val goneAlbums = gone(previousAlbums, albums.mapTo(HashSet()) { it.id }, { it.libraryId }) { it.copy(missingSince = it.missingSince ?: now) }
        val goneArtists = gone(previousArtists, artistIds, { it.libraryId }) { it.copy(missingSince = it.missingSince ?: now) }

        return MusicLibraryRows(libId, artists + goneArtists, albums + goneAlbums, tracks + goneTracks)
    }

    private fun Map<String, String?>.ids(): Map<String, String> =
        entries.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()

    private fun artState(folder: String?, names: List<String>, item: JellyfinMusicItem, exists: (String) -> Boolean): String = when {
        folder != null && names.any { exists("${folder.trimEnd('/')}/$it") } -> MusicArt.FILE
        !item.imageTags["Primary"].isNullOrBlank() -> MusicArt.JELLYFIN
        else -> MusicArt.NONE
    }

    // ── carry: the previous row, with only Jellyfin's fields replaced. Listing what Jellyfin owns (not what to
    //    keep) is what makes a field a later phase adds — a match candidate, a lock, a biography — survive every
    //    scan without this file having to learn about it.

    private fun carry(fresh: MusicTrack, prev: MusicTrack?, now: Long): MusicTrack {
        if (prev == null) return fresh.copy(createdAt = now, updatedAt = now)
        val merged = prev.copy(
            albumId = fresh.albumId, libraryId = fresh.libraryId, title = fresh.title, sortName = fresh.sortName,
            disc = fresh.disc, position = fresh.position, durationMs = fresh.durationMs, year = fresh.year,
            path = fresh.path, container = fresh.container, codec = fresh.codec, bitrate = fresh.bitrate,
            sampleRate = fresh.sampleRate, channels = fresh.channels, artists = fresh.artists, genres = fresh.genres,
            trackGainDb = fresh.trackGainDb, albumGainDb = fresh.albumGainDb, hasLyrics = fresh.hasLyrics,
            jellyfinProviderIds = fresh.jellyfinProviderIds, addedAt = fresh.addedAt, missingSince = null,
        )
        return if (merged == prev) prev else merged.copy(updatedAt = now)
    }

    private fun carry(fresh: MusicAlbum, prev: MusicAlbum?, now: Long): MusicAlbum {
        if (prev == null) return fresh.copy(createdAt = now, updatedAt = now)
        val merged = prev.copy(
            libraryId = fresh.libraryId, title = fresh.title, sortName = fresh.sortName, year = fresh.year,
            path = fresh.path, albumArtists = fresh.albumArtists, genres = fresh.genres, trackCount = fresh.trackCount,
            durationMs = fresh.durationMs, albumGainDb = fresh.albumGainDb, coverState = fresh.coverState,
            // A cover that disappeared from the folder no longer has the source it had.
            coverSource = if (fresh.coverState == MusicArt.NONE) null else prev.coverSource,
            jellyfinProviderIds = fresh.jellyfinProviderIds, addedAt = fresh.addedAt, missingSince = null,
        )
        return if (merged == prev) prev else merged.copy(updatedAt = now)
    }

    private fun carry(fresh: MusicArtist, prev: MusicArtist?, now: Long): MusicArtist {
        if (prev == null) return fresh.copy(createdAt = now, updatedAt = now)
        val merged = prev.copy(
            libraryId = fresh.libraryId, name = fresh.name, sortName = fresh.sortName,
            // A credit-only sighting never erases a folder an earlier scan saw.
            path = fresh.path ?: prev.path,
            jellyfinProviderIds = fresh.jellyfinProviderIds.ifEmpty { prev.jellyfinProviderIds },
            imageState = if (fresh.path == null && prev.path != null) prev.imageState else fresh.imageState,
            addedAt = fresh.addedAt ?: prev.addedAt, missingSince = null,
            // A credit-only sighting has no folder to look in: keep what the folder showed.
            backdropState = if (fresh.path == null) prev.backdropState else fresh.backdropState,
            logoState = if (fresh.path == null) prev.logoState else fresh.logoState,
        )
        return if (merged == prev) prev else merged.copy(updatedAt = now)
    }
}
