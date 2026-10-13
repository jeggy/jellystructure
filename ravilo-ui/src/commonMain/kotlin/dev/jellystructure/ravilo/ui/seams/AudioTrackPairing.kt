package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.AudioTrack

/**
 * R382, amendment 2026-10-13 (FR-R382-8) — which engine audio group is which ticket track.
 *
 * A ticket's audio list and a player engine's own list are two index spaces, and they coincide only by luck. On
 * Android they stopped coinciding the moment a track needed the FFmpeg extension renderer: Media3 builds
 * `Tracks.groups` by looping RENDERERS outermost (`TrackSelectionUtil.buildTracks`) and R379 puts the extension
 * renderer after the platform one, so a DTS or TrueHD track is listed after every track a platform decoder takes.
 * Zipping the ticket onto the groups by position then attributes every label, language, channel count and 314b
 * `copyOf` from that track on to the WRONG group — the row the viewer reads is not the group the pick selects
 * (DEB-740: tapping *Norsk* played Swedish).
 *
 * So a row is a ticket entry, and the group it plays is found by identity. The web already works this way
 * ([audioTracksFromRenditions] reads a rendition's `a{position}` name, never hls.js's list position) and the desktop
 * reads mpv's own per-track metadata; this is the same rule as a function, so the platforms share the reasoning.
 */
data class EngineAudioTrack(
    val language: String?,
    /** The container's own track title — or, on one of this server's composed masters, the rendition's `a{n} …` name. */
    val label: String?,
    val channels: Int?,
    /** A mime type (`audio/true-hd`) or a codec name (`truehd`); compared loosely, and only as a tie-break. */
    val codec: String?,
)

/** One picker row: the ticket entry it is, and the engine audio group that plays it (by its position in the engine's
 *  own audio list). [ticketPosition] is null for a group no ticket entry claims — it keeps its container metadata. */
data class PairedAudioRow(val enginePosition: Int, val ticketPosition: Int?)

/**
 * The rows for [engine]'s audio groups: one per group — never an invented row, so a single-audio session (R284) still
 * reports the one track it carries — paired with the ticket entry each group IS, and ordered by the TICKET, so a row's
 * position is its ticket position wherever the two lists are the same length. A group nothing claims sorts last.
 *
 * The passes run strongest signal first and each claims only unclaimed entries, so a weaker signal can never steal an
 * entry a stronger one would have matched, and two genuinely indistinguishable tracks keep their relative order (where
 * nothing distinguishes them, no attribution can be wrong). Codec is a tie-break, never a requirement: the loose
 * comparison exists to let `audio/true-hd` meet `truehd`, and a pass that fails on it simply falls through.
 */
internal fun pairAudioToTicket(engine: List<EngineAudioTrack>, ticket: List<AudioTrack>): List<PairedAudioRow> {
    if (ticket.isEmpty()) return engine.indices.map { PairedAudioRow(it, null) }
    val claimedBy = MutableList<Int?>(engine.size) { null }
    val claimed = MutableList(ticket.size) { false }
    for (pass in PAIRING_PASSES) {
        for (gi in engine.indices) {
            if (claimedBy[gi] != null) continue
            val ti = ticket.indices.firstOrNull { !claimed[it] && pass(engine[gi], it, ticket[it]) } ?: continue
            claimedBy[gi] = ti
            claimed[ti] = true
        }
    }
    // Unmatched groups sort after every matched row, keeping the engine's own order among themselves (sortedBy is stable).
    return engine.indices.map { PairedAudioRow(it, claimedBy[it]) }
        .sortedBy { it.ticketPosition ?: (ticket.size + it.enginePosition) }
}

/** Strongest first. A pass compares only fields both sides actually carry; a null on either side is never a match. */
private val PAIRING_PASSES: List<(EngineAudioTrack, Int, AudioTrack) -> Boolean> = listOf(
    // R291 — a rendition of one of this server's composed masters is named `a{its ticket position}`. The strongest
    // identity there is, and the only one that survives a master whose renditions all carry the same language.
    { e, i, _ -> renditionPosition(e.label) == i },
    { e, _, t -> sameLang(e, t) && sameChannels(e, t) && sameCodec(e, t) },
    { e, _, t -> sameLang(e, t) && sameChannels(e, t) && likeCodec(e, t) },
    { e, _, t -> sameLang(e, t) && sameChannels(e, t) },
    { e, _, t -> sameLang(e, t) && sameCodec(e, t) },
    { e, _, t -> sameLang(e, t) },
    // A track with no language at all (an undetermined sidecar) still has a shape.
    { e, _, t -> sameChannels(e, t) && sameCodec(e, t) },
    // Whatever is left, in order — what the whole list used to do, now confined to tracks nothing else could tell apart.
    { _, _, _ -> true },
)

private fun sameLang(e: EngineAudioTrack, t: AudioTrack): Boolean =
    e.language != null && t.language != null && dev.jellystructure.shared.tv.sameLanguage(e.language, t.language)

private fun sameChannels(e: EngineAudioTrack, t: AudioTrack): Boolean =
    e.channels != null && t.channels != null && e.channels == t.channels

private fun sameCodec(e: EngineAudioTrack, t: AudioTrack): Boolean {
    val a = codecKey(e.codec) ?: return false
    val b = codecKey(t.codec) ?: return false
    return a == b
}

/** `audio/true-hd` ↔ `truehd`, `audio/vnd.dts` ↔ `dts`. Deliberately not exact: `eac3` contains `ac3`, which is why
 *  this is a separate, weaker pass than [sameCodec] and never decides between two tracks of the same shape alone. */
private fun likeCodec(e: EngineAudioTrack, t: AudioTrack): Boolean {
    val a = codecKey(e.codec) ?: return false
    val b = codecKey(t.codec) ?: return false
    return a.contains(b) || b.contains(a)
}

private fun codecKey(codec: String?): String? =
    codec?.substringAfterLast('/')?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }
