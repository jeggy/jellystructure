package dev.jellystructure.ravilo.ui.seams

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-4072 (DEB-3995 FR-16) — a download's saved subtitle is a file on the phone, and the app-fed fetch reads it with no network. */
class LocalSubtitleFileTest {
    private val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHej\n"

    @Test fun `a file URL is read from disk`() {
        val f = File.createTempFile("sub-2", ".vtt").apply { writeText(vtt); deleteOnExit() }
        val r = OkHttpSubtitleSource().newCall(f.toURI().toString()).execute()
        assertEquals(vtt, (r as SubtitleFetch.Body).text)
    }

    @Test fun `a file that is gone fails, it does not hang or reach out`() {
        val r = OkHttpSubtitleSource().newCall(File("/nonexistent/sub-9.vtt").toURI().toString()).execute()
        assertTrue(r is SubtitleFetch.Failed)
    }

    @Test fun `a file URL passes through the request resolver untouched`() {
        assertEquals(null, SubtitleRequest.absolute("file:///data/sub-2.vtt", "https://srv"))   // the caller falls back to the URL as given
    }
}
