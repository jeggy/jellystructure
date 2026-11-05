package dev.jellystructure.media

import kotlinx.serialization.Serializable

/**
 * DEB-3637 (DEB-3633 FR-2) — what counts as a hole in a file's picture and sound, decided in ONE place.
 *
 * A hole is a stretch in which the file's main video **and** a given audio track both have no packet. One of
 * them pausing is not a hole: a still title card in a variable-frame-rate file has no video packets while the
 * sound plays on, and a silent scene has no audio packets while the picture continues. The deep check
 * (`FileIntegrity.timelineCommand`) prints one `framecrc` line per packet; [TimelineScan] folds those lines as
 * they arrive and never holds them, because a film has more than half a million of them.
 */
object TimelineHoles {
    /** A stretch shorter than this is never a hole. Named here and nowhere else (FR-2). */
    const val MIN_HOLE_MS = 500L
    /** How many holes are kept as `(start, length)`; the count and the totals cover all of them. */
    const val KEPT_HOLES = 50
    /** The worst track's total is kept beside the default track's only when it differs by more than this. */
    const val WORST_TRACK_MARGIN_MS = 1_000L
    /** A video packet with no duration covers one frame; this is the frame rate when the file does not say. */
    const val FALLBACK_FRAME_MS = 40L
    const val FALLBACK_AUDIO_MS = 24L
}

/** One stretch with no picture and no sound. */
@Serializable
data class TimelineHole(val startMs: Long, val lengthMs: Long)

/** What ffprobe's header read says about a stream and the packet lines cannot: the flags and the frame rate. */
data class StreamHint(val default: Boolean = false, val attachedPic: Boolean = false, val frameRate: Double? = null, val mediaType: String = "", val codec: String = "")

/**
 * The file's figures (FR-2): the **default audio track's** (else the first's) — hole count, total missing,
 * longest, the first [TimelineHoles.KEPT_HOLES] holes — and the worst track's total when it differs by more
 * than a second. [emptyStreams] are the streams that held no packet over the whole read
 * (a-damaged-file-still-plays FR-1), whatever their kind.
 */
data class TimelineReport(
    val holeCount: Int,
    val missingMs: Long,
    val longestMs: Long,
    val worstTrackMissingMs: Long?,
    val holes: List<TimelineHole>,
    val emptyStreams: List<Int>,
) {
    val whole: Boolean get() = holeCount == 0
}

/**
 * Folds `ffmpeg -f framecrc` lines into a [TimelineReport]. Header lines (`#tb 0: 1/1000`,
 * `#media_type 0: video`, `#codec_id 0: h264`) say which streams exist and how to read their ticks; a packet
 * line is `stream, dts, pts, duration, size, hash`. Anything else is ignored, so a caller may feed the whole
 * of a pass's stdout.
 *
 * Memory is bounded by the number of *gaps*, not packets: each stream keeps its coverage as sorted, disjoint
 * intervals, and a gap shorter than [TimelineHoles.MIN_HOLE_MS] is closed on the spot, because a hole is the
 * overlap of two gaps and so needs both to be at least that long.
 */
class TimelineScan(private val hints: Map<Int, StreamHint> = emptyMap()) {
    private class Stream(val index: Int) {
        var num = 1L
        var den = 1000L
        var mediaType = ""
        var codec = ""
        var packets = 0L
        var sawTimebase = false
        val coverage = Coverage()
    }

    private val streams = HashMap<Int, Stream>()

    /** True once the header named at least one stream — a pass that printed none produced no timeline. */
    val sawHeader: Boolean get() = streams.values.any { it.sawTimebase }

    fun line(raw: String) {
        if (raw.isEmpty()) return
        if (raw[0] == '#') { header(raw); return }
        packet(raw)
    }

    private fun header(raw: String) {
        // `#tb 0: 1/1000`, `#media_type 0: video`, `#codec_id 0: h264`
        val colon = raw.indexOf(':')
        if (colon < 0) return
        val head = raw.substring(1, colon).trim().split(' ').filter { it.isNotEmpty() }
        if (head.size != 2) return
        val index = head[1].toIntOrNull() ?: return
        val value = raw.substring(colon + 1).trim()
        when (head[0]) {
            "tb" -> {
                val (n, d) = value.split('/').takeIf { it.size == 2 }?.let { it[0].trim().toLongOrNull() to it[1].trim().toLongOrNull() } ?: return
                if (n == null || d == null || n <= 0 || d <= 0) return
                val s = streams.getOrPut(index) { Stream(index) }
                s.num = n; s.den = d; s.sawTimebase = true
            }
            "media_type" -> streams.getOrPut(index) { Stream(index) }.mediaType = value
            "codec_id" -> streams.getOrPut(index) { Stream(index) }.codec = value.lowercase()
        }
    }

    private fun packet(raw: String) {
        val f = raw.split(',')
        if (f.size < 5) return
        val index = f[0].trim().toIntOrNull() ?: return
        val s = streams[index] ?: return
        s.packets++
        val dts = f[1].trim().toLongOrNull()
        val pts = f[2].trim().toLongOrNull()?.takeIf { it != Long.MIN_VALUE } ?: dts?.takeIf { it != Long.MIN_VALUE } ?: return
        val duration = f[3].trim().toLongOrNull()?.takeIf { it > 0 }
        val startMs = ms(pts, s)
        val lengthMs = if (duration != null) maxOf(1L, ms(duration, s)) else fallbackMs(s)
        s.coverage.add(startMs, startMs + lengthMs)
    }

    private fun ms(ticks: Long, s: Stream): Long = ticks * 1000L * s.num / s.den

    /** The header's word, else the probe's: a ffmpeg that prints no `#media_type` must not read as no video. */
    private fun type(s: Stream): String = s.mediaType.ifEmpty { hints[s.index]?.mediaType.orEmpty() }
    private fun codecOf(s: Stream): String = s.codec.ifEmpty { hints[s.index]?.codec.orEmpty().lowercase() }

    private fun fallbackMs(s: Stream): Long = when (type(s)) {
        "video" -> hints[s.index]?.frameRate?.takeIf { it > 0.0 }?.let { maxOf(1L, (1000.0 / it).toLong()) } ?: TimelineHoles.FALLBACK_FRAME_MS
        else -> TimelineHoles.FALLBACK_AUDIO_MS
    }

    private fun isCoverArt(s: Stream): Boolean =
        type(s) == "video" && (hints[s.index]?.attachedPic == true || codecOf(s) in TrackCoverage.IMAGE_CODECS || s.packets <= 1)

    /** The figures so far; call once the pass has finished. */
    fun report(): TimelineReport {
        val all = streams.values.filter { it.sawTimebase }.sortedBy { it.index }
        val empty = all.filter { it.packets == 0L && type(it) in COUNTED_TYPES }.map { it.index }
        // The main video is the real picture with the most packets; cover art is never it (phase 255's rule).
        val video = all.filter { type(it) == "video" && !isCoverArt(it) }.maxWithOrNull(compareBy<Stream> { it.packets }.thenByDescending { -it.index })
        val audio = all.filter { type(it) == "audio" && it.packets > 0 }
        if (video == null || audio.isEmpty()) return TimelineReport(0, 0, 0, null, emptyList(), empty)
        val perTrack = audio.associate { a -> a.index to holesBetween(video.coverage, a.coverage) }
        val chosen = (audio.firstOrNull { hints[it.index]?.default == true } ?: audio.first())
        val mine = perTrack.getValue(chosen.index)
        val worstTotal = perTrack.values.maxOf { h -> h.sumOf { it.lengthMs } }
        val total = mine.sumOf { it.lengthMs }
        return TimelineReport(
            holeCount = mine.size,
            missingMs = total,
            longestMs = mine.maxOfOrNull { it.lengthMs } ?: 0L,
            worstTrackMissingMs = worstTotal.takeIf { it - total > TimelineHoles.WORST_TRACK_MARGIN_MS },
            holes = mine.take(TimelineHoles.KEPT_HOLES),
            emptyStreams = empty,
        )
    }

    /** The stretches no interval of [a] or [b] covers, between the first start and the last end. */
    private fun holesBetween(a: Coverage, b: Coverage): List<TimelineHole> {
        val merged = (a.intervals + b.intervals).sortedBy { it.start }
        val out = mutableListOf<TimelineHole>()
        var reach = Long.MIN_VALUE
        for (iv in merged) {
            if (reach != Long.MIN_VALUE && iv.start - reach >= TimelineHoles.MIN_HOLE_MS) out += TimelineHole(reach, iv.start - reach)
            if (iv.end > reach) reach = iv.end
        }
        return out
    }

    private class Interval(var start: Long, var end: Long)

    /** Sorted, disjoint intervals; two that sit closer than [TimelineHoles.MIN_HOLE_MS] are one. */
    private class Coverage {
        val intervals = ArrayList<Interval>()

        fun add(start: Long, end: Long) {
            val tol = TimelineHoles.MIN_HOLE_MS
            // Almost every packet lands on or after the last interval; walk back only for a reordered one.
            var i = intervals.size
            while (i > 0 && intervals[i - 1].start > end + tol - 1) i--
            // intervals[i-1] is the last one that starts early enough to join or precede the packet.
            var from = i
            while (from > 0 && start - intervals[from - 1].end < tol) from--
            // Joins every interval in [from, i) — they all start before end+tol and end after start-tol.
            if (from == i) { intervals.add(i, Interval(start, end)); return }
            val first = intervals[from]
            first.start = minOf(first.start, start)
            first.end = maxOf(first.end, end, intervals[i - 1].end)
            for (k in i - 1 downTo from + 1) intervals.removeAt(k)
        }
    }

    private companion object {
        val COUNTED_TYPES = setOf("video", "audio", "subtitle")
    }
}
