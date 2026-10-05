package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.ravilo.ui.seams.GroupController
import dev.jellystructure.ravilo.ui.seams.sessionLog
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionMembersReport

/** How long an added room has to show among the group's members before the add counts as failed. */
const val ROOM_ADD_CONFIRM_MS = 6_000L
private const val ROOM_ADD_POLL_MS = 500L

/**
 * R371 (FR-R371-2/-5; found on the Pixel 9 Pro) — one room op on this app's routing controller, then the report every
 * controller reads: the rooms, which speakers can join, and — for an add that the controller refused or that never
 * showed up among the members — `failed`, so every remote says *Couldn't add {room}* instead of waiting into
 * *Can't reach {place}*. [nameOf] names a Cast device for that line.
 */
suspend fun applyRoomOp(
    g: GroupController, c: SessionCommandRequest, itemId: String?, nameOf: (String) -> String,
    report: (SessionMembersReport) -> Unit, wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    val room = c.castDeviceId ?: return
    fun send(failed: String? = null) {
        val item = itemId ?: return
        val members = runCatching { g.members() }.getOrDefault(emptyList())
        val selectable = runCatching { g.selectable() }.getOrNull()
        report(SessionMembersReport(item, members, selectable, failed))
    }
    when (c.op) {
        "add_room" -> {
            val ok = g.add(room)
            sessionLog("R371: add_room $room → ${if (ok) "selected, waiting for it to join" else "refused"}")
            if (!ok) { send(failed = nameOf(room)); return }
            var waited = 0L
            while (waited < ROOM_ADD_CONFIRM_MS) {
                if (runCatching { g.members() }.getOrDefault(emptyList()).any { it.castDeviceId == room }) { sessionLog("R371: $room joined"); send(); return }
                wait(ROOM_ADD_POLL_MS); waited += ROOM_ADD_POLL_MS
            }
            sessionLog("R371: $room never joined the group in ${ROOM_ADD_CONFIRM_MS / 1000} s")
            send(failed = nameOf(room))
        }
        "remove_room" -> { val ok = g.remove(room); sessionLog("R371: remove_room $room → $ok"); send() }
        "set_volume" -> { val ok = c.level?.let { g.setRoomVolume(room, it) }; sessionLog("R371: room $room level ${c.level} → $ok"); send() }
        else -> send()
    }
}

/**
 * R369 (FR-R369-6) as amended by R371 — which presses wait for the session to reflect them (dim · spinner · *Can't reach*):
 * a room op does not (its answer is the rooms report, or *Couldn't add {room}*); waiting on it showed *Can't reach* for a
 * room level that never moves the session's revision.
 */
fun commandAwaitsReflection(c: SessionCommandRequest): Boolean =
    !(c.op == "add_room" || c.op == "remove_room" || ((c.op == "set_volume" || c.op == "set_mute") && c.castDeviceId != null))
