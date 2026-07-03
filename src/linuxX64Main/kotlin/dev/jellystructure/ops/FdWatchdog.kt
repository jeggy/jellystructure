package dev.jellystructure.ops

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.server.routes.fireWebhook
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.readlink

/**
 * Phase 129 (FR-OPS1 §A) — a breakdown of open FDs by kind, cheap enough (`readlink` only, no
 * `/proc/net` parse) to compute on every threshold crossing. Makes a spike attributable: sockets
 * (outbound idle vs. inbound vs. WS), files (a leak), or pipes (child processes).
 */
data class FdCensus(
    val total: Int,
    val sockets: Int,
    val pipes: Int,
    val anon: Int,
    val files: Int,
    val other: Int,
    val topFiles: List<Pair<String, Int>>,
) {
    /** Compact JSON for logs / the `fd_pressure` webhook / `/api/health`. */
    fun toJson(): String {
        val topFilesJson = topFiles.joinToString(",") { (path, count) -> """{"path":${path.fdJsonEsc()},"count":$count}""" }
        return """{"total":$total,"sockets":$sockets,"pipes":$pipes,"anon":$anon,"files":$files,"other":$other,"top_files":[$topFilesJson]}"""
    }
}

private fun String.fdJsonEsc(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * Phase 118 (FR C.4/C.5), bounded in code by Phase 129 (FR-OPS1) — the durable fix for Ktor Native's
 * unfixable FD_SETSIZE selector crash (KTOR-8703, no poll()/epoll() work planned) is keeping total
 * process FDs under 1024. This watchdog makes that budget observable, sheds load before the ceiling,
 * and — only as a last-resort backstop that should never fire once the Phase 129 bounding holds —
 * triggers a controlled restart via the existing Docker `restart: unless-stopped` policy.
 *
 * Ticks every **2s** (was 60s, Phase 129 — the shed/exit decisions need a fresh count, not a up-to-
 * 60s-stale one). The plain FD count is cheap every tick; [FdCensus] is only computed at/above the
 * warn threshold, keeping the hot tick a bare `/proc/self/fd` count.
 *
 * Thresholds (Phase 129): **700** warn (log + census) · **850** alert + webhook + census · **900**
 * global shed (`isOverShedThreshold`, read by [dev.jellystructure.server.CacheHeaders]'s global
 * intercept) · **980** drain + controlled `_exit(17)`, insurance only — see [drainAndExit].
 *
 * Documented FD budget (kept current here — any new long-lived FD source must add its line). Revised
 * by Phase 134 (FR-OPS2) after a second real incident (851 FDs, user-visible on a TV) was root-caused
 * live to a systemic file-read leak, NOT a scaling problem: `SystemFileSystem.source(...)` is a raw
 * `fopen()` with no finalizer, and the pervasive `source(p).buffered().readString()/readByteArray()`
 * idiom across ~16 call sites never closed it — a permanent per-call leak. Phase 134 introduced
 * `dev.jellystructure.io.FileIo` (`.use{}`-scoped read/write) as the *only* sanctioned way to read/write
 * a whole file; every prior leak site now goes through it, so file reads are transient again, not
 * committed. Phase 134 §F also scaled scan concurrency for real (100 workers need real gate capacity,
 * not just a higher config ceiling that queues uselessly behind unchanged 4/24 gates):
 *   outbound HTTP in-flight (OutboundHttp.withPermit)          ≤ 64  (was 24 — Phase 134 §F)
 *   outbound HTTP shared idle pool (OutboundHttp.client)       ≈ 40  (was ≈15 — also absorbs the
 *                                                                     Phase 134 QBittorrentClient stray)
 *   per-TV Jellyfin session WS (Phase 110)                     ≤ 16
 *   Jellyfin library listener WS (Phase 114)                   = 1
 *   child processes (ProcessGate)                              ≤ 16  (was 4 — Phase 134 §F; kept far
 *                                                                     below the 100-worker ceiling since
 *                                                                     each permit is a real forked
 *                                                                     process, not just an fd)
 *   SQLite (WAL + readers)                                      ~ 6
 *   per-TV `/api/tv/events` WS                                 ≤ 128 (Phase 134 §D — previously
 *                                                                     UNBOUNDED; this was the one
 *                                                                     genuine scaling gap the incident
 *                                                                     wasn't actually caused by)
 *   admin `/ws`                                                ~ 10
 *   stdio/misc                                                  ~ 10
 *   -------------------------------------------
 *   committed                                                  ≤ ~290 at the full 50-TV + 100-worker-
 *   scan target, leaving ~610 fds of headroom to the 900 shed threshold (~734 to the 1024 ceiling) for
 *   transient inbound request sockets and transient (`.use{}`-scoped) file reads — bounded in-process by
 *   the 900 global shed + 10s idle timeout (CIO Native exposes no accept-time cap, so that's the ceiling
 *   of in-code inbound control), not hard-capped, but no longer the load-bearing assumption it was before
 *   Phase 134: normal operation shouldn't get anywhere near it.
 */
class FdWatchdog(
    private val configStore: ConfigStore,
    private val dataDir: String,
    private val scope: CoroutineScope,
) {
    @Volatile var currentCount: Int = 0
        private set
    @Volatile var highWaterMark: Int = 0
        private set
    @Volatile var lastCensus: FdCensus? = null
        private set

    /** Phase 129 §D.2 — while true, [dev.jellystructure.server.CacheHeaders]'s global shed intercept
     *  refuses ALL new work (not just above-threshold work), so in-flight requests can finish before
     *  the controlled exit. */
    @Volatile var draining: Boolean = false
        private set

    private var warnedAt700 = false
    private var alertedAt850 = false
    private var exitTriggered = false

    val isOverShedThreshold: Boolean get() = currentCount > 900

    fun start() {
        scope.launch {
            while (true) {
                delay(2_000L)
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
                val census = censusOpenFds()
                lastCensus = census
                Logger.warn("FD count high: $count open file descriptors (>700, ceiling is 1024) census=${census.toJson()}", "ops")
            }
        } else {
            warnedAt700 = false
        }

        if (count > 850) {
            if (!alertedAt850) {
                alertedAt850 = true
                val census = censusOpenFds()
                lastCensus = census
                // Phase 134 (FR-OPS2 §E) — the incident that prompted this fix surfaced only this line
                // (no census), forcing a live /proc inspection to diagnose; carry the same breakdown
                // the >700 warn line and the webhook already do.
                Logger.error("FD count critical: $count open file descriptors (>850, ceiling is 1024) census=${census.toJson()}", "ops")
                fireWebhook(configStore.current, """{"event":"fd_pressure","count":$count,"high_water_mark":$highWaterMark,"census":${census.toJson()}}""")
            }
        } else {
            alertedAt850 = false
        }

        if (count > 980 && !exitTriggered) {
            exitTriggered = true
            drainAndExit(count)
        }
    }

    /**
     * Phase 129 (FR-OPS1 §D.2) — insurance only; should never fire in normal operation once §B's
     * in-code bounding holds. Sets [draining] (the global shed intercept then refuses everything
     * new), waits ~2s for in-flight work to drain, writes an **intentional** `last-restart.json`
     * marker (distinct from the crash marker — this is the safety valve working, not a fault), fires
     * a **synchronous** webhook (must have actually left the box before the process exits, unlike the
     * normal fire-and-forget `fireWebhook`), then exits with a clean non-zero code — strictly before
     * any FD reaches the 1024 hard ceiling — that the existing Docker `restart: unless-stopped` policy
     * brings back. [dev.jellystructure.ops.reportFdRestartRecoveryIfAny] reports the recovery at the
     * next boot.
     */
    private suspend fun drainAndExit(count: Int) {
        draining = true
        val census = lastCensus ?: censusOpenFds()
        Logger.error("FD count at drain threshold: $count open file descriptors (>980) — draining and restarting", "ops")
        delay(2_000L)
        writeLastRestartMarker(dataDir, count, census.toJson())
        fireSynchronousWebhook(configStore.current.behavior.notificationsWebhook, """{"event":"fd_controlled_restart","count":$count,"census":${census.toJson()}}""")
        platform.posix._exit(17)
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

    @OptIn(ExperimentalForeignApi::class)
    fun censusOpenFds(): FdCensus {
        val dir = opendir("/proc/self/fd") ?: return FdCensus(0, 0, 0, 0, 0, 0, emptyList())
        var sockets = 0
        var pipes = 0
        var anon = 0
        var files = 0
        var other = 0
        val fileCounts = mutableMapOf<String, Int>()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val target = readlinkSafe("/proc/self/fd/$name") ?: continue
                when {
                    target.startsWith("socket:") -> sockets++
                    target.startsWith("pipe:") -> pipes++
                    target.startsWith("anon_inode:") -> anon++
                    target.startsWith("/") -> {
                        files++
                        fileCounts[target] = (fileCounts[target] ?: 0) + 1
                    }
                    else -> other++
                }
            }
        } finally {
            closedir(dir)
        }
        val topFiles = fileCounts.entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
        return FdCensus(sockets + pipes + anon + files + other, sockets, pipes, anon, files, other, topFiles)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readlinkSafe(path: String): String? = memScoped {
        val bufSize = 256
        val buf = allocArray<ByteVar>(bufSize)
        val n = readlink(path, buf, (bufSize - 1).convert())
        if (n <= 0) return@memScoped null
        buf.readBytes(n.toInt()).decodeToString()
    }
}
