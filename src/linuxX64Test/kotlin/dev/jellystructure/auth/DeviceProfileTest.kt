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
        // "Disclosure Day": hevc Main 10, DvProfile=8, VideoRangeType=DOVIWithHDR10Plus. Live-verified:
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
}
