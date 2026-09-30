package dev.jellystructure.ravilo.castv2

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * R330 (FR-R330-1) — one sender's conversation with one Cast device, over any [CastTransport]:
 *
 * - [start] opens the platform connection (`CONNECT` to `receiver-0`), starts reading, and pings every
 *   [heartbeatMs]; [maxMissedPongs] pings without any answer close the session (dev review 4: CAF drops a quiet
 *   sender after ~10 s, so three missed 5 s pings is the right rule). Any message from the device counts as an
 *   answer, and a `PING` from it is answered with `PONG`.
 * - [appAvailable] asks `GET_APP_AVAILABILITY` (D2); [launchOrJoin] joins [appId] when it already runs (FR-R330-4)
 *   and otherwise `LAUNCH`es it — replacing whatever ran, the sheet having asked first — then `CONNECT`s to its
 *   transport and asks the media channel for its status.
 * - Media commands carry the last `MEDIA_STATUS`'s `mediaSessionId`; our own channel ([CastNamespaces.RAVILO])
 *   is carried as it is, both ways.
 *
 * The app closing or being replaced by another sender's app ends the session ([closedReason]). Nothing here knows
 * what Ravilo plays; `CastSenderDesktop` (ravilo-ui) turns it into the `CastSender` seam.
 */
class CastSession(
    private val transport: CastTransport,
    val appId: String,
    parent: CoroutineScope,
    private val senderId: String = DEFAULT_SENDER,
    private val heartbeatMs: Long = 5_000L,
    private val maxMissedPongs: Int = 3,
    private val requestTimeoutMs: Long = 10_000L,
    /** After a LAUNCH that was not answered with the app: how often, and how many times, the device is asked for it. */
    private val launchPollMs: Long = 2_000L,
    private val launchPolls: Int = 10,
) {
    enum class State { CONNECTING, CONNECTED, JOINED, CLOSED }

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val outbox = Channel<CastMessage>(Channel.UNLIMITED)
    private val reader = CastFrameReader()
    private val lock = Mutex()
    private val pending = mutableMapOf<Int, CompletableDeferred<JsonObject>>()
    private var nextRequestId = 1
    private var unanswered = 0

    private val _state = MutableStateFlow(State.CONNECTING)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _receiver = MutableStateFlow<CastReceiverStatus?>(null)
    val receiver: StateFlow<CastReceiverStatus?> = _receiver.asStateFlow()
    private val _media = MutableStateFlow<CastMediaStatus?>(null)
    val media: StateFlow<CastMediaStatus?> = _media.asStateFlow()
    private val _custom = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Every payload the app sent on [CastNamespaces.RAVILO]. */
    val custom: SharedFlow<String> = _custom.asSharedFlow()
    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Every payload the app sent on [CastNamespaces.RAVILO_LOG] — for the log only (289). */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    /** The app this session joined, once [launchOrJoin] succeeded. */
    var app: CastApp? = null
        private set
    var closedReason: String? = null
        private set

    fun start() {
        scope.launch { for (m in outbox) runCatching { transport.send(m.frame()) }.onFailure { close("send failed") } }
        scope.launch {
            try {
                for (chunk in transport.incoming) for (m in reader.feed(chunk)) dispatch(m)
            } catch (e: Exception) {
                close("unreadable stream: ${e.message}")
            }
            close("the device closed the connection")
        }
        post(CastNamespaces.CONNECTION, PLATFORM_RECEIVER, obj("type" to "CONNECT"))
        scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                if (unanswered >= maxMissedPongs) { close("no answer to $maxMissedPongs pings"); break }
                unanswered++
                post(CastNamespaces.HEARTBEAT, PLATFORM_RECEIVER, obj("type" to "PING"))
            }
        }
        _state.value = State.CONNECTED
    }

    // ── questions ──

    suspend fun requestReceiverStatus(): CastReceiverStatus? =
        request(CastNamespaces.RECEIVER, PLATFORM_RECEIVER, obj("type" to "GET_STATUS"))?.let(CastParse::receiverStatus)

    /** D2 — whether [appId] can run on this device at all. */
    suspend fun appAvailable(): Boolean = appAvailableOrNull() ?: false

    /** As [appAvailable], but null when the device gave no answer in time — not a "no", and not to be remembered as one. */
    suspend fun appAvailableOrNull(): Boolean? =
        request(CastNamespaces.RECEIVER, PLATFORM_RECEIVER, obj("type" to "GET_APP_AVAILABILITY", "appId" to listOf(appId)))
            ?.let { CastParse.available(it, appId) }

    /** FR-R330-3/4 — joins [appId] when it runs, else launches it; true once its transport is connected. */
    suspend fun launchOrJoin(): Boolean {
        val running = (requestReceiverStatus() ?: return false).apps.firstOrNull { it.appId == appId }
        var target = running
        if (target == null) {
            val answer = request(CastNamespaces.RECEIVER, PLATFORM_RECEIVER, obj("type" to "LAUNCH", "appId" to appId))
            if (answer != null && CastParse.type(answer) == "LAUNCH_ERROR") return false
            target = answer?.let(CastParse::receiverStatus)?.apps?.firstOrNull { it.appId == appId }
            // A TV asleep wakes for the launch and answers late — after the request has given up, or with a status
            // from before the app was up. The app is asked for until it is there (found on a BRAVIA, 2026-09-30: the
            // receiver came up on the TV while the sender had already dropped the connection).
            var tries = 0
            while (target == null && tries++ < launchPolls && _state.value != State.CLOSED) {
                delay(launchPollMs)
                target = requestReceiverStatus()?.apps?.firstOrNull { it.appId == appId }
            }
        }
        join(target ?: return false)
        return true
    }

    /** FR-R330-5 — joins [appId] only if it already runs; never launches. */
    suspend fun joinIfRunning(): Boolean {
        val running = requestReceiverStatus()?.apps?.firstOrNull { it.appId == appId } ?: return false
        join(running)
        return true
    }

    private fun join(target: CastApp) {
        app = target
        post(CastNamespaces.CONNECTION, target.transportId, obj("type" to "CONNECT"))
        _state.value = State.JOINED
        postRequest(CastNamespaces.MEDIA, target.transportId, obj("type" to "GET_STATUS"))
    }

    // ── commands ──

    /** `LOAD` on the media channel; [media] and [customData] are the caller's (FR-R330-3). */
    fun load(media: JsonObject, currentTimeSec: Double, autoplay: Boolean, customData: JsonObject?) {
        val a = app ?: return
        postRequest(CastNamespaces.MEDIA, a.transportId, obj(
            "type" to "LOAD", "sessionId" to a.sessionId, "media" to media, "autoplay" to autoplay,
            "currentTime" to currentTimeSec, "customData" to customData,
        ))
    }

    fun play() = mediaCommand("PLAY")
    fun pause() = mediaCommand("PAUSE")
    fun seek(positionMs: Long) = mediaCommand("SEEK", "currentTime" to positionMs.coerceAtLeast(0L) / 1000.0)
    fun stopMedia() = mediaCommand("STOP")
    fun queueNext() = mediaCommand("QUEUE_NEXT")
    fun queuePrev() = mediaCommand("QUEUE_PREV")
    fun setActiveTracks(ids: List<Long>) = mediaCommand("EDIT_TRACKS_INFO", "activeTrackIds" to ids)

    private fun mediaCommand(type: String, vararg extra: Pair<String, Any?>) {
        val a = app ?: return
        val id = _media.value?.mediaSessionId ?: return
        postRequest(CastNamespaces.MEDIA, a.transportId, obj("type" to type, "mediaSessionId" to id, *extra))
    }

    /** Our own channel ([CastNamespaces.RAVILO]): a `CastCommand`, already JSON. */
    fun sendCustom(json: String) {
        val a = app ?: return
        outbox.trySend(CastMessage(senderId, a.transportId, CastNamespaces.RAVILO, json))
    }

    /** FR-R324-5 — the device's (or group's) volume, 0.0–1.0. */
    fun setVolume(level: Double) =
        postRequest(CastNamespaces.RECEIVER, PLATFORM_RECEIVER, obj("type" to "SET_VOLUME", "volume" to mapOf("level" to level.coerceIn(0.0, 1.0))))

    /** Stops the app on the device — only ever explicit (FR-R245-10) — and ends the session. */
    fun stopApp() {
        val a = app ?: return close("stopped")
        postRequest(CastNamespaces.RECEIVER, PLATFORM_RECEIVER, obj("type" to "STOP", "sessionId" to a.sessionId))
        scope.launch { delay(300); close("stopped") }
    }

    /** Leaves the device as it is (the app plays on) and closes the connection. */
    fun close(reason: String = "closed") {
        if (_state.value == State.CLOSED) return
        closedReason = reason
        _state.value = State.CLOSED
        app?.let { outbox.trySend(CastMessage(senderId, it.transportId, CastNamespaces.CONNECTION, obj("type" to "CLOSE").toString())) }
        val later = scope.launch {
            delay(50)   // let the CLOSE go out
            teardown()
        }
        // The owner's scope is already gone (a timeout around the whole conversation): nothing will run later.
        if (later.isCancelled) teardown()
    }

    private fun teardown() {
        runCatching { transport.close() }
        pending.values.toList().forEach { it.cancel() }
        outbox.close()
        scope.cancel()
    }

    // ── the wire ──

    private fun post(namespace: String, destination: String, body: JsonObject) {
        outbox.trySend(CastMessage(senderId, destination, namespace, body.toString()))
    }

    private fun postRequest(namespace: String, destination: String, body: JsonObject) {
        scope.launch {
            val id = lock.withLock { nextRequestId++ }
            post(namespace, destination, JsonObject(body + ("requestId" to JsonPrimitive(id))))
        }
    }

    private suspend fun request(namespace: String, destination: String, body: JsonObject): JsonObject? {
        if (_state.value == State.CLOSED) return null
        val answer = CompletableDeferred<JsonObject>()
        val id = lock.withLock { (nextRequestId++).also { pending[it] = answer } }
        post(namespace, destination, JsonObject(body + ("requestId" to JsonPrimitive(id))))
        return try {
            withTimeoutOrNull(requestTimeoutMs) { answer.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            null
        } finally {
            lock.withLock { pending.remove(id) }
        }
    }

    private suspend fun dispatch(m: CastMessage) {
        unanswered = 0
        if (m.destinationId != senderId && m.destinationId != "*") return
        val body = parsePayload(m.payloadUtf8)
        when (m.namespace) {
            CastNamespaces.HEARTBEAT -> if (body != null && CastParse.type(body) == "PING") post(CastNamespaces.HEARTBEAT, m.sourceId, obj("type" to "PONG"))
            CastNamespaces.CONNECTION -> if (body != null && CastParse.type(body) == "CLOSE") {
                if (m.sourceId == PLATFORM_RECEIVER || m.sourceId == app?.transportId) close("the device closed the app")
            }
            CastNamespaces.RECEIVER -> if (body != null && CastParse.type(body) == "RECEIVER_STATUS") {
                val status = CastParse.receiverStatus(body)
                _receiver.value = status
                // Another sender launched something else: ours is gone (FR-R330-4's other side).
                val joined = app
                if (joined != null && status != null && status.apps.none { it.sessionId == joined.sessionId }) close("replaced by another app")
            }
            CastNamespaces.MEDIA -> if (body != null && CastParse.type(body) == "MEDIA_STATUS") {
                _media.value = CastParse.mediaStatus(body)?.withMediaFrom(_media.value)
            }
            CastNamespaces.RAVILO -> m.payloadUtf8?.let { _custom.tryEmit(it) }
            CastNamespaces.RAVILO_LOG -> m.payloadUtf8?.let { _notes.tryEmit(it) }
        }
        val id = body?.let(CastParse::requestId) ?: return
        if (id == 0) return
        lock.withLock { pending[id] }?.complete(body)
    }
}
