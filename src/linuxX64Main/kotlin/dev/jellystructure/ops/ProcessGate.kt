package dev.jellystructure.ops

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Phase 118 (FR C.3) — every `popen` call site in the backend (ffmpeg/ffprobe/mkvpropedit/screengrab/
 * health-check shell-outs) shares this gate, on top of any call site's own narrower bound (e.g.
 * Screengrabber's own Semaphore(2), the Phase 109 worker's single-remux serialization). A `popen` briefly
 * holds 2+ FDs (the pipe, plus whatever the child process itself opens) — with no shared ceiling, a burst
 * of user-triggered ffmpeg/ffprobe work could stack unbounded pipes toward the FD_SETSIZE crash.
 *
 * Phase 134 (FR-OPS2 §F): 4 → 16 to give a 100-scan-worker host real parallelism. Deliberately *not*
 * raised as high as the worker/HTTP ceilings — each permit here is a real forked OS process (CPU/memory
 * cost, not just an FD), so this stays a conservative multiple rather than tracking `scan_workers` 1:1.
 *
 * Bug fix: `popen`/`fgets`/`pclose` are blocking native calls with no coroutine suspension point of
 * their own — running them on whatever dispatcher the calling request happened to already be on (i.e.
 * Dispatchers.Default, the same shared pool the Ktor CIO engine's own request handling hops onto) let a
 * burst of artwork-resize calls (e.g. a TV's Home screen re-fetching a page of posters right after
 * reconnecting) tie up enough of that shared pool to stall unrelated HTTP/WS traffic — including the
 * admin web UI and other TVs' WS ping/pong, which read as a timeout and forced a reconnect, which
 * re-fetched the same artwork burst on reconnect and repeated the stall. A dedicated pool sized to the
 * semaphore below — so every granted permit gets its own thread — isolates this from request-serving
 * capacity entirely, the same fix already applied to `scanDispatcher` in Main.kt for the analogous
 * pipeline-vs-request-thread contention bug.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
object ProcessGate {
    private const val MAX_CONCURRENT = 16
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val dispatcher = newFixedThreadPoolContext(MAX_CONCURRENT, "process-gate")

    suspend fun <T> withPermit(block: suspend () -> T): T =
        semaphore.withPermit { withContext(dispatcher) { block() } }
}
