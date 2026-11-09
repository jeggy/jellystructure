package dev.jellystructure.ravilo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4089, FR-R383-6 — the socket reports the same text the About window draws. */
class TestDriverAboutTest {
    @Test fun `reply uses the rendered English lines including pending rates`() {
        val figures = AppResources.Figures(632L * 1024 * 1024, 13.2, 2.1)
        val lines = aboutFigureLines(figures, "en")
        assertEquals(listOf("Memory 632 MB", "CPU 13.2 %", "Idle wakeups 2.1/s"), lines)
        assertEquals("ok open | Memory 632 MB | CPU 13.2 % | Idle wakeups 2.1/s", aboutReply(true, lines))

        val pending = aboutFigureLines(figures.copy(cpuPercent = null, wakeupsPerSecond = null), "en")
        assertEquals("ok open | Memory 632 MB | CPU — % | Idle wakeups —/s", aboutReply(true, pending))
    }

    @Test fun `reply reflects the window language and absent figures`() {
        val lines = aboutFigureLines(AppResources.Figures(64L * 1024 * 1024, 0.0, 0.0), "da")
        assertEquals("ok open | Hukommelse 64 MB | CPU 0.0 % | Vækninger i hvile 0.0/s", aboutReply(true, lines))
        assertEquals(emptyList(), aboutFigureLines(null, "en"))
        assertEquals("ok open (no figures)", aboutReply(true, emptyList()))
        assertEquals("ok closed", aboutReply(false, lines))
    }

    @Test fun `only About mutations are guarded and force overrides recent real input`() {
        assertFalse(testDriverInput(listOf("about")))
        assertTrue(testDriverInput(listOf("about", "open")))
        assertTrue(testDriverInput(listOf("about", "close")))
        assertFalse(testDriverInput(listOf("about", "unknown")))
        assertTrue(testDriverInput(listOf("cmd", "MODE_MUSIC")))
        assertFalse(testDriverRefusesInput(listOf("about"), forced = false, idleSeconds = 0))
        assertTrue(testDriverRefusesInput(listOf("about", "open"), forced = false, idleSeconds = 59))
        assertTrue(testDriverRefusesInput(listOf("about", "close"), forced = false, idleSeconds = 0))
        assertFalse(testDriverRefusesInput(listOf("about", "open"), forced = false, idleSeconds = 60))
        assertFalse(testDriverRefusesInput(listOf("about", "close"), forced = true, idleSeconds = 0))
    }
}
