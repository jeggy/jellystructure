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
import dev.jellystructure.shared.tv.TvSegmentMarkers
import dev.jellystructure.shared.tv.ReceiverSubPick
import dev.jellystructure.shared.tv.VttCue
import dev.jellystructure.shared.tv.activeCueText
import dev.jellystructure.shared.tv.parseVtt
import dev.jellystructure.shared.tv.receiverSelectedAudio
import dev.jellystructure.shared.tv.receiverSelectedSub
import dev.jellystructure.shared.tv.receiverSubPick
import dev.jellystructure.shared.tv.receiverSubtitles
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
import org.w3c.dom.HTMLImageElement
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
/** R264 — the next-up card's countdown, and when it appears on a title with no credits marker. */
private const val NEXT_UP_SECS = 10
private const val NEXT_UP_TAIL_MS = 20_000L
/** R264 — rows the TV picker shows at once; the window follows the focus. */
private const val PICKER_ROWS = 9

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
    // R303 (FR-R303-2/7) — from the play push, never fetched: the film's or SERIES' logo + ink, the series' name.
    private var seriesName: String? = null
    private var logoUrl: String? = null
    private var logoInk: String? = null
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
    // R285 (FR-R285-3) — the cues of the text subtitle being drawn (empty = none), and a generation
    // counter so a slow VTT fetch that finishes after the viewer picked something else is discarded.
    private val http = HttpClient(Js)   // subtitle files only; the API has its own client
    private var cues: List<VttCue> = emptyList()
    private var cueGeneration = 0
    private var shownCue: String? = null
    // R285 (FR-R285-2) — a restream reopens the stream, and AVPlay/<video> start playing on open; a
    // viewer who changed a track while paused stays paused.
    private var pauseOnNextPlaying = false
    private var overlayJob: Job? = null
    // R264 (FR-R264-3) — what the push said about the title: its intro/credits and what plays next.
    private var segments: TvSegmentMarkers? = null
    private var nextId: String? = null
    private var nextTitle: String? = null
    private var nextKicker: String? = null
    private var skipShown = false
    private var nextUpShown = false
    private var nextUpCancelled = false
    private var nextUpDeadline = 0L
    // The picker: open, which tab, level 2's group (null = level 1), the focused row and the window's top.
    private var pickerOpen = false
    private var pickerTab = PickerTab.SUBTITLES
    private var pickerGroup: Int? = null
    private var pickerFocus = 0
    private var pickerTop = 0
    private var loadGeneration = 0

    // ── screens ──
    private fun el(id: String) = document.getElementById(id) as HTMLElement
    private fun show(vararg on: String) {
        for (id in listOf("idle", "loading", "buffering", "noserver", "busy", "failed")) el(id).classList.toggle("on", id in on)
    }

    fun start() {
        registerTvKeys()
        window.addEventListener("keydown", { e -> onKey(e as KeyboardEvent) })
        // R269 (FR-R269-7) — releasing Back before three seconds cancels the hold; a plain tap must
        // never reopen setup.
        window.addEventListener("keyup", { e -> if (isBack(e as KeyboardEvent)) backHeldSince = null })
        backend.setListener(object : MediaBackendListener {
            override fun onBufferingStart() { buffering = true; if (loaded) show("buffering"); sendStatus() }
            override fun onBufferingComplete() { buffering = false; if (loaded) show(); sendStatus() }
            override fun onPlaying() {
                if (pauseOnNextPlaying) { pauseOnNextPlaying = false; backend.pause(); return }
                playing = true; buffering = false; loaded = true; show(); sendStatus()
            }
            override fun onPaused() { playing = false; flashOverlay(); sendStatus() }
            override fun onStreamCompleted() { onFinished() }
            override fun onError(detail: String) { console.error("Ravilo screen: backend error: $detail"); failPlay(null) }
        })
        idle()
        // R279 — idle already drew in the remembered language (ReceiverStrings seeds itself from it),
        // so the pairing screen is never English in a Danish house just because nothing has loaded
        // yet. If this set is already enrolled, the active viewer's own setting supersedes it.
        if (activeUserId != null) GlobalScope.launch { adoptLanguageOfActiveUser() }
        GlobalScope.launch { pairingLoop() }
        GlobalScope.launch { eventLoop() }
        GlobalScope.launch { tickLoop() }
        GlobalScope.launch { cueLoop() }
        GlobalScope.launch { segmentLoop() }
    }

    private fun idle() {
        loaded = false; playing = false; buffering = false; itemId = null; title = null; kicker = null; artUrl = null; ticket = null
        seriesName = null; logoUrl = null; logoInk = null   // R303
        segments = null; nextId = null; nextTitle = null; nextKicker = null   // R264
        hideSkip(); hideNextUp(); closePicker(); el("overlay").classList.remove("on")
        showSubtitle(-1)
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
        // R303 (FR-R303-2/5/7) — the push carries the S·E kicker, the series' name and the logo; the
        // receiver used to show a bare title (or none: an episode's title was not even resolved).
        kicker = env.kicker
        seriesName = env.seriesName
        logoUrl = env.logoUrl
        logoInk = env.logoInk
        // R264 (FR-R264-3) — from the push: Skip Intro's window, the credits, and the next episode.
        segments = env.segments
        nextId = env.nextId; nextTitle = env.nextTitle; nextKicker = env.nextKicker
        nextUpCancelled = false
        hideSkip(); hideNextUp(); closePicker(); el("overlay").classList.remove("on")
        loaded = false
        el("loading-title").textContent = env.title.orEmpty()
        el("loading-kicker").textContent = env.kicker.orEmpty()
        el("loading-label").textContent = ReceiverStrings.t("loading")
        show("loading")
        // R237 (FR-R237-5) — one "Still trying…" line if the start runs past ~5 s, nothing else.
        val generation = ++loadGeneration
        GlobalScope.launch {
            delay(5_000)
            if (generation == loadGeneration && !loaded) el("loading-label").textContent = ReceiverStrings.t("loading.still_trying")
        }
        val t = negotiate(env.jellyfinId) ?: return
        ticket = t
        selectedAudio = receiverSelectedAudio(t)
        // R285 — positions in receiverSubtitles(), the list the remote is shown; this used to index
        // the ticket's raw list, which stops matching the moment a ticket interleaves PGS and text.
        showSubtitle(receiverSubtitles(t).indexOfFirst { it.isDefault && it.url != null })
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
            failPlay(http?.status ?: 0)
            return null
        }
    }

    /**
     * R264 (FR-R264-3) / R237 — one plain sentence for why a title did not play, then back to idle. [status]
     * is the start's HTTP status (0 = the server did not answer; null = the player itself failed): 401 is
     * this set's sign-in, 403 the profile, 404/410 a title gone, 0 the network — R237's own copy for each —
     * and anything else, including a player failure, the receiver's one generic sentence. It used to say
     * *"Can't reach the server"* for every one of them, which is true for exactly one.
     */
    private fun failPlay(status: Int?) {
        val (title, body) = when (status) {
            401 -> "error.play.reauth.title" to "error.play.reauth.body"
            403 -> "error.play.forbidden.title" to "error.play.forbidden.body"
            404, 410 -> "error.play.gone.title" to "receiver.failed_sub"
            0 -> "error.play.unreachable.title" to "error.play.unreachable.body"
            else -> "receiver.failed" to "receiver.failed_sub"
        }
        backend.close()
        hideSkip(); hideNextUp(); closePicker(); el("overlay").classList.remove("on")
        el("failed-t").textContent = ReceiverStrings.t(title)
        el("failed-s").textContent = ReceiverStrings.t(body)
        show("failed")
        loaded = false; playing = false
        sendStatus()
        val generation = loadGeneration
        GlobalScope.launch { delay(10_000); if (generation == loadGeneration && !loaded) idle() }
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
            "skip", "seek_relative" -> {   // R285 — 236's name is `skip {delta_ms}`; see the note on "set_audio" below
                val delta = args?.get("delta_ms")?.jsonPrimitive?.longOrNull ?: return
                backend.seekTo((backend.positionMs() + delta).coerceAtLeast(0))
                flashOverlay()
            }
            // R285 (FR-R285-2) — an HLS stream carries ONE audio track and no picture subtitles, so
            // `backend.selectAudioTrack/selectSubtitleTrack` had nothing to select: both commands were
            // accepted, reported back as applied, and changed nothing. Audio and PGS are a restream;
            // text is drawn here (FR-R285-3).
            //
            // R285 — and the NAMES: phase 236 defines `set_audio {index}`, `set_subtitle {index|null}`,
            // `set_subtitle_size`, `skip {delta_ms}`, and RemoteRoutes forwards exactly those. This
            // `when` matched `audio_track`/`subtitle_track`/`sub_size`/`seek_relative`, which nothing
            // sends, so every one of them fell to `else -> return`: no phone has ever changed a track
            // or a caption size on this app. The old names stay as aliases; they cost nothing.
            "set_audio", "audio_track" -> {
                val idx = args?.get("index")?.jsonPrimitive?.intOrNull ?: return
                applyAudio(idx)
                return
            }
            "set_subtitle", "subtitle_track" -> {
                val idx = args?.get("index")?.jsonPrimitive?.intOrNull ?: -1   // 236: a null index is Off
                if (!applySubtitle(idx)) return
            }
            // R264 (FR-R264-3) — the phone's remote: *Next episode*, the next-up card's two buttons, and
            // Skip Intro. They were accepted by the backend and fell to `else` here: nothing happened.
            "next", "nextup_play" -> { playNext(); return }
            "cancel_next_up", "nextup_cancel" -> cancelNextUp()
            "skip_segment" -> { skipIntro(); return }
            "set_subtitle_size", "sub_size" -> { subSize = args?.get("size")?.jsonPrimitive?.contentOrNull ?: subSize; applySubSize() }
            else -> return
        }
        sendStatus()
    }

    /** R285 (FR-R285-2) — audio is a restream; shared by the phone's command and the TV's own picker. */
    private fun applyAudio(idx: Int) {
        val wanted = ticket?.audio?.getOrNull(idx) ?: return
        if (wanted.index == ticket?.audioStreamIndex) return
        GlobalScope.launch { restream(ticket?.burnedSubtitleIndex ?: -1, wanted.index, thenShow = selectedSub) }
    }

    /** R285 — text is drawn here, a picture subtitle is a burn-in restream. False when nothing changed
     *  now (nothing to do, or a restream that reports its own status when it lands). */
    private fun applySubtitle(idx: Int): Boolean =
        when (val pick = receiverSubPick(ticket, idx)) {
            ReceiverSubPick.Nothing -> false
            is ReceiverSubPick.Burn -> { GlobalScope.launch { restream(pick.streamIndex, ticket?.audioStreamIndex, thenShow = -1) }; false }
            is ReceiverSubPick.Text ->
                if (pick.unburnFirst) { GlobalScope.launch { restream(-1, ticket?.audioStreamIndex, thenShow = idx) }; false }
                else { showSubtitle(idx); true }
        }

    // ── R264 (FR-R264-3): Skip Intro, next-up and auto-advance ──

    /** Every 250 ms while loaded: the intro window decides the Skip pill; the credits (or, with no credits
     *  marker, the last 20 s — and never before a post-credits scene's credits) decide the next-up card,
     *  whose countdown plays the next episode when it runs out (unless the household turned autoplay off). */
    private suspend fun segmentLoop() {
        while (true) {
            delay(250)
            if (!loaded || ticket == null) continue
            val pos = backend.positionMs(); val dur = backend.durationMs()
            val seg = segments
            val introStart = seg?.introStartMs; val introEnd = seg?.introEndMs
            val inIntro = introStart != null && introEnd != null && pos >= introStart && pos < introEnd - 1_000
            if (inIntro && !skipShown && !pickerOpen) showSkip() else if (!inIntro && skipShown) hideSkip()
            if (nextId == null || nextUpCancelled) continue
            val creditsAt = seg?.creditsStartMs?.takeIf { seg.stinger == null }
            val due = when {
                creditsAt != null -> pos >= creditsAt
                dur > 0 -> dur - pos <= NEXT_UP_TAIL_MS
                else -> false
            }
            if (due && !nextUpShown && !pickerOpen) showNextUp()
            if (nextUpShown) {
                val left = ((nextUpDeadline - nowMs()) / 1000).toInt().coerceAtLeast(0)
                if (autoplay()) el("nu-c").textContent = ReceiverStrings.t("receiver.starts_in", left)
                if (autoplay() && nowMs() >= nextUpDeadline) playNext()
            }
        }
    }

    private fun autoplay(): Boolean = config?.autoplayNext != false

    private fun showSkip() {
        skipShown = true
        el("skip").textContent = ReceiverStrings.t("player.skip_intro")
        el("skip").classList.add("on")
    }
    private fun hideSkip() { skipShown = false; el("skip").classList.remove("on") }

    /** FR-R264-4 — from either remote: to the end of the intro, never past it. */
    private fun skipIntro() {
        val end = segments?.introEndMs ?: return
        backend.seekTo(end)
        hideSkip()
        flashOverlay()
        sendStatus()
    }

    private fun showNextUp() {
        nextUpShown = true
        nextUpDeadline = nowMs() + NEXT_UP_SECS * 1000L
        el("nu-k").textContent = ReceiverStrings.t("player.up_next")
        el("nu-t").textContent = listOfNotNull(nextKicker, nextTitle).joinToString(" · ")
        el("nu-c").textContent = if (autoplay()) ReceiverStrings.t("receiver.starts_in", NEXT_UP_SECS) else ReceiverStrings.t("player.next")
        el("nextup").classList.add("on")
        sendStatus()
    }
    private fun hideNextUp() { nextUpShown = false; el("nextup").classList.remove("on") }

    /** Back on the card, or the phone's *cancel*: this title plays to its end and the set goes idle. */
    private fun cancelNextUp() { nextUpCancelled = true; hideNextUp() }

    /**
     * The next episode, through the backend like any other play: this set asks `POST /api/remote/play` to
     * play [nextId] on itself (any device token may; the target is its own), and the push that comes back
     * is the whole of what a play needs — title, logo, segments and the episode after that. The item it
     * leaves is stopped at its position first, so the progress the viewer made is kept.
     */
    private fun playNext() {
        val id = nextId ?: return
        val current = itemId
        val pos = if (loaded) backend.positionMs() else 0L
        hideNextUp(); hideSkip(); nextId = null
        GlobalScope.launch {
            if (current != null) runCatching { api.stopPlayback(current, pos) }
            runCatching { api.remotePlay(deviceId, id, 0) }.onFailure { failPlay(0) }
        }
    }

    // ── R264 (FR-R264-3): the picker, on the TV ──

    private fun openPicker() {
        if (ticket == null) return
        pickerOpen = true
        pickerTab = if (subtitleGroups(ticket).isEmpty() && audioGroups(ticket).size > 1) PickerTab.AUDIO else PickerTab.SUBTITLES
        pickerGroup = null
        pickerFocus = currentRows().indexOfFirst { it.selected }.coerceAtLeast(0)
        pickerTop = 0
        hideSkip()
        el("overlay").classList.remove("on")
        renderPicker()
        el("picker").classList.add("on")
    }

    private fun closePicker() { pickerOpen = false; el("picker").classList.remove("on") }

    private fun currentRows(): List<PickerRow> = pickerRows(
        ticket, pickerTab, pickerGroup, selectedSub,
        t = { ReceiverStrings.t(it) }, tn = { k, n -> ReceiverStrings.t(k, n) },
    )

    private fun renderPicker() {
        val rows = currentRows()
        pickerFocus = pickerFocus.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        if (pickerFocus < pickerTop) pickerTop = pickerFocus
        if (pickerFocus >= pickerTop + PICKER_ROWS) pickerTop = pickerFocus - PICKER_ROWS + 1
        val group = pickerGroup
        el("pk-head").textContent = if (group == null) ReceiverStrings.t("player.audio_subs")
            else groupTitle()
        val tabs = el("pk-tabs")
        tabs.innerHTML = ""
        if (group == null) {
            for ((tab, key) in listOf(PickerTab.SUBTITLES to "player.tab_subtitles", PickerTab.AUDIO to "player.tab_audio")) {
                val b = document.createElement("b") as HTMLElement
                b.textContent = ReceiverStrings.t(key)
                if (tab == pickerTab) b.className = "on"
                tabs.appendChild(b)
            }
        } else {
            val b = document.createElement("b") as HTMLElement
            b.textContent = "◀ " + ReceiverStrings.t("player.back")
            tabs.appendChild(b)
        }
        val list = el("pk-rows")
        list.innerHTML = ""
        rows.drop(pickerTop).take(PICKER_ROWS).forEachIndexed { i, r ->
            val row = document.createElement("div") as HTMLElement
            row.className = "pkr" + (if (r.selected) " sel" else "") + (if (pickerTop + i == pickerFocus) " foc" else "")
            val left = document.createElement("div") as HTMLElement
            left.className = "l"
            left.appendChild(document.createTextNode(r.label))   // text only: a track title is untrusted input
            r.line?.let { l -> val it = document.createElement("i") as HTMLElement; it.textContent = l; left.appendChild(it) }
            row.appendChild(left)
            val right = document.createElement("span") as HTMLElement
            right.className = if (r.action == PickerAction.Size) "rt pksz" else "rt"
            if (r.action == PickerAction.Size) {
                for (z in listOf("S", "M", "L")) {
                    val b = document.createElement("b") as HTMLElement
                    b.textContent = z
                    if (subSize.equals(z, ignoreCase = true)) b.className = "on"
                    right.appendChild(b)
                }
            } else right.textContent = r.right ?: ""
            row.appendChild(right)
            list.appendChild(row)
        }
    }

    private fun groupTitle(): String {
        val g = pickerGroup ?: return ""
        val groups = if (pickerTab == PickerTab.SUBTITLES) subtitleGroups(ticket) else audioGroups(ticket)
        val first = groups.getOrNull(g)?.versions?.firstOrNull()?.flatIndex ?: return ""
        val (label, lang) = if (pickerTab == PickerTab.SUBTITLES) receiverSubtitles(ticket).getOrNull(first).let { it?.label to it?.language }
            else ticket?.audio?.getOrNull(first).let { it?.label to it?.language }
        return groupName(label, lang, ReceiverStrings.t("player.unnamed"))
    }

    private fun onPickerKey(e: KeyboardEvent) {
        val rows = currentRows()
        val row = rows.getOrNull(pickerFocus)
        when {
            e.key == "ArrowUp" -> pickerFocus = (pickerFocus - 1).coerceAtLeast(0)
            e.key == "ArrowDown" -> pickerFocus = (pickerFocus + 1).coerceAtMost(rows.size - 1)
            (e.key == "ArrowLeft" || e.key == "ArrowRight") && row?.action == PickerAction.Size -> {
                val order = listOf("S", "M", "L")
                val at = order.indexOf(subSize.uppercase()).coerceAtLeast(0)
                subSize = order[(at + if (e.key == "ArrowRight") 1 else -1).coerceIn(0, 2)]
                applySubSize(); sendStatus()
            }
            e.key == "ArrowLeft" && pickerGroup != null -> { pickerGroup = null; pickerFocus = 0; pickerTop = 0 }
            (e.key == "ArrowLeft" || e.key == "ArrowRight") && pickerGroup == null -> {
                pickerTab = if (pickerTab == PickerTab.SUBTITLES) PickerTab.AUDIO else PickerTab.SUBTITLES
                pickerFocus = currentRows().indexOfFirst { it.selected }.coerceAtLeast(0); pickerTop = 0
            }
            e.key == "Enter" -> when (val a = row?.action) {
                is PickerAction.Open -> { pickerGroup = a.group; pickerFocus = currentRows().indexOfFirst { it.selected }.coerceAtLeast(0); pickerTop = 0 }
                is PickerAction.Subtitle -> { if (applySubtitle(a.index)) sendStatus(); closePicker(); return }   // R197: a pick closes it
                is PickerAction.Audio -> { applyAudio(a.index); closePicker(); return }
                PickerAction.Size -> { val order = listOf("S", "M", "L"); subSize = order[(order.indexOf(subSize.uppercase()) + 1) % 3]; applySubSize(); sendStatus() }
                null -> {}
            }
            isBack(e) -> if (pickerGroup != null) { pickerGroup = null; pickerFocus = 0; pickerTop = 0 } else { closePicker(); return }
            else -> return
        }
        renderPicker()
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
        // R264 (FR-R264-3) — the countdown did not run (credits shorter than it, or no marker) but the
        // household plays on: the next episode, as if the card had run out.
        if (nextId != null && !nextUpCancelled && autoplay()) { playNext(); return }
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
        if (isBack(e) && !loaded) {
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
        // R264 (FR-R264-3/4) — the TV remote drives the whole player; every action ends in a status report,
        // so the phone's remote shows a TV-remote pause within one push. Precedence: the picker, then the
        // next-up card, then Skip Intro, then the transport.
        if (pickerOpen) { onPickerKey(e); return }
        if (nextUpShown && (e.key == "Enter" || isBack(e))) { if (e.key == "Enter") playNext() else cancelNextUp(); sendStatus(); return }
        if (skipShown && e.key == "Enter") { skipIntro(); return }
        when {
            e.key == "Enter" || e.key == "MediaPlayPause" -> { if (playing) backend.pause() else backend.play(); flashOverlay() }
            e.key == "MediaPlay" -> backend.play()
            e.key == "MediaPause" -> backend.pause()
            e.key == "MediaStop" -> stopAndIdle()
            e.key == "ArrowLeft" || e.key == "MediaRewind" -> { backend.seekTo((backend.positionMs() - 10_000).coerceAtLeast(0)); flashOverlay() }
            e.key == "ArrowRight" || e.key == "MediaFastForward" -> { backend.seekTo(backend.positionMs() + 30_000); flashOverlay() }
            e.key == "ArrowDown" -> openPicker()
            e.key == "ArrowUp" -> flashOverlay()
            // R112's two steps: Back with the chrome up hides it; Back with nothing up stops (R180 teardown).
            isBack(e) -> if (el("overlay").classList.contains("on")) { overlayJob?.cancel(); el("overlay").classList.remove("on") } else stopAndIdle()
            else -> return
        }
        sendStatus()
    }

    /** A remote's Back arrives under several names: `XF86Back` (key code 10009) on a Tizen set, `Escape`/
     *  `Backspace` on a desktop browser, `Exit` for the Exit key this app registers. */
    private fun isBack(e: KeyboardEvent): Boolean =
        e.key == "Backspace" || e.key == "Escape" || e.key == "Exit" || e.key == "XF86Back" || e.key == "GoBack" || e.keyCode == 10009

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
        paintIdent()
        val frac = if (dur > 0) (pos.toDouble() / dur).coerceIn(0.0, 1.0) else 0.0
        el("ov-fill").style.width = "${(frac * 100).toInt()}%"
        el("ov-time").textContent = "${hms(pos)} / ${hms(dur)}"
        el("ov-pz").textContent = if (playing) "❚❚" else "▶"
        el("ov-hint").textContent = "↓  " + ReceiverStrings.t("player.audio_subs")
        el("overlay").classList.add("on")
        overlayJob?.cancel()
        overlayJob = GlobalScope.launch { delay(3_000); if (playing) el("overlay").classList.remove("on") }
    }

    /**
     * R303 (FR-R303-1/3/4) — what is playing, top right, inside the overlay so it shows and hides with it.
     * A logo when the push carries one (the series' for an episode); a dark-ink logo on a light plate,
     * never recoloured; no logo ⇒ a series shows its name in text, a film shows nothing (its title is in
     * the bottom block). A logo that fails to load falls back the same way as none — the `<img>`'s own
     * onerror, decided here and nowhere else.
     */
    private fun paintIdent() {
        val ident = el("ov-ident")
        val img = el("ov-logo") as HTMLImageElement
        val name = el("ov-showname")
        val logo = logoUrl?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/")) serverUrl.trimEnd('/') + it else it }
        val fallback = seriesName?.takeIf { it.isNotBlank() }
        fun showName() {
            img.removeAttribute("src")
            name.textContent = fallback.orEmpty()
            ident.className = if (fallback != null) "name" else ""
        }
        if (logo == null) { showName(); return }
        img.onerror = { _, _, _, _, _ -> showName(); null }
        if (img.getAttribute("src") != logo) img.src = logo
        img.alt = fallback ?: title.orEmpty()
        name.textContent = ""
        ident.className = "logo" + (if (logoInk == "dark") " plate" else "")
    }

    // ── R285: track changes and drawn subtitles ──

    /**
     * FR-R285-2 — the one restream path: the same item at the current position with [subIndex]
     * burned in (negative = none) and [audioIndex] carried. Keeps play/pause. A failure leaves the
     * running stream alone — a track change that did not happen is better than a player that stopped.
     */
    private suspend fun restream(subIndex: Int, audioIndex: Int?, thenShow: Int) {
        val id = itemId ?: return
        val pos = backend.positionMs()
        val wasPlaying = playing
        val t = runCatching { api.restream(id, subIndex, pos, backend.capabilities(), audioIndex) }.getOrNull() ?: run { sendStatus(); return }
        if (itemId != id) return                       // the viewer moved on while we were negotiating
        ticket = t
        selectedAudio = receiverSelectedAudio(t)
        showSubtitle(if (t.burnedSubtitleIndex != null) -1 else thenShow)   // never draw over a burn-in
        pauseOnNextPlaying = !wasPlaying
        backend.close()
        backend.open(t.hlsUrl ?: "", pos)
        sendStatus()
    }

    /** FR-R285-3 — draw text subtitle [index] of [receiverSubtitles] (anything else = none). */
    private fun showSubtitle(index: Int) {
        val url = receiverSubtitles(ticket).getOrNull(index)?.takeIf { it.deliveryMethod != "encode" }?.url
        selectedSub = if (url != null) index else -1
        cues = emptyList()
        val generation = ++cueGeneration
        applySubSize()
        if (url == null) return
        GlobalScope.launch {
            val body = runCatching { http.get(url).takeIf { it.status.isSuccess() }?.bodyAsText() }.getOrNull() ?: return@launch
            if (generation == cueGeneration) cues = parseVtt(body)
        }
    }

    private fun applySubSize() {
        val layer = el("cues")
        for (size in listOf("s", "m", "l")) layer.classList.toggle("size-$size", subSize.lowercase() == size)
    }

    /** The playback clock drives the cue layer: AVPlay has no cue events, and one loop serves both backends. */
    private suspend fun cueLoop() {
        while (true) {
            delay(200)
            val text = if (loaded && cues.isNotEmpty()) activeCueText(cues, backend.positionMs()) else null
            if (text == shownCue) continue
            shownCue = text
            val layer = el("cues")
            layer.textContent = text.orEmpty()       // textContent, never innerHTML: a subtitle file is untrusted input
            layer.classList.toggle("on", text != null)
        }
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
            // R264 — the next-up card, mirrored to the phone's remote (FR-R245-9's shape).
            hasNext = nextId != null,
            nextUpSecs = if (nextUpShown && autoplay()) ((nextUpDeadline - nowMs()) / 1000).toInt().coerceAtLeast(0) else null,
            nextTitle = nextTitle,
            audioTracks = t?.let { audioTracksOf(it).map(CastTrack::toScreenTrack) } ?: emptyList(),
            subtitleTracks = t?.let { subtitleTracksOf(it, trackIdBase = 100).map(CastTrack::toScreenTrack) } ?: emptyList(),
            selectedAudio = selectedAudio,
            // R285 — while a subtitle is burned in, THAT is the selection (R282's rule, on the wire).
            selectedSub = receiverSelectedSub(t, selectedSub),
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
    // The idle screen is `on` in the static markup (so a set with a server never flashes black); with
    // no server yet it would sit under setup, both "on" at once (seen in the emulator's DOM).
    document.getElementById("idle")?.classList?.remove("on")
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
