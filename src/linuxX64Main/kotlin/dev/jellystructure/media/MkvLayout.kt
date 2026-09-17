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

    /** Phase 201, 2026-09-13 amendment — a top-level element's declared size runs past the actual end
     *  of the file (e.g. `ffmpeg -xerror` reporting "Element at 0x... exceeds containing master
     *  element ending at 0x..."). Found on a file the day after the 85-episode concurrent-repair race
     *  documented in this phase's spec — almost certainly that race's `mv` landing badly on one
     *  element while a losing concurrent attempt wrote over the same fixed temp filename. Same
     *  failure mode as [TRACKS_AFTER_CLUSTER] (ffprobe/Jellyfin resync past it and report the file
     *  fine; a linear reader like ExoPlayer's `MatroskaExtractor` cannot) and the same fix (a
     *  `-cues_to_front` remux rewrites every element with a correct size), but a different write-time
     *  defect — kept as its own case so user-facing copy doesn't claim "track list unreachable" for a
     *  file where that specific claim isn't true. Detected the same way [TRACKS_AFTER_CLUSTER] already
     *  was found before this case existed: by the time the per-child loop below is skipping a top-level
     *  Segment child, the EBML header and Segment header already parsed correctly, so a [safeSkip]
     *  failure there is specifically "this element's declared size overruns the file," never "not
     *  Matroska at all." */
    ELEMENT_SIZE_OVERFLOW,

    /** Not a parseable EBML/Matroska file, or an element used an unknown ("all ones") size in a
     *  position this walker can't safely skip over. Distinct from [OK] so a caller doesn't mistake
     *  "couldn't tell" for "confirmed fine" — [MkvLayoutAudit] counts it separately. */
    UNKNOWN,
    ;

    /** Phase 234 (FR-234-1) — the ONE answer to "is this file broken in a way a remux fixes". The sweep,
     *  Fix now and the post-`mkvpropedit` check all read it, so they cannot disagree again. */
    val needsRepair: Boolean get() = this == TRACKS_AFTER_CLUSTER || this == ELEMENT_SIZE_OVERFLOW

    /** Phase 234 (FR-234-1/3) — what the log says before a repair. An overflowing element is not "Tracks
     *  evicted", and its repair is LOSSY: a stream copy discards what it cannot parse (measured: −246
     *  video packets on one production file), so the sentence says where the real fix is. */
    fun repairSentence(filePath: String): String = when (this) {
        TRACKS_AFTER_CLUSTER -> "mkvpropedit evicted Tracks past the first Cluster on $filePath — repairing via ffmpeg remux"
        ELEMENT_SIZE_OVERFLOW -> "An element in $filePath declares a size that overruns its container — repairing via ffmpeg remux. " +
            "This repair DISCARDS the data it cannot read; replace the file from its source to get it back"
        else -> "No repair needed for $filePath"
    }
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
                // Past this point we're skipping a top-level Segment child of a file that already
                // parsed a valid EBML header and Segment header — a skip failure here means the
                // declared size overruns the actual file, not "unparseable."
                if (!safeSkip(source, size)) return MkvLayout.ELEMENT_SIZE_OVERFLOW
            }
            CLUSTER_ID -> {
                if (!sawTracks) return MkvLayout.TRACKS_AFTER_CLUSTER
                // A Cluster with an unknown size is legal for a still-being-written/streamed file —
                // there is no declared bound to validate against, so don't guess.
                if (size == UNKNOWN_SIZE) return MkvLayout.OK
                return scanClusterChildren(source, size)
            }
            else -> {
                // An unknown-size element here (only legal for a still-being-written Cluster in
                // practice) can't be skipped — bail out honestly rather than guess.
                if (size == UNKNOWN_SIZE) return MkvLayout.UNKNOWN
                if (!safeSkip(source, size)) return MkvLayout.ELEMENT_SIZE_OVERFLOW
            }
        }
    }
    // Reached EOF with no Cluster at all (e.g. a truncated or non-video MKV) — nothing to strand a
    // player on, so it's not the failure mode this check exists to catch.
    return MkvLayout.OK
}

/**
 * Phase 201, 2026-09-13 amendment — walks the children of the first `Cluster`, checking each declared
 * size against the bytes the `Cluster` itself said it had left.
 *
 * This is where the real-world corruption actually lives: the 2026-09-13 concurrent-repair race left
 * files whose *first Cluster body* contains a child element declaring a preposterous size (one
 * production file: 22 GB inside a 395 KB Cluster). The top-level walk above never saw it, because it
 * treats the whole `Cluster` as one opaque element to stop at — so a file could be reported `OK` while
 * being exactly as unplayable as a `TRACKS_AFTER_CLUSTER` one, and with the same symptom (ffprobe and
 * Jellyfin resync past the bad element and report it fine; ExoPlayer's linear reader cannot).
 *
 * Bounded on purpose: only the *first* Cluster is descended into, so this reads one Cluster's worth of
 * bytes (a few hundred KB) rather than the whole file — the full 8 015-file production sweep ran in
 * ~88 s with this check in place. It needs no file length and no seeking: every bound is the `Cluster`'s
 * own declared size, decremented as children are consumed, which is exactly the arithmetic a linear
 * reader does and therefore exactly the arithmetic that fails on these files.
 */
private fun scanClusterChildren(source: Source, clusterSize: Long): MkvLayout {
    var remaining = clusterSize
    // A Cluster holds blocks, not thousands of tiny elements; this only exists so a pathological
    // (already-corrupt) file can't spin here. Hitting it means "couldn't tell", not "fine".
    var guard = 0
    while (remaining > 0 && guard < 100_000) {
        guard++
        // The Cluster said it had more bytes than the file actually contains.
        if (source.exhausted()) return MkvLayout.ELEMENT_SIZE_OVERFLOW
        val id = readVintSized(source, stripMarker = false) ?: return MkvLayout.ELEMENT_SIZE_OVERFLOW
        remaining -= id.bytesRead
        if (remaining <= 0) return MkvLayout.ELEMENT_SIZE_OVERFLOW
        val size = readVintSized(source, stripMarker = true) ?: return MkvLayout.ELEMENT_SIZE_OVERFLOW
        remaining -= size.bytesRead
        // An unknown-size child can't be measured against the remaining budget — stop rather than guess.
        if (size.value == UNKNOWN_SIZE) return MkvLayout.OK
        if (size.value > remaining) return MkvLayout.ELEMENT_SIZE_OVERFLOW
        if (!safeSkip(source, size.value)) return MkvLayout.ELEMENT_SIZE_OVERFLOW
        remaining -= size.value
    }
    return MkvLayout.OK
}

private class Vint(val value: Long, val bytesRead: Int)

/** [readVintId]/[readVintSize] with the consumed byte count, which [scanClusterChildren] needs to
 *  decrement its budget by. `stripMarker` picks between the two: an ID keeps its length-descriptor
 *  bits (per spec, and matching the ID constants above), a size has them stripped. */
private fun readVintSized(source: Source, stripMarker: Boolean): Vint? {
    if (source.exhausted()) return null
    val b0 = source.readByte().toInt() and 0xFF
    val len = vintLength(b0)
    if (len <= 0) return null
    val marker = 0x80 shr (len - 1)
    var value = if (stripMarker) (b0 and marker.inv() and 0xFF).toLong() else b0.toLong()
    repeat(len - 1) {
        if (source.exhausted()) return null
        value = (value shl 8) or (source.readByte().toLong() and 0xFF)
    }
    if (stripMarker && value == (1L shl (7 * len)) - 1) return Vint(UNKNOWN_SIZE, len)
    return Vint(value, len)
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
