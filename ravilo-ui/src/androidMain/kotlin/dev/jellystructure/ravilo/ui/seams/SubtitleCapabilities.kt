package dev.jellystructure.ravilo.ui.seams

/** Phase 161 — Media3's `MatroskaExtractor` parses embedded SRT/ASS/SSA subtitle tracks natively from
 *  an MKV container on a direct-played file (already relied on for VobSub/DVDSub embed rendering,
 *  R56) — see [supportsEmbeddedTextSubtitles]'s doc for the bug this capability declaration fixes. */
actual fun supportsEmbeddedTextSubtitles(): Boolean = true
