package dev.jellystructure.ravilo.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jellystructure.ravilo.ui.components.CastController
import dev.jellystructure.ravilo.ui.components.rememberSessionClock
import dev.jellystructure.ravilo.ui.components.rememberSessionsState
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.ravilo.ui.sessions.CommandFeedback
import dev.jellystructure.ravilo.ui.sessions.SessionRemote
import dev.jellystructure.ravilo.ui.sessions.commandFeedback
import dev.jellystructure.ravilo.ui.sessions.remoteSessionView
import dev.jellystructure.ravilo.ui.sessions.sessionStateLineText
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

private val NO_LINK: StateFlow<CastLinkState> = MutableStateFlow(CastLinkState.NONE)
private val NO_STATUS: StateFlow<CastRemoteStatus?> = MutableStateFlow(null)
private val NO_NAME: StateFlow<String?> = MutableStateFlow(null)

/**
 * FR-1 / FR-5 — the one remote for a playback, chosen by what plays and never by how it was reached.
 *
 * [sessionId] is the server's row when the door knew it (a *Playing everywhere* row, *Play on {place}*); null from the
 * cast mini bar, which finds the row of the cast this app holds a link to. When this app holds a link to the place the
 * remote is driven over it (the faster path, R356) and otherwise through the server; the screen cannot tell which.
 *
 * Null while there is nothing to draw yet (the session's detail is still coming); [onGone] is called when the link
 * this remote was driving went away without a server row to fall back on (FR-R245-5: the remote leaves quietly).
 */
@Composable
fun rememberOneRemote(
    sessionId: String?, cast: CastController?, extras: RemoteExtras, desktop: Boolean, onGone: () -> Unit,
): OneRemote? {
    val link by remember(cast) { cast?.sender?.link ?: NO_LINK }.collectAsState()
    val castStatus by remember(cast) { cast?.sender?.status ?: NO_STATUS }.collectAsState()
    val deviceName by remember(cast) { cast?.sender?.deviceName ?: NO_NAME }.collectAsState()
    val list = rememberSessionsState()
    val detail by SessionRemote.detail.collectAsState()
    val pending by SessionRemote.pending.collectAsState()
    val now = rememberSessionClock()

    val linkName = deviceName.takeIf { link != CastLinkState.NONE }
    val detailView = detail?.session?.takeIf { it.id == sessionId }
    val view = if (sessionId != null) remoteSessionView(list.sessions.firstOrNull { it.id == sessionId }, detailView, listKnown = list.receivedAtMs > 0)
    else rowForLink(list.sessions, linkName)
    val holdsLink = cast != null && link != CastLinkState.NONE && (sessionId == null || holdsLinkTo(view, linkName))
    val id = view?.id ?: sessionId

    // The remote attaches this device as a controller of the session (FR-R369-4), whichever driver it runs on.
    DisposableEffect(id) { if (id != null) SessionRemote.open(id); onDispose { if (id != null) SessionRemote.close() } }
    val row = id?.let { i -> list.sessions.firstOrNull { it.id == i } }
    LaunchedEffect(row?.revision) { if (row != null) SessionRemote.onState(row.revision, row.id) }
    val ended = view?.state == "ended"
    LaunchedEffect(ended, id) { if (ended && id != null) SessionRemote.onEnded(id) }

    // FR-R245-5 — a link that goes away after it was there, with no server row to keep, ends the remote quietly.
    var wasLinked by remember { mutableStateOf(false) }
    LaunchedEffect(link) { if (link != CastLinkState.NONE) wasLinked = true }
    val gone = sessionId == null && view == null && (cast == null || (wasLinked && link == CastLinkState.NONE))
    LaunchedEffect(gone) { if (gone) onGone() }

    if (holdsLink && cast != null) {
        val unreachable = link != CastLinkState.CONNECTED
        // The session's detail rides along with the link (DEB-2611): a song's rooms and shuffle come from it.
        val linkDetail = view?.let { lv -> detail?.takeIf { it.session.id == lv.id } }
        return linkRemote(
            LinkActions(cast), castStatus ?: CastRemoteStatus(), unreachable, deviceName.orEmpty(), view, linkDetail, extras,
            linkVolume = if (desktop && !unreachable) { m -> dev.jellystructure.ravilo.ui.screens.RemoteVolume(cast, m) } else null,
        )
    }
    val v = view ?: return null
    val d = detail?.takeIf { it.session.id == v.id }
    val feedback = commandFeedback(pending?.first, Clock.System.now().toEpochMilliseconds().coerceAtLeast(now), reflected = false)
    val viewOnly = viewOnlyOf(v)
    val ops: Set<String> = if (ended || !v.controllable) emptySet() else d?.ops.orEmpty().toSet()
    val cantReach = feedback == CommandFeedback.CANT_REACH && !ended
    val line = when {
        v.movingTo != null -> str("session.moving", mapOf("place" to v.movingTo.orEmpty()))
        v.moveFailed != null -> str("session.move_failed", mapOf("place" to v.moveFailed.orEmpty()))
        cantReach -> str("session.cant_reach", mapOf("place" to v.target.name))
        else -> sessionStateLineText(v)
    }
    val model = RemoteModel(
        sessionId = v.id, placeName = v.target.name, placeIcon = v.target.icon,
        status = SessionSender.status(v, d, list.serverNowMs, list.receivedAtMs, now),
        kind = remoteKindOf(v.kind),
        unreachable = (v.reconnecting || v.offline || cantReach) && !ended,
        retryable = (v.reconnecting || cantReach) && !ended,
        line = line, lineAlert = v.moveFailed != null || v.offline || v.reconnecting || cantReach,
        subLine = if (ended) str("session.ended", mapOf("place" to v.target.name)) else null,
        viewOnlyOwner = if (viewOnly) v.owner.name else null,
        confirmStop = !v.mine, ownerName = v.owner.name, here = v.here, view = v, ops = ops, feedback = feedback,
        canSubtitleSize = false, canNextUp = false,
        shuffle = d?.shuffle ?: false, repeat = d?.repeat ?: "off",
    )
    return OneRemote(model, SessionActions(v.id), extras, sessionVolume = if (d != null && v.controllable && !ended && !viewOnly) { { extras.volume?.invoke(d) } } else null)
}

/**
 * DEB-2611 (FR-1, FR-3, FR-10) — the remote over this app's own link. A song or a book keeps its kind and, with the
 * session's [detail] in hand, gets R371's volume block (rooms, *Add a speaker…*) as the server driver does; the
 * computer's own slider ([linkVolume]) is what a film or an episode — or a song with no detail yet — has.
 */
internal fun linkRemote(
    actions: RemoteActions, status: CastRemoteStatus, unreachable: Boolean, placeName: String, view: SessionView?,
    detail: SessionDetail?, extras: RemoteExtras, linkVolume: (@Composable (androidx.compose.ui.Modifier) -> Unit)?,
): OneRemote {
    val kind = remoteKindOf(view?.kind, episodeLike = status.hasNext || status.kicker != null)
    val music = kind == "music" || kind == "audiobook"
    // A song's artist (a book's chapter) rides on the server's row when the receiver does not say it.
    val s = if (music && status.kicker == null) status.copy(kicker = view?.subtitle) else status
    val model = RemoteModel(
        sessionId = view?.id, placeName = placeName, placeIcon = view?.target?.icon, status = s, kind = kind,
        unreachable = unreachable, retryable = unreachable, view = view, leavesOnStop = true,
        shuffle = detail?.shuffle ?: false, repeat = detail?.repeat ?: "off",
    )
    val rooms = music && detail != null && extras.volume != null
    return OneRemote(
        model, actions, extras,
        linkVolume = if (rooms) null else linkVolume,
        sessionVolume = if (rooms && !unreachable) { { extras.volume?.invoke(detail!!) } } else null,
    )
}

/** FR-5.2 — a press goes over this app's own link. */
private class LinkActions(private val cast: CastController) : RemoteActions {
    override fun play() = cast.sender.play()
    override fun pause() = cast.sender.pause()
    override fun seekTo(positionMs: Long) = cast.sender.seekTo(positionMs)
    override fun next() = cast.command("next")
    override fun stop() = cast.stopCasting()
    override fun selectAudio(trackId: Long?) = cast.sender.selectAudio(trackId)
    override fun selectSubtitle(trackId: Long?) = cast.sender.selectSubtitle(trackId)
    override fun nextUpPlay() = cast.command("nextup_play")
    override fun nextUpCancel() = cast.command("nextup_cancel")
    override fun subtitleSize(size: Char) = cast.command("subsize", size = size.toString())
    override fun retry() = cast.command("status")
    // A song's own controls travel the music queue's own commands over the link (R324).
    override fun previous() = cast.musicCommand("prev")
    override fun setShuffle(on: Boolean) = cast.musicCommand("shuffle", on = on)
    override fun setRepeat(mode: String) = cast.musicCommand("repeat", mode = mode)
}

/** FR-5.2 — a press goes through the server; the revision rides along (R369 FR-R369-1) and a 409 redraws. */
private class SessionActions(private val id: String) : RemoteActions {
    private fun send(c: SessionCommandRequest) = SessionRemote.command(id, c)
    override fun play() = send(SessionCommandRequest(op = "play"))
    override fun pause() = send(SessionCommandRequest(op = "pause"))
    override fun seekTo(positionMs: Long) = send(SessionCommandRequest(op = "seek", positionMs = positionMs))
    override fun next() = send(SessionCommandRequest(op = "next"))
    override fun stop() = send(SessionCommandRequest(op = "stop"))
    override fun selectAudio(trackId: Long?) = send(SessionCommandRequest(op = "set_audio", index = trackId?.toInt()))
    override fun selectSubtitle(trackId: Long?) = send(SessionCommandRequest(op = "set_subtitle", index = trackId?.toInt() ?: -1))
    // The server path carries neither a next-up countdown nor a subtitle size yet (FR-14): the controls are absent.
    override fun nextUpPlay() {}
    override fun nextUpCancel() {}
    override fun subtitleSize(size: Char) {}
    override fun retry() = SessionRemote.refresh()
    override fun previous() = send(SessionCommandRequest(op = "previous"))
    override fun setShuffle(on: Boolean) = send(SessionCommandRequest(op = "set_shuffle", on = on))
    override fun setRepeat(mode: String) = send(SessionCommandRequest(op = "set_repeat", mode = mode))
}
