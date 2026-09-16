package dev.jellystructure.ravilo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/** R249 (FR-R249-1/2/3) — the badge that says where you are is never covered by the one that says what's coming. */
class TileCornerBadgesTest {

    @Test
    fun `a Continue tile with a scheduled episode shows both, one per corner`() {
        val c = resolveCornerBadges(episodeBadge = "S17:E8", upcomingLabel = "S18E07", isNew = false, watched = false)
        assertEquals(CornerBadges(CornerBadge.EPISODE, CornerBadge.UPCOMING), c)
    }

    @Test
    fun `elsewhere Soon stays top-start and alone`() {
        val c = resolveCornerBadges(episodeBadge = null, upcomingLabel = "S18E07", isNew = true, watched = false)
        assertEquals(CornerBadges(CornerBadge.UPCOMING, null), c)
    }

    @Test
    fun `a Continue tile with no scheduled episode shows the episode badge alone`() {
        val c = resolveCornerBadges(episodeBadge = "S17:E8", upcomingLabel = null, isNew = false, watched = false)
        assertEquals(CornerBadges(CornerBadge.EPISODE, null), c)
    }

    @Test
    fun `watched owns top-end and Soon keeps top-start`() {
        val c = resolveCornerBadges(episodeBadge = null, upcomingLabel = "S18E07", isNew = true, watched = true)
        assertEquals(CornerBadges(CornerBadge.UPCOMING, CornerBadge.WATCHED), c)
    }

    @Test
    fun `NEW shows only when nothing outranks it and the title is unwatched`() {
        assertEquals(CornerBadges(CornerBadge.NEW, null), resolveCornerBadges(null, null, isNew = true, watched = false))
        assertEquals(CornerBadges(null, CornerBadge.WATCHED), resolveCornerBadges(null, null, isNew = true, watched = true))
        assertEquals(CornerBadges(CornerBadge.EPISODE, null), resolveCornerBadges("S1:E1", null, isNew = true, watched = false))
    }

    @Test
    fun `a Continue tile that is watched and has a scheduled episode drops nothing it can place`() {
        // Episode owns top-start, watched owns top-end; Soon has nowhere left and is omitted (FR-R249-3).
        val c = resolveCornerBadges(episodeBadge = "S17:E8", upcomingLabel = "S18E07", isNew = false, watched = true)
        assertEquals(CornerBadges(CornerBadge.EPISODE, CornerBadge.WATCHED), c)
    }
}
