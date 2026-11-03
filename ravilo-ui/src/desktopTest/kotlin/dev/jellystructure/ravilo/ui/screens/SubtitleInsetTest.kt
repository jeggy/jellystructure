package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-3506 (R300 FR-R300-2, R251 FR-R251-4) — captions rise to the 28 dp floor plus the band while the chrome shows, on a phone and a TV alike. */
class SubtitleInsetTest {
    @Test
    fun `chrome up lifts the caption by the band on top of the floor`() {
        assertEquals(28.dp + 96.dp, subtitleInsetFor(chromeUp = true, transportBand = 96.dp))
    }

    @Test
    fun `chrome hidden drops the caption back to the floor whatever the last band was`() {
        assertEquals(28.dp, subtitleInsetFor(chromeUp = false, transportBand = 96.dp))
    }

    @Test
    fun `a band not yet measured leaves the floor`() {
        assertEquals(28.dp, subtitleInsetFor(chromeUp = true, transportBand = 0.dp))
    }
}
