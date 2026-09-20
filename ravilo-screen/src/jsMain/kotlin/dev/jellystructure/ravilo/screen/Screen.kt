package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.LastLanguage
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
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
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
import org.w3c.dom.HTMLInputElement
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
private const val SERVER_URL_KEY = "ravilo.screen.serverUrl"
private const val SETUP_PREFILL_KEY = "ravilo.screen.serverUrl.prefill"
private const val PROGRESS_EVERY_MS = 10_000L
private const val STATUS_EVERY_MS = 5_000L
private const val PAIR_POLL_MS = 3_000L
private const val RECONNECT_BASE_MS = 2_000L
private const val RECONNECT_MAX_MS = 30_000L

private class Screen(private val serverUrl: String) {
    private val backend = detectMediaBackend()
    private val deviceId = loadOrCreateDeviceId()
    // FR-236-4 — a screen can hold one device token PER PAIRED USER (a device token is always
    // per (device, user) in this codebase); `activeUserId` picks which one authenticates outbound calls,
    // switched whenever a play_item names a session_user_id.
    private val tokens = loadTokens().toMutableMap()
    private var activeUserId: String? = tokens.keys.firstOrNull()
    private val api = TvApiClient(
        client = HttpClient(Js) { install(WebSockets) },
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
        // R269 (FR-R269-7) — releasing Back before three seconds cancels the hold; a plain tap must
        // never reopen setup.
        window.addEventListener("keyup", { e -> if ((e as KeyboardEvent).key == "Backspace" || e.key == "Exit") backHeldSince = null })
        backend.setListener(object : MediaBackendListener {
            override fun onBufferingStart() { buffering = true; if (loaded) show("buffering"); sendStatus() }
            override fun onBufferingComplete() { buffering = false; if (loaded) show(); sendStatus() }
            override fun onPlaying() { playing = true; buffering = false; loaded = true; show(); sendStatus() }
            override fun onPaused() { playing = false; flashOverlay(); sendStatus() }
            override fun onStreamCompleted() { onFinished() }
            override fun onError(detail: String) { console.error("Ravilo screen: backend error: $detail"); failLoad() }
        })
        idle()
        // R279 — idle already drew in the remembered language (ReceiverStrings seeds itself from it),
        // so the pairing screen is never English in a Danish house just because nothing has loaded
        // yet. If this set is already enrolled, the active viewer's own setting supersedes it.
        if (activeUserId != null) GlobalScope.launch { adoptLanguageOfActiveUser() }
        GlobalScope.launch { pairingLoop() }
        GlobalScope.launch { eventLoop() }
        GlobalScope.launch { tickLoop() }
    }

    private fun idle() {
        loaded = false; playing = false; buffering = false; itemId = null; title = null; kicker = null; artUrl = null; ticket = null
        el("idle-sentence").textContent = ReceiverStrings.t("cast.ready")
        // R269 (FR-R269-6) — a quiet line naming the configured server; the only way a household can see
        // a TV is pointed at a server that has since moved.
        document.getElementById("idle-server")?.textContent = serverUrl.removePrefix("https://").removePrefix("http://")
        show("idle")
    }

    // ── pairing: mints a code and polls it while idle. Per the dev review, this keeps running on idle
    // even once a token exists, so a second household member can pair the same screen. R269 (FR-R269-5)
    // — a stored-but-unreachable server is its own state (the noserver screen, retrying), never confused
    // with "no address at all" (that's runServerSetup(), never reached once an address is stored). ──
    private suspend fun pairingLoop() {
        while (true) {
            if (loaded) { delay(2_000); continue }
            val minted = runCatching { api.screenCode(deviceId, deviceName(), "tizen") }.getOrNull()
            if (minted == null) {
                el("noserver-t").textContent = ReceiverStrings.t("cast.no_server")
                el("noserver-s").textContent = ReceiverStrings.t("cast.no_server_sub")
                show("noserver")
                delay(5_000)
                continue
            }
            el("idle-code").textContent = minted.code
            idle()
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
        GlobalScope.launch { adoptLanguageOfActiveUser() }
    }

    /**
     * R279 — draws this set in the language of whichever viewer it is currently acting for.
     *
     * A Tizen receiver holds a token per (device, user), so "the user" changes: whoever last sent it
     * something to play is who it is acting for, and [onPlayItem] moves `activeUserId` accordingly.
     * The config used to be fetched exactly once, on the first pairing, so a household's second
     * viewer got the first one's language forever.
     *
     * Re-renders whatever is on screen now, since idle is a long-lived screen that would otherwise
     * keep the previous language until something else redrew it.
     */
    private suspend fun adoptLanguageOfActiveUser() {
        val fresh = runCatching { api.getConfig() }.getOrNull() ?: return
        config = fresh
        val before = ReceiverStrings.lang
        ReceiverStrings.adopt(fresh.uiLanguage)
        if (ReceiverStrings.lang != before && !loaded) idle()
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
        val previousUser = activeUserId
        env.sessionUserId?.let { if (tokens.containsKey(it)) activeUserId = it }
        // R279 — a play names the viewer this set is acting for; their language, not the first
        // viewer who ever paired with it.
        if (activeUserId != previousUser) adoptLanguageOfActiveUser()
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
                el("busy-t").textContent = ReceiverStrings.t("srv.busy")
                el("busy-s").textContent = ReceiverStrings.t("srv.busy_sub")
                show("busy")
                repeat(wait) {
                    el("busy-wait").textContent = ReceiverStrings.t("cast.waiting", ((nowMs() - (busySinceMs ?: nowMs())) / 1000).toInt())
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
        el("noserver-t").textContent = ReceiverStrings.t("cast.no_server")
        el("noserver-s").textContent = ReceiverStrings.t("cast.no_server_sub")
        show("noserver")
        loaded = false
        sendStatus()
    }

    private fun onPlaystateCommand(env: PlaystateCommandEnvelope) {
        if (!loaded) return
        // Bug fix — RemoteRoutes.kt sends this quartet capitalized ("Stop"/"Pause"/"Unpause", Phase 111's
        // original wire shape); every other consumer normalizes it (PlayerScreen.kt's identical `when`
        // calls .lowercase() first) but this one didn't, so stop/pause/unpause silently no-op on every
        // real receiver — found 2026-09-19 building phase 248's cast e2e test, which drives this exact
        // code path for the first time ever.
        when (env.command.lowercase()) {
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
            // Bug fix — the real sender (ravilo-ui's ScreenSender.seekTo()) only ever sends absolute
            // "seek" with position_ms (RemoteRoutes.kt maps it to this same player_command event); no
            // client in this codebase sends "seek_relative" at all. Found 2026-09-19 building phase
            // 248's cast e2e test, which drives this exact real sender/receiver pair for the first time.
            "seek" -> {
                val pos = args?.get("position_ms")?.jsonPrimitive?.longOrNull ?: return
                backend.seekTo(pos.coerceAtLeast(0))
                flashOverlay()
            }
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

    private var backHeldSince: Long? = null

    // ── remote control: Tizen's registered keys arrive as ordinary keydown events (same names as
    // TizenKeys.REQUIRED) alongside a normal D-pad's KeyboardEvent.key vocabulary. ──
    private fun onKey(e: KeyboardEvent) {
        // R269 (FR-R269-7) — the only way back to server setup: hold Back on IDLE for three seconds.
        // Tracked outside the `!loaded` guard below since idle is exactly where loaded is false, and
        // outside the `when` since it needs the key-repeat/-up pair, not a single keydown.
        if ((e.key == "Backspace" || e.key == "Exit") && !loaded) {
            if (!e.repeat && backHeldSince == null) {
                val since = nowMs(); backHeldSince = since
                GlobalScope.launch {
                    delay(3_000)
                    if (backHeldSince == since) { backHeldSince = null; reopenServerSetup() }
                }
            }
            return
        }
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

    /** R269 (FR-R269-7) — no live teardown of this running instance: store the current address as the
     *  setup screen's prefill, drop the stored one, and reload — `main()`'s normal "no address stored"
     *  path then shows setup with today's address already in the field. The simplest correct
     *  implementation of a hold that should be rare enough to never need to be fast. */
    private fun reopenServerSetup() {
        localStorage.setItem(SETUP_PREFILL_KEY, serverUrl)
        localStorage.removeItem(SERVER_URL_KEY)
        window.location.reload()
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

/**
 * R269 — the one exception to this app's no-navigation rule: reached only when the TV holds no server
 * address at all (FR-R269-1). Probes `GET /api/health` — already unauthenticated, already returns
 * `{"status":"ok",…}` (see the phase's dev review: no backend change was needed, the endpoint already
 * existed for an unrelated reason) — and stores the address only once that succeeds, so a half-typed
 * address can never brick the boot path (the dev notes' own requirement). Scheme inference matches what
 * R226's `ServerSetupScreen` actually ships (verified against that code, not the FR's own — since
 * corrected — description of it): `https://` unless the household types a scheme themselves.
 */
private suspend fun runServerSetup(): String {
    val root = document.getElementById("setup") as HTMLElement
    val input = document.getElementById("setupHost") as HTMLInputElement
    val hint = document.getElementById("setupHint") as HTMLElement
    val button = document.getElementById("setupConnect") as HTMLElement
    document.getElementById("setupTitle")?.textContent = ReceiverStrings.t("receiver.setup_title")
    button.textContent = ReceiverStrings.t("receiver.setup_connect")
    hint.textContent = ReceiverStrings.t("receiver.setup_hint")
    // FR-R269-7 — a hold-Back reopen prefills the address that was just working, never a blank field.
    localStorage.getItem(SETUP_PREFILL_KEY)?.let { input.value = it.removePrefix("https://").removePrefix("http://") }
    localStorage.removeItem(SETUP_PREFILL_KEY)
    root.classList.add("on")

    val probeClient = HttpClient(Js)
    val resolved = CompletableDeferred<String>()

    suspend fun tryConnect() {
        val host = input.value.trim()
        if (host.isBlank()) return
        val hasScheme = host.startsWith("http://", ignoreCase = true) || host.startsWith("https://", ignoreCase = true)
        val url = (if (hasScheme) host else "https://$host").trimEnd('/')
        hint.textContent = ReceiverStrings.t("receiver.setup_trying")
        val ok = runCatching {
            val r = probeClient.get("$url/api/health")
            r.status.isSuccess() && r.bodyAsText().contains("\"status\":\"ok\"")
        }.getOrDefault(false)
        if (ok) resolved.complete(url) else hint.textContent = ReceiverStrings.t("receiver.setup_not_found")
    }
    button.addEventListener("click", { GlobalScope.launch { tryConnect() } })
    input.addEventListener("keydown", { e -> if ((e as KeyboardEvent).key == "Enter") GlobalScope.launch { tryConnect() } })

    val url = resolved.await()
    localStorage.setItem(SERVER_URL_KEY, url)
    root.classList.remove("on")
    return url
}

fun main() {
    // R279 + FR-R269-9 — the ladder for a set with no user signed in: what this set last drew in,
    // then the TV's own reported language (the standard Web API, not a Tizen-specific systeminfo
    // call — works identically wherever this bundle runs), then English. The remembered language
    // leads because a set that has been used before knows more about the household than the TV's
    // factory locale does; R269's rung is what is left for a set out of its box, which is the only
    // case the setup screen it was written for can occur in.
    ReceiverStrings.adopt(LastLanguage.read(), window.navigator.language)
    window.addEventListener("load", {
        GlobalScope.launch {
            runCatching {
                val stored = localStorage.getItem(SERVER_URL_KEY)?.trim()?.takeIf { it.isNotBlank() }
                val serverUrl = stored ?: runServerSetup()
                Screen(serverUrl).start()
            }.onFailure { console.error("Ravilo screen failed to start: ${it.message}") }
        }
    })
}
