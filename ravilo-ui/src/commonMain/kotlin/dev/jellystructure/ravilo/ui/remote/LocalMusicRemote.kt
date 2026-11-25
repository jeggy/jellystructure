package dev.jellystructure.ravilo.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import dev.jellystructure.ravilo.ui.MusicRemotePlayer
import dev.jellystructure.ravilo.ui.RemoteControl
import dev.jellystructure.ravilo.ui.components.CastController
import dev.jellystructure.ravilo.ui.components.rememberSessionsState
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isDesktopPlatform
import dev.jellystructure.ravilo.ui.music.MusicCast
import dev.jellystructure.ravilo.ui.music.MusicPlayback
import dev.jellystructure.ravilo.ui.music.MusicEngine
import dev.jellystructure.ravilo.ui.music.MusicPlayerState
import dev.jellystructure.ravilo.ui.music.RepeatMode
import dev.jellystructure.ravilo.ui.music.chapterTitle
import dev.jellystructure.ravilo.ui.music.BookMath
import dev.jellystructure.ravilo.ui.music.rememberBookPosition
import dev.jellystructure.ravilo.ui.music.rememberLivePosition
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.ravilo.ui.sessions.PlaybackSessions
import dev.jellystructure.ravilo.ui.sessions.SessionRemote
import dev.jellystructure.shared.tv.SessionView
import kotlinx.coroutines.delay

/**
 * DEB-2185 (FR-3, FR-4) — the one remote for a song or a book **this app holds the queue of**: its own playback on this
 * device, or the same queue cast to a speaker. [MusicPlayback] is the one door both go through (a press reaches the
 * engine here and the speaker while casting), so this driver is one for both, and the place pill and the volume row are
 * the only parts that tell them apart. Null while nothing is loaded.
 *
 * The queue, the track's own facts and the book come from the player's state: that is what lets ♥, ⋯, the lyrics, the
 * cover's swipe and *Up next*'s reorder act on it ([MusicFace.local]).
 */
@Composable
fun rememberLocalMusicRemote(cast: CastController?, extras: RemoteExtras): OneRemote? {
    val st by MusicPlayback.state.collectAsState()
    val linked by MusicCast.linked.collectAsState()
    val device by MusicCast.deviceName.collectAsState()
    val list = rememberSessionsState()
    val detail by SessionRemote.detail.collectAsState()
    val book = st.book
    val track = st.current
    if (book == null && track == null) return null
    val live = if (book != null) rememberBookPosition() else rememberLivePosition()
    val casting = linked && device != null
    val place = if (casting) device.orEmpty() else str(if (isDesktopPlatform) "mode.label_desk" else "mode.label")

    // The server's row for this playback, when it lists one: it is what *Move to…* is built on.
    val row: SessionView? = list.sessions.filter { it.mine && it.state != "ended" && (it.kind == "music" || it.kind == "audiobook") && (if (casting) !it.here && it.target.name.trim().equals(place.trim(), ignoreCase = true) else it.here) }
        .maxByOrNull { it.createdAt }
    val rowDetail = row?.let { r -> detail?.takeIf { it.session.id == r.id } }

    val status = localMusicStatus(st, live)
    val (now, up) = upNextOf(st)
    val face = MusicFace(track = track, context = st.context, now = now, upNext = up, book = book, local = true)
    val model = RemoteModel(
        sessionId = row?.id, placeName = place, placeIcon = row?.target?.icon ?: if (casting) "speaker" else if (isDesktopPlatform) "computer" else "phone",
        status = status, kind = if (book != null) "audiobook" else "music",
        here = !casting, view = row, leavesOnStop = false,
        shuffle = st.shuffle, repeat = st.repeat.name.lowercase(), music = face,
    )
    val actions = LocalMusicActions
    if (casting && cast != null) {
        // Over the link the speaker's own level (or R371's rooms, once the session's detail is in hand) is the volume.
        val r = linkRemote(actions, status, unreachable = false, placeName = place, view = row, detail = rowDetail, extras = extras,
            linkVolume = { m -> LinkVolumeRow(cast, place, rowDetail, m) })
        return OneRemote(model.copy(view = row), r.actions, r.extras, r.linkVolume, r.sessionVolume)
    }
    return OneRemote(model, actions, extras, linkVolume = { m -> LocalVolumeRow(place, m) })
}

/** The status the remote's shared parts read, from the player's state. [live] is the song's (a book's own part) position. */
@Composable
internal fun localMusicStatus(st: MusicPlayerState, live: Long): CastRemoteStatus {
    val book = st.book
    val t = st.current
    return if (book != null) {
        val ci = BookMath.chapterAt(book.detail, live)
        val chapter = book.detail.chapters.getOrNull(ci)
        CastRemoteStatus(
            itemId = book.detail.id, title = chapter?.let { chapterTitle(it.title, ci) } ?: book.detail.title, kicker = book.detail.title,
            artUrl = book.detail.coverUrl, positionMs = live, durationMs = BookMath.length(book.detail),
            playing = st.playing, buffering = st.buffering, loaded = true, failed = st.failed, ended = book.finished, music = false,
        )
    } else CastRemoteStatus(
        itemId = t?.id, title = t?.title, kicker = t?.artists?.joinToString(", ") { it.name }?.ifBlank { null }, artUrl = t?.imageUrl,
        positionMs = live, durationMs = st.durationMs, playing = st.playing, buffering = st.buffering, loaded = true,
        failed = st.failed, ended = st.ended, hasNext = st.hasNext, music = true, repeat = st.repeat.name.lowercase(), shuffle = st.shuffle,
    )
}

/** A press on the remote of a playback this app holds: the same door the Playing page's buttons used. */
internal object LocalMusicActions : RemoteActions {
    private fun touch() = PlaybackSessions.touchLocal()
    override fun play() { touch(); MusicPlayback.play() }
    override fun pause() { touch(); MusicPlayback.pause() }
    override fun seekTo(positionMs: Long) {
        touch()
        if (MusicPlayback.state.value.book != null) MusicEngine.seekBook(positionMs) else MusicPlayback.seekTo(positionMs)
    }
    override fun next() { touch(); MusicPlayback.next() }
    override fun previous() { touch(); MusicPlayback.previous() }
    /** The song stops and the queue stays, paused where it was (FR-R322-12); a cast ends and the song comes back paused (FR-R356-14). */
    override fun stop() = MusicRemotePlayer.stop()
    override fun setShuffle(on: Boolean) { if (MusicPlayback.state.value.shuffle != on) MusicPlayback.toggleShuffle() }
    override fun setRepeat(mode: String) {
        val want = when (mode) { "all" -> RepeatMode.ALL; "one" -> RepeatMode.ONE; else -> RepeatMode.OFF }
        repeat(3) { if (MusicPlayback.state.value.repeat != want) MusicPlayback.cycleRepeat() }
    }
    override fun jumpTo(index: Int) { touch(); MusicPlayback.playAt(index) }
    override fun selectAudio(trackId: Long?) {}
    override fun selectSubtitle(trackId: Long?) {}
    override fun nextUpPlay() {}
    override fun nextUpCancel() {}
    override fun subtitleSize(size: Char) {}
    override fun retry() { MusicPlayback.retry() }
}

/**
 * FR-10 — this device's own volume row: the engine's level the dashboard and the desktop bar move (R357), polled while
 * the remote shows, since the phone's player level has no change signal of its own.
 */
@Composable
internal fun LocalVolumeRow(placeName: String, modifier: Modifier = Modifier) {
    val reported by produceState<Int?>(RemoteControl.musicVolumeReport()?.percent) {
        while (true) { value = RemoteControl.musicVolumeReport()?.percent; delay(400) }
    }
    VolumeSliderRow(
        label = str("cast.volume"), placeName = placeName, reported = reported, enabled = reported != null,
        send = { level -> MusicRemotePlayer.setVolume(level / 100f, false); RemoteControl.musicLevelSet(level / 100f) },
        modifier = modifier, tag = "remote-volume",
    )
}
