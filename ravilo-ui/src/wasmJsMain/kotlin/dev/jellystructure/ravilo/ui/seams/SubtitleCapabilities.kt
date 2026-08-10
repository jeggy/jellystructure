package dev.jellystructure.ravilo.ui.seams

/** Phase 161 — a browser `<video>` element can't parse an MKV container's embedded text tracks at
 *  all (and can't direct-play MKV in the first place, so this is moot either way) — stated explicitly
 *  rather than left to the conservative default. See [supportsEmbeddedTextSubtitles]'s doc. */
actual fun supportsEmbeddedTextSubtitles(): Boolean = false
