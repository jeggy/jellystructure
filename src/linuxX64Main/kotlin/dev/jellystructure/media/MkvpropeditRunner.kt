package dev.jellystructure.media

import dev.jellystructure.log.Logger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

object MkvpropeditRunner {

    suspend fun setLanguage(filePath: String, streamIndex: Int, language: String): Boolean =
        runCommand(TrackCommandBuilder.mkvLanguage(filePath, streamIndex, language) ?: return false) &&
            verifyAndRepairLayout(filePath)

    suspend fun setForced(filePath: String, forcedStreamIndex: Int, sameTypeIndices: List<Int>): Boolean =
        runCommand(TrackCommandBuilder.mkvForced(filePath, forcedStreamIndex, sameTypeIndices)) &&
            verifyAndRepairLayout(filePath)

    suspend fun setDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>): Boolean =
        runCommand(TrackCommandBuilder.mkvDefault(filePath, defaultStreamIndex, sameTypeIndices)) &&
            verifyAndRepairLayout(filePath)

    /**
     * Phase 201 (FR-201-2/3) — the post-condition every mkvpropedit edit above must satisfy: `Tracks`
     * still precedes the first `Cluster`. A growing edit (adding `language-ietf`, or writing
     * `flag-default=0` where the element previously didn't exist) can push `Tracks` out of its slot;
     * mkvpropedit then appends the real element to EOF and leaves a same-size `Void` behind, reachable
     * only via `SeekHead` — invisible to ffprobe/Jellyfin (both seek), fatal to ExoPlayer (reads
     * linearly and never builds a renderer). A failing check is repaired immediately, before the
     * calling edit reports success — an unrepaired file is a title that silently cannot be played.
     * [MkvLayout.UNKNOWN] (unparseable) is treated as OK: this is a defensive post-check on a file
     * mkvpropedit itself just edited successfully, not a general-purpose validator.
     */
    private suspend fun verifyAndRepairLayout(filePath: String): Boolean {
        val layout = runCatching {
            SystemFileSystem.source(Path(filePath)).buffered().use { scanMkvLayout(it) }
        }.getOrDefault(MkvLayout.UNKNOWN)
        if (layout != MkvLayout.TRACKS_AFTER_CLUSTER) return true
        Logger.warn("mkvpropedit evicted Tracks past the first Cluster on $filePath — repairing via ffmpeg remux", "track")
        val repaired = FfmpegRunner.repairTracksLayout(filePath)
        if (!repaired) Logger.warn("Tracks-layout repair failed for $filePath — file remains unplayable in Ravilo", "track")
        return repaired
    }

    // Phase 118 (FR C.3) — shared ProcessGate.
    @OptIn(ExperimentalForeignApi::class)
    private suspend fun runCommand(cmd: String): Boolean {
        Logger.info("mkvpropedit: $cmd", "track")
        return dev.jellystructure.ops.ProcessGate.withPermit {
            memScoped {
                val pipe = popen("$cmd 2>&1", "r")
                if (pipe == null) {
                    false
                } else {
                    val sb = StringBuilder()
                    val buf = allocArray<ByteVar>(4096)
                    while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
                    val rc = pclose(pipe)
                    if (rc != 0) Logger.warn("mkvpropedit exit $rc: $sb", "track")
                    rc == 0
                }
            }
        }
    }
}
