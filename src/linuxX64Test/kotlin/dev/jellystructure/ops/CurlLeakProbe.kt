package dev.jellystructure.ops

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.getenv
import kotlin.native.runtime.GC
import kotlin.test.Test

/** Manual probe: CURL_PROBE_URL=<url> [CURL_PROBE_N=2000] [CURL_PROBE_MODE=ok|fail|cancel]. Reports the
 *  GC's stable-reference root count and live heap every 250 requests through the app's shared client. */
class CurlLeakProbe {
    @OptIn(ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    private fun report(tag: String) {
        GC.collect()
        val g = GC.lastGCInfo!!
        println("PROBE $tag stable=${g.rootSet.stableReferences} liveKb=${g.memoryUsageAfter["heap"]!!.totalObjectsSizeBytes / 1024} rssKb=${MemoryStats.snapshot().rssKb}")
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun probe() = runBlocking {
        val url = getenv("CURL_PROBE_URL")?.toKString() ?: return@runBlocking
        val n = getenv("CURL_PROBE_N")?.toKString()?.toIntOrNull() ?: 2000
        val mode = getenv("CURL_PROBE_MODE")?.toKString() ?: "ok"
        val client = dev.jellystructure.OutboundHttp.client
        report("start")
        var ok = 0; var failed = 0; var cancelled = 0
        for (i in 1..n) {
            when (mode) {
                "cancel" -> {
                    val timeoutMs = getenv("CURL_PROBE_TIMEOUT_MS")?.toKString()?.toLongOrNull() ?: 50L
                    runCatching { withTimeoutOrNull(timeoutMs) { client.get(url).bodyAsText() } }
                        .onSuccess { if (it == null) cancelled++ else ok++ }
                        .onFailure { failed++; if (failed <= 3) println("PROBE exception ${it::class.simpleName}: ${it.message?.take(160)}") }
                }
                else -> runCatching { client.get(url).bodyAsText() }.onSuccess { ok++ }.onFailure { failed++ }
            }
            if (i % 250 == 0) report("after $i (ok=$ok failed=$failed cancelled=$cancelled)")
        }
        report("end (ok=$ok failed=$failed cancelled=$cancelled)")
    }
}
