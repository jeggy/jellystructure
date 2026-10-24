package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-2233 (FR-11) — Ravilo Web reads `?signin=<attempt>&h=<hand-off>` on load and strips it from the address; the app's scheme and the desktop's
 * loopback say `attempt` instead. Pure string work, so it runs on every target.
 */
class SsoReturnQueryTest {
    @Test fun `the web return is read from signin and h`() {
        assertEquals(SsoReturn("a1", "hand-off"), ssoReturnFromQuery("?signin=a1&h=hand-off", "signin"))
        assertEquals(SsoReturn("a1", "hand-off"), ssoReturnFromQuery("?h=hand-off&signin=a1", "signin"))
    }

    @Test fun `the app and the loopback return is read from attempt and h`() {
        assertEquals(SsoReturn("a1", "hh"), ssoReturnFromQuery("attempt=a1&h=hh", "attempt"))
        assertNull(ssoReturnFromQuery("?signin=a1&h=hh", "attempt"))
    }

    @Test fun `a missing or empty part is no return`() {
        for (q in listOf("", "?", "?signin=a1", "?h=x", "?signin=&h=x", "?signin=a1&h=", "?c=K7P4QX")) assertNull(ssoReturnFromQuery(q, "signin"), q)
    }

    @Test fun `percent escapes in a value are decoded`() {
        assertEquals(SsoReturn("a 1", "h/1"), ssoReturnFromQuery("?signin=a%201&h=h%2F1", "signin"))
        assertEquals(SsoReturn("a1", "é"), ssoReturnFromQuery("?signin=a1&h=%C3%A9", "signin"))
        assertEquals(SsoReturn("a1", "100%"), ssoReturnFromQuery("?signin=a1&h=100%", "signin"))
    }

    @Test fun `stripping leaves nothing when only the return was there`() {
        assertEquals("", ssoQueryWithoutReturn("?signin=a1&h=hand-off"))
        assertEquals("", ssoQueryWithoutReturn(""))
        assertEquals("", ssoQueryWithoutReturn("?"))
    }

    @Test fun `stripping keeps every other parameter in its order`() {
        assertEquals("?lang=da&x=1", ssoQueryWithoutReturn("?lang=da&signin=a1&h=hand-off&x=1"))
        assertEquals("?lang=da", ssoQueryWithoutReturn("?lang=da"))
    }
}
