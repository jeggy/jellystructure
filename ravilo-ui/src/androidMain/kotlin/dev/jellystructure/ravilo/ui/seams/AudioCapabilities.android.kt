package dev.jellystructure.ravilo.ui.seams

/**
 * R283 (FR-R283-1) — TrueHD and DTS are decoded by the FFmpeg audio extension in the GPL-contained
 * `:ravilo-player` module, which reaches this module only through [RaviloPlayerEngine]'s
 * `renderersFactoryProvider`. That provider being installed IS the decoder being present, so it is
 * the condition: a build assembled without `:ravilo-player` keeps the base list rather than claiming
 * a codec it would play silent.
 */
actual fun supportedAudioCodecs(): List<String> =
    if (RaviloPlayerEngine.renderersFactoryProvider != null) BASE_AUDIO_CODECS + FFMPEG_AUDIO_CODECS
    else BASE_AUDIO_CODECS

private val FFMPEG_AUDIO_CODECS = listOf("truehd", "dts")

/** R284 (FR-R284-6) — Media3's HLS source plays fMP4 segments, and HEVC decode is the platform's
 *  (the same MediaCodec path direct play already uses for every HEVC title in the library). */
actual fun supportsHevcOverHls(): Boolean = true
