package dev.jellystructure.ravilo.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.ravilo.ui.i18n.WithLocale
import dev.jellystructure.shared.tv.AboutFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** DEB-3711 (FR-2, FR-3, FR-6, Acceptance 4) — *Size* is the About section's last row, the admin chip's text, and never drawn for an unknown size. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class AboutSizeRowTest {
    private fun rows(a: AboutFacts, series: Boolean = false, lang: String = "en"): List<Pair<String, String>> {
        var out = emptyList<Pair<String, String>>()
        runComposeUiTest { setContent { WithLocale(lang) { out = aboutRows(a, series) } } }
        return out
    }

    private val film = AboutFacts(runtimeMin = 98, released = "2001-05-04", director = "A. Director", added = 1_700_000_000)

    @Test fun `Size is the last row after In your library since`() {
        val r = rows(film.copy(sizeBytes = 23_400_000_000))
        assertEquals("Size" to "23.4 GB", r.last())
        assertEquals("In your library since", r[r.size - 2].first)
    }

    @Test fun `a series shows its whole total as one Size row`() {
        val r = rows(film.copy(seasons = 3, episodes = 30, sizeBytes = 812_300_000_000), series = true)
        assertEquals(listOf("Size" to "812 GB"), r.filter { it.first == "Size" })
    }

    @Test fun `null and zero draw no Size row and leave the others unchanged`() {
        val base = rows(film)
        assertFalse(base.any { it.first == "Size" })
        assertEquals(base, rows(film.copy(sizeBytes = 0)))
        assertEquals(base, rows(film.copy(sizeBytes = null)))
    }

    @Test fun `the label is Storrelse in Danish and Stodd in Faroese, the value unchanged`() {
        assertEquals("Størrelse" to "23.4 GB", rows(film.copy(sizeBytes = 23_400_000_000), lang = "da").last())
        assertEquals("Stødd" to "23.4 GB", rows(film.copy(sizeBytes = 23_400_000_000), lang = "fo").last())
    }
}
