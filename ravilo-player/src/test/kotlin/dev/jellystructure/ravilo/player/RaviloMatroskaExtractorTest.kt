package dev.jellystructure.ravilo.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
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

    // DEB-3162 (FR-313-5d) — a hand-built MKV whose only track is A_EAC3. ffmpeg cannot write JOC, so the
    // first syncframe is assembled from its header fields: 48 kHz, 6 blocks, 5.1, no optional BSI fields,
    // then the additional-BSI flag, with or without the JOC marker (addbsil = 1, byte = 1).
    private fun eac3Syncframe(joc: Boolean): ByteArray {
        val bits = StringBuilder()
        fun put(value: Int, width: Int) = bits.append(Integer.toBinaryString(value).padStart(width, '0'))
        put(0x0B77, 16); put(0, 2); put(0, 3); put(383, 11) // sync, strmtyp, substreamid, frmsiz (768 B)
        put(0, 2); put(3, 2); put(7, 3); put(1, 1) // fscod, numblkscod, acmod, lfeon
        put(16, 5); put(31, 5); put(0, 1); put(0, 1); put(0, 1) // bsid, dialnorm, compre, mixmdate, infomdate
        if (joc) { put(1, 1); put(1, 6); put(1, 8) } else put(0, 1)
        while (bits.length % 8 != 0) bits.append('0')
        val frame = ByteArray(768)
        for (i in 0 until bits.length / 8) frame[i] = bits.substring(i * 8, i * 8 + 8).toInt(2).toByte()
        return frame
    }

    private fun ebml(id: Long, vararg body: ByteArray): ByteArray {
        val payload = body.fold(ByteArray(0)) { a, b -> a + b }
        val idBytes = generateSequence(id) { it shr 8 }.takeWhile { it > 0 }.map { it.toByte() }.toList().reversed().toByteArray()
        val size = payload.size.toLong() or 0x10_00_00_00L // four-byte size
        return idBytes + byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) + payload
    }

    private fun uint(id: Long, v: Int) = ebml(id, byteArrayOf(v.toByte()))

    private fun eac3Mkv(joc: Boolean): ByteArray {
        val frame = eac3Syncframe(joc)
        val track = ebml(
            0xAE, uint(0xD7, 1), uint(0x73C5, 1), uint(0x83, 2), ebml(0x86, "A_EAC3".toByteArray()),
            ebml(0xE1, ebml(0xB5, java.nio.ByteBuffer.allocate(8).putDouble(48000.0).array()), uint(0x9F, 6)),
        )
        val blocks = (0 until 3).map { ebml(0xA3, byteArrayOf(0x81.toByte(), 0, (it * 32).toByte(), 0x80.toByte()) + frame) }
        val segment = ebml(0x18538067, ebml(0x1549A966, ebml(0x2AD7B1, byteArrayOf(0x0F, 0x42, 0x40))),
            ebml(0x1654AE6B, track), ebml(0x1F43B675, uint(0xE7, 0), *blocks.toTypedArray()))
        return ebml(0x1A45DFA3, ebml(0x4282, "matroska".toByteArray())) + segment
    }

    /**
     * The format a player's renderer is first handed for the track: a real [SampleQueue] behind the
     * extractor, read the way a renderer starts (FLAG_REQUIRE_FORMAT), after the whole file is in.
     */
    private fun firstFormatSeenByThePlayer(extractor: Extractor, joc: Boolean): Pair<Format?, Format?> {
        val queues = mutableListOf<SampleQueue>()
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput =
                SampleQueue.createWithoutDrm(DefaultAllocator(true, 4096)).also { queues += it }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        }
        extractor.init(output)
        val input = FakeExtractorInput.Builder().setData(eac3Mkv(joc)).build()
        val position = PositionHolder()
        while (extractor.read(input, position) != Extractor.RESULT_END_OF_INPUT) Unit
        val queue = queues.single()
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        val result = queue.read(holder, buffer, SampleStream.FLAG_REQUIRE_FORMAT, false)
        assertEquals(C.RESULT_FORMAT_READ, result)
        return holder.format to queue.upstreamFormat
    }

    @Test fun anMkvEac3TrackCarryingJocDeclaresJoc() {
        val (first, upstream) = firstFormatSeenByThePlayer(patched(), joc = true)
        assertEquals("audio/eac3-joc", first?.sampleMimeType)
        assertEquals("audio/eac3-joc", upstream?.sampleMimeType)
        assertEquals(6, first?.channelCount)
        assertEquals(48000, first?.sampleRate)
    }

    @Test fun aPlainMkvEac3TrackStaysEac3() {
        val (first, _) = firstFormatSeenByThePlayer(patched(), joc = false)
        assertEquals("audio/eac3", first?.sampleMimeType)
    }

    @Test fun theStockExtractorNamesTheSameJocTrackPlain() {
        val (first, _) = firstFormatSeenByThePlayer(stock(), joc = true)
        assertEquals("audio/eac3", first?.sampleMimeType)
    }

    @Test fun theCopyMatchesTheMedia3OnTheClasspath() {
        // RaviloMatroskaExtractor is a copy of 1.8.0's MatroskaExtractor. On a Media3 upgrade, re-copy
        // the new upstream file, re-apply the hunks marked "RAVILO R294", then update this version.
        assertEquals("1.8.0", MediaLibraryInfo.VERSION)
    }
}
