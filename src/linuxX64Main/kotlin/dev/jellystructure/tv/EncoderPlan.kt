package dev.jellystructure.tv

import kotlin.math.roundToLong

/**
 * Phase 313 — our own encoder makes every quality at once: the pure part (what one job makes, the ffmpeg command,
 * the playlists and the master, the encode budget, and when Jellyfin does it instead). The process side is
 * [EncoderJobs]; the registry and the wiring are [Encoder].
 *
 * One job per play reads and decodes the file once and writes every rung as 2 s segments that start at the same
 * frames in every rung (keyframes forced at 2k s, the HLS muxer cutting at the same cumulative boundaries), so a
 * player switches without waiting for anything. Measured 2026-10-08 on the P4000 (313a, spec build notes): a 4-rung
 * H.264 SDR ladder from a 4K HDR10 REMUX, tone-mapped once, first segment in 2.0 s at 4.7× realtime; a 4-rung HEVC
 * HDR ladder with a 2160p top, 2.3 s at 2.6×; two of those at once, 1.3× each.
 */

/** 313 — the two codec families a play can get; a master never mixes them (FR-313-4). */
enum class EncoderCodec { H264, HEVC }

/** 313 — how segments are muxed: fMP4 (CMAF) everywhere, MPEG-TS for the Cast receiver until fMP4 is verified there. */
enum class EncoderMux { FMP4, TS }

/**
 * Phase 313 — the segment format per device. fMP4 only for Apple's and the browsers' players (Safari needs it for HEVC;
 * hls.js reads either); everything else — Media3 on Android phones and TVs, the Cast receiver — gets MPEG-TS. Found on the
 * Pixel 2026-10-08: an all-fMP4 ladder (fMP4 video + fMP4 audio renditions) left Media3 BUFFERING at 0 with 48 s
 * buffered and no decoder ever created, while every stream that plays on these devices (Jellyfin's, R291's) is TS.
 */
fun encoderMuxFor(deviceKind: String, platform: String?): EncoderMux =
    if (deviceKind != "cast" && platform?.lowercase() in setOf("mac", "ios", "web")) EncoderMux.FMP4 else EncoderMux.TS

/** One output rung: its picture box (the frame is fitted inside, aspect kept), its size, and its video bitrate. */
data class EncoderRung(val boxHeight: Int, val width: Int, val height: Int, val videoBps: Long)

/** The picture boxes a rung's height names (16:9). */
internal fun boxWidth(boxHeight: Int): Int = when {
    boxHeight >= 2160 -> 3840
    boxHeight >= 1440 -> 2560
    boxHeight >= 1080 -> 1920
    boxHeight >= 720 -> 1280
    else -> 854
}

/** The source's frame fitted inside [boxHeight]'s box, never upscaled, even dimensions. */
internal fun fitInBox(srcW: Int, srcH: Int, boxHeight: Int): Pair<Int, Int> {
    val bw = boxWidth(boxHeight).toDouble(); val bh = boxHeight.toDouble()
    val scale = minOf(bw / srcW, bh / srcH, 1.0)
    fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
    return even(srcW * scale) to even(srcH * scale)
}

/** FR-313-3 — the top rung is capped by its own output: what a picture of that size needs, not the source's bitrate. */
internal fun topCapBps(codec: EncoderCodec, boxHeight: Int): Long = when (codec) {
    EncoderCodec.HEVC -> when {
        boxHeight >= 2160 -> 20_000_000L
        boxHeight >= 1440 -> 12_000_000L
        boxHeight >= 1080 -> 8_000_000L
        boxHeight >= 720 -> 4_000_000L
        else -> 1_500_000L
    }
    EncoderCodec.H264 -> when {
        boxHeight >= 1080 -> 12_000_000L
        boxHeight >= 720 -> 6_000_000L
        else -> 2_000_000L
    }
}

/** The largest box a codec family's top rung may use: 2160p HEVC; H.264 stops at 1080p (a 4K H.264 rung is never made). */
internal fun maxBoxHeight(codec: EncoderCodec): Int = if (codec == EncoderCodec.HEVC) 2160 else 1080

/** At most this many rungs per play (FR-313-3); FR-313-8's budget may lower it. */
const val ENCODER_MAX_RUNGS = 4

/**
 * FR-313-3 — the rungs one play gets, top first: the top at the source's size (capped by the family's largest box),
 * its bitrate capped by its output size, the source's own bitrate and the device's decode ceiling × 0.9; then 308's
 * [LADDER] rungs below it that are clearly lower and no taller. At most [maxRungs].
 */
internal fun encoderRungs(
    codec: EncoderCodec, srcW: Int, srcH: Int, srcBps: Long?, ceilingBps: Long?, maxRungs: Int = ENCODER_MAX_RUNGS,
): List<EncoderRung> {
    // The largest box the family allows that the source fills in at least one dimension (3840×1600 fills 2160p's width).
    val topBox = listOf(2160, 1440, 1080, 720, 480).firstOrNull { it <= maxBoxHeight(codec) && (srcW >= boxWidth(it) || srcH >= it) } ?: 480
    val caps = listOfNotNull(topCapBps(codec, topBox), srcBps?.takeIf { it > 0 }, ceilingBps?.takeIf { it > 0 }?.let { (it * 0.9).roundToLong() })
    val topBps = caps.min()
    val (tw, th) = fitInBox(srcW, srcH, topBox)
    val out = mutableListOf(EncoderRung(topBox, tw, th, topBps))
    for (r in LADDER) {
        if (out.size >= maxRungs) break
        val last = out.last()
        // A lower rung is also capped by what its own size needs in this family (an HEVC 1080p rung never gets 12 Mbps).
        val bps = minOf(r.videoBps, topCapBps(codec, r.height))
        if (r.height > last.boxHeight || bps >= last.videoBps * 0.8) continue
        if (ceilingBps != null && ceilingBps > 0 && bps > ceilingBps * 0.9) continue
        val (w, h) = fitInBox(srcW, srcH, r.height)
        out += EncoderRung(r.height, w, h, bps)
    }
    return out
}

/**
 * FR-313-3/-8 — fewer rungs than planned, keeping the one a play starts on and its neighbours: the start rung, then the
 * one below it, then the one above, then further down. [rungs] is top first; [start] an index into it.
 */
internal fun trimRungs(rungs: List<EncoderRung>, start: Int, keep: Int): List<EncoderRung> {
    if (keep >= rungs.size) return rungs
    val s = start.coerceIn(0, rungs.lastIndex)
    val order = buildList { add(s); if (s + 1 <= rungs.lastIndex) add(s + 1); if (s - 1 >= 0) add(s - 1); for (i in s + 2..rungs.lastIndex) add(i); for (i in s - 2 downTo 0) add(i) }
    return order.take(keep.coerceAtLeast(1)).sorted().map { rungs[it] }
}

/** The rung a play starts on: the best one inside the bitrate budget (308/309's measured × 0.7), else the top. */
internal fun startRung(rungs: List<EncoderRung>, budgetBps: Long?): Int {
    if (budgetBps == null || budgetBps <= 0) return 0
    return rungs.indexOfFirst { it.videoBps <= budgetBps }.let { if (it < 0) rungs.lastIndex else it }
}

/** FR-313-7 — a rung's `BANDWIDTH` in the master (its peak: video × 1.5, plus the loudest audio rendition). */
internal fun rungBandwidth(r: EncoderRung, audioPeakBps: Long): Long = (r.videoBps * 1.5).roundToLong() + audioPeakBps

/**
 * 309 (FR-309-2) — where a play starts: the best rung whose advertised stream fits [takeBps] (what this device has
 * shown it can take, in the master's own units); with no record ([takeBps] null and [noRecord]), the 720p 4 Mbps rung —
 * the highest rung at or under [NO_RECORD_START_VIDEO_BPS] of video, never the top first; with neither (a player that
 * cannot climb, re-dev review item 2), the top.
 */
internal fun startRungFor(rungs: List<EncoderRung>, audioPeakBps: Long, takeBps: Long?, noRecord: Boolean): Int {
    if (rungs.isEmpty()) return 0
    if (takeBps != null && takeBps > 0) return rungs.indexOfFirst { rungBandwidth(it, audioPeakBps) <= takeBps }.let { if (it < 0) rungs.lastIndex else it }
    if (noRecord) return rungs.indexOfFirst { it.videoBps <= NO_RECORD_START_VIDEO_BPS }.let { if (it < 0) rungs.lastIndex else it }
    return 0
}

/** One audio rendition the job makes: its place among the file's own audio streams, codec and channels. */
data class EncoderAudio(val position: Int, val audioOrder: Int, val codec: String, val channels: Int, val language: String?, val label: String?, val default: Boolean)

/**
 * 313d (FR-313-6) — one text subtitle offered as a WebVTT rendition in the master (only to a player that takes its
 * subtitles from the manifest, `hls_subtitles`). [sourceUrl] is Jellyfin's own VTT conversion of that stream (embedded
 * or a sidecar), fetched once server-side and cached; it never reaches the player.
 */
data class EncoderSubtitle(val name: String, val language: String?, val forced: Boolean, val default: Boolean, val sourceUrl: String)

/** The source a job reads, from our own scan (no probe). [hdr] = PQ/HLG; [dolbyVisionProfile5] has no HDR10 base. */
data class EncoderSource(
    val path: String, val durationMs: Long, val videoCodec: String, val width: Int, val height: Int,
    val videoBps: Long?, val hdr: Boolean, val hlg: Boolean = false, val dolbyVisionProfile5: Boolean = false,
)

/** Everything one job makes (fixed for its life: FR-313-1 — a running ffmpeg cannot gain an output). */
data class EncoderPlan(
    val source: EncoderSource,
    val codec: EncoderCodec,
    val mux: EncoderMux,
    val rungs: List<EncoderRung>,
    val startRung: Int,
    val audio: List<EncoderAudio>,
    /** FR-313-6 — the image subtitle burned in (its place among the file's subtitle streams), or null. */
    val burnSubtitleOrder: Int? = null,
    /** CUDA device index (PCI bus order: 0 = P4000 on the household host); null = CPU (tests, never production). */
    val cudaDevice: Int? = 0,
    /** 313d (FR-313-6) — text subtitles as WebVTT renditions (not part of the job: served from Jellyfin's VTT). */
    val subtitles: List<EncoderSubtitle> = emptyList(),
) {
    /** HDR kept (HEVC rungs from an HDR source) vs tone-mapped once to SDR. */
    val keepsHdr: Boolean get() = codec == EncoderCodec.HEVC && source.hdr
    val tonemaps: Boolean get() = source.hdr && !keepsHdr
    val variantCount: Int get() = rungs.size + audio.size
}

/** 2 s segments (FR-313-7); segment k is [2k, 2k+2). */
const val ENCODER_SEGMENT_MS = 2_000L

internal fun encoderSegmentCount(durationMs: Long): Int = ((durationMs + ENCODER_SEGMENT_MS - 1) / ENCODER_SEGMENT_MS).toInt().coerceAtLeast(1)

/** The H.264 / HEVC level for a rung's box (also its CODECS string, FR-313's dev review item 9). */
internal fun levelOf(codec: EncoderCodec, boxHeight: Int): String = when (codec) {
    EncoderCodec.H264 -> when { boxHeight >= 1080 -> "4.1"; boxHeight >= 720 -> "3.1"; else -> "3.0" }
    // 2160p is 5.1: level 5.0 (Main tier) caps the peak at 25 Mbps, and a 20 Mbps top rung peaks at 30 (-maxrate 1.5×);
    // NVENC refuses the encode outright ("Invalid Level"), found casting to Stue TV 2026-10-09.
    EncoderCodec.HEVC -> when { boxHeight >= 2160 -> "5.1"; boxHeight >= 1440 -> "5.0"; boxHeight >= 1080 -> "4.1"; boxHeight >= 720 -> "3.1"; else -> "3.0" }
}

/** The exact `CODECS` value of a rung's video (Media3 hides a variant whose codec string no decoder supports). */
internal fun videoCodecString(codec: EncoderCodec, boxHeight: Int, tenBit: Boolean): String {
    val level = levelOf(codec, boxHeight)
    return when (codec) {
        EncoderCodec.H264 -> "avc1.6400" + when (level) { "4.1" -> "29"; "3.1" -> "1f"; else -> "1e" }
        // hvc1.<profile>.<compat>.L<level×30>.B0 — Main 10 = 2.4, Main = 1.6.
        EncoderCodec.HEVC -> (if (tenBit) "hvc1.2.4" else "hvc1.1.6") + ".L" + kotlin.math.round(level.toDouble() * 30).toInt() + ".B0"
    }
}

internal fun audioCodecString(codec: String): String = when (codec) { "ac3" -> "ac-3"; "eac3" -> "ec-3"; else -> "mp4a.40.2" }

internal fun audioBitrate(codec: String, channels: Int): Long = when {
    codec == "ac3" || codec == "eac3" -> if (channels > 2) 640_000L else 192_000L
    channels > 2 -> 384_000L
    else -> 192_000L
}

/**
 * FR-313-7 — the master: one `EXT-X-MEDIA` per audio rendition (every one has its own playlist — the video rungs carry
 * no audio), then one `EXT-X-STREAM-INF` per rung, the start rung first (309), every rung in one codec family.
 * Audio NAMEs are R291's `a{position} {label}` so a player maps a rendition back to the ticket's audio position.
 */
internal fun encoderMaster(plan: EncoderPlan, startMs: Long = 0): String {
    val out = StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(if (plan.mux == EncoderMux.FMP4) 7 else 4).append("\n#EXT-X-INDEPENDENT-SEGMENTS\n")
    // 313 (found live 2026-10-09) — a play that starts mid-film says where: AVPlayer (Mac, Safari, iPhone) loads from
    // here instead of reading segment 0 before its seek, so our encoder no longer starts a job at 0:00 first. Players
    // that seek explicitly (Media3, hls.js with a start position) land on the same place.
    if (startMs > 0) out.append("#EXT-X-START:TIME-OFFSET=").append(secs(startMs)).append(",PRECISE=YES\n")
    val audioPeak = plan.audio.maxOfOrNull { audioBitrate(it.codec, it.channels) } ?: 0L
    val audioCodec = plan.audio.firstOrNull { it.default }?.let { audioCodecString(it.codec) } ?: plan.audio.firstOrNull()?.let { audioCodecString(it.codec) }
    for (a in plan.audio) {
        out.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"").append(q("a${a.position} ${a.label ?: a.language ?: ""}".trim())).append('"')
        a.language?.takeIf { it.isNotBlank() }?.let { out.append(",LANGUAGE=\"").append(q(it)).append('"') }
        out.append(if (a.default) ",DEFAULT=YES,AUTOSELECT=YES" else ",DEFAULT=NO,AUTOSELECT=NO")
        if (a.channels > 0) out.append(",CHANNELS=\"").append(a.channels).append('"')
        out.append(",URI=\"a/").append(a.position).append("/main.m3u8\"\n")
    }
    for ((i, t) in plan.subtitles.withIndex()) {
        out.append("#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"subs\",NAME=\"").append(q(t.name.ifBlank { t.language ?: "Subtitles ${i + 1}" })).append('"')
        t.language?.takeIf { it.isNotBlank() }?.let { out.append(",LANGUAGE=\"").append(q(it)).append('"') }
        out.append(if (t.default) ",DEFAULT=YES,AUTOSELECT=YES" else ",DEFAULT=NO,AUTOSELECT=${if (t.forced) "YES" else "NO"}")
        if (t.forced) out.append(",FORCED=YES")
        out.append(",URI=\"t/").append(i).append("/main.m3u8\"\n")
    }
    val order = listOf(plan.startRung) + plan.rungs.indices.filter { it != plan.startRung }
    for (i in order) {
        val r = plan.rungs[i]
        val peak = rungBandwidth(r, audioPeak)
        val avg = r.videoBps + audioPeak
        val codecs = listOfNotNull(videoCodecString(plan.codec, r.boxHeight, plan.keepsHdr || plan.codec == EncoderCodec.HEVC && plan.source.hdr), audioCodec).joinToString(",")
        out.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(peak).append(",AVERAGE-BANDWIDTH=").append(avg)
            .append(",RESOLUTION=").append(r.width).append('x').append(r.height)
            .append(",CODECS=\"").append(codecs).append('"')
            .append(",VIDEO-RANGE=").append(if (plan.keepsHdr) (if (plan.source.hlg) "HLG" else "PQ") else "SDR")
        if (plan.audio.isNotEmpty()) out.append(",AUDIO=\"aud\"")
        if (plan.subtitles.isNotEmpty()) out.append(",SUBTITLES=\"subs\"")
        out.append(",CLOSED-CAPTIONS=NONE\n")
        out.append("v/").append(i).append("/main.m3u8\n")
    }
    return out.toString()
}

/** One variant's playlist: every segment of the whole file, VOD, so a player can seek anywhere (segments made on demand). */
internal fun encoderPlaylist(durationMs: Long, mux: EncoderMux): String {
    val count = encoderSegmentCount(durationMs)
    val ext = if (mux == EncoderMux.FMP4) "m4s" else "ts"
    val out = StringBuilder("#EXTM3U\n#EXT-X-VERSION:").append(if (mux == EncoderMux.FMP4) 7 else 3)
        .append("\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n")
    if (mux == EncoderMux.FMP4) out.append("#EXT-X-MAP:URI=\"init.mp4\"\n")
    for (k in 0 until count) {
        val len = if (k == count - 1) durationMs - k * ENCODER_SEGMENT_MS else ENCODER_SEGMENT_MS
        out.append("#EXTINF:").append(len / 1000).append('.').append((len % 1000).toString().padStart(3, '0')).append(",\n")
        out.append(k).append('.').append(ext).append('\n')
    }
    return out.append("#EXT-X-ENDLIST\n").toString()
}

/** 313d (FR-313-6) — a subtitle rendition's playlist: the whole film as one WebVTT segment (VOD). */
internal fun encoderSubtitlePlaylist(durationMs: Long): String {
    val secs = ((durationMs + 999) / 1000).coerceAtLeast(1)
    return "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:$secs\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n" +
        "#EXTINF:${durationMs / 1000}.${(durationMs % 1000).toString().padStart(3, '0')},\nsub.vtt\n#EXT-X-ENDLIST\n"
}

private fun q(s: String) = s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')
private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
private fun secs(ms: Long) = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"

/**
 * FR-313-2/-4/-5/-6/-7 — the ffmpeg command for one job from segment [startSegment]: small probe limits (our scan
 * already knows the file), one GPU decode, the tone-map once before the split (H.264 from HDR), every rung scaled from
 * that one decode, keyframes forced at every 2 s boundary of the file's own clock (`-copyts`, so a restart at a seek
 * makes segments that sit beside the earlier job's), every audio rendition from the same process, and the HLS muxer
 * writing `<dir>/<variant>/s<n>.<ext>`, n counted from [startSegment] (variants: the rungs, then the audio renditions).
 * [ffmpeg] is the binary (jellyfin-ffmpeg in production; any ffmpeg with libx264/libx265 for a CPU plan).
 */
internal fun encoderCommand(plan: EncoderPlan, startSegment: Int, dir: String, ffmpeg: String = "ffmpeg"): String {
    val startMs = startSegment * ENCODER_SEGMENT_MS
    val gpu = plan.cudaDevice
    val n = plan.rungs.size
    val tenBit = plan.keepsHdr
    val fmt = if (tenBit) "p010" else "nv12"
    val graph = StringBuilder()
    val src = "[0:v:0]"
    if (gpu != null) {
        val top = plan.rungs[0]
        if (plan.tonemaps) {
            // Measured (313a): scale to the top rung first, then tone-map once — cheaper than tone-mapping at 4K.
            graph.append(src).append("scale_cuda=w=${top.width}:h=${top.height}:format=p010,")
                .append("setparams=color_primaries=bt2020:color_trc=${if (plan.source.hlg) "arib-std-b67" else "smpte2084"}:colorspace=bt2020nc,")
                .append("tonemap_cuda=format=nv12:p=bt709:t=bt709:m=bt709:tonemap=bt2390:peak=100:desat=0")
        } else {
            graph.append(src).append("scale_cuda=w=${top.width}:h=${top.height}:format=$fmt")
        }
        if (plan.burnSubtitleOrder != null) {
            // FR-313-6 — the image subtitle composited on the GPU at the top rung's size, before the split (every rung
            // carries it). overlay_cuda takes 8-bit frames only, so a burn-in plan is always H.264 SDR (see [Encoder]).
            // Found live 2026-10-09 (Mac, a PGS subtitle): the overlay's output carries no colour tags, so the output's
            // `-color_* bt709` made ffmpeg 8 insert a software colour conversion after the split — "Impossible to convert
            // between … 'Parsed_split_5' and 'auto_scale_0' (src: cuda)". The tags are now set on the GPU frames
            // (`setparams`, metadata only). The subtitle chain is Jellyfin's own (`-canvas_size` before `-i`, a plain
            // `scale` first, `eof_action=pass:repeatlast=0`), measured on the same film: 2 s segments at ~8× realtime.
            graph.append("[base];[0:s:${plan.burnSubtitleOrder}]scale,scale=${top.width}:${top.height}:fast_bilinear,format=yuva420p,hwupload[sub];")
                .append("[base][sub]overlay_cuda=eof_action=pass:repeatlast=0,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709")
        }
        graph.append(",split=$n")
        for (i in 0 until n) graph.append("[t$i]")
        for (i in 1 until n) graph.append(";[t$i]scale_cuda=w=${plan.rungs[i].width}:h=${plan.rungs[i].height}[v$i]")
    } else {
        // CPU plan (tests): the same shape with software filters; a tone-map is approximated by a plain conversion.
        graph.append(src).append("scale=${plan.rungs[0].width}:${plan.rungs[0].height},format=${if (tenBit) "yuv420p10le" else "yuv420p"},split=$n")
        for (i in 0 until n) graph.append("[t$i]")
        for (i in 1 until n) graph.append(";[t$i]scale=${plan.rungs[i].width}:${plan.rungs[i].height}[v$i]")
    }
    val maps = StringBuilder().append(" -map \"[t0]\"")
    for (i in 1 until n) maps.append(" -map \"[v$i]\"")
    for (a in plan.audio) maps.append(" -map 0:a:").append(a.audioOrder)
    val enc = StringBuilder()
    val vEncoder = when {
        gpu != null && plan.codec == EncoderCodec.HEVC -> "hevc_nvenc"
        gpu != null -> "h264_nvenc"
        plan.codec == EncoderCodec.HEVC -> "libx265"
        else -> "libx264"
    }
    enc.append(" -c:v ").append(vEncoder)
    // p1, measured on the P4000 2026-10-08 (one 4K DV source, 30 s, decode + tone-map + 4 rungs): H.264 p4 1.1× → p1 8.8×,
    // HEVC Main 10 (2160p top) p4 2.3× → p1 4.6×. Pascal's NVENC can't carry four rungs at p4; Jellyfin uses p1 as well.
    if (gpu != null) enc.append(" -preset p1 -rc vbr -forced-idr 1 -no-scenecut 1")
    else enc.append(if (plan.codec == EncoderCodec.HEVC) " -preset ultrafast -x265-params log-level=error:scenecut=0:open-gop=0" else " -preset ultrafast -sc_threshold 0")
    // `t` in this expression counts from the job's own first frame, not the file's clock (`-copyts` doesn't change it;
    // found by EncoderAlignmentTest). Every job starts exactly on a 2 s boundary, so the job's 2 s grid is the file's.
    enc.append(" -g 240 -keyint_min 48 -force_key_frames ").append(shq("expr:gte(t,n_forced*2)"))
    if (plan.codec == EncoderCodec.HEVC) {
        enc.append(" -tag:v hvc1")
        if (gpu != null) enc.append(" -profile:v ").append(if (tenBit) "main10" else "main")
    } else if (gpu != null) enc.append(" -profile:v high")
    if (plan.keepsHdr) enc.append(" -color_primaries bt2020 -color_trc ").append(if (plan.source.hlg) "arib-std-b67" else "smpte2084").append(" -colorspace bt2020nc")
    else enc.append(" -color_primaries bt709 -color_trc bt709 -colorspace bt709")
    for ((i, r) in plan.rungs.withIndex()) {
        enc.append(" -b:v:$i ").append(r.videoBps).append(" -maxrate:v:$i ").append((r.videoBps * 1.5).roundToLong())
            .append(" -bufsize:v:$i ").append(r.videoBps * 2)
        if (gpu != null) enc.append(" -level:v:$i ").append(levelOf(plan.codec, r.boxHeight))
    }
    for ((j, a) in plan.audio.withIndex()) {
        val encoder = when (a.codec) { "ac3" -> "ac3"; "eac3" -> "eac3"; else -> "aac" }
        enc.append(" -c:a:$j ").append(encoder).append(" -b:a:$j ").append(audioBitrate(a.codec, a.channels)).append(" -ac:a:$j ").append(a.channels)
    }
    val varMap = (0 until n).joinToString(" ") { "v:$it" } + plan.audio.indices.joinToString("") { " a:$it" }
    val seg = if (plan.mux == EncoderMux.FMP4) "s%d.m4s" else "s%d.ts"
    val hw = if (gpu != null) "-init_hw_device cuda=gpu:$gpu -filter_hw_device gpu -hwaccel cuda -hwaccel_device gpu -hwaccel_output_format cuda " else ""
    return "$ffmpeg -nostdin -hide_banner -loglevel error -probesize 5M -analyzeduration 5M " + hw +
        // FR-313-6 — an image subtitle is drawn on a canvas the size of the picture (Jellyfin passes the same).
        (if (plan.burnSubtitleOrder != null && plan.source.width > 0 && plan.source.height > 0) "-canvas_size ${plan.source.width}x${plan.source.height} " else "") +
        "-ss ${secs(startMs)} -i ${shq(plan.source.path)} " +
        "-filter_complex ${shq(graph.toString())}" + maps + enc +
        " -sn -dn -map_metadata -1 -map_chapters -1 -copyts -avoid_negative_ts disabled -max_muxing_queue_size 4096" +
        " -f hls -hls_time 2 -hls_list_size 0 -hls_playlist_type vod -hls_flags temp_file" +
        (if (plan.mux == EncoderMux.FMP4) " -hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4" else " -hls_segment_type mpegts -max_delay 5000000") +
        // Files are numbered from 0 in every job: `-start_number K` makes the HLS muxer count its cut targets from K, so
        // a restarted job's first segment came out 2(K+1) s long (found by EncoderAlignmentTest). [EncoderJobs] maps
        // segment k to file `s<k - start>`.
        " -start_number 0 -var_stream_map ${shq(varMap)}" +
        " -hls_segment_filename ${shq("$dir/%v/$seg")} -progress pipe:1 -nostats -y ${shq("$dir/%v/p.m3u8")}"
}

// ── FR-313-8 — the encode budget ─────────────────────────────────────────────────────────────────────────────

/** One card as the budget sees it: its CUDA index (PCI bus order), its NVENC session cap (null = none), live sessions. */
data class EncoderCard(val index: Int, val name: String, val sessionCap: Int?, val liveSessions: Int)

/**
 * What one job costs a card, as a share of what the card can carry while every job stays above realtime with
 * headroom. From 313a on the P4000: a 4K HEVC ladder 2.6× alone (two at once 1.3× each ⇒ 0.5 each), a 1080p H.264
 * ladder 4.7× (≈ 0.25 each). A Turing card does more per session; the same shares keep it conservative.
 */
internal fun jobLoad(codec: EncoderCodec, topBoxHeight: Int, rungs: Int): Double {
    val base = when {
        codec == EncoderCodec.HEVC && topBoxHeight >= 2160 -> 0.5
        codec == EncoderCodec.HEVC -> 0.3
        else -> 0.25
    }
    return base * (rungs.coerceAtLeast(1) / ENCODER_MAX_RUNGS.toDouble()).coerceAtLeast(0.5)
}

/** A placement: which card, and how many rungs the job may make there. */
data class EncoderPlacement(val card: Int, val rungs: Int)

/**
 * FR-313-8 — where a new job goes: the first card (in [cards]' order: the P4000 first) whose load and sessions take it
 * with [rungs] rungs; else the same with fewer rungs (down to 1); else null (fall back to Jellyfin). [load] is each
 * card's share already taken by our running jobs; a card's [EncoderCard.sessionCap] is lowered by [reservePerCard]
 * (Jellyfin's own jobs and trickplay share the consumer card).
 */
internal fun placeJob(cards: List<EncoderCard>, load: Map<Int, Double>, codec: EncoderCodec, topBoxHeight: Int, rungs: Int, reservePerCard: Int = 2): EncoderPlacement? {
    for (want in rungs downTo 1) {
        for (c in cards) {
            val used = load[c.index] ?: 0.0
            val fitsLoad = used + jobLoad(codec, topBoxHeight, want) <= 1.0 + 1e-9
            val fitsSessions = c.sessionCap == null || c.liveSessions + want <= c.sessionCap - reservePerCard
            if (fitsLoad && fitsSessions) return EncoderPlacement(c.index, want)
        }
    }
    return null
}

/** `nvidia-smi --query-gpu=index,name,encoder.stats.sessionCount --format=csv,noheader` → cards (consumer GeForce: 8 sessions). */
internal fun parseNvidiaSmi(out: String): List<EncoderCard> = out.lines().mapNotNull { line ->
    val p = line.split(',').map { it.trim() }
    if (p.size < 3) return@mapNotNull null
    val idx = p[0].toIntOrNull() ?: return@mapNotNull null
    // A consumer GeForce card caps NVENC sessions (8 on current drivers); a Quadro has no cap.
    val cap = if (p[1].contains("GeForce", ignoreCase = true)) 8 else null
    EncoderCard(idx, p[1], cap, p[2].toIntOrNull() ?: 0)
}

// ── FR-313-12 — when Jellyfin does it instead ─────────────────────────────────────────────────────────────────

/** Video codecs NVDEC on the household cards (Pascal + Turing) decodes; anything else is Jellyfin's (VC-1, MPEG-2, AV1…). */
internal val GPU_DECODABLE = setOf("h264", "hevc", "h265", "vp9", "mpeg4")

/**
 * FR-313-12 — the rule-based choice, made once per play before anything starts: null when our encoder serves it,
 * otherwise the reason Jellyfin does (logged as `encoder: fallback to Jellyfin — <reason>`).
 */
internal fun encoderDecision(
    enabled: Boolean, ffmpegReady: Boolean, cards: List<EncoderCard>, source: EncoderSource?, isLiveOrAudio: Boolean,
    /** The playing device's kind. A Cast Connect load into the TV app plays as the TV (`tv`), not as a receiver. */
    deviceKind: String = "tv",
    /** [EncoderConfig.castReceivers] — the dev-only switch that lets a cast receiver through while its stall is diagnosed. */
    castReceivers: Boolean = false,
): String? = when {
    !enabled -> "encoder off"
    isLiveOrAudio -> "live TV or audio"
    // 2026-10-09 — a Chromecast cast served by our encoder stalled 208 times in 62 min (first frame 15.9 s); until that
    // is diagnosed the web receiver (`cast`) is served by Jellyfin. Cast Connect into the TV app is a `tv` play.
    deviceKind.equals("cast", ignoreCase = true) && !castReceivers -> CAST_RECEIVER_FALLBACK
    !ffmpegReady -> "no jellyfin-ffmpeg"
    cards.isEmpty() -> "no GPU in the container"
    source == null -> "file not on this server's disk or not scanned"
    source.durationMs <= 0 || source.width <= 0 || source.height <= 0 -> "file not scanned (no size or length)"
    source.videoCodec.lowercase() !in GPU_DECODABLE -> "video codec ${source.videoCodec} not decoded on the GPU"
    source.dolbyVisionProfile5 -> "Dolby Vision profile 5 (no HDR10 base layer)"
    else -> null
}

/**
 * 313 (2026-10-09) — files ffmpeg refused (exit before the first segment). Their plays go to Jellyfin for [ttlMs] (a
 * driver or binary update may fix it, so it isn't forever) instead of retrying the same command. Thread-safe enough for
 * its use: one write per refusal, reads per plan.
 */
internal class RefusedSources(private val ttlMs: Long = 6 * 3_600_000L) {
    private val refused = kotlin.concurrent.AtomicReference<Map<String, Pair<Long, String>>>(emptyMap())
    fun markRefused(path: String, codec: EncoderCodec, exitCode: Int, nowMs: Long) {
        while (true) {
            val cur = refused.value
            if (refused.compareAndSet(cur, cur + (path to (nowMs to "ffmpeg refused this file ($codec, exit $exitCode) — Jellyfin serves it (313)")))) return
        }
    }
    /** The reason [path] goes to Jellyfin, or null when ffmpeg hasn't refused it within [ttlMs]. */
    fun reason(path: String, nowMs: Long): String? = refused.value[path]?.takeIf { nowMs - it.first < ttlMs }?.second
}

/** 313 (2026-10-09) — the logged reason a cast receiver's transcode stays Jellyfin's. */
internal const val CAST_RECEIVER_FALLBACK = "cast receivers use Jellyfin until the stall is diagnosed (313)"

/** FR-313-4 — the codec family for a device: HEVC when it decodes HEVC over HLS and (for an HDR source) the source's HDR form. */
internal fun encoderCodecFor(hlsHevc: Boolean, videoCodecs: List<String>, supportsHdr10: Boolean, supportsHlg: Boolean, source: EncoderSource): EncoderCodec {
    val hevc = hlsHevc && videoCodecs.any { it.equals("hevc", true) || it.equals("h265", true) }
    if (!hevc) return EncoderCodec.H264
    if (!source.hdr) return EncoderCodec.HEVC
    return if ((source.hlg && supportsHlg) || (!source.hlg && supportsHdr10)) EncoderCodec.HEVC else EncoderCodec.H264
}
