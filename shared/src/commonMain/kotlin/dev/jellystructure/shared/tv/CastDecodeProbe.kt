package dev.jellystructure.shared.tv

/**
 * R351 (FR-R351-1–4) — what a Chromecast receiver declares about H.264, bitrate and audio channels, from the device's
 * own answers. Pure: the receiver passes in how to ask (`canDisplayType`), so the choice is testable without a device.
 *
 * Before this the receiver declared H.264 at 1920 × 1080 with no level (the server then declares High 5.1), no
 * bitrate ceiling and six channels without asking. A Google Nest Hub (a 720p-class decoder) was handed a 1080p H.264
 * copy, Shaka's own `canDisplayType` check rejected the only variant, and every film cast failed with 4032.
 */
object CastDecodeProbe {
    /** One H.264 High rung: the largest size it decodes at [level] (Jellyfin's ×10 encoding), and its codec string. */
    data class Rung(val width: Int, val height: Int, val level: Int, val codecs: String)

    /** Largest first; each size is asked at its higher level first so a 60 fps source at that size stays a copy. */
    val H264_LADDER: List<Rung> = listOf(
        Rung(3840, 2160, 51, "avc1.640033"),
        Rung(1920, 1080, 42, "avc1.64002A"),
        Rung(1920, 1080, 41, "avc1.640029"),
        Rung(1280, 720, 41, "avc1.640029"),
        Rung(1280, 720, 31, "avc1.64001F"),
        Rung(854, 480, 30, "avc1.64001E"),
    )

    /** FR-R351-3 — asked from the top; passing the first means "no ceiling" (today's behaviour). */
    val BITRATES_BPS: List<Int> = listOf(120, 60, 40, 25, 20, 15, 10, 8).map { it * 1_000_000 }

    /** The frame rate every probe is asked at. */
    const val PROBE_FPS = 30

    /** What the receiver declares. width/height 1920 × 1080 and level 0 = the old declaration (nothing answered). */
    data class Answer(
        val maxWidth: Int,
        val maxHeight: Int,
        /** 0 = unknown: the server falls back to its own default declaration. */
        val maxLevel: Int,
        /** 0 = no ceiling. */
        val maxBitrate: Int,
        val maxAudioChannels: Int,
        val rung: Rung?,
    )

    /**
     * [h264] asks one rung (size, level, [PROBE_FPS]). [bitrate] asks the chosen rung at a bitrate; null where the
     * platform cannot be asked about bitrate. [sixChannels] asks AAC with six channels; null where it cannot be asked.
     */
    fun decide(h264: (Rung) -> Boolean, bitrate: ((Rung, Int) -> Boolean)?, sixChannels: (() -> Boolean)?): Answer {
        val rung = H264_LADDER.firstOrNull { runCatching { h264(it) }.getOrDefault(false) }
        val ceiling = if (rung == null || bitrate == null) 0 else {
            val passes = BITRATES_BPS.firstOrNull { bps -> runCatching { bitrate(rung, bps) }.getOrDefault(false) }
            // The top passing = no ceiling. Nothing passing at all is an answer we don't trust: no ceiling either.
            if (passes == null || passes == BITRATES_BPS.first()) 0 else passes
        }
        val channels = if (sixChannels != null && runCatching { sixChannels() }.getOrDefault(true) == false) 2 else 6
        return Answer(
            maxWidth = rung?.width ?: 1920,
            maxHeight = rung?.height ?: 1080,
            maxLevel = rung?.level ?: 0,
            maxBitrate = ceiling,
            maxAudioChannels = channels,
            rung = rung,
        )
    }

    /**
     * FR-R351-2 — the extended MIME type Shaka builds from an HLS variant and hands to `cast.__platform__.canDisplayType`
     * on a Cast device: `video/mp4; codecs="avc1.640029"; width=1280; height=720; framerate=30; bitrate=20000000`.
     */
    fun extendedType(
        mime: String,
        codecs: String,
        width: Int? = null,
        height: Int? = null,
        framerate: Int? = null,
        bitrate: Int? = null,
        channels: Int? = null,
    ): String = buildString {
        append(mime).append("; codecs=\"").append(codecs).append('"')
        width?.let { append("; width=").append(it) }
        height?.let { append("; height=").append(it) }
        framerate?.let { append("; framerate=").append(it) }
        bitrate?.let { append("; bitrate=").append(it) }
        channels?.let { append("; channels=").append(it) }
    }

    /**
     * FR-R351-5 — the parts of a stream URL worth a log line: what Jellyfin was asked to make. Never a token: only the
     * named parameters are kept (no `ApiKey`, no token).
     */
    val LOGGED_PARAMS: List<String> = listOf(
        "VideoCodec", "AudioCodec", "MaxWidth", "MaxHeight", "VideoBitrate", "AudioChannels", "SegmentContainer",
        "TranscodeReasons", "h264-level", "Static",
    )

    fun streamSummary(url: String): String {
        val query = url.substringAfter('?', "")
        val params = query.split('&').mapNotNull { p ->
            val k = p.substringBefore('=')
            if (LOGGED_PARAMS.none { it.equals(k, ignoreCase = true) }) null else "$k=${p.substringAfter('=', "")}"
        }
        val path = url.substringBefore('?').substringAfterLast('/')
        return (listOf(path) + params).joinToString(" ")
    }
}
