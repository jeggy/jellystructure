package dev.jellystructure.media

import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.PipelineStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 317 — the new job type, its lane, and the mkvmerge id mapping. Dev review item 2's whole point is
 * that `avsync` is a lane of its own and NOT one the shared pool draws from; item 3's is that the lane
 * comes from the job type instead of being hard-coded.
 */
class AvSyncScheduleTest {
    @Test
    fun `the step maps to its job type — its dedupe key and its label`() {
        assertEquals(FileCheckSchedule.AVSYNC_JOB, FileCheckSchedule.jobTypeFor(FileCheckSteps.AVSYNC))
        assertEquals("avsync:/m/a.mkv", FileCheckSchedule.dedupeKey(FileCheckSchedule.AVSYNC_JOB, "/m/a.mkv"))
        assertTrue(FileCheckSchedule.AVSYNC_JOB in FileCheckSchedule.JOB_TYPES)
    }

    @Test
    fun `only the A-V sync check rides the avsync lane`() {
        assertEquals("avsync", FileCheckSchedule.laneFor(FileCheckSchedule.AVSYNC_JOB))
        assertEquals("segments", FileCheckSchedule.laneFor(FileCheckSchedule.VERIFY_JOB))
        assertEquals("segments", FileCheckSchedule.laneFor(FileCheckSchedule.LENGTHS_JOB))
        assertEquals("segments", FileCheckSchedule.laneFor(FileCheckSchedule.SUBTITLES_JOB))
    }

    @Test
    fun `the shared pool never draws from the avsync lane — and Empty queue still knows it`() {
        assertFalse(FileCheckSchedule.AVSYNC_LANE in MediaJobQueue.SHARED_POOL_LANES)
        assertTrue(FileCheckSchedule.AVSYNC_LANE in MediaJobQueue.QUEUE_NAMES)
    }

    @Test
    fun `a current result stands until the file or the model changes — never by cadence`() {
        val step = FileCheckSteps.defaultStep(FileCheckSteps.AVSYNC)
        assertFalse(step.recheckUnchanged)
        val stored = StoredFileCheck(size = 10, mtime = 20, checkedAtSec = 1, hasFinding = false)
        assertEquals(null, FileCheckSchedule.nextCadenceDueMs(stored, 2024, 2026, step))
        assertEquals(null, FileCheckSchedule.due(stored, 10, 20, nowMs = 9_999_999_999, releaseYear = 2024, currentYear = 2026, step = step))
        assertEquals(FileCheckDue.NO_RESULT, FileCheckSchedule.due(stored, 11, 20, nowMs = 1_000, releaseYear = 2024, currentYear = 2026, step = step))
        assertEquals(FileCheckDue.NO_RESULT, FileCheckSchedule.due(null, 10, 20, nowMs = 1_000, releaseYear = 2024, currentYear = 2026, step = step))
    }

    @Test
    fun `the step is seeded after the subtitle check — and only once`() {
        val pipeline = listOf(
            PipelineStep(step = "scan_files"), PipelineStep(step = FileCheckSteps.SUBTITLES),
            PipelineStep(step = "wait"), PipelineStep(step = "notify"),
        )
        val seeded = FileCheckSteps.seedAvSync(pipeline)
        assertEquals(
            listOf("scan_files", FileCheckSteps.SUBTITLES, FileCheckSteps.AVSYNC, "wait", "notify"),
            seeded.map { it.step },
        )
        assertEquals(seeded.map { it.step }, FileCheckSteps.seedAvSync(seeded).map { it.step })
        // An empty pipeline means "use the built-in default", which carries the step itself.
        assertTrue(FileCheckSteps.seedAvSync(emptyList()).isEmpty())
    }

    @Test
    fun `with no subtitle check in the pipeline it goes ahead of the trailing wait and notify`() {
        val pipeline = listOf(PipelineStep(step = "scan_files"), PipelineStep(step = "pull_tmdb"), PipelineStep(step = "notify"))
        assertEquals(
            listOf("scan_files", "pull_tmdb", FileCheckSteps.AVSYNC, "notify"),
            FileCheckSteps.seedAvSync(pipeline).map { it.step },
        )
    }

    @Test
    fun `mkvmerge's own ids are mapped to the specifiers ffmpeg uses`() {
        // A real `mkvmerge -J` shape: the video is track 0, so the audio ids are NOT the audio order.
        val out = """
            {"tracks":[
              {"id":0,"type":"video","properties":{}},
              {"id":1,"type":"audio","properties":{"language":"fao"}},
              {"id":2,"type":"subtitles","properties":{}},
              {"id":3,"type":"audio","properties":{"language":"dan"}}
            ]}
        """.trimIndent()
        assertEquals(mapOf("0:a:0" to 1, "0:a:1" to 3), AvSyncService.parseMkvAudioIds(out))
        assertEquals(emptyMap(), AvSyncService.parseMkvAudioIds("not json"))
        assertEquals(emptyMap(), AvSyncService.parseMkvAudioIds("""{"tracks":[]}"""))
    }
}
