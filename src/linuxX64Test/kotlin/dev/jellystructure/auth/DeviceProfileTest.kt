package dev.jellystructure.auth

import dev.jellystructure.shared.tv.ClientCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R183 — the DeviceProfile Jellyfin negotiates against. Every expectation here was first verified live
 * against the real server (Jellyfin 10.11.11) with the actual library files named in the comments; these
 * tests pin the resulting JSON so the negotiation can't silently regress the way R173's did.
 */
class DeviceProfileTest {

    private val sdrOnly = ClientCapabilities()
    private val hdrCapable = ClientCapabilities(supportsHdr10 = true, supportsHlg = true)

    @Test
    fun sdrOnlyClientAllowsNoHdrOrDolbyVisionExceptTheSdrBaseLayerVariant() {
        val ranges = allowedVideoRangeTypes(sdrOnly)
        assertEquals(listOf("SDR", "DOVIWithSDR"), ranges)
    }

    @Test
    fun hdr10ClientDirectPlaysDolbyVisionProfile8() {
        // "Contact Week": hevc Main 10, DvProfile=8, VideoRangeType=DOVIWithHDR10Plus. Live-verified:
        // with these values present PlaybackInfo returns SupportsDirectPlay=true.
        val ranges = allowedVideoRangeTypes(hdrCapable)
        assertContains(ranges, "DOVIWithHDR10Plus")
        assertContains(ranges, "DOVIWithHDR10")
        assertContains(ranges, "DOVIWithHLG")
        assertContains(ranges, "DOVIWithSDR")
    }

    @Test
    fun dolbyVisionOnlyVariantsStayGatedBehindARealDolbyVisionDecoder() {
        // Profile 5 (DOVI) has no HDR10 base layer, and profile 7 (DOVIWithEL*) needs the dual-layer
        // decode path — neither may be direct-played just because the device does HDR10.
        assertFalse(allowedVideoRangeTypes(hdrCapable).contains("DOVI"))
        assertFalse(allowedVideoRangeTypes(hdrCapable).contains("DOVIWithEL"))

        val dv = hdrCapable.copy(supportsDolbyVision = true)
        assertContains(allowedVideoRangeTypes(dv), "DOVI")
        assertFalse(allowedVideoRangeTypes(dv).contains("DOVIWithEL"))

        val dvEl = dv.copy(supportsDolbyVisionEl = true)
        assertContains(allowedVideoRangeTypes(dvEl), "DOVIWithEL")
        assertContains(allowedVideoRangeTypes(dvEl), "DOVIWithELHDR10Plus")
    }

    @Test
    fun invalidDolbyVisionIsNeverAllowed() {
        val everything = ClientCapabilities(
            supportsHdr10 = true, supportsHlg = true,
            supportsDolbyVision = true, supportsDolbyVisionEl = true,
        )
        assertFalse(allowedVideoRangeTypes(everything).any { it == "DOVIInvalid" })
    }

    @Test
    fun profileDeclaresAnHonestH264TranscodeTargetFromTheClientsOwnDecoderLimits() {
        val json = deviceProfile(
            hdrCapable.copy(maxH264Width = 4096, maxH264Height = 2176, maxH264Level = 52),
        )
        assertContains(json, """{"Condition":"LessThanEqual","Property":"VideoLevel","Value":"52","IsRequired":false}""")
        assertContains(json, """{"Condition":"LessThanEqual","Property":"Width","Value":"4096","IsRequired":false}""")
        assertContains(json, """{"Condition":"LessThanEqual","Property":"Height","Value":"2176","IsRequired":false}""")
        assertContains(json, """"Property":"VideoProfile","Value":"high|main|baseline|constrained baseline"""")
    }

    @Test
    fun unreportedDecoderLimitsFallBackToAUniversallyDecodable1080pTarget() {
        val json = deviceProfile(sdrOnly)
        assertContains(json, """{"Condition":"LessThanEqual","Property":"VideoLevel","Value":"51","IsRequired":false}""")
        assertContains(json, """{"Condition":"LessThanEqual","Property":"Width","Value":"1920","IsRequired":false}""")
        assertContains(json, """{"Condition":"LessThanEqual","Property":"Height","Value":"1080","IsRequired":false}""")
    }

    @Test
    fun noVideoBitrateConditionIsDeclared() {
        // Live-verified: a VideoBitrate condition doesn't just cap the transcode — it disqualifies direct
        // play of any source above it, which would needlessly transcode high-bitrate remuxes.
        assertFalse(deviceProfile(hdrCapable).contains("VideoBitrate"))
    }

    @Test
    fun rangeConditionIsRequiredAndTheH264TargetConditionsAreNot() {
        val json = deviceProfile(hdrCapable)
        assertContains(json, """"Property":"VideoRangeType","Value":"${allowedVideoRangeTypes(hdrCapable).joinToString("|")}","IsRequired":true""")
        assertTrue(json.contains(""""Codec":"h264","Conditions":["""))
    }

    /** 218/R245 amendment — the Chromecast receiver says hls_only + containers=[mp4,ts]; both were ignored,
     *  and an MKV was offered for direct play as if it were HLS. */
    @Test
    fun hlsOnlyGetsNoDirectPlayAndNamedContainersGetOnlyThose() {
        val receiver = deviceProfile(ClientCapabilities(containers = listOf("mp4", "ts"), hlsOnly = true))
        assertTrue(receiver.contains("\"DirectPlayProfiles\":[]"), receiver)
        assertTrue(receiver.contains("\"Protocol\":\"hls\""), receiver)
        val named = deviceProfile(ClientCapabilities(containers = listOf("mp4", "ts")))
        assertTrue(named.contains("\"Container\":\"mp4,ts\",\"Type\":\"Video\""), named)
        assertFalse(named.contains("mkv,mp4"), named)
        val silent = deviceProfile(ClientCapabilities())
        assertTrue(silent.contains("\"Container\":\"mkv,mp4,webm"), silent)
    }

    /** R265 (FR-R265-8) — text subtitles ride the HLS manifest only for a client that says it shows them
     *  (Safari, for AirPlay) AND takes only HLS; the Chromecast receiver (hls_only, no flag) keeps them
     *  sideloaded. Live-verified on Jellyfin 12.1.0: `Method: Hls` lists every SubRip track in master.m3u8. */
    @Test
    fun hlsSubtitlesOnlyForAnHlsOnlyClientThatAsksForThem() {
        val safari = deviceProfile(ClientCapabilities(hlsOnly = true, hlsSubtitles = true))
        assertTrue(safari.contains("""{"Format":"subrip","Method":"Hls"}"""), safari)
        assertTrue(safari.contains("""{"Format":"pgssub","Method":"Encode"}"""), safari)
        val receiver = deviceProfile(ClientCapabilities(hlsOnly = true))
        assertTrue(receiver.contains("""{"Format":"subrip","Method":"External"}"""), receiver)
        val notHlsOnly = deviceProfile(ClientCapabilities(hlsSubtitles = true))
        assertTrue(notHlsOnly.contains("""{"Format":"subrip","Method":"External"}"""), notHlsOnly)
    }
}
