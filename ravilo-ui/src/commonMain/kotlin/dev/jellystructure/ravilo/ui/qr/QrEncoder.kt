package dev.jellystructure.ravilo.ui.qr

/**
 * DEB-2233 (FR-5) — a QR encoder in plain Kotlin: byte mode, error-correction level M, versions 1–40, no platform
 * dependency and no library. The TV's sign-in screen draws the sign-in link with it ([QrCodeImage]).
 *
 * The method is ISO/IEC 18004: pick the smallest version whose M capacity holds the bytes, split the data across the
 * version's Reed-Solomon blocks and interleave them, place the function patterns and the zig-zag of data modules, try the
 * eight masks and keep the one with the lowest penalty, then write the format and version information. The design round's
 * `ts-qr.js` is the reference the tests compare against, and `verify-qr.py` there reads a matrix back independently.
 */
class QrMatrix internal constructor(val size: Int, private val dark: BooleanArray) {
    /** True for a dark module; false outside the symbol (the quiet zone is the caller's). */
    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]

    /** The matrix as text, one row per line, `#` dark and `.` light — what the golden test compares. */
    fun rows(): List<String> = (0 until size).map { y -> (0 until size).joinToString("") { x -> if (isDark(x, y)) "#" else "." } }
}

object QrEncoder {
    /** Bytes of data a version-[version] symbol holds at level M in byte mode. */
    fun capacityBytes(version: Int): Int {
        val dataCodewords = numRawDataModules(version) / 8 - ECC_PER_BLOCK_M[version] * BLOCKS_M[version]
        val countBits = if (version <= 9) 8 else 16
        return (dataCodewords * 8 - 4 - countBits) / 8
    }

    /** Encodes [text] as UTF-8 bytes. Throws [IllegalArgumentException] when it is longer than version 40 holds. */
    fun encode(text: String): QrMatrix {
        val data = text.encodeToByteArray()
        var version = 1
        while (version <= 40 && capacityBytes(version) < data.size) version++
        require(version <= 40) { "too long for a QR code: ${data.size} bytes" }

        val bits = ArrayList<Boolean>()
        fun put(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 == 1) }
        put(0b0100, 4)
        put(data.size, if (version <= 9) 8 else 16)
        for (b in data) put(b.toInt() and 0xFF, 8)

        val dataCodewords = numRawDataModules(version) / 8 - ECC_PER_BLOCK_M[version] * BLOCKS_M[version]
        val capBits = dataCodewords * 8
        put(0, minOf(4, capBits - bits.size))
        while (bits.size % 8 != 0) bits.add(false)
        var pad = 0xEC
        while (bits.size < capBits) { put(pad, 8); pad = pad xor (0xEC xor 0x11) }
        val codewords = ByteArray(dataCodewords)
        for (i in bits.indices) if (bits[i]) codewords[i ushr 3] = (codewords[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()

        return Builder(version, interleave(version, codewords)).build()
    }

    /** Which modules of a version-[version] symbol belong to a function pattern (finders, timing, alignment, format, version),
     *  not to the data. The tests read a matrix back with it. */
    internal fun functionModules(version: Int): BooleanArray =
        Builder(version, ByteArray(0)).also { it.drawFunctionPatterns() }.isFunction

    // ─── Reed-Solomon over GF(256), polynomial 0x11D ────────────────────────────────────────────────────────────────

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun divisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = gfMultiply(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun remainder(data: ByteArray, from: Int, to: Int, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (i in from until to) {
            val factor = (data[i].toInt() and 0xFF) xor result[0]
            for (j in 0 until result.size - 1) result[j] = result[j + 1]
            result[result.size - 1] = 0
            for (j in result.indices) result[j] = result[j] xor gfMultiply(divisor[j], factor)
        }
        return result
    }

    /** Splits the data into the version's blocks (the short ones first), appends each block's check bytes and interleaves. */
    private fun interleave(version: Int, data: ByteArray): ByteArray {
        val blocks = BLOCKS_M[version]
        val eccLen = ECC_PER_BLOCK_M[version]
        val rawCodewords = numRawDataModules(version) / 8
        val shortBlocks = blocks - rawCodewords % blocks
        val shortLen = rawCodewords / blocks
        val div = divisor(eccLen)
        val result = ByteArray(rawCodewords)
        val dataBlocks = ArrayList<ByteArray>()
        val eccBlocks = ArrayList<IntArray>()
        var k = 0
        for (i in 0 until blocks) {
            val dataLen = shortLen - eccLen + if (i < shortBlocks) 0 else 1
            dataBlocks.add(data.copyOfRange(k, k + dataLen))
            eccBlocks.add(remainder(data, k, k + dataLen, div))
            k += dataLen
        }
        var out = 0
        for (i in 0..(shortLen - eccLen)) for (j in 0 until blocks) {
            if (i != shortLen - eccLen || j >= shortBlocks) result[out++] = dataBlocks[j][i]
        }
        for (i in 0 until eccLen) for (j in 0 until blocks) result[out++] = eccBlocks[j][i].toByte()
        return result
    }

    // ─── Geometry ───────────────────────────────────────────────────────────────────────────────────────────────────

    private fun numRawDataModules(ver: Int): Int {
        var result = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val numAlign = ver / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (ver >= 7) result -= 36
        }
        return result
    }

    private fun alignmentPositions(ver: Int): IntArray {
        if (ver == 1) return IntArray(0)
        val num = ver / 7 + 2
        val step = if (ver == 32) 26 else (ver * 4 + num * 2 + 1) / (num * 2 - 2) * 2
        val result = IntArray(num)
        result[0] = 6
        var pos = ver * 4 + 10
        for (i in num - 1 downTo 1) { result[i] = pos; pos -= step }
        return result
    }

    private class Builder(val version: Int, val codewords: ByteArray) {
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        fun build(): QrMatrix {
            drawFunctionPatterns()
            drawCodewords()
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormat(mask)
                val p = penalty()
                if (p < bestPenalty) { best = mask; bestPenalty = p }
                applyMask(mask)   // XOR again: undone
            }
            applyMask(best)
            drawFormat(best)
            return QrMatrix(size, modules)
        }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y * size + x] = dark; isFunction[y * size + x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4)
            val pos = alignmentPositions(version)
            for (i in pos.indices) for (j in pos.indices) {
                if (!((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0))) drawAlignment(pos[i], pos[j])
            }
            drawFormat(0)
            drawVersion()
        }

        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) set(x, y, dist != 2 && dist != 4)
            }
        }

        fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }

        fun drawFormat(mask: Int) {
            // Level M is the format code 0b00.
            val data = (0 shl 3) or mask
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        fun drawVersion() {
            if (version < 7) return
            var rem = version
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, bit); set(b, a, bit)
            }
        }

        fun drawCodewords() {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0 until 2) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y * size + x] && i < codewords.size * 8) {
                        modules[y * size + x] = (codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !isFunction[y * size + x]) modules[y * size + x] = !modules[y * size + x]
            }
        }

        fun penalty(): Int {
            var result = 0
            fun m(x: Int, y: Int) = modules[y * size + x]
            // Rule 1: five or more of one colour in a row or column (3, then 1 for each more).
            for (pass in 0 until 2) for (a in 0 until size) {
                var run = 1
                for (b in 1 until size) {
                    val same = if (pass == 0) m(b, a) == m(b - 1, a) else m(a, b) == m(a, b - 1)
                    if (same) run++ else { if (run >= 5) result += run - 2; run = 1 }
                }
                if (run >= 5) result += run - 2
            }
            // Rule 2: 2x2 blocks of one colour.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m(x, y)
                if (c == m(x + 1, y) && c == m(x, y + 1) && c == m(x + 1, y + 1)) result += 3
            }
            // Rule 3: the 1:1:3:1:1 finder shape with four light modules on one side, in a row or a column.
            for (pass in 0 until 2) for (a in 0 until size) for (b in 0..size - 11) {
                var first = true
                var second = true
                for (k in 0 until 11) {
                    val dark = if (pass == 0) m(b + k, a) else m(a, b + k)
                    if (dark != FINDER_LIKE_A[k]) first = false
                    if (dark != FINDER_LIKE_B[k]) second = false
                }
                if (first) result += 40
                if (second) result += 40
            }
            // Rule 4: ten for every five percentage points the dark share is from half.
            val total = size * size
            result += kotlin.math.abs(modules.count { it } * 20 - total * 10) / total * 10
            return result
        }
    }

    private val FINDER_LIKE_A = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
    private val FINDER_LIKE_B = booleanArrayOf(false, false, false, false, true, false, true, true, true, false, true)

    // Level M only: index by version (index 0 unused).
    private val ECC_PER_BLOCK_M = intArrayOf(
        -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    )
    private val BLOCKS_M = intArrayOf(
        -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
    )
}
