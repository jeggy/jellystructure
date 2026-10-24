package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.config.ScanConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 265 (FR-265-4/5, acceptance 4 and 5) — the plan the pre-run dialog lists is the one the run executes,
 * and both run routes read the one-run skip the same way.
 */
class PipelinePlanTest {
    private fun cfg(vararg steps: PipelineStep) = AppConfig(scan = ScanConfig(pipeline = steps.toList()))

    @Test
    fun `an install with no step enabled lists discovery alone — the features are the plan now and no built-in list`() {
        val plan = pipelinePlan(cfg(PipelineStep(step = "scan_files", enabled = false), PipelineStep(step = "detect_segments", enabled = false)))
        assertEquals(listOf("scan_files"), plan.map { it.step })
        assertEquals(plan.map { it.step }, effectivePipeline(cfg(PipelineStep(step = "scan_files", enabled = false))).map { it.step })
    }

    @Test
    fun `an install with no saved pipeline lists the fresh install features`() {
        val plan = pipelinePlan(cfg()).map { it.step }
        assertEquals("scan_files", plan.first())
        assertTrue("write_nfo" in plan && "sync_jellyfin" in plan, "NFO files and telling Jellyfin are on: $plan")
        assertTrue("detect_segments" !in plan, "Intro & credits is off: $plan")
    }

    @Test
    fun `only enabled steps are listed — discovery first then the rest in the one dependency order with telling Jellyfin after the NFO files`() {
        val plan = pipelinePlan(cfg(
            PipelineStep(step = "pull_tmdb", scope = "all"),
            PipelineStep(step = "detect_segments", enabled = false),
            PipelineStep(step = "scan_files"),
            PipelineStep(step = "write_nfo"),
        ))
        assertEquals(listOf("scan_files", "pull_tmdb", "write_nfo", "sync_jellyfin"), plan.map { it.step })
        assertEquals("all", plan[1].scope)
    }

    @Test
    fun `discovery is listed even when the pipeline does not name it — the run does it anyway`() {
        assertEquals(listOf("scan_files", "pull_tmdb"), planOrder(listOf(PipelineStep(step = "pull_tmdb"))).map { it.step })
    }

    @Test
    fun `a plan of discovery alone is what the dialog starts without asking`() {
        assertEquals(listOf("scan_files"), pipelinePlan(cfg(PipelineStep(step = "scan_files"), PipelineStep(step = "pull_tmdb", enabled = false))).map { it.step })
    }

    @Test
    fun `the skip body — named steps skipped and scan_files ignored`() {
        assertEquals(setOf("detect_segments", "verify_files"), parseSkipSteps("""{"skipSteps":["detect_segments","scan_files","verify_files"]}"""))
    }

    @Test
    fun `no body or an empty body or junk is no skip — curl and an older frontend still start a full run`() {
        for (body in listOf("", "{}", "not json", """{"skipSteps":null}""", """{"other":1}""")) {
            assertEquals(emptySet(), parseSkipSteps(body), "body: $body")
        }
    }
}
