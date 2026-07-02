package dev.jellystructure.media

import dev.jellystructure.log.Logger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

object MkvpropeditRunner {

    suspend fun setLanguage(filePath: String, streamIndex: Int, language: String): Boolean =
        runCommand(TrackCommandBuilder.mkvLanguage(filePath, streamIndex, language) ?: return false)

    suspend fun setForced(filePath: String, forcedStreamIndex: Int, sameTypeIndices: List<Int>): Boolean =
        runCommand(TrackCommandBuilder.mkvForced(filePath, forcedStreamIndex, sameTypeIndices))

    suspend fun setDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>): Boolean =
        runCommand(TrackCommandBuilder.mkvDefault(filePath, defaultStreamIndex, sameTypeIndices))

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
