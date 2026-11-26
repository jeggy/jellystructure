package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5697 (FR-61) — the freshness and size rules both clients' Home snapshots share. */
class HomeSnapshotRulesTest {
    @Test fun freshSnapshotIsFresh() = assertTrue(isSnapshotFresh(0L, 1_000L))

    @Test fun snapshotExactlyAtCutoffIsStillFresh() = assertTrue(isSnapshotFresh(0L, SNAPSHOT_STALE_AFTER_MS))

    @Test fun snapshotOlderThanCutoffIsStale() = assertFalse(isSnapshotFresh(0L, SNAPSHOT_STALE_AFTER_MS + 1))

    @Test fun snapshotFromTheFutureIsStaleNotFresh() = assertFalse(isSnapshotFresh(10_000L, 0L))

    @Test fun cutoffIsSevenDays() = kotlin.test.assertEquals(604_800_000L, SNAPSHOT_STALE_AFTER_MS)

    @Test fun smallJsonIsUnderTheCap() = assertFalse(exceedsSnapshotSizeCap("{}"))

    @Test fun jsonAtTheCapIsKeptAndOneOverIsRejected() {
        assertFalse(exceedsSnapshotSizeCap("x".repeat(SNAPSHOT_MAX_BYTES.toInt())))
        assertTrue(exceedsSnapshotSizeCap("x".repeat(SNAPSHOT_MAX_BYTES.toInt() + 1)))
    }
}
