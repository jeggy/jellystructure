package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.music.MusicCast
import dev.jellystructure.ravilo.ui.music.MusicDeviceStore
import dev.jellystructure.ravilo.ui.music.MusicEngine
import dev.jellystructure.ravilo.ui.music.RepeatMode
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.shared.tv.CastChannelMusic
import dev.jellystructure.shared.tv.CastChannelStep
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastQueueRevision
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults
import dev.jellystructure.shared.tv.castChannelStep
import dev.jellystructure.shared.tv.castQueueAttachAll
import dev.jellystructure.shared.tv.castQueueIfFits
import dev.jellystructure.shared.tv.castQueueReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * R380 (FR-R380-7, owner 2026-10-08) — the Android TV app's side of Ravilo's Cast channel. When Cast Connect hands a
 * cast to the TV app (R266), the phone keeps the remote it has against the Chromecast: it sends [CastCommand]s on
 * CAST_NAMESPACE and builds its screen from what comes back. What each command means is :shared's [castChannelStep]
 * (the web receiver asks the same function); this object carries the answer out on the TV's own players — the film
 * player while one is open ([CastChannelVideo], registered by the player), the music engine for a queue — and sends the
 * status the phone mirrors. ravilo-android wires it to the Cast SDK ([send], [onMessage], [senderConnected]).
 */

/** What the phone's remote reads off a film the TV plays (R245/R285's shape). Indexes are positions in these lists. */
data class CastVideoSnapshot(
    val itemId: String,
    val title: String,
    val kicker: String?,
    val audioTracks: List<CastTrack>,
    val subtitleTracks: List<CastTrack>,
    val selectedAudio: Int,
    /** -1 = off. */
    val selectedSub: Int,
    val subSize: String,
    val hasNext: Boolean,
    val transcoding: Boolean?,
)

/** The film player as the channel drives it; registered by the player while it is composed. */
interface CastChannelVideo {
    fun snapshot(): CastVideoSnapshot?
    fun selectAudio(index: Int)
    /** -1 = off. */
    fun selectSubtitle(index: Int)
    fun setSubSize(size: String)
    fun nextEpisode()
}

object TvCastChannel {
    enum class Mode { NONE, FILM, MUSIC }

    /** Ravilo's Cast channel ([dev.jellystructure.shared.tv.CAST_NAMESPACE]), for ravilo-android's receiver. */
    const val NAMESPACE: String = dev.jellystructure.shared.tv.CAST_NAMESPACE

    private val _mode = MutableStateFlow(Mode.NONE)
    /** What a cast is driving on this TV now. */
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    /** Broadcasts [text] to every connected sender on CAST_NAMESPACE (set by ravilo-android; null where none). */
    @kotlin.concurrent.Volatile var send: ((String) -> Unit)? = null

    /** FR-R380-3 / 286 FR-286-6 — synced lyrics on this TV's Now playing: off at first, remembered per TV. */
    private val _lyricsOn = MutableStateFlow(runCatching { MusicDeviceStore.get(LYRICS_KEY) }.getOrNull() == "1")
    val lyricsOn: StateFlow<Boolean> = _lyricsOn.asStateFlow()
    fun setLyrics(on: Boolean) {
        _lyricsOn.value = on
        runCatching { MusicDeviceStore.put(LYRICS_KEY, if (on) "1" else "0") }
        if (_mode.value == Mode.MUSIC) pushStatus()
    }

    private var video: CastChannelVideo? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null
    private val json = RaviloWireJsonWithDefaults

    // ── music: R356's revision and R359's long queue ──
    private val revision = CastQueueRevision()
    private var queueId: String? = null
    private var queueTotal: Int? = null
    private var queueStart = 0
    private val waitingParts = mutableListOf<CastCommand>()
    private val deferred = mutableListOf<String>()
    private val assembling: Boolean get() = queueTotal != null

    /** The film player is open; returns its detach (the channel then says the film ended). */
    fun attachVideo(v: CastChannelVideo): () -> Unit {
        video = v
        if (_mode.value == Mode.FILM) pushStatus()
        return { if (video === v) { video = null; if (_mode.value == Mode.FILM) endFilm() } }
    }

    /** R266 — a film or episode LOAD was accepted: the next player to open is the cast's. */
    fun startFilm() {
        stopWatch()
        _mode.value = Mode.FILM
        // A film's status follows the player: its tracks arrive a moment after it opens, a pick restreams.
        watch = scope.launch {
            var last: CastVideoSnapshot? = null
            while (isActive) {
                val now = video?.snapshot()
                if (now != null && now != last) { last = now; pushStatus() }
                delay(VIDEO_POLL_MS)
            }
        }
    }

    /** R380 (FR-R380-1) — a music LOAD was accepted and the engine holds its queue. */
    fun startMusic(queueId: String?, queueTotal: Int?, queueStart: Int) {
        stopWatch()
        this.queueId = queueId; this.queueTotal = queueTotal; this.queueStart = queueStart
        waitingParts.removeAll { it.queueId != queueId }
        deferred.clear()
        revision.askedForQueue()
        _mode.value = Mode.MUSIC
        if (assembling) attachParts()
        watch = scope.launch {
            MusicEngine.state
                .map { st -> MusicKey(st.queue.map { it.id }, st.index, st.repeat, st.shuffle, st.ended, st.failed, st.active) }
                .distinctUntilChanged()
                .collect { key ->
                    if (!key.active) return@collect
                    if (key.ended) { pushStatus(); endMusic(); return@collect }
                    pushStatus()
                }
        }
    }

    private data class MusicKey(val ids: List<String>, val index: Int, val repeat: RepeatMode, val shuffle: Boolean, val ended: Boolean, val failed: Boolean, val active: Boolean)

    /** A sender connected (the phone's remote rejoined): the next status carries everything. */
    fun senderConnected() {
        revision.askedForQueue()
        if (_mode.value != Mode.NONE) pushStatus()
    }

    /** The cast's film closed (Back, a 180 teardown, the end): the phone's remote hears it end, not silence. */
    fun endFilm() {
        if (_mode.value != Mode.FILM) return
        stopWatch()
        _mode.value = Mode.NONE
        sendMessage(CastReceiverMessage(type = "ended", hasNext = false))
    }

    /** FR-R380-8 — the queue played out (or the music was stopped): `ended` with the queue, then nothing drives. */
    fun endMusic() {
        if (_mode.value != Mode.MUSIC) return
        stopWatch()
        val st = MusicEngine.state.value
        val cur = st.current
        sendMessage(castQueueIfFits(CastReceiverMessage(
            type = "ended", itemId = cur?.id, title = cur?.title, kicker = cur?.artists?.joinToString(", ") { it.name }?.ifBlank { null },
            hasNext = false, queue = st.queue.toCast().takeIf { !assembling }, queueIndex = queueStart + st.index,
            repeat = st.repeat.wire(), shuffle = st.shuffle, lyricsOn = _lyricsOn.value, headless = false,
        ), json))
        _mode.value = Mode.NONE
        queueId = null; queueTotal = null; queueStart = 0; waitingParts.clear(); deferred.clear()
    }

    /** The receiver stopped (the TV app left the screen, owner: Home ends a music cast). */
    fun receiverStopped() {
        when (_mode.value) {
            Mode.MUSIC -> { endMusic(); runCatching { MusicEngine.clear() } }
            Mode.FILM -> endFilm()
            Mode.NONE -> Unit
        }
    }

    private fun stopWatch() { watch?.cancel(); watch = null }

    // ── phone → TV ──

    /** One message from a sender on CAST_NAMESPACE (main thread). */
    fun onMessage(text: String) {
        val cmd = runCatching { json.decodeFromString(CastCommand.serializer(), text) }.getOrNull() ?: return
        if (cmd.type == "queue_part") { onQueuePart(cmd); return }
        val music = _mode.value == Mode.MUSIC
        // R359 (FR-R359-4) — an edit's places are the whole queue's: while it is still arriving, the edit waits for it.
        if (music && assembling && cmd.type in DEFERRED_WHILE_ARRIVING) { if (deferred.size < MAX_WAITING) deferred += text; return }
        val st = MusicEngine.state.value
        val musicNow = if (music) CastChannelMusic(st.queue.toCast(), st.index, st.current?.id, queueStart) else null
        when (val step = castChannelStep(cmd, musicNow)) {
            CastChannelStep.Ignore -> Unit
            is CastChannelStep.QueuePart -> onQueuePart(step.command)
            CastChannelStep.Stale -> pushStatus()
            is CastChannelStep.Status -> { if (step.full) revision.askedForQueue(); pushStatus() }
            CastChannelStep.MusicNext -> MusicEngine.next()
            CastChannelStep.MusicPrevious -> MusicEngine.previous()
            is CastChannelStep.PlayAt -> MusicEngine.playAt(step.index - queueStart)
            is CastChannelStep.QueueEdited -> MusicEngine.replaceQueue(step.tracks.toItems(), step.currentIndex)
            is CastChannelStep.Repeat -> setRepeat(step.mode)
            is CastChannelStep.Shuffle -> if (MusicEngine.state.value.shuffle != step.on) MusicEngine.toggleShuffle()
            is CastChannelStep.Lyrics -> setLyrics(step.on)
            is CastChannelStep.SubSize -> video?.setSubSize(step.size)
            CastChannelStep.EpisodeNext, CastChannelStep.NextUpPlay -> video?.nextEpisode()
            CastChannelStep.NextUpCancel -> Unit   // the TV's own next-up card is dismissed with its remote
            is CastChannelStep.Audio -> video?.selectAudio(step.index)
            is CastChannelStep.Subtitle -> video?.selectSubtitle(step.index)
        }
    }

    /**
     * A sender that switched tracks with Cast's own track selection instead of the channel (an older phone, or one that
     * saw track ids in the media info): the ids are the ones this TV's status gave out (100 + position for subtitles,
     * 200 + position for audio). No text id = subtitles off.
     */
    fun onStandardTrackSelect(text: Boolean, trackIds: List<Long>) {
        val v = video ?: return
        if (text) {
            val id = trackIds.firstOrNull { it in 100L until 200L }
            v.selectSubtitle(if (id == null) -1 else (id - 100L).toInt())
        } else trackIds.firstOrNull { it >= 200L }?.let { v.selectAudio((it - 200L).toInt()) }
    }

    private fun setRepeat(mode: String) {
        val want = when (mode) { "all" -> RepeatMode.ALL; "one" -> RepeatMode.ONE; else -> RepeatMode.OFF }
        repeat(3) { if (MusicEngine.state.value.repeat != want) MusicEngine.cycleRepeat() }
    }

    /** R359 — a part of the queue: joined to the engine's queue around the playing song; once whole, the edits that waited run. */
    private fun onQueuePart(cmd: CastCommand) {
        if (cmd.queueId == null || cmd.offset == null || cmd.tracks.isNullOrEmpty()) return
        if (cmd.queueId == queueId && !assembling) return
        waitingParts.removeAll { it.queueId != cmd.queueId && it.queueId != queueId }
        if (waitingParts.size < MAX_WAITING) waitingParts += cmd
        if (_mode.value == Mode.MUSIC && cmd.queueId == queueId) attachParts()
    }

    private fun attachParts() {
        if (!assembling) return
        val st = MusicEngine.state.value
        val run = st.queue.toCast()
        val (joined, start) = castQueueAttachAll(run, queueStart, queueId, waitingParts)
        if (start == queueStart && joined.size == run.size) return
        val index = st.index + (queueStart - start)
        queueStart = start
        val whole = start == 0 && joined.size >= (queueTotal ?: 0)
        if (whole) queueTotal = null
        MusicEngine.replaceQueue(joined.toItems(), index)
        if (whole) {
            val held = deferred.toList(); deferred.clear()
            held.forEach { onMessage(it) }
        }
        pushStatus()
    }

    // ── TV → phone ──

    /** The status the phone's remote mirrors (R245, 286, R356, R359). */
    fun pushStatus() {
        when (_mode.value) {
            Mode.NONE -> Unit
            Mode.FILM -> {
                val v = video?.snapshot() ?: return
                sendMessage(CastReceiverMessage(
                    type = "status", itemId = v.itemId, title = v.title, kicker = v.kicker, hasNext = v.hasNext,
                    audioTracks = v.audioTracks, subtitleTracks = v.subtitleTracks, selectedAudio = v.selectedAudio,
                    selectedSub = v.selectedSub, subSize = v.subSize, transcoding = v.transcoding,
                ))
            }
            Mode.MUSIC -> {
                val st = MusicEngine.state.value
                val cur = st.current ?: return
                val queue = st.queue.toCast()
                val arriving = assembling
                val full = !arriving && revision.next(queue)
                val msg = CastReceiverMessage(
                    type = "status", itemId = cur.id, title = cur.title,
                    kicker = cur.artists.joinToString(", ") { it.name }.ifBlank { null },
                    artUrl = cur.imageUrl?.let { absoluteArt(it) },
                    hasNext = st.hasNext || arriving,
                    queue = queue.takeIf { full }, queueIndex = queueStart + st.index,
                    repeat = st.repeat.wire(), shuffle = st.shuffle, lyricsOn = _lyricsOn.value, headless = false,
                    queueRev = revision.rev.takeIf { !arriving }, queueSize = queueTotal ?: queue.size,
                )
                castQueueReply(msg, json).forEach { sendMessage(it) }
            }
        }
    }

    private fun sendMessage(msg: CastReceiverMessage) {
        val out = send ?: return
        runCatching { out(json.encodeToString(CastReceiverMessage.serializer(), msg)) }
    }

    private fun absoluteArt(url: String): String =
        if (url.startsWith("http")) url else raviloBaseUrl().trimEnd('/') + "/" + url.trimStart('/')

    private fun List<dev.jellystructure.shared.tv.MusicTrackItem>.toCast(): List<CastTrackItem> = with(MusicCast) { map { it.toCast() } }
    private fun List<CastTrackItem>.toItems(): List<dev.jellystructure.shared.tv.MusicTrackItem> = with(MusicCast) { map { it.toItem() } }
    private fun RepeatMode.wire() = when (this) { RepeatMode.OFF -> "off"; RepeatMode.ALL -> "all"; RepeatMode.ONE -> "one" }

    private const val LYRICS_KEY = "tv_lyrics"
    private const val VIDEO_POLL_MS = 1_000L
    private const val MAX_WAITING = 400
    /** R359 (FR-R359-4) — what waits while a queue is still arriving (the web receiver's list). */
    private val DEFERRED_WHILE_ARRIVING = setOf("play_at", "queue_move", "queue_remove", "queue_add", "queue_play_next", "shuffle")
}
