package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 192 (FR-192-1) — the magic-byte sniff that stands between a downloaded response and disk. The
 * live incident this guards against: a CDN 504 Gateway Timeout HTML page was written to
 * `clearlogo.png` and stayed there for weeks because nothing ever checked what the bytes actually were.
 */
class ArtworkSignatureTest {

    private fun ascii(s: String) = s.map { it.code.toByte() }.toByteArray()

    @Test
    fun pngSignatureRecognized() {
        val bytes = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        assertEquals(SniffedImage.PNG, sniffImageSignature(bytes))
    }

    @Test
    fun jpegSignatureRecognized() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(SniffedImage.JPEG, sniffImageSignature(bytes))
    }

    @Test
    fun webpSignatureRecognized() {
        val bytes = ascii("RIFF") + byteArrayOf(0, 0, 0, 0) + ascii("WEBP")
        assertEquals(SniffedImage.WEBP, sniffImageSignature(bytes))
    }

    @Test
    fun gifSignatureRecognized() {
        assertEquals(SniffedImage.GIF, sniffImageSignature(ascii("GIF89a")))
    }

    @Test
    fun svgRootRecognizedEvenWithAnXmlProlog() {
        val bytes = ascii("""<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"></svg>""")
        assertEquals(SniffedImage.SVG, sniffImageSignature(bytes))
    }

    @Test
    fun htmlErrorPageIsRejected() {
        // The exact live incident: a CDN 504 Gateway Timeout page saved as clearlogo.png.
        val bytes = ascii("<!DOCTYPE HTML><HTML><TITLE>ERROR: The request could not be satisfied</TITLE>")
        assertNull(sniffImageSignature(bytes))
    }

    @Test
    fun emptyBytesRejected() {
        assertNull(sniffImageSignature(ByteArray(0)))
    }

    @Test
    fun truncatedSignatureIsNotMistakenForAMatch() {
        // Fewer bytes than the shortest signature needs — must not throw or false-positive.
        assertNull(sniffImageSignature(byteArrayOf(0x89.toByte(), 'P'.code.toByte())))
    }
}
