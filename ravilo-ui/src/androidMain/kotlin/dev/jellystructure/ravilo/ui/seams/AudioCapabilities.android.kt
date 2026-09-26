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

/** R265 — unchanged: Media3 with the device's own decoders, the list every platform used to send. */
actual fun supportedVideoCodecs(): List<String> = BASE_VIDEO_CODECS

/** R265 — never: AirPlay is Apple's, and this player takes files as well as HLS. */
actual fun playsHlsForAirPlay(): Boolean = false

/**
 * R291 — Media3 plays a master's EXT-X-MEDIA audio renditions and switches them by track selection. **On since
 * 2026-09-26**, with the renditions made by jellystructure itself (Jellyfin's audio endpoint never maps the
 * picked track). Seen on both device kinds against prod: the Pixel 9 598–907 ms a switch (touch, no warm), the
 * stue TV 310–400 ms with the picker's warm and 1.46 s without; back to the carried track, no pause at all; the
 * picture never stops.
 */
actual fun switchesHlsAudioRenditions(): Boolean = true
