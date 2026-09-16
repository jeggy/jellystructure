package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 222 (FR-222-6/7) — the stored waveform envelope and the orphan sweep, against a real scratch DB. */
class SegmentWaveformAndPruneTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var store: MediaSegmentStore

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-segwave-${getpid()}.db"
        db = createDatabase(dbPath)
        store = MediaSegmentStore(db)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) runCatching { platform.posix.remove("$dbPath$suffix") }
    }

    @Test
    fun envelopeRoundTripsAndAFailureIsRememberedDistinctly() {
        assertNull(store.getWaveform("show", "S01E01.mkv", 1))
        assertFalse(store.hasWaveform("show", "S01E01.mkv", 1))
        store.putWaveform("show", "S01E01.mkv", 1, 1_000L, byteArrayOf(0, 50, 100))
        val got = store.getWaveform("show", "S01E01.mkv", 1)!!
        assertEquals(1_000L, got.bucketMs)
        assertContentEquals(byteArrayOf(0, 50, 100), got.peaks)
        store.putWaveform("show", "S01E02.mkv", 2, 0L, ByteArray(0))
        assertTrue(store.hasWaveform("show", "S01E02.mkv", 2))
        assertEquals(0L, store.getWaveform("show", "S01E02.mkv", 2)!!.bucketMs)
        assertEquals(2L, store.waveformCount())
    }

    @Test
    fun pruneRemovesOnlyTheKeysNoEpisodeCarriesAnyMore() {
        store.upsertSegment("show", "S01E01.mkv", 1, SegmentKind.INTRO, 30_000L, 90_000L, SegmentSource.FINGERPRINT, 0.9)
        store.upsertSegment("show", "S01E01 - old name.mkv", 1, SegmentKind.INTRO, 30_000L, 90_000L, SegmentSource.FINGERPRINT, 0.9)
        store.recordEvidence("show", "S01E01 - old name.mkv", 1, SegmentKind.INTRO, EvidenceType.FINGERPRINT_MATCH, 30_000L, 90_000L, null, true)
        store.putWaveform("show", "S01E01 - old name.mkv", 1, 1_000L, byteArrayOf(1, 2, 3))
        store.putWaveform("show", "S01E09.mkv", 9, 1_000L, byteArrayOf(1))   // envelope-only orphan
        store.upsertSegment("film", "", 0, SegmentKind.CREDITS, 5_000_000L, null, SegmentSource.HEURISTIC, 0.7)

        val removed = store.pruneOrphans("show", setOf("S01E01.mkv" to 1))
        assertEquals(2, removed)
        assertEquals(1, store.segmentsForItem("show").size)
        assertEquals("S01E01.mkv", store.segmentsForItem("show").single().episodeKey)
        assertTrue(store.evidenceForEpisode("show", "S01E01 - old name.mkv", 1).isEmpty())
        assertNull(store.getWaveform("show", "S01E01 - old name.mkv", 1))
        assertNull(store.getWaveform("show", "S01E09.mkv", 9))
        // Another title is untouched, and a movie's sentinel key is valid for a movie.
        assertEquals(1, store.segmentsForItem("film").size)
        assertEquals(0, store.pruneOrphans("film", setOf("" to 0)))
        assertEquals(0, store.pruneOrphans("show", setOf("S01E01.mkv" to 1)))
    }

    @Test
    fun envelopeAccumulatorFoldsPcmIntoPeaksAcrossChunkBoundaries() {
        // 4 samples per bucket; samples: 0, 16384 (50 %), -32768 (100 %), 0 | 3276 (10 %), 0, 0, 0 | 32767 (tail)
        fun s16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
        val pcm = s16(0) + s16(16384) + s16(-32768) + s16(0) + s16(3276) + s16(0) + s16(0) + s16(0) + s16(32767)
        val acc = EnvelopeAccumulator(4)
        // Feed in odd-sized chunks so a sample is split across two feeds.
        acc.feed(pcm.copyOfRange(0, 5))
        acc.feed(pcm.copyOfRange(5, 12))
        acc.feed(pcm.copyOfRange(12, pcm.size))
        assertContentEquals(byteArrayOf(100, 9, 99), acc.finish())
    }
}
