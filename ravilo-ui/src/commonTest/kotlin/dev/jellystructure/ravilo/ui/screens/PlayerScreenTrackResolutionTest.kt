package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R196 (FR-RV-TRK2-4) / R246 — regression guard for [resolveTrackChoice], the pure core of
 * `PlayerScreen.resolveTrackSelection()`.
 *
 * R246 (FR-R246-1/3/4): the function now takes the TRACK LISTS only and derives the language groups
 * itself, so these tests can no longer be satisfied by a resolver that depends on anything but the
 * lists — the exact way the R196 residue hid: groups one composition behind the lists they were
 * resolved against. Every fixture below is built from real production track shapes; variant kinds
 * come from the labels exactly as they do in the player (`buildLanguageGroups` → `variantKind`).
 */
class PlayerScreenTrackResolutionTest {

    private fun audio(index: Int, language: String?, label: String? = null, isDefault: Boolean = false) =
        PlayerAudioTrack(index, label ?: language ?: "Track ${index + 1}", language, isDefault = isDefault)

    private fun sub(index: Int, language: String?, label: String? = null, forced: Boolean = false, isDefault: Boolean = false) =
        PlayerSubtitleTrack(index, label ?: language ?: "Track ${index + 1}", language, forced = forced, isDefault = isDefault)

    /** The signature the player would have remembered for [flatIndex] in [subs] — learned from the same groups the resolver builds. */
    private fun subSignature(subs: List<PlayerSubtitleTrack>, flatIndex: Int): String =
        buildLanguageGroups(subs.map { PickerEntryInput(it.language, it.label, it.forced, it.isDefault, emptyList()) })
            .flatMap { it.versions }.first { it.flatIndex == flatIndex }.signature()

    private fun resolve(series: RememberedChoice?, global: RememberedChoice? = null, audio: List<PlayerAudioTrack> = emptyList(), subs: List<PlayerSubtitleTrack> = emptyList()) =
        resolveTrackChoice(series, global, audio, subs)

    // ── the contract (R196) ────────────────────────────────────────────────────

    @Test
    fun seriesChoiceLanguageWinsOverGlobalChoiceAndDefault() {
        val result = resolve(RememberedChoice(audioLanguage = "da"), RememberedChoice(audioLanguage = "en"), audio = listOf(audio(0, "en", isDefault = true), audio(1, "da")))
        assertEquals(1, result.audioIndex, "per-series choice must beat both the global choice and the source default")
    }

    @Test
    fun globalChoiceUsedWhenNoSeriesChoiceMatches() {
        val result = resolve(RememberedChoice(audioLanguage = "fo"), RememberedChoice(audioLanguage = "da"), audio = listOf(audio(0, "en", isDefault = true), audio(1, "da")))
        assertEquals(1, result.audioIndex, "the global tier must be tried when the series tier's language isn't in this file")
    }

    @Test
    fun exactVariantSignaturePreferredOverLanguagesFirstVersion() {
        val subs = listOf(sub(0, "en", "English"), sub(1, "en", "English (SDH)"))
        val result = resolve(RememberedChoice(subtitleLanguage = "en", subtitleVariant = subSignature(subs, 1)), subs = subs)
        assertEquals(1, result.subIndex, "a remembered SDH variant must survive to the next file, not fall back to the plain first version")
    }

    @Test
    fun variantSignatureFallsBackToFirstVersionWhenAbsentFromThisFile() {
        val subs = listOf(sub(0, "en", "English"))
        val result = resolve(RememberedChoice(subtitleLanguage = "en", subtitleVariant = "sdh||0"), subs = subs)
        assertEquals(0, result.subIndex, "a variant absent from this release must fall through to the language's first version, not off/default")
    }

    @Test
    fun subtitlesOffShortCircuitsRegardlessOfLanguageGroups() {
        val result = resolve(RememberedChoice(subtitlesOff = true), RememberedChoice(subtitleLanguage = "en"), subs = listOf(sub(0, "en", isDefault = true)))
        assertEquals(-1, result.subIndex, "a remembered 'off' must stay off even though a default subtitle track exists")
    }

    @Test
    fun sourceDefaultUsedWhenNoRememberedChoiceExists() {
        val result = resolve(null, audio = listOf(audio(0, "en"), audio(1, "da", isDefault = true)), subs = listOf(sub(0, "en", forced = true), sub(1, "da")))
        assertEquals(1, result.audioIndex, "no remembered choice -> the source's own default audio track")
        assertEquals(0, result.subIndex, "no remembered choice and no default subtitle -> the forced track")
    }

    @Test
    fun noTracksAtAllResolvesToTheFirstAudioAndOff() {
        val result = resolve(RememberedChoice(audioLanguage = "da", subtitleLanguage = "da"))
        assertEquals(0, result.audioIndex)
        assertEquals(-1, result.subIndex)
    }

    // ── R235 (Helt Sort S07E03) ────────────────────────────────────────────────

    @Test
    fun forcedDefaultTrackLosesToAPlainSiblingInTheSameLanguage_noRememberedChoice() {
        val subs = listOf(sub(0, "da", "Dansk", forced = true, isDefault = true), sub(1, "da", "Dansk (CC)"))
        val result = resolve(null, audio = listOf(audio(0, "da", isDefault = true)), subs = subs)
        assertEquals(1, result.subIndex, "a forced+default track must lose to a plain track in the same language, not win because the file also flags it default")
    }

    @Test
    fun forcedDefaultTrackLosesToAPlainSiblingInTheSameLanguage_unmatchedRememberedVariant() {
        val subs = listOf(sub(0, "da", "Dansk", forced = true, isDefault = true), sub(1, "da", "Dansk (CC)"))
        val result = resolve(RememberedChoice(subtitleLanguage = "da", subtitleVariant = "plain||0"), subs = subs)
        assertEquals(1, result.subIndex, "an unmatched remembered variant must fall through to the language's first NON-FORCED version, not stream order")
    }

    @Test
    fun explicitlyChosenForcedTrackIsStillHonoredAndRemembered() {
        val subs = listOf(sub(0, "da", "Dansk", forced = true, isDefault = true), sub(1, "da", "Dansk (CC)"))
        val result = resolve(RememberedChoice(subtitleLanguage = "da", subtitleVariant = subSignature(subs, 0)), subs = subs)
        assertEquals(0, result.subIndex, "an EXPLICIT remembered pick of the forced track must still win — FR-R235-1 only changes the unmatched fallback")
    }

    @Test
    fun aFileWithOnlyAForcedTrackInALanguageStillSelectsIt() {
        val result = resolve(null, subs = listOf(sub(0, "da", forced = true, isDefault = true)))
        assertEquals(0, result.subIndex, "when forced is genuinely all there is, it must still be selected — nothing becomes unreachable")
    }

    @Test
    fun commentaryAudioTrackLosesToAPlainSiblingInTheSameLanguage() {
        val result = resolve(null, audio = listOf(audio(0, "en", "Director's Commentary", isDefault = true), audio(1, "en", "English")))
        assertEquals(1, result.audioIndex, "FR-R235-4 — a commentary track flagged default must lose to a plain track in the same language")
    }

    // ── R241 (ISO-639 granularity) ─────────────────────────────────────────────

    @Test
    fun rememberedSubtitleLanguageMatchesADifferentIsoGranularity() {
        val result = resolve(RememberedChoice(subtitleLanguage = "dan"), subs = listOf(sub(0, "da", "Dansk")))
        assertEquals(0, result.subIndex, "\"dan\" and \"da\" are the same language and must match across episodes")
    }

    @Test
    fun rememberedAudioLanguageMatchesADifferentIsoGranularity() {
        val result = resolve(RememberedChoice(audioLanguage = "eng"), audio = listOf(audio(0, "fr"), audio(1, "en", isDefault = true)))
        assertEquals(1, result.audioIndex, "\"eng\" and \"en\" are the same language and must match across episodes")
    }

    @Test
    fun unmatchedRememberedSubtitleVariantPrefersPlainOverSdh_regardlessOfStreamOrder() {
        val subs = listOf(sub(0, "eng", "English (SDH)"), sub(1, "eng", "English"))
        val result = resolve(RememberedChoice(subtitleLanguage = "eng", subtitleVariant = "plain||5"), subs = subs)
        assertEquals(1, result.subIndex, "an unmatched remembered subtitle variant must prefer PLAIN over SDH, matching tierAudio's own rule")
    }

    // ── R246 (FR-R246-3/4) — the three observed transitions, from the production file shapes ────

    /** (a) S17E07 → S17E08: learned from embedded `dan` PLAIN; the next file's only Danish is an external `da` SDH sidecar and English is the file default. */
    @Test
    fun rememberedPlainDanishReachesAnSdhOnlyDanishNextFile_overAnEnglishDefault() {
        val first = listOf(sub(0, "dan", "Dansk"))
        val learned = RememberedChoice(subtitleLanguage = "dan", subtitleVariant = subSignature(first, 0))
        val next = listOf(sub(0, "en", "English", isDefault = true), sub(1, "da", "Danish (SDH)"))
        val result = resolve(learned, subs = next)
        assertEquals(1, result.subIndex, "Danish (the only Danish there is, SDH) must be selected — not the English default (FR-R241-2 pinned: an SDH-only group satisfies a remembered plain choice)")
    }

    /** (b) detail page → S08E04: learned from embedded `dan` PLAIN; the next file has an external `da` PLAIN sidecar and nothing flagged default. */
    @Test
    fun rememberedDanishReachesAPlainSidecarNextFile_withNoDefaultFlag() {
        val first = listOf(sub(0, "dan", "Dansk"))
        val learned = RememberedChoice(subtitleLanguage = "dan", subtitleVariant = subSignature(first, 0))
        val next = listOf(sub(0, "en", "English"), sub(1, "da", "Danish"))
        val result = resolve(learned, subs = next)
        assertEquals(1, result.subIndex, "Danish must be selected, not Off")
    }

    /** (c) S08E04 → S08E09: learned from an external `da` PLAIN sidecar; the next file is the same shape. */
    @Test
    fun rememberedSidecarDanishReachesTheNextSidecarFile() {
        val first = listOf(sub(0, "en", "English"), sub(1, "da", "Danish"))
        val learned = RememberedChoice(subtitleLanguage = "da", subtitleVariant = subSignature(first, 1))
        val next = listOf(sub(0, "en", "English"), sub(1, "da", "Danish"))
        val result = resolve(learned, subs = next)
        assertEquals(1, result.subIndex, "Danish must be selected, not Off")
    }

    /** FR-R246-4 — the audio tier proved the same way: a dubbed kids' title with both tracks, the remembered one not the file's default. */
    @Test
    fun rememberedAudioLanguageWinsOverTheNextFilesDefaultTrack() {
        val first = listOf(audio(0, "en", "English", isDefault = true), audio(1, "da", "Dansk"))
        val learned = RememberedChoice(audioLanguage = "da", audioVariant = buildLanguageGroups(first.map { PickerEntryInput(it.language, it.label, false, it.isDefault, emptyList()) }).flatMap { it.versions }.first { it.flatIndex == 1 }.signature())
        val next = listOf(audio(0, "en", "English", isDefault = true), audio(1, "da", "Dansk"))
        val result = resolve(learned, audio = next)
        assertEquals(1, result.audioIndex, "the remembered Danish dub must win on the next file, not the English default")
    }

    @Test
    fun aGrownTrackSetHasADifferentSignature() {
        val small = trackSetSignature(listOf(audio(0, "en")), emptyList())
        val grown = trackSetSignature(listOf(audio(0, "en")), listOf(sub(0, "da", "Danish")))
        assertEquals(false, small == grown, "a sideload arriving after prepare must be detectable")
        assertEquals(small, trackSetSignature(listOf(audio(0, "en")), emptyList()), "the same set signs the same")
    }
}
