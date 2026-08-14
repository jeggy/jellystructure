package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R196 (FR-RV-TRK2-4) — regression guard for [resolveTrackChoice], the pure core extracted from
 * `PlayerScreen.resolveTrackSelection()` after the remembered audio/subtitle tier was found dead since
 * R195 (see `phase-R196-remembered-track-regression.md`). The actual R196 bug — a `LaunchedEffect(Unit)`
 * poll loop closing over the FIRST composition's frozen `audioGroups`/`subGroups` instead of live ones —
 * is a Compose-composition-lifecycle bug with no reproduction here (there's no Compose test harness in
 * this module); what these tests guard is the pure resolution CONTRACT this function must uphold once
 * it's handed correct, live data — and they document, by name, the exact failure shape the bug produced,
 * so a future change to this function can't silently reintroduce it.
 */
class PlayerScreenTrackResolutionTest {

    private fun version(flatIndex: Int, forced: Boolean = false, isDefault: Boolean = false, ordinal: Int = 0) =
        PickerVersion(
            flatIndex = flatIndex, kind = VariantKind.PLAIN, region = null, badges = emptyList(),
            forced = forced, isDefault = isDefault, hadTitleText = false, ordinal = ordinal, clusterSize = 1,
        )

    private fun group(language: String, vararg versions: PickerVersion) =
        PickerLanguage(language = language, isOff = false, versions = versions.toList(), isUnnamed = false)

    private fun audio(index: Int, language: String?, isDefault: Boolean = false) =
        PlayerAudioTrack(index, language ?: "Track ${index + 1}", language, isDefault = isDefault)

    private fun sub(index: Int, language: String?, forced: Boolean = false, isDefault: Boolean = false) =
        PlayerSubtitleTrack(index, language ?: "Track ${index + 1}", language, forced = forced, isDefault = isDefault)

    @Test
    fun seriesChoiceLanguageWinsOverGlobalChoiceAndDefault() {
        val audioTracks = listOf(audio(0, "en", isDefault = true), audio(1, "da"))
        val groups = listOf(group("en", version(0, isDefault = true)), group("da", version(1)))
        val result = resolveTrackChoice(
            seriesChoice = RememberedChoice(audioLanguage = "da"),
            globalChoice = RememberedChoice(audioLanguage = "en"),
            audioGroups = groups, subGroups = emptyList(),
            audioTracks = audioTracks, subtitleTracks = emptyList(),
        )
        assertEquals(1, result.audioIndex, "per-series choice must beat both the global choice and the source default")
    }

    @Test
    fun globalChoiceUsedWhenNoSeriesChoiceMatches() {
        val audioTracks = listOf(audio(0, "en", isDefault = true), audio(1, "da"))
        val groups = listOf(group("en", version(0, isDefault = true)), group("da", version(1)))
        val result = resolveTrackChoice(
            seriesChoice = RememberedChoice(audioLanguage = "fo"), // not present in this title
            globalChoice = RememberedChoice(audioLanguage = "da"),
            audioGroups = groups, subGroups = emptyList(),
            audioTracks = audioTracks, subtitleTracks = emptyList(),
        )
        assertEquals(1, result.audioIndex, "the global tier must be tried when the series tier's language isn't in this file")
    }

    @Test
    fun exactVariantSignaturePreferredOverLanguagesFirstVersion() {
        val sdh = version(1, ordinal = 1).copy(kind = VariantKind.SDH)
        val plain = version(0, ordinal = 0)
        val groups = listOf(group("en", plain, sdh))
        val subtitleTracks = listOf(sub(0, "en"), sub(1, "en"))
        val result = resolveTrackChoice(
            seriesChoice = RememberedChoice(subtitleLanguage = "en", subtitleVariant = sdh.signature()),
            globalChoice = null,
            audioGroups = emptyList(), subGroups = groups,
            audioTracks = emptyList(), subtitleTracks = subtitleTracks,
        )
        assertEquals(1, result.subIndex, "a remembered SDH variant must survive to the next file, not fall back to the plain first version")
    }

    @Test
    fun variantSignatureFallsBackToFirstVersionWhenAbsentFromThisFile() {
        val plain = version(0)
        val groups = listOf(group("en", plain))
        val subtitleTracks = listOf(sub(0, "en"))
        val result = resolveTrackChoice(
            seriesChoice = RememberedChoice(subtitleLanguage = "en", subtitleVariant = "sdh||0"), // no SDH here
            globalChoice = null,
            audioGroups = emptyList(), subGroups = groups,
            audioTracks = emptyList(), subtitleTracks = subtitleTracks,
        )
        assertEquals(0, result.subIndex, "a variant absent from this release must fall through to the language's first version, not off/default")
    }

    @Test
    fun subtitlesOffShortCircuitsRegardlessOfLanguageGroups() {
        val groups = listOf(group("en", version(0, isDefault = true)))
        val subtitleTracks = listOf(sub(0, "en", isDefault = true))
        val result = resolveTrackChoice(
            seriesChoice = RememberedChoice(subtitlesOff = true),
            globalChoice = RememberedChoice(subtitleLanguage = "en"),
            audioGroups = emptyList(), subGroups = groups,
            audioTracks = emptyList(), subtitleTracks = subtitleTracks,
        )
        assertEquals(-1, result.subIndex, "a remembered 'off' must stay off even though a default subtitle track exists")
    }

    @Test
    fun sourceDefaultUsedWhenNoRememberedChoiceExists() {
        val audioTracks = listOf(audio(0, "en"), audio(1, "da", isDefault = true))
        val subtitleTracks = listOf(sub(0, "en", forced = true), sub(1, "da"))
        val result = resolveTrackChoice(
            seriesChoice = null, globalChoice = null,
            audioGroups = emptyList(), subGroups = emptyList(),
            audioTracks = audioTracks, subtitleTracks = subtitleTracks,
        )
        assertEquals(1, result.audioIndex, "no remembered choice -> the source's own default audio track")
        assertEquals(0, result.subIndex, "no remembered choice and no default subtitle -> the forced track")
    }

    /**
     * The exact shape of the R196 regression: real tracks exist (so a viewer's file genuinely has a
     * matching language), but the groups handed in are empty — which is what PlayerScreen's poll loop
     * closure permanently saw once R195 rerouted the tiers through `remember(...)`-frozen groups instead
     * of live snapshot state. `resolveTrackChoice` cannot recover from this on its own — an empty group
     * list has nothing to match against — which is exactly WHY `PlayerScreen` must never hand it stale
     * groups (see the `rememberUpdatedState` fix at the `resolveTrackSelection()` call site). This test
     * exists to make that contract explicit: if this ever starts asserting the REMEMBERED language
     * instead of the default, something upstream is (correctly) no longer passing empty groups here —
     * fine — but if it ever starts failing because the "expected" default silently changed to something
     * else, that's this exact bug walking back in.
     */
    @Test
    fun emptyGroupsWithNonEmptyTracksFallsThroughToSourceDefault_theR195RegressionShape() {
        val audioTracks = listOf(audio(0, "en", isDefault = true), audio(1, "da"))
        val subtitleTracks = listOf(sub(0, "da"))
        val result = resolveTrackChoice(
            seriesChoice = RememberedChoice(audioLanguage = "da", subtitleLanguage = "da"),
            globalChoice = null,
            audioGroups = emptyList(), subGroups = emptyList(), // <- the bug: frozen-empty despite real tracks below
            audioTracks = audioTracks, subtitleTracks = subtitleTracks,
        )
        assertEquals(0, result.audioIndex, "empty groups can't honor 'da' -> falls to the source default, not the remembered language")
        assertEquals(-1, result.subIndex, "empty groups can't honor 'da' and no subtitle is marked default/forced -> off")
    }
}
