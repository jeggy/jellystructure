package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Playback_session
import dev.jellystructure.log.Logger
import dev.jellystructure.shared.tv.EVENTS_FEATURE_SESSIONS
import dev.jellystructure.shared.tv.EVENTS_FEATURE_SESSION_CONTROL
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionRoom
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

// ── R368 — every playback is a session on the server ──────────────────────────────────────────────────────────────
//
// The session sits ABOVE PlaybackTracker (dev review item 4: fold nothing). The tracker stays per (device, item),
// because that is what Jellyfin, phase 180's encode release and 178's deferral need; a session is one per (target,
// lane) and survives song boundaries, episode auto-advance and a backend restart.

/** Kinds of a session (FR-R368-1). */
object SessionKind {
    const val FILM = "film"
    const val EPISODE = "episode"
    const val MUSIC = "music"
    const val AUDIOBOOK = "audiobook"
}

/** States (FR-R368-1). `reconnecting` and `offline` are flags beside these, never a state (dev review item 12). */
object SessionState {
    const val STARTING = "starting"
    const val PLAYING = "playing"
    const val PAUSED = "paused"
    const val BUFFERING = "buffering"
    const val FAILED = "failed"
    const val ENDED = "ended"
}

/** Review item 5 — `video` for a film or an episode, `audio` for music and books. */
internal fun sessionLane(kind: String): String =
    if (kind == SessionKind.MUSIC || kind == SessionKind.AUDIOBOOK) "audio" else "video"

/** Review item 5 — what a start does to the live session on its (target, lane). */
internal sealed interface StartDecision {
    data object Create : StartDecision
    data class Join(val sessionId: String) : StartDecision
    data class Replace(val sessionId: String) : StartDecision
}

internal fun startDecision(live: SessionRec?, ownerUserId: String): StartDecision = when {
    live == null || live.endedAt != null -> StartDecision.Create
    live.ownerUserId == ownerUserId -> StartDecision.Join(live.id)
    else -> StartDecision.Replace(live.id)
}

/** Review item 9 — a report that is only the clock moving on is not a change (no revision bump, no push). */
internal fun isSessionChange(stored: SessionRec, itemId: String, positionMs: Long, paused: Boolean, nowMs: Long): Boolean {
    if (stored.itemId != itemId) return true
    val wasPlaying = stored.state == SessionState.PLAYING
    if (paused == wasPlaying) return true
    if (stored.state != SessionState.PLAYING && stored.state != SessionState.PAUSED) return true
    val expected = stored.positionMs + if (wasPlaying) (nowMs - stored.positionAt).coerceAtLeast(0L) else 0L
    return kotlin.math.abs(positionMs - expected) > SEEK_TOLERANCE_MS
}

private const val SEEK_TOLERANCE_MS = 3_000L

/** Review item 15 — the place's icon from what its device row knows. */
internal fun targetIcon(platform: String?, kind: String, smallScreen: Boolean = false): String = when {
    kind == "cast" && platform == CastService.AUDIO_PLATFORM -> "speaker"
    platform == CastService.AUDIO_PLATFORM -> "speaker"
    kind == "cast" -> if (smallScreen) "display" else "tv"
    kind == "cast_group" -> "group"
    platform == "phone" || platform == "android-phone" || platform == "ios" || kind == "phone" -> "phone"
    platform == "mac" || platform == "linux" || platform == "web" || kind == "web" -> "computer"
    else -> "tv"
}

/** Review item 12 (a) — the watchdog's test: a reconnecting session is judged by heartbeat alone. */
internal fun shouldForceStop(heartbeatStale: Boolean, needsSocket: Boolean, socketOpen: Boolean, reconnecting: Boolean): Boolean =
    heartbeatStale || (!reconnecting && needsSocket && !socketOpen)

/** Review item 2 — the `features=` an events socket opted into; only the known ones are kept. */
internal fun parseEventFeatures(raw: String?): Set<String> =
    raw.orEmpty().split(',').map { it.trim().lowercase() }.filter { it in KNOWN_FEATURES }.toSet()

private val KNOWN_FEATURES = setOf(EVENTS_FEATURE_SESSIONS, EVENTS_FEATURE_SESSION_CONTROL, dev.jellystructure.shared.tv.EVENTS_FEATURE_GROUP_CONTROL)   // R371 — group_control

/** R370 (review item 6) — what an app declares it plays (`plays=video,music,book`), kept beside its features. */
internal fun parsePlays(raw: String?): Set<String> =
    raw.orEmpty().split(',').map { it.trim().lowercase() }.filter { it in setOf("video", "music", "book") }.map { "plays:$it" }.toSet()

/** One item of a session's queue: facts the apps word, never a sentence. */
@Serializable
internal data class SessionItem(
    val id: String,
    val title: String? = null,
    val subtitle: String? = null,
    val artwork: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

/**
 * What a session keeps beside its queue (`options_json`): R343's plan (so Start over and a shuffle survive a restart,
 * review item 12 c), the tracker's direct-play flag (to rebuild it, 12 b), music's shuffle/repeat, R371's volume and
 * rooms, and the level each room had before a mute.
 */
@Serializable
internal data class StoredPlan(
    @SerialName("start_over_series_id") val startOverSeriesId: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0L,
    @SerialName("credits_start_ms") val creditsStartMs: Long? = null,
    val shuffle: Boolean = false,
    @SerialName("prior_position_ms") val priorPositionMs: Long = 0L,
    @SerialName("prior_last_played") val priorLastPlayed: String? = null,
    @SerialName("watched_at_start") val watchedAtStart: Boolean = false,
    @SerialName("anchor_last_played") val anchorLastPlayed: String? = null,
) {
    fun toPlan() = SessionPlan(startOverSeriesId, durationMs, creditsStartMs, shuffle, priorPositionMs, priorLastPlayed, watchedAtStart, anchorLastPlayed)
    companion object {
        fun of(p: SessionPlan) = StoredPlan(p.startOverSeriesId, p.durationMs, p.creditsStartMs, p.shuffle, p.priorPositionMs, p.priorLastPlayed, p.watchedAtStart, p.anchorLastPlayed)
    }
}

@Serializable
internal data class SessionOptions(
    val shuffle: Boolean = false,
    val repeat: String? = null,
    /** R343 / R375 — the current item's [SessionPlan], whole (review item 12 c). */
    val plan: StoredPlan? = null,
    @SerialName("direct_play") val directPlay: Boolean = false,
    val volume: Int? = null,
    val muted: Boolean? = null,
    /** R369 (dev review item 5) — the queue as the target reported it, ids only, and its revision. */
    @SerialName("queue_ids") val queueIds: List<String> = emptyList(),
    @SerialName("queue_rev") val queueRev: Int = 0,
    @SerialName("audio_tracks") val audioTracks: List<dev.jellystructure.shared.tv.CastTrack> = emptyList(),
    @SerialName("subtitle_tracks") val subtitleTracks: List<dev.jellystructure.shared.tv.CastTrack> = emptyList(),
    @SerialName("audio_index") val audioIndex: Int? = null,
    @SerialName("subtitle_index") val subtitleIndex: Int? = null,
    /** Did the target report its queue (so `expect_index` / `queue_rev` mean something)? */
    @SerialName("queue_known") val queueKnown: Boolean = false,
    val rooms: List<SessionRoom> = emptyList(),
    @SerialName("room_levels_before_mute") val roomLevelsBeforeMute: Map<String, Int> = emptyMap(),
    /** R371 — the speakers the link holder's routing controller says can join (null = not reported). */
    val addable: List<SessionRoom>? = null,
    /**
     * A Cast session's device and the app holding its link, kept with the row so a restart keeps them (found on the
     * Pixel 9 Pro, 2026-10-05: after a backend deploy, *Add a speaker…* said no phone nearby could reach the speaker until
     * a new cast). Written by persist, read back by restore; the live values are [SessionRec.castDeviceId] and the
     * service's link-holder map.
     */
    @SerialName("cast_device_id") val castDeviceId: String? = null,
    @SerialName("link_holder") val linkHolder: String? = null,
) {
    fun plan(): SessionPlan? = plan?.toPlan()
    fun withPlan(p: SessionPlan?): SessionOptions = if (p == null) this else copy(plan = StoredPlan.of(p))
}

/** One session, as the server holds it. The `memory only` fields are live state that a restart rebuilds. */
internal data class SessionRec(
    val id: String,
    val ownerUserId: String,
    val ownerName: String,
    val targetKind: String,
    val targetId: String,
    val targetName: String,
    val lane: String,
    val kind: String,
    val itemId: String,
    val bookId: String?,
    val queue: List<SessionItem>,
    val queueIndex: Int,
    val positionMs: Long,
    val positionAt: Long,
    val state: String,
    val options: SessionOptions,
    val revision: Long,
    val startedByDeviceId: String?,
    val jellyfinPlaySessionId: String?,
    val offline: Boolean,
    val endReason: String?,
    val endedBy: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val endedAt: Long?,
    // ── memory only ──
    val reconnecting: Boolean = false,
    /** Review item 6 — a stop holds the session this long for the next song / part / episode. */
    val stopHoldUntil: Long? = null,
    /** FR-R368-4 — a session restored at boot ends (or, with R372, goes offline) if its target is silent past this. */
    val reconnectDeadline: Long? = null,
    /** R372 — a move in flight / failed. */
    val movingTo: String? = null,
    val moveFailed: String? = null,
    /** R370 (review item 8) — a load that brings no report by this time ends `failed`. */
    val loadDeadline: Long? = null,
    /** R370 — a Cast place's stable key (the app's discovery id), when known. */
    val castDeviceId: String? = null,
    /** R371 (FR-R371-3) — a room that left on its own, and when. */
    val leftRoom: String? = null,
    val leftAt: Long? = null,
    /** R371 — a room that could not be added, and when (*Couldn't add {room}*). */
    val roomFailed: String? = null,
    val roomFailedAt: Long? = null,
) {
    val live: Boolean get() = endedAt == null
    // The item that plays, by id: a queue report moves [queueIndex] into the app's whole queue while [queue] may hold only the
    // songs the server has seen, and an index alone then named nothing (no title after Play here or a song boundary).
    val current: SessionItem? get() = queue.getOrNull(queueIndex)?.takeIf { it.id == itemId } ?: queue.firstOrNull { it.id == itemId } ?: queue.getOrNull(queueIndex)
}

/** Who can be shown what: the per-socket projection's inputs (owner decisions 1, 2 and 4). */
internal data class SessionViewer(val device: DeviceData, val address: String?, val householdControl: Boolean = false)

/**
 * FR-R368-6, review items 10/11, owner decisions 1, 2, 4 — one session as one viewer sees it, or null when it is not
 * listed to them. A viewer's own sessions are always listed; another member's only when the playing device and the
 * viewer's device share a public address (236's `nearby` rule); a title the viewer may not see shows person, place
 * and state only. [castMintedBy] is the device that minted the hand-off the receiver redeemed (FR-R368-9).
 */
internal fun sessionViewFor(
    viewer: SessionViewer, s: SessionRec, targetAddress: String?, canSee: Boolean, castMintedBy: String?,
    targetPlatform: String? = null, targetDeviceKind: String? = null,
    /** R369 (FR-R369-3) — from R369 on, a viewer controls their own sessions anywhere, and with 304's household
     *  switch on, another member's; in R368 alone only `here` and the minting phone do (FR-R368-9). */
    widened: Boolean = false,
): SessionView? {
    val mine = s.ownerUserId == viewer.device.jellyfinUserId
    if (!mine && !isNearby(viewer.address, targetAddress)) return null
    val here = s.targetId == viewer.device.deviceId
    val visible = mine || canSee
    val controllable = visible && s.live && (here || (castMintedBy != null && castMintedBy == viewer.device.deviceId) ||
        widened && (mine || viewer.householdControl))
    return toView(s, mine = mine, here = here, controllable = controllable, visible = visible, targetPlatform = targetPlatform, targetDeviceKind = targetDeviceKind)
}

/** The wire form of [s] — shared by the per-viewer projection and the admin's (which sees everything). */
internal fun toView(
    s: SessionRec, mine: Boolean, here: Boolean, controllable: Boolean, visible: Boolean,
    targetPlatform: String? = null, targetDeviceKind: String? = null,
): SessionView {
    val cur = s.current
    val targetKind = if (s.options.rooms.size > 1) "cast_group" else s.targetKind
    return SessionView(
        id = s.id, revision = s.revision,
        owner = SessionOwner(s.ownerUserId, s.ownerName),
        mine = mine, kind = s.kind,
        title = cur?.title?.takeIf { visible },
        subtitle = cur?.subtitle?.takeIf { visible },
        artwork = cur?.artwork?.takeIf { visible },
        target = SessionTarget(
            kind = targetKind, id = s.targetId,
            name = placeName(s),
            icon = if (targetKind == "cast_group") "group" else targetIcon(targetPlatform, targetDeviceKind ?: s.targetKind),
            castDeviceId = s.castDeviceId,
        ),
        state = s.state,
        positionMs = s.positionMs.takeIf { visible },
        positionAt = s.positionAt,
        durationMs = cur?.durationMs?.takeIf { visible },
        here = here, controllable = controllable,
        reconnecting = s.reconnecting, offline = s.offline,
        endReason = s.endReason, endedBy = s.endedBy,
        createdAt = s.createdAt, updatedAt = s.updatedAt,
        rooms = s.options.rooms,
        movingTo = s.movingTo, moveFailed = s.moveFailed,
        leftRoom = s.leftRoom, leftAt = s.leftAt, roomFailed = s.roomFailed, roomFailedAt = s.roomFailedAt,
    )
}

/** R371 (FR-R371-3) — the place line names the rooms in the order they joined: *A* · *A + B* · *A + 2*. */
internal fun placeName(s: SessionRec): String {
    val rooms = s.options.rooms
    return when {
        rooms.isEmpty() -> s.targetName
        // A Cast receiver can name itself after the group it launched in; the rooms the link holder reports are the truth.
        rooms.size == 1 -> rooms[0].name
        rooms.size == 2 -> "${rooms[0].name} + ${rooms[1].name}"
        else -> "${rooms[0].name} + ${rooms.size - 1}"
    }
}

/** What changed, for the fan-out: a start or an end goes out as the whole list, anything else as one state. */
internal sealed interface SessionChange {
    data object List : SessionChange
    data class State(val sessionId: String) : SessionChange
    /** R369 — only the remote's detail changed (a queue, the tracks, a level): pushed to the controllers only. */
    data class Detail(val sessionId: String) : SessionChange
}

/** The hold a stop keeps a session for (review item 6). */
internal const val SESSION_STOP_HOLD_MS = 15_000L
/** R372 (FR-R372-4, owner decision 1) — a paused (or offline) session ends this long after its last change. */
internal const val SESSION_PAUSED_KEEP_MS = 24L * 60 * 60_000
/** R372 — *Couldn't move to {place}* is said for this long, then the place line goes back to the place (found
 *  2026-10-05: it stayed on the remote for good, minutes after the old place had carried on). */
internal const val MOVE_FAILED_SHOWN_MS = 8_000L
/** R372 (FR-R372-2, owner decision 2) — every move starts this far back, music included. */
internal const val MOVE_REWIND_MS = 2_000L

/** R372 — where a move's new place starts: 2 s back, never below 0. */
internal fun moveStartMs(positionMs: Long): Long = (positionMs - MOVE_REWIND_MS).coerceAtLeast(0L)

/** R372 — a move in flight: the place it goes to (a device id, or `cast:<id>` until the receiver redeems). */
internal data class PendingMove(
    val sessionId: String, val targetKey: String, val targetName: String, val deadline: Long, val fromTargetId: String,
    /** The Cast device a move onto `cast:<id>` goes to; [targetKey] becomes the receiver once it redeems. */
    val castDeviceId: String? = targetKey.takeIf { it.startsWith("cast:") }?.removePrefix("cast:"),
)

/** R370 (review item 8) — a load with no `starting` / `playing` report by then fails. */
internal const val SESSION_LOAD_TIMEOUT_MS = 10_000L
/** How long an ended row stays listed (review item 13: the 60 s fade comes from the server). */
internal const val SESSION_ENDED_LINGER_MS = 60_000L
/** FR-R368-4 — a restored session's target must report within this. */
internal const val SESSION_RECONNECT_MS = 2 * 60_000L
/** FR-304-3 — the timeline and ended rows are kept this long. */
internal const val SESSION_KEEP_MS = 7L * 24 * 60 * 60_000L

/**
 * 304 (FR-304-3) — *Ended today* starts at local midnight in the server's zone. [offsetMs] is that zone's offset from
 * UTC at [nowMs] (the caller reads it, so a daylight-saving day is handled by whoever knows the zone).
 */
internal fun endedTodaySince(nowMs: Long, offsetMs: Long): Long {
    val local = nowMs + offsetMs
    val day = 24L * 60 * 60_000L
    return (local - ((local % day) + day) % day) - offsetMs
}

/**
 * R368 — the service. Every mutation happens under [mutex]; the database is written on every change and every
 * position report (one UPDATE per 10 s per session, review item 9). [notify] fans a change out (Main wires it to the
 * events bus and the admin's `/ws`); it is called outside the lock.
 */
class PlaybackSessions(
    private val db: JellystructureDb,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    companion object {
        /** The server's one instance (set by the wiring), for the admin's per-device *playing* line. */
        var current: PlaybackSessions? = null
    }

    /**
     * R368 shipped bug 1 — the admin's *▶ playing …* line for a device: the live session's own title on that place
     * (a song or a book by its title, not its Jellyfin id). Null when no session plays there.
     */
    suspend fun titleOn(deviceId: String): String? = mutex.withLock {
        sessions.values.lastOrNull { it.live && it.targetId == deviceId }?.current
            ?.let { c -> listOfNotNull(c.title, c.subtitle).joinToString(" · ").ifBlank { null } }
    }

    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, SessionRec>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val queueSer = ListSerializer(SessionItem.serializer())
    private var lastSweepMs = 0L

    /** Main: push a change (the list or one session) to every opted-in socket and to the admin's `/ws`. */
    internal var notify: (SessionChange) -> Unit = {}

    /** Main: resolve what an item is (kind + facts) — films and episodes from the library, songs from the music
     *  store, a book part from the audiobooks store. Null = unknown (the session still exists, untitled). */
    internal var describe: suspend (itemId: String, bookId: String?) -> Pair<String, SessionItem>? = { _, _ -> null }

    /** Main: a device's display name for [SessionRec.targetName]; the receiver's name drops the *Chromecast via* prefix. */
    internal var placeNameOf: (DeviceData) -> String = { it.displayName.removePrefix("${CastService.DEVICE_PREFIX} · ").ifBlank { it.deviceId } }

    /** R372 — moves in flight, by session id. */
    private val pendingMoves = HashMap<String, PendingMove>()
    /** R372 — (device id) → until when a report from the place a session just left is ignored (it must not start one). */
    private val movedFrom = HashMap<String, Long>()

    /** Main: stop what plays on a place a session moved away from (the old place stops once the new one plays). */
    internal var stopPlace: suspend (SessionRec, String) -> Unit = { _, _ -> }

    /** FR-R368-9 / review item 11 — receiver device id → the device that minted its hand-off code, while its cast lives. */
    private val castMinter = HashMap<String, String>()

    fun recordCastRedeemed(receiverDeviceId: String, minterDeviceId: String) { castMinter[receiverDeviceId] = minterDeviceId }
    internal fun castMinterOf(receiverDeviceId: String): String? = castMinter[receiverDeviceId]

    /** R370 (review item 3) — receiver device id → the Cast device it runs on (from the hand-off's body). */
    private val receiverCastDevice = HashMap<String, String>()
    internal fun castDeviceOfReceiver(receiverDeviceId: String): String? = receiverCastDevice[receiverDeviceId]
    internal fun receiversByCastDevice(): Map<String, String> = receiverCastDevice.entries.associate { (r, c) -> c to r }

    /**
     * R370 (FR-R370-3, review item 1) — a redeemed hand-off that named its Cast device and the `starting` session it was
     * minted for: the receiver is that session's place from now on, so its first start joins the row.
     */
    suspend fun onReceiverRedeemed(receiver: DeviceData, minterDeviceId: String, castDeviceId: String?, sessionId: String?) {
        castMinter[receiver.deviceId] = minterDeviceId
        if (castDeviceId != null) receiverCastDevice[receiver.deviceId] = castDeviceId
        if (sessionId == null) return
        // R372 — a move onto this Cast device: the receiver is the move's new place from now on (its start joins).
        val isMove = mutex.withLock { pendingMoves[sessionId]?.let { m -> pendingMoves[sessionId] = m.copy(targetKey = receiver.deviceId); true } ?: false }
        if (isMove) return
        val moved = mutex.withLock {
            val cur = sessions[sessionId]?.takeIf { it.live && it.targetKind == "cast" } ?: return@withLock false
            val next = cur.copy(targetId = receiver.deviceId, targetName = placeNameOf(receiver), castDeviceId = castDeviceId ?: cur.castDeviceId,
                ownerUserId = receiver.jellyfinUserId, revision = cur.revision + 1, updatedAt = now())
            sessions[sessionId] = next
            next.persist()
            true
        }
        if (moved) notify(SessionChange.State(sessionId))
    }

    /**
     * R370 (FR-R370-3) — a session created by *Play on…* before its place reports: `starting`, with a 10 s load
     * deadline (review item 8). A Cast place's row is keyed on its device until the receiver redeems
     * ([onReceiverRedeemed]); an app place's is its device id.
     */
    internal suspend fun createStarting(
        owner: DeviceData, targetKind: String, targetId: String, targetName: String, kind: String,
        items: List<SessionItem>, index: Int, startMs: Long, options: SessionOptions, startedBy: DeviceData,
        castDeviceId: String? = null, bookId: String? = null,
    ): SessionRec {
        val rec = mutex.withLock {
            val t = now()
            val cur = items.getOrNull(index) ?: items.firstOrNull() ?: SessionItem("")
            val r = SessionRec(
                id = newId(), ownerUserId = owner.jellyfinUserId, ownerName = firstName(owner.jellyfinUsername), targetKind = targetKind,
                targetId = targetId, targetName = targetName, lane = sessionLane(kind), kind = kind, itemId = cur.id, bookId = bookId,
                queue = listOf(cur), queueIndex = index, positionMs = startMs, positionAt = t, state = SessionState.STARTING,
                options = options.copy(queueIds = items.map { it.id }, queueKnown = items.size > 1), revision = 1,
                startedByDeviceId = startedBy.deviceId, jellyfinPlaySessionId = null, offline = false, endReason = null, endedBy = null,
                createdAt = t, updatedAt = t, endedAt = null, loadDeadline = t + SESSION_LOAD_TIMEOUT_MS, castDeviceId = castDeviceId,
            )
            sessions[r.id] = r
            r.persist()
            event(r.id, "started", startedBy.deviceId, targetName)
            r
        }
        notify(SessionChange.List)
        return rec
    }

    private fun now() = clock()
    private fun newId(): String = "ps-" + generateSecureToken().take(16)

    // ── persistence ──

    private fun SessionRec.persist() {
        db.playbackSessionQueries.upsert(Playback_session(
            id = id, owner_user_id = ownerUserId, owner_name = ownerName, target_kind = targetKind, target_id = targetId,
            target_name = targetName, lane = lane, kind = kind, item_id = itemId, book_id = bookId,
            queue_json = json.encodeToString(queueSer, queue), queue_index = queueIndex.toLong(), position_ms = positionMs,
            position_at = positionAt, state = state,
            options_json = json.encodeToString(SessionOptions.serializer(), options.copy(castDeviceId = castDeviceId, linkHolder = castMinter[targetId])),
            revision = revision, started_by_device_id = startedByDeviceId, jellyfin_play_session_id = jellyfinPlaySessionId,
            offline = if (offline) 1L else 0L, end_reason = endReason, ended_by = endedBy, created_at = createdAt,
            updated_at = updatedAt, ended_at = endedAt,
        ))
    }

    private fun Playback_session.toRec(): SessionRec = SessionRec(
        id = id, ownerUserId = owner_user_id, ownerName = owner_name, targetKind = target_kind, targetId = target_id,
        targetName = target_name, lane = lane, kind = kind, itemId = item_id, bookId = book_id,
        queue = runCatching { json.decodeFromString(queueSer, queue_json) }.getOrDefault(emptyList()),
        queueIndex = queue_index.toInt(), positionMs = position_ms, positionAt = position_at, state = state,
        options = runCatching { json.decodeFromString(SessionOptions.serializer(), options_json) }.getOrDefault(SessionOptions()),
        revision = revision, startedByDeviceId = started_by_device_id, jellyfinPlaySessionId = jellyfin_play_session_id,
        offline = offline != 0L, endReason = end_reason, endedBy = ended_by, createdAt = created_at, updatedAt = updated_at,
        endedAt = ended_at,
    )

    /** 304 (FR-304-3) — one timeline entry. */
    private fun event(sessionId: String, what: String, source: String?, detail: String? = null) {
        runCatching { db.playbackSessionQueries.insertEvent(sessionId, now(), what, source, detail) }
            .onFailure { println("[WARN] Playback sessions: timeline write failed: ${it.message}") }
    }

    /** 304 — the timeline of one session, oldest first. */
    fun timeline(sessionId: String): List<Triple<Long, String, Pair<String?, String?>>> =
        db.playbackSessionQueries.eventsFor(sessionId).executeAsList().map { Triple(it.at, it.what, it.source to it.detail) }

    // ── boot ──

    /**
     * FR-R368-4, review item 12 — loads every session that wasn't ended, marked *reconnecting* until its target reports
     * again. Returns them, so the caller rebuilds the tracker (with the current item's play-session id) and R343's
     * plans from `options_json`.
     */
    internal suspend fun restore(): List<SessionRec> = mutex.withLock {
        val t = now()
        val rows = db.playbackSessionQueries.liveOrEndedSince(t - SESSION_ENDED_LINGER_MS).executeAsList().map { it.toRec() }
        val restored = mutableListOf<SessionRec>()
        for (r in rows) {
            val rec = if (r.live) r.copy(reconnecting = true, reconnectDeadline = t + SESSION_RECONNECT_MS, stopHoldUntil = null,
                castDeviceId = r.castDeviceId ?: r.options.castDeviceId) else r
            if (rec.live && rec.targetKind == "cast") {
                rec.options.linkHolder?.let { castMinter.getOrPut(rec.targetId) { it } }
                rec.castDeviceId?.let { receiverCastDevice.getOrPut(rec.targetId) { it } }
            }
            sessions[rec.id] = rec
            if (rec.live) restored += rec
        }
        if (restored.isNotEmpty()) Logger.info("Playback sessions: ${restored.size} restored after a restart, reconnecting", "tv")
        restored
    }

    // ── reads ──

    internal suspend fun all(): List<SessionRec> = mutex.withLock { sessions.values.toList() }
    internal suspend fun get(id: String): SessionRec? = mutex.withLock { sessions[id] }

    /** Review item 12 (a) — is [deviceId] the target of a session still waiting to reconnect? */
    suspend fun isReconnecting(deviceId: String): Boolean = mutex.withLock { sessions.values.any { it.live && it.reconnecting && it.targetId == deviceId } }

    /** 304 — every live session plus those that ended since [since], for the admin. */
    internal suspend fun adminRows(since: Long): List<SessionRec> = mutex.withLock {
        val inMemory = sessions.values.toList()
        val fromDb = db.playbackSessionQueries.liveOrEndedSince(since).executeAsList().map { it.toRec() }
        (inMemory + fromDb.filter { r -> inMemory.none { it.id == r.id } }).filter { it.live || (it.endedAt ?: 0) >= since }
    }

    // ── writes ──

    private fun liveOn(targetId: String, lane: String): SessionRec? =
        sessions.values.lastOrNull { it.live && it.targetId == targetId && it.lane == lane }

    /**
     * FR-R368-2, review items 5 and 6 — a start (`playback/start`, `music/play`, or a receiver's first report): joins
     * the live session on (target, lane) for the same owner (a song boundary, a book's next part, an episode's
     * auto-advance), replaces another owner's, or creates one. Returns the session id.
     */
    internal suspend fun onStart(
        device: DeviceData, itemId: String, positionMs: Long, jellyfinPlaySessionId: String? = null,
        bookId: String? = null, plan: SessionPlan? = null, directPlay: Boolean = false,
        startedBy: DeviceData? = null, kindHint: String? = null,
    ): String {
        val described = runCatching { describe(itemId, bookId) }.getOrNull()
        val kind = when {
            bookId != null -> SessionKind.AUDIOBOOK
            kindHint != null -> kindHint
            else -> described?.first ?: SessionKind.FILM
        }
        val item = described?.second ?: SessionItem(itemId)
        val lane = sessionLane(kind)
        val changes = mutableListOf<SessionChange>()
        val stopOld = mutableListOf<Pair<SessionRec, String>>()
        val id = mutex.withLock {
            val t = now()
            // R372 (FR-R372-2) — the new place of a move reported: the session moves there (its id stays), the old
            // place is stopped once the new one plays.
            pendingMoves.values.firstOrNull { it.targetKey == device.deviceId }?.let { m ->
                val cur = sessions[m.sessionId]?.takeIf { it.live }
                pendingMoves.remove(m.sessionId)
                if (cur != null) {
                    // A move onto a speaker that was in the old place's group (found on the Pixel 9 Pro, 2026-10-05: remove
                    // the first room of Stue + Gæsteværelse): the receiver came up named after the group, and the row kept
                    // the old Cast device, so Play on… listed Stue as busy and Gæsteværelse as free. The new place is the Cast
                    // device the move named, under the name it was picked by, and the old group's rooms stay behind (the app
                    // holding the link reports the new place's rooms).
                    val cast = device.kind == "cast"
                    val next = cur.copy(
                        targetId = device.deviceId, targetKind = if (cast) "cast" else "app",
                        targetName = if (m.castDeviceId != null && m.targetName != m.castDeviceId) m.targetName else placeNameOf(device),
                        castDeviceId = if (cast) receiverCastDevice[device.deviceId] ?: m.castDeviceId ?: cur.castDeviceId else null,
                        itemId = itemId, positionMs = positionMs, positionAt = t, state = SessionState.STARTING, movingTo = null, moveFailed = null,
                        offline = false, reconnecting = false, stopHoldUntil = null, loadDeadline = null, jellyfinPlaySessionId = jellyfinPlaySessionId,
                        options = cur.options.withPlan(plan).copy(directPlay = directPlay, rooms = emptyList()), revision = cur.revision + 1, updatedAt = t,
                    )
                    sessions[next.id] = next
                    next.persist()
                    event(next.id, "moved", device.deviceId, next.targetName)
                    movedFrom[m.fromTargetId] = t + SESSION_ENDED_LINGER_MS
                    stopOld += cur to m.fromTargetId
                    changes += SessionChange.State(next.id)
                    return@withLock next.id
                }
            }
            val live = liveOn(device.deviceId, lane)
            when (val d = startDecision(live, device.jellyfinUserId)) {
                is StartDecision.Join -> {
                    val cur = sessions.getValue(d.sessionId)
                    val sameItem = cur.itemId == itemId
                    // A progress report can name the new song first (a skip on the receiver): its item may not be in the
                    // queue yet, and the row then had no title (the Mac, 2026-10-05).
                    val queue = if (sameItem && cur.queue.any { it.id == itemId && it.title != null }) cur.queue else listOf(item)
                    val next = cur.copy(
                        kind = kind, itemId = itemId, bookId = bookId ?: cur.bookId, queue = queue, queueIndex = if (cur.options.queueKnown) cur.queueIndex else 0,
                        loadDeadline = null,
                        positionMs = positionMs, positionAt = t, state = SessionState.STARTING,
                        options = cur.options.withPlan(plan).copy(directPlay = directPlay),
                        revision = cur.revision + 1, jellyfinPlaySessionId = jellyfinPlaySessionId,
                        reconnecting = false, reconnectDeadline = null, stopHoldUntil = null, offline = false,
                        updatedAt = t,
                    )
                    sessions[next.id] = next
                    next.persist()
                    if (cur.reconnecting) event(next.id, "reconnected", device.deviceId)
                    changes += SessionChange.State(next.id)
                    next.id
                }
                else -> {
                    if (d is StartDecision.Replace) {
                        endLocked(d.sessionId, "replaced", by = device)?.let { changes += SessionChange.List }
                    }
                    // R370 (found on the Pixel 9 Pro) — music cast from an app: what that app held in the same lane goes
                    // with it. A playing one ends by its own stop report; a paused one (restored after a relaunch, never
                    // opened, so nothing stops it) stayed beside the cast as the same song twice.
                    if (device.kind == "cast") castMinter[device.deviceId]?.let { minter ->
                        sessions.values.filter { it.live && it.targetId == minter && it.lane == lane && it.state != SessionState.PLAYING }.forEach { left ->
                            endLocked(left.id, "replaced")?.let { changes += SessionChange.List }
                        }
                    }
                    val starter = startedBy ?: device
                    val rec = SessionRec(
                        id = newId(), ownerUserId = device.jellyfinUserId, ownerName = firstName(device.jellyfinUsername),
                        targetKind = if (device.kind == "cast") "cast" else "app", targetId = device.deviceId,
                        targetName = placeNameOf(device), lane = lane, kind = kind, itemId = itemId, bookId = bookId,
                        queue = listOf(item), queueIndex = 0, positionMs = positionMs, positionAt = t,
                        state = SessionState.STARTING, options = SessionOptions(directPlay = directPlay).withPlan(plan),
                        revision = 1, startedByDeviceId = (if (device.kind == "cast") castMinter[device.deviceId] else null) ?: starter.deviceId,
                        jellyfinPlaySessionId = jellyfinPlaySessionId, offline = false, endReason = null, endedBy = null,
                        createdAt = t, updatedAt = t, endedAt = null,
                        castDeviceId = receiverCastDevice[device.deviceId],   // R370 — a receiver's Cast device, when a hand-off named it
                    )
                    sessions[rec.id] = rec
                    rec.persist()
                    event(rec.id, "started", rec.startedByDeviceId, rec.targetName)
                    changes += SessionChange.List
                    rec.id
                }
            }
        }
        changes.distinct().forEach { notify(it) }
        stopOld.forEach { (s, from) -> runCatching { stopPlace(s, from) } }
        return id
    }

    /**
     * R372 (FR-R372-2) — a move begins: the place line reads *Moving to {place}…* until the new place reports (it then
     * joins this session in [onStart]); with no report in 10 s the line says *Couldn't move to {place}* and the old place
     * carries on.
     */
    internal suspend fun beginMove(sessionId: String, targetKey: String, targetName: String): SessionRec? {
        val next = mutex.withLock {
            val cur = sessions[sessionId]?.takeIf { it.live } ?: return null
            pendingMoves[sessionId] = PendingMove(sessionId, targetKey, targetName, now() + SESSION_LOAD_TIMEOUT_MS, cur.targetId)
            val n = cur.copy(movingTo = targetName, moveFailed = null, revision = cur.revision + 1, updatedAt = now())
            sessions[sessionId] = n
            n.persist()
            n
        }
        notify(SessionChange.State(sessionId))
        return next
    }

    /**
     * FR-R368-3, review items 8 and 9 — a progress report: the position is stored on every one, but only a change
     * (state, item, a seek past 3 s, reconnecting ending) bumps the revision and pushes `session_state`. A report with
     * no session (one that started before this server knew sessions, or a receiver's first report) creates one.
     */
    suspend fun onProgress(device: DeviceData, itemId: String, positionMs: Long, paused: Boolean, sessionId: String? = null,
                           volumePercent: Int? = null, muted: Boolean? = null) {
        var created = false
        var change: SessionChange? = null
        // A report that names another song than the session holds (the receiver skipped, and its progress came before its
        // start): what that song is, so the row and the remote keep a title (the Mac, 2026-10-05).
        val peek = mutex.withLock { findLocked(device, itemId, sessionId) }
        val newItem = if (peek != null && peek.itemId != itemId) runCatching { describe(itemId, peek.bookId)?.second }.getOrNull() else null
        mutex.withLock {
            val t = now()
            val cur = findLocked(device, itemId, sessionId)
            if (cur == null) {
                // A report racing a stop that already ended this session (a server-side *Stop*, R369 item 12) must not
                // start a new one: only a report with no recent end on this place and item does — nor one from the place
                // a session just moved away from (R372).
                created = sessions.values.none { !it.live && it.targetId == device.deviceId && it.itemId == itemId } &&
                    (movedFrom[device.deviceId] ?: 0L) < t
                return@withLock
            }
            val changed = isSessionChange(cur, itemId, positionMs, paused, t) || cur.reconnecting || cur.offline || cur.stopHoldUntil != null
            val state = if (paused) SessionState.PAUSED else SessionState.PLAYING
            val volumeChanged = volumePercent != null && (volumePercent != cur.options.volume || muted != cur.options.muted)
            val queue = if (cur.itemId != itemId && cur.queue.none { it.id == itemId }) listOf(newItem ?: SessionItem(itemId)) else cur.queue
            val next = cur.copy(
                itemId = itemId, queue = queue, positionMs = positionMs, positionAt = t, state = state, loadDeadline = null,
                options = if (volumePercent != null) cur.options.copy(volume = volumePercent, muted = muted) else cur.options,
                revision = if (changed) cur.revision + 1 else cur.revision,
                reconnecting = false, reconnectDeadline = null, offline = false, stopHoldUntil = null,
                updatedAt = if (changed) t else cur.updatedAt,
            )
            sessions[next.id] = next
            next.persist()
            if (cur.reconnecting) event(next.id, "reconnected", device.deviceId)
            if (cur.offline) event(next.id, "back_online", device.deviceId)
            val wasPaused = cur.state == SessionState.PAUSED
            if (cur.state != SessionState.STARTING && wasPaused != paused) event(next.id, if (paused) "paused" else "resumed", device.deviceId)
            if (changed) change = SessionChange.State(next.id)
            else if (volumeChanged) change = SessionChange.Detail(next.id)   // R371 — a level is not a revision
        }
        if (created) {
            onStart(device, itemId, positionMs, kindHint = null)
            onProgress(device, itemId, positionMs, paused)
            return
        }
        change?.let { notify(it) }
    }

    private fun findLocked(device: DeviceData, itemId: String, sessionId: String?): SessionRec? {
        sessionId?.let { id -> sessions[id]?.takeIf { it.live && it.targetId == device.deviceId }?.let { return it } }
        return sessions.values.lastOrNull { it.live && it.targetId == device.deviceId && it.itemId == itemId }
            ?: sessions.values.lastOrNull { it.live && it.targetId == device.deviceId && it.ownerUserId == device.jellyfinUserId && it.stopHoldUntil != null }
    }

    /**
     * Review item 6 — a stop holds the session for [SESSION_STOP_HOLD_MS]: every song (and a book's next part, an
     * episode's auto-advance) is a stop then a start, and the start joins. With no start in time, [tick] ends it.
     */
    suspend fun onStop(device: DeviceData, itemId: String, positionMs: Long, sessionId: String? = null) {
        mutex.withLock {
            val cur = findLocked(device, itemId, sessionId) ?: return@withLock
            val t = now()
            val next = cur.copy(positionMs = positionMs, positionAt = t, stopHoldUntil = t + SESSION_STOP_HOLD_MS)
            sessions[next.id] = next
            next.persist()
        }
    }

    /**
     * R372 (FR-R372-4, review item 5; amends FR-R368-2) — the 110 watchdog stopped Jellyfin's playback on a silent place:
     * the session is paused where it was and marked offline (*{place} is offline · paused at …*), kept 24 h, with
     * *Play here* and *Move to…* offered.
     */
    suspend fun onReaped(device: DeviceData, itemId: String) {
        val id = mutex.withLock {
            val cur = sessions.values.lastOrNull { it.live && it.targetId == device.deviceId && (it.itemId == itemId || it.stopHoldUntil != null) } ?: return@withLock null
            event(cur.id, "offline", null, cur.targetName)
            val t = now()
            val next = cur.copy(state = SessionState.PAUSED, offline = true, stopHoldUntil = null, reconnecting = false, reconnectDeadline = null,
                revision = cur.revision + 1, updatedAt = t)
            sessions[next.id] = next
            next.persist()
            next.id
        }
        if (id != null) notify(SessionChange.State(id))
    }

    /** Ends [id] (must hold [mutex]). Returns the ended row, or null when it was not live. */
    private fun endLocked(id: String, reason: String, by: DeviceData? = null, byName: String? = null): SessionRec? {
        val cur = sessions[id]?.takeIf { it.live } ?: return null
        val t = now()
        val ended = cur.copy(
            state = SessionState.ENDED, endReason = reason, endedAt = t, updatedAt = t, revision = cur.revision + 1,
            endedBy = byName ?: by?.takeIf { it.jellyfinUserId != cur.ownerUserId }?.let { firstName(it.jellyfinUsername) },
            stopHoldUntil = null, reconnecting = false, reconnectDeadline = null,
        )
        sessions[id] = ended
        ended.persist()
        event(id, "ended", by?.deviceId, reason)
        return ended
    }

    /** R369 — ends a session at once (a `stop` command, an admin *End*). */
    internal suspend fun end(id: String, reason: String, by: DeviceData? = null, byName: String? = null): Boolean {
        val ok = mutex.withLock { endLocked(id, reason, by, byName) != null }
        if (ok) notify(SessionChange.List)
        return ok
    }

    /** Mutates one live session under the lock and pushes `session_state` when [bump]. Used by R369–R372. */
    internal suspend fun update(id: String, bump: Boolean = true, list: Boolean = false, f: (SessionRec) -> SessionRec): SessionRec? {
        val next = mutex.withLock {
            val cur = sessions[id] ?: return null
            val n = f(cur).let { if (bump) it.copy(revision = cur.revision + 1, updatedAt = now()) else it }
            sessions[id] = n
            n.persist()
            n
        }
        notify(if (list) SessionChange.List else SessionChange.State(id))
        return next
    }

    /**
     * R369 (dev review item 5) — a target's queue report, on change only: ids, the revision, the place in it, the
     * modes and a film's tracks. A changed place or mode bumps the revision (it is a change); the queue alone goes to
     * the controllers as `session_detail`.
     */
    suspend fun onQueueReport(device: DeviceData, r: dev.jellystructure.shared.tv.SessionQueueReport) {
        var change: SessionChange? = null
        mutex.withLock {
            val cur = findLocked(device, r.itemId, r.sessionId) ?: return@withLock
            val placeMoved = cur.queueIndex != r.queueIndex || cur.options.shuffle != (r.shuffle ?: cur.options.shuffle) ||
                cur.options.repeat != (r.repeat ?: cur.options.repeat) || cur.options.audioIndex != r.audioIndex || cur.options.subtitleIndex != r.subtitleIndex
            val o = cur.options.copy(
                queueIds = r.queue.ifEmpty { cur.options.queueIds }, queueRev = r.queueRev, queueKnown = true,
                shuffle = r.shuffle ?: cur.options.shuffle, repeat = r.repeat ?: cur.options.repeat,
                audioTracks = r.audioTracks, subtitleTracks = r.subtitleTracks, audioIndex = r.audioIndex, subtitleIndex = r.subtitleIndex,
                rooms = r.members ?: cur.options.rooms,
            )
            val next = cur.copy(queueIndex = r.queueIndex, options = o,
                revision = if (placeMoved) cur.revision + 1 else cur.revision, updatedAt = if (placeMoved) now() else cur.updatedAt)
            sessions[next.id] = next
            next.persist()
            change = if (placeMoved) SessionChange.State(next.id) else SessionChange.Detail(next.id)
        }
        change?.let { notify(it) }
    }

    /**
     * R371 (review item 5) — the rooms of a group as the app holding the session's Cast link reports them (on change).
     * The report replaces the server's room list; a room missing from it left (the place line says so for 5 s); the
     * session keeps its other rooms.
     */
    suspend fun onMembersReport(device: DeviceData, r: dev.jellystructure.shared.tv.SessionMembersReport) {
        var change: SessionChange? = null
        mutex.withLock {
            val cur = sessions.values.lastOrNull { it.live && it.itemId == r.itemId && (castMinter[it.targetId] == device.deviceId || it.targetId == device.deviceId) }
                ?: sessions.values.lastOrNull { it.live && it.itemId == r.itemId && it.ownerUserId == device.jellyfinUserId }
                ?: return@withLock
            val before = cur.options.rooms
            // A failed add from an app that could not read its controller says no members: the rooms stay as they were.
            val members = if (r.members.isEmpty() && r.failed != null) before else r.members
            val diff = roomsDiff(before, members)
            val t = now()
            diff.added.forEach { event(cur.id, "room_added", device.deviceId, it.name) }
            diff.left.forEach { event(cur.id, "room_removed", device.deviceId, it.name) }
            r.failed?.let { event(cur.id, "room_failed", device.deviceId, it) }
            val moved = diff.added.isNotEmpty() || diff.left.isNotEmpty() || r.failed != null
            Logger.info("Playback sessions: ${cur.id} rooms from ${device.deviceId}: ${members.joinToString { it.name }}" +
                (r.failed?.let { " · couldn't add $it" } ?: "") + (r.selectable?.let { " · can join: ${it.joinToString { s -> s.name }}" } ?: ""), "tv")
            val next = cur.copy(
                options = cur.options.copy(rooms = members, addable = r.selectable ?: cur.options.addable),
                leftRoom = diff.left.lastOrNull()?.name ?: cur.leftRoom, leftAt = if (diff.left.isNotEmpty()) t else cur.leftAt,
                roomFailed = r.failed ?: cur.roomFailed, roomFailedAt = if (r.failed != null) t else cur.roomFailedAt,
                revision = if (moved) cur.revision + 1 else cur.revision,
                updatedAt = if (moved) t else cur.updatedAt,
            )
            sessions[next.id] = next
            next.persist()
            change = if (next.revision != cur.revision) SessionChange.State(next.id) else SessionChange.Detail(next.id)
        }
        change?.let { notify(it) }
    }

    /** R371 (review item 7) — a room muted: the level before it is kept on the server, so every app shows the same. */
    internal suspend fun rememberRoomLevel(sessionId: String, castDeviceId: String, level: Int?) {
        mutex.withLock {
            val cur = sessions[sessionId] ?: return@withLock
            val m = cur.options.roomLevelsBeforeMute.toMutableMap()
            if (level == null) m.remove(castDeviceId) else m[castDeviceId] = level
            val next = cur.copy(options = cur.options.copy(roomLevelsBeforeMute = m))
            sessions[sessionId] = next
            next.persist()
        }
    }

    /** 304 / R369–R372 — a timeline entry from outside the service (a command from the admin, a move). */
    internal fun record(sessionId: String, what: String, source: String?, detail: String? = null) = event(sessionId, what, source, detail)

    /**
     * The clock's work, every few seconds: holds that ran out end (`stopped`), restored sessions that never reported
     * end at their stored position (FR-R368-4), ended rows leave the list after 60 s (review item 13), and once an
     * hour the timeline and ended rows older than 7 days are deleted (304 FR-304-3).
     */
    suspend fun tick() {
        val changes = mutableListOf<SessionChange>()
        mutex.withLock {
            val t = now()
            for (s in sessions.values.toList()) {
                val move = pendingMoves[s.id]
                if (s.live && move != null && t >= move.deadline) {
                    // R372 — no report from the new place in 10 s: the old one carries on.
                    pendingMoves.remove(s.id)
                    val n = s.copy(movingTo = null, moveFailed = move.targetName, revision = s.revision + 1, updatedAt = t)
                    sessions[s.id] = n
                    n.persist()
                    changes += SessionChange.State(s.id)
                } else if (s.live && s.moveFailed != null && t - s.updatedAt >= MOVE_FAILED_SHOWN_MS) {
                    val n = s.copy(moveFailed = null, revision = s.revision + 1, updatedAt = t)
                    sessions[s.id] = n
                    n.persist()
                    changes += SessionChange.State(s.id)
                } else if (s.live && (s.state == SessionState.PAUSED || s.offline) && s.stopHoldUntil == null && t - s.updatedAt >= SESSION_PAUSED_KEEP_MS) {
                    // R372 (FR-R372-4) — paused (or offline) for 24 h since the last change: it ends.
                    endLocked(s.id, if (s.offline) "offline" else "idle")?.let { changes += SessionChange.List }
                } else if (s.live && s.state == SessionState.STARTING && s.loadDeadline != null && t >= s.loadDeadline) {
                    // R370 (review item 8) — a load that brought no report within 10 s failed.
                    endLocked(s.id, "failed")?.let { changes += SessionChange.List }
                } else if (s.live && s.stopHoldUntil != null && t >= s.stopHoldUntil) {
                    endLocked(s.id, "stopped")?.let { changes += SessionChange.List }
                } else if (s.live && s.reconnecting && s.reconnectDeadline != null && t >= s.reconnectDeadline) {
                    onSilentAfterRestart(s, t)?.let { changes += it }
                } else if (!s.live && t - (s.endedAt ?: t) > SESSION_ENDED_LINGER_MS) {
                    sessions.remove(s.id)
                    changes += SessionChange.List
                }
            }
            if (t - lastSweepMs >= 60 * 60_000L) {
                lastSweepMs = t
                runCatching {
                    db.playbackSessionQueries.deleteEventsBefore(t - SESSION_KEEP_MS)
                    db.playbackSessionQueries.deleteEndedBefore(t - SESSION_KEEP_MS)
                }.onFailure { Logger.warn("Playback sessions: sweep failed: ${it.message}", "tv") }
            }
        }
        changes.distinct().forEach { notify(it) }
    }

    /**
     * FR-R368-4 as amended by R372 (owner decision 3): a restored session whose place never came back is *paused,
     * offline* at its stored position and kept 24 h like any silent place (it used to end after 2 minutes).
     */
    private fun onSilentAfterRestart(s: SessionRec, t: Long): SessionChange? {
        event(s.id, "offline", null, s.targetName)
        val n = s.copy(state = SessionState.PAUSED, offline = true, reconnecting = false, reconnectDeadline = null, revision = s.revision + 1, updatedAt = t)
        sessions[s.id] = n
        n.persist()
        return SessionChange.State(s.id)
    }
}

/** R371 — what changed between two members reports (by Cast device id, in the order they joined). */
internal data class RoomsDiff(val added: List<SessionRoom>, val left: List<SessionRoom>)

internal fun roomsDiff(before: List<SessionRoom>, after: List<SessionRoom>): RoomsDiff = RoomsDiff(
    added = after.filter { a -> before.none { it.castDeviceId == a.castDeviceId } },
    left = before.filter { b -> after.none { it.castDeviceId == b.castDeviceId } },
)

/** The first name a household knows someone by (FR-R368-6) — a Jellyfin user name's first word. */
internal fun firstName(username: String): String = username.trim().split(' ', '.', '_').firstOrNull { it.isNotBlank() }
    ?.replaceFirstChar { it.uppercase() } ?: username
