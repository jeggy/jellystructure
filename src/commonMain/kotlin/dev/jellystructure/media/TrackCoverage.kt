package dev.jellystructure.media

import dev.jellystructure.model.TrackKind
import kotlinx.serialization.Serializable

/**
 * Phase 255 (FR-255-2) — what counts as "a track that stops before the file does", decided in ONE place,
 * from measured packet ends only. A `DURATION` tag is a hint the caller may use to find where to look;
 * it never reaches this classifier as evidence (FR-255-3). Unit-tested against every row of the spec's
 * "What the library holds" table.
 */
object TrackCoverage {
    /** A stream is short when it ends at least this long — or [SHORT_FRACTION] of the reference — before
     *  the reference end. Every real hit fell short by ≥ 382 s; the thresholds keep a few seconds of silent
     *  credit tail from ever being called a problem. */
    const val MIN_SHORT_MS = 30_000L
    const val SHORT_FRACTION = 0.05
    /** Still-image codecs some releases mux as a *video* track (cover art). Phase 144's territory, never a
     *  finding here. */
    val IMAGE_CODECS = setOf("mjpeg", "png", "bmp", "gif", "webp", "tiff")

    fun thresholdMs(referenceMs: Long): Long = maxOf(MIN_SHORT_MS, (referenceMs * SHORT_FRACTION).toLong())

    /** Whether a video stream is a picture rather than a track. */
    fun isCoverArt(s: CoverageStream): Boolean =
        s.kind == TrackKind.VIDEO && (s.attachedPic || s.codec.lowercase() in IMAGE_CODECS || (s.packets != null && s.packets <= 1))

    /**
     * [headerMs] is the container's declared duration; each [CoverageStream] carries its measured end
     * (null when unmeasured — such a stream is silently skipped: no evidence, no finding). Returns zero or
     * more findings: one per short stream (kinds A–D) and at most one `E` for a wrong header.
     */
    fun classify(headerMs: Long?, streams: List<CoverageStream>): List<CoverageFinding> {
        val real = streams.filter { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.VIDEO) && !isCoverArt(it) }
        val measured = real.filter { it.measuredEndMs != null }
        if (measured.isEmpty()) return emptyList()
        // The reference end is the latest MEASURED end among the file's real audio/video streams — never the
        // header. (The spec says "among the video streams"; that reading can never find kind D, a video
        // that stops while the sound continues, so the latest of all real streams is the reference.)
        val referenceMs = measured.maxOf { it.measuredEndMs!! }
        val threshold = thresholdMs(referenceMs)
        val out = mutableListOf<CoverageFinding>()
        val audio = measured.filter { it.kind == TrackKind.AUDIO }
        for (s in measured) {
            val end = s.measuredEndMs!!
            if (referenceMs - end < threshold) continue
            val kind = when (s.kind) {
                TrackKind.VIDEO -> "D"
                else -> {
                    val others = audio.filter { it.index != s.index }
                    val sameLangComplete = others.filter { sameLang(it.language, s.language) && referenceMs - it.measuredEndMs!! < threshold }
                    when {
                        others.isEmpty() -> "C"
                        sameLangComplete.isNotEmpty() -> "A"
                        else -> "B"
                    }
                }
            }
            out += CoverageFinding(
                kind = kind, streamIndex = s.index, trackKind = s.kind.name, codec = s.codec, language = s.language,
                title = s.title, channels = s.channels, default = s.default,
                firstOfKind = real.filter { it.kind == s.kind }.minByOrNull { it.index }?.index == s.index,
                endsMs = end, referenceMs = referenceMs, headerMs = headerMs,
                alternativeStreamIndex = if (kind == "A") audio.filter { it.index != s.index && sameLang(it.language, s.language) && referenceMs - it.measuredEndMs!! < threshold }.minByOrNull { it.index }?.index else null,
            )
        }
        if (headerMs != null) {
            val headerThreshold = thresholdMs(headerMs)
            if (measured.all { headerMs - it.measuredEndMs!! >= headerThreshold }) {
                out += CoverageFinding(kind = "E", streamIndex = null, trackKind = null, codec = null, language = null, title = null, channels = null,
                    default = false, firstOfKind = false, endsMs = referenceMs, referenceMs = referenceMs, headerMs = headerMs)
            }
        }
        return out
    }

    private fun sameLang(a: String?, b: String?): Boolean = normLang(a) == normLang(b)
    private fun normLang(s: String?): String = s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" } ?: ""

    /** `HH:MM:SS.mmm` (a matroska `DURATION` tag) or plain seconds (`streams[].duration`) → ms; null for junk. */
    fun parseDurationHint(tag: String?, seconds: String?): Long? {
        tag?.trim()?.let { t ->
            val m = Regex("""^(\d+):(\d{1,2}):(\d{1,2})(?:\.(\d{1,9}))?$""").matchEntire(t)
            if (m != null) {
                val h = m.groupValues[1].toLong(); val mi = m.groupValues[2].toLong(); val s = m.groupValues[3].toLong()
                val frac = m.groupValues[4].padEnd(3, '0').take(3).toLong()
                return ((h * 3600 + mi * 60 + s) * 1000 + frac).takeIf { it > 0 }
            }
        }
        return seconds?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
    }

    fun mmss(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
    }
}

/** One audio/video stream as the classifier sees it. [hintMs] is the tag/`duration` hint (never a verdict);
 *  [measuredEndMs] is the last real packet's time, null when not measured. */
data class CoverageStream(
    val index: Int,
    val kind: TrackKind,
    val codec: String,
    val language: String?,
    val title: String?,
    val default: Boolean,
    val attachedPic: Boolean = false,
    val packets: Int? = null,
    val hintMs: Long? = null,
    val measuredEndMs: Long? = null,
    val channels: String? = null,
)

/** Phase 255 — one stored finding. `kind` is `A`/`B`/`C`/`D` for a short stream (the stream's own index is
 *  [streamIndex] — the probe's, never a list position) or `E` for a wrong header ([endsMs] = where the
 *  content really ends). */
@Serializable
data class CoverageFinding(
    val kind: String,
    val streamIndex: Int?,
    val trackKind: String?,
    val codec: String?,
    val language: String?,
    val title: String?,
    val channels: String?,
    val default: Boolean,
    /** The first stream of its kind in the container — what a player with no default flag picks. */
    val firstOfKind: Boolean,
    val endsMs: Long,
    val referenceMs: Long,
    val headerMs: Long?,
    /** Kind A only: the complete same-language stream's index. */
    val alternativeStreamIndex: Int? = null,
)

/** Phase 255 (FR-255-10) — the suggestion for one finding: prose, and the exact command where one exists. */
data class CoverageAdvice(val what: String, val experience: String, val who: String, val suggestion: String, val command: String?)

/**
 * Phase 255 (FR-255-10) — suggestions are text, chosen by the kind; ONE builder, so a page can never say
 * one thing in prose and another in the command. Paths are shell-quoted; a command writes `<in>.fixed.mkv`
 * beside the original and never overwrites it; `-map -0:<n>` carries the stream's own index (dev review
 * item 5 — 254's repair mislabelled tracks by list position).
 */
object TrackCoverageAdvice {
    private fun q(s: String) = "'${FileIntegrity.esc(s)}'"
    private fun out(path: String) = "$path.fixed.mkv"

    fun advise(f: CoverageFinding, path: String, tmdbRuntimeMinutes: Int?): CoverageAdvice {
        val ends = TrackCoverage.mmss(f.endsMs)
        val ref = TrackCoverage.mmss(f.referenceMs)
        val lang = f.language ?: "untagged"
        val trackName = listOfNotNull(
            f.language?.let { "\"$it\"" } ?: "untagged",
            f.codec?.uppercase(),
            f.channels,
            f.title?.let { "“$it”" },
        ).joinToString(" ") + " (stream #${f.streamIndex})"
        val removal = "ffmpeg -i ${q(path)} -map 0 -map -0:${f.streamIndex} -c copy ${q(out(path))}"
        val who = when {
            f.kind == "E" -> "Every viewer: the whole file ends there."
            f.default -> "This track is the file's default, so most players pick it without being asked."
            f.firstOfKind -> "This is the first ${if (f.trackKind == "VIDEO") "video" else "audio"} track and no track is flagged default, so a player that picks the first one gets it."
            else -> "Only viewers who choose this track are affected; the default track is complete."
        }
        return when (f.kind) {
            "A" -> CoverageAdvice(
                what = "The $trackName audio stops at $ends of $ref.",
                experience = "Silence from $ends for anyone playing that track.",
                who = who,
                suggestion = "Remove the short track — the complete one (stream #${f.alternativeStreamIndex}) has the same language and nothing is lost. Until then, mark the complete track as the default audio (Tracks & subtitles) so players pick it. Check the new file, then swap it in.",
                command = removal,
            )
            "B" -> CoverageAdvice(
                what = "The $trackName audio stops at $ends of $ref.",
                experience = "Silence from $ends for anyone playing that track.",
                who = who,
                suggestion = "The $lang audio stops at $ends — there is no complete $lang track in this file. Remove it (viewers choosing $lang currently get ${TrackCoverage.mmss(f.referenceMs - f.endsMs)} of silence), or replace the file with one whose $lang track is complete. Check the new file, then swap it in.",
                command = removal,
            )
            "C" -> CoverageAdvice(
                what = "The only audio track, $trackName, stops at $ends of $ref.",
                experience = "No audio after $ends — silence for the remaining ${TrackCoverage.mmss(f.referenceMs - f.endsMs)}.",
                who = "Every viewer: there is no other audio track.",
                suggestion = "This file has no audio after $ends and nothing in it can restore it. Re-download it: in Sonarr/Radarr, delete the file and search again.",
                command = null,
            )
            "D" -> CoverageAdvice(
                what = "The video ($trackName) stops at $ends of $ref.",
                experience = "Black picture from $ends while the sound continues.",
                who = "Every viewer.",
                suggestion = "The picture ends at $ends while the sound continues. Re-download it: in Sonarr/Radarr, delete the file and search again.",
                command = null,
            )
            else -> {
                val header = f.headerMs?.let { TrackCoverage.mmss(it) } ?: "?"
                val runtimeMs = tmdbRuntimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
                val isMkv = path.substringAfterLast('.').lowercase() == "mkv"
                val remux = if (isMkv) "mkvmerge -o ${q(out(path))} ${q(path)}" else "ffmpeg -i ${q(path)} -map 0 -c copy ${q(out(path))}"
                val what = "Everything in the file ends at $ends, but its header says $header."
                val experience = "Players show $header for a $ends file, and the episode can never reach 90 %, so it is never marked watched and never leaves Continue Watching."
                when {
                    runtimeMs == null -> CoverageAdvice(what, experience, who,
                        "No TMDB runtime is known, so this is one of two things: either the file is complete and only its header is wrong (rewrite the header with a copy remux — nothing is re-encoded), or the file is incomplete and needs re-downloading (Sonarr/Radarr: delete the file, search again). Check the length yourself first.",
                        remux)
                    closeEnough(f.endsMs, runtimeMs) -> CoverageAdvice(what, experience, who,
                        "The file is complete ($ends, TMDB: $tmdbRuntimeMinutes min) but its header says $header. Rewrite the header with a copy remux — nothing is re-encoded. Check the new file, then swap it in.",
                        remux)
                    else -> CoverageAdvice(what, experience, who,
                        "The file stops at $ends but the episode is $tmdbRuntimeMinutes minutes — the file is incomplete. Re-download it: in Sonarr/Radarr, delete the file and search again.",
                        null)
                }
            }
        }
    }

    /** Kind E vs E′: within 10 % or 5 minutes of the TMDB runtime counts as complete. */
    fun closeEnough(contentMs: Long, runtimeMs: Long): Boolean {
        val diff = runtimeMs - contentMs
        return diff <= maxOf(5 * 60_000L, (runtimeMs * 0.10).toLong())
    }
}
