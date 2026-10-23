package dev.jellystructure.ravilo.ui.seams

/** Strip ASS/SSA override tags from VTT cue text (R68). */
internal fun cleanCueText(text: String): String =
    text.replace(Regex("""\{\\[^}]*\}"""), "")
        .replace("""\N""", "\n")
        .replace("""\n""", "\n")
        .replace("""\h""", " ")
        .trim()

/** DEB-2205 (FR-6) — one subtitle cue, in the item's own time. No Android types: the parser and the lookup are JVM-tested. */
internal data class TextCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * DEB-2205 (FR-6) — the app reads a subtitle file itself (it is no longer part of the media item's preparation), so
 * it parses it itself. Reads what jellystructure's `/api/tv/subtitles/{item}/{containerIndex}.vtt` and Jellyfin's
 * sidecar `Stream.vtt` serve, and tolerates SubRip (`,` before the milliseconds, numeric cue ids, no header).
 *
 * - A leading BOM and CRLF / CR line ends are accepted.
 * - Timestamps are `hh:mm:ss.mmm` or `mm:ss.mmm` (hours may have more than two digits; a fraction of 1–3 digits).
 * - `NOTE`, `STYLE`, `REGION` blocks and the `WEBVTT` header are skipped; a cue id line is ignored; cue settings after
 *   the end time (`align:start line:90%`) are ignored.
 * - Cue text keeps its line breaks. VTT markup tags (`<i>`, `<c.x>`, `<v Name>`) are dropped, the common entities are
 *   decoded, and ASS override tags are stripped with [cleanCueText] (R68).
 * - A malformed cue is skipped; it never fails the file.
 */
internal object WebVttParser {
    private val timing = Regex("""^\s*(\S+)\s+-->\s+(\S+)""")
    private val stamp = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})$""")
    private val markup = Regex("""</?[a-zA-Z][^>]*>""")

    fun parse(raw: String): List<TextCue> {
        val text = raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val cues = ArrayList<TextCue>()
        for (block in text.split(Regex("\n[ \t]*\n+"))) {
            val lines = block.trim('\n').split('\n')
            val first = lines.firstOrNull()?.trimStart() ?: continue
            if (first.startsWith("WEBVTT") || first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val at = lines.indexOfFirst { timing.containsMatchIn(it) }
            if (at < 0) continue
            val m = timing.find(lines[at]) ?: continue
            val start = timestampMs(m.groupValues[1]) ?: continue
            val end = timestampMs(m.groupValues[2]) ?: continue
            if (end <= start) continue
            val body = lines.drop(at + 1).joinToString("\n") { decode(it) }
            val clean = cleanCueText(body)
            if (clean.isNotEmpty()) cues += TextCue(start, end, clean)
        }
        return cues
    }

    private fun decode(line: String): String =
        markup.replace(line, "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")

    internal fun timestampMs(s: String): Long? {
        val m = stamp.matchEntire(s) ?: return null
        val h = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        if (min > 59 || sec > 59) return null
        val frac = m.groupValues[4].padEnd(3, '0').toLong()
        return ((h * 60 + min) * 60 + sec) * 1000 + frac
    }
}

/** DEB-2205 (FR-6) — the cues of one track, looked up by playback position. */
internal class CueTimeline(cues: List<TextCue>) {
    private val sorted = cues.sortedBy { it.startMs }
    private val longest = sorted.maxOfOrNull { it.endMs - it.startMs } ?: 0L

    val size: Int get() = sorted.size

    /** The cues showing at [positionMs] (start inclusive, end exclusive), in start order; overlapping cues all show. */
    fun activeAt(positionMs: Long): List<TextCue> {
        if (sorted.isEmpty()) return emptyList()
        // The last cue starting at or before the position, then back while a cue could still be running.
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].startMs <= positionMs) lo = mid + 1 else hi = mid
        }
        var i = lo - 1
        var out: ArrayList<TextCue>? = null
        while (i >= 0 && sorted[i].startMs > positionMs - longest) {
            val c = sorted[i]
            if (positionMs < c.endMs) (out ?: ArrayList<TextCue>().also { out = it }).add(c)
            i--
        }
        return out?.also { it.reverse() } ?: emptyList()
    }
}
