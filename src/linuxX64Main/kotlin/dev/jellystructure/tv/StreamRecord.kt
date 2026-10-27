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
    /** DEB-2205 (FR-3) — per video codec family, the decode this device has shown it does NOT reach (see [DecodeFailure]). */
    val decodeFailed: Map<String, DecodeFailure> = emptyMap(),
)

/**
 * DEB-2205 (FR-3) — a decode that failed: the **video** bitrate of the direct-play stream whose picture dropped frames or froze
 * with buffer in hand, and when (epoch seconds). One per codec family ([DECODE_FAMILIES]). The stored entry is never dropped by time, but negotiation stops
 * reading it once [at] is [DECODE_RETRY_AFTER_SEC] old ([decodeFailedBps] with `now`), so the family gets one more try; a new failure
 * restarts that window. A file of that family above [DECODE_FAILED_HEADROOM] × [bps] goes to the ladder; a clean direct
 * play of that family at a higher bitrate lifts it ([liftDecodeFailed]).
 */
data class DecodeFailure(val bps: Long, val at: Long)

/** DEB-2205 (FR-3) — the video codec families a decode failure is kept for. */
internal val DECODE_FAMILIES = listOf("hevc", "h264", "av1", "vp9")
/** DEB-2205 (FR-3) — a file of the family above this share of the bitrate that failed goes to the ladder (JellyfinClient's 0.9 margin). */
internal const val DECODE_FAILED_HEADROOM = 0.9
/** DEB-2205 (FR-3, weekly retry) — a learned ceiling this old (seconds) is no longer a condition of negotiation; the stored entry stays. */
internal const val DECODE_RETRY_AFTER_SEC = 7 * 86_400L
/** DEB-2205 (FR-3) — a direct play this long, with no stop and almost no dropped frame, shows the decoder handles it. */
internal const val DECODE_LIFT_HOLD_SEC = 600L
/** DEB-2205 (FR-3) — …when under this share of its rendered frames were dropped (0.1 %). */
internal const val DECODE_LIFT_MAX_DROPPED = 0.001

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

internal fun fresh(at: Long?, now: Long, maxAge: Long): Boolean = at != null && now - at in 0..maxAge

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

/**
 * DEB-2205 (FR-5.2, FR-5.3) — a freeze recovery's restream negotiates under the rung that froze ([belowBandwidthBps], the frozen
 * variant's `BANDWIDTH`), whatever the device's take has grown to since: the take is read after the QoE post that precedes the
 * restream, and a stream that carried 30 Mbps for a minute can lift it past the file's own bitrate, which would let Jellyfin
 * answer with a direct play of the source — above the rung that froze. Null when the restream is not a recovery.
 */
internal fun recoveryNegotiationCap(cap: Long?, belowBandwidthBps: Long?): Long? =
    if (belowBandwidthBps == null || belowBandwidthBps <= 1) cap else minOf(cap ?: Long.MAX_VALUE, belowBandwidthBps - 1)

/**
 * 309 (FR-309-15, found live 2026-10-09 on Stue TV) — what a direct play proves the device takes: the file's whole bitrate
 * (size × 8 / length, +2 %), the figure Jellyfin weighs against `MaxStreamingBitrate`. The video bitrate plus "a little
 * audio" (×1.05 + 256 kbps) fell short of it for a remux with TrueHD — 54.5 Mbps proven for a 51.6 Mbps picture with a
 * 4.3 Mbps track — so the next start of the same film, after standby, was capped below it and transcoded to 1080p H.264.
 * Without a size or a length, the old estimate.
 */
internal fun directPlayStreamBps(fileSizeBytes: Long?, durationMs: Long?, videoBps: Long?): Long? {
    if (fileSizeBytes != null && fileSizeBytes > 0 && durationMs != null && durationMs > 0)
        return (fileSizeBytes * 8_000.0 / durationMs * 1.02).toLong()
    return videoBps?.takeIf { it > 0 }?.let { (it * 1.05).toLong() + 256_000L }
}

/**
 * DEB-2205 (FR-4) — what a ladder started in place of a direct play may start on. The record's take holds the direct play's own
 * proof (the file's whole bitrate, held for two minutes), so by itself it would start the ladder at its top rung; a hand-over starts
 * at the best rung within what the player's path last measured × 0.7 ([throughputBudget]), never above the take.
 */
internal fun ladderTakeFor(leavingDirectPlay: Boolean, take: Long?, measuredBps: Long?): Long? =
    if (!leavingDirectPlay) take else listOfNotNull(take, throughputBudget(measuredBps)).minOrNull()

/** DEB-2205 (FR-3) — the video codec family ([DECODE_FAMILIES]) a codec name belongs to (`h265` → `hevc`, `avc` → `h264`); null for any other. */
internal fun videoFamilyOf(codec: String?): String? = when (codec?.lowercase()?.trim()) {
    "hevc", "h265", "h.265", "hvc1", "hev1" -> "hevc"
    "h264", "avc", "avc1", "h.264" -> "h264"
    "av1", "av01" -> "av1"
    "vp9", "vp09" -> "vp9"
    else -> null
}

/** DEB-2205 (FR-3) — family → the video bits/s its decode failed at, the form [dev.jellystructure.auth.deviceProfile] takes. */
internal fun decodeFailedBps(record: StreamRecord?): Map<String, Long> =
    record?.decodeFailed?.mapValues { it.value.bps }?.filterValues { it > 0 } ?: emptyMap()

/**
 * DEB-2205 (FR-3, weekly retry) — what negotiation reads: [decodeFailedBps] without the families whose failure is
 * [DECODE_RETRY_AFTER_SEC] old or more at [now] (epoch seconds). The admin's view keeps the date-blind form.
 */
internal fun decodeFailedBps(record: StreamRecord?, now: Long): Map<String, Long> =
    record?.decodeFailed?.filterValues { now - it.at < DECODE_RETRY_AFTER_SEC }?.mapValues { it.value.bps }?.filterValues { it > 0 } ?: emptyMap()

/**
 * DEB-2205 (FR-3) — [record] with a decode failure of [family] at [videoBps] noted. A device that has failed lower already keeps
 * the lower figure (a failure at a higher bitrate says nothing new about where the decoder gives out); [at] is the newest failure.
 */
internal fun noteDecodeFailed(record: StreamRecord, family: String, videoBps: Long, now: Long): StreamRecord {
    if (family !in DECODE_FAMILIES || videoBps <= 0) return record
    val was = record.decodeFailed[family]
    val bps = if (was != null) minOf(was.bps, videoBps) else videoBps
    return record.copy(decodeFailed = record.decodeFailed + (family to DecodeFailure(bps, now)))
}

/**
 * DEB-2205 (FR-3) — a clean direct play of [family] at [playedVideoBps] lifts that family's ceiling (sets it to null) when it
 * played at a bitrate above the one that failed. [record] unchanged when there is nothing to lift.
 */
internal fun liftDecodeFailed(record: StreamRecord, family: String, playedVideoBps: Long): StreamRecord {
    val failed = record.decodeFailed[family] ?: return record
    return if (playedVideoBps > failed.bps) record.copy(decodeFailed = record.decodeFailed - family) else record
}

/**
 * DEB-2205 (FR-3) — a direct play clean enough to lift a ceiling: held [heldSec] ≥ 10 min on the same stream, no stop of any kind
 * ([stops] counts every cause, [frozenCount] the frozen kind), and under 0.1 % of [renderedFrames] dropped (a report with no
 * rendered count — an older app — never lifts).
 */
internal fun cleanDirectPlay(heldSec: Long, droppedFrames: Long, renderedFrames: Long, stops: Int, frozenCount: Int): Boolean =
    heldSec >= DECODE_LIFT_HOLD_SEC && stops == 0 && frozenCount == 0 && renderedFrames > 0 &&
        droppedFrames.toDouble() / renderedFrames < DECODE_LIFT_MAX_DROPPED

/** DEB-2205 (FR-3) — the three parallel comma-separated lists the record keeps its failures in (families, bits/s, epoch seconds); null when none. */
internal fun encodeDecodeFailed(failed: Map<String, DecodeFailure>): Triple<String?, String?, String?> {
    val entries = DECODE_FAMILIES.mapNotNull { f -> failed[f]?.let { f to it } }
    if (entries.isEmpty()) return Triple(null, null, null)
    return Triple(entries.joinToString(",") { it.second.bps.toString() }, entries.joinToString(",") { it.first }, entries.joinToString(",") { it.second.at.toString() })
}

internal fun parseDecodeFailed(bps: String?, codec: String?, at: String?): Map<String, DecodeFailure> {
    if (bps == null || codec == null || at == null) return emptyMap()
    val b = bps.split(',')
    val c = codec.split(',')
    val a = at.split(',')
    if (b.size != c.size || a.size != c.size) return emptyMap()
    return c.indices.mapNotNull { i ->
        val f = c[i].takeIf { it in DECODE_FAMILIES } ?: return@mapNotNull null
        val bb = b[i].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        f to DecodeFailure(bb, a[i].toLongOrNull() ?: 0L)
    }.toMap()
}

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
    // DEB-2508 — a hold that began before the record's stall is a proof older than that stall (a hand-over writes the stall on the new
    // play's first report, while the hold is still the previous play's on the same file): it neither proves nor clears. Hold from here.
    val stalledAt = rec.stalledAt
    if (stalledAt != null && hold.sinceSec < stalledAt) return rec to hold.copy(sinceSec = now)
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
        val (failedBps, failedCodec, failedAt) = encodeDecodeFailed(r.decodeFailed)
        queries.upsert(deviceId, r.provenBps, r.provenAt, r.measuredBps, r.measuredAt, r.stalledBps, r.stalledAt, now, failedBps, failedCodec, failedAt)
    }

    fun all(): Map<String, StreamRecord> = queries.all().executeAsList().associate { it.device_id to it.toRecord() }
}

private fun Device_stream_record.toRecord() = StreamRecord(proven_bps, proven_at, measured_bps, measured_at, stalled_bps, stalled_at,
    parseDecodeFailed(decode_failed_bps, decode_failed_codec, decode_failed_at))
