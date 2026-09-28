package dev.jellystructure.music

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicHealth
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.reencodesOnPhone
import dev.jellystructure.tv.normalizeGuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.concurrent.AtomicLong
import kotlin.concurrent.AtomicReference

/**
 * Phase 275 — the music rows, read from one in-memory snapshot (a household library is thousands of rows, not
 * millions) and written through to SQLite. Every write replaces the snapshot, so a reader never sees half a scan.
 */
class MusicStore(private val db: JellystructureDb) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()
    private val cache = AtomicReference<Snapshot?>(null)
    private val versionAtomic = AtomicLong(0L)

    /** Bumped on every write — a cache keyed on it (279's home rows) rebuilds after a scan or a match. */
    val version: Long get() = versionAtomic.value

    data class Snapshot(
        val artists: Map<String, MusicArtist>,
        val albums: Map<String, MusicAlbum>,
        val tracks: Map<String, MusicTrack>,
    ) {
        val tracksByAlbum: Map<String?, List<MusicTrack>> by lazy {
            tracks.values.groupBy { it.albumId }.mapValues { (_, l) -> l.sortedWith(compareBy({ it.disc ?: 1 }, { it.position ?: Int.MAX_VALUE }, { it.title })) }
        }
        /** Albums an artist is credited on as album artist, or on any of its tracks. */
        val albumsByArtist: Map<String, List<MusicAlbum>> by lazy {
            val out = HashMap<String, LinkedHashSet<MusicAlbum>>()
            for (a in albums.values) a.albumArtists.forEach { out.getOrPut(it.artistId) { LinkedHashSet() }.add(a) }
            for (t in tracks.values) {
                val album = t.albumId?.let { albums[it] } ?: continue
                t.artists.forEach { out.getOrPut(it.artistId) { LinkedHashSet() }.add(album) }
            }
            out.mapValues { it.value.toList() }
        }
    }

    fun snapshot(): Snapshot = cache.value ?: load().also { cache.value = it }

    private fun load(): Snapshot {
        val q = db.musicQueries
        val artists = q.allArtists().executeAsList().mapNotNull { runCatching { json.decodeFromString(MusicArtist.serializer(), it) }.getOrNull() }
        val albums = q.allAlbums().executeAsList().mapNotNull { runCatching { json.decodeFromString(MusicAlbum.serializer(), it) }.getOrNull() }
        val tracks = q.allTracks().executeAsList().mapNotNull { runCatching { json.decodeFromString(MusicTrack.serializer(), it) }.getOrNull() }
        return Snapshot(artists.associateBy { it.id }, albums.associateBy { it.id }, tracks.associateBy { it.id })
    }

    fun artist(id: String): MusicArtist? = snapshot().artists[id]
    fun album(id: String): MusicAlbum? = snapshot().albums[id]
    fun track(id: String): MusicTrack? = snapshot().tracks[id]

    /** Replace everything the scan of one library produced (present and missing rows), in one transaction. */
    suspend fun replaceLibrary(rows: MusicLibraryRows) = writeLock.withLock {
        db.transaction {
            rows.artists.forEach { writeArtist(it) }
            rows.albums.forEach { writeAlbum(it) }
            rows.tracks.forEach { writeTrack(it) }
        }
        val prev = snapshot()
        cache.value = Snapshot(
            prev.artists + rows.artists.associateBy { it.id },
            prev.albums + rows.albums.associateBy { it.id },
            prev.tracks + rows.tracks.associateBy { it.id },
        )
        versionAtomic.incrementAndGet()
    }

    suspend fun putAlbum(album: MusicAlbum) = writeLock.withLock {
        db.transaction { writeAlbum(album) }
        val prev = snapshot(); cache.value = prev.copy(albums = prev.albums + (album.id to album)); versionAtomic.incrementAndGet()
    }

    suspend fun putArtist(artist: MusicArtist) = writeLock.withLock {
        db.transaction { writeArtist(artist) }
        val prev = snapshot(); cache.value = prev.copy(artists = prev.artists + (artist.id to artist)); versionAtomic.incrementAndGet()
    }

    suspend fun putTracks(tracks: List<MusicTrack>) = writeLock.withLock {
        if (tracks.isEmpty()) return@withLock
        db.transaction { tracks.forEach { writeTrack(it) } }
        val prev = snapshot(); cache.value = prev.copy(tracks = prev.tracks + tracks.associateBy { it.id }); versionAtomic.incrementAndGet()
    }

    private fun writeArtist(a: MusicArtist) {
        db.musicQueries.putArtist(
            id = a.id, library_id = a.libraryId, json = json.encodeToString(MusicArtist.serializer(), a),
            name = a.name, sort_name = a.sortName, mbid = a.mbid, match_state = a.matchState,
            match_locked = if (a.matchLocked) 1L else 0L, search_text = searchText(a.name, a.sortName),
            missing_since = a.missingSince, updated_at = a.updatedAt,
        )
    }

    private fun writeAlbum(a: MusicAlbum) {
        db.musicQueries.putAlbum(
            id = a.id, library_id = a.libraryId, json = json.encodeToString(MusicAlbum.serializer(), a),
            title = a.title, sort_name = a.sortName, year = a.year?.toLong(),
            release_group_mbid = a.releaseGroupMbid, release_mbid = a.releaseMbid, match_state = a.matchState,
            match_locked = if (a.matchLocked) 1L else 0L, cover_state = a.coverState,
            search_text = searchText(a.title, a.sortName, *a.albumArtists.map { it.name }.toTypedArray()),
            missing_since = a.missingSince, updated_at = a.updatedAt,
        )
        db.musicQueries.deleteCreditsFor(a.id)
        a.albumArtists.forEachIndexed { i, c -> db.musicQueries.putCredit(a.id, c.artistId, "album_artist", i.toLong()) }
    }

    private fun writeTrack(t: MusicTrack) {
        db.musicQueries.putTrack(
            id = t.id, album_id = t.albumId, library_id = t.libraryId, json = json.encodeToString(MusicTrack.serializer(), t),
            title = t.title, disc = t.disc?.toLong(), position = t.position?.toLong(), duration_ms = t.durationMs,
            codec = t.codec, container = t.container, recording_mbid = t.recordingMbid,
            search_text = searchText(t.title, *t.artists.map { it.name }.toTypedArray()),
            missing_since = t.missingSince, updated_at = t.updatedAt,
        )
        db.musicQueries.deleteCreditsFor(t.id)
        t.artists.forEachIndexed { i, c -> db.musicQueries.putCredit(t.id, c.artistId, "artist", i.toLong()) }
    }

    /** FR-275-6 — the `/api/health` block. Missing rows are not counted: they are not in the library. */
    fun health(): MusicHealth {
        val s = snapshot()
        val albums = s.albums.values.filter { it.missingSince == null }
        val artists = s.artists.values.filter { it.missingSince == null }
        val tracks = s.tracks.values.filter { it.missingSince == null }
        return MusicHealth(
            artists = artists.size, albums = albums.size, tracks = tracks.size,
            matched = albums.count { it.matchState == MusicMatch.MATCHED },
            needsYou = albums.count { it.matchState == MusicMatch.NEEDS_YOU },
            unmatched = albums.count { it.matchState == MusicMatch.UNMATCHED },
            coversMissing = albums.count { it.coverState == MusicArt.NONE },
            // An artist known only as a credit has no folder to put a picture in — not a work item.
            artistImagesMissing = artists.count { it.path != null && it.imageState == MusicArt.NONE },
            reencodes = tracks.count { it.reencodesOnPhone() },
        )
    }

    companion object {
        /** Lower-cased words for the admin's search (FR-275-8). */
        fun searchText(vararg parts: String?): String =
            parts.filterNotNull().joinToString(" ").lowercase().replace(Regex("\\s+"), " ").trim()
    }
}

/**
 * FR-275-4 — the films' visibility rule for a music row: a restricted Jellyfin user sees it only when its library
 * is in their `EnabledFolders`; an unrestricted user (`allowed == null`) sees everything. Fail-closed on a row
 * with no library.
 */
fun musicVisible(libraryId: String?, allowed: Set<String>?): Boolean =
    allowed == null || (libraryId != null && normalizeGuid(libraryId) in allowed)
