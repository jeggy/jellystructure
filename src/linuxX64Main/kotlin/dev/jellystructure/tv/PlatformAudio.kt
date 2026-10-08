package dev.jellystructure.tv

import dev.jellystructure.shared.tv.ClientCapabilities

/**
 * R379 (owner decision 2026-10-08) — a device with no platform AC-3/E-AC-3 decoder gets that audio re-encoded by the
 * server, never decoded by the app's FFmpeg extension.
 *
 * Why: Media3's FFmpeg audio decoder (`ffmpeg_jni.cc`, jellyfin's 1.8.0+1) builds its resampler once, from the first
 * frame's channel layout. An AC-3 stream that changes channel count mid-file (5.1 → 2.0 → 5.1 at an advert or an
 * ident, common in TV captures) makes it read and write past its buffers: a native crash that takes the app with it
 * (a phone, 2026-10-07, four crashes at 0:35). R379's platform-first renderers fix that wherever the device decodes
 * AC-3 itself; this covers the devices that don't.
 *
 * The rule, on a device that reports [ClientCapabilities.platformAudioDecoders] (an app with R379; null = unchanged):
 * - every AC-3 family codec it does not decode itself leaves the declared audio list, so Jellyfin neither direct-plays
 *   it nor copies it into a transcode, and converts it instead (R297's `transcodeAudio` already intersects with the
 *   declared list);
 * - when THIS play's audio is such a codec and the app can take HEVC in fMP4 HLS ([ClientCapabilities.hlsHevcCapable]),
 *   the transcode is offered fMP4 with HEVC first (`hls_hevc`), so an HEVC source's picture is copied rather than
 *   re-encoded: the transcode is about the audio only. An H.264 source is copied in either container.
 */
internal val AC3_FAMILY = listOf("ac3", "eac3")

/** The AC-3 family codecs [c]'s device lacks a platform decoder for; empty for an app that does not report it. */
internal fun missingPlatformAudio(c: ClientCapabilities): Set<String> {
    val platform = c.platformAudioDecoders?.map { it.lowercase() }?.toSet() ?: return emptySet()
    return AC3_FAMILY.filterTo(LinkedHashSet()) { it !in platform }
}

/**
 * The capabilities a play is negotiated with. [sourceAudioCodec] is the codec of the audio track that will play (the
 * picked one, else the file's default), or null when unknown.
 */
internal fun forPlatformAudio(c: ClientCapabilities, sourceAudioCodec: String?): ClientCapabilities {
    val missing = missingPlatformAudio(c)
    if (missing.isEmpty()) return c
    val audio = c.audioCodecs.filterNot { it.lowercase() in missing }
    val audioOnly = sourceAudioCodec?.lowercase()?.let { it in missing } == true
    return c.copy(
        // A device that declares nothing keeps "declares nothing" (Jellyfin's defaults) — but every app that reports
        // its platform decoders also declares its codecs, so this is only a guard.
        audioCodecs = if (c.audioCodecs.isEmpty()) c.audioCodecs else audio,
        hlsHevc = c.hlsHevc || (audioOnly && c.hlsHevcCapable),
    )
}

/** The audio codec of the track [audioStreamIndex] names in [tracks], else the default track's, else the first's. */
internal fun playingAudioCodec(tracks: List<dev.jellystructure.shared.tv.AudioTrack>, audioStreamIndex: Int?): String? =
    (audioStreamIndex?.let { i -> tracks.firstOrNull { it.index == i } } ?: tracks.firstOrNull { it.isDefault } ?: tracks.firstOrNull())
        ?.codec
