package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 316, FR-316-10 / FR-316-5 test 11 (DEB-1330) — the ring of `media_history` trims every action to its newest
 * rows except the mover's and the repair's record, which FR-316-7 reads and FR-316-9 / FR-316-7 write as an undo.
 */
class MediaHistoryTrimTest {

    private val file = "/tmp/jellystructure-test-history-trim-${getpid()}.db"

    @AfterTest fun tearDown() {
        for (f in listOf(file, "$file-wal", "$file-shm")) runCatching { platform.posix.remove(f) }
    }

    private fun history(cap: Long): MediaHistory {
        runCatching { platform.posix.remove(file) }
        return MediaHistory(createDatabase(file), cap)
    }

    private fun details(h: MediaHistory, mediaId: String) = h.forItem(mediaId).map { it.detail }

    @Test fun `a moved_into_folder and a rebound_jellyfin_id line outlive the cap and the oldest line of any other action does not`() {
        val cap = 5L
        val h = history(cap)
        h.record("other-0", "edited_title", "oldest other line")
        h.record("film", "moved_into_folder", "Moved into its own folder X")
        h.record("film", "rebound_jellyfin_id", "rebound")
        for (i in 1..cap) h.record("other-$i", "edited_title", "other line $i")

        assertEquals(listOf("Moved into its own folder X"), details(h, "film").filter { it.startsWith("Moved") })
        assertEquals(listOf("rebound"), details(h, "film").filter { it == "rebound" })
        assertEquals(emptyList(), details(h, "other-0"), "the oldest other line is trimmed")
        for (i in 1..cap) assertEquals(listOf("other line $i"), details(h, "other-$i"))
    }

    @Test fun `a protected line is never trimmed however many lines follow and the others stay at the cap`() {
        val cap = 4L
        val h = history(cap)
        h.record("film", "moved_into_folder", "moved")
        repeat(30) { h.record("other-$it", "edited_title", "line $it") }
        assertEquals(1, details(h, "film").size)
        val others = (0 until 30).count { details(h, "other-$it").isNotEmpty() }
        assertEquals(cap.toInt(), others)
        assertTrue(details(h, "other-29").isNotEmpty() && details(h, "other-26").isNotEmpty() && details(h, "other-25").isEmpty())
    }

    @Test fun `with nothing protected the ring keeps exactly its newest rows`() {
        val cap = 3L
        val h = history(cap)
        repeat(10) { h.record("m$it", "edited_title", "line $it") }
        val kept = (0 until 10).filter { details(h, "m$it").isNotEmpty() }
        assertEquals(listOf(7, 8, 9), kept)
    }
}
