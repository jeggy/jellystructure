package dev.jellystructure.subtitles

import dev.jellystructure.db.Subtitle_check
import dev.jellystructure.model.MediaItem

/** Phase 273 (§C) — what happens after a video's sidecars were judged: keep, sync, move, replace, restore. The
 *  check service calls this and never talks to Bazarr itself; the Bazarr implementation lives with the client. */
interface SubtitleSteering {
    suspend fun afterCheck(item: MediaItem, videoPath: String, rows: List<Subtitle_check>, changed: List<Subtitle_check>)
}
