package dev.jellystructure.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DEB-2205 (FR-8) — the encoder's own record of one play, written when its job stops: stored as JSON on the play's QoE row
 * (`playback_qoe.encoder_summary`) and logged as one activity-ring line. It exists so the next "it froze" report is answered
 * from data: the 2026-10-22 play's job made its segments long before Play, and nothing recorded that.
 *
 * Every figure is the stream's whole life: the jobs of a stream that was seeked are one summary.
 */
@Serializable
data class EncoderSummary(
    /** Segments the stream's jobs wrote (variant 0; every variant advances together). */
    val segments: Int,
    /** ffmpeg's own `speed=` (× real time): the slowest and the median of its progress reports; null when it printed none. */
    @SerialName("speed_min") val speedMin: Double? = null,
    @SerialName("speed_median") val speedMedian: Double? = null,
    /** The longest any segment request waited for its file, and how many waited over [SLOW_WAIT_MS]. */
    @SerialName("longest_wait_ms") val longestWaitMs: Long = 0,
    @SerialName("waits_over_1s") val waitsOver1s: Int = 0,
    /** Bytes ffmpeg read per second while it ran (`/proc/<pid>/io` `rchar`), in MB/s; null when never sampled. */
    @SerialName("input_mbps") val inputMBps: Double? = null,
    /** The rungs it ran, e.g. `2160p@20000k`. */
    val rungs: List<String> = emptyList(),
    /** The job was SIGSTOPped at least once (40 s ahead of the player, FR-313-1). */
    val paused: Boolean = false,
) {
    /** The activity-ring line. */
    fun logLine(streamId: String, why: String): String =
        "encoder $streamId: summary ($why) segments=$segments speed=${fmt(speedMin)}/${fmt(speedMedian)}x min/median " +
            "longest-wait=${longestWaitMs}ms waits>1s=$waitsOver1s input=${fmt(inputMBps)}MB/s rungs=${rungs.joinToString("/")} paused=$paused (DEB-2205 FR-8)"

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    /** A play can have more than one stream (a restream is a second one): their summaries are one record. */
    operator fun plus(o: EncoderSummary): EncoderSummary {
        val bigger = if (o.segments > segments) o else this
        return EncoderSummary(
            segments = segments + o.segments,
            speedMin = listOfNotNull(speedMin, o.speedMin).minOrNull(),
            speedMedian = bigger.speedMedian ?: (if (bigger === this) o.speedMedian else speedMedian),
            longestWaitMs = maxOf(longestWaitMs, o.longestWaitMs),
            waitsOver1s = waitsOver1s + o.waitsOver1s,
            inputMBps = bigger.inputMBps ?: (if (bigger === this) o.inputMBps else inputMBps),
            rungs = (rungs + o.rungs).distinct(),
            paused = paused || o.paused,
        )
    }

    companion object {
        const val SLOW_WAIT_MS = 1_000L
        private val JSON = Json { encodeDefaults = true }
        fun fromJson(s: String): EncoderSummary? = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), s) }.getOrNull()
        private fun fmt(d: Double?): String = d?.let { (kotlin.math.round(it * 100) / 100).toString() } ?: "-"
    }
}

/**
 * The counters [EncoderSummary] is made from, filled while a stream's jobs run. Not thread-safe by itself: [EncoderJobs]
 * touches it under its own lock or from the one coroutine that reads a job's progress, and reads it once, at the stop.
 */
internal class EncoderStats(private val rungs: List<String>) {
    private val speeds = ArrayList<Double>()
    private var longestWaitMs = 0L
    private var slowWaits = 0
    private var inputBytes = 0L
    private var inputMs = 0L
    private var lastSample: Pair<Long, Long>? = null   // (rchar, at ms) of the previous sample of the CURRENT job
    private var segments = 0
    private var everPaused = false

    fun speed(x: Double) { if (x > 0.0 && x.isFinite()) speeds += x }

    fun waited(ms: Long) {
        if (ms > longestWaitMs) longestWaitMs = ms
        if (ms > EncoderSummary.SLOW_WAIT_MS) slowWaits++
    }

    /** A new job of the stream starts: its first sample has no predecessor to take a difference with. */
    fun newJob() { lastSample = null }

    /**
     * One `rchar` reading of the running job at [atMs]. A gap longer than [MAX_GAP_MS] (a SIGSTOP pause, or a job that
     * printed nothing for a while) adds neither bytes nor time, so a paused job does not dilute the rate.
     */
    fun inputSample(rchar: Long, atMs: Long) {
        val prev = lastSample
        lastSample = rchar to atMs
        if (prev == null) return
        val dt = atMs - prev.second
        val db = rchar - prev.first
        if (dt in 1..MAX_GAP_MS && db >= 0) { inputBytes += db; inputMs += dt }
    }

    fun paused() { everPaused = true }
    fun segmentsMade(n: Int) { segments += n }

    fun summary(): EncoderSummary = EncoderSummary(
        segments = segments,
        speedMin = speeds.minOrNull(),
        speedMedian = median(speeds),
        longestWaitMs = longestWaitMs,
        waitsOver1s = slowWaits,
        inputMBps = if (inputMs > 0) inputBytes.toDouble() / inputMs * 1000.0 / 1_000_000.0 else null,
        rungs = rungs,
        paused = everPaused,
    )

    companion object {
        const val MAX_GAP_MS = 3_000L
    }
}

internal fun median(xs: List<Double>): Double? {
    if (xs.isEmpty()) return null
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
}

/** ffmpeg `-progress` prints `speed=1.02x` (and `speed=N/A` until it has one); null for anything else. */
internal fun parseProgressSpeed(line: String): Double? {
    val t = line.trim()
    if (!t.startsWith("speed=")) return null
    return t.removePrefix("speed=").removeSuffix("x").trim().toDoubleOrNull()
}

/** `rchar: 123` out of `/proc/<pid>/io`'s text; null when absent. */
internal fun parseRchar(procIo: String): Long? =
    procIo.lineSequence().firstOrNull { it.startsWith("rchar:") }?.substringAfter(':')?.trim()?.toLongOrNull()
