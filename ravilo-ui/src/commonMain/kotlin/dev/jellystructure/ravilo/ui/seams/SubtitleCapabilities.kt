package dev.jellystructure.ravilo.ui.seams

/**
 * Phase 161 — reported to the server (via `ClientCapabilities.supportsEmbeddedTextSubs`) so it knows
 * whether it's safe to skip sideloading an embedded text subtitle (SRT/ASS/SSA) as an extracted VTT
 * on a direct-played file. Bug fix: on a direct-played MKV, Media3's `MatroskaExtractor` already
 * parses the container's own embedded text tracks natively — sideloading every one of them *too*
 * (R55's original design, before this client-declared capability existed) meant every text subtitle
 * reached the player twice, visible as e.g. two identical "English" rows in R195's picker, and cost a
 * multi-minute Jellyfin ffmpeg VTT-extraction stall on large multi-subtitle titles (see R183's "Known
 * adjacent issue"). Conservative default in `ClientCapabilities` (`false`) — a client that hasn't
 * confirmed this keeps the pre-existing sideload behavior; this only ever removes a redundancy for a
 * client that actively opts in, never drops a subtitle a client couldn't otherwise render.
 */
expect fun supportsEmbeddedTextSubtitles(): Boolean
