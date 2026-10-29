package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinAudioUserItem
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.policyHash
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicLyrics
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.effectiveGenres
import dev.jellystructure.model.originalYear
import dev.jellystructure.shared.tv.LyricLine
import dev.jellystructure.shared.tv.MusicAlbumCard
import dev.jellystructure.shared.tv.MusicAlbumDetail
import dev.jellystructure.shared.tv.MusicAlbumGroup
import dev.jellystructure.shared.tv.MusicArtistCard
import dev.jellystructure.shared.tv.MusicArtistDetail
import dev.jellystructure.shared.tv.MusicArtistRef
import dev.jellystructure.shared.tv.MusicGenreCount
import dev.jellystructure.shared.tv.MusicHome
import dev.jellystructure.shared.tv.MusicLastPlayed
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicPlaylistAdded
import dev.jellystructure.shared.tv.MusicRow
import dev.jellystructure.shared.tv.MusicSearch
import dev.jellystructure.shared.tv.MusicSelection
import dev.jellystructure.shared.tv.MUSIC_SELECTION_CAP
import dev.jellystructure.shared.tv.MusicSinglesUnder
import dev.jellystructure.shared.tv.MusicTrackCopies
import dev.jellystructure.shared.tv.MusicTrackCopy
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.MusicVersionType
import dev.jellystructure.shared.tv.MusicVideoCard
import dev.jellystructure.shared.tv.TrackLyrics
import dev.jellystructure.tv.tvToken
import dev.jellystructure.tv.tvTokenForClient
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** DEB-2254 (FR-1, FR-13) — why a playlist write changed nothing, and the status the route answers it with. */
enum class PlaylistRefusal(val status: HttpStatusCode) {
    /** `tvTokenForClient` gave no token: the paired token is rejected. The server key is never used instead. */
    UNAUTHORIZED(HttpStatusCode.Unauthorized),
    /** The viewer does not own the playlist. */
    NOT_OWNER(HttpStatusCode.Forbidden),
    BAD_REQUEST(HttpStatusCode.BadRequest),
    NOT_FOUND(HttpStatusCode.NotFound),
    /** Jellyfin did not do it. */
    FAILED(HttpStatusCode.BadGateway),
}

sealed class PlaylistWrite<out T> {
    class Done<out T>(val value: T) : PlaylistWrite<T>()
    class Refused(val why: PlaylistRefusal) : PlaylistWrite<Nothing>()
}

/**
 * Phase 279 — the phone's music, read from 275's rows and scoped to the viewer (275 FR-275-4: a library the viewer
 * may not open is not there at all). Play counts, last-played and favourites are the viewer's own, from Jellyfin,
 * cached a short while per viewer. Every image is a `/api/tv/image/music/…` URL; nothing returned names a provider,
 * a codec or a delivery (FR-279-10).
 */
class MusicTvService(
    private val store: MusicStore,
    /** Our own lyrics sidecar beside a song (277), read when Jellyfin has not picked it up yet. */
    private val lyricsFile: (MusicTrack) -> String?,
    private val jellyfin: JellyfinClient,
    private val configStore: ConfigStore,
    /** R388 — the sample of a capped *Shuffle all*; a test seeds it. */
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
    private val videos: suspend (DeviceData) -> List<dev.jellystructure.model.MediaItem> = { emptyList() },
    /** DEB-2655 (FR-9) — the radio stations [DeviceData]'s viewer may open (shown, available, in their visible set). */
    private val stations: suspend (DeviceData) -> List<dev.jellystructure.shared.tv.LiveTvChannel> = { emptyList() },
) {
    companion object {
        const val PAGE_SIZE = 60
        /** FR-279-2 / open question 1 — below this a mix is the whole library over again (measured: 60 tracks). */
        private const val USER_TTL_MS = 30_000L
        /** DEB-2254 (FR-3) — the longest name Jellyfin is given for a playlist. */
        private const val PLAYLIST_NAME_MAX = 100
        /** DEB-2254 (FR-5) — entries read to de-duplicate against, and to place a move. */
        private const val PLAYLIST_ENTRY_LIMIT = 20_000
        private val SELECTION_SCOPES = setOf("library", "artists", "albums", "tracks", "genre", "search")
        private val GROUP_ORDER = listOf("album", "single", "compilation", "live", "soundtrack")

        fun albumImage(a: MusicAlbum): String? = if (a.coverState != MusicArt.NONE) "/api/tv/image/music/album/${a.id.encodeURLPathPart()}?v=${a.updatedAt}" else null
        fun artistImage(r: MusicArtist): String? = if (r.imageState != MusicArt.NONE) "/api/tv/image/music/artist/${r.id.encodeURLPathPart()}?v=${r.updatedAt}" else null
        fun backgroundImage(r: MusicArtist): String? = if (r.backdropState == MusicArt.FILE) "/api/tv/image/music/background/${r.id.encodeURLPathPart()}?v=${r.updatedAt}" else null

        /** `[mm:ss.xx] line` → timed lines; tags like `[ar:…]` are skipped. Null when nothing is timed. */
        fun parseLrc(text: String): List<LyricLine>? {
            val stamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
            val out = mutableListOf<LyricLine>()
            for (raw in text.lines()) {
                val stamps = stamp.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val line = raw.substring(stamps.last().range.last + 1).trim()
                for (m in stamps) {
                    val (mm, ss, frac) = m.destructured
                    val ms = (mm.toLong() * 60 + ss.toLong()) * 1000 + when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.toLong() }
                    out += LyricLine(ms, line)
                }
            }
            return out.sortedBy { it.tMs }.takeIf { it.isNotEmpty() }
        }
    }

    /** What this viewer may see, from one snapshot. */
    inner class View(device: DeviceData) {
        private val snap = store.snapshot()
        private val allowed = device.allowedLibraries
        private val allowedTags = device.allowedTags
        /** Phase 292 — every song's version, over the whole library (one answer per recording, whoever looks). */
        val versions: MusicVersionIndex get() = snap.versions
        val versionTypes get() = snap.versionTypes
        /** DEB-1487 (FR-305-3a) — what an official-row fill reads about a held file. */
        val fill: MusicOfficial.Fill get() = snap.fill
        val albums: Map<String, MusicAlbum> = snap.albums.filterValues { it.missingSince == null && musicVisible(it.libraryId, allowed, allowedTags) }
        val tracks: Map<String, MusicTrack> = snap.tracks.filterValues { it.missingSince == null && musicVisible(it.libraryId, allowed, allowedTags) }
        val artists: Map<String, MusicArtist> = snap.artists.filterValues { it.missingSince == null && musicVisible(it.libraryId, allowed, allowedTags) }
        val tracksByAlbum: Map<String?, List<MusicTrack>> = tracks.values.groupBy { it.albumId }
            .mapValues { (_, l) -> l.sortedWith(compareBy({ it.disc ?: 1 }, { it.position ?: Int.MAX_VALUE }, { it.title.lowercase() })) }
        // Both are looked up once per artist in a sort or a selection, so each is one pass over the library, built on
        // first use, in the library's own order (a lookup answers exactly what the filter it replaces answered).
        private val albumsByArtist: Map<String, List<MusicAlbum>> by lazy {
            val m = HashMap<String, MutableList<MusicAlbum>>()
            for (a in albums.values) for (id in a.albumArtists.mapTo(HashSet()) { it.artistId }) m.getOrPut(id) { ArrayList() } += a
            m
        }
        private val songsByArtist: Map<String, List<MusicTrack>> by lazy {
            val m = HashMap<String, MutableList<MusicTrack>>()
            for (t in tracks.values) {
                val ids = HashSet<String>()
                for (r in t.artists) ids += r.artistId
                t.albumId?.let { albums[it] }?.albumArtists?.forEach { ids += it.artistId }
                for (id in ids) m.getOrPut(id) { ArrayList() } += t
            }
            m
        }
        fun ownAlbums(artistId: String): List<MusicAlbum> = albumsByArtist[artistId].orEmpty()
        fun songsBy(artistId: String): List<MusicTrack> = songsByArtist[artistId].orEmpty()

        // ── Phase 305 / R373: one copy of every song, chosen among the copies this viewer may see (dev review 8) ──
        val songs: MusicSongIndex get() = snap.songs
        val editions: MusicEditionsIndex get() = snap.editions
        /** The copies of [t]'s song this viewer may open, the better file first. */
        fun copies(t: MusicTrack): List<MusicTrack> = snap.songs.copies(t).filter { it.id in tracks }.ifEmpty { listOf(t) }
        /** FR-305-10 — the copy this viewer is shown and plays. */
        fun shown(t: MusicTrack): MusicTrack = copies(t).first()
        fun isShown(t: MusicTrack): Boolean = shown(t).id == t.id
        /** One copy of every song, the order of each song's first appearance kept. */
        fun fold(list: List<MusicTrack>): List<MusicTrack> = list.map { shown(it) }.distinctBy { it.id }
        /** A single or EP living under an album this viewer may open. */
        fun homedUnder(singleId: String): String? = snap.editions.homes[singleId]?.albumId?.takeIf { it in albums }
    }

    // ── the viewer's own numbers (Jellyfin) ──

    class UserMusic(val played: List<JellyfinAudioUserItem>, val favorites: Set<String>) {
        val playCount: Map<String, Int> = played.associate { it.id to (it.userData?.playCount ?: 0) }
        /** 305 dev review 8 — a song's numbers over its copies: the plays summed. */
        fun songPlays(v: View, t: MusicTrack): Int = v.copies(t).sumOf { playCount[it.id] ?: 0 }
    }
    private val userLock = Mutex()
    /** DEB-2518 (FR-8) — keyed on the viewer *and* the policy the answer was read under ([policy] = `DeviceData.policyHash()`). */
    private class CachedUser(val at: Long, val policy: Int, val music: UserMusic)
    private val userCache = HashMap<String, CachedUser>()

    suspend fun userMusic(device: DeviceData, fresh: Boolean = false): UserMusic {
        val now = dev.jellystructure.nowEpochSec() * 1000
        if (!fresh) userLock.withLock { userCache[device.jellyfinUserId]?.let { if (now - it.at < USER_TTL_MS && it.policy == device.policyHash()) return it.music } }
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return UserMusic(emptyList(), emptySet())
        val token = jellyfin.tvToken(base, device, cfg.apiKeys.jellyfinToken)
        val played = jellyfin.getAudioUserItems(base, token, device.jellyfinUserId, "IsPlayed")
        val favs = jellyfin.getAudioUserItems(base, token, device.jellyfinUserId, "IsFavorite", types = "Audio,MusicAlbum")
        val u = UserMusic(played.orEmpty(), favs.orEmpty().map { it.id }.toSet())
        // A failed read is not cached: the next request asks again.
        if (played != null && favs != null) userLock.withLock { userCache[device.jellyfinUserId] = CachedUser(now, device.policyHash(), u) }
        return u
    }

    /** DEB-2254 (FR-2) — per viewer, which playlists they own (definitive answers only), under the same clock and the same [forget]. */
    private val ownerCache = HashMap<String, Pair<Long, HashMap<String, Boolean>>>()

    suspend fun forget(device: DeviceData) = userLock.withLock { userCache.remove(device.jellyfinUserId); ownerCache.remove(device.jellyfinUserId) }

    // ── builders ──

    fun albumCard(v: View, a: MusicAlbum) = MusicAlbumCard(
        id = a.id, title = a.title, artists = a.albumArtists.map { MusicArtistRef(it.artistId, it.name) }, year = a.originalYear(),
        imageUrl = albumImage(a), type = MusicBrowse.albumType(a), trackCount = v.tracksByAlbum[a.id]?.size ?: 0,
    )

    fun artistCard(v: View, r: MusicArtist) = MusicArtistCard(
        id = r.id, name = r.name, imageUrl = artistImage(r), albumCount = v.ownAlbums(r.id).size,
        // R373 dev review 2 — songs, folded.
        trackCount = v.songsBy(r.id).mapTo(HashSet()) { v.songs.songOf(it) }.size,
    )

    /**
     * [folded] — a row of a list that spans albums (one copy of every song): *Bonus* only when the song is official
     * nowhere (305 owner decision 1), and a favourite when any copy is. Otherwise the row is this copy's own.
     */
    fun trackItem(v: View, t: MusicTrack, u: UserMusic?, folded: Boolean = false): MusicTrackItem {
        val album = t.albumId?.let { v.albums[it] }
        val copies = v.copies(t)
        return MusicTrackItem(
            id = t.id, title = t.title, albumId = t.albumId, album = album?.title,
            artists = t.artists.map { MusicArtistRef(it.artistId, it.name) }, disc = t.disc, position = t.position,
            // 292 (dev review 8d) — no lyrics button for a song with no singing, or whose lyrics the admin removed.
            durationMs = t.durationMs, hasLyrics = !v.versions.lyricsHidden(t) && (t.hasLyrics || t.lyricsState == MusicLyrics.SYNCED || t.lyricsState == MusicLyrics.PLAIN),
            imageUrl = album?.let { albumImage(it) }, trackGainDb = t.trackGainDb, albumGainDb = t.albumGainDb ?: album?.albumGainDb,
            favorite = if (folded) copies.any { u?.favorites?.contains(it.id) == true } else u?.favorites?.contains(t.id) == true,
            // R344 (FR-R344-1) — 292's shown set: a Session whose Live the owner removed goes as `session` alone.
            versions = v.versions.of(t).shown,
            // R373 (FR-R373-1/4/5).
            extra = if (folded) v.songs.bonus(t, copies) else v.editions.isExtra(t),
            alsoOn = copies.count { it.id != t.id },
        )
    }

    private fun recentAlbums(v: View) = v.albums.values.sortedWith(compareByDescending<MusicAlbum> { it.addedAt ?: it.createdAt }.thenBy { it.title.lowercase() })

    // ── FR-279-2 ──

    suspend fun home(device: DeviceData, radio: Boolean = false): MusicHome {
        val v = View(device)
        // DEB-2655 (FR-9) — the Radio row is last, after the genres, and only for a client that asked and a viewer with a station.
        val radioRow = if (radio) stations(device).takeIf { it.isNotEmpty() }?.let { MusicRow("radio", "Radio", stations = it) } else null
        if (v.albums.isEmpty() && v.tracks.isEmpty()) return MusicHome(rows = listOfNotNull(radioRow))
        val u = userMusic(device)
        val rows = mutableListOf<MusicRow>()
        recentAlbums(v).take(12).takeIf { it.isNotEmpty() }?.let { rows += MusicRow("recent", "Recently added", albums = it.map { a -> albumCard(v, a) }) }
        // R373 dev review 2 — deduplicated by song, order kept.
        v.fold(u.played.mapNotNull { v.tracks[it.id] }).take(5).takeIf { it.isNotEmpty() }?.let { rows += MusicRow("played", "Recently played", tracks = it.map { t -> trackItem(v, t, u, folded = true) }) }
        v.artists.values.filter { v.ownAlbums(it.id).isNotEmpty() }
            .sortedWith(compareBy<MusicArtist> { if (it.imageState != MusicArt.NONE) 0 else 1 }.thenBy { (it.sortName ?: it.name).lowercase() })
            .take(12).takeIf { it.isNotEmpty() }?.let { rows += MusicRow("artists", "Artists", artists = it.map { r -> artistCard(v, r) }) }
        // R326 (FR-R326-6) — no *Mix* row in round 1 (the owner, 2026-09-28); 279 amended in place.
        val byGenre = HashMap<String, MutableList<MusicAlbum>>()
        for (a in v.albums.values) for (g in a.effectiveGenres()) byGenre.getOrPut(g) { mutableListOf() } += a
        byGenre.filterValues { it.size >= 3 }.entries.sortedWith(compareByDescending<Map.Entry<String, MutableList<MusicAlbum>>> { it.value.size }.thenBy { it.key })
            .take(6).forEach { (g, list) -> rows += MusicRow("genre", g, albums = list.sortedByDescending { it.addedAt ?: it.createdAt }.take(12).map { albumCard(v, it) }) }
        // R344 (FR-R344-1, dev review 2) — the household's version colours ride the app's first music fetch.
        radioRow?.let { rows += it }
        return MusicHome(rows, versionTypes = v.versionTypes.map { MusicVersionType(it.key, it.color) })
    }

    // ── FR-279-3 ──

    suspend fun browse(device: DeviceData, what: String, sort: String?, page: Int, genre: String?): MusicList {
        val v = View(device)
        val u = if (sort == "played" || what == "tracks") userMusic(device) else null
        fun <T> slice(all: List<T>) = all.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        return when (what) {
            "artists" -> {
                val sorted = sortedArtists(v, u, sort)
                MusicList(artists = slice(sorted).map { artistCard(v, it) }, total = sorted.size, page = page, pageSize = PAGE_SIZE)
            }
            "tracks" -> {
                val sorted = sortedTracks(v, u, sort)
                MusicList(tracks = slice(sorted).map { trackItem(v, it, u, folded = true) }, total = sorted.size, page = page, pageSize = PAGE_SIZE)
            }
            else -> {
                val sorted = sortedAlbums(v, u, sort, genre)
                MusicList(albums = slice(sorted).map { albumCard(v, it) }, total = sorted.size, page = page, pageSize = PAGE_SIZE)
            }
        }
    }

    // The browse route's three orders, one copy: the list is drawn from them and R388's selection plays them.

    private fun sortedArtists(v: View, u: UserMusic?, sort: String?): List<MusicArtist> {
        val all = v.artists.values.filter { v.ownAlbums(it.id).isNotEmpty() || v.songsBy(it.id).isNotEmpty() }
        return when (sort) {
            "title" -> all.sortedBy { (it.sortName ?: it.name).lowercase() }
            "played" -> all.sortedByDescending { r -> v.songsBy(r.id).sumOf { u?.playCount?.get(it.id) ?: 0 } }
            else -> all.sortedByDescending { r -> v.ownAlbums(r.id).maxOfOrNull { it.addedAt ?: it.createdAt } ?: 0L }
        }
    }

    private fun sortedTracks(v: View, u: UserMusic?, sort: String?): List<MusicTrack> {
        // R373 (FR-R373-3) — one copy of every song: the better file this viewer may open.
        val all = v.tracks.values.filter { v.isShown(it) }
        return when (sort) {
            "title" -> all.sortedBy { it.title.lowercase() }
            "year" -> all.sortedWith(compareByDescending<MusicTrack> { it.albumId?.let { a -> v.albums[a]?.originalYear() } ?: it.year ?: 0 }.thenBy { it.title.lowercase() })
            "played" -> all.sortedWith(compareByDescending<MusicTrack> { u?.songPlays(v, it) ?: 0 }.thenBy { it.title.lowercase() })
            else -> all.sortedWith(compareByDescending<MusicTrack> { it.addedAt ?: it.createdAt }.thenBy { it.albumId }.thenBy { it.position ?: 0 })
        }
    }

    private fun sortedAlbums(v: View, u: UserMusic?, sort: String?, genre: String?): List<MusicAlbum> {
        val all = v.albums.values.filter { genre == null || genre in it.effectiveGenres() }
        return when (sort) {
            "title" -> all.sortedBy { (it.sortName ?: it.title).lowercase() }
            "year" -> all.sortedWith(compareByDescending<MusicAlbum> { it.originalYear() ?: 0 }.thenBy { it.title.lowercase() })
            "played" -> all.sortedByDescending { a -> v.tracksByAlbum[a.id].orEmpty().sumOf { u?.playCount?.get(it.id) ?: 0 } }
            else -> recentAlbums(v).filter { a -> all.any { it.id == a.id } }
        }
    }

    // ── R388 (FR-R388-2/3/4) — a selection, resolved to songs ──

    /**
     * What *Play all* / *Shuffle all* queues for the list the viewer is looking at: [scope] `library` · `artists` ·
     * `albums` · `tracks` · `genre` · `search`, in the order that list draws, one copy of every song, at most
     * [MUSIC_SELECTION_CAP]. Null for an unknown scope or a `genre` / `q` its scope needs and has not got (a 400).
     * [shuffle] draws the cap at random from the whole selection and keeps the list's relative order.
     */
    suspend fun selection(device: DeviceData, scope: String, sort: String?, genre: String?, q: String?, shuffle: Boolean): MusicSelection? {
        val genreName = genre?.takeIf { it.isNotBlank() }
        val query = q?.trim()?.takeIf { it.isNotEmpty() }
        if (scope !in SELECTION_SCOPES || (scope == "genre" && genreName == null) || (scope == "search" && query == null)) return null
        val v = View(device)
        val u = userMusic(device)
        fun albumSongs(a: MusicAlbum): List<MusicTrack> {
            val held = v.tracksByAlbum[a.id].orEmpty()
            return MusicOfficial.layout(a, held, v.fill).let { l -> if (l.official != null) l.rows.map { it.second } else held }
        }
        fun artistSongs(r: MusicArtist): List<MusicTrack> {
            val own = v.ownAlbums(r.id).sortedWith(compareBy<MusicAlbum> { it.originalYear() ?: 0 }.thenBy { it.title.lowercase() })
            val ownIds = own.mapTo(HashSet()) { it.id }
            val rest = v.songsBy(r.id).filter { it.albumId !in ownIds }
                .sortedWith(compareBy<MusicTrack> { it.albumId?.let { a -> v.albums[a]?.originalYear() } ?: it.year ?: 0 }.thenBy { it.albumId }.thenBy { it.disc ?: 1 }.thenBy { it.position ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
            return own.flatMap { albumSongs(it) } + rest
        }
        val ordered: List<MusicTrack> = when (scope) {
            "library" -> recentAlbums(v).flatMap { albumSongs(it) } +
                v.tracks.values.filter { it.albumId == null || it.albumId !in v.albums }.sortedWith(compareByDescending<MusicTrack> { it.addedAt ?: it.createdAt }.thenBy { it.title.lowercase() })
            "albums" -> sortedAlbums(v, u, sort, null).flatMap { albumSongs(it) }
            "genre" -> sortedAlbums(v, u, sort, genreName).flatMap { albumSongs(it) }
            "artists" -> sortedArtists(v, u, sort).flatMap { artistSongs(it) }
            "tracks" -> sortedTracks(v, u, sort)
            else -> searchMatches(v, query!!.lowercase()).let { m -> m.songs + m.albums.flatMap { albumSongs(it) } + m.artists.flatMap { artistSongs(it) } }
        }
        val folded = v.fold(ordered)
        val picked = if (shuffle && folded.size > MUSIC_SELECTION_CAP) folded.indices.shuffled(random).take(MUSIC_SELECTION_CAP).sorted().map { folded[it] }
            else folded.take(MUSIC_SELECTION_CAP)
        return MusicSelection(tracks = picked.map { trackItem(v, it, u, folded = true) }, total = folded.size, capped = folded.size > MUSIC_SELECTION_CAP)
    }

    fun genres(device: DeviceData): List<MusicGenreCount> {
        val v = View(device)
        val counts = HashMap<String, Int>()
        for (a in v.albums.values) for (g in a.effectiveGenres()) counts[g] = (counts[g] ?: 0) + 1
        return counts.map { MusicGenreCount(it.key, it.value) }.sortedWith(compareByDescending<MusicGenreCount> { it.albumCount }.thenBy { it.name.lowercase() })
    }

    private fun coversOf(v: View, albumIds: Sequence<String?>): List<String> =
        albumIds.mapNotNull { a -> a?.let { v.albums[it] }?.let { albumImage(it) } }.distinct().take(4).toList()

    /** FR-2 — does [token]'s user own playlist [id]? Asked of Jellyfin once per [USER_TTL_MS]; a failed ask is `false`, uncached. */
    private suspend fun isMine(device: DeviceData, base: String, token: String?, id: String): Boolean {
        if (token == null) return false
        val now = dev.jellystructure.nowEpochSec() * 1000
        userLock.withLock { ownerCache[device.jellyfinUserId]?.let { (at, m) -> if (now - at < USER_TTL_MS) m[id]?.let { return it } } }
        val status = jellyfin.playlistOwnerStatus(base, token, id)
        if (status != 200 && status != 403) return false
        val mine = status == 200
        userLock.withLock {
            val entry = ownerCache[device.jellyfinUserId]?.takeIf { now - it.first < USER_TTL_MS } ?: (now to HashMap<String, Boolean>()).also { ownerCache[device.jellyfinUserId] = it }
            entry.second[id] = mine
        }
        return mine
    }

    /** DEB-2518 (FR-2) — Jellyfin's playlists are not rows of ours, so [musicVisible] never sees them: an allow-listed viewer has no music, hence no playlist names, counts or writes. */
    private fun noMusicForTags(device: DeviceData) = device.allowedTags.isNotEmpty()

    suspend fun playlists(device: DeviceData): List<MusicPlaylist> {
        if (noMusicForTags(device)) return emptyList()
        val v = View(device)
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return emptyList()
        val token = jellyfin.tvToken(base, device, cfg.apiKeys.jellyfinToken)
        val ownToken = jellyfin.tvTokenForClient(base, device)
        return jellyfin.getMusicPlaylists(base, token, device.jellyfinUserId).orEmpty().map { p ->
            val covers = coversOf(v, jellyfin.getPlaylistEntries(base, token, device.jellyfinUserId, p.id).orEmpty().asSequence().map { e -> e.albumId ?: v.tracks[e.id]?.albumId })
            MusicPlaylist(p.id, p.name, p.childCount ?: 0, covers, mine = isMine(device, base, ownToken, p.id))
        }
    }

    /** R321 (FR-R321-10) — one of the viewer's playlists, in its own order; songs the viewer may not see are left out. */
    suspend fun playlist(device: DeviceData, id: String): MusicList? {
        if (noMusicForTags(device)) return null
        val v = View(device)
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return null
        val entries = jellyfin.getPlaylistEntries(base, jellyfin.tvToken(base, device, cfg.apiKeys.jellyfinToken), device.jellyfinUserId, id, limit = 1000) ?: return null
        val u = userMusic(device)
        val shown = entries.mapNotNull { e -> v.tracks[e.id]?.let { e to it } }
        // DEB-2254 (FR-9) — each row carries its entry's id in this playlist.
        return MusicList(
            tracks = shown.map { (e, t) -> trackItem(v, t, u).copy(entryId = e.playlistItemId) }, total = shown.size, pageSize = shown.size,
            mine = isMine(device, base, jellyfin.tvTokenForClient(base, device), id),
        )
    }

    // ── DEB-2254: playlists the viewer makes and changes. Every write is the viewer's own (FR-1): `tvTokenForClient`,
    //    never `tvToken` (which falls back to the server key and would blind Jellyfin's owner check), on a playlist
    //    Jellyfin says they own, and `forget(device)` after, so the next read is fresh. ──

    private suspend fun <T> asViewer(device: DeviceData, block: suspend (base: String, token: String) -> PlaylistWrite<T>): PlaylistWrite<T> {
        if (noMusicForTags(device)) return PlaylistWrite.Refused(PlaylistRefusal.NOT_FOUND)
        val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return PlaylistWrite.Refused(PlaylistRefusal.FAILED)
        val token = jellyfin.tvTokenForClient(base, device) ?: return PlaylistWrite.Refused(PlaylistRefusal.UNAUTHORIZED)
        return block(base, token)
    }

    /** FR-1 step 2 — null when the viewer owns [id]; otherwise why not. Always asked fresh: a write never leans on [ownerCache]. */
    private suspend fun notOwner(base: String, token: String, id: String): PlaylistRefusal? = when (jellyfin.playlistOwnerStatus(base, token, id)) {
        in 200..299 -> null
        401 -> PlaylistRefusal.UNAUTHORIZED
        403 -> PlaylistRefusal.NOT_OWNER
        404 -> PlaylistRefusal.NOT_FOUND
        else -> PlaylistRefusal.FAILED
    }

    private fun cleanName(name: String): String? = name.trim().takeIf { it.isNotEmpty() && it.length <= PLAYLIST_NAME_MAX }

    /** FR-5 — [ids] less what [held] already has, less repeats inside [ids] (the first stays). "Has" is by song: a held
     *  entry stands for every copy of its song this viewer may open (R373's fold), so a different copy is not added. */
    private fun dedupe(v: View, ids: List<String>, held: Collection<String>): Pair<List<String>, Int> {
        fun song(id: String) = v.tracks[id]?.let { v.shown(it).id } ?: id
        val seen = held.mapTo(HashSet()) { song(it) }
        val keep = ids.filter { seen.add(song(it)) }
        return keep to ids.size - keep.size
    }

    /** FR-3 */
    suspend fun createPlaylist(device: DeviceData, name: String, itemIds: List<String>): PlaylistWrite<MusicPlaylist> = asViewer(device) { base, token ->
        val clean = cleanName(name) ?: return@asViewer PlaylistWrite.Refused(PlaylistRefusal.BAD_REQUEST)
        val v = View(device)
        if (itemIds.any { it !in v.tracks }) return@asViewer PlaylistWrite.Refused(PlaylistRefusal.BAD_REQUEST)
        val (ids, _) = dedupe(v, itemIds, emptyList())
        val id = jellyfin.createPlaylist(base, token, device.jellyfinUserId, clean, ids) ?: return@asViewer PlaylistWrite.Refused(PlaylistRefusal.FAILED)
        forget(device)
        PlaylistWrite.Done(MusicPlaylist(id, clean, ids.size, coversOf(v, ids.asSequence().map { v.tracks[it]?.albumId }), mine = true))
    }

    /** FR-4 / FR-5 */
    suspend fun addToPlaylist(device: DeviceData, playlistId: String, itemIds: List<String>): PlaylistWrite<MusicPlaylistAdded> = asViewer(device) { base, token ->
        notOwner(base, token, playlistId)?.let { return@asViewer PlaylistWrite.Refused(it) }
        val v = View(device)
        if (itemIds.any { it !in v.tracks }) return@asViewer PlaylistWrite.Refused(PlaylistRefusal.BAD_REQUEST)
        val held = jellyfin.getPlaylistEntries(base, token, device.jellyfinUserId, playlistId, limit = PLAYLIST_ENTRY_LIMIT)
            ?: return@asViewer PlaylistWrite.Refused(PlaylistRefusal.FAILED)
        val (ids, already) = dedupe(v, itemIds, held.map { it.id })
        // Nothing left: no Jellyfin call.
        if (ids.isEmpty()) return@asViewer PlaylistWrite.Done(MusicPlaylistAdded(0, already))
        val ok = jellyfin.addToPlaylist(base, token, device.jellyfinUserId, playlistId, ids)
        forget(device)
        if (!ok) PlaylistWrite.Refused(PlaylistRefusal.FAILED) else PlaylistWrite.Done(MusicPlaylistAdded(ids.size, already))
    }

    /** FR-9 — the playlist as it now stands. */
    suspend fun removeFromPlaylist(device: DeviceData, playlistId: String, entryId: String): PlaylistWrite<MusicList> = asViewer(device) { base, token ->
        notOwner(base, token, playlistId)?.let { return@asViewer PlaylistWrite.Refused(it) }
        val entries = jellyfin.getPlaylistEntries(base, token, device.jellyfinUserId, playlistId, limit = PLAYLIST_ENTRY_LIMIT)
            ?: return@asViewer PlaylistWrite.Refused(PlaylistRefusal.FAILED)
        if (entries.none { it.playlistItemId == entryId }) return@asViewer PlaylistWrite.Refused(PlaylistRefusal.NOT_FOUND)
        val ok = jellyfin.removeFromPlaylist(base, token, playlistId, entryId)
        forget(device)
        if (!ok) return@asViewer PlaylistWrite.Refused(PlaylistRefusal.FAILED)
        playlist(device, playlistId)?.let { PlaylistWrite.Done(it) } ?: PlaylistWrite.Refused(PlaylistRefusal.FAILED)
    }

    /**
     * FR-10 — [to] is the index in the list as the viewer sees it (songs they may not open are not in it), with the
     * moved row where it was dropped. Jellyfin counts every entry, so [to] is translated: the row goes in front of the
     * shown row now at [to] (the list without the moved row), or after the last shown row when [to] is past the end.
     */
    suspend fun movePlaylistEntry(device: DeviceData, playlistId: String, entryId: String, to: Int): PlaylistWrite<MusicList> = asViewer(device) { base, token ->
        notOwner(base, token, playlistId)?.let { return@asViewer PlaylistWrite.Refused(it) }
        if (to < 0) return@asViewer PlaylistWrite.Refused(PlaylistRefusal.BAD_REQUEST)
        val entries = jellyfin.getPlaylistEntries(base, token, device.jellyfinUserId, playlistId, limit = PLAYLIST_ENTRY_LIMIT)
            ?: return@asViewer PlaylistWrite.Refused(PlaylistRefusal.FAILED)
        if (entries.none { it.playlistItemId == entryId }) return@asViewer PlaylistWrite.Refused(PlaylistRefusal.NOT_FOUND)
        val v = View(device)
        val rest = entries.filter { it.playlistItemId != entryId }
        val shown = rest.filter { it.id in v.tracks }
        val index = if (to < shown.size) rest.indexOfFirst { it === shown[to] } else shown.lastOrNull()?.let { last -> rest.indexOfFirst { it === last } + 1 } ?: rest.size
        val ok = jellyfin.movePlaylistEntry(base, token, playlistId, entryId, index)
        forget(device)
        if (!ok) return@asViewer PlaylistWrite.Refused(PlaylistRefusal.FAILED)
        playlist(device, playlistId)?.let { PlaylistWrite.Done(it) } ?: PlaylistWrite.Refused(PlaylistRefusal.FAILED)
    }

    /** FR-11 */
    suspend fun renamePlaylist(device: DeviceData, playlistId: String, name: String): PlaylistWrite<MusicPlaylist> = asViewer(device) { base, token ->
        notOwner(base, token, playlistId)?.let { return@asViewer PlaylistWrite.Refused(it) }
        val clean = cleanName(name) ?: return@asViewer PlaylistWrite.Refused(PlaylistRefusal.BAD_REQUEST)
        val ok = jellyfin.renamePlaylist(base, token, playlistId, clean)
        forget(device)
        if (!ok) return@asViewer PlaylistWrite.Refused(PlaylistRefusal.FAILED)
        val v = View(device)
        val entries = jellyfin.getPlaylistEntries(base, token, device.jellyfinUserId, playlistId).orEmpty()
        val count = jellyfin.getMusicPlaylists(base, token, device.jellyfinUserId)?.firstOrNull { it.id == playlistId }?.childCount ?: entries.size
        PlaylistWrite.Done(MusicPlaylist(playlistId, clean, count, coversOf(v, entries.asSequence().map { e -> e.albumId ?: v.tracks[e.id]?.albumId }), mine = true))
    }

    /** FR-12 */
    suspend fun deletePlaylist(device: DeviceData, playlistId: String): PlaylistWrite<Unit> = asViewer(device) { base, token ->
        notOwner(base, token, playlistId)?.let { return@asViewer PlaylistWrite.Refused(it) }
        val ok = jellyfin.deleteItem(base, token, playlistId)
        forget(device)
        if (ok) PlaylistWrite.Done(Unit) else PlaylistWrite.Refused(PlaylistRefusal.FAILED)
    }

    // ── FR-279-4 ──

    suspend fun album(device: DeviceData, id: String): MusicAlbumDetail? {
        val v = View(device)
        val a = v.albums[id] ?: return null
        val u = userMusic(device)
        val first = a.albumArtists.firstOrNull()?.artistId
        // R373 dev review 7 — *More from* leaves out the singles that live on this album.
        val more = if (first == null) emptyList() else v.ownAlbums(first).filter { it.id != a.id && v.homedUnder(it.id) != a.id }.sortedByDescending { it.originalYear() ?: 0 }.take(10)
        val held = v.tracksByAlbum[a.id].orEmpty()
        // R373 (FR-R373-1) — the official album, its extras, its singles and their B-sides; `tracks` keeps the files'
        // order so an installed app shows today's page.
        val layout = MusicOfficial.layout(a, held, v.fill)
        val edition = if (layout.official != null) MusicOfficial.editionOf(a, layout.extras) else MusicOfficial.Edition()
        val singles = v.editions.singlesUnder(a.id).filter { it.id in v.albums }
        val bsides = v.editions.bsides(a.id).filter { (s, t) -> s.id in v.albums && t.id in v.tracks }
        return MusicAlbumDetail(
            album = albumCard(v, a), tracks = held.map { trackItem(v, it, u) },
            moreFromArtist = more.map { albumCard(v, it) }, favorite = a.id in u.favorites,
            officialIds = layout.official?.let { layout.rows.map { it.second.id } },
            extraIds = if (layout.official != null) layout.extras.map { it.id } else emptyList(),
            editionTitle = edition.title, editionCountry = edition.country,
            singles = singles.map { albumCard(v, it) },
            bsideTracks = bsides.map { (_, t) -> trackItem(v, t, u) },
            singleFrom = v.homedUnder(a.id)?.let { v.albums[it] }?.let { albumCard(v, it) },
        )
    }

    /** R373 (FR-R373-4) — the other copies of a song this viewer may open, each with its release. Null when the song
     *  is not this viewer's to see. */
    suspend fun copies(device: DeviceData, trackId: String): MusicTrackCopies? {
        val v = View(device)
        val t = v.tracks[trackId] ?: return null
        val u = userMusic(device)
        return MusicTrackCopies(v.copies(t).filter { it.id != t.id }.map { c ->
            MusicTrackCopy(trackItem(v, c, u), c.albumId?.let { v.albums[it] }?.let { albumCard(v, it) })
        })
    }

    /**
     * 305 dev review 8 — favourite a song or an album. Un-favouriting a song clears **every** favourited copy of it this
     * viewer may see (the folded row shows a favourite when any copy is one, so leaving one would put it straight back).
     */
    suspend fun setFavorite(device: DeviceData, itemId: String, favorite: Boolean) {
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        val token = jellyfin.tvToken(base, device, cfg.apiKeys.jellyfinToken)
        if (favorite) { jellyfin.markFavorite(base, token, device.jellyfinUserId, itemId); forget(device); return }
        val v = View(device)
        val t = v.tracks[itemId]
        val ids = if (t == null) listOf(itemId) else {
            val u = userMusic(device)
            (listOf(itemId) + v.copies(t).map { it.id }.filter { it in u.favorites }).distinct()
        }
        for (id in ids) jellyfin.unmarkFavorite(base, token, device.jellyfinUserId, id)
        forget(device)
    }

    /** [lang] — the viewer's language for the biography (`en` · `da` · `fo`), else English, else whatever there is. */
    suspend fun artist(device: DeviceData, id: String, lang: String?): MusicArtistDetail? {
        val v = View(device)
        val r = v.artists[id] ?: return null
        val u = userMusic(device)
        // R373 (FR-R373-7, 305 dev review 2e) — a single or EP living under an album leaves whichever group held it.
        val own = v.ownAlbums(r.id).filter { v.homedUnder(it.id) == null }
        val under = v.ownAlbums(r.id).mapNotNull { v.homedUnder(it.id) }.groupingBy { it }.eachCount()
        val groups = GROUP_ORDER.mapNotNull { type ->
            own.filter { MusicBrowse.albumType(it) == type }.sortedByDescending { it.originalYear() ?: 0 }.takeIf { it.isNotEmpty() }?.let { MusicAlbumGroup(type, it.map { a -> albumCard(v, a) }) }
        } + listOfNotNull(
            // Credited on someone else's album — a group of its own, after the artist's own.
            v.tracks.values.filter { t -> t.artists.any { it.artistId == r.id } }.mapNotNull { it.albumId?.let { a -> v.albums[a] } }
                .filter { a -> a.albumArtists.none { it.artistId == r.id } }.distinctBy { it.id }
                .takeIf { it.isNotEmpty() }?.let { MusicAlbumGroup("appears_on", it.map { a -> albumCard(v, a) }) },
        )
        // Every song of theirs, most played first: the phone shows five and plays them all (R321 FR-R321-8).
        val top = v.fold(v.songsBy(r.id)).sortedWith(compareByDescending<MusicTrack> { u.songPlays(v, it) }.thenBy { it.albumId }.thenBy { it.position ?: 0 }).take(200)
        val vids = MusicVideoLinks.forArtist(r, videos(device)).map { m ->
            val vid = m.jellyfinId ?: m.id
            MusicVideoCard(vid, m.title, m.year, m.runtime?.let { it * 60_000L }, dev.jellystructure.tv.RaviloImageUrl.poster(vid))
        }
        val bio = r.biographyEdited ?: lang?.let { r.biographies[it] } ?: r.biographies["en"] ?: r.biographies.values.firstOrNull()
        return MusicArtistDetail(
            artist = artistCard(v, r), backgroundUrl = backgroundImage(r), type = r.type, span = r.lifeSpan, biography = bio,
            groups = groups, topTracks = top.map { trackItem(v, it, u, folded = true) }, videos = vids,
            singlesUnder = under.entries.mapNotNull { (albumId, n) -> v.albums[albumId]?.let { it to n } }
                .sortedBy { it.first.originalYear() ?: 0 }.map { (a, n) -> MusicSinglesUnder(albumCard(v, a), n) },
        )
    }

    // ── FR-279-5 ──

    private data class SearchMatches(val songs: List<MusicTrack>, val albums: List<MusicAlbum>, val artists: List<MusicArtist>)

    /** The search route's match rule and orders, uncapped — [q] trimmed and lower-cased. R388's *search* scope plays these. */
    private fun searchMatches(v: View, q: String): SearchMatches {
        fun hit(vararg s: String?) = s.any { it != null && q in it.lowercase() }
        val songs = v.fold(v.tracks.values.filter { t -> hit(t.title, *t.artists.map { it.name }.toTypedArray()) }).sortedBy { it.title.lowercase() }
        val albums = v.albums.values.filter { a -> hit(a.title, *a.albumArtists.map { it.name }.toTypedArray()) }.sortedBy { it.title.lowercase() }
        val artists = v.artists.values.filter { r -> hit(r.name, r.sortName, *r.aliases.toTypedArray()) }.sortedBy { it.name.lowercase() }
        return SearchMatches(songs, albums, artists)
    }

    suspend fun search(device: DeviceData, query: String?): MusicSearch {
        val v = View(device)
        val q = query?.trim()?.lowercase().orEmpty()
        val u = userMusic(device)
        if (q.isEmpty()) {
            val recent = v.fold(u.played.mapNotNull { v.tracks[it.id] }).take(10)
            val artists = v.artists.values.filter { v.ownAlbums(it.id).isNotEmpty() }.sortedBy { (it.sortName ?: it.name).lowercase() }
            return MusicSearch(songs = recent.map { trackItem(v, it, u, folded = true) }, artists = artists.take(20).map { artistCard(v, it) }, songsTotal = recent.size, artistsTotal = artists.size)
        }
        val (songs, albums, artists) = searchMatches(v, q)
        return MusicSearch(
            songs = songs.take(50).map { trackItem(v, it, u, folded = true) }, albums = albums.take(50).map { albumCard(v, it) }, artists = artists.take(50).map { artistCard(v, it) },
            songsTotal = songs.size, albumsTotal = albums.size, artistsTotal = artists.size,
        )
    }

    // ── FR-279-8 ──

    suspend fun lyrics(device: DeviceData, trackId: String): TrackLyrics? {
        val view = View(device)
        val t = view.tracks[trackId] ?: return null
        // 292 (dev review 8d) — no singing, or the admin removed them: nothing, even while Jellyfin holds a copy.
        if (view.versions.lyricsHidden(t)) return null
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        val dto = if (base.isBlank()) null else jellyfin.getLyrics(base, jellyfin.tvToken(base, device, cfg.apiKeys.jellyfinToken), trackId)
        val lines = dto?.lyrics.orEmpty().filter { it.text.isNotBlank() || it.start != null }
        if (lines.isNotEmpty()) {
            return if (lines.all { it.start != null }) TrackLyrics(synced = lines.map { LyricLine(it.start!! / 10_000, it.text) })
            else TrackLyrics(plain = lines.joinToString("\n") { it.text })
        }
        // Jellyfin has not read our sidecar yet (the album refresh is on its way): read it ourselves.
        val file = lyricsFile(t) ?: return null
        val text = runCatching { dev.jellystructure.io.FileIo.readText(kotlinx.io.files.Path(file)) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return if (file.endsWith(".lrc", true)) parseLrc(text)?.let { TrackLyrics(synced = it) } ?: TrackLyrics(plain = text) else TrackLyrics(plain = text.trim())
    }

    // ── FR-279-11 (dev review 5: which song, not where in it) ──

    suspend fun lastPlayed(device: DeviceData): MusicLastPlayed? {
        val v = View(device)
        val u = userMusic(device, fresh = true)
        val t = u.played.firstNotNullOfOrNull { v.tracks[it.id] } ?: return null
        return MusicLastPlayed(trackItem(v, t, u), t.albumId?.let { v.albums[it] }?.let { albumCard(v, it) })
    }

    /** A song or an album this viewer may see (the play and favourite routes' check). */
    fun visible(device: DeviceData, id: String): Boolean = View(device).let { id in it.tracks || id in it.albums }
}
