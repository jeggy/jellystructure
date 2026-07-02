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

    // Phase 109: exposed so MediaJobQueue can target the same temp file for cancellation (pkill -f) and
    // for the disk-space preflight — the tmp copy is a second full-size file on the same filesystem.
    fun tmpPath(filePath: String): String {
        val dir = filePath.substringBeforeLast('/')
        val name = filePath.substringAfterLast('/')
        return "$dir/.jstmp_$name"
    }

    /** Phase 109: source duration in seconds via ffprobe, for live remux progress %. Null if unknown
     *  (ffprobe failure, or a non-numeric/empty duration) — callers degrade to speed-only progress. */
    suspend fun probeDurationSeconds(filePath: String): Double? {
        val escaped = filePath.replace("'", "'\\''")
        val out = captureCommand("ffprobe -v error -show_entries format=duration -of csv=p=0 '$escaped' 2>/dev/null")
        return out?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
    }

    /**
     * Phase 109: like [runRemux] but runs the core command with `nice`/`ionice` (protects API/playback
     * from a big remux) and `-progress pipe:1 -nostats` (structured progress instead of ffmpeg's default
     * human-readable stats line), parsing `out_time_ms=`/`speed=` out of each progress block and invoking
     * [onProgress] once per block (~every 0.5s, ffmpeg's own default `-progress` cadence). [onProgress]
     * runs synchronously inside the blocking read loop — callers must keep it cheap (a WS broadcast) and
     * non-suspending; it uses `runBlocking` internally to bridge into a suspend broadcaster.
     */
    suspend fun runRemuxTracked(
        filePath: String,
        cmd: String,
        durationSeconds: Double?,
        onProgress: (pct: Double, speed: String?) -> Unit,
    ): Boolean {
        val niced = cmd.replaceFirst("ffmpeg -y ", "nice -n 19 ionice -c3 ffmpeg -y -progress pipe:1 -nostats ")
        val escaped = filePath.replace("'", "'\\''")
        val ok = runCommandTracked(withOwnershipPreservation(escaped, niced), durationSeconds, onProgress)
        if (!ok) {
            val tmp = tmpPath(filePath)
            @OptIn(ExperimentalForeignApi::class)
            remove(tmp)
        }
        return ok
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun captureCommand(cmd: String): String? = memScoped {
        val pipe = popen(cmd, "r") ?: return null
        val sb = StringBuilder()
        val buf = allocArray<ByteVar>(4096)
        while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
        pclose(pipe)
        sb.toString()
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun runCommandTracked(
        cmd: String,
        durationSeconds: Double?,
        onProgress: (pct: Double, speed: String?) -> Unit,
    ): Boolean {
        Logger.info("ffmpeg (tracked): $cmd", "track")
        return memScoped {
            val pipe = popen(cmd, "r") ?: return false
            val sb = StringBuilder()
            val buf = allocArray<ByteVar>(4096)
            var lastSpeed: String? = null
            var lastOutTimeUs: Long? = null
            while (fgets(buf, 4096, pipe) != null) {
                val line = buf.toKString()
                sb.append(line)
                for (raw in line.split('\n')) {
                    val trimmed = raw.trim()
                    when {
                        trimmed.startsWith("out_time_ms=") -> lastOutTimeUs = trimmed.removePrefix("out_time_ms=").toLongOrNull()
                        trimmed.startsWith("speed=") -> lastSpeed = trimmed.removePrefix("speed=").trim().takeIf { it.isNotBlank() && it != "N/A" }
                        trimmed == "progress=continue" || trimmed == "progress=end" -> {
                            val pct = if (durationSeconds != null && lastOutTimeUs != null)
                                ((lastOutTimeUs.toDouble() / 1_000_000.0) / durationSeconds * 100.0).coerceIn(0.0, 100.0)
                            else 0.0
                            onProgress(pct, lastSpeed)
                        }
                    }
                }
            }
            val rc = pclose(pipe)
            if (rc != 0) Logger.warn("ffmpeg exit $rc: $sb", "track")
            rc == 0
        }
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

    /** R131: extract a single JPEG frame at [atSeconds] into [output] (overwrites) — the screen-grabber's
     *  core. `-ss` before `-i` is a fast input seek; `-q:v 3` ≈ JPEG quality 90. */
    suspend fun extractFrame(input: String, output: String, atSeconds: Int): Boolean {
        val inEsc = input.replace("'", "'\\''")
        val outEsc = output.replace("'", "'\\''")
        return runCommand("ffmpeg -y -ss $atSeconds -i '$inEsc' -frames:v 1 -q:v 3 '$outEsc' 2>&1")
    }

    /** R133: resize [input] into [output] for the Ravilo artwork service. Pass [width] OR [height] (the
     *  other side scales to preserve aspect; -2 keeps it even, required by some encoders). PNG output
     *  (logos) preserves alpha; JPEG gets `-q:v 3`. */
    suspend fun resizeImage(input: String, output: String, width: Int? = null, height: Int? = null): Boolean {
        val inEsc = input.replace("'", "'\\''")
        val outEsc = output.replace("'", "'\\''")
        val w = width?.takeIf { it > 0 } ?: -2
        val h = height?.takeIf { it > 0 } ?: -2
        val q = if (output.endsWith(".png")) "" else "-q:v 3 "
        return runCommand("ffmpeg -y -i '$inEsc' -vf scale=$w:$h -frames:v 1 $q'$outEsc' 2>&1")
    }
}
