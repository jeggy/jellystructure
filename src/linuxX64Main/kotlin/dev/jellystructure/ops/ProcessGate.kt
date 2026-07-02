package dev.jellystructure.ops

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Phase 118 (FR C.3) — every `popen` call site in the backend (ffmpeg/ffprobe/mkvpropedit/screengrab/
 * health-check shell-outs) shares this gate, on top of any call site's own narrower bound (e.g.
 * Screengrabber's own Semaphore(2), the Phase 109 worker's single-remux serialization). A `popen` briefly
 * holds 2+ FDs (the pipe, plus whatever the child process itself opens) — with no shared ceiling, a burst
 * of user-triggered ffmpeg/ffprobe work could stack unbounded pipes toward the FD_SETSIZE crash.
 */
object ProcessGate {
    private val semaphore = Semaphore(4)

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }
}
