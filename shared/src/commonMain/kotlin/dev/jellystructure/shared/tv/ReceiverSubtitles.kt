package dev.jellystructure.shared.tv

/*
 * R285 — the platform-neutral half of "audio and subtitles work on every receiver". Lives in :shared
 * rather than :ravilo-receiver-core because it is pure and :shared is where a unit test can reach it
 * (the receiver modules are browser-only JS with no test runner).
 */

/** One WebVTT cue, times in milliseconds, [text] already stripped to what should be drawn. */
data class VttCue(val startMs: Long, val endMs: Long, val text: String)

private val VTT_TIMING = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})""")
// <i>, <b>, <c.class>, <v Speaker>, <00:01.000> karaoke stamps — and ASS/SSA override blocks ({\an8}),
// which Jellyfin's VTT conversion leaves in (the same ones ravilo-web's R68 cleaner strips).
private val VTT_MARKUP = Regex("""<[^>]*>|\{\\[^}]*\}""")

/**
 * R285 (FR-R285-3) — a forgiving WebVTT parser: the receiver must draw *something* for any file
 * Jellyfin's extractor produces. Hours are optional, `,` is accepted for `.` (SRT habit), cue
 * identifiers and cue settings are ignored, NOTE/STYLE/REGION blocks and anything without a timing
 * line are skipped, a cue whose text is empty after stripping is dropped. Result is sorted by start.
 */
fun parseVtt(source: String): List<VttCue> {
    val cues = mutableListOf<VttCue>()
    val blocks = source.replace("\r\n", "\n").replace('\r', '\n').trimStart('﻿').split(Regex("\n{2,}"))
    for (block in blocks) {
        val lines = block.split('\n')
        val at = lines.indexOfFirst { "-->" in it }
        if (at < 0) continue
        val m = VTT_TIMING.find(lines[at]) ?: continue
        fun ms(h: String, mm: String, s: String, f: String) =
            ((h.toLongOrNull() ?: 0L) * 3600 + mm.toLong() * 60 + s.toLong()) * 1000 + f.toLong()
        val g = m.groupValues
        val start = ms(g[1], g[2], g[3], g[4]); val end = ms(g[5], g[6], g[7], g[8])
        if (end <= start) continue
        val text = lines.drop(at + 1).joinToString("\n") { it.replace(VTT_MARKUP, "").trim() }
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
            .lines().filter { it.isNotBlank() }.joinToString("\n")
        if (text.isNotEmpty()) cues += VttCue(start, end, text)
    }
    return cues.sortedBy { it.startMs }
}

/** The text on screen at [positionMs]: every cue covering it, in start order, one per line; null = none. */
fun activeCueText(cues: List<VttCue>, positionMs: Long): String? =
    cues.filter { positionMs >= it.startMs && positionMs < it.endMs }.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }

/**
 * R285 (FR-R285-1) — the subtitle list a receiver offers, in the order it is addressed by position:
 * every text track the receiver can draw, **then** every burn-in (PGS) candidate. Appended, never
 * interleaved, so a text track keeps the index it had before PGS was listed at all — a phone remote
 * that is mid-session, or simply older, keeps pointing at the right thing.
 */
fun receiverSubtitles(ticket: StreamTicket?): List<SubTrack> {
    val subs = ticket?.subtitles ?: return emptyList()
    return subs.filter { it.url != null && it.deliveryMethod != "encode" } + subs.filter { it.deliveryMethod == "encode" }
}

/** What a receiver must do for "subtitle [index]" ([index] < 0 = off). */
sealed interface ReceiverSubPick {
    /** Show this VTT (or nothing, when [url] is null) — after [unburnFirst] restreams the burn-in out. */
    data class Text(val url: String?, val unburnFirst: Boolean) : ReceiverSubPick
    /** Restream with this Jellyfin stream index burned in. */
    data class Burn(val streamIndex: Int) : ReceiverSubPick
    /** Already what is on screen. */
    data object Nothing : ReceiverSubPick
}

/** R285 (FR-R285-2) — R282's pick rule, for a receiver: a drawn subtitle and a burn-in never coexist. */
fun receiverSubPick(ticket: StreamTicket?, index: Int): ReceiverSubPick {
    val burned = ticket?.burnedSubtitleIndex
    val sub = receiverSubtitles(ticket).getOrNull(index)
    return when {
        sub?.deliveryMethod == "encode" && sub.index == burned -> ReceiverSubPick.Nothing
        sub?.deliveryMethod == "encode" -> ReceiverSubPick.Burn(sub.index)
        else -> ReceiverSubPick.Text(sub?.url, unburnFirst = burned != null)
    }
}

/** R285 (FR-R285-2) — the selected position a receiver REPORTS: the burned track while one is burned
 *  in (it is what the viewer is reading), else the drawn text track [selectedText] (-1 = off). */
fun receiverSelectedSub(ticket: StreamTicket?, selectedText: Int): Int {
    val burned = ticket?.burnedSubtitleIndex ?: return selectedText
    return receiverSubtitles(ticket).indexOfFirst { it.deliveryMethod == "encode" && it.index == burned }.takeIf { it >= 0 } ?: selectedText
}

/** R285 (FR-R285-2) — position in [StreamTicket.audio] of the track a single-audio stream carries; 0 when unknown. */
fun receiverSelectedAudio(ticket: StreamTicket?): Int =
    ticket?.audioStreamIndex?.let { c -> ticket.audio.indexOfFirst { it.index == c }.takeIf { it >= 0 } } ?: 0
