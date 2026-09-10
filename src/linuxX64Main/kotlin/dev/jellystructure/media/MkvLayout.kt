package dev.jellystructure.media

import kotlinx.io.Source

/**
 * Phase 201 (FR-201-1/2) — the invariant: a written MKV's `Tracks` element must precede its first
 * `Cluster`. `mkvpropedit` edits in place; when a grown `Tracks` no longer fits its original slot it
 * gets overwritten with a `Void` of the same size and the real `Tracks` is appended to EOF, reachable
 * only through `SeekHead`. Everything that opens the file as a local seekable file (ffprobe, Jellyfin,
 * mkvpropedit itself) follows `SeekHead` and is fine. ExoPlayer's `MatroskaExtractor` reads the HTTP
 * body linearly: it reaches the first `Cluster` having never seen `Tracks`, and never builds a single
 * renderer — no error, just permanent buffering.
 */
enum class MkvLayout {
    /** `Tracks` precedes the first `Cluster` (or no `Cluster` was found at all) — playable. */
    OK,

    /** A `Cluster` was reached before `Tracks` was ever seen — unplayable by a linear reader. */
    TRACKS_AFTER_CLUSTER,

    /** Not a parseable EBML/Matroska file, or an element used an unknown ("all ones") size in a
     *  position this walker can't safely skip over. Distinct from [OK] so a caller doesn't mistake
     *  "couldn't tell" for "confirmed fine" — [MkvLayoutAudit] counts it separately. */
    UNKNOWN,
}

// Matroska/EBML top-level element IDs (the length-descriptor bits are part of the ID, per spec).
private const val EBML_HEADER_ID = 0x1A45DFA3L
private const val SEGMENT_ID = 0x18538067L
private const val TRACKS_ID = 0x1654AE6BL
private const val CLUSTER_ID = 0x1F43B675L

/** Sentinel returned by [readVintSize] for EBML's "unknown size" ( all data bits set to 1). */
private const val UNKNOWN_SIZE = -1L

/**
 * A top-level, header-only EBML walk: reads element IDs and sizes and skips straight to the next
 * sibling via [Source.skip] rather than reading element bodies — cheap (a few hundred bytes for a
 * typical file) precisely because it never reads the (multi-megabyte) `Cluster` payload it stops at.
 *
 * Deliberately does not use ffprobe (FR-201-2): ffprobe seeks the whole file and therefore always
 * reports a complete, correct track list regardless of where `Tracks` physically sits — exactly the
 * blind spot this check exists to catch.
 */
internal fun scanMkvLayout(source: Source): MkvLayout {
    val headerId = readVintId(source) ?: return MkvLayout.UNKNOWN
    if (headerId != EBML_HEADER_ID) return MkvLayout.UNKNOWN
    val headerSize = readVintSize(source) ?: return MkvLayout.UNKNOWN
    if (headerSize == UNKNOWN_SIZE) return MkvLayout.UNKNOWN
    if (!safeSkip(source, headerSize)) return MkvLayout.UNKNOWN

    val segmentId = readVintId(source) ?: return MkvLayout.UNKNOWN
    if (segmentId != SEGMENT_ID) return MkvLayout.UNKNOWN
    readVintSize(source) ?: return MkvLayout.UNKNOWN // Segment's own size is irrelevant — we stop at Cluster or EOF, whichever first.

    var sawTracks = false
    while (!source.exhausted()) {
        val id = readVintId(source) ?: break
        val size = readVintSize(source) ?: break
        when (id) {
            TRACKS_ID -> {
                sawTracks = true
                if (!safeSkip(source, size)) return MkvLayout.UNKNOWN
            }
            CLUSTER_ID -> return if (sawTracks) MkvLayout.OK else MkvLayout.TRACKS_AFTER_CLUSTER
            else -> {
                // An unknown-size element here (only legal for a still-being-written Cluster in
                // practice) can't be skipped — bail out honestly rather than guess.
                if (size == UNKNOWN_SIZE) return MkvLayout.UNKNOWN
                if (!safeSkip(source, size)) return MkvLayout.UNKNOWN
            }
        }
    }
    // Reached EOF with no Cluster at all (e.g. a truncated or non-video MKV) — nothing to strand a
    // player on, so it's not the failure mode this check exists to catch.
    return MkvLayout.OK
}

private fun safeSkip(source: Source, byteCount: Long): Boolean {
    if (byteCount < 0) return false
    return runCatching { source.skip(byteCount) }.isSuccess
}

private fun vintLength(firstByte: Int): Int {
    if (firstByte == 0) return -1
    var mask = 0x80
    for (len in 1..8) {
        if (firstByte and mask != 0) return len
        mask = mask shr 1
    }
    return -1
}

/** Reads an EBML element ID — the length-descriptor bits are kept as part of the returned value,
 *  matching how the spec defines IDs (and how the constants above are written). */
private fun readVintId(source: Source): Long? {
    if (source.exhausted()) return null
    val b0 = source.readByte().toInt() and 0xFF
    val len = vintLength(b0)
    if (len <= 0) return null
    var value = b0.toLong()
    repeat(len - 1) {
        if (source.exhausted()) return null
        value = (value shl 8) or (source.readByte().toLong() and 0xFF)
    }
    return value
}

/** Reads an EBML VINT size — the length-descriptor bit is stripped. Returns [UNKNOWN_SIZE] when every
 *  data bit is 1 (EBML's "size unknown" convention). */
private fun readVintSize(source: Source): Long? {
    if (source.exhausted()) return null
    val b0 = source.readByte().toInt() and 0xFF
    val len = vintLength(b0)
    if (len <= 0) return null
    val marker = 0x80 shr (len - 1)
    var value = (b0 and marker.inv() and 0xFF).toLong()
    repeat(len - 1) {
        if (source.exhausted()) return null
        value = (value shl 8) or (source.readByte().toLong() and 0xFF)
    }
    val maxValue = (1L shl (7 * len)) - 1
    return if (value == maxValue) UNKNOWN_SIZE else value
}
