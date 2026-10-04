package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.SessionCommandEnvelope
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.sessionCommandIsStale
import dev.jellystructure.shared.tv.sessionRemoteCommand
import kotlinx.serialization.json.JsonPrimitive

// R369 — any Ravilo app controls a session: the client's pure rules.

/** What this app does with a `session_command` it received (FR-R369-2, dev review items 1 and 3). */
sealed interface SessionCommandAction {
    /** It plays here: the command goes to this app's own player, as a press would. */
    data class Local(val command: RemoteCommand) : SessionCommandAction
    /** This app sent a cast that is in its reconnect gap: the command goes over its own Cast link. */
    data class ViaLink(val command: RemoteCommand) : SessionCommandAction
    /** Meant for a queue that has moved on (review item 4c): dropped, and this app reports where it is. */
    data object Stale : SessionCommandAction
    /** An op this build does not know, or nothing to apply it to. */
    data object Ignore : SessionCommandAction
}

/**
 * Dev review items 3/4c — read one `session_command`: stale against what plays here, then local or over the link.
 * [here] — the session's target is this device.
 */
fun sessionCommandAction(
    env: SessionCommandEnvelope, here: Boolean, holdsLink: Boolean,
    currentItemId: String?, currentIndex: Int?, queueRev: Int?,
): SessionCommandAction {
    val cmd = sessionRemoteCommand(env.command) ?: return SessionCommandAction.Ignore
    if (here && sessionCommandIsStale(env, currentItemId, currentIndex, queueRev)) return SessionCommandAction.Stale
    return when {
        here -> SessionCommandAction.Local(cmd)
        holdsLink -> SessionCommandAction.ViaLink(cmd)
        else -> SessionCommandAction.Ignore
    }
}

/** Owner decision 1 (R369) — stopping someone else's playback asks every time; your own never. Pause, next and volume
 *  never ask. */
fun stopNeedsConfirm(view: SessionView): Boolean = !view.mine

/** FR-R369-6, review item 11 — a press in flight: dimmed up to 400 ms, a spinner after 1 s, *Can't reach* after 3 s with
 *  nothing reflecting it. */
enum class CommandFeedback { NONE, DIM, SPINNER, CANT_REACH }

fun commandFeedback(sentAtMs: Long?, nowMs: Long, reflected: Boolean): CommandFeedback {
    val at = sentAtMs ?: return CommandFeedback.NONE
    if (reflected) return CommandFeedback.NONE
    val waited = nowMs - at
    return when {
        waited < 400 -> CommandFeedback.DIM
        waited < 1_000 -> CommandFeedback.NONE
        waited < 3_000 -> CommandFeedback.SPINNER
        else -> CommandFeedback.CANT_REACH
    }
}

/** FR-R369-5 — the bar's ⏯ and next exist only for a session the server says this app controls. */
fun barControlsShown(view: SessionView): Boolean = view.controllable && view.state != "ended"

/** R370 (owner decision 1) — the `cast_devices_seen` frame; a group is never reported (FR-R370-1). */
fun castDevicesSeenFrame(devices: List<dev.jellystructure.shared.tv.CastSeenDevice>): String {
    val list = devices.filter { it.kind != "group" }.sortedBy { it.castDeviceId }
    return "{\"type\":\"cast_devices_seen\",\"devices\":" +
        kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(dev.jellystructure.shared.tv.CastSeenDevice.serializer()), list) + "}"
}

/** R370 — this app's Cast routes as it reports them (the route's id is the device's stable key, review item 3). */
fun castSeenOf(routes: List<dev.jellystructure.ravilo.ui.seams.CastRoute>): List<dev.jellystructure.shared.tv.CastSeenDevice> =
    routes.filter { it.kind != "group" }.map { dev.jellystructure.shared.tv.CastSeenDevice(it.deviceKey, it.name, if (it.kind == "speaker") "speaker" else "display") }

/** R369 — the `attach_session` / `detach_session` frames (dev review item 7). */
fun attachFrame(id: String): String = "{\"type\":\"attach_session\",\"id\":${JsonPrimitive(id)}}"
fun detachFrame(id: String): String = "{\"type\":\"detach_session\",\"id\":${JsonPrimitive(id)}}"
