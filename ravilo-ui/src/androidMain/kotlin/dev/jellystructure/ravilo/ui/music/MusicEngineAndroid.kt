package dev.jellystructure.ravilo.ui.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.TokenStore
import dev.jellystructure.ravilo.ui.createTvApiClient
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.ravilo.ui.seams.RaviloPlayerEngine
import dev.jellystructure.shared.tv.AudiobookDetail
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
import kotlinx.coroutines.isActive
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

    // ── R323: a book in place of songs ──
    private var book: BookPlayback? = null
    /** The part playing came as a direct stream, so the next one may be fetched early (dev review 3): an early
     *  ticket supersedes this part's Jellyfin session, which only a direct stream survives. */
    private var partDirect = false
    /** The next part, already handed to ExoPlayer for a seamless boundary. */
    private var queuedPart: Int? = null
    private var prefetching = false
    /** *Play* pressed while a part is still loading: the load plays it, rather than a second load starting. */
    private var pendingPlay = false
    private var watchJob: Job? = null
    private var lastChapter = -1
    private const val BACK30 = "ravilo.book.back30"
    private const val FWD30 = "ravilo.book.fwd30"
    private val back30 = SessionCommand(BACK30, Bundle.EMPTY)
    private val fwd30 = SessionCommand(FWD30, Bundle.EMPTY)

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
        val own = QueuePlayer(p).also { local = it }
        session = MediaSession.Builder(ctx, remote ?: own)
            .setId("ravilo-music")
            .setCallback(SessionCallback)
            .apply { activityIntent(ctx)?.let { setSessionActivity(it) } }
            .build()
        remote?.let { showStopCasting() }
        return p
    }

    /** The session the service hosts; built on first use. */
    internal fun sessionOrBuild(): MediaSession { player(); return session!! }

    // ── R356 (FR-R356-1): while the phone is the remote for a cast, the session plays the cast's mirror ──

    /** The phone's own player as the session sees it (next/previous from the queue). */
    private var local: QueuePlayer? = null
    /** The cast's mirror ([CastSessionRemote]) while a cast is live; null = the phone's own player. */
    private var remote: Player? = null
    private const val STOP_CAST = CastSessionRemote.STOP_CASTING_ACTION
    private val stopCast = SessionCommand(STOP_CAST, Bundle.EMPTY)

    /**
     * R356 — [p] becomes the session's player (the card, the lock screen, the media keys follow the cast), and the
     * service is started so Media3 can keep it in the foreground while the cast plays; null gives the session back to
     * the phone's own player. Main thread.
     */
    internal fun useRemote(p: Player?) {
        if (remote === p) return
        remote = p
        if (p != null) {
            val s = sessionOrBuild()
            s.player = p
            showStopCasting()
            ensureService()
        } else {
            val s = session ?: return
            local?.let { s.player = it }
            runCatching { s.setMediaButtonPreferences(emptyList()) }
            if (book != null) applyBookPlayer()
        }
    }

    /** The card's *Stop casting* (FR-R356-3), beside the transport. */
    private fun showStopCasting() {
        runCatching {
            session?.setMediaButtonPreferences(listOf(
                CommandButton.Builder(CommandButton.ICON_STOP).setSessionCommand(stopCast)
                    .setDisplayName(dev.jellystructure.ravilo.ui.i18n.t("cast.stop", dev.jellystructure.ravilo.i18n.LastLanguage.read() ?: "en"))
                    .setSlots(CommandButton.SLOT_OVERFLOW).build(),
            ))
        }
    }

    /** R356 (FR-R356-4) — a cover as the card loads it: absolute, at the size the phone's own player asks for. */
    internal fun artworkUri(url: String): String = absolute(url) + (if ('?' in url) "&" else "?") + "w=720"

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

    /** Dev review 7 / open question 2 → the queue: after a reboot the system's *play* resumes the saved queue.
     *  R323 (dev review 2) — a book's −30 s / +30 s are two custom commands on the card. */
    private object SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(back30).add(fwd30).add(stopCast).build())
                .build()

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                BACK30 -> skipBy(-30_000L)
                FWD30 -> skipBy(30_000L)
                STOP_CAST -> CastSessionRemote.onCardAction(customCommand.customAction)   // R356 (FR-R356-3, -14a)
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            // R323 — the last thing listened to was a book: resume it where the server says this viewer is.
            val last = BookLastStore.load()?.takeIf { it.first == MultiTokenStore.getActive()?.userId && MusicQueueStore.load() == null }
            if (last != null) {
                scope.launch {
                    val c = client()
                    val d = runCatching { c?.getAudiobook(last.second) }.getOrNull()
                    val part = d?.position?.part ?: 0
                    val pos = d?.position?.positionMs ?: 0L
                    val ticket = d?.let { runCatching { c?.playAudiobook(it.id, part, ClientCapabilities(), pos.takeIf { p -> p > 0 }) }.getOrNull() }
                    if (d == null || ticket == null) { future.setException(IllegalStateException("could not resume")); return@launch }
                    book = BookPlayback(d, part, d.speed); openTrackId = d.parts.getOrNull(part)?.id; partDirect = ticket.directPlay
                    future.set(MediaSession.MediaItemsWithStartPosition(listOf(bookItem(d, part, ticket)), 0, pos))
                    applyBookPlayer(); publish(); startTicks(); startWatch()
                }
                return future
            }
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
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // R323 — the part queued early took over, without a gap: it is now the one playing.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) onPartTransition()
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

    /** R323 (FR-R323-8) — the card: title = the chapter, artist = the author, album = the book, the cover. */
    private fun bookMeta(d: AudiobookDetail, part: Int, positionMs: Long): MediaMetadata {
        val ch = BookMath.chapter(d, BookMath.bookPosition(d, part, positionMs))
        return MediaMetadata.Builder()
            .setTitle(ch?.title?.takeIf { it.isNotBlank() } ?: d.title)
            .setArtist(d.authors.joinToString(", ") { it.name })
            .setAlbumTitle(d.title)
            .apply { d.coverUrl?.let { setArtworkUri(Uri.parse(absolute(it) + (if ('?' in it) "&" else "?") + "w=720")) } }
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .build()
    }

    private fun bookItem(d: AudiobookDetail, part: Int, ticket: StreamTicket, positionMs: Long = 0L): MediaItem =
        MediaItem.Builder()
            .setUri(absolute(ticket.hlsUrl.orEmpty()))
            .setMediaId(d.parts.getOrNull(part)?.id ?: "${d.id}#$part")
            .apply { if (!ticket.directPlay) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setMediaMetadata(bookMeta(d, part, positionMs))
            .build()

    /** Leave the song that is open (a skip, the end, a stop): the server hears where it stopped. */
    private fun closeSong(atMs: Long? = null) {
        val id = openTrackId ?: return
        val pos = atMs ?: (exo?.currentPosition ?: 0L)
        openTrackId = null
        tickJob?.cancel()
        val c = client()
        val b = book
        scope.launch {
            // R323 — the book's own place first (ours), then the part's Jellyfin session ends.
            if (b != null) runCatching { c?.audiobookProgress(b.id, b.part, pos, true) }
            runCatching { c?.stopPlayback(id, pos) }
        }
    }

    private fun startSong(i: Int, startMs: Long = 0L, play: Boolean = true) {
        val track = q.tracks.getOrNull(i) ?: return
        closeSong()
        dropBook()
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
            // A load replaced by a newer one (a quick skip) was cancelled, not failed: runCatching caught the
            // cancellation, and flagging it would raise the failure sheet over the song that is now playing.
            if (!isActive) return@launch
            if (ticket == null) { failed = true; publish(); return@launch }
            openTrackId = track.id
            val p = player()
            p.volume = musicVolumeScale(track, context, q.shuffled, MusicPrefs.evenVolume) * userVolume
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
        book?.let { b -> onPartEnded(b); return }
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
        // R323 (280's dev review 1) — a book's heartbeat is the book's; the server mirrors it to the part's session.
        val b = book
        if (b != null) { scope.launch { runCatching { c?.audiobookProgress(b.id, b.part, pos, paused) } }; return }
        scope.launch { runCatching { c?.reportProgress(id, pos, paused) } }
    }

    private fun publish() {
        val p = exo?.takeIf { openTrackId != null }
        val track = q.current
        val b = book
        if (b != null) {
            _state.value = MusicPlayerState(
                playing = p != null && p.playWhenReady && !failed && !b.finished,
                buffering = p != null && p.playbackState == Player.STATE_BUFFERING,
                positionMs = p?.currentPosition ?: parkedPositionMs,
                durationMs = b.detail.parts.getOrNull(b.part)?.durationMs?.takeIf { it > 0 } ?: p?.duration?.takeIf { it > 0 } ?: 0L,
                failed = failed, book = b,
            )
            return
        }
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
        book?.let { BookLastStore.save(uid, it.id); MusicQueueStore.clear(); return }
        if (q.tracks.isEmpty()) { MusicQueueStore.clear(); return }
        MusicQueueStore.save(MusicQueueSnapshot(uid, q.tracks, q.index, currentPositionMs(), context))
    }

    // ── commands ──

    actual fun playQueue(tracks: List<MusicTrackItem>, startIndex: Int, context: MusicContext, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        endBook()
        this.context = context
        q.set(tracks, startIndex, shuffle)
        startSong(q.index)
    }

    actual fun loadPaused(tracks: List<MusicTrackItem>, index: Int, positionMs: Long, context: MusicContext?) {
        if (tracks.isEmpty()) return
        closeSong()
        dropBook()
        exo?.stop()
        this.context = context
        q.restore(tracks, index)
        failed = false; ended = false
        parkedPositionMs = positionMs.coerceAtLeast(0L)
        // R352 (FR-R352-4) — what was loaded is now the last-played record (*Stop casting* brings the speaker's queue
        // back through here; it used to leave the record at the song from before the cast).
        publish(); save()
    }

    actual fun togglePlay() { if (_state.value.playing) pause() else play() }

    actual fun play() {
        book?.let { b ->
            if (b.finished) return
            if (openTrackId == null && !failed && loadJob?.isActive == true) { pendingPlay = true; ensureService(); return }
            if (openTrackId == null || failed) startPart(b.part, currentPositionMs(), true) else { ensureService(); exo?.play() }
            return
        }
        if (q.current == null) return
        if (openTrackId == null || failed) startSong(q.index, if (ended) 0L else currentPositionMs(), true)
        else { ensureService(); exo?.play() }
    }

    actual fun pause() { exo?.pause(); publish(); save() }

    actual fun next() {
        if (book != null) { skipBy(30_000L); return }   // a headset's *next* on a book
        val n = q.skipIndex(repeat) ?: return
        startSong(n, 0L, true)
    }

    actual fun previous() {
        if (book != null) { skipBy(-30_000L); return }
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
        endBook()
        val wasEmpty = q.current == null
        q.insertNext(track)
        if (wasEmpty) { context = MusicContext("queue", track.title); startSong(0) } else { publish(); save() }
    }

    actual fun addToQueue(track: MusicTrackItem) {
        endBook()
        val wasEmpty = q.current == null
        q.append(track)
        if (wasEmpty) { context = MusicContext("queue", track.title); startSong(0) } else { publish(); save() }
    }

    actual fun clear() {
        loadJob?.cancel()
        closeSong()
        dropBook()
        BookLastStore.clear()
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
        watchJob?.cancel(); queuedPart = null
        exo?.stop()
        parkedPositionMs = pos
        publish(); save()
    }

    actual fun retry() {
        book?.let { startPart(it.part, currentPositionMs(), true); return }
        if (q.current != null) startSong(q.index, currentPositionMs(), true)
    }

    actual fun skip() {
        if (book != null) { retry(); return }
        val n = q.skipIndex(repeat)
        if (n != null) startSong(n, 0L, true) else { failed = false; publish() }
    }

    actual fun currentPositionMs(): Long = if (openTrackId != null) exo?.currentPosition ?: parkedPositionMs else parkedPositionMs

    /**
     * R337 — the desktop bar's slider; a phone's own volume is its keys. R354 (FR-R354-6) — a remote command's level
     * (the Jellyfin dashboard's SetVolume / Mute): the output is scaled by it, on top of *Even out volume*'s gain and
     * a book's sleep fade, and it stays for the songs and parts after this one.
     */
    @Volatile private var userVolume = 1f
    actual fun setUserVolume(level: Float) {
        userVolume = level.coerceIn(0f, 1f)
        val p = exo ?: return
        val t = q.current
        p.volume = if (book == null && t != null) musicVolumeScale(t, context, q.shuffled, MusicPrefs.evenVolume) * userVolume else userVolume
    }
    actual fun setEvenVolume(on: Boolean) {
        MusicPrefs.evenVolume = on
        val t = q.current ?: return
        exo?.volume = musicVolumeScale(t, context, q.shuffled, on) * userVolume
    }

    // ── R323: the book ──

    /** A song is starting: the book goes (its place was already sent by [closeSong]). */
    private fun dropBook() {
        if (book == null) return
        book = null; queuedPart = null; prefetching = false; lastChapter = -1
        watchJob?.cancel()
        exo?.let { it.setPlaybackSpeed(1f); it.skipSilenceEnabled = false; it.volume = userVolume }
        if (remote == null) runCatching { session?.setMediaButtonPreferences(emptyList()) }
    }

    /** The music queue is starting while a book is loaded: close the part and drop the book. */
    private fun endBook() {
        if (book == null) return
        loadJob?.cancel()
        closeSong()
        exo?.stop(); exo?.clearMediaItems()
        dropBook()
    }

    /** The player's settings for a book: its speed, skip silence, full volume, and ±30 s on the card. */
    private fun applyBookPlayer() {
        val b = book ?: return
        val p = exo ?: return
        p.setPlaybackSpeed(b.speed.toFloat())
        p.skipSilenceEnabled = BookPrefs.skipSilence
        p.volume = userVolume
        if (remote != null) return   // R356 — the card is the cast's while one is live
        runCatching {
            session?.setMediaButtonPreferences(listOf(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30).setSessionCommand(back30).setDisplayName("-30").setSlots(CommandButton.SLOT_BACK).build(),
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30).setSessionCommand(fwd30).setDisplayName("+30").setSlots(CommandButton.SLOT_FORWARD).build(),
            ))
        }
    }

    private fun startPart(part: Int, startMs: Long, play: Boolean) {
        val b = book ?: return
        val d = b.detail
        if (part !in d.parts.indices) return
        closeSong()
        watchJob?.cancel(); queuedPart = null; prefetching = false
        book = b.copy(part = part, finished = false)
        failed = false; ended = false
        parkedPositionMs = startMs
        publish()
        if (play) ensureService()
        loadJob?.cancel()
        loadJob = scope.launch {
            val c = client()
            val ticket = runCatching { c?.playAudiobook(d.id, part, ClientCapabilities(), startMs.takeIf { it > 0 }) }.getOrNull()
            if (!isActive) return@launch   // replaced by a newer start — cancelled, not failed
            if (ticket == null) { failed = true; publish(); return@launch }
            openTrackId = d.parts[part].id
            partDirect = ticket.directPlay
            val p = player()
            p.setMediaItem(bookItem(d, part, ticket, startMs), startMs)
            p.prepare()
            applyBookPlayer()
            p.playWhenReady = play || pendingPlay
            pendingPlay = false
            publish(); save(); startTicks(); startWatch()
        }
    }

    /** ExoPlayer moved on to the part queued early. That part's session is open (its ticket started it). */
    private fun onPartTransition() {
        val b = book ?: return
        val next = queuedPart ?: return
        queuedPart = null
        openTrackId = b.detail.parts.getOrNull(next)?.id
        book = b.copy(part = next)
        exo?.let { if (it.mediaItemCount > 1 && it.currentMediaItemIndex > 0) it.removeMediaItems(0, it.currentMediaItemIndex) }
        lastChapter = -1
        val c = client()
        scope.launch { runCatching { c?.audiobookProgress(b.id, next, 0L, false) } }   // ours moves on; earlier parts marked played
        publish(); save(); startTicks()
    }

    /** A part played to its end with nothing queued: the next part (a short gap), or the book is finished. */
    private fun onPartEnded(b: BookPlayback) {
        val next = b.part + 1
        if (next in b.detail.parts.indices) { startPart(next, 0L, true); return }
        val end = b.detail.parts.getOrNull(b.part)?.durationMs ?: exo?.duration ?: 0L
        closeSong(atMs = end)   // the last part within its last five minutes: the server marks it finished
        exo?.pause()
        watchJob?.cancel()
        book = b.copy(finished = true, sleep = null)
        parkedPositionMs = end
        publish()
    }

    /** Twice a second while a book is loaded: fetch the next part early, keep the card's chapter right, and run
     *  the sleep timer (with its fade). */
    private fun startWatch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            while (true) {
                delay(500)
                val b = book ?: return@launch
                val p = exo ?: continue
                if (openTrackId == null) continue
                val pos = p.currentPosition
                val d = b.detail
                val partLen = d.parts.getOrNull(b.part)?.durationMs ?: p.duration
                val speed = b.speed.coerceAtLeast(0.1)
                // Dev review 3 — the next part's ticket before the boundary, so Media3 crosses it without a gap.
                val next = b.part + 1
                if (partDirect && queuedPart == null && !prefetching && next in d.parts.indices && partLen > 0 && (partLen - pos) / speed < 20_000) {
                    prefetching = true
                    val c = client()
                    scope.launch {
                        val t = runCatching { c?.playAudiobook(d.id, next, ClientCapabilities(), null) }.getOrNull()
                        prefetching = false
                        if (t != null && book?.id == d.id && book?.part == next - 1) { exo?.addMediaItem(bookItem(d, next, t)); queuedPart = next }
                    }
                }
                // FR-R323-8 — the card's title follows the chapter.
                val bookPos = BookMath.bookPosition(d, b.part, pos)
                val ch = BookMath.chapterAt(d, bookPos)
                if (ch != lastChapter) {
                    lastChapter = ch
                    val i = p.currentMediaItemIndex
                    p.currentMediaItem?.let { item -> runCatching { p.replaceMediaItem(i, item.buildUpon().setMediaMetadata(bookMeta(d, b.part, pos)).build()) } }
                }
                // FR-R323-5 — the sleep timer, fading its last 10 s when the fade is on.
                val s = b.sleep ?: continue
                val leftMs = when {
                    s.endsAtMs != null -> s.endsAtMs - System.currentTimeMillis()
                    s.endOfChapter -> ((BookMath.chapterEnd(d, s.chapter) - bookPos) / speed).toLong()
                    else -> Long.MAX_VALUE
                }
                if (leftMs <= 0) {
                    p.pause(); p.volume = userVolume
                    book = book?.copy(sleep = null)
                    publish(); reportProgress()
                } else if (BookPrefs.sleepFade && leftMs < 10_000) p.volume = (leftMs / 10_000f).coerceIn(0f, 1f) * userVolume
                else if (p.volume != userVolume) p.volume = userVolume
            }
        }
    }

    actual fun playBook(detail: AudiobookDetail, part: Int, positionMs: Long, play: Boolean) {
        if (detail.parts.isEmpty()) return
        // One listening queue at a time (FR-R323-7): the songs go.
        if (q.current != null || book?.id != detail.id) {
            loadJob?.cancel()
            closeSong()
            exo?.stop(); exo?.clearMediaItems()
            q.clear(); context = null
            MusicQueueStore.clear()
        }
        val speed = book?.takeIf { it.id == detail.id }?.speed ?: detail.speed
        book = BookPlayback(detail, part.coerceIn(0, detail.parts.lastIndex), speed)
        startPart(part.coerceIn(0, detail.parts.lastIndex), positionMs.coerceAtLeast(0L), play)
    }

    actual fun skipBy(deltaMs: Long) {
        if (book == null) return
        seekBook(bookPositionMs() + deltaMs)
    }

    actual fun seekBook(bookMs: Long) {
        val b = book ?: return
        val (part, off) = BookMath.locate(b.detail, bookMs)
        if (b.finished) { book = b.copy(finished = false) }
        if (part == b.part && openTrackId != null) {
            if (queuedPart != null) { exo?.let { if (it.mediaItemCount > 1) it.removeMediaItems(1, it.mediaItemCount) }; queuedPart = null }
            exo?.seekTo(off); publish(); reportProgress()
        } else startPart(part, off, _state.value.playing || openTrackId == null && !b.finished)
    }

    actual fun setSpeed(speed: Double) {
        val b = book ?: return
        val s = speed.coerceIn(0.5, 3.0)
        book = b.copy(speed = s)
        exo?.setPlaybackSpeed(s.toFloat())
        publish()
        val c = client()
        scope.launch { runCatching { c?.setAudiobookSpeed(b.id, s) } }
    }

    actual fun setSleep(timer: SleepTimer?) {
        val b = book ?: return
        val t = timer?.let { if (it.endOfChapter) it.copy(chapter = BookMath.chapterAt(b.detail, bookPositionMs())) else it }
        book = b.copy(sleep = t)
        if (t == null) exo?.volume = userVolume
        publish()
    }

    actual fun setSkipSilence(on: Boolean) {
        BookPrefs.skipSilence = on
        if (book != null) exo?.skipSilenceEnabled = on
    }

    actual fun bookPositionMs(): Long {
        val b = book ?: return 0L
        return BookMath.bookPosition(b.detail, b.part, currentPositionMs())
    }

    /** The service went away (the app was removed from recents, or the system stopped it). */
    internal fun onServiceDestroyed() {
        // R356 — while casting the phone's queue is what *Play on this phone* and a hand-back come back to: kept.
        if (remote == null) clear() else { loadJob?.cancel(); closeSong(); exo?.stop() }
        session?.release(); session = null
        exo?.release(); exo = null
        local = null
        remote = null   // R356 — a live cast's next report hands the mirror to a new session (and service)
    }

    /** R356 (FR-R356-5) — the session plays a cast's mirror: the phone itself plays nothing. */
    internal val castingRemote: Boolean get() = remote != null
}
