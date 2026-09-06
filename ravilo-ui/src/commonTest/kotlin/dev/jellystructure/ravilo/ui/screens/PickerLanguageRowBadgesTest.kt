package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R238 (FR-R238-1/2) — the collapsed language row must caption itself with the version that is
 * actually playing.
 *
 * The bug: R195's predicate was loop-invariant (`it.flatIndex >= 0 && (single || selected)`), so it
 * always returned the FIRST version in stream order. On the real `Kulsort.S07E03` that meant a
 * correctly-selected `Dansk (CC)` track was captioned with the forced track's `Default` / `Signs only`
 * badges — describing the exact track R235 exists to avoid, on the one surface a viewer checks to
 * confirm their subtitles are right.
 */
class PickerLanguageRowBadgesTest {

    private fun version(flatIndex: Int, vararg badges: String) = PickerVersion(
        flatIndex = flatIndex,
        kind = VariantKind.PLAIN,
        region = null,
        badges = badges.toList(),
        forced = false,
        isDefault = false,
        hadTitleText = true,
        ordinal = 0,
        clusterSize = 1,
    )

    private fun group(vararg versions: PickerVersion) =
        PickerLanguage(language = "dan", isOff = false, versions = versions.toList(), isUnnamed = false)

    /** The exact shape of Kulsort S07E03: forced+default first in stream order, full track second. */
    private val heltSort = group(
        version(0, "Default", "Signs only"),
        version(1, "Sound described"),
    )

    @Test
    fun captionsTheSelectedVersionNotTheFirstOne() {
        assertEquals(listOf("Sound described"), languageRowBadges(heltSort, selectedFlat = 1))
    }

    @Test
    fun followsTheViewerWhenTheySwitchToSignsOnly() {
        assertEquals(listOf("Default", "Signs only"), languageRowBadges(heltSort, selectedFlat = 0))
    }

    @Test
    fun aLanguageThatIsNotSelectedShowsNoBadges() {
        // FR-R238-2 — nothing beats wrong. Neither version is playing here.
        assertEquals(emptyList(), languageRowBadges(heltSort, selectedFlat = 7))
    }

    @Test
    fun singleVersionLanguageIsUnchangedEvenWhenNotSelected() {
        val single = group(version(3, "Default"))
        assertEquals(listOf("Default"), languageRowBadges(single, selectedFlat = 3))
        assertEquals(listOf("Default"), languageRowBadges(single, selectedFlat = 99))
    }

    @Test
    fun offEntriesAndUnplayableVersionsNeverCaptionARow() {
        // flatIndex -1 is the synthetic "Off" version; it must never be treated as the single real one.
        val offOnly = group(version(-1, "should never show"))
        assertEquals(emptyList(), languageRowBadges(offOnly, selectedFlat = -1))
    }

    @Test
    fun threeVersionsStillResolveToTheSelectedOne() {
        val many = group(version(0, "Signs only"), version(1, "Sound described"), version(2, "Commentary"))
        assertEquals(listOf("Commentary"), languageRowBadges(many, selectedFlat = 2))
    }
}
