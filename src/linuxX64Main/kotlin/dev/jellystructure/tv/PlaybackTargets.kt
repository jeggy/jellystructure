package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastRelayLoadEnvelope
import dev.jellystructure.shared.tv.CastSeenDevice
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionLoadEnvelope
import dev.jellystructure.shared.tv.SessionStartRequest
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.TargetCapabilities
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock

// ── R370 — one *Play on…* list, start anywhere ────────────────────────────────────────────────────────────────────

/** Review item 12, owner decision 3 — places seen this recently are listed *Not reachable* (TVs, displays, speakers). */
internal const val TARGET_UNREACHABLE_WINDOW_MS = 24L * 60 * 60_000

/** Owner decision 1 — the apps that can relay a speaker launch: Android phones and the desktop apps. */
internal val RELAY_PLATFORMS = setOf("phone", "mac", "linux")

/** One Ravilo app holding its events socket. */
internal data class LiveApp(val device: DeviceData, val features: Set<String>)

/** One Cast device as an app reported seeing it. */
internal data class ReachEntry(val app: DeviceData, val device: CastSeenDevice, val reportedAt: Long)

/**
 * Owner decision 1 — *who can reach what*: each relay-capable app's last `cast_devices_seen` report. A report replaces
 * that app's list; a closed socket drops it.
 */
class CastReach(private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val mutex = Mutex()
    private val byApp = HashMap<String, Pair<DeviceData, Pair<List<CastSeenDevice>, Long>>>()

    suspend fun report(app: DeviceData, devices: List<CastSeenDevice>) = mutex.withLock {
        byApp[app.deviceId] = app to (devices.filter { it.kind != "group" } to clock())
    }

    suspend fun drop(appDeviceId: String) = mutex.withLock { byApp.remove(appDeviceId); Unit }

    internal suspend fun entries(): List<ReachEntry> = mutex.withLock {
        byApp.values.flatMap { (app, seen) -> seen.first.map { ReachEntry(app, it, seen.second) } }
    }
}

/**
 * Owner decision 1 — which app relays a launch onto [castDeviceId]: only an Android or desktop app that reports the
 * device and shares the requester's public address; the most recently reporting one. Null = no relay (the place is
 * *Not reachable*).
 */
internal fun chooseRelayApp(reach: List<ReachEntry>, castDeviceId: String, callerAddress: String?, liveDeviceIds: Set<String>): DeviceData? =
    reach.filter { it.device.castDeviceId == castDeviceId && (it.app.platform in RELAY_PLATFORMS) && it.app.deviceId in liveDeviceIds &&
        isNearby(callerAddress, it.app.lastPublicAddress) }
        .maxByOrNull { it.reportedAt }?.app

/** Review item 6 — what an app plays, from its `plays=`; an app that declared nothing (an older build) plays video. */
internal fun appCapabilities(features: Set<String>, platform: String?): TargetCapabilities {
    val declared = features.filter { it.startsWith("plays:") }.map { it.removePrefix("plays:") }.toSet()
    val plays = if (declared.isEmpty()) setOf("video") else declared
    return TargetCapabilities(video = "video" in plays, audio = "music" in plays, display = "video" in plays, book = "book" in plays)
}

/**
 * FR-R370-1/-2, review items 6, 7, 12; owner decisions 1–3 — the places [viewer] may start on: Ravilo apps holding their
 * socket (own anywhere, other members' when nearby), Cast devices a relay app on the viewer's network sees, and *Not
 * reachable* receivers seen in the last 24 h. Never a group. [busyOf] gives what plays on a place (as the viewer sees it).
 */
internal fun buildTargets(
    viewer: DeviceData, apps: List<LiveApp>, reach: List<ReachEntry>, receivers: List<DeviceData>,
    receiverCastDevice: Map<String, String>, busyOf: (placeId: String, castDeviceId: String?) -> SessionView?, nowMs: Long,
): List<PlaybackTarget> {
    val out = mutableListOf<PlaybackTarget>()
    // Ravilo apps (a TV only while Ravilo is open on it: it holds a socket only on screen, R293).
    for (a in apps) {
        val d = a.device
        if (d.kind == "cast" || d.kind == "screen") continue
        if (d.jellyfinUserId != viewer.jellyfinUserId && !isNearby(viewer.lastPublicAddress, d.lastPublicAddress)) continue
        if (out.any { it.id == d.deviceId }) continue
        out += PlaybackTarget(
            id = d.deviceId, kind = "app", name = d.displayName.ifBlank { d.deviceId }, icon = targetIcon(d.platform, d.kind),
            capabilities = appCapabilities(a.features, d.platform), busy = busyOf(d.deviceId, null), here = d.deviceId == viewer.deviceId,
        )
    }
    // Cast devices a relay app on the viewer's network reports.
    val nearbyReach = reach.filter { isNearby(viewer.lastPublicAddress, it.app.lastPublicAddress) && it.app.platform in RELAY_PLATFORMS }
    for (e in nearbyReach.distinctBy { it.device.castDeviceId }) {
        val speaker = e.device.kind == "speaker"
        out += PlaybackTarget(
            id = "cast:${e.device.castDeviceId}", kind = "cast", name = e.device.name, icon = if (speaker) "speaker" else "tv",
            capabilities = TargetCapabilities(video = !speaker, audio = true, display = !speaker, book = false),
            busy = busyOf("cast:${e.device.castDeviceId}", e.device.castDeviceId), castDeviceId = e.device.castDeviceId,
        )
    }
    // Not reachable (owner decision 3): receivers seen in the last 24 h that no relay app on this network sees.
    val reachable = nearbyReach.map { it.device.castDeviceId }.toSet()
    for (r in receivers) {
        if (nowMs - r.lastSeen > TARGET_UNREACHABLE_WINDOW_MS) continue
        val castId = receiverCastDevice[r.deviceId]
        if (castId != null && castId in reachable) continue
        if (r.jellyfinUserId != viewer.jellyfinUserId && !isNearby(viewer.lastPublicAddress, r.lastPublicAddress)) continue
        val name = r.displayName.removePrefix("${CastService.DEVICE_PREFIX} · ").ifBlank { r.deviceId }
        if (out.any { it.name.equals(name, ignoreCase = true) }) continue
        val speaker = r.platform == CastService.AUDIO_PLATFORM
        out += PlaybackTarget(
            id = castId?.let { "cast:$it" } ?: r.deviceId, kind = "cast", name = name, icon = if (speaker) "speaker" else "tv",
            capabilities = TargetCapabilities(video = !speaker, audio = true, display = !speaker), reachable = false,
            busy = busyOf(r.deviceId, castId), castDeviceId = castId, reason = "no_relay", lastSeen = r.lastSeen,
        )
    }
    return out
}

/** R370 — the outcome of a start. */
internal sealed interface StartResult2 {
    data class Started(val session: SessionRec, val loadHere: Boolean, val castDeviceId: String?) : StartResult2
    data object Busy : StartResult2
    data object Forbidden : StartResult2
    data object Unreachable : StartResult2
    data object BadRequest : StartResult2
}

/**
 * R370 — the start route's work (FR-R370-3/-4/-5, review items 1, 8, 10): creates the `starting` session, then loads
 * it — `session_load` to an app, the caller's own LOAD (when its discovery sees the Cast device), or a relay launch.
 */
class SessionStarter(
    private val sessions: PlaybackSessions,
    private val control: SessionControl,
    private val devices: RaviloDeviceService,
    private val bus: TvEventBus,
    private val castService: CastService,
    internal val reach: CastReach,
    private val serverUrl: () -> String,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    /** Main: the music queue's facts for a relay LOAD (title, artist, cover …). */
    internal var trackItem: suspend (String) -> CastTrackItem? = { null }

    internal suspend fun liveApps(): List<LiveApp> = bus.liveSockets().mapNotNull { (u, d, f) ->
        devices.listSessions(d).firstOrNull { it.jellyfinUserId == u }?.let { LiveApp(it, f) }
    }

    /** `targets_changed` to every `features=sessions` socket (review item 11) — the open sheet re-reads. */
    fun targetsChanged() = bus.notifySessions { _, _ -> """{"type":"targets_changed"}""" }

    internal suspend fun start(caller: DeviceData, req: SessionStartRequest): StartResult2 {
        if (req.items.isEmpty()) return StartResult2.BadRequest
        val kind = req.kind
        // Owner decision 4 — books never cast.
        val castTarget = req.targetId.startsWith("cast:")
        if (castTarget && kind == SessionKind.AUDIOBOOK) return StartResult2.BadRequest
        // Review item 10 — replacing a busy place; across users only with 304's switch on.
        req.replace?.let { r ->
            val busy = sessions.get(r.sessionId)
            if (busy != null && busy.live) {
                if (!control.mayControl(caller, false, busy)) return StartResult2.Forbidden
                sessions.end(busy.id, "replaced", by = caller)
                if (bus.isConnected(busy.targetId)) bus.notifyPlaystateCommand(busy.ownerUserId, busy.targetId, "Stop", null)
            }
        }
        val items = req.items.map { id -> sessions.describe(id, null)?.second ?: SessionItem(id) }
        val options = SessionOptions(shuffle = req.shuffle, repeat = req.repeat)
        val startMs = req.startMs ?: 0L
        if (!castTarget) {
            val target = liveApps().firstOrNull { it.device.deviceId == req.targetId }?.device ?: return StartResult2.Unreachable
            val busy = sessions.all().firstOrNull { it.live && it.targetId == target.deviceId && it.lane == sessionLane(kind) }
            if (busy != null && req.replace == null && req.moveOf == null) return StartResult2.Busy
            val rec = sessions.createStarting(caller, "app", target.deviceId, sessions.placeNameOf(target), kind, items, req.index, startMs, options, caller)
            val tracks = if (kind == SessionKind.MUSIC) req.items.mapNotNull { trackItem(it) } else emptyList()
            val env = SessionLoadEnvelope(sessionId = rec.id, kind = kind, items = req.items, index = req.index, startMs = startMs,
                shuffle = req.shuffle, repeat = req.repeat, title = items.getOrNull(req.index)?.title, tracks = tracks)
            bus.notifyDevice(target.jellyfinUserId, target.deviceId, json.encodeToString(SessionLoadEnvelope.serializer(), env))
            return StartResult2.Started(rec, loadHere = false, castDeviceId = null)
        }
        val castId = req.targetId.removePrefix("cast:")
        val seen = reach.entries().filter { it.device.castDeviceId == castId }
        val name = seen.firstOrNull()?.device?.name ?: castId
        val callerSees = seen.any { it.app.deviceId == caller.deviceId }
        val rec = sessions.createStarting(caller, "cast", "cast:$castId", name, kind, items, req.index, startMs, options, caller, castDeviceId = castId)
        if (callerSees && caller.platform in RELAY_PLATFORMS) return StartResult2.Started(rec, loadHere = true, castDeviceId = castId)
        val live = bus.liveSockets().map { it.second }.toSet()
        val relay = chooseRelayApp(seen, castId, caller.lastPublicAddress, live)
        if (relay == null) { sessions.end(rec.id, "failed"); return StartResult2.Unreachable }
        if (!relayLaunch(caller, relay, rec, castId, items, req)) { sessions.end(rec.id, "failed"); return StartResult2.Unreachable }
        return StartResult2.Started(rec, loadHere = false, castDeviceId = castId)
    }

    /** Owner decision 1 — the relay launch: a hand-off minted for the person who started it, sent to the relay app. */
    internal suspend fun relayLaunch(owner: DeviceData, relay: DeviceData, rec: SessionRec, castId: String, items: List<SessionItem>, req: SessionStartRequest): Boolean {
        val code = runCatching { castService.mint(owner, castId, rec.id) }.getOrNull() ?: return false
        val cur = items.getOrNull(req.index) ?: items.first()
        val music = rec.kind == SessionKind.MUSIC
        val tracks = if (music) items.map { it.id }.mapNotNull { trackItem(it) } else emptyList()
        val load = CastLoadData(
            serverUrl = serverUrl(), code = code.code, itemId = cur.id, title = cur.title ?: "", kicker = cur.subtitle,
            artUrl = cur.artwork?.let { if (it.startsWith("http")) it else serverUrl().trimEnd('/') + it },
            positionMs = req.startMs, tracks = tracks, currentIndex = if (music) req.index else -1,
            repeat = req.repeat, shuffle = req.shuffle, sessionId = rec.id,
        )
        val env = CastRelayLoadEnvelope(sessionId = rec.id, castDeviceId = castId, load = load)
        bus.notifyDevice(relay.jellyfinUserId, relay.deviceId, json.encodeToString(CastRelayLoadEnvelope.serializer(), env))
        sessions.record(rec.id, "relayed", relay.deviceId, castId)
        return true
    }

    /** R369/R372 — `play` on a paused, offline cast session from an app without Cast: the relay launches it again. */
    internal suspend fun relayResume(s: SessionRec): Boolean {
        val castId = s.castDeviceId ?: sessions.castDeviceOfReceiver(s.targetId) ?: return false
        val owner = devices.listSessions(s.startedByDeviceId ?: return false).firstOrNull { it.jellyfinUserId == s.ownerUserId } ?: return false
        val live = bus.liveSockets().map { it.second }.toSet()
        val relay = chooseRelayApp(reach.entries(), castId, owner.lastPublicAddress, live) ?: return false
        val ids = s.options.queueIds.ifEmpty { listOf(s.itemId) }
        val items = ids.map { id -> if (id == s.itemId) s.current ?: SessionItem(id) else sessions.describe(id, s.bookId)?.second ?: SessionItem(id) }
        return relayLaunch(owner, relay, s, castId, items,
            SessionStartRequest(targetId = "cast:$castId", kind = s.kind, items = ids, index = s.queueIndex.coerceIn(0, ids.lastIndex), startMs = s.positionMs,
                shuffle = s.options.shuffle, repeat = s.options.repeat ?: "off"))
    }

    internal suspend fun relayAppFor(s: SessionRec): DeviceData? {
        val castId = s.castDeviceId ?: sessions.castDeviceOfReceiver(s.targetId) ?: return null
        val owner = devices.listSessions(s.startedByDeviceId ?: return null).firstOrNull() ?: return null
        return chooseRelayApp(reach.entries(), castId, owner.lastPublicAddress, bus.liveSockets().map { it.second }.toSet())
    }

    internal suspend fun relayAvailable(s: SessionRec): Boolean {
        val castId = s.castDeviceId ?: sessions.castDeviceOfReceiver(s.targetId) ?: return false
        val owner = devices.listSessions(s.startedByDeviceId ?: return false).firstOrNull() ?: return false
        return chooseRelayApp(reach.entries(), castId, owner.lastPublicAddress, bus.liveSockets().map { it.second }.toSet()) != null
    }

    /** `GET /api/tv/playback/targets` for [viewer]. */
    internal suspend fun targetsFor(viewer: DeviceData, viewOf: suspend (SessionRec) -> SessionView?): List<PlaybackTarget> {
        val all = sessions.all().filter { it.live }
        val byPlace = HashMap<String, SessionView?>()
        for (s in all) {
            val v = viewOf(s)
            byPlace[s.targetId] = v
            (s.castDeviceId ?: sessions.castDeviceOfReceiver(s.targetId))?.let { byPlace["cast:$it"] = v }
        }
        val receivers = devices.allDevices().filter { it.kind == "cast" }.groupBy { it.deviceId }.map { (_, rows) -> rows.maxByOrNull { it.lastSeen }!! }
        return buildTargets(viewer, liveApps(), reach.entries(), receivers, sessions.receiversByCastDevice().entries.associate { (c, r) -> r to c },
            { place, castId -> byPlace[place] ?: castId?.let { byPlace["cast:$it"] } }, clock())
    }

    /** The `cast_devices_seen` socket message (owner decision 1). */
    suspend fun onCastDevicesSeen(app: DeviceData, text: String) {
        val obj = runCatching { Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
        if ((obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "cast_devices_seen") return
        val arr = obj["devices"] as? kotlinx.serialization.json.JsonArray ?: return
        val list = arr.mapNotNull { runCatching { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(CastSeenDevice.serializer(), it) }.getOrNull() }
        reach.report(app, list)
        targetsChanged()
    }
}

