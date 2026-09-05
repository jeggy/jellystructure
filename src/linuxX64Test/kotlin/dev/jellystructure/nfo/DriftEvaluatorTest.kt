package dev.jellystructure.nfo

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.AppConfig
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 193 — the "Jellyfin hasn't re-read the NFO yet" banner. `evaluate()` never reaches Jellyfin for
 * the JELLYFIN_BEHIND state (the check is `jfSyncedAt < nfoWrittenAt` on the stored item alone), so a
 * bare [JellyfinClient] that's never actually called is enough here.
 */
class DriftEvaluatorTest {

    private fun baseItem(nfoWrittenAt: Long?, jfSyncedAt: Long?): MediaItem {
        val item = MediaItem(
            id = "test-item", title = "Test", year = 2024, kind = MediaKind.MOVIE, path = "/tmp/test.mkv",
            tmdbId = null, originalLanguage = null, posterPath = null, overview = null,
            tracks = emptyList(), issueCount = 0, scannedAt = 0L,
        )
        // Make the on-disk-equivalent hash agree so evaluate() falls through NFO_STALE into the
        // jfSyncedAt/nfoWrittenAt comparison, exactly like a converged write followed by a pending sync.
        val cfg = AppConfig()
        val hash = NfoWriter.contentHash(item, cfg.apiKeys.jellyfinUrl, cfg.metadata.ageRatingCascade)
        return item.copy(nfoHash = hash, nfoWrittenAt = nfoWrittenAt, jfSyncedAt = jfSyncedAt)
    }

    @Test
    fun jellyfinBehindReportedWhenNoScanIsRunning() = runBlocking {
        val item = baseItem(nfoWrittenAt = 1000L, jfSyncedAt = 500L)
        val result = DriftEvaluator.evaluate(item, JellyfinClient(), AppConfig(), scanRunning = false)
        assertEquals(DriftState.JELLYFIN_BEHIND.name.lowercase(), result.state)
        assertEquals(1000L, result.nfoWrittenAt)
    }

    @Test
    fun jellyfinBehindSuppressedWhileAScanIsRunning() = runBlocking {
        // Phase 193 (FR-193-5) — a scan in progress will reach this item's (now library-wide, FR-193-2)
        // sync_jellyfin pass before it ends, so the transient case shouldn't surface a banner at all.
        val item = baseItem(nfoWrittenAt = 1000L, jfSyncedAt = 500L)
        val result = DriftEvaluator.evaluate(item, JellyfinClient(), AppConfig(), scanRunning = true)
        assertEquals(DriftState.CONVERGED.name.lowercase(), result.state)
        assertNull(result.nfoWrittenAt)
    }

    @Test
    fun convergedWhenAlreadySynced() = runBlocking {
        val item = baseItem(nfoWrittenAt = 500L, jfSyncedAt = 1000L)
        val result = DriftEvaluator.evaluate(item, JellyfinClient(), AppConfig(), scanRunning = false)
        // No jellyfinId/Jellyfin config on this item → falls straight to CONVERGED without a live call.
        assertEquals(DriftState.CONVERGED.name.lowercase(), result.state)
    }
}
