package dev.jellystructure.ravilo.ui.desktop

import java.lang.management.ManagementFactory

/**
 * R329 (FR-R329-1) — the spike's numbers, read out of the player that is running.
 *
 * The bar is *dropped frames over five minutes, CPU, and whether an HLS audio-rendition switch works*, measured at
 * 1080p H.264 and 4K HEVC. Every one of those lives inside AVPlayer on a Mac, and the only way into a Mac over ssh
 * is the test driver — so far the Mac's numbers have come from the server's log and `ps`, which cannot see a dropped
 * frame at all. This is the way in: one line, the player's own account.
 *
 * The live film player registers itself here while it holds an item ([attach]/[detach]); with no film loaded the
 * line says so rather than reporting zeros that would read as a pass.
 */
object DesktopPlayerProbe {
    @Volatile private var live: dev.jellystructure.ravilo.ui.seams.RaviloPlayer? = null

    internal fun attach(player: dev.jellystructure.ravilo.ui.seams.RaviloPlayer) { live = player }

    /** Only the player that registered clears the slot: a new load may already have taken it. */
    internal fun detach(player: dev.jellystructure.ravilo.ui.seams.RaviloPlayer) { if (live === player) live = null }

    /** The test driver's `player` command. */
    fun line(): String = runCatching { live?.spikeLine() ?: "no film loaded" }.getOrElse { "failed: ${it.message}" }

    /** The app's own share of the machine, as the JVM measures it over the last interval; `-` where it is not offered. */
    internal fun processCpuPercent(): String {
        val load = runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.processCpuLoad
        }.getOrNull() ?: return "-"
        return if (load < 0) "-" else "${(load * 100).toInt()}%"
    }

    /** Heap in use — the 1.50.0 Mac build died of `OutOfMemoryError: Java heap space`, so a spike run should see it. */
    internal fun heapMb(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used / (1024 * 1024)
}
