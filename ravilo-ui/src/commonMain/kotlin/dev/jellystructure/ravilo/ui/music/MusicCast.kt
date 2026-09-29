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
                val nowLinked = link == CastLinkState.CONNECTED && st != null && st.music && st.loaded && !st.ended && !st.failed
                _status.value = st
                _device.value = name
                _linked.value = nowLinked
                if (nowLinked && st?.itemId != lastItemId) { lastItemId = st?.itemId; barHidden.value = false }
                // FR-R324-3 — the session just connected from the music-mode sheet: hand the phone's queue over, or
                // join with nothing playing here (FR-R324-2's second remote).
                if (link == CastLinkState.CONNECTED && c.pendingMusicHandoff) {
                    c.pendingMusicHandoff = false
                    handOff(c)
                }
                if (wasLinked && !nowLinked) barHidden.value = false
            }
        }
    }

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
        c.stopCasting()
    }

    /** FR-R324-5 — *Stop casting*: the room goes quiet; the phone keeps what it had, paused. */
    fun stop() { cast?.stopCasting() }

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
    }

    private val casting: Boolean get() = MusicCast.linked.value

    fun playQueue(tracks: List<MusicTrackItem>, startIndex: Int, context: MusicContext, shuffle: Boolean = false) {
        if (casting) MusicCast.playQueue(tracks, startIndex, context, shuffle) else MusicEngine.playQueue(tracks, startIndex, context, shuffle)
    }
    fun loadPaused(tracks: List<MusicTrackItem>, index: Int, positionMs: Long, context: MusicContext?) = MusicEngine.loadPaused(tracks, index, positionMs, context)
    fun togglePlay() { if (casting) { if (_state.value.playing) pause() else play() } else MusicEngine.togglePlay() }
    fun play() { if (casting) MusicCast.controller?.sender?.play() else MusicEngine.play() }
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
    fun currentPositionMs(): Long = if (casting) (MusicCast.status.value?.positionMs ?: 0L) else MusicEngine.currentPositionMs()
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
