package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.ravilo.ui.seams.GroupController
import dev.jellystructure.ravilo.ui.seams.sessionLog
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionMembersReport
import dev.jellystructure.shared.tv.SessionRoom

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
    cooldown: RoomCooldown = RoomCooldowns, now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    val room = c.castDeviceId ?: return
    // Re-adding a room that has just left the group took Play services' Cast provider down with the session (the Pixel 9
    // Pro, 10:25:37): wait until the room has been gone a while.
    if (c.op == "add_room") cooldown.remaining(room, now()).takeIf { it > 0 }?.let { ms ->
        sessionLog("R371: $room left the group ${ROOM_REJOIN_COOLDOWN_MS - ms} ms ago; adding it in $ms ms")
        wait(ms)
    }
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

/** R371 — how long a room that left the group waits before it is added again (see [applyRoomOp]). */
const val ROOM_REJOIN_COOLDOWN_MS = 10_000L

/** When each room last left this app's group (main thread only). */
class RoomCooldown {
    private val leftAt = HashMap<String, Long>()
    fun left(castDeviceId: String, atMs: Long) { leftAt[castDeviceId] = atMs }
    fun remaining(castDeviceId: String, nowMs: Long): Long =
        leftAt[castDeviceId]?.let { (ROOM_REJOIN_COOLDOWN_MS - (nowMs - it)).coerceAtLeast(0L) } ?: 0L
}
val RoomCooldowns = RoomCooldown()

/**
 * R371 (found on the Pixel 9 Pro, 10:24) — what the link holder reports of its group. Play services republishes its Cast
 * routes when it rediscovers a device, and for a moment the group's route may list the leader alone: a room that joined
 * was reported gone 3 s later. A room added (or a level) is reported at once; a room gone only once two reads in a row
 * agree; an empty read (no controller) never.
 */
class MembershipDebounce(private val confirmReads: Int = 2) {
    private var reported: List<SessionRoom>? = null
    private var pendingDrop: Set<String>? = null
    private var reads = 0
    /** The rooms confirmed gone by the last [next] that reported. */
    var lastLeft: Set<String> = emptySet()
        private set

    fun next(members: List<SessionRoom>): List<SessionRoom>? {
        lastLeft = emptySet()
        if (members.isEmpty()) return null
        val before = reported
        if (before == null) { reported = members; return members }
        if (members == before) { pendingDrop = null; return null }
        val dropped = before.map { it.castDeviceId }.toSet() - members.map { it.castDeviceId }.toSet()
        if (dropped.isEmpty()) { reported = members; pendingDrop = null; return members }
        if (pendingDrop == dropped) reads++ else { pendingDrop = dropped; reads = 1 }
        if (reads < confirmReads) return null
        reported = members; pendingDrop = null; lastLeft = dropped
        return members
    }

    fun reset() { reported = null; pendingDrop = null; reads = 0; lastLeft = emptySet() }
}

/** How soon after a room op a dropped Cast session counts as the op's doing (and is rejoined). */
const val ROOM_OP_DROP_WINDOW_MS = 15_000L
/** How long a rejoin looks for the Cast device again (Play services restarts its provider in a few seconds). */
const val REJOIN_SEARCH_MS = 30_000L

/**
 * R371 (found on the Pixel 9 Pro, 10:25:37) — the Cast session dropped right after this app changed its rooms (Play
 * services' Cast provider died; the receiver played on with no controller): rejoin it — select the device again, which
 * joins the running receiver, no LOAD. Not after this app's own *Stop*, and only while the server still has the session.
 */
fun rejoinAfterDrop(msSinceRoomOp: Long?, stoppedHere: Boolean, castDeviceId: String?, sessionLive: Boolean): Boolean =
    !stoppedHere && castDeviceId != null && sessionLive && msSinceRoomOp != null && msSinceRoomOp in 0..ROOM_OP_DROP_WINDOW_MS

/**
 * R369 (FR-R369-6) as amended by R371 — which presses wait for the session to reflect them (dim · spinner · *Can't reach*):
 * a room op does not (its answer is the rooms report, or *Couldn't add {room}*); waiting on it showed *Can't reach* for a
 * room level that never moves the session's revision.
 */
fun commandAwaitsReflection(c: SessionCommandRequest): Boolean =
    !(c.op == "add_room" || c.op == "remove_room" || ((c.op == "set_volume" || c.op == "set_mute") && c.castDeviceId != null))
