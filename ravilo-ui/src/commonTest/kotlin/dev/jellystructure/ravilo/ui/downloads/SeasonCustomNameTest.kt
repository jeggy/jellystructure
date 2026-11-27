package dev.jellystructure.ravilo.ui.downloads

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DEB-5321 (FR-8) — a downloaded season is headed by its custom name; a record without one reads as today. */
class SeasonCustomNameTest {
    private fun row(custom: String?) = DownloadRow(
        DownloadRecord("e1", "episode", "T", seriesId = "s", season = 2, episode = 1, quality = "original", seasonCustomName = custom),
        DownloadPhase.Downloaded,
    )

    @Test fun `a custom name heads the season, an unnamed one reads Season and the number`() {
        assertEquals("Harbour Nights", SeasonGroup(2, listOf(row("Harbour Nights"))).label("Sæson"))
        assertEquals("Sæson 2", SeasonGroup(2, listOf(row(null))).label("Sæson"))
        assertEquals("Sæson 2", SeasonGroup(2, listOf(row("  "))).label("Sæson"))
    }

    @Test fun `a stored record that lacks the field reads null`() {
        val old = """{"itemId":"e1","kind":"episode","title":"T","quality":"original","season":2}"""
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString<DownloadRecord>(old).seasonCustomName)
    }
}
