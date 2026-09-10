package dev.jellystructure.media

import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 201 — the EBML walk that decides whether `Tracks` precedes the first `Cluster`. Built from
 * synthetic byte streams rather than real files: this is exactly the "proven by test, not by
 * observation" argument the spec makes for extracting it (the real bug only reproduces on a file whose
 * grown `Tracks` element happens to no longer fit its slot — data-dependent, not something one fixture
 * file can exercise for every shape).
 */
class MkvLayoutTest {

    private fun Buffer.writeIdBytes(id: Long, len: Int) {
        for (i in (len - 1) downTo 0) writeByte(((id shr (8 * i)) and 0xFF).toByte())
    }

    private fun Buffer.writeSizeBytes(value: Long, len: Int) {
        // The marker bit lives in the TOP byte's high bits, not in the low bits of the integer — OR
        // it into the big-endian byte array after laying out `value`, not into the raw Long.
        val bytes = ByteArray(len)
        var v = value
        for (i in (len - 1) downTo 0) {
            bytes[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        val marker = (0x80 shr (len - 1))
        bytes[0] = (bytes[0].toInt() or marker).toByte()
        for (b in bytes) writeByte(b)
    }

    private fun Buffer.writeElement(id: Long, idLen: Int, contentSize: Int, sizeLen: Int = 1) {
        writeIdBytes(id, idLen)
        writeSizeBytes(contentSize.toLong(), sizeLen)
        repeat(contentSize) { writeByte(0) }
    }

    private fun Buffer.writeEbmlHeaderAndSegment() {
        writeElement(id = 0x1A45DFA3L, idLen = 4, contentSize = 5) // EBML header
        writeIdBytes(0x18538067L, 4) // Segment
        writeSizeBytes(500_000L, 4) // an arbitrary known segment size, never consulted
    }

    @Test
    fun `Tracks before the first Cluster is OK`() {
        val buf = Buffer().apply {
            writeEbmlHeaderAndSegment()
            writeElement(id = 0x114D9B74L, idLen = 4, contentSize = 3) // SeekHead
            writeElement(id = 0x1549A966L, idLen = 4, contentSize = 4) // Info
            writeElement(id = 0x1654AE6BL, idLen = 4, contentSize = 6) // Tracks
            writeElement(id = 0x1F43B675L, idLen = 4, contentSize = 0) // Cluster
        }

        assertEquals(MkvLayout.OK, scanMkvLayout(buf))
    }

    @Test
    fun `a Cluster before Tracks was ever seen is TRACKS_AFTER_CLUSTER`() {
        // the reproduced shape: mkvpropedit left a Void where Tracks used to be and appended the real
        // Tracks element to EOF, reachable only via SeekHead — this linear walk never gets there.
        val buf = Buffer().apply {
            writeEbmlHeaderAndSegment()
            writeElement(id = 0x114D9B74L, idLen = 4, contentSize = 3) // SeekHead
            writeElement(id = 0x1549A966L, idLen = 4, contentSize = 4) // Info
            writeElement(id = 0xECL, idLen = 1, contentSize = 6)       // Void (where Tracks used to be)
            writeElement(id = 0x1254C367L, idLen = 4, contentSize = 5) // Tags
            writeElement(id = 0x1C53BB6BL, idLen = 4, contentSize = 8) // Cues
            writeElement(id = 0x1F43B675L, idLen = 4, contentSize = 0) // Cluster — Tracks never seen
        }

        assertEquals(MkvLayout.TRACKS_AFTER_CLUSTER, scanMkvLayout(buf))
    }

    @Test
    fun `no Cluster at all is OK`() {
        val buf = Buffer().apply {
            writeEbmlHeaderAndSegment()
            writeElement(id = 0x1654AE6BL, idLen = 4, contentSize = 6) // Tracks
            writeElement(id = 0x1549A966L, idLen = 4, contentSize = 4) // Info
        }

        assertEquals(MkvLayout.OK, scanMkvLayout(buf))
    }

    @Test
    fun `not an EBML file at all is UNKNOWN`() {
        val buf = Buffer().apply { write(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67)) }

        assertEquals(MkvLayout.UNKNOWN, scanMkvLayout(buf))
    }

    @Test
    fun `an empty stream is UNKNOWN`() {
        assertEquals(MkvLayout.UNKNOWN, scanMkvLayout(Buffer()))
    }

    @Test
    fun `a Void element between Tracks and Cluster does not reset the sighting`() {
        val buf = Buffer().apply {
            writeEbmlHeaderAndSegment()
            writeElement(id = 0x1654AE6BL, idLen = 4, contentSize = 6)  // Tracks
            writeElement(id = 0xECL, idLen = 1, contentSize = 20)       // Void padding
            writeElement(id = 0x1F43B675L, idLen = 4, contentSize = 0)  // Cluster
        }

        assertEquals(MkvLayout.OK, scanMkvLayout(buf))
    }

    /** A multi-byte VINT size (2-byte length descriptor) — real files routinely need more than one
     *  size byte for a several-KB `Tracks` element; this pins that the length encoding is decoded
     *  correctly, not just the common 1-byte case. */
    @Test
    fun `a Tracks element with a multi-byte VINT size is still recognized`() {
        val buf = Buffer().apply {
            writeEbmlHeaderAndSegment()
            writeElement(id = 0x1654AE6BL, idLen = 4, contentSize = 600, sizeLen = 2) // Tracks, 2-byte size
            writeElement(id = 0x1F43B675L, idLen = 4, contentSize = 0)
        }

        assertEquals(MkvLayout.OK, scanMkvLayout(buf))
    }
}
