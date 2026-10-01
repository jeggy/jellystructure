package dev.jellystructure.auth

import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.tv.burnInLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R351 (FR-R351-10/11) — a receiver's channel count reaches every place a stream is built for it. A Nest Hub said
 * `ch=2`, the profile carried it as an `Audio` codec profile only (which Jellyfin reads for songs), and the hub was
 * sent six-channel AAC its renderer refused (Shaka 3016).
 */
class AudioChannelLimitTest {

    /** What the Nest Hub's receiver declared in the re-test (`caps h264≤1280x720 L41 ceiling=none ch=2`). */
    private val hub = ClientCapabilities(
        containers = listOf("mp4", "ts"), videoCodecs = listOf("h264"), audioCodecs = listOf("aac", "mp3"),
        maxAudioChannels = 2, hlsOnly = true, maxH264Width = 1280, maxH264Height = 720, maxH264Level = 41,
    )
    private val surround = hub.copy(maxAudioChannels = 6, maxH264Width = 3840, maxH264Height = 2160, maxH264Level = 51)

    @Test fun a_stereo_receiver_limits_the_films_audio_and_its_conversion_to_two() {
        val p = deviceProfile(hub)
        assertTrue(""""Type":"VideoAudio","Conditions":[{"Condition":"LessThanEqual","Property":"AudioChannels","Value":"2","IsRequired":true}]""" in p, p)
        assertTrue(""""Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac,mp3","Protocol":"hls","Context":"Streaming","MaxAudioChannels":"2"}""" in p, p)
        // The `Audio` profile is for songs; a film's profile never relies on it.
        assertFalse(""""Type":"Audio",""" in p, p)
    }

    @Test fun a_six_channel_receiver_keeps_six() {
        val p = deviceProfile(surround)
        assertTrue(""""Property":"AudioChannels","Value":"6"""" in p, p)
        assertTrue(""""Context":"Streaming","MaxAudioChannels":"6"}""" in p, p)
        assertEquals(6, channelLimit(surround))
    }

    @Test fun the_hevc_conversion_carries_the_limit_too() {
        val p = deviceProfile(hub.copy(videoCodecs = listOf("h264", "hevc"), hlsHevc = true))
        assertTrue(""""Container":"mp4","Type":"Video","VideoCodec":"hevc,h264","AudioCodec":"aac,mp3","Protocol":"hls","Context":"Streaming","MaxAudioChannels":"2"}""" in p, p)
    }

    @Test fun a_client_that_takes_everything_negotiates_as_before() {
        val p = deviceProfile(ClientCapabilities())
        assertNull(channelLimit(ClientCapabilities()))
        assertFalse("AudioChannels" in p, p)
        assertFalse("MaxAudioChannels" in p, p)
    }

    @Test fun a_song_to_a_stereo_speaker_is_limited_by_an_audio_profile() {
        val p = audioDeviceProfile(ClientCapabilities(audioCodecs = listOf("mp3", "aac", "flac"), maxAudioChannels = 2))
        assertTrue(""""CodecProfiles":[{"Type":"Audio","Conditions":[{"Condition":"LessThanEqual","Property":"AudioChannels","Value":"2","IsRequired":true}]}]""" in p, p)
        // A phone's songs (no limit) keep the empty list.
        assertTrue(""""CodecProfiles":[]""" in audioDeviceProfile(ClientCapabilities()))
    }

    @Test fun the_conversion_url_carries_the_limit_when_jellyfin_left_it_out() {
        val url = "http://jf/videos/x/master.m3u8?VideoCodec=h264&AudioCodec=aac&ApiKey=t"
        assertEquals("$url&TranscodingMaxAudioChannels=2", withChannelLimit(url, hub))
        assertEquals("$url&TranscodingMaxAudioChannels=6", withChannelLimit(url, surround))
    }

    @Test fun the_conversion_url_is_left_alone_when_it_already_says_or_needs_nothing() {
        val said = "http://jf/videos/x/master.m3u8?VideoCodec=h264&TranscodingMaxAudioChannels=2&ApiKey=t"
        assertEquals(said, withChannelLimit(said, hub))
        val saidGlobal = "http://jf/videos/x/master.m3u8?MaxAudioChannels=2&ApiKey=t"
        assertEquals(saidGlobal, withChannelLimit(saidGlobal, hub))
        val direct = "http://jf/Videos/x/stream?Static=true&MediaSourceId=x"
        assertEquals(direct, withChannelLimit(direct, hub))
        val any = "http://jf/videos/x/master.m3u8?VideoCodec=h264"
        assertEquals(any, withChannelLimit(any, ClientCapabilities()))
        assertEquals(any, withChannelLimit(any, null))
    }

    @Test fun a_burn_in_keeps_the_receivers_limits_and_nothing_else() {
        val l = burnInLimits(hub.copy(supportsHdr10 = true, hlsHevc = true))
        assertEquals(2, l.maxAudioChannels); assertEquals(1280, l.maxH264Width); assertEquals(720, l.maxH264Height); assertEquals(41, l.maxH264Level)
        assertEquals(listOf("aac", "mp3"), l.audioCodecs)
        // The burn-in stays SDR h264/ts, as before.
        assertFalse(l.supportsHdr10); assertFalse(l.hlsHevc)
        assertTrue(""""Width","Value":"1280"""" in deviceProfile(l))
        assertEquals(ClientCapabilities(), burnInLimits(null))
    }
}
