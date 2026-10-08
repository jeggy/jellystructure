package dev.jellystructure.tv

import dev.jellystructure.auth.deviceProfile
import dev.jellystructure.shared.tv.AudioTrack
import dev.jellystructure.shared.tv.ClientCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * R379 (owner decision 2026-10-08) — a device without a platform AC-3/E-AC-3 decoder has that audio re-encoded by the
 * server, never decoded by the app's FFmpeg extension (whose resampler crashes on a mid-file channel-count change).
 */
class PlatformAudioTest {

    /** What the Android app declares today: the base list + TrueHD/DTS through the FFmpeg extension. */
    private val android = ClientCapabilities(
        containers = listOf("mkv", "mp4", "avi", "mov"),
        audioCodecs = listOf("aac", "mp3", "flac", "opus", "ac3", "eac3", "truehd", "dts"),
        hlsAdaptive = true,
        hlsAudioRenditions = true,
    )

    private fun directPlayAudio(profile: String): List<String> =
        Regex("\"Type\":\"Video\",\"VideoCodec\":\"[^\"]*\",\"AudioCodec\":\"([^\"]*)\"").find(profile)!!.groupValues[1].split(',')

    @Test
    fun anOlderAppThatReportsNothingIsNegotiatedExactlyAsBefore() {
        assertSame(android, forPlatformAudio(android, "ac3"))
        assertTrue(missingPlatformAudio(android).isEmpty())
    }

    @Test
    fun aDeviceWithBothDecodersKeepsEverything() {
        val withDolby = android.copy(platformAudioDecoders = listOf("ac3", "eac3"))
        assertSame(withDolby, forPlatformAudio(withDolby, "ac3"))
    }

    @Test
    fun aDeviceWithNoAc3DecoderLeavesBothOutOfItsProfile() {
        val noDolby = android.copy(platformAudioDecoders = emptyList(), hlsHevcCapable = true)
        val narrowed = forPlatformAudio(noDolby, "aac")
        assertEquals(listOf("aac", "mp3", "flac", "opus", "truehd", "dts"), narrowed.audioCodecs)
        // The source's own audio is AAC: nothing about this play needs the HEVC copy path.
        assertFalse(narrowed.hlsHevc)
        val profile = deviceProfile(narrowed)
        assertFalse("ac3" in directPlayAudio(profile))
        assertFalse("eac3" in directPlayAudio(profile))
        // R297's transcode audio is the segment's list ∩ the declared one, so AC-3 is never copied into a stream either.
        assertFalse(Regex("\"TranscodingProfiles\":\\[[^\\]]*\"AudioCodec\":\"[^\"]*ac3").containsMatchIn(profile))
    }

    @Test
    fun onlyTheMissingOneLeaves() {
        val ac3Only = android.copy(platformAudioDecoders = listOf("ac3"))
        val narrowed = forPlatformAudio(ac3Only, null)
        assertTrue("ac3" in narrowed.audioCodecs)
        assertFalse("eac3" in narrowed.audioCodecs)
    }

    @Test
    fun anAc3PlayOnAnHevcCapableDeviceCopiesTheVideoInFmp4() {
        val noDolby = android.copy(platformAudioDecoders = emptyList(), hlsHevcCapable = true)
        val narrowed = forPlatformAudio(noDolby, "AC3")
        assertTrue(narrowed.hlsHevc)
        // fMP4 with HEVC first is what lets Jellyfin copy an HEVC source's picture (Phase 253's measurement).
        assertTrue(deviceProfile(narrowed).contains("\"Container\":\"mp4\""))
    }

    @Test
    fun anAc3PlayOnADeviceThatCannotTakeHevcInHlsStaysOnTheTsProfile() {
        val noDolby = android.copy(platformAudioDecoders = emptyList(), hlsHevcCapable = false)
        assertFalse(forPlatformAudio(noDolby, "eac3").hlsHevc)
    }

    @Test
    fun theDeclaredNothingGuardIsKept() {
        val declaresNothing = ClientCapabilities(platformAudioDecoders = emptyList())
        assertTrue(forPlatformAudio(declaresNothing, "ac3").audioCodecs.isEmpty())
    }

    @Test
    fun thePlayingTrackIsThePickedOneElseTheDefaultElseTheFirst() {
        val tracks = listOf(
            AudioTrack(index = 1, language = "eng", label = null, codec = "truehd"),
            AudioTrack(index = 2, language = "eng", label = null, codec = "ac3", isDefault = true),
            AudioTrack(index = 3, language = "dan", label = null, codec = "aac"),
        )
        assertEquals("aac", playingAudioCodec(tracks, 3))
        assertEquals("ac3", playingAudioCodec(tracks, null))
        assertEquals("ac3", playingAudioCodec(tracks, 99))
        assertEquals("truehd", playingAudioCodec(tracks.map { it.copy(isDefault = false) }, null))
        assertEquals(null, playingAudioCodec(emptyList(), null))
    }

    @Test
    fun aDeviceWithoutDolbyPicks314sAddedAacCopyAndNeedsNoEncodeAtAll() {
        // An AC-3 5.1 film that phase 314 gave an AAC "Stereo" copy: the narrowed list makes the original undecodable,
        // so 314's choice lands on the copy, which the device plays directly; no HEVC copy path is needed for it.
        val noDolby = android.copy(platformAudioDecoders = emptyList(), hlsHevcCapable = true)
        val audio = listOf(
            dev.jellystructure.filefix.JellyfinAudio(1, "ac3", "eng", null, isDefault = true),
            dev.jellystructure.filefix.JellyfinAudio(2, "aac", "eng", dev.jellystructure.filefix.STEREO_NAME, isDefault = false),
        )
        val copy = dev.jellystructure.filefix.copyForDevice(audio, null, forPlatformAudio(noDolby, null).audioCodecs)
        assertEquals(2, copy)
        val tracks = audio.map { AudioTrack(index = it.index, language = it.language, label = it.title, codec = it.codec, isDefault = it.isDefault) }
        assertFalse(forPlatformAudio(noDolby, playingAudioCodec(tracks, copy)).hlsHevc)
        // On a device WITH Dolby the original plays and no copy is picked.
        assertEquals(null, dev.jellystructure.filefix.copyForDevice(audio, null, forPlatformAudio(noDolby.copy(platformAudioDecoders = listOf("ac3", "eac3")), null).audioCodecs))
    }
}
