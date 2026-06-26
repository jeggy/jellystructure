package dev.jellystructure.media

import dev.jellystructure.resolver.LanguageResolver

/**
 * Builds the exact shell-command strings for track edits.
 * Shared between the linuxX64 runners (which execute the commands via popen) and the wasmJs
 * frontend (which displays the same strings as a command preview). One implementation → the
 * UI always shows what will actually run.
 *
 * All returned strings use shell `\<newline>` line-continuations for readability; popen passes
 * them directly to /bin/sh which handles continuations correctly.
 */
object TrackCommandBuilder {

    private fun trackArg(streamIndex: Int) = "track:@${streamIndex + 1}"
    private fun tmpPath(filePath: String) =
        "${filePath.substringBeforeLast('/')}/.jstmp_${filePath.substringAfterLast('/')}"
    private fun esc(path: String) = path.replace("'", "'\\''")

    // ── MKV (mkvpropedit, in-place, fast) ────────────────────────────────────────

    /** Set the language + language-ietf tags on one track. Returns null if [language] has no
     *  ISO-639-2 mapping (caller should treat this as a validation failure). */
    fun mkvLanguage(filePath: String, streamIndex: Int, language: String): String? {
        val iso3 = LanguageResolver.toIso6392(language) ?: return null
        val bcp47 = LanguageResolver.normalize(language)
        return "mkvpropedit '${esc(filePath)}' \\\n" +
            "  --edit ${trackArg(streamIndex)} \\\n" +
            "  --set language=$iso3 \\\n" +
            "  --set language-ietf=$bcp47"
    }

    /** Set flag-default on all tracks of the same type (1 for [defaultStreamIndex], 0 for rest). */
    fun mkvDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>): String {
        val parts = sameTypeIndices.joinToString(" \\\n  ") { idx ->
            "--edit ${trackArg(idx)} --set flag-default=${if (idx == defaultStreamIndex) 1 else 0}"
        }
        return "mkvpropedit '${esc(filePath)}' \\\n  $parts"
    }

    /** Set flag-forced on all subtitle tracks (1 for [forcedStreamIndex], 0 for rest).
     *  Pass forcedStreamIndex = -1 to clear forced on all tracks. */
    fun mkvForced(filePath: String, forcedStreamIndex: Int, sameTypeIndices: List<Int>): String {
        val parts = sameTypeIndices.joinToString(" \\\n  ") { idx ->
            "--edit ${trackArg(idx)} --set flag-forced=${if (idx == forcedStreamIndex) 1 else 0}"
        }
        return "mkvpropedit '${esc(filePath)}' \\\n  $parts"
    }

    // ── FFmpeg (remux, slow — non-MKV or reorder) ─────────────────────────────────

    /** Set language metadata on one stream. Returns null if [language] has no ISO-639-2 mapping. */
    fun ffmpegLanguage(filePath: String, streamIndex: Int, language: String): String? {
        val iso3 = LanguageResolver.toIso6392(language) ?: return null
        val cleanLang = iso3.replace("'", "").replace("\"", "").take(10)
        val escaped = esc(filePath)
        val escapedTmp = esc(tmpPath(filePath))
        return "ffmpeg -y -i '$escaped' \\\n" +
            "  -map 0 -c copy \\\n" +
            "  -metadata:s:$streamIndex language=$cleanLang \\\n" +
            "  '$escapedTmp' && mv '$escapedTmp' '$escaped'"
    }

    /** Set disposition:default on tracks of [typeChar] ("a" = audio, "s" = subtitle).
     *  [sameTypeIndices] must be in file order (ascending absolute stream index). */
    fun ffmpegDefault(
        filePath: String,
        defaultStreamIndex: Int,
        sameTypeIndices: List<Int>,
        typeChar: String,
    ): String {
        val escaped = esc(filePath)
        val escapedTmp = esc(tmpPath(filePath))
        val dispositions = sameTypeIndices.mapIndexed { relIdx, absIdx ->
            "-disposition:$typeChar:$relIdx ${if (absIdx == defaultStreamIndex) "default" else "0"}"
        }.joinToString(" \\\n  ")
        return "ffmpeg -y -i '$escaped' \\\n" +
            "  -map 0 -c copy \\\n" +
            "  $dispositions \\\n" +
            "  '$escapedTmp' && mv '$escapedTmp' '$escaped'"
    }

    /** Reorder tracks of one type. [orderedIndices] gives the desired physical order as absolute
     *  stream indices; video and the opposite type are kept unchanged. */
    fun ffmpegReorder(filePath: String, orderedIndices: List<Int>, isAudio: Boolean): String {
        val escaped = esc(filePath)
        val escapedTmp = esc(tmpPath(filePath))
        val specificMaps = orderedIndices.joinToString(" ") { "-map 0:$it" }
        val maps = if (isAudio)
            "-map 0:v $specificMaps -map 0:s? -map 0:d?"
        else
            "-map 0:v -map 0:a $specificMaps -map 0:d?"
        return "ffmpeg -y -i '$escaped' \\\n" +
            "  $maps \\\n" +
            "  -c copy \\\n" +
            "  '$escapedTmp' && mv '$escapedTmp' '$escaped'"
    }
}
