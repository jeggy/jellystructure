package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.UpcomingFeed
import dev.jellystructure.shared.tv.UpcomingItem
import dev.jellystructure.shared.tv.UpcomingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2183 (DEB-2078, FR-2..FR-6) — when a stack forms, what it says and which rows it opens. Stand-in titles. */
class UpcomingStacksTest {
    private fun ep(
        n: Int, key: String? = "s1", date: String = "2026-11-01", season: Int = 1, time: String? = "04:00",
        status: UpcomingStatus = UpcomingStatus.MONITORED, progress: Int? = null, itemId: String? = null,
        id: String = "ep-$key-$season-$n",
    ) = UpcomingItem(
        id = id, kind = MediaKind.SERIES, title = "Stand-in Lighthouse", date = date, time = time, season = season,
        episode = n, status = status, progress = progress, seriesKey = key, itemId = itemId,
    )

    private fun film(id: String = "mv-1", date: String = "2026-11-01") =
        UpcomingItem(id = id, kind = MediaKind.MOVIE, title = "Stand-in Ferry", date = date)

    private fun shape(entries: List<UpcomingEntry>) = entries.map {
        when (it) {
            is UpcomingEntry.Single -> it.item.id
            is UpcomingEntry.Stack -> "stack:" + it.members.joinToString("+") { m -> m.id }
        }
    }

    @Test
    fun `two same-key episodes on one day make one stack and a third on another day stays single`() {
        val a = ep(1); val b = ep(2); val c = ep(3, date = "2026-11-08")
        assertEquals(listOf("stack:${a.id}+${b.id}", c.id), shape(stackUpcoming(listOf(a, b, c), byDay = true)))
    }

    @Test
    fun `a lone episode stays single`() {
        val a = ep(1)
        assertEquals(listOf(UpcomingEntry.Single(a)), stackUpcoming(listOf(a), byDay = true))
    }

    @Test
    fun `films never stack`() {
        val f1 = film("mv-1"); val f2 = film("mv-2")
        assertEquals(listOf("mv-1", "mv-2"), shape(stackUpcoming(listOf(f1, f2), byDay = true)))
        assertEquals(listOf("mv-1", "mv-2"), shape(stackUpcoming(listOf(f1, f2), byDay = false)))
    }

    @Test
    fun `a null series key with an item id stacks by the item id`() {
        val a = ep(1, key = null, itemId = "cat-9", id = "x1"); val b = ep(2, key = null, itemId = "cat-9", id = "x2")
        val out = stackUpcoming(listOf(a, b), byDay = true)
        assertEquals(listOf("stack:x1+x2"), shape(out))
        assertEquals("cat-9", (out.single() as UpcomingEntry.Stack).key)
    }

    @Test
    fun `episodes with neither key nor item id never stack`() {
        val a = ep(1, key = null, id = "x1"); val b = ep(2, key = null, id = "x2")
        assertEquals(listOf("x1", "x2"), shape(stackUpcoming(listOf(a, b), byDay = true)))
        assertEquals(listOf("x1", "x2"), shape(stackUpcoming(listOf(a, b), byDay = false)))
    }

    @Test
    fun `two series on one day make two stacks`() {
        val a = ep(1, key = "s1"); val b = ep(1, key = "s2"); val c = ep(2, key = "s1"); val d = ep(2, key = "s2")
        assertEquals(listOf("stack:${a.id}+${c.id}", "stack:${b.id}+${d.id}"), shape(stackUpcoming(listOf(a, b, c, d), byDay = true)))
    }

    @Test
    fun `the missing row stacks the same key across days`() {
        val a = ep(3, date = "2026-10-20", status = UpcomingStatus.MISSING)
        val b = ep(2, date = "2026-10-13", status = UpcomingStatus.MISSING)
        val c = ep(1, date = "2026-10-06", status = UpcomingStatus.MISSING)
        assertEquals(listOf("stack:${a.id}+${b.id}+${c.id}"), shape(stackUpcoming(listOf(a, b, c), byDay = false)))
        assertEquals(listOf(a.id, b.id, c.id), shape(stackUpcoming(listOf(a, b, c), byDay = true)))
    }

    @Test
    fun `a stack takes its first member's position and members keep feed order`() {
        val x = film(); val a = ep(2); val y = ep(1, key = "s2"); val b = ep(1)
        val out = stackUpcoming(listOf(x, a, y, b), byDay = true)
        assertEquals(listOf("mv-1", "stack:${a.id}+${b.id}", y.id), shape(out))
    }

    @Test
    fun `the chip filters first and stacking runs on what is left`() {
        val a = ep(1); val b = ep(2); val f = film()
        val all = listOf(a, f, b)
        assertEquals(listOf("stack:${a.id}+${b.id}", "mv-1"), shape(stackUpcoming(all, byDay = true)))
        assertEquals(listOf("stack:${a.id}+${b.id}"), shape(stackUpcoming(all.filter { it.kind == MediaKind.SERIES }, byDay = true)))
        assertEquals(listOf("mv-1"), shape(stackUpcoming(all.filter { it.kind == MediaKind.MOVIE }, byDay = true)))
    }

    @Test
    fun `the restore keys name the stack and the date`() {
        val a = ep(1); val b = ep(2)
        val stack = stackUpcoming(listOf(a, b), byDay = true).single()
        assertEquals("stack:s1:2026-11-01", stack.restoreKey(inMissingRow = false))
        assertEquals("missing-stack:s1", stack.restoreKey(inMissingRow = true))
        assertEquals(a.id, UpcomingEntry.Single(a).restoreKey(false))
        assertEquals("missing:${a.id}", UpcomingEntry.Single(a).restoreKey(true))
        assertEquals(a, stack.opens())
    }

    @Test
    fun `members of a day stack come from the same key and date`() {
        val a = ep(1); val b = ep(2); val c = ep(3, date = "2026-11-08"); val other = ep(1, key = "s2")
        val feed = UpcomingFeed(true, items = listOf(a, other, b, c))
        assertEquals(listOf(a, b), stackMembersOf(feed, a))
        assertEquals(listOf(c), stackMembersOf(feed, c))
        assertEquals(listOf(other), stackMembersOf(feed, other))
    }

    @Test
    fun `members of a missing stack come from the missing list across days`() {
        val a = ep(2, date = "2026-10-13", status = UpcomingStatus.MISSING)
        val b = ep(1, date = "2026-10-06", status = UpcomingStatus.MISSING)
        val upcoming = ep(3, date = "2026-11-01")
        val feed = UpcomingFeed(true, items = listOf(upcoming), missing = listOf(a, b))
        assertEquals(listOf(a, b), stackMembersOf(feed, a))
    }

    @Test
    fun `a film has only itself as a stack member`() {
        val f = film()
        assertEquals(listOf(f), stackMembersOf(UpcomingFeed(true, items = listOf(f, film("mv-2"))), f))
    }

    @Test
    fun `all available reads already available`() {
        val s = stackStatus(List(3) { ep(it + 1, status = UpcomingStatus.AVAILABLE) })
        assertEquals(StackPill.ALL_AVAILABLE, s.pill); assertNull(s.downloading)
    }

    @Test
    fun `four of six available reads k of n`() {
        val s = stackStatus(List(6) { ep(it + 1, status = if (it < 4) UpcomingStatus.AVAILABLE else UpcomingStatus.MONITORED) })
        assertEquals(StackPill.SOME_AVAILABLE, s.pill); assertEquals(4, s.available); assertEquals(6, s.size)
    }

    @Test
    fun `all missing reads missing`() {
        assertEquals(StackPill.ALL_MISSING, stackStatus(List(2) { ep(it + 1, status = UpcomingStatus.MISSING) }).pill)
    }

    @Test
    fun `none available and none missing has no pill`() {
        assertEquals(StackPill.NONE, stackStatus(List(2) { ep(it + 1) }).pill)
    }

    @Test
    fun `one downloading sits beside four of six`() {
        val members = List(6) { i ->
            ep(i + 1, status = when { i < 4 -> UpcomingStatus.AVAILABLE; i == 4 -> UpcomingStatus.DOWNLOADING; else -> UpcomingStatus.MONITORED }, progress = if (i == 4) 37 else null)
        }
        val s = stackStatus(members)
        assertEquals(StackPill.SOME_AVAILABLE, s.pill); assertEquals(37, s.downloading?.progress)
    }

    @Test
    fun `two downloading shows the first in episode order`() {
        val s = stackStatus(listOf(
            ep(5, status = UpcomingStatus.DOWNLOADING, progress = 80), ep(2, status = UpcomingStatus.DOWNLOADING, progress = 10),
        ))
        assertEquals(StackPill.NONE, s.pill); assertEquals(2, s.downloading?.episode)
    }

    @Test
    fun `the earliest time skips a member without one`() {
        assertEquals("03:30", stackEarliestTime(listOf(ep(1, time = "04:00"), ep(2, time = null), ep(3, time = "03:30"))))
        assertNull(stackEarliestTime(listOf(ep(1, time = null), ep(2, time = null))))
    }

    @Test
    fun `the earliest date of a missing stack`() {
        assertEquals("2026-10-06", stackEarliestDate(listOf(ep(2, date = "2026-10-13"), ep(1, date = "2026-10-06"))))
    }

    @Test
    fun `the range within a season`() {
        assertEquals("S02E01–E08", stackEpisodeRange(List(8) { ep(it + 1, season = 2) }))
    }

    @Test
    fun `the range across seasons`() {
        assertEquals("S01E10–S02E02", stackEpisodeRange(listOf(ep(10, season = 1), ep(1, season = 2), ep(2, season = 2))))
    }

    @Test
    fun `the range of members out of order and a lone code`() {
        assertEquals("S01E01–E03", stackEpisodeRange(listOf(ep(3), ep(1), ep(2))))
        assertEquals("S01E04", stackEpisodeRange(listOf(ep(4))))
        assertTrue(stackEpisodeRange(listOf(UpcomingItem(id = "x", kind = MediaKind.SERIES, title = "t", date = "2026-11-01"))) == null)
    }
}
