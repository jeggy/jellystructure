package dev.jellystructure.media

import dev.jellystructure.log.Logger
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

    suspend fun setDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>, kind: TrackKind): Boolean {
        val core = TrackCommandBuilder.ffmpegDefault(filePath, defaultStreamIndex, sameTypeIndices, typeChar(kind))
        val escaped = filePath.replace("'", "'\\''")
        return runRemux(filePath, withOwnershipPreservation(escaped, core))
    }

    suspend fun setLanguage(filePath: String, streamIndex: Int, language: String): Boolean {
        val core = TrackCommandBuilder.ffmpegLanguage(filePath, streamIndex, language) ?: return false
        val escaped = filePath.replace("'", "'\\''")
        return runRemux(filePath, withOwnershipPreservation(escaped, core))
    }

    // Remux file removing a single track (by absolute stream index) — no shared builder (unique op).
    suspend fun removeTrack(filePath: String, streamIndex: Int): Boolean {
        val tmp = tmpPath(filePath)
        val escaped = filePath.replace("'", "'\\''")
        val escapedTmp = tmp.replace("'", "'\\''")
        val core = "ffmpeg -y -i '$escaped' -map 0 -map -0:$streamIndex -c copy '$escapedTmp' 2>&1 && mv '$escapedTmp' '$escaped'"
        return runRemux(filePath, withOwnershipPreservation(escaped, core))
    }

    suspend fun reorderTracks(filePath: String, kind: TrackKind, orderedIndices: List<Int>): Boolean {
        val core = TrackCommandBuilder.ffmpegReorder(filePath, orderedIndices, kind == TrackKind.AUDIO)
        val escaped = filePath.replace("'", "'\\''")
        return runRemux(filePath, withOwnershipPreservation(escaped, core))
    }

    // Dry-run command strings for the /tracks/plan endpoint — delegate to the shared builder.
    fun planSetDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>, kind: TrackKind): String =
        TrackCommandBuilder.ffmpegDefault(filePath, defaultStreamIndex, sameTypeIndices, typeChar(kind))

    fun planReorderTracks(filePath: String, kind: TrackKind, orderedIndices: List<Int>): String =
        TrackCommandBuilder.ffmpegReorder(filePath, orderedIndices, kind == TrackKind.AUDIO)

    private suspend fun runRemux(filePath: String, cmd: String): Boolean {
        val ok = runCommand(cmd)
        if (!ok) {
            val tmp = tmpPath(filePath)
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
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
