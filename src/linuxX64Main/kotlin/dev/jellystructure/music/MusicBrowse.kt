package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicAlbumRow
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtist
import dev.jellystructure.model.MusicArtistRow
import dev.jellystructure.model.MusicFacet
import dev.jellystructure.model.MusicFacetValue
import dev.jellystructure.model.MusicFormats
import dev.jellystructure.model.MusicGenreRow
import dev.jellystructure.model.MusicLyrics
import dev.jellystructure.model.MusicMatch
import dev.jellystructure.model.MusicSongRow
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicTrackRow
import dev.jellystructure.model.effectiveGenres
import dev.jellystructure.model.originalYear
import dev.jellystructure.model.reencodesOnPhone

/**
 * Phase 278 (FR-278-1..3) — the Music kind's three views and their facets, decided here so the admin page renders
 * rows and counts and derives nothing. A facet value's count is the number of rows that pass every *other* active
 * facet and carry that value (the films workbench's rule): picking a value never makes its own count lie.
 *
 * An album carries a value set per facet; a song inherits its album's (format and lyrics are its own); an artist
 * carries the union of its albums' (match and picture are its own). Missing rows are not in the library.
 */
object MusicBrowse {
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val SONGS = "songs"
    val VIEWS = setOf(ALBUMS, ARTISTS, SONGS)

    /** The Library's facet keys, in the bar's order. */
    val FACETS = listOf(
        "match" to "Match", "cover" to "Cover", "artimg" to "Artist image", "format" to "Format", "genre" to "Genre",
        "decade" to "Decade", "type" to "Album type", "lyrics" to "Lyrics", "check" to "Check", "lib" to "Library",
    )
    private val FIXED: Map<String, List<Pair<String, String>>> = mapOf(
        "match" to listOf("matched" to "matched", MusicMatch.NEEDS_YOU to "needs you", MusicMatch.UNMATCHED to "unmatched", "locked" to "locked"),
        "cover" to listOf("has" to "has a cover", "missing" to "missing"),
        "artimg" to listOf("has" to "has a picture", "missing" to "missing"),
        "format" to listOf("MP3" to "MP3", "WMA" to "WMA", "FLAC" to "FLAC", "AAC" to "AAC", "other" to "other"),
        "type" to listOf("album" to "album", "single" to "single / EP", "compilation" to "compilation", "live" to "live", "soundtrack" to "soundtrack"),
        "lyrics" to listOf("has" to "has lyrics", "missing" to "missing"),
        // Phase 283 (FR-283-3) — what the folder and the songs disagree on.
        "check" to listOf(MusicFlags.SHARED to "one album in several folders", MusicFlags.FOLDER to "folder and songs disagree", "none" to "nothing to check"),
    )
    private const val WMA_NOTE = "plays on a phone only by re-encoding"
    /** Containers a desktop browser plays as they are — the Tracks tab's ▶ (direct play or nothing). */
    private val BROWSER_CONTAINERS = setOf("mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "webm")

    /** An album's match as a cell reads it: a locked match is its own state; a cleared-and-locked one is unmatched. */
    fun albumMatch(a: MusicAlbum): String = if (a.matchState == MusicMatch.MATCHED && a.matchLocked) "locked" else a.matchState
    fun artistMatch(r: MusicArtist): String = if (r.matchState == MusicMatch.MATCHED && r.matchLocked) "locked" else r.matchState

    /** The Artist page's groups and the *Album type* facet: a secondary type (live, compilation) wins over *Album*. */
    fun albumType(a: MusicAlbum): String {
        val sec = a.secondaryTypes.map { it.lowercase() }
        return when {
            "compilation" in sec -> "compilation"
            "live" in sec -> "live"
            "soundtrack" in sec -> "soundtrack"
            a.primaryType.equals("single", true) || a.primaryType.equals("ep", true) -> "single"
            else -> "album"
        }
    }

    fun formatName(t: MusicTrack): String = when (val n = MusicFormats.label(t.container, t.codec, null)) {
        "MP3", "WMA", "FLAC", "AAC" -> n
        else -> "other"
    }

    fun hasLyrics(t: MusicTrack): Boolean = t.lyricsState == MusicLyrics.SYNCED || t.lyricsState == MusicLyrics.PLAIN || t.hasLyrics

    fun browserPlays(t: MusicTrack): Boolean = !t.reencodesOnPhone() && (t.container?.lowercase()?.substringBefore(',') in BROWSER_CONTAINERS)

    private fun decade(y: Int?): String? = y?.let { (it / 10 * 10).toString() }

    class Result(val rowsTotal: Int, val facets: List<MusicFacet>, val albums: List<MusicAlbumRow>, val artists: List<MusicArtistRow>, val songs: List<MusicSongRow>)

    /**
     * One view. [selected] maps a facet key to the values ticked (OR within a facet, AND across facets); [query]
     * matches titles and names, case-insensitively; [sort] applies to albums (`added` · `title` · `year` · `artist`).
     */
    fun browse(
        snap: MusicStore.Snapshot,
        view: String,
        selected: Map<String, Set<String>>,
        query: String?,
        libraryNames: Map<String, String>,
        sort: String? = null,
        roots: Set<String> = emptySet(),
        triage: String? = null,
    ): Result {
        val ctx = Ctx(snap, roots)
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        fun hit(vararg s: String?) = q == null || s.any { it != null && q in it.lowercase() }
        // Phase 293 (FR-293-2) — a Dashboard row's key narrows the rows before the facets, so the facets narrow within
        // it and their counts stay honest. A key for another view (or an unknown key) narrows nothing.
        val tk = triage?.takeIf { MusicTriage.viewOf(it) == view }
        val tp = tk?.let { MusicTriage.Music(snap, roots) }
        return when (view) {
            ARTISTS -> {
                val all = ctx.artists.filter { hit(it.name, it.sortName) && (tp == null || tp.artist(tk!!, it)) }
                val (shown, counts, universe) = apply(all, ctx::artistValues, selected)
                Result(shown.size, facets(counts, universe, selected, libraryNames), emptyList(),
                    shown.sortedBy { (it.sortName ?: it.name).lowercase() }.map { ctx.artistRow(it) }, emptyList())
            }
            SONGS -> {
                val all = ctx.tracks.filter { t -> hit(t.title, t.artists.joinToString(" ") { it.name }, t.albumId?.let { snap.albums[it]?.title }) && (tp == null || tp.song(tk!!, t)) }
                val (shown, counts, universe) = apply(all, ctx::songValues, selected)
                val sorted = shown.sortedWith(compareBy({ it.albumId?.let { a -> snap.albums[a]?.title?.lowercase() } ?: "" }, { it.disc ?: 1 }, { it.position ?: Int.MAX_VALUE }, { it.title.lowercase() }))
                Result(shown.size, facets(counts, universe, selected, libraryNames), emptyList(), emptyList(), sorted.map { ctx.songRow(it) })
            }
            else -> {
                val all = ctx.albums.filter { a -> hit(a.title, a.albumArtists.joinToString(" ") { it.name }) && (tp == null || tp.album(tk!!, a)) }
                val (shown, counts, universe) = apply(all, ctx::albumValues, selected)
                val sorted = when (sort) {
                    "title" -> shown.sortedBy { (it.sortName ?: it.title).lowercase() }
                    "year" -> shown.sortedWith(compareByDescending<MusicAlbum> { it.originalYear() ?: 0 }.thenBy { it.title.lowercase() })
                    "artist" -> shown.sortedWith(compareBy({ it.albumArtists.firstOrNull()?.name?.lowercase() ?: "" }, { it.originalYear() ?: 0 }))
                    else -> shown.sortedWith(compareByDescending<MusicAlbum> { it.addedAt ?: it.createdAt }.thenBy { it.title.lowercase() })
                }
                Result(shown.size, facets(counts, universe, selected, libraryNames), sorted.map { ctx.albumRow(it) }, emptyList(), emptyList())
            }
        }
    }

    /** Metadata → Music genres (FR-278-13): every genre an album shows, with its albums and their songs. */
    fun genres(snap: MusicStore.Snapshot): List<MusicGenreRow> {
        val ctx = Ctx(snap)
        val albums = HashMap<String, Int>(); val songs = HashMap<String, Int>()
        for (a in ctx.albums) {
            val n = ctx.liveTracks(a).size
            for (g in a.effectiveGenres()) { albums[g] = (albums[g] ?: 0) + 1; songs[g] = (songs[g] ?: 0) + n }
        }
        return albums.map { (g, n) -> MusicGenreRow(g, n, songs[g] ?: 0) }.sortedWith(compareByDescending<MusicGenreRow> { it.albums }.thenBy { it.name.lowercase() })
    }

    /** The Tracks tab's rows; [lyricsOf] answers from the files beside each track. */
    fun trackRows(tracks: List<MusicTrack>, lyricsOf: (MusicTrack) -> String?): List<MusicTrackRow> = tracks.map { t ->
        MusicTrackRow(
            id = t.id, disc = t.disc, position = t.position, title = t.title, artists = t.artists, lengthMs = t.durationMs,
            format = MusicFormats.label(t.container, t.codec, t.bitrate), sampleRate = t.sampleRate, reencodes = t.reencodesOnPhone(),
            browser = browserPlays(t), recording = t.recordingState, mbTitle = t.mbTitle, mbLengthMs = t.mbLengthMs,
            lyrics = lyricsOf(t), gainDb = t.trackGainDb,
        )
    }

    // ── internals ──

    private class Ctx(val snap: MusicStore.Snapshot, val roots: Set<String> = emptySet()) {
        val albums = snap.albums.values.filter { it.missingSince == null }
        /** Phase 283 — every album's flags, once per request. */
        val flags: Map<String, List<dev.jellystructure.model.MusicFlagDto>> by lazy { MusicFlags.of(albums, roots) }
        val tracks = snap.tracks.values.filter { it.missingSince == null }
        val artists = snap.artists.values.filter { it.missingSince == null }
        private val albumCache = HashMap<String, Map<String, Set<String>>>()

        fun liveTracks(a: MusicAlbum) = snap.tracksByAlbum[a.id].orEmpty().filter { it.missingSince == null }

        fun albumValues(a: MusicAlbum): Map<String, Set<String>> = albumCache.getOrPut(a.id) {
            val ts = liveTracks(a)
            val artist = a.albumArtists.firstOrNull()?.let { snap.artists[it.artistId] }
            mapOf(
                "match" to setOf(albumMatch(a)),
                "cover" to setOf(if (a.coverState != MusicArt.NONE) "has" else "missing"),
                "artimg" to setOf(if (artist != null && artist.imageState != MusicArt.NONE) "has" else "missing"),
                "format" to ts.map { formatName(it) }.toSet(),
                "genre" to a.effectiveGenres().toSet(),
                "decade" to setOfNotNull(decade(a.originalYear())),
                "type" to setOf(albumType(a)),
                "lyrics" to ts.map { if (hasLyrics(it)) "has" else "missing" }.toSet(),
                "check" to flags[a.id].orEmpty().map { it.kind }.toSet().ifEmpty { setOf("none") },
                "lib" to setOfNotNull(a.libraryId),
            )
        }

        fun songValues(t: MusicTrack): Map<String, Set<String>> {
            val own = mapOf("format" to setOf(formatName(t)), "lyrics" to setOf(if (hasLyrics(t)) "has" else "missing"))
            val album = t.albumId?.let { snap.albums[it] } ?: return own + ("lib" to setOfNotNull(t.libraryId))
            return albumValues(album) + own
        }

        fun artistValues(r: MusicArtist): Map<String, Set<String>> {
            val out = HashMap<String, MutableSet<String>>()
            for (a in snap.albumsByArtist[r.id].orEmpty().filter { it.missingSince == null })
                for ((k, v) in albumValues(a)) out.getOrPut(k) { HashSet() }.addAll(v)
            out["match"] = mutableSetOf(artistMatch(r))
            out["artimg"] = mutableSetOf(if (r.imageState != MusicArt.NONE) "has" else "missing")
            return out
        }

        fun albumRow(a: MusicAlbum) = MusicAlbumRow(
            id = a.id, title = a.title, artist = a.albumArtists.joinToString(" & ") { it.name }, artistId = a.albumArtists.firstOrNull()?.artistId,
            year = a.originalYear(), songs = liveTracks(a).size, match = albumMatch(a), cover = a.coverState != MusicArt.NONE,
            v = a.updatedAt, type = albumType(a),
            folder = MusicFlags.folders(a.path, roots)?.album, flags = flags[a.id].orEmpty().map { it.kind },
            note = a.matchNote.takeIf { a.matchState != MusicMatch.MATCHED },
        )

        fun artistRow(r: MusicArtist): MusicArtistRow {
            val own = albums.count { a -> a.albumArtists.any { it.artistId == r.id } }
            return MusicArtistRow(
                id = r.id, name = r.name, albums = own, songs = songsBy(r.id), match = artistMatch(r),
                picture = r.imageState != MusicArt.NONE, folder = r.path != null, v = r.updatedAt,
            )
        }

        /** Songs an artist is credited on, or that sit on an album they are the album artist of. */
        fun songsBy(artistId: String): Int = tracks.count { t ->
            t.artists.any { it.artistId == artistId } || (t.albumId?.let { snap.albums[it] }?.albumArtists?.any { it.artistId == artistId } == true)
        }

        fun songRow(t: MusicTrack): MusicSongRow {
            val album = t.albumId?.let { snap.albums[it] }
            return MusicSongRow(
                id = t.id, albumId = t.albumId, album = album?.title, disc = t.disc, position = t.position, title = t.title,
                artists = t.artists, lengthMs = t.durationMs, format = MusicFormats.label(t.container, t.codec, t.bitrate),
                // `jellyfin` — Jellyfin found lyrics we did not fetch (embedded in the file, or a sidecar of its own).
                reencodes = t.reencodesOnPhone(), lyrics = t.lyricsState?.takeIf { it == MusicLyrics.SYNCED || it == MusicLyrics.PLAIN }
                    ?: if (t.hasLyrics) "jellyfin" else null,
                recording = t.recordingState, albumMatched = album?.matchState == MusicMatch.MATCHED,
            )
        }
    }

    private data class Applied<T>(val shown: List<T>, val counts: Map<String, Map<String, Int>>, val universe: Map<String, Set<String>>)

    private fun <T> apply(items: List<T>, values: (T) -> Map<String, Set<String>>, selected: Map<String, Set<String>>): Applied<T> {
        val vs = items.map { it to values(it) }
        val active = selected.filterValues { it.isNotEmpty() }
        fun passes(v: Map<String, Set<String>>, skip: String?) = active.all { (k, want) -> k == skip || v[k].orEmpty().any { it in want } }
        val shown = vs.filter { passes(it.second, null) }.map { it.first }
        val counts = HashMap<String, Map<String, Int>>()
        val universe = HashMap<String, MutableSet<String>>()
        for ((key, _) in FACETS) {
            val m = HashMap<String, Int>()
            for ((_, v) in vs) {
                val own = v[key].orEmpty()
                universe.getOrPut(key) { HashSet() }.addAll(own)
                if (passes(v, key)) own.forEach { m[it] = (m[it] ?: 0) + 1 }
            }
            counts[key] = m
        }
        return Applied(shown, counts, universe)
    }

    private fun facets(counts: Map<String, Map<String, Int>>, universe: Map<String, Set<String>>, selected: Map<String, Set<String>>, libraryNames: Map<String, String>): List<MusicFacet> =
        FACETS.map { (key, label) ->
            val c = counts[key].orEmpty()
            val on = selected[key].orEmpty()
            val values = FIXED[key]?.map { (v, l) -> MusicFacetValue(v, l, c[v] ?: 0, v in on, WMA_NOTE.takeIf { v == "WMA" }) }
                ?: (universe[key].orEmpty() + on).let { all ->
                    when (key) {
                        "decade" -> all.sortedBy { it }.map { MusicFacetValue(it, "${it}s", c[it] ?: 0, it in on) }
                        "lib" -> all.sortedBy { libraryNames[it] ?: it }.map { MusicFacetValue(it, libraryNames[it] ?: it, c[it] ?: 0, it in on) }
                        else -> all.sortedWith(compareByDescending<String> { c[it] ?: 0 }.thenBy { it.lowercase() }).map { MusicFacetValue(it, it, c[it] ?: 0, it in on) }
                    }
                }
            MusicFacet(key, label, values)
        }
}
