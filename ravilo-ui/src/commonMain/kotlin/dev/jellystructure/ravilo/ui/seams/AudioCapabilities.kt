package dev.jellystructure.ravilo.ui.seams

/**
 * R283 — the audio codecs this build's player can really decode, reported to the server as
 * `ClientCapabilities.audioCodecs`. Names are Jellyfin's own (`MediaStreams[].Codec`).
 *
 * This replaced a literal in `PlayerStore` that dated from R14 and omitted `truehd`/`dts`, although
 * the Android build bundles a decoder for both. It was harmless while the server discarded the list;
 * phase 177 began honouring it, and every TrueHD/DTS-default file (573 in production) silently flipped
 * from direct play to a 4K→h264 transcode — one audio track left, PGS forced through burn-in. Measured
 * on "Honeyman (2021)": adding the two codecs flips `SupportsDirectPlay` false → true.
 *
 * A statement of fact about the running build, never a wish: an actual must not list a codec it
 * cannot decode, since the server will then hand it a stream that plays silent.
 */
expect fun supportedAudioCodecs(): List<String>

/** What every platform's player decodes without an extension — the pre-R283 list, unchanged. */
internal val BASE_AUDIO_CODECS = listOf("aac", "mp3", "flac", "opus", "ac3", "eac3")

/**
 * R284 (FR-R284-6) / 253 (FR-253-3) — whether this player takes HEVC in fMP4 HLS, so a transcode that
 * is not about the video can copy it instead of re-encoding to h264. Lives here with the other
 * "what can this build really play" answer. A wrong yes is a black screen, so: only where known.
 */
expect fun supportsHevcOverHls(): Boolean

/**
 * R265 (FR-R265-8) — the video codecs this player decodes, asked of the platform rather than copied
 * from the TV's list (the web used to declare h264/hevc/vp9/av1 on every browser, so a browser with
 * no HEVC decoder was handed HEVC to direct-play). Jellyfin's names.
 */
expect fun supportedVideoCodecs(): List<String>

/** The pre-R265 list every platform declared; still the Android answer (Media3 + the device's codecs). */
internal val BASE_VIDEO_CODECS = listOf("h264", "hevc", "vp9", "av1")

/**
 * R265 (FR-R265-8) — the player's `<video>` plays HLS by itself and can hand it to AirPlay: Safari.
 * Such a client takes ONLY HLS (`hls_only` — Safari cannot play an MKV, which is most of the library)
 * and shows subtitles from the manifest (`hls_subtitles` — an AirPlay hand-over takes the stream to the
 * TV, and the page's own `<track>`s stay behind). False on Android and on every other browser, which
 * keep what they negotiated before.
 */
expect fun playsHlsForAirPlay(): Boolean
