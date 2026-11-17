package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-4932 (FR-23) — R181's track rule, now in `:shared`. `ravilo-ui`'s `PlayerScreenTrackResolutionTest` pins the same rule
 * through the player's track types; these pin it where the Samsung app reads it, over the ticket's own lists.
 */
class TrackChoiceTest {
    private fun a(index: Int, language: String?, label: String? = null, isDefault: Boolean = false) = TrackInput(index, language, label ?: language, isDefault = isDefault)
    private fun s(index: Int, language: String?, label: String? = null, forced: Boolean = false, isDefault: Boolean = false) =
        TrackInput(index, language, label ?: language, forced = forced, isDefault = isDefault)

    @Test
    fun theSeriesTierBeatsTheGlobalTierAndTheFilesDefault() {
        val audio = listOf(a(0, "en", isDefault = true), a(1, "da"), a(2, "fo"))
        assertEquals(1, resolveTrackChoice(RememberedChoice(audioLanguage = "da"), RememberedChoice(audioLanguage = "fo"), audio, emptyList()).audioIndex)
    }

    @Test
    fun theGlobalTierIsUsedWhenTheSeriesLanguageIsNotInThisFile() {
        val audio = listOf(a(0, "en", isDefault = true), a(1, "da"))
        assertEquals(1, resolveTrackChoice(RememberedChoice(audioLanguage = "sv"), RememberedChoice(audioLanguage = "da"), audio, emptyList()).audioIndex)
    }

    @Test
    fun noRememberedChoiceFallsToTheFilesDefaultThenTheFirstTrack() {
        assertEquals(1, resolveTrackChoice(null, null, listOf(a(0, "en"), a(1, "da", isDefault = true)), emptyList()).audioIndex)
        assertEquals(0, resolveTrackChoice(RememberedChoice(audioLanguage = "sv"), null, listOf(a(0, "en"), a(1, "da")), emptyList()).audioIndex)
    }

    @Test
    fun aRememberedVariantSignatureSurvivesToTheNextFile() {
        val subs = listOf(s(0, "en", "English"), s(1, "en", "English (SDH)"))
        val choice = RememberedChoice(subtitleLanguage = "en", subtitleVariant = variantSignatureAt(subs, 1))
        assertEquals(1, resolveTrackChoice(choice, null, emptyList(), subs).subIndex)
    }

    @Test
    fun anUnmatchedVariantPrefersPlainOverSdhRegardlessOfStreamOrder() {
        val subs = listOf(s(0, "eng", "English (SDH)"), s(1, "eng", "English"))
        assertEquals(1, resolveTrackChoice(RememberedChoice(subtitleLanguage = "eng", subtitleVariant = "plain||5"), null, emptyList(), subs).subIndex)
    }

    @Test
    fun aRememberedOffStaysOffOverAFlaggedDefaultSubtitle() {
        val subs = listOf(s(0, "en", isDefault = true))
        assertEquals(-1, resolveTrackChoice(RememberedChoice(subtitlesOff = true), RememberedChoice(subtitleLanguage = "en"), emptyList(), subs).subIndex)
        assertEquals(-1, rememberedSubtitleIndex(RememberedChoice(subtitlesOff = true), subs))
    }

    @Test
    fun aForcedDefaultSubtitleLosesToAPlainSiblingInTheSameLanguage() {
        val subs = listOf(s(0, "da", "Dansk", forced = true, isDefault = true), s(1, "da", "Dansk (CC)"))
        assertEquals(1, resolveTrackChoice(null, null, emptyList(), subs).subIndex)
    }

    @Test
    fun aCommentaryDefaultAudioTrackLosesToAPlainSibling() {
        assertEquals(1, resolveTrackChoice(null, null, listOf(a(0, "en", "Director's Commentary", isDefault = true), a(1, "en", "English")), emptyList()).audioIndex)
    }

    @Test
    fun aRememberedLanguageMatchesAcrossIsoGranularity() {
        assertEquals(0, resolveTrackChoice(RememberedChoice(subtitleLanguage = "dan"), null, emptyList(), listOf(s(0, "da", "Dansk"))).subIndex)
        assertEquals(1, resolveTrackChoice(RememberedChoice(audioLanguage = "eng"), null, listOf(a(0, "fr"), a(1, "en", isDefault = true)), emptyList()).audioIndex)
    }

    @Test
    fun theTierFunctionsAnswerNullWhenThereIsNothingToMatch() {
        assertNull(rememberedAudioIndex(null, listOf(a(0, "en"))))
        assertNull(rememberedAudioIndex(RememberedChoice(subtitleLanguage = "da"), listOf(a(0, "en"))))
        assertNull(rememberedAudioIndex(RememberedChoice(audioLanguage = "da"), listOf(a(0, "en"))))
        assertNull(rememberedSubtitleIndex(null, listOf(s(0, "en"))))
        assertNull(rememberedSubtitleIndex(RememberedChoice(audioLanguage = "da"), listOf(s(0, "da"))))
        assertNull(rememberedSubtitleIndex(RememberedChoice(subtitleLanguage = "da"), listOf(s(0, "en"))))
    }

    @Test
    fun aPicksSignatureIsTheOneTheServerReadsBack() {
        val audio = listOf(a(0, "en", "English"), a(1, "da", "Dansk"), a(2, "da", "Dansk (Commentary)"))
        val sig = variantSignatureAt(audio, 2)
        assertEquals(2, rememberedAudioIndex(RememberedChoice(audioLanguage = "da", audioVariant = sig), audio))
        assertEquals(2, resolveAudioChoice("da", sig, audio.map { VersionInput(it.language, it.label, it.forced, it.isDefault) }))
        assertNull(variantSignatureAt(audio, 9))
    }

    @Test
    fun aPickKeepsTheOtherAxisAndDropsAStaleVariant() {
        val before = RememberedChoice(audioLanguage = "da", audioVariant = "plain||0", subtitleLanguage = "en", subtitleVariant = "sdh||0")
        val audioOnly = rememberPick(before, newAudioLanguage = "en", newAudioVariant = null)
        assertEquals(RememberedChoice(audioLanguage = "en", audioVariant = null, subtitleLanguage = "en", subtitleVariant = "sdh||0"), audioOnly)
        val off = rememberPick(before, newSubtitlesOff = true)
        assertEquals(RememberedChoice(audioLanguage = "da", audioVariant = "plain||0", subtitlesOff = true), off)
        val backOn = rememberPick(off, newSubtitleLanguage = "da", newSubtitleVariant = "plain||0", newSubtitlesOff = false)
        assertEquals(RememberedChoice(audioLanguage = "da", audioVariant = "plain||0", subtitleLanguage = "da", subtitleVariant = "plain||0"), backOn)
    }
}
