package dev.jellystructure.subtitles

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phase 273 — timing as a fingerprint. A cue track (on while a subtitle shows, off otherwise, 10 frames a
 * second) lines up with another subtitle of the same edit, in any language, at one offset and one speed; with a
 * speech track it lines up where people talk. Cross-correlation over ±120 s at five speeds finds that
 * alignment, or shows there is none.
 *
 * The alignment found is expressed as the retiming that fits the subtitle to the reference:
 * `t' = t × scale + shiftMs`. A negative shift means the subtitle is late.
 */
object SubtitleTiming {
    const val HZ = 10
    const val FRAME_MS = 1000L / HZ
    const val MAX_LAG_S = 120
    const val MAX_LAG = MAX_LAG_S * HZ

    /** Same speed · PAL (25 fps) sped up · PAL slowed down · the 1.001 NTSC pair. */
    val SCALES = doubleArrayOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 1.001, 1.0 / 1.001)

    data class Fit(val rho: Double, val z: Double, val scale: Double, val shiftMs: Long)

    /** A reference's spectrum, computed once and correlated against any number of candidates of the same size. */
    class Spectrum internal constructor(val n: Int, internal val re: DoubleArray, internal val im: DoubleArray, internal val norm: Double)

    /** The transform size for signals reaching [lastMs]: room for the whole track plus the lag window, so the
     *  circular correlation never wraps a real alignment. */
    fun sizeFor(lastMs: Long): Int {
        val need = (lastMs * 1.05 / FRAME_MS).toLong() + 2L * MAX_LAG + 16
        var n = 1024
        while (n < need) n = n shl 1
        return n
    }

    /** The cue track at [scale], centred over its own span (first cue to last) and zero outside it, so two
     *  subtitles that merely cover the same 22 minutes do not correlate. */
    fun cueSignal(cues: List<Cue>, n: Int, scale: Double = 1.0): DoubleArray {
        val m = DoubleArray(n)
        var lo = n
        var hi = 0
        for (c in cues) {
            val i = (c.startMs * scale / FRAME_MS).toInt()
            val j = (c.endMs * scale / FRAME_MS).toInt()
            if (j <= 0 || i >= n) continue
            val a = maxOf(i, 0)
            val b = minOf(j, n)
            for (k in a until b) m[k] = 1.0
            if (a < lo) lo = a
            if (b > hi) hi = b
        }
        centre(m, lo, hi)
        return m
    }

    /** A speech track (per frame, the share of 10 ms frames that sounded like dialogue, 0–100) as a signal,
     *  centred over its whole length. */
    fun speechSignal(frames: ByteArray, n: Int): DoubleArray {
        val m = DoubleArray(n)
        val len = minOf(frames.size, n)
        for (k in 0 until len) m[k] = (frames[k].toInt() and 0xFF) / 100.0
        centre(m, 0, len)
        return m
    }

    private fun centre(m: DoubleArray, lo: Int, hi: Int) {
        if (hi <= lo) return
        var sum = 0.0
        for (k in lo until hi) sum += m[k]
        val mean = sum / (hi - lo)
        for (k in lo until hi) m[k] -= mean
    }

    fun spectrum(signal: DoubleArray, plan: FftPlan): Spectrum {
        val n = signal.size
        require(plan.n == n) { "plan size ${plan.n} for a signal of $n" }
        val re = signal.copyOf()
        val im = DoubleArray(n)
        var ss = 0.0
        for (v in signal) ss += v * v
        plan.transform(re, im, inverse = false)
        return Spectrum(n, re, im, sqrt(ss))
    }

    /** The candidate's spectra at every speed, computed once and reused against every reference of size [n]. */
    fun candidateSpectra(cues: List<Cue>, plan: FftPlan): List<Pair<Double, Spectrum>> =
        SCALES.map { s -> s to spectrum(cueSignal(cues, plan.n, s), plan) }

    /** The best alignment of the candidate against [ref], by z-score within the ±[MAX_LAG_S] window. */
    fun bestFit(candidate: List<Pair<Double, Spectrum>>, ref: Spectrum, plan: FftPlan): Fit {
        var best: Fit? = null
        val n = ref.n
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val window = DoubleArray(2 * MAX_LAG + 1)
        for ((scale, c) in candidate) {
            require(c.n == n) { "spectra of different sizes" }
            // correlation c[L] = Σ cand[t]·ref[t+L]  ⇔  IFFT(conj(C)·R)
            for (k in 0 until n) {
                val ar = c.re[k]; val ai = -c.im[k]
                val br = ref.re[k]; val bi = ref.im[k]
                re[k] = ar * br - ai * bi
                im[k] = ar * bi + ai * br
            }
            plan.transform(re, im, inverse = true)
            for (w in 0..2 * MAX_LAG) {
                val lag = w - MAX_LAG
                window[w] = re[if (lag < 0) n + lag else lag]
            }
            var peak = 0
            var sum = 0.0
            for (w in window.indices) { sum += window[w]; if (window[w] > window[peak]) peak = w }
            val mean = sum / window.size
            var varSum = 0.0
            for (v in window) varSum += (v - mean) * (v - mean)
            val std = sqrt(varSum / window.size)
            val z = if (std > 1e-12) (window[peak] - mean) / std else 0.0
            val rho = if (c.norm > 1e-12 && ref.norm > 1e-12) window[peak] / (c.norm * ref.norm) else 0.0
            val lag = peak - MAX_LAG
            // ref[x] ≈ cand(x − lag): the subtitle's retimed position is its scaled time plus lag frames.
            val fit = Fit(rho, z, scale, lag * FRAME_MS)
            if (best == null || fit.z > best.z) best = fit
        }
        return best!!
    }

    /** The largest misalignment anywhere between the subtitle's first and last cue, were it left as it is. */
    fun worstMs(fit: Fit, firstMs: Long, lastMs: Long): Long {
        val a = abs(firstMs * (fit.scale - 1.0) + fit.shiftMs)
        val b = abs(lastMs * (fit.scale - 1.0) + fit.shiftMs)
        return maxOf(a, b).roundToLong()
    }

    /**
     * FR-273-4 — whether the alignment holds through the file: the best shift of each 3-minute chunk of the
     * reference, searched ±10 s around the global one. The first and last chunks are left out (logos, credits).
     * Null for a chunk with too little dialogue to say. A different cut shows as chunks that disagree.
     */
    fun chunkShiftsMs(candidate: List<Cue>, fit: Fit, ref: List<Cue>, chunkS: Int = 180, searchS: Int = 10): List<ChunkShift> {
        val last = maxOf(ref.lastOrNull()?.endMs ?: 0L, candidate.lastOrNull()?.endMs ?: 0L)
        val n = sizeFor((last * maxOf(fit.scale, 1.0)).toLong())
        val cand = cueSignal(candidate, n, fit.scale)
        val r = cueSignal(ref, n, 1.0)
        val chunk = chunkS * HZ
        val search = searchS * HZ
        val lag0 = (fit.shiftMs / FRAME_MS).toInt()
        val refEnd = ((ref.lastOrNull()?.endMs ?: 0L) / FRAME_MS).toInt()
        val chunks = ArrayList<ChunkShift>()
        var t0 = chunk
        while (t0 + chunk <= refEnd - chunk / 2) {
            val centreMs = (t0 + chunk / 2) * FRAME_MS
            var active = 0
            for (x in t0 until t0 + chunk) if (r[x] > 0) active++
            if (active < chunk / 20) { chunks += ChunkShift(centreMs, null); t0 += chunk; continue }
            var bestLag = lag0
            var bestV = Double.NEGATIVE_INFINITY
            var atLag0 = 0.0
            for (l in lag0 - search..lag0 + search) {
                var v = 0.0
                for (x in t0 until t0 + chunk) {
                    val y = x - l
                    if (y in 0 until n) v += r[x] * cand[y]
                }
                if (l == lag0) atLag0 = v
                if (v > bestV) { bestV = v; bestLag = l }
            }
            // A best lag on the edge of the search is not an alignment, only the end of where it looked. And a
            // stretch only counts as shifted when it fits clearly better there (by a fifth) than at the file's
            // own shift; otherwise quiet or music-heavy stretches read as a cut that is not there.
            val shift = when {
                abs(bestLag - lag0) >= search || bestV <= 0.0 -> null
                bestLag != lag0 && bestV - atLag0 < 0.2 * bestV -> lag0
                else -> bestLag
            }
            chunks += ChunkShift(centreMs, shift?.let { it * FRAME_MS })
            t0 += chunk
        }
        return chunks
    }

    /** One 3-minute stretch of the reference: its centre, and the shift that fits it best (null = too quiet). */
    data class ChunkShift(val centreMs: Long, val shiftMs: Long?)

    /**
     * FR-273-4 — what the chunks say about the whole file. A single stretch that disagrees with both neighbours is
     * noise (a chunk of music) and is smoothed away; the rest are fitted with a straight line. Points close to the
     * line mean one offset plus a steady drift, which a sync can fix ([linear] true); points that step away from
     * it mean the cut differs partway ([linear] false). [worstMs] is the largest misalignment the chunks show.
     */
    data class ChunkSummary(val linear: Boolean, val worstMs: Long, val driftMsPerHour: Long)

    fun summarise(chunks: List<ChunkShift>): ChunkSummary? {
        val known = chunks.filter { it.shiftMs != null }
        if (known.size < 3) return null
        val smoothed = known.indices.map { i ->
            val v = known[i].shiftMs!!
            if (i == 0 || i == known.lastIndex) v
            else listOf(known[i - 1].shiftMs!!, v, known[i + 1].shiftMs!!).sorted()[1]
        }
        // the ends have only one neighbour: drop an end that disagrees with its neighbour while the neighbour
        // agrees with the next one in
        val keep = smoothed.indices.filter { i ->
            when (i) {
                0 -> abs(smoothed[0] - smoothed[1]) <= 1_000 || abs(smoothed[1] - smoothed[2]) > 1_000
                smoothed.lastIndex -> abs(smoothed[i] - smoothed[i - 1]) <= 1_000 || abs(smoothed[i - 1] - smoothed[i - 2]) > 1_000
                else -> true
            }
        }
        val xs = keep.map { known[it].centreMs.toDouble() }
        val ys = keep.map { smoothed[it].toDouble() }
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        for (k in xs.indices) { sxx += (xs[k] - mx) * (xs[k] - mx); sxy += (xs[k] - mx) * (ys[k] - my) }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        val resid = xs.indices.maxOf { abs(ys[it] - (my + slope * (xs[it] - mx))) }
        return ChunkSummary(
            linear = resid <= 1_000.0,
            worstMs = ys.maxOf { abs(it) }.roundToLong(),
            driftMsPerHour = (slope * 3_600_000).roundToLong(),
        )
    }
}

/** An iterative radix-2 complex FFT of one size, in place. A plan holds its own twiddles, so a check builds one
 *  and nothing is shared between jobs running on different threads. */
class FftPlan(val n: Int) {
    private val cs: DoubleArray
    private val sn: DoubleArray

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of two" }
        cs = DoubleArray(n / 2) { k -> cos(2 * PI * k / n) }
        sn = DoubleArray(n / 2) { k -> sin(2 * PI * k / n) }
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        require(re.size == n && im.size == n) { "arrays of ${re.size}/${im.size} for a plan of $n" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val half = len / 2
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (m in 0 until half) {
                    val wr = cs[k]
                    val wi = if (inverse) sn[k] else -sn[k]
                    val a = i + m
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (k in 0 until n) { re[k] /= n; im[k] /= n }
    }
}
