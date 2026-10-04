package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.shared.tv.MusicAlbumDetail
import dev.jellystructure.shared.tv.MusicTrackItem

/*
 * R373 (FR-R373-2, dev review 3) — what the album page plays. The order is the wire's contract (`official_ids` →
 * `extra_ids` → `bside_tracks`); this file only resolves the ids against the items the payload carries. An unmatched
 * album (no `official_ids`) plays its tracks in the files' order, whatever pick was stored.
 */

/** The ▾ pick: the official album, or the album with its extras and B-sides. */
enum class AlbumPick { ALBUM, EXTRAS }

/** An album the server matched to an official tracklist (else it is today's page). */
fun hasEditions(d: MusicAlbumDetail): Boolean = d.officialIds != null

private fun resolve(d: MusicAlbumDetail, ids: List<String>): List<MusicTrackItem> {
    val byId = d.tracks.associateBy { it.id }
    return ids.mapNotNull { byId[it] }   // an id missing from the payload is skipped
}

fun officialTracks(d: MusicAlbumDetail): List<MusicTrackItem> = d.officialIds?.let { resolve(d, it) } ?: d.tracks
fun extraTracks(d: MusicAlbumDetail): List<MusicTrackItem> = if (hasEditions(d)) resolve(d, d.extraIds) else emptyList()

/** FR-R373-2 point 3 — *Play album* is the official order; *Play album + extras* is official → extras → B-sides. */
fun albumQueue(d: MusicAlbumDetail, pick: AlbumPick): List<MusicTrackItem> {
    if (!hasEditions(d)) return d.tracks
    val official = officialTracks(d)
    return if (pick == AlbumPick.ALBUM) official else official + extraTracks(d) + d.bsideTracks
}

/**
 * A tap on a row plays from it inside the pick's order; a row outside the pick (an extra or a B-side while the pick
 * is *Play album*) plays the extended order from that row and leaves the remembered pick alone (dev review 3).
 * Returns the queue and the index to start at.
 */
fun queueForTap(d: MusicAlbumDetail, pick: AlbumPick, trackId: String): Pair<List<MusicTrackItem>, Int> {
    val own = albumQueue(d, pick)
    own.indexOfFirst { it.id == trackId }.takeIf { it >= 0 }?.let { return own to it }
    val extended = albumQueue(d, AlbumPick.EXTRAS)
    return extended to extended.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
}

/** The header's length: the official album's (as today's header sums `tracks`). */
fun headerLengthMs(d: MusicAlbumDetail): Long = officialTracks(d).sumOf { it.durationMs ?: 0L }

/** The pick as it is stored, read back; an unmatched album ignores it. */
fun effectivePick(d: MusicAlbumDetail, stored: AlbumPick): AlbumPick = if (hasEditions(d)) stored else AlbumPick.ALBUM

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
