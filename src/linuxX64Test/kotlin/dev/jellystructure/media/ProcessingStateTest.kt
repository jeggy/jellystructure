package dev.jellystructure.media

import dev.jellystructure.config.AppConfig
import dev.jellystructure.config.ArrConfig
import dev.jellystructure.config.MusicSteps
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.config.ProcessingCarryOver
import dev.jellystructure.config.ProcessingConfig
import dev.jellystructure.config.ProcessingFeatures
import dev.jellystructure.config.RecommendationsStep
import dev.jellystructure.config.ScanConfig
import dev.jellystructure.config.SeerrConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2194 (DEB-1664, FR-3, FR-9) — which whole-library features are due on their own interval, which steps an automatic run
 * takes, what the engine's step list is for a given set of switches, and the feature list `GET /api/processing` answers.
 */
class ProcessingStateTest {
    private val now = 1_000_000L
    private fun cfg(p: ProcessingConfig = ProcessingConfig()) = ProcessingCarryOver.settle(AppConfig(processing = p))

    @Test
    fun `an effective pipeline is the features that are on in dependency order with discovery first`() {
        val steps = effectivePipeline(cfg()).map { it.step }
        assertEquals("scan_files", steps.first())
        assertTrue("write_nfo" in steps && ProcessingFeatures.TELL_JELLYFIN in steps && "pull_tmdb" in steps)
        assertFalse("detect_segments" in steps, "Intro & credits is off on a fresh install")
        assertFalse("build_suggestions" in steps, "no Seerr, no suggestions")
        assertTrue(steps.indexOf("write_nfo") < steps.indexOf(ProcessingFeatures.TELL_JELLYFIN) && steps.indexOf(ProcessingFeatures.TELL_JELLYFIN) < steps.indexOf("rescan_arr"))
        assertEquals(ProcessingFeatures.ORDER.filter { it in steps }, steps)
    }

    @Test
    fun `an install with every step off still runs discovery and nothing else`() {
        val legacy = AppConfig(scan = ScanConfig(pipeline = listOf(PipelineStep(step = "scan_files", enabled = false), PipelineStep(step = "detect_segments", enabled = false))))
        assertEquals(listOf("scan_files"), effectivePipeline(legacy).map { it.step })
        assertEquals(listOf("scan_files"), pipelinePlan(legacy).map { it.step })
    }

    @Test
    fun `a single title takes the same features as a library run — the switch moves both`() {
        val on = cfg()
        val off = cfg(ProcessingConfig().let { it.copy(metadata = it.metadata.copy(nfo = false)) })
        assertTrue("write_nfo" in effectivePipeline(on).map { it.step })
        assertFalse("write_nfo" in effectivePipeline(off).map { it.step })
        assertFalse(ProcessingFeatures.TELL_JELLYFIN in effectivePipeline(off).map { it.step }, "telling Jellyfin follows the NFO files")
    }

    @Test
    fun `lyrics stay out while the lyrics provider is off and suggestions come in with Seerr`() {
        val base = AppConfig(processing = ProcessingConfig())
        val seerr = base.copy(seerr = SeerrConfig(enabled = true, url = "http://seerr"), music = base.music.copy(fetchLyrics = false))
        val steps = effectivePipeline(ProcessingCarryOver.settle(seerr)).map { it.step }
        assertTrue("build_suggestions" in steps)
        assertFalse(MusicSteps.LYRICS in steps)
    }

    // ── own interval ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `music is due when it never ran or its interval has passed and not before`() {
        val c = cfg(ProcessingConfig().let { it.copy(music = it.music.copy(interval = ProcessingFeatures.DAILY), audiobooks = it.audiobooks.copy(enabled = false)) })
        fun due(last: Long?) = ProcessingSchedule.dueFeatures(c, now, { if (it == ProcessingFeatures.MUSIC) last else null }, { null })
        assertTrue(ProcessingFeatures.MUSIC in due(null))
        assertTrue(ProcessingFeatures.MUSIC in due(now - 86_400L))
        assertTrue(ProcessingFeatures.MUSIC in due(now - 86_400L + 200L), "a few minutes early is still the same time each day")
        assertFalse(ProcessingFeatures.MUSIC in due(now - 3_600L))
        assertFalse(ProcessingFeatures.AUDIOBOOKS in due(null), "a feature that is off is never due")
    }

    @Test
    fun `recommendations are due by their own cadence and suggestions only with Seerr`() {
        val c = cfg(ProcessingConfig().let { it.copy(music = it.music.copy(enabled = false), audiobooks = it.audiobooks.copy(enabled = false)) })
        fun due(recsBuilt: Long?) = ProcessingSchedule.dueFeatures(c, now, { null }, { if (it == RecommendationsStep.STEP) recsBuilt else null })
        assertTrue(ProcessingFeatures.SUGGESTIONS in due(null))
        assertTrue(ProcessingFeatures.SUGGESTIONS in due(now - 8 * 86_400L))
        assertTrue(due(now - 86_400L).isEmpty(), "weekly: built yesterday is not due, and no Seerr means no suggestions part")
    }

    @Test
    fun `an automatic run leaves Music out until it is due and a manual run takes everything`() {
        val pipe = effectivePipeline(cfg())
        val automatic = ProcessingSchedule.stepsForRun(pipe, manual = false, due = emptySet()).map { it.step }
        assertFalse(MusicSteps.SCAN in automatic || MusicSteps.AUDIOBOOKS in automatic)
        assertTrue("pull_tmdb" in automatic && RecommendationsStep.STEP in automatic, "per-title and cadence-guarded steps are untouched")
        assertTrue(MusicSteps.SCAN in ProcessingSchedule.stepsForRun(pipe, false, setOf(ProcessingFeatures.MUSIC)).map { it.step })
        assertEquals(pipe, ProcessingSchedule.stepsForRun(pipe, manual = true, due = emptySet()))
    }

    @Test
    fun `a feature run is only the whole-library steps of the due features`() {
        val pipe = effectivePipeline(cfg())
        val steps = ProcessingSchedule.stepsOf(pipe, setOf(ProcessingFeatures.MUSIC)).map { it.step }
        assertTrue(steps.isNotEmpty() && steps.all { MusicSteps.isMusic(it) && it != MusicSteps.AUDIOBOOKS })
        assertEquals(listOf(MusicSteps.SCAN), steps.take(1))
    }

    // ── the words ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a saved cron that matches a preset shows as that preset and anything else as Custom`() {
        assertEquals("Every hour at :30", ProcessingSchedule.describeFind("30 */1 * * *"))
        assertEquals("Every hour", ProcessingSchedule.describeFind("0 */1 * * *"))
        assertEquals("Every 6 hours", ProcessingSchedule.describeFind("0 */6 * * *"))
        assertEquals("Daily at 03:00", ProcessingSchedule.describeFind("0 3 * * *"))
        assertEquals("Weekly on Sunday at 04:00", ProcessingSchedule.describeFind("0 4 * * 0"))
        assertEquals("Custom · 15 2 1 * *", ProcessingSchedule.describeFind("15 2 1 * *"))
        assertTrue(ProcessingSchedule.describeFind("").startsWith("Not on a schedule"))
    }

    // ── GET /api/processing ─────────────────────────────────────────────────────────────────────────────────────────

    private fun view(c: AppConfig = cfg(), inp: ProcessingInputs = ProcessingInputs(now)) = ProcessingViews.build(c, inp).features.associateBy { it.id }

    @Test
    fun `the view lists the nine features in order with Find locked on`() {
        val v = ProcessingViews.build(cfg(), ProcessingInputs(now))
        assertEquals(ProcessingFeatures.ALL.map { it.id }, v.features.map { it.id })
        assertEquals(9, v.features.size)
        val find = v.features.first()
        assertTrue(find.locked && find.enabled && find.canRun)
        assertNotNull(find.reason, "Find's always-on switch carries its reason (FR-11)")
        assertEquals("Every hour", find.interval)
    }

    @Test
    fun `a dependent part stays in the list with its reason while it cannot run`() {
        val c = cfg().copy(music = AppConfig().music.copy(writeTags = false))
        val music = view(c)[ProcessingFeatures.MUSIC]!!
        val tags = music.parts.first { it.id == MusicSteps.TAGS }
        assertEquals(7, music.parts.size, "n of m stays honest")
        assertTrue(tags.on && !tags.canRun)
        assertEquals("Tag writing is off in Settings.", tags.reason)
        val sugg = view(c)[ProcessingFeatures.SUGGESTIONS]!!.parts.first { it.id == "build_suggestions" }
        assertEquals("Seerr is not connected.", sugg.reason)
        val rescan = view(c)[ProcessingFeatures.METADATA]!!.parts.first { it.id == "rescan_arr" }
        assertFalse(rescan.canRun)
        val withArr = c.copy(radarr = ArrConfig(enabled = true, url = "http://r", apiKey = "k"))
        assertTrue(view(withArr)[ProcessingFeatures.METADATA]!!.parts.first { it.id == "rescan_arr" }.canRun)
    }

    @Test
    fun `a feature with no library mapped is unavailable with one sentence and an off feature says so`() {
        val v = view(inp = ProcessingInputs(now, musicLibraries = false))
        assertEquals("unavailable", v[ProcessingFeatures.MUSIC]!!.state)
        assertTrue(v[ProcessingFeatures.MUSIC]!!.reason!!.contains("No music library is mapped"))
        assertFalse(v[ProcessingFeatures.MUSIC]!!.canRun)
        assertEquals("off", v[ProcessingFeatures.SEGMENTS]!!.state)
        assertEquals("Off", v[ProcessingFeatures.SEGMENTS]!!.next)
        assertNull(v[ProcessingFeatures.SEGMENTS]!!.nextAt)
    }

    @Test
    fun `last ran next and waiting are computed on the server`() {
        val inp = ProcessingInputs(
            now, lastRan = mapOf(ProcessingFeatures.MUSIC to now - 600L, ProcessingFeatures.FIND to now - 7_200L),
            queuedJobTypes = listOf("verify_file", "verify_file", "check_subtitles", "segments_season"),
            directPlay = listOf("Stereo for everything" to true), deferred = true,
        )
        val v = view(inp = inp)
        assertEquals("Ran 10 min ago", v[ProcessingFeatures.MUSIC]!!.lastRan)
        assertEquals("Ran 2 h ago", v[ProcessingFeatures.FIND]!!.lastRan)
        assertEquals(now + 86_400L - 600L, v[ProcessingFeatures.MUSIC]!!.nextAt)
        assertEquals(2, v[ProcessingFeatures.HEALTH]!!.waiting)
        assertEquals("2 waiting", v[ProcessingFeatures.HEALTH]!!.waitingText)
        assertEquals("waiting", v[ProcessingFeatures.HEALTH]!!.state, "queued work held for a TV")
        assertEquals("Nothing waiting", v[ProcessingFeatures.DIRECT_PLAY]!!.waitingText)
        assertEquals("Not run yet", v[ProcessingFeatures.AUDIOBOOKS]!!.lastRan)
        assertNotNull(v[ProcessingFeatures.FIND]!!.nextAt)
        assertTrue(v[ProcessingFeatures.DIRECT_PLAY]!!.enabled, "a file-fix kind is on")
    }
}
