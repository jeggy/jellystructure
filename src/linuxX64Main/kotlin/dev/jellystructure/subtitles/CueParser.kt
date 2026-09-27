package dev.jellystructure.subtitles

/** Phase 273 — one subtitle event: when it is on screen, in milliseconds. The text is never kept: whether a
 *  subtitle belongs to a video is decided from its timing alone (FR-273-1). */
data class Cue(val startMs: Long, val endMs: Long)

/**
 * Phase 273 (dev review item 5) — the backend had no subtitle parser. SRT, WebVTT (Jellyfin's extraction answers
 * in WebVTT) and ASS/SSA are read into cues; events whose text is empty once markup is stripped are dropped,
 * because a cue with nothing on screen says nothing about when someone speaks. Image formats (`.sub`/`.idx`,
 * PGS) are not text and never reach this.
 */
object CueParser {

    /** The format is recognised from the content, not the extension: sidecars are often misnamed. */
    fun parse(raw: String): List<Cue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val cues = when {
            text.trimStart().startsWith("WEBVTT") -> parseArrowFormat(text)
            ASS_EVENTS.containsMatchIn(text) -> parseAss(text)
            else -> parseArrowFormat(text)
        }
        return cues.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    // SRT and WebVTT share the shape that matters here: a line with `start --> end` (VTT may add cue settings
    // after it, SRT may add coordinates), then text lines up to a blank line.
    private fun parseArrowFormat(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val arrow = line.indexOf("-->")
            if (arrow < 0) { i++; continue }
            val start = parseClock(line.substring(0, arrow))
            val end = parseClock(line.substring(arrow + 3))
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].contains("-->")) {
                body.append(lines[i]).append(' ')
                i++
            }
            if (start != null && end != null && stripMarkup(body.toString()).isNotBlank()) out += Cue(start, end)
        }
        return out
    }

    private fun parseAss(text: String): List<Cue> {
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var fields = 10
        val out = ArrayList<Cue>()
        var inEvents = false
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.startsWith("[")) { inEvents = t.equals("[Events]", ignoreCase = true); continue }
            if (!inEvents) continue
            if (t.startsWith("Format:", ignoreCase = true)) {
                val names = t.substringAfter(':').split(',').map { it.trim().lowercase() }
                startIdx = names.indexOf("start").takeIf { it >= 0 } ?: startIdx
                endIdx = names.indexOf("end").takeIf { it >= 0 } ?: endIdx
                textIdx = names.indexOf("text").takeIf { it >= 0 } ?: textIdx
                fields = names.size
                continue
            }
            if (!t.startsWith("Dialogue:", ignoreCase = true)) continue
            val parts = t.substringAfter(':').split(',', limit = fields)
            if (parts.size <= maxOf(startIdx, endIdx, textIdx)) continue
            val start = parseClock(parts[startIdx]) ?: continue
            val end = parseClock(parts[endIdx]) ?: continue
            if (stripMarkup(parts[textIdx]).isBlank()) continue
            out += Cue(start, end)
        }
        return out
    }

    /** `hh:mm:ss,mmm`, `hh:mm:ss.mmm`, VTT's `mm:ss.mmm` and ASS's `h:mm:ss.cc`; the fraction is read as a
     *  decimal fraction of a second whatever its length. */
    internal fun parseClock(s: String): Long? {
        val m = CLOCK.find(s) ?: return null
        val h = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLongOrNull() ?: return null
        val sec = m.groupValues[3].toLongOrNull() ?: return null
        val frac = m.groupValues[4]
        val ms = if (frac.isEmpty()) 0L else (frac + "000").take(3).toLong()
        return ((h * 60 + min) * 60 + sec) * 1000 + ms
    }

    internal fun stripMarkup(s: String): String = s
        .replace(TAGS, "")
        .replace(ASS_OVERRIDES, "")
        .replace("\\N", " ").replace("\\n", " ").replace("\\h", " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .trim()

    private val CLOCK = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,3}))?""")
    private val TAGS = Regex("""<[^>]*>""")
    private val ASS_OVERRIDES = Regex("""\{[^}]*}""")
    private val ASS_EVENTS = Regex("""(?im)^\s*(\[Events]|Dialogue:)""")
}
