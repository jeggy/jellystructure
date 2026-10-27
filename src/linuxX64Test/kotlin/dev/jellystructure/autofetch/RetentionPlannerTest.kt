package dev.jellystructure.autofetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2476 (FR-16) — retention decisions as data: Acceptance 11 and 12 without a filesystem. */
class RetentionPlannerTest {
    private fun day(n: Int) = "2026-10-" + n.toString().padStart(2, '0')

    private fun placed(n: Int, onPage: Boolean = true, missingSince: Long? = null, exists: Boolean = true) =
        RetentionRow("i$n", day(n), 100 - n, "/lib/e$n.mkv", exists, missingSince, onPage)

    private fun page(n: Int) = RetentionRow("i$n", day(n), 100 - n, null, false, null, true)

    private val newest12 = RetentionSpec("newest", 12, leavesPage = false)
    private val full = ListingEvidence(true, 13)

    @Test
    fun `a first run on 64 items downloads only the newest 12`() {
        val rows = (1..64).map { page(it % 28 + 1).copy(itemId = "p$it", published = "2026-" + (1 + it / 28).toString().padStart(2, '0') + "-" + (it % 28 + 1).toString().padStart(2, '0'), order = it) }
        assertEquals(12, RetentionPlanner.downloadable(rows, newest12).size)
    }

    @Test
    fun `the page grows to 13 and the oldest of 12 is deleted at the end of the run`() {
        val rows = (4..15).map { placed(it) } + page(16)
        assertEquals(listOf("i16"), RetentionPlanner.downloadable(rows, newest12).map { it.itemId })
        val after = (4..15).map { placed(it) } + placed(16)
        val plan = RetentionPlanner.deletions(after, newest12, full, 1_000_000)
        assertEquals(listOf("i4"), plan.deletes.map { it.row.itemId })
        assertEquals(DeleteReason.NEWEST_KEPT, plan.deletes.single().reason)
    }

    @Test
    fun `a failed download never makes room by deleting an older episode`() {
        val after = (4..15).map { placed(it) } // i16 failed and is not placed
        assertTrue(RetentionPlanner.deletions(after, newest12, full, 1_000_000).deletes.isEmpty())
    }

    @Test
    fun `a deleted episode is not downloaded again`() {
        val rows = (5..16).map { placed(it) } + page(4)
        assertTrue(RetentionPlanner.downloadable(rows, newest12).isEmpty())
    }

    @Test
    fun `raising N lets older items still on the page back in`() {
        val rows = (5..16).map { placed(it) } + page(4)
        assertEquals(listOf("i4"), RetentionPlanner.downloadable(rows, RetentionSpec("newest", 13)).map { it.itemId })
    }

    @Test
    fun `all keeps everything and downloads every missing item`() {
        val rows = (1..30).map { placed(it) } + page(31)
        assertEquals(1, RetentionPlanner.downloadable(rows, RetentionSpec("all")).size)
        assertTrue(RetentionPlanner.deletions(rows, RetentionSpec("all"), full, 1).deletes.isEmpty())
    }

    @Test
    fun `a row whose file is gone is released and nothing is deleted`() {
        val rows = listOf(placed(1, exists = false)) + (2..13).map { placed(it) }
        val plan = RetentionPlanner.deletions(rows, newest12, full, 1)
        assertEquals(listOf("i1"), plan.released.map { it.itemId })
        assertTrue(plan.deletes.isEmpty())
    }

    @Test
    fun `leaves the page waits for a second full read six hours later`() {
        val spec = RetentionSpec("newest", 12, leavesPage = true)
        val now = 1_000_000L
        val first = (6..10).map { placed(it, onPage = false, missingSince = now) } + (11..16).map { placed(it) }
        assertTrue(RetentionPlanner.deletions(first, spec, full, now).deletes.isEmpty())
        val later = first.map { if (!it.onPage) it.copy(missingSince = now - RetentionPlanner.SIX_HOURS) else it }
        val plan = RetentionPlanner.deletions(later, spec, full, now)
        assertEquals(5, plan.deletes.size)
        assertTrue(plan.deletes.all { it.reason == DeleteReason.LEFT_PAGE })
    }

    @Test
    fun `an empty listing or one sharing nothing deletes nothing and says why`() {
        val spec = RetentionSpec("newest", 12, leavesPage = true)
        val now = 1_000_000L
        val gone = (1..6).map { placed(it, onPage = false, missingSince = now - 10 * 3600) }
        val empty = RetentionPlanner.deletions(gone, spec, ListingEvidence(true, 0), now)
        assertTrue(empty.deletes.isEmpty()); assertTrue(empty.note!!.contains("empty"))
        val strangers = RetentionPlanner.deletions(gone, spec, ListingEvidence(true, 12), now)
        assertTrue(strangers.deletes.isEmpty()); assertTrue(strangers.note!!.contains("shares no episode"))
    }

    @Test
    fun `a failed read applies no retention of either kind`() {
        val spec = RetentionSpec("newest", 12, leavesPage = true)
        val rows = (1..14).map { placed(it, onPage = it > 3, missingSince = if (it <= 3) 0L else null) }
        val plan = RetentionPlanner.deletions(rows, spec, ListingEvidence(false, 11), 1_000_000)
        assertTrue(plan.deletes.isEmpty())
        assertEquals("retention not applied, the listing could not be read in full", plan.note)
        assertNull(RetentionPlanner.deletions((1..5).map { placed(it) }, spec, ListingEvidence(false, 5), 1).note)
    }

    @Test
    fun `ties on the date go by listing order`() {
        val a = RetentionRow("a", "2026-10-20", 0); val b = RetentionRow("b", "2026-10-20", 1); val c = RetentionRow("c", null, 2)
        assertEquals(listOf("a", "b", "c"), listOf(c, b, a).sortedWith(RetentionPlanner.NEWEST_FIRST).map { it.itemId })
    }
}
