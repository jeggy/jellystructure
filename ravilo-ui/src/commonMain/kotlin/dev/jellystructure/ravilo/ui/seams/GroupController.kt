package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.SessionRoom

/**
 * R371 (review items 2, 5, 7) — growing a music cast one room at a time, and each room's own level, through the
 * platform's routing controller for this app's Cast session. Android (11+) wraps `MediaRouter2.RoutingController`
 * (`selectRoute` / `deselectRoute`; androidx's group calls are restricted); a member's level goes through its route's
 * volume (MediaRouter has no per-route mute: a mute is a level of 0, the level before it kept on the server). Null
 * where the platform cannot (the web, iPhone, the TV, a desktop until its speaker test passes, Android below 11): those
 * apps send the op to the server, which relays it.
 */
interface GroupController {
    /** The rooms this session plays on, in the order they joined, with their levels. */
    fun members(): List<SessionRoom>
    /** The speakers and displays that can join (the *Add a speaker…* list). */
    fun selectable(): List<SessionRoom>
    /** One tap adds one room; false when [castDeviceId] cannot join. */
    fun add(castDeviceId: String): Boolean
    fun remove(castDeviceId: String): Boolean
    /** 0–100. */
    fun setRoomVolume(castDeviceId: String, percent: Int): Boolean
}

/** This platform's controller, or null (see [GroupController]). */
expect fun platformGroupController(): GroupController?

/** R371 (FR-R371-2, review item 10) — what removing a room does. */
sealed interface RoomRemoval {
    /** Deselect it: the group goes on without it. */
    data class Deselect(val castDeviceId: String) : RoomRemoval
    /** The first room leads the group: dropping it is a *Move to* the room that remains (R372), a new LOAD and a stop. */
    data class MoveTo(val castDeviceId: String) : RoomRemoval
    /** The last room: removing it stops the session. */
    data object Stop : RoomRemoval
}

fun removeRoomPlan(rooms: List<SessionRoom>, castDeviceId: String): RoomRemoval {
    if (rooms.size <= 1) return RoomRemoval.Stop
    val i = rooms.indexOfFirst { it.castDeviceId == castDeviceId }
    if (i == 0) return RoomRemoval.MoveTo(rooms[1].castDeviceId)
    return RoomRemoval.Deselect(castDeviceId)
}

/** FR-R371-3 — the place line: *A* · *A + B* · *A + 2*; a room that left is named for 5 s, then dropped. */
fun placeLine(rooms: List<String>, leftRoom: String?, leftAt: Long?, nowMs: Long): Pair<String, String?> {
    val main = when {
        rooms.isEmpty() -> ""
        rooms.size == 1 -> rooms[0]
        rooms.size == 2 -> "${rooms[0]} + ${rooms[1]}"
        else -> "${rooms[0]} + ${rooms.size - 1}"
    }
    val left = leftRoom?.takeIf { leftAt != null && nowMs - leftAt < ROOM_LEFT_SHOWN_MS }
    return main to left
}

const val ROOM_LEFT_SHOWN_MS = 5_000L

/**
 * R371 (owner decisions 1–3) — who adds a speaker: an app with a [GroupController] (Android 11+, the desktop once proven)
 * that holds the session's link does it directly; every other app sends it to the server (the relay). With no app able
 * to reach the speakers the row is shown disabled with its reason.
 */
enum class AddSpeakerRoad { DIRECT, SERVER, DISABLED }

fun addSpeakerRoad(hasController: Boolean, holdsLink: Boolean, serverReaches: Boolean): AddSpeakerRoad = when {
    hasController && holdsLink -> AddSpeakerRoad.DIRECT
    serverReaches -> AddSpeakerRoad.SERVER
    else -> AddSpeakerRoad.DISABLED
}

/** FR-R371-4 — the volume rows: one slider for one place; the master *Volume* then one per room (each with its mute). */
data class VolumeRow(val castDeviceId: String?, val name: String, val level: Int?, val muted: Boolean, val enabled: Boolean)

fun volumeRows(master: Int?, masterMuted: Boolean, placeName: String, rooms: List<SessionRoom>, roomsReachable: Boolean): List<VolumeRow> {
    val first = VolumeRow(null, placeName, master, masterMuted, enabled = master != null)
    if (rooms.size <= 1) return listOf(first)
    return listOf(first) + rooms.map { VolumeRow(it.castDeviceId, it.name, it.volume, it.muted || it.volume == 0, enabled = roomsReachable && it.volume != null) }
}
