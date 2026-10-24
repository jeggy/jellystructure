package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.shared.tv.MusicAlbumDetail
import dev.jellystructure.shared.tv.MusicTrackItem

/*
 * R373 (FR-R373-2, dev review 3) — what the album page plays. The order is the wire's contract (`official_ids` →
 * `extra_ids` → `bside_tracks`); this file only resolves the ids against the items the payload carries. An unmatched
 * album (no `official_ids`) plays its tracks in the files' order.
 */

/** An album the server matched to an official tracklist (else it is today's page). */
fun hasEditions(d: MusicAlbumDetail): Boolean = d.officialIds != null

private fun resolve(d: MusicAlbumDetail, ids: List<String>): List<MusicTrackItem> {
    val byId = d.tracks.associateBy { it.id }
    return ids.mapNotNull { byId[it] }   // an id missing from the payload is skipped
}

fun officialTracks(d: MusicAlbumDetail): List<MusicTrackItem> = d.officialIds?.let { resolve(d, it) } ?: d.tracks
fun extraTracks(d: MusicAlbumDetail): List<MusicTrackItem> = if (hasEditions(d)) resolve(d, d.extraIds) else emptyList()

/**
 * FR-R373-2 point 3 (amended 2026-10-23) — *Play album* is the official order; with [withExtras] it is official → extras →
 * B-sides. An unmatched album has one order, whatever is asked.
 */
fun albumQueue(d: MusicAlbumDetail, withExtras: Boolean): List<MusicTrackItem> {
    if (!hasEditions(d)) return d.tracks
    val official = officialTracks(d)
    return if (!withExtras) official else official + extraTracks(d) + d.bsideTracks
}

/** A row of the split button's ▾ menu. Nothing is remembered: what a row does never depends on the last one picked. */
enum class AlbumPlayRow { PLAY, SHUFFLE, PLAY_EXTRAS, SHUFFLE_EXTRAS }

/** The ▾ menu's rows, top to bottom. The extras group, after a separator, exists only on an album with editions. */
fun albumPlayRows(d: MusicAlbumDetail): List<AlbumPlayRow> =
    if (hasEditions(d)) listOf(AlbumPlayRow.PLAY, AlbumPlayRow.SHUFFLE, AlbumPlayRow.PLAY_EXTRAS, AlbumPlayRow.SHUFFLE_EXTRAS)
    else listOf(AlbumPlayRow.PLAY, AlbumPlayRow.SHUFFLE)

/** What a menu row (or the button's main part, which is [AlbumPlayRow.PLAY]) queues, and whether it shuffles it. */
fun albumPlayFor(d: MusicAlbumDetail, row: AlbumPlayRow): Pair<List<MusicTrackItem>, Boolean> = when (row) {
    AlbumPlayRow.PLAY -> albumQueue(d, withExtras = false) to false
    AlbumPlayRow.SHUFFLE -> albumQueue(d, withExtras = false) to true
    AlbumPlayRow.PLAY_EXTRAS -> albumQueue(d, withExtras = true) to false
    AlbumPlayRow.SHUFFLE_EXTRAS -> albumQueue(d, withExtras = true) to true
}

/**
 * A tap on a row plays from it inside the official album; a row outside it (an extra or a B-side) plays the extended
 * order from that row (dev review 3, with the pick always *Play album*). Returns the queue and the index to start at.
 */
fun queueForTap(d: MusicAlbumDetail, trackId: String): Pair<List<MusicTrackItem>, Int> {
    val own = albumQueue(d, withExtras = false)
    own.indexOfFirst { it.id == trackId }.takeIf { it >= 0 }?.let { return own to it }
    val extended = albumQueue(d, withExtras = true)
    return extended to extended.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
}

/** DEB-2254 (FR-6) — what the album's ⋯ adds to a playlist: the official album's songs, in the order *Play album* plays them. */
fun albumPlaylistIds(d: MusicAlbumDetail): List<String> = officialTracks(d).map { it.id }

/** The header's length: the official album's (as today's header sums `tracks`). */
fun headerLengthMs(d: MusicAlbumDetail): Long = officialTracks(d).sumOf { it.durationMs ?: 0L }

/**
 * 305 owner decision 3 (dev review 8) — the extras section's name: MusicBrainz's own title or disambiguation as it is
 * (*20th Anniversary*, *super deluxe*); else the country in the viewer's language (*Extras · Japan*); else *Extras*.
 */
fun editionLabel(title: String?, country: String?, lang: String): String {
    title?.trim()?.takeIf { it.isNotEmpty() }?.let { return t("ed.edition", lang, mapOf("edition" to it)) }
    val code = country?.trim()?.uppercase()?.takeIf { it.length == 2 }
    if (code != null) {
        val key = "country.$code"
        val name = t(key, lang)
        if (name != key && name.isNotBlank()) return t("ed.edition", lang, mapOf("edition" to name))
    }
    return t("ed.extras", lang)
}
