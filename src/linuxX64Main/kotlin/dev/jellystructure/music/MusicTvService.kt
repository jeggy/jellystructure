package dev.jellystructure.music

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinAudioUserItem
import dev.jellystructure.auth.JellyfinClient
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
import dev.jellystructure.shared.tv.MusicRow
import dev.jellystructure.shared.tv.MusicSearch
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.MusicVersionType
import dev.jellystructure.shared.tv.MusicVideoCard
import dev.jellystructure.shared.tv.TrackLyrics
import dev.jellystructure.tv.tvToken
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val videos: suspend (DeviceData) -> List<dev.jellystructure.model.MediaItem> = { emptyList() },
) {
    companion object {
        const val PAGE_SIZE = 60
        /** FR-279-2 / open question 1 — below this a mix is the whole library over again (measured: 60 tracks). */
        private const val USER_TTL_MS = 30_000L
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
        /** Phase 292 — every song's version, over the whole library (one answer per recording, whoever looks). */
        val versions: MusicVersionIndex get() = snap.versions
        val versionTypes get() = snap.versionTypes
        val albums: Map<String, MusicAlbum> = snap.albums.filterValues { it.missingSince == null && musicVisible(it.libraryId, allowed) }
        val tracks: Map<String, MusicTrack> = snap.tracks.filterValues { it.missingSince == null && musicVisible(it.libraryId, allowed) }
        val artists: Map<String, MusicArtist> = snap.artists.filterValues { it.missingSince == null && musicVisible(it.libraryId, allowed) }
        val tracksByAlbum: Map<String?, List<MusicTrack>> = tracks.values.groupBy { it.albumId }
            .mapValues { (_, l) -> l.sortedWith(compareBy({ it.disc ?: 1 }, { it.position ?: Int.MAX_VALUE }, { it.title.lowercase() })) }
        fun ownAlbums(artistId: String) = albums.values.filter { a -> a.albumArtists.any { it.artistId == artistId } }
        fun songsBy(artistId: String) = tracks.values.filter { t -> t.artists.any { it.artistId == artistId } || t.albumId?.let { albums[it] }?.albumArtists?.any { it.artistId == artistId } == true }
    }

    // ── the viewer's own numbers (Jellyfin) ──

    class UserMusic(val played: List<JellyfinAudioUserItem>, val favorites: Set<String>) {
        val playCount: Map<String, Int> = played.associate { it.id to (it.userData?.playCount ?: 0) }
    }
    private val userLock = Mutex()
    private val userCache = HashMap<String, Pair<Long, UserMusic>>()

    suspend fun userMusic(device: DeviceData, fresh: Boolean = false): UserMusic {
        val now = dev.jellystructure.nowEpochSec() * 1000
        if (!fresh) userLock.withLock { userCache[device.jellyfinUserId]?.let { (at, u) -> if (now - at < USER_TTL_MS) return u } }
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return UserMusic(emptyList(), emptySet())
        val token = jellyfin.tvToken(base, device, cfg.apiKeys.jellyfinToken)
        val played = jellyfin.getAudioUserItems(base, token, device.jellyfinUserId, "IsPlayed")
        val favs = jellyfin.getAudioUserItems(base, token, device.jellyfinUserId, "IsFavorite", types = "Audio,MusicAlbum")
        val u = UserMusic(played.orEmpty(), favs.orEmpty().map { it.id }.toSet())
        // A failed read is not cached: the next request asks again.
        if (played != null && favs != null) userLock.withLock { userCache[device.jellyfinUserId] = now to u }
        return u
    }

    suspend fun forget(device: DeviceData) = userLock.withLock { userCache.remove(device.jellyfinUserId) }

    // ── builders ──

    fun albumCard(v: View, a: MusicAlbum) = MusicAlbumCard(
        id = a.id, title = a.title, artists = a.albumArtists.map { MusicArtistRef(it.artistId, it.name) }, year = a.originalYear(),
        imageUrl = albumImage(a), type = MusicBrowse.albumType(a), trackCount = v.tracksByAlbum[a.id]?.size ?: 0,
    )

    fun artistCard(v: View, r: MusicArtist) = MusicArtistCard(
        id = r.id, name = r.name, imageUrl = artistImage(r), albumCount = v.ownAlbums(r.id).size, trackCount = v.songsBy(r.id).size,
    )

    fun trackItem(v: View, t: MusicTrack, u: UserMusic?): MusicTrackItem {
        val album = t.albumId?.let { v.albums[it] }
        return MusicTrackItem(
            id = t.id, title = t.title, albumId = t.albumId, album = album?.title,
            artists = t.artists.map { MusicArtistRef(it.artistId, it.name) }, disc = t.disc, position = t.position,
            // 292 (dev review 8d) — no lyrics button for a song with no singing, or whose lyrics the admin removed.
            durationMs = t.durationMs, hasLyrics = !v.versions.lyricsHidden(t) && (t.hasLyrics || t.lyricsState == MusicLyrics.SYNCED || t.lyricsState == MusicLyrics.PLAIN),
            imageUrl = album?.let { albumImage(it) }, trackGainDb = t.trackGainDb, albumGainDb = t.albumGainDb ?: album?.albumGainDb,
            favorite = u?.favorites?.contains(t.id) == true,
            // R344 (FR-R344-1) — 292's shown set: a Session whose Live the owner removed goes as `session` alone.
            versions = v.versions.of(t).shown,
        )
    }

    private fun recentAlbums(v: View) = v.albums.values.sortedWith(compareByDescending<MusicAlbum> { it.addedAt ?: it.createdAt }.thenBy { it.title.lowercase() })

    // ── FR-279-2 ──

    suspend fun home(device: DeviceData): MusicHome {
        val v = View(device)
        if (v.albums.isEmpty() && v.tracks.isEmpty()) return MusicHome()
        val u = userMusic(device)
        val rows = mutableListOf<MusicRow>()
        recentAlbums(v).take(12).takeIf { it.isNotEmpty() }?.let { rows += MusicRow("recent", "Recently added", albums = it.map { a -> albumCard(v, a) }) }
        u.played.mapNotNull { v.tracks[it.id] }.take(5).takeIf { it.isNotEmpty() }?.let { rows += MusicRow("played", "Recently played", tracks = it.map { t -> trackItem(v, t, u) }) }
        v.artists.values.filter { v.ownAlbums(it.id).isNotEmpty() }
            .sortedWith(compareBy<MusicArtist> { if (it.imageState != MusicArt.NONE) 0 else 1 }.thenBy { (it.sortName ?: it.name).lowercase() })
            .take(12).takeIf { it.isNotEmpty() }?.let { rows += MusicRow("artists", "Artists", artists = it.map { r -> artistCard(v, r) }) }
        // R326 (FR-R326-6) — no *Mix* row in round 1 (the owner, 2026-09-28); 279 amended in place.
        val byGenre = HashMap<String, MutableList<MusicAlbum>>()
        for (a in v.albums.values) for (g in a.effectiveGenres()) byGenre.getOrPut(g) { mutableListOf() } += a
        byGenre.filterValues { it.size >= 3 }.entries.sortedWith(compareByDescending<Map.Entry<String, MutableList<MusicAlbum>>> { it.value.size }.thenBy { it.key })
            .take(6).forEach { (g, list) -> rows += MusicRow("genre", g, albums = list.sortedByDescending { it.addedAt ?: it.createdAt }.take(12).map { albumCard(v, it) }) }
        // R344 (FR-R344-1, dev review 2) — the household's version colours ride the app's first music fetch.
        return MusicHome(rows, versionTypes = v.versionTypes.map { MusicVersionType(it.key, it.color) })
    }

    // ── FR-279-3 ──

    suspend fun browse(device: DeviceData, what: String, sort: String?, page: Int, genre: String?): MusicList {
        val v = View(device)
        val u = if (sort == "played" || what == "tracks") userMusic(device) else null
        fun <T> slice(all: List<T>) = all.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        return when (what) {
            "artists" -> {
                val all = v.artists.values.filter { v.ownAlbums(it.id).isNotEmpty() || v.songsBy(it.id).isNotEmpty() }
                val sorted = when (sort) {
                    "title" -> all.sortedBy { (it.sortName ?: it.name).lowercase() }
                    "played" -> all.sortedByDescending { r -> v.songsBy(r.id).sumOf { u?.playCount?.get(it.id) ?: 0 } }
                    else -> all.sortedByDescending { r -> v.ownAlbums(r.id).maxOfOrNull { it.addedAt ?: it.createdAt } ?: 0L }
                }
                MusicList(artists = slice(sorted).map { artistCard(v, it) }, total = sorted.size, page = page, pageSize = PAGE_SIZE)
            }
            "tracks" -> {
                val all = v.tracks.values.toList()
                val sorted = when (sort) {
                    "title" -> all.sortedBy { it.title.lowercase() }
                    "year" -> all.sortedWith(compareByDescending<MusicTrack> { it.albumId?.let { a -> v.albums[a]?.originalYear() } ?: it.year ?: 0 }.thenBy { it.title.lowercase() })
                    "played" -> all.sortedWith(compareByDescending<MusicTrack> { u?.playCount?.get(it.id) ?: 0 }.thenBy { it.title.lowercase() })
                    else -> all.sortedWith(compareByDescending<MusicTrack> { it.addedAt ?: it.createdAt }.thenBy { it.albumId }.thenBy { it.position ?: 0 })
                }
                MusicList(tracks = slice(sorted).map { trackItem(v, it, u) }, total = sorted.size, page = page, pageSize = PAGE_SIZE)
            }
            else -> {
                val all = v.albums.values.filter { genre == null || genre in it.effectiveGenres() }
                val sorted = when (sort) {
                    "title" -> all.sortedBy { (it.sortName ?: it.title).lowercase() }
                    "year" -> all.sortedWith(compareByDescending<MusicAlbum> { it.originalYear() ?: 0 }.thenBy { it.title.lowercase() })
                    "played" -> all.sortedByDescending { a -> v.tracksByAlbum[a.id].orEmpty().sumOf { u?.playCount?.get(it.id) ?: 0 } }
                    else -> recentAlbums(v).filter { a -> all.any { it.id == a.id } }
                }
                MusicList(albums = slice(sorted).map { albumCard(v, it) }, total = sorted.size, page = page, pageSize = PAGE_SIZE)
            }
        }
    }

    fun genres(device: DeviceData): List<MusicGenreCount> {
        val v = View(device)
        val counts = HashMap<String, Int>()
        for (a in v.albums.values) for (g in a.effectiveGenres()) counts[g] = (counts[g] ?: 0) + 1
        return counts.map { MusicGenreCount(it.key, it.value) }.sortedWith(compareByDescending<MusicGenreCount> { it.albumCount }.thenBy { it.name.lowercase() })
    }

    suspend fun playlists(device: DeviceData): List<MusicPlaylist> {
        val v = View(device)
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return emptyList()
        val token = jellyfin.tvToken(base, device, cfg.apiKeys.jellyfinToken)
        return jellyfin.getMusicPlaylists(base, token, device.jellyfinUserId).orEmpty().map { p ->
            val covers = jellyfin.getPlaylistEntries(base, token, device.jellyfinUserId, p.id).orEmpty()
                .mapNotNull { e -> (e.albumId ?: v.tracks[e.id]?.albumId)?.let { v.albums[it] }?.let { albumImage(it) } }.distinct().take(4)
            MusicPlaylist(p.id, p.name, p.childCount ?: 0, covers)
        }
    }

    /** R321 (FR-R321-10) — one of the viewer's playlists, in its own order; songs the viewer may not see are left out. */
    suspend fun playlist(device: DeviceData, id: String): MusicList? {
        val v = View(device)
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        if (base.isBlank()) return null
        val entries = jellyfin.getPlaylistEntries(base, jellyfin.tvToken(base, device, cfg.apiKeys.jellyfinToken), device.jellyfinUserId, id, limit = 1000) ?: return null
        val u = userMusic(device)
        val tracks = entries.mapNotNull { v.tracks[it.id] }
        return MusicList(tracks = tracks.map { trackItem(v, it, u) }, total = tracks.size, pageSize = tracks.size)
    }

    // ── FR-279-4 ──

    suspend fun album(device: DeviceData, id: String): MusicAlbumDetail? {
        val v = View(device)
        val a = v.albums[id] ?: return null
        val u = userMusic(device)
        val first = a.albumArtists.firstOrNull()?.artistId
        val more = if (first == null) emptyList() else v.ownAlbums(first).filter { it.id != a.id }.sortedByDescending { it.originalYear() ?: 0 }.take(10)
        return MusicAlbumDetail(
            album = albumCard(v, a), tracks = v.tracksByAlbum[a.id].orEmpty().map { trackItem(v, it, u) },
            moreFromArtist = more.map { albumCard(v, it) }, favorite = a.id in u.favorites,
        )
    }

    /** [lang] — the viewer's language for the biography (`en` · `da` · `fo`), else English, else whatever there is. */
    suspend fun artist(device: DeviceData, id: String, lang: String?): MusicArtistDetail? {
        val v = View(device)
        val r = v.artists[id] ?: return null
        val u = userMusic(device)
        val own = v.ownAlbums(r.id)
        val groups = GROUP_ORDER.mapNotNull { type ->
            own.filter { MusicBrowse.albumType(it) == type }.sortedByDescending { it.originalYear() ?: 0 }.takeIf { it.isNotEmpty() }?.let { MusicAlbumGroup(type, it.map { a -> albumCard(v, a) }) }
        } + listOfNotNull(
            // Credited on someone else's album — a group of its own, after the artist's own.
            v.tracks.values.filter { t -> t.artists.any { it.artistId == r.id } }.mapNotNull { it.albumId?.let { a -> v.albums[a] } }
                .filter { a -> a.albumArtists.none { it.artistId == r.id } }.distinctBy { it.id }
                .takeIf { it.isNotEmpty() }?.let { MusicAlbumGroup("appears_on", it.map { a -> albumCard(v, a) }) },
        )
        // Every song of theirs, most played first: the phone shows five and plays them all (R321 FR-R321-8).
        val top = v.songsBy(r.id).sortedWith(compareByDescending<MusicTrack> { u.playCount[it.id] ?: 0 }.thenBy { it.albumId }.thenBy { it.position ?: 0 }).take(200)
        val vids = MusicVideoLinks.forArtist(r, videos(device)).map { m ->
            val vid = m.jellyfinId ?: m.id
            MusicVideoCard(vid, m.title, m.year, m.runtime?.let { it * 60_000L }, dev.jellystructure.tv.RaviloImageUrl.poster(vid))
        }
        val bio = r.biographyEdited ?: lang?.let { r.biographies[it] } ?: r.biographies["en"] ?: r.biographies.values.firstOrNull()
        return MusicArtistDetail(
            artist = artistCard(v, r), backgroundUrl = backgroundImage(r), type = r.type, span = r.lifeSpan, biography = bio,
            groups = groups, topTracks = top.map { trackItem(v, it, u) }, videos = vids,
        )
    }

    // ── FR-279-5 ──

    suspend fun search(device: DeviceData, query: String?): MusicSearch {
        val v = View(device)
        val q = query?.trim()?.lowercase().orEmpty()
        val u = userMusic(device)
        if (q.isEmpty()) {
            val recent = u.played.mapNotNull { v.tracks[it.id] }.take(10)
            val artists = v.artists.values.filter { v.ownAlbums(it.id).isNotEmpty() }.sortedBy { (it.sortName ?: it.name).lowercase() }
            return MusicSearch(songs = recent.map { trackItem(v, it, u) }, artists = artists.take(20).map { artistCard(v, it) }, songsTotal = recent.size, artistsTotal = artists.size)
        }
        fun hit(vararg s: String?) = s.any { it != null && q in it.lowercase() }
        val songs = v.tracks.values.filter { t -> hit(t.title, *t.artists.map { it.name }.toTypedArray()) }.sortedBy { it.title.lowercase() }
        val albums = v.albums.values.filter { a -> hit(a.title, *a.albumArtists.map { it.name }.toTypedArray()) }.sortedBy { it.title.lowercase() }
        val artists = v.artists.values.filter { r -> hit(r.name, r.sortName, *r.aliases.toTypedArray()) }.sortedBy { it.name.lowercase() }
        return MusicSearch(
            songs = songs.take(50).map { trackItem(v, it, u) }, albums = albums.take(50).map { albumCard(v, it) }, artists = artists.take(50).map { artistCard(v, it) },
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
