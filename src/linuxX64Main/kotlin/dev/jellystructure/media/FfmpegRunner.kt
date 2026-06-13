package dev.jellystructure.media

import dev.jellystructure.model.TrackKind
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
    fun setDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>, kind: TrackKind): Boolean {
        val typeStr = typeChar(kind)
        val tmp = tmpPath(filePath)
        val escaped = filePath.replace("'", "'\\''")
        val escapedTmp = tmp.replace("'", "'\\''")

        val dispositions = sameTypeIndices.mapIndexed { relIdx, absIdx ->
            val flag = if (absIdx == defaultStreamIndex) "default" else "0"
            "-disposition:$typeStr:$relIdx $flag"
        }.joinToString(" ")

        val cmd = "ffmpeg -y -i '$escaped' -map 0 -c copy $dispositions '$escapedTmp' 2>&1 && mv '$escapedTmp' '$escaped'"
        val ok = runCommand(cmd)
        if (!ok) {
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
    }

    // Remux file rewriting the language metadata tag on one stream (by absolute stream index).
    fun setLanguage(filePath: String, streamIndex: Int, language: String): Boolean {
        val tmp = tmpPath(filePath)
        val escaped = filePath.replace("'", "'\\''")
        val escapedTmp = tmp.replace("'", "'\\''")
        val cleanLang = language.replace("'", "").replace("\"", "").take(10)

        val cmd = "ffmpeg -y -i '$escaped' -map 0 -c copy -metadata:s:$streamIndex language=$cleanLang '$escapedTmp' 2>&1 && mv '$escapedTmp' '$escaped'"
        val ok = runCommand(cmd)
        if (!ok) {
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
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

    private fun tmpPath(filePath: String): String {
        val dir = filePath.substringBeforeLast('/')
        val name = filePath.substringAfterLast('/')
        return "$dir/.jstmp_$name"
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun runCommand(cmd: String): Boolean {
        println("[INFO] ffmpeg: $cmd")
        return memScoped {
            val pipe = popen(cmd, "r") ?: return false
            val sb = StringBuilder()
            val buf = allocArray<ByteVar>(4096)
            while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
            val rc = pclose(pipe)
            if (rc != 0) println("[WARN] ffmpeg exit $rc: $sb")
            rc == 0
        }
    }
}
