package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.i18n.t
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-15) — every string *Sign in a TV* shows resolves in en, da and fo, and none names Jellyfin, LDAP, Quick Connect,
 * the plugin, single sign-on or a status code. Includes the strings it borrows (unreachable, Retry, Cancel, Sign in).
 */
class TvApproveStringsTest {
    private val keys = listOf(
        "tvcode.row", "tvcode.body", "tvcode.field", "tvcode.continue", "tvcode.confirm", "tvcode.confirm_body", "tvcode.not_you",
        "tvcode.done", "tvcode.done_body", "tvcode.expired", "tvcode.used", "tvcode.unavailable", "tvcode.get_new", "tvcode.done_cta",
        "login.error_unreachable", "action.retry", "action.cancel", "login.sign_in",
    )
    private val args = mapOf("tv" to "Living room TV", "name" to "Anna")
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

    @Test fun `the question names the TV and the viewer and the outcome names the TV`() {
        for (lang in SUPPORTED_LANGUAGES) {
            val q = t("tvcode.confirm", lang.code, args)
            assertTrue(q.contains("Living room TV") && q.contains("Anna"), "confirm in ${lang.code}: $q")
            assertTrue(t("tvcode.done", lang.code, args).contains("Living room TV"), "done in ${lang.code}")
        }
        assertEquals("Sign in on Living room TV as Anna?", t("tvcode.confirm", "en", args))
        assertEquals("Living room TV is signed in", t("tvcode.done", "en", args))
        assertEquals("That code has expired — get a new one on the TV", t("tvcode.expired", "en"))
    }

    @Test fun `the entry says what the TV's own instruction says`() {
        for (lang in SUPPORTED_LANGUAGES) assertTrue(t("tvsignin.how", lang.code).contains(t("tvcode.row", lang.code)), lang.code)
    }
}
