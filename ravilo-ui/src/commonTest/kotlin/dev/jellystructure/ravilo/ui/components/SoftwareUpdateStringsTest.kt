package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.i18n.t
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R386 (FR-R386-10) — the strings the Software update block, the notice, About and the menu say. `t` renders an unknown key
 * as itself and falls back to English per key, so "not the key" proves a string exists and "not the English" proves the
 * translation is really there.
 */
class SoftwareUpdateStringsTest {
    private val languages = SUPPORTED_LANGUAGES.map { it.code }
    private val newKeys = listOf(
        "title", "version", "uptodate", "checked", "checked_on", "check_now", "checking", "downloading", "progress", "dl_sub", "ready", "ready_sub",
        "restart", "whats_new", "wait_sub", "held", "held_sub", "fail_dl", "fail_dl_sub", "fail_ver", "fail_ver_sub", "try_again", "offline",
        "offline_sub", "offline_sub_on", "offline_sub_never", "auto", "auto_on", "auto_off", "closing", "closing_sub", "notice", "notice_restart",
        "notice_wait", "hide", "about", "row_version", "row_server", "menu_check",
    ).map { "mac.update.$it" }

    /** What FR-R386-10 reuses instead of restating. */
    private val reused = listOf(
        "mac.update_available", "mac.download", "mac.install_place.title", "mac.install_place.body_translocated", "mac.install_place.body_volume",
        "mac.menu_quit", "mac.about_version",
    )

    @Test fun `every new key is in every language, and in da and fo it is not the English`() {
        for (key in newKeys + reused) {
            val english = t(key, "en")
            assertNotEquals(key, english, "'$key' is in no table — it would render as itself")
            assertTrue(english.isNotBlank(), "'$key' is blank in English")
            // Words that are the same in two languages (a name, a label) are the exception, named here and not guessed at.
            for (lang in languages - "en") if ("$key/$lang" !in sameAsEnglish) assertNotEquals(english, t(key, lang), "'$key' falls back to English in '$lang'")
        }
    }

    private val sameAsEnglish = setOf(
        "mac.update.version/da", "mac.update.version/fo", "mac.update.row_version/da", "mac.update.row_server/da", "mac.about_version/da",
    )

    @Test fun `the round's drawn strings are exactly what ships`() {
        assertEquals("Software update", t("mac.update.title", "en"))
        assertEquals("Softwareopdatering", t("mac.update.title", "da"))
        assertEquals("Ritbúnaðardagføring", t("mac.update.title", "fo"))
        assertEquals("Restart to update", t("mac.update.restart", "en"))
        assertEquals("Genstart for at opdatere", t("mac.update.restart", "da"))
        assertEquals("Byrja umaftur og dagfør", t("mac.update.restart", "fo"))
        assertEquals("Check for Updates…", t("mac.update.menu_check", "en"))
        assertEquals("Søg efter opdateringer…", t("mac.update.menu_check", "da"))
        assertEquals("Leita eftir dagføringum…", t("mac.update.menu_check", "fo"))
    }

    @Test fun `placeholders are filled and none is left behind`() {
        for (lang in languages) {
            assertTrue("1.53" in t("mac.update.ready", lang, mapOf("v" to "1.53")))
            assertTrue("1.53" in t("mac.update.closing", lang, mapOf("v" to "1.53")))
            val held = t("mac.update.held_sub", lang, mapOf("sv" to "1.52", "v" to "1.53"))
            assertTrue("1.52" in held && "1.53" in held, held)
            assertFalse("{" in held, held)
            val progress = t("mac.update.progress", lang, mapOf("a" to "41", "b" to "94"))
            assertTrue("41" in progress && "94" in progress && "MB" in progress && "{" !in progress, progress)
            assertTrue("09:14" in t("mac.update.checked", lang, mapOf("t" to "09:14")))
        }
    }

    @Test fun `the strings the round drew stand-in for are not taken`() {
        // FR-R386-10: the install-place strings serve, and the notes list was Notes B's.
        for (stand in listOf("su_move_title", "su_move_tmp", "su_move_dmg", "su_ship_install", "su_notes_title", "su_n1", "su_n2", "su_n3")) {
            assertEquals("mac.update.$stand", t("mac.update.$stand", "en"), "mac.update.$stand must not exist")
        }
    }

    @Test fun `no string names the mechanism`() {
        val forbidden = listOf("sparkle", "appcast", "gatekeeper", "transloc", "quarantin", "ed25519", "signature")
        for (lang in languages) for (key in newKeys) {
            val text = t(key, lang).lowercase()
            for (word in forbidden) assertFalse(word in text, "'$key' in '$lang' names '$word': $text")
        }
    }

    @Test fun `the shipped update line's strings are gone — no download link and no Open Anyway sentence`() {
        assertEquals("mac.open_anyway", t("mac.open_anyway", "en"))
    }
}
