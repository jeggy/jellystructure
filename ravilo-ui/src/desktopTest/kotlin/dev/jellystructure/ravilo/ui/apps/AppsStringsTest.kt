package dev.jellystructure.ravilo.ui.apps

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4912 (FR-9's rule, carried to Apps) — every string Apps draws is in all three languages, and none names Jellyfin (Ravilo constitution, invariant 12). */
class AppsStringsTest {
    private val keys = listOf(
        "nav.apps", "apps.subtitle", "apps.tv_settings", "apps.see_all", "apps.all_title", "apps.all_subtitle", "apps.pinned_subtitle",
        "apps.order_recent", "apps.pin", "apps.unpin", "apps.move_left", "apps.move_right", "apps.pinned_mark",
        "apps.nothing_else", "apps.back_to_signin", "apps.back_to_setup",
    )

    @Test fun everyAppsStringIsInAllThreeLanguagesAndNamesNoJellyfin() {
        for (lang in listOf("en", "da", "fo")) {
            val text = File("../i18n/$lang.json").readText()
            for (k in keys) {
                val line = text.lines().firstOrNull { it.trimStart().startsWith("\"$k\":") }
                assertTrue(line != null, "$lang.json lacks $k")
                assertFalse(line.contains("jellyfin", ignoreCase = true), "$lang $k names Jellyfin")
            }
        }
    }

    // Faroese has no bare "TV": it is a sjónvarp (check-i18n-spelling's standing rule).
    @Test fun faroeseNeverSaysBareTv() {
        val fo = File("../i18n/fo.json").readText().lines().filter { it.trimStart().startsWith("\"apps.") || it.trimStart().startsWith("\"nav.apps") }
        for (l in fo) assertFalse(Regex("""\bTV\b""").containsMatchIn(l.substringAfter(": ").replace("Google TV", "")), "fo: $l")
    }
}
