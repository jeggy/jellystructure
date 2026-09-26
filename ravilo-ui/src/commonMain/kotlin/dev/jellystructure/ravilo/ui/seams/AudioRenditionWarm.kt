package dev.jellystructure.ravilo.ui.seams

/**
 * R291 (FR-R291-2, dev review item 5) — start the audio-only job of the rendition at ticket position
 * [index] before it is picked, so a switch finds its first segment already encoded.
 *
 * Measured on the stue TV 2026-09-26: picking a rendition nobody had asked for yet froze the picture for
 * 1.1–2 s behind R218's stall spinner — the time Jellyfin takes to encode that rendition's first
 * audio-only segment. The picker calls this after its focus has rested on an audio row for a moment;
 * the player fetches that rendition's segment at the playhead, with its own HTTP stack, so the request
 * lands in the very Jellyfin job the switch will read from (a job is keyed on path · user agent · device
 * · play session). One warm at a time; a muxed track, an unknown index or no playing stream does nothing.
 * Web: no-op — the web player does not declare renditions.
 */
expect fun warmAudioRendition(index: Int)
