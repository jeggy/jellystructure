package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.PrewarmRequest
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Phase 309 (FR-309-3/-6, owner 2026-10-07/08) — what a detail page does while the viewer reads it:
 *
 * - after **2 s** on the page, the speed test (`GET /api/tv/probe`, which answers 204 when this device was measured
 *   within 24 h), then the **early encode** of what Play would play: our encoder's job, started now, which Play adopts
 *   as its own when it is pressed (same file, tracks and start). It is re-posted every 30 s so the job's idle timer
 *   doesn't end it while the viewer still reads.
 * - leaving the page without pressing Play stops it **at once** (owner). Pressing Play is not leaving: the cancel waits
 *   a moment and is skipped when a play of that item has started ([playStarted]); a cancel that arrives after Play's
 *   start finds nothing to stop on the server (the job is the play's now).
 *
 * Nothing here is awaited by anything the viewer waits on, and nothing is shown.
 */
class DetailPrewarm(private val api: TvApiClient) {
    /** Runs while the page shows [itemId] as Play's target; cancel it (leave the composition) to leave. */
    suspend fun dwell(itemId: String, seriesId: String? = null) {
        var warmed = false
        try {
            delay(DWELL_MS)
            api.probe()
            val remembered = MultiTokenStore.getActive()?.userId?.let { pid ->
                PlaybackPrefsStore.getSeriesChoice(pid, seriesId ?: itemId) ?: PlaybackPrefsStore.getGlobalChoice(pid)
            }
            // 309 — while this app is linked to a Cast device, Play hands the film to that device's receiver: warm its
            // encode, not this phone's (the server uses the receiver's own capabilities and record).
            val castTo = dev.jellystructure.ravilo.ui.seams.CastTargetHint.castDeviceId
            castDeviceId = castTo
            val req = PrewarmRequest(itemId, currentClientCapabilities(),
                audioLanguage = remembered?.audioLanguage, audioVariant = remembered?.audioVariant, castDeviceId = castTo)
            while (true) {
                val r = api.prewarmPlayback(req) ?: return   // an older server, or a failure: nothing to keep warm
                if (r.status != "warm") return               // direct play, or the server won't warm it
                warmed = true
                delay(REPOST_MS)
            }
        } finally {
            if (warmed) leave(itemId)
        }
    }

    private fun leave(itemId: String) {
        exitScope.launch {
            withContext(NonCancellable) {
                delay(LEAVE_GRACE_MS)
                if (playStartedItem == itemId && nowMs() - playStartedAtMs < PLAY_COUNTS_MS) return@withContext
                api.cancelPrewarm(itemId, castDeviceId)
            }
        }
    }

    /** The Cast device the last early encode was made for (null: this device), so leaving cancels the right one. */
    @Volatile private var castDeviceId: String? = null

    companion object {
        const val DWELL_MS = 2_000L
        const val REPOST_MS = 30_000L
        /** Long enough for Play's start request to go out first. */
        const val LEAVE_GRACE_MS = 1_500L
        /** A play of the item started this recently: the page was left for it. */
        const val PLAY_COUNTS_MS = 10_000L

        private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var playStartedItem: String? = null
        @Volatile private var playStartedAtMs: Long = 0

        private fun nowMs() = kotlin.time.Clock.System.now().toEpochMilliseconds()

        /** [PlayerStore] — a play of [itemId] started: the page's leave must not stop its early encode. */
        fun playStarted(itemId: String) {
            playStartedAtMs = nowMs()
            playStartedItem = itemId
        }
    }
}
