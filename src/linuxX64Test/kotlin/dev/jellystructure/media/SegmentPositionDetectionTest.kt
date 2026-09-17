package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 233 — chapter classification, the fingerprint window cuts and the self-repair purge. */
class SegmentPositionDetectionTest {
    private lateinit var dbPath: String
    private lateinit var store: MediaSegmentStore

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-segpos-${getpid()}.db"
        store = MediaSegmentStore(createDatabase(dbPath))
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    /** It's Always Sunny on production: "Opening Credits" at 104 s was written as the END credits. */
    @Test
    fun openingCreditsIsAnIntroAndNeverTheCredits() {
        val chapters = listOf(
            ChapterMarker(0, 104_000, "Cold Open"),
            ChapterMarker(104_000, 126_000, "Opening Credits"),
            ChapterMarker(126_000, 1_290_000, "Act One"),
            ChapterMarker(1_290_000, 1_320_000, "End Credits"),
        )
        val hit = assertNotNull(SegmentDetection.fromChapters(chapters, durationMs = 1_320_000))
        assertEquals(104_000L, hit.markers.introStartMs)
        assertEquals(126_000L, hit.markers.introEndMs)
        assertEquals(1_290_000L, hit.markers.creditsStartMs)
        assertEquals(ChapterSegmentKind.INTRO, hit.evidence.first { it.title == "Opening Credits" }.kind)
    }

    @Test
    fun aCreditsChapterInTheFirstHalfIsRefusedWhenTheLengthIsKnown() {
        val chapters = listOf(ChapterMarker(0, 90_000, "Scene"), ChapterMarker(90_000, 120_000, "Credits"), ChapterMarker(120_000, 1_200_000, "Scene"))
        assertNull(SegmentDetection.fromChapters(chapters, durationMs = 1_200_000))
        // Unknown length keeps the pre-233 behaviour: the 60 s guard alone.
        assertEquals(90_000L, SegmentDetection.fromChapters(chapters)?.markers?.creditsStartMs)
    }

    @Test
    fun headFramesStopAtHalfTheFile() {
        val frames = List(7_000) { it }                        // ~866 s of fingerprint
        val cut = SegmentDetection.headFrames(frames, 300_000) // a 5-minute file
        assertTrue(cut.size in 1_150..1_200, "kept ${cut.size}")   // (150 s − 2.64 s) / 0.1238 s ≈ 1 190
        assertEquals(frames, SegmentDetection.headFrames(frames, null))
        assertEquals(frames, SegmentDetection.headFrames(frames, 4 * 3_600_000L))
    }

    @Test
    fun tailFramesDropWhatLiesBeforeHalfAndKeepAbsoluteTime() {
        val frames = List(2_400) { it }                        // the last ~300 s
        // A 5-minute file: the tail window starts at 0, half is 150 s.
        val (cut, start) = SegmentDetection.tailFrames(frames, 0, 300_000)
        assertTrue(start >= 150_000 && start < 150_200, "start $start")
        assertEquals(cut.first(), frames.size - cut.size)      // the frames kept are the LATER ones
        // A 40-minute file: the window (starts at 2 100 s) is already past half — untouched.
        assertEquals(frames to 2_100_000L, SegmentDetection.tailFrames(frames, 2_100_000, 2_400_000))
    }

    @Test
    fun purgeDeletesTheWrongSideAndSparesAPersonsRows() {
        val dur = 350_824L
        // Bananer i pyjamas S01E02: the "intro" is the outro theme; the credits beside it are right.
        store.upsertSegment("show", "e2.mkv", 2, SegmentKind.INTRO, 333_713, 348_199, SegmentSource.FINGERPRINT, 0.8)
        store.upsertSegment("show", "e2.mkv", 2, SegmentKind.CREDITS, 333_650, null, SegmentSource.FINGERPRINT, 0.8)
        assertTrue(PipelineStepOps.holdsImplausible("show", "e2.mkv", 2, dur, store))
        assertEquals(1, PipelineStepOps.purgeImplausible("show", "e2.mkv", 2, dur, store))
        assertNull(store.getSegment("show", "e2.mkv", 2, SegmentKind.INTRO))
        assertNotNull(store.getSegment("show", "e2.mkv", 2, SegmentKind.CREDITS))
        assertFalse(PipelineStepOps.holdsImplausible("show", "e2.mkv", 2, dur, store))

        // Manual, locked and checked rows survive whatever they say.
        store.upsertSegment("show", "e3.mkv", 3, SegmentKind.CREDITS, 8_000, null, SegmentSource.MANUAL, null)
        store.upsertSegment("show", "e4.mkv", 4, SegmentKind.CREDITS, 8_000, null, SegmentSource.FINGERPRINT, 0.8, locked = true)
        store.upsertSegment("show", "e5.mkv", 5, SegmentKind.CREDITS, 8_000, null, SegmentSource.CHAPTER, null)
        store.setChecked("show", "e5.mkv", 5)
        for (n in 3..5) assertEquals(0, PipelineStepOps.purgeImplausible("show", "e$n.mkv", n, dur, store), "episode $n")
    }

    @Test
    fun anOverlapWithNoKnownLengthLosesBothAutomaticSides() {
        store.upsertSegment("show", "e6.mkv", 6, SegmentKind.INTRO, 3_879, 33_841, SegmentSource.FINGERPRINT, 0.8)
        store.upsertSegment("show", "e6.mkv", 6, SegmentKind.CREDITS, 8_512, null, SegmentSource.FINGERPRINT, 0.8)
        assertTrue(PipelineStepOps.holdsImplausible("show", "e6.mkv", 6, null, store))
        assertEquals(2, PipelineStepOps.purgeImplausible("show", "e6.mkv", 6, null, store))
    }

    /** An outro theme written as the only "intro" overlaps nothing; with no measured length only TMDB's
     *  runtime can flag it — and only by a wide margin. */
    @Test
    fun aRuntimeEstimateFlagsOnlyTheClearlyWrong() {
        store.upsertSegment("show", "e7.mkv", 7, SegmentKind.INTRO, 280_000, 295_000, SegmentSource.FINGERPRINT, 0.8)
        assertFalse(PipelineStepOps.holdsImplausible("show", "e7.mkv", 7, null, store))
        assertTrue(PipelineStepOps.holdsImplausible("show", "e7.mkv", 7, null, store, runtimeMinutes = 5))
        store.upsertSegment("show", "e8.mkv", 8, SegmentKind.INTRO, 150_000, 170_000, SegmentSource.FINGERPRINT, 0.8)
        assertFalse(PipelineStepOps.holdsImplausible("show", "e8.mkv", 8, null, store, runtimeMinutes = 5))
    }
}
