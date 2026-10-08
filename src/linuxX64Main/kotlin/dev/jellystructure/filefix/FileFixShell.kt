package dev.jellystructure.filefix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ptr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

/** One command's result: its exit status (0 = success) and what it printed (stdout and stderr). */
data class ShellResult(val exit: Int, val output: String) {
    val ok: Boolean get() = exit == 0
}

/** Phase 314 — how the jobs run commands. An interface so the job's rules are testable without ffmpeg. */
interface FileFixShell {
    /** Runs [cmd] to completion. */
    suspend fun run(cmd: String): ShellResult

    /**
     * Runs a long [cmd] whose work file is [marker], and stops it (`pkill -f`) the moment [shouldStop] answers true —
     * playback started, or the job was cancelled (FR-314-10, review item 10: the job queue's playback yield, not
     * SIGSTOP). Returns null when it was stopped.
     */
    suspend fun runStoppable(cmd: String, marker: String, shouldStop: () -> Boolean): ShellResult?

    /** Writes [text] to [path] (a work file). */
    fun writeText(path: String, text: String): Boolean

    fun exists(path: String): Boolean
    fun remove(path: String)
    /** Size in bytes and modification time (epoch seconds), or null. */
    fun stat(path: String): Pair<Long, Long>?
    /** Every regular file directly in [dir]. */
    fun listDir(dir: String): List<String>
}

/** The real one: `popen` through the shared ProcessGate, like FfmpegRunner. */
object PosixFileFixShell : FileFixShell {
    @OptIn(ExperimentalForeignApi::class)
    private fun capture(cmd: String): ShellResult = memScoped {
        val pipe = popen("$cmd 2>&1", "r") ?: return@memScoped ShellResult(-1, "could not start the command")
        val sb = StringBuilder()
        val buf = allocArray<ByteVar>(4096)
        // ffprobe / mkvmerge -J of a file with dozens of tracks and attachments runs past 64 KB; 4 MB is a safe ceiling.
        while (fgets(buf, 4096, pipe) != null) if (sb.length < 4_000_000) sb.append(buf.toKString())
        val status = pclose(pipe)
        ShellResult(if (status == 0) 0 else ((status shr 8) and 0xff).takeIf { it != 0 } ?: status, sb.toString())
    }

    override suspend fun run(cmd: String): ShellResult = dev.jellystructure.ops.ProcessGate.withPermit {
        withContext(Dispatchers.IO) { capture(cmd) }
    }

    override suspend fun runStoppable(cmd: String, marker: String, shouldStop: () -> Boolean): ShellResult? =
        dev.jellystructure.ops.ProcessGate.withPermit {
            coroutineScope {
                var stopped = false
                val work = async(Dispatchers.IO) { capture(cmd) }
                val watcher = launch {
                    while (work.isActive) {
                        delay(1000)
                        if (work.isActive && shouldStop()) {
                            stopped = true
                            withContext(Dispatchers.IO) { capture("pkill -f ${FileFixCommands.q(ereEscape(marker))}") }
                            break
                        }
                    }
                }
                val result = work.await()
                watcher.cancel()
                if (stopped) null else result
            }
        }

    /** `pkill -f` reads its pattern as an extended regex; a file name may hold `.`, `(`, `[`… (2026-08-02 review, L1). */
    private fun ereEscape(s: String): String = s.replace(Regex("""([.^$*+?()\[\]{}|\\])"""), """\\$1""")

    @OptIn(ExperimentalForeignApi::class)
    override fun writeText(path: String, text: String): Boolean {
        val f = platform.posix.fopen(path, "w") ?: return false
        val ok = platform.posix.fputs(text, f) >= 0
        platform.posix.fclose(f)
        return ok
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun exists(path: String): Boolean = platform.posix.access(path, platform.posix.F_OK) == 0

    @OptIn(ExperimentalForeignApi::class)
    override fun remove(path: String) { platform.posix.remove(path) }

    @OptIn(ExperimentalForeignApi::class)
    override fun stat(path: String): Pair<Long, Long>? = memScoped {
        val st = alloc<platform.posix.stat>()
        if (platform.posix.stat(path, st.ptr) != 0) null
        else st.st_size.toLong() to st.st_mtim.tv_sec.toLong()
    }

    override fun listDir(dir: String): List<String> =
        capture("find ${FileFixCommands.q(dir)} -maxdepth 1 -type f").output.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
