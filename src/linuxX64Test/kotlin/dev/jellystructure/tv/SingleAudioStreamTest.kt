package dev.jellystructure.tv

import dev.jellystructure.auth.deviceProfile
import dev.jellystructure.shared.tv.ClientCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 253 — URLs below are shaped like the ones Jellyfin 12.1.0 returned for "Honeyman" (2026-09-20). */
class SingleAudioStreamTest {
    private val url = "/videos/9a58/master.m3u8?DeviceId=x&MediaSourceId=9a58&VideoCodec=h264&AudioCodec=ac3&AudioStreamIndex=5&SubtitleStreamIndex=6&SubtitleMethod=Encode&ApiKey=k"

    @Test fun the_index_is_read_from_what_jellyfin_did() = assertEquals(5, carriedAudioIndex(url, null))
    @Test fun what_jellyfin_did_outranks_what_was_asked() = assertEquals(5, carriedAudioIndex(url, 4))
    @Test fun it_is_not_confused_by_the_subtitle_index() = assertEquals(4, carriedAudioIndex("/v/master.m3u8?SubtitleStreamIndex=6&AudioStreamIndex=4", null))
    @Test fun a_url_without_one_falls_back_to_the_request() = assertEquals(7, carriedAudioIndex("/v/master.m3u8?VideoCodec=h264", 7))
    @Test fun nothing_known_is_null_not_zero() = assertNull(carriedAudioIndex(null, null))

    @Test fun the_transcode_profile_is_h264_ts_unless_the_client_opts_in() {
        val p = deviceProfile(ClientCapabilities(videoCodecs = listOf("h264", "hevc"), hlsOnly = true))
        assertTrue(""""TranscodingProfiles":[{"Container":"ts","Type":"Video","VideoCodec":"h264"""" in p)
        assertFalse(""""Container":"mp4","Type":"Video","VideoCodec":"hevc""" in p)
    }

    // R297 — the transcode's audio is what the client declared, never a codec it said it cannot play.
    @Test fun a_chromecast_without_ac3_gets_no_ac3_in_its_transcode() {
        val p = deviceProfile(ClientCapabilities(videoCodecs = listOf("h264", "hevc"), audioCodecs = listOf("aac", "mp3", "opus"), hlsOnly = true, hlsHevc = true))
        assertTrue(""""Container":"mp4","Type":"Video","VideoCodec":"hevc,h264","AudioCodec":"aac,mp3",""" in p, p)
    }

    @Test fun a_client_that_plays_ac3_keeps_the_full_list() {
        val android = ClientCapabilities(videoCodecs = listOf("h264", "hevc"), audioCodecs = listOf("aac", "mp3", "flac", "opus", "ac3", "eac3", "truehd", "dts"), hlsOnly = true, hlsHevc = true)
        assertTrue(""""AudioCodec":"aac,ac3,eac3,mp3","Protocol":"hls"""" in deviceProfile(android))
        assertTrue(""""Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac,ac3,mp3",""" in deviceProfile(android.copy(hlsHevc = false)))
    }

    @Test fun a_client_that_declares_no_audio_keeps_todays_profile() {
        assertTrue(""""AudioCodec":"aac,ac3,mp3","Protocol":"hls"""" in deviceProfile(ClientCapabilities(hlsOnly = true)))
    }

    @Test fun nothing_in_common_still_transcodes_to_aac() {
        assertTrue(""""AudioCodec":"aac","Protocol":"hls"""" in deviceProfile(ClientCapabilities(audioCodecs = listOf("opus"), hlsOnly = true)))
    }

    @Test fun hls_hevc_asks_for_fmp4_with_hevc_first() {
        val p = deviceProfile(ClientCapabilities(videoCodecs = listOf("h264", "hevc"), hlsOnly = true, hlsHevc = true))
        assertTrue(""""TranscodingProfiles":[{"Container":"mp4","Type":"Video","VideoCodec":"hevc,h264"""" in p)
    }
}
