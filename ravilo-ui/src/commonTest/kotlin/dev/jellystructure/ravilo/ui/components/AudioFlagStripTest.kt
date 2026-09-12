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
    fun `Tamil, Telugu and Catalan are deliberately still unmapped`() {
        // No ISO-3166 country flag fits them (India already maps to Hindi's flag; Catalonia isn't a
        // country) — FR-R239-3's honest remaining gap, not an oversight.
        val counts = countFlagStrip(listOf("tam", "tel", "cat"))

        assertTrue(counts.shownFlags.isEmpty())
        assertEquals(3, counts.extra)
    }
}
