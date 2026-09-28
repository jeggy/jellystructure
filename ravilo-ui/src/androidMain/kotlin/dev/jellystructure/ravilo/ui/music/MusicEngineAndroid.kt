package dev.jellystructure.ravilo.ui.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.TokenStore
import dev.jellystructure.ravilo.ui.createTvApiClient
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.ravilo.ui.seams.RaviloPlayerEngine
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * R322 (FR-R322-1) — the phone's music engine: one ExoPlayer and one MediaSession that outlive the screen. The app
 * process owns them; [RaviloMusicService] keeps the process in the foreground while a song plays and hands the
 * session to the system (the notification and lock-screen card, FR-R322-13, drawn by Media3).
 *
 * Every song is one Jellyfin session (279 FR-279-6): starting it asks the server for its stream (which starts the
 * session), the engine reports where it is every 10 s, and leaving it — a skip, the end, a stop — reports the stop.
 * ExoPlayer holds only the song that is playing; the queue is [MusicQueue], and the session's *next/previous*
 * come from a [ForwardingPlayer] that answers from it.
 *
 * Its audio is *music* with focus handling and becoming-noisy (dev review 8): a video taking focus pauses it by the
 * platform's rule, and FR-R322-12's explicit stop comes from [stopForVideo].
 */
@OptIn(UnstableApi::class)
actual object MusicEngine {
    actual val supported: Boolean = true
    private val _state = MutableStateFlow(MusicPlayerState())
    actual val state: StateFlow<MusicPlayerState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val q = MusicQueue()
    private var context: MusicContext? = null
    private var repeat = RepeatMode.OFF
    private var api: TvApiClient? = null
    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null
    /** The song whose Jellyfin session is open, and so the one progress and stop are reported for. */
    private var openTrackId: String? = null
    /** Where a song that is loaded but not open (paused from a snapshot, or at queue end) stands. */
    private var parkedPositionMs = 0L
    private var failed = false
    private var ended = false
    private var loadJob: Job? = null
    private var tickJob: Job? = null

    actual fun attach(api: TvApiClient) { this.api = api }

    /** A cold start (the system resuming playback after a reboot, say) has no app to hand a client over. */
    private fun client(): TvApiClient? = api ?: raviloBaseUrl().takeIf { it.isNotBlank() }?.let { base ->
        createTvApiClient(base) { MultiTokenStore.getActive()?.deviceToken ?: TokenStore.get() }.also { api = it }
    }

    // ── the player and the session ──

    private fun player(): ExoPlayer = exo ?: build()

    private fun build(): ExoPlayer {
        val ctx = RaviloAppContext.get()
        val b = ExoPlayer.Builder(ctx)
        // Dev review 4 — the same FFmpeg audio renderers the video player uses (FLAC, Opus, ALAC…), when the app set them.
        RaviloPlayerEngine.renderersFactoryProvider?.invoke(ctx)?.let { b.setRenderersFactory(it) }
        b.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        b.setHandleAudioBecomingNoisy(true)
        b.setWakeMode(C.WAKE_MODE_NETWORK)
        val p = b.build()
        p.addListener(listener)
        exo = p
        session = MediaSession.Builder(ctx, QueuePlayer(p))
            .setId("ravilo-music")
            .setCallback(SessionCallback)
            .apply { activityIntent(ctx)?.let { setSessionActivity(it) } }
            .build()
        return p
    }

    /** The session the service hosts; built on first use. */
    internal fun sessionOrBuild(): MediaSession { player(); return session!! }

    private fun activityIntent(ctx: Context): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(ctx, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The service is what keeps a background song alive; it is started with the first song. */
    private fun ensureService() {
        runCatching {
            val ctx = RaviloAppContext.get()
            ctx.startService(Intent(ctx, RaviloMusicService::class.java))
        }
    }

    /** Media3 calls back on the player: *next/previous* come from the queue, and *play* reopens a closed song. */
    private class QueuePlayer(p: Player) : ForwardingPlayer(p) {
        private val queueCommands = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon().addAll(*queueCommands).build()
        override fun isCommandAvailable(command: Int): Boolean = command in queueCommands || super.isCommandAvailable(command)
        override fun hasNextMediaItem(): Boolean = MusicEngine.state.value.hasNext
        override fun hasPreviousMediaItem(): Boolean = MusicEngine.state.value.active
        override fun seekToNext() = MusicEngine.next()
        override fun seekToNextMediaItem() = MusicEngine.next()
        override fun seekToPrevious() = MusicEngine.previous()
        override fun seekToPreviousMediaItem() = MusicEngine.previous()
        override fun play() = MusicEngine.play()
        override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) MusicEngine.play() else MusicEngine.pause() }
        override fun pause() = MusicEngine.pause()
    }

    /** Dev review 7 / open question 2 → the queue: after a reboot the system's *play* resumes the saved queue. */
    private object SessionCallback : MediaSession.Callback {
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            val snap = MusicQueueStore.load()?.takeIf { it.userId == MultiTokenStore.getActive()?.userId && it.tracks.isNotEmpty() }
            if (snap == null) { future.setException(IllegalStateException("nothing to resume")); return future }
            scope.launch {
                q.restore(snap.tracks, snap.index); context = snap.context
                val track = q.current
                val ticket = track?.let { t -> runCatching { client()?.playMusic(t.id, ClientCapabilities(), snap.positionMs.takeIf { it > 0 }) }.getOrNull() }
                if (track == null || ticket == null) { future.setException(IllegalStateException("could not resume")); return@launch }
                openTrackId = track.id
                future.set(MediaSession.MediaItemsWithStartPosition(listOf(mediaItem(track, ticket)), 0, snap.positionMs))
                publish(); startTicks()
            }
            return future
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onSongEnded() else publish()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publish()
            reportProgress()
        }
        override fun onPlayerError(error: PlaybackException) {
            failed = true
            publish()
        }
    }

    // ── one song ──

    private fun absolute(url: String): String {
        val base = client()?.baseUrl.orEmpty()
        return if (url.startsWith("/") && base.isNotBlank()) base.trimEnd('/') + url else url
    }

    private fun mediaItem(track: MusicTrackItem, ticket: StreamTicket): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            // FR-R322-13 — the credited artists joined ", ", the album, and the cover the app shows (≥ 512 px).
            .setArtist(track.artists.joinToString(", ") { it.name })
            .setAlbumTitle(track.album)
            .apply { track.imageUrl?.let { setArtworkUri(Uri.parse(absolute(it) + (if ('?' in it) "&" else "?") + "w=720")) } }
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setUri(absolute(ticket.hlsUrl.orEmpty()))
            .setMediaId(track.id)
            .apply { if (!ticket.directPlay) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(meta)
            .build()
    }

    /** Leave the song that is open (a skip, the end, a stop): the server hears where it stopped. */
    private fun closeSong(atMs: Long? = null) {
        val id = openTrackId ?: return
        val pos = atMs ?: (exo?.currentPosition ?: 0L)
        openTrackId = null
        tickJob?.cancel()
        val c = client()
        scope.launch { runCatching { c?.stopPlayback(id, pos) } }
    }

    private fun startSong(i: Int, startMs: Long = 0L, play: Boolean = true) {
        val track = q.tracks.getOrNull(i) ?: return
        closeSong()
        q.index = i
        failed = false; ended = false
        parkedPositionMs = startMs
        _state.value = _state.value.copy(queue = q.tracks, index = i, context = context, playing = play, buffering = true, positionMs = startMs,
            durationMs = track.durationMs ?: 0L, failed = false, ended = false)
        if (play) ensureService()
        loadJob?.cancel()
        loadJob = scope.launch {
            val c = client()
            val ticket = runCatching { c?.playMusic(track.id, ClientCapabilities(), startMs.takeIf { it > 0 }) }.getOrNull()
            if (ticket == null) { failed = true; publish(); return@launch }
            openTrackId = track.id
            val p = player()
            p.volume = musicVolumeScale(track, context, q.shuffled, MusicPrefs.evenVolume)
            p.setMediaItem(mediaItem(track, ticket), startMs)
            p.prepare()
            p.playWhenReady = play
            publish()
            save()
            startTicks()
        }
    }

    private fun onSongEnded() {
        // An emptied player also reports "ended"; only a song that was open can end.
        if (openTrackId == null) { publish(); return }
        val next = q.nextIndex(repeat)
        if (next != null) { startSong(next, 0L, true); return }
        // FR-R322-5 — queue end: stay on the last song, paused at 0:00. Nothing restarts on its own.
        closeSong(atMs = exo?.duration?.takeIf { it > 0 })
        exo?.pause(); exo?.seekTo(0)
        ended = true; parkedPositionMs = 0L
        publish(); save()
    }

    /** FR-R322-... progress every 10 s while a song is open, so Jellyfin's Now Playing and play counts are right. */
    private fun startTicks() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                delay(10_000)
                reportProgress()
                save()
            }
        }
    }

    private fun reportProgress() {
        val id = openTrackId ?: return
        val p = exo ?: return
        val pos = p.currentPosition
        val paused = !p.playWhenReady
        val c = client()
        scope.launch { runCatching { c?.reportProgress(id, pos, paused) } }
    }

    private fun publish() {
        val p = exo?.takeIf { openTrackId != null }
        val track = q.current
        _state.value = MusicPlayerState(
            queue = q.tracks, index = q.index, context = context,
            playing = p != null && p.playWhenReady && !failed && !ended,
            buffering = p != null && p.playbackState == Player.STATE_BUFFERING,
            positionMs = p?.currentPosition ?: parkedPositionMs,
            durationMs = p?.duration?.takeIf { it > 0 } ?: track?.durationMs ?: 0L,
            repeat = repeat, shuffle = q.shuffled, failed = failed, ended = ended,
        )
    }

    private fun save() {
        val uid = MultiTokenStore.getActive()?.userId ?: return
        if (q.tracks.isEmpty()) { MusicQueueStore.clear(); return }
        MusicQueueStore.save(MusicQueueSnapshot(uid, q.tracks, q.index, currentPositionMs(), context))
    }

    // ── commands ──

    actual fun playQueue(tracks: List<MusicTrackItem>, startIndex: Int, context: MusicContext, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        this.context = context
        q.set(tracks, startIndex, shuffle)
        startSong(q.index)
    }

    actual fun loadPaused(tracks: List<MusicTrackItem>, index: Int, positionMs: Long, context: MusicContext?) {
        if (tracks.isEmpty()) return
        closeSong()
        exo?.stop()
        this.context = context
        q.restore(tracks, index)
        failed = false; ended = false
        parkedPositionMs = positionMs.coerceAtLeast(0L)
        publish()
    }

    actual fun togglePlay() { if (_state.value.playing) pause() else play() }

    actual fun play() {
        if (q.current == null) return
        if (openTrackId == null || failed) startSong(q.index, if (ended) 0L else currentPositionMs(), true)
        else { ensureService(); exo?.play() }
    }

    actual fun pause() { exo?.pause(); publish(); save() }

    actual fun next() {
        val n = q.skipIndex(repeat) ?: return
        startSong(n, 0L, true)
    }

    actual fun previous() {
        if (currentPositionMs() > 3_000L) { seekTo(0L); return }
        val p = q.previousIndex()
        if (p == null) seekTo(0L) else startSong(p, 0L, true)
    }

    actual fun seekTo(positionMs: Long) {
        if (openTrackId != null) exo?.seekTo(positionMs.coerceAtLeast(0L)) else parkedPositionMs = positionMs.coerceAtLeast(0L)
        if (ended && positionMs > 0) ended = false
        publish()
    }

    actual fun playAt(index: Int) { if (index in q.tracks.indices) startSong(index, 0L, true) }

    actual fun cycleRepeat() {
        repeat = when (repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }
        publish()
    }

    actual fun toggleShuffle() { q.setShuffle(!q.shuffled); publish(); save() }
    actual fun move(from: Int, to: Int) { q.move(from, to); publish(); save() }
    actual fun remove(index: Int) { q.remove(index); publish(); save() }

    actual fun playNext(track: MusicTrackItem) {
        val wasEmpty = q.current == null
        q.insertNext(track)
        if (wasEmpty) { context = MusicContext("queue", track.title); startSong(0) } else { publish(); save() }
    }

    actual fun addToQueue(track: MusicTrackItem) {
        val wasEmpty = q.current == null
        q.append(track)
        if (wasEmpty) { context = MusicContext("queue", track.title); startSong(0) } else { publish(); save() }
    }

    actual fun clear() {
        loadJob?.cancel()
        closeSong()
        exo?.stop(); exo?.clearMediaItems()
        q.clear(); context = null; failed = false; ended = false; parkedPositionMs = 0L
        MusicQueueStore.clear()
        publish()
    }

    actual fun stopForVideo() {
        if (openTrackId == null) return
        val pos = currentPositionMs()
        loadJob?.cancel()
        closeSong(pos)
        exo?.stop()
        parkedPositionMs = pos
        publish(); save()
    }

    actual fun retry() { if (q.current != null) startSong(q.index, currentPositionMs(), true) }

    actual fun skip() {
        val n = q.skipIndex(repeat)
        if (n != null) startSong(n, 0L, true) else { failed = false; publish() }
    }

    actual fun currentPositionMs(): Long = if (openTrackId != null) exo?.currentPosition ?: parkedPositionMs else parkedPositionMs

    actual fun setEvenVolume(on: Boolean) {
        MusicPrefs.evenVolume = on
        val t = q.current ?: return
        exo?.volume = musicVolumeScale(t, context, q.shuffled, on)
    }

    /** The service went away (the app was removed from recents, or the system stopped it). */
    internal fun onServiceDestroyed() {
        clear()
        session?.release(); session = null
        exo?.release(); exo = null
    }
}
