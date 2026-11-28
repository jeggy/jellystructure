package dev.jellystructure.tv

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import dev.jellystructure.config.AppConfig
import dev.jellystructure.shared.tv.ClientCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** DEB-5506 (DEB-5503, FR-46) — `[ravilo] samsung_direct_play`. */
class SamsungDirectPlaySwitchTest {
    private val caps = ClientCapabilities(
        containers = listOf("mkv", "mp4"), videoCodecs = listOf("h264", "hevc"), audioCodecs = listOf("aac", "ac3"),
        maxAudioChannels = 6, hlsOnly = false, hlsHevc = true, audioByRestream = true,
    )

    @Test fun `a tizen device with the switch off negotiates as hls_only and nothing else changes`() {
        val out = capabilitiesForPlatform(caps, "tizen", samsungDirectPlay = false)
        assertTrue(out.hlsOnly)
        assertEquals(caps.copy(hlsOnly = true), out)
    }

    @Test fun `a tizen device with the switch on is unchanged`() {
        assertSame(caps, capabilitiesForPlatform(caps, "tizen", samsungDirectPlay = true))
    }

    @Test fun `a tizen device whose app does not send audio_by_restream negotiates as hls_only whatever the switch says`() {
        val old = caps.copy(audioByRestream = false)
        assertEquals(old.copy(hlsOnly = true), capabilitiesForPlatform(old, "tizen", samsungDirectPlay = true))
        assertEquals(old.copy(hlsOnly = true), capabilitiesForPlatform(old, "tizen", samsungDirectPlay = false))
        assertSame(old, capabilitiesForPlatform(old, "tv", samsungDirectPlay = true), "Android TV never reads the field")
    }

    @Test fun `Android TV with the switch off is unchanged`() {
        assertSame(caps, capabilitiesForPlatform(caps, "tv", samsungDirectPlay = false))
        assertSame(caps, capabilitiesForPlatform(caps, null, samsungDirectPlay = false))
    }

    @Test fun `a config without the key decodes to on and the key turns it off`() {
        val toml = Toml(inputConfig = TomlInputConfig(ignoreUnknownNames = true))
        assertTrue(toml.decodeFromString(AppConfig.serializer(), "").ravilo.samsungDirectPlay)
        assertEquals(false, toml.decodeFromString(AppConfig.serializer(), "[ravilo]\nsamsung_direct_play = false\n").ravilo.samsungDirectPlay)
    }
}
