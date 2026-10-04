package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.EVENTS_FEATURE_SESSION_CONTROL
import dev.jellystructure.shared.tv.SESSION_OPS_CONTROL
import dev.jellystructure.shared.tv.SESSION_OPS_INDEX_MOVING
import dev.jellystructure.shared.tv.SESSION_OPS_LEGACY
import dev.jellystructure.shared.tv.SessionCommandEnvelope
import dev.jellystructure.shared.tv.SessionCommandRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock

// ── R369 — any Ravilo app (and the admin, 304b) controls a session through the server ─────────────────────────────

/** Dev review item 4a — the ops checked against the session's revision: what moves the queue's place or the
 *  playhead, and every queue edit. Play, pause, stop, volume and mute never are (they cannot conflict). */
internal fun revisionChecked(op: String): Boolean =
    op in SESSION_OPS_INDEX_MOVING || op == "seek" || op.startsWith("queue_")

internal sealed interface CommandVerdict {
    data object Accept : CommandVerdict
    /** 409 `{ reason: stale, session }` — the app redraws and does nothing more. */
    data object Stale : CommandVerdict
}

/** FR-R369-1, review item 4a/b — a checked op on an old revision is stale; an unchecked one is applied anyway. */
internal fun sessionCommandVerdict(op: String, revision: Long?, s: SessionRec): CommandVerdict =
    if (revisionChecked(op) && revision != null && revision != s.revision) CommandVerdict.Stale else CommandVerdict.Accept

/** How a command reaches the session's target (FR-R369-2, review items 1, 2, 11). */
internal sealed interface CommandRoute {
    /** The target declared `session_control`: `session_command` on its socket. */
    data object SessionCommand : CommandRoute
    /** An older target: today's `playstate_command` ([command] in Jellyfin's Playstate names). */
    data class LegacyPlaystate(val command: String, val seekMs: Long? = null) : CommandRoute
    /** An older target: today's `player_command`. */
    data class LegacyPlayerCommand(val command: String, val argsJson: String? = null) : CommandRoute
    /** The receiver is in its reconnect gap: the phone that sent the cast relays it on its Cast link. */
    data object ViaSender : CommandRoute
    /** R370 — a cast session whose receiver closed: a relay app launches it again (only `play`). */
    data object RelayLoad : CommandRoute
    /** The op is not one this target obeys (absent from its `ops`). */
    data object NotOffered : CommandRoute
    /** No socket, no sender, no relay: `409 { reason: unreachable }`. */
    data object Unreachable : CommandRoute
}

/** The legacy carrier for [c], or null when the dashboard's commands have no word for it. */
internal fun legacyRoute(c: SessionCommandRequest): CommandRoute? = when (c.op) {
    "play" -> CommandRoute.LegacyPlaystate("Unpause")
    "pause" -> CommandRoute.LegacyPlaystate("Pause")
    "toggle" -> CommandRoute.LegacyPlaystate("PlayPause")
    "stop" -> CommandRoute.LegacyPlaystate("Stop")
    "seek" -> c.positionMs?.let { CommandRoute.LegacyPlaystate("Seek", it.coerceAtLeast(0)) }
    "next" -> CommandRoute.LegacyPlayerCommand("next")
    "previous" -> CommandRoute.LegacyPlayerCommand("previous")
    "set_volume" -> if (c.castDeviceId == null) c.level?.let { CommandRoute.LegacyPlayerCommand("set_volume", """{"volume":${it.coerceIn(0, 100)}}""") } else null
    "set_mute" -> if (c.castDeviceId == null) CommandRoute.LegacyPlayerCommand("mute", c.muted?.let { """{"muted":$it}""" }) else null
    else -> null
}

/**
 * FR-R369-2 — where [c] goes. [targetSocket] / [targetControl]: the target holds its events socket / declared
 * `session_control`. [senderControl]: the phone that minted the cast's code holds a `session_control` socket.
 * [relayAvailable]: R370's relay app for this place is online.
 */
internal fun commandRoute(
    c: SessionCommandRequest, targetSocket: Boolean, targetControl: Boolean, senderControl: Boolean,
    relayAvailable: Boolean = false, castTarget: Boolean = false, targetOffline: Boolean = false,
): CommandRoute = when {
    targetSocket && targetControl -> CommandRoute.SessionCommand
    targetSocket -> legacyRoute(c) ?: CommandRoute.NotOffered
    senderControl -> CommandRoute.ViaSender
    castTarget && targetOffline && c.op == "play" && relayAvailable -> CommandRoute.RelayLoad
    else -> CommandRoute.Unreachable
}

/** The ops a target obeys — the remote shows a control for these only (absent, never greyed). */
internal fun opsFor(targetControl: Boolean): List<String> = if (targetControl) SESSION_OPS_LEGACY + SESSION_OPS_CONTROL else SESSION_OPS_LEGACY

/**
 * FR-R369-3, review item 8, 304b — who may control a session: its owner, anyone in the household (same network,
 * allowed to see it) while the household switch is on, and the admin always. A session the viewer may not see is
 * never theirs to control.
 */
internal fun controllableBy(
    callerUserId: String?, admin: Boolean, s: SessionRec, householdControl: Boolean, nearby: Boolean, canSee: Boolean,
): Boolean = when {
    admin -> true
    callerUserId == null -> false
    s.ownerUserId == callerUserId -> true
    else -> householdControl && nearby && canSee
}

/** FR-R369-1 — the outcome of one command. */
internal sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Stale(val session: SessionRec) : CommandResult
    data object Unreachable : CommandResult
    data object NotOffered : CommandResult
    data object Forbidden : CommandResult
    data object NotFound : CommandResult
}

/**
 * R369 — the command service: authorises against the session (never the caller's device list, review item 8),
 * checks the revision, routes to the target (or the sender, or the relay), and writes the timeline (*from the admin*).
 * It never edits the queue itself: it forwards, and stores what the target then reports (constitution).
 */
class SessionControl(
    private val db: JellystructureDb,
    private val sessions: PlaybackSessions,
    private val devices: RaviloDeviceService,
    private val bus: TvEventBus,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val controllers = HashMap<String, MutableSet<String>>()   // sessionId → device ids
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    /** 304b (FR-304-4) — the household switch. */
    var householdControl: () -> Boolean = { false }
    /** Owner decision 1 — may this viewer see what plays (the playback rules). */
    internal var canSee: suspend (DeviceData, SessionRec) -> Boolean = { _, _ -> true }
    /** R370 — is a relay app online for this cast place (wired once R370 exists). */
    internal var relayAvailable: suspend (SessionRec) -> Boolean = { false }
    /** R370 — ask the relay app to launch the receiver again for this session (only `play` on a closed one). */
    internal var relayLoad: suspend (SessionRec) -> Boolean = { false }
    /** R372 — the Play here / Move to… / a paused speaker's Play on an offline session. */
    internal var onCommandSent: (SessionRec, SessionCommandRequest, String) -> Unit = { _, _, _ -> }

    /** Review item 7 — boot clears the table (rows are live state). */
    fun clearOnBoot() { runCatching { db.playbackSessionQueries.clearControllers() } }

    suspend fun attach(sessionId: String, device: DeviceData) {
        mutex.withLock { controllers.getOrPut(sessionId) { mutableSetOf() }.add(device.deviceId) }
        runCatching { db.playbackSessionQueries.attachController(sessionId, device.deviceId, "device", clock()) }
    }

    suspend fun detach(sessionId: String, deviceId: String) {
        mutex.withLock { controllers[sessionId]?.remove(deviceId); controllers.entries.removeAll { it.value.isEmpty() } }
        runCatching { db.playbackSessionQueries.detachController(sessionId, deviceId) }
    }

    /** A closed socket detaches everything it held (review item 7: a killed app leaves no row). */
    suspend fun detachDevice(deviceId: String) {
        mutex.withLock { controllers.values.forEach { it.remove(deviceId) }; controllers.entries.removeAll { it.value.isEmpty() } }
        runCatching { db.playbackSessionQueries.detachDevice(deviceId) }
    }

    suspend fun controllerDevices(sessionId: String): List<String> = mutex.withLock { controllers[sessionId]?.toList().orEmpty() }

    suspend fun isController(sessionId: String, deviceId: String): Boolean = mutex.withLock { controllers[sessionId]?.contains(deviceId) == true }

    /** Who may command [s]: [caller] (a device) or the admin. */
    internal suspend fun mayControl(caller: DeviceData?, admin: Boolean, s: SessionRec): Boolean {
        if (admin) return true
        val c = caller ?: return false
        val target = devices.listSessions(s.targetId).maxByOrNull { it.lastSeen }
        return controllableBy(c.jellyfinUserId, false, s, householdControl(), isNearby(c.lastPublicAddress, target?.lastPublicAddress), canSee(c, s))
    }

    /** The ops [s]'s target obeys now. */
    internal suspend fun opsOf(s: SessionRec): List<String> = opsFor(bus.hasFeature(s.targetId, EVENTS_FEATURE_SESSION_CONTROL))

    /** FR-R369-1/-2 — one command. [source] is `admin` or the caller's device id (the timeline's *from*). */
    internal suspend fun command(sessionId: String, c: SessionCommandRequest, caller: DeviceData?, admin: Boolean): CommandResult {
        val s = sessions.get(sessionId)?.takeIf { it.live } ?: return CommandResult.NotFound
        if (!mayControl(caller, admin, s)) return CommandResult.Forbidden
        if (sessionCommandVerdict(c.op, c.revision, s) == CommandVerdict.Stale) return CommandResult.Stale(s)
        val source = if (admin) "admin" else caller?.deviceId
        val targetSocket = bus.isConnected(s.targetId)
        val targetControl = targetSocket && bus.hasFeature(s.targetId, EVENTS_FEATURE_SESSION_CONTROL)
        val minter = sessions.castMinterOf(s.targetId)
        val senderControl = !targetSocket && minter != null && bus.hasFeature(minter, EVENTS_FEATURE_SESSION_CONTROL)
        val route = commandRoute(c, targetSocket, targetControl, senderControl, relayAvailable(s), s.targetKind == "cast", s.offline)
        val env = SessionCommandEnvelope(
            sessionId = s.id, command = c, source = source,
            expectItem = s.itemId.takeIf { c.op in SESSION_OPS_INDEX_MOVING },
            expectIndex = s.queueIndex.takeIf { c.op in SESSION_OPS_INDEX_MOVING && s.options.queueKnown },
            queueRev = s.options.queueRev.takeIf { c.op.startsWith("queue_") && s.options.queueKnown },
        )
        val text = json.encodeToString(SessionCommandEnvelope.serializer(), env)
        when (route) {
            CommandRoute.SessionCommand -> bus.notifySessionCommand(s.ownerUserId, s.targetId, text)
            is CommandRoute.LegacyPlaystate -> bus.notifyPlaystateCommand(s.ownerUserId, s.targetId, route.command, route.seekMs)
            is CommandRoute.LegacyPlayerCommand -> bus.notifyPlayerCommand(s.ownerUserId, s.targetId, route.command, route.argsJson)
            CommandRoute.ViaSender -> bus.notifySessionCommand(s.ownerUserId, minter!!, text)
            CommandRoute.RelayLoad -> if (!relayLoad(s)) return CommandResult.Unreachable
            CommandRoute.NotOffered -> return CommandResult.NotOffered
            CommandRoute.Unreachable -> return CommandResult.Unreachable
        }
        sessions.record(s.id, "command", source, c.op)
        onCommandSent(s, c, source ?: "")
        // Review item 12 — a stop ends the session at once, with the stopper's name (*{person} stopped it*).
        if (c.op == "stop") sessions.end(s.id, "stopped", by = caller, byName = if (admin) "admin" else null)
        return CommandResult.Accepted
    }
}
