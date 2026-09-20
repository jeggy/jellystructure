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
