package dev.jellystructure.media

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv
import kotlin.native.runtime.GC
import kotlin.test.Test

/** Manual memory probe (not a regression test): set ENVELOPE_PROBE_FILES to a newline-separated list of
 *  media paths and run this test alone. Prints RSS after every envelope and the live heap after a forced GC. */
class EnvelopeMemoryProbe {
    @OptIn(ExperimentalForeignApi::class)
    private fun status(key: String): Long = memScoped {
        val fp = fopen("/proc/self/status", "r") ?: return 0
        val buf = allocArray<ByteVar>(512)
        var v = 0L
        while (fgets(buf, 512, fp) != null) {
            val line = buf.toKString()
            if (line.startsWith("$key:")) { v = line.substringAfter(':').trim().split(' ')[0].toLong(); break }
        }
        fclose(fp)
        v
    }

    @OptIn(ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    private fun liveHeapKb(): Long {
        GC.collect()
        return (GC.lastGCInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: -1L) / 1024
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun probe() = runBlocking {
        val list = getenv("ENVELOPE_PROBE_FILES")?.toKString() ?: return@runBlocking
        val files = list.split('\n').filter { it.isNotBlank() }
        println("PROBE start rss=${status("VmRSS")}kB live=${liveHeapKb()}kB files=${files.size}")
        for ((i, f) in files.withIndex()) {
            val p = FfmpegRunner.computeEnvelope(f, 1000L)
            println("PROBE [$i] peaks=${p?.size} rss=${status("VmRSS")}kB threads=${status("Threads")}")
        }
        println("PROBE end rss=${status("VmRSS")}kB ; after collect live=${liveHeapKb()}kB rss=${status("VmRSS")}kB")
    }
}
