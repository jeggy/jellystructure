package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// R368 — every playback is a session on the server, and every Ravilo app sees them. New paths, new types and new
// event names only (R319: nothing existing changes shape). The events go only to a socket that opened with
// `features=sessions` (dev review item 2): an installed app reads any unknown event as "the config changed".

/** R368 (dev review item 2) — the `features=` value a socket sends to receive `session_list` / `session_state`. */
const val EVENTS_FEATURE_SESSIONS = "sessions"

/** R369 (dev review item 2) — a target that obeys `session_command` declares this beside [EVENTS_FEATURE_SESSIONS]. */
const val EVENTS_FEATURE_SESSION_CONTROL = "session_control"

/**
 * R371 (review items 7 and 8) — an app whose platform can grow a Cast session room by room (Android 11+'s routing
 * controller) declares this; the server sends a room op only to such an app — the one holding the session's Cast link,
 * else a relay app on that network, which joins the session's Cast device first.
 */
const val EVENTS_FEATURE_GROUP_CONTROL = "group_control"

/** Whose playback a row is. [name] is the first name the household knows them by. */
@Serializable
data class SessionOwner(val id: String, val name: String)

/**
 * Where it plays. [kind] is `app` (a Ravilo device), `cast` (a receiver) or `cast_group` (R371); [icon] is one of
 * `phone · computer · tv · display · speaker · group` (dev review item 15).
 */
@Serializable
data class SessionTarget(
    val kind: String,
    val id: String,
    val name: String,
    val icon: String,
    /** R370 (review item 3) — a Cast place's own device id (the key the apps' discovery shares), when known. */
    @SerialName("cast_device_id") val castDeviceId: String? = null,
)

/** R371 — one room of a session that plays on several speakers, as the linked app reported it. */
@Serializable
data class SessionRoom(
    @SerialName("cast_device_id") val castDeviceId: String,
    val name: String,
    /** 0–100; null = the room does not report its volume (FR-R371-4: *—*, slider disabled). */
    val volume: Int? = null,
    val muted: Boolean = false,
)

/**
 * R368 (FR-R368-5) — one session as THIS viewer sees it (built per socket: [mine], [here] and [controllable] differ
 * per viewer). A session the viewer may not see (owner decision 1) has no [title], [subtitle], [artwork],
 * [positionMs] or [durationMs]: the person, the place and the state only.
 *
 * The moving position is drawn from [positionMs] + the server time since [positionAt] while [state] is `playing`
 * (dev review item 9); the envelopes carry `server_now_ms` so a device with a wrong clock still draws it right.
 */
@Serializable
data class SessionView(
    val id: String,
    val revision: Long,
    val owner: SessionOwner,
    val mine: Boolean = false,
    /** `film` · `episode` · `music` · `audiobook` */
    val kind: String,
    val title: String? = null,
    /** artist · album | `S01E05` | chapter — facts the app words, never a sentence. */
    val subtitle: String? = null,
    val artwork: String? = null,
    val target: SessionTarget,
    /** `starting` · `playing` · `paused` · `buffering` · `failed` · `ended` */
    val state: String,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("position_at") val positionAt: Long = 0L,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** This device is the target. */
    val here: Boolean = false,
    /** The server's answer (R369 FR-R369-3): the app never works it out itself. */
    val controllable: Boolean = false,
    /** After a backend restart, until the target reports again (dev review item 12: a flag, not a seventh state). */
    val reconnecting: Boolean = false,
    /** R372 (FR-R372-4) — the place went silent: paused there, kept 24 h, *Play here* / *Move to…* offered. */
    val offline: Boolean = false,
    /** Why it ended (`stopped` · `replaced` · `finished` · `failed` · `idle` · `no_return_after_restart` · `watchdog`). */
    @SerialName("end_reason") val endReason: String? = null,
    /** R369 (dev review item 12) — the first name of whoever stopped it, for *{person} stopped it*. */
    @SerialName("ended_by") val endedBy: String? = null,
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L,
    /** R371 — the rooms of a `cast_group`, in the order they joined; empty for one place. */
    val rooms: List<SessionRoom> = emptyList(),
    /** R372 — a move in flight: the place it goes to, or the place it could not go to (`move_failed`). */
    @SerialName("moving_to") val movingTo: String? = null,
    @SerialName("move_failed") val moveFailed: String? = null,
    /** R371 (FR-R371-3) — a room that left on its own, and when: the place line says *{room} left* for 5 s. */
    @SerialName("left_room") val leftRoom: String? = null,
    @SerialName("left_at") val leftAt: Long? = null,
)

/** `GET /api/tv/playback/sessions` — the whole list for this viewer. */
@Serializable
data class SessionList(
    val sessions: List<SessionView> = emptyList(),
    val revision: Long = 0L,
    @SerialName("server_now_ms") val serverNowMs: Long = 0L,
)

/** `{"type":"session_list", …}` — on connect and whenever a session starts or ends. */
@Serializable
data class SessionListEnvelope(
    val type: String = "session_list",
    val sessions: List<SessionView> = emptyList(),
    val revision: Long = 0L,
    @SerialName("server_now_ms") val serverNowMs: Long = 0L,
)

/** `{"type":"session_state", …}` — one session changed (a state, an item, a seek, an option, reconnecting). */
@Serializable
data class SessionStateEnvelope(
    val type: String = "session_state",
    val session: SessionView,
    @SerialName("server_now_ms") val serverNowMs: Long = 0L,
)

/** R369/R370 — the session directives an opted-in socket may receive besides the list and the state. */
val SESSION_DIRECTIVES: Set<String> = setOf("session_detail", "session_load", "cast_relay_load", "targets_changed")

/** R368 (dev review item 2) — the `features=` list for an events socket; null = today's URL exactly. */
fun eventsFeaturesQuery(features: Set<String>): String? =
    features.filter { it.isNotBlank() }.sorted().takeIf { it.isNotEmpty() }?.joinToString(",")

// ── R369 — any Ravilo app controls a session ─────────────────────────────────────────────────────────────────────────

/** Ops every target obeys, through today's `playstate_command` / `player_command` when it did not declare
 *  `session_control` (dev review item 2). */
val SESSION_OPS_LEGACY: List<String> = listOf("play", "pause", "seek", "next", "previous", "stop", "set_volume", "set_mute")

/** Ops only a `session_control` target obeys; absent from `SessionDetail.ops` otherwise (never shown broken). */
val SESSION_OPS_CONTROL: List<String> = listOf("jump", "set_shuffle", "set_repeat", "set_audio", "set_subtitle",
    "queue_add", "queue_play_next", "queue_move", "queue_remove")

/** `POST /api/tv/playback/sessions/{id}/command` (and the admin's own route). [revision] is the session's as the app
 *  last saw it; an index-moving op or a seek on an old one is refused with 409 (dev review item 4a). */
@Serializable
data class SessionCommandRequest(
    val revision: Long? = null,
    val op: String,
    @SerialName("position_ms") val positionMs: Long? = null,
    val index: Int? = null,
    val to: Int? = null,
    val on: Boolean? = null,
    val mode: String? = null,
    /** 0–100. */
    val level: Int? = null,
    val muted: Boolean? = null,
    @SerialName("track_id") val trackId: String? = null,
    /** R371 — one room of a group (`set_volume` / `set_mute` / `remove_room`), or the speaker to add (`add_room`). */
    @SerialName("cast_device_id") val castDeviceId: String? = null,
)

/**
 * `{"type":"session_command", …}` to the session's target (only a socket that declared `session_control`). The target
 * checks [expectItem] (the item the server last knew was playing) / [expectIndex] / [queueRev] and drops a command
 * meant for a queue that has moved on — the second of two `next`s sent on two paths skips nothing (dev review item 4c).
 */
@Serializable
data class SessionCommandEnvelope(
    val type: String = "session_command",
    @SerialName("session_id") val sessionId: String,
    val command: SessionCommandRequest,
    @SerialName("expect_item") val expectItem: String? = null,
    @SerialName("expect_index") val expectIndex: Int? = null,
    @SerialName("queue_rev") val queueRev: Int? = null,
    /** `admin` or the sending device's id. */
    val source: String? = null,
    /** R371 — a room op's session's Cast device (its leader), so a relay app that holds no link can join it first. */
    @SerialName("place_cast_device_id") val placeCastDeviceId: String? = null,
)

/** What a session command asks of a player, as the dashboard's own commands are read ([RemoteCommand]); null for an
 *  op no player acts on (a newer server's op is dropped, never a crash). */
fun sessionRemoteCommand(c: SessionCommandRequest): RemoteCommand? = when (c.op) {
    "play" -> RemoteCommand.Play
    "pause" -> RemoteCommand.Pause
    "toggle" -> RemoteCommand.Toggle
    "stop" -> RemoteCommand.Stop
    "next" -> RemoteCommand.Next
    "previous" -> RemoteCommand.Previous
    "seek" -> c.positionMs?.let { RemoteCommand.SeekTo(it.coerceAtLeast(0L)) }
    "jump" -> c.index?.let { RemoteCommand.Jump(it) }
    "set_shuffle" -> c.on?.let { RemoteCommand.SetShuffle(it) }
    "set_repeat" -> c.mode?.let { RemoteCommand.SetRepeat(it) }
    "set_audio" -> c.index?.let { RemoteCommand.SelectAudio(it) }
    "set_subtitle" -> c.index?.let { RemoteCommand.SelectSubtitle(it) }
    "set_volume" -> if (c.castDeviceId == null) c.level?.let { RemoteCommand.SetVolume(it.coerceIn(0, 100)) } else null
    "set_mute" -> if (c.castDeviceId == null) RemoteCommand.Mute(c.muted) else null
    "queue_add" -> c.trackId?.let { RemoteCommand.QueueAdd(it) }
    "queue_play_next" -> c.trackId?.let { RemoteCommand.QueuePlayNext(it) }
    "queue_move" -> if (c.index != null && c.to != null) RemoteCommand.QueueMove(c.index, c.to) else null
    "queue_remove" -> c.index?.let { RemoteCommand.QueueRemove(it) }
    else -> null
}

/** Ops that move the queue's place: checked against the session's revision and, at the target, its current item. */
val SESSION_OPS_INDEX_MOVING: Set<String> = setOf("next", "previous", "jump")

/**
 * Dev review item 4c — at the target: is this command meant for a queue that has already moved on? An index-moving op
 * whose [SessionCommandEnvelope.expectItem] / [SessionCommandEnvelope.expectIndex] is not what plays now, or a queue
 * edit on another [SessionCommandEnvelope.queueRev], is dropped (the target then reports where it is).
 */
fun sessionCommandIsStale(env: SessionCommandEnvelope, currentItemId: String?, currentIndex: Int?, queueRev: Int?): Boolean {
    val op = env.command.op
    if (op in SESSION_OPS_INDEX_MOVING) {
        if (env.expectItem != null && currentItemId != null && env.expectItem != currentItemId) return true
        if (env.expectIndex != null && currentIndex != null && env.expectIndex != currentIndex) return true
    }
    if (op.startsWith("queue_") && env.queueRev != null && queueRev != null && env.queueRev != queueRev) return true
    return false
}

/**
 * R369 (dev review item 3) — a session command as the receiver's own sender command ([CastCommand]): one handler
 * serves both paths, so a command can't mean two things. Null for the ops the receiver reads as a [RemoteCommand]
 * (play, pause, seek, stop, volume) or does not know.
 */
fun castCommandForSession(c: SessionCommandRequest, music: Boolean): CastCommand? = when (c.op) {
    "next" -> CastCommand("next")
    "previous" -> if (music) CastCommand("prev") else null
    "jump" -> c.index?.let { CastCommand("play_at", index = it) }
    "set_shuffle" -> CastCommand("shuffle", on = c.on == true)
    "set_repeat" -> CastCommand("repeat", mode = c.mode ?: "off")
    "set_audio" -> c.index?.let { CastCommand("audio", index = it) }
    "set_subtitle" -> CastCommand("subtitle", index = c.index ?: -1)
    "queue_move" -> if (c.index != null && c.to != null) CastCommand("queue_move", index = c.index, to = c.to) else null
    "queue_remove" -> c.index?.let { CastCommand("queue_remove", index = it) }
    else -> null
}

/**
 * R371 (review item 5) — the rooms of a Cast group as the app holding the session's link sees them, on change only:
 * `POST /api/tv/playback/sessions/members`.
 */
@Serializable
data class SessionMembersReport(
    @SerialName("item_id") val itemId: String,
    val members: List<SessionRoom> = emptyList(),
)

/** R371 — the ops on one room of a group (they go to the app holding the session's Cast link, or a relay app). */
val SESSION_OPS_ROOM: Set<String> = setOf("add_room", "remove_room")

/** One entry of a session's queue as the remote lists it. */
@Serializable
data class SessionQueueEntry(val id: String, val title: String? = null, val subtitle: String? = null)

/**
 * R369 (dev review item 5) — an app or a receiver reports its queue to the server **on change only** (never every
 * tick), ids only: `POST /api/tv/playback/sessions/queue`. Tracks and the picks ride it for a film.
 */
@Serializable
data class SessionQueueReport(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("item_id") val itemId: String,
    val queue: List<String> = emptyList(),
    @SerialName("queue_rev") val queueRev: Int = 0,
    @SerialName("queue_index") val queueIndex: Int = 0,
    val shuffle: Boolean? = null,
    val repeat: String? = null,
    @SerialName("audio_tracks") val audioTracks: List<CastTrack> = emptyList(),
    @SerialName("subtitle_tracks") val subtitleTracks: List<CastTrack> = emptyList(),
    @SerialName("audio_index") val audioIndex: Int? = null,
    @SerialName("subtitle_index") val subtitleIndex: Int? = null,
    /** R371 — the rooms of a Cast group, as the app holding the link sees them. */
    val members: List<SessionRoom>? = null,
)

/** `GET /api/tv/playback/sessions/{id}` — what a remote needs beyond the row (dev review item 5). The queue is a window
 *  around [queueIndex] ([queueOffset] is where it starts, [queueSize] the whole length), as R359 windows a LOAD. */
@Serializable
data class SessionDetail(
    val session: SessionView,
    val queue: List<SessionQueueEntry> = emptyList(),
    @SerialName("queue_offset") val queueOffset: Int = 0,
    @SerialName("queue_size") val queueSize: Int = 0,
    @SerialName("queue_rev") val queueRev: Int = 0,
    @SerialName("queue_index") val queueIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    @SerialName("audio_tracks") val audioTracks: List<CastTrack> = emptyList(),
    @SerialName("subtitle_tracks") val subtitleTracks: List<CastTrack> = emptyList(),
    @SerialName("audio_index") val audioIndex: Int? = null,
    @SerialName("subtitle_index") val subtitleIndex: Int? = null,
    /** 0–100; null = the place does not report its volume. */
    val volume: Int? = null,
    val muted: Boolean = false,
    /** The ops the target obeys — a control for any other op is absent, not greyed. */
    val ops: List<String> = emptyList(),
)

/** `{"type":"session_detail", …}` — only to the session's attached controllers (dev review item 5). */
@Serializable
data class SessionDetailEnvelope(
    val type: String = "session_detail",
    val detail: SessionDetail,
    @SerialName("server_now_ms") val serverNowMs: Long = 0L,
)

/** A command refused: `409 { reason, session }` — `stale` (an old revision) or `unreachable` (FR-R369-2). */
@Serializable
data class SessionCommandRefusal(val reason: String, val session: SessionView? = null)
