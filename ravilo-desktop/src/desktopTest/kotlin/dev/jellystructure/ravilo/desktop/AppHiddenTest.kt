package dev.jellystructure.ravilo.desktop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R383 (FR-R383-3, FR-R383-7) — a hidden window requests no frames. Compose Desktop's window needs a display, which CI
 * has not, so the test pins what the window's frames follow from: the window is `visible` only when [AppHidden.windowVisible]
 * says so, and an app launched hidden is told it is hidden before and just after its first frame. A window that is not
 * visible is not drawn into: the frame counter reads 0 (the QA row reads `frames:` on a Mac).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppHiddenTest {
    @Test fun `a window the Mac hides is not visible, whether or not the viewer closed it`() {
        assertTrue(AppHidden.windowVisible(shown = true, appHidden = false))
        assertFalse(AppHidden.windowVisible(shown = true, appHidden = true))
        assertFalse(AppHidden.windowVisible(shown = false, appHidden = false))
        assertFalse(AppHidden.windowVisible(shown = false, appHidden = true))
    }

    @Test fun `an app launched hidden is hidden from the first read, with no hide event`() = runTest {
        val told = mutableListOf<Boolean>()
        AppHidden.readAtStartup(read = { true }) { told += it }
        assertEquals(true, told.first(), "the first answer decides the first frame")
        assertTrue(told.all { it })
    }

    @Test fun `a hide that lands after the first read is still found`() = runTest {
        // `open -g -j` can hide the app a moment after NSApp exists: the first read says no, a later one says yes.
        var reads = 0
        val told = mutableListOf<Boolean>()
        AppHidden.readAtStartup(read = { reads++ >= 2 }) { told += it }
        assertEquals(false, told.first())
        assertEquals(true, told.last())
    }

    @Test fun `a Mac that unhid the app is believed over the first read`() = runTest {
        var reads = 0
        val told = mutableListOf<Boolean>()
        AppHidden.readAtStartup(read = { reads++ == 0 }) { told += it }
        assertEquals(true, told.first())
        assertEquals(false, told.last())
    }

    @Test fun `without the Swift library the app is never hidden, and the re-reads stop after about ten seconds`() = runTest {
        // Linux and a Mac without the library: MacNative.lib is null, so now() is false, not a crash.
        assertFalse(AppHidden.now())
        val told = mutableListOf<Boolean>()
        AppHidden.readAtStartup(read = { false }) { told += it }
        assertEquals(AppHidden.STARTUP_REREADS.size + 1, told.size)
        assertTrue(told.none { it })
        assertTrue(currentTime in 9_000..12_000, "re-reads ran for $currentTime ms")
    }
}
