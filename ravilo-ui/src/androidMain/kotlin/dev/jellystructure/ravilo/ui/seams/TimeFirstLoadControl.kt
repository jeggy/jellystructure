package dev.jellystructure.ravilo.ui.seams

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import dev.jellystructure.ravilo.ui.screens.DIRECT_BUFFER_TARGET_MS
import dev.jellystructure.ravilo.ui.screens.directBufferMs

/**
 * DEB-2205 (FR-4) — a direct play watches its buffer in **seconds ahead**, so its buffer is sized in seconds rather than bytes:
 * `prioritizeTimeOverSizeThresholds` is on and the target is [DIRECT_BUFFER_TARGET_MS] (50 s, Media3's stock, above the spec's 30 s),
 * bounded by the device's memory — the memory class × 0.25 as bytes, at the file's own bitrate ([directBufferMs]). A device that cannot
 * hold 30 s of the file buffers what it can hold, and the hand-over rule's thresholds scale to that ([handoverScale]).
 *
 * **Only a direct play** ([directStreamBps] > 0). Every other stream — an HLS ladder above all — is bounded by bytes exactly as
 * before: with time priority on, Media3 would load past its byte target until the time target is met, and a 2160p rung at 50 s does not
 * fit the heap of the TV this app runs on. [shouldContinueLoading] restores the byte stop for those.
 */
internal class TimeFirstLoadControl(
    private val memoryClassMb: Int,
    /** The whole-file bitrate of the direct play being loaded (bits/s); 0 = the stream is not one. */
    private val directStreamBps: () -> Long,
    bufferForPlaybackAfterRebufferMs: Int,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    DEFAULT_MIN_BUFFER_MS, DEFAULT_MAX_BUFFER_MS, DEFAULT_BUFFER_FOR_PLAYBACK_MS, bufferForPlaybackAfterRebufferMs,
    DEFAULT_TARGET_BUFFER_BYTES, /* prioritizeTimeOverSizeThresholds = */ true, DEFAULT_BACK_BUFFER_DURATION_MS, DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
) {
    @Volatile private var targetBytes = C.LENGTH_UNSET

    override fun calculateTargetBufferBytes(trackSelectionArray: Array<ExoTrackSelection?>): Int =
        super.calculateTargetBufferBytes(trackSelectionArray).also { targetBytes = it }

    /** The buffer a direct play may hold now, in µs; null for a stream that is not one. */
    internal fun directCapUs(): Long? = directStreamBps().takeIf { it > 0 }?.let { directBufferMs(memoryClassMb, it) * 1_000L }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val cap = directCapUs()
        if (cap != null) return parameters.bufferedDurationUs < cap && super.shouldContinueLoading(parameters)
        // Not a direct play: the byte bound Media3 would have applied without time priority.
        if (targetBytes > 0 && allocator.totalBytesAllocated >= targetBytes) return false
        return super.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        // A device that holds less than the stock start threshold (10 s after a rebuffer) must still be able to start: it starts with what it can hold.
        val cap = directCapUs()
        if (cap != null && parameters.bufferedDurationUs >= cap * 9 / 10) return true
        return super.shouldStartPlayback(parameters)
    }
}
