package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.SessionCommandRefusal
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionDetailEnvelope
import dev.jellystructure.shared.tv.SessionQueueReport
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * R369 — the app's side of controlling a session through the server: the remote that is open (attached as a
 * controller over the events socket, review item 7), its [SessionDetail] (seeded from the GET, then `session_detail`),
 * the commands (202 = sent; a 409 redraws), and the press in flight (FR-R369-6's dim / spinner / *Can't reach*).
 */
object SessionRemote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var api: TvApiClient? = null

    private val _outgoing = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Frames for the events socket (`attach_session` / `detach_session`). */
    val outgoing: SharedFlow<String> = _outgoing.asSharedFlow()

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail.asStateFlow()

    private val _openId = MutableStateFlow<String?>(null)
    val openId: StateFlow<String?> = _openId.asStateFlow()

    /** When the last press was sent and at which revision (nothing reflected it yet); null = none in flight. */
    private val _pending = MutableStateFlow<Pair<Long, Long>?>(null)
    val pending: StateFlow<Pair<Long, Long>?> = _pending.asStateFlow()

    private val _refusals = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** `unreachable` → the app shows `session.cant_reach` for the place. */
    val refusals: SharedFlow<String> = _refusals.asSharedFlow()

    private fun now() = Clock.System.now().toEpochMilliseconds()

    /** Opening a remote attaches this device as a controller (FR-R369-4) and fetches the detail. */
    fun open(id: String) {
        if (_openId.value != id) { _openId.value?.let { _outgoing.tryEmit(detachFrame(it)) }; _detail.value = null }
        _openId.value = id
        PlaybackSessions.touch(id)
        _outgoing.tryEmit(attachFrame(id))
        refresh()
        dev.jellystructure.ravilo.ui.seams.sessionLog("remote: open $id")
    }

    fun close() {
        _openId.value?.let { dev.jellystructure.ravilo.ui.seams.sessionLog("remote: close $it") }
        _openId.value?.let { _outgoing.tryEmit(detachFrame(it)) }
        _openId.value = null
        _detail.value = null
        _pending.value = null
    }

    /** The socket opened again: re-attach and re-fetch (review item 13 — a controller off screen had no socket), and
     *  say again which Cast devices this app sees (R370). */
    fun onSocketOpen() {
        _openId.value?.let { _outgoing.tryEmit(attachFrame(it)); refresh() }
        lastSeenFrame?.let { _outgoing.tryEmit(it) }
    }

    private var lastSeenFrame: String? = null

    /** R370 (owner decision 1) — the Cast devices this app's own discovery sees, to the server on change (a relay app). */
    fun reportCastDevices(devices: List<dev.jellystructure.shared.tv.CastSeenDevice>) {
        val frame = castDevicesSeenFrame(devices)
        if (frame == lastSeenFrame) return
        lastSeenFrame = frame
        _outgoing.tryEmit(frame)
    }

    fun refresh() {
        val id = _openId.value ?: return
        val a = api ?: return
        scope.launch { runCatching { a.playbackSessionDetail(id) }.getOrNull()?.let { if (_openId.value == id) _detail.value = it } }
    }

    fun onDetail(env: SessionDetailEnvelope) {
        if (env.detail.session.id != _openId.value) return
        val before = _detail.value
        _detail.value = env.detail
        if (before == null || env.detail.session.revision > before.session.revision || env.detail.volume != before.volume) _pending.value = null
    }

    /** A `session_state` for the open one also reflects a press. */
    fun onState(revision: Long, id: String) {
        val p = _pending.value ?: return
        if (id == _openId.value && revision > p.second) _pending.value = null
    }

    /** R372 — the open session ended (or left the list): nothing is in flight any more (no *Can't reach* after it). */
    fun onEnded(id: String) {
        if (id != _openId.value) return
        if (_pending.value != null) _pending.value = null
        dev.jellystructure.ravilo.ui.seams.sessionLog("remote: $id ended")
    }

    /** FR-R369-1 — one command; the session's revision as this app last saw it rides along. */
    fun command(id: String, c: SessionCommandRequest) {
        val a = api ?: return
        val rev = c.revision ?: (_detail.value?.session?.takeIf { it.id == id }?.revision
            ?: PlaybackSessions.state.value.sessions.firstOrNull { it.id == id }?.revision)
        // R371 — a room op's answer is the rooms report (or *Couldn't add*), not the session's revision.
        if (commandAwaitsReflection(c)) _pending.value = now() to (rev ?: 0L)
        PlaybackSessions.touch(id)
        dev.jellystructure.ravilo.ui.seams.sessionLog("remote: ${c.op} on $id (rev $rev)")
        scope.launch {
            val refusal: SessionCommandRefusal? = runCatching { a.playbackSessionCommand(id, c.copy(revision = rev)) }.getOrElse { SessionCommandRefusal("unreachable") }
            when (refusal?.reason) {
                null -> Unit
                "stale" -> { _pending.value = null; refresh() }   // FR-R369-1 — redraw and do nothing more
                else -> { _pending.value = null; _refusals.tryEmit(refusal.reason) }
            }
            if (refusal != null) dev.jellystructure.ravilo.ui.seams.sessionLog("remote: ${c.op} on $id refused: ${refusal.reason}")
        }
    }

    /**
     * R372 (FR-R372-2/-3) — move the session to [targetId] (`here` for *Play here*). [onLoadHere] gets the Cast device
     * this app must LOAD itself (its own discovery sees it), with the place to start (2 s back).
     */
    fun move(id: String, targetId: String, onLoadHere: (castDeviceId: String, startMs: Long) -> Unit = { _, _ -> }) {
        val a = api ?: return
        val rev = _detail.value?.session?.takeIf { it.id == id }?.revision
        _pending.value = now() to (rev ?: 0L)
        scope.launch {
            val r = runCatching { a.movePlaybackSession(id, dev.jellystructure.shared.tv.SessionMoveRequest(targetId, rev)) }
            r.onSuccess { resp ->
                val castId = resp.castDeviceId
                if (resp.loadHere && castId != null) {
                    val v = resp.session
                    val pos = v?.let { drawnPositionMs(it, PlaybackSessions.state.value.serverNowMs, PlaybackSessions.state.value.receivedAtMs, now()) } ?: 0L
                    kotlinx.coroutines.withContext(Dispatchers.Main) { onLoadHere(castId, moveStartMs(pos)) }
                }
            }.onFailure { _pending.value = null; _refusals.tryEmit("unreachable"); refresh() }
        }
    }

    /** R371 (review item 5) — this app's group rooms, on change only. */
    fun reportMembers(report: dev.jellystructure.shared.tv.SessionMembersReport) {
        val a = api ?: return
        scope.launch { runCatching { a.reportSessionMembers(report) } }
    }

    /** R369 (dev review item 5) — this player's queue, on change only. */
    fun reportQueue(report: SessionQueueReport) {
        val a = api ?: return
        scope.launch { runCatching { a.reportSessionQueue(report) } }
    }
}

/** R369 — what changed in a player's queue that the server must hear (review item 5: on change, never every tick). */
data class QueueKey(val ids: List<String>, val index: Int, val shuffle: Boolean, val repeat: String)

/** R372 (owner decision 2) — every move starts 2 s back, music included, never below 0. */
fun moveStartMs(positionMs: Long): Long = (positionMs - 2_000L).coerceAtLeast(0L)

/** The revision a queue report carries: changes with the ids, so a queue edit can be checked at the target. */
fun queueRevOf(ids: List<String>): Int = ids.hashCode()
