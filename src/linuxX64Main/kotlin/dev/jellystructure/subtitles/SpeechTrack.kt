package dev.jellystructure.subtitles

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlin.math.log10

/**
 * Phase 273 (FR-273-5) — a speech track from stereo audio, for files with no subtitle inside them to compare
 * against. Dialogue sits in the centre of a stereo mix and music and effects sit wide, so a 10 ms frame counts
 * as dialogue when its mid energy `(L+R)/2` is 10 dB above its side energy `(L−R)/2` and it is louder than the
 * file's 40th percentile. The output is one byte per 100 ms: the share of its ten frames that counted, 0–100.
 * No voice-activity detector: the research measured 93% separation with these two tests alone, against 96% with
 * webrtcvad, which Kotlin/Native does not have.
 *
 * Fed 16 kHz interleaved little-endian s16 stereo. Holds two bytes per 10 ms frame (about 2 MB for a 3-hour
 * film) because the loudness threshold is a percentile of the whole file.
 */
class SpeechAccumulator {
    private var ratio = ByteArray(1 shl 16)
    private var loud = ByteArray(1 shl 16)
    private var frames = 0
    private var sumMid = 0.0
    private var sumSide = 0.0
    private var inFrame = 0
    private val pending = IntArray(4)
    private var pendingN = 0
    private var silentSide = 0

    fun feed(bytes: ByteArray) {
        for (b in bytes) push(b.toInt() and 0xFF)
    }

    @OptIn(ExperimentalForeignApi::class)
    fun feed(buf: CPointer<ByteVar>, n: Int) {
        for (i in 0 until n) push(buf[i].toInt() and 0xFF)
    }

    private fun push(byte: Int) {
        pending[pendingN++] = byte
        if (pendingN < 4) return
        pendingN = 0
        val l = ((pending[1] shl 24) shr 16) or pending[0]
        val r = ((pending[3] shl 24) shr 16) or pending[2]
        val mid = (l + r) / 2.0
        val side = (l - r) / 2.0
        sumMid += mid * mid
        sumSide += side * side
        if (++inFrame == SAMPLES_PER_FRAME) closeFrame()
    }

    private fun closeFrame() {
        val em = sumMid / SAMPLES_PER_FRAME + 1.0
        val es = sumSide / SAMPLES_PER_FRAME + 1.0
        if (frames == ratio.size) {
            ratio = ratio.copyOf(ratio.size * 2)
            loud = loud.copyOf(loud.size * 2)
        }
        ratio[frames] = (10 * log10(em / es)).toInt().coerceIn(-100, 100).toByte()
        loud[frames] = (10 * log10(em)).toInt().coerceIn(0, 100).toByte()
        if (es <= 2.0 && em > 100.0) silentSide++
        frames++
        sumMid = 0.0
        sumSide = 0.0
        inFrame = 0
    }

    /** Null when there is no audio at all. [SpeechResult.mono] when the two channels carry the same signal,
     *  where mid/side says nothing about dialogue (FR-273-1's `mono_audio`). */
    fun finish(): SpeechResult? {
        if (frames == 0) return null
        var audible = 0
        for (k in 0 until frames) if (loud[k] > 20) audible++
        if (audible > 0 && silentSide >= audible * 0.95) return SpeechResult(ByteArray(0), mono = true)
        val sorted = loud.copyOf(frames).also { it.sort() }
        val p40 = sorted[(frames * 0.4).toInt().coerceAtMost(frames - 1)]
        val out = ByteArray((frames + FRAMES_PER_STEP - 1) / FRAMES_PER_STEP)
        for (o in out.indices) {
            var n = 0
            var speech = 0
            for (k in o * FRAMES_PER_STEP until minOf(frames, (o + 1) * FRAMES_PER_STEP)) {
                n++
                if (ratio[k] > 10 && loud[k] > p40) speech++
            }
            out[o] = if (n == 0) 0 else (speech * 100 / n).toByte()
        }
        return SpeechResult(out, mono = false)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_FRAME = SAMPLE_RATE / 100
        const val FRAMES_PER_STEP = 100 / SubtitleTiming.HZ
    }
}

class SpeechResult(val frames: ByteArray, val mono: Boolean)

/** Phase 273 — an embedded reference is stored as its cue times: little-endian int32 pairs (start, end) in ms. */
object CueCodec {
    fun encode(cues: List<Cue>): ByteArray {
        val out = ByteArray(cues.size * 8)
        var o = 0
        fun put(v: Long) {
            val x = v.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            out[o++] = x.toByte(); out[o++] = (x shr 8).toByte(); out[o++] = (x shr 16).toByte(); out[o++] = (x shr 24).toByte()
        }
        for (c in cues) { put(c.startMs); put(c.endMs) }
        return out
    }

    fun decode(bytes: ByteArray): List<Cue> {
        val out = ArrayList<Cue>(bytes.size / 8)
        var i = 0
        fun get(): Long {
            val v = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
            i += 4
            return v.toLong()
        }
        while (i + 8 <= bytes.size) out += Cue(get(), get())
        return out
    }
}
