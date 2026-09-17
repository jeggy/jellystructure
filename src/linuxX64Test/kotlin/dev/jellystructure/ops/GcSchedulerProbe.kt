package dev.jellystructure.ops

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.native.runtime.GC
import kotlin.test.Test

/**
 * Manual probe for the Kotlin/Native GC scheduler (phase 228 open question 1): hold a live set of
 * GC_PROBE_LIVE_MB (default 300) of small objects, then churn GC_PROBE_CHURN_MB (default 2000) of
 * short-lived allocations, printing the auto-tuned target against the live heap the runtime itself
 * reports. Enabled only when GC_PROBE=1.
 */
class GcSchedulerProbe {
    class Node(val payload: ByteArray, val next: Node?)

    @OptIn(ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    private fun report(tag: String, forced: Boolean) {
        if (forced) GC.collect()
        val g = GC.lastGCInfo ?: run { println("PROBE $tag: no GC yet"); return }
        val live = g.memoryUsageAfter["heap"]!!.totalObjectsSizeBytes / 1048576
        println("PROBE $tag epoch=${g.epoch} liveMB=$live targetMB=${GC.targetHeapBytes / 1048576} " +
            "pauseMs=${((g.firstPauseEndTimeNs ?: 0) - (g.firstPauseStartTimeNs ?: 0) + (g.secondPauseEndTimeNs ?: 0) - (g.secondPauseStartTimeNs ?: 0)) / 1_000_000} " +
            "kept=${g.sweepStatistics["heap"]?.keptCount} swept=${g.sweepStatistics["heap"]?.sweptCount} rssKb=${MemoryStats.snapshot().rssKb}")
    }

    @OptIn(ExperimentalForeignApi::class, kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    @Test
    fun probe() {
        if (getenv("GC_PROBE")?.toKString() != "1") return
        val liveMb = getenv("GC_PROBE_LIVE_MB")?.toKString()?.toIntOrNull() ?: 300
        val churnMb = getenv("GC_PROBE_CHURN_MB")?.toKString()?.toIntOrNull() ?: 2000
        println("PROBE config autotune=${GC.autotune} utilization=${GC.targetHeapUtilization} trigger=${GC.heapTriggerCoefficient} minMB=${GC.minHeapBytes / 1048576} maxMB=${GC.maxHeapBytes / 1048576} interval=${GC.regularGCInterval}")
        // Live set: ~200-byte objects (a 160-byte array + node), like a parsed JSON tree.
        var head: Node? = null
        val perNode = 200
        repeat(liveMb * 1048576 / perNode) { head = Node(ByteArray(160), head) }
        report("after building live set", forced = true)
        report("second forced collect", forced = true)
        // Churn: short-lived 4 KB arrays, no retention. Report on the runtime's own schedule.
        var sink = 0
        var lastEpoch = -1L
        val chunks = churnMb * 256
        for (i in 0 until chunks) {
            val a = ByteArray(4096); a[i and 4095] = 1; sink += a[0]
            if (i % (256 * 100) == 0) {          // every 100 MB allocated
                val e = GC.lastGCInfo?.epoch ?: -1
                if (e != lastEpoch) { report("churn ${i / 256} MB", forced = false); lastEpoch = e }
            }
        }
        report("after ${churnMb} MB churn (unforced)", forced = false)
        report("final forced collect", forced = true)
        println("PROBE sink=$sink live=${head != null}")
    }
}
