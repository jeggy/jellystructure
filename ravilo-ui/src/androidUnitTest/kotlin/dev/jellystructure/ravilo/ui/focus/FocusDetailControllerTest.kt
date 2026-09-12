package dev.jellystructure.ravilo.ui.focus

import dev.jellystructure.shared.tv.FocusDetailFacts
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase R240 — [FocusDetailController] is the dwell/eligibility state machine behind L/J. It renders,
 * never computes (FR-R240-1), never states a title that isn't focused while a dwell is in flight
 * (FR-R240-6), and re-runs the reveal rule for whatever is focused right now on a config flip
 * (FR-R240-13) — all three are plain suspend/state-flow logic, testable with no Compose runtime.
 */
class FocusDetailControllerTest {

    private fun card(id: String) = MediaCard(
        id = id,
        kind = MediaKind.MOVIE,
        title = "Title $id",
        year = 2020,
        genre = null,
        rating = null,
        posterUrl = null,
        backdropUrl = null,
        focusDetail = FocusDetailFacts(year = 2020),
    )

    @Test
    fun `mode none never emits, even with facts present`() = runBlocking {
        val controller = FocusDetailController(this)
        controller.onFocus("row1", "a", card("a"), mode = "none", delayMs = 0)
        assertNull(controller.current.value)
    }

    @Test
    fun `absent facts never emits, even with a real mode`() = runBlocking {
        val controller = FocusDetailController(this)
        val noFacts = card("a").copy(focusDetail = null)
        controller.onFocus("row1", "a", noFacts, mode = "line", delayMs = 0)
        assertNull(controller.current.value)
    }

    @Test
    fun `delay zero reveals immediately, not deferred by a zero-length timer`() = runBlocking {
        val controller = FocusDetailController(this)
        controller.onFocus("row1", "a", card("a"), mode = "line", delayMs = 0)
        // No delay() / yield() at all — if this were scheduled via launch+delay(0) it would still be
        // pending here, since even a zero-length delay suspends at least one dispatch.
        assertEquals("a", controller.current.value?.itemKey)
    }

    // Real wall-clock delays (this module has no kotlinx-coroutines-test dependency, see build.gradle.kts's
    // "this module's first tests" note), so every gap below is sized with a wide margin against scheduler
    // jitter rather than tuned to the millisecond: the assertion is "well before" / "well after" the dwell
    // deadline, never "exactly at" it.

    @Test
    fun `while the dwell runs nothing is stated, not even the previous title`() = runBlocking {
        val controller = FocusDetailController(this)
        controller.onFocus("row1", "a", card("a"), mode = "line", delayMs = 60)
        delay(20) // well before the 60ms deadline
        assertNull(controller.current.value)
        delay(150) // well after it
        assertEquals("a", controller.current.value?.itemKey)

        // Sweep onto "b" before "a" settles again — the previous (now-stale) reveal must not linger.
        controller.onFocus("row1", "a", card("a"), mode = "line", delayMs = 60)
        delay(20)
        controller.onFocus("row1", "b", card("b"), mode = "line", delayMs = 60)
        delay(20) // neither "a" nor "b" has had 60ms yet
        assertNull(controller.current.value)
        delay(150)
        assertEquals("b", controller.current.value?.itemKey)
    }

    @Test
    fun `a superseded focus cancels the prior dwell so it never fires late`() = runBlocking {
        val controller = FocusDetailController(this)
        controller.onFocus("row1", "a", card("a"), mode = "line", delayMs = 60)
        delay(20)
        controller.onFocus("row1", "b", card("b"), mode = "line", delayMs = 60)
        // "a"'s original 60ms deadline (from its own start) has now passed; only "b"'s dwell — which
        // restarted the clock — may still be pending.
        delay(50)
        assertNull(controller.current.value)
        delay(150) // well past "b"'s own deadline too
        assertEquals("b", controller.current.value?.itemKey)
    }

    @Test
    fun `clear removes the current reveal and blocks an in-flight dwell`() = runBlocking {
        val controller = FocusDetailController(this)
        controller.onFocus("row1", "a", card("a"), mode = "line", delayMs = 60)
        delay(20)
        controller.clear()
        delay(150)
        assertNull(controller.current.value)
    }

    @Test
    fun `reapply re-runs the reveal rule for whatever is focused right now`() = runBlocking {
        val controller = FocusDetailController(this)
        controller.onFocus("row1", "a", card("a"), mode = "none", delayMs = 0)
        assertNull(controller.current.value) // mode was "none" at focus time

        // FR-R240-13 — flipping the switch re-derives the CURRENTLY focused tile's state, without a
        // fresh focus move.
        controller.reapply(mode = "line", delayMs = 0)
        assertEquals("a", controller.current.value?.itemKey)
        assertEquals("line", controller.current.value?.mode)
    }

    @Test
    fun `reapply is a no-op when nothing is currently focused`() = runBlocking {
        val controller = FocusDetailController(this)
        controller.reapply(mode = "line", delayMs = 0)
        assertNull(controller.current.value)
    }
}
