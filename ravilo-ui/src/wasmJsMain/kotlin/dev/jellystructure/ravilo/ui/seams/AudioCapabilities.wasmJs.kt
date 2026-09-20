package dev.jellystructure.ravilo.ui.seams

/** R283 (FR-R283-1) — a browser `<video>` element: exactly the pre-R283 list, no behaviour change. */
actual fun supportedAudioCodecs(): List<String> = BASE_AUDIO_CODECS

/** R284 (FR-R284-6) — most browsers have no HEVC decoder at all; the web stays on h264. */
actual fun supportsHevcOverHls(): Boolean = false
