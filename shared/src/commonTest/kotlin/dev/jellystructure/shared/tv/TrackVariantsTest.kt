package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R291 (FR-R291-1) — the server resolves the remembered audio choice with the picker's own grouping and signature. */
class TrackVariantsTest {
    private fun t(lang: String?, title: String?, def: Boolean = false) = VersionInput(lang, title, forced = false, isDefault = def)

    @Test
    fun `kinds and regions come from the title alone`() {
        assertEquals(VariantKind.SDH, variantKind("English (SDH)", false))
        assertEquals(VariantKind.DESCRIBE, variantKind("Dansk - Synstolkning - AAC", false))
        assertEquals(VariantKind.COMMENTARY, variantKind("Director's commentary", false))
        assertEquals(VariantKind.FORCED, variantKind("Forced", true))
        assertEquals(VariantKind.PLAIN, variantKind("Dansk - Dolby Digital 5.1", false))
        assertEquals("br", regionOf("Português (Brazil)")?.code); assertNull(regionOf("Português"))
    }

    @Test
    fun `versions are numbered within their kind-and-region cluster and signed the way the picker signs them`() {
        val tracks = listOf(t("eng", "English"), t("eng", "English (SDH)"), t("dan", "Dansk"), t("eng", "English 2"))
        val eng = groupVersions(tracks).first { it.language == "eng" }
        assertEquals(listOf("plain||0", "sdh||0", "plain||1"), eng.versions.map { it.signature() })
        assertEquals(listOf(0, 1, 3), eng.versions.map { it.flatIndex })
        assertEquals(2, eng.versions[2].clusterSize)
    }

    @Test
    fun `a provenance-only duplicate collapses to its first member`() {
        val tracks = listOf(t("eng", "English BluRay"), t("eng", "English WEB-DL"))
        assertEquals(1, groupVersions(tracks).single().versions.size)
    }

    @Test
    fun `the remembered signature wins then plain then first and a code granularity change still matches`() {
        val tracks = listOf(t("eng", "English (SDH)"), t("eng", "English", def = true), t("da", "Dansk"))
        assertEquals(0, resolveAudioChoice("en", "sdh||0", tracks), "exact variant")
        assertEquals(1, resolveAudioChoice("eng", "plain||7", tracks), "unknown variant ⇒ the language's first plain")
        assertEquals(2, resolveAudioChoice("dan", null, tracks), "ISO-639-2 vs 639-1 is the same language")
        assertNull(resolveAudioChoice("fo", null, tracks), "a language not in this file falls through")
        assertNull(resolveAudioChoice(null, "plain||0", tracks))
    }
}
