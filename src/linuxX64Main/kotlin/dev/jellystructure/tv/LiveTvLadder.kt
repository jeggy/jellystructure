package dev.jellystructure.tv

import dev.jellystructure.shared.tv.LiveTvLadderRung

/** DEB-2627 FR-1 — what a tune's `hls_url` is, as `LiveTvStreamTicket.streamKind` spells it. */
internal const val STREAM_KIND_HLS_LADDER = "hls_ladder"
internal const val STREAM_KIND_HLS = "hls"
internal const val STREAM_KIND_OTHER = "other"
/** DEB-2628 FR-4 — a station played through the audio relay. Not a classification of a playlist: only a radio tune says it. */
internal const val STREAM_KIND_AUDIO = "audio"

/** What [classifyHlsPlaylist] read: [kind] is one of the `STREAM_KIND_*` values; [ladder] is non-empty only for `hls_ladder`. */
internal data class SourceClassification(val kind: String, val ladder: List<LiveTvLadderRung> = emptyList())

/**
 * DEB-2627 FR-1 — classifies the text of a playlist the backend fetched. Null when it is not an HLS playlist at all
 * (no `#EXTM3U` first line), which the caller reads as `other`.
 *
 * `hls_ladder` needs two or more `#EXT-X-STREAM-INF` entries and no protection: a `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`
 * with `METHOD=SAMPLE-AES*`, or with a `KEYFORMAT` other than `identity`, makes the playlist plain `hls` (a client
 * cannot choose between rungs it cannot decrypt). One variant, a media playlist and a protected master are all `hls`.
 */
internal fun classifyHlsPlaylist(text: String): SourceClassification? {
    val lines = text.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.firstOrNull() != "#EXTM3U") return null
    var protected = false
    val rungs = ArrayList<LiveTvLadderRung>()
    for (line in lines) {
        when {
            line.startsWith("#EXT-X-STREAM-INF:") -> {
                val attrs = hlsAttributes(line.substringAfter(':'))
                val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L
                val (w, h) = attrs["RESOLUTION"]?.let(::hlsResolution) ?: (null to null)
                rungs += LiveTvLadderRung(bandwidth, w, h)
            }
            line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
                val attrs = hlsAttributes(line.substringAfter(':'))
                val method = attrs["METHOD"].orEmpty().uppercase()
                val keyFormat = attrs["KEYFORMAT"]
                if (method.startsWith("SAMPLE-AES") || (keyFormat != null && keyFormat != "identity")) protected = true
            }
        }
    }
    if (rungs.size >= 2 && !protected) return SourceClassification(STREAM_KIND_HLS_LADDER, rungs.sortedByDescending { it.bandwidthBps })
    return SourceClassification(STREAM_KIND_HLS)
}

/** `KEY=value,KEY="quoted, value"` — an HLS attribute list (RFC 8216 §4.2). Keys are upper-cased; quotes are removed. */
internal fun hlsAttributes(list: String): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    val n = list.length
    while (i < n) {
        val eq = list.indexOf('=', i)
        if (eq < 0) break
        val key = list.substring(i, eq).trim().uppercase()
        val value: String
        if (eq + 1 < n && list[eq + 1] == '"') {
            val close = list.indexOf('"', eq + 2).let { if (it < 0) n else it }
            value = list.substring(eq + 2, close)
            i = close + 2
        } else {
            val comma = list.indexOf(',', eq + 1).let { if (it < 0) n else it }
            value = list.substring(eq + 1, comma).trim()
            i = comma + 1
        }
        out[key] = value
    }
    return out
}

private fun hlsResolution(s: String): Pair<Int, Int>? {
    val parts = s.lowercase().split('x')
    if (parts.size != 2) return null
    val w = parts[0].toIntOrNull() ?: return null
    val h = parts[1].toIntOrNull() ?: return null
    return w to h
}

/** DEB-2627 FR-1 — a fetch that failed or timed out is a classification, never a tune failure: `hls` when the URL says `m3u8`. */
internal fun classifyUnfetched(url: String): SourceClassification =
    SourceClassification(if (url.contains("m3u8", ignoreCase = true)) STREAM_KIND_HLS else STREAM_KIND_OTHER)
