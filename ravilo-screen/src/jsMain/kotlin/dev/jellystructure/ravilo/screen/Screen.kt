package dev.jellystructure.ravilo.screen

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.ravilo.receiver.audioTracksOf
import dev.jellystructure.ravilo.receiver.hms
import dev.jellystructure.ravilo.receiver.nowMs
import dev.jellystructure.ravilo.receiver.subtitleTracksOf
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.EventsCatchUp
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.NavigateEnvelope
import dev.jellystructure.shared.tv.NEXT_UP_NO_MARKER_MS
import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.ReconnectBackoff
import dev.jellystructure.shared.tv.PlayItemEnvelope
import dev.jellystructure.shared.tv.PlayerCommandEnvelope
import dev.jellystructure.shared.tv.PlaystateCommandEnvelope
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.RememberedChoice
import dev.jellystructure.shared.tv.SkipMode
import dev.jellystructure.shared.tv.SessionLoadEnvelope
import dev.jellystructure.shared.tv.TrackInput
import dev.jellystructure.shared.tv.rememberPick
import dev.jellystructure.shared.tv.trustedCreditsStartMs
import dev.jellystructure.shared.tv.rememberedAudioIndex
import dev.jellystructure.shared.tv.rememberedSubtitleIndex
import dev.jellystructure.shared.tv.variantSignatureAt
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.ScreenStatus
import dev.jellystructure.shared.tv.ScreenTrack
import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.TicketSubtitles
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import dev.jellystructure.shared.tv.TvSegmentMarkers
import dev.jellystructure.shared.tv.ReceiverSubPick
import dev.jellystructure.shared.tv.VttCue
import dev.jellystructure.shared.tv.activeCueText
import dev.jellystructure.shared.tv.parseVtt
import dev.jellystructure.shared.tv.playbackFinished
import dev.jellystructure.shared.tv.receiverSelectedAudio
import dev.jellystructure.shared.tv.receiverSelectedSub
import dev.jellystructure.shared.tv.receiverSubPick
import dev.jellystructure.shared.tv.receiverSubtitles
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
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
 * R264's push-driven player and DEB-4656's shell entry point. The player still follows `play_item`
 * events and reports `ended`; [ScreenShell] owns browsing routes and restores them after playback.
 */
private const val DEVICE_ID_KEY = "ravilo.screen.deviceId"
internal const val SERVER_URL_KEY = "ravilo.screen.serverUrl"
internal const val CODE_ONLY_SERVER_KEY = "ravilo.screen.codeOnlyServer"
internal const val SETUP_PREFILL_KEY = "ravilo.screen.serverUrl.prefill"
private const val PROGRESS_EVERY_MS = 10_000L
/** DEB-5694 (FR-63) — the waits before each try: a stop is sent up to three times (1 s, then 2 s apart), a lost play's stream asked for again at once and at 2 s, 4 s and 8 s. */
private val STOP_RETRY_WAITS_MS = longArrayOf(0, 1_000, 2_000)
private val RESTREAM_RETRY_WAITS_MS = longArrayOf(0, 2_000, 4_000, 8_000)
private const val STATUS_EVERY_MS = 5_000L
/** DEB-5696 (FR-60) — how long a start answered 401 shows the reauth line before the profile is removed. */
private const val REAUTH_LINE_MS = 3_000L
/** DEB-4925 (FR-22) — away this long, the shell comes back to a freshly loaded Home. */
private const val LONG_AWAY_MS = 5 * 60_000L
/** R264 — the next-up card's countdown, and when it appears on a title with no credits marker. */
private const val NEXT_UP_SECS = 10

/** DEB-5630 (FR-48) — the card at the credits, by its primary action (Android TV's `CreditsCardMode`). */
private enum class CreditsMode { STINGER, NEXT, SKIP, ASK }   // DEB-5843 (FR-5) — ASK: Are you still watching?
/** DEB-5396 (FR-36) — with no trusted credits marker the next episode's encode starts this long before the end (R381's own lead). */
private const val NEXT_WARM_LEAD_MS = 60_000L
/** DEB-5652 (FR-54) — one rail card and its gap (400 px + 32 px). */
private const val RAIL_CARD_STEP = 432
/** R264 — rows the TV picker shows at once; the window follows the focus. */
private const val PICKER_ROWS = 9

private class Screen(private val serverUrl: String) {
    private val backend = detectMediaBackend(serverUrl)
    private val deviceId = loadOrCreateDeviceId()
    private val profiles = ScreenProfileStore()
    private val legacyTokens = profiles.takeLegacyTokens()
    private val tokens = profiles.profiles().associate { it.userId to it.token }.toMutableMap()
    private var activeUserId: String? = profiles.profiles().singleOrNull()?.userId
    private val api = TvApiClient(
        client = HttpClient(Js) {
            install(WebSockets)
            // DEB-5685 FR-58: Android TV's bound (RaviloRootActuals.kt). TvApiClient's events socket and long polls set their own
            // infinite timeout { }, so only an ordinary request is cut, and each page's failure path takes it.
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000L
                requestTimeoutMillis = 10_000L
                socketTimeoutMillis = 10_000L
            }
        },
        baseUrl = serverUrl,
        deviceToken = { activeUserId?.let(tokens::get) },
        platform = "tizen",
    )
    /** DEB-5024 (FR-25) — what each item went through, posted through the shared client's QoE call (it never throws). */
    /**
     * DEB-5364 (DEB-4541 FR-1) — the newest QoE post in flight. A restream a stall asks for waits for it ([afterReport]): FR-27 and FR-32
     * say the report goes first, and the server reads the device's record (the stall's hold) when it negotiates the restream.
     */
    private var lastQoePost: Job? = null

    private val qoe = ScreenQoe(::nowMs, backend::currentBandwidthBps, ::onLowestRung, ::askForLowerRung, backend::availableBitratesBps, ::askAfterFreeze, ::showFrozen) { report -> lastQoePost = GlobalScope.launch { api.postPlaybackQoe(report) } }
    /** DEB-5396 (FR-36) — the next episode's encode, started at the credits (or 60 s before the end) and dropped by anything but its own start. */
    private val nextWarm = ScreenPrewarm(api, backend::capabilities, ::rememberedAudioFor)
    private val shell = ScreenShell(api, serverUrl, ::reopenServerSetup, onProfileMenu = { openProfileMenu() }, onSignIn = ::signInAgain, userId = { activeUserId }, onPlay = ::playFromTv,
        playback = ScreenPlaybackSwitches(api, { config }, { config = it }),
        prewarmCapabilities = backend::capabilities, prewarmRemembered = ::rememberedAudioFor)

    private val signIn = ScreenSignIn(api, deviceId, deviceName(), ::onPaired, onBack = { showPicker() }, onChangeServer = ::reopenServerSetup)
    private val profileUi = ScreenProfiles(profiles, ::selectProfile, ::showSignIn, ::showPicker, ::signOut,
        ::openSettings, ::openMyList)
    private var eventsJob: Job? = null
    private var config: RaviloConfig? = null
    private var itemId: String? = null
    private var title: String? = null
    private var kicker: String? = null
    private var artUrl: String? = null
    // R303 (FR-R303-2/7) — from the play push, never fetched: the film's or SERIES' logo + ink, the series' name.
    private var seriesName: String? = null
    // DEB-4932 (FR-23) — the series this start belongs to, the key of the remembered-track tier; null for a film and for a phone's push.
    private var seriesId: String? = null
    private var logoUrl: String? = null
    private var logoInk: String? = null
    private var loaded = false
    private var playing = false
    private var buffering = false
    private var lastProgressAt = 0L
    private var lastStatusAt = 0L
    private var busySinceMs: Long? = null
    /** DEB-5394 (FR-37) — the last successful `startPlayback` round trip, handed to the QoE when its item begins. */
    private var startRequestMs = 0L
    private var selectedAudio = 0
    /** DEB-5503 (FR-44) — the audio of a direct play that is not the file's default, to be selected in the file at the first PLAYING (null = nothing to select). */
    private var pendingFileAudio: Int? = null
    private var selectedSub = -1
    private var subSize = "M"
    private var ticket: StreamTicket? = null
    // R285 (FR-R285-3) — the cues of the text subtitle being drawn (empty = none), and a generation
    // counter so a slow VTT fetch that finishes after the viewer picked something else is discarded.
    private var subtitleJob: Job? = null   // DEB-4692 — the fetch of the subtitle being drawn
    private var cues: List<VttCue> = emptyList()
    private var cueGeneration = 0
    private var shownCue: String? = null
    // R285 (FR-R285-2) — a restream reopens the stream, and AVPlay/<video> start playing on open; a
    // viewer who changed a track while paused stays paused.
    private var pauseOnNextPlaying = false
    // DEB-5025 (FR-28, FR-29) — Android TV's control bar: up or hidden, the control focused (it survives a hide), a scrub in
    // progress (null = none), the control the Skip Intro pill was reached from, and when the last key woke it.
    private var overlayJob: Job? = null
    private var chromeUp = false
    private var chromeFocus = ChromeFocus.PLAY
    private var scrubMs: Long? = null
    private var skipReturn: ChromeFocus? = null
    private var chromeWakeAt = 0L
    // R264 (FR-R264-3) — what the push said about the title: its intro/credits and what plays next.
    private var segments: TvSegmentMarkers? = null
    private var nextId: String? = null
    private var nextTitle: String? = null
    private var nextKicker: String? = null
    private var skipShown = false
    /** DEB-5630 (FR-47) — Auto's countdown ends here; Back on the pill latches [skipIntroCancelled] for this item; [skipIntroDone] holds until the playhead leaves the intro (a seek takes a tick to show). */
    private var skipDeadline = 0L
    private var skipIntroCancelled = false
    private var skipIntroDone = false
    /** DEB-5630 (FR-48) — the card at the credits (it is [nextUpShown]'s card): which primary action it offers, and whether Watch credits has the focus. */
    private var creditsMode = CreditsMode.NEXT
    // DEB-5843 (FR-1, FR-5) — "Are you still watching?": the shared rule on this TV-class player's clock. A start that is not
    // the countdown's own advance is a hand-start (the TV's Play, a rail pick, a phone's push or hand-over) and so is input.
    private val stillWatching = dev.jellystructure.shared.tv.StillWatching(tvClass = true)
    private var countdownAdvanceTo: String? = null
    private var askedAt = 0L
    private var cardOnStay = false
    /** Skip to scene was taken: the scene's card does not rise again for this item. */
    private var stingerSkipped = false
    private var nextUpShown = false
    private var nextUpCancelled = false
    private var nextUpDeadline = 0L
    /** R384 (FR-R384-4) — the title played to its end while the countdown runs on over its last frame. */
    private var finishedUnderNextUp = false
    // The picker: open, which tab, level 2's group (null = level 1), the focused row and the window's top.
    private var pickerOpen = false
    private var pickerTab = PickerTab.SUBTITLES
    private var pickerGroup: Int? = null
    private var pickerFocus = 0
    private var pickerTop = 0
    private var loadGeneration = 0
    /** DEB-5081 (DEB-5078, FR-33) — the start in flight, so a failure before its first picture can ask once more. */
    private class StartAttempt(val env: PlayItemEnvelope, val generation: Int, val startOver: Boolean, val startAt: Long, val asked: Boolean)
    private var attempt: StartAttempt? = null
    /** FR-33 — the failed attempt is closed and the second ask is on its way: a late error of the attempt just closed is not a new failure. */
    private var askingAgain = false
    /** DEB-5694 (FR-63) — a play that lost its network after its first picture is being asked for again at [recoveredAt]; the position of the stream asked for last, null until one was. */
    private var recovering = false
    private var recoveredAt: Long? = null
    // DEB-4661 (FR-9) — a start made with this TV's remote (the Play button, or the next-up card's `remotePlay` on this
    // very device), so a refusal never says *from your phone*. [tvNextItem] is the item the TV asked its own backend for.
    private var tvNextItem: String? = null
    private var startedFromTv = false
    // DEB-4865 — the episodes the title's page said follow the one playing (the first is [nextId]); empty for a phone's push.
    private var following: List<ScreenFollowing> = emptyList()
    // DEB-5652 (DEB-5640, FR-54) — the series' seasons the page handed over (empty = no rail: a film, or a phone's push), whether nothing follows
    // an episode, and the rail: open, with the card that has the focus.
    private var railSeasons: List<ScreenRailSeason> = emptyList()
    private var railNewestFirst = false
    private var railOpen = false
    private var railFocus = 0

    // ── screens ──
    private fun el(id: String) = document.getElementById(id) as HTMLElement
    private fun show(vararg on: String) {
        for (id in listOf("idle", "loading", "buffering", "noserver", "busy", "failed")) el(id).classList.toggle("on", id in on)
    }

    fun start() {
        registerTvKeys()
        window.addEventListener("keydown", { e -> onKey(e as KeyboardEvent) })
        document.addEventListener("visibilitychange", { onVisibility(document.asDynamic().hidden == true) })
        backend.setListener(object : MediaBackendListener {
            override fun onBufferingStart() { buffering = true; qoe.bufferingStarted(backend.positionMs()); if (loaded) show("buffering"); sendStatus() }
            override fun onBufferingComplete() { buffering = false; qoe.bufferingCompleted(); if (loaded) show(); sendStatus() }
            override fun onPlaying() {
                if (pauseOnNextPlaying) { pauseOnNextPlaying = false; backend.pause(); return }
                playing = true; buffering = false; loaded = true; qoe.paused(false); show(); sendStatus()
                pendingFileAudio?.let { pendingFileAudio = null; chooseFileAudio(it) }
            }
            override fun onPaused() { playing = false; qoe.paused(true); showChrome(); sendStatus() }
            override fun onPlayTime(ms: Long) = qoe.playTime(ms)
            override fun onPlayerEvent(type: String, data: String?) = qoe.playerEvent(type, data)
            override fun onViewerAction() = qoe.viewerAction()
            override fun onPrepared(openToPreparedMs: Long) = qoe.prepared(openToPreparedMs)
            override fun onStreamCompleted() { onFinished() }
            override fun onError(detail: String) {
                console.error("Ravilo screen: backend error: $detail")
                if (askingAgain || recovering) return
                val failure = HTTP_STATUS.find(detail)?.value?.toIntOrNull() ?: -1
                if (askAgain(failure)) return   // FR-33
                val pos = backend.positionMs().let { p -> recoveredAt?.takeIf { p <= it } ?: p }   // a stream asked for at recoveredAt that has not moved is still there
                if (recoverStream(pos)) return   // FR-63
                // FR-63 — an error after the first picture ends the play with its stop at the position, which the server's watchdog used to close.
                val stopped = itemId?.takeIf { loaded && pastFirstFrame() }
                qoe.end(failure = failure)   // FR-25 — a start that never reached a picture
                failPlay(null)
                if (stopped != null) GlobalScope.launch { stopWithRetry(stopped, pos) }
            }
        })
        idle()
        if (activeUserId != null) {
            profiles.setActive(activeUserId)
            shell.showHome()
        } else if (profiles.profiles().isNotEmpty()) showPicker() else showSignIn()
        if (legacyTokens.isNotEmpty()) GlobalScope.launch {
            for (token in legacyTokens) runCatching { api.unpair(token) }
        }
        // R279 — idle already drew in the set's own language ([ScreenLanguage] is resolved before any screen),
        // so the pairing screen is never English in a Danish house just because nothing has loaded yet.
        if (activeUserId != null) GlobalScope.launch { adoptLanguageOfActiveUser() }
        startEvents()
        GlobalScope.launch { tickLoop() }
        GlobalScope.launch { cueLoop() }
        GlobalScope.launch { segmentLoop() }
        if (backend.watchesPosition) GlobalScope.launch { positionLoop() }
    }

    private fun idle() {
        nextWarm.cancel()   // FR-36 — Back, a stop, Home, hiding the app and a refused start all end here: a viewer who leaves leaves nothing running
        qoe.end()   // FR-25 — the item's final report, whichever way it ended (Back, stop, the stream's end, hiding the app)
        loaded = false; playing = false; buffering = false; itemId = null; title = null; kicker = null; artUrl = null; ticket = null
        attempt = null; askingAgain = false; recovering = false; recoveredAt = null
        startedFromTv = false
        seriesName = null; logoUrl = null; logoInk = null   // R303
        segments = null; nextId = null; nextTitle = null; nextKicker = null; following = emptyList()   // R264
        railSeasons = emptyList(); railNewestFirst = false   // FR-54
        hideSkip(); hideNextUp(); closePicker(); closeRail(); hideChrome(); chromeFocus = ChromeFocus.PLAY
        showSubtitle(-1)
        el("idle-sentence").textContent = ScreenLanguage.t("cast.ready")
        // R269 (FR-R269-6) — a quiet line naming the configured server; the only way a household can see
        // a TV is pointed at a server that has since moved.
        document.getElementById("idle-server")?.textContent = serverUrl.removePrefix("https://").removePrefix("http://")
        show("idle")
        shell.returnFromPlayer()
    }

    // ── DEB-4657 FR-1–FR-3: sign-in and one active viewer ──
    private fun showSignIn() {
        activeUserId = null
        eventsJob?.cancel(); eventsJob = null
        shell.hide()
        profileUi.hide()
        signIn.open(profiles.profiles().isNotEmpty())
    }

    private fun showPicker() {
        activeUserId = null
        eventsJob?.cancel(); eventsJob = null
        signIn.hide()
        shell.hide()
        profileUi.showPicker()
    }

    private fun selectProfile(profile: ScreenProfile) {
        activeUserId = profile.userId
        profiles.setActive(profile.userId)
        profileUi.hide()
        signIn.hide()
        shell.resetHome()
        GlobalScope.launch { adoptLanguageOfActiveUser() }
        startEvents()
    }

    private fun openProfileMenu() = profileUi.showMenu()

    private fun openMyList() {
        profileUi.hide()
        shell.openMyList()
    }

    /** DEB-4663 (FR-11) — the profile menu's Settings. */
    private fun openSettings() {
        profileUi.hide()
        shell.openSettings()
    }

    private fun signOut() {
        val userId = activeUserId ?: return
        profileUi.hide()
        removeProfile(userId)
        if (profiles.profiles().isEmpty()) showSignIn() else showPicker()
    }

    /**
     * DEB-5696 (DEB-5685, FR-60) — this profile's sign-in is gone (a failure's *Sign in*, a start answered 401, the server's `signed_out`):
     * the profile and its token are removed, the events socket stops, and the token is given up on the server as far as it can be.
     */
    private fun removeProfile(userId: String) {
        val token = tokens[userId]
        eventsJob?.cancel(); eventsJob = null
        if (activeUserId == userId) activeUserId = null
        tokens.remove(userId)
        profiles.remove(userId)
        ScreenHomeSnapshot.delete(userId)   // DEB-5697 (FR-61) — a signed-out profile leaves no feed behind
        // A local sign-out must complete even when the server is unreachable.
        if (token != null) GlobalScope.launch { runCatching { api.unpair(token) } }
    }

    /** FR-60 — OK on a failure's *Sign in*: this profile goes, the others stay (FR-3), and FR-2 opens with *Add user*'s path. */
    private fun signInAgain() {
        val userId = activeUserId ?: return
        removeProfile(userId)
        showSignIn()
    }

    /** FR-60 — the events socket told us `signed_out`, or a start came back 401: the profile goes; the picker opens, or FR-2 when it was the last. */
    private fun signedOut(userId: String) {
        if (activeUserId != userId) return
        profileUi.hide()
        removeProfile(userId)
        if (profiles.profiles().isEmpty()) showSignIn() else showPicker()
    }

    private fun onPaired(pr: PairResult) {
        profiles.add(pr)
        tokens[pr.session.userId] = pr.deviceToken
        activeUserId = pr.session.userId
        signIn.hide()
        profileUi.hide()
        shell.resetHome()
        GlobalScope.launch { adoptLanguageOfActiveUser() }
        startEvents()
    }

    private fun startEvents() {
        eventsJob?.cancel()
        eventsJob = activeUserId?.let { GlobalScope.launch { eventLoop() } }
    }

    /**
     * The active viewer's config, fetched whenever the viewer changes (autoplay-next reads it).
     *
     * R279 drew the set in the viewer's own `uiLanguage` here. DEB-4663 (FR-11) gives the Tizen app its own
     * language setting — the TV's, or one the viewer picked on this TV — so the viewer's server-side
     * language no longer moves it.
     */
    private suspend fun adoptLanguageOfActiveUser() {
        val requestedUser = activeUserId ?: return
        val fresh = runCatching { api.getConfig() }.getOrNull() ?: return
        if (activeUserId != requestedUser) return
        val showedProgress = config?.showContinueProgress ?: true
        config = fresh
        if (fresh.showContinueProgress != showedProgress) shell.redrawHome()
    }

    // ── events socket: reconnect with backoff (FR-R264-6) ──
    /**
     * DEB-5695 (DEB-5685, FR-59) — R293's rules, as Android TV's `RaviloApp` keeps them: the wait before the next open is [ReconnectBackoff]'s (1 s
     * doubling to 60 s with jitter, back to 1 s only after a socket was held open 5 minutes), and an open is [EventsCatchUp]'s check — Home is read
     * again when the socket was down more than 30 s, the config when its rev moved. `config_changed` reads the config again too.
     */
    private suspend fun eventLoop() {
        val backoff = ReconnectBackoff()
        val catchUp = EventsCatchUp()
        val userId = activeUserId   // FR-60 — the sign-in this socket belongs to
        var firstOpen = true
        while (true) {
            var openedAt: Long? = null
            runCatching {
                api.connectEvents(
                    onOpen = {
                        openedAt = nowMs()
                        if (loaded) sendStatus()
                        val rev = runCatching { api.getConfigRev() }.getOrNull()
                        val decision = catchUp.onOpen(rev, nowMs())
                        // The first open's config pull is the start-up one (adoptLanguageOfActiveUser), already made.
                        if (decision.refreshConfig && !firstOpen) adoptLanguageOfActiveUser()
                        if (decision.refreshHome) shell.stateChanged()
                        firstOpen = false
                    },
                    onEvent = { adoptLanguageOfActiveUser() },
                    onSignedOut = { if (userId != null) signedOut(userId) },
                    onSignInRemoved = { if (userId != null) signedOut(userId) },   // DEB-5781 (DEB-5777 FR-143-5) — the same removal as signed_out
                    onHomeChanged = { shell.stateChanged() },
                    onPlaystateChanged = { shell.stateChanged() },
                    onPlayItem = { env -> onPlayItem(env) },
                    onPlaystateCommand = { env -> onPlaystateCommand(env) },
                    onPlayerCommand = { env -> onPlayerCommand(env) },
                    onNavigate = { env -> onNavigate(env) },
                    onSessionDirective = { type, text -> onSessionDirective(type, text) },
                    outgoing = outgoing,
                )
            }
            val heldOpenMs = openedAt?.let { nowMs() - it } ?: 0L
            if (openedAt != null) catchUp.onClosed(nowMs())
            delay(backoff.next(heldOpenMs))
        }
    }

    // ── progress (every 10s while playing) + status (on change + every 5s while loaded, FR-236-5) ──
    private suspend fun tickLoop() {
        while (true) {
            delay(1_000)
            if (!loaded) continue
            val now = nowMs()
            if (now - lastStatusAt >= STATUS_EVERY_MS) sendStatus()
            if (playing && !recovering && now - lastProgressAt >= PROGRESS_EVERY_MS) {
                lastProgressAt = now
                val id = itemId ?: continue
                GlobalScope.launch { runCatching { api.reportProgress(id, backend.positionMs(), !playing) } }
            }
        }
    }

    // ── backend → screen ──
    /** Frames this app sends on the events socket: today only `session_load_failed`. */
    private val outgoing = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * DEB-4808 (FR-10) — a phone's *Play on* list starts a session on this TV (R370), and the backend answers it with a
     * `session_load` to the socket, not a `play_item`. A film or an episode starts as a push would (the session joins on the
     * item the TV then reports); a kind this app does not play is refused on the session, never dropped, or the server's
     * watchdog ends it `failed` 10 s later with nothing said (R380, FR-R380-11).
     */
    private suspend fun onSessionDirective(type: String, text: String) {
        if (type != "session_load") return
        val env = runCatching { RaviloWireJson.decodeFromString(SessionLoadEnvelope.serializer(), text) }.getOrNull() ?: return
        val id = env.items.getOrNull(env.index) ?: env.items.firstOrNull() ?: return
        if (env.kind != "film" && env.kind != "episode") {
            outgoing.tryEmit(
                "{\"type\":\"session_load_failed\",\"id\":${JsonPrimitive(env.sessionId)},\"kind\":${JsonPrimitive(env.kind)}}",
            )
            return
        }
        val detail = if (env.kind == "film") withTimeoutOrNull(3_000) { runCatching { api.getMovie(id) }.getOrNull() } else null
        onPlayItem(PlayItemEnvelope(type = "play_item", jellyfinId = id, kind = if (env.kind == "film") "movie" else "episode",
            title = env.title, startPositionMs = env.startMs, logoUrl = detail?.logoUrl, logoInk = detail?.logoInk, segments = detail?.segments))
    }

    private suspend fun onPlayItem(env: PlayItemEnvelope) {
        // The socket belongs to the selected viewer. A queued event from an older socket cannot
        // switch this session to another viewer's token after the picker changes profiles.
        if (env.sessionUserId != null && env.sessionUserId != activeUserId) return
        val fromTv = env.jellyfinId == tvNextItem
        tvNextItem = null
        // DEB-5024 (FR-24) — a start from another app skips the title's page, where the speed test runs: run it here (the server
        // answers 204 when this TV was measured within 24 h; nothing waits on it).
        if (!fromTv) GlobalScope.launch { api.probe() }
        following = emptyList()
        if (!fromTv) railSeasons = emptyList()   // FR-54 — a phone's push has no rail
        // The next-up card's round trip (a start this TV asked for) stays in the series it left; a phone's push has no series id.
        beginPlay(env, fromTv, series = if (fromTv) seriesId else null)
    }

    /** One start, whoever asked: a phone's push (FR-10), the next-up card's round trip, or this TV's own Play (FR-9). */
    private suspend fun beginPlay(env: PlayItemEnvelope, fromTv: Boolean, startOver: Boolean = false, series: String? = null) {
        // FR-36 — the warmed next episode starting is the adoption the warm was for; a start of anything else drops it.
        if (nextWarm.item == env.jellyfinId) nextWarm.release() else nextWarm.cancel()
        // DEB-5843 (FR-1) — a start the countdown did not make is a hand-start: Play, a rail pick, a push or a hand-over.
        if (countdownAdvanceTo == env.jellyfinId) countdownAdvanceTo = null else stillWatching.onInput(nowMs())
        // FR-10 — a phone's push takes over whatever page the viewer is on (the shell keeps it mounted underneath).
        profileUi.hide()
        startedFromTv = fromTv
        itemId = env.jellyfinId
        seriesId = series
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
        nextUpCancelled = false; finishedUnderNextUp = false
        skipIntroCancelled = false; skipIntroDone = false; stingerSkipped = false   // FR-47, FR-48 — a latch lasts one item
        hideSkip(); hideNextUp(); closePicker(); closeRail(); hideChrome(); chromeFocus = ChromeFocus.PLAY   // FR-29 — a start focuses Play
        loaded = false
        el("loading-title").textContent = env.title.orEmpty()
        el("loading-kicker").textContent = env.kicker.orEmpty()
        el("loading-label").textContent = ScreenLanguage.t("loading")
        shell.showPlayer()
        show("loading")
        qoe.end()   // FR-25 — an item still open when another starts (a phone's push over a playing title) ends first
        // R237 (FR-R237-5) — one "Still trying…" line if the start runs past ~5 s, nothing else.
        val generation = ++loadGeneration
        GlobalScope.launch {
            delay(5_000)
            if (generation == loadGeneration && !loaded) el("loading-label").textContent = ScreenLanguage.t("loading.still_trying")
        }
        playAttempt(env, generation, startOver, asked = false, askedAt = null)
    }

    /**
     * One ask for the stream and its opening — [beginPlay]'s second half, so FR-33's second ask runs the same code under the same
     * loading view. [asked] is that second ask; [askedAt] the position the first one started at (the server's answer for the item
     * could differ by then), null for a start that leaves it to the server.
     */
    private suspend fun playAttempt(env: PlayItemEnvelope, generation: Int, startOver: Boolean, asked: Boolean, askedAt: Long?) {
        val negotiated = negotiate(env.jellyfinId, generation, startOver, askedAt) ?: return
        if (generation != loadGeneration) {
            // Back (or a newer play) came in while the start was being negotiated: the server opened a session for it.
            GlobalScope.launch { runCatching { api.stopPlayback(env.jellyfinId, 0) } }
            return
        }
        // FR-23 — what this profile last chose, applied before the stream opens (a restream here costs no reopen).
        val startAt = env.startPositionMs.takeIf { it > 0 } ?: negotiated.startPositionMs
        attempt = StartAttempt(env, generation, startOver, startAt, asked)
        askingAgain = false
        val remembered = withRemembered(env.jellyfinId, negotiated, startAt)
        if (generation != loadGeneration) {
            GlobalScope.launch { runCatching { api.stopPlayback(env.jellyfinId, 0) } }
            return
        }
        val t = remembered.ticket
        ticket = t
        selectedAudio = currentAudioPos(t)   // DEB-5917 — the track that plays until a select holds; a remembered in-file pick is made at the first PLAYING
        pendingFileAudio = remembered.fileAudio
        // R285 — positions in receiverSubtitles(), the list the remote is shown; this used to index
        // the ticket's raw list, which stops matching the moment a ticket interleaves PGS and text.
        showSubtitle(if (t.burnedSubtitleIndex != null) -1 else remembered.subtitle)
        loaded = true
        playing = false
        lastProgressAt = nowMs()
        qoe.beginItem(env.jellyfinId, t.startVariantBps, t.directPlay, backend.capabilities().linkKind, streamBps = t.streamBps.takeIf { t.directPlay })
        qoe.startRequested(startRequestMs)   // DEB-5394 (FR-37)
        val opened = runCatching { backend.open(t.hlsUrl ?: "", env.startPositionMs.takeIf { it > 0 } ?: t.startPositionMs, t.startVariantBps, t.directPlay) }
        if (opened.isFailure) {
            // DEB-4874 — a start that never opened has played nothing: the server's session for it is closed at 0, never at the
            // length of whatever was open before (which marked the episode watched), and the viewer is told once.
            console.error("Ravilo screen: open failed: ${opened.exceptionOrNull()}")
            if (askAgain(-1)) return   // FR-33
            qoe.end(failure = -1)
            GlobalScope.launch { runCatching { api.stopPlayback(env.jellyfinId, 0) } }
            failPlay(null)
            return
        }
        sendStatus()
    }

    /**
     * DEB-5081 (DEB-5078, FR-33) — a start that failed on our encoder before its first picture is asked once more, out of sight. True when
     * it was: the failed attempt is closed as for any failure (FR-25's report, the server's session stopped at 0 — DEB-4874) and the
     * loading view stays, with no error, no shutter and no second *Still trying…*. False — and today's error — for a ticket that is not
     * ours, a failure after the first frame, a second failure, and a start Back or a newer start has already replaced.
     */
    private fun askAgain(failure: Int): Boolean {
        val a = attempt ?: return false
        if (a.asked || a.generation != loadGeneration || ticket?.encoder != "ours" || !qoe.active || pastFirstFrame()) return false
        askingAgain = true
        loaded = false   // Back during the second ask is a Back on a start that has not begun (cancelStart)
        qoe.end(failure = failure)
        backend.close()
        GlobalScope.launch {
            runCatching { api.stopPlayback(a.env.jellyfinId, 0) }
            if (a.generation == loadGeneration) playAttempt(a.env, a.generation, a.startOver, asked = true, askedAt = a.startAt.takeIf { it > 0 })
        }
        return true
    }

    /** FR-23 — the tier a start asks the server to honour: the series' choice when it has an audio language, else the global one. */
    private fun rememberedAudio(): RememberedChoice? = rememberedAudioFor(seriesId)

    private fun rememberedAudioFor(series: String?): RememberedChoice? {
        val user = activeUserId ?: return null
        return series?.let { ScreenTrackMemory.series(user, it) }?.takeIf { it.audioLanguage != null }
            ?: ScreenTrackMemory.global(user)?.takeIf { it.audioLanguage != null }
    }

    /** The ticket's own lists as the shared rule reads them: positions in [StreamTicket.audio] and in [receiverSubtitles]. */
    private fun audioInputs(t: StreamTicket) = t.audio.mapIndexed { i, a -> TrackInput(i, a.language, a.label, isDefault = a.isDefault) }
    private fun subtitleInputs(t: StreamTicket) = receiverSubtitles(t).mapIndexed { i, s -> TrackInput(i, s.language, s.label, s.forced, s.isDefault) }

    /** What a start ends up with once the profile's remembered choice is applied: the ticket to play and the text subtitle to draw (-1 = none). */
    private class RememberedStart(val ticket: StreamTicket, val subtitle: Int, val fileAudio: Int? = null)

    /** DEB-5503 (FR-44) — the audio that plays on a ticket before anyone picks: a transcode's own, or the file's default on a direct play. */
    private fun currentAudioPos(t: StreamTicket): Int = if (t.directPlay) defaultAudioPos(t) else receiverSelectedAudio(t)

    private fun defaultAudioPos(t: StreamTicket): Int = t.audio.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0

    /** DEB-5503 (FR-44) — audio [pos] of a direct play can be selected in the file: it is one of the container's tracks, in a codec this app declares. */
    private fun fileAudioSelectable(t: StreamTicket, pos: Int): Boolean {
        val a = t.audio.getOrNull(pos) ?: return false
        return t.directPlay && !a.external && a.codec?.lowercase() in backend.capabilities().audioCodecs
    }

    /**
     * DEB-5503 (FR-44) — make audio [pos] the one that plays in the file. DEB-5917: [onResult] is true only when the select held (the player's
     * current stream says so); false when it is impossible, threw, or did not take, and the caller restreams to our ladder.
     */
    private fun selectFileAudio(t: StreamTicket, pos: Int, onResult: (Boolean) -> Unit) {
        val inContainer = t.audio.filter { !it.external }
        if (!fileAudioSelectable(t, pos)) { onResult(false); return }
        backend.selectFileAudio(inContainer.indexOf(t.audio[pos]), inContainer.size, onResult)
    }

    /** DEB-5917 (FR-44) — a restream for audio on a direct play asks as a device that plays HLS only, so the answer is our ladder and never the same file again. */
    private fun ladderCapabilities() = backend.capabilities().copy(hlsOnly = true)

    /** DEB-5503 (FR-44) — Jellyfin's stream index of the audio a restream of this ticket must keep: a transcode's own, or the pick made in a direct play's file. */
    private fun carriedAudio(): Int? {
        val t = ticket ?: return null
        return t.audioStreamIndex ?: t.audio.getOrNull(selectedAudio)?.index?.takeIf { t.directPlay && selectedAudio != defaultAudioPos(t) }
    }

    /**
     * DEB-4932 (FR-23) — apply this profile's remembered audio and subtitle to a fresh [t]. Audio went out with the start, so the
     * ticket normally already carries it; it is switched here only when the series language is not in this file but the global
     * one is. A subtitle is resolved with R181's tier order ([rememberedSubtitleIndex]): *Off* shows none, text is drawn, a
     * picture subtitle is burned in. Anything that needs a different stream is one restream, made before the stream opens. With
     * nothing remembered (or nothing matching), the start is exactly what it was: the file's default.
     */
    private suspend fun withRemembered(id: String, t: StreamTicket, startAt: Long): RememberedStart {
        val default = receiverSubtitles(t).indexOfFirst { it.isDefault && it.url != null }
        val user = activeUserId ?: return RememberedStart(t, default)
        val series = seriesId?.let { ScreenTrackMemory.series(user, it) }
        val global = ScreenTrackMemory.global(user)
        val audioPos = (rememberedAudioIndex(series, audioInputs(t)) ?: rememberedAudioIndex(global, audioInputs(t)))
            ?.takeIf { it != currentAudioPos(t) && t.audio.size > 1 }   // DEB-5917 — a file with one audio track never restreams for audio
        val subPos = rememberedSubtitleIndex(series, subtitleInputs(t)) ?: rememberedSubtitleIndex(global, subtitleInputs(t))
        val pick = subPos?.let { receiverSubPick(t, it) }
        val burn = (pick as? ReceiverSubPick.Burn)?.streamIndex
        // A text pick (or Off) with a burn-in already on the stream needs the burn-in cut out; so does anything else that asks for a restream.
        val unburn = subPos != null && burn == null && t.burnedSubtitleIndex != null
        val text = when {
            subPos == null -> default
            pick is ReceiverSubPick.Text && pick.url != null -> subPos
            else -> -1
        }
        if (audioPos == null && burn == null && !unburn) return RememberedStart(t, text)
        // DEB-5503 (FR-44) — a direct play's audio is chosen inside the file at the first PLAYING; a burn-in (or an audio the player cannot take) is a restream, as before.
        if (audioPos != null && burn == null && !unburn && fileAudioSelectable(t, audioPos)) return RememberedStart(t, text, fileAudio = audioPos)
        val audioStream = audioPos?.let { t.audio[it].index } ?: t.audioStreamIndex
        val subStream = burn ?: if (unburn) -1 else (t.burnedSubtitleIndex ?: -1)
        // DEB-5917 (FR-44) — an audio the player cannot take in the file asks as HLS only: the answer is our ladder with that audio.
        val caps = if (t.directPlay && audioPos != null) ladderCapabilities() else backend.capabilities()
        val next = runCatching { api.restream(id, subStream, startAt, caps, audioStream) }.getOrNull()
            ?: return RememberedStart(t, text)   // the restream did not come: the stream the start gave is still a good one
        return RememberedStart(next, text)
    }

    /** Busy (Phase 182's 503 + Retry-After) waits and retries; unreachable shows the no-server screen —
     *  same shape as [dev.jellystructure.ravilo.cast.Receiver]'s own negotiate(). */
    private suspend fun negotiate(itemId: String, generation: Int, startOver: Boolean = false, askedAt: Long? = null): StreamTicket? {
        val caps = backend.capabilities()
        while (true) {
            // FR-23 — the remembered audio rides the start's own fields, so the stream is cut in that language with no second request.
            val audio = rememberedAudio()
            val requestedAt = nowMs()
            val r = runCatching {
                api.startPlayback(itemId, caps, startPositionMs = if (startOver) 0L else askedAt, audioLanguage = audio?.audioLanguage, audioVariant = audio?.audioVariant, startOver = startOver)
            }
            if (generation != loadGeneration) return r.getOrNull()
            val e = r.exceptionOrNull() ?: run { busySinceMs = null; startRequestMs = nowMs() - requestedAt; return r.getOrNull() }
            val http = e as? TvApiError.Http
            if (http != null && http.status == 503) {
                val wait = http.retryAfterSeconds ?: 5
                if (busySinceMs == null) busySinceMs = nowMs()
                el("busy-t").textContent = ScreenLanguage.t("srv.busy")
                el("busy-s").textContent = ScreenLanguage.t("srv.busy_sub")
                show("busy")
                repeat(wait) {
                    if (generation != loadGeneration) return null
                    el("busy-wait").textContent = ScreenLanguage.t("cast.waiting", ((nowMs() - (busySinceMs ?: nowMs())) / 1000).toInt())
                    delay(1_000)
                }
                continue
            }
            failPlay(http?.status ?: 0)
            if (http != null && http.status == 401) {
                // FR-60 — the reauth line stays for 3 s, then this profile goes and the picker (or FR-2, for the last) opens.
                val userId = activeUserId
                if (userId != null) GlobalScope.launch {
                    delay(REAUTH_LINE_MS)
                    if (activeUserId != userId) return@launch
                    signedOut(userId)
                    idle()
                }
            }
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
            404, 410 -> "error.play.gone.title" to retryKey()
            0 -> "error.play.unreachable.title" to "error.play.unreachable.body"
            else -> "receiver.failed" to retryKey()
        }
        backend.close()
        nextWarm.cancel()
        hideSkip(); hideNextUp(); closePicker(); closeRail(); hideChrome()
        el("failed-t").textContent = ScreenLanguage.t(title)
        el("failed-s").textContent = ScreenLanguage.t(body)
        show("failed")
        loaded = false; playing = false
        sendStatus()
        val generation = loadGeneration
        GlobalScope.launch { delay(10_000); if (generation == loadGeneration && !loaded) idle() }
    }

    /** FR-9 — a start the viewer made with this TV's remote is retried with this TV's remote. */
    private fun retryKey() = if (startedFromTv) "receiver.failed_sub_tv" else "receiver.failed_sub"

    /**
     * DEB-4661 (FR-9) — Play on the TV. The TV negotiates the start itself (`POST /api/remote/play` is refused to a Tizen 5.0
     * widget, DEB-4719) and resumes from the server's playstate; [ScreenPlayRequest.context] is what a phone's push would have carried.
     */
    private fun playFromTv(request: ScreenPlayRequest) {
        if (loaded || shell.isPlayer()) return
        val generation = ++loadGeneration
        shell.showPlayer()
        el("loading-title").textContent = request.title
        el("loading-kicker").textContent = ""
        el("loading-label").textContent = ScreenLanguage.t("loading")
        show("loading")
        GlobalScope.launch {
            // DEB-4660 — the title's page hands over what it already read (the movie's logo and markers, an episode's series, S·E
            // line and the episode after it), so nothing is fetched twice and nothing is missing from the start.
            request.context?.let { c ->
                following = c.following
                railSeasons = c.seasons; railNewestFirst = c.newestFirst
                beginPlay(PlayItemEnvelope(type = "play_item", jellyfinId = request.id, kind = c.kind, title = request.title, kicker = c.kicker,
                    seriesName = c.seriesName, logoUrl = c.logoUrl, logoInk = c.logoInk, segments = c.segments,
                    nextId = c.nextId, nextTitle = c.nextTitle, nextKicker = c.nextKicker), fromTv = true, startOver = request.startOver, series = c.seriesId)
                return@launch
            }
            // A request with no page behind it reads what a phone's push would have carried. A detail that does not come is a
            // play without Skip Intro and a logo.
            following = emptyList(); railSeasons = emptyList()
            val detail = if (request.kind == MediaKind.MOVIE) withTimeoutOrNull(3_000) { runCatching { api.getMovie(request.id) }.getOrNull() } else null
            if (generation != loadGeneration) return@launch
            beginPlay(PlayItemEnvelope(type = "play_item", jellyfinId = request.id, kind = request.kind.name.lowercase(), title = request.title,
                logoUrl = detail?.logoUrl, logoInk = detail?.logoInk, segments = detail?.segments), fromTv = true)
        }
    }

    /** Back on a start that has not begun (loading, busy, refused): give up on it and return to the page it came from. */
    private fun cancelStart() {
        ++loadGeneration
        backend.close()
        idle()
        sendStatus()
    }

    private fun onPlaystateCommand(env: PlaystateCommandEnvelope) {
        stillWatching.onInput(nowMs())   // DEB-5843 (FR-2) — a person sent it
        if (!loaded) return
        // Bug fix — RemoteRoutes.kt sends this quartet capitalized ("Stop"/"Pause"/"Unpause", Phase 111's
        // original wire shape); every other consumer normalizes it (PlayerScreen.kt's identical `when`
        // calls .lowercase() first) but this one didn't, so stop/pause/unpause silently no-op on every
        // real receiver — found 2026-09-19 building phase 248's cast e2e test, which drives this exact
        // code path for the first time ever.
        when (env.command.lowercase()) {
            "stop" -> { stopAndIdle(); return }
            "pause" -> backend.pause()
            "unpause" -> if (nextUpShown && creditsMode == CreditsMode.ASK) { playNext(); return } else backend.play()   // DEB-5843 (FR-5) — play is Continue watching
            "seek" -> env.seekPositionMs?.let { seekTo(it); showChrome() }
        }
        sendStatus()
    }

    /** Phase 236 (FR-236-3) — everything past stop/pause/unpause/home. Commands this build doesn't
     *  recognise are ignored, per FR-236-11 — the caller sees the truth on this device's next status. */
    private fun onPlayerCommand(env: PlayerCommandEnvelope) {
        stillWatching.onInput(nowMs())   // DEB-5843 (FR-2)
        if (!loaded) return
        val args = env.args
        when (env.command) {
            // Bug fix — the real sender (ravilo-ui's ScreenSender.seekTo()) only ever sends absolute
            // "seek" with position_ms (RemoteRoutes.kt maps it to this same player_command event); no
            // client in this codebase sends "seek_relative" at all. Found 2026-09-19 building phase
            // 248's cast e2e test, which drives this exact real sender/receiver pair for the first time.
            "seek" -> {
                val pos = args?.get("position_ms")?.jsonPrimitive?.longOrNull ?: return
                seekTo(pos.coerceAtLeast(0))
                showChrome()
            }
            "skip", "seek_relative" -> {   // R285 — 236's name is `skip {delta_ms}`; see the note on "set_audio" below
                val delta = args?.get("delta_ms")?.jsonPrimitive?.longOrNull ?: return
                seekTo((backend.positionMs() + delta).coerceAtLeast(0), forward = delta >= 0)
                showChrome()
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
        val t = ticket ?: return
        val wanted = t.audio.getOrNull(idx) ?: return
        rememberAudioPick(idx)
        if (t.audio.size < 2) return   // DEB-5917 — one audio track: nothing to change
        if (t.directPlay) { if (idx != selectedAudio) chooseFileAudio(idx); return }
        if (wanted.index == ticket?.audioStreamIndex) return
        GlobalScope.launch { restream(ticket?.burnedSubtitleIndex ?: -1, wanted.index, thenShow = selectedSub) }
    }

    /**
     * DEB-5503 (FR-44) — audio [idx] of a direct play: in the file when the player takes it, a restream with that audio when it does not.
     * DEB-5917 — the select is checked; a failed or impossible one restreams once, as HLS only. If that restream is answered with the file
     * again, the item plays on with the track it has: [restream] makes no further attempt and the picker marks the track playing.
     */
    private fun chooseFileAudio(idx: Int) {
        val t = ticket ?: return
        val wanted = t.audio.getOrNull(idx) ?: return
        if (idx == selectedAudio || t.audio.size < 2) return   // nothing changes: no select, no restream
        selectFileAudio(t, idx) { held ->
            if (ticket !== t) return@selectFileAudio   // the viewer moved on while the select was being checked
            if (held) { selectedAudio = idx; sendStatus() }
            else GlobalScope.launch { restream(t.burnedSubtitleIndex ?: -1, wanted.index, thenShow = selectedSub, audioHlsOnly = true) }
        }
    }

    /** R285 — text is drawn here, a picture subtitle is a burn-in restream. False when nothing changed
     *  now (nothing to do, or a restream that reports its own status when it lands). */
    private fun applySubtitle(idx: Int): Boolean {
        rememberSubtitlePick(idx)
        return when (val pick = receiverSubPick(ticket, idx)) {
            ReceiverSubPick.Nothing -> false
            is ReceiverSubPick.Burn -> { val audio = carriedAudio(); GlobalScope.launch { restream(pick.streamIndex, audio, thenShow = -1) }; false }
            is ReceiverSubPick.Text ->
                if (pick.unburnFirst) { val audio = carriedAudio(); GlobalScope.launch { restream(-1, audio, thenShow = idx) }; false }
                else { showSubtitle(idx); true }
        }
    }

    /** FR-23 — a pick (the TV's picker or a phone's `set_audio`) writes the series tier and the global one; a start with no series writes the global tier only. */
    private fun rememberAudioPick(idx: Int) {
        val t = ticket ?: return
        val user = activeUserId ?: return
        val language = t.audio.getOrNull(idx)?.language ?: return
        val variant = variantSignatureAt(audioInputs(t), idx)
        ScreenTrackMemory.remember(user, seriesId) { rememberPick(it, newAudioLanguage = language, newAudioVariant = variant) }
    }

    /** FR-23 — *Off* is remembered as Off. A track with no language cannot be matched in another file, so it remembers nothing. */
    private fun rememberSubtitlePick(idx: Int) {
        val t = ticket ?: return
        val user = activeUserId ?: return
        if (idx < 0) { ScreenTrackMemory.remember(user, seriesId) { rememberPick(it, newSubtitlesOff = true) }; return }
        val language = receiverSubtitles(t).getOrNull(idx)?.language ?: return
        val variant = variantSignatureAt(subtitleInputs(t), idx)
        ScreenTrackMemory.remember(user, seriesId) { rememberPick(it, newSubtitleLanguage = language, newSubtitleVariant = variant, newSubtitlesOff = false) }
    }

    // ── R264 (FR-R264-3): Skip Intro, next-up and auto-advance ──

    /** Every 250 ms while loaded: the intro window decides the Skip pill (DEB-5630, FR-47: by the household's `skip_intro`); the credits
     *  decide the card (FR-48: a trusted marker, or with none only a Next Episode card in the last second), whose countdown plays the
     *  next episode when it runs out (unless the household turned autoplay off). */
    private suspend fun segmentLoop() {
        while (true) {
            delay(250)
            if (!loaded || ticket == null) continue
            val pos = backend.positionMs(); val dur = backend.durationMs()
            val seg = segments
            val introStart = seg?.introStartMs; val introEnd = seg?.introEndMs
            val inIntro = introStart != null && introEnd != null && pos >= introStart && pos < introEnd - 1_000
            if (!inIntro) skipIntroDone = false
            val introMode = skipIntroMode()
            val pillWanted = inIntro && introMode != SkipMode.OFF && !skipIntroCancelled && !skipIntroDone
            if (pillWanted && !skipShown && !pickerOpen) showSkip() else if (!pillWanted && skipShown) hideSkip()
            if (skipShown && introMode == SkipMode.AUTO) {
                if (nowMs() >= skipDeadline) skipIntro() else el("skip").textContent = skipLabel()
            }
            if (!nextUpCancelled) {
                // DEB-5396 (FR-36) — the next episode's encode starts at the credits, or 60 s before the end with no trusted marker (earlier
                // than the card, and the same with autoplay off). The loop re-posts it every 30 s.
                // The position never drops it: a seek back does, from its target ([dropWarmForSeek]), and a re-prepare reads 0 for a moment.
                val warmAt = nextWarmAtMs()
                if (warmAt != null && pos >= warmAt) nextId?.let { nextWarm.warm(it, seriesId) }
                if (!nextUpShown && !pickerOpen && cardDue(pos, dur)) showNextUp(creditsModeOf())
            }
            if (nextUpShown && creditsMode == CreditsMode.NEXT) {
                val left = ((nextUpDeadline - nowMs()) / 1000).toInt().coerceAtLeast(0)
                if (autoplay()) el("nu-c").textContent = ScreenLanguage.t("receiver.starts_in", left)
                if (autoplay() && nowMs() >= nextUpDeadline) advanceOrAsk()
            }
            // DEB-5843 (FR-4) — an ask nobody answered for 30 minutes goes where Back goes.
            if (nextUpShown && creditsMode == CreditsMode.ASK &&
                dev.jellystructure.shared.tv.STILL_WATCHING_CLOSE_MS <= nowMs() - askedAt) leaveFromAsk()
        }
    }

    /** DEB-5843 (FR-1, FR-5) — the countdown ran out: the next episode starts by itself, or the shared rule says ask first. */
    private fun advanceOrAsk() {
        if (stillWatching.decide(nowMs())) askStillWatching() else { countdownAdvanceTo = nextId; playNext() }
    }

    /** FR-5 — pause the stream (an item that already ended under the countdown has nothing to pause), keep the card, say the ask. */
    private fun askStillWatching() {
        if (!finishedUnderNextUp) backend.pause()
        askedAt = nowMs()
        showNextUp(CreditsMode.ASK)
    }

    /** FR-5 / FR-4 — Back, *Back*, the phone's cancel, or 30 minutes unanswered: what Back does on this card, which is to leave. */
    private fun leaveFromAsk() {
        stillWatching.onInput(nowMs())
        nextUpCancelled = true
        // R384 — an item that ended under the countdown reads 0 from the player: it is stopped at its length.
        if (finishedUnderNextUp) { finishedUnderNextUp = false; hideNextUp(); endHere() } else skipCredits()
    }

    private fun skipIntroMode(): SkipMode = config?.skipIntro ?: SkipMode.PROMPT
    private fun skipCreditsMode(): SkipMode = config?.skipCredits ?: SkipMode.PROMPT
    private fun skipSecs(): Int = (config?.skipSecs ?: 6).coerceIn(1, 30)

    /** FR-48 — Android TV's `creditsCardMode`: a stinger always wins, else a real next episode, else the plain skip. */
    private fun creditsModeOf(): CreditsMode = when {
        segments?.stinger != null -> CreditsMode.STINGER
        nextId != null -> CreditsMode.NEXT
        else -> CreditsMode.SKIP
    }

    /**
     * FR-48 (R230, R384, DEB-3950) — is the card due? *Skip credits: Off* never raises it from the playhead (the real end does, [onFinished]).
     * At a trusted credits marker it rises for every mode; with none, only a Next Episode card that can advance, in the last second —
     * never *Skip credits* or *Skip to scene*, which would offer a jump past credits that are already over.
     */
    private fun cardDue(pos: Long, dur: Long): Boolean {
        if (skipCreditsMode() == SkipMode.OFF) return false
        val creditsAt = trustedCreditsStartMs(segments?.creditsStartMs, dur)
        if (creditsAt != null) return pos >= creditsAt && !(stingerSkipped && creditsModeOf() == CreditsMode.STINGER)
        return nextId != null && segments?.stinger == null && dur > 0 && dur - pos <= NEXT_UP_NO_MARKER_MS
    }

    private fun autoplay(): Boolean = config?.autoplayNext != false

    /** DEB-5396 (FR-36) — where the next episode's warm starts: the trusted credits marker, else 60 s before the end; null while the length is unknown. */
    private fun nextWarmAtMs(): Long? {
        val seg = segments
        val creditsAt = seg?.creditsStartMs?.takeIf { seg.stinger == null }
        val dur = backend.durationMs()
        return creditsAt?.let { trustedCreditsStartMs(it, dur) } ?: if (dur > 0) dur - NEXT_WARM_LEAD_MS else null
    }

    /**
     * DEB-5394 (FR-36) — a seek to [targetMs]: the warm job is dropped when the seek lands before the trigger, and only when no card shows
     * (the card's countdown starts the next episode, and a dropped job would make that start cold). It is judged from the target alone: the
     * warm is held only from the trigger on, so a seek forward cannot land before it, and a re-prepare or a restream never gets here.
     */
    private fun dropWarmForSeek(targetMs: Long) {
        if (nextWarm.item == null || nextUpShown) return
        val warmAt = nextWarmAtMs() ?: return
        if (targetMs < warmAt) nextWarm.cancel()
    }

    /** Every seek the viewer or the phone asks for ends here; [forward] is a relative seek known to go forward, which never drops the warm job. */
    private fun seekTo(targetMs: Long, forward: Boolean = false) {
        if (!forward) dropWarmForSeek(targetMs)
        backend.seekTo(targetMs)
    }

    private fun showSkip() {
        closeRail()
        skipShown = true
        skipDeadline = nowMs() + skipSecs() * 1000L
        el("skip").textContent = skipLabel()
        el("skip").classList.add("on")
    }

    /** FR-47 — *Auto* counts the seconds left on the pill; *Prompt* is the plain label. */
    private fun skipLabel(): String {
        val label = ScreenLanguage.t("player.skip_intro")
        if (skipIntroMode() != SkipMode.AUTO) return label
        return label + " · " + ((skipDeadline - nowMs() + 999) / 1000).toInt().coerceIn(0, skipSecs())
    }
    private fun hideSkip() {
        skipShown = false
        el("skip").classList.remove("on", "foc")
        if (chromeFocus == ChromeFocus.SKIP) chromeFocus = skipReturn ?: ChromeFocus.PLAY
    }

    /** FR-R264-4 — from either remote: to the end of the intro, never past it. */
    private fun skipIntro() {
        val end = segments?.introEndMs ?: return
        skipIntroDone = true
        backend.seekTo(end)
        hideSkip()
        showChrome()
        sendStatus()
    }

    /** The credits card (FR-48) in [mode]: *Next Episode* is today's card with its countdown; the other two wait for the viewer. */
    private fun showNextUp(mode: CreditsMode) {
        closeRail()
        creditsMode = mode; cardOnStay = false
        nextUpShown = true
        nextUpDeadline = nowMs() + NEXT_UP_SECS * 1000L
        val episode = seriesName != null || kicker != null
        when (mode) {
            CreditsMode.NEXT -> {
                el("nu-k").textContent = ScreenLanguage.t("player.up_next")
                el("nu-t").textContent = listOfNotNull(nextKicker, nextTitle).joinToString(" · ")
                el("nu-c").textContent = if (autoplay()) ScreenLanguage.t("receiver.starts_in", NEXT_UP_SECS) else ScreenLanguage.t("player.next")
                el("nu-p").textContent = ScreenLanguage.t("player.next")
            }
            CreditsMode.STINGER -> {
                el("nu-k").textContent = ScreenLanguage.t("player.credits_kicker")
                el("nu-t").textContent = ScreenLanguage.t("player.stinger_title")
                el("nu-c").textContent = ""
                el("nu-p").textContent = ScreenLanguage.t("player.skip_to_scene")
            }
            CreditsMode.ASK -> {
                el("nu-k").textContent = ScreenLanguage.t("player.still_watching")
                el("nu-t").textContent = listOfNotNull(nextKicker, nextTitle).joinToString(" · ")
                el("nu-c").textContent = ""
                el("nu-p").textContent = ScreenLanguage.t("player.continue_watching")
            }
            CreditsMode.SKIP -> {
                el("nu-k").textContent = ScreenLanguage.t("player.credits_kicker")
                el("nu-t").textContent = ScreenLanguage.t(if (episode) "player.end_of_episode" else "player.end_of_movie")
                el("nu-c").textContent = ""
                el("nu-p").textContent = ScreenLanguage.t("player.skip_credits")
            }
        }
        el("nu-w").textContent = ScreenLanguage.t(if (mode == CreditsMode.ASK) "player.back" else "player.watch_credits")
        renderCardFocus()
        el("nextup").classList.add("on")
        sendStatus()
    }
    private fun renderCardFocus() {
        el("nu-p").classList.toggle("foc", !cardOnStay)
        el("nu-w").classList.toggle("foc", cardOnStay)
    }

    /** FR-48 — the card's keys: Left/Right between the two, OK acts, Back is *Watch credits*. False for a key the card does not use. */
    private fun onCardKey(e: KeyboardEvent): Boolean = when {
        creditsMode == CreditsMode.ASK && (isKey(e, "MediaPlayPause", "XF86PlayBack", 10252) || isKey(e, "MediaPlay", "XF86AudioPlay", 415)) -> { playNext(); true }   // DEB-5843 (FR-5) — the play key continues
        e.key == "Enter" -> { if (cardOnStay) cancelNextUp() else creditsPrimary(); true }
        isBack(e) -> { cancelNextUp(); true }
        e.key == "ArrowLeft" || e.key == "ArrowRight" -> { cardOnStay = e.key == "ArrowRight"; renderCardFocus(); true }
        else -> false
    }

    private fun creditsPrimary() = when (creditsMode) {
        CreditsMode.NEXT -> playNext()
        CreditsMode.STINGER -> skipToScene()
        CreditsMode.SKIP -> skipCredits()
        CreditsMode.ASK -> playNext()   // DEB-5843 (FR-5) — Continue watching
    }

    /** FR-48 — to the scene (R182: its marker, else 2 s before the end of the file, past the long credits roll); the card does not rise again. */
    private fun skipToScene() {
        val pos = backend.positionMs()
        val target = segments?.stinger?.atMs ?: (backend.durationMs() - 2_000L).coerceAtLeast(pos)
        stingerSkipped = true
        hideNextUp()
        seekTo(target, forward = true)
        showChrome()
    }

    /** FR-48 — nothing to seek to: the item ends as the end of the stream does, stopped at its length when R347's rule calls it finished. */
    private fun skipCredits() {
        hideNextUp()
        if (playbackFinished(backend.positionMs(), backend.durationMs(), segments?.creditsStartMs)) endHere() else stopAndIdle()
    }

    private fun hideNextUp() { nextUpShown = false; el("nextup").classList.remove("on") }

    /** Back on the card, or the phone's *cancel*: this title plays to its end and the set goes idle. R384 — after the
     *  end (the countdown ran on over the last frame) that is now. */
    private fun cancelNextUp() {
        if (nextUpShown && creditsMode == CreditsMode.ASK) { leaveFromAsk(); return }   // DEB-5843 (FR-5)
        nextUpCancelled = true; hideNextUp()
        nextWarm.cancel()   // FR-36 — the card's cancel: this title plays to its end and nothing follows it
        if (finishedUnderNextUp) { finishedUnderNextUp = false; endHere() }
    }

    /**
     * The next episode. A start the TV made itself knows the episodes after this one (the title's page handed them over,
     * [following]) and starts the next the way [playFromTv] starts any title: it negotiates for itself, because
     * `POST /api/remote/play` is refused to a Tizen 5.0 widget (DEB-4719, DEB-4865). A start with no such list — a
     * phone's push — asks `POST /api/remote/play` to play [nextId] on this set, and the push that comes back is the whole
     * of what a play needs. The item it leaves is stopped at its position first, so the progress the viewer made is kept.
     */
    private fun playNext() {
        val id = nextId ?: return
        val current = itemId
        // R384 — a title that finished under the countdown is stopped at its length (the player may say 0 once ended).
        val pos = if (finishedUnderNextUp) backend.durationMs() else currentPos()
        val queue = following
        val known = queue.firstOrNull()?.takeIf { it.id == id }
        hideNextUp(); hideSkip(); nextId = null
        if (known != null) {
            startEpisode(current, pos, known, queue.drop(1))
            return
        }
        tvNextItem = id
        GlobalScope.launch {
            if (current != null) stopWithRetry(current, pos)
            runCatching { api.remotePlay(deviceId, id, 0) }.onFailure { tvNextItem = null; startedFromTv = true; failPlay(0) }
        }
    }

    /** The TV starts [episode] itself the way [playNext] starts `nextId`: the item it leaves is stopped at [pos], and the start carries [after] as what follows. */
    private fun startEpisode(current: String?, pos: Long, episode: ScreenFollowing, after: List<ScreenFollowing>) {
        val series = seriesName; val logo = logoUrl; val ink = logoInk; val seriesKey = seriesId
        GlobalScope.launch {
            if (current != null) stopWithRetry(current, pos)
            val next = after.firstOrNull()
            following = after
            beginPlay(PlayItemEnvelope(type = "play_item", jellyfinId = episode.id, kind = "episode", title = episode.title, kicker = episode.kicker,
                seriesName = series, logoUrl = logo, logoInk = ink, segments = episode.segments,
                nextId = next?.id, nextTitle = next?.title, nextKicker = next?.kicker), fromTv = true, series = seriesKey)
        }
    }

    // ── DEB-5652 (DEB-5640, FR-54): Android TV's episode chip and rail ──

    /** The season the playing episode is in, with its cards; null when this play has no rail. */
    private fun railSeason(): ScreenRailSeason? = railSeasons.firstOrNull { sn -> sn.entries.any { it.id == itemId } }

    /** FR-54 — ↓ may open the rail: this play has one, and no picker, card or skip pill is up. The chip is drawn under the same rule. */
    private fun railAllowed() = railSeason() != null && !pickerOpen && !nextUpShown && !skipShown

    private fun openRail() {
        val sn = railSeason() ?: return
        railFocus = sn.entries.indexOfFirst { it.id == itemId }.coerceAtLeast(0)
        railOpen = true
        renderRail(sn)
        el("rail").classList.add("on")
        paintChip()
    }

    private fun closeRail() {
        if (!railOpen) return
        railOpen = false
        el("rail").classList.remove("on")
        paintChip()
    }

    /** FR-54 — ←/→ along the cards, OK on the playing one closes, OK on another starts it, Back or ↑ closes onto the transport row. */
    private fun onRailKey(e: KeyboardEvent): Boolean {
        val sn = railSeason() ?: run { closeRail(); return false }
        when {
            e.key == "ArrowLeft" || e.key == "ArrowRight" -> {
                railFocus = (railFocus + if (e.key == "ArrowLeft") -1 else 1).coerceIn(0, sn.entries.lastIndex)
                renderRail(sn)
            }
            e.key == "Enter" -> {
                val pick = sn.entries.getOrNull(railFocus)
                if (pick == null || pick.id == itemId) closeRail() else chooseEpisode(pick)
            }
            isBack(e) || e.key == "ArrowUp" -> closeRail()
            e.key == "ArrowDown" -> {}   // nothing is below the rail
            else -> return false   // the media keys are the transport's
        }
        showChrome()
        return true
    }

    /**
     * FR-54 — OK on a card that is not the playing one: the current play stops at its position and [pick] starts with the episodes after it
     * in the series' order as what follows (none in a newest-first series or in Specials). FR-36's warm job is dropped by [beginPlay]
     * unless the pick is the warmed `nextId`; FR-23's remembered tracks apply as for any start.
     */
    private fun chooseEpisode(pick: ScreenRailEntry) {
        val current = itemId
        val pos = currentPos()
        val order = railSeasons.filter { it.index >= 1 }.flatMap { it.entries }
        val at = order.indexOfFirst { it.id == pick.id }
        val after = if (railNewestFirst || at < 0) emptyList() else order.drop(at + 1).map { ScreenFollowing(it.id, it.title, it.kicker, it.segments) }
        closeRail(); hideSkip(); hideNextUp(); nextId = null
        startEpisode(current, pos, ScreenFollowing(pick.id, pick.title, pick.kicker, pick.segments), after)
    }

    private fun renderRail(sn: ScreenRailSeason) {
        el("rl-season").textContent = sn.label
        el("rl-hint").textContent = ScreenLanguage.t("player.rail_hint")
        val track = el("rl-track")
        track.textContent = ""
        sn.entries.forEachIndexed { i, ep ->
            val card = document.createElement("div") as HTMLElement
            card.className = "rl-card" + (if (ep.id == itemId) " cur" else "") + (if (i == railFocus) " foc" else "")
            val art = document.createElement("div") as HTMLElement
            art.className = "rl-art"
            ep.stillUrl?.takeIf { it.isNotBlank() }?.let { url ->
                val img = document.createElement("img") as HTMLImageElement
                img.src = if (url.startsWith("/")) serverUrl.trimEnd('/') + url else url
                art.appendChild(img)
                art.appendChild(railNode("div", "rl-scrim"))
            }
            ep.number?.let { art.appendChild(railNode("div", "rl-num", it.toString())) }
            if (ep.id == itemId) art.appendChild(railNode("div", "rl-now", ScreenLanguage.t("player.now_playing")))
            if (ep.watched) art.appendChild(railNode("div", "rl-check", "✓"))
            if (ep.progress > 0f || ep.watched) {
                val bar = railNode("div", "rl-bar" + if (ep.watched) " done" else "")
                val fill = document.createElement("i") as HTMLElement
                fill.style.width = "${if (ep.watched) 100f else ep.progress * 100f}%"
                bar.appendChild(fill)
                art.appendChild(bar)
            }
            card.appendChild(art)
            card.appendChild(railNode("div", "rl-t", ep.number?.let { "$it. ${ep.title}" } ?: ep.title))
            if (ep.runtimeMin > 0) card.appendChild(railNode("div", "rl-d", t("up.runtime_min", ScreenLanguage.lang, ep.runtimeMin)))
            track.appendChild(card)
        }
        // The focused card is kept in view: the track slides so it sits no further right than the third column.
        val offset = (railFocus - 2).coerceAtLeast(0) * RAIL_CARD_STEP
        track.style.transform = "translateX(-${offset}px)"
    }

    private fun railNode(tag: String, className: String, text: String? = null): HTMLElement {
        val n = document.createElement(tag) as HTMLElement
        n.className = className
        if (text != null) n.textContent = text
        return n
    }

    /** The Episodes chip under the transport row: drawn while the play has a rail, hidden (never removed) while anything else is up. */
    private fun paintChip() {
        val has = railSeason() != null
        el("ov-bottom").classList.toggle("has-rail", has)
        el("ov-chip").classList.toggle("hid", !has || !railAllowed() || railOpen)
        if (has) setText("ov-chip-t", ScreenLanguage.t("detail.episodes"))
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
        renderPicker()
        el("picker").classList.add("on")
    }

    private fun closePicker() { pickerOpen = false; el("picker").classList.remove("on") }

    private fun currentRows(): List<PickerRow> = pickerRows(
        ticket, pickerTab, pickerGroup, selectedSub,
        t = { ScreenLanguage.t(it) }, tn = { k, n -> ScreenLanguage.t(k, n) },
        fileAudio = selectedAudio.takeIf { ticket?.directPlay == true },
    )

    private fun renderPicker() {
        val rows = currentRows()
        pickerFocus = pickerFocus.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        if (pickerFocus < pickerTop) pickerTop = pickerFocus
        if (pickerFocus >= pickerTop + PICKER_ROWS) pickerTop = pickerFocus - PICKER_ROWS + 1
        val group = pickerGroup
        el("pk-head").textContent = if (group == null) ScreenLanguage.t("player.audio_subs")
            else groupTitle()
        val tabs = el("pk-tabs")
        tabs.innerHTML = ""
        if (group == null) {
            for ((tab, key) in listOf(PickerTab.SUBTITLES to "player.tab_subtitles", PickerTab.AUDIO to "player.tab_audio")) {
                val b = document.createElement("b") as HTMLElement
                b.textContent = ScreenLanguage.t(key)
                if (tab == pickerTab) b.className = "on"
                tabs.appendChild(b)
            }
        } else {
            val b = document.createElement("b") as HTMLElement
            b.textContent = "◀ " + ScreenLanguage.t("player.back")
            tabs.appendChild(b)
        }
        val list = el("pk-rows")
        list.innerHTML = ""
        rows.drop(pickerTop).take(PICKER_ROWS).forEachIndexed { i, r ->
            val row = document.createElement("div") as HTMLElement
            row.className = "pkr" + (if (r.selected) " sel" else "") + (if (pickerTop + i == pickerFocus) " foc" else "")
            r.flag?.let { row.appendChild(pickerFlag(it)) }   // DEB-4899 — Android TV's picker leads with the language's flag
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

    /** DEB-4899 — a packed `flags/<cc>.png` (the title page's FR-19 flags), never fetched from the server. */
    private fun pickerFlag(cc: String): HTMLElement {
        val img = document.createElement("img") as HTMLElement
        img.className = "pkr-flag"
        img.setAttribute("src", "flags/$cc.png")
        img.setAttribute("alt", "")
        return img
    }

    private fun groupTitle(): String {
        val g = pickerGroup ?: return ""
        val groups = if (pickerTab == PickerTab.SUBTITLES) subtitleGroups(ticket) else audioGroups(ticket)
        val first = groups.getOrNull(g)?.versions?.firstOrNull()?.flatIndex ?: return ""
        val (label, lang) = if (pickerTab == PickerTab.SUBTITLES) receiverSubtitles(ticket).getOrNull(first).let { it?.label to it?.language }
            else ticket?.audio?.getOrNull(first).let { it?.label to it?.language }
        return groupName(label, lang, ScreenLanguage.t("player.unnamed"))
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

    /** FR-63 — the play has shown a picture: QoE's first frame, or a stream asked for again after one (a restream starts QoE's count of its first frame over, and it is not a start). */
    private fun pastFirstFrame() = qoe.firstFrameShown || recoveredAt != null

    /** FR-63 — where the play is: the position it was lost at while its stream is being asked for again (an errored AVPlay answers 0), else AVPlay's. */
    private fun currentPos(): Long = if (!loaded) 0L else if (recovering) recoveredAt ?: 0L else backend.positionMs()

    /** DEB-5694 (FR-63) — Android TV's Phase 180 stop: up to three tries, 1 s then 2 s apart, so a network that comes back a moment late still closes the session at its position. */
    private suspend fun stopWithRetry(id: String, positionMs: Long) {
        for (wait in STOP_RETRY_WAITS_MS) {
            if (wait > 0) delay(wait)
            if (runCatching { api.stopPlayback(id, positionMs) }.isSuccess) return
        }
    }

    /**
     * DEB-5694 (FR-63) — an AVPlay error after the first picture on our encoder's stream is asked for again at [pos], under the buffering view:
     * [restream] once, and when that call fails again at 2 s, 4 s and 8 s. True when it began; false — and the error ends the play — for a stream that is
     * not ours, a direct play, and a stream asked for at [recoveredAt] that errored again before its position moved. If none answers the play ends on R237's copy.
     */
    private fun recoverStream(pos: Long): Boolean {
        val t = ticket ?: return false
        val id = itemId ?: return false
        if (!loaded || !qoe.active || !pastFirstFrame() || t.encoder != "ours" || t.directPlay) return false
        if (recoveredAt?.let { pos <= it } == true) return false
        recovering = true; recoveredAt = pos
        show("buffering")
        val generation = loadGeneration
        val audio = carriedAudio()
        GlobalScope.launch {
            var answered = false
            for (wait in RESTREAM_RETRY_WAITS_MS) {
                if (wait > 0) delay(wait)
                if (generation != loadGeneration || itemId != id) return@launch   // Back or a newer play came in
                if (restream(t.burnedSubtitleIndex ?: -1, audio, thenShow = selectedSub, atMs = pos)) { answered = true; break }
            }
            if (generation != loadGeneration || itemId != id) return@launch
            recovering = false
            if (answered) return@launch
            qoe.end(failure = -1)
            failPlay(null)
            stopWithRetry(id, pos)
        }
        return true
    }

    private fun stopAndIdle() {
        finishedUnderNextUp = false
        val id = itemId
        val pos = currentPos()
        if (id != null) GlobalScope.launch { stopWithRetry(id, pos) }
        backend.close()
        idle()
        sendStatus()
    }

    private fun onFinished() {
        if (!loaded) return   // DEB-4874 — an end that is not of a stream this screen has open stops nothing at its length
        // R264 (FR-R264-3) — the household plays on. R384 (FR-R384-4) — the countdown runs its whole length over the last
        // frame (put up now when the last second was never seen), and playNext() follows it, with the stop at the end;
        // the end used to play the next episode at once, cutting a countdown that started 1 s before it to one tick.
        if (nextId != null && !nextUpCancelled && autoplay()) {
            finishedUnderNextUp = true
            if (nextUpShown && creditsMode == CreditsMode.ASK) return   // DEB-5843 (FR-5) — the ask waits; the end does not restart the countdown
            if (!nextUpShown || creditsMode != CreditsMode.NEXT) showNextUp(CreditsMode.NEXT)   // a stinger's or a film's card has nothing left to offer: the real end is R230's countdown
            return
        }
        endHere()
    }

    /** The title's end with nothing to play after it: stopped at its length, the idle view, *ended* to the phone. */
    private fun endHere() {
        val id = itemId
        val dur = backend.durationMs()
        if (id != null) GlobalScope.launch { stopWithRetry(id, dur) }
        backend.close()
        val endedItem = itemId; val endedTitle = title; val endedArt = artUrl
        idle()
        // FR-R245-9-shaped "ended" report — the phone/backend decide what (if anything) plays next.
        GlobalScope.launch {
            runCatching { api.postScreenStatus(ScreenStatus(itemId = endedItem, title = endedTitle, artUrl = endedArt, ended = true, sessionUserId = activeUserId)) }
        }
    }

    // ── DEB-4925 (FR-22): leaving the app and coming back ──
    private var awaySince: Long? = null

    /** Tizen fires `visibilitychange` when the viewer goes to another app and again on the way back. A second hide or return is a no-op. */
    private fun onVisibility(hidden: Boolean) {
        if (hidden) {
            if (awaySince != null) return
            awaySince = nowMs()
            shell.awayFromApp(true)   // FR-35 — a title's page stops its warm encode
            // AVPlay's plane does not survive the app being sent away: end the stream as Back would, the return is a fresh start.
            if (loaded) stopAndIdle() else if (shell.isPlayer()) cancelStart()
            return
        }
        val since = awaySince ?: return
        awaySince = null
        shell.awayFromApp(false)
        startEvents()
        if (activeUserId != null) GlobalScope.launch { adoptLanguageOfActiveUser() }
        if (nowMs() - since >= LONG_AWAY_MS) shell.refreshAfterAway()
        else shell.refocus()
    }

    // ── remote control: Tizen's registered keys arrive as ordinary keydown events (same names as
    // TizenKeys.REQUIRED) alongside a normal D-pad's KeyboardEvent.key vocabulary. ──
    private fun onKey(e: KeyboardEvent) {
        stillWatching.onInput(nowMs())   // DEB-5843 (FR-2) — one write; the key is not consumed
        // DEB-4661 (FR-9) — Back on a start that has not begun: loading, busy, or refused.
        if (!loaded && shell.isPlayer() && isBack(e)) { cancelStart(); return }
        if (!loaded && signIn.handleKey(e)) return
        if (!loaded && profileUi.handleKey(e)) return
        if (!loaded && shell.handleKey(e)) return
        if (!loaded) return
        // R264 (FR-R264-3/4) — the TV remote drives the whole player; every action ends in a status report,
        // so the phone's remote shows a TV-remote pause within one push. Precedence: the picker, then the
        // next-up card, then Skip Intro, then the transport.
        if (railOpen && onRailKey(e)) { sendStatus(); return }   // FR-54 — the rail owns its keys; the card and the pill close it when they rise, and a media key falls through to the transport
        if (pickerOpen) { onPickerKey(e); if (!pickerOpen) showChrome(); return }   // FR-29 — closing it leaves the controls up, on Audio & subtitles
        if (nextUpShown && onCardKey(e)) { sendStatus(); return }
        // FR-47 — Auto: Back on the pill cancels the skip for this item (before any other Back, so it does not also hide the controls).
        if (skipShown && skipIntroMode() == SkipMode.AUTO && isBack(e) && (!chromeUp || chromeFocus == ChromeFocus.SKIP)) { skipIntroCancelled = true; hideSkip(); return }
        if (skipShown && e.key == "Enter" && (!chromeUp || chromeFocus == ChromeFocus.SKIP)) { skipIntro(); return }
        if (!onTransportKey(e)) return
        sendStatus()
    }

    /**
     * DEB-5025 (FR-29) — Android TV's key model (`PlayerScreenState.dpad*`, R178, R251, R350 FR-R350-7, R363, R44, R112) on the
     * Samsung's remote. Returns false for a key it does not use. With the controls hidden OK plays or pauses and shows them on
     * Play; ←/→/↑ act on the first press from the remembered control; ↓ only reveals. Media keys act whatever is focused.
     */
    private fun isKey(e: KeyboardEvent, standard: String, tizen: String, code: Int): Boolean =
        e.key == standard || e.key == tizen || e.keyCode == code

    private fun onTransportKey(e: KeyboardEvent): Boolean {
        val hidden = !chromeUp
        when {
            isKey(e, "MediaPlayPause", "XF86PlayBack", 10252) -> { togglePlay(); showChrome() }
            isKey(e, "MediaPlay", "XF86AudioPlay", 415) -> { backend.play(); showChrome() }
            isKey(e, "MediaPause", "XF86AudioPause", 19) -> { backend.pause(); showChrome() }
            isKey(e, "MediaStop", "XF86AudioStop", 413) -> stopAndIdle()
            isKey(e, "MediaRewind", "XF86AudioRewind", 412) -> { seekBy(-SKIP_BACK_MS); showChrome() }
            e.key == "MediaFastForward" || e.keyCode == 417 -> { seekBy(SKIP_FWD_MS); showChrome() }   // 417 is Tizen's fast-forward, whatever name it arrives under
            e.key == "MediaTrackNext" -> if (nextId != null) playNext() else showChrome()
            e.key == "Enter" -> when {
                hidden -> { chromeFocus = ChromeFocus.PLAY; scrubMs = null; togglePlay(); showChrome() }   // R178, R350: OK acted as Play
                chromeFocus == ChromeFocus.NEXT -> playNext()
                else -> { actOnFocus(); showChrome() }
            }
            e.key == "ArrowLeft" || e.key == "ArrowRight" -> { moveAcross(if (e.key == "ArrowLeft") -1 else 1); showChrome() }
            e.key == "ArrowUp" -> {
                if (hidden && skipShown) { chromeFocus = ChromeFocus.SKIP; skipReturn = null }   // R363 FR-3 — Up that wakes the controls inside the intro offers the skip first
                else when (chromeFocus) {
                    ChromeFocus.BAR, ChromeFocus.TRACKS, ChromeFocus.NEXT -> {
                        if (skipShown) { skipReturn = chromeFocus; scrubMs = null; chromeFocus = ChromeFocus.SKIP }
                        else if (chromeFocus != ChromeFocus.BAR) chromeFocus = ChromeFocus.BAR
                    }
                    ChromeFocus.SKIP -> {}
                    else -> chromeFocus = ChromeFocus.BAR
                }
                showChrome()
            }
            e.key == "ArrowDown" -> {
                if (!hidden) when (chromeFocus) {   // FR-54 — ↓ from the row opens the episode rail when this play has one
                    ChromeFocus.BAR -> { commitScrub(); chromeFocus = ChromeFocus.PLAY }
                    ChromeFocus.SKIP -> chromeFocus = skipReturn ?: ChromeFocus.PLAY
                    else -> if (railAllowed()) openRail()
                }
                showChrome()
            }
            // R112's steps: Back closes a scrub, then hides the controls, then stops (R180 teardown). The picker's Back is its own.
            isBack(e) -> when {
                scrubMs != null -> { scrubMs = null; showChrome() }
                chromeUp -> hideChrome()
                else -> stopAndIdle()
            }
            else -> return false
        }
        return true
    }

    private fun togglePlay() { if (playing) backend.pause() else backend.play() }
    private fun seekBy(deltaMs: Long) { seekTo((backend.positionMs() + deltaMs).coerceAtLeast(0), forward = deltaMs >= 0) }

    private fun commitScrub() {
        val target = scrubMs ?: return
        scrubMs = null
        seekTo(target)
    }

    /** ←/→ (dir −1/+1): on the seek bar a scrub step, anywhere else one control along the order; the pill is not in the order. */
    private fun moveAcross(dir: Int) {
        when (chromeFocus) {
            ChromeFocus.BAR -> {
                val dur = backend.durationMs()
                if (dur > 0) scrubMs = ((scrubMs ?: backend.positionMs()) + dir * scrubStepMs(dur)).coerceIn(0, dur)
            }
            ChromeFocus.SKIP -> {}
            else -> {
                val order = chromeOrder(nextId != null)
                val at = order.indexOf(chromeFocus)
                if (at >= 0) chromeFocus = order[(at + dir).coerceIn(0, order.lastIndex)]
            }
        }
    }

    /** OK on the focused control (Next is [onTransportKey]'s: it leaves the player's item). */
    private fun actOnFocus() {
        when (chromeFocus) {
            ChromeFocus.BAR -> if (scrubMs != null) commitScrub() else scrubMs = backend.positionMs()
            ChromeFocus.BACK10 -> seekBy(-SKIP_BACK_MS)
            ChromeFocus.PLAY -> togglePlay()
            ChromeFocus.FWD30 -> seekBy(SKIP_FWD_MS)
            ChromeFocus.TRACKS -> openPicker()
            ChromeFocus.SKIP -> skipIntro()
            ChromeFocus.NEXT -> {}
        }
    }

    /** A remote's Back arrives under several names: `XF86Back` (key code 10009) on a Tizen set, `Escape`/
     *  `Backspace` on a desktop browser, `Exit` for the Exit key this app registers. */
    private fun isBack(e: KeyboardEvent): Boolean = isBackKey(e)

    /** DEB-4947 (FR-11) — Settings ▸ Change server and the sign-in screen's *Wrong server?* both land here. No live
     *  teardown of this running instance: store the current address as the setup screen's prefill, drop the stored
     *  one, and reload — `main()`'s normal "no address stored" path then shows setup with today's address already
     *  in the field. */
    private fun reopenServerSetup() {
        localStorage.setItem(SETUP_PREFILL_KEY, serverUrl)
        localStorage.removeItem(SERVER_URL_KEY)
        ScreenHomeSnapshot.deleteAll()   // DEB-5697 (FR-61) — the saved feeds belong to the server being left
        window.location.reload()
    }

    /** FR-28/FR-29 — put the controls up (or keep them) and start the 3.6 s again. A phone's pause, seek or track command comes here too. */
    private fun showChrome() {
        chromeWakeAt = nowMs()
        paintChrome(full = true)
        chromeUp = true
        el("overlay").classList.add("on")
        el("cues").classList.add("lift")
        if (overlayJob?.isActive == true) return
        overlayJob = GlobalScope.launch {
            while (chromeUp) {
                delay(200)
                // Playing, with no picker and no scrub: hide 3.6 s after the last key. Otherwise hold, and count from the release.
                if (!playing || pickerOpen || railOpen || scrubMs != null) chromeWakeAt = nowMs()
                else if (nowMs() - chromeWakeAt >= CHROME_HIDE_MS) { hideChrome(); return@launch }
                paintChrome(full = false)
            }
        }
    }

    /** Hiding never moves the focus (R178): the next key acts from the control last focused. */
    private fun hideChrome() {
        chromeUp = false
        scrubMs = null
        el("overlay").classList.remove("on")
        el("cues").classList.remove("lift")
        el("skip").classList.remove("foc")
    }

    private fun setText(id: String, text: String) { val n = el(id); if (n.textContent != text) n.textContent = text }

    /** [full] is a key's repaint (the lines and every label); a tick only moves the bar and the glyph. */
    private fun paintChrome(full: Boolean) {
        val pos = backend.positionMs(); val dur = backend.durationMs()
        paintChip()
        if (full) {
            setText("ov-kicker", kicker.orEmpty())
            setText("ov-title", title.orEmpty())
            setText("ov-back", "◀ " + ScreenLanguage.t("player.back"))
            setText("ov-b10", "−10s")
            setText("ov-f30", "+30s")
            setText("ov-tracks", ScreenLanguage.t("player.audio_subs"))
            setText("ov-next", if (nextId != null) ">> " + ScreenLanguage.t("player.next") else "")
            paintIdent()
            for (c in ChromeFocus.values()) el(c.id).classList.toggle("foc", c == chromeFocus && (chromeFocus != ChromeFocus.NEXT || nextId != null))
        }
        setText("ov-play", if (playing) "❚❚" else "▶")
        fun pct(ms: Long) = if (dur > 0) "${(ms.toDouble() / dur * 100).coerceIn(0.0, 100.0)}%" else "0%"
        setText("ov-elapsed", hms(pos))
        setText("ov-total", hms(dur))
        el("ov-fill").style.width = pct(pos)
        el("ov-buf").style.width = pct(backend.bufferedMs())
        val scrub = scrubMs
        val seek = el("ov-seek")
        seek.classList.toggle("scrub", scrub != null)
        val at = pct(scrub ?: pos)
        el("ov-thumb").style.left = at
        if (scrub != null) { el("ov-scrub").style.left = at; setText("ov-scrub", hms(scrub)) }
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
     * False only when the call failed; [atMs] is the position to ask at when AVPlay cannot say (FR-63's errored stream).
     */
    private suspend fun restream(subIndex: Int, audioIndex: Int?, thenShow: Int, belowBandwidthBps: Long? = null, atMs: Long? = null, audioHlsOnly: Boolean = false): Boolean {
        val id = itemId ?: return true
        val pos = atMs ?: backend.positionMs()
        val wasPlaying = playing
        val t = runCatching { api.restream(id, subIndex, pos, if (audioHlsOnly) ladderCapabilities() else backend.capabilities(), audioIndex, belowBandwidthBps = belowBandwidthBps) }.getOrNull()
            ?: run { if (loaded && !buffering && !recovering) show(); sendStatus(); return false }   // FR-32 — a freeze's buffering view must not outlive a restream that never came
        if (itemId != id) return true                  // the viewer moved on while we were negotiating
        ticket = t
        selectedAudio = currentAudioPos(t)
        pendingFileAudio = null
        showSubtitle(if (t.burnedSubtitleIndex != null) -1 else thenShow)   // never draw over a burn-in
        pauseOnNextPlaying = !wasPlaying
        backend.close()
        qoe.restreamed(t.streamBps.takeIf { t.directPlay })   // FR-25 — the same item again: its counts stay, its own start is not a stall
        backend.open(t.hlsUrl ?: "", pos, t.startVariantBps, t.directPlay)
        sendStatus()
        return true
    }

    /** DEB-5024 (FR-27) — no rung in the master is below the one playing; no answer from AVPlay counts as none. */
    private fun onLowestRung(): Boolean {
        if (ticket?.directPlay == true) return true   // DEB-5503 (FR-45) — a direct play has no AVAILABLE_BITRATE: it is the lowest rung there is
        val rungs = backend.availableBitratesBps()
        if (rungs.isEmpty()) return true
        val playing = backend.currentBandwidthBps() ?: return false
        return playing <= rungs.min()
    }

    /** DEB-5364 — waits (3 s at most; a failed post ends it too) for the report that was posted just before a stall's restream. */
    private suspend fun afterReport() { lastQoePost?.let { withTimeoutOrNull(3_000) { it.join() } } }

    /** DEB-5024 (FR-27) — the same stream, at its position, asked for again so the server answers with its ladder; once per item. */
    private fun askForLowerRung() {
        val t = ticket
        val audio = carriedAudio()
        // DEB-5503 (FR-45) — a direct play asks under the file's own bitrate, so the server's answer is our ladder and not the same file.
        val below = t?.takeIf { it.directPlay }?.streamBps
        GlobalScope.launch { afterReport(); restream(t?.burnedSubtitleIndex ?: -1, audio, thenShow = selectedSub, belowBandwidthBps = below) }
    }

    /**
     * DEB-5024 (FR-32) — a freeze of 5 s: the same stream, at its position, asked for again under the bitrate that froze, so the
     * server answers with our encoder's rungs below it and never the stream that froze. Once per item (the QoE state decides).
     */
    private fun askAfterFreeze(belowBandwidthBps: Long?) {
        val t = ticket
        val audio = carriedAudio()
        GlobalScope.launch { afterReport(); restream(t?.burnedSubtitleIndex ?: -1, audio, thenShow = selectedSub, belowBandwidthBps = belowBandwidthBps) }
    }

    /** DEB-5024 (FR-32) — a freeze is on screen: the buffering view (R218) covers it until the position moves or a new stream plays. */
    private fun showFrozen(on: Boolean) {
        if (!loaded) return
        if (on) show("buffering") else if (!buffering) show()
    }

    /** DEB-5024 (FR-32) — AVPlay's position every 500 ms: a position that stops while it says PLAYING is a stall nobody announced. */
    private suspend fun positionLoop() {
        while (true) {
            delay(500)
            if (!loaded || recovering) continue   // FR-63 — an errored stream's frozen position is not a freeze to restream
            qoe.watchPosition(backend.positionMs(), playing && backend.state() == "PLAYING", backend.durationMs())
        }
    }

    /** FR-R285-3 — draw text subtitle [index] of [receiverSubtitles] (anything else = none). */
    private fun showSubtitle(index: Int) {
        val url = receiverSubtitles(ticket).getOrNull(index)?.takeIf { it.deliveryMethod != "encode" }?.url
        selectedSub = if (url != null) index else -1
        cues = emptyList()
        val generation = ++cueGeneration
        applySubSize()
        subtitleJob?.cancel(); subtitleJob = null
        if (url == null) return
        val afterFirstFrame = ticket?.directPlay == true
        // DEB-4692 (DEB-2205 FR-6) — through the API client: our own subtitle path is resolved on the server and sent with the
        // device token (it was a bare GET: a 401, or this widget's own origin), and a 202 is asked again until the track arrives.
        subtitleJob = GlobalScope.launch {
            // DEB-5503 (FR-44) — a direct play's picture is the start path: Jellyfin extracts an embedded track on the first request (R381), so the sidecar waits for the first frame.
            if (afterFirstFrame) {
                while (generation == cueGeneration && loaded && !qoe.firstFrameShown) delay(250)
                if (generation != cueGeneration || !loaded) return@launch
            }
            val body = TicketSubtitles.fetch({ api.subtitleAttempt(url) }, { nowMs() }) { delay(it) }?.text ?: return@launch
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
            nextUpSecs = if (nextUpShown && creditsMode == CreditsMode.NEXT && autoplay()) ((nextUpDeadline - nowMs()) / 1000).toInt().coerceAtLeast(0) else null,
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

    private fun deviceName(): String = "Samsung TV"

    private fun loadOrCreateDeviceId(): String {
        localStorage.getItem(DEVICE_ID_KEY)?.let { return it }
        val id = "screen-" + randomHex(24)
        localStorage.setItem(DEVICE_ID_KEY, id)
        return id
    }


}

/** [dev.jellystructure.ravilo.receiver.audioTracksOf]/[subtitleTracksOf] build [CastTrack]s (the Cast
 *  wire shape, carrying a `trackId` CAF needs); a screen's own status wire shape is [ScreenTrack] — same
 *  fields minus `trackId`, since ScreenStatus's `selected_audio`/`selected_sub` already select by index. */
private fun CastTrack.toScreenTrack() = ScreenTrack(index = index, label = label, language = language, forced = forced, isDefault = isDefault)

/** FR-25 — an HTTP status inside AVPlay's error text, when it carries one. */
private val HTTP_STATUS = Regex("""\b[45]\d\d\b""")

private fun randomHex(length: Int): String {
    val chars = "0123456789abcdef"
    return buildString { repeat(length) { append(chars[Random.nextInt(chars.length)]) } }
}


fun main() {
    // DEB-4663 (FR-11) — the language is the one Settings picked on this TV, else the TV's own
    // (`navigator.language`, the standard Web API: no Tizen-specific systeminfo call), else English.
    // It is resolved before any screen, setup included.
    ScreenLanguage.start(window.navigator.language)
    window.addEventListener("load", {
        GlobalScope.launch {
            runCatching {
                val stored = localStorage.getItem(SERVER_URL_KEY)?.trim()?.takeIf { it.isNotBlank() }
                // DEB-4947 / DEB-4663 — a reopened setup screen leaves the address that was working behind it.
                val previous = localStorage.getItem(SETUP_PREFILL_KEY)
                val serverUrl = stored ?: runServerSetup()
                if (localStorage.getItem(CODE_ONLY_SERVER_KEY)?.let { !sameServer(it, serverUrl) } == true)
                    localStorage.removeItem(CODE_ONLY_SERVER_KEY)
                // FR-11 — a new address signs every profile out: their tokens belong to the old server.
                if (stored == null && previous != null && !sameServer(previous, serverUrl)) ScreenProfileStore.forgetAll()
                Screen(serverUrl).start()
            }.onFailure { console.error("Ravilo screen failed to start: ${it.message}") }
        }
    })
}
