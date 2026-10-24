package dev.jellystructure.config

import dev.jellystructure.io.FileIo
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 261 (FR-261-4/6, dev review item 8, acceptance 3) — the file checks join an existing pipeline once,
 * with their own defaults, disabled when `behavior.verify_files = false`; the written TOML round-trips; a step
 * the operator removes is not re-added on the next boot.
 */
class FileCheckStepsConfigTest {
    private fun pathFor(name: String) = "/tmp/jellystructure-test-filecheck-${getpid()}-$name.toml"

    private fun load(path: String): ConfigStore = runBlocking { ConfigStore(path).also { it.load() } }

    private val pipeline = """
        |[scan]
        |
        |[[scan.pipeline]]
        |step = "scan_files"
        |
        |[[scan.pipeline]]
        |step = "pull_tmdb"
        |
        |[[scan.pipeline]]
        |step = "notify"
        |""".trimMargin()

    @Test
    fun `verify_files false boots with both steps added and off — and the pipeline carries over to the features`() {
        val path = pathFor("off")
        runBlocking { FileIo.writeText(Path(path), "[behavior]\nverify_files = false\n\n$pipeline") }
        val cfg = load(path).current
        // Phase 269 seeds build_recommendations the same way, Phase 273 check_subtitles and Phase 317 check_av_sync, all of them
        // enabled; the music steps (275–277) are seeded too, their own order is tested in MusicIngestTest/MusicMatchTest.
        // DEB-1664 (FR-2.3): the seeded pipeline is then carried over to [processing], and [[scan.pipeline]] is what the features derive to.
        assertEquals(listOf("scan_files", "pull_tmdb", "check_av_sync", "check_subtitles", "build_recommendations", "build_suggestions"),
            cfg.scan.pipeline.filter { it.enabled }.map { it.step }.filterNot { MusicSteps.isMusic(it) })
        assertEquals("report", cfg.subtitleCheck.action, "an existing install's subtitle check starts on Only report")
        assertEquals(listOf(false, false), cfg.scan.pipeline.filter { it.step in FileCheckSteps.ALL }.map { it.enabled })
        val health = cfg.processing!!.health
        assertTrue(health.enabled && !health.verify && !health.lengths && health.avSync, "the two file checks are off, the sound check on: $health")
        assertTrue(cfg.scan.fileCheckStepsSeeded)
        platform.posix.remove(path)
    }

    @Test
    fun `the seeded steps carry their own cadence and round-trip through the file`() {
        val path = pathFor("on")
        runBlocking { FileIo.writeText(Path(path), pipeline) }
        load(path)
        val reread = load(path).current   // what persist() wrote, decoded again
        val verify = reread.scan.pipeline.single { it.step == FileCheckSteps.VERIFY }
        val lengths = reread.scan.pipeline.single { it.step == FileCheckSteps.LENGTHS }
        assertEquals(listOf(true, true, "5years", "5years", "5years"), listOf(verify.enabled, verify.recheckUnchanged, verify.refreshThisYear, verify.refresh1To5y, verify.refreshOlder))
        assertEquals(listOf("yearly", "2years", "5years"), listOf(lengths.refreshThisYear, lengths.refresh1To5y, lengths.refreshOlder))
        platform.posix.remove(path)
    }

    @Test
    fun `a part the operator switched off stays off on the next boot`() {
        val path = pathFor("removed")
        runBlocking { FileIo.writeText(Path(path), pipeline) }
        val store = load(path)
        val p = store.current.processing!!
        runBlocking { store.update(store.current.copy(processing = p.copy(health = p.health.copy(lengths = false)))) }
        val again = load(path).current
        assertFalse(again.processing!!.health.lengths)
        assertFalse(again.scan.pipeline.single { it.step == FileCheckSteps.LENGTHS }.enabled)
        platform.posix.remove(path)
    }

    @Test
    fun `an empty pipeline carries over to the fresh install features — and they run the file steps`() {
        val path = pathFor("empty")
        runBlocking { FileIo.writeText(Path(path), "[behavior]\nverify_files = true\n") }
        val cfg = load(path).current
        assertEquals(ProcessingConfig(), cfg.processing?.copy(find = FindProcessing()))
        assertEquals(listOf("scan_files", "pull_tmdb", "fetch_artwork", "sync_imdb_ratings", "write_nfo", "detect_drift", "sync_jellyfin", "rescan_arr", "verify_files", "check_track_lengths", "check_av_sync", "prewarm_subtitles", "check_subtitles", "build_recommendations"),
            dev.jellystructure.media.effectivePipeline(cfg).map { it.step }.filterNot { MusicSteps.isMusic(it) })
        platform.posix.remove(path)
    }

    @Test
    fun `a new install starts its subtitle check on fix`() {
        val path = pathFor("new")
        platform.posix.remove(path)
        val cfg = load(path).current
        assertEquals("fix", cfg.subtitleCheck.action)
        assertTrue(cfg.scan.subtitleCheckSeeded)
        assertEquals("fix", load(path).current.subtitleCheck.action, "and stays there on the next boot")
        platform.posix.remove(path)
    }
}
