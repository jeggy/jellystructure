package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.i18n.t
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-15) — every string the TV's sign-in screen shows resolves in en, da and fo, and none names Jellyfin, LDAP,
 * Quick Connect, the plugin, single sign-on or a status code.
 */
class TvSignInStringsTest {
    private val keys = listOf(
        "tvsignin.title", "tvsignin.how", "tvsignin.how_noqr", "tvsignin.code_label", "tvsignin.this_tv",
        "tvsignin.approving", "tvsignin.password_instead", "tvsignin.back_to_profiles",
    )

    // FR-15's word list, lower-cased; "single sign-on" and "sso" because the TV never mentions it either.
    private val forbidden = listOf("jellyfin", "ldap", "quick connect", "quickconnect", "plugin", "single sign-on", "sso", "oidc", "status code", "http")

    @Test
    fun `every sign-in string resolves in every language`() {
        for (lang in SUPPORTED_LANGUAGES) for (k in keys) {
            val v = t(k, lang.code, mapOf("name" to "n"))
            assertTrue(v.isNotBlank() && v != k, "$k in ${lang.code} resolved to '$v'")
        }
    }

    @Test
    fun `no sign-in string names a component or a status code in any language`() {
        for (lang in SUPPORTED_LANGUAGES) for (k in keys) {
            val v = t(k, lang.code, mapOf("name" to "n")).lowercase()
            for (w in forbidden) assertFalse(v.contains(w), "$k in ${lang.code} contains '$w'")
            assertFalse(Regex("\\b[1-5][0-9]{2}\\b").containsMatchIn(v), "$k in ${lang.code} carries a status code")
        }
    }

    @Test
    fun `the instruction names Sign in a TV as the phone's entry will`() {
        val entry = mapOf("en" to "Sign in a TV", "da" to "Log et TV ind", "fo" to "Rita eitt sjónvarp inn")
        for (lang in SUPPORTED_LANGUAGES) {
            assertTrue(t("tvsignin.how", lang.code).contains(entry.getValue(lang.code)), "how in ${lang.code}")
            assertTrue(t("tvsignin.how_noqr", lang.code).contains(entry.getValue(lang.code)), "how_noqr in ${lang.code}")
        }
    }

    @Test
    fun `the instruction without a QR does not mention a camera and the one with it does`() {
        assertTrue(t("tvsignin.how", "en").contains("camera"))
        assertFalse(t("tvsignin.how_noqr", "en").contains("camera"))
    }

    @Test
    fun `approving carries the approver's name`() {
        for (lang in SUPPORTED_LANGUAGES) assertTrue(t("tvsignin.approving", lang.code, mapOf("name" to "Anna")).contains("Anna"), lang.code)
        assertEquals("Approving on Anna’s phone…", t("tvsignin.approving", "en", mapOf("name" to "Anna")))
    }
}
