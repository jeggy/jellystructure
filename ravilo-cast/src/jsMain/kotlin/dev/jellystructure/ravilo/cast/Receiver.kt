package dev.jellystructure.ravilo.cast

import dev.jellystructure.shared.tv.CAST_NAMESPACE
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastEpisode
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.ClientCapabilities
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

private object Strings {
    private val en = mapOf(
        "ready" to "Ready to play from your phone", "loading" to "Loading…",
        "noserver" to "Can’t reach your Ravilo server", "noserver_s" to "Check that the server is on and try again from your phone.",
        "busy" to "The server is busy right now", "busy_s" to "It will start as soon as it can.", "waiting" to "waiting {n} s",
        "nextep" to "UP NEXT", "startsin" to "Starts in {n} s",
    )
    private val da = mapOf(
        "ready" to "Klar til at spille fra din telefon", "loading" to "Indlæser…",
        "noserver" to "Kan ikke nå din Ravilo-server", "noserver_s" to "Tjek at serveren er tændt, og prøv igen fra din telefon.",
        "busy" to "Serveren er travl lige nu", "busy_s" to "Den starter, så snart den kan.", "waiting" to "venter {n} s",
        "nextep" to "NÆSTE", "startsin" to "Starter om {n} s",
    )
    private val fo = mapOf(
        "ready" to "Klár at spæla frá telefonini", "loading" to "Løðir…",
        "noserver" to "Kann ikki ná Ravilo-servaranum", "noserver_s" to "Kanna um servarin er á, og royn aftur frá telefonini.",
        "busy" to "Servarin hevur mikið at gera nú", "busy_s" to "Hon byrjar, so skjótt sum gjørligt.", "waiting" to "bíðar {n} s",
        "nextep" to "NÆSTA", "startsin" to "Byrjar um {n} s",
    )
    var lang = "en"
    fun t(key: String, n: Int? = null): String {
        val table = when (lang) { "da" -> da; "fo" -> fo; else -> en }
        val s = table[key] ?: en[key] ?: key
        return if (n != null) s.replace("{n}", n.toString()) else s
    }
}

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
        el("idle-sentence").textContent = Strings.t("ready")
        el("nextup").classList.remove("on"); el("overlay").classList.remove("on")
        show("idle")
    }

    fun start() {
        el("idle-sentence").textContent = Strings.t("ready")
        val messages = cast.framework.messages
        // FR-R245-13 — the LOAD interceptor: enrol if needed, negotiate our own ticket, then hand CAF the
        // real media. The phone never hands us a media URL.
        playerManager.setMessageInterceptor(messages.MessageType.LOAD) { request: dynamic ->
            GlobalScope.promise { intercept(request) }
        }
        val et = cast.framework.events.EventType
        playerManager.addEventListener(et.TIME_UPDATE) { ev: dynamic -> onTime(((ev.currentMediaTime as Double?) ?: 0.0) * 1000) }
        playerManager.addEventListener(et.PLAYER_STATE_CHANGED) { _: dynamic -> onPlayerState() }
        playerManager.addEventListener(et.MEDIA_FINISHED) { _: dynamic -> onFinished() }
        playerManager.addEventListener(et.ERROR) { _: dynamic -> /* a media error surfaces as buffering/finished; the phone's remote shows what it can */ }
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
        Strings.lang = data.lang
        subSize = data.subSize
        current = data
        introSkipped = false
        val api = apiFor(data.serverUrl)
        el("loading-kicker").textContent = data.kicker ?: ""
        el("loading-title").textContent = data.title
        el("loading-label").textContent = Strings.t("loading")
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
        config?.uiLanguage?.let { if (it.isNotBlank()) Strings.lang = it }
        val t = negotiate(api, data.itemId) ?: return null   // busy/noserver screens already showing
        ticket = t
        val messages = cast.framework.messages
        request.media.contentId = t.hlsUrl
        request.media.contentUrl = t.hlsUrl
        request.media.contentType = "application/x-mpegURL"
        request.media.streamType = messages.StreamType.BUFFERED
        val tracks = js("[]")
        var defaultSub: Int? = null
        t.subtitles.filter { it.url != null && it.deliveryMethod != "encode" }.forEachIndexed { i, sub ->
            val id = 100 + i
            val track = js("new cast.framework.messages.Track(0, 'TEXT')")
            track.trackId = id
            track.type = messages.TrackType.TEXT
            track.trackContentId = sub.url
            track.trackContentType = "text/vtt"
            track.subtype = if (sub.forced) messages.TextTrackType.FORCED else messages.TextTrackType.SUBTITLES
            track.language = sub.language
            track.name = sub.label ?: sub.language ?: "Subtitles"
            tracks.push(track)
            if (sub.isDefault && defaultSub == null) defaultSub = id
        }
        request.media.tracks = tracks
        request.media.textTrackStyle = textStyle()
        request.media.metadata = request.media.metadata ?: js("new cast.framework.messages.GenericMediaMetadata()")
        request.media.metadata.title = data.title
        request.media.metadata.subtitle = data.kicker ?: ""
        if (defaultSub != null) { val active = js("[]"); active.push(defaultSub); request.activeTrackIds = active }
        request.currentTime = ((data.positionMs ?: t.startPositionMs) / 1000.0)
        request.autoplay = true
        positionMs = data.positionMs ?: t.startPositionMs
        paused = false
        sendStatus()
        return request
    }

    private fun failLoad(e: Throwable): dynamic {
        el("noserver-t").textContent = Strings.t("noserver"); el("noserver-s").textContent = Strings.t("noserver_s")
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
        val hdr10 = can("video/mp4", "hev1.2.6.L153.B0", 3840, 2160)
        val uhd = can("video/mp4", "avc1.640033", 3840, 2160) || can("video/mp4", "hev1.1.6.L153.B0", 3840, 2160)
        return ClientCapabilities(
            containers = listOf("mp4", "ts"),
            videoCodecs = listOfNotNull("h264", "hevc".takeIf { hevc }, "vp9".takeIf { vp9 }),
            audioCodecs = listOf("aac", "mp3", "opus", "ac3", "eac3"),
            maxAudioChannels = 6,
            hlsOnly = true,
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
                el("busy-t").textContent = Strings.t("busy"); el("busy-s").textContent = Strings.t("busy_s")
                show("busy")
                send(CastReceiverMessage(type = "busy", itemId = itemId, title = current?.title, kicker = current?.kicker, artUrl = current?.artUrl, retryAfter = wait, sinceMs = busySinceMs, receiverId = receiverId))
                repeat(wait) { s ->
                    el("busy-wait").textContent = Strings.t("waiting", ((nowMs() - (busySinceMs ?: nowMs())) / 1000).toInt())
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
        el("nu-k").textContent = Strings.t("nextep"); el("nu-t").textContent = next.title
        el("nextup").classList.add("on")
        nextUpJob = GlobalScope.launch {
            var left = secs
            while (left > 0) {
                el("nu-c").textContent = Strings.t("startsin", left)
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

    private fun onFinished() {
        nextUpJob?.cancel(); nextUpJob = null
        el("nextup").classList.remove("on")
        stopSession()
        if (nextEpisode() != null && config?.autoplayNext != false && config?.skipCredits != SkipMode.OFF) { loadNext(); return }
        // FR-R245-15 — ended is literally the idle view.
        send(CastReceiverMessage(type = "ended", itemId = current?.itemId, title = current?.title, kicker = current?.kicker, artUrl = current?.artUrl, hasNext = nextEpisode() != null, receiverId = receiverId))
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
        }
    }

    // ── receiver → phone ──
    private fun sendStatus() {
        val d = current ?: return
        val t = ticket
        val subs = t?.subtitles?.filter { it.url != null && it.deliveryMethod != "encode" }?.mapIndexed { i, s ->
            CastTrack(index = i, label = s.label, language = s.language, forced = s.forced, isDefault = s.isDefault, trackId = (100 + i).toLong())
        } ?: emptyList()
        val audios = t?.audio?.mapIndexed { i, a -> CastTrack(index = i, label = a.label, language = a.language, isDefault = a.isDefault) } ?: emptyList()
        val active: dynamic = runCatching { playerManager.getMediaInformation()?.let { playerManager.getPlayerState(); playerManager.getStats() } }.getOrNull()
        send(CastReceiverMessage(
            type = "status", itemId = d.itemId, title = d.title, kicker = d.kicker, artUrl = d.artUrl,
            hasNext = nextEpisode() != null, audioTracks = audios, subtitleTracks = subs,
            selectedAudio = 0, selectedSub = subs.indexOfFirst { it.isDefault }, subSize = subSize, receiverId = receiverId,
        ))
    }

    private fun send(msg: CastReceiverMessage) {
        runCatching { context.sendCustomMessage(CAST_NAMESPACE, undefined, JSON.parse(json.encodeToString(CastReceiverMessage.serializer(), msg))) }
    }
}

private fun nowMs(): Long = (js("Date.now()") as Double).toLong()
private fun hms(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

fun main() {
    window.addEventListener("load", { runCatching { Receiver().start() }.onFailure { console.error("Ravilo receiver failed to start: ${it.message}") } })
}
