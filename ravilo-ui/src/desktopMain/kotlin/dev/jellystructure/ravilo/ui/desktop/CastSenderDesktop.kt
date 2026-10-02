package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.castv2.CastDevice
import dev.jellystructure.ravilo.castv2.CastMediaStatus
import dev.jellystructure.ravilo.castv2.CastSession
import dev.jellystructure.ravilo.castv2.openCastTransport
import dev.jellystructure.ravilo.ui.DesktopApp
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastMediaSnapshot
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.ravilo.ui.seams.CastSender
import dev.jellystructure.ravilo.ui.seams.foldReceiverMessage
import dev.jellystructure.ravilo.ui.seams.castStatusWithQueue
import dev.jellystructure.ravilo.ui.seams.isCastBurnIn
import dev.jellystructure.ravilo.ui.seams.mergeCastStatus
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.CastQueueAssembly
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import dev.jellystructure.shared.tv.castLoadLog
import dev.jellystructure.shared.tv.castLoadPlan
import dev.jellystructure.shared.tv.newCastQueueId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * R330 (FR-R330-3) — the Mac's own Chromecast sender, over `:ravilo-castv2`. It is to the common remote what
 * `CastSenderAndroid` is on the phone: [load] launches our receiver (or joins it), connects, and sends the same
 * `LOAD` the phone's SDK sends — `CastLoadData` as `customData`, so the receiver cannot tell the Mac from the phone
 * (dev review 11); [status] is rebuilt by the shared [mergeCastStatus] from the media channel plus our channel.
 *
 * All state is touched on one thread (a single-parallelism dispatcher). A connection that drops is rejoined, up to
 * three times; the app closing, being replaced, or an explicit stop ends it in silence (FR-R245-5). On start, a
 * device this Mac last cast to that still runs Ravilo with something loaded is rejoined silently (FR-R330-5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object CastSenderDesktop : CastSender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val json = RaviloWireJsonWithDefaults
    private const val LAST_DEVICE = "cast.last_device"

    private val _link = MutableStateFlow(CastLinkState.NONE)
    override val link: StateFlow<CastLinkState> = _link.asStateFlow()
    private val _device = MutableStateFlow<String?>(null)
    override val deviceName: StateFlow<String?> = _device.asStateFlow()
    private val _status = MutableStateFlow<CastRemoteStatus?>(null)
    override val status: StateFlow<CastRemoteStatus?> = _status.asStateFlow()
    private val _volume = MutableStateFlow<Double?>(null)
    /** The device's (or group's) own volume, from its receiver status — what the capsule's slider shows while casting. */
    override val volume: StateFlow<Double?> = _volume.asStateFlow()
    private val _connectedId = MutableStateFlow<String?>(null)
    /** The device a session is open to, for the sheet's selected row. */
    val connectedDeviceId: StateFlow<String?> = _connectedId.asStateFlow()

    @Volatile private var appId: String? = null
    private var session: CastSession? = null
    private var device: CastDevice? = null
    private var said: CastReceiverMessage? = null
    /** R356 — the queue revision last asked for with `get_queue`. */
    private var askedQueueRev: Int? = null
    /** R359 — the receiver's queue, while it arrives in parts. */
    private val queueParts = CastQueueAssembly()
    private var pendingLoad: CastLoadData? = null
    private var watch: Job? = null
    private var reconnectTried = false
    /** The session [stop] was asked for, until it has closed. */
    @Volatile private var stopping: CastSession? = null

    fun appIdForDebug(): String? = appId

    override fun setAppId(appId: String) {
        scope.launch {
            this@CastSenderDesktop.appId = appId
            if (!reconnectTried) { reconnectTried = true; reconnect() }
        }
    }

    /** The sheet's row: open a session to [d], launching our app there if it is not running. */
    fun connect(d: CastDevice) { scope.launch { open(d, launch = true) } }

    private suspend fun open(d: CastDevice, launch: Boolean, confirm: suspend (CastSession) -> Boolean = { true }): Boolean {
        val app = appId ?: return false
        // A device that was told to stop (music moving on to another one) gets the moment it needs to hear it:
        // closing the connection at once could drop the STOP still waiting to be written.
        stopping?.let { old -> stopping = null; if (old === session) withTimeoutOrNull(1_500) { old.state.first { it == CastSession.State.CLOSED } } }
        closeQuietly()
        // Nothing the device before said describes this one — its receiver id least of all: sent along with the
        // first LOAD, it made the new device's receiver ask to enrol again on its own next load (289 FR-289-7).
        _status.value = null; _volume.value = null; said = null
        _link.value = if (launch) CastLinkState.CONNECTING else CastLinkState.RECONNECTING
        _device.value = d.name
        val s = runCatching { CastSession(openCastTransport(d.host, d.port), app, scope).also { it.start() } }.getOrNull()
        val joined = s != null && (if (launch) s.launchOrJoin() else s.joinIfRunning()) && confirm(s)
        println("${DesktopLog.stamp()} cast: ${if (launch) "connect" else "rejoin"} ${d.name} [${d.model}] → ${if (s == null) "no connection" else if (joined) "joined" else "not joined (${s.closedReason ?: "no app"})"}")
        if (s == null || !joined) {
            s?.close("not joined")
            _link.value = CastLinkState.NONE; _device.value = null
            return false
        }
        session = s; device = d; said = null
        _connectedId.value = d.id
        DesktopApp.prefs.put(LAST_DEVICE, d.id)
        observe(s)
        _link.value = CastLinkState.CONNECTED
        pendingLoad?.let { pendingLoad = null; loadNow(it) }
        return true
    }

    private fun observe(s: CastSession) {
        watch?.cancel()
        watch = scope.launch {
            launch { s.media.collect { rebuild(null) } }
            launch { s.receiver.collect { r -> r?.volumeLevel?.let { _volume.value = it } } }
            launch {
                s.custom.collect { raw ->
                    val msg = runCatching { json.decodeFromString(CastReceiverMessage.serializer(), raw) }.getOrNull() ?: return@collect
                    // R359 (FR-R359-5) — a queue too long for one message comes in parts after its status; whole, it is
                    // held as if the status had carried it. A part is not a state report: nothing else changes.
                    if (msg.type == "queue_part") {
                        queueParts.part(msg)?.let { whole ->
                            println("${DesktopLog.stamp()} cast: the receiver's queue (${whole.size} songs, rev ${msg.queueRev}) came in parts")
                            _status.value = castStatusWithQueue(_status.value, whole, msg.queueRev, said?.queueIndex)
                        }
                        return@collect
                    }
                    if (msg.queue != null) queueParts.reset()
                    // R356 (FR-R356-9) — a queue revision this app does not hold, without the queue: ask for it once.
                    if (dev.jellystructure.ravilo.ui.seams.castQueueGap(_status.value, msg) && askedQueueRev != msg.queueRev) {
                        askedQueueRev = msg.queueRev
                        println("${DesktopLog.stamp()} cast: queue revision ${msg.queueRev} not held; asking for it")
                        s.sendCustom(json.encodeToString(CastCommand.serializer(), CastCommand("get_queue")))
                    }
                    said = foldReceiverMessage(said, msg)
                    rebuild(msg.type)
                }
            }
            // 289 — what the receiver's player did (a load, an error's code, why an item ended), into this app's log:
            // a speaker has no screen and no DevTools, and "it went quiet" is otherwise all anyone can say.
            launch {
                s.notes.collect { raw ->
                    val note = runCatching { json.parseToJsonElement(raw).jsonObject["note"]?.jsonPrimitive?.content }.getOrNull() ?: return@collect
                    println("${DesktopLog.stamp()} cast: ${device?.name} · $note")
                }
            }
            // Android's progress listener: the position moves on between reports, and the card follows.
            launch {
                while (isActive) {
                    delay(1_000)
                    if (s.media.value?.playerState == "PLAYING") rebuild(null)
                    CastNowPlaying.update(_status.value)
                }
            }
        }
        // Outside [watch]: closing cancels the watchers, and the rejoin must outlive them.
        scope.launch {
            s.state.first { it == CastSession.State.CLOSED }
            onClosed(s)
        }
    }

    private fun rebuild(event: String?) {
        val s = session ?: return
        _status.value = mergeCastStatus(_status.value, said, snapshot(s.media.value), event, System.currentTimeMillis())
    }

    private fun snapshot(m: CastMediaStatus?) = CastMediaSnapshot(
        playerState = m?.playerState,
        idleFinished = m?.playerState == "IDLE" && m.idleReason == "FINISHED",
        positionMs = m?.positionMs(),
        durationMs = m?.durationSec?.let { (it * 1000).toLong() },
        activeTrackIds = m?.activeTrackIds?.toSet() ?: emptySet(),
        mediaTrackIds = m?.trackIds?.toSet(),
        title = m?.title,
        subtitle = m?.subtitle,
        imageUrl = m?.imageUrl,
    )

    private suspend fun onClosed(s: CastSession) {
        if (session !== s) return
        val reason = s.closedReason
        val d = device
        println("${DesktopLog.stamp()} cast: session to ${d?.name} closed ($reason)")
        session = null
        watch?.cancel(); watch = null
        val silent = reason == null || reason == "stopped" || reason == "replaced by another app" || reason == "the device closed the app"
        if (!silent && d != null) {
            // The connection dropped, the app did not end: rejoin it, as the phone's SDK does.
            repeat(3) {
                delay(2_000)
                val again = CastDiscovery.devices.value.firstOrNull { it.id == d.id } ?: d
                if (open(again, launch = false)) return
            }
        }
        endQuietly()
    }

    private fun endQuietly() {
        _link.value = CastLinkState.NONE
        _device.value = null
        _status.value = null
        _volume.value = null
        _connectedId.value = null
        said = null; device = null
        CastNowPlaying.end()
    }

    private fun closeQuietly() {
        val s = session ?: return
        session = null
        watch?.cancel(); watch = null
        s.close("switched")
    }

    /** FR-R330-5 — start-up: rejoin the last device if it still runs Ravilo with something loaded; otherwise nothing. */
    private suspend fun reconnect() {
        val lastId = DesktopApp.prefs.get(LAST_DEVICE) ?: return
        if (session != null) return
        CastDiscovery.acquire()
        try {
            val d = withTimeoutOrNull(15_000) { CastDiscovery.devices.first { list -> list.any { it.id == lastId } } }
                ?.firstOrNull { it.id == lastId } ?: return
            open(d, launch = false) { s ->
                val media = withTimeoutOrNull(3_000) { s.media.first { it != null } }
                media != null && media.playerState != "IDLE"
            }
            if (session != null) send(json.encodeToString(CastCommand.serializer(), CastCommand("status")))
        } finally {
            CastDiscovery.release()
        }
    }

    // ── the seam ──

    override fun load(data: CastLoadData) { scope.launch { if (session == null) pendingLoad = data else loadNow(data) } }

    private fun loadNow(data: CastLoadData) {
        val s = session ?: run { pendingLoad = data; return }
        // R359 (FR-R359-1/3) — a queue too long for one message goes as a window, the rest at once in parts.
        val plan = castLoadPlan(data, newCastQueueId(), json)
        val media = castLoadMedia(plan.load, json)
        said = null
        queueParts.reset()
        // The remote holds the whole queue from the start; the receiver says each song's place in the whole of it.
        _status.value = CastRemoteStatus(itemId = data.itemId, title = data.title, kicker = data.kicker, artUrl = data.artUrl, loaded = true, buffering = true,
            music = data.tracks.isNotEmpty(), queue = data.tracks, queueIndex = data.currentIndex, repeat = data.repeat, shuffle = data.shuffle)
        // FR-R359-2 — CastLoadData rides once, as the media's customData (the one the receiver reads); the request's is left out.
        val bytes = s.load(media, (data.positionMs ?: 0L) / 1000.0, autoplay = true, customData = null)
        // The parts go after the LOAD (the receiver also holds a part that overtakes it).
        if (plan.parts.isNotEmpty()) scope.launch { plan.parts.forEach { s.sendCustom(json.encodeToString(CastCommand.serializer(), it)) } }
        // FR-R359-7 — the LOAD's size and the number of parts, so a future limit is visible here.
        println("${DesktopLog.stamp()} cast: load ${if (data.tracks.isNotEmpty()) "music, ${castLoadLog(data.tracks.size, bytes, plan.parts.size)}" else "a film, ${(bytes + 512) / 1024} KB"} on ${device?.name}")
    }

    override fun play() { scope.launch { session?.play() } }
    override fun pause() { scope.launch { session?.pause() } }
    override fun seekTo(positionMs: Long) { scope.launch { session?.seek(positionMs) } }

    /** FR-R245-10 — only ever explicit: the app stops on the device, and nothing is rejoined next time. */
    override fun stop() {
        stopping = session
        scope.launch {
            DesktopApp.prefs.put(LAST_DEVICE, null)
            session?.stopApp() ?: endQuietly()
        }
    }

    override fun send(json: String) { scope.launch { session?.sendCustom(json) } }

    override fun setVolume(level: Double) { scope.launch { _volume.value = level.coerceIn(0.0, 1.0); session?.setVolume(level) } }

    private fun command(type: String, index: Int) =
        session?.sendCustom(json.encodeToString(CastCommand.serializer(), CastCommand(type, index = index)))

    /** R285 (FR-R285-4) — text to text is a CAF track switch; a burn-in either side is the receiver's restream. */
    override fun selectSubtitle(trackId: Long?) {
        scope.launch {
            val s = session ?: return@launch
            val snap = snapshot(s.media.value)
            val subs = said?.subtitleTracks ?: emptyList()
            val target = subs.indexOfFirst { it.trackId == trackId }
            val burnedNow = isCastBurnIn(subs.getOrNull(said?.selectedSub ?: -1), snap)
            if (burnedNow || isCastBurnIn(subs.getOrNull(target), snap)) { command("subtitle", if (trackId == null) -1 else target); return@launch }
            val textIds = subs.mapNotNull { it.trackId }.toSet()
            s.setActiveTracks(snap.activeTrackIds.filter { it !in textIds } + listOfNotNull(trackId))
        }
    }

    /** R285 (FR-R285-4) — an HLS cast carries one audio track: changing it is the receiver's restream. */
    override fun selectAudio(trackId: Long?) {
        scope.launch {
            val s = session ?: return@launch
            val snap = snapshot(s.media.value)
            val position = said?.audioTracks?.indexOfFirst { it.trackId != null && it.trackId == trackId } ?: -1
            if (position >= 0 && snap.mediaTrackIds?.none { it == trackId } != false) { command("audio", position); return@launch }
            val textIds = (said?.subtitleTracks ?: emptyList()).mapNotNull { it.trackId }.toSet()
            s.setActiveTracks(snap.activeTrackIds.filter { it in textIds } + listOfNotNull(trackId))
        }
    }
}

/**
 * The LOAD's media for [data]: the receiver resolves contentId itself (it enrols and negotiates its own ticket), so no
 * media URL leaves the Mac; `CastLoadData` rides as its customData — once (R359 FR-R359-2).
 */
internal fun castLoadMedia(data: CastLoadData, json: Json = RaviloWireJsonWithDefaults): JsonObject {
    val song = data.tracks.getOrNull(data.currentIndex)
    fun abs(url: String?) = url?.let { if (it.startsWith("http")) it else data.serverUrl.trimEnd('/') + it }
    val metadata = buildJsonObject {
        if (song != null) {
            // R324 (FR-R324-9) — a song's card: cover · title · artist; the receiver rewrites it per song.
            put("metadataType", 3)
            put("title", song.title)
            song.artist?.let { put("artist", it) }
            song.album?.let { put("albumName", it) }
            abs(song.coverUrl ?: data.artUrl)?.let { url -> putJsonArray("images") { add(buildJsonObject { put("url", url) }) } }
        } else {
            put("metadataType", 1)
            put("title", data.title)
            data.kicker?.let { put("subtitle", it) }
            data.artUrl?.let { url -> putJsonArray("images") { add(buildJsonObject { put("url", url) }) } }
        }
    }
    return buildJsonObject {
        put("contentId", "ravilo://${data.itemId}")
        put("streamType", "BUFFERED")
        put("contentType", if (song != null) "audio/mpeg" else "application/x-mpegURL")
        put("metadata", metadata)
        put("customData", json.encodeToJsonElement(CastLoadData.serializer(), data))
    }
}

/**
 * R330 (FR-R330-7, D4) — while the Mac casts, its Now Playing card is the speaker's (or the TV's): the song, the
 * position, and the media keys going to the receiver — play, pause, next and previous for music, seeking for both.
 */
internal object CastNowPlaying {
    private val json = RaviloWireJsonWithDefaults
    private val target = MacNowPlaying.Target { command, seconds ->
        val sender = CastSenderDesktop
        val st = sender.status.value
        when (command) {
            MacNowPlaying.Command.PLAY -> sender.play()
            MacNowPlaying.Command.PAUSE, MacNowPlaying.Command.STOP -> sender.pause()
            MacNowPlaying.Command.TOGGLE -> if (st?.playing == true) sender.pause() else sender.play()
            MacNowPlaying.Command.NEXT -> sender.send(json.encodeToString(CastCommand.serializer(), CastCommand("next")))
            MacNowPlaying.Command.PREVIOUS -> sender.send(json.encodeToString(CastCommand.serializer(), CastCommand("prev")))
            MacNowPlaying.Command.SEEK_TO -> sender.seekTo((seconds * 1000).toLong())
            MacNowPlaying.Command.SKIP_FORWARD -> sender.seekTo((st?.positionMs ?: 0L) + (seconds * 1000).toLong())
            MacNowPlaying.Command.SKIP_BACK -> sender.seekTo((st?.positionMs ?: 0L) - (seconds * 1000).toLong())
        }
    }
    private var mode: MacNowPlaying.Mode? = null

    fun update(st: CastRemoteStatus?) {
        if (st == null || !st.loaded) { end(); return }
        val wanted = if (st.music) MacNowPlaying.Mode.MUSIC else MacNowPlaying.Mode.FILM
        if (mode != wanted || !MacNowPlaying.owns(target)) { MacNowPlaying.claim(target, wanted); mode = wanted }
        val song = st.queue.getOrNull(st.queueIndex)
        MacNowPlaying.artwork(target, song?.coverUrl?.takeIf { it.startsWith("http") } ?: st.artUrl)
        MacNowPlaying.update(target, song?.title ?: st.title.orEmpty(), song?.artist ?: st.kicker, song?.album,
            st.durationMs.takeIf { it > 0 } ?: -1L, st.positionMs, 1.0, st.playing, video = !st.music)
    }

    fun end() {
        if (mode == null) return
        mode = null
        MacNowPlaying.release(target)
    }
}
