package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R329 (FR-R329-1) — the read-out the spike's numbers come through. With no film loaded it must say so: a line of
 * zeros would read as "no dropped frames" and pass the bar without a film ever having played.
 */
class DesktopPlayerProbeTest {
    @Test
    fun `no film loaded says so rather than reporting zeros`() {
        assertEquals("no film loaded", DesktopPlayerProbe.line())
    }

    @Test
    fun `cpu is a percentage or honestly absent`() {
        val cpu = DesktopPlayerProbe.processCpuPercent()
        assertTrue(cpu == "-" || cpu.endsWith("%"), "unexpected cpu reading: $cpu")
    }

    @Test
    fun `heap in use is reported`() {
        assertTrue(DesktopPlayerProbe.heapMb() > 0)
    }
}
