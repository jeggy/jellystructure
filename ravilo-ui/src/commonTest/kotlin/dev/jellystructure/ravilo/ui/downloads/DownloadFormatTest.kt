package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadFormatTest {
    @Test fun `sizes are decimal as the round draws them`() {
        assertEquals("7.9 GB", formatBytes(7_900_000_000L))
        assertEquals("26.9 GB", formatBytes(26_900_000_000L))
        assertEquals("650 MB", formatBytes(650_000_000L))
        assertEquals("1.0 GB", formatBytes(1_000_000_000L))
        assertEquals("0 MB", formatBytes(-5L))
    }

    @Test fun `the rung names its resolution and the original names none`() {
        assertEquals("1080p", resolutionLabel(DownloadOption("high", 1080, 1)))
        assertEquals("1080p", resolutionLabel(DownloadOption("high", 1036, 1)))
        assertEquals(2160, optionHeight(DownloadOption("original", 2160, 1, exact = true)))
        assertEquals("720p", resolutionLabel(DownloadOption("medium", null, 1)))
        assertEquals("480p", resolutionLabel(DownloadOption("small", null, 1)))
        assertNull(resolutionLabel(DownloadOption("original", 2160, 1, exact = true)))
    }
}
