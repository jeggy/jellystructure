package dev.jellystructure.towo

import dev.jellystructure.log.Logger
import dev.jellystructure.nowEpochSec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DEFAULT_INTERVAL_MS = 6L * 60 * 60 * 1000  // spec §A's default quota-watch interval

/**
 * Phase 162 (Towo), build-order step 6 — the headline feature. Every [intervalMs] (default 6h, per
 * spec §A), looks for sessions sitting in `paused_quota` with `continue_after_reset` armed whose
 * `resume_at` has passed, and resumes each one via ControlToRunner.ResumeSession.
 *
 * Deliberately control-plane-owned, not runner-owned (see the dev-review addendum / TowoProtocol.kt's
 * ResumeSession doc) -- this process already persists continue_after_reset/resume_at and already runs
 * a periodic scheduler shape elsewhere (Main.kt's scan-schedule loop); re-deriving arming state on the
 * runner side would just be a second, harder-to-keep-consistent copy of the same state.
 *
 * A runner that's offline when its tick comes due is not an error: the session stays `paused_quota`
 * and is retried on the next tick, same as spec §8's "only paused_quota reschedules" — it never
 * silently drops into any other state on its own.
 */
class TowoAutoContinueScheduler(
    private val store: TowoStore,
    private val service: TowoService,
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    fun start() {
        scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure {
                    Logger.warn("Towo auto-continue tick failed: ${it.message}", "towo")
                }
                delay(intervalMs)
            }
        }
    }

    private suspend fun tick() {
        val now = nowEpochSec()
        val candidates = store.sessionsByStatus("paused_quota")
            .filter { it.continueAfterReset && it.resumeAt != null && it.resumeAt <= now }
        if (candidates.isEmpty()) return
        Logger.info("Towo auto-continue: ${candidates.size} session(s) past their reset and armed", "towo")
        for (session in candidates) {
            val resumed = service.resumeSession(session.id, "Continue.")
            if (resumed) {
                Logger.info("Towo auto-continue: resumed session ${session.id}", "towo")
            } else {
                Logger.info("Towo auto-continue: session ${session.id}'s runner is offline or has no known folder — will retry next tick", "towo")
            }
        }
    }
}
