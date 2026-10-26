package dev.jellystructure.media

import dev.jellystructure.jobs.JobEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * DEB-2534 (restores DEB-2196, 2026-10-25 amendment, *The toasts*) — a stopped run always ends with one
 * `JobEvent.Finished`, because the *Scan cancelled* toast and the Dashboard leaving *Scanning…* both wait for it.
 * Only a run that wound down cooperatively used to send it; a Stop pressed before the worker pool started
 * (the Jellyfin fetch, the first step's HTTP call) unwound as a CancellationException and sent nothing.
 * [ScanTracker.claimFinished] makes the two roads send it once between them.
 */
internal suspend fun broadcastCancelledFinished(
    scanTracker: ScanTracker, jobId: String, succeeded: Int, send: suspend (JobEvent) -> Unit,
) {
    if (!scanTracker.claimFinished()) return
    send(JobEvent.Finished(jobId, succeeded, 0, scanTracker.queuedSentences()))
}

/** Runs [block]; if the operator's Stop cancelled it, sends the run's `Finished` (if no one has) and rethrows. */
internal suspend fun <T> finishOnOperatorCancel(
    scanTracker: ScanTracker, jobId: String, send: suspend (JobEvent) -> Unit, block: suspend () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    // The coroutine is already cancelled, so the send must not be: NonCancellable lets it complete.
    if (scanTracker.cancelRequested) withContext(NonCancellable) { broadcastCancelledFinished(scanTracker, jobId, 0, send) }
    throw e
}
