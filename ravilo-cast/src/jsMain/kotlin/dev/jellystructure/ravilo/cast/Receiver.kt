package dev.jellystructure.ravilo.cast

import dev.jellystructure.ravilo.receiver.ReceiverStrings
import dev.jellystructure.ravilo.receiver.audioTracksOf
import dev.jellystructure.ravilo.receiver.hms
import dev.jellystructure.ravilo.receiver.nowMs
import dev.jellystructure.ravilo.receiver.subtitleTracksOf
import dev.jellystructure.shared.tv.CAST_LOG_NAMESPACE
import dev.jellystructure.shared.tv.CAST_NAMESPACE
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastDecodeProbe
import dev.jellystructure.shared.tv.CastEpisode
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.CastNext
import dev.jellystructure.shared.tv.castNextIndex
import dev.jellystructure.shared.tv.castPreviousWaits
import dev.jellystructure.shared.tv.castQueueAttachAll
import dev.jellystructure.shared.tv.castQueueIfFits
import dev.jellystructure.shared.tv.castQueueReply
import dev.jellystructure.shared.tv.TrackLyrics
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.ReceiverSubPick
import dev.jellystructure.shared.tv.receiverSelectedAudio
import dev.jellystructure.shared.tv.receiverSelectedSub
import dev.jellystructure.shared.tv.receiverSubPick
import dev.jellystructure.shared.tv.receiverSubtitles
import dev.jellystructure.shared.tv.nextUpStartMs
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.SkipMode
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import dev.jellystructure.shared.tv.RECEIVER_PAUSED_BEAT_MS
import dev.jellystructure.shared.tv.ServerNotice
import dev.jellystructure.shared.tv.receiverRemoteDeclaration
import dev.jellystructure.shared.tv.serverNoticeOf
import dev.jellystructure.shared.tv.ReceiverRemoteAction
import dev.jellystructure.shared.tv.ReceiverRemoteState
import dev.jellystructure.shared.tv.RemoteCommand
import dev.jellystructure.shared.tv.RemoteVolume
import dev.jellystructure.shared.tv.VolumeReport
import dev.jellystructure.shared.tv.volumeReportOf
import dev.jellystructure.shared.tv.receiverRemoteAction
import dev.jellystructure.shared.tv.remoteCommandOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.Promise

/**
 * R245 (FR-R245-13..17) — the Ravilo Chromecast receiver.
 *
 * It is a Ravilo DEVICE, not a screen: it enrols with the phone's hand-off code (218 FR-218-9), holds
 * its own device token (never a Jellyfin token — jellystructure makes every Jellyfin call for it),
 * negotiates its own StreamTicket through the same `/api/tv/playback/start` a TV uses, reports progress
 * and stop like any device, advances to the next episode and honours Skip Intro by itself
 * (FR-R245-14). The phone mirrors; it does not drive.
 *
 * CAF is reached through `dynamic` — the SDK is a global the page loads before this bundle.
 */
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

private const val TOKEN_KEY = "ravilo.cast.token"
private const val RECEIVER_ID_KEY = "ravilo.cast.receiverId"
private const val PROGRESS_EVERY_MS = 10_000L
/** 286 (FR-286-6) — lyrics on THIS display, remembered beside the receiver id. */
private const val LYRICS_KEY = "ravilo.cast.lyrics"
/** FR-286-5 — how long the transport row stays after a key. */
private const val TRANSPORT_MS = 5_000L
/** R356 (FR-R356-14b) — after another controller's STOP, how long before the app closes (CAF tells the senders first). */
private const val SENDER_STOP_CLOSE_MS = 400L
/** R357 (FR-R357-4) — how long the device's volume must hold still before it is reported (a dragged slider is one report). */
private const val VOLUME_SETTLE_MS = 300L
/** R359 (FR-R359-4) — how long the rest of a long queue may take before the songs held are taken as the queue. */
private const val ASSEMBLY_GIVE_UP_MS = 15_000L
/** R359 — at most this many parts (and held edits) are kept; 5 000 songs are ~30 parts. */
private const val MAX_WAITING_PARTS = 400
/** R359 (FR-R359-4) — what waits while a queue is still arriving: everything that names a place in it, or reorders it. */
private val DEFERRED_WHILE_ARRIVING = setOf("play_at", "queue_move", "queue_remove", "queue_add", "queue_play_next", "shuffle")

private class Receiver {
    private val cast: dynamic = js("window.cast")
    private val context: dynamic = cast.framework.CastReceiverContext.getInstance()
    private val playerManager: dynamic = context.getPlayerManager()

    private var serverUrl: String = ""
    private var token: String? = localStorage.getItem(TOKEN_KEY)
    private var receiverId: String? = localStorage.getItem(RECEIVER_ID_KEY)
    private var api: TvApiClient? = null
    private var config: RaviloConfig? = null

    private var current: CastLoadData? = null
    private var ticket: StreamTicket? = null
    /** The server holds a playback session for [current]: true from a negotiated ticket until its stop is sent. */
    private var sessionOpen = false
    /** A LOAD is being prepared (enrolment, the ticket): the player has not been handed the new item yet. */
    private var loading = 0
    // R285 (FR-R285-4) — a track change that needs a new stream: set by onCommand, consumed by the
    // very next LOAD the receiver issues to itself. (burn-in index or -1, audio index, then-show text position)
    private var pendingRestream: Triple<Int, Int?, Int>? = null
    private var lastProgressAt = 0L
    private var positionMs = 0L
    private var durationMs = 0L
    private var paused = false
    private var overlayJob: Job? = null
    private var nextUpJob: Job? = null
    private var busySinceMs: Long? = null
    private var introSkipped = false
    private var subSize = "M"

    // ── R354 — the Jellyfin dashboard (through 299's bridge) reaches the receiver on its own events socket ──
    /** Which server and token the events socket should be open for; null = closed (nothing loaded, or not enrolled). */
    private val eventsKey = MutableStateFlow<String?>(null)
    /** The device's own volume as a remote command moves it; synced from CAF before each command. */
    private val remoteVolume = RemoteVolume()
    private var pausedBeat: Job? = null
    /** R357 (FR-R357-4) — the volume the last at-once report said, so CAF's own event after a command is not a second one. */
    private var volumeSent: VolumeReport? = null
    private var volumeJob: Job? = null

    // ── 286 — music: the receiver owns the queue (FR-286-4) ──
    /** Null until the first LOAD asked; true on an audio-only device (FR-286-3), where no screen is ever built. */
    private var headless: Boolean? = null
    private val music: Boolean get() = current?.tracks?.isNotEmpty() == true
    private var lyricsOn = localStorage.getItem(LYRICS_KEY) == "1"
    private var lyrics: TrackLyrics? = null
    private var lyricsJob: Job? = null
    private var transportJob: Job? = null
    /** The order the phone sent, kept so Shuffle → off restores it. */
    private var unshuffled: List<CastTrackItem> = emptyList()

    // ── R356 (FR-R356-8) — the queue goes to the senders only when it changed ──
    /** Raised whenever the queue's songs or their order change (seen by [queueFingerprint] at each status). */
    private var queueRev = 0
    private var queueFingerprint: Int? = null
    /** The revision last sent in full; -1 = never. */
    private var sentRev = -1
    /** A sender connected, or asked (`status` / `get_queue`): the next status carries the full queue. */
    private var fullDue = true
    /** FR-R356-11 — the size of the first status without the queue after a full one is noted once. */
    private var slimNoted = false

    // ── R359 (FR-R359-3/4) — a long queue arrives in parts ──
    /** `queue_part`s not joined yet: one that came before its LOAD, or one that came before the part next to it. */
    private val waitingParts = mutableListOf<CastCommand>()
    /** Queue edits (and shuffle, play-at) sent while the queue was still arriving: their places are the whole queue's. */
    private val deferred = mutableListOf<String>()
    /** +1: a next waits for the song after the run; -1: a previous waits for the one before it; 0: nothing waits. */
    private var waitingStep = 0
    private var waitingByViewer = true
    private var assemblyJob: Job? = null

    // ── screens ──
    /** FR-286-3 — on a headless device the body is empty, so every element lookup lands on this detached one. */
    private val nowhere: HTMLElement = document.createElement("div") as HTMLElement
    private fun el(id: String) = document.getElementById(id) as? HTMLElement ?: nowhere
    private fun show(vararg on: String) {
        for (id in listOf("idle", "loading", "buffering", "noserver", "busy", "nowplaying")) el(id).classList.toggle("on", id in on)
        if ("nowplaying" !in on) { el("np-ly").classList.remove("on"); el("np-tr").classList.remove("on") }
    }
    private fun idle() {
        // R354 (FR-R354-7) — back at the idle view: the events socket closes (`1000 idle`), the paused heartbeat stops.
        pausedBeat?.cancel(); pausedBeat = null
        syncEvents()
        el("idle-sentence").textContent = ReceiverStrings.t("cast.ready")
        el("nextup").classList.remove("on"); el("overlay").classList.remove("on")
        show("idle")
    }

    /** FR-286-3 — `display_supported` from CAF, asked once the context has started; false ⇒ the DOM is emptied. */
    private fun isHeadless(): Boolean {
        headless?.let { return it }
        val h = runCatching { (context.getDeviceCapabilities()?.display_supported as? Boolean) == false }.getOrDefault(false)
        headless = h
        // Our own screens go; the framework's player element stays. Emptying the whole body took `<cast-media-player>`
        // with it, and on a speaker (2026-09-30, read through the log channel) every load after the first then failed
        // with 905, and every converted song with Shaka's "Cannot read property 'insertRule' of null" (289 FR-289-5).
        if (h) runCatching {
            val own = document.querySelectorAll("body > section, body > div")
            for (i in 0 until own.length) own.item(i)?.let { it.parentNode?.removeChild(it) }
        }
        return h
    }

    fun start() {
        el("idle-sentence").textContent = ReceiverStrings.t("cast.ready")
        val messages = cast.framework.messages
        // FR-R245-13 — the LOAD interceptor: enrol if needed, negotiate our own ticket, then hand CAF the
        // real media. The phone never hands us a media URL.
        playerManager.setMessageInterceptor(messages.MessageType.LOAD) { request: dynamic ->
            GlobalScope.promise { loading++; try { intercept(request) } finally { loading-- } }
        }
        val et = cast.framework.events.EventType
        playerManager.addEventListener(et.TIME_UPDATE) { ev: dynamic -> onTime(((ev.currentMediaTime as Double?) ?: 0.0) * 1000) }
        // R245 amendment (2026-09-18) — there is no `PLAYER_STATE_CHANGED` in CAF's EventType (checked
        // against the live framework): the constant was `undefined`, `addEventListener(undefined)`
        // throws, and the whole receiver died in start() before `context.start()` — so a TV that had
        // accepted the launch showed nothing. The three state events CAF does define:
        for (type in listOf(et.PLAYING, et.PAUSE, et.BUFFERING)) {
            playerManager.addEventListener(type) { _: dynamic -> onPlayerState() }
        }
        playerManager.addEventListener(et.MEDIA_FINISHED) { ev: dynamic -> note("finished ${ev.endedReason} loading=$loading state=${playerManager.getPlayerState()}"); onFinished(ev.endedReason as String?) }
        // R297 (FR-R297-3) — record why a stream failed, so the next failure is read through DevTools, not guessed.
        playerManager.addEventListener(et.ERROR) { ev: dynamic ->
            console.error("ravilo-cast: media error", ev.detailedErrorCode, ev.reason, ev.error)
            note("error code=${ev.detailedErrorCode} reason=${ev.reason} loading=$loading state=${playerManager.getPlayerState()} ${runCatching { JSON.stringify(ev.error) as String? }.getOrNull()?.take(300) ?: ""}")
            // R299 (FR-R299-1) — a load that never reached a media session gets no MEDIA_FINISHED; CAF
            // reports it here with the player still IDLE. Say so once; a mid-play error is followed by
            // MEDIA_FINISHED(ERROR), which onFinished turns into the same message.
            // 289 — not while a LOAD is being prepared: whatever failed then is the item before, and `current` is the new one.
            if (loading == 0 && (playerManager.getPlayerState() as String) == "IDLE" && current != null) { stopSession(); failed() }
        }
        playerManager.addEventListener(et.SEEKED) { _: dynamic -> flashOverlay() }
        context.addCustomMessageListener(CAST_NAMESPACE) { ev: dynamic -> onCommand(JSON.stringify(ev.data) as String) }
        // 289 — registered so that the receiver may speak on it; nothing is ever said to it.
        runCatching { context.addCustomMessageListener(CAST_LOG_NAMESPACE) { _: dynamic -> } }
        context.addEventListener(cast.framework.system.EventType.SHUTDOWN) { _: dynamic -> stopSession() }
        // R357 (FR-R357-4) — the device's volume moved (a sender's slider, the TV's own remote, a dashboard command):
        // one progress report at once. Registered on its own, like every event this framework build may not have.
        runCatching {
            val changed: dynamic = cast.framework.system.EventType.SYSTEM_VOLUME_CHANGED
            if (changed != null) context.addEventListener(changed) { ev: dynamic ->
                val d: dynamic = ev?.data
                reportVolumeNow(volumeReportOf(d?.level as? Double, d?.muted as? Boolean) ?: systemVolume())
            }
        }.onFailure { console.warn("ravilo-cast: no SYSTEM_VOLUME_CHANGED on this framework (${it.message})") }
        // R356 (FR-R356-8/10) — a sender that connects (a phone that resumes, the Mac, an installed app older than R356)
        // gets the whole queue in the next status; the ones without it are only for senders that hold it already.
        runCatching {
            context.addEventListener(cast.framework.system.EventType.SENDER_CONNECTED) { _: dynamic ->
                fullDue = true
                if (current != null) sendStatus()
            }
        }.onFailure { console.warn("ravilo-cast: no SENDER_CONNECTED on this framework (${it.message})") }
        // 286 (dev review 4) — Google's own next/previous (the Home app, the Assistant, a display's remote) arrive
        // as queue messages; on a music LOAD they land on OUR list, never on CAF's (which holds one item).
        //
        // 2026-09-30 — each one is registered on its own and may fail: the live framework (CAF 3.0.0156) refuses an
        // interceptor for QUEUE_NEXT ("Unknown message type - QUEUE_NEXT") by throwing, which ended start() before
        // `context.start()`. The receiver's page showed its idle screen, the device never saw the app reach RUNNING,
        // aborted it after 60 s, and EVERY cast — films included — failed from the day 286 was deployed. The same
        // shape as R245's amendment above (`PLAYER_STATE_CHANGED`): a constant or a call this framework build does not
        // take must cost that one feature, never the receiver. QUEUE_UPDATE carries next/previous (`jump`) from every
        // sender SDK and is accepted.
        for (type in listOf(messages.MessageType.QUEUE_UPDATE, messages.MessageType.QUEUE_NEXT, messages.MessageType.QUEUE_PREV)) {
            if (type == null) continue
            runCatching { playerManager.setMessageInterceptor(type) { request: dynamic ->
                if (!music) request
                else {
                    val jump = (request.jump as? Int) ?: (request.jump as? Double)?.toInt()
                    when {
                        request.type == "QUEUE_PREV" || (request.type == "QUEUE_UPDATE" && jump != null && jump < 0) -> musicPrevious()
                        request.type == "QUEUE_NEXT" || (request.type == "QUEUE_UPDATE" && jump != null && jump > 0) -> musicNext(byViewer = true)
                        request.type == "QUEUE_UPDATE" && request.repeatMode != null -> setRepeat(when (request.repeatMode as String) { "REPEAT_ALL", "REPEAT_ALL_AND_SHUFFLE" -> "all"; "REPEAT_SINGLE" -> "one"; else -> "off" })
                        request.type == "QUEUE_UPDATE" && request.shuffle != null -> setShuffle(request.shuffle as Boolean)
                        request.type == "QUEUE_UPDATE" && request.currentItemId != null -> Unit
                    }
                    null
                }
            } }.onFailure { console.warn("ravilo-cast: no interceptor for $type on this framework (${it.message})") }
        }
        // R356 (FR-R356-14b, 2026-10-02) — a STOP from a controller other than Ravilo's own (Play services' Cast card's
        // *Stop cast*, Google Home, the Assistant, a display's own Stop) ends the cast. It used to stop the player only:
        // MEDIA_FINISHED(STOPPED) read as a failure, the app kept running and the phone kept its session, so Play on the
        // phone sent the song back to the speaker. Ravilo's own *Stop casting* stops the app itself and never sends this.
        runCatching {
            val stop: dynamic = messages.MessageType.STOP
            if (stop != null) playerManager.setMessageInterceptor(stop) { request: dynamic -> onSenderStop(); request }
        }.onFailure { console.warn("ravilo-cast: no interceptor for STOP on this framework (${it.message})") }
        // FR-286-5 — the display's own remote: media keys come as commands (the interceptors above), the D-pad
        // as key events, and a tap on the hub as a click. None of it exists on a speaker (no DOM, no remote).
        document.addEventListener("keydown", { ev -> onKey(ev as KeyboardEvent) })
        document.addEventListener("keyup", { ev -> onKeyUp(ev as KeyboardEvent) })
        document.addEventListener("click", { _ -> if (music) toggle() })
        val opts: dynamic = js("({})")
        opts.disableIdleTimeout = false
        context.start(opts)
        eventLoop()
    }

    // ── R354 (FR-R354-7): the Jellyfin dashboard's commands ──

    /** Open while something is loaded and the receiver holds a token; recomputed on every status and on idle. */
    private fun syncEvents() {
        val t = token
        eventsKey.value = if (current != null && t != null && api != null) "$serverUrl|$t" else null
    }

    /**
     * The receiver's own `/api/tv/events` (299 FR-299-4): while it is open the server bridges a Jellyfin session under
     * the receiver's own identity, and the dashboard's commands arrive here. Closed (`1000 idle`) at the idle view or
     * when the token changes; reconnects 2 s → 30 s in between, like ravilo-screen's.
     */
    private fun eventLoop() = GlobalScope.launch {
        var backoff = 2_000L
        while (true) {
            val key = eventsKey.first { it != null }
            val a = api
            if (a == null) { delay(1_000L); continue }
            var opened = false
            val how = runCatching {
                a.connectEvents(
                    closeWhen = { eventsKey.first { it != key }; "idle" },
                    onOpen = { opened = true; backoff = 2_000L },
                    onEvent = {},
                    onPlaystateCommand = { env -> remoteCommandOf(env)?.let { onRemote(it) } },
                    onPlayerCommand = { env -> remoteCommandOf(env)?.let { onRemote(it) } },
                    // R354 (FR-R354-9e) — a screen takes the dashboard's messages; a speaker declares none and ignores one.
                    onServerMessage = { env -> if (!isHeadless()) serverNoticeOf(env)?.let { showNotice(it) } },
                    remote = receiverRemoteDeclaration(isHeadless()),
                )
            }.getOrElse { "err:" + (it::class.simpleName ?: "Throwable") }
            note("events closed: $how")
            if (eventsKey.value != key) continue   // asked to close, or the token changed: no wait
            delay(backoff)
            if (!opened) backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private var noticeJob: Job? = null

    /** R354 (FR-R354-9e) — a dashboard message over whatever the screen shows, for its time; a newer one replaces it. */
    private fun showNotice(n: ServerNotice) {
        el("msg-h").textContent = n.header ?: ""
        el("msg-t").textContent = n.text
        el("msg").classList.add("on")
        note("message (${n.text.length} chars) for ${n.durationMs} ms")
        noticeJob?.cancel()
        noticeJob = GlobalScope.launch { delay(n.durationMs); el("msg").classList.remove("on") }
    }

    /** One dashboard command, carried out as the receiver's own controls would, then a fresh status for the senders. */
    private fun onRemote(cmd: RemoteCommand) {
        val d = current
        val st = ReceiverRemoteState(
            loaded = d != null, music = music, playing = (playerManager.getPlayerState() as? String) == "PLAYING",
            positionMs = positionMs, durationMs = durationMs,
            hasNextEpisode = !music && nextEpisode() != null, hasPreviousEpisode = !music && d != null && d.episodes.getOrNull(d.currentIndex - 1) != null,
        )
        runCatching {
            val sv: dynamic = context.getSystemVolume()
            remoteVolume.sync((sv?.level as? Double)?.toFloat(), sv?.muted as? Boolean)
        }
        val action = receiverRemoteAction(cmd, st, remoteVolume)
        note("remote $cmd -> $action")
        when (action) {
            ReceiverRemoteAction.Nothing -> return
            ReceiverRemoteAction.Play -> { playerManager.play(); if (music) showTransport() }
            ReceiverRemoteAction.Pause -> { playerManager.pause(); if (music) showTransport() }
            ReceiverRemoteAction.Stop -> if (music) musicEnded() else stopFilm()
            is ReceiverRemoteAction.Seek -> { playerManager.seek(action.positionMs / 1000.0); if (music) showTransport() }
            ReceiverRemoteAction.MusicNext -> { showTransport(); musicNext(byViewer = true) }
            ReceiverRemoteAction.MusicPrevious -> { showTransport(); musicPrevious() }
            ReceiverRemoteAction.EpisodeNext -> loadNext()
            ReceiverRemoteAction.EpisodePrevious -> loadEpisode(-1)
            is ReceiverRemoteAction.Volume -> {
                runCatching {
                    context.setSystemVolumeLevel(action.level.toDouble())
                    context.setSystemVolumeMuted(action.muted)
                }
                reportVolumeNow(remoteVolume.report())   // R357 (FR-R357-4) — what the command set, at once
            }
        }
        sendStatus()
    }

    /**
     * R357 (FR-R357-3) — the device's own volume (CAF's system volume: level × 100 and muted), the one its senders'
     * sliders show and R354's commands move; every progress report says it. Null when CAF does not say (FR-R357-5).
     */
    private fun systemVolume(): VolumeReport? = runCatching {
        val sv: dynamic = context.getSystemVolume()
        volumeReportOf(sv?.level as? Double, sv?.muted as? Boolean)
    }.getOrNull()

    /**
     * R357 (FR-R357-4) — one progress report with [v] once the volume has held still, while an item is loaded; never the
     * same volume twice in a row.
     */
    private fun reportVolumeNow(v: VolumeReport?) {
        if (current == null || v == null) return
        volumeJob?.cancel()
        volumeJob = GlobalScope.launch {
            delay(VOLUME_SETTLE_MS)
            val d = current ?: return@launch
            if (v == volumeSent) return@launch
            volumeSent = v
            runCatching { api?.reportProgress(d.itemId, positionMs, paused, v) }
        }
    }

    /**
     * R356 (FR-R356-14b) — another controller's STOP: the server hears where it stopped, the item is forgotten (so CAF's
     * MEDIA_FINISHED(STOPPED) that follows is neither a failure nor a next song) and the app closes a moment later, once
     * CAF has told the senders the media stopped. Every sender's session ends; the phone takes the song back, paused.
     */
    private fun onSenderStop() {
        val d = current
        note("stop from a sender (${d?.itemId}): the cast ends")
        nextUpJob?.cancel(); nextUpJob = null
        stopSession()
        current = null
        pausedBeat?.cancel(); pausedBeat = null
        GlobalScope.launch {
            delay(SENDER_STOP_CLOSE_MS)
            runCatching { context.stop() }.onFailure { idle() }
        }
    }

    /** A film stopped from the dashboard: the idle view, and *ended* to the senders — never *failed*. */
    private fun stopFilm() {
        val d = current ?: return
        nextUpJob?.cancel(); nextUpJob = null
        el("nextup").classList.remove("on")
        stopSession()
        current = null   // before the player's stop, so its MEDIA_FINISHED finds nothing to call a failure
        runCatching { playerManager.stop() }
        send(CastReceiverMessage(type = "ended", itemId = d.itemId, title = d.title, kicker = d.kicker, artUrl = d.artUrl, hasNext = false, receiverId = receiverId))
        idle()
    }

    /** R354 (FR-R354-7) — a paused receiver says so every 30 s, so the server's 90 s watchdog keeps its session. */
    private fun beatWhilePaused() {
        pausedBeat?.cancel()
        pausedBeat = GlobalScope.launch {
            while (true) {
                delay(RECEIVER_PAUSED_BEAT_MS)
                val d = current ?: break
                if (!paused) break
                runCatching { api?.reportProgress(d.itemId, positionMs, true, systemVolume()) }
            }
        }
    }

    private fun apiFor(base: String): TvApiClient {
        val a = api
        if (a != null && serverUrl == base) return a
        serverUrl = base.trimEnd('/')
        // 286 (dev review 1) — an audio-only device says so on every request (`cast-audio`): it is what flips the
        // admin card's speaker line and what Users & devices badges; the row's `kind` stays `cast`.
        return TvApiClient(client = HttpClient(Js) { install(WebSockets) /* R354 */ }, baseUrl = serverUrl, deviceToken = { token }, platform = if (isHeadless()) "cast-audio" else "cast" /* R252 */).also { api = it }
    }

    private suspend fun intercept(request: dynamic): dynamic {
        // R359 (FR-R359-2) — the media's customData is the one read (and has been since R245); senders from R359 on send
        // it once, there. The request's is read only when the media carries none.
        val raw = request.media?.customData ?: request.customData
        val sent = runCatching { json.decodeFromString(CastLoadData.serializer(), JSON.stringify(raw) as String) }.getOrNull()
            ?: return request
        val data = withQueueNow(sent)
        // R359 (FR-R359-3) — a sender's LOAD is a new queue: what was waiting for the one before is dropped.
        if (data.code.isNotEmpty()) startQueue(data)
        // R279 — the hand-off payload's `lang` is the CASTING USER's own configured uiLanguage
        // (the sender reads it off their config before minting the code), so it is the only thing
        // on this device that knows which of a household's viewers pressed play. Adopted first, and
        // remembered, so the idle screen after this cast stays in their language too.
        ReceiverStrings.adopt(data.lang)
        subSize = data.subSize
        // 289 (FR-289-2) — a sender's LOAD over something that is playing (an album started while another plays, a
        // second film): what it replaces is stopped and reported HERE, under its own id and position. The player's
        // own MEDIA_FINISHED for it arrives after `current` is the new item. The receiver's own loads (next song,
        // next episode, a restream) carry no code and have dealt with the item before themselves.
        note("load ${data.itemId} own=${data.code.isEmpty()} tracks=${data.tracks.size} state=${playerManager.getPlayerState()} before=${current?.itemId} open=$sessionOpen")
        if (data.code.isNotEmpty()) replaced(data.itemId)
        current = data
        introSkipped = false
        isHeadless()
        val api = apiFor(data.serverUrl)
        if (data.tracks.isNotEmpty()) return interceptMusic(request, data, api)
        el("loading-kicker").textContent = data.kicker ?: ""
        el("loading-title").textContent = data.title
        el("loading-label").textContent = ReceiverStrings.t("loading")
        el("nextup").classList.remove("on")
        show("loading")
        // 218 FR-218-9 / 300 FR-300-1 — every sender's LOAD enrols: the server stores that sender's live sign-in.
        enrol(data, api)?.let { e -> return failLoad(e) }
        if (config == null) config = runCatching { api.getConfig() }.getOrNull()
        // …and the receiver's own config only as a fallback. It used to overwrite `lang` outright,
        // which was wrong twice over: `config` is fetched once and cached across casts, so a second
        // viewer casting to the same Chromecast was drawn in the FIRST viewer's language.
        ReceiverStrings.adopt(data.lang, config?.uiLanguage)
        // R285 (FR-R285-4) — a reload we asked for ourselves is a restream of the running session, not
        // a new negotiation. If it fails, keep what is playing: returning null cancels this LOAD only.
        val restream = pendingRestream.also { pendingRestream = null }
        val t = if (restream != null) {
            runCatching { api.restream(data.itemId, restream.first, data.positionMs ?: positionMs, capabilities(), restream.second) }.getOrNull() ?: return null
        } else if (data.episodesShuffled || data.startOver) {
            // R343 (FR-R343-8) — a shuffled entry (every start of the list) and a Start over (the sender's first
            // load only) say so on the start, from 0:00 or the handed-over position. A plain load is unchanged.
            negotiate(api, data.itemId) {
                api.startPlayback(data.itemId, capabilities(), startPositionMs = data.positionMs ?: 0L, shuffle = data.episodesShuffled, startOver = data.startOver)
            } ?: return null
        } else negotiate(api, data.itemId) ?: return null   // busy/noserver screens already showing
        ticket = t
        sessionOpen = true
        // R351 (FR-R351-5) — what this device was given, readable from the sender's log without a second test.
        note("ticket ${data.itemId} direct=${t.directPlay} caps=${decode.maxWidth}x${decode.maxHeight}/L${decode.maxLevel}/ch${decode.maxAudioChannels} ${CastDecodeProbe.streamSummary(t.hlsUrl ?: "")}")
        val messages = cast.framework.messages
        request.media.contentId = t.hlsUrl
        request.media.contentUrl = t.hlsUrl
        request.media.contentType = "application/x-mpegURL"
        request.media.streamType = messages.StreamType.BUFFERED
        val tracks = js("[]")
        var defaultSub: Int? = null
        // receiverSubtitles() lists text first, so a text track's position — and its CAF id — is what
        // it always was; the appended burn-in candidates get no CAF Track (there is no file to load).
        receiverSubtitles(t).filter { it.deliveryMethod != "encode" }.forEachIndexed { i, sub ->
            val id = 100 + i
            val track = js("new cast.framework.messages.Track(0, 'TEXT')")
            track.trackId = id
            track.type = messages.TrackType.TEXT
            track.trackContentId = sub.url
            track.trackContentType = "text/vtt"
            track.subtype = if (sub.forced) messages.TextTrackType.FORCED else messages.TextTrackType.SUBTITLES
            track.language = sub.language
            // R279 — the CAF track name, which the platform's own caption list can surface.
            track.name = sub.label ?: sub.language ?: ReceiverStrings.t("pl.subtitles")
            tracks.push(track)
            if (sub.isDefault && defaultSub == null) defaultSub = id
        }
        // R282's invariant on a Chromecast: a burned-in subtitle is the only subtitle. And after an
        // un-burn (or an audio change) the text track the viewer had — or just picked — comes back.
        if (t.burnedSubtitleIndex != null) defaultSub = null
        else if (restream != null) defaultSub = restream.third.takeIf { it >= 0 }?.let { 100 + it }
        request.media.tracks = tracks
        request.media.textTrackStyle = textStyle()
        request.media.metadata = request.media.metadata ?: js("new cast.framework.messages.GenericMediaMetadata()")
        request.media.metadata.title = data.title
        request.media.metadata.subtitle = data.kicker ?: ""
        if (defaultSub != null) { val active = js("[]"); active.push(defaultSub); request.activeTrackIds = active }
        request.currentTime = ((data.positionMs ?: t.startPositionMs) / 1000.0)
        // R285 — a track change made while paused stays paused; every other load autoplays, as before.
        val keepPaused = restream != null && paused
        request.autoplay = !keepPaused
        positionMs = data.positionMs ?: t.startPositionMs
        paused = keepPaused
        sendStatus()
        return request
    }

    /**
     * 218 FR-218-9, amended by 300 FR-300-1 — every sender's LOAD enrols (it used to be once per receiver when storage
     * survived, which left a receiver on its first sender's sign-in for good). Only a sender's LOAD can enrol: it
     * carries the code. The receiver's own loads (the next song, the next episode, a restream) repeat the sender's
     * data without one — and a sender that had just come from another device sent that device's id along, so the
     * first *Next* on the TV asked to enrol with an empty code and showed the no-server screen (289 FR-289-7).
     */
    private fun mustEnrol(data: CastLoadData): Boolean = data.code.isNotEmpty()

    /**
     * Phase 300 (FR-300-1) — redeems a sender's code, every time one comes (it used to be only the first time, so a
     * receiver kept its first sender's Jellyfin sign-in after that sender signed in again, and a second user's cast
     * played under the first user's account). The server stores the sender's live sign-in on this receiver's row for
     * that user and answers with that row's device token. Returns the failure to show, or null to go on: a failed
     * redemption with a token already held goes on with it (a retried LOAD re-sends a used code).
     */
    private suspend fun enrol(data: CastLoadData, api: TvApiClient): Throwable? {
        if (!mustEnrol(data)) return null
        val pr = runCatching { api.castRedeem(data.code, data.deviceName, receiverId) }.getOrElse { e ->
            if (token != null && receiverId != null) { note("enrol failed, keeping this receiver's sign-in: ${e::class.simpleName}"); return null }
            return e
        }
        // Another user's device token: their config (language, theme) is fetched afresh. The events socket follows the
        // token by itself (its key holds it).
        if (pr.deviceToken != token) config = null
        token = pr.deviceToken; receiverId = pr.session.deviceId
        localStorage.setItem(TOKEN_KEY, pr.deviceToken); localStorage.setItem(RECEIVER_ID_KEY, pr.session.deviceId)
        return null
    }

    private fun failLoad(e: Throwable): dynamic {
        el("noserver-t").textContent = ReceiverStrings.t("cast.no_server"); el("noserver-s").textContent = ReceiverStrings.t("cast.no_server_sub")
        show("noserver")
        send(CastReceiverMessage(type = "noserver", itemId = current?.itemId, title = current?.title, kicker = current?.kicker, artUrl = current?.artUrl, receiverId = receiverId))
        return null
    }

    /** FR-R245-13 — the receiver's own capabilities, probed at runtime; an old stick gets H.264 1080p. */
    private fun capabilities(): ClientCapabilities {
        fun can(mime: String, codec: String, w: Int = 1920, h: Int = 1080): Boolean =
            runCatching { context.canDisplayType(mime, codec, w, h) as Boolean }.getOrDefault(false)
        val hevc = can("video/mp4", "hev1.1.6.L153.B0")
        val vp9 = can("video/webm", "vp09.00.10.08")
        // R297 (FR-R297-1) — audio is probed like video. The stue TV's built-in Chromecast answers no
        // to AC-3 and E-AC-3, yet this list claimed both, so Jellyfin copied AC-3 through and Shaka
        // rejected every variant (error 4032): every cast failed about two seconds in.
        val ac3 = can("audio/mp4", "ac-3")
        val eac3 = can("audio/mp4", "ec-3")
        val opus = can("audio/mp4", "opus")
        val hdr10 = can("video/mp4", "hev1.2.6.L153.B0", 3840, 2160)
        // R351 (FR-R351-1–4) — H.264's size, level and bitrate, and the channel count, asked as Shaka will ask them.
        // These used to be declared without asking (1080p, level 5.1, no ceiling, six channels), and a Nest Hub was
        // handed a 1080p copy its decoder refuses: every film cast to it failed with Shaka 4032.
        val d = decode
        return ClientCapabilities(
            containers = listOf("mp4", "ts"),
            videoCodecs = listOfNotNull("h264", "hevc".takeIf { hevc }, "vp9".takeIf { vp9 }),
            audioCodecs = listOfNotNull("aac", "mp3", "opus".takeIf { opus }, "ac3".takeIf { ac3 && multichannel("ac-3") }, "eac3".takeIf { eac3 && multichannel("ec-3") }),
            maxAudioChannels = d.maxAudioChannels,
            hlsOnly = true,
            // R285 (FR-R285-5) / 253 — CAF plays fMP4 HLS, and `hevc` here is this device's own answer
            // to canDisplayType('video/mp4', hev1…): fMP4-HEVC is exactly what was probed. Without this
            // every HEVC title was re-encoded to h264 for a stick that had just said it decodes HEVC.
            hlsHevc = hevc,
            supportsHdr10 = hdr10,
            supportsHlg = hdr10,
            supportsDolbyVision = false,
            supportsDolbyVisionEl = false,
            maxH264Width = d.maxWidth,
            maxH264Height = d.maxHeight,
            maxH264Level = d.maxLevel,
            maxVideoBitrate = d.maxBitrate,
            maxH264Bitrate = d.maxBitrate,
            linkKind = "unknown",
        )
    }

    /** R351 (FR-R351-2) — `cast.__platform__`, the call Shaka itself makes on a Cast device; null elsewhere (a desktop
     *  browser, the fake CAF in the receiver's e2e test). */
    private val platform: dynamic by lazy {
        js("(typeof cast !== 'undefined' && cast.__platform__ && typeof cast.__platform__.canDisplayType === 'function') ? cast.__platform__ : null")
    }

    /** One question in Shaka's extended MIME form; null when the platform cannot be asked that way. */
    private fun platformCan(type: String): Boolean? {
        val p = platform ?: return null
        return runCatching { p.canDisplayType(type) as Boolean }.getOrNull()
    }

    /** FR-R351-4 — a surround codec counts only if the device takes six channels of it (Shaka asks with `channels`). */
    private fun multichannel(codec: String): Boolean = platformCan(CastDecodeProbe.extendedType("audio/mp4", codec, channels = 6)) != false

    /** FR-R351-1–4 — asked once per receiver start; the log channel carries the answer (FR-R351-5). */
    private val decode: CastDecodeProbe.Answer by lazy {
        val fps = CastDecodeProbe.PROBE_FPS
        val a = CastDecodeProbe.decide(
            h264 = { r ->
                platformCan(CastDecodeProbe.extendedType("video/mp4", r.codecs, r.width, r.height, fps))
                    ?: (context.canDisplayType("video/mp4", r.codecs, r.width, r.height, fps) as Boolean)
            },
            bitrate = if (platform == null) null else { r, bps ->
                platformCan(CastDecodeProbe.extendedType("video/mp4", r.codecs, r.width, r.height, fps, bps)) == true
            },
            sixChannels = if (platform == null) null else { -> platformCan(CastDecodeProbe.extendedType("audio/mp4", "mp4a.40.2", channels = 6)) != false },
        )
        note("caps h264≤${a.maxWidth}x${a.maxHeight} L${a.maxLevel} ceiling=${if (a.maxBitrate > 0) "${a.maxBitrate / 1_000_000}Mbps" else "none"} ch=${a.maxAudioChannels} platform=${platform != null}")
        a
    }

    /** Busy (phase 182's 503 + Retry-After) waits and retries; unreachable shows the no-server screen. */
    private suspend fun negotiate(api: TvApiClient, itemId: String, start: suspend () -> StreamTicket = { api.startPlayback(itemId, capabilities()) }): StreamTicket? {
        while (true) {
            val r = runCatching { start() }
            val e = r.exceptionOrNull() ?: run { busySinceMs = null; return r.getOrNull() }
            val http = e as? TvApiError.Http
            if (http != null && http.status == 503) {
                val wait = http.retryAfterSeconds ?: 5
                if (busySinceMs == null) busySinceMs = nowMs()
                el("busy-t").textContent = ReceiverStrings.t("srv.busy"); el("busy-s").textContent = ReceiverStrings.t("srv.busy_sub")
                show("busy")
                send(CastReceiverMessage(type = "busy", itemId = itemId, title = current?.title, kicker = current?.kicker, artUrl = current?.artUrl, retryAfter = wait, sinceMs = busySinceMs, receiverId = receiverId))
                repeat(wait) { s ->
                    el("busy-wait").textContent = ReceiverStrings.t("cast.waiting", ((nowMs() - (busySinceMs ?: nowMs())) / 1000).toInt())
                    delay(1_000)
                }
                continue
            }
            if (http != null && http.status == 401) { token = null; localStorage.removeItem(TOKEN_KEY) }
            failLoad(e)
            return null
        }
    }


    // ── 286 — music ──

    /** FR-286-3 — a fixed audio set; `canDisplayType` means nothing on a speaker (dev review 2). */
    private fun audioCapabilities(): ClientCapabilities = ClientCapabilities(
        containers = listOf("mp3", "flac", "ogg", "opus", "m4a", "mp4", "aac", "webm"),
        videoCodecs = emptyList(),
        audioCodecs = listOf("mp3", "aac", "flac", "opus", "vorbis"),
        maxAudioChannels = 2,
        hlsOnly = false,
        linkKind = "unknown",
        // Phase 288 — a FLAC with no seek table is read from its start to find a time; a TV asked to begin at 2:40
        // reported the end of the song instead. The server converts such a file for a player that says this.
        seekNeedsIndex = true,
    )

    private fun absolute(url: String?): String? = url?.let { if (it.startsWith("http")) it else serverUrl + (if (it.startsWith("/")) it else "/$it") }

    private fun track(i: Int = current?.currentIndex ?: -1): CastTrackItem? = current?.tracks?.getOrNull(i)

    /** FR-286-4 — one song: its own ticket, its own session; reported per song through progress/stop as before. */
    private suspend fun interceptMusic(request: dynamic, data: CastLoadData, api: TvApiClient): dynamic {
        val i = data.currentIndex.coerceIn(0, data.tracks.lastIndex)
        val t = data.tracks[i]
        if (unshuffled.isEmpty() || unshuffled.map { it.id }.toSet() != data.tracks.map { it.id }.toSet()) unshuffled = data.tracks
        current = data.copy(itemId = t.id, title = t.title, kicker = t.artist, artUrl = absolute(t.coverUrl), currentIndex = i)
        attachParts()   // R359 — parts that came before the LOAD was taken up
        el("nextup").classList.remove("on"); el("overlay").classList.remove("on")
        if (!isHeadless()) { paintNow(); show("nowplaying", "buffering") }
        enrol(data, api)?.let { e -> return failLoad(e) }   // 300 FR-300-1
        if (config == null) config = runCatching { api.getConfig() }.getOrNull()
        ReceiverStrings.adopt(data.lang, config?.uiLanguage)
        val startAt = data.positionMs
        val ticket = negotiate(api, t.id) { api.playMusic(t.id, audioCapabilities(), startAt?.takeIf { it > 0 }) } ?: return null
        this.ticket = ticket
        sessionOpen = true
        val messages = cast.framework.messages
        val url = absolute(ticket.hlsUrl) ?: ticket.hlsUrl.orEmpty()
        request.media.contentId = url
        request.media.contentUrl = url
        request.media.contentType = when {
            url.contains(".m3u8") -> "application/x-mpegURL"
            else -> when (ticket.container.lowercase()) {
                "flac" -> "audio/flac"; "ogg", "oga", "opus", "vorbis" -> "audio/ogg"; "m4a", "mp4", "aac", "alac" -> "audio/mp4"; "webm" -> "audio/webm"; "wav" -> "audio/wav"
                else -> "audio/mpeg"
            }
        }
        request.media.streamType = messages.StreamType.BUFFERED
        request.media.tracks = js("[]")
        // The song's length, said with the load: a speaker's player reports none for a FLAC, and every sender's bar
        // (the Home app's too) then reads 0:00 with no progress.
        t.durationMs?.takeIf { it > 0 }?.let { request.media.duration = it / 1000.0 }
        // FR-286-3 — the metadata block the Home app and the Assistant read: title · artist · album · album artist · cover.
        val meta = js("new cast.framework.messages.MusicTrackMediaMetadata()")
        meta.title = t.title
        meta.artist = t.artist ?: ""
        meta.albumName = t.album ?: ""
        meta.albumArtist = t.albumArtist ?: t.artist ?: ""
        t.year?.let { meta.releaseDate = "$it-01-01" }
        absolute(t.coverUrl)?.let { c -> val img = js("({})"); img.url = c; val arr = js("[]"); arr.push(img); meta.images = arr }
        request.media.metadata = meta
        // R356 (FR-R356-8) — CAF echoes the media's customData in its media status to every sender: without the songs.
        // The queue lives in `current`; the receiver's own next load copies it from there, never from this.
        request.media.customData = JSON.parse(json.encodeToString(CastLoadData.serializer(), data.copy(tracks = emptyList(), code = "")))
        request.currentTime = ((startAt ?: ticket.startPositionMs) / 1000.0)
        request.autoplay = true
        positionMs = startAt ?: ticket.startPositionMs
        durationMs = t.durationMs ?: 0L
        paused = false
        // Dev review 4 — advertise next/previous, or the Home app hides its buttons.
        // `or` on a `dynamic` is a JS method call, not a bitwise or — the flags are combined in JS itself.
        runCatching {
            val flags: dynamic = js("cast.framework.messages.Command.PAUSE | cast.framework.messages.Command.SEEK | cast.framework.messages.Command.STREAM_VOLUME | cast.framework.messages.Command.STREAM_MUTE | cast.framework.messages.Command.QUEUE_NEXT | cast.framework.messages.Command.QUEUE_PREV")
            playerManager.setSupportedMediaCommands(flags, true)
        }
        lyrics = null; lyricsJob?.cancel()
        if (!isHeadless() && t.hasLyrics) lyricsJob = GlobalScope.launch { lyrics = runCatching { api.getLyrics(t.id) }.getOrNull()?.takeIf { !it.synced.isNullOrEmpty() }; paintLyrics() }
        sendStatus()
        note("hand ${t.id} ${request.media.contentType} direct=${ticket.directPlay} at=${request.currentTime} state=${playerManager.getPlayerState()}")
        return request
    }

    /** 286's repeat rule; R359 (FR-R359-4) adds [CastNext.Wait] — the next song is in a part still on its way. */
    private fun nextStep(byViewer: Boolean): CastNext {
        val d = current ?: return CastNext.End
        return castNextIndex(d.currentIndex, d.tracks.size, d.queueStart, d.queueTotal, d.repeat, byViewer)
    }

    private fun nextIndex(byViewer: Boolean): Int? = (nextStep(byViewer) as? CastNext.To)?.index

    /** The next song by repeat's rule; at the end of the queue the screen IS the idle view (FR-286-5). */
    private fun musicNext(byViewer: Boolean) {
        when (val n = nextStep(byViewer)) {
            is CastNext.To -> loadTrack(n.index)
            // R359 (FR-R359-4) — past the window while parts are arriving: the queue has not ended; go on when it comes.
            CastNext.Wait -> { waitingStep = +1; waitingByViewer = byViewer; note("next waits for the rest of the queue") }
            CastNext.End -> musicEnded()
        }
    }

    private fun musicPrevious() {
        val d = current ?: return
        if (positionMs <= 3_000L && castPreviousWaits(d.currentIndex, d.queueStart, d.queueTotal)) { waitingStep = -1; return }   // R359
        if (positionMs > 3_000L || d.currentIndex <= 0) { playerManager.seek(0.0); return }
        loadTrack(d.currentIndex - 1)
    }

    // ── R359 (FR-R359-3/4) — a long queue arrives as a window, then in parts ──

    /** A queue whose run is still arriving: [CastLoadData.queueTotal] set and more than the run holds. */
    private val assembling: Boolean get() = current?.let { d -> d.tracks.isNotEmpty() && d.queueTotal != null && d.tracks.size < d.queueTotal!! } == true

    /**
     * The receiver's own next loads carry the queue as it was when they were asked; parts (or an edit) that came in since
     * are in `current`. A load of the queue `current` holds takes the queue from there, the song found again by its place
     * (or, after an edit, by its id). A sender's LOAD (it carries a code) is taken as it comes.
     */
    private fun withQueueNow(data: CastLoadData): CastLoadData {
        val c = current ?: return data
        if (data.code.isNotEmpty() || data.tracks.isEmpty() || data.queueId == null || data.queueId != c.queueId || c.tracks.isEmpty()) return data
        val at = data.queueStart + data.currentIndex - c.queueStart
        val i = at.takeIf { c.tracks.getOrNull(it)?.id == data.itemId } ?: c.tracks.indexOfFirst { it.id == data.itemId }.takeIf { it >= 0 } ?: return data
        return data.copy(tracks = c.tracks, currentIndex = i, queueStart = c.queueStart, queueTotal = c.queueTotal)
    }

    /** A sender's LOAD: a new queue (or a film). Parts of any other queue, commands held for one, a waiting next: gone. */
    private fun startQueue(data: CastLoadData) {
        waitingParts.removeAll { it.queueId != data.queueId }
        deferred.clear(); waitingStep = 0
        assemblyJob?.cancel(); assemblyJob = null
        if (data.queueTotal != null && data.tracks.size < data.queueTotal!!) {
            note("queue: ${data.tracks.size} of ${data.queueTotal} songs in the LOAD (from ${data.queueStart}); the rest follows")
            watchAssembly()
        }
    }

    /** A `queue_part`: joined to the run when it is this queue's (now or once it joins), held when it comes before its LOAD. */
    private fun onQueuePart(cmd: CastCommand) {
        if (cmd.queueId == null || cmd.offset == null || cmd.tracks.isNullOrEmpty()) return
        if (cmd.queueId == current?.queueId && !assembling) return   // that queue is whole already (or was given up on)
        // Only the newest queue's parts are held: a part of another one means the sender moved on.
        waitingParts.removeAll { it.queueId != cmd.queueId && it.queueId != current?.queueId }
        if (waitingParts.size < MAX_WAITING_PARTS) waitingParts += cmd
        if (cmd.queueId == current?.queueId) attachParts()
    }

    /** Joins every waiting part of `current`'s queue to it; once the queue is whole, says so and catches up. */
    private fun attachParts() {
        val d = current ?: return
        if (!assembling) return
        val (run, start) = castQueueAttachAll(d.tracks, d.queueStart, d.queueId, waitingParts)
        if (start == d.queueStart && run.size == d.tracks.size) return
        val whole = start == 0 && run.size >= (d.queueTotal ?: 0)
        // Songs put in front move the current one along; its place in the whole queue does not change.
        current = d.copy(tracks = run, currentIndex = d.currentIndex + (d.queueStart - start), queueStart = start, queueTotal = if (whole) null else d.queueTotal)
        unshuffled = run   // in the sender's order: shuffle and the queue's edits wait until it is whole
        if (whole) {
            assemblyJob?.cancel(); assemblyJob = null
            note("queue: whole, ${run.size} songs")
            val held = deferred.toList(); deferred.clear()
            held.forEach { onCommand(it) }
        } else watchAssembly()
        catchUp()
        paintNow(); sendStatus()
    }

    /** A next or previous that waited for a part goes on once the song it wanted is here (or the queue stopped coming). */
    private fun catchUp() {
        val d = current ?: return
        when (waitingStep) {
            +1 -> if (nextStep(waitingByViewer) != CastNext.Wait) { waitingStep = 0; musicNext(waitingByViewer) }
            -1 -> if (!castPreviousWaits(d.currentIndex, d.queueStart, d.queueTotal)) { waitingStep = 0; if (d.currentIndex > 0) loadTrack(d.currentIndex - 1) else playerManager.seek(0.0) }
        }
    }

    /**
     * Parts follow the LOAD at once; if they stop coming (the sender went away part-way), the run the receiver holds
     * becomes the queue — reported as such, so every sender takes it — and whatever waited for the rest goes on.
     */
    private fun watchAssembly() {
        assemblyJob?.cancel()
        assemblyJob = GlobalScope.launch {
            delay(ASSEMBLY_GIVE_UP_MS)
            val d = current ?: return@launch
            if (!assembling) return@launch
            note("queue: the rest never came; playing the ${d.tracks.size} songs held")
            current = d.copy(queueTotal = null, queueStart = 0)
            deferred.clear()   // their places were the whole queue's
            catchUp(); paintNow(); sendStatus()
        }
    }

    private fun musicEnded() {
        val d = current ?: return
        stopSession()
        runCatching { playerManager.stop() }
        // R359 (FR-R359-5) — the queue rides along only when it fits; the senders hold it from the statuses.
        send(castQueueIfFits(CastReceiverMessage(type = "ended", itemId = d.itemId, title = d.title, kicker = d.kicker, artUrl = d.artUrl, hasNext = false, receiverId = receiverId,
            queue = d.tracks.takeIf { !assembling }, queueIndex = d.queueStart + d.currentIndex, repeat = d.repeat, shuffle = d.shuffle, lyricsOn = if (isHeadless()) null else lyricsOn, headless = headless), json))
        current = null
        idle()
    }

    /** A LOAD the receiver issues to itself for song [i]; [interceptMusic] does the rest. */
    private fun loadTrack(i: Int, positionMs: Long? = null) {
        val d = current ?: return
        val t = d.tracks.getOrNull(i) ?: return
        stopSession()
        val data = d.copy(itemId = t.id, title = t.title, kicker = t.artist, artUrl = absolute(t.coverUrl), positionMs = positionMs, currentIndex = i, code = "")
        val req = js("new cast.framework.messages.LoadRequestData()")
        req.media = js("new cast.framework.messages.MediaInformation()")
        req.customData = JSON.parse(json.encodeToString(CastLoadData.serializer(), data))
        req.media.customData = req.customData
        req.autoplay = true
        playerManager.load(req)
    }

    private fun setRepeat(mode: String) { current = current?.copy(repeat = mode); sendStatus() }

    private fun setShuffle(on: Boolean) {
        val d = current ?: return
        if (assembling) return   // R359 (FR-R359-4) — the sender's order stands until the whole queue is here (a second at most)
        val cur = d.tracks.getOrNull(d.currentIndex)
        val list = if (on) {
            val rest = d.tracks.filterIndexed { i, _ -> i != d.currentIndex }.shuffled()
            listOfNotNull(cur) + rest
        } else unshuffled.ifEmpty { d.tracks }
        val idx = list.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
        current = d.copy(tracks = list, currentIndex = idx, shuffle = on)
        paintNow(); sendStatus()
    }

    private fun toggle() {
        if (!music) return
        showTransport()
        if ((playerManager.getPlayerState() as String) == "PLAYING") playerManager.pause() else playerManager.play()
    }

    private var keyDownAt = 0L
    private var keyHeld = false
    private var lastSeekAt = 0L

    /** FR-286-5 — OK/Enter · Play/Pause toggle; Stop stops; ◀ ▶ previous/next, held = seek 10 s; ▼ lyrics; Back hides only. */
    private fun onKey(ev: KeyboardEvent) {
        if (!music || isHeadless()) return
        when (ev.key) {
            "Enter", " ", "MediaPlayPause", "MediaPlay", "MediaPause" -> { ev.preventDefault(); toggle() }
            "MediaStop" -> { ev.preventDefault(); musicEnded() }
            "MediaTrackNext" -> { ev.preventDefault(); showTransport(); musicNext(byViewer = true) }
            "MediaTrackPrevious" -> { ev.preventDefault(); showTransport(); musicPrevious() }
            "ArrowLeft", "ArrowRight" -> {
                ev.preventDefault()
                val now = nowMs()
                if (!ev.repeat) { keyDownAt = now; keyHeld = false; return }
                if (now - keyDownAt < 450) return
                keyHeld = true
                if (now - lastSeekAt < 250) return
                lastSeekAt = now
                val to = (positionMs + if (ev.key == "ArrowRight") 10_000L else -10_000L).coerceIn(0L, durationMs.coerceAtLeast(0L))
                playerManager.seek(to / 1000.0); showTransport()
            }
            "ArrowDown" -> { ev.preventDefault(); setLyrics(!lyricsOn) }
            "GoBack", "BrowserBack", "Escape", "Backspace" -> { ev.preventDefault(); ev.stopPropagation(); el("np-tr").classList.remove("on"); transportJob?.cancel() }
        }
    }

    private fun onKeyUp(ev: KeyboardEvent) {
        if (!music || isHeadless()) return
        if (ev.key != "ArrowLeft" && ev.key != "ArrowRight") return
        ev.preventDefault()
        if (keyHeld) { keyHeld = false; return }
        showTransport()
        if (ev.key == "ArrowRight") musicNext(byViewer = true) else musicPrevious()
    }

    private fun setLyrics(on: Boolean) {
        lyricsOn = on
        localStorage.setItem(LYRICS_KEY, if (on) "1" else "0")
        paintLyrics(); sendStatus()
    }

    private fun showTransport() {
        if (isHeadless()) return
        el("np-tr").classList.add("on")
        transportJob?.cancel()
        transportJob = GlobalScope.launch { delay(TRANSPORT_MS); el("np-tr").classList.remove("on") }
        paintTransport()
    }

    private fun paintTransport() {
        val playing = runCatching { (playerManager.getPlayerState() as String) == "PLAYING" }.getOrDefault(!paused)
        el("np-tr-g").classList.toggle("play", !playing)
        el("np-tr-t").textContent = "${hms(positionMs)} / ${hms(durationMs)}"
    }

    /** FR-286-5 — cover · Now playing · title · artist · album · year · Next. */
    private fun paintNow() {
        if (isHeadless()) return
        val d = current ?: return
        val t = track() ?: return
        val cover = el("np-cover")
        val c = absolute(t.coverUrl)
        if (c != null) { (cover as? HTMLImageElement)?.src = c; cover.classList.remove("none") } else { (cover as? HTMLImageElement)?.removeAttribute("src"); cover.classList.add("none") }
        el("np-k").textContent = ReceiverStrings.t("music.now_playing")
        el("np-t").textContent = t.title
        el("np-a").textContent = t.artist ?: ""
        el("np-al").textContent = listOfNotNull(t.album, t.year?.toString()).joinToString(" · ")
        val next = nextIndex(byViewer = false)?.takeIf { it != d.currentIndex }?.let { d.tracks.getOrNull(it) }
        el("np-next").textContent = next?.let { "${ReceiverStrings.t("music.up_next")} · ${it.title}" } ?: ""
        paintProgress(); paintLyrics()
    }

    private fun paintProgress() {
        if (isHeadless() || !music) return
        val frac = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        el("np-fill").style.width = "${(frac * 1000).toInt() / 10.0}%"
        el("np-pos").textContent = hms(positionMs)
        el("np-dur").textContent = hms(durationMs)
        if (el("np-tr").classList.contains("on")) paintTransport()
    }

    /** FR-286-6 — the current line and two either side, following the receiver's own position. */
    private fun paintLyrics() {
        if (isHeadless() || !music) return
        val lines = lyrics?.synced
        val on = lyricsOn && !lines.isNullOrEmpty()
        el("np-ly").classList.toggle("on", on)
        document.body?.classList?.toggle("ly", on)
        if (!on || lines == null) return
        val cur = lines.indexOfLast { it.tMs <= positionMs + 200 }
        for (k in 0..4) el("ly-$k").textContent = lines.getOrNull(cur - 2 + k)?.line?.ifBlank { "·" } ?: ""
    }

    private fun textStyle(): dynamic {
        val style = js("new cast.framework.messages.TextTrackStyle()")
        style.fontScale = when (subSize) { "S" -> 0.85; "L" -> 1.25; else -> 1.0 }
        style.foregroundColor = "#FFFFFFFF"
        style.backgroundColor = "#00000000"
        style.edgeType = "OUTLINE"
        style.edgeColor = "#000000CC"
        style.fontFamily = "sans-serif"
        return style
    }

    // ── playback events ──
    private fun onTime(ms: Double) {
        positionMs = ms.toLong()
        // A speaker's player reports no length for a FLAC; the song's own, from the queue, stands in (289 FR-289-3).
        val said = ((playerManager.getDurationSec() as Double?) ?: 0.0).times(1000).toLong()
        durationMs = if (said > 0) said else track()?.durationMs ?: 0L
        val data = current ?: return
        if (music) { if (el("buffering").classList.contains("on")) show("nowplaying"); paintProgress(); paintLyrics() }
        else if (el("loading").classList.contains("on")) show()
        // FR-R245-14 — Skip Intro on the receiver, when the household's setting is Auto.
        val ep = data.episodes.getOrNull(data.currentIndex)
        val iStart = ep?.introStartMs
        val iEnd = ep?.introEndMs
        if (!introSkipped && config?.skipIntro == SkipMode.AUTO && iStart != null && iEnd != null && positionMs in iStart until iEnd) {
            introSkipped = true
            playerManager.seek(iEnd / 1000.0)
        }
        val now = nowMs()
        if (now - lastProgressAt >= PROGRESS_EVERY_MS) {
            lastProgressAt = now
            val vol = systemVolume()   // R357 (FR-R357-3)
            GlobalScope.launch { runCatching { api?.reportProgress(data.itemId, positionMs, paused, vol) } }
        }
        // Next-up card near the end (the receiver owns the countdown; the phone mirrors it). R351 (FR-R351-13) — the
        // credits marker or 20 s before the end, but never so late that the countdown cannot finish before the end.
        val nextUpAt = nextUpStartMs(durationMs, ep?.creditsStartMs, config?.skipSecs ?: 6)
        if (nextUpJob == null && nextUpAt != null && positionMs >= nextUpAt && nextEpisode() != null && config?.autoplayNext != false && config?.skipCredits != SkipMode.OFF) {
            note("nextup at ${positionMs}ms of ${durationMs}ms (credits ${ep?.creditsStartMs ?: "none"})")
            startNextUp()
        }
    }

    private fun onPlayerState() {
        val st = playerManager.getPlayerState() as String
        when (st) {
            "BUFFERING" -> if (music) show("nowplaying", "buffering") else if (!el("loading").classList.contains("on")) show("buffering")
            "PLAYING" -> { paused = false; pausedBeat?.cancel(); pausedBeat = null; if (music) { show("nowplaying"); paintTransport() } else show() }
            "PAUSED" -> { paused = true; if (music) { show("nowplaying"); showTransport() } else { show(); flashOverlay() }; val vol = systemVolume(); GlobalScope.launch { runCatching { api?.reportProgress(current?.itemId ?: return@launch, positionMs, true, vol) } }; beatWhilePaused() }
            "IDLE" -> {}
        }
        sendStatus()
    }

    private fun flashOverlay() {
        val data = current ?: return
        el("ov-kicker").textContent = data.kicker ?: ""
        el("ov-title").textContent = data.title
        val frac = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
        el("ov-fill").style.width = "${(frac * 100).toInt()}%"
        el("ov-time").textContent = "${hms(positionMs)} / ${hms(durationMs)}"
        el("overlay").classList.add("on")
        overlayJob?.cancel()
        overlayJob = GlobalScope.launch { delay(3_000); if (!paused) el("overlay").classList.remove("on") }
    }

    private fun nextEpisode(): CastEpisode? {
        val d = current ?: return null
        return d.episodes.getOrNull(d.currentIndex + 1)
    }

    private fun startNextUp() {
        val next = nextEpisode() ?: return
        val secs = config?.skipSecs ?: 6
        // R343 (FR-R343-8) — in a shuffle the next entry is not the next in order, and the card says so.
        el("nu-k").textContent = ReceiverStrings.t(if (current?.episodesShuffled == true) "player.up_next_shuffled" else "player.up_next"); el("nu-t").textContent = next.title
        el("nextup").classList.add("on")
        nextUpJob = GlobalScope.launch {
            var left = secs
            while (left > 0) {
                el("nu-c").textContent = ReceiverStrings.t("receiver.starts_in", left)
                send(CastReceiverMessage(type = "nextup", nextupSecs = left, nextTitle = next.title, receiverId = receiverId))
                delay(1_000)
                left--
            }
            el("nextup").classList.remove("on")
            loadNext()
        }
    }

    private fun cancelNextUp() {
        nextUpJob?.cancel(); nextUpJob = null
        el("nextup").classList.remove("on")
        send(CastReceiverMessage(type = "status", itemId = current?.itemId, title = current?.title, kicker = current?.kicker, artUrl = current?.artUrl, hasNext = nextEpisode() != null, receiverId = receiverId))
    }

    private fun loadNext() = loadEpisode(+1)

    /** The episode [step] places from this one in the list the sender handed over (+1 next, −1 previous, R354). */
    private fun loadEpisode(step: Int) {
        val d = current ?: return
        val next = d.episodes.getOrNull(d.currentIndex + step) ?: return
        nextUpJob?.cancel(); nextUpJob = null
        stopSession()
        // R343 — Start over belongs to the sender's first load only; the next episode is an ordinary play.
        val data = d.copy(itemId = next.id, title = next.title, kicker = next.kicker, artUrl = next.stillUrl ?: d.artUrl, positionMs = null, currentIndex = d.currentIndex + step, code = "", startOver = false)
        val req = js("new cast.framework.messages.LoadRequestData()")
        req.media = js("new cast.framework.messages.MediaInformation()")
        req.customData = JSON.parse(json.encodeToString(CastLoadData.serializer(), data))
        req.media.customData = req.customData
        req.autoplay = true
        playerManager.load(req)
    }

    private fun onFinished(endedReason: String?) {
        if (current == null) return   // R299 — already reported as failed
        // 289 (FR-289-1) — a new LOAD took the player. That is neither an end nor a failure: `current` is already
        // the new item, so a stop sent from here stopped THAT one on the server (which then ended the session the
        // load was about to play), and the "failed" that followed emptied the receiver. Next, Previous, a song
        // picked from the queue, an album started over another and a film's track change all ended in silence.
        // Compared as text: a constant this framework build lacks would be `undefined`, and equal to no reason.
        // The same while a LOAD is being prepared, whatever reason this framework build gives.
        if (endedReason == "INTERRUPTED" || loading > 0) return
        nextUpJob?.cancel(); nextUpJob = null
        el("nextup").classList.remove("on")
        stopSession()
        // R297 (FR-R297-2) — only a real end moves on. An error used to walk the whole queue, ~2 s an episode.
        val reachedEnd = endedReason == null || endedReason == cast.framework.events.EndedReason.END_OF_STREAM
        // 286 (FR-286-4) — a song's end: the next by repeat's rule; the queue's end is the idle view.
        if (music) { if (reachedEnd) musicNext(byViewer = false) else failed(); return }
        if (reachedEnd && nextEpisode() != null && config?.autoplayNext != false && config?.skipCredits != SkipMode.OFF) { loadNext(); return }
        // R299 (FR-R299-1) — a load that failed is not an end. The phone used to read "ended" with no
        // media session as R245's "Lost contact… it may still be playing", every clause of it false.
        if (!reachedEnd) { failed(); return }
        // FR-R245-15 — ended is literally the idle view.
        send(CastReceiverMessage(type = "ended", itemId = current?.itemId, title = current?.title, kicker = current?.kicker, artUrl = current?.artUrl, hasNext = nextEpisode() != null, receiverId = receiverId))
        idle()
    }

    /** R299 (FR-R299-1) — the item could not be played here; the phone says so and offers itself. */
    private fun failed() {
        val d = current ?: return
        send(CastReceiverMessage(type = "failed", itemId = d.itemId, title = d.title, kicker = d.kicker, artUrl = d.artUrl, receiverId = receiverId))
        // Said once. CAF reports a failed load on ERROR and again on MEDIA_FINISHED(ERROR); with the
        // item forgotten here, the second path finds nothing to stop or report (seen live: two
        // "stopped at 0ms" on one failed cast).
        current = null
        idle()
    }

    private fun stopSession() {
        val d = current ?: return
        val a = api ?: return
        val pos = positionMs
        sessionOpen = false
        GlobalScope.launch { runCatching { a.stopPlayback(d.itemId, pos) } }
    }

    /**
     * 289 (FR-289-2) — the item a sender's new LOAD replaces, reported as stopped where it was. When the new item is
     * the same one (a song started again), the stop is awaited: the server reads a stop that arrives after the new
     * start as the viewer having left, and ends the new session (180's FR-180-3). An item whose stop was already sent
     * (it ended, it failed) is not stopped twice — for the same reason.
     */
    private suspend fun replaced(byItemId: String) {
        val d = current ?: return
        val a = api ?: return
        if (!sessionOpen) return
        sessionOpen = false
        val pos = positionMs
        if (d.itemId == byItemId) runCatching { a.stopPlayback(d.itemId, pos) }
        else GlobalScope.launch { runCatching { a.stopPlayback(d.itemId, pos) } }
    }

    // ── phone → receiver ──
    private fun onCommand(raw: String) {
        val cmd = runCatching { json.decodeFromString(CastCommand.serializer(), raw) }.getOrNull() ?: return
        // R359 (FR-R359-3) — the rest of a long queue; it may come before its LOAD has been taken up.
        if (cmd.type == "queue_part") { onQueuePart(cmd); return }
        // R359 (FR-R359-4) — an edit's places are the whole queue's: while it is still arriving, the edit waits for it.
        if (music && assembling && cmd.type in DEFERRED_WHILE_ARRIVING) { if (deferred.size < MAX_WAITING_PARTS) deferred += raw; return }
        if (music) when (cmd.type) {
            "next" -> { musicNext(byViewer = true); return }
            "prev" -> { musicPrevious(); return }
            "play_at" -> { cmd.index?.let { loadTrack(it) }; return }
            "queue_move" -> {
                val d = current ?: return; val from = cmd.index ?: return; val to = cmd.to ?: return
                if (from !in d.tracks.indices || to !in d.tracks.indices) return
                val list = d.tracks.toMutableList(); val item = list.removeAt(from); list.add(to, item)
                val idx = list.indexOfFirst { it.id == d.itemId }.coerceAtLeast(0)
                current = d.copy(tracks = list, currentIndex = idx); paintNow(); sendStatus(); return
            }
            "queue_remove" -> {
                val d = current ?: return; val i = cmd.index ?: return
                if (i !in d.tracks.indices || i == d.currentIndex) return
                val list = d.tracks.toMutableList(); list.removeAt(i)
                current = d.copy(tracks = list, currentIndex = if (i < d.currentIndex) d.currentIndex - 1 else d.currentIndex); paintNow(); sendStatus(); return
            }
            "queue_add" -> { val d = current ?: return; val t = cmd.track ?: return; current = d.copy(tracks = d.tracks + t); paintNow(); sendStatus(); return }
            "queue_play_next" -> { val d = current ?: return; val t = cmd.track ?: return; val list = d.tracks.toMutableList(); list.add(d.currentIndex + 1, t); current = d.copy(tracks = list); paintNow(); sendStatus(); return }
            "repeat" -> { setRepeat(cmd.mode ?: "off"); paintNow(); return }
            "shuffle" -> { setShuffle(cmd.on == true); return }
            "lyrics" -> { setLyrics(cmd.on == true); return }
            // R356 (FR-R356-8) — asked: the answer carries the whole queue (an installed sender older than R356 asks
            // `status` on every resume; a newer one asks `get_queue` when it missed a revision).
            "status", "get_queue" -> { fullDue = true; sendStatus(); return }
            else -> return
        }
        when (cmd.type) {
            "subsize" -> { subSize = cmd.size ?: "M"; playerManager.setTextTrackStyle(textStyle()); sendStatus() }
            "next" -> loadNext()
            "nextup_cancel" -> cancelNextUp()
            "nextup_play" -> { nextUpJob?.cancel(); nextUpJob = null; el("nextup").classList.remove("on"); loadNext() }
            "status" -> sendStatus()
            // R285 (FR-R285-4) — both were named in CastCommand's own doc and handled nowhere. An HLS
            // cast carries one audio track and no picture subtitles, so both are a restream.
            "audio" -> {
                val wanted = ticket?.audio?.getOrNull(cmd.index ?: return) ?: return
                if (wanted.index == ticket?.audioStreamIndex) return
                reload(ticket?.burnedSubtitleIndex ?: -1, wanted.index, thenShow = activeTextPosition())
            }
            "subtitle" -> when (val pick = receiverSubPick(ticket, cmd.index ?: -1)) {
                ReceiverSubPick.Nothing -> Unit
                is ReceiverSubPick.Burn -> reload(pick.streamIndex, ticket?.audioStreamIndex, thenShow = -1)
                is ReceiverSubPick.Text ->
                    if (pick.unburnFirst) reload(-1, ticket?.audioStreamIndex, thenShow = cmd.index ?: -1)
                    else { setActiveText(cmd.index ?: -1); sendStatus() }
            }
        }
    }

    /** Position (in receiverSubtitles) of the CAF text track showing now; -1 = none. Ids are 100 + position. */
    private fun activeTextPosition(): Int = runCatching {
        val ids = playerManager.getTextTracksManager().getActiveIds()
        if (ids != null && (ids.length as Int) > 0) (ids[0] as Int) - 100 else -1
    }.getOrDefault(-1)

    private fun setActiveText(position: Int) {
        runCatching {
            val ids = js("[]"); if (position >= 0) ids.push(100 + position)
            playerManager.getTextTracksManager().setActiveByIds(ids)
        }
    }

    /** R285 (FR-R285-4) — re-LOAD the running item at the current position; [intercept] turns it into a restream. */
    private fun reload(subIndex: Int, audioIndex: Int?, thenShow: Int) {
        val d = current ?: return
        pendingRestream = Triple(subIndex, audioIndex, thenShow)
        val pos = runCatching { ((playerManager.getCurrentTimeSec() as Double) * 1000).toLong() }.getOrDefault(positionMs)
        val req = js("new cast.framework.messages.LoadRequestData()")
        req.media = js("new cast.framework.messages.MediaInformation()")
        req.customData = JSON.parse(json.encodeToString(CastLoadData.serializer(), d.copy(positionMs = pos, code = "")))
        req.media.customData = req.customData
        req.autoplay = !paused
        playerManager.load(req)
    }

    // ── receiver → phone ──
    private fun sendStatus() {
        syncEvents()   // R354 — the events socket follows what is loaded
        val d = current ?: return
        val t = ticket
        val subs = subtitleTracksOf(t, trackIdBase = 100)
        val audios = audioTracksOf(t, trackIdBase = 200)   // R285 — a handle for the sender, not a CAF id
        val active: dynamic = runCatching { playerManager.getMediaInformation()?.let { playerManager.getPlayerState(); playerManager.getStats() } }.getOrNull()
        // R356 (FR-R356-8) — the queue only when it changed since it was last sent, after a sender connected, or when
        // asked. It went out whole on every play, pause, buffer and song change: ~280 B a song, 55 KB for 199 songs,
        // which filled a frozen phone's binder buffer until Play services dropped the app.
        //
        // R359 (FR-R359-3/5) — while a long queue is still arriving its revision is not raised and the queue is not sent:
        // the senders hold the whole of it already, and the index said is the song's place in the whole queue. Once it
        // is whole, it goes out like any changed queue — in parts when it does not fit one message.
        val arriving = assembling
        if (!arriving) {
            val fp = d.tracks.hashCode()
            if (fp != queueFingerprint) { queueFingerprint = fp; queueRev++ }
        }
        val full = music && !arriving && (fullDue || sentRev != queueRev)
        if (full) { sentRev = queueRev; fullDue = false }
        val msg = CastReceiverMessage(
            type = "status", itemId = d.itemId, title = d.title, kicker = d.kicker, artUrl = d.artUrl,
            hasNext = if (music) nextStep(byViewer = true) != CastNext.End else nextEpisode() != null, audioTracks = audios, subtitleTracks = subs,
            // R285 — facts, not constants: these were `0` and "whichever track is flagged default",
            // whatever was actually playing. The burned-in track IS the selection while one is burned in.
            selectedAudio = receiverSelectedAudio(t), selectedSub = receiverSelectedSub(t, activeTextPosition()), subSize = subSize, receiverId = receiverId,
            transcoding = t?.let { !it.directPlay },
            // 286 (dev review 10) — the music snapshot the phone mirrors (R324 FR-R324-4).
            queue = d.tracks.takeIf { full }, queueIndex = (d.queueStart + d.currentIndex).takeIf { music }, repeat = d.repeat.takeIf { music }, shuffle = d.shuffle.takeIf { music },
            lyricsOn = if (music && !isHeadless()) lyricsOn else null, headless = headless,
            queueRev = queueRev.takeIf { music && !arriving }, queueSize = (d.queueTotal ?: d.tracks.size).takeIf { music },
        )
        // FR-R359-5 — a queue too long for one message follows the status in parts.
        val out = castQueueReply(msg, json)
        val bytes = out.sumOf { send(it) }
        // FR-R356-11 — the size, once per full send and once for the first status without the queue after it.
        if (music && full) { slimNoted = false; note("status ${bytes} B with the queue (${d.tracks.size} songs, rev $queueRev${if (out.size > 1) ", in ${out.size - 1} parts" else ""})") }
        else if (music && !slimNoted) { slimNoted = true; note("status ${bytes} B without the queue (${d.tracks.size} songs, rev $queueRev)") }
    }

    /** 289 — one line on the log channel ([CAST_LOG_NAMESPACE]); never anything a sender's state depends on. */
    private fun note(text: String) {
        runCatching { val m: dynamic = js("({})"); m.at = nowMs().toDouble(); m.note = text; context.sendCustomMessage(CAST_LOG_NAMESPACE, undefined, m) }
    }

    /** Broadcast to every sender; returns the message's size in characters of JSON (R356's log line). */
    private fun send(msg: CastReceiverMessage): Int {
        val text = json.encodeToString(CastReceiverMessage.serializer(), msg)
        runCatching { context.sendCustomMessage(CAST_NAMESPACE, undefined, JSON.parse(text)) }
        return text.length
    }
}

fun main() {
    window.addEventListener("load", { runCatching { Receiver().start() }.onFailure { console.error("Ravilo receiver failed to start: ${it.message}") } })
}
