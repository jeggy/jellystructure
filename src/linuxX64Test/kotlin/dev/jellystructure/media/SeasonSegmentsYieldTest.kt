package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-5535 (FR-3) — a running `segments_season` steps aside for a play at its next episode or pair, and a resume
 * does not repeat what finished. The heuristic tier runs for real on a season whose markers are all locked (so no
 * ffprobe or ffmpeg runs); the passes are counted stand-ins, since a fingerprint needs real audio.
 */
class SeasonSegmentsYieldTest {
    private val dir = "/tmp/jellystructure-test-season-yield-${getpid()}"
    private lateinit var store: MediaSegmentStore

    @BeforeTest fun setUp() {
        platform.posix.system("mkdir -p '$dir'")
        store = MediaSegmentStore(createDatabase("$dir/test.db"))
    }

    @AfterTest fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        platform.posix.system("rm -rf '$dir'")
    }

    private fun episode(n: Int) = Episode(filename = "e$n.mkv", path = "$dir/e$n.mkv", seasonNumber = 1, episodeNumber = n, tracks = emptyList(), issueCount = 0)

    private val season = MediaItem(
        id = "show-1", title = "Show", year = 2020, kind = MediaKind.TV_SHOW, path = "$dir/show", tmdbId = null,
        originalLanguage = "en", posterPath = null, overview = null, issueCount = 0, scannedAt = 0L, tracks = emptyList(),
        episodes = (1..4).map { episode(it) },
    )

    /** Every marker locked, so the tier visits each episode and writes nothing. */
    private fun lockAll() {
        for (n in 1..4) for (kind in listOf(SegmentKind.INTRO, SegmentKind.CREDITS)) {
            store.upsertSegment(season.id, "e$n.mkv", n, kind, 1_000L, 9_000L, SegmentSource.MANUAL, null, locked = true)
        }
    }

    /** A tier over the real [PipelineStepOps.detectChapterAndHeuristic], yielding once [playingAfter] episodes ended. */
    private fun tier(playing: () -> Boolean, visited: MutableList<String>): suspend (Set<String>, suspend (String) -> Unit) -> Boolean = { skip, onKeyDone ->
        PipelineStepOps.detectChapterAndHeuristic(
            season, store, force = true, yieldToPlayback = playing, skipEpisodeKeys = skip,
            onEpisodeKeyDone = { visited += it; onKeyDone(it) },
        )
    }

    @Test fun `a season of four yields after episode two and its resume runs episodes three and four only`() = runBlocking {
        lockAll()
        val visited = mutableListOf<String>()
        var finishedEpisodes = 0
        val playing = { finishedEpisodes >= 2 }
        val firstRun = PipelineStepOps.runSeasonSegments(
            done = emptyList(), runFingerprint = false, isCancelled = { false },
            heuristic = { skip, onKeyDone -> tier(playing, visited)(skip) { finishedEpisodes++; onKeyDone(it) } },
            intro = { error("no fingerprint pass") }, outro = { error("no fingerprint pass") },
        )
        val yielded = firstRun as PipelineStepOps.SeasonRunOutcome.Yielded
        assertEquals(listOf("e1.mkv#1", "e2.mkv#2"), yielded.done)
        assertEquals(listOf("e1.mkv#1", "e2.mkv#2"), visited)

        visited.clear()
        val resumed = PipelineStepOps.runSeasonSegments(
            done = yielded.done, runFingerprint = false, isCancelled = { false },
            heuristic = tier({ false }, visited), intro = { error("no fingerprint pass") }, outro = { error("no fingerprint pass") },
        )
        assertEquals(PipelineStepOps.SeasonRunOutcome.Finished, resumed)
        assertEquals(listOf("e3.mkv#3", "e4.mkv#4"), visited, "a forced job skips the episodes it already did")
    }

    @Test fun `a yield after the last episode is not a yield`() = runBlocking {
        lockAll()
        var finished = 0
        val outcome = PipelineStepOps.runSeasonSegments(
            done = emptyList(), runFingerprint = false, isCancelled = { false },
            heuristic = { skip, onKeyDone -> tier({ false }, mutableListOf())(skip) { finished++; onKeyDone(it) } },
            intro = { false }, outro = { false },
        )
        assertEquals(PipelineStepOps.SeasonRunOutcome.Finished, outcome)
        assertEquals(4, finished)
    }

    @Test fun `a yield inside the intro pass resumes without the heuristic tier or a second fingerprint`() = runBlocking {
        lockAll()
        val visited = mutableListOf<String>()
        var introRuns = 0; var outroRuns = 0
        var introYields = true
        val intro: suspend () -> Boolean = { introRuns++; introYields }
        val outro: suspend () -> Boolean = { outroRuns++; false }

        val first = PipelineStepOps.runSeasonSegments(
            done = emptyList(), runFingerprint = true, isCancelled = { false },
            heuristic = tier({ false }, visited), intro = intro, outro = outro,
        ) as PipelineStepOps.SeasonRunOutcome.Yielded
        assertTrue(PipelineStepOps.TIER_HEURISTIC in first.done)
        assertFalse(PipelineStepOps.PASS_INTRO in first.done, "a pass that stopped mid-way is run again from its start")
        assertEquals(4, visited.size)

        visited.clear(); introYields = false
        val resumed = PipelineStepOps.runSeasonSegments(
            done = first.done, runFingerprint = true, isCancelled = { false },
            heuristic = tier({ false }, visited), intro = intro, outro = outro,
        )
        assertEquals(PipelineStepOps.SeasonRunOutcome.Finished, resumed)
        assertEquals(emptyList(), visited, "the heuristic tier is not run again")
        assertEquals(2, introRuns)
        assertEquals(1, outroRuns)
    }

    @Test fun `a finished intro pass is not run again when the outro pass yields`() = runBlocking {
        lockAll()
        var introRuns = 0; var outroYields = true
        val first = PipelineStepOps.runSeasonSegments(
            done = emptyList(), runFingerprint = true, isCancelled = { false },
            heuristic = tier({ false }, mutableListOf()), intro = { introRuns++; false }, outro = { outroYields },
        ) as PipelineStepOps.SeasonRunOutcome.Yielded
        assertTrue(PipelineStepOps.PASS_INTRO in first.done)

        outroYields = false
        val resumed = PipelineStepOps.runSeasonSegments(
            done = first.done, runFingerprint = true, isCancelled = { false },
            heuristic = { _, _ -> error("tier already done") }, intro = { introRuns++; false }, outro = { outroYields },
        )
        assertEquals(PipelineStepOps.SeasonRunOutcome.Finished, resumed)
        assertEquals(1, introRuns)
    }

    @Test fun `a cancel while yielding is a cancel`() = runBlocking {
        lockAll()
        val outcome = PipelineStepOps.runSeasonSegments(
            done = emptyList(), runFingerprint = false, isCancelled = { true },
            heuristic = { _, _ -> true }, intro = { false }, outro = { false },
        )
        assertEquals(PipelineStepOps.SeasonRunOutcome.Cancelled, outcome)
    }

    @Test fun `the walk never yields when the claim would not have held the row`() {
        // yieldsToPlayback is waitsForPlayback(row asks, household switch, playing): the three-way table is
        // PlaybackParkingTest's; the two cases that must not yield are a job that did not ask and a switch that is off.
        assertFalse(MediaJobQueue.waitsForPlayback(rowDefers = false, householdDefers = true, playing = true))
        assertFalse(MediaJobQueue.waitsForPlayback(rowDefers = true, householdDefers = false, playing = true))
        assertTrue(MediaJobQueue.waitsForPlayback(rowDefers = true, householdDefers = true, playing = true))
    }

    @Test fun `the tier carried by a requeued row survives its params round trip`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val params = dev.jellystructure.jobs.MediaJobParams(segmentSeason = 1, segmentDone = listOf("e1.mkv#1", PipelineStepOps.TIER_HEURISTIC))
        val back = json.decodeFromString(dev.jellystructure.jobs.MediaJobParams.serializer(), json.encodeToString(dev.jellystructure.jobs.MediaJobParams.serializer(), params))
        assertEquals(params.segmentDone, back.segmentDone)
        assertEquals(emptyList(), json.decodeFromString(dev.jellystructure.jobs.MediaJobParams.serializer(), """{"segmentSeason":1}""").segmentDone, "an old row has no progress")
    }
}
