package dev.jellystructure.ravilo.player

import androidx.media3.common.MediaLibraryInfo
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mkv.RaviloMatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** R294 — the fixtures and their generator are in src/test/resources/mkv/. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RaviloMatroskaExtractorTest {

    private class Run(val output: FakeExtractorOutput, val seeks: List<Long>)

    private fun run(extractor: Extractor, fixture: String): Run {
        val data = requireNotNull(javaClass.classLoader?.getResourceAsStream("mkv/$fixture")) { fixture }.use { it.readBytes() }
        val output = FakeExtractorOutput()
        extractor.init(output)
        val input = FakeExtractorInput.Builder().setData(data).build()
        val position = PositionHolder()
        val seeks = mutableListOf<Long>()
        while (true) {
            when (extractor.read(input, position)) {
                Extractor.RESULT_END_OF_INPUT -> return Run(output, seeks)
                Extractor.RESULT_SEEK -> {
                    seeks += position.position
                    check(seeks.size <= 8) { "seek loop: $seeks" }
                    input.setPosition(position.position.toInt())
                }
            }
        }
    }

    private fun stock() = MatroskaExtractor(DefaultSubtitleParserFactory(), 0)
    private fun patched() = RaviloMatroskaExtractor(DefaultSubtitleParserFactory(), 0)

    private fun FakeExtractorOutput.video() =
        (0 until trackOutputs.size()).map { trackOutputs.valueAt(it) }
            .single { it.lastFormat?.sampleMimeType?.startsWith("video/") == true }

    private val cleanVideoSamples by lazy { run(stock(), "clean.mkv").output.video().sampleCount }

    @Test fun theFixturesHaveFrames() = assertTrue("clean.mkv should have ~250 frames", cleanVideoSamples > 200)

    @Test fun stockExtractorFindsTracksAtTheEndAndHasDroppedEveryFrame() {
        for (fixture in listOf("tracks-after-cluster.mkv", "tracks-and-cues-at-end.mkv")) {
            val out = run(stock(), fixture).output
            assertTrue("$fixture: stock does reach Tracks, at EOF", out.tracksEnded)
            assertEquals("$fixture: stock has no video samples", 0, out.video().sampleCount)
        }
    }

    @Test fun patchedExtractorReadsTracksFirstAndKeepsEveryFrame() {
        for (fixture in listOf("tracks-after-cluster.mkv", "tracks-and-cues-at-end.mkv")) {
            val r = run(patched(), fixture)
            val video = r.output.video()
            assertEquals("$fixture: every frame", cleanVideoSamples, video.sampleCount)
            // Within one frame (25 fps) of the start: the ffmpeg remux behind the Cues-at-end fixture
            // starts its video at 5 ms, the other at 0. Anything later would mean frames were dropped.
            assertTrue("$fixture: the first frame is the first Cluster's", video.getSampleTimeUs(0) < 40_000L)
            assertTrue("$fixture: seekable", r.output.seekMap!!.isSeekable)
        }
    }

    @Test fun trackFetchIsTwoSeeksWhenCuesAreAtTheFront() {
        // To Tracks at EOF, then straight back to the first Cluster.
        assertEquals(2, run(patched(), "tracks-after-cluster.mkv").seeks.size)
    }

    @Test fun trackFetchThenCuesWhenBothAreAtTheEnd() {
        // To Tracks, to Cues, back to the first Cluster: the real arrival's shape.
        assertEquals(3, run(patched(), "tracks-and-cues-at-end.mkv").seeks.size)
    }

    @Test fun aNormalFileIsUntouched() {
        val stock = run(stock(), "clean.mkv")
        val patched = run(patched(), "clean.mkv")
        assertEquals(stock.seeks, patched.seeks)
        assertEquals(stock.output.video().sampleCount, patched.output.video().sampleCount)
    }

    @Test fun theFactorySwapsOnlyTheMatroskaExtractor() {
        val extractors = RaviloExtractorsFactory().createExtractors()
        assertEquals(1, extractors.count { it is RaviloMatroskaExtractor })
        assertEquals(0, extractors.count { it is MatroskaExtractor })
        assertTrue(extractors.size > 10)
    }

    @Test fun theCopyMatchesTheMedia3OnTheClasspath() {
        // RaviloMatroskaExtractor is a copy of 1.8.0's MatroskaExtractor. On a Media3 upgrade, re-copy
        // the new upstream file, re-apply the hunks marked "RAVILO R294", then update this version.
        assertEquals("1.8.0", MediaLibraryInfo.VERSION)
    }
}
