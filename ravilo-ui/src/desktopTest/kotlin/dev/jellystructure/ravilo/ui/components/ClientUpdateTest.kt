package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.shared.tv.RaviloVersion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-5851 (FR-2, FR-3) — when the update check runs, and what it keeps. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientUpdateTest {
    private val hour = 3_600_000L

    private class Fixture(scope: TestScope, var answer: String? = "v1.73-4-gabcdef12", app: String = "1.72") {
        var asks = 0
        var record: String? = null
        val player = MutableStateFlow(false)
        val check = ClientUpdateCheck(
            appVersion = { app },
            fetchVersion = { asks++; answer },
            readRecord = { record },
            writeRecord = { record = it },
            nowMs = { scope.currentTime },
            playerOpen = player,
        )
    }

    @Test
    fun `nothing is asked before Home has drawn`() = runTest {
        val f = Fixture(this)
        val job = launch { f.check.run() }
        advanceTimeBy(5 * hour); runCurrent()
        assertEquals(0, f.asks)
        f.check.homeDrawn(); runCurrent()
        assertEquals(1, f.asks)
        job.cancel()
    }

    @Test
    fun `asks once a day and shows the line after 48 hours`() = runTest {
        val f = Fixture(this)
        val job = launch { f.check.run() }
        f.check.homeDrawn(); runCurrent()
        assertNull(f.check.notice.value)
        advanceTimeBy(24 * hour + 1); runCurrent()
        assertEquals(2, f.asks)
        assertNull(f.check.notice.value)
        advanceTimeBy(24 * hour); runCurrent()
        assertEquals(3, f.asks)
        assertEquals(RaviloVersion(1, 73), f.check.notice.value)
        job.cancel()
    }

    @Test
    fun `a due check waits for the player to close`() = runTest {
        val f = Fixture(this)
        val job = launch { f.check.run() }
        f.check.homeDrawn(); runCurrent()
        f.player.value = true
        advanceTimeBy(30 * hour); runCurrent()
        assertEquals(1, f.asks)
        f.player.value = false; runCurrent()
        assertEquals(2, f.asks)
        job.cancel()
    }

    @Test
    fun `a first check does not start while a player is open`() = runTest {
        val f = Fixture(this)
        f.player.value = true
        val job = launch { f.check.run() }
        f.check.homeDrawn(); runCurrent()
        assertEquals(0, f.asks)
        f.player.value = false; runCurrent()
        assertEquals(1, f.asks)
        job.cancel()
    }

    @Test
    fun `a failed ask keeps the last answer and the record`() = runTest {
        val f = Fixture(this)
        f.record = encodeFirstSeen(dev.jellystructure.shared.tv.UpdateFirstSeen(RaviloVersion(1, 73), -49 * hour))
        f.check.checkOnce()
        assertEquals(RaviloVersion(1, 73), f.check.notice.value)
        f.answer = null
        val before = f.record
        f.check.checkOnce()
        assertEquals(RaviloVersion(1, 73), f.check.notice.value)
        assertEquals(before, f.record)
    }

    @Test
    fun `a dev build is never told and keeps no record`() = runTest {
        val f = Fixture(this, app = "1.72-3-gabcdef12")
        f.check.checkOnce()
        assertNull(f.check.notice.value)
        assertNull(f.record)
    }

    @Test
    fun `the record round-trips and a bad one reads as nothing`() {
        val v = dev.jellystructure.shared.tv.UpdateFirstSeen(RaviloVersion(1, 73), 12345)
        assertEquals(v, decodeFirstSeen(encodeFirstSeen(v)))
        assertNull(decodeFirstSeen("junk"))
        assertNull(decodeFirstSeen("1.73|x"))
        assertNull(decodeFirstSeen(null))
    }
}
