package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.i18n.t
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-15) — every string *Sign in with ‹name›* shows resolves in en, da and fo, and none names Jellyfin, LDAP, Quick Connect, the plugin,
 * single sign-on or a status code. Includes the strings the screen borrows (Cancel, the unreachable line).
 */
class SsoStringsTest {
    private val keys = listOf("sso.button", "sso.or", "sso.waiting", "sso.failed", "ssopage.close_tab", "action.cancel", "login.error_unreachable")
    private val args = mapOf("name" to "Harbour ID")
    private val forbidden = listOf("jellyfin", "ldap", "quick connect", "quickconnect", "plugin", "single sign-on", "sso", "oidc", "status code", "http")

    @Test fun `every string resolves in every language`() {
        for (lang in SUPPORTED_LANGUAGES) for (k in keys) {
            val v = t(k, lang.code, args)
            assertTrue(v.isNotBlank() && v != k, "$k in ${lang.code} resolved to '$v'")
            assertFalse(v.contains("{"), "$k in ${lang.code} left a placeholder: '$v'")
        }
    }

    @Test fun `no string names a component or a status code in any language`() {
        for (lang in SUPPORTED_LANGUAGES) for (k in keys) {
            val v = t(k, lang.code, args).lowercase()
            for (w in forbidden) assertFalse(v.contains(w), "$k in ${lang.code} contains '$w'")
            assertFalse(Regex("\\b[1-5][0-9]{2}\\b").containsMatchIn(v), "$k in ${lang.code} carries a status code")
        }
    }

    @Test fun `the button carries the name the operator chose verbatim`() {
        for (lang in SUPPORTED_LANGUAGES) assertTrue(t("sso.button", lang.code, mapOf("name" to "Fjord Login")).contains("Fjord Login"), lang.code)
        assertEquals("Sign in with Fjord Login", t("sso.button", "en", mapOf("name" to "Fjord Login")))
        assertEquals("Finish signing in in your browser", t("sso.waiting", "en"))
    }
}
