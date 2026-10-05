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
            combine(c.sender.link, c.sender.status, c.sender.deviceName) { l, s, n -> Triple(l, s, n) }.collect { (link, raw, name) ->
                val wasLinked = _linked.value
                // R370 — a relay launch (this app only starts the receiver for someone else) never links the music here.
                val nowLinked = castMusicLive(link, raw) && !relaying
                // R358 (FR-R358-1/2) — until the device's word is a place of its own (it plays, or it names another
                // song), what this device shows, follows and hands back is the hand-over's song and position. The
                // sender's own "loading" status and a receiver that never got a stream both say 0:00, and that 0:00
                // was taken back as the song's place (the Mac, 2026-10-02: a song cast at 0:30 came back at 0:00).
                if (nowLinked && raw != null && castReportIsAPlace(raw, handOver)) heard = true
                val st = castShownStatus(raw, handOver, heard)
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
                    handOver = null
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
                        handBack(castHandBackFrom(last, handOver), elapsed)
                    }
                }
                // A session that ends any way but ours (Google Home's *Stop cast*, the notification, the device
                // dropping the app, the network): the speaker's song and place come back to this device, paused.
                // R371 (found on the Pixel 9 Pro) — only a session that is GONE hands back: adding a room switches the
                // Cast session onto a group route, which reads RECONNECTING for ~200 ms, and that used to hand the music
                // back to the phone mid-group (with whatever queue the speaker had said).
                if (castSessionGone(wasConnected, link)) {
                    // R358 (FR-R358-1) — no live report at all (the device never got a stream): the hand-over's place.
                    val handed = castHandBackFrom(lastMusic, handOver)
                    val elapsed = lastMusicAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
                    val byApp = endedByApp
                    endedByApp = false; lastMusic = null; lastMusicAt = null
                    handOver = null; heard = false
                    if (!byApp) handBack(handed, elapsed)
                }
                wasConnected = castLinkLive(wasConnected, link)
            }
        }
    }

    private var wasConnected = false
    @kotlin.concurrent.Volatile private var lastMusic: CastRemoteStatus? = null
    @kotlin.concurrent.Volatile private var lastMusicAt: kotlin.time.TimeMark? = null
    private var stoppedJob: Job? = null
    private const val STOP_SETTLE_MS = 1_500L
    /** R358 — the song and place this device handed to the device last ([handOff], [playQueue]); null for a plain join. */
    @kotlin.concurrent.Volatile private var handOver: CastHandOver? = null
    /** R358 — since [handOver], the device has reported a place of its own ([castReportIsAPlace]). */
    @kotlin.concurrent.Volatile private var heard = false

    /**
     * R358 (FR-R358-2) — [st] as this device's screens show it: the hand-over's song and position until the device has
     * reported a place of its own. For a report that has not reached [status] yet (the Android media card reads the
     * sender directly), so that report itself counts.
     */
    fun shown(link: CastLinkState, st: CastRemoteStatus?): CastRemoteStatus? =
        castShownStatus(st, handOver, heard || (castMusicLive(link, st) && st != null && castReportIsAPlace(st, handOver)))

    /** FR-R353-5 — the speaker's last live song and place ([castHandBack]) into this device's player, paused. */
    private fun handBack(last: CastRemoteStatus?, elapsedMs: Long) {
        val plan = castHandBack(last, elapsedMs, endedByApp = false) ?: return
        val handed = last ?: return
        dev.jellystructure.ravilo.ui.seams.sessionLog("R353: hand-back — ${handed.queue.size} songs at #${plan.index} ${plan.positionMs} ms")
        val (queue, index) = handBackQueue(state(handed).queue, plan.index, MusicEngine.state.value.queue)
        if (queue.size != handed.queue.size) dev.jellystructure.ravilo.ui.seams.sessionLog("R353: the speaker's ${handed.queue.size} songs are part of this device's ${queue.size}: the whole queue comes back")
        scope.launch(Dispatchers.Main) { MusicEngine.loadPaused(queue, index, plan.positionMs, context) }
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

    /** R370 (owner decision 1) — this app is relaying a launch: it must not mirror, follow or hand back that music. */
    @kotlin.concurrent.Volatile var relaying = false

    /** R370 — the relay is done (the receiver joined the server): drop the link, leave it playing, take nothing back. */
    fun leaveRelay() {
        val c = cast ?: return
        endedByApp = true
        lastMusic = null; lastMusicAt = null; handOver = null
        c.sender.leave()
    }

    private fun handOff(c: CastController) {
        val st = MusicEngine.state.value
        if (st.book != null || st.queue.isEmpty()) { dev.jellystructure.ravilo.ui.seams.sessionLog("R324: connected with nothing to hand over (a plain join)"); return }
        val pos = handOffPositionMs(MusicEngine.currentPositionMs(), st.positionMs, st.playing)
        dev.jellystructure.ravilo.ui.seams.sessionLog("R324: hand-off of ${st.queue.size} songs at #${st.index} ${pos} ms (${if (st.playing) "playing" else "paused"}) to ${c.sender.deviceName.value}")
        remember(st.queue); context = st.context
        // The engine stops and keeps its queue paused where it was (FR-R322-12's stop), so *Play on this phone*
        // and a failed hand-off both have somewhere to come back to.
        MusicEngine.stopForVideo()
        val queue = st.queue.map { it.toCast() }
        // R358 (FR-R358-1) — what comes back if the device never reports a place of its own.
        handOver = queue.getOrNull(st.index)?.let { CastHandOver(queue, st.index, pos.coerceAtLeast(0L)) }; heard = false
        c.castMusic(queue, st.index, pos.takeIf { it > 0 }, st.repeat.wire(), st.shuffle)
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
        dev.jellystructure.ravilo.ui.seams.sessionLog("R324: play here — ${s.queue.size} songs at #${s.index} ${st.positionMs} ms from ${_device.value}")
        if (s.queue.isNotEmpty() && s.index >= 0) {
            val (queue, index) = handBackQueue(s.queue, s.index, MusicEngine.state.value.queue)
            MusicEngine.loadPaused(queue, index, st.positionMs, context)
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
        val queue = order.map { it.toCast() }
        // R358 — an album started while casting: until the device plays it, the album's first song at its start.
        handOver = queue.getOrNull(idx)?.let { CastHandOver(queue, idx, 0L) }; heard = false
        c.castMusic(queue, idx, null, _status.value?.repeat ?: "off", shuffle)
    }

    fun command(type: String, index: Int? = null, to: Int? = null, track: MusicTrackItem? = null, on: Boolean? = null, mode: String? = null) {
        track?.let { known[it.id] = it }
        cast?.musicCommand(type, index = index, to = to, track = track?.toCast(), on = on, mode = mode)
    }

    fun setVolume(level: Double) { cast?.sender?.setVolume(level) }
    /** The speaker's or TV's own volume as it last reported it; null when it does not say (or nothing is linked). */
    val deviceVolume: kotlinx.coroutines.flow.StateFlow<Double?>? get() = cast?.sender?.volume
    fun setLyrics(on: Boolean) = command("lyrics", on = on)

    internal fun MusicTrackItem.toCast() = CastTrackItem(
        id = id, title = title, artist = artists.joinToString(", ") { it.name }.ifBlank { null }, album = album,
        albumArtist = artists.firstOrNull()?.name, coverUrl = imageUrl, durationMs = durationMs, hasLyrics = hasLyrics,
    )
    internal fun CastTrackItem.toItem() = MusicTrackItem(
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
                .collect {
                    if (it.playing != _state.value.playing) { glyphBefore = _state.value.playing; glyphFlippedAt = kotlin.time.TimeSource.Monotonic.markNow() }
                    _state.value = it
                }
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
    /**
     * Found with the Pixel and the Mac pausing the same cast at once (2026-10-05): a press decided play-or-pause from the
     * state when the click was HANDLED, and the other device's pause had landed a few milliseconds earlier, so the Mac's
     * press resumed it. A button passes what it SHOWED: a press on a pause glyph pauses, on a play glyph plays.
     */
    fun togglePlay(shownPlaying: Boolean) {
        if (!casting) { togglePlay(); return }
        // Re-measured the same day: the Mac's click landed ~250 ms after the Pixel's pause had already turned its glyph
        // into ▶, so "what it showed" was play and the speaker resumed. Nobody reacts to a glyph that fast: a press within
        // [GLYPH_REACTION] of a flip this device did not cause is meant for the glyph that was there before it.
        val flipped = glyphFlippedAt
        val pressedOwn = ownPressAt
        val meant = if (flipped != null && flipped.elapsedNow() < GLYPH_REACTION && (pressedOwn == null || pressedOwn.elapsedNow() > flipped.elapsedNow())) glyphBefore else shownPlaying
        ownPressAt = kotlin.time.TimeSource.Monotonic.markNow()
        if (meant) pause() else play()
    }
    private val GLYPH_REACTION = kotlin.time.Duration.parse("700ms")
    @kotlin.concurrent.Volatile private var glyphBefore = false
    @kotlin.concurrent.Volatile private var glyphFlippedAt: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
    @kotlin.concurrent.Volatile private var ownPressAt: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null
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

/**
 * R358 — the song and place this device had when it handed the music to a device: the place to come back to when the
 * device never reports one of its own (it never got a stream, or the cast ended before its first report).
 */
data class CastHandOver(val queue: List<CastTrackItem>, val index: Int, val positionMs: Long) {
    val itemId: String get() = queue[index].id
}

/**
 * R358 (FR-R358-1) — the device's report is a place of its own: it says the song plays, or it names another song than
 * the one handed over (the speaker moved on). The sender's own "loading" status, a receiver still loading, and a
 * receiver that never got a stream all say position 0 of the handed-over song, playing nothing — that is not a place.
 * With nothing handed over (a plain join) every report is the device's own.
 */
fun castReportIsAPlace(st: CastRemoteStatus, handOver: CastHandOver?): Boolean =
    handOver == null || st.playing || st.queue.getOrNull(st.queueIndex)?.id != handOver.itemId

/**
 * R358 (FR-R358-2) — the report as this device shows (and keeps, and hands back) it: until [heard] (a report was a place,
 * [castReportIsAPlace]), the hand-over's song and position, not playing. Everything else the device said stands.
 */
fun castShownStatus(st: CastRemoteStatus?, handOver: CastHandOver?, heard: Boolean): CastRemoteStatus? {
    if (st == null || handOver == null || heard || !st.music) return st
    val same = st.queue.getOrNull(st.queueIndex)?.id == handOver.itemId
    val song = handOver.queue[handOver.index]
    return st.copy(
        itemId = handOver.itemId,
        queue = if (same) st.queue else handOver.queue,
        queueIndex = if (same) st.queueIndex else handOver.index,
        positionMs = handOver.positionMs,
        durationMs = st.durationMs.takeIf { it > 0 && same } ?: song.durationMs ?: 0L,
        playing = false,
    )
}

/**
 * R358 (FR-R358-1) — what a cast's end hands back: the device's last live report as shown ([castShownStatus] — the
 * hand-over's place until the device reported one), or, with no live report at all, the hand-over itself.
 */
fun castHandBackFrom(lastLive: CastRemoteStatus?, handOver: CastHandOver?): CastRemoteStatus? =
    lastLive ?: handOver?.let { h ->
        CastRemoteStatus(itemId = h.itemId, music = true, loaded = true, queue = h.queue, queueIndex = h.index,
            positionMs = h.positionMs, durationMs = h.queue[h.index].durationMs ?: 0L)
    }

/**
 * R370 (found on the Pixel 9 Pro) — where a hand-off starts the song: the player's live place, or — for a song restored
 * paused after a relaunch and never opened, whose player has no place yet — where the state says it is. The speaker used
 * to start such a song at 0:00.
 */
fun handOffPositionMs(live: Long, statePositionMs: Long, playing: Boolean): Long =
    if (live > 0L || playing) live.coerceAtLeast(0L) else statePositionMs.coerceAtLeast(0L)

/**
 * R371 — a Cast session is live while CONNECTED, and stays live through RECONNECTING when it was (a room added moves it
 * onto a group route for a moment); it is gone only at NONE.
 */
fun castLinkLive(wasLive: Boolean, link: CastLinkState): Boolean = link == CastLinkState.CONNECTED || (wasLive && link == CastLinkState.RECONNECTING)

/** The session that was live has ended: the music comes back to this device. */
fun castSessionGone(wasLive: Boolean, link: CastLinkState): Boolean = wasLive && link == CastLinkState.NONE

/**
 * R353 / R371 (bug 9, found on the Pixel 9 Pro) — the queue that comes back: the speaker's, unless it is a part of the
 * queue this device still holds (the hand-off keeps it, paused) — a window, or the one song a speaker was left with —
 * then this device's whole queue, at the speaker's song. A queue that came back as one song stayed one song through every
 * cast and relaunch after it.
 */
fun handBackQueue(received: List<MusicTrackItem>, receivedIndex: Int, local: List<MusicTrackItem>): Pair<List<MusicTrackItem>, Int> {
    val song = received.getOrNull(receivedIndex) ?: return received to receivedIndex
    if (received.size >= local.size) return received to receivedIndex
    val start = local.indices.firstOrNull { i -> local.getOrNull(i + received.size - 1) != null && received.indices.all { k -> local[i + k].id == received[k].id } }
        ?: return received to receivedIndex
    return if (local[start + receivedIndex].id == song.id) local to (start + receivedIndex) else received to receivedIndex
}

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
