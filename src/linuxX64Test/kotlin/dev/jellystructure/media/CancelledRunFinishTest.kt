package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import dev.jellystructure.jobs.JobEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * DEB-2534 (restores DEB-2196, 2026-10-25 amendment, *The toasts*) — a Stop pressed before the worker pool started
 * (nothing processed yet) still ends the run with exactly one `Finished`, so the *Scan cancelled* toast shows and the
 * Dashboard leaves *Scanning…*; a run that wound down on the cancel flag, and then also unwound, sends it once.
 */
class CancelledRunFinishTest {
    private val dir = "/tmp/jellystructure-test-cancel-finish-${getpid()}"

    private fun withTracker(block: suspend CoroutineScope.(ScanTracker) -> Unit) {
        platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'")
        try { runBlocking { block(ScanTracker(createDatabase("$dir/test.db"))) } } finally {
            dev.jellystructure.db.closeLastDatabaseForTests()
            platform.posix.system("rm -rf '$dir'")
        }
    }

    @Test
    fun `a stop before any title is processed sends one Finished`() = withTracker { t ->
        val sent = mutableListOf<JobEvent>()
        val id = t.startNew()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            finishOnOperatorCancel(t, id, { sent += it }) { awaitCancellation() }
        }
        t.attachJob(job)
        t.cancelRun(this)
        job.join()
        assertEquals(1, sent.size, sent.toString())
        val f = assertIs<JobEvent.Finished>(sent.single())
        assertEquals(id, f.jobId)
        assertEquals(0, f.succeeded)
        assertEquals("CANCELLED", t.status().status)
    }

    @Test
    fun `a run that already sent its Finished on the cancel flag does not send a second on unwinding`() = withTracker { t ->
        val sent = mutableListOf<JobEvent>()
        val id = t.startNew()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            finishOnOperatorCancel(t, id, { sent += it }) {
                try { awaitCancellation() } finally { broadcastCancelledFinished(t, id, 8) { sent += it } }
            }
        }
        t.attachJob(job)
        t.cancelRun(this)
        job.join()
        assertEquals(1, sent.size, sent.toString())
        assertEquals(8, assertIs<JobEvent.Finished>(sent.single()).succeeded)
    }

    @Test
    fun `a cancellation the operator did not ask for sends no Finished`() = withTracker { t ->
        val sent = mutableListOf<JobEvent>()
        val id = t.startNew()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            finishOnOperatorCancel(t, id, { sent += it }) { awaitCancellation() }
        }
        job.cancel(CancellationException("shutdown"))
        job.join()
        assertEquals(0, sent.size, sent.toString())
    }

    @Test
    fun `a resumed run can send its own Finished again`() = withTracker { t ->
        val sent = mutableListOf<JobEvent>()
        val id = t.startNew()
        t.cancel()
        broadcastCancelledFinished(t, id, 0) { sent += it }
        broadcastCancelledFinished(t, id, 0) { sent += it }
        assertEquals(1, sent.size)
        t.startResume()
        t.cancel()
        broadcastCancelledFinished(t, id, 0) { sent += it }
        assertEquals(2, sent.size)
    }
}
