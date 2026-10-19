package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RaviloWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-1854 (FR-6, FR-7, FR-11) — which place an account's *Change password* is drawn in, from the config's two fields. */
class PasswordRowTest {
    private val page = "https://accounts.example.invalid/password"

    @Test fun `a local account keeps the form on every platform`() {
        assertEquals(PasswordRow.Form, passwordRowOf(managedElsewhere = false, url = null, tv = false))
        assertEquals(PasswordRow.Form, passwordRowOf(managedElsewhere = false, url = null, tv = true))
        assertEquals(PasswordRow.Form, passwordRowOf(managedElsewhere = false, url = page, tv = false))
    }

    @Test fun `managed elsewhere with a page set is the link`() {
        assertEquals(PasswordRow.Link(page), passwordRowOf(managedElsewhere = true, url = page, tv = false))
    }

    @Test fun `managed elsewhere with no page is the one sentence, blank counting as none`() {
        assertEquals(PasswordRow.Line, passwordRowOf(managedElsewhere = true, url = null, tv = false))
        assertEquals(PasswordRow.Line, passwordRowOf(managedElsewhere = true, url = " ", tv = false))
    }

    @Test fun `a TV draws nothing for an account managed elsewhere, page or no page`() {
        assertEquals(PasswordRow.Hidden, passwordRowOf(managedElsewhere = true, url = page, tv = true))
        assertEquals(PasswordRow.Hidden, passwordRowOf(managedElsewhere = true, url = null, tv = true))
    }

    @Test fun `only the form and the link are on the D-pad chain`() {
        assertTrue(PasswordRow.Form.focusable); assertTrue(PasswordRow.Link(page).focusable)
        assertFalse(PasswordRow.Line.focusable); assertFalse(PasswordRow.Hidden.focusable)
    }

    @Test fun `a config from a server without the keys reads as local`() {
        val old = RaviloWireJson.decodeFromString(RaviloConfig.serializer(), "{}")
        assertEquals(PasswordRow.Form, old.passwordRow(tv = false))
        assertEquals(PasswordRow.Form, old.passwordRow(tv = true))
    }

    @Test fun `the two wire keys decode into the link`() {
        val c = RaviloWireJson.decodeFromString(RaviloConfig.serializer(), """{"password_managed_elsewhere":true,"password_change_url":"$page"}""")
        assertEquals(PasswordRow.Link(page), c.passwordRow(tv = false))
    }
}
