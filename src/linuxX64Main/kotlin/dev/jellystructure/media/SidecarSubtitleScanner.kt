package dev.jellystructure.media

import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Phase 200 (FR-200-1) — a subtitle file sitting beside the video (`.srt`, `.ass`, `.ssa`, `.vtt`,
 * `.sub`, `.idx`) exists on disk but nowhere in `ffprobe`'s view of the container; nothing in `src/`
 * discovered it before this. Read-only (no downloading, renaming or deleting — Phase 157/Bazarr owns
 * that), and basename-matched to its own video so a shared-folder sibling episode's subtitles are
 * never attributed to the wrong file.
 */
internal object SidecarSubtitleScanner {

    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx")

    // Full-name shapes seen in the wild (FR-200-3) — sidecar filenames occasionally spell the language
    // out rather than using an ISO code. Not an atlas: scoped to names actually observed in production
    // plus the handful of other common ones a household library is likely to contain.
    private val FULL_NAME_TO_ISO1 = mapOf(
        "english" to "en", "danish" to "da", "swedish" to "sv", "norwegian" to "no",
        "finnish" to "fi", "faroese" to "fo", "icelandic" to "is", "german" to "de",
        "dutch" to "nl", "french" to "fr", "spanish" to "es", "italian" to "it",
        "polish" to "pl", "russian" to "ru", "arabic" to "ar", "persian" to "fa",
        "farsi" to "fa", "portuguese" to "pt", "portuguese-brazil" to "pt",
        "brazilian" to "pt", "chinese" to "zh", "japanese" to "ja", "korean" to "ko",
        "hindi" to "hi", "turkish" to "tr", "greek" to "el", "czech" to "cs",
        "hungarian" to "hu", "romanian" to "ro", "croatian" to "hr", "serbian" to "sr",
        "bulgarian" to "bg", "ukrainian" to "uk", "vietnamese" to "vi", "thai" to "th",
        "indonesian" to "id",
    )

    /** One parsed sidecar filename suffix (everything after `<video-stem>`, before the extension). */
    internal data class SidecarInfo(val language: String?, val forced: Boolean, val sdh: Boolean)

    /**
     * Parses the tag suffix of a sidecar filename — e.g. `.da.hi`, `_eng`, `.persian`, `.cc`, or empty
     * for a bare `<stem>.srt`. Flag order is not assumed (`.da.hi` and `.hi.da` both occur in the
     * wild). An unrecognised or absent language is `null`, never a guess (FR-200-3).
     */
    internal fun parseSidecarSuffix(suffix: String): SidecarInfo {
        val tokens: List<String> = when {
            suffix.isEmpty() -> emptyList()
            suffix.startsWith("_") -> listOf(suffix.removePrefix("_").lowercase())
            suffix.startsWith(".") -> suffix.removePrefix(".").split(".").map { it.lowercase() }
            else -> listOf(suffix.lowercase())
        }.filter { it.isNotBlank() }

        var forced = false
        var sdh = false
        var language: String? = null
        for (token in tokens) {
            when {
                token == "forced" -> forced = true
                token == "hi" || token == "cc" || token == "sdh" -> sdh = true
                else -> {
                    val resolved = languageFromToken(token)
                    if (resolved != null) language = resolved
                }
            }
        }
        return SidecarInfo(language, forced, sdh)
    }

    private fun languageFromToken(token: String): String? {
        FULL_NAME_TO_ISO1[token]?.let { return it }
        // A bare 2/3-letter code is trusted as-is if LanguageResolver recognizes it as a real language
        // (round-trips through toIso6392) — never a guess for an arbitrary unrecognised short token.
        if (token.length in 2..3 && dev.jellystructure.resolver.LanguageResolver.toIso6392(token) != null) {
            return dev.jellystructure.resolver.LanguageResolver.normalize(token)
        }
        return null
    }

    /**
     * Every sidecar subtitle track for [videoPath], basename-matched to its own folder. [startStreamIndex]
     * seeds synthetic stream indices past the container's own real ones — external tracks are never
     * valid mkvpropedit/ffmpeg edit targets (see [Track.external]'s doc), so these numbers only need to
     * be unique within the item, not meaningful to any tool.
     */
    fun discover(videoPath: String, startStreamIndex: Int): List<Track> {
        val dir = videoPath.substringBeforeLast('/', missingDelimiterValue = ".")
        val videoName = videoPath.substringAfterLast('/')
        val stem = videoName.substringBeforeLast('.', missingDelimiterValue = videoName)
        if (stem.isBlank()) return emptyList()

        val dirPath = Path(dir)
        if (!SystemFileSystem.exists(dirPath)) return emptyList()
        val entries = runCatching { SystemFileSystem.list(dirPath) }.getOrDefault(emptyList())

        val matches = entries.mapNotNull { entryPath ->
            val name = entryPath.toString().substringAfterLast('/')
            if (name.startsWith(".")) return@mapNotNull null // hidden, incl. AppleDouble `._` files
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (ext !in SUBTITLE_EXTENSIONS) return@mapNotNull null
            val nameWithoutExt = name.removeSuffix(".$ext")
            if (!nameWithoutExt.startsWith(stem)) return@mapNotNull null
            val boundaryOk = nameWithoutExt.length == stem.length ||
                nameWithoutExt[stem.length] == '.' || nameWithoutExt[stem.length] == '_'
            if (!boundaryOk) return@mapNotNull null
            val suffix = nameWithoutExt.removePrefix(stem)
            entryPath.toString() to parseSidecarSuffix(suffix)
        }.sortedBy { it.first }

        return matches.mapIndexed { i, (path, info) ->
            val idx = startStreamIndex + i
            Track(
                streamIndex = idx,
                specifier = "ext:s:$i",
                kind = TrackKind.SUBTITLE,
                codec = path.substringAfterLast('.').lowercase(),
                language = info.language,
                title = null,
                default = false,
                forced = info.forced,
                external = true,
                externalPath = path,
                sdh = info.sdh,
            )
        }
    }
}
