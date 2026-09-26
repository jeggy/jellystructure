package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * R291 (FR-R291-2) — the picker warms only the row whose OK switches outright: a single-version language
 * at level 1, or a version at level 2. A language with several versions opens level 2 on OK, and a
 * subtitle row has no rendition.
 */
class AudioWarmTargetTest {
    private fun e(lang: String, title: String, isDefault: Boolean = false) = PickerEntryInput(lang, title, forced = false, isDefault = isDefault, badges = emptyList())

    // English with a commentary (two versions), then Spanish and French (one each) — the measured title's shape.
    private val groups = buildLanguageGroups(listOf(
        e("eng", "English - TrueHD 7.1", isDefault = true),
        e("eng", "English - Commentary"),
        e("spa", "Spanish - AC3 5.1"),
        e("fre", "French - AC3 5.1"),
    ))

    @Test
    fun `a single-version language warms its one track`() {
        val spanish = groups.first { it.language == "spa" }
        assertEquals(2, audioWarmTarget(pickerTab = 0, pickerLevel = 0, group = spanish, pickerVersionIdx = 0))
    }

    @Test
    fun `a language with several versions warms nothing until a version is focused`() {
        val english = groups.first { it.language == "eng" }
        assertNull(audioWarmTarget(0, 0, english, 0))
        assertEquals(1, audioWarmTarget(0, 1, english, 1))
    }

    @Test
    fun `the subtitle tab and a missing row warm nothing`() {
        assertNull(audioWarmTarget(1, 0, groups.first { it.language == "fre" }, 0))
        assertNull(audioWarmTarget(0, 0, null, 0))
    }
}
