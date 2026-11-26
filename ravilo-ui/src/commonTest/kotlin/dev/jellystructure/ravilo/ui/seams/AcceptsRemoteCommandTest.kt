package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R293 FR-R293-5 — commands off screen are dropped. */
class AcceptsRemoteCommandTest {
    @Test
    fun `commands are applied only on screen and signed in`() {
        assertTrue(acceptsRemoteCommand(onScreen = true, signedIn = true))
        assertFalse(acceptsRemoteCommand(onScreen = false, signedIn = true))
        assertFalse(acceptsRemoteCommand(onScreen = true, signedIn = false))
    }
}
