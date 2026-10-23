package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2205 (FR-6) — the app reads and looks up subtitle cues itself: the parser and the lookup, on the JVM. */
class WebVttTest {
    @Test
    fun `a plain file parses with hours, without hours, and multi-line text`() {
        val cues = WebVttParser.parse(
            "WEBVTT\n\n" +
                "00:00:01.000 --> 00:00:03.500\nHello\nthere\n\n" +
                "00:05.250 --> 00:07.000\nShort form\n",
        )
        assertEquals(listOf(TextCue(1_000, 3_500, "Hello\nthere"), TextCue(5_250, 7_000, "Short form")), cues)
    }

    @Test
    fun `a BOM and CRLF line ends are accepted`() {
        val cues = WebVttParser.parse("﻿WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nLine one\r\nLine two\r\n\r\n00:00:03.000 --> 00:00:04.000\r\nNext\r\n")
        assertEquals(listOf(TextCue(1_000, 2_000, "Line one\nLine two"), TextCue(3_000, 4_000, "Next")), cues)
    }

    @Test
    fun `a comma or a dot before the milliseconds both parse, and a short fraction is padded`() {
        val cues = WebVttParser.parse("1\n00:00:01,500 --> 00:00:02,5\nSubRip style\n\n00:00:03.5 --> 00:00:04.25\nVTT style\n")
        assertEquals(listOf(TextCue(1_500, 2_500, "SubRip style"), TextCue(3_500, 4_250, "VTT style")), cues)
    }

    @Test
    fun `cue ids, cue settings, NOTE, STYLE and REGION blocks are ignored`() {
        val cues = WebVttParser.parse(
            "WEBVTT - a title\n\n" +
                "NOTE a comment\nover two lines\n\n" +
                "STYLE\n::cue { color: red }\n\n" +
                "REGION\nid:fred\nwidth:40%\n\n" +
                "intro-1\n00:00:01.000 --> 00:00:02.000 align:start line:90%\nWith an id and settings\n\n" +
                "NOTE\n00:00:09.000 --> 00:00:09.500\nthis is a note, not a cue\n",
        )
        assertEquals(listOf(TextCue(1_000, 2_000, "With an id and settings")), cues)
    }

    @Test
    fun `markup tags go, entities decode, and ASS override tags are stripped`() {
        val cues = WebVttParser.parse("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<i>Italic</i> &amp; <v Roger>spoken</v> {\\an8}top\\Nnext\n")
        assertEquals("Italic & spoken top\nnext", cues.single().text)
    }

    @Test
    fun `an empty, backwards or malformed cue is skipped and never fails the file`() {
        val cues = WebVttParser.parse(
            "WEBVTT\n\n" +
                "00:00:01.000 --> 00:00:02.000\n\n" +
                "00:00:05.000 --> 00:00:04.000\nbackwards\n\n" +
                "garbage --> more garbage\nnope\n\n" +
                "00:00:61.000 --> 00:00:62.000\nminute out of range\n\n" +
                "00:00:07.000 --> 00:00:08.000\nGood\n",
        )
        assertEquals(listOf(TextCue(7_000, 8_000, "Good")), cues)
    }

    @Test
    fun `an empty file and a header-only file have no cues`() {
        assertTrue(WebVttParser.parse("").isEmpty())
        assertTrue(WebVttParser.parse("WEBVTT\n").isEmpty())
    }

    @Test
    fun `hours beyond two digits parse`() {
        assertEquals(100L * 3_600_000 + 61_000, WebVttParser.timestampMs("100:01:01.000"))
        assertNull(WebVttParser.timestampMs("1:2"))
    }

    // ── lookup ──

    private val timeline = CueTimeline(
        listOf(
            TextCue(10_000, 12_000, "b"),
            TextCue(1_000, 3_000, "a"),
            TextCue(2_000, 20_000, "long"),      // runs through the cues that follow it
            TextCue(11_000, 13_000, "c"),
        ),
    )

    @Test
    fun `before the first cue and after the last nothing shows`() {
        assertTrue(timeline.activeAt(0).isEmpty())
        assertTrue(timeline.activeAt(999).isEmpty())
        assertTrue(timeline.activeAt(20_000).isEmpty())
    }

    @Test
    fun `a cue shows from its start, inclusive, to its end, exclusive`() {
        assertEquals(listOf("a"), timeline.activeAt(1_000).map { it.text })
        assertEquals(listOf("a", "long"), timeline.activeAt(2_999).map { it.text })
        assertEquals(listOf("long"), timeline.activeAt(3_000).map { it.text })
    }

    @Test
    fun `a cue that ends inside the next one shows with it until it ends`() {
        assertEquals(listOf("long", "b"), timeline.activeAt(10_000).map { it.text })
        assertEquals(listOf("long", "b", "c"), timeline.activeAt(11_500).map { it.text })
        assertEquals(listOf("long", "c"), timeline.activeAt(12_000).map { it.text })
        assertEquals(listOf("long"), timeline.activeAt(13_000).map { it.text })
    }

    @Test
    fun `an empty timeline shows nothing`() {
        assertTrue(CueTimeline(emptyList()).activeAt(5_000).isEmpty())
    }

    @Test
    fun `a seek backwards finds the earlier cue again`() {
        assertEquals(listOf("b", "long").sorted(), timeline.activeAt(10_500).map { it.text }.sorted())
        assertEquals(listOf("a", "long"), timeline.activeAt(2_500).map { it.text })
    }
}
