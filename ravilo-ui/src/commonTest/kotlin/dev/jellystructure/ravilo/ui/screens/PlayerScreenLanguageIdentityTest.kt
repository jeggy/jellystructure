package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R247 (FR-R247-4/6/7) — the picker-side half: `Dubbed` is identity not string equality, `da` and
 * `dan` group as one language, and a recognised code never reaches a row as a code.
 */
class PlayerScreenLanguageIdentityTest {

    @Test
    fun `an English audio track on an English title is not Dubbed`() {
        // It's Always Sunny: jellystructure says `en`, Jellyfin's stream says `eng` (FR-R247-6).
        val track = PlayerAudioTrack(index = 0, label = "English", language = "eng", channels = 6, isDefault = true)
        val badges = audioBadges(track, originalLanguage = "en", lang = "en")
        assertEquals(listOf("Default", "Surround 5.1"), badges)
        assertFalse(badges.any { it.equals("Dubbed", ignoreCase = true) })
    }

    @Test
    fun `a Danish dub of an English title is still Dubbed`() {
        val track = PlayerAudioTrack(index = 1, label = "Dansk", language = "dan", channels = 2)
        val badges = audioBadges(track, originalLanguage = "en", lang = "en")
        assertTrue(badges.any { it.equals("Dubbed", ignoreCase = true) }, badges.toString())
    }

    @Test
    fun `da and dan entries merge into one language group`() {
        val groups = buildLanguageGroups(listOf(
            PickerEntryInput("da", "Dansk", forced = false, isDefault = false, badges = emptyList()),
            PickerEntryInput("eng", "English", forced = false, isDefault = true, badges = emptyList()),
            PickerEntryInput("dan", "Dansk (CC)", forced = false, isDefault = false, badges = emptyList()),
        ))
        assertEquals(2, groups.size, groups.map { it.language }.toString())
        val danish = groups.first { it.language == "da" }
        assertEquals(listOf(0, 2), danish.versions.map { it.flatIndex })
    }

    @Test
    fun `a recognised code names its row and shows no code`() {
        val groups = buildLanguageGroups(listOf(
            PickerEntryInput("hbs-srp", "HBS-SRP", forced = false, isDefault = false, badges = emptyList()),
            PickerEntryInput("el", "EL", forced = false, isDefault = false, badges = emptyList()),
        ))
        assertEquals("Srpski" to null, pickerRowName(groups[0], "en"))
        assertEquals("Ελληνικά" to null, pickerRowName(groups[1], "en"))
    }

    @Test
    fun `an unrecognised code shows the track's label first and the code beside it`() {
        val groups = buildLanguageGroups(listOf(
            PickerEntryInput("qqq", "Director's notes", forced = false, isDefault = false, badges = emptyList()),
        ))
        assertEquals("Director's notes" to "qqq", pickerRowName(groups[0], "en"))
    }

    @Test
    fun `an unrecognised code with only the platform's code-label shows the code once`() {
        // ExoPlayer/wasm fall back to the code uppercased as the label; that is not a title.
        val groups = buildLanguageGroups(listOf(
            PickerEntryInput("qqq", "QQQ", forced = false, isDefault = false, badges = emptyList()),
        ))
        assertNull(groups[0].sampleTitle)
        assertEquals("qqq" to null, pickerRowName(groups[0], "en"))
    }
}
