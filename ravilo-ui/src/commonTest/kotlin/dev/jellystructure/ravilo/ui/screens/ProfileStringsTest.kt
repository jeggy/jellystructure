package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.i18n.t
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R304 (FR-R304-6) — no string a viewer can read on the Profile page names Jellyfin, in any language; and
 * every string the page uses resolves (a missing key would render as the key itself).
 */
class ProfileStringsTest {
    private val keys = listOf(
        "profile.mylist_empty", "profile.account", "profile.app_language", "profile.app_language_note",
        "profile.signout_title", "profile.signout_body", "profile.signout_confirm", "profile.signout_cancel",
        "profile.signed_in_to", "profile.lang_changed", "profile.admin", "profile.sign_out",
        "nav.my_list", "browse.see_all_short", "account.pw_change", "nav.settings",
    )

    @Test
    fun `every profile string resolves in every language`() {
        for (lang in SUPPORTED_LANGUAGES) for (k in keys) {
            val v = t(k, lang.code, mapOf("host" to "h", "name" to "n"))
            assertTrue(v.isNotBlank() && v != k, "$k in ${lang.code} resolved to '$v'")
        }
    }

    @Test
    fun `no profile string names Jellyfin in any language`() {
        for (lang in SUPPORTED_LANGUAGES) for (k in keys) {
            assertFalse(t(k, lang.code).contains("jellyfin", ignoreCase = true), "$k in ${lang.code} names the server software")
        }
    }

    @Test
    fun `the role reads Admin and the sign-out copy says name and password`() {
        assertTrue(t("profile.signout_body", "en").contains("name and password"))
        assertTrue(t("profile.admin", "en") == "Admin")
    }
}
