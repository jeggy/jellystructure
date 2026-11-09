package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.Skin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R212 — regression guard for the pure staleness/size-guard logic `HomeSnapshotCache`'s platform
 * actuals delegate to. The actuals themselves (file I/O on Android, localStorage on Web) have no
 * test harness in this module (same limitation [[PlayerScreenTrackResolutionTest]] documents); what
 * these tests guard is the CONTRACT `isSnapshotFresh`/`exceedsSnapshotSizeCap` must uphold — the
 * 7-day cutoff and the 2MB defensive cap the phase spec's "Storage guardrails" commit to.
 */
class HomeSnapshotCacheTest {

    private fun snapshot(savedAtEpochMs: Long) = HomeSnapshot(
        feed = HomeFeed(heroes = emptyList(), channels = emptyList(), rows = emptyList()),
        uiLanguage = "en",
        skin = Skin.AURORA,
        tileScale = 1f,
        gridColumns = 6,
        portraitGridColumns = 2,
        savedAtEpochMs = savedAtEpochMs,
    )

    @Test
    fun freshSnapshotIsFresh() {
        assertTrue(isSnapshotFresh(snapshot(savedAtEpochMs = 0L), nowEpochMs = 1_000L))
    }

    @Test
    fun snapshotExactlyAtCutoffIsStillFresh() {
        assertTrue(isSnapshotFresh(snapshot(savedAtEpochMs = 0L), nowEpochMs = HOME_SNAPSHOT_STALE_AFTER_MS))
    }

    @Test
    fun snapshotOlderThanCutoffIsStale() {
        assertFalse(isSnapshotFresh(snapshot(savedAtEpochMs = 0L), nowEpochMs = HOME_SNAPSHOT_STALE_AFTER_MS + 1))
    }

    @Test
    fun snapshotFromTheFutureIsTreatedAsStaleNotFresh() {
        // Clock skew: savedAtEpochMs > now would otherwise compute a negative age.
        assertFalse(isSnapshotFresh(snapshot(savedAtEpochMs = 10_000L), nowEpochMs = 0L))
    }

    @Test
    fun smallJsonIsUnderTheCap() {
        assertFalse(exceedsSnapshotSizeCap("{}"))
    }

    @Test
    fun jsonOverTheCapIsRejected() {
        val oversized = "x".repeat((HOME_SNAPSHOT_MAX_BYTES + 1).toInt())
        assertTrue(exceedsSnapshotSizeCap(oversized))
    }

    // DEB-3966 (FR-6) — Screen scale rides the snapshot so a cold start draws its first frame at the saved scale.
    @Test
    fun screenScaleSurvivesASnapshotRoundTrip() {
        val saved = snapshot(savedAtEpochMs = 5L).copy(uiScalePct = 90)
        val back = homeSnapshotJson.decodeFromString(HomeSnapshot.serializer(), homeSnapshotJson.encodeToString(HomeSnapshot.serializer(), saved))
        assertEquals(90, back.uiScalePct)
    }

    @Test
    fun aSnapshotFromBeforeScreenScaleDrawsAt100() {
        val old = homeSnapshotJson.encodeToString(HomeSnapshot.serializer(), snapshot(savedAtEpochMs = 5L))
            .replace(Regex(",?\"uiScalePct\":\\d+"), "")
        assertFalse(old.contains("uiScalePct"), old)
        assertEquals(100, homeSnapshotJson.decodeFromString(HomeSnapshot.serializer(), old).uiScalePct)
    }
}
