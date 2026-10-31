package dev.jellystructure.ravilo.ui.seams

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import dev.jellystructure.shared.tv.LadderRules
import java.io.FileNotFoundException

/**
 * The buffer ahead of the playhead as the playback thread last saw it, readable from the loader's thread, where Media3's
 * player getters may not be called. [estimateMs] ages the last reading by the time since, as if playing: a stale reading
 * errs toward "still holds picture", which only means one more retry.
 */
internal class LadderBuffer(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    @Volatile private var bufferedMs = 0L
    @Volatile private var atMs = 0L

    fun note(bufferedMs: Long) {
        this.bufferedMs = bufferedMs
        atMs = now()
    }

    fun reset() {
        bufferedMs = 0L
    }

    fun estimateMs(): Long = (bufferedMs - (now() - atMs)).coerceAtLeast(0L)
}

/**
 * DEB-3102 (FR-4) — what a failed load does on an HLS ladder.
 *
 * - **Any** failed load (a timeout, a reset, any 4xx or 5xx — a segment or a playlist) falls to the next rung down at once while
 *   one exists, the failed rung left alone for [LadderRules.EXCLUDE_FAILED_RUNG_MS]. Media3's own policy does this only for a
 *   few status codes and for 60 s; timeouts and resets retried the same rung.
 * - Where no rung is left to fall to, the load is retried after 0.5, 1, 2, 4 and then 5 s ([LadderRules.retryDelayMs]), for as
 *   long as the buffer holds picture ([LadderRules.retryLimit]); only an empty buffer ends in an error.
 *
 * Errors that are not the network's (a parse failure, cleartext refused, an internal loader fault) keep Media3's answer: no retry.
 */
@UnstableApi
internal class LadderLoadErrorPolicy(private val buffer: LadderBuffer) : DefaultLoadErrorHandlingPolicy() {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions, loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        if (!isTheNetworks(loadErrorInfo.exception) || !fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK))
            return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
        return LoadErrorHandlingPolicy.FallbackSelection(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK, LadderRules.EXCLUDE_FAILED_RUNG_MS)
    }

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isTheNetworks(loadErrorInfo.exception)) LadderRules.retryDelayMs(loadErrorInfo.errorCount) else C.TIME_UNSET

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = LadderRules.retryLimit(buffer.estimateMs())

    private fun isTheNetworks(e: java.io.IOException): Boolean =
        e !is ParserException && e !is FileNotFoundException && e !is Loader.UnexpectedLoaderException &&
            e !is HttpDataSource.CleartextNotPermittedException
}
