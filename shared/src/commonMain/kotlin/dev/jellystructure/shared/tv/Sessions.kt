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
