package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.receiver.ReceiverStrings
import dev.jellystructure.ravilo.receiver.audioTracksOf
import dev.jellystructure.ravilo.receiver.hms
import dev.jellystructure.ravilo.receiver.nowMs
import dev.jellystructure.ravilo.receiver.subtitleTracksOf
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.NavigateEnvelope
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.PlayItemEnvelope
import dev.jellystructure.shared.tv.PlayerCommandEnvelope
import dev.jellystructure.shared.tv.PlaystateCommandEnvelope
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.ScreenStatus
import dev.jellystructure.shared.tv.ScreenTrack
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import kotlin.random.Random

/**
 * R264 — the receiver-only TV app: no navigation, one more media player driven entirely by the backend
 * (Phase 236). Unlike [dev.jellystructure.ravilo.cast.Receiver] (which owns a local episode queue because
 * CAF requires one), this receiver never decides what plays next by itself — it plays exactly the item
 * named in the most recent `play_item` event and reports `ended` when it finishes; whichever autoplay
 * logic exists lives on the phone/backend and arrives as the next `play_item`.
 */
private val json = Json { ignoreUnknownKeys = true }

private const val DEVICE_ID_KEY = "ravilo.screen.deviceId"
private const val TOKENS_KEY = "ravilo.screen.tokens"
private const val PROGRESS_EVERY_MS = 10_000L
private const val STATUS_EVERY_MS = 5_000L
private const val PAIR_POLL_MS = 3_000L
private const val RECONNECT_BASE_MS = 2_000L
private const val RECONNECT_MAX_MS = 30_000L

private class Screen(serverUrl: String) {
    private val backend = detectMediaBackend()
    private val deviceId = loadOrCreateDeviceId()
    // FR-236-4 — a screen can hold one device token PER PAIRED USER (a device token is always
    // per (device, user) in this codebase); `activeUserId` picks which one authenticates outbound calls,
    // switched whenever a play_item names a session_user_id.
    private val tokens = loadTokens().toMutableMap()
    private var activeUserId: String? = tokens.keys.firstOrNull()
    private val api = TvApiClient(
        client = HttpClient(Js),
        baseUrl = serverUrl,
        deviceToken = { tokens[activeUserId] ?: tokens.values.firstOrNull() },
        platform = "screen",
    )

    private var config: RaviloConfig? = null
    private var itemId: String? = null
    private var title: String? = null
    private var kicker: String? = null
    private var artUrl: String? = null
    private var loaded = false
    private var playing = false
    private var buffering = false
    private var lastProgressAt = 0L
    private var lastStatusAt = 0L
    private var busySinceMs: Long? = null
    private var selectedAudio = 0
    private var selectedSub = -1
    private var subSize = "M"
    private var ticket: StreamTicket? = null
    private var overlayJob: Job? = null

    // ── screens ──
    private fun el(id: String) = document.getElementById(id) as HTMLElement
    private fun show(vararg on: String) {
        for (id in listOf("idle", "loading", "buffering", "noserver", "busy")) el(id).classList.toggle("on", id in on)
    }

    fun start() {
        registerTvKeys()
        window.addEventListener("keydown", { e -> onKey(e as KeyboardEvent) })
        backend.setListener(object : MediaBackendListener {
            override fun onBufferingStart() { buffering = true; if (loaded) show("buffering"); sendStatus() }
            override fun onBufferingComplete() { buffering = false; if (loaded) show(); sendStatus() }
            override fun onPlaying() { playing = true; buffering = false; loaded = true; show(); sendStatus() }
            override fun onPaused() { playing = false; flashOverlay(); sendStatus() }
            override fun onStreamCompleted() { onFinished() }
            override fun onError(detail: String) { console.error("Ravilo screen: backend error: $detail"); failLoad() }
        })
        idle()
        GlobalScope.launch { pairingLoop() }
        GlobalScope.launch { eventLoop() }
        GlobalScope.launch { tickLoop() }
    }

    private fun idle() {
        loaded = false; playing = false; buffering = false; itemId = null; title = null; kicker = null; artUrl = null; ticket = null
        el("idle-sentence").textContent = ReceiverStrings.t("ready")
        show("idle")
    }

    // ── pairing: mints a code and polls it while idle. Per the dev review, this keeps running on idle
    // even once a token exists, so a second household member can pair the same screen. ──
    private suspend fun pairingLoop() {
        while (true) {
            if (loaded) { delay(2_000); continue }
            val minted = runCatching { api.screenCode(deviceId, deviceName(), "tizen") }.getOrNull()
            if (minted == null) { delay(5_000); continue }
            el("idle-code").textContent = minted.code
            while (nowMs() < minted.expiresAt) {
                if (loaded) break
                val result = runCatching { api.screenClaim(minted.code, minted.claimSecret) }.getOrNull()
                if (result != null) { onPaired(result); break }
                delay(PAIR_POLL_MS)
            }
        }
    }

    private fun onPaired(pr: PairResult) {
        tokens[pr.session.userId] = pr.deviceToken
        if (activeUserId == null) activeUserId = pr.session.userId
        saveTokens()
        GlobalScope.launch {
            config = runCatching { api.getConfig() }.getOrNull()
            config?.uiLanguage?.let { if (it.isNotBlank()) ReceiverStrings.lang = it }
        }
    }

    // ── events socket: reconnect with backoff (FR-R264-6) ──
    private suspend fun eventLoop() {
        var backoff = RECONNECT_BASE_MS
        while (true) {
            runCatching {
                api.connectEvents(
                    onOpen = { backoff = RECONNECT_BASE_MS; if (loaded) sendStatus() },
                    onEvent = {},
                    onPlayItem = { env -> onPlayItem(env) },
                    onPlaystateCommand = { env -> onPlaystateCommand(env) },
                    onPlayerCommand = { env -> onPlayerCommand(env) },
                    onNavigate = { env -> onNavigate(env) },
                )
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
        }
    }

    // ── progress (every 10s while playing) + status (on change + every 5s while loaded, FR-236-5) ──
    private suspend fun tickLoop() {
        while (true) {
            delay(1_000)
            if (!loaded) continue
            val now = nowMs()
            if (now - lastStatusAt >= STATUS_EVERY_MS) sendStatus()
            if (playing && now - lastProgressAt >= PROGRESS_EVERY_MS) {
                lastProgressAt = now
                val id = itemId ?: continue
                GlobalScope.launch { runCatching { api.reportProgress(id, backend.positionMs(), !playing) } }
            }
        }
    }

    // ── backend → screen ──
    private suspend fun onPlayItem(env: PlayItemEnvelope) {
        env.sessionUserId?.let { if (tokens.containsKey(it)) activeUserId = it }
        itemId = env.jellyfinId
        title = env.title
        kicker = null
        el("loading-title").textContent = env.title.orEmpty()
        el("loading-kicker").textContent = ""
        el("loading-label").textContent = ReceiverStrings.t("loading")
        show("loading")
        val t = negotiate(env.jellyfinId) ?: return
        ticket = t
        selectedAudio = 0
        selectedSub = t.subtitles.indexOfFirst { it.isDefault }
        loaded = true
        playing = false
        lastProgressAt = nowMs()
        backend.open(t.hlsUrl ?: "", env.startPositionMs.takeIf { it > 0 } ?: t.startPositionMs)
        sendStatus()
    }

    /** Busy (Phase 182's 503 + Retry-After) waits and retries; unreachable shows the no-server screen —
     *  same shape as [dev.jellystructure.ravilo.cast.Receiver]'s own negotiate(). */
    private suspend fun negotiate(itemId: String): StreamTicket? {
        val caps = backend.capabilities()
        while (true) {
            val r = runCatching { api.startPlayback(itemId, caps) }
            val e = r.exceptionOrNull() ?: run { busySinceMs = null; return r.getOrNull() }
            val http = e as? TvApiError.Http
            if (http != null && http.status == 503) {
                val wait = http.retryAfterSeconds ?: 5
                if (busySinceMs == null) busySinceMs = nowMs()
                el("busy-t").textContent = ReceiverStrings.t("busy")
                el("busy-s").textContent = ReceiverStrings.t("busy_s")
                show("busy")
                repeat(wait) {
                    el("busy-wait").textContent = ReceiverStrings.t("waiting", ((nowMs() - (busySinceMs ?: nowMs())) / 1000).toInt())
                    delay(1_000)
                }
                continue
            }
            if (http != null && http.status == 401) {
                tokens.remove(activeUserId); activeUserId = tokens.keys.firstOrNull(); saveTokens()
            }
            failLoad()
            return null
        }
    }

    private fun failLoad() {
        el("noserver-t").textContent = ReceiverStrings.t("noserver")
        el("noserver-s").textContent = ReceiverStrings.t("noserver_s")
        show("noserver")
        loaded = false
        sendStatus()
    }

    private fun onPlaystateCommand(env: PlaystateCommandEnvelope) {
        if (!loaded) return
        when (env.command) {
            "stop" -> { stopAndIdle(); return }
            "pause" -> backend.pause()
            "unpause" -> backend.play()
            "seek" -> env.seekPositionMs?.let { backend.seekTo(it); flashOverlay() }
        }
        sendStatus()
    }

    /** Phase 236 (FR-236-3) — everything past stop/pause/unpause/home. Commands this build doesn't
     *  recognise are ignored, per FR-236-11 — the caller sees the truth on this device's next status. */
    private fun onPlayerCommand(env: PlayerCommandEnvelope) {
        if (!loaded) return
        val args = env.args
        when (env.command) {
            "seek_relative" -> {
                val delta = args?.get("delta_ms")?.jsonPrimitive?.longOrNull ?: return
                backend.seekTo((backend.positionMs() + delta).coerceAtLeast(0))
                flashOverlay()
            }
            "audio_track" -> {
                val idx = args?.get("index")?.jsonPrimitive?.intOrNull ?: return
                selectedAudio = idx
                backend.selectAudioTrack(idx)
            }
            "subtitle_track" -> {
                val idx = args?.get("index")?.jsonPrimitive?.intOrNull ?: return
                selectedSub = idx
                backend.selectSubtitleTrack(idx)
            }
            "sub_size" -> subSize = args?.get("size")?.jsonPrimitive?.contentOrNull ?: subSize
            else -> return
        }
        sendStatus()
    }

    private fun onNavigate(env: NavigateEnvelope) {
        if (env.destination == "home") stopAndIdle()
    }

    private fun stopAndIdle() {
        val id = itemId
        val pos = if (loaded) backend.positionMs() else 0L
        if (id != null) GlobalScope.launch { runCatching { api.stopPlayback(id, pos) } }
        backend.close()
        idle()
        sendStatus()
    }

    private fun onFinished() {
        val id = itemId
        val dur = backend.durationMs()
        if (id != null) GlobalScope.launch { runCatching { api.stopPlayback(id, dur) } }
        backend.close()
        val endedItem = itemId; val endedTitle = title; val endedArt = artUrl
        idle()
        // FR-R245-9-shaped "ended" report — the phone/backend decide what (if anything) plays next.
        GlobalScope.launch {
            runCatching { api.postScreenStatus(ScreenStatus(itemId = endedItem, title = endedTitle, artUrl = endedArt, ended = true, sessionUserId = activeUserId)) }
        }
    }

    // ── remote control: Tizen's registered keys arrive as ordinary keydown events (same names as
    // TizenKeys.REQUIRED) alongside a normal D-pad's KeyboardEvent.key vocabulary. ──
    private fun onKey(e: KeyboardEvent) {
        if (!loaded) return
        when (e.key) {
            "Enter", "MediaPlayPause" -> if (playing) backend.pause() else backend.play()
            "MediaPlay" -> backend.play()
            "MediaPause" -> backend.pause()
            "MediaStop" -> stopAndIdle()
            "MediaRewind" -> { backend.seekTo((backend.positionMs() - 10_000).coerceAtLeast(0)); flashOverlay() }
            "MediaFastForward" -> { backend.seekTo(backend.positionMs() + 30_000); flashOverlay() }
            "Backspace", "Exit" -> stopAndIdle()
        }
    }

    private fun flashOverlay() {
        val pos = backend.positionMs(); val dur = backend.durationMs()
        el("ov-kicker").textContent = kicker.orEmpty()
        el("ov-title").textContent = title.orEmpty()
        val frac = if (dur > 0) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else 0.0
        el("ov-fill").style.width = "${(frac * 100).toInt()}%"
        el("ov-time").textContent = "${hms(pos)} / ${hms(dur)}"
        el("overlay").classList.add("on")
        overlayJob?.cancel()
        overlayJob = GlobalScope.launch { delay(3_000); if (playing) el("overlay").classList.remove("on") }
    }

    // ── screen → backend (FR-236-5: on every change + at least every 5s while loaded) ──
    private fun sendStatus() {
        lastStatusAt = nowMs()
        val t = ticket
        val status = ScreenStatus(
            itemId = itemId,
            title = title,
            kicker = kicker,
            artUrl = artUrl,
            positionMs = if (loaded) backend.positionMs() else 0,
            durationMs = if (loaded) backend.durationMs() else 0,
            playing = playing,
            buffering = buffering,
            loaded = loaded,
            busyRetryAfter = null,
            busySinceMs = busySinceMs,
            noServer = false,
            audioTracks = t?.let { audioTracksOf(it).map(CastTrack::toScreenTrack) } ?: emptyList(),
            subtitleTracks = t?.let { subtitleTracksOf(it, trackIdBase = 100).map(CastTrack::toScreenTrack) } ?: emptyList(),
            selectedAudio = selectedAudio,
            selectedSub = selectedSub,
            subSize = subSize,
            transcoding = t?.let { !it.directPlay } ?: false,
            sessionUserId = activeUserId,
        )
        GlobalScope.launch { runCatching { api.postScreenStatus(status) } }
    }

    private fun deviceName(): String = "Tizen TV"

    private fun loadOrCreateDeviceId(): String {
        localStorage.getItem(DEVICE_ID_KEY)?.let { return it }
        val id = "screen-" + randomHex(24)
        localStorage.setItem(DEVICE_ID_KEY, id)
        return id
    }

    private fun loadTokens(): Map<String, String> {
        val raw = localStorage.getItem(TOKENS_KEY) ?: return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    private fun saveTokens() {
        localStorage.setItem(TOKENS_KEY, json.encodeToString(tokens))
    }
}

/** [dev.jellystructure.ravilo.receiver.audioTracksOf]/[subtitleTracksOf] build [CastTrack]s (the Cast
 *  wire shape, carrying a `trackId` CAF needs); a screen's own status wire shape is [ScreenTrack] — same
 *  fields minus `trackId`, since ScreenStatus's `selected_audio`/`selected_sub` already select by index. */
private fun CastTrack.toScreenTrack() = ScreenTrack(index = index, label = label, language = language, forced = forced, isDefault = isDefault)

private fun randomHex(length: Int): String {
    val chars = "0123456789abcdef"
    return buildString { repeat(length) { append(chars[Random.nextInt(chars.length)]) } }
}

fun main() {
    // The receiver's server address has no on-screen setup yet (an open item for a follow-on phase —
    // this build's scope is the pairing/playback loop) — index.html sets this global at package time.
    val configured: dynamic = js("window.RAVILO_SERVER_URL")
    val serverUrl = (configured as? String)?.trim().orEmpty()
    if (serverUrl.isBlank()) {
        console.error("Ravilo screen: no server URL configured — set window.RAVILO_SERVER_URL in index.html")
        return
    }
    window.addEventListener("load", { runCatching { Screen(serverUrl).start() }.onFailure { console.error("Ravilo screen failed to start: ${it.message}") } })
}
