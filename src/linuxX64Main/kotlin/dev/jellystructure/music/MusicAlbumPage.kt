package dev.jellystructure.music

import dev.jellystructure.model.MusicAlbum
import dev.jellystructure.model.MusicAlbumPageDto
import dev.jellystructure.model.MusicAlbumRef
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicBsideRow
import dev.jellystructure.model.MusicCopiesDto
import dev.jellystructure.model.MusicCopyRow
import dev.jellystructure.model.MusicFormats
import dev.jellystructure.model.MusicGapRow
import dev.jellystructure.model.MusicOfficialLine
import dev.jellystructure.model.MusicPairSide
import dev.jellystructure.model.MusicSamePairDto
import dev.jellystructure.model.MusicSingleFromDto
import dev.jellystructure.model.MusicSingleRow
import dev.jellystructure.model.MusicSinglesUnderRow
import dev.jellystructure.model.MusicTrack
import dev.jellystructure.model.MusicTrackRow
import dev.jellystructure.model.MusicVersions
import dev.jellystructure.model.effectiveGenres
import dev.jellystructure.model.originalYear

/**
 * Phase 305 (FR-305-12, dev review 9) — the album page's answer, built from one snapshot. The server orders the rows
 * (official in the official order, then the extras) and the page renders what it gets. Lifted out of `MusicRoutes`
 * so a test can read it; the route adds what needs a service (drift, lyrics on disk, flags, Jellyfin's link).
 */
object MusicAlbumPage {
    fun howLabel(how: String): String = when (how) {
        MusicSingleHome.MB -> "MusicBrainz"
        MusicSingleHome.REMIX -> "via remix"
        MusicSingleHome.TITLE -> "by title"
        MusicSingleHome.USER -> "you"
        else -> ""
    }

    fun coverUrl(a: MusicAlbum): String? = if (a.coverState != MusicArt.NONE) "/api/music/image/album/${a.id}?v=${a.updatedAt}" else null

    /** *country · year · label · format · tracks* — the held pressing. */
    fun heldLine(a: MusicAlbum): String? = a.release?.let { r ->
        listOfNotNull(r.country, r.date?.take(4), r.label, r.format, r.trackCount.takeIf { it > 0 }?.let { "$it tracks" }).joinToString(" · ").ifBlank { null }
    }

    fun build(snap: MusicStore.Snapshot, albumId: String, lyricsOf: (MusicTrack) -> String? = { null }): MusicAlbumPageDto? {
        val a = snap.albums[albumId] ?: return null
        val tracks = snap.tracksByAlbum[a.id].orEmpty().filter { it.missingSince == null }
        val layout = MusicOfficial.layout(a, tracks)
        val official = layout.official
        val rows: List<MusicTrackRow>
        var line: MusicOfficialLine? = null
        var gaps = emptyList<MusicGapRow>()
        if (official == null) {
            rows = MusicBrowse.trackRows(tracks, snap.versions, lyricsOf)
        } else {
            val off = layout.rows.map { (i, t) -> MusicBrowse.trackRows(listOf(t), snap.versions, lyricsOf).single().copy(number = MusicOfficial.number(official, i), slot = i) }
            val ext = layout.extras.map { t ->
                MusicBrowse.trackRows(listOf(t), snap.versions, lyricsOf).single().copy(
                    extra = true, firstOn = t.recordingMbid?.let { a.extraOrigins[it] }?.let { MusicOfficial.firstOnText(it) },
                )
            }
            rows = off + ext
            gaps = layout.gaps.map { (i, s) -> MusicGapRow(i, MusicOfficial.number(official, i), s.title, s.disc, s.position, s.lengthMs) }
            val ed = MusicOfficial.editionOf(a, layout.extras)
            val edName = ed.title ?: ed.country?.let { MusicCountries.name(it) }
            val heldLen = layout.rows.associate { it.first to it.second.durationMs }
            line = MusicOfficialLine(
                songs = official.songs.size, extras = layout.extras.size, k = official.k, m = official.m,
                release = official.releaseTitle, releaseMbid = official.releaseMbid, chosenByYou = a.userOfficial != null,
                held = heldLine(a), editionTitle = ed.title, editionCountry = ed.country,
                divider = if (layout.extras.isEmpty()) null else listOfNotNull("Extras", edName, layout.extras.size.toString()).joinToString(" · "),
                lengthMs = official.songs.withIndex().sumOf { (i, s) -> s.lengthMs ?: heldLen[i] ?: 0L },
            )
        }
        val ed = snap.editions
        val bsides = ed.bsides(a.id)
        val singles = ed.singlesUnder(a.id).map { s ->
            val h = ed.homeOf(s)!!
            MusicSingleRow(s.id, s.title, s.originalYear(), coverUrl(s), MusicBrowse.albumType(s), h.how, howLabel(h.how), bsides.count { it.first.id == s.id })
        }
        val isSingle = MusicOfficial.isSingleOrEp(a) || snap.homeOverrides[a.id] != null
        val singleFrom = if (!isSingle) null else ed.homeOf(a).let { h ->
            if (h == null) MusicSingleFromDto(null, null, "none", "")
            else MusicSingleFromDto(h.albumId, h.albumId?.let { snap.albums[it]?.title }, h.how, howLabel(h.how))
        }
        val artistIds = a.albumArtists.map { it.artistId }.toSet()
        val targets = if (!isSingle && singles.isEmpty()) emptyList() else snap.albums.values.filter { o ->
            o.id != a.id && o.missingSince == null && MusicOfficial.isAlbum(o) && o.albumArtists.any { it.artistId in artistIds }
        }.sortedWith(compareBy({ it.originalYear() ?: 0 }, { it.title.lowercase() })).map { MusicAlbumRef(it.id, it.title, it.originalYear()) }
        return MusicAlbumPageDto(
            album = a, tracks = rows, genres = a.effectiveGenres(), coverUrl = coverUrl(a), type = MusicBrowse.albumType(a), year = a.originalYear(),
            versionSummary = MusicVersions.albumSummary(tracks.map { snap.versions.of(it) }),
            official = line, gaps = gaps, singles = singles,
            bsides = bsides.map { (s, t) -> MusicBsideRow(MusicBrowse.trackRows(listOf(t), snap.versions, lyricsOf).single(), s.id, s.title, s.originalYear()) },
            singleFrom = singleFrom, isSingle = isSingle, moveTargets = targets,
        )
    }

    /** FR-305-15 — the singles that live under each of the artist's albums. */
    fun singlesUnder(snap: MusicStore.Snapshot, albumIds: Collection<String>): List<MusicSinglesUnderRow> =
        albumIds.mapNotNull { id -> snap.albums[id]?.let { a -> snap.editions.singlesUnder(id).size.takeIf { it > 0 }?.let { MusicSinglesUnderRow(id, a.title, it) } } }

    /** FR-305-13 — the copies panel: every copy, the shown one marked, each other's reason. */
    fun copies(snap: MusicStore.Snapshot, trackId: String): MusicCopiesDto? {
        val t = snap.tracks[trackId] ?: return null
        val songs = snap.songs
        val all = songs.copies(t)
        val first = all.first()
        return MusicCopiesDto(
            trackId = first.id, title = first.title, artist = first.artists.joinToString(" & ") { it.name }, artistId = first.artists.firstOrNull()?.artistId,
            copies = all.map { c -> copyRow(snap, c) },
        )
    }

    fun copyRow(snap: MusicStore.Snapshot, c: MusicTrack): MusicCopyRow {
        val album = c.albumId?.let { snap.albums[it] }
        val r = snap.songs.reason(c)
        return MusicCopyRow(
            id = c.id, albumId = c.albumId, album = album?.title, kind = MusicCopyRank.kindName(snap.editions.kindOf(c)), year = album?.originalYear(),
            disc = c.disc, position = c.position, format = MusicFormats.label(c.container, c.codec, c.bitrate) + (c.bitDepth?.let { " · $it-bit" } ?: ""),
            lossless = MusicCopyRank.lossless(c.codec, c.container), shown = snap.songs.isShown(c), reason = r, reasonText = MusicSongIndex.reasonText(r),
            extra = snap.editions.isExtra(c), versions = snap.versions.of(c).shown, lengthMs = c.durationMs,
        )
    }

    private fun side(snap: MusicStore.Snapshot, t: MusicTrack): MusicPairSide {
        val album = t.albumId?.let { snap.albums[it] }
        val plays = MusicBrowse.browserPlays(t)
        return MusicPairSide(
            trackId = t.id, title = t.title, album = album?.title, albumId = t.albumId, kind = MusicCopyRank.kindName(snap.editions.kindOf(t)),
            lengthMs = t.durationMs, recording = t.mbTitle, playable = plays,
            why = if (plays) null else "A browser can’t play ${MusicFormats.label(t.container, t.codec, null)} as it is",
        )
    }

    /** FR-305-14 — the Dashboard's *Songs that may be the same*: info, counted in pairs, fixed here (it opens *Listen
     *  and decide*, it does not post). Absent at zero. */
    fun sameSongsRow(snap: MusicStore.Snapshot): dev.jellystructure.model.DashboardRow? {
        val n = snap.songs.suggestions.size.takeIf { it > 0 } ?: return null
        return dev.jellystructure.model.DashboardRow(
            id = "music_same_songs", domain = "music", severity = "info", label = "Songs that may be the same",
            sentence = "Two different recordings that sound alike — listen, and say whether they are one song.",
            count = n, unit = "pair", fix = "here", action = "Listen and decide", opens = "same_songs",
        )
    }

    /** FR-305-14 — the open suggestions, one pair at a time in the modal; the offset makes both start together. */
    fun suggestions(snap: MusicStore.Snapshot): List<MusicSamePairDto> = snap.songs.suggestions.mapNotNull { p ->
        val a = snap.tracks[p.a] ?: return@mapNotNull null
        val b = snap.tracks[p.b] ?: return@mapNotNull null
        MusicSamePairDto(side(snap, a), side(snap, b), p.offsetMs)
    }.sortedWith(compareBy({ it.a.title.lowercase() }, { it.a.trackId }))
}
