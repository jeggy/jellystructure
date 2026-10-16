package dev.jellystructure.tv

/**
 * DEB-1432 / FR-313-7 (build review item 11) — a restarted job's fMP4 on the file's own clock.
 *
 * ffmpeg's mov muxer writes each job's `tfdt` **relative to the job's first packet** and puts where the job began into
 * the init segment's edit list instead (an empty edit of that many milliseconds, then `media_time 0`). A player that
 * applies the edit list (Media3, AVPlayer) is right; one that reads `tfdt` alone (hls.js — the web player, Chromium's
 * MSE path) sees every restarted job's first segment at 0 s. Found live 2026-10-16: a 15-min seek on the web left the
 * video buffered where it belonged and the audio rendition at 0 s, so hls.js asked for a segment ~19 min away, and the
 * one-job-per-stream rule (an out-of-range request restarts the job) made the two renditions take the encoder from
 * each other until the player gave up after ~20 s.
 *
 * What is served instead: the init **without** its edit list, and every segment with `tfdt` shifted by what the edit
 * list said — the same timeline both kinds of player read, and the one a job started at 0 already had.
 */

private fun u32(b: ByteArray, o: Int): Long =
    ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)

private fun put64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (56 - 8 * i)).toByte() }

private fun fourcc(b: ByteArray, o: Int): String = CharArray(4) { (b[o + 4 + it].toInt() and 0xFF).toChar() }.concatToString()

/** The child boxes of [parent] (payload `[from, to)`), each as (offset, size, type); stops at a box that does not fit. */
private fun children(b: ByteArray, from: Int, to: Int): List<Triple<Int, Int, String>> {
    val out = ArrayList<Triple<Int, Int, String>>()
    var o = from
    while (o + 8 <= to) {
        val size = u32(b, o).toInt()
        if (size < 8) break
        out += Triple(o, size, fourcc(b, o))
        o += size
    }
    return out
}

private fun find(b: ByteArray, from: Int, to: Int, type: String): Triple<Int, Int, String>? = children(b, from, to).firstOrNull { it.third == type }

/**
 * Where the one track of [init] starts on the file's clock, in **its own timescale** ticks: the leading empty edit of
 * its `elst` (movie timescale) converted by `mdhd`. 0 when there is no such edit (a job that began at 0 s with no
 * delay) or the box tree is not what ffmpeg writes.
 */
internal fun fmp4TrackStartTicks(init: ByteArray): Long {
    val moov = find(init, 0, init.size, "moov") ?: return 0
    val mvhd = find(init, moov.first + 8, moov.first + moov.second, "mvhd") ?: return 0
    val movieScale = if (init[mvhd.first + 8].toInt() == 1) u32(init, mvhd.first + 8 + 4 + 8 + 8) else u32(init, mvhd.first + 8 + 4 + 4 + 4)
    val trak = find(init, moov.first + 8, moov.first + moov.second, "trak") ?: return 0
    val edts = find(init, trak.first + 8, trak.first + trak.second, "edts") ?: return 0
    val elst = find(init, edts.first + 8, edts.first + edts.second, "elst") ?: return 0
    val v1 = init[elst.first + 8].toInt() == 1
    val entries = u32(init, elst.first + 12).toInt()
    if (entries < 1 || movieScale <= 0) return 0
    val e = elst.first + 16
    val duration = if (v1) u64(init, e) else u32(init, e)
    val mediaTime = if (v1) u64(init, e + 8) else u32(init, e + 4).let { if (it >= 0x80000000L) it - 0x100000000L else it }
    if (mediaTime != -1L) return 0   // not an empty edit: nothing delays the track
    val mdia = find(init, trak.first + 8, trak.first + trak.second, "mdia") ?: return 0
    val mdhd = find(init, mdia.first + 8, mdia.first + mdia.second, "mdhd") ?: return 0
    val trackScale = if (init[mdhd.first + 8].toInt() == 1) u32(init, mdhd.first + 8 + 4 + 8 + 8) else u32(init, mdhd.first + 8 + 4 + 4 + 4)
    return duration * trackScale / movieScale
}

/** [init] with its `edts` turned into a `free` box (same size, so no offset moves): the timeline is in the segments. */
internal fun fmp4WithoutEditList(init: ByteArray): ByteArray {
    val moov = find(init, 0, init.size, "moov") ?: return init
    val out = init.copyOf()
    for (trak in children(init, moov.first + 8, moov.first + moov.second).filter { it.third == "trak" }) {
        val edts = find(init, trak.first + 8, trak.first + trak.second, "edts") ?: continue
        out[edts.first + 4] = 'f'.code.toByte(); out[edts.first + 5] = 'r'.code.toByte(); out[edts.first + 6] = 'e'.code.toByte(); out[edts.first + 7] = 'e'.code.toByte()
    }
    return out
}

/**
 * [head] (the first bytes of a segment — its `moof` precedes the data and `tfdt` is the third box inside it) with
 * `baseMediaDecodeTime` raised by [ticks], in place; false (and [head] untouched) when no `tfdt` lies within [head].
 */
internal fun fmp4ShiftBaseTime(head: ByteArray, ticks: Long): Boolean {
    val moof = find(head, 0, head.size, "moof") ?: return false
    val traf = find(head, moof.first + 8, minOf(head.size, moof.first + moof.second), "traf") ?: return false
    val tfdt = find(head, traf.first + 8, minOf(head.size, traf.first + traf.second), "tfdt") ?: return false
    if (tfdt.first + 20 > head.size) return false
    if (head[tfdt.first + 8].toInt() == 1) put64(head, tfdt.first + 12, u64(head, tfdt.first + 12) + ticks)
    else {
        // Version 0 holds 32 bits: widening would move every offset behind it. ffmpeg's fMP4 HLS writes version 1.
        val v = u32(head, tfdt.first + 12) + ticks
        if (v > 0xFFFFFFFFL) return false
        for (i in 0 until 4) head[tfdt.first + 12 + i] = (v ushr (24 - 8 * i)).toByte()
    }
    return true
}
