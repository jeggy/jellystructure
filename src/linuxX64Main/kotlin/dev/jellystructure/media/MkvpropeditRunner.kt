package dev.jellystructure.media

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

    fun setLanguage(filePath: String, streamIndex: Int, language: String): Boolean {
        val escaped = filePath.replace("'", "'\\''")
        val cmd = "mkvpropedit '$escaped' --edit ${trackArg(streamIndex)} --set language=${language.replace("'", "")}"
        return runCommand(cmd)
    }

    fun setDefault(filePath: String, defaultStreamIndex: Int, sameTypeIndices: List<Int>): Boolean {
        val escaped = filePath.replace("'", "'\\''")
        val parts = sameTypeIndices.map { idx ->
            val flag = if (idx == defaultStreamIndex) 1 else 0
            "--edit ${trackArg(idx)} --set flag-default=$flag"
        }.joinToString(" ")
        val cmd = "mkvpropedit '$escaped' $parts"
        return runCommand(cmd)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun runCommand(cmd: String): Boolean {
        println("[INFO] mkvpropedit: $cmd")
        return memScoped {
            val pipe = popen("$cmd 2>&1", "r") ?: return false
            val sb = StringBuilder()
            val buf = allocArray<ByteVar>(4096)
            while (fgets(buf, 4096, pipe) != null) sb.append(buf.toKString())
            val rc = pclose(pipe)
            if (rc != 0) println("[WARN] mkvpropedit exit $rc: $sb")
            rc == 0
        }
    }
}
