package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R328 (found live 2026-10-09, Mac) — closing the window while a song plays on a speaker never quits. */
class CloseWhileCastingTest {
    @Test fun `a cast keeps the app running when the window closes`() {
        assertTrue(DesktopBackground.keepsRunningOnClose(localPlaying = false, castLinked = true))
        assertTrue(DesktopBackground.keepsRunningOnClose(localPlaying = true, castLinked = false))
        assertFalse(DesktopBackground.keepsRunningOnClose(localPlaying = false, castLinked = false))
    }
}
