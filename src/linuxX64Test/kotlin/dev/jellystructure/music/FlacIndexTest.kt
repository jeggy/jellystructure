package dev.jellystructure.music

import dev.jellystructure.shared.tv.ClientCapabilities
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Phase 288 — a FLAC's metadata blocks, as the files on a real shelf had them. */
class FlacIndexTest {
    private fun block(type: Int, length: Int, last: Boolean = false) =
        byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte()) + ByteArray(length)

    private fun flac(vararg blocks: ByteArray) = Buffer().apply { write("fLaC".encodeToByteArray()); blocks.forEach { write(it) } }

    @Test fun aSeekTableIsFound() {
        // STREAMINFO, SEEKTABLE with 30 points, VORBIS_COMMENT, a 2 MB PICTURE, PADDING — a reference-encoder file.
        assertEquals(true, FlacIndex.hasSeekTable(flac(block(0, 34), block(3, 30 * 18), block(4, 200), block(6, 2_000_000), block(1, 8192, last = true))))
    }

    @Test fun noSeekTableIsSaid() {
        // What ffmpeg writes: STREAMINFO, VORBIS_COMMENT, PICTURE, PADDING.
        assertEquals(false, FlacIndex.hasSeekTable(flac(block(0, 34), block(4, 200), block(6, 500_000), block(1, 8192, last = true))))
    }

    @Test fun anEmptySeekTableIsNone() {
        assertEquals(false, FlacIndex.hasSeekTable(flac(block(0, 34), block(3, 0), block(1, 100, last = true))))
    }

    @Test fun aSeekTableAfterThePictureIsFound() {
        assertEquals(true, FlacIndex.hasSeekTable(flac(block(0, 34), block(6, 300_000), block(3, 18, last = true))))
    }

    @Test fun somethingElseIsUnknown() {
        assertNull(FlacIndex.hasSeekTable(Buffer().apply { write("ID3\u0004rest of an mp3".encodeToByteArray()) }))
        assertNull(FlacIndex.hasSeekTable(Buffer()))
        assertNull(FlacIndex.hasSeekTable("/nowhere/at/all.flac"))
        assertNull(FlacIndex.hasSeekTable(null as String?))
    }

    @Test fun aCutHeaderIsNoSeekTable() {
        // The header says 1000 bytes follow and the file stops: nothing more to find.
        val cut = Buffer().apply { write("fLaC".encodeToByteArray()); write(byteArrayOf(0, 0, 0x03, 0xE8.toByte())); write(ByteArray(10)) }
        assertEquals(false, FlacIndex.hasSeekTable(cut))
    }

    private val receiver = ClientCapabilities(containers = listOf("mp3", "flac", "m4a"), audioCodecs = listOf("mp3", "aac", "flac"), seekNeedsIndex = true)

    @Test fun aPlayerThatSeeksItselfKeepsItsCapabilities() {
        val phone = receiver.copy(seekNeedsIndex = false)
        assertEquals(phone, FlacIndex.capabilitiesFor(phone, "flac", "flac", "/nowhere/at/all.flac"))
    }

    @Test fun whatCannotBeReadIsNotConverted() {
        // FR-288-4 — unknown is "has one": the song plays as it does today.
        assertEquals(receiver, FlacIndex.capabilitiesFor(receiver, "flac", "flac", "/nowhere/at/all.flac"))
        assertEquals(receiver, FlacIndex.capabilitiesFor(receiver, "flac", "flac", null))
        assertEquals(receiver, FlacIndex.capabilitiesFor(receiver, "mp3", "mp3", "/nowhere/at/all.mp3"))
    }
}
