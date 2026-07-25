package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.LocalPlaystateCommands
import dev.jellystructure.ravilo.ui.components.EpisodeTriptych
import dev.jellystructure.ravilo.ui.components.LANG_CC
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.MediaKey
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import dev.jellystructure.ravilo.ui.seams.PlayerChromeActions
import dev.jellystructure.ravilo.ui.seams.PlayerChromeBridge
import dev.jellystructure.ravilo.ui.seams.PlayerChromeState
import dev.jellystructure.ravilo.ui.seams.PlayerImmersiveEffect
import dev.jellystructure.ravilo.ui.seams.PlayerLifecycleEffect
import dev.jellystructure.ravilo.ui.seams.PlayerVideoSurface
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.ravilo.ui.seams.languageName
import dev.jellystructure.ravilo.ui.seams.playerBackdropColor
import dev.jellystructure.ravilo.ui.seams.playerTapTogglesChrome
import dev.jellystructure.ravilo.ui.seams.setPointerCursorHidden
import dev.jellystructure.ravilo.ui.seams.wakeOnPointerMove
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.ravilo.ui.theme.RaviloColors
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// ─── Constants ────────────────────────────────────────────────────────────────

private const val CHROME_HIDE_MS = 3_600L
private const val CURSOR_HIDE_MS = 2_000L // R157 FR-R157-3.2
private const val NEXTUP_AT_MS   = 20_000L    // R111: show next-up card when this many ms remain (was 34s — too early)
// R182 — replaces the old hardcoded COUNTDOWN_SECS = 8: the "Skip button countdown" viewer setting
// (RaviloConfig.skipSecs, one of 4/6/8) now governs both the Skip Intro pill and this next-up/credits
// countdown. This is only the pre-fetch seed (RaviloConfig's own default) until getConfig() resolves.
private const val DEFAULT_SKIP_SECS = 6
private const val SKIP_BACK_MS   = 10_000L
private const val SKIP_FWD_MS    = 30_000L
private const val POLL_MS        = 500L

// ─── Focus model ──────────────────────────────────────────────────────────────

private enum class PlFocus { SKIP_INTRO, SEEK_BAR, SKIP_BACK, PLAY, SKIP_FWD, TRACKS, NEXT_EP, BACK }
private enum class NuFocus { PLAY, STAY }

// R182 (FR-RV-SKIP1-2) — the credits card's ONE primary action, chosen by priority: a stinger (from
// Phase 150 §C) always wins (never auto-skip past it); else a real next episode; else a plain
// skip-credits/exit. "Watch credits" (NuFocus.STAY) is offered in every mode alongside this one action.
private enum class CreditsCardMode { STINGER, NEXT_EPISODE, SKIP_CREDITS }

// R182 — SKIP_INTRO only enters the order while the pill is actually visible (mirrors NEXT_EP's own
// hasNextEp gating), first in the order per the design prototype's own focus-order function.
private fun transportOrder(hasNextEp: Boolean, hasSkipIntro: Boolean): List<PlFocus> =
    buildList {
        if (hasSkipIntro) add(PlFocus.SKIP_INTRO)
        add(PlFocus.SEEK_BAR); add(PlFocus.SKIP_BACK); add(PlFocus.PLAY)
        add(PlFocus.SKIP_FWD); add(PlFocus.TRACKS)
        if (hasNextEp) add(PlFocus.NEXT_EP)
        add(PlFocus.BACK)
    }

// R157 (FR-R157-2.2) — hoverable()/HoverInteraction is cross-platform commonMain, unlike the
// lower-level onPointerEvent (skiko-only — desktop/web — unavailable on Android). Shared by every
// transport button below instead of repeating the interaction-source + LaunchedEffect boilerplate.
@Composable
private fun Modifier.hoverToCall(onHover: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { if (it is HoverInteraction.Enter) onHover() }
    }
    return this.hoverable(interactionSource)
}

// ─── Top-level composable ─────────────────────────────────────────────────────

@Composable
fun PlayerScreen(
    itemId: String,
    itemTitle: String,
    itemKicker: String? = null,
    nextEpisodeId: String? = null,
    nextEpisodeLabel: String? = null,
    nextEpisodeTitle: String? = null,
    episodes: List<PlayerEpisodeEntry>? = null,
    currentEpIndex: Int = 0,
    // R181 — the series' own id (movies: their own id) for per-series remembered audio/subtitle
    // choices; R181/R180 — the title's original-audio language for the "Dubbed" audio badge.
    seriesId: String? = null,
    originalLanguage: String? = null,
    // Phase 150 — this title's own intro/credits segments (R182 Skip Intro / Skip Credits consumes
    // this; this stage only threads it in).
    segments: dev.jellystructure.shared.tv.TvSegmentMarkers = dev.jellystructure.shared.tv.TvSegmentMarkers(),
    store: PlayerStore,
    onBack: () -> Unit,
    onNavigateToEpisode: ((String) -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sessionState by store.state.collectAsState()

    // Bug fix: the poll loop below is a LaunchedEffect(Unit) that's deliberately never restarted
    // across an episode transition (see loadedForItemId's comment — same underlying `player`
    // instance is reused so the outgoing stream doesn't visibly restart). But `itemId` and
    // `nextEpisodeId` are plain function parameters — a coroutine launched once captures their
    // VALUE at that moment and never sees later recompositions' updates. After the first
    // successful auto-advance, this loop's `itemId` stayed frozen on the very first episode
    // forever while `loadedForItemId` correctly kept moving on, so `playerLoadedForCurrentItem`
    // went permanently false — silently disabling next-up/auto-advance/end-of-stream detection
    // for the rest of this screen's life. Reported as "stuck" after a couple of episodes, with
    // "auto play next" never working again. rememberUpdatedState gives the poll loop a live
    // reference that always reflects the latest recomposition, without needing to restart it.
    val currentItemId by rememberUpdatedState(itemId)
    val currentNextEpisodeId by rememberUpdatedState(nextEpisodeId)
    // R181/R180 — same staleness risk as itemId/nextEpisodeId above: these are read inside the poll
    // loop's resolution/remember logic, which must see the current recomposition's value.
    val currentSeriesId by rememberUpdatedState(seriesId)
    val currentOriginalLanguage by rememberUpdatedState(originalLanguage)
    // Phase 150 — same staleness risk: the near-end/skip-intro checks (R182) run inside the poll loop.
    val currentSegments by rememberUpdatedState(segments)

    // R182 — resolved per-viewer skip behaviour (jellystructure Ravilo config → Preferences; there is
    // no in-app settings screen for these — see PlayerStore.getConfig()). Re-fetched every episode (the
    // LaunchedEffect(itemId) below) so a mid-binge settings change takes effect on the next episode.
    var skipIntroMode by remember { mutableStateOf(dev.jellystructure.shared.tv.SkipMode.PROMPT) }
    var skipCreditsMode by remember { mutableStateOf(dev.jellystructure.shared.tv.SkipMode.PROMPT) }
    var skipSecs by remember { mutableIntStateOf(DEFAULT_SKIP_SECS) }
    var autoplayNextEnabled by remember { mutableStateOf(true) }
    val currentSkipIntroMode by rememberUpdatedState(skipIntroMode)
    val currentSkipCreditsMode by rememberUpdatedState(skipCreditsMode)
    val currentSkipSecs by rememberUpdatedState(skipSecs)
    val currentAutoplayNext by rememberUpdatedState(autoplayNextEnabled)

    val player = remember { RaviloPlayer() }

    // Playback state — polled every 500 ms from the player
    var positionMs   by remember { mutableLongStateOf(0L) }
    var durationMs   by remember { mutableLongStateOf(0L) }
    var bufferedMs   by remember { mutableLongStateOf(0L) }
    var isPlaying    by remember { mutableStateOf(false) }
    var audioTracks  by remember { mutableStateOf<List<PlayerAudioTrack>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<PlayerSubtitleTrack>>(emptyList()) }

    // Chrome visibility — bumping chromeRevision restarts the auto-hide timer
    var chromeVisible  by remember { mutableStateOf(true) }
    var chromeRevision by remember { mutableLongStateOf(0L) }
    // R157 (FR-R157-3.2) — bumping restarts the cursor auto-hide timer, independently of chrome.
    var pointerActivityRevision by remember { mutableLongStateOf(0L) }

    // R182 — Skip Intro pill (FR-RV-SKIP1-1). skipIntroCountingDown mirrors the design prototype's own
    // skipPromptOn: true only during the brief grace window right after entering the intro; the pill's
    // visibility beyond that rides the SAME chrome show/hide seam (chromeVisible) rather than a separate
    // latch, so it reappears on wake() and can hide again with chrome — see skipIntroPillVisible below.
    // skipIntroCountdownDone guards against re-arming a second countdown for the same intro window.
    var skipIntroCountingDown by remember { mutableStateOf(false) }
    var skipIntroCountdownDone by remember { mutableStateOf(false) }
    var skipIntroCountdownSecs by remember { mutableIntStateOf(0) }

    // Focus
    var focus    by remember { mutableStateOf(PlFocus.PLAY) }

    // Scrubbing
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos  by remember { mutableLongStateOf(0L) }

    // Track picker
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerTab  by remember { mutableIntStateOf(0) }   // 0=audio, 1=subs
    var pickerIdx  by remember { mutableIntStateOf(0) }
    var selectedAudio by remember { mutableIntStateOf(0) }
    var selectedSub   by remember { mutableIntStateOf(-1) } // -1 = off
    // R181 — which itemId the layered resolver has already run for; compared against currentItemId
    // (rememberUpdatedState) each poll tick so it re-arms exactly once per episode, same mechanism as
    // loadedForItemId above, and never re-fires mid-playback or fights a later manual pick.
    var resolvedForItemId by remember { mutableStateOf<String?>(null) }

    // Next-up card
    var nextUpVisible by remember { mutableStateOf(false) }
    // R111: once the viewer picks "Watch credits" we latch it dismissed so the near-end poll doesn't
    // re-show the card on the very next tick. Reset per-episode in LaunchedEffect(itemId).
    var nextUpDismissed by remember { mutableStateOf(false) }
    var countdown     by remember { mutableIntStateOf(DEFAULT_SKIP_SECS) }
    var nuFocus       by remember { mutableStateOf(NuFocus.PLAY) }
    // Bug fix: PlayerScreen is reused across an episode transition (replaceTop keeps this composable —
    // see LaunchedEffect(itemId) below), and so is `player` itself (remembered once, above) — its
    // outgoing stream keeps playing (and reporting near-the-end position/duration) for as long as the
    // new episode's stream ticket takes to round-trip, before `player.load()` actually swaps it. The
    // poll loop (LaunchedEffect(Unit) below, never restarts either) kept reading that stale position
    // every tick, so right after a manual "next episode" pick it could re-satisfy the near-end/isEnded
    // check using the OUTGOING episode's tail end and re-arm nextUpVisible — starting a fresh 8s
    // countdown that then auto-advances a SECOND time while the new episode is already playing,
    // restarting it. Tracks which itemId the player is actually loaded for; the poll loop's near-end/
    // isEnded checks are gated on this matching the current itemId so a stale outgoing stream can never
    // trigger next-up again.
    var loadedForItemId by remember { mutableStateOf<String?>(null) }

    // Episode rail
    var epRailOpen  by remember { mutableStateOf(false) }
    var focusedEpIdx by remember { mutableIntStateOf(currentEpIndex) }

    // Pause flash
    var pauseFlash by remember { mutableStateOf(false) }
    var pauseFlashIsPlay by remember { mutableStateOf(true) }

    // R56: encode subs (PGS) from the server-pushed ticket, appended after native tracks in the picker.
    val encodeSubTracks: List<PlayerSubtitleTrack> = remember(sessionState) {
        val subs = (sessionState as? PlayerSessionState.Ready)?.ticket?.subtitles
            ?.filter { it.deliveryMethod == "encode" } ?: emptyList()
        subs.mapIndexed { i, sub ->
            PlayerSubtitleTrack(
                index = -1, // not an ExoPlayer track index
                label = sub.label?.takeIf { it.isNotBlank() }
                    ?: languageName(sub.language) ?: sub.language?.uppercase() ?: "Sub ${i + 1}",
                language = sub.language,
                forced = sub.forced,
                isDefault = sub.isDefault,
                deliveryMethod = "encode",
                jellyfinStreamIndex = sub.index,
            )
        }
    }

    // Subtitle options: Off + native player tracks (embed/external) + encode (PGS burn-in) from ticket.
    val subOptions: List<PlayerSubtitleTrack?> = remember(subtitleTracks, encodeSubTracks) {
        listOf(null) + subtitleTracks + encodeSubTracks
    }

    // ─── Helper functions ───────────────────────────────────────────────────

    fun wake() { chromeVisible = true; chromeRevision++ }
    fun scheduleHide() { chromeRevision++ }
    // R178 (FR-RV-SEL1-1): every site that hides the chrome must also drop the hand-rolled `focus`
    // back to PLAY — otherwise a later Select re-dispatches on whatever control was focused when
    // chrome hid (e.g. reopening Audio & Subs) instead of the expected play/pause. Route every
    // chrome-hide through this single choke point rather than a bare `chromeVisible = false`.
    fun hideChrome() { chromeVisible = false; focus = PlFocus.PLAY }

    fun skip(ms: Long) {
        val newPos = (positionMs + ms).coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(newPos)
        positionMs = newPos
        wake()
    }

    fun commitScrub() {
        player.seekTo(scrubPos)
        positionMs = scrubPos
        scrubbing = false
        wake()
    }

    fun togglePlay() {
        if (isPlaying) {
            player.pause(); isPlaying = false
            pauseFlashIsPlay = false
        } else {
            player.play(); isPlaying = true
            pauseFlashIsPlay = true
        }
        pauseFlash = true
        wake()
    }

    fun advanceNext() {
        nextUpVisible = false
        // R142: a genuinely finished episode (≥90%) is marked played as we advance, so up-next stays
        // correct; a manual skip mid-episode is not (it stays in-progress with its resume sliver).
        if (durationMs > 0 && positionMs >= durationMs * 90 / 100) store.markWatched(itemId)
        nextEpisodeId?.let { onNavigateToEpisode?.invoke(it) }
    }

    // R111: "Watch credits" — hide the card AND latch it so the near-end poll won't immediately re-show
    // it. It can still re-appear at the true end of the file (the isEnded branch ignores the latch).
    fun stayThrough() { nextUpVisible = false; nextUpDismissed = true; chromeVisible = true; scheduleHide() }

    // R182 (FR-RV-SKIP1-2) — CreditsCardMode.STINGER's primary action. Stinger.atMs is null in every
    // title today (TMDB's during/aftercreditsstinger keywords carry no timestamp — see Phase 150
    // SegmentDetection.stingerFromTmdbKeywords); "never blindly skip past it" means we can't just jump
    // to the natural end either, so without a known position this lands 2s before the file's actual end
    // — past the long credits ROLL (whose start we DO know) while still leaving the tail of the file,
    // where a mid/post-credits scene always sits, to play out normally.
    fun skipToScene() {
        val target = currentSegments.stinger?.atMs ?: (durationMs - 2_000L).coerceAtLeast(positionMs)
        player.seekTo(target)
        positionMs = target
        nextUpVisible = false
        wake()
    }

    // R182 (FR-RV-SKIP1-2) — CreditsCardMode.SKIP_CREDITS's primary action: a movie or a series' last
    // episode with no stinger has nothing to seek forward TO, so this is simply "I'm done" — the same
    // exit the pre-R182 isEnded handler used to trigger unconditionally (and silently, with no card) for
    // any item with no next episode.
    fun skipCredits() {
        nextUpVisible = false
        if (durationMs > 0 && positionMs >= durationMs * 90 / 100) store.markWatched(itemId)
        onBack()
    }

    // R182 (FR-RV-SKIP1-1) — fires on an explicit OK press on the pill AND from the countdown-elapsed
    // auto-trigger in Auto mode (see the skipIntroCountingDown LaunchedEffect below) — same action either
    // way, matching the design prototype's own single skipIntro() used from both paths.
    fun skipIntro() {
        val end = currentSegments.introEndMs ?: return
        player.seekTo(end)
        positionMs = end
        skipIntroCountingDown = false
        skipIntroCountdownDone = true
        if (focus == PlFocus.SKIP_INTRO) focus = PlFocus.PLAY
        wake()
    }

    fun chooseEpisode() {
        if (focusedEpIdx == currentEpIndex) { epRailOpen = false; return }
        episodes?.getOrNull(focusedEpIdx)?.id?.let { onNavigateToEpisode?.invoke(it) }
    }

    // R181 — client-local only (never sent to the jellystructure backend), keyed per profile via
    // MultiTokenStore.getActive()?.userId, same store family as the auth-session cache. Read-modify-
    // write against whatever's already persisted so an audio-only (or subtitle-only) pick doesn't
    // clobber the other axis's remembered choice. Off is a real persisted state (subtitlesOff = true),
    // not "no preference" (null subtitleLanguage with subtitlesOff = false).
    fun persistChoice(newAudioLanguage: String? = null, newSubtitleLanguage: String? = null, newSubtitlesOff: Boolean? = null) {
        val profileId = MultiTokenStore.getActive()?.userId ?: return
        val seriesKey = currentSeriesId ?: currentItemId
        val existing = PlaybackPrefsStore.getSeriesChoice(profileId, seriesKey) ?: PlaybackPrefsStore.getGlobalChoice(profileId)
        val choice = RememberedChoice(
            audioLanguage = newAudioLanguage ?: existing?.audioLanguage,
            subtitleLanguage = if (newSubtitlesOff == true) null else (newSubtitleLanguage ?: existing?.subtitleLanguage),
            subtitlesOff = newSubtitlesOff ?: existing?.subtitlesOff ?: false,
        )
        PlaybackPrefsStore.setSeriesChoice(profileId, seriesKey, choice)
        PlaybackPrefsStore.setGlobalChoice(profileId, choice)
    }

    // R181 (FR-RV-TRK1) — layered resolution, run once per item the first tick after track discovery
    // completes (see the call site in the poll loop below): per-series remembered choice → learned
    // global-language preference → source default → first-track/Off. Each tier's lookup returns null
    // when it can't be satisfied in THIS title (e.g. a remembered language absent from this file's
    // tracks), falling through to the next via `?:` rather than a hard-coded index. Matches by
    // language, never by ExoPlayer track index (indices differ across episodes/files). Scope note:
    // only matches against native/external subtitleTracks, never encodeSubTracks (PGS burn-in) — a
    // remembered language that exists only as a PGS track in this file falls through instead of
    // silently triggering an autoplay transcode; PGS stays a manual pick, same as today.
    fun resolveTrackSelection() {
        val profileId = MultiTokenStore.getActive()?.userId
        val seriesKey = currentSeriesId ?: currentItemId
        val seriesChoice = profileId?.let { PlaybackPrefsStore.getSeriesChoice(it, seriesKey) }
        val globalChoice = profileId?.let { PlaybackPrefsStore.getGlobalChoice(it) }

        fun tierAudio(choice: RememberedChoice?): Int? =
            choice?.audioLanguage?.let { lang -> audioTracks.firstOrNull { it.language.equals(lang, ignoreCase = true) }?.index }

        val audioIdx = tierAudio(seriesChoice) ?: tierAudio(globalChoice)
            ?: audioTracks.firstOrNull { it.isDefault }?.index
            ?: audioTracks.firstOrNull()?.index
            ?: 0
        player.selectAudioTrack(audioIdx)
        selectedAudio = audioIdx

        fun tierSub(choice: RememberedChoice?): Int? = when {
            choice == null -> null
            choice.subtitlesOff -> -1
            else -> choice.subtitleLanguage?.let { lang -> subtitleTracks.firstOrNull { it.language.equals(lang, ignoreCase = true) }?.index }
        }

        val subIdx = tierSub(seriesChoice) ?: tierSub(globalChoice)
            ?: subtitleTracks.firstOrNull { it.isDefault }?.index
            ?: subtitleTracks.firstOrNull { it.forced }?.index
            ?: -1
        player.selectSubtitleTrack(subIdx)
        selectedSub = subIdx
    }

    fun choosePick() {
        if (pickerTab == 0) {
            selectedAudio = pickerIdx
            player.selectAudioTrack(pickerIdx)
            persistChoice(newAudioLanguage = audioTracks.getOrNull(pickerIdx)?.language)
        } else {
            val sub = subOptions.getOrNull(pickerIdx)
            if (sub != null && sub.deliveryMethod == "encode") {
                // R56: PGS burn-in — restream with subtitle index baked into the Jellyfin transcode.
                store.restreamWithSub(itemId, sub.jellyfinStreamIndex, player.positionMs)
                // R181 — still worth remembering the language (helps other titles' global tier and a
                // rewatch of this series where the language exists as a native track), even though the
                // resolver above never auto-selects a PGS track back in.
                persistChoice(newSubtitleLanguage = sub.language, newSubtitlesOff = false)
            } else {
                val subIdx = pickerIdx - 1   // option 0 = Off
                selectedSub = subIdx
                player.selectSubtitleTrack(subIdx)
                if (subIdx < 0) persistChoice(newSubtitlesOff = true)
                else persistChoice(newSubtitleLanguage = subtitleTracks.getOrNull(subIdx)?.language, newSubtitlesOff = false)
            }
        }
        pickerOpen = false
        wake()
    }

    fun scrubStep() = SKIP_BACK_MS.coerceAtMost(maxOf(5_000L, (durationMs * 0.012).toLong()))

    // R155 — remote playstate commands (Phase 111 Home Assistant / Jellyfin dashboard buttons via the
    // Phase 110 bridge). Set (not toggle) play state so a stale/duplicate command is idempotent.
    // Collecting LocalPlaystateCommands only while this screen is composed is itself the "ignore when
    // no player is open" behaviour (FR-R155-2) — nothing else needs to check that.
    val remotePlaystate = LocalPlaystateCommands.current
    LaunchedEffect(remotePlaystate) {
        remotePlaystate?.collect { cmd ->
            when (cmd.command.lowercase()) {
                "stop" -> onBack()
                "pause" -> if (isPlaying) togglePlay()
                "unpause" -> if (!isPlaying) togglePlay()
                "seek" -> cmd.seekPositionMs?.let { ms ->
                    val clamped = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
                    player.seekTo(clamped)
                    positionMs = clamped
                    wake()
                }
            }
        }
    }

    // ─── Effects ────────────────────────────────────────────────────────────

    // Start the playback session
    LaunchedEffect(itemId) {
        // Bug fix: also reset the next-up card/countdown here, not just nextUpDismissed — see
        // loadedForItemId's comment above. Belt-and-suspenders alongside the loadedForItemId gate below:
        // this clears any next-up state left over from the outgoing episode the instant a new one
        // starts loading, rather than waiting for the (already-gated) poll loop to notice.
        nextUpDismissed = false  // R111: each episode (replaceTop keeps this composable) starts fresh
        nextUpVisible = false
        countdown = currentSkipSecs
        // durationProvider: lets the store take the ≥90% mark-played decision itself if it is closed
        // (episode change / screen teardown) without an explicit stopSession — see PlayerStore.close().
        store.startSession(
            itemId,
            positionProvider = { positionMs },
            isPausedProvider = { !isPlaying },
            durationProvider = { durationMs },
        )
    }

    // R182 — resolve skip behaviour in parallel with session start (not blocking playback start on an
    // extra round-trip). Falls back to the current (previous episode's, or the DEFAULT_SKIP_SECS-seeded)
    // values on failure — never blocks or breaks playback.
    LaunchedEffect(itemId) {
        store.getConfig()?.let { cfg ->
            skipIntroMode = cfg.skipIntro
            skipCreditsMode = cfg.skipCredits
            skipSecs = cfg.skipSecs
            autoplayNextEnabled = cfg.autoplayNext
        }
    }

    // Load player when the StreamTicket is ready (initial load or R56 restream)
    LaunchedEffect(sessionState) {
        val s = sessionState as? PlayerSessionState.Ready ?: return@LaunchedEffect
        val streamUrl = s.ticket.hlsUrl
            ?: "${s.ticket.jellyfinBaseUrl}/Videos/${s.ticket.itemId}/stream.${s.ticket.container}?api_key=${s.ticket.accessToken}"
        player.load(streamUrl, s.ticket.startPositionMs, s.ticket.subtitles, s.ticket.audio)
        player.play()
        isPlaying = true
        loadedForItemId = itemId   // Bug fix: see loadedForItemId's declaration comment above.
        wake()
    }

    // Poll player state
    LaunchedEffect(Unit) {
        while (true) {
            delay(POLL_MS)
            try {
                positionMs  = player.positionMs
                durationMs  = player.durationMs
                bufferedMs  = player.bufferedMs
                isPlaying   = player.isPlaying
                audioTracks = player.audioTracks
                subtitleTracks = player.subtitleTracks

                // Bug fix: gate every next-up/end-of-stream check on the player actually being loaded
                // for the CURRENT itemId — otherwise, right after a manual (or auto) advance, these
                // checks kept reading the OUTGOING episode's near-the-end position/duration during the
                // brief gap before the new episode's stream ticket loads, re-arming next-up and
                // auto-advancing a second time into the episode that had just started. See
                // loadedForItemId's declaration comment above. Uses currentItemId (rememberUpdatedState),
                // not the raw itemId parameter — see that declaration's comment for why this loop
                // specifically needs the live reference.
                val playerLoadedForCurrentItem = loadedForItemId == currentItemId

                // R181 — resolve once per item, the first tick after the (now-current) stream's tracks
                // are actually discovered. Gated on playerLoadedForCurrentItem for the same reason as
                // the next-up checks below: right after an advance, audioTracks/subtitleTracks can
                // still briefly reflect the OUTGOING episode until the new load swaps in, and resolving
                // against stale tracks could pick a bogus index for the new one.
                if (playerLoadedForCurrentItem && resolvedForItemId != currentItemId && audioTracks.isNotEmpty()) {
                    resolveTrackSelection()
                    resolvedForItemId = currentItemId
                }

                // Credits card trigger (R111/R182 FR-RV-SKIP1-2): the real creditsStartMs when known,
                // else the existing NEXTUP_AT_MS-before-end heuristic (§D graceful fallback for an
                // unscanned title). R182 also drops the old "no next episode ⇒ exit immediately, no
                // card at all" special case below — a movie/last-episode now gets the same card, with a
                // Skip credits action, instead of being silently kicked out at the natural end.
                val creditsStart = currentSegments.creditsStartMs
                val creditsReached = if (creditsStart != null) positionMs >= creditsStart
                    else durationMs > 0 && (durationMs - positionMs) in 1..NEXTUP_AT_MS
                if (playerLoadedForCurrentItem && creditsReached && !nextUpVisible && !nextUpDismissed && !player.isEnded) {
                    nextUpVisible = true
                    nuFocus = NuFocus.PLAY
                }

                // Natural end — safety net for a title whose duration/creditsStartMs never satisfied the
                // check above (e.g. bad metadata); still shows the card rather than exiting silently.
                // Bug fix: this was missing the same !nextUpDismissed guard the trigger above has, so a
                // movie/last-episode's credits card became un-dismissable — "Watch credits" (or Back,
                // which calls the same stayThrough()) hid it, but the instant playback reached its real
                // end a moment later, this check fired again (it only looked at nextUpVisible, not
                // whether the viewer had already dismissed it) and popped it right back up, forever.
                if (playerLoadedForCurrentItem && player.isEnded && !nextUpVisible && !nextUpDismissed) {
                    nextUpVisible = true; nuFocus = NuFocus.PLAY
                }

                // R182 (FR-RV-SKIP1-1) — Skip Intro pill. Entering [introStartMs, introEndMs) for the
                // first time this episode arms the brief grace-window countdown (skipIntroCountingDown);
                // beyond that the pill's visibility rides chromeVisible (see skipIntroPillVisible in the
                // composable body) rather than anything tracked here. Leaving the window (playback moved
                // past introEndMs, or a fresh episode with no intro at all) resets the once-per-window
                // latch so a later intro (rare, but not impossible) can arm again.
                val iStart = currentSegments.introStartMs
                val iEnd = currentSegments.introEndMs
                val insideIntro = playerLoadedForCurrentItem && iStart != null && iEnd != null && iEnd > iStart &&
                    positionMs in iStart until iEnd
                if (insideIntro && currentSkipIntroMode != dev.jellystructure.shared.tv.SkipMode.OFF &&
                    !skipIntroCountingDown && !skipIntroCountdownDone) {
                    skipIntroCountingDown = true
                }
                if (!insideIntro && (skipIntroCountingDown || skipIntroCountdownDone)) {
                    skipIntroCountingDown = false
                    skipIntroCountdownDone = false
                    if (focus == PlFocus.SKIP_INTRO) focus = PlFocus.PLAY
                }
            } catch (e: Throwable) {
                // Bug fix: an exception on any single tick (e.g. a transient native-player getter
                // failure) used to kill this whole polling loop for the rest of the PlayerScreen's
                // lifetime — silently ending all next-up/auto-advance/end-of-stream detection with no
                // crash and no visible symptom beyond "playback just stops responding". Keep polling
                // instead of dying on one bad tick.
            }
        }
    }

    // Auto-hide chrome timer (restarted every time chromeRevision bumps)
    LaunchedEffect(chromeRevision) {
        if (chromeRevision == 0L) return@LaunchedEffect
        delay(CHROME_HIDE_MS)
        if (!pickerOpen && !nextUpVisible && !epRailOpen) hideChrome()
    }

    // R182 (FR-RV-SKIP1-1) — derived every recomposition (the composable body always sees the latest
    // positionMs/segments — no staleness risk here, unlike the long-lived effects above): visible once
    // inside [introStartMs, introEndMs), for the brief grace countdown OR whenever chrome itself is up
    // (matching the design prototype's own refreshSkipIntro() — reappears on wake(), no separate latch),
    // and never over another modal overlay.
    val skipIntroStart = segments.introStartMs
    val skipIntroEnd = segments.introEndMs
    val insideIntroWindow = skipIntroStart != null && skipIntroEnd != null && skipIntroEnd > skipIntroStart &&
        positionMs in skipIntroStart until skipIntroEnd
    val skipIntroPillVisible = insideIntroWindow && skipIntroMode != dev.jellystructure.shared.tv.SkipMode.OFF &&
        (skipIntroCountingDown || chromeVisible) && !pickerOpen && !nextUpVisible && !epRailOpen

    // Mirrors the design prototype: the pill grabs focus the instant it appears, and releases it back to
    // PLAY the instant it's gone — so a later Select never dispatches on a control that's no longer shown.
    LaunchedEffect(skipIntroPillVisible) {
        if (skipIntroPillVisible) focus = PlFocus.SKIP_INTRO
        else if (focus == PlFocus.SKIP_INTRO) focus = PlFocus.PLAY
    }

    // R182 (FR-RV-SKIP1-2) — priority: a stinger always wins (never auto-skip past it), else a real
    // next episode, else plain skip-credits/exit. Derived every recomposition — segments/nextEpisodeId
    // only ever change across an episode transition, when nextUpVisible/nextUpDismissed also reset.
    val creditsCardMode = when {
        segments.stinger != null -> CreditsCardMode.STINGER
        nextEpisodeId != null -> CreditsCardMode.NEXT_EPISODE
        else -> CreditsCardMode.SKIP_CREDITS
    }

    // R157/R169 (FR-R157-1.3 fallback) — web only, no-op elsewhere: keep the <video> element's z-order
    // in sync. The video only needs to hide behind the canvas when a *Compose-drawn* overlay that the
    // web DOM chrome (below) doesn't replicate is open — the track picker, next-up card, episode rail,
    // or (R182) the Skip Intro pill.
    val videoBehindCanvas = pickerOpen || nextUpVisible || epRailOpen || skipIntroPillVisible
    LaunchedEffect(videoBehindCanvas) { player.setChromeVisible(videoBehindCanvas) }

    // R169 (FR-R169-3) — push live state to the web DOM transport bar (no-op on Android/TV) whenever
    // it's the active chrome (chromeVisible, and none of the Compose-only overlays are open); hide it
    // otherwise so Compose's own picker/next-up/rail — still drawn exactly as before — aren't covered.
    SideEffect {
        if (chromeVisible && !videoBehindCanvas) {
            PlayerChromeBridge.show(
                PlayerChromeState(isPlaying = isPlaying, positionMs = positionMs, durationMs = durationMs),
                PlayerChromeActions(
                    onTogglePlay = ::togglePlay,
                    onSkipBack = { skip(-SKIP_BACK_MS) },
                    onSkipForward = { skip(SKIP_FWD_MS) },
                    onSeek = { ms -> player.seekTo(ms); positionMs = ms; wake() },
                ),
            )
        } else {
            PlayerChromeBridge.hide()
        }
    }

    // R157 (FR-R157-3.2) — cursor auto-hides after a couple of seconds of no pointer movement during
    // playback (no-op on Android/TV); reappears immediately on the next move via the restart above.
    LaunchedEffect(pointerActivityRevision) {
        setPointerCursorHidden(false)
        delay(CURSOR_HIDE_MS)
        if (isPlaying) setPointerCursorHidden(true)
    }
    DisposableEffect(Unit) { onDispose { setPointerCursorHidden(false) } }

    // Next-up countdown
    // R182 (FR-RV-SKIP1-2): "the auto-advance countdown ring appears ONLY in the Next Episode case" —
    // the stinger case pauses auto-skip entirely (no countdown) and skip-credits simply waits for input.
    LaunchedEffect(nextUpVisible) {
        if (!nextUpVisible || creditsCardMode != CreditsCardMode.NEXT_EPISODE) return@LaunchedEffect
        countdown = currentSkipSecs
        repeat(currentSkipSecs) {
            delay(1_000)
            countdown--
        }
        // R182 (dev-review addendum §2) — autoplayNext gates only the countdown's OWN auto-invocation;
        // a manual pick (Select on the PLAY button, the transport's Next Episode control, or MediaKey.NEXT)
        // still calls advanceNext() directly through their own existing call sites, untouched. With
        // autoplayNext off the card simply sits at 0 waiting for one of those instead of navigating itself.
        if (nextUpVisible && currentAutoplayNext) advanceNext()
    }

    // R182 — Skip Intro pill's own brief grace-window countdown (FR-RV-SKIP1-1): governs the pill's
    // INITIAL visibility only (see skipIntroPillVisible below, which takes over via chromeVisible once
    // this elapses) — Auto mode additionally seeks past the intro once the countdown runs out, unless
    // the viewer already pressed OK sooner (skipIntro() sets skipIntroCountingDown = false itself).
    LaunchedEffect(skipIntroCountingDown) {
        if (!skipIntroCountingDown) return@LaunchedEffect
        skipIntroCountdownSecs = currentSkipSecs
        repeat(currentSkipSecs) {
            delay(1_000)
            skipIntroCountdownSecs--
        }
        // Cancelled (not reaching here) if skipIntro() already flipped skipIntroCountingDown to false —
        // same cancel-on-key-change idiom the existing next-up countdown effect above relies on.
        skipIntroCountingDown = false
        skipIntroCountdownDone = true
        if (currentSkipIntroMode == dev.jellystructure.shared.tv.SkipMode.AUTO) skipIntro()
    }

    // Pause-flash auto-dismiss
    LaunchedEffect(pauseFlash) {
        if (!pauseFlash) return@LaunchedEffect
        delay(550)
        pauseFlash = false
    }

    // Pause/resume when activity goes to background (Home button) and returns
    PlayerLifecycleEffect(player, wasPlaying = { isPlaying })
    // Bug fix: force landscape + hide system bars for as long as the player is on screen — the
    // phone app is portrait-locked with visible system bars everywhere else, which left the player
    // stuck in portrait (heavy top/bottom letterboxing on any normal landscape video) plus a status/
    // nav-bar-shaped margin baked in on top of that.
    PlayerImmersiveEffect()

    // Stop the playback session for the item we are leaving. Bug fix: this used to be keyed on Unit
    // together with the player teardown below, so the effect block ran exactly once and its onDispose
    // captured the `store` from the FIRST composition. After a binge (each episode replaceTops a new
    // Dest.Player with its own store — see RaviloApp) pressing Back therefore stopped EPISODE 1's
    // session, using episode 5's positionMs/durationMs — clobbering episode 1's resume position and
    // even mark-playing it when episode 5 happened to be ≥90% — while episode 5's own session was never
    // stopped at all. Keying on `store` makes this fire once per episode with that episode's own
    // playhead, which is also what makes the resume point (and Continue Watching) correct.
    DisposableEffect(store) {
        onDispose { store.stopSession(positionMs, durationMs) }  // R142: ≥90% → mark played
    }

    // Release the player engine only when the screen itself goes away — the engine is remembered per
    // screen and deliberately REUSED across episodes, so this must NOT be keyed on the store/itemId.
    DisposableEffect(Unit) {
        onDispose {
            player.release()
            PlayerChromeBridge.hide()  // R169 — no-op on Android/TV
        }
    }

    // ─── Key handling ────────────────────────────────────────────────────────

    val playerFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { playerFR.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(playerBackdropColor)
            .wakeOnPointerMove { wake(); pointerActivityRevision++ }
            .dpadFocusable(
                focusRequester = playerFR,
                onFocused = {},
                onLeft = {
                    wake()
                    when {
                        nextUpVisible -> nuFocus = NuFocus.PLAY
                        epRailOpen -> focusedEpIdx = (focusedEpIdx - 1).coerceAtLeast(0)
                        pickerOpen -> { pickerTab = 0; pickerIdx = selectedAudio }
                        focus == PlFocus.SEEK_BAR -> {
                            if (!scrubbing) { scrubbing = true; scrubPos = positionMs }
                            scrubPos = (scrubPos - scrubStep()).coerceAtLeast(0L)
                        }
                        else -> {
                            val order = transportOrder(nextEpisodeId != null, skipIntroPillVisible)
                            val idx = order.indexOf(focus)
                            if (idx > 0) focus = order[idx - 1]
                        }
                    }
                },
                onRight = {
                    wake()
                    when {
                        nextUpVisible -> nuFocus = NuFocus.STAY
                        epRailOpen -> episodes?.let { focusedEpIdx = (focusedEpIdx + 1).coerceAtMost(it.size - 1) }
                        pickerOpen -> {
                            pickerTab = 1
                            pickerIdx = if (selectedSub < 0) 0 else (selectedSub + 1).coerceAtMost(subOptions.lastIndex)
                        }
                        focus == PlFocus.SEEK_BAR -> {
                            if (!scrubbing) { scrubbing = true; scrubPos = positionMs }
                            scrubPos = (scrubPos + scrubStep()).coerceAtMost(durationMs)
                        }
                        else -> {
                            val order = transportOrder(nextEpisodeId != null, skipIntroPillVisible)
                            val idx = order.indexOf(focus)
                            if (idx < order.lastIndex) focus = order[idx + 1]
                        }
                    }
                },
                onUp = {
                    wake()
                    when {
                        epRailOpen -> { epRailOpen = false; scheduleHide() }
                        pickerOpen -> if (pickerIdx > 0) pickerIdx--
                        nextUpVisible -> {}
                        focus != PlFocus.SEEK_BAR -> focus = PlFocus.SEEK_BAR
                        else -> {}
                    }
                },
                onDown = {
                    wake()
                    when {
                        nextUpVisible -> {}
                        epRailOpen -> {}
                        pickerOpen -> {
                            val size = if (pickerTab == 0) audioTracks.size.coerceAtLeast(1) else subOptions.size
                            if (pickerIdx < size - 1) pickerIdx++
                        }
                        focus == PlFocus.SEEK_BAR -> {
                            if (scrubbing) commitScrub()
                            focus = PlFocus.PLAY
                        }
                        // R182 — matches the design prototype's own ArrowDown handling for 'skipintro'.
                        focus == PlFocus.SKIP_INTRO -> focus = PlFocus.PLAY
                        episodes != null -> { epRailOpen = true; chromeVisible = true }
                        else -> {}
                    }
                },
                onSelect = {
                    // R178 (FR-RV-SEL1-2): captured *before* wake() (which flips chromeVisible to true
                    // unconditionally) — this is the only way to know whether chrome was actually hidden
                    // when Select was pressed. Defense in depth alongside hideChrome() (FR-RV-SEL1-1):
                    // even if some future path hides chrome without resetting `focus`, Select still can't
                    // re-trigger a hidden control's action while nothing is visibly focused.
                    //
                    // Bug fix: the Skip Intro pill (skipIntroPillVisible) and the Next-up/credits card
                    // (nextUpVisible) both render independent of chromeVisible by design — chrome can
                    // still be auto-hidden (CHROME_HIDE_MS) when either shows, since neither forces
                    // chromeVisible = true. The plain !chromeVisible check treated that as "everything's
                    // hidden, just wake" and swallowed the real Select into a togglePlay(), even though
                    // the pill/card was visibly focused on screen the whole time. Both are genuinely
                    // interactive whenever they're showing, regardless of chrome.
                    val wasHidden = !chromeVisible && focus != PlFocus.SKIP_INTRO && !nextUpVisible
                    wake()
                    if (wasHidden) {
                        // FR-RV-SEL1-3: togglePlay() already calls wake(), so chrome is revealed too.
                        togglePlay()
                    } else when {
                        nextUpVisible -> {
                            if (nuFocus == NuFocus.STAY) stayThrough()
                            else when (creditsCardMode) {
                                CreditsCardMode.STINGER -> skipToScene()
                                CreditsCardMode.NEXT_EPISODE -> advanceNext()
                                CreditsCardMode.SKIP_CREDITS -> skipCredits()
                            }
                        }
                        epRailOpen -> chooseEpisode()
                        pickerOpen -> choosePick()
                        focus == PlFocus.SKIP_INTRO -> skipIntro()
                        focus == PlFocus.SEEK_BAR -> {
                            if (scrubbing) commitScrub() else { scrubbing = true; scrubPos = positionMs }
                        }
                        focus == PlFocus.PLAY     -> togglePlay()
                        focus == PlFocus.SKIP_BACK -> skip(-SKIP_BACK_MS)
                        focus == PlFocus.SKIP_FWD  -> skip(SKIP_FWD_MS)
                        focus == PlFocus.TRACKS    -> {
                            pickerOpen = true
                            pickerIdx = if (pickerTab == 0) selectedAudio
                                        else (selectedSub + 1).coerceIn(0, subOptions.lastIndex)
                        }
                        focus == PlFocus.NEXT_EP   -> advanceNext()
                        focus == PlFocus.BACK      -> onBack()
                        else -> {}
                    }
                },
                onBack = {
                    when {
                        pickerOpen    -> { pickerOpen = false; wake() }
                        epRailOpen    -> { epRailOpen = false; wake() }
                        nextUpVisible -> stayThrough()
                        scrubbing     -> { scrubbing = false; wake() }
                        // R112: if the controls are showing, Back just hides them → fullscreen video.
                        // Only Back with nothing on screen leaves the player (so it takes two presses).
                        chromeVisible -> hideChrome()
                        else          -> onBack()
                    }
                },
                // R44: physical remote / keyboard transport keys → playback actions, regardless of
                // which on-screen control is focused and even when the chrome is hidden.
                onMediaKey = { mk ->
                    when (mk) {
                        MediaKey.PLAY_PAUSE   -> togglePlay()
                        MediaKey.PLAY         -> if (!isPlaying) togglePlay() else wake()
                        MediaKey.PAUSE        -> if (isPlaying) togglePlay() else wake()
                        MediaKey.FAST_FORWARD -> skip(SKIP_FWD_MS)
                        MediaKey.REWIND       -> skip(-SKIP_BACK_MS)
                        MediaKey.NEXT         -> if (nextEpisodeId != null) advanceNext() else wake()
                        MediaKey.PREVIOUS     -> { player.seekTo(0); positionMs = 0; wake() }
                        MediaKey.STOP         -> onBack()
                    }
                },
                // R157 (FR-R157-2.4) — on web, a click on empty space toggles chrome instead of
                // activating the focused control (the web convention; a click has no D-pad "focus"
                // concept to act on). TV: playerTapTogglesChrome is false and LocalHandset is always
                // false (a TV window is never handset-sized), so this stays null there and
                // dpadFocusable's default (onTap falls back to onSelect) preserves the D-pad behaviour.
                // Bug fix: ravilo-ui's androidMain is shared by both the TV and phone apps, so the old
                // `playerTapTogglesChrome` platform constant (false for "Android") couldn't distinguish
                // them — a phone tap fell through to onSelect, i.e. "activate whatever the D-pad focus
                // happens to be on," not the tap-to-toggle-chrome behaviour every mobile video player
                // has.
                // Bug fix: this used to read LocalCompact (< 600dp window WIDTH), which flips to false
                // the instant a phone rotates to landscape for playback — width becomes the long edge.
                // Once the auto-hide timer fired, tapping the video could never bring the controls back
                // on a phone (confirmed live: pause worked once from the initially-visible chrome, but
                // there was no way to reveal it again after it hid). LocalHandset uses the SMALLEST side
                // (Android's own "smallest width" convention), so it stays true across rotation.
                onTap = if (playerTapTogglesChrome || LocalHandset.current) {
                    { if (chromeVisible) hideChrome() else wake() }
                } else null,
            )
    ) {
        // ── Platform video surface (SurfaceView on Android, <video> element on WASM) ───
        PlayerVideoSurface(player, Modifier.fillMaxSize())

        // ── Dim scrim (deepens when chrome is up or paused) ──────────────────
        val dimAlpha = when {
            chromeVisible && !isPlaying -> 0.50f
            chromeVisible               -> 0.34f
            else                        -> 0f
        }
        if (dimAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
        }

        // ── Loading overlay ───────────────────────────────────────────────────
        if (sessionState is PlayerSessionState.Loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BufferingSpinner(colors)
                Spacer(Modifier.height(48.dp))
                // R180 (FR-RV-ASP1-2) — a neutral loading string regardless of cause; naming the PGS
                // burn-in restream here would leak the delivery method, which the picker keeps invisible.
                Text(
                    str("loading"),
                    color = Color.White.copy(0.7f), fontSize = 18.sp,
                )
            }
        }

        // ── Error overlay ──────────────────────────────────────────────────────
        // Bug fix: PlayerSessionState.Error was never checked anywhere in this screen — a failed
        // startPlayback (all retries exhausted, see PlayerStore.startSession) used to leave the player
        // sitting on the outgoing episode's frozen last frame forever, with no indication anything had
        // gone wrong and no way to recover short of backing out entirely. Reported as "stuck" with
        // "auto play next doesn't work".
        val sessionError = sessionState as? PlayerSessionState.Error
        if (sessionError != null) {
            val retryFR = remember { FocusRequester() }
            val backFR = remember { FocusRequester() }
            LaunchedEffect(sessionState) { runCatching { retryFR.requestFocus() } }
            var retryFocused by remember { mutableStateOf(false) }
            var backFocused by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(str("error.generic"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text(sessionError.message, color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .background(if (retryFocused) Color.White else Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .border(2.dp, if (retryFocused) colors.focusRing else Color.Transparent, RoundedCornerShape(8.dp))
                                .dpadFocusable(
                                    focusRequester = retryFR,
                                    onFocused = { retryFocused = true },
                                    onBlurred = { retryFocused = false },
                                    onSelect = { store.startSession(itemId, positionProvider = { positionMs }, isPausedProvider = { !isPlaying }) },
                                )
                                .padding(horizontal = 22.dp, vertical = 12.dp),
                        ) {
                            Text(str("action.retry"), color = if (retryFocused) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(
                            modifier = Modifier
                                .background(if (backFocused) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                .border(2.dp, if (backFocused) colors.focusRing else Color.Transparent, RoundedCornerShape(8.dp))
                                .dpadFocusable(
                                    focusRequester = backFR,
                                    onFocused = { backFocused = true },
                                    onBlurred = { backFocused = false },
                                    onSelect = onBack,
                                )
                                .padding(horizontal = 22.dp, vertical = 12.dp),
                        ) {
                            Text(str("action.back"), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ── Buffering spinner (while playing but stalled) ────────────────────
        // (In a real integration the engine signals buffering; we skip this for now)

        // ── Center pause flash ────────────────────────────────────────────────
        // R90: scale-bounce matches the CSS plflash spec — pop in from 0.9→1.0, expand out to 1.4.
        AnimatedVisibility(
            pauseFlash,
            enter = scaleIn(initialScale = RaviloMotion.PAUSE_FLASH_FROM_SCALE, animationSpec = tween(RaviloMotion.PAUSE_FLASH_IN_MS)) +
                    fadeIn(tween(RaviloMotion.PAUSE_FLASH_IN_MS)),
            exit  = scaleOut(targetScale = RaviloMotion.PAUSE_FLASH_TO_SCALE, animationSpec = tween(RaviloMotion.PAUSE_FLASH_OUT_MS)) +
                    fadeOut(tween(RaviloMotion.PAUSE_FLASH_OUT_MS)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(0.42f))
                        .border(2.dp, Color.White.copy(0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(28.dp)) {
                        if (pauseFlashIsPlay) {
                            val path = Path().apply {
                                moveTo(size.width * 0.15f, 0f)
                                lineTo(size.width, size.height / 2)
                                lineTo(size.width * 0.15f, size.height)
                                close()
                            }
                            drawPath(path, Color.White)
                        } else {
                            val bw = size.width * 0.28f
                            val bh = size.height
                            val gap = size.width * 0.15f
                            val cx = size.width / 2
                            val cy = size.height / 2
                            drawRect(Color.White, topLeft = Offset(cx - gap / 2 - bw, cy - bh / 2), size = Size(bw, bh))
                            drawRect(Color.White, topLeft = Offset(cx + gap / 2, cy - bh / 2), size = Size(bw, bh))
                        }
                    }
                }
            }
        }

        // ── Player chrome (auto-hiding transport + metadata) ──────────────────
        // R90: 300ms fade-in / 200ms fade-out matches the CSS spec (was enter=200/exit=300, reversed).
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(RaviloMotion.CHROME_FADE_IN_MS)),
            exit = fadeOut(tween(RaviloMotion.CHROME_FADE_OUT_MS)),
        ) {
            PlayerChrome(
                colors          = colors,
                itemTitle       = itemTitle,
                itemKicker      = itemKicker,
                positionMs      = positionMs,
                durationMs      = durationMs,
                bufferedMs      = bufferedMs,
                scrubbing       = scrubbing,
                scrubPos        = scrubPos,
                isPlaying       = isPlaying,
                focus           = focus,
                hasNextEp       = nextEpisodeId != null,
                isSeries        = episodes != null,
                epRailOpen      = epRailOpen,
                pickerOpen      = pickerOpen,
                nextUpVisible   = nextUpVisible,
                directPlay      = (sessionState as? PlayerSessionState.Ready)?.ticket?.directPlay ?: true,
                container       = (sessionState as? PlayerSessionState.Ready)?.ticket?.container ?: "",
                // R157 — PlayerChrome is a stateless presentational composable; it reports which
                // logical control was clicked/hovered and this dispatcher (which has wake/skip/
                // togglePlay/etc in scope) does the actual work, mirroring the root's onSelect dispatch.
                onControlClick = { clicked ->
                    wake()
                    when (clicked) {
                        PlFocus.BACK      -> onBack()
                        PlFocus.SKIP_BACK -> skip(-SKIP_BACK_MS)
                        PlFocus.PLAY      -> togglePlay()
                        PlFocus.SKIP_FWD  -> skip(SKIP_FWD_MS)
                        PlFocus.TRACKS    -> {
                            pickerOpen = true
                            pickerIdx = if (pickerTab == 0) selectedAudio else (selectedSub + 1).coerceIn(0, subOptions.lastIndex)
                        }
                        PlFocus.NEXT_EP   -> advanceNext()
                        else -> {}
                    }
                },
                onControlHover = { hovered -> focus = hovered },
                onSeekStart = { ms -> wake(); focus = PlFocus.SEEK_BAR; scrubbing = true; scrubPos = ms },
                onSeekDrag = { ms -> scrubPos = ms },
                onSeekEnd = { commitScrub() },
            )
        }

        // ── Skip Intro pill (R182, FR-RV-SKIP1-1) ─────────────────────────────
        // Bottom-end, but padded up well above the transport row (design: right:64px/bottom:210px) so it
        // never overlaps the seek bar/controls — those two never show at once in practice anyway (intro
        // is at the episode's start, the transport padding here is what matters).
        AnimatedVisibility(
            visible = skipIntroPillVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 160.dp),
        ) {
            SkipIntroPill(
                colors = colors,
                countdown = skipIntroCountdownSecs,
                totalSecs = skipSecs,
                focused = focus == PlFocus.SKIP_INTRO,
            )
        }

        // ── Track picker popup (Audio / Subtitles) ────────────────────────────
        AnimatedVisibility(
            visible = pickerOpen,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            TrackPicker(
                colors        = colors,
                pickerTab     = pickerTab,
                pickerIdx     = pickerIdx,
                audioTracks   = audioTracks,
                subOptions    = subOptions,
                nativeSubtitleTracks = subtitleTracks,
                selectedAudio = selectedAudio,
                selectedSub   = selectedSub,
                originalLanguage = currentOriginalLanguage,
            )
        }

        // ── Next-up card ──────────────────────────────────────────────────────
        // R90: slide-up from below + fade matches the CSS translateY(112%)→0 spec (was fade-only).
        AnimatedVisibility(
            visible = nextUpVisible,
            enter = slideInVertically { it } + fadeIn(tween(RaviloMotion.NEXT_UP_SLIDE_MS)),
            exit  = slideOutVertically { it } + fadeOut(tween(RaviloMotion.NEXT_UP_SLIDE_MS)),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            NextUpCard(
                colors         = colors,
                mode           = creditsCardMode,
                itemKicker     = itemKicker,
                isSeries       = episodes != null,
                nextEpLabel    = nextEpisodeLabel,
                nextEpTitle    = nextEpisodeTitle,
                // Bug fix: the card's thumbnail was always an empty placeholder box — `episodes` (the
                // same grouped list `currentEpIndex` indexes into) already carries each entry's still(s),
                // so the next episode's is one lookup away rather than needing new plumbing end-to-end.
                nextEpStillUrls = episodes?.getOrNull(currentEpIndex + 1)?.stillUrls ?: emptyList(),
                countdown      = countdown,
                totalSecs      = skipSecs,
                nuFocus        = nuFocus,
            )
        }

        // ── Episode rail (series only) ────────────────────────────────────────
        val epList = episodes
        if (epList != null) {
            AnimatedVisibility(
                visible = epRailOpen,
                enter = slideInVertically { it } + fadeIn(tween(300)),
                exit = slideOutVertically { it } + fadeOut(tween(250)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                EpisodeRail(
                    colors          = colors,
                    episodes        = epList,
                    currentEpIndex  = currentEpIndex,
                    focusedEpIdx    = focusedEpIdx,
                )
            }
        }
    }
}

// ─── Player chrome overlay ────────────────────────────────────────────────────

@Composable
private fun PlayerChrome(
    colors: RaviloColors,
    itemTitle: String,
    itemKicker: String?,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    isPlaying: Boolean,
    focus: PlFocus,
    hasNextEp: Boolean,
    isSeries: Boolean,
    epRailOpen: Boolean,
    pickerOpen: Boolean,
    nextUpVisible: Boolean,
    directPlay: Boolean,
    container: String,
    onControlClick: (PlFocus) -> Unit,
    onControlHover: (PlFocus) -> Unit,
    onSeekStart: (Long) -> Unit,
    onSeekDrag: (Long) -> Unit,
    onSeekEnd: () -> Unit,
) {
    val topScrim = remember {
        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))
    }
    val botScrim = remember {
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))
    }

    Box(Modifier.fillMaxSize()) {
        // Top gradient
        Box(Modifier.fillMaxWidth().height(230.dp).align(Alignment.TopCenter).background(topScrim))
        // Bottom gradient
        Box(Modifier.fillMaxWidth().height(280.dp).align(Alignment.BottomCenter).background(botScrim))

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(
                focused = focus == PlFocus.BACK,
                // Matches the root's onSelect { focus == PlFocus.BACK -> onBack() } — a direct click
                // on the visible back button navigates immediately (not the two-press hardware-Back
                // semantics in the root's onBack, which first closes pickers/rail/chrome).
                onClick = { onControlClick(PlFocus.BACK) },
                onHover = { onControlHover(PlFocus.BACK) },
            )
            Spacer(Modifier.weight(1f))
            StreamPill(colors = colors, directPlay = directPlay, container = container)
        }

        // Bottom transport
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .padding(top = 12.dp, bottom = 16.dp),
        ) {
            // Metadata
            itemKicker?.let {
                Text(
                    text = it.uppercase(),
                    color = colors.accentSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = itemTitle,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SpaceGrotesk,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))

            // Seek row
            SeekRow(
                colors     = colors,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                scrubbing  = scrubbing,
                scrubPos   = scrubPos,
                barFocused = focus == PlFocus.SEEK_BAR,
                // R157 (FR-R157-2.3) — click-to-seek / drag-to-scrub; forwarded from PlayerScreen,
                // which owns the actual scrub state and commitScrub().
                onSeekStart = onSeekStart,
                onSeekDrag = onSeekDrag,
                onSeekEnd = onSeekEnd,
            )

            Spacer(Modifier.height(6.dp))

            // Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SkipButton(
                    label = "−10s", focused = focus == PlFocus.SKIP_BACK,
                    onClick = { onControlClick(PlFocus.SKIP_BACK) },
                    onHover = { onControlHover(PlFocus.SKIP_BACK) },
                )
                PlayPauseButton(
                    isPlaying = isPlaying, focused = focus == PlFocus.PLAY,
                    onClick = { onControlClick(PlFocus.PLAY) },
                    onHover = { onControlHover(PlFocus.PLAY) },
                )
                SkipButton(
                    label = "+30s", focused = focus == PlFocus.SKIP_FWD,
                    onClick = { onControlClick(PlFocus.SKIP_FWD) },
                    onHover = { onControlHover(PlFocus.SKIP_FWD) },
                )
                Spacer(Modifier.weight(1f))
                TrackButton(
                    label = str("player.audio_subs"), focused = focus == PlFocus.TRACKS,
                    onClick = { onControlClick(PlFocus.TRACKS) },
                    onHover = { onControlHover(PlFocus.TRACKS) },
                )
                if (hasNextEp) TrackButton(
                    label = ">> ${str("player.next")}", focused = focus == PlFocus.NEXT_EP,
                    onClick = { onControlClick(PlFocus.NEXT_EP) },
                    onHover = { onControlHover(PlFocus.NEXT_EP) },
                )
            }

            // Episode chip (series, only when rail/picker/nextup are closed)
            if (isSeries && !epRailOpen && !pickerOpen && !nextUpVisible) {
                Spacer(Modifier.height(12.dp))
                EpisodeChip(colors)
            }
        }
    }
}

// ─── Seek row ─────────────────────────────────────────────────────────────────

@Composable
private fun SeekRow(
    colors: RaviloColors,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    barFocused: Boolean,
    onSeekStart: (Long) -> Unit = {},
    onSeekDrag: (Long) -> Unit = {},
    onSeekEnd: () -> Unit = {},
) {
    val timeColor = Color.White.copy(alpha = 0.8f)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = (if (scrubbing) scrubPos else positionMs).toTimestamp(),
            color = timeColor,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.weight(1f)) {
            SeekBar(
                colors      = colors,
                positionMs  = positionMs,
                durationMs  = durationMs,
                bufferedMs  = bufferedMs,
                scrubbing   = scrubbing,
                scrubPos    = scrubPos,
                focused     = barFocused,
                onSeekStart = onSeekStart,
                onSeekDrag  = onSeekDrag,
                onSeekEnd   = onSeekEnd,
            )
        }
        if (durationMs > 0) {
            Text(
                text = durationMs.toTimestamp(),
                color = timeColor.copy(alpha = 0.6f),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ─── Seek bar (Canvas) ────────────────────────────────────────────────────────

@Composable
private fun SeekBar(
    colors: RaviloColors,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    focused: Boolean,
    // R157 (FR-R157-2.3) — click-to-seek / drag-to-scrub. Positions are already clamped to
    // [0, durationMs] by the caller (mirroring the existing D-pad scrub step logic).
    onSeekStart: (Long) -> Unit = {},
    onSeekDrag: (Long) -> Unit = {},
    onSeekEnd: () -> Unit = {},
) {
    val played   = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val scrubFrac = if (durationMs > 0 && scrubbing) (scrubPos.toFloat() / durationMs).coerceIn(0f, 1f) else played

    val barH by animateDpAsState(if (focused) 12.dp else 8.dp, label = "barH")
    val accent   = colors.accent
    val accentS  = colors.accentSecondary
    val ringColor = colors.focusRing

    fun posAt(x: Float, widthPx: Int): Long {
        if (durationMs <= 0 || widthPx <= 0) return 0L
        return ((x / widthPx) * durationMs).toLong().coerceIn(0L, durationMs)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(durationMs) {
                detectTapGestures(onTap = { offset ->
                    onSeekStart(posAt(offset.x, size.width))
                    onSeekEnd()
                })
            }
            .pointerInput(durationMs) {
                detectDragGestures(
                    onDragStart = { offset -> onSeekStart(posAt(offset.x, size.width)) },
                    onDrag = { change, _ -> change.consume(); onSeekDrag(posAt(change.position.x, size.width)) },
                    onDragEnd = { onSeekEnd() },
                    onDragCancel = { onSeekEnd() },
                )
            }
    ) {
        val barHPx = barH.toPx()
        val y = center.y
        val w = size.width
        val corner = CornerRadius(barHPx / 2)
        val barTop = y - barHPx / 2

        // Background track
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, barTop), Size(w, barHPx), corner)

        // Buffered
        if (buffered > 0f)
            drawRoundRect(Color.White.copy(alpha = 0.30f), Offset(0f, barTop), Size(w * buffered, barHPx), corner)

        // Played (accent gradient)
        if (played > 0f) {
            val grad = Brush.linearGradient(listOf(accent, accentS), start = Offset.Zero, end = Offset(w, 0f))
            drawRoundRect(grad, Offset(0f, barTop), Size(w * played, barHPx), corner)
        }

        // Scrub ghost bar
        if (scrubbing) {
            val ghostX = w * scrubFrac
            drawRect(Color.White, Offset(ghostX - 2.dp.toPx(), y - 13.dp.toPx()), Size(4.dp.toPx(), 26.dp.toPx()))
        }

        // Handle
        val handleR = if (focused) 13.dp.toPx() else 10.dp.toPx()
        val handleX = w * played
        if (focused) {
            drawCircle(ringColor.copy(alpha = 0.45f), handleR + 6.dp.toPx(), Offset(handleX, y))
        }
        drawCircle(Color.White, handleR, Offset(handleX, y))
    }
}

// ─── Control buttons ──────────────────────────────────────────────────────────

@Composable
private fun PlayPauseButton(isPlaying: Boolean, focused: Boolean, onClick: () -> Unit = {}, onHover: () -> Unit = {}) {
    val colors = RaviloTheme.colors
    val grad = remember(colors.accent, colors.accentSecondary) { colors.accentGradient }
    val size by animateDpAsState(if (focused) 50.dp else 44.dp, label = "ppScale")
    val glyphColor = Color.White
    Box(
        modifier = Modifier
            .size(size)
            .then(if (focused) Modifier.shadow(14.dp, CircleShape, spotColor = colors.focusGlow) else Modifier)
            .clip(CircleShape)
            .background(if (focused) grad else Brush.linearGradient(listOf(Color.White.copy(0.14f), Color.White.copy(0.14f))))
            .border(
                width = 1.dp,
                color = if (focused) colors.focusRing.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.22f),
                shape = CircleShape,
            )
            // R157 (FR-R157-2.1/2.2) — a real pointer target: click activates, hover moves logical
            // focus here. Deliberately NOT dpadFocusable/.focusable() — the root Box is the sole real
            // Compose-focus owner (D-pad navigation is hand-rolled via the PlFocus enum); making every
            // button its own focus node would fight that model and risk breaking D-pad/TV navigation.
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .hoverToCall(onHover),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(if (isPlaying) 16.dp else 14.dp)) {
            val cw = this.size.width
            val ch = this.size.height
            if (isPlaying) {
                // Two vertical bars (pause)
                val bw = cw * 0.28f
                val gap = cw * 0.16f
                val cx = cw / 2
                drawRect(glyphColor, topLeft = Offset(cx - gap / 2 - bw, 0f), size = Size(bw, ch))
                drawRect(glyphColor, topLeft = Offset(cx + gap / 2, 0f), size = Size(bw, ch))
            } else {
                // Right-pointing triangle (play)
                val path = Path().apply {
                    moveTo(cw * 0.15f, 0f)
                    lineTo(cw, ch / 2)
                    lineTo(cw * 0.15f, ch)
                    close()
                }
                drawPath(path, glyphColor)
            }
        }
    }
}

// R158: transport-control focus chrome matches the app-wide language (accent ring + focusGlow +
// draw-only scale) instead of an opaque fill flip — the resting translucent pill never changes color.
@Composable
private fun SkipButton(label: String, focused: Boolean, onClick: () -> Unit = {}, onHover: () -> Unit = {}) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier
            .scale(if (focused) 1.06f else 1f)
            .height(40.dp)
            .then(if (focused) Modifier.shadow(14.dp, RoundedCornerShape(10.dp), spotColor = colors.focusGlow) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) colors.accent else Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(10.dp),
            )
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .hoverToCall(onHover)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (focused) 1f else 0.85f),
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TrackButton(label: String, focused: Boolean, onClick: () -> Unit = {}, onHover: () -> Unit = {}) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier
            .scale(if (focused) 1.04f else 1f)
            .height(40.dp)
            .then(if (focused) Modifier.shadow(14.dp, RoundedCornerShape(10.dp), spotColor = colors.focusGlow) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) colors.accent else Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(10.dp),
            )
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .hoverToCall(onHover)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = if (focused) 1f else 0.85f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun BackButton(focused: Boolean, onClick: () -> Unit = {}, onHover: () -> Unit = {}) {
    val colors = RaviloTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .hoverToCall(onHover),
    ) {
        Box(
            modifier = Modifier
                .scale(if (focused) 1.06f else 1f)
                .size(34.dp)
                .then(if (focused) Modifier.shadow(14.dp, CircleShape, spotColor = colors.focusGlow) else Modifier)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) colors.accent else Color.White.copy(alpha = 0.22f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", color = Color.White.copy(alpha = if (focused) 1f else 0.85f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = str("action.back"),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StreamPill(colors: RaviloColors, directPlay: Boolean, container: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PillBox {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (directPlay) Color(0xFF2DD49A) else colors.accentSecondary)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (directPlay) "DIRECT PLAY" else "HLS",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
        }
        if (container.isNotEmpty()) {
            PillBox {
                Text(
                    text = container.uppercase(),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun PillBox(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun EpisodeChip(colors: RaviloColors) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(30.dp))
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape).background(colors.accentGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text("↓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(str("detail.episodes"), color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Track picker popup ───────────────────────────────────────────────────────

// R180 (FR-RV-ASP1-1) — one row's presentation data, resolved once per recomposition from either a
// PlayerAudioTrack or a PlayerSubtitleTrack (or neither, for the synthetic Off row).
private data class PickerRow(
    val language: String?,
    val fallbackLabel: String,
    val isOff: Boolean,
    val badges: List<String>,
)

@Composable
private fun TrackPicker(
    colors: RaviloColors,
    pickerTab: Int,
    pickerIdx: Int,
    audioTracks: List<PlayerAudioTrack>,
    subOptions: List<PlayerSubtitleTrack?>,
    nativeSubtitleTracks: List<PlayerSubtitleTrack>,
    selectedAudio: Int,
    selectedSub: Int,
    originalLanguage: String?,
) {
    val lang = LocalLang.current
    val audioFlag = audioTracks.getOrNull(selectedAudio)?.language?.lowercase()?.let { LANG_CC[it] }
    val subFlag = if (selectedSub >= 0) nativeSubtitleTracks.getOrNull(selectedSub)?.language?.lowercase()?.let { LANG_CC[it] } else null

    Box(
        modifier = Modifier
            .padding(end = 48.dp, bottom = 36.dp)
            .width(520.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E1119).copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(22.dp),
    ) {
        Column {
            // Tabs
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerTab(str("player.tab_audio"), pickerTab == 0, audioFlag)
                PickerTab(str("player.tab_subtitles"), pickerTab == 1, subFlag)
            }
            Spacer(Modifier.height(14.dp))

            // Options — R180: every row leads with a flag/glyph and a plain endonym name; badges are
            // a fixed jargon-free vocabulary derived in audioBadges()/subtitleBadges(), never a codec
            // or delivery-method string.
            // Bug fix: this used to recompute on every recomposition — including the per-track title
            // regex matching inside audioBadges()/subtitleBadges() — even though TrackPicker
            // recomposes on every single D-pad Up/Down while the picker is open (pickerIdx is a
            // parameter). remember() so it only re-runs when the tab or the underlying track data
            // actually changes.
            val rows: List<PickerRow> = remember(pickerTab, audioTracks, subOptions, originalLanguage, lang) {
                if (pickerTab == 0) {
                    val effectiveAudio = audioTracks.ifEmpty { listOf(PlayerAudioTrack(0, "Default", null)) }
                    effectiveAudio.map { PickerRow(it.language, it.label, false, audioBadges(it, originalLanguage, lang)) }
                } else {
                    subOptions.map { sub ->
                        if (sub == null) PickerRow(null, "", true, emptyList())
                        else PickerRow(sub.language, sub.label, false, subtitleBadges(sub, lang))
                    }
                }
            }
            val selectedInTab = if (pickerTab == 0) selectedAudio else selectedSub + 1
            val lastIdx = (rows.size - 1).coerceAtLeast(0)
            val listState = rememberLazyListState()

            // R180 — open pre-scrolled to the active row (jump, no animation). Keyed on pickerTab: it
            // "changes" (from unset) on TrackPicker's first composition — i.e. every time the picker
            // freshly opens, since AnimatedVisibility disposes this composable on close — and again on
            // every later tab switch, both of which should reset scroll position outright rather than
            // animate from wherever the other tab happened to be scrolled.
            LaunchedEffect(pickerTab) {
                listState.scrollToItem(pickerIdx.coerceIn(0, lastIdx))
            }
            // R180 — keep the D-pad-focused row visible as it moves (10+ track titles genuinely
            // overflow a fixed-height list — verified: a real title has 7 audio + 13 subtitle tracks).
            LaunchedEffect(pickerIdx) {
                listState.animateScrollToItem(pickerIdx.coerceIn(0, lastIdx))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(rows) { i, row ->
                    PickerOption(
                        language      = row.language,
                        fallbackLabel = row.fallbackLabel,
                        isOff         = row.isOff,
                        badges        = row.badges,
                        selected      = i == selectedInTab,
                        focused       = i == pickerIdx,
                        colors        = colors,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerTab(label: String, active: Boolean, flagRes: DrawableResource? = null) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.Transparent)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) colors.accent else Color.White.copy(0.18f),
                shape = RoundedCornerShape(30.dp),
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = label,
                color = if (active) colors.text else colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (flagRes != null) {
                Image(
                    painter = painterResource(flagRes),
                    contentDescription = null,
                    modifier = Modifier
                        .size(width = 20.dp, height = 14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(3.dp)),
                )
            }
        }
    }
}

/** R180 (FR-RV-ASP1-1) — the picker row's primary name: endonym first, then the English humanized
 *  name, then whatever label the track already carries (untitled/uncoded tracks — a real, expected
 *  case, e.g. commentary tracks with no language tag). */
private fun pickerName(language: String?, fallbackLabel: String): String =
    endonym(language) ?: languageName(language) ?: fallbackLabel

@Composable
private fun PickerOption(
    language: String?,
    fallbackLabel: String,
    isOff: Boolean,
    badges: List<String>,
    selected: Boolean,
    focused: Boolean,
    colors: RaviloColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) colors.focusRing.copy(0.7f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Radio tick
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(2.dp, if (selected) colors.accent else Color.White.copy(0.35f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("✓", color = colors.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        // Flag / glyph — never a codec or delivery-method cue (FR-RV-ASP1-2).
        val flagRes = if (!isOff) language?.lowercase()?.let { LANG_CC[it] } else null
        Box(modifier = Modifier.size(width = 40.dp, height = 30.dp), contentAlignment = Alignment.Center) {
            when {
                flagRes != null -> Image(
                    painter = painterResource(flagRes),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(5.dp))
                        .border(1.dp, Color.White.copy(0.18f), RoundedCornerShape(5.dp)),
                )
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.White.copy(0.07f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val tint = Color.White.copy(0.55f)
                    if (isOff) PickerGlyphOff(Modifier.size(20.dp), tint) else PickerGlyphMic(Modifier.size(20.dp), tint)
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val name = if (isOff) str("off") else pickerName(language, fallbackLabel)
                Text(name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val suffix = if (isOff) null else regionSuffix(fallbackLabel)
                if (suffix != null) Text(suffix, color = colors.textSecondary, fontSize = 12.sp)
            }
            if (badges.isNotEmpty()) {
                Row(modifier = Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { badge ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(0.06f))
                                .border(1.dp, Color.White.copy(0.14f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(badge, color = colors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/** R180 — hand-drawn crossed-subtitle glyph for the Off row (mirrors the design's `I.subsoff` SVG). */
@Composable
private fun PickerGlyphOff(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val strokeW = w * 0.10f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.18f),
            size = Size(w * 0.80f, h * 0.64f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(strokeW, cap = StrokeCap.Round),
        )
        drawLine(tint, Offset(w * 0.26f, h * 0.5f), Offset(w * 0.50f, h * 0.5f), strokeW, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.58f, h * 0.5f), Offset(w * 0.74f, h * 0.5f), strokeW, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.08f, h * 0.08f), Offset(w * 0.92f, h * 0.92f), strokeW, StrokeCap.Round)
    }
}

/** R180 — hand-drawn mic glyph for a track with no resolvable language (e.g. an untagged commentary
 *  track) — mirrors the design's `I.mic` SVG. */
@Composable
private fun PickerGlyphMic(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val strokeW = w * 0.10f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.36f, h * 0.06f),
            size = Size(w * 0.28f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(strokeW, cap = StrokeCap.Round),
        )
        drawArc(
            color = tint,
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.26f),
            size = Size(w * 0.68f, h * 0.48f),
            style = Stroke(strokeW, cap = StrokeCap.Round),
        )
        drawLine(tint, Offset(w * 0.5f, h * 0.68f), Offset(w * 0.5f, h * 0.88f), strokeW, StrokeCap.Round)
    }
}

// ─── Next-up card ─────────────────────────────────────────────────────────────

@Composable
private fun NextUpCard(
    colors: RaviloColors,
    mode: CreditsCardMode,
    itemKicker: String?,
    isSeries: Boolean,
    nextEpLabel: String?,
    nextEpTitle: String?,
    nextEpStillUrls: List<String?>,
    countdown: Int,
    totalSecs: Int,
    nuFocus: NuFocus,
) {
    // R182 (FR-RV-SKIP1-2) — everything below the thumbnail/ring differs by mode; the NEXT_EPISODE case
    // is exactly the pre-R182 card, unchanged. Never more than two buttons in any mode.
    val kickerText = if (mode == CreditsCardMode.NEXT_EPISODE) str("player.up_next") else str("player.credits_kicker")
    val subLabel = if (mode == CreditsCardMode.NEXT_EPISODE) nextEpLabel else itemKicker
    val titleText = when (mode) {
        CreditsCardMode.NEXT_EPISODE -> nextEpTitle ?: str("detail.episode")
        CreditsCardMode.STINGER -> str("player.stinger_title")
        CreditsCardMode.SKIP_CREDITS -> if (isSeries) str("player.end_of_episode") else str("player.end_of_movie")
    }
    val primaryLabel = when (mode) {
        CreditsCardMode.NEXT_EPISODE -> "> ${str("player.play_in", mapOf("secs" to countdown.toString()))}"
        CreditsCardMode.STINGER -> str("player.skip_to_scene")
        CreditsCardMode.SKIP_CREDITS -> str("player.skip_credits")
    }
    // R111: compact card tucked into the bottom-right corner (was a 560dp full-width banner).
    Box(
        modifier = Modifier
            .padding(end = 28.dp, bottom = 24.dp)
            .width(360.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0E1119).copy(alpha = 0.95f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Thumbnail (falls back to the flat box colour when no still is available) + countdown ring
            // (NEXT_EPISODE only — a stinger pauses auto-skip entirely and skip-credits simply waits for
            // input, so neither shows a ring at all). When the next entry is itself a multi-episode-file
            // group, nextEpStillUrls carries up to 3 URLs and EpisodeTriptych renders the same
            // seamed-diagonal treatment used everywhere else a group appears.
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1A1D28)),
                contentAlignment = Alignment.BottomEnd,
            ) {
                if (nextEpStillUrls.any { it != null }) {
                    EpisodeTriptych(stillUrls = nextEpStillUrls, modifier = Modifier.matchParentSize())
                }
                if (mode == CreditsCardMode.NEXT_EPISODE) {
                    Box(modifier = Modifier.padding(6.dp)) {
                        CountdownRing(colors, countdown, totalSecs)
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = kickerText,
                    color = colors.accentSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                // R182 — "there's a scene after this" is worth flagging even before the title line.
                if (mode == CreditsCardMode.STINGER) {
                    Text("★ ${str("player.stinger_badge")}", color = colors.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(3.dp))
                subLabel?.let {
                    Text(it, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(
                    text = titleText,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NuButton(
                        label = primaryLabel,
                        focused = nuFocus == NuFocus.PLAY,
                        isPrimary = true,
                        colors = colors,
                    )
                    NuButton(
                        label = str("player.watch_credits"),
                        focused = nuFocus == NuFocus.STAY,
                        isPrimary = false,
                        colors = colors,
                    )
                }
            }
        }
    }
}

@Composable
private fun CountdownRing(colors: RaviloColors, countdown: Int, totalSecs: Int) {
    Canvas(Modifier.size(34.dp)) {
        val r = 13.dp.toPx()
        val stroke = 3.5.dp.toPx()
        drawCircle(Color.White.copy(0.25f), r, style = Stroke(stroke))
        if (countdown > 0 && totalSecs > 0) {
            drawArc(
                color = colors.accent,
                startAngle = -90f,
                sweepAngle = 360f * countdown.toFloat() / totalSecs,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun NuButton(label: String, focused: Boolean, isPrimary: Boolean, colors: RaviloColors) {
    Box(
        modifier = Modifier
            .scale(if (focused) 1.04f else 1f)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused && isPrimary -> Color.White
                    isPrimary -> Color.White.copy(alpha = 0.15f)
                    focused -> Color.White.copy(alpha = 0.18f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) colors.focusRing else Color.White.copy(0.15f),
                shape = RoundedCornerShape(8.dp),
            )
            .then(if (focused) Modifier.shadow(12.dp, RoundedCornerShape(8.dp), spotColor = colors.focusGlow) else Modifier)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused && isPrimary) Color.Black else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ─── Skip Intro pill (R182) ───────────────────────────────────────────────────

@Composable
private fun SkipIntroPill(colors: RaviloColors, countdown: Int, totalSecs: Int, focused: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .scale(if (focused) 1.04f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White else Color(0xFF0E1119).copy(alpha = 0.92f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) colors.focusRing else Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(10.dp),
            )
            .then(if (focused) Modifier.shadow(12.dp, RoundedCornerShape(10.dp), spotColor = colors.focusGlow) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        CountdownRing(colors, countdown, totalSecs)
        Text(
            text = str("player.skip_intro"),
            color = if (focused) Color(0xFF0A0C13) else colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "OK",
            color = if (focused) Color(0xFF0A0C13) else colors.text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background((if (focused) Color(0xFF0A0C13) else Color.White).copy(alpha = 0.12f))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

// ─── Episode rail panel ───────────────────────────────────────────────────────

@Composable
private fun EpisodeRail(
    colors: RaviloColors,
    episodes: List<PlayerEpisodeEntry>,
    currentEpIndex: Int,
    focusedEpIdx: Int,
) {
    val gradient = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.28f to Color.Black.copy(alpha = 0.72f),
                1f to Color.Black.copy(alpha = 0.97f),
            )
        )
    }
    val listState = rememberLazyListState()
    LaunchedEffect(focusedEpIdx) {
        listState.animateScrollToItem(focusedEpIdx.coerceAtLeast(0))
    }

    Box(modifier = Modifier.fillMaxWidth().background(gradient)) {
        Column(modifier = Modifier.padding(top = 32.dp, bottom = 40.dp)) {
            // Header
            val seasonLabel = episodes.firstOrNull()?.kicker?.substringBefore("·")?.trim() ?: ""
            Row(
                modifier = Modifier.padding(horizontal = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(seasonLabel, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                Text(
                    str("player.rail_hint"),
                    color = Color.White.copy(0.45f),
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(14.dp))

            // Episode cards
            LazyRow(
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(episodes) { idx, ep ->
                    val isCurrent = idx == currentEpIndex
                    val isFocused = idx == focusedEpIdx
                    EpisodeRailCard(ep, isCurrent, isFocused, colors)
                }
            }
        }
    }
}

@Composable
private fun EpisodeRailCard(
    ep: PlayerEpisodeEntry,
    isCurrent: Boolean,
    isFocused: Boolean,
    colors: RaviloColors,
) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .scale(if (isFocused) 1.05f else 1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF1A1D28))
                .border(
                    width = if (isFocused) 2.dp else if (isCurrent) 2.dp else 0.dp,
                    color = if (isFocused) colors.focusRing else if (isCurrent) colors.accent else Color.Transparent,
                    shape = RoundedCornerShape(9.dp),
                ),
        ) {
            // Still image(s) — a single image for a lone episode, a seamed triptych for a group
            // (falls back to the flat box colour when no still is available at all).
            if (ep.stillUrls.any { it != null }) {
                EpisodeTriptych(stillUrls = ep.stillUrls, modifier = Modifier.matchParentSize())
                // Dark scrim so the episode number / badge / progress stay legible
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.28f)))
            }
            // Episode number (omitted for a multi-episode-file group — see [PlayerEpisodeEntry]).
            ep.numberLabel?.let { label ->
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            // NOW PLAYING badge
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.accentGradient)
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(str("player.now_playing"), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                }
            }
            // Progress bar
            if (ep.progressPct > 0f || ep.watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(0.30f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (ep.watched) 1f else ep.progressPct)
                            .fillMaxHeight()
                            .background(if (ep.watched) Color(0xFF2DD49A) else colors.accent),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = ep.numberLabel?.let { "$it. ${ep.title}" } ?: ep.title,
            color = if (isCurrent || isFocused) Color.White else Color.White.copy(0.65f),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (ep.durationLabel.isNotEmpty()) {
            Text(ep.durationLabel, color = Color.White.copy(0.40f), fontSize = 11.sp)
        }
    }
}

// ─── Buffering spinner ────────────────────────────────────────────────────────

@Composable
private fun BufferingSpinner(colors: RaviloColors) {
    val rotation by rememberInfiniteTransition(label = "buf")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "rot")
    Canvas(Modifier.size(64.dp)) {
        drawCircle(Color.White.copy(0.16f), radius = 32.dp.toPx() - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
        drawArc(
            color      = colors.accent,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter  = false,
            style      = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

// ─── Utilities ────────────────────────────────────────────────────────────────

private fun Long.toTimestamp(): String {
    val totalSecs = (this / 1000).coerceAtLeast(0)
    val h = (totalSecs / 3600).toInt()
    val m = ((totalSecs % 3600) / 60).toInt()
    val s = (totalSecs % 60).toInt()
    return if (h > 0) "$h:${m.pad2()}:${s.pad2()}" else "$m:${s.pad2()}"
}

private fun Int.pad2() = toString().padStart(2, '0')

// ─── R180: flag-forward picker — endonyms, title-parsing, badges ──────────────

/**
 * R180 (FR-RV-ASP1-1) — the picker's primary row label is the **endonym** (a track's own native name:
 * `da → Dansk`, not `da → Danish`) — unlike [languageName], which returns English names and is used
 * elsewhere (e.g. [pickerName]'s fallback for a language the endonym map doesn't cover). Covers the
 * library's top languages (verified via a DB scan of the real library during the R180 dev review).
 */
private fun endonym(code: String?): String? =
    code?.lowercase()?.trim()?.let { RAVILO_ENDONYMS[it] }

private val RAVILO_ENDONYMS: Map<String, String> = mapOf(
    "en" to "English", "eng" to "English",
    "da" to "Dansk", "dan" to "Dansk",
    "fo" to "Føroyskt", "fao" to "Føroyskt",
    "sv" to "Svenska", "swe" to "Svenska",
    "no" to "Norsk", "nor" to "Norsk", "nb" to "Norsk", "nob" to "Norsk",
    "de" to "Deutsch", "ger" to "Deutsch", "deu" to "Deutsch",
    "fr" to "Français", "fre" to "Français", "fra" to "Français",
    "es" to "Español", "spa" to "Español",
    "fi" to "Suomi", "fin" to "Suomi",
    "nl" to "Nederlands", "dut" to "Nederlands", "nld" to "Nederlands",
    "zh" to "中文", "chi" to "中文", "zho" to "中文",
    "pt" to "Português", "por" to "Português",
    "it" to "Italiano", "ita" to "Italiano",
    "pl" to "Polski", "pol" to "Polski",
    "ru" to "Русский", "rus" to "Русский",
    "ja" to "日本語", "jpn" to "日本語",
    "ko" to "한국어", "kor" to "한국어",
    "ar" to "العربية", "ara" to "العربية",
    "hi" to "हिन्दी", "hin" to "हिन्दी",
    "cs" to "Čeština", "cze" to "Čeština", "ces" to "Čeština",
    "tr" to "Türkçe", "tur" to "Türkçe",
    "is" to "Íslenska", "isl" to "Íslenska", "ice" to "Íslenska",
)

private enum class TrackVariant { SDH, DESCRIBES_ACTION, COMMENTARY, NONE }

// (FR-RV-ASP1-3) — derived from a track's own label/DisplayTitle, verified against the real library DB
// during the R180 dev review (audio titles carry these too, e.g. "Synstolkning", "Commentary by…").
private val SDH_RE = Regex("""\bsdh\b|hard of hearing""", RegexOption.IGNORE_CASE)
private val AD_RE = Regex("""synstolkning|audio description|\bad\b|\bdescribed\b""", RegexOption.IGNORE_CASE)
private val COMMENTARY_RE = Regex("""commentary""", RegexOption.IGNORE_CASE)
// DB-verified low-coverage "this track is the source's own original" marker (~20 tracks in the library,
// e.g. "English [Original]", "dansk [original]", "Original | Dansk (Danmark)") — see R180 addendum §4.
private val ORIGINAL_MARKER_RE = Regex("""\[\s*original\s*]|^\s*original\s*\|""", RegexOption.IGNORE_CASE)

// Region/variant qualifiers rendered as a small muted suffix on the name (FR-RV-ASP1-1), never a badge —
// covers both the subtitle region suffix (Simplified/Traditional/…) and the audio regional-dub tags
// found in the DB scan (European/Latin American/Brazilian/VFF/VFQ/…). First match wins.
private val REGION_MARKERS: List<Pair<Regex, String>> = listOf(
    Regex("""\bsimplified\b""", RegexOption.IGNORE_CASE) to "Simplified",
    Regex("""\btraditional\b""", RegexOption.IGNORE_CASE) to "Traditional",
    Regex("""\bcanad(a|ian)\b""", RegexOption.IGNORE_CASE) to "Canadian",
    Regex("""\blatin american?\b""", RegexOption.IGNORE_CASE) to "Latin American",
    Regex("""\beuropean\b""", RegexOption.IGNORE_CASE) to "European",
    Regex("""\bbrazil(ian)?\b""", RegexOption.IGNORE_CASE) to "Brazilian",
    Regex("""\btaiwan\b""", RegexOption.IGNORE_CASE) to "Taiwan",
    Regex("""\bunited states\b""", RegexOption.IGNORE_CASE) to "United States",
    Regex("""\bunited kingdom\b""", RegexOption.IGNORE_CASE) to "UK",
    Regex("""\bvff\b""", RegexOption.IGNORE_CASE) to "VFF",
    Regex("""\bvfq\b""", RegexOption.IGNORE_CASE) to "VFQ",
)

/** Muted region/variant suffix for a track's name (e.g. `中文 · Simplified`), or null — never a badge. */
private fun regionSuffix(title: String?): String? {
    if (title.isNullOrBlank()) return null
    for ((re, display) in REGION_MARKERS) if (re.containsMatchIn(title)) return display
    return null
}

private fun trackVariant(title: String?): TrackVariant {
    if (title.isNullOrBlank()) return TrackVariant.NONE
    return when {
        COMMENTARY_RE.containsMatchIn(title) -> TrackVariant.COMMENTARY
        SDH_RE.containsMatchIn(title) -> TrackVariant.SDH
        AD_RE.containsMatchIn(title) -> TrackVariant.DESCRIBES_ACTION
        else -> TrackVariant.NONE
    }
}

private fun isOriginalMarked(title: String?): Boolean =
    title != null && ORIGINAL_MARKER_RE.containsMatchIn(title)

/**
 * R180 (FR-RV-ASP1-3) — jargon-free badge words for an audio row, derived **only** from flags/channel
 * count/title markers, never from a codec string. [originalLanguage] is the title's own original-audio
 * language (R181 plumbing) for the Dubbed badge; a track explicitly self-tagged `[Original]` is never
 * Dubbed regardless (the low-coverage marker corroborates/overrides the language comparison — R180
 * addendum §4). Plain (non-`@Composable`) — takes [lang] directly so it's callable from resolver code,
 * not just rendering; pass `LocalLang.current` from a composable call site.
 */
private fun audioBadges(track: PlayerAudioTrack, originalLanguage: String?, lang: String): List<String> {
    val badges = mutableListOf<String>()
    if (track.isDefault) badges += t("player.badge_default", lang)
    track.channels?.let { ch ->
        when {
            ch > 2 -> badges += t("player.badge_surround51", lang)
            ch == 2 -> badges += t("player.badge_stereo", lang)
            // mono/unknown: no fitting word in the fixed vocabulary — omit rather than guess (non-goal).
        }
    }
    when (trackVariant(track.label)) {
        TrackVariant.COMMENTARY -> badges += t("player.badge_commentary", lang)
        TrackVariant.DESCRIBES_ACTION -> badges += t("player.badge_describes_action", lang)
        else -> {}
    }
    val dubbed = !isOriginalMarked(track.label) &&
        originalLanguage != null && track.language != null &&
        !originalLanguage.equals(track.language, ignoreCase = true)
    if (dubbed) badges += t("player.badge_dubbed", lang)
    return badges
}

/** R180 (FR-RV-ASP1-3) — jargon-free badge words for a subtitle row. Default and Signs-only (forced)
 *  may co-occur, per the spec. See [audioBadges] for why [lang] is explicit. */
private fun subtitleBadges(track: PlayerSubtitleTrack, lang: String): List<String> {
    val badges = mutableListOf<String>()
    if (track.isDefault) badges += t("player.badge_default", lang)
    if (track.forced) badges += t("player.badge_signs_only", lang)
    when (trackVariant(track.label)) {
        TrackVariant.SDH -> badges += t("player.badge_sound_described", lang)
        TrackVariant.DESCRIBES_ACTION -> badges += t("player.badge_describes_action", lang)
        TrackVariant.COMMENTARY -> badges += t("player.badge_commentary", lang)
        else -> {}
    }
    return badges
}
