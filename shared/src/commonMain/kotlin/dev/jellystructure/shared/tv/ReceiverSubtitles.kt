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

/** R291 (FR-R291-4) — what a receiver must do for "audio [index]". */
sealed interface ReceiverAudioPick {
    /** Already the audio that plays: do nothing (and do not reload). */
    data object Nothing : ReceiverAudioPick
    /**
     * R291 (FR-R291-2) — the stream's master lists every track as a rendition ([StreamTicket.audioRenditions]):
     * make the rendition at [position] active in place. The picture keeps playing, and a burned-in subtitle
     * stays burned in (FR-R291-5) because nothing is reloaded.
     */
    data class Rendition(val position: Int) : ReceiverAudioPick
    /** R285 (FR-R285-4) — restream with Jellyfin's [streamIndex]: the only path on a stream with one audio track. */
    data class Restream(val streamIndex: Int) : ReceiverAudioPick
}

/**
 * R291 (FR-R291-4) — a receiver's audio pick: a rendition switch where the stream has renditions, R285's restream
 * where it has not. [selectedPosition] is the position playing now — [receiverSelectedAudio] at the start of a
 * stream, and the last rendition made active after that, since an in-place switch leaves
 * [StreamTicket.audioStreamIndex] (the muxed, carried track) as it was.
 *
 * The caller still has to find that rendition in its own player's track list, by [renditionPosition] of the name;
 * a rendition the player does not list falls back to [ReceiverAudioPick.Restream] there (FR-R291-4: never silently).
 */
fun receiverAudioPick(ticket: StreamTicket?, index: Int, selectedPosition: Int): ReceiverAudioPick {
    val t = ticket ?: return ReceiverAudioPick.Nothing
    val wanted = t.audio.getOrNull(index) ?: return ReceiverAudioPick.Nothing
    if (index == selectedPosition) return ReceiverAudioPick.Nothing
    return if (t.audioRenditions) ReceiverAudioPick.Rendition(index) else ReceiverAudioPick.Restream(wanted.index)
}

/**
 * R291 (FR-R291-2) — the position behind an audio rendition's name. The backend's `composeMaster` names every
 * rendition `a{position}` or `a{position} {label}` (unique, since a player merges renditions that share a NAME),
 * which is the key a player maps back to the ticket's audio list — the same reading the web player's
 * `selectRendition` makes of hls.js's and Safari's lists. Null for anything else, including a name that merely
 * starts with an `a` ("abc", "a1b", "audio").
 */
fun renditionPosition(name: String?): Int? {
    val n = name?.takeIf { it.startsWith("a") } ?: return null
    val digits = n.drop(1).takeWhile { it.isDigit() }
    if (digits.isEmpty()) return null
    val rest = n.drop(1 + digits.length)
    if (rest.isNotEmpty() && !rest.startsWith(" ")) return null
    return digits.toIntOrNull()
}
