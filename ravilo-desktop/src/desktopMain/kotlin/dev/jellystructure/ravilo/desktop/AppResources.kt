package dev.jellystructure.ravilo.desktop

import dev.jellystructure.ravilo.ui.desktop.MacNative

/**
 * R383 (FR-R383-6) — the app's own CPU %, memory and idle wakeups, for the About window. The kernel gives counters
 * (`ravilo_resource_usage`), so a rate is two reads and the wall clock between them.
 */
internal object AppResources {
    /** One read: [wallNanos] is the caller's monotonic clock, the rest is the kernel's. */
    data class Sample(val wallNanos: Long, val footprintBytes: Long, val cpuNanos: Long, val idleWakeups: Long)

    /** What the window shows. A rate is null until there are two reads a moment apart. */
    data class Figures(val footprintBytes: Long, val cpuPercent: Double?, val wakeupsPerSecond: Double?)

    /** A read now; null off a Mac, with no Swift library, or with a library from before the export. */
    fun read(wallNanos: Long = System.nanoTime()): Sample? =
        MacNative.resourceUsage()?.let { Sample(wallNanos, it[0], it[1], it[2]) }

    /** CPU % is of one core, as Activity Monitor's is: a process using two cores fully reads 200. */
    fun figures(previous: Sample?, now: Sample): Figures {
        val wall = previous?.let { now.wallNanos - it.wallNanos }?.takeIf { it > 0 }
            ?: return Figures(now.footprintBytes, cpuPercent = null, wakeupsPerSecond = null)
        val seconds = wall / 1e9
        return Figures(
            footprintBytes = now.footprintBytes,
            cpuPercent = ((now.cpuNanos - previous.cpuNanos).coerceAtLeast(0) / wall.toDouble()) * 100.0,
            wakeupsPerSecond = (now.idleWakeups - previous.idleWakeups).coerceAtLeast(0) / seconds,
        )
    }

    /** `357 MB`, `1.3 GB` — the units and rounding Activity Monitor uses, with a dot whatever the language. */
    fun formatMemory(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1000) "${mb.toLong()} MB" else "${"%.1f".format(java.util.Locale.ROOT, mb / 1024.0)} GB"
    }

    fun formatCpu(percent: Double?): String = percent?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "—"

    fun formatWakeups(perSecond: Double?): String = perSecond?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "—"
}
