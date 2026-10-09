package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R330 (found live 2026-10-09, Mac) — a start-up that makes no connection to the last speaker tries again. */
class StartupRejoinTest {
    @Test fun `no connection is tried again, up to three tries in all`() {
        assertTrue(startupRejoinRetries(attempt = 1, noConnection = true))
        assertTrue(startupRejoinRetries(attempt = 2, noConnection = true))
        assertFalse(startupRejoinRetries(attempt = 3, noConnection = true))
        assertEquals(3, STARTUP_REJOIN_TRIES)
    }

    @Test fun `a device that answered with nothing loaded is not tried again`() {
        assertFalse(startupRejoinRetries(attempt = 1, noConnection = false))
    }
}
