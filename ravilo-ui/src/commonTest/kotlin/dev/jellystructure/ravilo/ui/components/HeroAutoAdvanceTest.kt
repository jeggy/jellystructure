package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4283 (FR-R383-3) — the hero's slide interval runs only while the hero can be seen. */
@OptIn(ExperimentalCoroutinesApi::class)
class HeroAutoAdvanceTest {
    @Test
    fun `an in-view hero is due after one interval`() = runTest {
        val visible = mutableStateOf(true)
        awaitHeroAdvance(7_000L) { visible.value }
        assertEquals(7_000L, currentTime)
    }

    @Test
    fun `a hero scrolled out of view never advances`() = runTest {
        val visible = mutableStateOf(false)
        var due = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { awaitHeroAdvance(7_000L) { visible.value }; due = true }
        advanceTimeBy(60_000L); runCurrent()
        assertFalse(due, "no slide change while off screen")
        job.cancel()
    }

    @Test
    fun `leaving view mid-interval drops the wait and coming back starts a full interval`() = runTest {
        val visible = mutableStateOf(true)
        var due = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { awaitHeroAdvance(7_000L) { visible.value }; due = true }
        advanceTimeBy(5_000L); runCurrent()
        visible.value = false; Snapshot.sendApplyNotifications(); runCurrent()
        advanceTimeBy(20_000L); runCurrent()
        assertFalse(due)
        visible.value = true; Snapshot.sendApplyNotifications(); runCurrent()
        val back = currentTime
        advanceTimeBy(6_999L); runCurrent()
        assertFalse(due, "the 5 s already shown before leaving do not count")
        advanceTimeBy(1L); runCurrent()
        assertTrue(due)
        assertEquals(back + 7_000L, currentTime)
    }
}
