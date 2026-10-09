package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.Device_stream_record
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.QoeStall
import kotlin.math.roundToLong

/**
 * Phase 309 (FR-309-1) — what one device has shown it can take, kept by the backend from every play's QoE and 309's
 * probe. Every number is one this device measured or lived through (owner, 2026-10-05: *"only the real live test on the
 * client"*): no address, no inside or outside, no setting.
 *
 * Units: bits per second of the **stream as the master advertises it** (a variant's `BANDWIDTH`, which is what the
 * players report as the variant they play and what Media3 compares its estimate with); for a direct play, the file's own
 * bitrate. One unit everywhere, so "it held this" and "start on that" compare like with like.
 */
data class StreamRecord(
    val provenBps: Long? = null,
    val provenAt: Long? = null,
    val measuredBps: Long? = null,
    val measuredAt: Long? = null,
    val stalledBps: Long? = null,
    val stalledAt: Long? = null,
)

/** A stream held this long with no stall proves it (FR-309-1). */
internal const val PROOF_HOLD_SEC = 120L
/** A proof or a measurement older than this is forgotten (FR-309-1/-2: "a record older than 30 days" is none). */
internal const val RECORD_MAX_AGE_SEC = 30L * 86_400L
/** A stall lowers what the device can take for this long (FR-309-1). */
internal const val STALL_HOLD_SEC = 24L * 3_600L
/** FR-309-1 — after a stall, at most this share of the stream it stalled on. */
internal const val STALL_HEADROOM = 0.8
/** Owner (Q8, 2026-10-08): a stall counts when it lasts at least this long… (the shared [dev.jellystructure.shared.tv.StallRule]) */
internal const val STALL_COUNTS_MS = dev.jellystructure.shared.tv.StallRule.COUNTS_MS
/** …or when two come within this long of each other. */
internal const val STALL_PAIR_WINDOW_MS = dev.jellystructure.shared.tv.StallRule.PAIR_WINDOW_MS
/** FR-309-3 — a measurement (probe or QoE) older than this is refreshed by the next probe. */
internal const val MEASUREMENT_FRESH_SEC = 24L * 3_600L

/** FR-309-2 — a device with no record starts on the 720p 4 Mbps rung (video bits/s; owner: "never the top first"). */
internal const val NO_RECORD_START_VIDEO_BPS = 4_000_000L
/** The same start for Jellyfin's own ladder (308), whose master lines carry video + audio. */
internal const val NO_RECORD_START_STREAM_BPS = 4_500_000L
/** Owner decision 3 (2026-10-07): with no record, a file up to this plays directly; above it, the ladder. */
internal const val NO_RECORD_DIRECT_PLAY_BPS = 8_000_000L

private fun fresh(at: Long?, now: Long, maxAge: Long): Boolean = at != null && now - at in 0..maxAge

/**
 * FR-309-1 — what the device can take now: its proof or its measurement × 0.7, whichever is higher, under a recent
 * stall's cap (× 0.8 for 24 h). Null when the device has neither proved nor measured anything and has not stalled.
 *
 * Deviation, recorded in the spec: the spec says "proven_bps **or** measured × 0.7"; taking the higher of the two keeps
 * a device that proved only its 720p start (its first plays climb from there) from being held at 720p forever once it
 * has measured a fast path. The stall cap still wins over both.
 *
 * [qoeMeasuredBps]/[qoeMeasuredAt]: 308's measured throughput from the QoE rows (a newer probe wins over it).
 */
internal fun canTake(record: StreamRecord?, now: Long, qoeMeasuredBps: Long? = null, qoeMeasuredAt: Long? = null): Long? {
    val proven = record?.provenBps?.takeIf { it > 0 && fresh(record.provenAt, now, RECORD_MAX_AGE_SEC) }
    val measured = newestMeasurement(record, now, qoeMeasuredBps, qoeMeasuredAt)?.first
    val stallCap = record?.stalledBps?.takeIf { it > 0 && fresh(record.stalledAt, now, STALL_HOLD_SEC) }
        ?.let { (it * STALL_HEADROOM).roundToLong() }
    val base = listOfNotNull(proven, measured?.let { (it * THROUGHPUT_HEADROOM).roundToLong() }).maxOrNull()
    return when {
        base != null && stallCap != null -> minOf(base, stallCap)
        else -> base ?: stallCap
    }
}

/** FR-309-13 — the newer of the probe's measurement and the QoE rows' (each ≤ 30 days): value and when. */
internal fun newestMeasurement(record: StreamRecord?, now: Long, qoeBps: Long?, qoeAt: Long?): Pair<Long, Long>? {
    val probe = record?.measuredBps?.takeIf { it > 0 && fresh(record.measuredAt, now, RECORD_MAX_AGE_SEC) }?.let { it to record.measuredAt!! }
    val qoe = qoeBps?.takeIf { it > 0 && fresh(qoeAt, now, RECORD_MAX_AGE_SEC) }?.let { it to qoeAt!! }
    return listOfNotNull(probe, qoe).maxByOrNull { it.second }
}

/** FR-309-3 — true when the device should measure its path again (nothing fresh within 24 h). */
internal fun probeNeeded(record: StreamRecord?, now: Long, qoeAt: Long?): Boolean {
    val newest = listOfNotNull(record?.measuredAt?.takeIf { (record.measuredBps ?: 0) > 0 }, qoeAt).maxOrNull()
    return newest == null || now - newest > MEASUREMENT_FRESH_SEC
}

/**
 * FR-309-8 + owner decisions 3/4 — the cap on the stream Jellyfin negotiates (its `MaxStreamingBitrate`), which decides
 * whether a file direct-plays and, for an audio-only transcode, whether the picture is re-encoded (a file above the cap
 * is). For a player that adapts: what the device can take, else (no record) [NO_RECORD_DIRECT_PLAY_BPS]. For one that
 * cannot (every installed 1.50 app, mpv): what the device can take, else nothing (re-dev review item 2 — never pin a
 * player that can't climb at a no-record start).
 *
 * [tvApp] (found live 2026-10-09: a Cast Connect start in the Ravilo TV app with no record was capped at 8 Mbps and got a
 * 1080p transcode of a 4K film the BRAVIA plays directly) — the Ravilo app on a TV (not the Cast web receiver, not a
 * phone or a computer) with no record is not capped by a guess: its own decode ceiling (`max_video_bitrate`, already in
 * [maxStreamingBitrate], and phase 177's per-codec requirement) decides what it plays directly. A record, once it has
 * one, caps it like any device.
 */
internal fun negotiationCap(adaptive: Boolean, take: Long?, tvApp: Boolean = false): Long? =
    take ?: if (adaptive && !tvApp) NO_RECORD_DIRECT_PLAY_BPS else null

/** 309 — the Ravilo app on a TV (Android TV / Google TV): `platform = tv`, and never a Cast receiver's row. */
internal val DeviceData.isTvApp: Boolean get() = platform == "tv" && kind != "cast"

/** One QoE post as the record reads it. [variantBps]: the stream it is on (BANDWIDTH, or a direct play's file bitrate). */
internal data class RecordSample(val variantBps: Long?, val stalls: List<QoeStall>, val perItem: Boolean)

/** Per (device, item, play) between two posts: the stream held since when, and the stalls already seen. */
internal data class HoldState(val variantBps: Long?, val sinceSec: Long, val stallsSeen: Int)

/** FR-309-1 / owner Q8 — the stalls among [stalls] that count: ≥ 2 s, or two within a minute of each other. */
internal fun countingStalls(stalls: List<QoeStall>): List<QoeStall> = dev.jellystructure.shared.tv.StallRule.counting(stalls)

/**
 * FR-309-1 — one QoE post folded into the record. Only an R381 per-item report is read (a legacy row's counters were
 * session totals or a reused player's next start, R381 FR-R381-4). A new stall that counts writes `stalled`; any new
 * stall restarts the hold; a stream held ≥ 2 min with no new stall raises `proven` (or replaces an expired one).
 * Returns the new record (unchanged if nothing moved) and the new hold state.
 */
internal fun foldRecord(record: StreamRecord, hold: HoldState?, sample: RecordSample, now: Long): Pair<StreamRecord, HoldState?> {
    if (!sample.perItem) return record to hold
    var rec = record
    val seen = hold?.stallsSeen ?: 0
    val newStalls = if (sample.stalls.size > seen) sample.stalls.drop(seen) else emptyList()
    if (newStalls.isNotEmpty()) {
        val counting = countingStalls(sample.stalls)
        val newCounting = counting.filter { it in newStalls }
        if (newCounting.isNotEmpty()) {
            val on = newCounting.last().variantBps?.takeIf { it > 0 } ?: sample.variantBps
            if (on != null && on > 0) rec = rec.copy(stalledBps = on, stalledAt = now)
        }
        // Any new stall: the hold starts again from here.
        return rec to HoldState(sample.variantBps, now, sample.stalls.size)
    }
    val v = sample.variantBps
    if (v == null || v <= 0) return rec to HoldState(null, now, sample.stalls.size)
    if (hold == null || hold.variantBps != v) return rec to HoldState(v, now, sample.stalls.size)
    if (now - hold.sinceSec >= PROOF_HOLD_SEC) {
        val provenStill = rec.provenBps?.takeIf { fresh(rec.provenAt, now, RECORD_MAX_AGE_SEC) }
        if (provenStill == null || v >= provenStill) rec = rec.copy(provenBps = v, provenAt = now)
        // A proof newer than the last stall, at or above the stalled stream, ends that stall's cap (trust regained).
        val stalledOn = rec.stalledBps
        if (stalledOn != null && v >= stalledOn) rec = rec.copy(stalledBps = null, stalledAt = null)
    }
    return rec to hold
}

/** 309 (FR-309-1) — the record, one row per device. */
class StreamRecordStore(private val db: JellystructureDb) {
    private val queries get() = db.streamRecordQueries

    fun get(deviceId: String): StreamRecord? = queries.get(deviceId).executeAsOneOrNull()?.toRecord()

    fun put(deviceId: String, r: StreamRecord, now: Long) {
        queries.upsert(deviceId, r.provenBps, r.provenAt, r.measuredBps, r.measuredAt, r.stalledBps, r.stalledAt, now)
    }

    fun all(): Map<String, StreamRecord> = queries.all().executeAsList().associate { it.device_id to it.toRecord() }
}

private fun Device_stream_record.toRecord() = StreamRecord(proven_bps, proven_at, measured_bps, measured_at, stalled_bps, stalled_at)
