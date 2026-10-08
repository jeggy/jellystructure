package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** R380 (FR-R380-7) — the TV film player's picker groups as a cast remote lists them, and what each position picks. */
class CastVideoListsTest {
    private fun v(flat: Int, default: Boolean = false, forced: Boolean = false) =
        PickerVersion(flatIndex = flat, kind = VariantKind.PLAIN, region = null, badges = emptyList(), forced = forced, isDefault = default,
            hadTitleText = false, ordinal = 0, clusterSize = 1)
    private fun g(lang: String?, vararg versions: PickerVersion, off: Boolean = false) =
        PickerLanguage(language = lang, isOff = off, versions = versions.toList(), isUnnamed = false)

    private val audioGroups = listOf(g("eng", v(0, default = true), v(2)), g("dan", v(1)))
    private val subGroups = listOf(g(null, v(-1), off = true), g("dan", v(0)), g("eng", v(1), v(2, forced = true)))
    private val audio = listOf(PlayerAudioTrack(0, "English 5.1", "eng"), PlayerAudioTrack(1, "Dansk", "dan"), PlayerAudioTrack(2, "Commentary", "eng"))
    private val subs = listOf(PlayerSubtitleTrack(0, "Dansk", "dan"), PlayerSubtitleTrack(1, "English", "eng"), PlayerSubtitleTrack(2, "English (signs)", "eng", forced = true))

    @Test fun `every version of every language in the picker's order, with the web receiver's track ids`() {
        val l = castVideoLists(audioGroups, subGroups, audio, subs, selectedAudioFlat = 2, selectedSubFlat = 1)
        assertEquals(listOf("English 5.1", "Commentary", "Dansk"), l.audio.map { it.label })
        assertEquals(listOf(200L, 201L, 202L), l.audio.map { it.trackId })
        assertEquals(listOf("Dansk", "English", "English (signs)"), l.subtitles.map { it.label })
        assertEquals(listOf(100L, 101L, 102L), l.subtitles.map { it.trackId })
        assertEquals(true, l.subtitles[2].forced)
        assertEquals(1, l.selectedAudio, "Commentary (flat 2) is the second row")
        assertEquals(1, l.selectedSub, "English (flat 1) is the second row; Off is not a row")
    }

    @Test fun `subtitles off is -1, and the off entry is kept for subtitle -1`() {
        val l = castVideoLists(audioGroups, subGroups, audio, subs, selectedAudioFlat = 0, selectedSubFlat = -1)
        assertEquals(-1, l.selectedSub)
        assertEquals(true, assertNotNull(l.subtitleOff).group.isOff)
    }

    @Test fun `a position picks the group and version behind it`() {
        val l = castVideoLists(audioGroups, subGroups, audio, subs, selectedAudioFlat = 0, selectedSubFlat = -1)
        assertEquals(1, l.audioEntries[2].version.flatIndex)
        assertEquals("dan", l.audioEntries[2].group.language)
        assertEquals(2, l.subtitleEntries[2].version.flatIndex)
    }
}
