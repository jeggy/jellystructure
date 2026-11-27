package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** R350 (FR-R350-6) — the player's episode rail is headed by its season, never by the first episode's code. */
class PlayerRailSeasonLabelTest {
    private fun entry(kicker: String, n: Int? = null, name: String? = null, custom: String? = null) = PlayerEpisodeEntry(
        id = kicker, numberLabel = "1", title = "t", kicker = kicker, durationLabel = "", progressPct = 0f, watched = false,
        stillUrls = emptyList(), seasonNumber = n, seasonName = name, seasonCustomName = custom,
    )

    @Test fun `the season in the viewer's words — not S01E01 over episode 3`() {
        val season1 = listOf(entry("S01E01", 1, "Season 1"), entry("S01E02", 1, "Season 1"), entry("S01E03", 1, "Season 1"))
        assertEquals("Season 1", playerRailSeasonLabel(season1, "Season"))
        assertEquals("Sæson 1", playerRailSeasonLabel(season1, "Sæson"))
        assertEquals("Sesong 13", playerRailSeasonLabel(listOf(entry("S13E19", 13)), "Sesong"))
    }

    @Test fun `Specials keep their own name, and an entry without a number reads it from the kicker`() {
        assertEquals("Specials", playerRailSeasonLabel(listOf(entry("S00E01", 0, "Specials")), "Season"))
        assertEquals("Season 2", playerRailSeasonLabel(listOf(entry("S02E05–E07")), "Season"))
        assertEquals("", playerRailSeasonLabel(emptyList(), "Season"))
        assertEquals("", playerRailSeasonLabel(null, "Season"))
    }

    @Test fun `a custom name heads its season, a blank one reads as today`() {
        assertEquals("Harbour Nights", playerRailSeasonLabel(listOf(entry("S07E01", 7, "Season 7", "Harbour Nights")), "Sæson"))
        assertEquals("Sæson 1", playerRailSeasonLabel(listOf(entry("S01E01", 1, "Season 1", "  ")), "Sæson"))
        assertEquals("Sæson 1", playerRailSeasonLabel(listOf(entry("S01E01", 1, "Season 1")), "Sæson"))
        assertEquals("Specials", playerRailSeasonLabel(listOf(entry("S00E01", 0, "Specials")), "Sæson"))
    }
}
