package dev.jellystructure.tv

import io.ktor.websocket.CloseReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 256 (FR-256-1, dev review items 1–2) — why an `/api/tv/events` socket ended, classified once. Never
 * the raw exception message (curl/Ktor text carries URLs), never a token: one of five causes, each a
 * short fixed word or the client's own close code + reason (already bounded by the protocol).
 */
object EventsCloseCause {
    fun classify(closeReason: CloseReason?, error: Throwable?, replaced: Boolean): String = when {
        replaced -> "replaced"
        error != null -> classifyError(error)
        closeReason != null -> when {
            closeReason.message.equals("Ping timeout", ignoreCase = true) -> "ping timeout"
            else -> "client close ${closeReason.code} ${closeReason.message.take(40).ifBlank { "-" }}"
        }
        else -> "eof"
    }

    private fun classifyError(e: Throwable): String {
        val name = e::class.simpleName ?: "Throwable"
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Ping timeout", ignoreCase = true) -> "ping timeout"
            name.contains("ClosedReceiveChannel") || name.contains("ClosedSendChannel") || name.contains("EOF", ignoreCase = true) -> "eof"
            msg.contains("reset", ignoreCase = true) || msg.contains("ECONNRESET") || msg.contains("broken pipe", ignoreCase = true) -> "reset"
            else -> "server error $name"
        }
    }
}

/**
 * Phase 256 (FR-256-2, dev review item 6) — R293's `X-Ravilo-Events-Prev` header, bounded on arrival: at
 * most 512 bytes, at most ten entries, each entry the six documented fields (`open-s;how;lifecycle;
 * interactive;net;sdk`), every field reduced to `[A-Za-z0-9._:+-]` and 40 characters. Anything else is
 * dropped silently. Null for an absent or empty header.
 */
fun sanitizeEventsPrev(raw: String?): String? {
    val s = raw?.take(512)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val field = Regex("[^A-Za-z0-9._:+-]")
    val entries = s.split(',').take(10).mapNotNull { entry ->
        val parts = entry.split(';').take(6).map { field.replace(it.trim(), "_").take(40).ifEmpty { "-" } }
        if (parts.isEmpty() || parts.all { it == "-" }) null else parts.joinToString(";")
    }
    return entries.takeIf { it.isNotEmpty() }?.joinToString(",")
}

/** Phase 256 (FR-256-3) — one device's reconnect rate over the rolling hour, for the log, the health
 *  endpoint and the admin row. [medianLifetimeMs] is over the sockets that ended in that hour. */
@Serializable
data class FlapStats(
    @SerialName("device_id") val deviceId: String,
    @SerialName("connects_last_hour") val connectsLastHour: Int,
    @SerialName("median_lifetime_ms") val medianLifetimeMs: Long?,
)

/**
 * Phase 256 (FR-256-3) — per device, connects in a rolling hour, in memory (a restart forgets it, which is
 * fine for a signal about *now*). Above [threshold] a device is *unstable*: one WARN per device per hour
 * (the caller asks [warnDue]), the figures on `/api/health/full`, and a line on the device's admin row.
 * Cleared the moment the rate falls back under the threshold. Guarded by the caller's lock ([TvEventBus]
 * owns one instance and touches it only under its mutex).
 */
class DeviceFlapCounter(
    private val windowMs: Long = 3_600_000L,
    val threshold: Int = 12,
    private val clock: () -> Long = { dev.jellystructure.nowEpochSec() * 1000L },
) {
    private class Device(
        val connects: ArrayDeque<Long> = ArrayDeque(),
        val lifetimes: ArrayDeque<Pair<Long, Long>> = ArrayDeque(),   // (closedAt, openMs)
        var lastWarnedAt: Long? = null,
    )
    private val devices = HashMap<String, Device>()

    fun connected(deviceId: String, nowMs: Long = clock()) {
        val d = devices.getOrPut(deviceId) { Device() }
        d.connects.addLast(nowMs)
        prune(d, nowMs)
    }

    fun closed(deviceId: String, openMs: Long, nowMs: Long = clock()) {
        val d = devices.getOrPut(deviceId) { Device() }
        d.lifetimes.addLast(nowMs to openMs)
        prune(d, nowMs)
    }

    private fun prune(d: Device, nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (d.connects.isNotEmpty() && d.connects.first() < cutoff) d.connects.removeFirst()
        while (d.lifetimes.isNotEmpty() && d.lifetimes.first().first < cutoff) d.lifetimes.removeFirst()
    }

    fun stats(deviceId: String, nowMs: Long = clock()): FlapStats {
        val d = devices[deviceId] ?: return FlapStats(deviceId, 0, null)
        prune(d, nowMs)
        val sorted = d.lifetimes.map { it.second }.sorted()
        val median = if (sorted.isEmpty()) null else sorted[sorted.size / 2]
        return FlapStats(deviceId, d.connects.size, median)
    }

    /** The device's stats when it is above the threshold right now, else null (the rate fell back). */
    fun unstable(deviceId: String, nowMs: Long = clock()): FlapStats? =
        stats(deviceId, nowMs).takeIf { it.connectsLastHour > threshold }

    /** True at most once per device per hour while it is unstable; the caller logs the WARN. */
    fun warnDue(deviceId: String, nowMs: Long = clock()): FlapStats? {
        val st = unstable(deviceId, nowMs) ?: return null
        val d = devices[deviceId] ?: return null
        d.lastWarnedAt?.let { if (nowMs - it < windowMs) return null }
        d.lastWarnedAt = nowMs
        return st
    }

    /** Every device above the threshold right now, for `/api/health/full`. */
    fun unstableSnapshot(nowMs: Long = clock()): List<FlapStats> =
        devices.keys.sorted().mapNotNull { unstable(it, nowMs) }.also {
            // A device silent for a whole window has nothing left to say; drop it so the map stays bounded.
            devices.entries.removeAll { (_, d) -> prune(d, nowMs); d.connects.isEmpty() && d.lifetimes.isEmpty() }
        }
}

/**
 * Phase 256 (FR-256-5, dev review item 4) — when a device was first seen more than one release behind the
 * deployed backend, per (device, version), in memory. The line shows only after [graceMs] (seven days) of
 * that, and never for a dev build on either side. A restart forgets it, so a freshly deployed backend says
 * nothing for a week — recorded in the spec as the price of no migration.
 */
class VersionBehindTracker(private val graceMs: Long = 7L * 24 * 3_600_000L) {
    data class Behind(val releases: Int, val sinceMs: Long)
    private val firstSeen = HashMap<String, Long>()   // "$deviceId|$version" -> first seen ≥ 2 behind

    fun observe(deviceId: String, appVersion: String?, serverVersion: String, nowMs: Long): Behind? {
        if (dev.jellystructure.shared.tv.isRaviloDevBuild(serverVersion)) return null
        val behind = dev.jellystructure.shared.tv.raviloReleasesBehind(appVersion, serverVersion) ?: return null
        val key = "$deviceId|$appVersion"
        if (behind < 2) { firstSeen.remove(key); return null }
        val since = firstSeen.getOrPut(key) { nowMs }
        return if (nowMs - since >= graceMs) Behind(behind, since) else null
    }
}
