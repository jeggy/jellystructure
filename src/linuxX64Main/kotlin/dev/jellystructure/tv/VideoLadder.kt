package dev.jellystructure.tv

import kotlin.math.roundToLong

/**
 * 308 — the picture adapts to the connection, and never stops to buffer.
 *
 * A transcode used to be one rendition at one bitrate, and Jellyfin set that bitrate to the SOURCE's (meidam's 4K DV7
 * REMUX became a 1080p H.264 stream at `-b:v 80889815` over a path his phone had measured at ~19 Mbps; it advanced at
 * 0.29× realtime). Jellyfin's own adaptive streaming cannot help (Build notes): it is off for any request from the
 * local network — and every request through this server is one — and where it is on, its "variants" are the same
 * job 2–4 Mbps apart under one play session.
 *
 * So the composed master (R291's `AudioRenditions`) lists a **ladder**: the best variant this device decodes, then the
 * rungs of [LADDER] beneath it. Each variant is Jellyfin's own transcode URL with its `VideoBitrate`, `MaxWidth` /
 * `MaxHeight` and its **own `PlaySessionId`** (Jellyfin keys a job's output on media · user agent · device · play
 * session, so two variants under one session would share — and serve — each other's segments). Jellyfin starts a
 * variant's ffmpeg only when a segment of it is asked for, at that segment (`-ss` to the segment's start, the same
 * path a seek takes), and kills a job nobody has read for 60 s; so only the variant the player reads encodes, plus
 * briefly the one it switched away from. The player chooses between them on its own, continuously (FR-308-2).
 *
 * Every number the decisions use is one this device measured itself (owner, 2026-10-05: *"whatever is between the
 * server and the client doesn't matter … only the real live test on the client"*): no address, no inside or outside,
 * no setting, no limit.
 */

/** One rung below the top: a picture height and a video bitrate (bits/s). */
internal data class LadderRung(val height: Int, val videoBps: Long)

/** 308 (FR-308-1) — the one ladder table. The top variant is the transcode Jellyfin negotiated, at [topVideoBps]. */
internal val LADDER: List<LadderRung> = listOf(
    LadderRung(1080, 12_000_000L),
    LadderRung(1080, 8_000_000L),
    LadderRung(720, 4_000_000L),
    LadderRung(480, 1_500_000L),
)

/** The top variant never asks for more than this (a 1080p H.264 at 80 Mbps looks like one at 20; it only costs). */
internal const val LADDER_TOP_CAP_BPS = 40_000_000L

/** A rung must sit clearly below the one above it to be worth a variant (and a job) of its own. */
private const val RUNG_GAP = 0.8

/**
 * 308 (FR-308-3/-4) — the share of the measured throughput a stream may use: the same headroom Media3's adaptive
 * selection keeps (its `bandwidthFraction`), so the first guess and the player's own choices agree.
 */
internal const val THROUGHPUT_HEADROOM = 0.7

/** How many of the device's latest HLS measurements count, and how old one may be. */
private const val MEASUREMENTS = 3
private const val MEASUREMENT_MAX_AGE_SEC = 30L * 86_400L

/** The variant planned for one rung: its Jellyfin URL, its own play session, its picture height and bitrate. */
internal data class LadderVariant(val url: String, val playSessionId: String, val height: Int, val videoBps: Long)

/**
 * The reasons Jellyfin gives for re-encoding the **picture** (`TranscodeReasons` in its URL). A transcode for the
 * audio or the container alone copies the video — its segments follow the source's keyframes, and a lower variant
 * would be a different encode cut at different boundaries — so it keeps today's single stream. A burned-in subtitle
 * re-encodes the picture too.
 */
private val VIDEO_REASONS = setOf(
    "ContainerBitrateExceedsLimit", "VideoCodecNotSupported", "VideoProfileNotSupported", "VideoLevelNotSupported",
    "VideoResolutionNotSupported", "VideoBitDepthNotSupported", "VideoFramerateNotSupported", "RefFramesNotSupported",
    "AnamorphicVideoNotSupported", "InterlacedVideoNotSupported", "VideoBitrateNotSupported", "UnknownVideoStreamInfo",
    "VideoRangeTypeNotSupported", "VideoCodecTagNotSupported", "SubtitleCodecNotSupported",
)

/** 308 (FR-308-1) — does this Jellyfin transcode URL re-encode the picture (so a ladder of encodes is possible)? */
internal fun reencodesVideo(transcodingUrl: String): Boolean {
    val reasons = queryParam(transcodingUrl, "TranscodeReasons")?.replace("%2C", ",", ignoreCase = true)?.split(',')?.map { it.trim() }.orEmpty()
    if (reasons.any { it in VIDEO_REASONS }) return true
    return queryParam(transcodingUrl, "SubtitleMethod").equals("Encode", ignoreCase = true)
}

/**
 * 308 (FR-308-1) — the top variant's video bitrate: the source's own (Jellyfin never encodes above it), else what
 * Jellyfin negotiated, never above [LADDER_TOP_CAP_BPS] nor above the device's decode ceiling × 0.9 (Phase 177's
 * margin). Null when nothing is known — then there is no ladder.
 */
internal fun topVideoBps(negotiatedBps: Long?, sourceBps: Long?, ceilingBps: Long?): Long? {
    val base = sourceBps?.takeIf { it > 0 } ?: negotiatedBps?.takeIf { it > 0 } ?: return null
    var top = minOf(base, LADDER_TOP_CAP_BPS)
    ceilingBps?.takeIf { it > 0 }?.let { top = minOf(top, (it * 0.9).roundToLong()) }
    return top
}

/** 308 (FR-308-1) — the rungs under a top of [topBps] at [topHeight]: lower, no taller, and decodable. */
internal fun lowerRungs(topBps: Long, topHeight: Int, ceilingBps: Long?): List<LadderRung> =
    LADDER.filter { r ->
        r.videoBps < topBps * RUNG_GAP && r.height <= topHeight && (ceilingBps == null || ceilingBps <= 0 || r.videoBps <= ceilingBps * 0.9)
    }.fold(mutableListOf()) { acc, r -> if (acc.isEmpty() || r.videoBps < acc.last().videoBps * RUNG_GAP) acc.add(r); acc }

/** One query parameter of [url] (case-insensitive name), or null. */
internal fun queryParam(url: String, name: String): String? =
    url.substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=').equals(name, ignoreCase = true) }
        ?.substringAfter('=', "")

/** [url] with each of [params] set (replacing a parameter of the same name, case-insensitively, or appended). */
internal fun withQuery(url: String, params: Map<String, String>): String {
    val path = url.substringBefore('?')
    val parts = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.toMutableList()
    for ((k, v) in params) {
        val i = parts.indexOfFirst { it.substringBefore('=').equals(k, ignoreCase = true) }
        if (i >= 0) parts[i] = "$k=$v" else parts.add("$k=$v")
    }
    return if (parts.isEmpty()) path else path + "?" + parts.joinToString("&")
}

/**
 * 308 (FR-308-1) — one variant's Jellyfin URL: the negotiated [template] with its own bitrate, play session and — for
 * a rung below the top — its picture box (16:9 around [LadderRung.height]; Jellyfin keeps the aspect inside it).
 */
internal fun variantUrl(template: String, playSessionId: String, videoBps: Long, height: Int?): String =
    withQuery(template, buildMap {
        put("VideoBitrate", videoBps.toString())
        put("PlaySessionId", playSessionId)
        if (height != null) {
            put("MaxHeight", height.toString())
            put("MaxWidth", ((height * 16 + 8) / 9).let { it + (it and 1) }.toString())
        }
    })

/** The play session of a ladder's [index]th variant (0 = the top) under Jellyfin's own [jellyfinPlaySessionId]. */
internal fun variantSession(jellyfinPlaySessionId: String, index: Int): String = "${jellyfinPlaySessionId}v$index"

/** One QoE row as the throughput rule reads it. */
internal data class ThroughputSample(val bandwidthBps: Long?, val directPlay: Boolean, val updatedAtSec: Long)

/**
 * 308 (FR-308-3) — what this device has measured the path to carry: the median of its latest [MEASUREMENTS] bandwidth
 * estimates from **HLS** plays (R216's `bandwidth_estimate_bps`), at most 30 days old; null with none. A direct play's
 * estimate is left out: the player reads a file progressively and its meter reports the reading pace, not the path
 * (the household's LAN direct plays report 4–8 Mbps on a link its HLS plays measure at 200+).
 */
internal fun measuredThroughput(samples: List<ThroughputSample>, nowSec: Long): Long? {
    val recent = samples.asSequence()
        .filter { !it.directPlay && (it.bandwidthBps ?: 0L) > 0L && nowSec - it.updatedAtSec <= MEASUREMENT_MAX_AGE_SEC }
        .sortedByDescending { it.updatedAtSec }
        .take(MEASUREMENTS)
        .map { it.bandwidthBps!! }
        .sorted()
        .toList()
    if (recent.isEmpty()) return null
    return if (recent.size % 2 == 1) recent[recent.size / 2] else (recent[recent.size / 2 - 1] + recent[recent.size / 2]) / 2
}

/** 308 (FR-308-3/-4) — the bitrate a stream may use on that path: [THROUGHPUT_HEADROOM] of [measuredBps]. */
internal fun throughputBudget(measuredBps: Long?): Long? = measuredBps?.takeIf { it > 0 }?.let { (it * THROUGHPUT_HEADROOM).roundToLong() }

/**
 * 308 (FR-308-3) — the order the variants are listed in, as indexes into [bandwidths] (each variant's `BANDWIDTH`):
 * the first guess first — the best one inside [budget], else the lowest — then the rest from the top down. A player
 * that starts on the first listed variant (Safari/AVPlayer, hls.js) starts on the guess; one that starts from its own
 * estimate (Media3, Shaka) is seeded with the measurement itself. With no budget the top stays first, as today.
 */
internal fun startOrder(bandwidths: List<Long>, budget: Long?): List<Int> {
    val byBandwidth = bandwidths.indices.sortedByDescending { bandwidths[it] }
    if (budget == null || byBandwidth.isEmpty()) return byBandwidth
    val first = byBandwidth.firstOrNull { bandwidths[it] <= budget } ?: byBandwidth.last()
    return listOf(first) + byBandwidth.filter { it != first }
}

/** A variant line of a Jellyfin master: its `#EXT-X-STREAM-INF` tag and its URI as written. */
internal data class MasterVariant(val streamInf: String, val uri: String) {
    val bandwidth: Long get() = Regex("(?:^|[:,])BANDWIDTH=(\\d+)").find(streamInf)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val height: Int? get() = Regex("RESOLUTION=\\d+x(\\d+)").find(streamInf)?.groupValues?.get(1)?.toIntOrNull()
}

/** The first variant of a Jellyfin master (Jellyfin may add a second line for the same stream with another level). */
internal fun firstVariant(master: String): MasterVariant? {
    val lines = master.lines()
    val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
    if (i < 0) return null
    val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return null
    return MasterVariant(lines[i], uri.trim())
}

/** 308 (FR-308-5) — the variant a player last reported playing: its `BANDWIDTH`, picture height and switch counts. */
data class VariantNow(val bandwidthBps: Long, val height: Int?, val stepsDown: Int, val stepsUp: Int)
