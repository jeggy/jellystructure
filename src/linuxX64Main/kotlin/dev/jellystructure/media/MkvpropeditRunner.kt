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
    // mkvpropedit uses 1-based track numbers matching the overall stream index from ffprobe.
    private fun trackArg(streamIndex: Int) = "track:@${streamIndex + 1}"

    suspend fun setLanguage(filePath: String, streamIndex: Int, language: String): Boolean {
        val escaped = filePath.replace("'", "'\\''")
        val cmd = "mkvpropedit '$escaped' --edit ${trackArg(streamIndex)} --set language=${language.replace("'", "")}"
        return runCommand(cmd)
    }

    suspend fun setDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>): Boolean {
        val escaped = filePath.replace("'", "'\\''")
        val parts = sameTypeIndices.map { idx ->
            val flag = if (idx == defaultStreamIndex) 1 else 0
            "--edit ${trackArg(idx)} --set flag-default=$flag"
        }.joinToString(" ")
        val cmd = "mkvpropedit '$escaped' $parts"
        return runCommand(cmd)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun runCommand(cmd: String): Boolean {
        Logger.info("mkvpropedit: $cmd", "track")
        return memScoped {
            val pipe = popen("$cmd 2>&1", "r") ?: return false
            val sb = StringBuilder()
            val buf = allocArray<ByteVar>(4096)
            while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
            val rc = pclose(pipe)
            if (rc != 0) Logger.warn("mkvpropedit exit $rc: $sb", "track")
            rc == 0
        }
    }
}
