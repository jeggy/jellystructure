package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.components.CastController
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.shared.tv.AudiobookDetail
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.MusicArtistRef
import dev.jellystructure.shared.tv.MusicTrackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/*
 * R324 — the phone plays music on the household's speakers. Two objects:
 *
 * [MusicCast] is the bridge (dev review 4): while a Cast session carries a music queue, the phone is a REMOTE — its
 * state is rebuilt from the receiver's reports (286's `queue` snapshot + the SDK's position), never from anything the
 * engine remembers, and every command becomes a message to the receiver. It also owns the hand-off both ways
 * (FR-R324-3): the engine's queue goes to the receiver the moment the session connects; *Play on this phone* pulls
 * the receiver's queue and position back into the engine and stops the speaker.
 *
 * [MusicPlayback] is what every music screen calls. It has [MusicEngine]'s surface and routes each call to the
 * receiver while [MusicCast.linked], to the engine otherwise — so the Playing tab, the Queue tab and the mini bar are
 * one code path whichever device plays (FR-R324-4). Books never cast; their calls always reach the engine.
 */
object MusicCast {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var cast: CastController? = null
    private var bindJob: Job? = null

    private val _linked = MutableStateFlow(false)
    /** A Cast session is connected and carries a music queue. */
    val linked: StateFlow<Boolean> = _linked.asStateFlow()
    private val _status = MutableStateFlow<CastRemoteStatus?>(null)
    val status: StateFlow<CastRemoteStatus?> = _status.asStateFlow()
    private val _device = MutableStateFlow<String?>(null)
    /** The speaker's (or display's) name, for *Playing on {device}*, the mini bar and the ⋯ block. */
    val deviceName: StateFlow<String?> = _device.asStateFlow()
    /** FR-R324-7 — swipe-down hid the mini bar; the room plays on. Cleared when Playing opens or a new song starts. */
    val barHidden = MutableStateFlow(false)
    private var lastItemId: String? = null
    /**
     * A device is connected and is not showing a film: what is started next plays there. True from the moment a speaker
     * is chosen until *Stop casting* — also after its queue has played out, when [linked] is false again. Without it
     * a song picked then played on the computer, with the speaker still lit as chosen (the Mac, 2026-09-30).
     */
    @kotlin.concurrent.Volatile var holdsDevice: Boolean = false
        private set

    /** The full items behind the ids the receiver echoes back: artists' ids, album ids, lyrics flags survive a round trip. */
    private val known = LinkedHashMap<String, MusicTrackItem>()
    /** *Playing from {label}* while casting — the context the queue left the phone with. */
    private var context: MusicContext? = null

    val controller: CastController? get() = cast

    /** Wired once by the app root with the one [CastController] (R245) — a second call with the same one is a no-op. */
    fun bind(c: CastController) {
        if (cast === c) return
        cast = c
        bindJob?.cancel()
        bindJob = scope.launch {
            combine(c.sender.link, c.sender.status, c.sender.deviceName) { l, s, n -> Triple(l, s, n) }.collect { (link, st, name) ->
                val wasLinked = _linked.value
                val nowLinked = castMusicLive(link, st)
                _status.value = st
                _device.value = name
                _linked.value = nowLinked
                holdsDevice = link == CastLinkState.CONNECTED && (st == null || st.music || !st.loaded || st.ended || st.failed)
                if (nowLinked && st?.itemId != lastItemId) { lastItemId = st?.itemId; barHidden.value = false }
                if (nowLinked && st != null) follow(st)
                // FR-R324-3 — the session just connected from the music-mode sheet: hand the phone's queue over, or
                // join with nothing playing here (FR-R324-2's second remote).
                if (link != CastLinkState.CONNECTED) awaitNewLink = false
                if (link == CastLinkState.CONNECTED && c.pendingMusicHandoff && !awaitNewLink) {
                    c.pendingMusicHandoff = false
                    // R353 — the engine's player is main-thread only (ExoPlayer throws off it): this collector runs on
                    // Default, so the hand-off hops to Main. On the Pixel 9 the first song handed to a speaker killed the app.
                    scope.launch(Dispatchers.Main) { handOff(c) }
                }
                if (wasLinked && !nowLinked) barHidden.value = false
                // R353 (FR-R353-5) — what the speaker last said about its queue while a song was live, kept past the
                // song's end and the session's end (the sender clears its status to null as the link drops, and an
                // `ended` report carries no useful place, so it must be kept before either).
                // Only a LIVE report counts: after the receiver's `ended` the next media status is idle (not loaded,
                // not ended, position 0) and used to replace the song's place (the Pixel 9, 2026-10-02).
                if (nowLinked && st != null && st.queue.isNotEmpty()) {
                    lastMusic = st; lastMusicAt = kotlin.time.TimeSource.Monotonic.markNow()
                } else if (link == CastLinkState.CONNECTED && st != null && !st.music && st.loaded) {
                    lastMusic = null   // a film took the device: there is no song to come back to
                }
                if (nowLinked || link != CastLinkState.CONNECTED) { stoppedJob?.cancel(); stoppedJob = null; resumeSentAt = null }
                // FR-R353-5 (amended 2026-10-02) — the music stopped ON the device while the session stays: the
                // Jellyfin dashboard's Stop, the queue playing out, the TV remote's Stop key. The receiver goes to its
                // idle view and says `ended`; the phone keeps the session and takes the speaker's song back, paused,
                // so Play starts it on the speaker again. Confirmed after a short settle, because a song finishing
                // reports IDLE for a moment before the receiver loads the next one.
                if (castStoppedOnDevice(wasLinked, link, st, endedByApp)) {
                    val last = lastMusic
                    val elapsed = lastMusicAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
                    stoppedJob?.cancel()
                    stoppedJob = scope.launch {
                        kotlinx.coroutines.delay(STOP_SETTLE_MS)
                        if (_linked.value || c.sender.link.value != CastLinkState.CONNECTED || endedByApp || lastMusic !== last) return@launch
                        lastMusic = null; lastMusicAt = null
                        handBack(last, elapsed)
                    }
                }
                // A session that ends any way but ours (Google Home's *Stop cast*, the notification, the device
                // dropping the app, the network): the speaker's song and place come back to this device, paused.
                if (wasConnected && link != CastLinkState.CONNECTED) {
                    val handed = lastMusic
                    val elapsed = lastMusicAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
                    val byApp = endedByApp
                    endedByApp = false; lastMusic = null; lastMusicAt = null
                    if (!byApp) handBack(handed, elapsed)
                }
                wasConnected = link == CastLinkState.CONNECTED
            }
        }
    }

    private var wasConnected = false
    @kotlin.concurrent.Volatile private var lastMusic: CastRemoteStatus? = null
    @kotlin.concurrent.Volatile private var lastMusicAt: kotlin.time.TimeMark? = null
    private var stoppedJob: Job? = null
    private const val STOP_SETTLE_MS = 1_500L

    /** FR-R353-5 — the speaker's last live song and place ([castHandBack]) into this device's player, paused. */
    private fun handBack(last: CastRemoteStatus?, elapsedMs: Long) {
        val plan = castHandBack(last, elapsedMs, endedByApp = false) ?: return
        val handed = last ?: return
        scope.launch(Dispatchers.Main) { MusicEngine.loadPaused(state(handed).queue, plan.index, plan.positionMs, context) }
    }

    /**
     * FR-R353-5 — Play after the device stopped while still connected (the dashboard's Stop): the song this device took
     * back goes to the device again, from its place. False when there is nothing to send, so the caller plays here.
     */
    fun resumeOnDevice(): Boolean {
        val c = cast ?: return false
        if (_linked.value || !holdsDevice || c.sender.link.value != CastLinkState.CONNECTED) return false
        val st = MusicEngine.state.value
        if (st.book != null || st.queue.isEmpty() || st.playing) return false
        // R355 (FR-R355-4) — one hand-off per press: until the device's first live report, a second Play is the same
        // request (two left the Pixel 9 22 ms apart, 2026-10-02 14:38:31 — two codes, two tickets for one song).
        val now = kotlin.time.TimeSource.Monotonic.markNow()
        if (castResumeInFlight(resumeSentAt?.let { (now - it).inWholeMilliseconds })) return true
        resumeSentAt = now
        scope.launch(Dispatchers.Main) { handOff(c) }
        return true
    }
    /** R355 — when [resumeOnDevice] last sent the queue; cleared by a live report and by the link dropping. */
    @kotlin.concurrent.Volatile private var resumeSentAt: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
    /** Set by [playHere], [stop] and [moveAway]: they bring the music back themselves. */
    @kotlin.concurrent.Volatile private var endedByApp = false

    private fun handOff(c: CastController) {
        val st = MusicEngine.state.value
        if (st.book != null || st.queue.isEmpty()) return   // a book never casts; nothing loaded ⇒ a plain join
        val pos = MusicEngine.currentPositionMs()
        remember(st.queue); context = st.context
        // The engine stops and keeps its queue paused where it was (FR-R322-12's stop), so *Play on this phone*
        // and a failed hand-off both have somewhere to come back to.
        MusicEngine.stopForVideo()
        c.castMusic(st.queue.map { it.toCast() }, st.index, pos.takeIf { it > 0 }, st.repeat.wire(), st.shuffle)
    }

    private fun remember(tracks: List<MusicTrackItem>) { tracks.forEach { known[it.id] = it } }

    // R352 (FR-R352-4) — the device's last-played record follows what the speaker plays: saved when the song changes,
    // when it pauses, and at most every 15 s while it plays. Without it a relaunch after a cast (or after quitting while
    // casting) restored the song from before the hand-over.
    private var followedItem: String? = null
    private var followedPlaying = false
    private var followedAt: kotlin.time.TimeMark? = null

    private fun follow(st: CastRemoteStatus) {
        val due = st.itemId != followedItem || (followedPlaying && !st.playing) ||
            (followedAt?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE) >= FOLLOW_SAVE_MS
        if (!due) return
        val s = state(st)
        if (s.queue.isEmpty() || s.index < 0) return
        val uid = dev.jellystructure.ravilo.ui.screens.MultiTokenStore.getActive()?.userId ?: return
        followedItem = st.itemId; followedPlaying = st.playing; followedAt = kotlin.time.TimeSource.Monotonic.markNow()
        MusicQueueStore.save(MusicQueueSnapshot(uid, s.queue, s.index, st.positionMs, context))
    }
    private const val FOLLOW_SAVE_MS = 15_000L

    /** The receiver's snapshot as the screens' [MusicPlayerState]. */
    fun state(st: CastRemoteStatus): MusicPlayerState {
        val queue = st.queue.map { known[it.id] ?: it.toItem() }
        return MusicPlayerState(
            queue = queue, index = st.queueIndex.coerceIn(-1, queue.lastIndex), context = context,
            playing = st.playing, buffering = st.buffering, positionMs = st.positionMs, durationMs = st.durationMs,
            repeat = when (st.repeat) { "all" -> RepeatMode.ALL; "one" -> RepeatMode.ONE; else -> RepeatMode.OFF }, shuffle = st.shuffle,
            failed = st.failed, ended = st.ended,
        )
    }

    /** FR-R324-3 — *Play on this phone*: the receiver's queue and position come back to the engine; the speaker stops. */
    fun playHere() {
        val c = cast ?: return
        val st = _status.value ?: return
        val s = state(st)
        if (s.queue.isNotEmpty() && s.index >= 0) {
            MusicEngine.loadPaused(s.queue, s.index, st.positionMs, context)
            MusicEngine.play()
        }
        endedByApp = true
        c.stopCasting()
    }

    /**
     * FR-R324-5 — *Stop casting*: the room goes quiet; the phone keeps what was playing, paused. What was playing is
     * the speaker's queue where it stopped — an album started while casting never reached the engine, and the bar
     * used to fall back to the song the hand-off left behind (the Mac, 2026-09-30).
     */
    fun stop() {
        // R356 (FR-R356-14d) — with no live song here (a failed or ended report) nothing is taken back now, so the end
        // is left to the hand-back from the last live song when the session ends (FR-R353-5). It used to be marked as
        // the app's own end either way, and the phone kept the song from before the cast.
        endedByApp = takeBack()
        cast?.stopCasting()
    }

    /** The speaker's queue and position into this device's own player, paused; false when there was nothing live. */
    private fun takeBack(): Boolean {
        val st = _status.value
        if (!_linked.value || st == null) return false
        val s = state(st)
        if (s.queue.isEmpty() || s.index < 0) return false
        MusicEngine.loadPaused(s.queue, s.index, MusicPlayback.currentPositionMs(), context)
        return true
    }

    /**
     * Another device was chosen while this one plays music: what plays comes back, this device stops, and the hand-off
     * waits for the NEW connection — the one still open must not be handed the queue it just gave up.
     */
    fun moveAway() {
        if (!_linked.value) return
        takeBack()
        awaitNewLink = true
        endedByApp = true
        cast?.stopCasting()
    }
    @kotlin.concurrent.Volatile private var awaitNewLink = false

    /** FR-R324-1 (Q1) — an album started while casting replaces the speaker's queue. */
    fun playQueue(tracks: List<MusicTrackItem>, startIndex: Int, context: MusicContext, shuffle: Boolean) {
        val c = cast ?: return
        remember(tracks); this.context = context
        val order = if (shuffle) listOf(tracks[startIndex]) + tracks.filterIndexed { i, _ -> i != startIndex }.shuffled() else tracks
        val idx = if (shuffle) 0 else startIndex
        c.castMusic(order.map { it.toCast() }, idx, null, _status.value?.repeat ?: "off", shuffle)
    }

    fun command(type: String, index: Int? = null, to: Int? = null, track: MusicTrackItem? = null, on: Boolean? = null, mode: String? = null) {
        track?.let { known[it.id] = it }
        cast?.musicCommand(type, index = index, to = to, track = track?.toCast(), on = on, mode = mode)
    }

    fun setVolume(level: Double) { cast?.sender?.setVolume(level) }
    /** The speaker's or TV's own volume as it last reported it; null when it does not say (or nothing is linked). */
    val deviceVolume: kotlinx.coroutines.flow.StateFlow<Double?>? get() = cast?.sender?.volume
    fun setLyrics(on: Boolean) = command("lyrics", on = on)

    private fun MusicTrackItem.toCast() = CastTrackItem(
        id = id, title = title, artist = artists.joinToString(", ") { it.name }.ifBlank { null }, album = album,
        albumArtist = artists.firstOrNull()?.name, coverUrl = imageUrl, durationMs = durationMs, hasLyrics = hasLyrics,
    )
    private fun CastTrackItem.toItem() = MusicTrackItem(
        id = id, title = title, album = album, artists = artist?.let { listOf(MusicArtistRef(id = "", name = it)) } ?: emptyList(),
        durationMs = durationMs, hasLyrics = hasLyrics, imageUrl = coverUrl,
    )
    private fun RepeatMode.wire() = when (this) { RepeatMode.OFF -> "off"; RepeatMode.ALL -> "all"; RepeatMode.ONE -> "one" }
}

/** Every music screen's player: the receiver while [MusicCast.linked], the phone's own [MusicEngine] otherwise. */
object MusicPlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(MusicEngine.state.value)
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()
    val supported: Boolean get() = MusicEngine.supported

    init {
        scope.launch {
            combine(MusicEngine.state, MusicCast.linked, MusicCast.status) { local, linked, st -> if (linked && st != null) MusicCast.state(st) else local }
                .collect { _state.value = it }
        }
        // When the device last said where it is: between its reports the position moves on by itself (below).
        scope.launch {
            MusicCast.status.collect { st -> castSaid = st?.let { it.positionMs to kotlin.time.TimeSource.Monotonic.markNow() } }
        }
    }

    private val casting: Boolean get() = MusicCast.linked.value

    fun playQueue(tracks: List<MusicTrackItem>, startIndex: Int, context: MusicContext, shuffle: Boolean = false) {
        if (casting || MusicCast.holdsDevice) MusicCast.playQueue(tracks, startIndex, context, shuffle) else MusicEngine.playQueue(tracks, startIndex, context, shuffle)
    }
    fun loadPaused(tracks: List<MusicTrackItem>, index: Int, positionMs: Long, context: MusicContext?) = MusicEngine.loadPaused(tracks, index, positionMs, context)
    fun togglePlay() {
        if (casting) { if (_state.value.playing) pause() else play() }
        else if (!MusicEngine.state.value.playing && MusicCast.resumeOnDevice()) Unit
        else MusicEngine.togglePlay()
    }
    /** FR-R353-5 — still connected after the device stopped (the dashboard's Stop): Play starts the song there again. */
    fun play() { if (casting) MusicCast.controller?.sender?.play() else if (!MusicCast.resumeOnDevice()) MusicEngine.play() }
    fun pause() { if (casting) MusicCast.controller?.sender?.pause() else MusicEngine.pause() }
    fun next() { if (casting) MusicCast.command("next") else MusicEngine.next() }
    fun previous() { if (casting) MusicCast.command("prev") else MusicEngine.previous() }
    fun seekTo(positionMs: Long) { if (casting) MusicCast.controller?.sender?.seekTo(positionMs) else MusicEngine.seekTo(positionMs) }
    fun playAt(index: Int) { if (casting) MusicCast.command("play_at", index = index) else MusicEngine.playAt(index) }
    fun cycleRepeat() {
        if (!casting) { MusicEngine.cycleRepeat(); return }
        val next = when (_state.value.repeat) { RepeatMode.OFF -> "all"; RepeatMode.ALL -> "one"; RepeatMode.ONE -> "off" }
        MusicCast.command("repeat", mode = next)
    }
    fun toggleShuffle() { if (casting) MusicCast.command("shuffle", on = !_state.value.shuffle) else MusicEngine.toggleShuffle() }
    fun move(from: Int, to: Int) { if (casting) MusicCast.command("queue_move", index = from, to = to) else MusicEngine.move(from, to) }
    fun remove(index: Int) { if (casting) MusicCast.command("queue_remove", index = index) else MusicEngine.remove(index) }
    fun playNext(track: MusicTrackItem) { if (casting) MusicCast.command("queue_play_next", track = track) else MusicEngine.playNext(track) }
    fun addToQueue(track: MusicTrackItem) { if (casting) MusicCast.command("queue_add", track = track) else MusicEngine.addToQueue(track) }
    /** FR-R322-10 on the phone; while casting the bar only hides (FR-R324-7 — the screens call [MusicCast.barHidden]). */
    fun clear() { if (casting) MusicCast.barHidden.value = true else MusicEngine.clear() }
    fun stopForVideo() = MusicEngine.stopForVideo()
    fun retry() { if (casting) MusicCast.controller?.sender?.play() else MusicEngine.retry() }
    fun skip() { if (casting) MusicCast.command("next") else MusicEngine.skip() }
    @kotlin.concurrent.Volatile private var castSaid: Pair<Long, kotlin.time.TimeMark>? = null

    /**
     * Where the song is now. While casting the device's word arrives about once a second; a line of lyrics lit up to a
     * second late reads as out of step, so the position runs on from the last report while the device is playing
     * (never more than two seconds, and never past the song's end).
     */
    fun currentPositionMs(): Long {
        if (!casting) return MusicEngine.currentPositionMs()
        val st = MusicCast.status.value ?: return 0L
        val said = castSaid
        if (!st.playing || said == null || said.first != st.positionMs) return st.positionMs
        val ahead = said.second.elapsedNow().inWholeMilliseconds.coerceIn(0L, 2_000L)
        return (st.positionMs + ahead).let { if (st.durationMs > 0) it.coerceAtMost(st.durationMs) else it }
    }
    fun setEvenVolume(on: Boolean) = MusicEngine.setEvenVolume(on)

    // ── R323 — a book never casts (out of scope); every call reaches the engine ──
    fun playBook(detail: AudiobookDetail, part: Int, positionMs: Long, play: Boolean = true) = MusicEngine.playBook(detail, part, positionMs, play)
    fun skipBy(deltaMs: Long) = MusicEngine.skipBy(deltaMs)
    fun seekBook(bookMs: Long) = MusicEngine.seekBook(bookMs)
    fun setSpeed(speed: Double) = MusicEngine.setSpeed(speed)
    fun setSleep(timer: SleepTimer?) = MusicEngine.setSleep(timer)
    fun setSkipSilence(on: Boolean) = MusicEngine.setSkipSilence(on)
    fun bookPositionMs(): Long = MusicEngine.bookPositionMs()
}

/** R353 (FR-R353-5) — where the music comes back to after a cast ended from outside the app. */
data class CastHandBack(val index: Int, val positionMs: Long)

/**
 * R353 (FR-R353-5) — the speaker's last queue report → the song and place this device resumes from, or null when the app
 * ended the cast itself (it already brought the music back), nothing musical was playing, or the queue is unusable.
 * A playing song has moved on by [elapsedMs] since the report; a queue that played out is at 0:00 (FR-R322-5).
 */
fun castHandBack(last: CastRemoteStatus?, elapsedMs: Long, endedByApp: Boolean): CastHandBack? {
    if (endedByApp || last == null || !last.music || last.failed) return null
    val index = last.queueIndex.takeIf { it in last.queue.indices } ?: return null
    if (last.ended) return CastHandBack(index, 0L)
    val moved = if (last.playing) last.positionMs + elapsedMs.coerceAtLeast(0L) else last.positionMs
    val length = last.durationMs.takeIf { it > 0 } ?: last.queue[index].durationMs?.takeIf { it > 0 }
    // A song that reached its end (the queue played out) comes back at its start, as a queue end does here (FR-R322-5).
    if (length != null && moved >= length - SONG_END_SLACK_MS) return CastHandBack(index, 0L)
    return CastHandBack(index, moved.coerceAtLeast(0L))
}

/**
 * A connected device is playing (or holding paused) a music queue: the phone is its remote ([MusicCast.linked]). Only
 * such a report is kept as the place to hand back (FR-R353-5) — the idle status that follows the receiver's `ended` is
 * not (not loaded, not ended, position 0) and used to overwrite the song's place.
 */
fun castMusicLive(link: CastLinkState, st: CastRemoteStatus?): Boolean =
    link == CastLinkState.CONNECTED && st != null && st.music && st.loaded && !st.ended && !st.failed

/** How close to a song's end counts as having played it out: the last report before the end is up to ~1 s early. */
private const val SONG_END_SLACK_MS = 2_000L

/**
 * R353 (FR-R353-5, amended 2026-10-02) — the music stopped ON the device while the session stays connected: it was live
 * a moment ago and the device now reports no live song (the receiver's `ended` after the Jellyfin dashboard's Stop, the
 * TV remote's Stop key, the queue playing out). The app's own ends ([MusicCast.stop], *Play on this phone*, another
 * device chosen) bring the music back themselves; a failure is not a stop (R299 offers *Play on this phone* itself);
 * a film that took the device has no song to give back. A session that ends is the other path (the link drops).
 */
fun castStoppedOnDevice(wasLinked: Boolean, link: CastLinkState, st: CastRemoteStatus?, endedByApp: Boolean): Boolean {
    if (!wasLinked || link != CastLinkState.CONNECTED || endedByApp) return false
    if (st == null) return true
    if (st.failed) return false
    return !st.loaded || st.ended   // a film loaded in its place is neither: no song to give back
}

/** R355 (FR-R355-4) — how long a sent hand-off counts as the answer to another Play while the device has not reported. */
const val CAST_RESUME_WINDOW_MS = 5_000L

/**
 * R355 (FR-R355-4) — true when [MusicCast.resumeOnDevice] already sent the queue [sinceSentMs] ago and the device has
 * not reported it live yet: a second Play is the same request, not a second hand-off. Null (nothing sent, or a live
 * report / the link dropping cleared it) or older than [CAST_RESUME_WINDOW_MS] ⇒ send.
 */
fun castResumeInFlight(sinceSentMs: Long?): Boolean = sinceSentMs != null && sinceSentMs in 0 until CAST_RESUME_WINDOW_MS
