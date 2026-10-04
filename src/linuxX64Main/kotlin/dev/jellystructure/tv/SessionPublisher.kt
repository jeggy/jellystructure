package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.model.AdminSessionEvent
import dev.jellystructure.model.AdminSessionList
import dev.jellystructure.model.AdminSessionRow
import dev.jellystructure.shared.tv.SessionList
import dev.jellystructure.shared.tv.SessionListEnvelope
import dev.jellystructure.shared.tv.SessionStateEnvelope
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionDetailEnvelope
import dev.jellystructure.shared.tv.SessionQueueEntry
import dev.jellystructure.shared.tv.SessionView
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlinx.coroutines.launch

/**
 * R368 (FR-R368-5, dev review items 9–11) — builds each viewer's list and fans changes out: the whole list to every
 * opted-in socket when a session starts or ends, one `session_state` when one changes. The list is built per socket
 * (`mine`, `here`, `controllable` and the visibility rule differ per viewer). 304 — the admin's list (every owner,
 * every network) goes out on `/ws` beside it.
 */
class SessionPublisher(
    internal val sessions: PlaybackSessions,
    private val devices: RaviloDeviceService,
    private val bus: TvEventBus?,
    /** Owner decision 1 — may [viewer] see what [itemId] is (the library / music / book rules of playback)? */
    private val canSee: suspend (viewer: DeviceData, kind: String, itemId: String, bookId: String?) -> Boolean,
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** 304b (FR-304-4) — *Household members can control each other's playing* (off by default). */
    var householdControl: () -> Boolean = { false }

    /** 304 — the admin's `/ws` (null in tests). */
    var adminBroadcast: (suspend (AdminSessionList) -> Unit)? = null

    /** 304 — controller names per session (R369's table), for the admin row. */
    var controllersOf: suspend (sessionId: String) -> List<String> = { emptyList() }

    /** R369 — the ops the target obeys, for the admin row and `SessionDetail.ops`. */
    internal var opsOf: suspend (SessionRec) -> List<String> = { emptyList() }

    /** 304 — the server zone's offset from UTC at an instant, for *Ended today*. */
    var zoneOffsetMs: (Long) -> Long = { 0L }

    private fun deviceIndex(): Map<String, DeviceData> =
        devices.allDevices().groupBy { it.deviceId }.mapValues { (_, rows) -> rows.maxByOrNull { it.lastSeen }!! }

    private suspend fun viewOf(viewer: DeviceData, s: SessionRec, index: Map<String, DeviceData>): SessionView? {
        val target = index[s.targetId]
        val mine = s.ownerUserId == viewer.jellyfinUserId
        val visible = mine || runCatching { canSee(viewer, s.kind, s.itemId, s.bookId) }.getOrDefault(false)
        return sessionViewFor(
            SessionViewer(viewer, viewer.lastPublicAddress, householdControl()), s, target?.lastPublicAddress, visible,
            sessions.castMinterOf(s.targetId), target?.platform, target?.kind, widened = controlWidened(),
        )
    }

    /** R369 sets this: from then on `controllable` covers the viewer's own sessions everywhere (and, with the switch,
     *  a household member's); in R368 alone only `here` and the minting phone control (FR-R368-9). */
    var controlWidened: () -> Boolean = { false }

    /** `GET /api/tv/playback/sessions` — the list for [viewer], ordered as the apps draw it is the app's job. */
    suspend fun listFor(viewer: DeviceData): SessionList {
        val index = deviceIndex()
        val views = sessions.all().mapNotNull { viewOf(viewer, it, index) }
        return SessionList(views, views.maxOfOrNull { it.revision } ?: 0L, clock())
    }

    /** The `session_list` frame for one socket. */
    suspend fun listFrameFor(viewer: DeviceData): String {
        val l = listFor(viewer)
        return json.encodeToString(SessionListEnvelope.serializer(), SessionListEnvelope(sessions = l.sessions, revision = l.revision, serverNowMs = l.serverNowMs))
    }

    private suspend fun viewerOf(userId: String, deviceId: String): DeviceData? =
        devices.listSessions(deviceId).firstOrNull { it.jellyfinUserId == userId }

    /** Fans [change] out (called by [PlaybackSessions.notify]). */
    internal fun publish(change: SessionChange) {
        val b = bus
        if (b != null) when (change) {
            is SessionChange.List -> b.notifySessions { u, d -> viewerOf(u, d)?.let { listFrameFor(it) } }
            is SessionChange.State -> {
                b.notifySessions { u, d ->
                    val viewer = viewerOf(u, d) ?: return@notifySessions null
                    val s = sessions.get(change.sessionId) ?: return@notifySessions null
                    viewOf(viewer, s, deviceIndex())?.let { v ->
                        json.encodeToString(SessionStateEnvelope.serializer(), SessionStateEnvelope(session = v, serverNowMs = clock()))
                    }
                }
                pushDetail(change.sessionId)
            }
            is SessionChange.Detail -> pushDetail(change.sessionId)
        }
        val send = adminBroadcast
        if (send != null && scope != null) scope.launch { runCatching { send(adminList()) } }
    }

    /** R369 (dev review items 5 and 7) — `session_detail` goes only to the session's attached controllers. */
    private fun pushDetail(sessionId: String) {
        val b = bus ?: return
        val c = control ?: return
        val sc = scope ?: return
        sc.launch {
            val s = sessions.get(sessionId) ?: return@launch
            for (deviceId in c.controllerDevices(sessionId)) {
                val viewer = devices.listSessions(deviceId).maxByOrNull { it.lastSeen } ?: continue
                val d = runCatching { detailFor(viewer, s) }.getOrNull() ?: continue
                b.notifyDevice(viewer.jellyfinUserId, deviceId, json.encodeToString(SessionDetailEnvelope.serializer(), SessionDetailEnvelope(detail = d, serverNowMs = clock())))
            }
        }
    }

    /** R369 — a 409's `session`: the row as [viewer] sees it (the admin, null, sees it whole). */
    internal suspend fun viewFor(viewer: DeviceData?, s: SessionRec): SessionView? {
        val index = deviceIndex()
        return if (viewer == null) toView(s, mine = false, here = false, controllable = s.live, visible = true,
            targetPlatform = index[s.targetId]?.platform, targetDeviceKind = index[s.targetId]?.kind)
        else viewOf(viewer, s, index)
    }

    /** R369 — the command service; attached controllers, ops and the household switch come from it. */
    var control: SessionControl? = null

    /** 304b — writes the household switch (Main: through ConfigStore) and re-sends every list (`controllable` flips). */
    var setHouseholdControl: (suspend (Boolean) -> Unit)? = null

    /**
     * R369 (dev review item 7) — `attach_session {id}` / `detach_session {id}` on the events socket. Only a session
     * this viewer is listed is attachable.
     */
    suspend fun onSocketMessage(text: String, device: DeviceData) {
        val c = control ?: return
        val obj = runCatching { Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
        val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        if (type == "cast_devices_seen") { starter?.onCastDevicesSeen(device, text); return }
        val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        when (type) {
            "attach_session" -> {
                val s = sessions.get(id) ?: return
                if (viewFor(device, s) == null) return
                c.attach(id, device)
                pushDetail(id)
                adminRefresh()
            }
            "detach_session" -> { c.detach(id, device.deviceId); adminRefresh() }
        }
    }

    /** The socket closed: everything it held is detached, and what it could reach is forgotten (R370). */
    suspend fun onSocketClosed(deviceId: String) {
        control?.detachDevice(deviceId)
        starter?.let { it.reach.drop(deviceId); it.targetsChanged() }
        adminRefresh()
    }

    /** R370 — the start service (targets, starts, the relay). */
    var starter: SessionStarter? = null

    private fun adminRefresh() {
        val send = adminBroadcast
        if (send != null && scope != null) scope.launch { runCatching { send(adminList()) } }
    }

    /** How many queue entries ride a detail around the current one (R359's window). */
    private val queueWindow = 60

    /**
     * R369 (dev review item 5) — `GET /api/tv/playback/sessions/{id}`: the row plus the queue (a window around the
     * current entry, titles resolved here), the modes, a film's tracks, the volume and the ops the target obeys. Null
     * when [viewer] may not see this session at all; a session whose title they may not see carries no queue.
     */
    internal suspend fun detailFor(viewer: DeviceData?, s: SessionRec, admin: Boolean = false): SessionDetail? {
        val index = deviceIndex()
        val view = if (admin || viewer == null) toView(s, mine = false, here = false, controllable = s.live, visible = true,
            targetPlatform = index[s.targetId]?.platform, targetDeviceKind = index[s.targetId]?.kind)
        else viewOf(viewer, s, index) ?: return null
        val visible = view.title != null || s.current?.title == null
        val ids = s.options.queueIds.ifEmpty { listOf(s.itemId) }
        val at = if (s.options.queueKnown) s.queueIndex.coerceIn(0, (ids.size - 1).coerceAtLeast(0)) else 0
        val from = (at - queueWindow / 2).coerceAtLeast(0)
        val window = if (!visible) emptyList() else ids.drop(from).take(queueWindow).map { id ->
            val item = if (id == s.itemId) s.current else runCatching { sessions.describe(id, s.bookId)?.second }.getOrNull()
            SessionQueueEntry(id, item?.title, item?.subtitle)
        }
        return SessionDetail(
            session = view, queue = window, queueOffset = from, queueSize = ids.size, queueRev = s.options.queueRev, queueIndex = at,
            shuffle = s.options.shuffle, repeat = s.options.repeat ?: "off",
            audioTracks = s.options.audioTracks, subtitleTracks = s.options.subtitleTracks,
            audioIndex = s.options.audioIndex, subtitleIndex = s.options.subtitleIndex,
            volume = s.options.volume, muted = s.options.muted == true,
            ops = if (s.live) (control?.opsOf(s) ?: opsOf(s)) else emptyList(),
        )
    }

    /** Owner decision 2 — an address change re-sends the list (another member's rows may come or go). */
    fun addressChanged() = publish(SessionChange.List)

    /** 304 (FR-304-1, review items 1, 2, 9) — every live session plus today's ended ones, for every owner, in full. */
    suspend fun adminList(): AdminSessionList {
        val now = clock()
        val since = endedTodaySince(now, zoneOffsetMs(now))
        val index = deviceIndex()
        val rows = sessions.adminRows(since).sortedWith(compareBy<SessionRec> { !it.live }.thenByDescending { it.updatedAt }).map { s ->
            val v = toView(s, mine = false, here = false, controllable = false, visible = true, targetPlatform = index[s.targetId]?.platform, targetDeviceKind = index[s.targetId]?.kind)
            AdminSessionRow(
                id = s.id, revision = s.revision, ownerId = s.ownerUserId, ownerName = s.ownerName, kind = s.kind,
                title = v.title, subtitle = v.subtitle, artwork = v.artwork, targetKind = v.target.kind, targetId = s.targetId,
                targetName = v.target.name, targetIcon = v.target.icon, state = s.state, positionMs = s.positionMs,
                positionAt = s.positionAt, durationMs = v.durationMs, createdAt = s.createdAt, updatedAt = s.updatedAt,
                endedAt = s.endedAt, endReason = s.endReason, endedBy = s.endedBy,
                startedFrom = s.startedByDeviceId?.let { id -> index[id]?.let { sessions.placeNameOf(it) } ?: id },
                controllers = runCatching { controllersOf(s.id) }.getOrDefault(emptyList()),
                reconnecting = s.reconnecting, offline = s.offline, rooms = s.options.rooms.map { it.name },
                ops = if (s.live) runCatching { opsOf(s) }.getOrDefault(emptyList()) else emptyList(),
                events = sessions.timeline(s.id).map { (at, what, src) ->
                    AdminSessionEvent(at, what, src.first?.let { id -> if (id == "admin") "admin" else index[id]?.let { sessions.placeNameOf(it) } ?: id }, src.second)
                },
            )
        }
        return AdminSessionList(rows, now, householdControl())
    }
}

