package dev.jellystructure.ravilo.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R383 (FR-R383-6) — the About window's figures. The kernel read is a Swift export that only a Mac runs, so CI pins what
 * is arithmetic: how two counter reads become CPU % and wakeups per second, and how a byte count reads.
 */
class AppResourcesTest {
    private val second = 1_000_000_000L

    @Test fun `two reads two seconds apart give CPU percent of one core and wakeups per second`() {
        val a = AppResources.Sample(wallNanos = 10 * second, footprintBytes = 300L shl 20, cpuNanos = 5 * second, idleWakeups = 1_000)
        val b = AppResources.Sample(wallNanos = 12 * second, footprintBytes = 357L shl 20, cpuNanos = 5 * second + second, idleWakeups = 1_240)
        val f = AppResources.figures(a, b)
        assertEquals(357L shl 20, f.footprintBytes)
        assertEquals(50.0, f.cpuPercent!!, 1e-9)
        assertEquals(120.0, f.wakeupsPerSecond!!, 1e-9)
    }

    @Test fun `a process using two cores fully reads 200 percent, as Activity Monitor shows it`() {
        val a = AppResources.Sample(0, 0, 0, 0)
        val b = AppResources.Sample(second, 0, 2 * second, 0)
        assertEquals(200.0, AppResources.figures(a, b).cpuPercent!!, 1e-9)
    }

    @Test fun `the first read has memory but no rate, and so has a read with no time between`() {
        val a = AppResources.Sample(5 * second, 64L shl 20, second, 10)
        val first = AppResources.figures(null, a)
        assertEquals(64L shl 20, first.footprintBytes)
        assertNull(first.cpuPercent)
        assertNull(first.wakeupsPerSecond)
        assertNull(AppResources.figures(a, a).cpuPercent)
    }

    @Test fun `a counter that went backwards reads zero, never a negative rate`() {
        val a = AppResources.Sample(0, 0, 9 * second, 500)
        val b = AppResources.Sample(second, 0, 3 * second, 100)
        val f = AppResources.figures(a, b)
        assertEquals(0.0, f.cpuPercent!!, 1e-9)
        assertEquals(0.0, f.wakeupsPerSecond!!, 1e-9)
    }

    @Test fun `memory reads in MB under a thousand and in GB above, with a dot`() {
        assertEquals("357 MB", AppResources.formatMemory(357L * 1024 * 1024 + 4096))
        assertEquals("999 MB", AppResources.formatMemory(999L * 1024 * 1024))
        assertEquals("1.0 GB", AppResources.formatMemory(1024L * 1024 * 1024))
        assertEquals("1.3 GB", AppResources.formatMemory((1.3 * 1024 * 1024 * 1024).toLong()))
        assertEquals("4.4 GB", AppResources.formatMemory((4.4 * 1024 * 1024 * 1024).toLong()))
    }

    @Test fun `an unknown rate reads as a dash`() {
        assertEquals("—", AppResources.formatCpu(null))
        assertEquals("—", AppResources.formatWakeups(null))
        assertEquals("2.7", AppResources.formatCpu(2.66))
        assertEquals("38.0", AppResources.formatWakeups(38.0))
    }

    @Test fun `without the Swift library there is no read, not a crash`() {
        // Linux, and a Mac without libravilo-mac.dylib: MacNative.lib is null.
        assertNull(AppResources.read())
        assertNotNull(AppResources.figures(null, AppResources.Sample(0, 1, 2, 3)))
    }
}
