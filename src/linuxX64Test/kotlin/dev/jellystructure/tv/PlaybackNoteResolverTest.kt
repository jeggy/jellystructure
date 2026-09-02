package dev.jellystructure.tv

import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 185 (FR-185-6) — "one predicate, two consumers": [playbackNoteFires] must agree with Phase 177's
 * own forced-transcode VideoBitrate comparison (bitrate > 0.9 × ceiling), keyed per the file's own codec
 * since a device's ceiling is recorded per codec (FR-185-1's own reasoning: this exact TV always reports
 * its AVC decoder because R183/R216 force an AVC transcode target, so a single shared column would
 * compare an HEVC REMUX against the wrong ceiling).
 */
class PlaybackNoteResolverTest {

    private fun videoTrack(codec: String = "hevc", bitrate: Int? = 82_000_000) = Track(
        streamIndex = 0, specifier = "0:v:0", kind = TrackKind.VIDEO, codec = codec,
        language = null, title = null, default = true, forced = false,
        videoBitrate = bitrate,
    )

    private fun caps(hevc: Long? = 60_000_000L, h264: Long? = null) =
        DeviceDecodeCapabilities(hevcMaxBitrate = hevc, h264MaxBitrate = h264, measuredAt = 1000L)

    @Test
    fun `an 82 Mbps HEVC file against a 60 Mbps HEVC ceiling fires`() {
        // The reported Till Daybreak case: 82 Mbps against a 60 Mbps ceiling — 82 > 0.9*60=54.
        assertTrue(playbackNoteFires(videoTrack(codec = "hevc", bitrate = 82_000_000), caps(hevc = 60_000_000L)))
    }

    @Test
    fun `right at the 0-9 margin does not fire`() {
        assertFalse(playbackNoteFires(videoTrack(codec = "hevc", bitrate = 54_000_000), caps(hevc = 60_000_000L)))
    }

    @Test
    fun `just over the margin fires`() {
        assertTrue(playbackNoteFires(videoTrack(codec = "hevc", bitrate = 54_000_001), caps(hevc = 60_000_000L)))
    }

    @Test
    fun `a null ceiling for the file's codec never fires even with a ceiling recorded for the other codec`() {
        // The exact bug a single shared-ceiling column would cause: an HEVC file must be compared
        // against the HEVC ceiling, never the H264 one, even when only H264 is known.
        assertFalse(playbackNoteFires(videoTrack(codec = "hevc", bitrate = 82_000_000), caps(hevc = null, h264 = 30_000_000L)))
    }

    @Test
    fun `an h264 file uses the h264 ceiling not the hevc one`() {
        assertTrue(playbackNoteFires(videoTrack(codec = "h264", bitrate = 40_000_000), caps(hevc = 60_000_000L, h264 = 20_000_000L)))
    }

    @Test
    fun `no capabilities at all never fires`() {
        assertFalse(playbackNoteFires(videoTrack(), null))
    }

    @Test
    fun `no video bitrate never fires`() {
        assertFalse(playbackNoteFires(videoTrack(bitrate = null), caps()))
    }

    @Test
    fun `a non-video track never fires`() {
        val audio = Track(streamIndex = 1, specifier = "0:a:0", kind = TrackKind.AUDIO, codec = "aac", language = "eng", title = null, default = true, forced = false)
        assertFalse(playbackNoteFires(audio, caps()))
    }

    @Test
    fun `an unrecognized codec never fires`() {
        assertFalse(playbackNoteFires(videoTrack(codec = "av1", bitrate = 200_000_000), caps(hevc = 60_000_000L)))
    }
}
