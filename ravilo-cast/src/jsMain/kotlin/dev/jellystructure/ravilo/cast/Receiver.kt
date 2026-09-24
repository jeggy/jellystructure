package dev.jellystructure.ravilo.cast

import dev.jellystructure.ravilo.receiver.ReceiverStrings
import dev.jellystructure.ravilo.receiver.audioTracksOf
import dev.jellystructure.ravilo.receiver.hms
import dev.jellystructure.ravilo.receiver.nowMs
import dev.jellystructure.ravilo.receiver.subtitleTracksOf
import dev.jellystructure.shared.tv.CAST_NAMESPACE
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastEpisode
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.ClientCapabilities
import dev.jellystructure.shared.tv.ReceiverSubPick
import dev.jellystructure.shared.tv.receiverSelectedAudio
import dev.jellystructure.shared.tv.receiverSelectedSub
import dev.jellystructure.shared.tv.receiverSubPick
import dev.jellystructure.shared.tv.receiverSubtitles
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.SkipMode
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
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

    // ── screens ──
    private fun el(id: String) = document.getElementById(id) as HTMLElement
    private fun show(vararg on: String) {
        for (id in listOf("idle", "loading", "buffering", "noserver", "busy")) el(id).classList.toggle("on", id in on)
    }
    private fun idle() {
        el("idle-sentence").textContent = ReceiverStrings.t("cast.ready")
        el("nextup").classList.remove("on"); el("overlay").classList.remove("on")
        show("idle")
    }

    fun start() {
        el("idle-sentence").textContent = ReceiverStrings.t("cast.ready")
        val messages = cast.framework.messages
        // FR-R245-13 — the LOAD interceptor: enrol if needed, negotiate our own ticket, then hand CAF the
        // real media. The phone never hands us a media URL.
        playerManager.setMessageInterceptor(messages.MessageType.LOAD) { request: dynamic ->
            GlobalScope.promise { intercept(request) }
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
        playerManager.addEventListener(et.MEDIA_FINISHED) { ev: dynamic -> onFinished(ev.endedReason as String?) }
        // R297 (FR-R297-3) — record why a stream failed, so the next failure is read through DevTools, not guessed.
        playerManager.addEventListener(et.ERROR) { ev: dynamic ->
            console.error("ravilo-cast: media error", ev.detailedErrorCode, ev.reason, ev.error)
            // R299 (FR-R299-1) — a load that never reached a media session gets no MEDIA_FINISHED; CAF
            // reports it here with the player still IDLE. Say so once; a mid-play error is followed by
            // MEDIA_FINISHED(ERROR), which onFinished turns into the same message.
            if ((playerManager.getPlayerState() as String) == "IDLE" && current != null) { stopSession(); failed() }
        }
        playerManager.addEventListener(et.SEEKED) { _: dynamic -> flashOverlay() }
        context.addCustomMessageListener(CAST_NAMESPACE) { ev: dynamic -> onCommand(JSON.stringify(ev.data) as String) }
        context.addEventListener(cast.framework.system.EventType.SHUTDOWN) { _: dynamic -> stopSession() }
        val opts: dynamic = js("({})")
        opts.disableIdleTimeout = false
        context.start(opts)
    }

    private fun apiFor(base: String): TvApiClient {
        val a = api
        if (a != null && serverUrl == base) return a
        serverUrl = base.trimEnd('/')
        return TvApiClient(client = HttpClient(Js), baseUrl = serverUrl, deviceToken = { token }, platform = "cast" /* R252 */).also { api = it }
    }

    private suspend fun intercept(request: dynamic): dynamic {
        val raw = request.media?.customData ?: request.customData
        val data = runCatching { json.decodeFromString(CastLoadData.serializer(), JSON.stringify(raw) as String) }.getOrNull()
            ?: return request
        // R279 — the hand-off payload's `lang` is the CASTING USER's own configured uiLanguage
        // (the sender reads it off their config before minting the code), so it is the only thing
        // on this device that knows which of a household's viewers pressed play. Adopted first, and
        // remembered, so the idle screen after this cast stays in their language too.
        ReceiverStrings.adopt(data.lang)
        subSize = data.subSize
        current = data
        introSkipped = false
        val api = apiFor(data.serverUrl)
        el("loading-kicker").textContent = data.kicker ?: ""
        el("loading-title").textContent = data.title
        el("loading-label").textContent = ReceiverStrings.t("loading")
        el("nextup").classList.remove("on")
        show("loading")
        // 218 FR-218-9 — enrol once per receiver when storage survived, else per cast.
        if (token == null || (data.receiverId != null && data.receiverId != receiverId) || receiverId == null) {
            val enrolled = runCatching { api.castRedeem(data.code, data.deviceName, receiverId) }
            val pr = enrolled.getOrElse { e -> return failLoad(e) }
            token = pr.deviceToken; receiverId = pr.session.deviceId
            localStorage.setItem(TOKEN_KEY, pr.deviceToken); localStorage.setItem(RECEIVER_ID_KEY, pr.session.deviceId)
        }
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
        } else negotiate(api, data.itemId) ?: return null   // busy/noserver screens already showing
        ticket = t
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
        val uhd = can("video/mp4", "avc1.640033", 3840, 2160) || can("video/mp4", "hev1.1.6.L153.B0", 3840, 2160)
        return ClientCapabilities(
            containers = listOf("mp4", "ts"),
            videoCodecs = listOfNotNull("h264", "hevc".takeIf { hevc }, "vp9".takeIf { vp9 }),
            audioCodecs = listOfNotNull("aac", "mp3", "opus".takeIf { opus }, "ac3".takeIf { ac3 }, "eac3".takeIf { eac3 }),
            maxAudioChannels = 6,
            hlsOnly = true,
            // R285 (FR-R285-5) / 253 — CAF plays fMP4 HLS, and `hevc` here is this device's own answer
            // to canDisplayType('video/mp4', hev1…): fMP4-HEVC is exactly what was probed. Without this
            // every HEVC title was re-encoded to h264 for a stick that had just said it decodes HEVC.
            hlsHevc = hevc,
            supportsHdr10 = hdr10,
            supportsHlg = hdr10,
            supportsDolbyVision = false,
            supportsDolbyVisionEl = false,
            maxH264Width = if (uhd) 3840 else 1920,
            maxH264Height = if (uhd) 2160 else 1080,
            linkKind = "unknown",
        )
    }

    /** Busy (phase 182's 503 + Retry-After) waits and retries; unreachable shows the no-server screen. */
    private suspend fun negotiate(api: TvApiClient, itemId: String): StreamTicket? {
        val caps = capabilities()
        while (true) {
            val r = runCatching { api.startPlayback(itemId, caps) }
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
        durationMs = ((playerManager.getDurationSec() as Double?) ?: 0.0).times(1000).toLong()
        val data = current ?: return
        if (el("loading").classList.contains("on")) show()
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
            GlobalScope.launch { runCatching { api?.reportProgress(data.itemId, positionMs, paused) } }
        }
        // Next-up card near the end (the receiver owns the countdown; the phone mirrors it).
        val creditsAt = ep?.creditsStartMs?.takeIf { it > durationMs / 2 } ?: (durationMs - 20_000L)
        if (nextUpJob == null && durationMs > 0 && positionMs >= creditsAt && nextEpisode() != null && config?.autoplayNext != false && config?.skipCredits != SkipMode.OFF) {
            startNextUp()
        }
    }

    private fun onPlayerState() {
        val st = playerManager.getPlayerState() as String
        when (st) {
            "BUFFERING" -> if (!el("loading").classList.contains("on")) show("buffering")
            "PLAYING" -> { paused = false; show(); }
            "PAUSED" -> { paused = true; show(); flashOverlay(); GlobalScope.launch { runCatching { api?.reportProgress(current?.itemId ?: return@launch, positionMs, true) } } }
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
        el("nu-k").textContent = ReceiverStrings.t("player.up_next"); el("nu-t").textContent = next.title
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

    private fun loadNext() {
        val d = current ?: return
        val next = nextEpisode() ?: return
        nextUpJob?.cancel(); nextUpJob = null
        stopSession()
        val data = d.copy(itemId = next.id, title = next.title, kicker = next.kicker, artUrl = next.stillUrl ?: d.artUrl, positionMs = null, currentIndex = d.currentIndex + 1, code = "")
        val req = js("new cast.framework.messages.LoadRequestData()")
        req.media = js("new cast.framework.messages.MediaInformation()")
        req.customData = JSON.parse(json.encodeToString(CastLoadData.serializer(), data))
        req.media.customData = req.customData
        req.autoplay = true
        playerManager.load(req)
    }

    private fun onFinished(endedReason: String?) {
        if (current == null) return   // R299 — already reported as failed
        nextUpJob?.cancel(); nextUpJob = null
        el("nextup").classList.remove("on")
        stopSession()
        // R297 (FR-R297-2) — only a real end moves on. An error used to walk the whole queue, ~2 s an episode.
        val reachedEnd = endedReason == null || endedReason == cast.framework.events.EndedReason.END_OF_STREAM
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
        GlobalScope.launch { runCatching { a.stopPlayback(d.itemId, pos) } }
    }

    // ── phone → receiver ──
    private fun onCommand(raw: String) {
        val cmd = runCatching { json.decodeFromString(CastCommand.serializer(), raw) }.getOrNull() ?: return
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
        val d = current ?: return
        val t = ticket
        val subs = subtitleTracksOf(t, trackIdBase = 100)
        val audios = audioTracksOf(t, trackIdBase = 200)   // R285 — a handle for the sender, not a CAF id
        val active: dynamic = runCatching { playerManager.getMediaInformation()?.let { playerManager.getPlayerState(); playerManager.getStats() } }.getOrNull()
        send(CastReceiverMessage(
            type = "status", itemId = d.itemId, title = d.title, kicker = d.kicker, artUrl = d.artUrl,
            hasNext = nextEpisode() != null, audioTracks = audios, subtitleTracks = subs,
            // R285 — facts, not constants: these were `0` and "whichever track is flagged default",
            // whatever was actually playing. The burned-in track IS the selection while one is burned in.
            selectedAudio = receiverSelectedAudio(t), selectedSub = receiverSelectedSub(t, activeTextPosition()), subSize = subSize, receiverId = receiverId,
            transcoding = t?.let { !it.directPlay },
        ))
    }

    private fun send(msg: CastReceiverMessage) {
        runCatching { context.sendCustomMessage(CAST_NAMESPACE, undefined, JSON.parse(json.encodeToString(CastReceiverMessage.serializer(), msg))) }
    }
}

fun main() {
    window.addEventListener("load", { runCatching { Receiver().start() }.onFailure { console.error("Ravilo receiver failed to start: ${it.message}") } })
}
