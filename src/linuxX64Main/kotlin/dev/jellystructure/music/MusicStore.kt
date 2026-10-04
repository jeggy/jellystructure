package dev.jellystructure.music

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicHealth
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicRecordingFacts
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicVersionChoice
import dev.jellystructure.model.MusicVersionTypeInfo
import dev.jellystructure.model.MusicVersions
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
        /** Phase 292 — MusicBrainz's version facts per recording mbid. */
        val facts: Map<String, MusicRecordingFacts> = emptyMap(),
        /** Phase 292 — the owner's ticks, per recording key (`rec:` / `trk:`), per type. */
        val choices: Map<String, Map<String, MusicVersionChoice>> = emptyMap(),
        /** Phase 292 — the owner's colour and meaning per type (absent = the default). */
        val typeOverrides: Map<String, MusicVersionTypeOverride> = emptyMap(),
        /** Phase 305 (FR-305-4) — *Use as the official album*, per album id. */
        val picks: Map<String, MusicOfficialPick> = emptyMap(),
        /** Phase 305 (FR-305-6 rule 4) — *Move to…*, per single id (album id null = *No album*). */
        val homeOverrides: Map<String, MusicSingleHome.Override> = emptyMap(),
        /** Phase 305 (FR-305-8) — the owner's *same song* / *not the same song*, keyed (a, b) with a < b. */
        val sameSong: Map<Pair<String, String>, String> = emptyMap(),
        /** Phase 305 (FR-305-9) — what `compare_songs` measured. */
        val soundPairs: List<MusicSoundPair> = emptyList(),
    ) {
        /** Phase 305 — official lists, extras, single homes and B-sides, computed once per snapshot. */
        val editions: MusicEditionsIndex by lazy { MusicEditionsIndex(this) }

        /** Phase 305 — one copy of every song, computed once per snapshot (the groups are never stored). */
        val songs: MusicSongIndex by lazy { MusicSongIndex(this) }

        /** Phase 292 — every song's version answer, computed once per snapshot. */
        val versions: MusicVersionIndex by lazy { MusicVersionIndex(this) }

        /** Phase 292 (FR-292-13) — the nine types with the owner's colour and meaning. */
        val versionTypes: List<MusicVersionTypeInfo> by lazy {
            MusicVersions.TYPES.map { t -> typeOverrides[t.key]?.let { o -> t.copy(color = o.color ?: t.color, meaning = o.meaning ?: t.meaning) } ?: t }
        }

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
        val v = db.musicVersionsQueries
        val facts = v.allFacts().executeAsList().mapNotNull { runCatching { json.decodeFromString(MusicRecordingFacts.serializer(), it) }.getOrNull() }
        val choices = v.allChoices().executeAsList().groupBy { it.recording_key }
            .mapValues { (_, rows) -> rows.associate { it.type to MusicVersionChoice(it.type, it.state == STATE_ON, it.set_at) } }
        val types = v.allTypes().executeAsList().associate { it.key to MusicVersionTypeOverride(it.color, it.meaning) }
        val e = db.musicEditionsQueries
        val picks = e.allPicks().executeAsList().associate { it.album_id to MusicOfficialPick(it.album_id, it.release_mbid, it.release_group_mbid, it.set_at) }
        val homes = e.allHomes().executeAsList().associate { it.album_id to MusicSingleHome.Override(it.home_album_id, it.set_at) }
        val same = e.allSameSong().executeAsList().associate { (it.track_a to it.track_b) to it.state }
        val sound = e.allSoundPairs().executeAsList().map { MusicSoundPair(it.track_a, it.track_b, it.ber, it.coverage, it.offset_ms, it.measured_at) }
        return Snapshot(artists.associateBy { it.id }, albums.associateBy { it.id }, tracks.associateBy { it.id }, facts.associateBy { it.recordingMbid }, choices, types,
            picks, homes, same, sound)
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
        cache.value = prev.copy(
            artists = prev.artists + rows.artists.associateBy { it.id },
            albums = prev.albums + rows.albums.associateBy { it.id },
            tracks = prev.tracks + rows.tracks.associateBy { it.id },
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

    // ── Phase 292: versions ──

    /** MusicBrainz's facts for some recordings (a match, the catch-up); replaces what was known for each. */
    suspend fun putFacts(facts: List<MusicRecordingFacts>) = writeLock.withLock {
        if (facts.isEmpty()) return@withLock
        db.transaction { facts.forEach { db.musicVersionsQueries.putFacts(it.recordingMbid, json.encodeToString(MusicRecordingFacts.serializer(), it), it.fetchedAt) } }
        val prev = snapshot(); cache.value = prev.copy(facts = prev.facts + facts.associateBy { it.recordingMbid }); versionAtomic.incrementAndGet()
    }

    /**
     * The owner's ticks: for every key in [keys], each type in [changes] becomes on (`true`), removed (`false`) or
     * goes back to automatic (`null`). One transaction for the whole selection.
     */
    suspend fun setChoices(keys: Collection<String>, changes: Map<String, Boolean?>, now: Long) = setChoices(keys.associateWith { changes }, now)

    /** Per key, its own changes (*Set version…* on a selection: each recording once, one transaction). */
    suspend fun setChoices(plans: Map<String, Map<String, Boolean?>>, now: Long) = writeLock.withLock {
        if (plans.isEmpty()) return@withLock
        val q = db.musicVersionsQueries
        db.transaction {
            for ((k, changes) in plans) for ((type, on) in changes) {
                if (on == null) q.deleteChoice(k, type) else q.putChoice(k, type, if (on) STATE_ON else STATE_REMOVED, now)
            }
        }
        val prev = snapshot()
        val next = prev.choices.toMutableMap()
        for ((k, changes) in plans) {
            val m = next[k].orEmpty().toMutableMap()
            for ((type, on) in changes) if (on == null) m.remove(type) else m[type] = MusicVersionChoice(type, on, now)
            if (m.isEmpty()) next.remove(k) else next[k] = m
        }
        cache.value = prev.copy(choices = next); versionAtomic.incrementAndGet()
    }

    /** *Back to automatic* (FR-292-7): every tick on these keys goes. */
    suspend fun clearChoices(keys: Collection<String>) = writeLock.withLock {
        db.transaction { keys.forEach { db.musicVersionsQueries.deleteChoices(it) } }
        val prev = snapshot(); cache.value = prev.copy(choices = prev.choices - keys.toSet()); versionAtomic.incrementAndGet()
    }

    /**
     * Dev review 6 — an unmatched song gained its recording: its `trk:` ticks move to [to]. Where both carry a tick
     * for one type, the newer wins. [copyOnly] keeps the source (a converted file is a new item, the old one goes).
     */
    suspend fun moveChoices(from: String, to: String, copyOnly: Boolean = false) = writeLock.withLock {
        val prev = snapshot()
        val src = prev.choices[from].orEmpty()
        if (src.isEmpty() || from == to) return@withLock
        val merged = prev.choices[to].orEmpty().toMutableMap()
        for ((type, c) in src) { val had = merged[type]; if (had == null || c.setAt >= had.setAt) merged[type] = c }
        val q = db.musicVersionsQueries
        db.transaction {
            merged.values.forEach { q.putChoice(to, it.type, if (it.on) STATE_ON else STATE_REMOVED, it.setAt) }
            if (!copyOnly) q.deleteChoices(from)
        }
        val next = prev.choices.toMutableMap()
        next[to] = merged
        if (!copyOnly) next.remove(from)
        cache.value = prev.copy(choices = next); versionAtomic.incrementAndGet()
    }

    /** FR-292-13 — a type's colour and meaning; null leaves that half as it is. */
    suspend fun putVersionType(key: String, color: String?, meaning: String?) = writeLock.withLock {
        val prev = snapshot()
        val had = prev.typeOverrides[key]
        val next = MusicVersionTypeOverride(color ?: had?.color, meaning ?: had?.meaning)
        db.transaction { db.musicVersionsQueries.putType(key, next.color, next.meaning) }
        cache.value = prev.copy(typeOverrides = prev.typeOverrides + (key to next)); versionAtomic.incrementAndGet()
    }

    // ── Phase 305: the owner's decisions and the sound measurements ──

    /** FR-305-4 — *Use as the official album*: the row, and the picked tracklist on the album's JSON, in one write. */
    suspend fun putPick(pick: MusicOfficialPick, album: MusicAlbum) = writeLock.withLock {
        db.transaction {
            db.musicEditionsQueries.putPick(pick.albumId, pick.releaseMbid, pick.releaseGroupMbid, pick.setAt)
            writeAlbum(album)
        }
        val prev = snapshot(); cache.value = prev.copy(picks = prev.picks + (pick.albumId to pick), albums = prev.albums + (album.id to album)); versionAtomic.incrementAndGet()
    }

    /** *Back to automatic*, and a re-match to another group or *Clear match* (dev review 3). */
    suspend fun deletePick(album: MusicAlbum) = writeLock.withLock {
        db.transaction {
            db.musicEditionsQueries.deletePick(album.id)
            writeAlbum(album)
        }
        val prev = snapshot(); cache.value = prev.copy(picks = prev.picks - album.id, albums = prev.albums + (album.id to album)); versionAtomic.incrementAndGet()
    }

    /** FR-305-6 rule 4 — *Move to…* ([home] null = *No album*); [clear] = *Back to automatic*. */
    suspend fun putHome(singleId: String, home: String?, now: Long, clear: Boolean = false) = writeLock.withLock {
        db.transaction { if (clear) db.musicEditionsQueries.deleteHome(singleId) else db.musicEditionsQueries.putHome(singleId, home, now) }
        val prev = snapshot()
        cache.value = prev.copy(homeOverrides = if (clear) prev.homeOverrides - singleId else prev.homeOverrides + (singleId to MusicSingleHome.Override(home, now)))
        versionAtomic.incrementAndGet()
    }

    /** FR-305-8/14 — the owner's decision on a pair, kept with a < b whatever order it is given; null clears it. */
    suspend fun putSameSong(x: String, y: String, state: String?, now: Long) = writeLock.withLock {
        val k = MusicSameSong.key(x, y)
        db.transaction { if (state == null) db.musicEditionsQueries.deleteSameSong(k.first, k.second) else db.musicEditionsQueries.putSameSong(k.first, k.second, state, now) }
        val prev = snapshot()
        cache.value = prev.copy(sameSong = if (state == null) prev.sameSong - k else prev.sameSong + (k to state))
        versionAtomic.incrementAndGet()
    }

    /** FR-305-9 — `compare_songs`' measurements; replaces each pair's earlier one. */
    suspend fun putSoundPairs(pairs: List<MusicSoundPair>) = writeLock.withLock {
        if (pairs.isEmpty()) return@withLock
        val norm = pairs.map { p -> if (p.a < p.b) p else p.copy(a = p.b, b = p.a, offsetMs = -p.offsetMs) }
        db.transaction { norm.forEach { db.musicEditionsQueries.putSoundPair(it.a, it.b, it.ber, it.coverage, it.offsetMs, it.measuredAt) } }
        val prev = snapshot()
        val keys = norm.mapTo(HashSet()) { it.a to it.b }
        cache.value = prev.copy(soundPairs = prev.soundPairs.filter { (it.a to it.b) !in keys } + norm)
        versionAtomic.incrementAndGet()
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
        const val STATE_ON = "on"
        const val STATE_REMOVED = "removed"

        /** Lower-cased words for the admin's search (FR-275-8). */
        fun searchText(vararg parts: String?): String =
            parts.filterNotNull().joinToString(" ").lowercase().replace(Regex("\\s+"), " ").trim()
    }
}

/** Phase 305 (FR-305-4) — the owner's pick of one pressing as an album's official tracklist. */
data class MusicOfficialPick(val albumId: String, val releaseMbid: String, val releaseGroupMbid: String?, val setAt: Long)

/** Phase 292 — the owner's colour and meaning for one type; null = the default. */
data class MusicVersionTypeOverride(val color: String?, val meaning: String?)

/**
 * FR-275-4 — the films' visibility rule for a music row: a restricted Jellyfin user sees it only when its library
 * is in their `EnabledFolders`; an unrestricted user (`allowed == null`) sees everything. Fail-closed on a row
 * with no library.
 */
fun musicVisible(libraryId: String?, allowed: Set<String>?): Boolean =
    allowed == null || (libraryId != null && normalizeGuid(libraryId) in allowed)
