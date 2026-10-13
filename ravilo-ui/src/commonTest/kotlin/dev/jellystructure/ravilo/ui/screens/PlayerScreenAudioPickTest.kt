package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R382, amendment 2026-10-12 (FR-R382-7) — the picker layer's contract: a version addresses its own track in the
 * list the player reported, and the row's badges name the version that is playing.
 *
 * These pass before the 2026-10-13 fix as well as after it. DEB-740's off-by-one was one layer below, in the list
 * `audioTracks` builds (see `AudioTrackPairingTest`); this file is why that is known rather than assumed — it holds
 * the layer the first amendment suspected.
 */
class PlayerScreenAudioPickTest {

    private fun audio(index: Int, label: String, lang: String?, channels: Int? = 6, default: Boolean = false, copyOf: Int? = null, supported: Boolean = true) =
        PlayerAudioTrack(index, label, lang, channels, default, copyOf = copyOf, supported = supported)

    /** The picker's audio groups, exactly as `PlayerScreen` composes them (314b's fold over R195's grouping). */
    private fun groupsOf(tracks: List<PlayerAudioTrack>): List<PickerLanguage> =
        foldedAudioGroups(tracks) { list ->
            buildLanguageGroups(list.map { PickerEntryInput(it.language, it.label, forced = false, isDefault = it.isDefault, badges = emptyList()) })
        }

    // ── FR-R382-7.1 — the reported shape ────────────────────────────────────────────────────────────

    private val sixTracks = listOf(
        audio(0, "English", "eng", default = true),
        audio(1, "Dansk", "dan"),
        audio(2, "Suomi", "fin"),
        audio(3, "Norsk", "nor"),
        audio(4, "Svenska", "swe"),
        audio(5, "English", "eng", channels = 2),
    )

    @Test
    fun `six tracks fold to five rows and every version addresses its own track`() {
        val groups = groupsOf(sixTracks)
        assertEquals(5, groups.size, groups.map { it.language }.toString())
        for (g in groups) {
            for (v in g.versions) {
                val track = sixTracks[v.flatIndex]
                assertTrue(
                    dev.jellystructure.ravilo.ui.seams.sameLanguage(track.language, g.language),
                    "row ${g.language} version ${v.flatIndex} is ${track.language}",
                )
            }
        }
    }

    @Test
    fun `the Norsk row selects the Norwegian track and not the Swedish one`() {
        val groups = groupsOf(sixTracks)
        val norsk = groups.first { dev.jellystructure.ravilo.ui.seams.sameLanguage(it.language, "nor") }
        assertEquals(1, norsk.versions.size)
        val picked = playableAudio(norsk.versions[0].flatIndex, sixTracks)
        assertEquals(3, picked)
        assertEquals("nor", sixTracks[picked].language)
    }

    // ── FR-R382-7.2 — two same-language tracks in one row ───────────────────────────────────────────

    @Test
    fun `each version of a two-version row selects its own track and the badges follow what plays`() {
        val tracks = listOf(
            audio(0, "English - TrueHD - 7.1", "eng", channels = 8, default = true),
            audio(1, "English - Dolby Digital - 5.1", "eng", channels = 6),
        )
        val english = groupsOf(tracks).single()
        assertEquals(listOf(0, 1), english.versions.map { it.flatIndex })
        // Each version's pick is its own track, each way.
        assertEquals(0, playableAudio(english.versions[0].flatIndex, tracks))
        assertEquals(1, playableAudio(english.versions[1].flatIndex, tracks))
        // And the row captions itself with the version that is playing, not with the first one (R238).
        val badged = groupsOf(tracks).single().copy(
            versions = english.versions.mapIndexed { i, v -> v.copy(badges = listOf("v$i")) },
        )
        assertEquals(listOf("v0"), languageRowBadges(badged, selectedFlat = 0))
        assertEquals(listOf("v1"), languageRowBadges(badged, selectedFlat = 1))
    }

    // ── FR-R382-7.3 — a 314b copy that is not last ─────────────────────────────────────────────────

    @Test
    fun `a folded copy in the middle of the list shifts no other row's index`() {
        // Track 1 is an E-AC-3 copy jellystructure added of track 0, and it is NOT the last track.
        val tracks = listOf(
            audio(0, "English - TrueHD - 7.1", "eng", channels = 8, default = true, supported = false),
            audio(1, "English - Dolby Digital Plus - 5.1", "eng", channels = 6, copyOf = 0),
            audio(2, "Dansk", "dan"),
            audio(3, "Norsk", "nor"),
        )
        val groups = groupsOf(tracks)
        assertEquals(3, groups.size, groups.map { it.language }.toString())
        for (g in groups) for (v in g.versions) {
            assertTrue(
                dev.jellystructure.ravilo.ui.seams.sameLanguage(tracks[v.flatIndex].language, g.language),
                "row ${g.language} version ${v.flatIndex} is ${tracks[v.flatIndex].language}",
            )
        }
        assertEquals(listOf(2), groups.first { it.language == "dan" }.versions.map { it.flatIndex })
        assertEquals(listOf(3), groups.first { it.language == "nor" }.versions.map { it.flatIndex })
        // 314b: the English row is the source alone, and picking it plays the copy this device can decode.
        val english = groups.first { it.language == "eng" }
        assertEquals(listOf(0), english.versions.map { it.flatIndex })
        assertEquals(1, playableAudio(english.versions[0].flatIndex, tracks))
        assertEquals(0, shownAudio(1, tracks))   // …and the row that shows as playing is still the source's
    }

    // ── FR-R382-7.4 — the whole list round-trips ───────────────────────────────────────────────────

    @Test
    fun `every version round-trips through the display derivation back to its own row`() {
        val lists = listOf(
            sixTracks,
            listOf(audio(0, "English", "eng", default = true)),
            listOf(
                audio(0, "English - TrueHD - 7.1", "eng", channels = 8, default = true, supported = false),
                audio(1, "English - Dolby Digital Plus - 5.1", "eng", channels = 6, copyOf = 0),
                audio(2, "Dansk", "dan"),
            ),
        )
        for (tracks in lists) {
            for (g in groupsOf(tracks)) {
                for (v in g.versions) {
                    val played = playableAudio(v.flatIndex, tracks)
                    assertEquals(v.flatIndex, shownAudio(played, tracks), "${tracks.size} tracks: version ${v.flatIndex}")
                }
            }
        }
    }
}
