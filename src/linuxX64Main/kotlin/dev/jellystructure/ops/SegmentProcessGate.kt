package dev.jellystructure.ops

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Phase 170 (detect_segments follow-ups, §1) — a dedicated, smaller process-slot pool for the segment-
 * detection lane's heavy-decode ffmpeg/fpcalc calls (`detectCreditsStart`'s blackdetect/silencedetect,
 * `computeFingerprint`/`computeOutroFingerprint`'s Chromaprint decode, `computeWaveform`'s raw-PCM
 * extraction — all in `FfmpegRunner.kt`), separate from [ProcessGate]'s shared 16 slots.
 *
 * Those calls already carry their own `nice -n 19 ionice -c3`/`-threads 2` CPU-priority protection
 * (Phase 150/159), which stops a segments run from starving *CPU*. It does nothing about *slot*
 * contention: every other `popen` call site in the backend — regular scan-time ffprobe, artwork resize,
 * screengrab, track-editing remux — shares the exact same [ProcessGate] semaphore, so a big show's
 * detect_segments run (Phase 164's segments job lane, `behavior.segment_workers` concurrent jobs, each
 * running several of these calls per episode) can still occupy enough of [ProcessGate]'s 16 permits to
 * make an unrelated scan or artwork request wait behind it.
 *
 * 4 slots — a small multiple of `behavior.segment_workers`' default of 2, deliberately far below
 * [ProcessGate]'s 16 so the segments lane can never crowd out request-serving process work, matching
 * the same "own dedicated pool, not a bigger shared one" shape [ProcessGate] itself already uses to
 * isolate `popen` from `Dispatchers.Default`.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
object SegmentProcessGate {
    private const val MAX_CONCURRENT = 4
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val dispatcher = newFixedThreadPoolContext(MAX_CONCURRENT, "segment-process-gate")

    suspend fun <T> withPermit(block: suspend () -> T): T =
        semaphore.withPermit { withContext(dispatcher) { block() } }
}
