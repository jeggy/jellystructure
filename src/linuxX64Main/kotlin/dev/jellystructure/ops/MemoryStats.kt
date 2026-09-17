package dev.jellystructure.ops

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import kotlin.native.runtime.GC

/**
 * Process memory as the kernel and the Kotlin/Native runtime each see it, for `/api/health`.
 *
 * Two numbers that must be read together: `rss_kb` is what the container's cgroup is charged for (the
 * figure on the memory graph), `gc_heap_after_kb` is the size of the objects the last collection kept
 * alive. RSS far above the live heap means the runtime is holding pages for objects that are already
 * dead — an allocator/GC question, not a reference leak. A live heap that itself climbs is a reference
 * leak. Neither is visible from outside the process, which is why this exists.
 */
object MemoryStats {
    class Snapshot(
        val rssKb: Long,
        val rssAnonKb: Long,
        val swapKb: Long,
        val threads: Long,
        val gcEpoch: Long?,
        val gcHeapBeforeKb: Long?,
        val gcHeapAfterKb: Long?,
        val gcPauseMs: Long?,
        val gcAgeMs: Long?,
        val gcTargetHeapKb: Long,
        val gcMinHeapKb: Long,
        val gcMaxHeapKb: Long,
        val gcUtilization: Double,
        val gcTriggerCoefficient: Double,
        val gcAutotune: Boolean,
        val gcIntervalMs: Long,
        val gcMarked: Long?,
        val gcUsageBefore: String,
        val gcUsageAfter: String,
        val gcSweep: String,
        val gcRoots: String,
    ) {
        fun toJson(): String =
            """{"rss_kb":$rssKb,"rss_anon_kb":$rssAnonKb,"swap_kb":$swapKb,"threads":$threads,""" +
                """"gc_epoch":${gcEpoch ?: "null"},"gc_heap_before_kb":${gcHeapBeforeKb ?: "null"},""" +
                """"gc_heap_after_kb":${gcHeapAfterKb ?: "null"},"gc_pause_ms":${gcPauseMs ?: "null"},"gc_age_ms":${gcAgeMs ?: "null"},""" +
                """"gc_target_heap_kb":$gcTargetHeapKb,"gc_min_heap_kb":$gcMinHeapKb,"gc_max_heap_kb":$gcMaxHeapKb,"gc_utilization":$gcUtilization,""" +
                """"gc_trigger_coefficient":$gcTriggerCoefficient,"gc_autotune":$gcAutotune,"gc_interval_ms":$gcIntervalMs,"gc_marked":${gcMarked ?: "null"},""" +
                """"gc_usage_before":$gcUsageBefore,"gc_usage_after":$gcUsageAfter,"gc_sweep":$gcSweep,"gc_roots":$gcRoots}"""
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun procStatus(): Map<String, Long> = memScoped {
        val fp = fopen("/proc/self/status", "r") ?: return emptyMap()
        val buf = allocArray<ByteVar>(512)
        val out = HashMap<String, Long>()
        while (fgets(buf, 512, fp) != null) {
            val line = buf.toKString()
            val key = line.substringBefore(':', "")
            if (key == "VmRSS" || key == "RssAnon" || key == "VmSwap" || key == "Threads") {
                out[key] = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
            }
        }
        fclose(fp)
        out
    }

    /** FR-228-4 — raises `GC.minHeapBytes` (the auto-tune floor) when the env var is a positive number
     *  of MiB; anything else leaves the runtime default untouched. Logged either way so a deployment's
     *  choice is visible in the first lines of its log. */
    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
    fun applyGcFloorFromEnv(raw: String) {
        val mb = raw.trim().toLongOrNull()?.takeIf { it > 0 }
        if (mb == null) {
            println("[INFO] GC floor: runtime default (minHeapBytes=${GC.minHeapBytes / 1024 / 1024} MiB); set JELLYSTRUCTURE_GC_MIN_HEAP_MB to raise it")
            return
        }
        GC.minHeapBytes = mb * 1024 * 1024
        println("[INFO] GC floor: minHeapBytes raised to $mb MiB (JELLYSTRUCTURE_GC_MIN_HEAP_MB)")
    }

    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    private fun usageJson(m: Map<String, kotlin.native.runtime.MemoryUsage>?): String =
        m?.entries?.joinToString(",", "{", "}") { "\"${it.key}\":${it.value.totalObjectsSizeBytes / 1024}" } ?: "null"

    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    fun snapshot(): Snapshot {
        val st = procStatus()
        val gc = kotlin.native.runtime.GC.lastGCInfo
        val pauseNs = gc?.let { info ->
            val first = (info.firstPauseEndTimeNs ?: 0L) - (info.firstPauseStartTimeNs ?: 0L)
            val second = (info.secondPauseEndTimeNs ?: 0L) - (info.secondPauseStartTimeNs ?: 0L)
            first + second
        }
        return Snapshot(
            rssKb = st["VmRSS"] ?: 0L,
            rssAnonKb = st["RssAnon"] ?: 0L,
            swapKb = st["VmSwap"] ?: 0L,
            threads = st["Threads"] ?: 0L,
            gcEpoch = gc?.epoch,
            gcHeapBeforeKb = gc?.memoryUsageBefore?.get("heap")?.totalObjectsSizeBytes?.div(1024),
            gcHeapAfterKb = gc?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes?.div(1024),
            gcPauseMs = pauseNs?.div(1_000_000),
            gcAgeMs = null,
            gcTargetHeapKb = GC.targetHeapBytes / 1024,
            gcMinHeapKb = GC.minHeapBytes / 1024,
            gcMaxHeapKb = GC.maxHeapBytes / 1024,
            gcUtilization = GC.targetHeapUtilization,
            gcTriggerCoefficient = GC.heapTriggerCoefficient,
            gcAutotune = GC.autotune,
            gcIntervalMs = GC.regularGCInterval.inWholeMilliseconds,
            gcMarked = gc?.markedCount,
            gcUsageBefore = usageJson(gc?.memoryUsageBefore),
            gcUsageAfter = usageJson(gc?.memoryUsageAfter),
            gcRoots = gc?.rootSet?.let { r -> """{"thread_local":${r.threadLocalReferences},"stack":${r.stackReferences},"global":${r.globalReferences},"stable":${r.stableReferences}}""" } ?: "null",
            gcSweep = gc?.sweepStatistics?.entries?.joinToString(",", "{", "}") { "\"${it.key}\":{\"swept\":${it.value.sweptCount},\"kept\":${it.value.keptCount}}" } ?: "null",
        )
    }
}
