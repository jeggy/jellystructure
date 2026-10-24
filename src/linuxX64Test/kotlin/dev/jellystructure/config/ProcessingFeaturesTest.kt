package dev.jellystructure.config

import dev.jellystructure.io.FileIo
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2194 (DEB-1664, FR-1, FR-2, FR-3) — the nine features in code, the carry-over from today's pipeline, the pipeline derived
 * back for a rolled-back image, and what `PUT /api/config` does when the pipeline card or the features moved.
 */
class ProcessingFeaturesTest {
    private fun pathFor(name: String) = "/tmp/jellystructure-test-processing-${getpid()}-$name.toml"
    private fun load(path: String): ConfigStore = runBlocking { ConfigStore(path).also { it.load() } }

    /** Prod's shape: every step the product has, enabled; `recheck_unchanged` on scan_files; hourly at :30; a trailing notify. */
    private fun prodPipeline(): List<PipelineStep> = listOf(
        PipelineStep(step = "scan_files", recheckUnchanged = true),
        PipelineStep(step = "pull_tmdb", scope = "all"),
        PipelineStep(step = "fetch_artwork"),
        PipelineStep(step = "sync_imdb_ratings"),
        PipelineStep(step = "write_nfo", overwrite = true),
        PipelineStep(step = "sync_jellyfin"),
        PipelineStep(step = "detect_drift", autoReassert = true),
        PipelineStep(step = "rescan_arr"),
        PipelineStep(step = "detect_segments", scope = "all", detectFingerprint = true, trustStingerTags = false, chapterKeywords = listOf("fin")),
        FileCheckSteps.defaultStep(FileCheckSteps.VERIFY),
        FileCheckSteps.defaultStep(FileCheckSteps.LENGTHS),
        FileCheckSteps.defaultStep(FileCheckSteps.AVSYNC),
        PipelineStep(step = "prewarm_subtitles"),
        FileCheckSteps.defaultStep(FileCheckSteps.SUBTITLES),
        PipelineStep(step = RecommendationsStep.STEP, rebuildEvery = "daily"),
        PipelineStep(step = SuggestionsStep.STEP, rebuildEvery = "weekly"),
        PipelineStep(step = MusicSteps.SCAN), PipelineStep(step = MusicSteps.AUDIOBOOKS),
        PipelineStep(step = MusicSteps.MATCH, scope = "all"), PipelineStep(step = MusicSteps.COMPARE),
        PipelineStep(step = MusicSteps.ARTWORK), PipelineStep(step = MusicSteps.LYRICS), PipelineStep(step = MusicSteps.NFO), PipelineStep(step = MusicSteps.TAGS),
        PipelineStep(step = "wait", minutes = 2),
        PipelineStep(step = "notify"),
    )

    private fun prod() = AppConfig(scanSchedule = "30 */1 * * *", behavior = Behavior(notifyOnScanDone = false), scan = ScanConfig(pipeline = prodPipeline()))

    @Test
    fun `a prod shaped pipeline carries over to every feature on with the same options and an hourly Find`() {
        val p = ProcessingCarryOver.derive(prod())
        assertEquals("30 */1 * * *", p.find.schedule)
        for (id in ProcessingFeatures.ALL.map { it.id }.filter { it != ProcessingFeatures.DIRECT_PLAY }) assertTrue(p.featureOn(id), "feature $id is on")
        for (f in ProcessingFeatures.ALL) for (part in f.parts) assertTrue(p.partOn(part.step), "part ${part.step} is on")
        assertEquals(AgeTiers(true, "weekly", "monthly", "6months"), p.metadata.tiers, "scan_files' recheck_unchanged and tiers")
        assertEquals("all", p.metadata.tmdbScope)
        assertTrue(p.metadata.nfoOverwrite)
        assertTrue(p.metadata.autoReassert, "drift's auto_reassert is carried")
        assertEquals(SegmentsProcessing(true, "all", true, false, listOf("fin")), p.segments)
        assertEquals(AgeTiers(true, "5years", "5years", "5years"), p.health.verifyTiers)
        assertEquals(AgeTiers(true, "yearly", "2years", "5years"), p.health.lengthsTiers)
        assertEquals("daily", p.suggestions.recommendationsEvery)
        assertEquals("all", p.music.matchScope)
        assertEquals(ProcessingFeatures.HOURLY, p.music.interval, "the pipeline ran whole-library steps with every run")
    }

    @Test
    fun `settle carries over once turns Scan finished on for a notify and writes the derived pipeline without wait or notify`() {
        val cfg = ProcessingCarryOver.settle(prod())
        assertNotNull(cfg.processing)
        assertTrue(cfg.behavior.notifyOnScanDone, "a saved notify turns Scan finished on")
        assertEquals("30 */1 * * *", cfg.scanSchedule)
        assertTrue(cfg.scan.pipeline.none { it.step == "wait" || it.step == "notify" })
        assertTrue(cfg.scan.fileCheckStepsSeeded && cfg.scan.avSyncStepSeeded && cfg.scan.subtitleCheckSeeded, "a rolled-back image must not seed back what a feature turned off")
        assertEquals(cfg, ProcessingCarryOver.settle(cfg), "settling twice changes nothing")
    }

    @Test
    fun `an empty pipeline carries over to the fresh install defaults`() {
        val p = ProcessingCarryOver.derive(AppConfig(scanSchedule = "0 4 * * *"))
        assertEquals("0 4 * * *", p.find.schedule, "the schedule the install had is kept")
        assertTrue(p.metadata.nfo, "NFO files on")
        assertTrue(p.partOn(ProcessingFeatures.TELL_JELLYFIN), "telling Jellyfin on")
        assertFalse(p.segments.enabled, "Intro & credits off")
        assertEquals(AgeTiers(true, "weekly", "monthly", "6months"), p.metadata.tiers)
        assertEquals(AgeTiers(true, "weekly", "weekly", "weekly"), p.subtitles.checkFitTiers)
        assertEquals(AgeTiers(false, "weekly", "monthly", "6months"), p.health.avSyncTiers, "sound in step once per file")
        assertEquals(ProcessingFeatures.DAILY, p.music.interval)
        assertEquals(ProcessingFeatures.DAILY, p.audiobooks.interval)
        assertEquals(RecommendationsStep.DEFAULT_CADENCE, p.suggestions.recommendationsEvery)
    }

    @Test
    fun `the legacy scan_interval_hours becomes every N hours when the schedule is blank`() {
        val p = ProcessingCarryOver.derive(AppConfig(behavior = Behavior(scanIntervalHours = 6), scan = ScanConfig(pipeline = listOf(PipelineStep(step = "scan_files")))))
        assertEquals("0 */6 * * *", p.find.schedule)
    }

    @Test
    fun `a feature is on when any of its parts is an enabled step and a removed step stays off`() {
        val p = ProcessingCarryOver.derive(AppConfig(scan = ScanConfig(pipeline = listOf(
            PipelineStep(step = "scan_files"), PipelineStep(step = "pull_tmdb"), PipelineStep(step = "write_nfo", enabled = false),
            PipelineStep(step = "detect_segments", enabled = false, detectFingerprint = true, chapterKeywords = listOf("end")),
            PipelineStep(step = "bogus_step"),
        ))))
        assertTrue(p.metadata.enabled && p.metadata.tmdb)
        assertFalse(p.metadata.nfo)
        assertFalse(p.partOn(ProcessingFeatures.TELL_JELLYFIN), "no NFO files, nothing to tell Jellyfin")
        assertFalse(p.music.enabled || p.audiobooks.enabled || p.health.enabled || p.subtitles.enabled || p.suggestions.enabled)
        assertFalse(p.segments.enabled)
        assertTrue(p.segments.detectFingerprint, "Intro & credits' options are carried even from a disabled step")
        assertEquals(listOf("end"), p.segments.chapterKeywords)
        assertEquals(listOf("bogus_step"), ProcessingCarryOver.unknownSteps(AppConfig(scan = ScanConfig(pipeline = listOf(PipelineStep(step = "bogus_step"), PipelineStep(step = "wait"))))))
    }

    @Test
    fun `the derived pipeline equals the features and carries back to them`() {
        val p = ProcessingCarryOver.derive(prod())
        val derived = p.derivedPipeline()
        assertEquals(ProcessingFeatures.ORDER, derived.map { it.step })
        for (s in derived) assertEquals(p.partOn(s.step), s.enabled, s.step)
        assertEquals(p, ProcessingCarryOver.derive(AppConfig(scanSchedule = p.find.schedule, scan = ScanConfig(pipeline = derived)), keep = p), "derive then carry over is the identity")
        val off = p.copy(metadata = p.metadata.copy(nfo = false), health = p.health.copy(enabled = false))
        val steps = off.derivedPipeline().filter { it.enabled }.map { it.step }
        assertFalse("write_nfo" in steps || ProcessingFeatures.TELL_JELLYFIN in steps || FileCheckSteps.VERIFY in steps)
        assertTrue("scan_files" in steps && "pull_tmdb" in steps)
    }

    @Test
    fun `the run order is the dependency graph and nothing else`() {
        val o = ProcessingFeatures.ORDER
        fun before(a: String, b: String) = assertTrue(o.indexOf(a) in 0 until o.indexOf(b), "$a before $b in $o")
        assertEquals("scan_files", o.first(), "Find new & changed runs first")
        before("pull_tmdb", "fetch_artwork"); before("pull_tmdb", "write_nfo"); before("pull_tmdb", RecommendationsStep.STEP)
        before("write_nfo", ProcessingFeatures.TELL_JELLYFIN)
        before("write_nfo", "rescan_arr"); before(ProcessingFeatures.TELL_JELLYFIN, "rescan_arr")
        before("prewarm_subtitles", FileCheckSteps.SUBTITLES)
        before(MusicSteps.SCAN, MusicSteps.MATCH)
        for (after in listOf(MusicSteps.ARTWORK, MusicSteps.LYRICS, MusicSteps.NFO, MusicSteps.TAGS)) before(MusicSteps.MATCH, after)
        assertEquals(o.size, o.toSet().size)
        for (f in ProcessingFeatures.ALL) for (part in f.parts) assertTrue(part.step in o, part.step)
    }

    @Test
    fun `skipSteps with old ids map to parts and an id that is not a part is ignored`() {
        val raw = setOf("fetch_artwork", "detect_segments", "sync_jellyfin", "wait", "notify", "nonsense")
        assertEquals(setOf("fetch_artwork", "detect_segments"), ProcessingFeatures.skippableParts(raw))
        assertEquals(setOf("sync_jellyfin", "wait", "notify", "nonsense"), ProcessingFeatures.ignoredSkips(raw))
    }

    // ── PUT /api/config ─────────────────────────────────────────────────────────────────────────────────────────────

    private fun stored(): AppConfig = ProcessingCarryOver.settle(prod())

    @Test
    fun `an older admin that does not send processing and moves the pipeline card wins`() {
        val s = stored()
        val edited = s.copy(processing = null, scan = s.scan.copy(pipeline = s.scan.pipeline.map { if (it.step == "write_nfo") it.copy(enabled = false) else it }))
        val out = ProcessingCarryOver.settle(ProcessingCarryOver.reconcile(s, edited))
        assertFalse(out.processing!!.metadata.nfo)
        assertTrue(out.processing!!.metadata.autoReassert, "an option the card cannot see is not lost")
        assertEquals(ProcessingFeatures.HOURLY, out.processing!!.music.interval)
        assertFalse(out.scan.pipeline.first { it.step == "write_nfo" }.enabled)
    }

    @Test
    fun `an admin that sends processing back untouched and moves the card still lets the card win`() {
        val s = stored()
        val edited = s.copy(scanSchedule = "0 3 * * *")
        val out = ProcessingCarryOver.settle(ProcessingCarryOver.reconcile(s, edited))
        assertEquals("0 3 * * *", out.processing!!.find.schedule)
    }

    @Test
    fun `a changed processing wins and the pipeline is derived from it`() {
        val s = stored()
        val edited = s.copy(processing = s.processing!!.copy(metadata = s.processing!!.metadata.copy(nfo = false)))
        val out = ProcessingCarryOver.settle(ProcessingCarryOver.reconcile(s, edited))
        assertFalse(out.processing!!.metadata.nfo)
        assertFalse(out.scan.pipeline.first { it.step == "write_nfo" }.enabled)
        assertFalse(out.scan.pipeline.first { it.step == ProcessingFeatures.TELL_JELLYFIN }.enabled)
    }

    @Test
    fun `a save that moved nothing keeps the stored features`() {
        val s = stored()
        assertEquals(s.processing, ProcessingCarryOver.reconcile(s, s.copy(processing = null)).processing)
        assertEquals(s.processing, ProcessingCarryOver.reconcile(s, s).processing)
    }

    // ── the file ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a prod shaped file gains processing on load and keeps a pipeline`() {
        val path = pathFor("carry")
        platform.posix.remove(path)
        // what a pre-features install wrote: a pipeline and a schedule, no [processing]
        runBlocking { FileIo.writeText(Path(path), legacyToml(prod())) }
        val cfg = load(path).current
        assertNotNull(cfg.processing, "[processing] is carried over at load")
        assertEquals("30 */1 * * *", cfg.processing!!.find.schedule)
        assertTrue(cfg.scan.pipeline.isNotEmpty(), "[[scan.pipeline]] is still written for a rolled-back image")
        val text = runBlocking { FileIo.readText(Path(path)) }
        assertTrue("[processing" in text && "[[scan.pipeline]]" in text, "the file has both")
        val again = load(path).current
        assertEquals(cfg.processing, again.processing, "every [processing] key round-trips through the file")
        assertEquals(cfg.scan.pipeline, again.scan.pipeline)
        platform.posix.remove(path)
    }

    @Test
    fun `a new install starts on the fresh install features with an hourly Find`() {
        val path = pathFor("new")
        platform.posix.remove(path)
        val cfg = load(path).current
        assertEquals(ProcessingConfig(), cfg.processing)
        assertEquals(ProcessingFeatures.DEFAULT_FIND_SCHEDULE, cfg.scanSchedule)
        assertEquals(ProcessingConfig(), load(path).current.processing, "and stays there on the next boot")
        assertNull(cfg.scan.pipeline.firstOrNull { it.step == "notify" })
        platform.posix.remove(path)
    }

    /** The TOML of [cfg] as an older server wrote it: legacy fields only. */
    private fun legacyToml(cfg: AppConfig): String = buildString {
        appendLine("scan_schedule = \"${cfg.scanSchedule}\"")
        appendLine("[behavior]\nnotify_on_scan_done = false\n")
        appendLine("[scan]")
        for (s in cfg.scan.pipeline) {
            appendLine("\n[[scan.pipeline]]")
            appendLine("step = \"${s.step}\"")
            appendLine("enabled = ${s.enabled}")
            appendLine("recheck_unchanged = ${s.recheckUnchanged}")
            appendLine("refresh_this_year = \"${s.refreshThisYear}\"")
            appendLine("refresh_1_5y = \"${s.refresh1To5y}\"")
            appendLine("refresh_older = \"${s.refreshOlder}\"")
            appendLine("scope = \"${s.scope}\"")
            appendLine("overwrite = ${s.overwrite}")
            appendLine("auto_reassert = ${s.autoReassert}")
            appendLine("rebuild_every = \"${s.rebuildEvery}\"")
        }
    }
}
