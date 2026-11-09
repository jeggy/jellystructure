package dev.jellystructure.ravilo.ui.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-4070 (FR-8, amended; acceptance 9) — the five states, and Play keeping its width through all of them in every language. */
class DownloadControlTest {
    @Test fun `the five states map from the phases`() {
        assertEquals(ControlState.DOWNLOAD, DownloadPhase.None.controlState())
        assertEquals(ControlState.QUEUED, DownloadPhase.Queued.controlState())
        assertEquals(ControlState.QUEUED, DownloadPhase.WaitingWifi.controlState())
        assertEquals(ControlState.QUEUED, DownloadPhase.WaitingServer.controlState())
        assertEquals(ControlState.PERCENT, DownloadPhase.Running(42).controlState())
        assertEquals(ControlState.PERCENT, DownloadPhase.Paused(42).controlState())
        assertEquals(ControlState.DOWNLOADED, DownloadPhase.Downloaded.controlState())
        assertEquals(ControlState.RETRY, DownloadPhase.Failed(500).controlState())
        assertEquals(42, DownloadPhase.Running(42).percent())
        assertEquals(100, DownloadPhase.Running(140).percent())
        assertEquals(0, DownloadPhase.Queued.percent())
    }

    @Test fun `why a download is not moving is a string key`() {
        assertEquals("download.waiting_wifi", DownloadPhase.WaitingWifi.statusKey())
        assertEquals("download.waiting_server", DownloadPhase.WaitingServer.statusKey())
        assertEquals("download.failed", DownloadPhase.Failed().statusKey())
        assertEquals(null, DownloadPhase.Running(3).statusKey())
    }

    // A stand-in for text measurement: width grows with the label's length, as it does for any real font.
    private fun measure(s: String) = s.length * 7f

    private val languages = mapOf(
        "en" to mapOf("download.action" to "Download", "download.queued" to "Queued", "download.downloaded" to "Downloaded", "download.retry" to "Retry"),
        "da" to mapOf("download.action" to "Hent", "download.queued" to "I kø", "download.downloaded" to "Hentet", "download.retry" to "Prøv igen"),
        "fo" to mapOf("download.action" to "Tak niður", "download.queued" to "Í bíðirøð", "download.downloaded" to "Tikið niður", "download.retry" to "Royn aftur"),
    )

    @Test fun `the control has one width per language, the widest of its labels`() {
        languages.forEach { (_, table) ->
            val labels = DownloadControlLayout.shortLabels { table.getValue(it) }
            assertEquals(5, labels.size)
            val w = DownloadControlLayout.controlWidthDp(labels, ::measure)
            assertEquals(labels.maxOf(::measure) + DownloadControlLayout.CHROME_DP, w)
            labels.forEach { assertTrue(measure(it) + DownloadControlLayout.CHROME_DP <= w, "$it fits") }
        }
    }

    @Test fun `Play keeps the same width through all five states in every language`() {
        val row = 372f
        languages.forEach { (lang, table) ->
            val control = DownloadControlLayout.controlWidthDp(DownloadControlLayout.shortLabels { table.getValue(it) }, ::measure)
            // The width is a function of the language alone: no state is an input, so none can move Play.
            val play = DownloadControlLayout.playWidthDp(row, control)
            ControlState.entries.forEach { _ ->
                assertEquals(play, DownloadControlLayout.playWidthDp(row, control), lang)
            }
            assertTrue(play > control, "$lang: Play stays the primary action, wider than the secondary one ($play vs $control)")
        }
    }
}
