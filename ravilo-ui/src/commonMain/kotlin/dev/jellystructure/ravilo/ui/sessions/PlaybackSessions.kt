package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.EVENTS_FEATURE_SESSIONS
import dev.jellystructure.shared.tv.EVENTS_FEATURE_SESSION_CONTROL
import dev.jellystructure.shared.tv.SessionList
import dev.jellystructure.shared.tv.SessionListEnvelope
import dev.jellystructure.shared.tv.SessionStateEnvelope
import dev.jellystructure.shared.tv.SessionView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * R368 (invariant 2) — what this app knows about the household's playback: exactly the last `session_list` it
 * received, with each later `session_state` replacing its row when its revision is higher. No client builds a session
 * from its own player. [serverNowMs] / [receivedAtMs] let a row's position be drawn on the server's clock.
 */
data class SessionsState(
    val sessions: List<SessionView> = emptyList(),
    val serverNowMs: Long = 0L,
    val receivedAtMs: Long = 0L,
)

/** Invariant 2 — a list replaces everything. */
fun applySessionList(env: SessionListEnvelope, receivedAtMs: Long): SessionsState =
    SessionsState(env.sessions, env.serverNowMs, receivedAtMs)

fun applySessionList(list: SessionList, receivedAtMs: Long): SessionsState =
    SessionsState(list.sessions, list.serverNowMs, receivedAtMs)

/** Invariant 2 — a state replaces its row only with a higher revision; one for an id not in the list is ignored. */
fun applySessionState(state: SessionsState, env: SessionStateEnvelope, receivedAtMs: Long): SessionsState {
    val i = state.sessions.indexOfFirst { it.id == env.session.id }
    if (i < 0) return state
    if (env.session.revision <= state.sessions[i].revision) return state
    return state.copy(sessions = state.sessions.toMutableList().also { it[i] = env.session }, serverNowMs = env.serverNowMs, receivedAtMs = receivedAtMs)
}

/** FR-R368-7 — this device first, then the viewer's other sessions by latest change, then other people's. */
fun orderSessionRows(rows: List<SessionView>): List<SessionView> =
    rows.sortedWith(compareByDescending<SessionView> { it.here }.thenByDescending { it.mine }.thenByDescending { it.updatedAt })

/** A row that still plays somewhere (not the 60 s fade of an ended one). */
fun SessionView.isLive(): Boolean = state != "ended"

/** FR-R368-7 — the glyph's count: sessions NOT on this device; null (absent) at 0. */
fun sessionsElsewhereCount(rows: List<SessionView>): Int? = rows.count { !it.here && it.isLive() }.takeIf { it > 0 }

/** R360 amended by R368 — the glyph shows for a device or a row, and not for neither. */
fun castIconShown(hasDevices: Boolean, rows: List<SessionView>): Boolean = hasDevices || rows.any { it.isLive() }

/**
 * FR-R368-8 (owner, 2026-10-04) — which session the bar shows when nothing plays on this device: always the one this
 * app touched last while it lives; before any touch (or once it ended), the most recently started one that is
 * playing. In films mode only music and audiobooks from elsewhere (R337's rule). Never this device's own.
 */
fun pickBarSession(rows: List<SessionView>, touchedId: String?, filmsMode: Boolean): SessionView? {
    val elsewhere = rows.filter { !it.here && it.isLive() && (!filmsMode || it.kind == "music" || it.kind == "audiobook") }
    elsewhere.firstOrNull { it.id == touchedId }?.let { return it }
    return elsewhere.filter { it.state == "playing" }.maxByOrNull { it.createdAt }
}

/** FR-R368-8 — the bar's **+N**: the other sessions elsewhere besides the one it shows. */
fun barMoreCount(rows: List<SessionView>, shown: SessionView?): Int =
    rows.count { !it.here && it.isLive() && it.id != shown?.id }

/**
 * Review item 9 — where to draw a row's position: the stored position plus the server time since it was reported,
 * while `playing`; frozen otherwise. The server's own clock ([serverNowMs] at [receivedAtMs]) is used, so a device
 * clock that is minutes off still draws the right time. Clamped to the duration.
 */
fun drawnPositionMs(view: SessionView, serverNowMs: Long, receivedAtMs: Long, nowMs: Long): Long? {
    val pos = view.positionMs ?: return null
    val moved = if (view.state == "playing" && serverNowMs > 0) (serverNowMs + (nowMs - receivedAtMs) - view.positionAt).coerceAtLeast(0L) else 0L
    val at = pos + moved
    return view.durationMs?.takeIf { it > 0 }?.let { at.coerceAtMost(it) } ?: at
}

/** FR-R368-10, review item 2 — the phone, the computer and the web app ask for sessions; the TV does not (yet). R369
 *  adds `session_control` where the app obeys `session_command`. */
fun eventsFeaturesFor(isTv: Boolean, obeysSessionCommands: Boolean = false): Set<String> = when {
    isTv -> emptySet()
    obeysSessionCommands -> setOf(EVENTS_FEATURE_SESSIONS, EVENTS_FEATURE_SESSION_CONTROL)
    else -> setOf(EVENTS_FEATURE_SESSIONS)
}

/** `S01E05` rows read *Title · S01E05*; music rows read the title (FR-R368-7). */
fun sessionRowTitle(v: SessionView): String? = when {
    v.title == null -> null
    v.kind == "episode" && v.subtitle != null -> "${v.title} · ${v.subtitle}"
    else -> v.title
}

/** The one store the app's UI reads (seeded from the GET, then following the events). */
object PlaybackSessions {
    private val _state = MutableStateFlow(SessionsState())
    val state: StateFlow<SessionsState> = _state.asStateFlow()

    /** FR-R368-8 — the session this app touched last (a local pick of an id, review item 13). */
    private val _touched = MutableStateFlow<String?>(null)
    val touched: StateFlow<String?> = _touched.asStateFlow()

    private fun now() = Clock.System.now().toEpochMilliseconds()

    fun onList(env: SessionListEnvelope) { _state.value = applySessionList(env, now()) }
    fun onList(list: SessionList) { _state.value = applySessionList(list, now()) }
    fun onState(env: SessionStateEnvelope) { _state.value = applySessionState(_state.value, env, now()) }
    fun touch(id: String) { _touched.value = id }
    /** Signed out / another profile: nothing of the last viewer's household stays on screen. */
    fun clear() { _state.value = SessionsState(); _touched.value = null }
}
