package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.shared.tv.VideoVersion
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 314b/c — a copy jellystructure added is the same audio as its source, so the picker shows one row for the two
 * and plays the copy only where the device can't decode the source; and the Picture tab lists a film's versions.
 */
class AddedCopyFoldingTest {
    // The player's list: Danish TrueHD, English AC-3, then the Danish Stereo copy merged from its sidecar.
    private val tracks = listOf(
        PlayerAudioTrack(0, "Dansk", "dan", 8, isDefault = true, supported = false),
        PlayerAudioTrack(1, "English", "eng", 6),
        PlayerAudioTrack(2, "Stereo", "dan", 2, copyOf = 0),
    )

    /** One row per track, each one version pointing at its place in the list it was given. */
    private val oneRowEach: (List<PlayerAudioTrack>) -> List<PickerLanguage> = { ts ->
        ts.mapIndexed { i, t ->
            PickerLanguage(language = t.language, isOff = false, isUnnamed = false, sampleTitle = t.label,
                versions = listOf(PickerVersion(flatIndex = i, kind = VariantKind.PLAIN, region = null, badges = emptyList(), forced = false,
                    isDefault = t.isDefault, hadTitleText = true, ordinal = 0, clusterSize = 1)))
        }
    }

    @Test fun `a copy is folded onto its source - the rows still address the player's own list`() {
        val groups = foldedAudioGroups(tracks, oneRowEach)
        assertEquals(listOf("Dansk", "English"), groups.map { it.sampleTitle })
        assertEquals(listOf(0, 1), groups.map { it.versions.single().flatIndex })
    }

    @Test fun `a copy whose source isn't in the list stays its own row`() {
        val orphan = listOf(PlayerAudioTrack(0, "English", "eng"), PlayerAudioTrack(1, "Stereo", "dan", copyOf = 7))
        assertEquals(2, foldedAudioGroups(orphan, oneRowEach).size)
    }

    @Test fun `picking the source plays the copy only where the source can't be decoded`() {
        assertEquals(2, playableAudio(0, tracks), "TrueHD isn't supported here: the Stereo copy plays")
        assertEquals(1, playableAudio(1, tracks))
        assertEquals(0, playableAudio(0, tracks.map { if (it.index == 0) it.copy(supported = true) else it }), "the source plays where it can")
        assertEquals(0, playableAudio(0, tracks.map { if (it.index == 2) it.copy(supported = false) else it }), "nothing better: unchanged")
    }

    @Test fun `while the copy plays the picker shows its source's row`() {
        assertEquals(0, shownAudio(2, tracks))
        assertEquals(1, shownAudio(1, tracks))
        assertEquals(5, shownAudio(5, tracks), "out of range: unchanged")
    }

    @Test fun `the Picture tab lists the versions in their own words - only when there are two`() {
        val v = listOf(VideoVersion("orig", "Dolby Vision, full detail"), VideoVersion("dv81", "Dolby Vision", current = true))
        val g = pictureVersionGroups(v)
        assertEquals(listOf("Dolby Vision, full detail", "Dolby Vision"), g.map { it.sampleTitle })
        assertEquals(listOf(0, 1), g.map { it.versions.single().flatIndex })
        assertEquals(emptyList(), pictureVersionGroups(v.take(1)))
    }
}
