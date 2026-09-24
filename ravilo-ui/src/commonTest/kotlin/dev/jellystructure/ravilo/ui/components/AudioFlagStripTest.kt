package dev.jellystructure.ravilo.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase R239 (FR-R239-1/2) — `+N` must count every language a group has, not every flag it can draw,
 * and a group with subtitles must always say so, even when nothing maps. Measured live: a title with
 * 6 mapped + 15 unmapped subtitle languages used to render five flags and "+1"; there are twenty-one.
 */
class AudioFlagStripTest {

    @Test
    fun `an empty language list has no languages at all`() {
        val counts = countFlagStrip(emptyList())

        assertFalse(counts.hasAnyLanguage)
        assertEquals(0, counts.extra)
        assertTrue(counts.shownFlags.isEmpty())
    }

    @Test
    fun `five or fewer mapped languages show every flag and no overflow`() {
        val counts = countFlagStrip(listOf("en", "da", "fr", "de", "es"))

        assertEquals(5, counts.shownFlags.size)
        assertEquals(0, counts.extra)
    }

    @Test
    fun `unmapped languages beyond the shown flags still count toward extra`() {
        // The exact shape from the live measurement: 6 mapped, 15 unmapped — five flags shown, +16 (not +1).
        val mapped6 = listOf("en", "da", "fr", "de", "es", "it")
        val unmapped15 = (1..15).map { "xx$it" } // 15 distinct unmapped codes
        val counts = countFlagStrip(mapped6 + unmapped15)

        assertEquals(5, counts.shownFlags.size)
        assertEquals(16, counts.extra) // 21 total languages - 5 shown
        assertTrue(counts.hasAnyLanguage)
    }

    @Test
    fun `a wholly unmapped language list still reports languages present`() {
        // The other lie the old code told: this used to be indistinguishable from "no subtitles at all".
        // "tam"/"tel" (Tamil/Telugu) are FR-R239-3's deliberately-still-unmapped codes — no ISO country
        // flag fits them — so they stay valid stand-ins for "unmapped" as the table grows.
        val counts = countFlagStrip(listOf("tam", "tel"))

        assertTrue(counts.hasAnyLanguage)
        assertTrue(counts.shownFlags.isEmpty())
        assertEquals(2, counts.extra)
    }

    @Test
    fun `aliases of the same language collapse to one shown flag`() {
        val counts = countFlagStrip(listOf("no", "nor", "nob", "nno"))

        assertEquals(1, counts.shownFlags.size)
        assertEquals(0, counts.extra)
    }

    @Test
    fun `a duplicate unmapped code is not double-counted`() {
        val counts = countFlagStrip(listOf("tam", "tam", "tam"))

        assertEquals(1, counts.extra)
    }

    @Test
    fun `mixed mapped and unmapped under five total shows all flags with no overflow`() {
        val counts = countFlagStrip(listOf("en", "tam"))

        assertEquals(1, counts.shownFlags.size) // only "en" maps
        assertEquals(1, counts.extra) // "tam" counted, just not shown as a flag
    }

    @Test
    fun `FR-R239-3 new codes each map to a flag, aliases included`() {
        val counts = countFlagStrip(listOf("sr", "srp", "bg", "bul", "id", "ind", "ms", "msa", "may", "sl", "slv", "et", "est", "lv", "lav", "lt", "lit", "tl", "fil"))

        assertEquals(4, counts.extra) // 9 distinct flags, 5 shown, 4 over FLAG_MAX — still all mapped, none unmapped
        assertEquals(5, counts.shownFlags.size) // capped at FLAG_MAX
    }

    @Test
    fun `Tamil and Telugu are deliberately still unmapped`() {
        // No ISO-3166 country flag fits them, and reusing India's (already Hindi's) would collapse
        // three distinct languages onto one shown flag via this file's own dedup — the exact
        // undercount FR-R239-1 exists to prevent. Honest remaining gap, not an oversight.
        val counts = countFlagStrip(listOf("tam", "tel"))

        assertTrue(counts.shownFlags.isEmpty())
        assertEquals(2, counts.extra)
    }

    @Test
    fun `Catalan maps to its own flag, both language-code forms`() {
        // R239 amendment (2026-09-13) — Catalonia's flag (Senyera) is a single, uncontested regional
        // flag, unlike Tamil/Telugu's situation above.
        val counts = countFlagStrip(listOf("ca", "cat"))

        assertEquals(1, counts.shownFlags.size)
        assertEquals(0, counts.extra)
    }

    @Test
    fun `Catalan mapped, Tamil and Telugu still not, all three counted`() {
        val counts = countFlagStrip(listOf("cat", "tam", "tel"))

        assertEquals(1, counts.shownFlags.size) // "cat" maps
        assertEquals(2, counts.extra) // "tam"/"tel" counted, just not shown as flags
    }

    // 2026-09-24 — a line too narrow for every flag folds the flags it drops into "+N", so the count
    // stays honest instead of being clipped off the edge.
    @Test
    fun aSmallerFlagBudgetFoldsTheDroppedFlagsIntoTheCount() {
        val langs = listOf("eng", "dan", "fin", "nor", "swe", "fre", "ger")
        val full = countFlagStrip(langs)
        assertEquals(5, full.shownFlags.size); assertEquals(2, full.extra)
        val three = countFlagStrip(langs, maxFlags = 3)
        assertEquals(3, three.shownFlags.size); assertEquals(4, three.extra)
        val none = countFlagStrip(langs, maxFlags = 0)
        assertEquals(0, none.shownFlags.size); assertEquals(7, none.extra)
        assertEquals(true, none.hasAnyLanguage)
    }
}
