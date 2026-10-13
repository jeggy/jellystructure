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
 *
 * R380 (FR-R380-11, DEB-919) — a book's [items] are book ids and its [startMs] is **book time** (the parts before,
 * end to end, plus the place in this one), never a part's own position. `book` is accepted for [kind] as a spelling
 * of `audiobook`: it is the `plays=` token, and a sender that used it got a session nothing could play.
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
    /** R380 (found live 2026-10-09) — the device that started or moved it (*Pixel 9 Pro*), for the TV's *Playing from
     *  {device}*, as a Cast LOAD's `sender_name` does; null from an older server. */
    @SerialName("sender_name") val senderName: String? = null,
    /**
     * R380 (FR-R380-11, DEB-919) — a book: the book this session plays. [items] is the book on a start and the **part**
     * being played on a move, so the book is named here or not at all. Null for every other kind, and from a server
     * older than this (an app then resumes the book's own place, which is the server's anyway).
     */
    @SerialName("book_id") val bookId: String? = null,
    /** R380 (FR-R380-11) — a book: the place to start, in **book time**; null = this viewer's stored place. */
    @SerialName("book_ms") val bookMs: Long? = null,
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

/** What each `plays=` token promises, as session kinds. The two vocabularies differ: `book` is advertised, `audiobook`
 *  is the kind a `session_load` carries. */
private val PLAYS_KINDS: Map<String, List<String>> = mapOf(
    "video" to listOf("film", "episode"),
    "music" to listOf("music"),
    "book" to listOf("audiobook"),
)

/**
 * R380 (FR-R380-11, DEB-919) — the session kinds a `plays=` declaration promises to play (an app that declared nothing
 * plays video, as the server's own reading of `plays=` has it). A capability that outran its handler is how a TV came
 * to be offered as a book place and then dropped every book handed to it: a handler is held against this set.
 */
fun kindsPlayed(plays: String?): Set<String> =
    (plays?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: listOf("video"))
        .flatMapTo(mutableSetOf()) { PLAYS_KINDS[it].orEmpty() }

/**
 * R380 (FR-R380-11, DEB-919) — the one kind of a session, from whatever a caller spelled: `book` is the `plays=` token
 * and QA's own repro used it, so it is read as `audiobook` rather than becoming a session in the video lane that no
 * app has an arm for. Null = a kind this server does not know (the start is refused instead of failing 10 s later).
 */
fun sessionKindOf(kind: String): String? = when (kind) {
    "film", "episode", "music", "audiobook" -> kind
    "book" -> "audiobook"
    else -> null
}
