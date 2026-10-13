package dev.jellystructure.media

import dev.jellystructure.config.FileCheckSteps
import dev.jellystructure.config.PipelineStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `the Jobs group line carries its own lane — additive on the wire`() {
        // FR-317-14 — the group row badged itself `segments` for every type, so one avsync job showed two
        // disagreeing badges (the Running card beside it reads the row's real lane).
        val group = JobGroup(
            type = FileCheckSchedule.AVSYNC_JOB, label = "Check A/V sync",
            lane = FileCheckSchedule.laneFor(FileCheckSchedule.AVSYNC_JOB),
            waiting = 1, running = emptyList(), doneToday = 0, failedToday = 0, findingsToday = 0,
        )
        assertEquals(FileCheckSchedule.AVSYNC_LANE, group.lane)
        // The wire rule: the field is additive, so a payload without it still reads as what every group
        // was badged before — an installed admin page and an older server stay in step.
        val old = """{"type":"verify_file","label":"Verify files","waiting":2,"running":[],"done_today":1,"failed_today":0,"findings_today":0}"""
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(JobGroup.serializer(), old)
        assertEquals("segments", decoded.lane)
        assertEquals(2, decoded.waiting)
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

    // ── FR-317-21 / FR-317-26: a document that arrives short, and one that is a refusal ──────────

    @Test
    fun `a document cut inside the second audio track yields the first id and never the second`() {
        val ident = AvSyncService.parseMkvIdentification(MkvIdentificationFixtures.CUT_INSIDE_THE_SECOND_AUDIO_TRACK)
        // The prefix is read as far as it is whole: track 1 is a complete object, track 2 is cut
        // mid-`track_name`. `0:a:1` must be absent rather than guessed, and the ordinal of the id that WAS
        // read must be the one it would have had in the whole document.
        assertEquals(mapOf("0:a:0" to 1), ident.ids)
        // A short document is not a failed identification: mkvmerge did identify the file.
        assertFalse(ident.failed)
        assertTrue(ident.truncated)
        assertNull(ident.error)
    }

    @Test
    fun `a document cut inside the only audio track yields nothing rather than the video's id`() {
        val ident = AvSyncService.parseMkvIdentification(MkvIdentificationFixtures.CUT_INSIDE_THE_FIRST_AUDIO_TRACK)
        assertEquals(emptyMap(), ident.ids)
        assertTrue(ident.failed)        // nothing usable came back at all
    }

    @Test
    fun `an errors document is a failed identification and not an empty container`() {
        val failed = AvSyncService.parseMkvIdentification(MkvIdentificationFixtures.ERROR_DOCUMENT_FROM_A_NON_ASCII_PATH)
        assertEquals(emptyMap(), failed.ids)
        assertTrue(failed.failed)
        // The WARN quotes mkvmerge's own first line, trailing newline and all trimmed off.
        assertEquals("The file 'f' could not be opened for reading: open file error.", failed.error)

        val empty = AvSyncService.parseMkvIdentification("""{"errors":[],"warnings":[],"tracks":[]}""")
        assertEquals(emptyMap(), empty.ids)
        assertFalse(empty.failed)       // a container with nothing in it is not a refusal to look
        assertNull(empty.error)

        val notJson = AvSyncService.parseMkvIdentification("not json")
        assertTrue(notJson.failed)
        assertNull(notJson.error)       // there is no errors array to quote
    }

    @Test
    fun `the WARN carries the path the byte count and mkvmerge's own first error line`() {
        val doc = MkvIdentificationFixtures.ERROR_DOCUMENT_FROM_A_NON_ASCII_PATH
        val warn = AvSyncService.probeFailureWarning("/mnt/media/x/a.mkv", doc.encodeToByteArray().size, AvSyncService.parseMkvIdentification(doc))
        assertTrue(warn.contains("111 bytes back"), warn)
        assertTrue(warn.contains("could not be opened for reading"), warn)
        assertTrue(warn.contains("/mnt/media/x/a.mkv"), warn)
        // The exit status is deliberately absent: it cannot be had where this line is written.
        assertFalse(warn.contains("exit"), warn)

        // A document that arrived short has no error line to quote, and says so rather than inventing one.
        val short = MkvIdentificationFixtures.CUT_INSIDE_THE_FIRST_AUDIO_TRACK
        val shortWarn = AvSyncService.probeFailureWarning("/mnt/media/x/b.mkv", short.encodeToByteArray().size, AvSyncService.parseMkvIdentification(short))
        assertTrue(shortWarn.contains("2110 bytes back"), shortWarn)
        assertTrue(shortWarn.contains("stops part-way through its own track list"), shortWarn)
        assertFalse(shortWarn.contains("mkvmerge says"), shortWarn)
    }

    @Test
    fun `a document with both errors and a usable track array still gives its ids`() {
        // mkvmerge reports plenty about files it identifies perfectly well.
        val ident = AvSyncService.parseMkvIdentification(
            """{"errors":["something to say about it"],"warnings":[],
                "tracks":[{"id":0,"type":"video","properties":{}},{"id":1,"type":"audio","properties":{}}]}""",
        )
        assertEquals(mapOf("0:a:0" to 1), ident.ids)
        assertEquals("something to say about it", ident.error)
    }

    @Test
    fun `an audio track with no readable id stops the walk rather than advancing the ordinal`() {
        val out = """
            {"tracks":[
              {"id":0,"type":"video","properties":{}},
              {"type":"audio","properties":{"language":"fao"}},
              {"id":3,"type":"audio","properties":{"language":"dan"}}
            ]}
        """.trimIndent()
        // `0:a:0` has no id, so `0:a:1` cannot be known to be the second audio track either: the map is
        // empty, never `{"0:a:1" to 3}`.
        assertEquals(emptyMap(), AvSyncService.parseMkvAudioIds(out))
    }
}
