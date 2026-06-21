package dev.jellystructure.media

import dev.jellystructure.log.Logger
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen
import platform.posix.remove

object FfmpegRunner {
    private fun typeChar(kind: TrackKind) = when (kind) {
        TrackKind.AUDIO -> "a"
        TrackKind.SUBTITLE -> "s"
        else -> "v"
    }

    // Remux file setting the default flag for one track of the given type.
    // sameTypeIndices must be in ascending order (as returned by ffprobe).
    suspend fun setDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>, kind: TrackKind): Boolean {
        val typeStr = typeChar(kind)
        val tmp = tmpPath(filePath)
        val escaped = filePath.replace("'", "'\\''")
        val escapedTmp = tmp.replace("'", "'\\''")

        val dispositions = sameTypeIndices.mapIndexed { relIdx, absIdx ->
            val flag = if (absIdx == defaultStreamIndex) "default" else "0"
            "-disposition:$typeStr:$relIdx $flag"
        }.joinToString(" ")

        val core = "ffmpeg -y -i '$escaped' -map 0 -c copy $dispositions '$escapedTmp' 2>&1 && mv '$escapedTmp' '$escaped'"
        val cmd = withOwnershipPreservation(escaped, core)
        val ok = runCommand(cmd)
        if (!ok) {
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
    }

    // Remux file rewriting the language metadata tag on one stream (by absolute stream index).
    suspend fun setLanguage(filePath: String, streamIndex: Int, language: String): Boolean {
        // MP4's mdhd box needs a 3-letter ISO 639-2 code; a 2-letter value makes ffmpeg write `und`,
        // which re-probes as untagged. Convert first; fail (don't write a guess) if there's no mapping.
        val iso3 = LanguageResolver.toIso6392(language) ?: return false
        val tmp = tmpPath(filePath)
        val escaped = filePath.replace("'", "'\\''")
        val escapedTmp = tmp.replace("'", "'\\''")
        val cleanLang = iso3.replace("'", "").replace("\"", "").take(10)

        val core = "ffmpeg -y -i '$escaped' -map 0 -c copy -metadata:s:$streamIndex language=$cleanLang '$escapedTmp' 2>&1 && mv '$escapedTmp' '$escaped'"
        val cmd = withOwnershipPreservation(escaped, core)
        val ok = runCommand(cmd)
        if (!ok) {
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
    }

    // Remux file removing a single track (by absolute stream index).
    suspend fun removeTrack(filePath: String, streamIndex: Int): Boolean {
        val tmp = tmpPath(filePath)
        val escaped = filePath.replace("'", "'\\''")
        val escapedTmp = tmp.replace("'", "'\\''")
        val core = "ffmpeg -y -i '$escaped' -map 0 -map -0:$streamIndex -c copy '$escapedTmp' 2>&1 && mv '$escapedTmp' '$escaped'"
        val cmd = withOwnershipPreservation(escaped, core)
        val ok = runCommand(cmd)
        if (!ok) {
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
    }

    // Remux file reordering tracks of a given type. orderedIndices gives the desired physical order
    // (absolute stream indices). Video and the opposite type are mapped first/last unchanged.
    // Pass the opposite type char: "a" when reordering subtitles, "s" when reordering audio.
    suspend fun reorderTracks(filePath: String, kind: TrackKind, orderedIndices: List<Int>): Boolean {
        val tmp = tmpPath(filePath)
        val escaped = filePath.replace("'", "'\\''")
        val escapedTmp = tmp.replace("'", "'\\''")
        val maps = buildReorderMaps(kind, orderedIndices)
        val core = "ffmpeg -y -i '$escaped' $maps -c copy '$escapedTmp' 2>&1 && mv '$escapedTmp' '$escaped'"
        val cmd = withOwnershipPreservation(escaped, core)
        val ok = runCommand(cmd)
        if (!ok) {
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
    }

    // Remux file reordering tracks — dry-run command string.
    fun planReorderTracks(filePath: String, kind: TrackKind, orderedIndices: List<Int>): String {
        val tmp = tmpPath(filePath)
        val escaped = filePath.replace("'", "'\\''")
        val escapedTmp = tmp.replace("'", "'\\''")
        val maps = buildReorderMaps(kind, orderedIndices)
        return "ffmpeg -y -i '$escaped' \\\n  $maps -c copy \\\n  '$escapedTmp' && mv '$escapedTmp' '$escaped'"
    }

    private fun buildReorderMaps(kind: TrackKind, orderedIndices: List<Int>): String {
        val specificMaps = orderedIndices.joinToString(" ") { "-map 0:$it" }
        return when (kind) {
            TrackKind.AUDIO -> "-map 0:v $specificMaps -map 0:s? -map 0:d?"
            TrackKind.SUBTITLE -> "-map 0:v -map 0:a $specificMaps -map 0:d?"
            else -> "-map 0 $specificMaps"
        }
    }

    // Build the dry-run command string (for /tracks/plan endpoint).
    fun planSetDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>, kind: TrackKind): String {
        val typeStr = typeChar(kind)
        val escaped = filePath.replace("'", "'\\''")
        val tmp = tmpPath(filePath)
        val escapedTmp = tmp.replace("'", "'\\''")
        val dispositions = sameTypeIndices.mapIndexed { relIdx, absIdx ->
            val flag = if (absIdx == defaultStreamIndex) "default" else "0"
            "-disposition:$typeStr:$relIdx $flag"
        }.joinToString(" \\\n  ")
        return "ffmpeg -y -i '$escaped' \\\n  -map 0 -c copy \\\n  $dispositions \\\n  '$escapedTmp' && mv '$escapedTmp' '$escaped'"
    }

    // Wraps a core shell command with stat capture before and chown/chmod restore after success.
    // `escapedOrig` must already be single-quote-safe. The approach is shell-only so it works
    // in any POSIX sh (GNU stat -c is Linux-specific but that's our only deployment target).
    private fun withOwnershipPreservation(escapedOrig: String, core: String): String =
        "_jsu=\$(stat -c '%u' '$escapedOrig' 2>/dev/null);" +
        "_jsg=\$(stat -c '%g' '$escapedOrig' 2>/dev/null);" +
        "_jsm=\$(stat -c '%a' '$escapedOrig' 2>/dev/null);" +
        "$core && " +
        "{ [ -n \"\$_jsu\" ] && chown \"\${_jsu}:\${_jsg}\" '$escapedOrig' 2>/dev/null || true;" +
        "[ -n \"\$_jsm\" ] && chmod \"\$_jsm\" '$escapedOrig' 2>/dev/null || true; }"

    private fun tmpPath(filePath: String): String {
        val dir = filePath.substringBeforeLast('/')
        val name = filePath.substringAfterLast('/')
        return "$dir/.jstmp_$name"
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun runCommand(cmd: String): Boolean {
        Logger.info("ffmpeg: $cmd", "track")
        return memScoped {
            val pipe = popen(cmd, "r") ?: return false
            val sb = StringBuilder()
            val buf = allocArray<ByteVar>(4096)
            while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
            val rc = pclose(pipe)
            if (rc != 0) Logger.warn("ffmpeg exit $rc: $sb", "track")
            rc == 0
        }
    }
}
