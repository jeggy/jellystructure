package dev.jellystructure.ravilo.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-5836 (FR-3) — the Ravilo constitution's invariant 12: copy says what the viewer can do, never the name of the
 * part that failed. No string a viewer can see, in any language, names one of our parts.
 */
class NoInternalNamesTest {
    /** The words, written down rather than guessed. */
    private val internalNames = listOf("seerr", "jellyfin", "jellystructure", "config editor")

    /** Keys only the admin web reads (`ContainerConversionUi.kt`), never a Ravilo client. */
    private fun adminOnly(key: String) = key.startsWith("migrate.")

    @Test
    fun no_viewer_string_in_any_language_names_an_internal_part() {
        val hits = LOCALES.flatMap { (lang, table) ->
            table.filter { (key, text) -> !adminOnly(key) && internalNames.any { it in text.lowercase() } }
                .map { (key, text) -> "$lang $key: $text" }
        }
        assertEquals(emptyList(), hits, "viewer text names an internal part")
    }

    @Test
    fun the_allowlist_is_still_needed() {
        // If the admin-only keys stop naming a part, drop the exception instead of keeping a dead one.
        assertTrue(LOCALES.values.any { table -> table.any { (k, v) -> adminOnly(k) && internalNames.any { it in v.lowercase() } } })
    }
}
