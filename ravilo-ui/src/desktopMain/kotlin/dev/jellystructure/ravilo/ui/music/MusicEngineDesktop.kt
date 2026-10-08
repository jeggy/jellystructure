package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.RemoteControl
import dev.jellystructure.ravilo.ui.RemoteTarget
import dev.jellystructure.ravilo.ui.TeardownWork
import dev.jellystructure.ravilo.ui.TokenStore
import dev.jellystructure.ravilo.ui.createTvApiClient
import dev.jellystructure.ravilo.ui.desktop.DesktopShutdown
import dev.jellystructure.ravilo.ui.desktop.MacNative
import dev.jellystructure.ravilo.ui.desktop.MacNowPlaying
import dev.jellystructure.ravilo.ui.desktop.DesktopEngine
import dev.jellystructure.ravilo.ui.desktop.DesktopEngines
import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
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
 * R329 (FR-R329-9) — the Mac's music engine: R322/R323's engine on an audio-only AVPlayer ([MacPlayer]). The queue
 * is [MusicQueue], a book is [BookPlayback] with [BookMath]; every screen reaches this through `MusicPlayback` (R324)
 * exactly as on the phone.
 *
 * As on Android: every song is one Jellyfin session — starting it asks the server for its stream, the engine reports
 * where it is every 10 s, and leaving it (a skip, the end, a stop) reports the stop; gaps between songs are accepted
 * (R322). Where the phone listens to ExoPlayer, this engine polls AVPlayer four times a second. Songs direct-play
 * (dev review 5): the capabilities say MP3, AAC, FLAC and ALAC, so the server converts only what AVPlayer cannot
 * (WMA, Opus, Vorbis) — 286 FR-286-7's line. A book's next part is fetched early on direct play (R323), which on
 * AVPlayer shortens the gap at the boundary rather than removing it. Media keys and Control Center come through
 * [MacNowPlaying] (FR-R329-10). Without the Swift library, [supported] is false and music mode is absent (R321).
 */
actual object MusicEngine {
    // R337 (dev review 3b) — on Linux the engine is mpv (R335 FR-R335-8); this used to ask for the Mac library on every
    // desktop, so music could never appear on Linux.
    actual val supported: Boolean = if (DesktopPaths.isMac) MacNative.lib != null else dev.jellystructure.ravilo.ui.desktop.Mpv.lib != null
    private val _state = MutableStateFlow(MusicPlayerState())
    actual val state: StateFlow<MusicPlayerState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val q = MusicQueue()
    private val player: DesktopEngine = DesktopEngines.music()
    /** R337 (FR-R337-6) — the desktop bar's volume, multiplied into every level the engine sets (gain, sleep fade). */
    private var userVolume = 1f
    private var lastLevel = 1f
    private fun applyVolume(level: Float) { lastLevel = level; player.setVolume((level * userVolume).coerceIn(0f, 1f)) }
    actual fun setUserVolume(level: Float) { userVolume = level.coerceIn(0f, 1f); player.setVolume((lastLevel * userVolume).coerceIn(0f, 1f)) }
    private var context: MusicContext? = null
    private var repeat = RepeatMode.OFF
    private var api: TvApiClient? = null
    /** The song (or part) whose Jellyfin session is open, and so the one progress and stop are reported for. */
    private var openTrackId: String? = null
    private var parkedPositionMs = 0L
    private var failed = false
    private var ended = false
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var watchJob: Job? = null
    /** The end of the item that is loaded has been acted on (AVPlayer reports "ended" until the next load). */
    private var endHandled = false
    /** Read by the quit (off Compose's thread), so the monitor keeps it current. */
    @Volatile private var lastPositionMs = 0L

    // ── R323: a book in place of songs ──
    private var book: BookPlayback? = null
    private var partDirect = false
    /** The next part's stream, fetched early on direct play so the boundary costs only AVPlayer's own start. */
    private var prefetched: Pair<Int, StreamTicket>? = null
    private var prefetching = false
    private var pendingPlay = false
    private var lastChapter = -1

    /** R329 — capabilities the direct-play profile reads (dev review 5); R335 (FR-R335-8) — mpv's on Linux, WMA included. */
    private val musicCapabilities = if (DesktopEngines.isMpv) ClientCapabilities(
        containers = listOf("mp3", "flac", "m4a", "mp4", "aac", "wav", "ogg", "opus", "wma", "asf", "mka", "webm"),
        audioCodecs = listOf("mp3", "aac", "flac", "alac", "pcm_s16le", "pcm_s24le", "opus", "vorbis", "wmav2", "wmapro", "ac3", "eac3"),
    ) else ClientCapabilities(
        containers = listOf("mp3", "flac", "m4a", "mp4", "aac", "wav"),
        audioCodecs = listOf("mp3", "aac", "flac", "alac", "pcm_s16le", "pcm_s24le"),
    )

    private val nowPlaying = MacNowPlaying.Target { command, seconds ->
        when (command) {
            MacNowPlaying.Command.PLAY -> play()
            MacNowPlaying.Command.PAUSE, MacNowPlaying.Command.STOP -> pause()
            MacNowPlaying.Command.TOGGLE -> togglePlay()
            MacNowPlaying.Command.NEXT -> next()
            MacNowPlaying.Command.PREVIOUS -> previous()
            MacNowPlaying.Command.SEEK_TO -> {
                val ms = (seconds * 1000).toLong()
                val b = book
                if (b != null) seekBook(BookMath.bookPosition(b.detail, b.part, ms)) else seekTo(ms)
            }
            MacNowPlaying.Command.SKIP_FORWARD -> if (book != null) skipBy((seconds * 1000).toLong()) else seekTo(currentPositionMs() + (seconds * 1000).toLong())
            MacNowPlaying.Command.SKIP_BACK -> if (book != null) skipBy(-(seconds * 1000).toLong()) else seekTo(currentPositionMs() - (seconds * 1000).toLong())
        }
    }

    init {
        // FR-R328-9 — quitting reports the open song's stop (and a book's place) before the process ends.
        DesktopShutdown.register {
            val id = openTrackId ?: return@register
            val pos = lastPositionMs
            val c = client()
            book?.let { b -> runCatching { c?.audiobookProgress(b.id, b.part, pos, true) } }
            runCatching { c?.stopPlayback(id, pos) }
        }
    }

    actual fun attach(api: TvApiClient) { this.api = api }

    private fun client(): TvApiClient? = api ?: raviloBaseUrl().takeIf { it.isNotBlank() }?.let { base ->
        createTvApiClient(base) { MultiTokenStore.getActive()?.deviceToken ?: TokenStore.get() }.also { api = it }
    }

    private fun absolute(url: String): String {
        val base = client()?.baseUrl.orEmpty()
        return if (url.startsWith("/") && base.isNotBlank()) base.trimEnd('/') + url else url
    }

    private fun artworkUrl(url: String?): String? = url?.let { absolute(it) + (if ('?' in it) "&" else "?") + "w=720" }

    private fun loadTicket(ticket: StreamTicket, startMs: Long, play: Boolean, rate: Float, volume: Float) {
        endHandled = false
        player.load(absolute(ticket.hlsUrl.orEmpty()), musicMimeFor(ticket.directPlay, ticket.container), startMs)
        player.setRate(rate)
        applyVolume(volume)
        // A load that is not to play says so: mpv starts whatever it loads (AVPlayer waits for play), so a book restored
        // at launch — loaded paused where the viewer left it — read itself aloud on Linux (Fedora, 2026-09-30).
        if (play) player.play() else player.pause()
        startMonitor()
    }

    // ── one song ──

    private fun closeSong(atMs: Long? = null) {
        val id = openTrackId ?: return
        val pos = atMs ?: player.state.positionMs
        openTrackId = null
        tickJob?.cancel()
        val c = client()
        val b = book
        TeardownWork.track(scope.launch {
            if (b != null) runCatching { c?.audiobookProgress(b.id, b.part, pos, true) }
            runCatching { c?.stopPlayback(id, pos) }
        })
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
        loadJob?.cancel()
        loadJob = scope.launch {
            val c = client()
            val ticket = runCatching { c?.playMusic(track.id, musicCapabilities, startMs.takeIf { it > 0 }) }.getOrNull()
            if (!isActive) return@launch
            if (ticket == null) { failed = true; publish(); return@launch }
            openTrackId = track.id
            MacNowPlaying.claim(nowPlaying, MacNowPlaying.Mode.MUSIC)
            MacNowPlaying.artwork(nowPlaying, artworkUrl(track.imageUrl))
            loadTicket(ticket, startMs, play, rate = 1f, volume = musicVolumeScale(track, context, q.shuffled, MusicPrefs.evenVolume))
            publish(); save(); startTicks()
        }
    }

    private fun onSongEnded() {
        if (openTrackId == null) { publish(); return }
        book?.let { b -> onPartEnded(b); return }
        val next = q.nextIndex(repeat)
        if (next != null) { startSong(next, 0L, true); return }
        // FR-R322-5 — queue end: stay on the last song, paused at 0:00. Nothing restarts on its own.
        closeSong(atMs = player.state.durationMs.takeIf { it > 0 })
        player.pause(); player.seekTo(0)
        ended = true; parkedPositionMs = 0L
        publish(); save()
    }

    /** R357 (FR-R357-4) — from the first song on, a volume change (a dashboard command, the bar's slider) reports at once. */
    private var volumeJob: Job? = null
    private fun watchVolume() {
        if (volumeJob?.isActive != true) volumeJob = scope.launch { RemoteControl.onVolumeSettled(RemoteTarget.MUSIC) { reportProgress() } }
    }

    private fun startTicks() {
        watchVolume()
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
        val s = player.state
        val pos = s.positionMs
        val paused = !s.wantsPlay
        val c = client()
        val vol = RemoteControl.musicVolumeReport()   // R357 (FR-R357-3) — the bar's level and R354's mute
        val b = book
        if (b != null) { scope.launch { runCatching { c?.audiobookProgress(b.id, b.part, pos, paused, vol) } }; return }
        scope.launch { runCatching { c?.reportProgress(id, pos, paused, vol) } }
    }

    /**
     * Four times a second while something is loaded: the end, a failure and a change of playing/buffering become
     * state (the phone's player listener); for a book also the early fetch, the chapter on the card and the sleep
     * timer (the phone's watch). Once a second, the Now Playing card.
     */
    private fun startMonitor() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            var lastPlaying: Boolean? = null
            var lastBuffering: Boolean? = null
            var n = 0
            while (isActive && player.loaded) {
                val s = player.state
                lastPositionMs = s.positionMs
                if (openTrackId != null && s.ended && !endHandled) { endHandled = true; onSongEnded() }
                if (s.failed && !failed && openTrackId != null) { failed = true; publish() }
                val playing = s.wantsPlay && !s.ended && !s.failed
                if (playing != lastPlaying || s.buffering != lastBuffering) {
                    val paused = lastPlaying == true && !playing
                    lastPlaying = playing; lastBuffering = s.buffering
                    publish()
                    if (paused) reportProgress()
                }
                book?.let { b -> watchBook(b, s.positionMs, s.durationMs) }
                if (n++ % 4 == 0) updateCard()
                delay(250)
            }
        }
    }

    private fun updateCard() {
        val s = player.state
        val b = book
        if (b != null) {
            val d = b.detail
            val ch = BookMath.chapter(d, BookMath.bookPosition(d, b.part, s.positionMs))
            MacNowPlaying.update(nowPlaying, ch?.title?.takeIf { it.isNotBlank() } ?: d.title, d.authors.joinToString(", ") { it.name }, d.title,
                d.parts.getOrNull(b.part)?.durationMs ?: s.durationMs, s.positionMs, b.speed, s.timeControl == 2, video = false)
            return
        }
        val t = q.current ?: return
        MacNowPlaying.update(nowPlaying, t.title, t.artists.joinToString(", ") { it.name }, t.album,
            s.durationMs.takeIf { it > 0 } ?: t.durationMs ?: -1L, s.positionMs, 1.0, s.timeControl == 2, video = false)
    }

    private fun publish() {
        val s = player.state.takeIf { openTrackId != null }
        val track = q.current
        val b = book
        if (b != null) {
            _state.value = MusicPlayerState(
                playing = s != null && s.wantsPlay && !failed && !b.finished,
                buffering = s != null && s.buffering,
                positionMs = s?.positionMs ?: parkedPositionMs,
                durationMs = b.detail.parts.getOrNull(b.part)?.durationMs?.takeIf { it > 0 } ?: s?.durationMs?.takeIf { it > 0 } ?: 0L,
                failed = failed, book = b,
            )
            return
        }
        _state.value = MusicPlayerState(
            queue = q.tracks, index = q.index, context = context,
            playing = s != null && s.wantsPlay && !failed && !ended,
            buffering = s != null && s.buffering,
            positionMs = s?.positionMs ?: parkedPositionMs,
            durationMs = s?.durationMs?.takeIf { it > 0 } ?: track?.durationMs ?: 0L,
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
        player.release()
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
            if (openTrackId == null && !failed && loadJob?.isActive == true) { pendingPlay = true; return }
            if (openTrackId == null || failed) startPart(b.part, currentPositionMs(), true) else player.play()
            publish()
            return
        }
        if (q.current == null) return
        if (openTrackId == null || failed) startSong(q.index, if (ended) 0L else currentPositionMs(), true)
        else { player.play(); publish() }
    }

    actual fun pause() { player.pause(); publish(); save(); reportProgress() }

    actual fun next() {
        if (book != null) { skipBy(30_000L); return }
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
        if (openTrackId != null) { player.seekTo(positionMs.coerceAtLeast(0L)); endHandled = false } else parkedPositionMs = positionMs.coerceAtLeast(0L)
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

    actual fun replaceQueue(tracks: List<MusicTrackItem>, index: Int) {
        if (tracks.isEmpty() || book != null) return
        q.restore(tracks, index)
        publish(); save()
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
        player.release()
        MacNowPlaying.release(nowPlaying)
        q.clear(); context = null; failed = false; ended = false; parkedPositionMs = 0L
        MusicQueueStore.clear()
        publish()
    }

    actual fun stopForVideo() {
        if (openTrackId == null) return
        val pos = currentPositionMs()
        loadJob?.cancel()
        closeSong(pos)
        prefetched = null
        player.release()
        MacNowPlaying.release(nowPlaying)
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

    actual fun currentPositionMs(): Long = if (openTrackId != null && player.loaded) player.state.positionMs else parkedPositionMs

    actual fun setEvenVolume(on: Boolean) {
        MusicPrefs.evenVolume = on
        val t = q.current ?: return
        if (book == null) applyVolume(musicVolumeScale(t, context, q.shuffled, on))
    }

    // ── R323: the book ──

    private fun dropBook() {
        if (book == null) return
        book = null; prefetched = null; prefetching = false; lastChapter = -1
        player.setRate(1f); applyVolume(1f)
    }

    private fun endBook() {
        if (book == null) return
        loadJob?.cancel()
        closeSong()
        player.release()
        dropBook()
    }

    private fun startPart(part: Int, startMs: Long, play: Boolean) {
        val b = book ?: return
        val d = b.detail
        if (part !in d.parts.indices) return
        closeSong()
        val early = prefetched?.takeIf { it.first == part && startMs == 0L }?.second
        prefetched = null; prefetching = false
        book = b.copy(part = part, finished = false)
        failed = false; ended = false
        parkedPositionMs = startMs
        publish()
        loadJob?.cancel()
        loadJob = scope.launch {
            val ticket = early ?: runCatching { client()?.playAudiobook(d.id, part, musicCapabilities, startMs.takeIf { it > 0 }) }.getOrNull()
            if (!isActive) return@launch
            if (ticket == null) { failed = true; publish(); return@launch }
            openTrackId = d.parts[part].id
            partDirect = ticket.directPlay
            lastChapter = -1
            MacNowPlaying.claim(nowPlaying, MacNowPlaying.Mode.BOOK)
            MacNowPlaying.artwork(nowPlaying, artworkUrl(d.coverUrl))
            loadTicket(ticket, startMs, play || pendingPlay, rate = (book?.speed ?: b.speed).toFloat(), volume = 1f)
            pendingPlay = false
            if (early != null) { val c = client(); scope.launch { runCatching { c?.audiobookProgress(d.id, part, 0L, false) } } }
            publish(); save(); startTicks()
        }
    }

    private fun onPartEnded(b: BookPlayback) {
        val next = b.part + 1
        if (next in b.detail.parts.indices) { startPart(next, 0L, true); return }
        val end = b.detail.parts.getOrNull(b.part)?.durationMs ?: player.state.durationMs
        closeSong(atMs = end)
        player.pause()
        book = b.copy(finished = true, sleep = null)
        parkedPositionMs = end
        publish()
    }

    /** The phone's watch, run by the monitor: fetch the next part early, keep the card's chapter, run the sleep timer. */
    private fun watchBook(b: BookPlayback, pos: Long, duration: Long) {
        if (openTrackId == null) return
        val d = b.detail
        val partLen = d.parts.getOrNull(b.part)?.durationMs ?: duration
        val speed = b.speed.coerceAtLeast(0.1)
        val next = b.part + 1
        if (partDirect && prefetched == null && !prefetching && next in d.parts.indices && partLen > 0 && (partLen - pos) / speed < 20_000) {
            prefetching = true
            val c = client()
            scope.launch {
                val t = runCatching { c?.playAudiobook(d.id, next, musicCapabilities, null) }.getOrNull()
                prefetching = false
                if (t != null && book?.id == d.id && book?.part == next - 1) prefetched = next to t
            }
        }
        val bookPos = BookMath.bookPosition(d, b.part, pos)
        val ch = BookMath.chapterAt(d, bookPos)
        if (ch != lastChapter) { lastChapter = ch; updateCard() }
        val s = b.sleep ?: return
        val leftMs = when {
            s.endsAtMs != null -> s.endsAtMs - System.currentTimeMillis()
            s.endOfChapter -> ((BookMath.chapterEnd(d, s.chapter) - bookPos) / speed).toLong()
            else -> Long.MAX_VALUE
        }
        if (leftMs <= 0) {
            player.pause(); applyVolume(1f)
            book = book?.copy(sleep = null)
            publish(); reportProgress()
        } else if (BookPrefs.sleepFade && leftMs < 10_000) applyVolume((leftMs / 10_000f).coerceIn(0f, 1f))
    }

    actual fun playBook(detail: AudiobookDetail, part: Int, positionMs: Long, play: Boolean) {
        if (detail.parts.isEmpty()) return
        if (q.current != null || book?.id != detail.id) {
            loadJob?.cancel()
            closeSong()
            player.release()
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
            player.seekTo(off); endHandled = false; publish(); reportProgress()
        } else startPart(part, off, _state.value.playing || openTrackId == null && !b.finished)
    }

    actual fun setSpeed(speed: Double) {
        val b = book ?: return
        val s = speed.coerceIn(0.5, 3.0)
        book = b.copy(speed = s)
        player.setRate(s.toFloat())
        publish()
        val c = client()
        scope.launch { runCatching { c?.setAudiobookSpeed(b.id, s) } }
    }

    actual fun setSleep(timer: SleepTimer?) {
        val b = book ?: return
        val t = timer?.let { if (it.endOfChapter) it.copy(chapter = BookMath.chapterAt(b.detail, bookPositionMs())) else it }
        book = b.copy(sleep = t)
        if (t == null) applyVolume(1f)
        publish()
    }

    /** AVPlayer cannot skip silences; the setting is absent on the Mac ([playerSkipsSilence]) and stored only. */
    actual fun setSkipSilence(on: Boolean) { BookPrefs.skipSilence = on }

    actual fun bookPositionMs(): Long {
        val b = book ?: return 0L
        return BookMath.bookPosition(b.detail, b.part, currentPositionMs())
    }
}

/**
 * A direct-played song's URL (`/Audio/{id}/stream?Static=true…`) has no extension, so AVPlayer is told what the file
 * is, from the container Jellyfin reports; a transcode is HLS and needs nothing. Unknown ⇒ empty (AVPlayer guesses).
 */
internal fun musicMimeFor(directPlay: Boolean, container: String): String {
    if (!directPlay) return ""
    val c = container.lowercase()
    return when {
        "flac" in c -> "audio/flac"
        "mp3" in c -> "audio/mpeg"
        "m4a" in c || "mp4" in c || "mov" in c || "alac" in c -> "audio/mp4"
        "aac" in c -> "audio/aac"
        "wav" in c -> "audio/wav"
        else -> ""
    }
}

