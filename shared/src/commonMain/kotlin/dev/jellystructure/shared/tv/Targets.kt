package dev.jellystructure.shared.tv

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// R370 — one *Play on…* list: start anywhere. New paths, new types, new events (to `features=sessions` sockets only).

/** What a place can play (review item 6: from what the app declares in `plays=`, a receiver's from its platform). */
@Serializable
data class TargetCapabilities(
    val video: Boolean = false,
    val audio: Boolean = false,
    val display: Boolean = false,
    val book: Boolean = false,
)

/**
 * One single place (FR-R370-1 — never a group, *whole house* or *everywhere*). [kind]: `app` (a Ravilo device holding
 * its events socket), `cast` (a Cast device a Ravilo app on this network sees, or a receiver seen in the last 24 h),
 * `screen` (a paired screen). [busy] is what plays there (as this viewer may see it). [reachable] false = *Not
 * reachable* (dimmed, cannot be picked) with [reason].
 */
@Serializable
data class PlaybackTarget(
    val id: String,
    val kind: String,
    val name: String,
    /** `phone · computer · tv · display · speaker` */
    val icon: String,
    val capabilities: TargetCapabilities = TargetCapabilities(),
    val busy: SessionView? = null,
    val reachable: Boolean = true,
    /** This device itself (tier 1). */
    val here: Boolean = false,
    /** A Cast place's stable key — the same string the app's own discovery reports (review item 3). */
    @SerialName("cast_device_id") val castDeviceId: String? = null,
    /** Why it is not reachable: `no_relay` (no Ravilo app on its network sees it) · `offline`. */
    val reason: String? = null,
    @SerialName("last_seen") val lastSeen: Long? = null,
)

/** `GET /api/tv/playback/targets`. */
@Serializable
data class TargetList(
    val targets: List<PlaybackTarget> = emptyList(),
    @SerialName("server_now_ms") val serverNowMs: Long = 0L,
)

/** R370 (review item 10) — replacing what plays on a busy place. */
@Serializable
data class SessionReplace(
    @SerialName("session_id") val sessionId: String,
    val revision: Long? = null,
)

/**
 * `POST /api/tv/playback/sessions` (FR-R370-3) — start [items] (in play order) at [index] on [targetId]. [kind] is
 * `film` · `episode` · `music` · `audiobook`; [startMs] null = the server's resume point.
 */
@Serializable
data class SessionStartRequest(
    @SerialName("target_id") val targetId: String,
    val kind: String,
    val items: List<String>,
    val index: Int = 0,
    @SerialName("start_ms") val startMs: Long? = null,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    val replace: SessionReplace? = null,
    /** R372 — this start is a move of [moveOf] (the session keeps its id). */
    @SerialName("move_of") val moveOf: String? = null,
)

/**
 * The answer: the session in `starting`, and [loadHere] when the caller must do the Cast LOAD itself (an Android or
 * desktop app starting on a Cast device its own discovery sees — review item 1; it mints the hand-off with
 * [castDeviceId] and the session id, so the receiver joins the row).
 */
@Serializable
data class SessionStartResponse(
    val session: SessionView? = null,
    @SerialName("load_here") val loadHere: Boolean = false,
    @SerialName("cast_device_id") val castDeviceId: String? = null,
)

/**
 * `{"type":"session_load", …}` to an app target (review item 8): the `play_item` envelope's facts plus the session
 * and, for music, the queue (ids in play order, the index, the place in the song, the modes).
 */
@Serializable
data class SessionLoadEnvelope(
    val type: String = "session_load",
    @SerialName("session_id") val sessionId: String,
    val kind: String,
    val items: List<String> = emptyList(),
    val index: Int = 0,
    @SerialName("start_ms") val startMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    val title: String? = null,
    /** Music: the queue's facts (title, artist, cover …), so the app plays it without asking for each song. */
    val tracks: List<CastTrackItem> = emptyList(),
)

/**
 * `{"type":"cast_relay_load", …}` (owner decision 1) — asks a connected Android or desktop Ravilo app on the
 * speaker's network to launch the receiver on [castDeviceId] with [load] (its hand-off code minted for the person
 * who started it). The relaying app then drops its Cast link: it never becomes the session's controller and never
 * hands anything back (R353).
 */
@Serializable
data class CastRelayLoadEnvelope(
    val type: String = "cast_relay_load",
    @SerialName("session_id") val sessionId: String,
    @SerialName("cast_device_id") val castDeviceId: String,
    val load: CastLoadData,
)

/**
 * R372 (FR-R372-2/-3) — `POST /api/tv/playback/sessions/{id}/move`: the session moves to [targetId] (a place from the
 * list, or `here` — *Play here*: the calling device). It keeps its id; the new place starts 2 s back; the old one stops
 * once the new one plays. The answer is a [SessionStartResponse] (`load_here` when the caller must do the Cast LOAD).
 */
@Serializable
data class SessionMoveRequest(
    @SerialName("target_id") val targetId: String,
    val revision: Long? = null,
)

/** One Cast device an app's own discovery sees, as it reports it (`cast_devices_seen` on the events socket). */
@Serializable
data class CastSeenDevice(
    @SerialName("cast_device_id") val castDeviceId: String,
    val name: String,
    /** `display` · `speaker` (a group is never reported — FR-R370-1). */
    val kind: String = "display",
)

/** R370 (review item 3) — the optional body of `POST /api/tv/cast/handoff`; no body = today's hand-off. */
@Serializable
data class CastHandoffRequest(
    @SerialName("cast_device_id") val castDeviceId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

/** Review item 6 — the `plays=` an app declares on its events socket. */
fun playsQuery(video: Boolean, music: Boolean, book: Boolean): String? =
    listOfNotNull("video".takeIf { video }, "music".takeIf { music }, "book".takeIf { book }).takeIf { it.isNotEmpty() }?.joinToString(",")
