package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ProcessingCarryOver
import dev.jellystructure.config.ProcessingFeatures
import dev.jellystructure.db.createDatabase
import dev.jellystructure.server.routes.planScanRun
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2470 (FR-15) — Continue scan runs the stopped run as it was started: same library, parts and Re-check switch; no second repair queue;
 * and a stopped run the server holds no record of is refused, never guessed as a full run.
 */
class ScanResumeTest {
    private val cfg: AppConfig = ProcessingCarryOver.settle(AppConfig())
    private val allParts = ProcessingFeatures.ALL.flatMap { it.partIds }.toSet()
    private val keepVerify = allParts - "verify_files"
    private val none = { emptySet<String>() }

    private fun plan(
        library: String? = null, full: Boolean = false, skip: Set<String> = keepVerify, only: Set<String>? = null, direct: Boolean = false,
        resume: StartedRun? = null, resumeSkip: Set<String> = emptySet(),
    ) = planScanRun("scan-1", "manual", cfg, none, library, full, skip, resumeSkip, only, null, resume, direct)

    @Test
    fun `a narrow run resumes with the same step plan and not full`() {
        val original = plan()
        assertTrue(original.pipeline.map { it.step }.let { "verify_files" in it && "pull_tmdb" !in it }, "the fixture really is narrow")
        val resumed = plan(resume = original.record, resumeSkip = setOf("jf-1"))
        assertEquals(original.pipeline.map { it.step }, resumed.pipeline.map { it.step })
        assertFalse(resumed.full)
        assertFalse(resumed.startMsg.contains("(full)"))
        assertTrue(resumed.startMsg.contains("(resumed)"), resumed.startMsg)
        assertTrue(resumed.startMsg.contains("skipped:"), resumed.startMsg)
        assertEquals(original.record.skippedNames, resumed.record.skippedNames, "the same skipped parts by product name")
    }

    @Test
    fun `a resume with nothing looked at yet still says resumed`() {
        assertTrue(plan(resume = plan().record).startMsg.contains("(resumed)"))
    }

    @Test
    fun `a library scoped run resumes scoped and an everything run resumes over all`() {
        assertEquals("lib-1", plan(library = "lib-1").record.libraryId)
        assertEquals("lib-1", plan(library = "lib-1", resume = plan(library = "lib-1").record).libraryId)
        assertNull(plan(resume = plan().record).libraryId)
    }

    @Test
    fun `a Re-check everything run resumes full and the others do not`() {
        val full = plan(full = true)
        assertTrue(full.startMsg.contains("(full)"))
        val resumed = plan(resume = full.record)
        assertTrue(resumed.full && resumed.startMsg.contains("(full)") && resumed.startMsg.contains("(resumed)"), resumed.startMsg)
        assertFalse(plan(resume = plan().record).full)
    }

    @Test
    fun `a scheduler feature run resumes as those features alone with no discovery`() {
        val only = setOf(ProcessingFeatures.HEALTH)
        val original = planScanRun("scan-1", "scheduled", cfg, none, onlyFeatures = only)
        assertTrue(original.featuresOnly)
        val resumed = plan(skip = emptySet(), resume = original.record)
        assertTrue(resumed.featuresOnly, "the engine is told not to discover")
        assertEquals(original.pipeline.map { it.step }, resumed.pipeline.map { it.step })
        assertTrue(resumed.startMsg.contains("feature run started (resumed)"), resumed.startMsg)
    }

    @Test
    fun `a resume never runs a part the stopped run did not have and ignores a step Settings no longer has`() {
        val original = plan()
        val widerSettings = planScanRun("scan-1", "manual", cfg, none, null, false, emptySet(), emptySet(), null, null, original.record)
        assertEquals(original.pipeline.map { it.step }, widerSettings.pipeline.map { it.step })
        val trimmed = original.record.let { StartedRun(it.jobId, it.libraryId, it.steps + "no_such_step", it.skippedNames, it.full, it.featuresOnly) }
        assertEquals(original.pipeline.map { it.step }, plan(resume = trimmed).pipeline.map { it.step }, "a step Settings no longer has is simply not there")
    }

    @Test
    fun `a resume queues no second repair`() {
        assertTrue(plan(direct = true).queueDirectPlay)
        assertFalse(plan(direct = true, resume = plan(direct = true).record).queueDirectPlay)
    }

    // ── the tracker: the record, the refusal, the status fields ──────────────────────────────────────────────────

    private inline fun withDb(block: (dev.jellystructure.db.JellystructureDb) -> Unit) {
        val dir = "/tmp/jellystructure-test-scan-resume-${getpid()}"
        platform.posix.system("rm -rf '$dir'; mkdir -p '$dir'")
        try { block(createDatabase("$dir/test.db")) } finally {
            dev.jellystructure.db.closeLastDatabaseForTests()
            platform.posix.system("rm -rf '$dir'")
        }
    }

    @Test
    fun `a cancelled run with a record can be continued and says so`() = withDb { db ->
        val t = ScanTracker(db)
        val id = t.startNew()
        assertEquals(RESUME_NOT_CANCELLED, t.resumeRefusal())
        t.recordStartedRun(plan().record.let { StartedRun(id, it.libraryId, it.steps, it.skippedNames, it.full, it.featuresOnly) })
        t.cancel()
        kotlinx.coroutines.runBlocking {
            assertNull(t.resumeRefusal())
            val s = t.status()
            assertTrue(s.canResume)
            assertNull(s.resumeReason)
        }
        assertNotNull(t.startedRun)
    }

    @Test
    fun `a cancelled row loaded with no record is refused with the server's sentence`() = withDb { db ->
        val before = ScanTracker(db)
        before.startNew()
        before.cancel()
        val after = ScanTracker(db)   // a restart: same row, no record
        kotlinx.coroutines.runBlocking {
            after.load()
            assertEquals("CANCELLED", after.status().status)
            assertEquals(RESUME_NO_RECORD, after.resumeRefusal())
            val s = after.status()
            assertFalse(s.canResume)
            assertEquals(RESUME_NO_RECORD, s.resumeReason)
        }
    }

    @Test
    fun `a run that is not cancelled offers no resume and a new run drops the old record`() = withDb { db ->
        val t = ScanTracker(db)
        val id = t.startNew()
        t.recordStartedRun(StartedRun(id, null, listOf("scan_files"), emptyList(), false, false))
        kotlinx.coroutines.runBlocking {
            assertFalse(t.status().canResume)
            assertNull(t.status().resumeReason, "only a stopped scan has a reason")
        }
        t.startNew()
        assertNull(t.startedRun)
    }
}
