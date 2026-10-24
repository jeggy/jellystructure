package dev.jellystructure.ravilo.ui.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-5) — the QR encoder. Three kinds of proof:
 *  - one string against a matrix that OpenCV's QR decoder read back to the string (see [golden]);
 *  - the capacity table against the standard's level-M byte-mode figures;
 *  - a read-back of every size class the encoder can choose (format BCH, un-mask, de-interleave, every block's Reed-Solomon
 *    syndromes zero, the string parsed out), because the multi-block versions are where an interleave goes wrong.
 *
 * Not used as the reference: the design round's `ts-qr.js`. It writes the two format-information copies transposed, which
 * `verify-qr.py` (written against the same layout) cannot see and a real decoder cannot read.
 */
class QrEncoderTest {
    private val link = "https://ravilo.example.net/tv?c=K7P4QX"

    // Version 3, level M, mask 3, `#` dark. Decoded to `link` by OpenCV's QR detector (a decoder that shares nothing with this
    // code) when this was written; the same check passed on thirteen lengths from version 1 to 16.
    private val golden = listOf(
        "#######.##.#..######..#######",
        "#.....#.##..#.#...#...#.....#",
        "#.###.#..#...##....##.#.###.#",
        "#.###.#.###.#.####....#.###.#",
        "#.###.#..#####.#...#..#.###.#",
        "#.....#..#.#.#..####..#.....#",
        "#######.#.#.#.#.#.#.#.#######",
        "........#####.#..#...........",
        "#.##.###.##.##...##...#..#.##",
        "##.#.....#####.##.#######...#",
        "###...##..##.#..###.###...##.",
        "####.#..#.##...#..##..#.#...#",
        "#..#.##..#....####..#....##..",
        "..##.#..###...##.###..#...###",
        "###..##......#....####..#.###",
        ".##.##.##.##..#...#.#.###..#.",
        "#...#.####.#..##...##...##.#.",
        ".##.......###.#..#..#..#.###.",
        "#....##.###.######..#.###.#..",
        "..##.#.#..#.###.###.#.##..#..",
        ".#.####.#..#.###.##########..",
        "........###.#.......#...#####",
        "#######.#.#...#.#####.#.##.#.",
        "#.....#.##..#####...#...##...",
        "#.###.#..##.#..#.#..#####.#..",
        "#.###.#.##.#...####.##.###..#",
        "#.###.#.##..##...####..#..#.#",
        "#.....#..###....#.###....#.#.",
        "#######.#..#.####..###.#.#.#."
    )

    @Test
    fun `the link encodes to the decoder-verified matrix`() {
        assertEquals(golden, QrEncoder.encode(link).rows())
    }

    @Test
    fun `capacity at level M matches the standard`() {
        val expected = mapOf(1 to 14, 2 to 26, 3 to 42, 4 to 62, 5 to 84, 6 to 106, 7 to 122, 9 to 180, 10 to 213, 20 to 666, 40 to 2331)
        for ((v, bytes) in expected) assertEquals(bytes, QrEncoder.capacityBytes(v), "version $v")
    }

    @Test
    fun `the smallest version that fits is chosen`() {
        assertEquals(21, QrEncoder.encode("x".repeat(14)).size)
        assertEquals(25, QrEncoder.encode("x".repeat(15)).size)
        assertEquals(29, QrEncoder.encode(link).size)
        assertEquals(33, QrEncoder.encode("x".repeat(43)).size)
    }

    @Test
    fun `text longer than version 40 is refused`() {
        assertFailsWith<IllegalArgumentException> { QrEncoder.encode("x".repeat(2332)) }
    }

    @Test
    fun `finders timing and the dark module are where the standard puts them`() {
        for (text in listOf("a", link, "y".repeat(130))) {
            val m = QrEncoder.encode(text)
            val n = m.size
            for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                for (dy in 0 until 7) for (dx in 0 until 7) {
                    val ring = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
                    assertEquals(ring != 2, m.isDark(ox + dx, oy + dy), "finder at $ox,$oy offset $dx,$dy in $n")
                }
            }
            for (i in 8 until n - 8) {
                assertEquals(i % 2 == 0, m.isDark(i, 6), "row timing $i")
                assertEquals(i % 2 == 0, m.isDark(6, i), "column timing $i")
            }
            assertTrue(m.isDark(8, n - 8), "dark module")
        }
    }

    @Test
    fun `every version reads back to its string`() {
        // One string per size class: 1 block, 2 blocks (v4-5), 4 (v6-8), 5 and up, a version with version information (v7+), a 16-bit count (v10+).
        for (len in listOf(5, 14, 30, 42, 60, 84, 100, 122, 170, 213, 300, 500, 1000)) {
            val text = buildString { for (i in 0 until len) append(('a' + (i * 7 + len) % 26)) }
            assertEquals(text, readBack(QrEncoder.encode(text)), "len $len")
        }
        assertEquals(link, readBack(QrEncoder.encode(link)))
        assertEquals("København — Tórshavn ✓", readBack(QrEncoder.encode("København — Tórshavn ✓")))
    }

    // ─── A decoder, written to share nothing with the encoder but the function-module map ───────────────────────────

    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) { exp[i] = x; log[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    /** The remainder of `value * x^degree` divided by [poly] (a polynomial of that degree) over GF(2) — a BCH code's check bits. */
    private fun bch(value: Int, poly: Int, degree: Int): Int {
        var r = value shl degree
        for (i in 31 downTo degree) if ((r ushr i) and 1 == 1) r = r xor (poly shl (i - degree))
        return r
    }

    private fun readBack(m: QrMatrix): String {
        val n = m.size
        val version = (n - 17) / 4
        fun d(x: Int, y: Int) = if (m.isDark(x, y)) 1 else 0

        // Format information, first copy: level bits must be M (00), the whole word must pass its BCH, and the second copy must agree.
        var f1 = 0
        for (i in 0..5) f1 = f1 or (d(8, i) shl i)
        f1 = f1 or (d(8, 7) shl 6) or (d(8, 8) shl 7) or (d(7, 8) shl 8)
        for (i in 9 until 15) f1 = f1 or (d(14 - i, 8) shl i)
        var f2 = 0
        for (i in 0 until 8) f2 = f2 or (d(n - 1 - i, 8) shl i)
        for (i in 8 until 15) f2 = f2 or (d(8, n - 15 + i) shl i)
        assertEquals(f1, f2, "format copies agree")
        val fmt = f1 xor 0x5412
        assertEquals(fmt and 0x3FF, bch(fmt ushr 10, 0x537, 10), "format BCH")
        assertEquals(0, (fmt ushr 13) and 3, "level M")
        val mask = (fmt ushr 10) and 7

        if (version >= 7) {
            var v = 0
            for (i in 0 until 18) v = v or (d(n - 11 + i % 3, i / 3) shl i)
            assertEquals(version, v ushr 12, "version information")
            assertEquals(v and 0xFFF, bch(version, 0x1F25, 12), "version BCH")
        }

        // Data modules in the standard's zig-zag, mask removed.
        val isFn = QrEncoder.functionModules(version)
        val bits = ArrayList<Int>()
        var right = n - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until n) for (j in 0 until 2) {
                val x = right - j
                val y = if ((right + 1) and 2 == 0) n - 1 - vert else vert
                if (!isFn[y * n + x]) {
                    val inv = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    bits.add(d(x, y) xor (if (inv) 1 else 0))
                }
            }
            right -= 2
        }
        val cw = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { a, b -> (a shl 1) or bits[i * 8 + b] } }

        // De-interleave and check each block: the polynomial of data + check bytes must vanish at alpha^0..alpha^(ecc-1).
        val blocks = BLOCKS_M[version]
        val ecc = ECC_M[version]
        val short = blocks - cw.size % blocks
        val shortLen = cw.size / blocks
        val lens = IntArray(blocks) { shortLen - ecc + if (it < short) 0 else 1 }
        val data = Array(blocks) { IntArray(lens[it]) }
        var k = 0
        for (i in 0..(shortLen - ecc)) for (j in 0 until blocks) if (i < lens[j]) data[j][i] = cw[k++]
        val check = Array(blocks) { IntArray(ecc) }
        for (i in 0 until ecc) for (j in 0 until blocks) check[j][i] = cw[k++]
        for (j in 0 until blocks) {
            val word = data[j] + check[j]
            for (s in 0 until ecc) {
                var acc = 0
                for (c in word) acc = mul(acc, exp[s]) xor c
                assertEquals(0, acc, "block $j syndrome $s in version $version")
            }
        }

        // Parse the byte-mode segment.
        val stream = data.flatMap { it.toList() }
        val sb = StringBuilder()
        stream.forEach { for (b in 7 downTo 0) sb.append((it ushr b) and 1) }
        val s = sb.toString()
        assertEquals("0100", s.substring(0, 4), "byte mode")
        val countBits = if (version <= 9) 8 else 16
        val count = s.substring(4, 4 + countBits).toInt(2)
        val bytes = ByteArray(count) { s.substring(4 + countBits + it * 8, 4 + countBits + it * 8 + 8).toInt(2).toByte() }
        return bytes.decodeToString()
    }

    private val ECC_M = intArrayOf(
        -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    )
    private val BLOCKS_M = intArrayOf(
        -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
    )
}
