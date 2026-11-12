package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-4258 (DEB-4256 FR-6, FR-7, acceptance 22) — the waiting 503's body, read leniently: an older or newer server never breaks the phone. */
class DownloadWaitReasonTest {
    @Test fun `encoder_full carries the count and unavailable carries none`() {
        assertEquals(DownloadWaitReason.EncoderFull(3), DownloadWaitReason.parse("""{"error":"busy","reason":"encoder_full","streams":3}"""))
        assertEquals(DownloadWaitReason.Unavailable, DownloadWaitReason.parse("""{"error":"cannot","reason":"unavailable"}"""))
    }

    @Test fun `a count-less or non-positive encoder_full is the busy line's`() {
        assertEquals(DownloadWaitReason.EncoderFull(null), DownloadWaitReason.parse("""{"reason":"encoder_full"}"""))
        assertEquals(DownloadWaitReason.EncoderFull(null), DownloadWaitReason.parse("""{"reason":"encoder_full","streams":0}"""))
    }

    @Test fun `no reason or an unknown one or other fields or text that is not JSON are the generic wait`() {
        assertEquals(DownloadWaitReason.Unknown, DownloadWaitReason.parse("""{"error":"busy"}"""))
        assertEquals(DownloadWaitReason.Unknown, DownloadWaitReason.parse("""{"reason":"something_new","streams":9,"extra":true}"""))
        assertEquals(DownloadWaitReason.Unknown, DownloadWaitReason.parse("Service Unavailable"))
        assertEquals(DownloadWaitReason.Unknown, DownloadWaitReason.parse(""))
        assertEquals(DownloadWaitReason.Unknown, DownloadWaitReason.parse(null))
    }
}
