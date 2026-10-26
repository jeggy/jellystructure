package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.focus.LocalFocusVisible
import dev.jellystructure.ravilo.ui.focus.focusScaleGlow
import dev.jellystructure.ravilo.ui.focus.focusRing
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.jellystructure.ravilo.ui.LocalReauthRequired
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.components.loadErrorBodyKey
import dev.jellystructure.ravilo.ui.components.loadErrorOffersRetry
import dev.jellystructure.ravilo.ui.components.loadErrorTitleKey
import dev.jellystructure.ravilo.ui.components.EpisodeTriptych
import dev.jellystructure.ravilo.ui.components.flagFor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.StartOffset
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.MediaKey
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.playerBackGesture
import dev.jellystructure.ravilo.ui.i18n.LocalLang
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.warmAudioRendition
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import dev.jellystructure.ravilo.ui.seams.PlayerFullscreen
import dev.jellystructure.ravilo.ui.seams.pictureInPictureAvailable
import dev.jellystructure.ravilo.ui.seams.togglePlayerFullscreen
import dev.jellystructure.ravilo.ui.seams.togglePictureInPicture
import dev.jellystructure.ravilo.ui.seams.PlayerImmersiveEffect
import dev.jellystructure.ravilo.ui.seams.rememberHandsetPlayerControls
import dev.jellystructure.ravilo.ui.components.CastButton
import dev.jellystructure.ravilo.ui.components.LocalCast
import dev.jellystructure.ravilo.ui.components.LocalCastHandoff
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.LocalPortrait
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import dev.jellystructure.ravilo.ui.seams.PlayerLifecycleEffect
import dev.jellystructure.ravilo.ui.seams.PlayerVideoSurface
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.ravilo.ui.seams.canonicalLanguage
import dev.jellystructure.ravilo.ui.seams.endonymOf
import dev.jellystructure.ravilo.ui.seams.isKnownLanguage
import dev.jellystructure.ravilo.ui.seams.languageName
import dev.jellystructure.ravilo.ui.seams.sameLanguage
import dev.jellystructure.ravilo.ui.seams.playerBackdropColor
import dev.jellystructure.ravilo.ui.seams.playerArrowsSeek
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

internal const val PLAYER_CHROME_HIDE_MS = 3_600L
/** R77's screen-relative subtitle floor; R251 (FR-R251-4) adds the transport band's height to it while the chrome is up. */
private val SUBTITLE_FLOOR_INSET = 28.dp
internal const val CURSOR_HIDE_MS = 2_000L // R157 FR-R157-3.2
internal const val EPRAIL_HIDE_MS = 30_000L // R208: auto-close the episode rail after inactivity
// R182 — replaces the old hardcoded COUNTDOWN_SECS = 8: the "Skip button countdown" viewer setting
// (RaviloConfig.skipSecs, one of 4/6/8) now governs both the Skip Intro pill and this next-up/credits
// countdown. This is only the pre-fetch seed (RaviloConfig's own default) until getConfig() resolves.
internal const val DEFAULT_SKIP_SECS = 6
internal const val SKIP_BACK_MS   = 10_000L
internal const val SKIP_FWD_MS    = 30_000L
internal const val POLL_MS        = 500L
// Bug fix (auto-advance retry loop): how long a requested advance gets to actually take effect before
// we treat it as failed. Navigating to the next episode is a synchronous state swap in practice, so
// this sits generously above any real transition.
internal const val ADVANCE_TIMEOUT_MS = 5_000L
// Bug fix (auto-advance retry loop): a trustworthy creditsStartMs can never sit in the first half of a
// title. A bogus marker (0, or one that belongs to a different episode inside a multi-episode file)
// used to satisfy the credits check on the very first poll tick, so the next-episode countdown fired
// at the START of every episode and the player hopped through a whole series in skipSecs-long steps —
// reported as "it just gets stuck trying to play the next episode over and over". Below half of the
// duration the marker is ignored and the no-marker rule is used instead (R384: 1 s before the end,
// `NEXT_UP_NO_MARKER_MS` in `:shared`; it was R111's 20 s `NEXTUP_AT_MS`).
// R347 (FR-R347-1) — that trust rule now lives in `:shared` (`trustedCreditsStartMs`), beside the one
// "finished" rule (`playbackFinished`) the server's stop uses too.

// R218 (FR-R218-2) — "no buffering presentation may appear before ~400ms of continuous waiting, for B,
// C and D alike... a spinner that flashes for 200ms is itself a defect." Untested on a real TV per the
// phase's own Open question #1 — tune against R216's rebuffer telemetry once data accumulates, not
// guessed at again.
internal const val BUFFER_MOMENT_DEBOUNCE_MS = 400L
// R218 (FR-R218-3) — "a wait that passes 60 seconds is pathological... at that point Moment C dims
// further and raises the centre spinner." Stall only — cold start and seek don't deepen.
internal const val BUFFER_MOMENT_DEEPEN_MS = 60_000L

// ─── Focus model ──────────────────────────────────────────────────────────────

internal enum class PlFocus { SKIP_INTRO, SEEK_BAR, SKIP_BACK, PLAY, SKIP_FWD, TRACKS, NEXT_EP, BACK, SPEAKERS, PIP, FULLSCREEN }   // R372 — SPEAKERS · R376 — PIP, FULLSCREEN

/** R251 — the five D-pad keys the chrome-hidden rule applies to (media keys are not among them, R44). */
internal enum class PlayerDpadKey { LEFT, RIGHT, UP, DOWN, SELECT }

/**
 * R251 / R350 (FR-R350-7) — what a key does when it finds the chrome hidden. Returns true when the press only
 * reveals the chrome (the next press acts).
 *
 * R251 made every key reveal-only, because `hideChrome()` used to move focus to PLAY (R178) and **→ → OK** then
 * meant different things depending on a timer the viewer was not watching. R350 removes the cause instead: hiding
 * never moves focus, so a hidden chrome is the visible chrome minus the drawing, and **Left, Right and Up act** on
 * the first press — from the control the viewer last focused (Left/Right on the seek bar scrub, as when visible).
 * Every Right after the chrome hid used to cost an extra press (owner, 2026-10-02).
 *
 * Two keys keep a hidden-chrome rule, because they would fire or open something the viewer cannot see:
 * - **OK** stays R178's play/pause (the caller also puts focus on PLAY, so the highlight matches what OK did);
 * - **Down** moves only within the transport (seek bar → Play); from anywhere else it would open the episode rail,
 *   so from a hidden chrome it only reveals.
 *
 * "Hidden" is R178's own test with its live exceptions: the Skip Intro pill and the next-up/credits card render
 * regardless of `chromeVisible` and are genuinely focused when shown; the picker and the episode rail hold the
 * auto-hide off while open, so they can never be found hidden — listed so a future hide path cannot swallow a key
 * aimed at them.
 */
/** R376 (FR-R376-4b) — two clicks on the picture at most this far apart (ms) are a double-click. */
internal const val DOUBLE_CLICK_SPAN_MS = 400L

internal fun isDoubleClick(lastMs: Long, nowMs: Long): Boolean = lastMs != Long.MIN_VALUE && nowMs - lastMs in 0..DOUBLE_CLICK_SPAN_MS

internal fun dpadRevealsOnly(
    key: PlayerDpadKey,
    chromeVisible: Boolean,
    focus: PlFocus,
    nextUpVisible: Boolean,
    epRailOpen: Boolean,
    pickerOpen: Boolean,
): Boolean {
    val hidden = !chromeVisible && focus != PlFocus.SKIP_INTRO && !nextUpVisible && !epRailOpen && !pickerOpen
    if (!hidden) return false
    return when (key) {
        PlayerDpadKey.LEFT, PlayerDpadKey.RIGHT, PlayerDpadKey.UP -> false
        PlayerDpadKey.DOWN -> focus != PlFocus.SEEK_BAR
        PlayerDpadKey.SELECT -> true
    }
}
internal enum class NuFocus { PLAY, STAY }

// R218 (FR-R218-1) — "PlayerStore exposes a single derived buffering state, not three booleans." This
// enum IS that single state; it lives here (Compose-side, in PlayerScreen) rather than inside
// PlayerStore itself — PlayerStore is deliberately decoupled from any concrete RaviloPlayer instance
// (see R216's own comment on qoeSnapshotProvider, injected rather than owned, for the same reason), and
// deriving this from the player's own polled signals would break that. The requirement's real substance
// — one state, not three independently-racing overlays — is what this enum (and the single
// LaunchedEffect debouncing it below) delivers.
internal enum class PlBufferMoment { NONE, COLD, STALL, SEEK }

/** R381 — with no trusted credits marker, the next episode is preloaded this long before the end (owner: 60 s). */
internal const val NEXT_PREFETCH_BEFORE_END_MS = 60_000L

// R182 (FR-RV-SKIP1-2) — the credits card's ONE primary action, chosen by priority: a stinger (from
// Phase 150 §C) always wins (never auto-skip past it); else a real next episode; else a plain
// skip-credits/exit. "Watch credits" (NuFocus.STAY) is offered in every mode alongside this one action.
internal enum class CreditsCardMode { STINGER, NEXT_EPISODE, SKIP_CREDITS }

// R363 (FR-R363-1) — SKIP_INTRO is never in the order: Left from −10 s used to pass through the seek bar (which
// consumes Left as a scrub), so the pill was in the order but unreachable. It is reached by Up from the controls
// under it (`skipIntroUpTarget`), and Left/Right on the pill do nothing.
//
// Bug fix: BACK used to be appended last here, but BackButton lives in the top bar, spatially
// unrelated to this bottom transport row (SeekRow/SkipButton/PlayPauseButton/TrackButton) — pressing
// Right past the last real control (NEXT_EP, or TRACKS with no next episode) teleported focus up to
// the top-left Back button, which read as "Right does Up" instead of a no-op at the row's end. BACK
// stays reachable via mouse/touch hover (onControlHover sets `focus` directly, independent of this
// list) and via the hardware Back key (root onBack), which is the primary D-pad way to leave anyway.
internal fun transportOrder(hasNextEp: Boolean, hasSpeakers: Boolean = false, hasPip: Boolean = false, hasFullscreen: Boolean = false): List<PlFocus> =
    buildList {
        add(PlFocus.SEEK_BAR); add(PlFocus.SKIP_BACK); add(PlFocus.PLAY)
        add(PlFocus.SKIP_FWD); add(PlFocus.TRACKS)
        if (hasNextEp) add(PlFocus.NEXT_EP)
        if (hasPip) add(PlFocus.PIP)   // R376 (FR-R376-8) — the browser's picture-in-picture, before the TV's Speakers
        if (hasFullscreen) add(PlFocus.FULLSCREEN)   // R376 (FR-R376-4b) — the browser tab's full screen, right after picture-in-picture
        if (hasSpeakers) add(PlFocus.SPEAKERS)   // R372 (owner decision 3) — the TV's Speakers panel, last in the row
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

/**
 * PlayerScreen's bookkeeping state, held in ONE object instead of sixteen `var … by remember` locals.
 * Since DEB-2392 it lives inside [PlayerScreenState] (which also holds the rest of the screen's state and its logic).
 * Not a refactor for taste: PlayerScreen's R8-compiled dex method needed 257–266 registers, and past
 * ~256 ART's verifier rejects the whole class — the release app crashes the moment a title is played,
 * while every debug build, unit test and compile check passes (2026-09-06, and again 2026-09-17 when a
 * one-line LaunchedEffect tipped it over on the stue TV). `scripts/check-player-dex.sh` reads the
 * register count from the release APK and runs in CI. Every property is snapshot state, exactly as the
 * locals were, so reads in composition still recompose.
 */
internal class PlayerBookkeeping(initialCastLink: CastLinkState) {
    /** DEB-2205 (FR-1, FR-5) — the player's state as the poll loop last read it, for [PlayerStore]'s stop measure. */
    @kotlin.concurrent.Volatile var watchState: PlayerWatch = PlayerWatch(0L, false, false, false, -1L)
    fun watch(): PlayerWatch = watchState
    var castLinkSeen by mutableStateOf(initialCastLink)
    var negotiationStartMs by mutableStateOf<Long?>(null)
    var measuredStartupMs by mutableStateOf<Long?>(null)
    var skipIntroCountingDown by mutableStateOf(false)
    var skipIntroCountdownDone by mutableStateOf(false)
    var skipIntroCountdownSecs by mutableIntStateOf(0)
    // R363 — the pill's reachability (see SkipIntroFocus.kt): whether playback is inside the intro (the poll loop's
    // answer), the countdown's arming edge not yet seen by the pill's focus effect, the control the pill was reached
    // from (null: it took focus itself), whether the pill had focus when the controls last hid, and whether the
    // viewer used another control since the intro began.
    var skipIntroInside by mutableStateOf(false)
    var skipIntroArmingEdge by mutableStateOf(false)
    var skipIntroReturn by mutableStateOf<PlFocus?>(null)
    var skipIntroHadFocus by mutableStateOf(false)
    var skipIntroTouchedOther by mutableStateOf(false)
    // R376 (FR-R376-4b) — when the last click on the bare picture landed; see [isDoubleClick].
    private var lastPictureClickMs = Long.MIN_VALUE
    fun pictureClickIsDouble(nowMs: Long): Boolean {
        val double = isDoubleClick(lastPictureClickMs, nowMs)
        lastPictureClickMs = if (double) Long.MIN_VALUE else nowMs
        return double
    }
    // R363 (FR-R363-6) — a skip or a scrub commit since the poll loop's last tick: a credits card it brings up starts
    // on Watch credits.
    var viewerSeek by mutableStateOf(false)
    var resolvedForItemId by mutableStateOf<String?>(null)
    var resolvedTrackSig by mutableStateOf<String?>(null)
    var resolvedTrackCount by mutableIntStateOf(0)
    var manualPickSinceResolve by mutableStateOf(false)
    // R282 — the current ticket's burned-in subtitle (252's `burned_subtitle_index`), null when none.
    // Server-pushed: written only by the load effect, from the ticket, never remembered across one.
    var burnedSubIndex by mutableStateOf<Int?>(null)
    // R282 (FR-R282-4) — an un-burn restream is in flight: when its ticket loads, re-arm R181's
    // resolver so the just-persisted pick is matched against the FRESH track set.
    private var rearmLocal by mutableStateOf(false)
    // 309 (FR-309-8/-9) — a restream the store started on its own (a stalled direct play moved to the ladder, mpv's
    // stepping) re-arms the resolver the same way; reading it here keeps the player body unchanged.
    var rearmResolveOnLoad: Boolean
        get() = rearmLocal || dev.jellystructure.ravilo.ui.seams.PlayerLadderHints.rearmTracks
        set(value) {
            rearmLocal = value
            if (!value) dev.jellystructure.ravilo.ui.seams.PlayerLadderHints.rearmTracks = false
        }
    // Phase 314c — the film's picture versions (the original and its Dolby Vision 8.1 version), from the ticket.
    var videoVersions by mutableStateOf<List<dev.jellystructure.shared.tv.VideoVersion>>(emptyList())
    // R284 — a SINGLE-AUDIO session (the ticket is a transcode that names the one audio track it
    // carries, 253 FR-253-2): the ticket's full audio list, and the Jellyfin index of the carried
    // track. Empty/null on direct play, where the player's own track list is the truth.
    var sessionAudio by mutableStateOf<List<dev.jellystructure.shared.tv.AudioTrack>>(emptyList())
    /** R376 (FR-R376-3) — every audio track of the current ticket, for a pick the player could not make in place. */
    var ticketAudio by mutableStateOf<List<dev.jellystructure.shared.tv.AudioTrack>>(emptyList())
    var sessionAudioIndex by mutableStateOf<Int?>(null)
    // R284 (FR-R284-3) — the item an automatic audio restream was already tried for: at most one, so
    // a server that does not honour the request can never loop the player.
    var audioRestreamTriedFor by mutableStateOf<String?>(null)
    var loadedForItemId by mutableStateOf<String?>(null)
    var positionKnownForItemId by mutableStateOf<String?>(null)
    var advanceRequestedForItemId by mutableStateOf<String?>(null)
    /** R381 — the item whose next episode was prepared and prefetched (at most once per item). */
    var prefetchedForItemId: String? = null
    /** R372 (FR-R372-10) — the end-of-play decision, latched per item (see [EndOfPlayWatch]). */
    val endOfPlay = EndOfPlayWatch()
    // R290 — the start latch, per item (see PlayerStart.kt): the item whose latch has opened; whether
    // R218's 400 ms have passed since the press (before that the start is plain black); a `play()` held
    // back until the latch (FR-R290-4: a discarded stream is prepared, never played); a restream the
    // resolver asked for that has not become a new ticket yet; and when the current stream's first frame
    // rendered, for the silent-stream grace.
    var latchedForItemId by mutableStateOf<String?>(null)
    var startScreenDue by mutableStateOf(false)
    var playHeldForLatch by mutableStateOf(false)
    var startRestreamPending by mutableStateOf(false)
    var firstFrameAtMs by mutableStateOf<Long?>(null)
    // R292 — a return from the background is R290's one start moment, with three differences the record
    // carries: whether the latch plays or lands paused (open question 2), the one restream that restores
    // a burn-in / single-audio choice (FR-R292-5), and the track indices to re-apply on the new engine.
    var playOnLatch by mutableStateOf(true)
    var returnRecord by mutableStateOf<ResumeRecord?>(null)
    var reapplyChoicesForItemId by mutableStateOf<String?>(null)
    var pauseFlash by mutableStateOf(false)
    var pauseFlashIsPlay by mutableStateOf(true)
    var stallDeepened by mutableStateOf(false)
    // Per-user playback settings (were 4 locals + 4 rememberUpdatedState mirrors).
    var skipIntroMode by mutableStateOf(dev.jellystructure.shared.tv.SkipMode.PROMPT)
    var skipCreditsMode by mutableStateOf(dev.jellystructure.shared.tv.SkipMode.PROMPT)
    var skipSecs by mutableIntStateOf(DEFAULT_SKIP_SECS)
    var autoplayNextEnabled by mutableStateOf(true)
}

/** R194 — a relative image path against the server base. Top level, so the player's body carries no local function (DEB-2280: the release player was one register over its limit). */
internal fun resolvePlayerImageUrl(serverBaseUrl: String, url: String?): String? =
    url?.let { if (it.startsWith("/") && serverBaseUrl.isNotBlank()) "$serverBaseUrl$it" else it }

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
    // R192 — poster fallback for the OS media session's artwork when there's no `episodes` list to
    // pull a still from (movies). Ignored when `episodes`/`currentEpIndex` resolves a still.
    posterUrl: String? = null,
    // Phase 150 — this title's own intro/credits segments (R182 Skip Intro / Skip Credits consumes
    // this; this stage only threads it in).
    segments: dev.jellystructure.shared.tv.TvSegmentMarkers = dev.jellystructure.shared.tv.TvSegmentMarkers(),
    // R303 (FR-R303-2) — the top-right identity: the film's or series' logo + ink, the series' name.
    logoUrl: String? = null,
    logoInk: String? = null,
    seriesName: String? = null,
    // R292 (FR-R292-2/6) — the resume record's holder (saved state lives in RaviloApp); see PlayerResume.kt.
    resume: PlayerResumeStore = PlayerResumeStore("") {},
    store: PlayerStore,
    onBack: () -> Unit,
    onNavigateToEpisode: ((String) -> Unit)? = null,
    /** Where the first start begins when another app sent this film here (a session moved or started on this device,
     *  R370/R372); null = the usual (Jellyfin's resume point). */
    startAtMs: Long? = null,
) {
    // DEB-2392 — a shell. The state and the logic live in [PlayerScreenState] (PlayerScreenState.kt), the effects in
    // [PlayerScreenEffects] and the picture in the layers below: this function used to hold all of them and needed 251–270
    // registers in the R8 build against ART's cliff of 256 (scripts/check-player-dex.sh). Add new state THERE, a new effect
    // to PlayerScreenEffects and a new piece of picture to its own layer — never back into this body.
    //
    // Read from CompositionLocals, NOT taken as parameters. As a 16th parameter `onReauthRequired` produced a release-build-only
    // `VerifyError` that killed the player on open — see [LocalReauthRequired]'s doc for the full account. Do not turn any of
    // these back into a parameter.
    val castController = LocalCast.current
    val inputs = PlayerScreenInputs(
        itemId = itemId, itemTitle = itemTitle, itemKicker = itemKicker,
        nextEpisodeId = nextEpisodeId, nextEpisodeLabel = nextEpisodeLabel, nextEpisodeTitle = nextEpisodeTitle,
        episodes = episodes, currentEpIndex = currentEpIndex, seriesId = seriesId, originalLanguage = originalLanguage,
        posterUrl = posterUrl, segments = segments, logoUrl = logoUrl, logoInk = logoInk, seriesName = seriesName,
        resume = resume, store = store, onBack = onBack, onNavigateToEpisode = onNavigateToEpisode, startAtMs = startAtMs,
        sessionState = store.state.collectAsState().value,
        // R194 — resolves a relative /api/tv/image/... path (season/series poster) against the server base URL, same rule
        // RemoteImage applies for on-screen art; the OS media session's artwork URI bypasses Coil entirely.
        serverBaseUrl = LocalServerBaseUrl.current,
        // R244 — the handset chrome's gate: LocalHandset (smallest side < 600 dp, orientation-stable).
        handset = LocalHandset.current && !dev.jellystructure.ravilo.ui.isDesktopPlatform,   // R337 dev review 9 — R329's chrome at every desktop width
        lang = LocalLang.current,
        haptics = LocalHapticFeedback.current,
        // R245 (FR-R245-4) — pressing cast INSIDE the player: the instant the session connects, the app hands the current
        // position over, stops local playback and swaps straight to the remote.
        castLink = castController?.sender?.link?.collectAsState()?.value ?: CastLinkState.NONE,
    )
    val s = remember { PlayerScreenState(inputs) }
    s.inputs = inputs
    s.handsetControls = if (inputs.handset) rememberHandsetPlayerControls() else null
    PlayerScreenEffects(s)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(playerBackdropColor)
            .wakeOnPointerMove { s.wake(); s.pointerActivityRevision++ }
            .playerBackGesture(onBack = { s.playerBack() })
            .dpadFocusable(
                focusRequester = s.playerFR,
                onFocused = {},
                onLeft = { s.dpadLeft() },
                onRight = { s.dpadRight() },
                onUp = { s.dpadUp() },
                onDown = { s.dpadDown() },
                onSelect = { s.dpadSelect() },
                onBack = { s.playerBack() },
                onMediaKey = { mk -> s.onMediaKey(mk) },
                // R157 (FR-R157-2.4) — a click on empty space toggles chrome instead of activating the focused control
                // (see [PlayerScreenState.pictureTap]). TV: playerTapTogglesChrome is false and LocalHandset is false (R256:
                // by platform — a TV window IS handset-sized, 960 x 540 dp), so this stays null there and dpadFocusable's
                // default (onTap falls back to onSelect) preserves the D-pad behaviour. R244 — on a handset the gesture layer
                // (HandsetGestureLayer) owns single AND double taps, so the root must not also toggle chrome on the first tap
                // of a double-tap. LocalHandset uses the SMALLEST side, so it stays true across a phone's rotation (the
                // window's WIDTH, LocalCompact, does not).
                onTap = if (playerTapTogglesChrome && !s.handset) ({ s.pictureTap() }) else null,
            )
    ) {
        PlayerVideoLayer(s)
        PlayerStatusLayer(s)
        PlayerChromeLayer(s)
        PlayerPickerLayer(s)
        PlayerCardLayer(s)
    }
}

/**
 * Every effect of the player screen, in one composable of its own. Each is the effect the screen body used to carry, with
 * the same key; the work is in [PlayerScreenState].
 */
@Composable
private fun PlayerScreenEffects(s: PlayerScreenState) {
    val castHandoff = LocalCastHandoff.current
    val itemId = s.itemId

    // R155 → R354 (FR-R354-3/-4) — remote commands (the Jellyfin dashboard via 299's bridge, Home Assistant, a phone remote)
    // drive this player as its own controls do, while it is composed — which is itself R155's "ignore when no player is open"
    // (with none, music takes the command). Set, not toggle, so a stale/duplicate command is idempotent.
    RemoteVideoCommands(
        player = s.player,
        isPlaying = { s.isPlaying },
        togglePlay = { s.togglePlay() },
        seekTo = { ms -> s.seekTo(ms) },
        skip = { ms -> s.skip(ms) },
        stop = { s.onBack() },
        next = { if (s.resolvedNextEpisodeId != null) s.advanceNext() },
        previous = { s.previousEpisode() },
    )
    // R380 (FR-R380-7) — a cast remote's view of this player (TV only).
    if (isTvPlatform) SideEffect { s.bindCastSource() }
    CastChannelVideoHost(itemId, s.castSource)

    LaunchedEffect(itemId) { s.startItem() }
    // R182 — resolve skip behaviour in parallel with session start (not blocking playback start on an extra round-trip).
    // Falls back to the current (previous episode's, or the DEFAULT_SKIP_SECS-seeded) values on failure — never blocks or
    // breaks playback.
    LaunchedEffect(itemId) { s.loadConfig() }
    // Load player when the StreamTicket is ready (initial load or R56 restream)
    val sessionState = s.sessionState
    LaunchedEffect(sessionState) { s.loadTicket(sessionState) }
    // Poll player state
    LaunchedEffect(Unit) { s.pollLoop() }

    // R350 (FR-R350-7) — keyed on the play state as well: a resume by the cast road wakes nothing.
    ChromeHideEffect(s.chromeRevision, s.isPlaying) { s.chromeHideTimer() }
    LaunchedEffect(s.epRailOpen, s.chromeRevision) { s.epRailHideTimer() }

    val rawBufferMoment = s.rawBufferMoment
    LaunchedEffect(rawBufferMoment) { s.settleBufferMoment(rawBufferMoment) }
    LaunchedEffect(s.displayedBufferMoment) { s.deepenStall() }

    val skipIntroPillVisible = s.skipIntroPillVisible
    LaunchedEffect(skipIntroPillVisible) { s.skipIntroPillChanged(skipIntroPillVisible) }

    LaunchedEffect(s.pointerActivityRevision) { s.cursorHideTimer() }
    DisposableEffect(Unit) { onDispose { setPointerCursorHidden(false) } }

    LaunchedEffect(s.nextUpVisible) { s.nextUpCountdown() }
    LaunchedEffect(s.bk.advanceRequestedForItemId) { s.advanceTimeout() }
    LaunchedEffect(s.bk.skipIntroCountingDown) { s.skipIntroCountdown() }
    LaunchedEffect(s.bk.pauseFlash) { s.pauseFlashDismiss() }

    // Pause/resume when activity goes to background (Home button) and returns. Bug fix: also END the playback session while
    // we are away instead of leaving it open with a paused heartbeat (phantom "streaming" in the Jellyfin dashboard, and no
    // final resume position if the app is then killed), and re-arm it for the SAME item if the viewer comes back.
    PlayerLifecycleEffect(
        s.player,
        wasPlaying = { s.isPlaying },
        onBackground = { wasPlaying -> s.onBackground(wasPlaying) },
        onForeground = { afterStandby -> s.onForeground(afterStandby) },
    )
    // Bug fix: force landscape + hide system bars for as long as the player is on screen — the phone app is portrait-locked
    // with visible system bars everywhere else, which left the player stuck in portrait (heavy top/bottom letterboxing on any
    // normal landscape video) plus a status/nav-bar-shaped margin baked in on top of that.
    PlayerImmersiveEffect(followSensor = s.handset)   // R244 (FR-R244-7) — a phone follows the sensor
    val castLink = s.inputs.castLink
    LaunchedEffect(castLink) { s.castLinkChanged(castLink, castHandoff) }
    // R244 (FR-R244-10) — S · M · L applied live to the caption renderer; phone-local.
    LaunchedEffect(s.subtitleSize) {
        if (s.handset) s.player.setSubtitleScale(s.subtitleScale())
    }

    // Stop the playback session for the item we are leaving. Bug fix: this used to be keyed on Unit together with the player
    // teardown below, so the effect block ran exactly once and its onDispose captured the `store` from the FIRST composition.
    // After a binge (each episode replaceTops a new Dest.Player with its own store — see RaviloApp) pressing Back therefore
    // stopped EPISODE 1's session, using episode 5's positionMs/durationMs — clobbering episode 1's resume position and even
    // mark-playing it when episode 5 happened to be ≥90% — while episode 5's own session was never stopped at all. Keying on
    // `store` makes this fire once per episode with that episode's own playhead, which is also what makes the resume point
    // (and Continue Watching) correct.
    //
    // Phase 180/R218 bug fix: this called stopSession() directly, which reports the stop but leaves PlayerStore's own `scope`
    // running — the coroutine backing startSession()'s up-to-5-attempt retry loop (~15s worst case) was never cancelled.
    // close() already does the right thing: it calls stopSession() itself using the same positionProvider/durationProvider
    // AND cancels `scope`, which is what actually closes the race.
    val store = s.store
    DisposableEffect(store) {
        onDispose { store.close() }  // R142: ≥90% → mark played (inside stopSession, called by close())
    }

    // Release the player engine only when the screen itself goes away — the engine is remembered per screen and deliberately
    // REUSED across episodes, so this must NOT be keyed on the store/itemId.
    DisposableEffect(Unit) {
        onDispose { s.player.release() }
    }

    // R372 — the key handler takes focus again when the TV's Speakers panel closes over it.
    LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { TvSpeakers.open }.collect { if (!it) runCatching { s.playerFR.requestFocus() } } }
}

/** The picture itself: the video surface, its shutter, and the browser hints. */
@Composable
private fun BoxScope.PlayerVideoLayer(s: PlayerScreenState) = with(s) {
    // ── Platform video surface (SurfaceView on Android, <video> element on WASM) ───
    // Phase R220 (FR-R220-3 rung 4) — the Android actual's recovery ladder calls this only after re-attach/seek-flush/
    // surface-recreate have all failed while the player is genuinely still playing. Re-arming mirrors
    // PlayerLifecycleEffect's own onForeground path exactly (same re-negotiate-without-a-full-player-rebuild shape) rather
    // than inventing a second one. FR-R220-5 — feeds rawBufferMoment so a rung running past R218's debounce shows STALL.
    // R251 (FR-R251-4) — subtitles rise above the transport band while the TV chrome is up: R77's screen-relative 28 dp floor
    // plus the band's measured height, on the chrome's own fade timing; back to the floor when hidden. Same visibility test as
    // the chrome's AnimatedVisibility. The handset chrome (R244) is not lifted here — R244 owns the phone player.
    val chromeUpForSubs = !handset && (chromeVisible || displayedBufferMoment == PlBufferMoment.STALL) &&
        displayedBufferMoment != PlBufferMoment.COLD
    val transportBandDp = with(LocalDensity.current) { transportBandPx.toDp() }
    val subtitleInset by animateDpAsState(
        targetValue = if (chromeUpForSubs) SUBTITLE_FLOOR_INSET + transportBandDp else SUBTITLE_FLOOR_INSET,
        animationSpec = tween(if (chromeUpForSubs) RaviloMotion.CHROME_FADE_IN_MS else RaviloMotion.CHROME_FADE_OUT_MS),
        label = "subtitleInset",
    )
    PlayerVideoSurface(
        player,
        Modifier.fillMaxSize(),
        onVideoOutputStuck = { armSession(itemId) },
        onVideoOutputRecovering = { videoOutputRecovering = it },
        fill = fillMode,   // R244 (FR-R244-6)
        subtitleBottomInset = subtitleInset,   // R251 (FR-R251-4)
    )
    // R290 (FR-R290-4) — also shut until the latch: a discarded stream's first frame must not show through.
    VideoShutter(startPhaseNow != StartPhase.PLAYING || sessionState is PlayerSessionState.Loading || (sessionState is PlayerSessionState.Ready && !hasRenderedFirstFrame))
    BrowserSoundHint(player)   // R376 — a browser that played muted says how to get the sound
    // R376 (owner, 2026-10-08) — Safari direct-plays; picking AirPlay restarts the item as HLS where it is.
    AirPlayHlsRestart(itemId, streamIsHls = (sessionState as? PlayerSessionState.Ready)?.ticket?.directPlay != true) {
        bk.rearmResolveOnLoad = true
        store.restreamForAirPlay(itemId, bk.burnedSubIndex ?: -1, player.positionMs, bk.ticketAudio.getOrNull(selectedAudio)?.index)
    }
}

/** What the viewer is told about the stream: dim scrim, gestures, the start screen, spinners, errors, the pause flash. */
@Composable
private fun BoxScope.PlayerStatusLayer(s: PlayerScreenState) = with(s) {
    val colors = RaviloTheme.colors
    // ── Dim scrim (deepens when chrome is up, paused, or R218's moment C stalls) ──
    val dimAlpha = when {
        // R218 (FR-R218-3) — "Moment C dims further... because the chrome alone stops being enough of a signal" past the 60s
        // deepen threshold; ~38% before that, per the chosen direction.
        displayedBufferMoment == PlBufferMoment.STALL && bk.stallDeepened -> 0.55f
        displayedBufferMoment == PlBufferMoment.STALL                 -> 0.38f
        chromeVisible && !isPlaying -> 0.50f
        chromeVisible               -> 0.34f
        else                        -> 0f
    }
    if (dimAlpha > 0f) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
    }

    // R244 (FR-R244-4/5/6) — the handset gesture surface, under the chrome so controls take their own taps first. A double-tap
    // seeks WITHOUT raising the chrome (it is not a request to see the controls); inert while locked, while a sheet is up, or
    // before a session is ready.
    if (handset) {
        HandsetGestureLayer(
            enabled = !locked && !pickerOpen && !epRailOpen && !nextUpVisible && sessionState is PlayerSessionState.Ready,
            brightness = handsetControls?.brightness, setBrightness = handsetControls?.setBrightness,
            volume = handsetControls?.volume, setVolume = handsetControls?.setVolume,
            fill = fillMode,
            onSingleTap = { if (chromeVisible) hideChrome() else wake() },
            onDoubleTapSeek = { inc -> doubleTapSeek(inc) },
            onFillChange = { fillMode = it },
        )
    }

    // ── R218 moment B: cold start (ticket in hand, no first frame yet) ────
    // Direction B "Grounded": pulse + title context (already-seen catalog data, no delivery information) + an indeterminate
    // sweep so a long wait never looks frozen + the existing undebounced "Loading…" string — the SAME string moment A uses
    // (FR-R218-4: one string, already translated, no drift between moments).
    // R290 (FR-R290-1/2, open question 2) — ONE start screen from the press to the latch (negotiation, cold start, a restream,
    // R237's retry) — and R218's own COLD moment for a restream after it. R237's "still trying" line is the one variation.
    val startPhase = startPhaseNow
    if (startPhase == StartPhase.START || (startPhase == StartPhase.PLAYING && displayedBufferMoment == PlBufferMoment.COLD)) {
        PlayerStartScreen(
            colors = colors, itemKicker = itemKicker, itemTitle = itemTitle,
            retrying = startPhase == StartPhase.START && (sessionState as? PlayerSessionState.Loading)?.retrying == true,
        )
    }

    // R218 moment C (deepened, 60s+): the chrome-up spinner (PlayerChrome's play button, forced visible) stops being enough of
    // a signal on its own — raise a second, centre spinner too.
    if (displayedBufferMoment == PlBufferMoment.STALL && bk.stallDeepened) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BufferingSpinner(colors) }
    }

    // ── Loading overlay ───────────────────────────────────────────────────
    val sessionLoading = sessionState as? PlayerSessionState.Loading
    if (sessionLoading != null && startPhase == StartPhase.PLAYING) {   // R290 — during the start it is the start screen
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Incidental layout correction: these were direct children of the Box above, which centre-stacks its children — so
            // the 48.dp Spacer did nothing and "Loading..." was drawn ON TOP of the spinner. A Column is what the spacing was
            // always written for, and R237's second line below needs it to be legible rather than a third overlap.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BufferingSpinner(colors)
                Spacer(Modifier.height(48.dp))
                // R180 (FR-RV-ASP1-2) — a neutral loading string regardless of cause; naming the PGS burn-in restream here
                // would leak the delivery method, which the picker keeps invisible.
                Text(
                    str("loading"),
                    color = Color.White.copy(0.7f), fontSize = 18.sp,
                )
                // R237 (FR-R237-5) — additive, and the ONLY change to R218's states: once a first attempt has already failed
                // this is provably not a normal cold start, and R218's own deepen-at-60s is far past the point a viewer gives
                // up (measured: ~4s). No new visual language, no progress count, no attempt number.
                if (sessionLoading.retrying) {
                    Spacer(Modifier.height(10.dp))
                    Text(str("loading.still_trying"), color = Color.White.copy(0.45f), fontSize = 14.sp)
                }
            }
        }
    }

    // ── Error overlay ──────────────────────────────────────────────────────
    // Bug fix: PlayerSessionState.Error was never checked anywhere in this screen — a failed startPlayback (all retries
    // exhausted, see PlayerStore.startSession) used to leave the player sitting on the outgoing episode's frozen last frame
    // forever, with no indication anything had gone wrong and no way to recover short of backing out entirely.
    val sessionError = sessionState as? PlayerSessionState.Error
    if (sessionError != null) {
        // R237 — its own composable. Inlined into the screen body, this overlay once pushed the Compose-generated method past
        // what ART's verifier accepts in the R8-minified release build (java.lang.VerifyError: Verifier rejected class …
        // PlayerScreen…; the player died the instant it was opened — release build only, stue TV, 2026-09-06).
        PlayerSessionErrorOverlay(
            error = sessionError,
            colors = colors,
            handset = handset,
            onReauthRequired = LocalReauthRequired.current,
            onRetry = { store.startSession(itemId, positionProvider = { positionMs }, isPausedProvider = { !isPlaying }) },
            onBack = inputs.onBack,
        )
    }

    // ── Center pause flash ────────────────────────────────────────────────
    // R90: scale-bounce matches the CSS plflash spec — pop in from 0.9→1.0, expand out to 1.4.
    AnimatedVisibility(
        bk.pauseFlash,
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
                    if (bk.pauseFlashIsPlay) {
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
}

/** The transport chrome (TV or handset) and the Skip Intro pill. */
@Composable
private fun BoxScope.PlayerChromeLayer(s: PlayerScreenState) = with(s) {
    val colors = RaviloTheme.colors
    val portrait = LocalPortrait.current
    // ── Player chrome (auto-hiding transport + metadata) ──────────────────
    // R90: 300ms fade-in / 200ms fade-out matches the CSS spec (was enter=200/exit=300, reversed).
    // R218 (FR-R218-1, moment C, Open question #2) — "the chrome comes up on its own" during a stall; forced visible here
    // WITHOUT touching chromeVisible/chromeRevision themselves, so R208's 30s auto-hide timer is never armed or reset by it,
    // and it retracts the instant the stall clears (displayedBufferMoment leaves STALL) rather than needing its own separate
    // hide timer. A chrome the viewer had already raised manually is unaffected either way — this only ever ADDS visibility,
    // never removes it.
    //
    // R218 moment B, bug fix found live on-device (2026-08-29): chromeVisible defaults to true, so without the extra
    // condition below the cold-start overlay (drawn earlier/underneath in this same Box) showed simultaneously WITH the normal
    // transport chrome layered on top of it — top bar and bottom transport visible around the pulse/title/sweep, contradicting
    // "full-screen on black" (moment C's chrome-forcing is the opposite intent: RAISE chrome over a stall so the viewer keeps
    // their position; moment B has no position to keep yet, so it suppresses chrome instead).
    AnimatedVisibility(
        visible = startPhaseNow == StartPhase.PLAYING && (chromeVisible || stallActive) && !coldActive && !(handset && locked),   // R290 (FR-R290-1)
        enter = fadeIn(tween(RaviloMotion.CHROME_FADE_IN_MS)),
        exit = fadeOut(tween(RaviloMotion.CHROME_FADE_OUT_MS)),
    ) {
        // R244 (FR-R244-1) — the handset gets its own chrome (its own composable, its own file); the TV layout is untouched.
        if (handset) PlayerHandsetChromeHost(s) else PlayerTvChromeHost(s)
    }

    // ── Skip Intro pill (R182, FR-RV-SKIP1-1) ─────────────────────────────
    // Bottom-end, but padded up well above the transport row (design: right:64px/bottom:210px) so it never overlaps the seek
    // bar/controls — those two never show at once in practice anyway (intro is at the episode's start, the transport padding
    // here is what matters).
    AnimatedVisibility(
        visible = skipIntroPillVisible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        // R244 (FR-R244-2/12) — on a handset the pill sits bottom-START, above the seek bar and left of the rail, so the two
        // can never overlap at any frame size; ≥ 46 dp tall.
        modifier = if (handset) Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 16.dp, bottom = if (portrait) 150.dp else 96.dp)
                   else Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 160.dp),
    ) {
        SkipIntroPill(
            colors = colors,
            countdown = bk.skipIntroCountdownSecs,
            totalSecs = bk.skipSecs,
            focused = focus == PlFocus.SKIP_INTRO,
            onTap = if (handset) ({ skipIntro() }) else null,
            showRing = skipIntroShowsRing(bk.skipIntroMode),   // R363 (FR-R363-5)
        )
    }
}

@Composable
private fun PlayerHandsetChromeHost(s: PlayerScreenState) = with(s) {
    val castController = LocalCast.current
    HandsetPlayerChrome(
        colors = RaviloTheme.colors, itemTitle = itemTitle, itemKicker = itemKicker,
        logoUrl = inputs.logoUrl, logoInk = inputs.logoInk, seriesName = inputs.seriesName,  // R303
        positionMs = positionMs, durationMs = durationMs, bufferedMs = bufferedMs,
        scrubbing = scrubbing, scrubPos = scrubPos, isPlaying = isPlaying,
        railItems = handsetRailFor(hasNextEp = resolvedNextEpisodeId != null, hasSeason = episodes != null),
        stallActive = stallActive, seekMomentActive = displayedBufferMoment == PlBufferMoment.SEEK,
        showRotate = handsetControls?.systemRotationLocked == true, landscapeForced = landscapeForced,
        onBack = inputs.onBack,
        onRotate = { landscapeForced = !landscapeForced; handsetControls?.setLandscape(landscapeForced); wake() },
        onTitleTap = if (episodes != null) ({ epRailOpen = true; chromeVisible = true }) else null,
        // 2026-10-05 — with no play context a tap on a Ravilo app (a TV, the Mac) in the player's sheet did nothing at all;
        // the film and where it is now go along (a Chromecast keeps its own hand-off).
        castSlot = if (castController != null) ({ CastButton(playContext = dev.jellystructure.ravilo.ui.components.ScreenPlayContext(itemId, positionMs)) }) else null,
        onPictureInPicture = if (player.pictureInPictureAvailable()) ({ wake(); player.togglePictureInPicture() }) else null,   // R376 (FR-R376-8)
        onFullscreen = { wake(); togglePlayerFullscreen() },   // R376 (FR-R376-4b) — the chrome shows it only where PlayerFullscreen.available
        onSkipBack = { skip(-SKIP_BACK_MS) },
        onPlayPause = { togglePlay() },
        onSkipFwd = { skip(SKIP_FWD_MS) },
        onRail = { item -> handsetRail(item) },
        onSeekStart = { ms -> beginScrubAt(ms) },
        onSeekDrag = { ms -> scrubPos = ms },
        onSeekEnd = { commitScrub() },
    )
}

@Composable
private fun PlayerTvChromeHost(s: PlayerScreenState) = with(s) {
    PlayerChrome(
        colors          = RaviloTheme.colors,
        itemTitle       = itemTitle,
        itemKicker      = itemKicker,
        logoUrl = inputs.logoUrl, logoInk = inputs.logoInk, seriesName = inputs.seriesName,  // R303
        positionMs      = positionMs,
        durationMs      = durationMs,
        bufferedMs      = bufferedMs,
        scrubbing       = scrubbing,
        scrubPos        = scrubPos,
        isPlaying       = isPlaying,
        focus           = focus,
        hasNextEp       = resolvedNextEpisodeId != null,
        hasSpeakers     = TvSpeakers.available,
        hasPip          = player.pictureInPictureAvailable(),   // R376 (FR-R376-8) — absent where unsupported
        isSeries        = episodes != null,
        epRailOpen      = epRailOpen,
        pickerOpen      = pickerOpen,
        nextUpVisible   = nextUpVisible,
        stallActive      = stallActive,
        seekMomentActive = displayedBufferMoment == PlBufferMoment.SEEK,
        onTransportBandHeight = { transportBandPx = it },  // R251 (FR-R251-4)
        // R157 — PlayerChrome is a stateless presentational composable; it reports which logical control was clicked/hovered
        // and the state holder does the actual work, mirroring the root's onSelect dispatch.
        onControlClick = { clicked -> controlClicked(clicked) },
        onControlHover = { hovered -> focus = hovered },
        onSeekStart = { ms -> beginScrubAt(ms) },
        onSeekDrag = { ms -> scrubPos = ms },
        onSeekEnd = { commitScrub() },
    )
}

/** The Audio / Subtitles / Picture picker: a bottom sheet on a handset, a popup on a TV. */
@Composable
private fun BoxScope.PlayerPickerLayer(s: PlayerScreenState) = with(s) {
    val colors = RaviloTheme.colors
    // ── Track picker popup (Audio / Subtitles) ────────────────────────────
    // R195 bug fix: on touch (phone/web), there was no way to dismiss the picker without making a selection — no D-pad Back
    // reaches this screen's key handler on a phone (no hardware D-pad), and nothing else called pickerBack(). A full-screen
    // scrim behind the picker gives touch users the same "tap away to back out" a modal is expected to have; tapping it runs
    // the exact same pickerBack() Back already uses (steps out of level 2 first, closes from level 1), so touch and D-pad
    // still can't diverge in behaviour. No ripple — a screen-spanning tap target shouldn't show one.
    // R244 (FR-R244-10) — on a handset the SAME picker renders inside a bottom sheet, gaining only the Subtitle size row (one
    // component, two destinations — R245's remote opens this too).
    if (handset) HandsetSheet(visible = pickerOpen, onDismiss = { pickerBack() }) {
        TrackPicker(
            colors           = colors,
            pickerTab        = pickerTab,
            pickerLevel      = pickerLevel,
            audioGroups      = audioGroups,
            subGroups        = subGroupsWithOff,
            pickerIdx        = pickerIdx,
            pickerVersionIdx = pickerVersionIdx,
            selectedAudio    = shownAudioNow,
            pictureVersions  = bk.videoVersions,
            selectedSub      = shownSubNow,
            onTapLanguage = { idx -> pickerIdx = idx; pickerSelect() },
            onTapVersion  = { idx -> pickerVersionIdx = idx; pickerSelect() },
            onTapBack     = { pickerBack() },
            onTapTab      = { tab -> pickerTapTab(tab) },
            handset = true,
            extraRow = if (pickerTab == 1 && pickerLevel == 0) ({
                SubtitleSizeRow(colors, subtitleSize, str("pl.sub_size_note")) { subtitleSize = it; wake() }
            }) else null,
        )
    }
    if (!handset) AnimatedVisibility(visible = pickerOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { pickerBack() },
                ),
        )
    }
    if (!handset) AnimatedVisibility(
        visible = pickerOpen,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = Modifier.align(Alignment.BottomEnd),
    ) {
        TrackPicker(
            colors           = colors,
            pickerTab        = pickerTab,
            pickerLevel      = pickerLevel,
            audioGroups      = audioGroups,
            subGroups        = subGroupsWithOff,
            pickerIdx        = pickerIdx,
            pickerVersionIdx = pickerVersionIdx,
            selectedAudio    = shownAudioNow,
            pictureVersions  = bk.videoVersions,
            selectedSub      = shownSubNow,
            // R195 — touch parity with the D-pad: a tap just moves the target index then runs the EXACT SAME
            // pickerSelect()/pickerBack() logic Select/Back already use, so touch (phone) and D-pad (TV) can never diverge in
            // behaviour.
            onTapLanguage = { idx -> pickerIdx = idx; pickerSelect() },
            onTapVersion  = { idx -> pickerVersionIdx = idx; pickerSelect() },
            onTapBack     = { pickerBack() },
            onTapTab      = { tab -> pickerTapTab(tab) },
        )
    }
}

/** The credits card, the episode rail, the handset's lock, and the shutter over everything. */
@Composable
private fun BoxScope.PlayerCardLayer(s: PlayerScreenState) = with(s) {
    val colors = RaviloTheme.colors
    val portrait = LocalPortrait.current
    // ── Next-up card ──────────────────────────────────────────────────────
    // R90: slide-up from below + fade matches the CSS translateY(112%)→0 spec (was fade-only).
    AnimatedVisibility(
        visible = nextUpVisible,
        enter = slideInVertically { it } + fadeIn(tween(RaviloMotion.NEXT_UP_SLIDE_MS)),
        exit  = slideOutVertically { it } + fadeOut(tween(RaviloMotion.NEXT_UP_SLIDE_MS)),
        // R244 (FR-R244-12) — a corner card in landscape, a full-width strip in portrait.
        modifier = if (handset) Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                   else Modifier.align(Alignment.BottomEnd),
    ) {
        NextUpCard(
            colors         = colors,
            mode           = creditsCardMode,
            itemKicker     = itemKicker,
            isSeries       = episodes != null,
            nextEpLabel    = inputs.nextEpisodeLabel,
            nextEpTitle    = inputs.nextEpisodeTitle,
            // Bug fix: the card's thumbnail was always an empty placeholder box — `episodes` (the same grouped list
            // `currentEpIndex` indexes into) already carries each entry's still(s), so the next episode's is one lookup away
            // rather than needing new plumbing end-to-end.
            nextEpStillUrls = episodes?.getOrNull(currentEpIndex + 1)?.stillUrls ?: emptyList(),
            countdown      = countdown,
            totalSecs      = bk.skipSecs,
            nuFocus        = nuFocus,
            handsetFull    = handset && portrait,
            onPrimaryTap   = if (handset) ({ creditsPrimary() }) else null,
            onStayTap      = if (handset) ({ stayThrough() }) else null,
        )
    }

    // ── Episode rail (series only) ────────────────────────────────────────
    val epList = episodes
    // R244 (FR-R244-11) — the TV's horizontal rail is a vertical season sheet on a handset.
    if (epList != null && handset) {
        HandsetSheet(visible = epRailOpen, onDismiss = { epRailOpen = false; wake() }) {
            SeasonSheet(colors, epList, currentEpIndex, onPick = { idx -> focusedEpIdx = idx; chooseEpisode() })
        }
    }
    if (epList != null && !handset) {
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

    // R244 (FR-R244-8) — locked: everything but a lock glyph; tap hints, long-press unlocks; the system back still leaves
    // (locking already calls hideChrome(), so playerBack's chrome-visible branch is false and it falls through to onBack() —
    // R260's dev review).
    if (handset && locked) {
        HandsetLockOverlay(onUnlock = { locked = false; tick(); wake() })
    }

    // R295 (FR-R295-5) — last, so it covers everything: a phone shows plain black until the window has rotated into the
    // player's orientation (or 700 ms pass), never a portrait frame first.
    VideoShutter(!rememberWindowShapeSettled(handset))
}

/**
 * R295 (FR-R295-5) — true once the window has taken its final shape for the player. A phone's screens
 * are pinned to portrait in the manifest and the player switches to follow the sensor (R244 FR-R244-7)
 * from an effect, after its first frame; the rotation that follows is asynchronous, so anything drawn
 * before it lands is drawn in the wrong shape. Settles on the first change of shape or after 700 ms
 * (a phone held upright never rotates), and stays settled: a later rotation by the viewer is not a
 * reason to go black. Always true where [waitForRotation] is false (a TV never rotates).
 */
@Composable
private fun rememberWindowShapeSettled(waitForRotation: Boolean): Boolean {
    val size = LocalWindowInfo.current.containerSize
    val landscapeNow = size.width > size.height
    val initialLandscape = remember { landscapeNow }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(landscapeNow) { if (landscapeNow != initialLandscape) settled = true }
    LaunchedEffect(Unit) { delay(700); settled = true }
    return settled || !waitForRotation
}

// ─── Player chrome overlay ────────────────────────────────────────────────────

@Composable
private fun PlayerChrome(
    colors: RaviloColors,
    itemTitle: String,
    itemKicker: String?,
    // R303 (FR-R303-1/2) — the top-right slot's inputs; PlayerIdentSlot decides what to draw.
    logoUrl: String? = null,
    logoInk: String? = null,
    seriesName: String? = null,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    scrubbing: Boolean,
    scrubPos: Long,
    isPlaying: Boolean,
    focus: PlFocus,
    hasNextEp: Boolean,
    hasSpeakers: Boolean = false,
    hasPip: Boolean = false,
    isSeries: Boolean,
    epRailOpen: Boolean,
    pickerOpen: Boolean,
    nextUpVisible: Boolean,
    // R218 (FR-R218-1, moment C) — the chrome is forced up while this is true (see this composable's
    // call site) and the play button shows a spinner in place of its glyph instead of the usual icon.
    stallActive: Boolean = false,
    // R218 (FR-R218-1, moment D) — forwarded straight to SeekRow's own spinner; see that param's doc.
    seekMomentActive: Boolean = false,
    onControlClick: (PlFocus) -> Unit,
    onControlHover: (PlFocus) -> Unit,
    onSeekStart: (Long) -> Unit,
    onSeekDrag: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    // R251 (FR-R251-4) — the bottom transport band's measured height in px (it is bottom-anchored, so
    // this is also its height above the screen edge); the caller lifts subtitles above it.
    onTransportBandHeight: (Int) -> Unit = {},
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
                .padding(horizontal = 48.dp)
                // R337 (FR-R337-9) — the Mac's traffic lights float over the picture's top-left: Back sits after them.
                .padding(start = MAC_LIGHTS_PAD),
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
            // 2026-09-24 — the DIRECT PLAY / HLS + container pill (R14) is gone: R180 FR-RV-ASP1-2, no
            // delivery-method cue may reach a viewer, and it shipped in every release build.
            Spacer(Modifier.weight(1f))
            // R303 (FR-R303-1) — what is playing, top right, inside the chrome so it fades with it. Its
            // own composable (dev review item 5) — nothing more of it may be inlined here.
            PlayerIdentSlot(
                logoUrl = logoUrl, logoInk = logoInk, seriesName = seriesName,
                maxWidth = TV_IDENT_MAX_WIDTH, maxHeight = TV_IDENT_MAX_HEIGHT,
                nameFontSize = 16.sp, nameLineHeight = 17.sp,
            )
        }

        // Bottom transport
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { onTransportBandHeight(it.height) }  // R251 (FR-R251-4)
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
                overflow = TextOverflow.Ellipsis,  // R251 (FR-R251-3)
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
                isSeeking  = seekMomentActive,
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
                    isBuffering = stallActive,
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
                // R376 (FR-R376-8) — picture-in-picture, where the browser has it; absent, never greyed.
                if (hasPip) TrackButton(
                    label = str("player.pip"), focused = focus == PlFocus.PIP,
                    onClick = { onControlClick(PlFocus.PIP) },
                    onHover = { onControlHover(PlFocus.PIP) },
                )
                // R376 (FR-R376-4b) — the browser tab's full screen, right after picture-in-picture; absent, never greyed.
                if (PlayerFullscreen.available) TrackButton(
                    label = str(if (PlayerFullscreen.active) "desk.exit_fullscreen" else "desk.fullscreen"), focused = focus == PlFocus.FULLSCREEN,
                    onClick = { onControlClick(PlFocus.FULLSCREEN) },
                    onHover = { onControlHover(PlFocus.FULLSCREEN) },
                )
                if (hasSpeakers) TrackButton(
                    label = str("tv.speakers"), focused = focus == PlFocus.SPEAKERS,
                    onClick = { onControlClick(PlFocus.SPEAKERS) },
                    onHover = { onControlHover(PlFocus.SPEAKERS) },
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
    // R218 (FR-R218-1, moment D) — "lightest of the four: no overlay, no words." There is no trickplay
    // scrub tile yet (StreamTicket.trickplayUrl is always null — see PlaybackService; that's its own
    // future phase per this phase's own Out-of-scope section), so this is the nearest real surface to
    // the design's "spinner inside the scrub tile": a small spinner beside the position timestamp,
    // which — like the design's tile — sits right where the viewer is already looking while a seek
    // resolves, and never blanks the transport.
    isSeeking: Boolean = false,
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
        if (isSeeking) {
            val rotation by rememberInfiniteTransition(label = "seekBuf")
                .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "seekBufRot")
            Canvas(Modifier.size(14.dp)) {
                drawArc(
                    color = colors.accent,
                    startAngle = rotation,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
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

        // R365 (FR-R365-8) — while scrubbing, the HANDLE (with its focus ring) is where the video will jump to, and a
        // faint marker stays at the real position on the played fill; the 4 dp "ghost" hairline it replaces was the
        // only thing that moved, so the scrub looked like nothing happening.
        if (scrubbing) {
            val realX = w * played
            drawRect(Color.White.copy(alpha = 0.55f), Offset(realX - 1.5.dp.toPx(), y - 9.dp.toPx()), Size(3.dp.toPx(), 18.dp.toPx()))
        }

        // Handle
        val handleR = if (focused) 13.dp.toPx() else 10.dp.toPx()
        val handleX = w * (if (scrubbing) scrubFrac else played)
        if (focused) {
            drawCircle(ringColor.copy(alpha = 0.45f), handleR + 6.dp.toPx(), Offset(handleX, y))
        }
        drawCircle(Color.White, handleR, Offset(handleX, y))
    }
}

// ─── Control buttons ──────────────────────────────────────────────────────────

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    focused: Boolean,
    onClick: () -> Unit = {},
    onHover: () -> Unit = {},
    // R218 (FR-R218-1, moment C) — "a spinner standing in the play button's place." A boolean, not a
    // third icon state: buffering wins over play/pause visually (the glyph underneath is irrelevant
    // while it's true), and clears the instant playback resumes since the caller derives this from the
    // same debounced moment state driving the rest of the stall treatment.
    isBuffering: Boolean = false,
) {
    val colors = RaviloTheme.colors
    val grad = remember(colors.accent, colors.accentSecondary) { colors.accentGradient }
    val size = 44.dp   // DEB-2204 (FR-2) — the focused 50 dp is a draw-only scale now, so the row never reflows
    val glyphColor = Color.White
    Box(
        modifier = Modifier
            .focusScaleGlow(focused, 50f / 44f, 14.dp, CircleShape, colors.focusGlow)
            .size(size)
            .clip(CircleShape)
            .background(if (focused) grad else Brush.linearGradient(listOf(Color.White.copy(0.14f), Color.White.copy(0.14f))))
            .focusRing(focused, CircleShape, colors.focusRing.copy(alpha = 0.6f), 1.dp, resting = Color.White.copy(alpha = 0.22f))
            // R157 (FR-R157-2.1/2.2) — a real pointer target: click activates, hover moves logical
            // focus here. Deliberately NOT dpadFocusable/.focusable() — the root Box is the sole real
            // Compose-focus owner (D-pad navigation is hand-rolled via the PlFocus enum); making every
            // button its own focus node would fight that model and risk breaking D-pad/TV navigation.
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
            .hoverToCall(onHover),
        contentAlignment = Alignment.Center,
    ) {
        if (isBuffering) {
            val rotation by rememberInfiniteTransition(label = "ppBuf")
                .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "ppBufRot")
            Canvas(Modifier.size(size * 0.6f)) {
                drawArc(
                    color = glyphColor,
                    startAngle = rotation,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        } else {
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
}

// R158: transport-control focus chrome matches the app-wide language (accent ring + focusGlow +
// draw-only scale) instead of an opaque fill flip — the resting translucent pill never changes color.
@Composable
private fun SkipButton(label: String, focused: Boolean, onClick: () -> Unit = {}, onHover: () -> Unit = {}) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier
            .focusScaleGlow(focused, 1.06f, 14.dp, RoundedCornerShape(10.dp), colors.focusGlow)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .focusRing(focused, RoundedCornerShape(10.dp), colors.accent, 2.dp, resting = Color.White.copy(alpha = 0.22f), restingWidth = 1.dp)
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
            .focusScaleGlow(focused, 1.04f, 14.dp, RoundedCornerShape(10.dp), colors.focusGlow)
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .focusRing(focused, RoundedCornerShape(10.dp), colors.accent, 2.dp, resting = Color.White.copy(alpha = 0.22f), restingWidth = 1.dp)
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

/** R337 (FR-R337-9) — room for the Mac's traffic lights before the player's Back. A constant, not an expression in the
 *  chrome: that function is near ART's register ceiling (R258). */
private val MAC_LIGHTS_PAD = if (dev.jellystructure.ravilo.ui.isMacPlatform) 44.dp else 0.dp

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
                .focusScaleGlow(focused, 1.06f, 14.dp, CircleShape, colors.focusGlow)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .focusRing(focused, CircleShape, colors.accent, 2.dp, resting = Color.White.copy(alpha = 0.22f), restingWidth = 1.dp),
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

/**
 * 2026-09-24 — plain black over the video surface while a stream is being negotiated or has not drawn
 * its first frame. Between two streams (a restream: burn-in, un-burn, audio) the surface otherwise shows
 * whatever the decoder's reconfiguration leaves in it — on the soveværelse TV's MediaTek decoder, a green
 * frame with the old picture shrunk into its top-left corner. Everything R218 draws for the wait sits
 * above this, unchanged. Its own composable so the check-player-dex budget is not spent in PlayerScreen.
 */
@Composable
private fun VideoShutter(visible: Boolean) {
    if (visible) Box(Modifier.fillMaxSize().background(Color.Black))
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
            dev.jellystructure.ravilo.ui.components.ArrowGlyph(dev.jellystructure.ravilo.ui.components.GlyphDirection.DOWN, Color.White, 13.dp)   // R315
        }
        Text(str("detail.episodes"), color = Color.White.copy(0.7f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Track picker popup ───────────────────────────────────────────────────────

// R180 (FR-RV-ASP1-1) — one row's presentation data, resolved once per recomposition from either a
// PlayerAudioTrack or a PlayerSubtitleTrack (or neither, for the synthetic Off row).
/**
 * R195 §3 — the picker is two levels: [pickerLevel] 0 shows one row per [PickerLanguage] in the
 * active tab's group list; [pickerLevel] 1 shows the [pickerIdx]-selected group's own
 * [PickerLanguage.versions]. [onTapLanguage]/[onTapVersion] give touch (phone/web) exact parity with
 * the D-pad — both just move the target index then run through PlayerScreen's SAME pickerSelect() the
 * Select key already uses (see the call site). [audioGroups]/[subGroups] are BOTH needed (not just the
 * active tab's) because the tab bar shows both tabs' flags regardless of which is active.
 */
@Composable
internal fun TrackPicker(
    colors: RaviloColors,
    pickerTab: Int,
    pickerLevel: Int,
    audioGroups: List<PickerLanguage>,
    subGroups: List<PickerLanguage>,
    pickerIdx: Int,
    pickerVersionIdx: Int,
    selectedAudio: Int,
    selectedSub: Int,
    onTapLanguage: (Int) -> Unit,
    onTapVersion: (Int) -> Unit,
    onTapBack: () -> Unit,
    // R195 bug fix: PickerTab had no touch handling at all — Left/Right only ever reached this via a
    // D-pad, so a phone (no D-pad) could never switch to the Subtitles tab. A tap now runs the exact
    // same tab-switch logic Left/Right already use (reset to level 1, re-target the new tab's group
    // containing the live selection).
    onTapTab: (Int) -> Unit,
    // R244 (FR-R244-10) — inside a HandsetSheet: full width, no popup chrome of its own; [extraRow]
    // is the sheet's one addition (the Subtitle size row), rendered after the list.
    handset: Boolean = false,
    extraRow: (@Composable () -> Unit)? = null,
    // Phase 314c — the Picture tab (the film's versions), shown only when there are two or more.
    pictureVersions: List<dev.jellystructure.shared.tv.VideoVersion> = emptyList(),
) {
    val lang = LocalLang.current
    val pictureGroups = remember(pictureVersions) { pictureVersionGroups(pictureVersions) }
    val selectedPicture = pictureVersions.indexOfFirst { it.current }
    val groups = when (pickerTab) { 0 -> audioGroups; 1 -> subGroups; else -> pictureGroups }
    val selectedFlat = when (pickerTab) { 0 -> selectedAudio; 1 -> selectedSub; else -> selectedPicture }
    val audioFlag = flagFor(audioGroups.firstOrNull { g -> g.versions.any { it.flatIndex == selectedAudio } }?.language)
    val subFlag = flagFor(subGroups.firstOrNull { g -> g.versions.any { it.flatIndex == selectedSub } }?.language)

    Box(
        modifier = if (handset) Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp) else Modifier
            .padding(end = 48.dp, bottom = 36.dp)
            .width(520.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E1119).copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(22.dp),
    ) {
        Column {
            if (pickerLevel == 0) {
                // Tabs — only shown at level 1 (§D: switching tab always resets to level 1 anyway).
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PickerTab(str("player.tab_audio"), pickerTab == 0, audioFlag, onTap = { onTapTab(0) })
                    PickerTab(str("player.tab_subtitles"), pickerTab == 1, subFlag, onTap = { onTapTab(1) })
                    if (pictureGroups.isNotEmpty()) PickerTab(str("player.tab_picture"), pickerTab == 2, null, onTap = { onTapTab(2) })
                }
                Spacer(Modifier.height(14.dp))
            }

            val group = groups.getOrNull(pickerIdx)
            if (pickerLevel == 1 && group != null) {
                PickerCrumbHeader(group = group, colors = colors, onBack = onTapBack)
                Spacer(Modifier.height(10.dp))
            }

            val listState = rememberLazyListState()
            val rowCount = if (pickerLevel == 0) groups.size else (group?.versions?.size ?: 0)
            // An Unnamed cluster's hint is list item 0 at level 2, so rows sit one item further down.
            val headerItems = if (pickerLevel == 1 && group?.isUnnamed == true) 1 else 0
            val focusedIdx = if (pickerLevel == 0) pickerIdx else pickerVersionIdx
            val lastIdx = (rowCount - 1).coerceAtLeast(0)

            // R180 — open pre-scrolled to the active row (jump, no animation); R195 — also whenever the
            // level itself changes. 2026-09-24: with ONE row of context above it — pinning the active
            // row to the top hid *Off* just above *English* on the soveværelse TV, and OK was pressed on
            // the wrong row.
            LaunchedEffect(pickerTab, pickerLevel) {
                listState.scrollToItem((headerItems + focusedIdx.coerceIn(0, lastIdx) - 1).coerceAtLeast(0))
            }
            // R180 — keep the D-pad-focused row visible as it moves (10+ track titles genuinely
            // overflow a fixed-height list — verified: a real title has 7 audio + 13 subtitle tracks).
            // 2026-09-24: scroll only when the row (or its neighbour) would leave the list, instead of
            // re-pinning every move to the top, which made the whole list jump on each press.
            LaunchedEffect(focusedIdx) {
                listState.revealPickerRow(headerItems + focusedIdx.coerceIn(0, lastIdx), headerItems + lastIdx)
            }
            // R291 (FR-R291-2) — the audio row the D-pad rests on is warmed, so picking it finds its first
            // segment encoded. Only a row whose OK switches outright: a single-version language, or a
            // version at level 2 — never the playing track, never a subtitle, never on a touch sheet.
            if (!handset) LaunchedEffect(pickerTab, pickerLevel, pickerIdx, pickerVersionIdx) {
                val target = audioWarmTarget(pickerTab, pickerLevel, audioGroups.getOrNull(pickerIdx), pickerVersionIdx)
                if (target != null && target != selectedAudio) {
                    delay(AUDIO_WARM_DWELL_MS)
                    warmAudioRendition(target)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pickerLevel == 0 && pickerTab == 2) {
                    // Phase 314c — a version is a plain row: its name in plain words, the tick on the one playing.
                    itemsIndexed(groups) { i, g ->
                        PickerPictureRow(
                            label = g.sampleTitle.orEmpty(),
                            selected = g.versions.any { it.flatIndex == selectedFlat },
                            focused = i == pickerIdx,
                            colors = colors,
                            onTap = { onTapLanguage(i) },
                        )
                    }
                } else if (pickerLevel == 0) {
                    itemsIndexed(groups) { i, g ->
                        val active = g.versions.any { it.flatIndex == selectedFlat }
                        PickerLanguageRow(
                            group = g,
                            selected = active,
                            focused = i == pickerIdx,
                            colors = colors,
                            lang = lang,
                            // R238 — so the row can caption itself with the version actually playing.
                            selectedFlat = selectedFlat,
                            // R247 (FR-R247-5) — a subtitle row never draws the microphone.
                            isSubtitle = pickerTab == 1,
                            onTap = { onTapLanguage(i) },
                        )
                    }
                } else if (group != null) {
                    // §E — the tail: an Unnamed cluster has no name to tell its versions apart by, so
                    // the footer hint (R197: "select one to switch instantly") matters more here than
                    // for a normal group, where the version rows' own sentences already do the job.
                    if (group.isUnnamed) {
                        item {
                            Text(
                                str("player.picker_preview_hint"),
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    itemsIndexed(group.versions) { i, v ->
                        PickerVersionRow(
                            group = group,
                            version = v,
                            selected = v.flatIndex == selectedFlat,
                            focused = i == pickerVersionIdx,
                            colors = colors,
                            lang = lang,
                            onTap = { onTapVersion(i) },
                        )
                    }
                }
            }
            extraRow?.invoke()
        }
    }
}

/** R291 — long enough that sweeping past a row warms nothing (a remote's key repeat is 100–200 ms a step),
 *  short enough to finish before a deliberate OK. 2026-09-26: 500 → 250 ms once a warm became cheap on the
 *  server (a warm-only job stops 3 segments ahead); on the stue TV a warmed switch froze 310–400 ms, a cold one 1.46 s. */
private const val AUDIO_WARM_DWELL_MS = 250L

/** R291 — the ticket audio position OK would switch to on the focused row, or null when OK does not switch
 *  (a subtitle tab, or a language holding several versions, whose OK opens level 2). */
internal fun audioWarmTarget(pickerTab: Int, pickerLevel: Int, group: PickerLanguage?, pickerVersionIdx: Int): Int? = when {
    pickerTab != 0 || group == null -> null
    pickerLevel == 1 -> group.versions.getOrNull(pickerVersionIdx)?.flatIndex
    group.versions.size == 1 -> group.versions[0].flatIndex
    else -> null
}

/** §B — level 2's header bar: the flag sits ONCE here (never repeated per version row), with the
 *  language name and version count. Also hosts the touch Back affordance (§D: hardware Back already
 *  returns to level 1 via PlayerLifecycleEffect/dpadFocusable's onBack; this is the tap equivalent for
 *  a phone/web viewer with no hardware Back key in reach). */
@Composable
private fun PickerCrumbHeader(group: PickerLanguage, colors: RaviloColors, onBack: () -> Unit) {
    val lang = LocalLang.current
    val flagRes = flagFor(group.language)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onBack)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("‹", color = colors.textSecondary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (flagRes != null && !group.isUnnamed) {
            Image(
                painter = painterResource(flagRes),
                contentDescription = null,
                modifier = Modifier
                    .size(width = 28.dp, height = 20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color.White.copy(0.18f), RoundedCornerShape(4.dp)),
            )
        }
        Column {
            Text(
                if (group.isUnnamed) str("player.unnamed") else groupDisplayName(group, lang),
                color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                t("player.versions_count", lang, mapOf("n" to group.versions.size.toString())),
                color = colors.textSecondary, fontSize = 11.sp,
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(0.10f)))
}

/**
 * 2026-09-24 — where the picker list should scroll so the focused row is visible with one neighbour of
 * context on the side it is moving towards. Pure, so the rule is unit-tested without a LazyColumn:
 * returns the item index to put at the top of the list, or null when nothing needs to move.
 * [firstFull]/[lastFull] are the first and last FULLY visible item indices.
 */
internal fun pickerScrollTarget(idx: Int, lastItem: Int, firstFull: Int, lastFull: Int): Int? {
    val above = (idx - 1).coerceAtLeast(0)
    val below = (idx + 1).coerceAtMost(lastItem)
    val window = (lastFull - firstFull).coerceAtLeast(0)
    return when {
        above < firstFull -> above
        below > lastFull -> (below - window).coerceAtLeast(0)
        else -> null
    }
}

private suspend fun androidx.compose.foundation.lazy.LazyListState.revealPickerRow(idx: Int, lastItem: Int) {
    val info = layoutInfo
    val full = info.visibleItemsInfo.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
    if (full.isEmpty()) { scrollToItem((idx - 1).coerceAtLeast(0)); return }
    val target = pickerScrollTarget(idx, lastItem, full.first().index, full.last().index) ?: return
    animateScrollToItem(target)
}

@Composable
private fun PickerTab(label: String, active: Boolean, flagRes: DrawableResource? = null, onTap: (() -> Unit)? = null) {
    val colors = RaviloTheme.colors
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(30.dp))
            // 2026-09-24 — the current tab is a FILLED chip, not a ring. It used to carry the same 2dp
            // accent ring as the focused row, so after Left/Right the picker showed two focus rings and
            // the viewer could not tell that Up/Down move the list, not the tabs. The picker's only ring
            // is the focused row's.
            .background(if (active) colors.accent.copy(alpha = 0.28f) else Color.White.copy(0.06f))
            .border(1.dp, if (active) colors.accent.copy(alpha = 0.45f) else Color.White.copy(0.12f), RoundedCornerShape(30.dp))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
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

/**
 * R247 (FR-R247-4) — what a level-1 row prints: the primary name and, only for a code nothing
 * recognises, that code as a secondary `xx` beside it. A recognised code never reaches the screen as
 * a code. For an unrecognised one the track's own label (the group's first non-blank title) is the
 * primary and the code is the secondary; with no label at all the code itself is the primary (R206:
 * a row is never rendered with no name) and nothing is repeated beside it.
 */
internal fun pickerRowName(group: PickerLanguage, lang: String): Pair<String, String?> {
    val code = group.language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (code == null || isKnownLanguage(code)) return groupDisplayName(group, lang) to null
    val kindWord = when {
        group.versions.all { it.kind == VariantKind.COMMENTARY } -> t("player.badge_commentary", lang)
        group.versions.all { it.kind == VariantKind.DESCRIBE } -> t("player.badge_describes_action", lang)
        else -> null
    }
    val primary = group.sampleTitle?.takeIf { it.isNotBlank() } ?: kindWord ?: return code to null
    return primary to code
}

/**
 * R195 §A/§B — a [PickerLanguage] group's own display name (level-1 row / level-2 crumb header).
 * `group.language` covers the overwhelming majority of groups; a null-language cluster (untagged
 * tracks — real, e.g. a commentary track with no language tag, per R180's own `pickerName` doc) has
 * no language to name itself after, so falls back to its dominant [VariantKind] as a clean word
 * (never the raw title text — R180's FR-RV-ASP1-2 "never echo raw title/codec text" non-goal still
 * applies here). [isOff]/[isUnnamed] groups are named by their caller (str("off")/str("player.unnamed"))
 * — this function is only for a real, language-or-kind-identifiable group.
 */
private fun groupDisplayName(group: PickerLanguage, lang: String): String {
    // R206 — a non-null `group.language` unrecognized by BOTH endonym() and languageName() used to
    // fall through to a hardcoded "" here, rendering the row with a glyph and no text at all. Fall
    // through to the same kind-word logic used for a null-language cluster instead, and failing that,
    // show the raw code rather than nothing — a picker row is never rendered with no name.
    // R247 (FR-R247-4) — a recognised code always has a name here (the canonicaliser and the name
    // table are one list); the code-as-name fallback below is reached only for a code nothing knows,
    // and then it is the track's own label first, lower-case code last (never the shouting uppercase).
    if (group.language != null) {
        pickerName(group.language, "").takeIf { it.isNotBlank() }?.let { return it }
    }
    return when {
        group.versions.all { it.kind == VariantKind.COMMENTARY } -> t("player.badge_commentary", lang)
        group.versions.all { it.kind == VariantKind.DESCRIBE } -> t("player.badge_describes_action", lang)
        group.language != null -> group.sampleTitle?.takeIf { it.isNotBlank() } ?: group.language.trim().lowercase()
        else -> t("player.unnamed", lang)
    }
}

/**
 * R195 §A / R238 (FR-R238-1) — the badges a collapsed language row should caption itself with: those of
 * the version that is actually playing, or those of the only version there is, or none.
 *
 * Extracted as a pure function so it is testable (and so [PickerLanguageRow] does not grow — see
 * `PlayerSessionErrorOverlay`'s note on what inlining into big composables cost us).
 *
 * The bug this replaces: `firstOrNull { it.flatIndex >= 0 && (single || selected) }`, whose predicate
 * is loop-invariant in `single`/`selected` and so always yielded the FIRST version in stream order.
 * On Kulsort S07E03 that captioned a correctly-selected `Dansk (CC)` track with the forced track's
 * `Default` / `Signs only` badges — describing the exact track R235 exists to avoid, on the one
 * surface a viewer checks to confirm their subtitles are right. FR-R238-2: where the playing version
 * can't be identified we show nothing, because a missing badge costs nothing and a wrong one
 * misinforms.
 */
internal fun languageRowBadges(group: PickerLanguage, selectedFlat: Int): List<String> {
    group.versions.firstOrNull { it.flatIndex >= 0 && it.flatIndex == selectedFlat }
        ?.let { return it.badges }
    val only = group.versions.filter { it.flatIndex >= 0 }
    return if (only.size == 1) only[0].badges else emptyList()
}

/** Phase 314c — one picture version in the Picture tab. */
@Composable
private fun PickerPictureRow(label: String, selected: Boolean, focused: Boolean, colors: RaviloColors, onTap: () -> Unit) {
    val focused = focused && LocalFocusVisible.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .focusRing(
                focused, RoundedCornerShape(10.dp), colors.focusRing.copy(0.7f), 2.dp,
                fillFocused = Color.White.copy(alpha = 0.10f), fillResting = Color.Transparent,
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PickerTick(selected = selected, colors = colors)
        Text(label, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

/** Phase 314c — the Picture tab's rows: one group per version, its label carried as the group's title; none for one version. */
internal fun pictureVersionGroups(versions: List<dev.jellystructure.shared.tv.VideoVersion>): List<PickerLanguage> =
    if (versions.size < 2) emptyList() else versions.mapIndexed { i, v ->
        PickerLanguage(
            language = null, isOff = false, isUnnamed = false, sampleTitle = v.label,
            versions = listOf(PickerVersion(flatIndex = i, kind = VariantKind.PLAIN, region = null, badges = emptyList(), forced = false,
                isDefault = false, hadTitleText = true, ordinal = 0, clusterSize = 1)),
        )
    }

/** R195 §A — one row per language. No arrow + no count when there's exactly one version (OK selects
 *  it outright, matching R180's original one-press behaviour); a count + arrow otherwise. Badges
 *  belong to the single version, or — with several — to whichever one is currently playing. */
@Composable
private fun PickerLanguageRow(
    group: PickerLanguage,
    selected: Boolean,
    focused: Boolean,
    colors: RaviloColors,
    lang: String,
    selectedFlat: Int,
    isSubtitle: Boolean,
    onTap: () -> Unit,
) {
    // R298 (FR-R298-4) — the phone's sheet has no remote; the selected row's tick and tint are enough.
    val focused = focused && LocalFocusVisible.current
    val single = group.versions.size <= 1
    val badges = if (group.isOff) emptyList() else languageRowBadges(group, selectedFlat)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .focusRing(
                focused, RoundedCornerShape(10.dp), colors.focusRing.copy(0.7f), 2.dp,
                fillFocused = Color.White.copy(alpha = 0.10f), fillResting = Color.Transparent,
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PickerTick(selected = selected, colors = colors)
        PickerGlyphBox(language = group.language, isOff = group.isOff, isUnnamed = group.isUnnamed, isSubtitle = isSubtitle)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (name, code) = when {
                    group.isOff -> str("off") to null
                    group.isUnnamed -> str("player.unnamed") to null
                    else -> pickerRowName(group, lang)
                }
                Text(name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                // R247 (FR-R247-4) — an unrecognised code rides beside the label in the secondary style.
                if (code != null) Text(code, color = colors.textSecondary, fontSize = 11.sp)
            }
            if (badges.isNotEmpty()) PickerBadgeRow(badges, colors)
        }
        if (!single) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    t("player.versions_count", lang, mapOf("n" to group.versions.size.toString())),
                    color = colors.textSecondary, fontSize = 11.sp,
                )
                Text("›", color = colors.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** R195 §B/§C/§E — one version row inside level 2. The header already carries the language's own flag
 *  (§B: never repeated here); a REGION flag shows only when it differs from the header's language flag
 *  (§C) — a same-flag region (e.g. Português→Portugal, both flag_pt) shows no flag, relying on the
 *  header. Unnamed-group rows (§E) are numbered "Version N" instead of the language name. */
@Composable
private fun PickerVersionRow(
    group: PickerLanguage,
    version: PickerVersion,
    selected: Boolean,
    focused: Boolean,
    colors: RaviloColors,
    lang: String,
    onTap: () -> Unit,
) {
    val focused = focused && LocalFocusVisible.current   // R298 (FR-R298-4)
    val headerFlag = flagFor(group.language)
    val regionFlag = version.region?.flag?.takeIf { it != headerFlag }
    val baseName = if (group.isUnnamed) t("player.version_n", lang, mapOf("n" to (version.ordinal + 1).toString()))
        else groupDisplayName(group, lang)
    // Bug fix (live report, 2026-08-10): two same-language, same-kind tracks can both carry SOME
    // non-blank title text (so neither trips isUnnamed's "no title at all" case) yet still be
    // genuinely indistinguishable — two "English" rows both reading the identical sentence, no way
    // to tell them apart. `clusterSize > 1` catches this independent of isUnnamed (which only covers
    // the narrower "nothing at all is knowable" case) — append an explicit ordinal so this can never
    // render as two identical rows again, whatever the kind.
    val ambiguous = !group.isUnnamed && version.clusterSize > 1
    val name = if (ambiguous) {
        t("player.variant_ordinal_suffix", lang, mapOf("name" to baseName, "n" to (version.ordinal + 1).toString(), "total" to version.clusterSize.toString()))
    } else baseName
    val badges = version.badges + listOfNotNull(
        version.region?.name?.takeIf { version.kind == VariantKind.PLAIN || version.region.flag != null },
        // "Now showing" also earns its keep on an ambiguous-but-named cluster (e.g. two ordinally-
        // suffixed "English" rows) — not just the whole-group Unnamed case — since the ordinal alone
        // doesn't say WHICH of "English · 1/2" / "English · 2/2" is currently playing.
        if (selected) t("player.now_showing", lang).takeIf { group.isUnnamed || ambiguous } else null,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .focusRing(
                focused, RoundedCornerShape(10.dp), colors.focusRing.copy(0.7f), 2.dp,
                fillFocused = Color.White.copy(alpha = 0.10f), fillResting = Color.Transparent,
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PickerTick(selected = selected, colors = colors)
        // §C — a fixed-width slot even when this row has no flag, so text stays aligned when SOME
        // sibling rows do carry a region flag.
        Box(modifier = Modifier.size(width = 28.dp, height = 20.dp), contentAlignment = Alignment.Center) {
            if (regionFlag != null) Image(
                painter = painterResource(regionFlag),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).border(1.dp, Color.White.copy(0.18f), RoundedCornerShape(4.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            if (badges.isNotEmpty()) PickerBadgeRow(badges, colors)
            Text(
                versionSentence(version, group.isUnnamed, lang),
                color = colors.textSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun PickerTick(selected: Boolean, colors: RaviloColors) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accent else Color.Transparent)
            .border(2.dp, if (selected) colors.accent else Color.White.copy(0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) dev.jellystructure.ravilo.ui.components.CheckGlyph(colors.onAccent, 12.dp)   // R315
    }
}

@Composable
private fun PickerBadgeRow(badges: List<String>, colors: RaviloColors) {
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

// Flag / glyph — never a codec or delivery-method cue (FR-RV-ASP1-2). §E — Unnamed gets a neutral
// globe placeholder where the flag would be, distinct from Off's crossed-out glyph.
@Composable
private fun PickerGlyphBox(language: String?, isOff: Boolean, isUnnamed: Boolean, isSubtitle: Boolean = false) {
    val flagRes = if (!isOff && !isUnnamed) flagFor(language) else null
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
                when {
                    isOff -> PickerGlyphOff(Modifier.size(20.dp), tint)
                    isUnnamed -> PickerGlyphGlobe(Modifier.size(20.dp), tint)
                    // R247 (FR-R247-5) — a subtitle row with no flag says "captions", never "audio".
                    isSubtitle -> PickerGlyphCaptions(Modifier.size(20.dp), tint)
                    else -> PickerGlyphMic(Modifier.size(20.dp), tint)
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

/** R247 (FR-R247-5) — captions glyph for a subtitle row with no flag: the design's `I.cc` shape, a
 *  rounded box with two text lines inside, drawn in code the way [PickerGlyphMic] is. */
@Composable
private fun PickerGlyphCaptions(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val strokeW = w * 0.10f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.08f, h * 0.20f),
            size = Size(w * 0.84f, h * 0.60f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(strokeW, cap = StrokeCap.Round),
        )
        drawLine(tint, Offset(w * 0.26f, h * 0.44f), Offset(w * 0.74f, h * 0.44f), strokeW, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.26f, h * 0.60f), Offset(w * 0.58f, h * 0.60f), strokeW, StrokeCap.Round)
    }
}

/** R195 §E — neutral globe glyph for an "Unnamed" language group (tracks fully indistinguishable by
 *  any metadata this system has). A circle + one meridian ellipse + one equator line — deliberately
 *  distinct from both PickerGlyphOff (crossed-out) and PickerGlyphMic (a real, just untagged track). */
@Composable
private fun PickerGlyphGlobe(modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val strokeW = w * 0.09f
        drawCircle(color = tint, radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(strokeW))
        drawOval(
            color = tint,
            topLeft = Offset(w * 0.28f, h * 0.08f),
            size = Size(w * 0.44f, h * 0.84f),
            style = Stroke(strokeW * 0.8f),
        )
        drawLine(tint, Offset(w * 0.10f, h * 0.5f), Offset(w * 0.90f, h * 0.5f), strokeW * 0.8f, StrokeCap.Round)
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
    // R244 (FR-R244-12) — a full-width strip in portrait on a handset, and tappable buttons.
    handsetFull: Boolean = false,
    onPrimaryTap: (() -> Unit)? = null,
    onStayTap: (() -> Unit)? = null,
) {
    // R182 (FR-RV-SKIP1-2) — everything below the thumbnail/ring differs by mode; the NEXT_EPISODE case
    // is exactly the pre-R182 card, unchanged. Never more than two buttons in any mode.
    // R343 (FR-R343-5) — in a shuffle the next entry is not the next in order, and the card says so.
    val kickerText = if (mode == CreditsCardMode.NEXT_EPISODE) str(if (LocalShuffledNextUp.current) "player.up_next_shuffled" else "player.up_next") else str("player.credits_kicker")
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
        modifier = (if (handsetFull) Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
                    else Modifier.padding(end = 28.dp, bottom = 24.dp).width(360.dp))
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
                    Text(it, color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = titleText,
                    color = colors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SpaceGrotesk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,  // R251 (FR-R251-3)
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NuButton(
                        label = primaryLabel,
                        focused = nuFocus == NuFocus.PLAY,
                        isPrimary = true,
                        colors = colors,
                        onTap = onPrimaryTap,
                    )
                    NuButton(
                        label = str("player.watch_credits"),
                        focused = nuFocus == NuFocus.STAY,
                        isPrimary = false,
                        colors = colors,
                        onTap = onStayTap,
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
private fun NuButton(label: String, focused: Boolean, isPrimary: Boolean, colors: RaviloColors, onTap: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .focusScaleGlow(focused, 1.04f, 12.dp, RoundedCornerShape(8.dp), colors.focusGlow)
            .height(if (onTap != null) 46.dp else 34.dp)   // R244 — a phone target is ≥ 46 dp
            .then(if (onTap != null) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(
                focused, RoundedCornerShape(8.dp), colors.focusRing, 2.dp, resting = Color.White.copy(0.15f), restingWidth = 1.dp,
                fillFocused = if (isPrimary) Color.White else Color.White.copy(alpha = 0.18f),
                fillResting = if (isPrimary) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.08f),
            )
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (focused && isPrimary) Color.Black else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,  // R251 (FR-R251-3)
        )
    }
}

// ─── Skip Intro pill (R182) ───────────────────────────────────────────────────

/** R363 (FR-R363-5) — the pill's ring, by tag for `SkipIntroPillTest`. */
internal const val SKIP_INTRO_RING_TAG = "skip-intro-ring"

@Composable
internal fun SkipIntroPill(colors: RaviloColors, countdown: Int, totalSecs: Int, focused: Boolean, onTap: (() -> Unit)? = null, showRing: Boolean = true) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .focusScaleGlow(focused, 1.04f, 12.dp, RoundedCornerShape(10.dp), colors.focusGlow)
            // R244 — a phone taps the pill; the TV's D-pad path is unchanged.
            .then(if (onTap != null) Modifier.heightIn(min = 46.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap) else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .focusRing(
                focused, RoundedCornerShape(10.dp), colors.focusRing, 2.dp, resting = Color.White.copy(alpha = 0.14f), restingWidth = 1.dp,
                fillFocused = Color.White, fillResting = Color(0xFF0E1119).copy(alpha = 0.92f),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // R363 (FR-R363-5) — Auto only: in Prompt mode the ring counted down to nothing (the pill just hides).
        if (showRing) Box(Modifier.testTag(SKIP_INTRO_RING_TAG)) { CountdownRing(colors, countdown, totalSecs) }
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
            val seasonLabel = playerRailSeasonLabel(episodes, str("detail.season"))   // R350 (FR-R350-6)
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
            .focusScaleGlow(isFocused, 1.05f, 0.dp, RoundedCornerShape(9.dp), colors.focusGlow),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF1A1D28))
                .focusRing(isFocused, RoundedCornerShape(9.dp), colors.focusRing, 2.dp, resting = if (isCurrent) colors.accent else Color.Transparent),
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
            overflow = TextOverflow.Ellipsis,  // R251 (FR-R251-3)
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

/**
 * R218 (FR-R218-1, moment B) — Direction B's "pulsing brand dot cluster": three dots breathing in
 * scale+opacity, staggered 180ms apart, across the accent→accentSecondary gradient (skin-aware, unlike
 * the design file's hardcoded Aurora hex values — the same gradient [PlayPauseButton]'s focus ring and
 * [PlayerScreen]'s progress fill already use). Approximates the design's asymmetric 45%-peak keyframe
 * with a simple symmetric breathe (RepeatMode.Reverse) — visually equivalent for a continuous ambient
 * loop, not a cut corner that changes what it communicates.
 */
/**
 * R218's Direction B "Grounded", unchanged (FR-R218-6: "same three parts, scaled: 40px spinner, 26px
 * title, 15px label"; TV sizes are the on-device-verified treatment, stue TV 2026-08-29; the phone gets
 * the design file's own numbers). R290 draws it from the press to the latch and R218 for a later COLD
 * moment — one composable, so the two cannot drift. [retrying] is R237's FR-R237-5 line, the only variation.
 */
@Composable
private fun PlayerStartScreen(colors: RaviloColors, itemKicker: String?, itemTitle: String, retrying: Boolean) {
    val isPhone = LocalHandset.current && !dev.jellystructure.ravilo.ui.isDesktopPlatform   // R337 dev review 9
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandPulse(colors, dotSize = if (isPhone) 10.dp else 16.dp, gap = if (isPhone) 9.dp else 14.dp)
            Spacer(Modifier.height(if (isPhone) 20.dp else 34.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                itemKicker?.let { kicker ->
                    // R180 (FR-RV-ASP1-2) — the chrome's own kicker/title split, reused verbatim.
                    Text(kicker.uppercase(), color = colors.accentSecondary, fontSize = if (isPhone) 11.sp else 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(Modifier.height(if (isPhone) 4.dp else 6.dp))
                }
                Text(itemTitle, color = Color.White, fontSize = if (isPhone) 26.sp else 30.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = (-0.8).sp)
            }
            Spacer(Modifier.height(if (isPhone) 20.dp else 34.dp))
            IndeterminateSweep(colors, width = if (isPhone) 200.dp else 280.dp, height = if (isPhone) 3.dp else 4.dp)
            Spacer(Modifier.height(if (isPhone) 18.dp else 28.dp))
            Text(str("loading"), color = Color.White.copy(0.7f), fontSize = if (isPhone) 15.sp else 18.sp)
            if (retrying) {
                Spacer(Modifier.height(10.dp))
                Text(str("loading.still_trying"), color = Color.White.copy(0.45f), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun BrandPulse(colors: RaviloColors, dotSize: Dp = 16.dp, gap: Dp = 14.dp) {
    val dotColors = remember(colors.accent, colors.accentSecondary) {
        listOf(colors.accent, lerp(colors.accent, colors.accentSecondary, 0.5f), colors.accentSecondary)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        dotColors.forEachIndexed { i, dotColor ->
            val phase by rememberInfiniteTransition(label = "pulse$i").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 180),
                ),
                label = "pulsePhase$i",
            )
            Box(
                Modifier
                    .size(dotSize)
                    .scale(0.8f + phase * 0.45f)
                    .alpha(0.2f + phase * 0.8f)
                    .background(dotColor, CircleShape),
            )
        }
    }
}

/**
 * R218 (FR-R218-1, moment B) — Direction B's indeterminate sweep: a gradient segment crossing the bar
 * on a loop, "so a long wait never looks frozen." No numbers, no percentage (this phase's own
 * invariant) — motion alone.
 */
@Composable
private fun IndeterminateSweep(colors: RaviloColors, width: Dp, height: Dp = 4.dp) {
    val grad = remember(colors.accent, colors.accentSecondary) { colors.accentGradient }
    val progress by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = -0.4f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "sweepX",
    )
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height))
            .background(Color.White.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(width * 0.38f)
                .offset(x = width * progress)
                .clip(RoundedCornerShape(height))
                .background(grad),
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
private fun endonym(code: String?): String? = endonymOf(code)
// R247 (FR-R247-3) — the endonym column lives in seams/LanguageIdentity.kt beside the English one,
// keyed by canonical code; `RAVILO_ENDONYMS` (24 raw-keyed entries) is gone.

private enum class TrackVariant { SDH, DESCRIBES_ACTION, COMMENTARY, NONE }

// (FR-RV-ASP1-3) — derived from a track's own label/DisplayTitle, verified against the real library DB
// during the R180 dev review (audio titles carry these too, e.g. "Synstolkning", "Commentary by…").
// R195 (FR-RV §5.1) — widened to also catch a bare "HI" token and the word "hearing" alone (was only
// "sdh" or the full phrase "hard of hearing"); still title-text only — an untitled SDH track needs the
// Bazarr `hi` flag plumbing this phase's dev-review addendum scoped as separate backend work.
// R235 (FR-R235-5) — "cc"/"closed caption" is the same descriptive-subtitle concept as SDH and was
// unmatched (the reported file's full track is titled "Dansk (CC)"), so it grouped as PLAIN with no
// badge and no distinct variant signature from the file's other (forced) Danish track.
private val SDH_RE get() = dev.jellystructure.shared.tv.SDH_RE   // R291 — one regex, in :shared
private val AD_RE get() = dev.jellystructure.shared.tv.AD_RE   // R291 — :shared
// 2026-09-24 — the word in the languages this library's release titles are written in, not only
// English: a Norwegian-titled *Kommentarspor med regissører* track got no Commentary badge.
// `kommentar` covers da/no/sv/de (incl. compounds like Kommentarspor), the rest fi/fr/es/pt/it/nl.
private val COMMENTARY_RE get() = dev.jellystructure.shared.tv.COMMENTARY_RE   // R291 — :shared
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
internal fun audioBadges(track: PlayerAudioTrack, originalLanguage: String?, lang: String): List<String> {
    val badges = mutableListOf<String>()
    if (track.isDefault) badges += t("player.badge_default", lang)
    track.channels?.let { ch ->
        when {
            // 2026-09-24 — a TrueHD 7.1 Atmos track (8 channels) was badged "Surround 5.1".
            ch >= 7 -> badges += t("player.badge_surround71", lang)
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
    // R247 (FR-R247-6) — identity, not string equality: jellystructure's `originalLanguage` is `en`,
    // Jellyfin's stream code is `eng`, and `equals(ignoreCase)` badged every original-language track
    // on every 3-letter-coded title Dubbed.
    val dubbed = !isOriginalMarked(track.label) &&
        originalLanguage != null && track.language != null &&
        !sameLanguage(originalLanguage, track.language)
    if (dubbed) badges += t("player.badge_dubbed", lang)
    return badges
}

/** R180 (FR-RV-ASP1-3) — jargon-free badge words for a subtitle row. Default and Signs-only (forced)
 *  may co-occur, per the spec. See [audioBadges] for why [lang] is explicit. */
internal fun subtitleBadges(track: PlayerSubtitleTrack, lang: String): List<String> {
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

// ─── R195 — same-language disambiguation: kind/region classification, grouping, provenance suppression ───
//
// Dev-review addendum (2026-08-10) findings this builds on: SDH/region title-text detection already
// existed (SDH_RE/REGION_MARKERS, R180 stage 3) — this section EXTENDS that infrastructure rather than
// replacing it. `trackVariant`/`REGION_MARKERS`/`regionSuffix` above stay as-is (still used for the
// flat single-version case's muted suffix); the grouping/kind/region types below are the new,
// two-level-picker-specific layer.

// R291 (FR-R291-1) — one definition, in :shared, so the server's resolve and the picker cannot disagree.
internal typealias VariantKind = dev.jellystructure.shared.tv.VariantKind

private fun variantKind(title: String?, forced: Boolean): VariantKind = dev.jellystructure.shared.tv.variantKind(title, forced)   // R291 — :shared

/**
 * R195 (FR-RV §5.2) — region synonym table: extends [REGION_MARKERS] (which only ever produced a muted
 * TEXT suffix) with an actual flag + a stable region code for the two-level picker's §C region flags.
 * `flag = null` is a deliberate, honest gap for Brazil/Taiwan: no `flag_br`/`flag_tw` drawable asset
 * exists in this module yet (checked: `composeResources/drawable/flag_*.png` has no br/tw entry) —
 * falls through to "no region flag shown, name text only" rather than show a WRONG flag or block this
 * phase on new artwork. `\b`-bounded, first-match-wins, same discipline as `REGION_MARKERS`.
 */
internal data class RegionInfo(val code: String, val name: String, val flag: DrawableResource?)

// R291 — the region table (code + name) is :shared's REGION_TABLE; only the flag is this module's.
// `flag = null` stays the honest gap for Brazil/Taiwan/Latin America/Canada/European (no drawable).
private fun regionInfoOf(region: dev.jellystructure.shared.tv.RegionCode?): RegionInfo? = region?.let {
    RegionInfo(it.code, it.name, when (it.code) { "es" -> flagFor("es"); "pt" -> flagFor("pt"); "cn" -> flagFor("zh"); else -> null })
}

private fun resolveRegion(title: String?): RegionInfo? = regionInfoOf(dev.jellystructure.shared.tv.regionOf(title))

/** R195 (FR-RV §5.3) — release-provenance tokens that are never a viewer-meaningful choice on their
 *  own. Used ONLY to detect a collapse-worthy duplicate in [buildLanguageGroups] (positive evidence
 *  the difference between two otherwise-identical tracks is release plumbing) — never to classify a
 *  track's [VariantKind]. */
private fun hasProvenanceMarker(title: String?): Boolean = dev.jellystructure.shared.tv.hasProvenanceMarker(title)   // R291 — :shared

/** Per-row input to [buildLanguageGroups] — the shape both audio and subtitle tracks flatten to, so
 *  the grouping logic stays generic over R195's "audio gets the identical treatment for free" non-goal. */
internal data class PickerEntryInput(
    val language: String?,
    val title: String?,
    val forced: Boolean,
    val isDefault: Boolean,
    val badges: List<String>,
)

/** One selectable version within a language group. [flatIndex] is this version's index into the
 *  underlying audioTracks/subtitleTracks list (Off is its own pseudo-group, never a PickerVersion —
 *  see [buildLanguageGroups]'s caller). [ordinal] is this version's position within its own (kind,
 *  region) cluster — feeds both "Recording {ordinal+1}"-style numbering and the remembered variant
 *  signature ([signature]). [clusterSize] is that cluster's total size — bug fix (live report,
 *  2026-08-10): a same-language, same-kind, no-region pair can both carry SOME non-blank title text
 *  (so neither is `isUnnamed`'s "no title at all" case) yet still be genuinely indistinguishable —
 *  two "English" rows both reading "The full version of everything spoken." with no way to tell them
 *  apart. `clusterSize > 1` flags exactly this, independent of the whole-group `isUnnamed` flag,
 *  which only covers the narrower case where NOTHING in the group has any name/kind/region at all. */
internal data class PickerVersion(
    val flatIndex: Int,
    val kind: VariantKind,
    val region: RegionInfo?,
    val badges: List<String>,
    val forced: Boolean,
    val isDefault: Boolean,
    val hadTitleText: Boolean,
    val ordinal: Int,
    val clusterSize: Int,
)

/** R195 (FR-RV §5.4) — the opaque signature a [PickerVersion] resolves to for [RememberedChoice].
 *  Never contains `:` or `,` — see [RememberedChoice]'s doc (the wasm actual's hand-rolled parser
 *  splits on both). Format: `"<kind>|<regionCode>|<ordinal>"`. */
internal fun PickerVersion.signature(): String = dev.jellystructure.shared.tv.variantSignature(kind, region?.code, ordinal)   // R291 — the server signs the same way

internal data class PickerLanguage(
    val language: String?,
    val isOff: Boolean,
    val versions: List<PickerVersion>,
    /** §E — every version in this language is fully indistinguishable (same kind=PLAIN, no region, no
     *  title text at all — the only way that can genuinely happen). Level 1 shows "Unnamed" + a neutral
     *  globe glyph instead of a flag; level 2 numbers them "Version 1"…"Version n". */
    val isUnnamed: Boolean,
    /** R247 (FR-R247-4) — the group's first non-blank track title, the primary label for a language
     *  code nothing recognises (the code itself then rides beside it in the secondary style). */
    val sampleTitle: String? = null,
)

/** R246 (FR-R246-5) — one string per track set, so a set that changed after the first resolve is detectable. */
internal fun trackSetSignature(audio: List<PlayerAudioTrack>, subs: List<PlayerSubtitleTrack>): String =
    audio.joinToString(";") { "a:${it.index}:${it.language}:${it.label}" } + "|" + subs.joinToString(";") { "s:${it.index}:${it.language}:${it.label}:${it.forced}" }

/**
 * R282 (FR-R282-3) — the subtitle the picker shows as selected: the burned-in track's flat index
 * (`nativeCount + its position among the encode subs`, the same scheme `subVersionOptions` uses) while
 * a burn-in is active, else the text selection. One derivation for every display site, so the picker
 * always names what is on screen. A burn-in the ticket's own list does not contain shows as [selectedSub].
 */
internal fun shownSelectedSub(selectedSub: Int, nativeCount: Int, encodeSubs: List<PlayerSubtitleTrack>, burnedIndex: Int?): Int {
    if (burnedIndex == null) return selectedSub
    val i = encodeSubs.indexOfFirst { it.jellyfinStreamIndex == burnedIndex }
    return if (i >= 0) nativeCount + i else selectedSub
}

/**
 * R284 (FR-R284-1) — the audio list the picker and the resolver see. On a single-audio session
 * ([carriedIndex] non-null) an HLS stream exposes ONE track to the player — on Android it was even
 * labelled with the first ticket track's name — so the ticket's list is the real one. Waits for the
 * player to report its own tracks first ([playerTracks] non-empty), so "tracks are ready" keeps
 * meaning what R181's resolver gate assumes. Direct play: the player's list, untouched.
 */
internal fun sessionAudioTracks(sessionAudio: List<dev.jellystructure.shared.tv.AudioTrack>, carriedIndex: Int?, playerTracks: List<PlayerAudioTrack>): List<PlayerAudioTrack> =
    if (carriedIndex == null || sessionAudio.isEmpty() || playerTracks.isEmpty()) playerTracks
    else sessionAudio.mapIndexed { i, a ->
        PlayerAudioTrack(i, a.label?.takeIf { it.isNotBlank() } ?: languageName(a.language) ?: a.language?.uppercase() ?: "Track ${i + 1}", a.language, a.channels, a.isDefault,
            copyOf = a.copyOf?.let { src -> sessionAudio.indexOfFirst { it.index == src } }?.takeIf { it >= 0 })
    }

/**
 * Phase 314b (FR-314-4/-5) — the audio picker's groups with every added copy folded onto its source's row: a copy
 * jellystructure added (an AAC *Stereo*, an E-AC-3 *Surround*) is the same audio, so the viewer sees one row per real
 * track. [build] is the grouping (R195's `buildLanguageGroups` over the entries); the versions keep their flat index
 * into [tracks], so selection still addresses the player's own list. A copy whose source isn't in the list stays a row.
 */
internal fun foldedAudioGroups(tracks: List<PlayerAudioTrack>, build: (List<PlayerAudioTrack>) -> List<PickerLanguage>): List<PickerLanguage> {
    val visible = tracks.withIndex().filter { (_, t) -> t.copyOf == null || t.copyOf !in tracks.indices }
    if (visible.size == tracks.size) return build(tracks)
    return build(visible.map { it.value }).map { g -> g.copy(versions = g.versions.map { v -> v.copy(flatIndex = visible[v.flatIndex].index) }) }
}

/** Phase 314b — the row the picker shows as playing: a copy that plays shows its source's row. */
internal fun shownAudio(selected: Int, tracks: List<PlayerAudioTrack>): Int =
    tracks.getOrNull(selected)?.copyOf?.takeIf { it in tracks.indices } ?: selected

/** Phase 314b (FR-314-5) — what a pick of [flat] plays: its added copy when this device can't decode it and can the copy. */
internal fun playableAudio(flat: Int, tracks: List<PlayerAudioTrack>): Int {
    val t = tracks.getOrNull(flat) ?: return flat
    if (t.supported) return flat
    return tracks.indexOfFirst { it.copyOf == flat && it.supported }.takeIf { it >= 0 } ?: flat
}

/** R284 — the carried track's position in the session's audio list; null = not a single-audio session
 *  (or the ticket names a track its own list lacks — then the player's list is all there is). */
internal fun carriedAudioPosition(sessionAudio: List<dev.jellystructure.shared.tv.AudioTrack>, carriedIndex: Int?): Int? =
    carriedIndex?.let { c -> sessionAudio.indexOfFirst { it.index == c }.takeIf { it >= 0 } }

/** R282 (FR-R282-4) — what a subtitle pick does, given what is already burned into the picture. */
internal enum class SubPickAction { SELECT, BURN_IN, UNBURN, NONE }

/**
 * R282 (FR-R282-4). [pickIsEncode]/[pickStreamIndex] describe the picked version (Off = not encode);
 * [burnedIndex] is the current ticket's burn-in. The invariant this encodes: a text selection and a
 * burn-in never coexist — leaving a burn-in is always a restream, never a second renderer.
 */
internal fun subPickAction(pickIsEncode: Boolean, pickStreamIndex: Int?, burnedIndex: Int?): SubPickAction = when {
    pickIsEncode && pickStreamIndex == burnedIndex -> SubPickAction.NONE
    pickIsEncode -> SubPickAction.BURN_IN
    burnedIndex != null -> SubPickAction.UNBURN
    else -> SubPickAction.SELECT
}

/** R196 (FR-RV-TRK2-4) — result of [resolveTrackChoice]: the flat index to hand to
 *  `RaviloPlayer.selectAudioTrack`/`selectSubtitleTrack` (subtitle `-1` = off). */
internal data class TrackSelectionResult(val audioIndex: Int, val subIndex: Int)

/**
 * R181/R195/R196 (FR-RV-TRK1, FR-RV §5.4, FR-RV-TRK2-4) — the pure, unit-testable core of
 * [PlayerScreen]'s `resolveTrackSelection()`. Layered resolution: per-series remembered choice (exact
 * variant signature first, then the language's first version) → learned global choice (same) → the
 * source's own default track → first track / forced / off. Each tier's lookup returns null when it
 * can't be satisfied in THIS title (e.g. a remembered language absent from this file's tracks), falling
 * through to the next via `?:` rather than a hard-coded index. Matches by language, never by ExoPlayer
 * track index (indices differ across episodes/files).
 *
 * Extracted from the composable specifically so the R196 regression — a call site inside a
 * `LaunchedEffect(Unit)` poll loop that only ever sees a composition-old group list — has a test that
 * can actually catch a recurrence. R246 closed the residue of that bug for good: this function takes
 * the TRACK LISTS only and derives the groups itself, so there is no group parameter left to be stale.
 *
 * Scope note (unchanged since R181): only matches against native/external [subtitleTracks], never
 * PGS/encode burn-in subs — a remembered language that exists only as a PGS track in this file falls
 * through instead of silently triggering an autoplay transcode; PGS stays a manual pick.
 */
internal fun resolveTrackChoice(
    seriesChoice: RememberedChoice?,
    globalChoice: RememberedChoice?,
    audioTracks: List<PlayerAudioTrack>,
    subtitleTracks: List<PlayerSubtitleTrack>,
): TrackSelectionResult {
    // R246 (FR-R246-1) — the groups are derived HERE, from the lists this call is handed, through the
    // same buildLanguageGroups the picker uses. R196 left the composable responsible for supplying
    // live groups; on the tick the tracks arrive the freshest composed groups were still the previous
    // composition's (empty, or the outgoing episode's), and every remembered tier returned null —
    // exactly the source-default selections the 2026-09-16 sweep saw. Badges are display-only and
    // play no part in resolution, so none are built.
    val audioGroups = buildLanguageGroups(audioTracks.map { PickerEntryInput(it.language, it.label, forced = false, isDefault = it.isDefault, badges = emptyList()) })
    val subGroups = buildLanguageGroups(subtitleTracks.map { PickerEntryInput(it.language, it.label, forced = it.forced, isDefault = it.isDefault, badges = emptyList()) })
    // Phase 210/R241 — a remembered language must match even when this file tags it at a different
    // ISO-639 granularity than the file the choice was learned from. Confirmed against real production
    // data ("It's Always Rainy in Pittsburgh", 182 episodes/16 seasons of mixed release sources):
    // some seasons tag subtitle languages as 3-letter ("dan", "eng"), others as 2-letter ("da", "en") —
    // a plain `.equals(lang, ignoreCase = true)` never matches "da" against a remembered "dan", so the
    // remembered tier silently misses on the very next episode and falls through to the source's own
    // (often unrelated) default track — reported live as "it remembers on this episode, not the next."
    // R247 (FR-R247-2) — the comparison is the seam's `sameLanguage()` (canonical identity), no
    // longer a local `languageName(a) == languageName(b)` that missed every language the table lacked.

    // R235 (FR-R235-1/4) — within a language group, automatic selection (no exact remembered variant
    // match) must never land on a signs-only/commentary/audio-description version when a PLAIN one
    // exists in the same group: those exist to supplement a soundtrack the viewer already understands,
    // not to substitute for one they don't. An explicit remembered signature (bySignature) still wins
    // outright — this only changes what an UNMATCHED memory or a fresh pick falls through to.
    fun tierAudio(choice: RememberedChoice?): Int? {
        val lang = choice?.audioLanguage ?: return null
        val group = audioGroups.firstOrNull { sameLanguage(it.language, lang) } ?: return null
        val bySignature = choice.audioVariant?.let { sig -> group.versions.firstOrNull { it.signature() == sig } }
        return (bySignature ?: group.versions.firstOrNull { it.kind == VariantKind.PLAIN } ?: group.versions.firstOrNull())?.flatIndex
    }
    val audioIdx = tierAudio(seriesChoice) ?: tierAudio(globalChoice)
        ?: run {
            // FR-R235-2's same fix one tab over: the source's own "default" flag is a packaging habit,
            // not an instruction — a commentary/audio-description track marked default must still lose
            // to a plain track in the same language when one exists.
            val def = audioTracks.firstOrNull { it.isDefault } ?: return@run null
            val defKind = variantKind(def.label, forced = false)
            if (defKind == VariantKind.PLAIN) def.index
            else audioTracks.firstOrNull { it.language.equals(def.language, ignoreCase = true) && variantKind(it.label, forced = false) == VariantKind.PLAIN }?.index
                ?: def.index
        }
        ?: audioTracks.firstOrNull()?.index
        ?: 0

    fun tierSub(choice: RememberedChoice?): Int? = when {
        choice == null -> null
        choice.subtitlesOff -> -1
        else -> {
            val lang = choice.subtitleLanguage ?: return null
            val group = subGroups.firstOrNull { sameLanguage(it.language, lang) } ?: return null
            val native = group.versions.filter { it.flatIndex < subtitleTracks.size }
            val bySignature = choice.subtitleVariant?.let { sig -> native.firstOrNull { it.signature() == sig } }
            // Phase 210/R241 — same R235 invariant as tierAudio above, missing here until now: an
            // unmatched remembered variant must prefer a PLAIN version over SDH/forced in the same
            // language, not whichever sorts first in stream order (real library data shows some
            // seasons carry a duplicate plain+SDH English pair the other seasons don't).
            val nonForced = native.filter { !it.forced }
            (bySignature ?: nonForced.firstOrNull { it.kind == VariantKind.PLAIN } ?: nonForced.firstOrNull() ?: native.firstOrNull())?.flatIndex
        }
    }
    val subIdx = tierSub(seriesChoice) ?: tierSub(globalChoice)
        ?: run {
            // FR-R235-2 — the reported bug exactly: Kulsort S07E03's forced Danish track is ALSO
            // flagged the file's default, so `isDefault` alone picked signs-only over the full "Dansk
            // (CC)" track sitting right beside it. The forced flag is checked first because "default"
            // here is the misleading one — a non-forced track never needs this override.
            val def = subtitleTracks.firstOrNull { it.isDefault } ?: return@run null
            if (!def.forced) def.index
            else subtitleTracks.firstOrNull { !it.forced && it.language.equals(def.language, ignoreCase = true) }?.index
                ?: def.index
        }
        ?: subtitleTracks.firstOrNull { it.forced }?.index
        ?: -1

    return TrackSelectionResult(audioIdx, subIdx)
}

/**
 * R195 §3 — groups a flat per-track list into one row per language. [entries] is index-aligned with
 * the underlying audioTracks/subtitleTracks list; the returned [PickerVersion.flatIndex] values are
 * exactly the indices [choosePick] already knows how to select with — this function only regroups,
 * never renumbers the underlying tracks.
 */
internal fun buildLanguageGroups(entries: List<PickerEntryInput>): List<PickerLanguage> {
    // R291 (FR-R291-1) — the grouping, clustering, numbering and provenance collapse are :shared's
    // groupVersions(): the server resolves a remembered audio choice with the very same function, so
    // the signature a pick remembers here is the signature the next start asks for.
    val groups = dev.jellystructure.shared.tv.groupVersions(entries.map { dev.jellystructure.shared.tv.VersionInput(it.language, it.title, it.forced, it.isDefault) })
    return groups.map { g ->
        val versions = g.versions.map { v ->
            PickerVersion(
                flatIndex = v.flatIndex,
                kind = v.kind,
                region = regionInfoOf(v.region),
                badges = entries[v.flatIndex].badges,
                forced = v.forced,
                isDefault = v.isDefault,
                hadTitleText = v.hadTitleText,
                ordinal = v.ordinal,
                clusterSize = v.clusterSize,
            )
        }
        val sampleTitle = entries.filter { sameLanguage(it.language, g.language) }.firstNotNullOfOrNull { e ->
            e.title?.trim()?.takeIf { it.isNotEmpty() && !it.equals(e.language?.trim(), ignoreCase = true) }
        }
        val isUnnamed = versions.size > 1 && versions.all { it.kind == VariantKind.PLAIN && it.region == null && !it.hadTitleText }
        PickerLanguage(g.language, isOff = false, versions = versions, isUnnamed = isUnnamed, sampleTitle = sampleTitle)
    }
}

/** R195 §B — the one-sentence plain-language line under a level-2 version row. A specific kind always
 *  wins over region for the sentence (a track can be both, e.g. Brazilian+SDH — region still shows via
 *  its own flag+text on the row per §C, just not duplicated into the sentence too). */
private fun versionSentence(v: PickerVersion, isUnnamedGroup: Boolean, lang: String): String = when {
    isUnnamedGroup -> t("player.variant_no_distinguishing_data", lang)
    v.kind == VariantKind.SDH -> t("player.variant_sdh", lang)
    v.kind == VariantKind.FORCED -> t("player.variant_forced", lang)
    v.kind == VariantKind.DESCRIBE -> t("player.variant_describe", lang)
    v.kind == VariantKind.COMMENTARY -> t("player.variant_commentary", lang)
    v.region != null -> t("player.variant_region", lang, mapOf("region" to v.region.name))
    else -> t("player.variant_plain", lang)
}

/**
 * R237 (FR-R237-2/3) — the player's failed-start card. Split out of [PlayerScreen] deliberately; see
 * the call site's comment for the release-build `VerifyError` that inlining it caused.
 *
 * Replaces the old `error.generic` heading over `sessionError.message` (the raw exception text,
 * `"HTTP 409: {json body}"`) — the first line said nothing and the second was a status code and a JSON
 * blob, so on 2026-09-06 a precise server-side diagnosis was rendered to nobody.
 */
@Composable
private fun PlayerSessionErrorOverlay(
    error: PlayerSessionState.Error,
    colors: RaviloColors,
    // R244 (FR-R244-12) — on a handset the same sentence and the same two actions sit in a bottom sheet.
    handset: Boolean = false,
    onReauthRequired: (() -> Unit)?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val errTitle = when (error.kind) {
        LoadErrorKind.REAUTH -> "error.play.reauth.title"
        LoadErrorKind.FORBIDDEN -> "error.play.forbidden.title"
        LoadErrorKind.GONE -> "error.play.gone.title"
        LoadErrorKind.UNREACHABLE -> "error.play.unreachable.title"
        LoadErrorKind.GENERIC -> "error.generic"
        LoadErrorKind.PLAYBACK_UNAVAILABLE -> "player.unavailable"   // R335 (FR-R335-7)
        // R385 — a start that got no answer at all reaches here too, and on a Mac its cause can be named. Not one of
        // FR-R385-5's three surfaces, but the same two keys: saying "check the connection" here and the truth two
        // screens away would be the drift this phase is about.
        LoadErrorKind.MAC_INSTALL_PLACE, LoadErrorKind.MAC_LOCAL_NETWORK -> loadErrorTitleKey(error.kind)
    }
    val errBody = when (error.kind) {
        LoadErrorKind.REAUTH -> "error.play.reauth.body"
        LoadErrorKind.FORBIDDEN -> "error.play.forbidden.body"
        LoadErrorKind.UNREACHABLE -> "error.play.unreachable.body"
        LoadErrorKind.MAC_INSTALL_PLACE, LoadErrorKind.MAC_LOCAL_NETWORK -> loadErrorBodyKey(error.kind)
        // GONE has no next step, and GENERIC has no honest sentence to offer beyond its heading.
        LoadErrorKind.GONE, LoadErrorKind.GENERIC, LoadErrorKind.PLAYBACK_UNAVAILABLE -> null
    }
    // FR-R237-3 — Retry stays only where trying again can plausibly change the answer. Offering it for
    // a verdict that is deterministic for ten minutes is the same mistake as the retry loop, moved into
    // the viewer's hands. REAUTH gets the action that actually resolves it; 403/404 get Back alone.
    // R385 — the one rule, shared with R280's surface: identical for every kind that was here before.
    val showRetry = loadErrorOffersRetry(error.kind)
    val showSignIn = error.kind == LoadErrorKind.REAUTH && onReauthRequired != null
    val retryFR = remember { FocusRequester() }
    val backFR = remember { FocusRequester() }
    LaunchedEffect(error) {
        runCatching { if (showRetry || showSignIn) retryFR.requestFocus() else backFR.requestFocus() }
    }
    var retryFocused by rememberFocusVisual()
    var backFocused by rememberFocusVisual()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (handset) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xFF0E1119), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 22.dp)
                else Modifier,
        ) {
            Text(str(errTitle), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (errBody != null) {
                Spacer(Modifier.height(10.dp))
                Text(str(errBody), color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showRetry || showSignIn) Box(
                    modifier = Modifier
                        .focusRing(retryFocused, RoundedCornerShape(8.dp), colors.focusRing, 2.dp, fillFocused = Color.White, fillResting = Color.White.copy(alpha = 0.15f))
                        .dpadFocusable(
                            focusRequester = retryFR,
                            onFocused = { retryFocused = true },
                            onBlurred = { retryFocused = false },
                            onSelect = { if (showSignIn) onReauthRequired?.invoke() else onRetry() },
                        )
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(
                        str(if (showSignIn) "action.sign_in" else "action.retry"),
                        color = if (retryFocused) Color.Black else Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(
                    modifier = Modifier
                        .focusRing(backFocused, RoundedCornerShape(8.dp), colors.focusRing, 2.dp, fillFocused = Color.White.copy(alpha = 0.18f), fillResting = Color.White.copy(alpha = 0.08f))
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
