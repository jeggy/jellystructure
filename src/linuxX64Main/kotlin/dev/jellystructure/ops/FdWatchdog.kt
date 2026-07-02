package dev.jellystructure.ops

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.server.routes.fireWebhook
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir

/**
 * Phase 118 (FR C.4/C.5) — the durable fix for Ktor Native's unfixable FD_SETSIZE selector crash
 * (KTOR-8703, no poll()/epoll() work planned) is keeping total process FDs under 1024. This watchdog
 * makes that budget observable and gives the server a chance to shed load before it dies:
 * ticks every 60s, counts `/proc/self/fd`, tracks a high-water mark, warns once per crossing at 700,
 * alerts via webhook once per crossing at 900, and [isOverShedThreshold] (950) is read by the image
 * routes / TV events route to reject new work instead of accumulating more FDs toward the ceiling.
 *
 * Documented FD budget (kept current here — any new long-lived FD source must add its line):
 *   outbound HTTP (OutboundHttp)          ≤ 24
 *   per-TV Jellyfin session WS (Phase 110) ≤ 16
 *   Jellyfin library listener WS (Phase 114) = 1
 *   child processes (ProcessGate)         ≤ 4
 *   SQLite (WAL + readers)                 ~ 6
 *   admin/TV app WS                       ≤ ~20
 *   stdio/misc                             ~ 10
 *   -------------------------------------------
 *   committed                             ≤ ~81, leaving ≥900 fds of headroom for inbound sockets,
 *   which the Phase 118 cache headers + CIO idle timeout keep low in practice.
 */
class FdWatchdog(private val configStore: ConfigStore, private val scope: CoroutineScope) {
    @Volatile var currentCount: Int = 0
        private set
    @Volatile var highWaterMark: Int = 0
        private set

    private var warnedAt700 = false
    private var alertedAt900 = false

    val isOverShedThreshold: Boolean get() = currentCount > 950

    fun start() {
        scope.launch {
            while (true) {
                delay(60_000L)
                runCatching { tick() }
            }
        }
    }

    private suspend fun tick() {
        val count = countOpenFds()
        if (count < 0) return // /proc unavailable — nothing to report
        currentCount = count
        if (count > highWaterMark) highWaterMark = count

        if (count > 700) {
            if (!warnedAt700) {
                warnedAt700 = true
                Logger.warn("FD count high: $count open file descriptors (>700, ceiling is 1024)", "ops")
            }
        } else {
            warnedAt700 = false
        }

        if (count > 900) {
            if (!alertedAt900) {
                alertedAt900 = true
                Logger.error("FD count critical: $count open file descriptors (>900, ceiling is 1024)", "ops")
                fireWebhook(configStore.current, """{"event":"fd_pressure","count":$count,"high_water_mark":$highWaterMark}""")
            }
        } else {
            alertedAt900 = false
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun countOpenFds(): Int {
        val dir = opendir("/proc/self/fd") ?: return -1
        var count = 0
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") count++
            }
        } finally {
            closedir(dir)
        }
        // opendir itself holds one fd for the duration of the scan; subtract it so the count reflects
        // the process's steady-state FD usage, not this watchdog's own transient probe.
        return (count - 1).coerceAtLeast(0)
    }
}
