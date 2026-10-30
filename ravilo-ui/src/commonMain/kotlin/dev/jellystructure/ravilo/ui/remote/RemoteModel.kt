package dev.jellystructure.ravilo.ui.remote

import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.ravilo.ui.sessions.CommandFeedback
import dev.jellystructure.ravilo.ui.sessions.drawnPositionMs
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionView
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DEB-2185 (FR-1, FR-5) — what the one remote draws, whichever driver produced it: this app's own Cast / screen link
 * ([dev.jellystructure.ravilo.ui.seams.CastSender]) or the server's [SessionDetail] ([SessionSender]). The screen reads
 * only this, so the same playback is the same picture from either door.
 */
data class RemoteModel(
    /** The session this remote is about, when the server lists one (a cast this app sent has a row too). */
    val sessionId: String?,
    val placeName: String,
    /** A place's icon key ([dev.jellystructure.ravilo.ui.components.placeIcon]); null = the screen glyph. */
    val placeIcon: String?,
    val status: CastRemoteStatus,
    /** `film` · `episode` · `music` · `audiobook` — what plays decides the shape (FR-1), never which driver is behind it. */
    val kind: String,
    /** The place cannot be reached right now (*Lost contact*, or *offline*): the controls are greyed (FR-5.4). */
    val unreachable: Boolean = false,
    /** Only *Lost contact* offers *Try again*; an offline place comes back by itself. */
    val retryable: Boolean = false,
    /** The state line, when the server has words of its own for it (*Moving to…*, *{person} stopped it*, …). */
    val line: String? = null,
    /** [line] is a warning (drawn in the warning colour) rather than information. */
    val lineAlert: Boolean = false,
    /** A second, quieter line under [line] (*Stopped on {place}* under *{person} stopped it*). */
    val subLine: String? = null,
    /** Stop leaves the remote (this app's own link goes with it); a session's stays to say *{person} stopped it*. */
    val leavesOnStop: Boolean = false,
    /** Someone else's playback with the household switch off: [owner] is who, and nothing is pressable (FR-8). */
    val viewOnlyOwner: String? = null,
    /** Stopping asks first, every time, because the playback is someone else's (FR-7). */
    val confirmStop: Boolean = false,
    val ownerName: String? = null,
    /** The place is this device (*Play here* is not offered). */
    val here: Boolean = false,
    /** The server's row, when there is one: *Move to…* needs it. Null = the pill opens the platform's cast sheet. */
    val view: SessionView? = null,
    /** The ops the place obeys; null = every control (this app's own link offers all of them). */
    val ops: Set<String>? = null,
    /** A press in flight (FR-R369-6): dim, spinner, *Can't reach*. */
    val feedback: CommandFeedback = CommandFeedback.NONE,
    /** A next-up countdown the place mirrors, and a subtitle size it takes — absent where the place does not say. */
    val canSubtitleSize: Boolean = true,
    val canNextUp: Boolean = true,
    /** A song's queue state, as the server's [SessionDetail] says it (DEB-2611): drawn on shuffle and repeat. */
    val shuffle: Boolean = false,
    val repeat: String = "off",
)

/** DEB-2961 (one-remote FR-2, FR-14) — the server driver's S · M · L row exists when the place's ops list the caption size. */
fun serverCanSubtitleSize(ops: Set<String>): Boolean = "set_subtitle_size" in ops

/** FR-5.3 — a control the place cannot do is absent; the link offers everything, the server says its ops. */
fun RemoteModel.offers(op: String): Boolean = ops == null || op in ops

/** DEB-2611 (FR-1) — a song or a book: the interim music shape, with no subtitle control of any kind. */
val RemoteModel.musicShape: Boolean get() = kind == "music" || kind == "audiobook"

/**
 * FR-3 — *Audio & Subs* is offered where the place obeys an audio or subtitle op and has something to pick. A `music` or
 * `audiobook` never offers it, even when the file carries a text stream, on either driver (DEB-2611, FR-1).
 */
fun RemoteModel.offersTracks(): Boolean =
    !musicShape && (offers("set_audio") || offers("set_subtitle")) &&
        (ops == null || status.audioTracks.size > 1 || status.subtitleTracks.isNotEmpty())

/** The remote's kind from the session's own (DEB-2611): a song or a book keeps its kind; the rest are FR-2's two shapes. */
fun remoteKindOf(sessionKind: String?, episodeLike: Boolean = false): String = when {
    sessionKind == "music" || sessionKind == "audiobook" -> sessionKind
    sessionKind == "episode" || episodeLike -> "episode"
    else -> "film"
}

/** FR-8 — someone else's playback, view-only: the transport, volume, the tracks sheet and Stop are absent. */
val RemoteModel.viewOnly: Boolean get() = viewOnlyOwner != null

/** What a press on the remote does — over this app's link, or through the server (FR-5.2). */
interface RemoteActions {
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun next()
    fun stop()
    fun selectAudio(trackId: Long?)
    fun selectSubtitle(trackId: Long?)
    fun nextUpPlay()
    fun nextUpCancel()
    fun subtitleSize(size: Char)
    /** A song's own controls (DEB-2611): previous, shuffle on/off, repeat `off` · `all` · `one`. */
    fun previous() {}
    fun setShuffle(on: Boolean) {}
    fun setRepeat(mode: String) {}
    /** *Try again* — ask the place where it is. */
    fun retry()
}

/**
 * *Move to…* and *Play here* — the app's own parts (a Cast device this app sees is its own LOAD), supplied by
 * [dev.jellystructure.ravilo.ui.RaviloApp]; absent ⇒ not drawn.
 */
class RemoteExtras(
    /** R372 (FR-R372-2) — a place picked in *Move to…*. */
    val onMoveTo: ((SessionView, dev.jellystructure.shared.tv.PlaybackTarget) -> Unit)? = null,
    /** R372 (FR-R372-3) — *Play here*: a move to this device. */
    val onPlayHere: ((SessionView) -> Unit)? = null,
    /** R372's label for *Play here* on this platform (`cast.play_here` · `_mac` · `_desk`). */
    val playHereLabel: String? = null,
    /** R371 — a song's or a book's volume panel (master + rooms + *Add a speaker…*), kept behind the remote until the volume slice. */
    val volume: (@androidx.compose.runtime.Composable (SessionDetail) -> Unit)? = null,
    /** The platform's own cast sheet — what the place pill opens when the server lists no row for this playback. */
    val openCastSheet: () -> Unit = {},
)

/** The remote on screen: the model, what a press does, and the parts the app supplies. */
class OneRemote(
    val model: RemoteModel,
    val actions: RemoteActions,
    val extras: RemoteExtras,
    /** This app's own link, when the playback is driven over it: the volume slider on a computer reads it. */
    val linkVolume: (@androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit)? = null,
    /** The server's volume panel, when the playback is driven through the server. */
    val sessionVolume: (@androidx.compose.runtime.Composable () -> Unit)? = null,
)

/** The one remote's audio & subtitles sheet is drawn at the app's root; the screen and the root share this. */
object RemoteOnScreen {
    val trackSheetOpen = MutableStateFlow(false)
    val remote = MutableStateFlow<OneRemote?>(null)
}

/**
 * R369 (dev review item 9), FR-5.1 — the `SessionSender`: [SessionDetail] and its row read as the same
 * [CastRemoteStatus] a Cast link reports. A film's tracks carry their index as the id (the way a screen's do), so the
 * shared tracks sheet picks them the same way on both drivers.
 */
object SessionSender {
    fun status(
        v: SessionView, d: SessionDetail?, serverNowMs: Long, receivedAtMs: Long, nowMs: Long,
    ): CastRemoteStatus {
        val ended = v.state == "ended"
        val audio = d?.audioTracks.orEmpty().map { it.copy(trackId = it.index.toLong()) }
        val subs = d?.subtitleTracks.orEmpty().map { it.copy(trackId = it.index.toLong()) }
        // An episode's kicker is its S01E05; a song's is its artist and a book's its chapter (drawn under the title).
        val kicker = v.kind == "episode" || v.kind == "music" || v.kind == "audiobook"
        return CastRemoteStatus(
            itemId = null,
            title = v.title,
            kicker = if (kicker) v.subtitle else null,
            artUrl = v.artwork,
            positionMs = drawnPositionMs(v, serverNowMs, receivedAtMs, nowMs) ?: 0L,
            durationMs = v.durationMs ?: 0L,
            playing = v.state == "playing",
            buffering = v.state == "buffering" || v.state == "starting",
            loaded = !ended && v.state != "failed",
            ended = ended,
            failed = v.state == "failed",
            hasNext = d?.let { "next" in it.ops } ?: false,
            audioTracks = audio,
            subtitleTracks = subs,
            selectedAudio = audio.indexOfFirst { it.index == d?.audioIndex }.coerceAtLeast(0),
            selectedSub = subs.indexOfFirst { it.index == d?.subtitleIndex },
            subSize = d?.subSize?.firstOrNull()?.takeIf { it in "SML" } ?: 'M',   // DEB-2961 — absent = M
        )
    }
}

/**
 * FR-1 — does this app hold the direct link to the place [v] plays on? The Cast link's name is the place's name
 * (R355's rule for a route belonging to a session); with a link to it, a press goes over the link (R356's faster path).
 */
fun holdsLinkTo(v: SessionView?, linkName: String?): Boolean =
    v != null && linkName != null && v.target.name.trim().equals(linkName.trim(), ignoreCase = true)

/**
 * The server's row for the cast this app holds a link to — a cast this app sent is the viewer's own, elsewhere, and
 * live. Null on a server that lists none (an older one): the remote then runs on the link alone.
 */
fun rowForLink(rows: List<SessionView>, linkName: String?): SessionView? =
    if (linkName == null) null
    else rows.filter { it.mine && !it.here && it.state != "ended" && holdsLinkTo(it, linkName) }
        .maxByOrNull { it.createdAt }

/** FR-8 — the viewer may look but not press: not theirs, and the household switch is off (the server's `controllable`). */
fun viewOnlyOf(v: SessionView): Boolean = !v.mine && !v.controllable && v.state != "ended"
