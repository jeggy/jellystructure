package dev.jellystructure.ravilo.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5851 (FR-9) — the "Ravilo updated · Reload" toast is held over a play and shown when it closes. */
class UpdateToastHoldTest {
    @Test fun `a waiting build shows the toast when no player is on screen`() = assertTrue(updateToastShows(available = true, held = false))
    @Test fun `a player on screen holds it`() = assertFalse(updateToastShows(available = true, held = true))
    @Test fun `nothing waiting shows nothing`() { assertFalse(updateToastShows(available = false, held = false)); assertFalse(updateToastShows(available = false, held = true)) }
}
