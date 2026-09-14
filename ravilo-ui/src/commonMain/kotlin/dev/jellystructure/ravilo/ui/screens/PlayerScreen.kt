package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.LocalPlaystateCommands
import dev.jellystructure.ravilo.ui.LocalReauthRequired
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.components.EpisodeTriptych
import dev.jellystructure.ravilo.ui.components.LANG_CC
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
private const val EPRAIL_HIDE_MS = 30_000L // R208: auto-close the episode rail after inactivity
private const val NEXTUP_AT_MS   = 20_000L    // R111: show next-up card when this many ms remain (was 34s — too early)
// R182 — replaces the old hardcoded COUNTDOWN_SECS = 8: the "Skip button countdown" viewer setting
// (RaviloConfig.skipSecs, one of 4/6/8) now governs both the Skip Intro pill and this next-up/credits
// countdown. This is only the pre-fetch seed (RaviloConfig's own default) until getConfig() resolves.
private const val DEFAULT_SKIP_SECS = 6
private const val SKIP_BACK_MS   = 10_000L
private const val SKIP_FWD_MS    = 30_000L
private const val POLL_MS        = 500L
// Bug fix (auto-advance retry loop): how long a requested advance gets to actually take effect before
// we treat it as failed. Navigating to the next episode is a synchronous state swap in practice, so
// this sits generously above any real transition.
private const val ADVANCE_TIMEOUT_MS = 5_000L
// Bug fix (auto-advance retry loop): a trustworthy creditsStartMs can never sit in the first half of a
// title. A bogus marker (0, or one that belongs to a different episode inside a multi-episode file)
// used to satisfy the credits check on the very first poll tick, so the next-episode countdown fired
// at the START of every episode and the player hopped through a whole series in skipSecs-long steps —
// reported as "it just gets stuck trying to play the next episode over and over". Below this fraction
// of the duration the marker is ignored and the NEXTUP_AT_MS end-of-file heuristic is used instead.
private const val CREDITS_MARKER_MIN_FRACTION = 0.5

// R218 (FR-R218-2) — "no buffering presentation may appear before ~400ms of continuous waiting, for B,
// C and D alike... a spinner that flashes for 200ms is itself a defect." Untested on a real TV per the
// phase's own Open question #1 — tune against R216's rebuffer telemetry once data accumulates, not
// guessed at again.
private const val BUFFER_MOMENT_DEBOUNCE_MS = 400L
// R218 (FR-R218-3) — "a wait that passes 60 seconds is pathological... at that point Moment C dims
// further and raises the centre spinner." Stall only — cold start and seek don't deepen.
private const val BUFFER_MOMENT_DEEPEN_MS = 60_000L

// ─── Focus model ──────────────────────────────────────────────────────────────

private enum class PlFocus { SKIP_INTRO, SEEK_BAR, SKIP_BACK, PLAY, SKIP_FWD, TRACKS, NEXT_EP, BACK }
private enum class NuFocus { PLAY, STAY }

// R218 (FR-R218-1) — "PlayerStore exposes a single derived buffering state, not three booleans." This
// enum IS that single state; it lives here (Compose-side, in PlayerScreen) rather than inside
// PlayerStore itself — PlayerStore is deliberately decoupled from any concrete RaviloPlayer instance
// (see R216's own comment on qoeSnapshotProvider, injected rather than owned, for the same reason), and
// deriving this from the player's own polled signals would break that. The requirement's real substance
// — one state, not three independently-racing overlays — is what this enum (and the single
// LaunchedEffect debouncing it below) delivers.
private enum class PlBufferMoment { NONE, COLD, STALL, SEEK }

// R182 (FR-RV-SKIP1-2) — the credits card's ONE primary action, chosen by priority: a stinger (from
// Phase 150 §C) always wins (never auto-skip past it); else a real next episode; else a plain
// skip-credits/exit. "Watch credits" (NuFocus.STAY) is offered in every mode alongside this one action.
private enum class CreditsCardMode { STINGER, NEXT_EPISODE, SKIP_CREDITS }

// R182 — SKIP_INTRO only enters the order while the pill is actually visible (mirrors NEXT_EP's own
// hasNextEp gating), first in the order per the design prototype's own focus-order function.
//
// Bug fix: BACK used to be appended last here, but BackButton lives in the top bar, spatially
// unrelated to this bottom transport row (SeekRow/SkipButton/PlayPauseButton/TrackButton) — pressing
// Right past the last real control (NEXT_EP, or TRACKS with no next episode) teleported focus up to
// the top-left Back button, which read as "Right does Up" instead of a no-op at the row's end. BACK
// stays reachable via mouse/touch hover (onControlHover sets `focus` directly, independent of this
// list) and via the hardware Back key (root onBack), which is the primary D-pad way to leave anyway.
private fun transportOrder(hasNextEp: Boolean, hasSkipIntro: Boolean): List<PlFocus> =
    buildList {
        if (hasSkipIntro) add(PlFocus.SKIP_INTRO)
        add(PlFocus.SEEK_BAR); add(PlFocus.SKIP_BACK); add(PlFocus.PLAY)
        add(PlFocus.SKIP_FWD); add(PlFocus.TRACKS)
        if (hasNextEp) add(PlFocus.NEXT_EP)
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
    // R192 — poster fallback for the OS media session's artwork when there's no `episodes` list to
    // pull a still from (movies). Ignored when `episodes`/`currentEpIndex` resolves a still.
    posterUrl: String? = null,
    // Phase 150 — this title's own intro/credits segments (R182 Skip Intro / Skip Credits consumes
    // this; this stage only threads it in).
    segments: dev.jellystructure.shared.tv.TvSegmentMarkers = dev.jellystructure.shared.tv.TvSegmentMarkers(),
    store: PlayerStore,
    onBack: () -> Unit,
    onNavigateToEpisode: ((String) -> Unit)? = null,
) {
    // R237 (FR-R237-3) — the action that can actually resolve a re-auth failure, in place of a Retry
    // that is guaranteed to fail again: the same exit R234's forced sign-out already uses (sign the
    // session out, land on the profile picker or the login gate) rather than a login takeover layered
    // over a dead player — see phase-R237's open question 2. Null ⇒ Back alone.
    //
    // Read from a CompositionLocal, NOT taken as a parameter. As a 16th parameter it produced a
    // release-build-only `VerifyError` that killed the player on open — see [LocalReauthRequired]'s
    // doc for the full account. Do not turn this back into a parameter.
    val onReauthRequired = LocalReauthRequired.current
    val colors = RaviloTheme.colors
    val sessionState by store.state.collectAsState()
    // R194 — resolves a relative /api/tv/image/... path (season/series poster) against the server base
    // URL, same rule RemoteImage applies for on-screen art. Needed here separately because the OS media
    // session's artwork URI is handed straight to the platform player, bypassing Coil entirely.
    val serverBaseUrl = LocalServerBaseUrl.current
    fun resolveImageUrl(url: String?): String? =
        url?.let { if (it.startsWith("/") && serverBaseUrl.isNotBlank()) "$serverBaseUrl$it" else it }

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

    // Bug fix (auto-advance retry loop): a "next episode" id that IS the episode already playing is not
    // a next episode — navigating to it can only be a no-op, while the credits card kept offering it and
    // the countdown kept re-firing it forever. Resolved once here so every consumer (credits-card mode,
    // transport order, the Next Episode control, MediaKey.NEXT) agrees on whether a next episode exists.
    val resolvedNextEpisodeId = nextEpisodeId?.takeIf { it != itemId }

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

    // R218 — the same poll loop's signal for the four buffering moments (see PlayerBufferMoment below).
    var hasRenderedFirstFrame by remember { mutableStateOf(false) }
    var isBuffering    by remember { mutableStateOf(false) }
    var isSeeking      by remember { mutableStateOf(false) }
    // Phase R220 (FR-R220-5) — true for the whole time the Android actual's video-output recovery ladder
    // is running; folded into rawBufferMoment below so a rung past R218's own debounce shows the existing
    // STALL presentation instead of a silent frozen frame (isPlaying/isBuffering/isSeeking all read
    // healthy throughout a video-output stall — see PlayerVideoSurface's own doc for why).
    var videoOutputRecovering by remember { mutableStateOf(false) }

    // Phase 185/R222 (FR-185-4 client half) — negotiation-to-first-frame timing. negotiationStartMs is
    // stamped in armSession() itself (the single chokepoint for both the initial start and the
    // background/foreground re-arm — see that function's own doc), consumed by the poll loop below the
    // instant hasRenderedFirstFrame's false→true transition is observed. measuredStartupMs is what
    // armSession hands PlayerStore as this session's startupMsProvider, read once at stop time; null is
    // an honest "never measured" (still buffering, or the session ended some other way), not an error.
    var negotiationStartMs by remember { mutableStateOf<Long?>(null) }
    var measuredStartupMs  by remember { mutableStateOf<Long?>(null) }

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
    // R195 — two levels: pickerIdx indexes PickerLanguage groups (level 1); pickerVersionIdx indexes
    // groups[pickerIdx].versions (level 2, only meaningful while pickerLevel == 1).
    var pickerIdx  by remember { mutableIntStateOf(0) }
    var pickerLevel by remember { mutableIntStateOf(0) }        // 0 = language list, 1 = version list
    var pickerVersionIdx by remember { mutableIntStateOf(0) }
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
    // R184 (FR-RV-POS1-2) — which itemId positionMs/durationMs actually reflect right now. Bug fix: a
    // lifecycle stop (PlayerLifecycleEffect's onBackground below) landing in the gap between advanceNext()
    // swapping to the next episode's PlayerStore and that episode's stream actually loading could read
    // the OUTGOING episode's still-playing near-the-end positionMs and report it as the NEW episode's own
    // stop position — Jellyfin then resumed the new episode minutes in on the very next play. Mirrors
    // loadedForItemId above but tracked separately since it must stay valid even when this screen never
    // reaches a poll tick before onBackground fires.
    var positionKnownForItemId by remember { mutableStateOf<String?>(null) }
    // Bug fix (auto-advance retry loop): which itemId we have already asked the host to advance away
    // from. advanceNext() only *requests* a navigation — if the host can't act on it (no episode list,
    // an id that isn't in it, or a target equal to the current item) nothing changes, and the poll loop
    // below happily re-armed the credits card on the very next tick, restarting the countdown and
    // re-requesting the same advance forever ("auto play next doesn't work and ends in a forever loop").
    // One attempt per episode: the re-arm checks are gated on this, and a request that never takes
    // effect leaves the player instead (see the ADVANCE_TIMEOUT_MS effect below).
    var advanceRequestedForItemId by remember { mutableStateOf<String?>(null) }

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

    // R195 §3 — language groups for the two-level picker. `flatIndex` on each PickerVersion is an
    // index into `audioTracks` (audio) or `subtitleTracks + encodeSubTracks` starting at 0 (subtitles —
    // Off is its own pseudo-language, never grouped, matching subOptions' existing "index 0 = Off, then
    // native+encode" scheme so choosePick/resolveTrackSelection keep working off the same indices).
    val pickerLang = LocalLang.current
    val audioGroups: List<PickerLanguage> = remember(audioTracks, originalLanguage, pickerLang) {
        val effectiveAudio = audioTracks.ifEmpty { listOf(PlayerAudioTrack(0, "Default", null)) }
        buildLanguageGroups(effectiveAudio.map {
            PickerEntryInput(it.language, it.label, forced = false, isDefault = it.isDefault, badges = audioBadges(it, originalLanguage, pickerLang))
        })
    }
    val subVersionOptions: List<PlayerSubtitleTrack> = remember(subtitleTracks, encodeSubTracks) { subtitleTracks + encodeSubTracks }
    val subGroups: List<PickerLanguage> = remember(subVersionOptions, pickerLang) {
        buildLanguageGroups(subVersionOptions.map {
            PickerEntryInput(it.language, it.label, forced = it.forced, isDefault = it.isDefault, badges = subtitleBadges(it, pickerLang))
        })
    }
    // Off is a permanent, always-single-version pseudo-language ahead of every real group (§A: "Off is
    // the first row, as today"). flatIndex -1 is a sentinel `group.isOff`/choosePick() checks for.
    val offVersion = PickerVersion(flatIndex = -1, kind = VariantKind.PLAIN, region = null, badges = emptyList(), forced = false, isDefault = false, hadTitleText = false, ordinal = 0, clusterSize = 1)
    val subGroupsWithOff: List<PickerLanguage> = listOf(PickerLanguage(language = null, isOff = true, versions = listOf(offVersion), isUnnamed = false)) + subGroups
    val pickerGroups: List<PickerLanguage> = if (pickerTab == 0) audioGroups else subGroupsWithOff

    // R196 (FR-RV-TRK2-1) — same staleness risk as itemId/seriesId/segments above (see that comment):
    // resolveTrackSelection() runs from the LaunchedEffect(Unit) poll loop's closure, captured once at
    // first composition and never restarted. audioGroups/subGroups are plain remember(...) vals, not
    // snapshot state, so that closure permanently saw the FIRST composition's values — an empty
    // subGroups and a single null-language placeholder audioGroups (both tracks lists start empty) —
    // and the remembered-choice tiers always returned null and fell through to the source default,
    // even once real tracks (and a real stored choice) existed. Bug: this made "remember my subtitle
    // language" silently stop working entirely (not just for auto-advance) the moment R195 rerouted
    // the tiers through these groups instead of the raw (snapshot-state) track lists. rememberUpdatedState
    // gives the poll loop's closure a live reference, matching the established fix for itemId etc.
    val currentAudioGroups by rememberUpdatedState(audioGroups)
    val currentSubGroups by rememberUpdatedState(subGroups)

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
        val nextId = resolvedNextEpisodeId
        if (nextId == null || onNavigateToEpisode == null) {
            // Nothing to advance to. Latch the card dismissed so the poll loop can't re-show it (and,
            // with it, re-run this countdown) every tick for the rest of the file.
            nextUpDismissed = true
            return
        }
        // R142: a genuinely finished episode (≥90%) is marked played as we advance, so up-next stays
        // correct; a manual skip mid-episode is not (it stays in-progress with its resume sliver).
        if (durationMs > 0 && positionMs >= durationMs * 90 / 100) store.markWatched(itemId)
        // Latched BEFORE the call: see advanceRequestedForItemId — exactly one advance attempt per
        // episode, never a retry loop.
        advanceRequestedForItemId = itemId
        onNavigateToEpisode.invoke(nextId)
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
    fun persistChoice(
        newAudioLanguage: String? = null, newSubtitleLanguage: String? = null, newSubtitlesOff: Boolean? = null,
        // R195 (FR-RV §5.4) — which same-language VERSION, not just the language. Cleared/carried
        // forward alongside its own axis's language (a stale signature must never survive onto a
        // DIFFERENT language than the one it was computed for).
        newAudioVariant: String? = null, newSubtitleVariant: String? = null,
    ) {
        val profileId = MultiTokenStore.getActive()?.userId ?: return
        val seriesKey = currentSeriesId ?: currentItemId
        val existing = PlaybackPrefsStore.getSeriesChoice(profileId, seriesKey) ?: PlaybackPrefsStore.getGlobalChoice(profileId)
        val choice = RememberedChoice(
            audioLanguage = newAudioLanguage ?: existing?.audioLanguage,
            subtitleLanguage = if (newSubtitlesOff == true) null else (newSubtitleLanguage ?: existing?.subtitleLanguage),
            subtitlesOff = newSubtitlesOff ?: existing?.subtitlesOff ?: false,
            audioVariant = if (newAudioLanguage != null) newAudioVariant else existing?.audioVariant,
            subtitleVariant = if (newSubtitlesOff == true) null else if (newSubtitleLanguage != null) newSubtitleVariant else existing?.subtitleVariant,
        )
        PlaybackPrefsStore.setSeriesChoice(profileId, seriesKey, choice)
        PlaybackPrefsStore.setGlobalChoice(profileId, choice)
    }

    // R181 (FR-RV-TRK1) — layered resolution, run once per item the first tick after track discovery
    // completes (see the call site in the poll loop below): per-series remembered choice → learned
    // global-language preference → source default → first-track/Off. Matches by language (and, since
    // R195, by exact remembered variant signature first — see resolveTrackChoice's doc), never by
    // ExoPlayer track index (indices differ across episodes/files). Scope note: only matches against
    // native/external subtitleTracks, never encodeSubTracks (PGS burn-in) — a remembered language that
    // exists only as a PGS track in this file falls through instead of silently triggering an autoplay
    // transcode; PGS stays a manual pick, same as today.
    //
    // R196 (FR-RV-TRK2-1/2/4) — the actual tier logic now lives in the pure, unit-tested top-level
    // resolveTrackChoice() (near buildLanguageGroups below), and reads currentAudioGroups/
    // currentSubGroups (rememberUpdatedState) rather than the plain audioGroups/subGroups vals — this
    // function is called from the poll loop's LaunchedEffect(Unit) closure, which is captured once and
    // never restarted (see that comment on currentItemId etc.), and a plain remember(...) val read from
    // inside it stays frozen at whatever it was on the FIRST composition. That silently disabled every
    // remembered-choice tier (audio and subtitles, first episode and every later one) from the moment
    // R195 rerouted them through audioGroups/subGroups instead of the raw (snapshot-state) track lists.
    fun resolveTrackSelection() {
        val profileId = MultiTokenStore.getActive()?.userId
        val seriesKey = currentSeriesId ?: currentItemId
        val seriesChoice = profileId?.let { PlaybackPrefsStore.getSeriesChoice(it, seriesKey) }
        val globalChoice = profileId?.let { PlaybackPrefsStore.getGlobalChoice(it) }

        val result = resolveTrackChoice(seriesChoice, globalChoice, currentAudioGroups, currentSubGroups, audioTracks, subtitleTracks)
        player.selectAudioTrack(result.audioIndex)
        selectedAudio = result.audioIndex
        player.selectSubtitleTrack(result.subIndex)
        selectedSub = result.subIndex
    }

    // R195 §3 — applies whichever version is currently targeted (level-1's implicit single version,
    // or level-2's focused pickerVersionIdx). Never touches pickerOpen/pickerLevel itself — callers
    // (pickerSelect(), touch taps) decide whether picking should close the picker or leave it open.
    fun choosePick() {
        val group = pickerGroups.getOrNull(pickerIdx) ?: return
        val version = group.versions.getOrNull(if (pickerLevel == 1) pickerVersionIdx else 0) ?: return
        if (pickerTab == 0) {
            selectedAudio = version.flatIndex
            player.selectAudioTrack(version.flatIndex)
            persistChoice(newAudioLanguage = group.language, newAudioVariant = version.signature())
        } else if (group.isOff) {
            selectedSub = -1
            player.selectSubtitleTrack(-1)
            persistChoice(newSubtitlesOff = true)
        } else {
            val sub = subVersionOptions.getOrNull(version.flatIndex)
            if (sub != null && sub.deliveryMethod == "encode") {
                // R56: PGS burn-in — restream with subtitle index baked into the Jellyfin transcode.
                store.restreamWithSub(itemId, sub.jellyfinStreamIndex, player.positionMs)
            } else {
                selectedSub = version.flatIndex
                player.selectSubtitleTrack(version.flatIndex)
            }
            // R181 — still worth remembering even for the PGS/encode branch (helps other titles'
            // global tier and a rewatch where the language exists as a native track), even though the
            // resolver above never auto-selects a PGS track back in.
            persistChoice(newSubtitleLanguage = group.language, newSubtitlesOff = false, newSubtitleVariant = version.signature())
        }
    }

    // R195 §A / R197 (FR-RV-PICK1-1) — OK on a level-1 row: a single-version language selects
    // immediately and closes; a multi-version language instead ENTERS level 2, focused on whichever
    // version is already playing. OK on a level-2 row: select AND CLOSE, same as level 1. R195
    // originally shipped level 2 as "select, stay open" so a viewer could audition versions without
    // reopening the picker — reversed by R197 (live report: with the video still covered and nothing
    // on screen distinguishing "applied, still open" from "didn't take", a many-version language felt
    // broken next to a one-version language, which dismisses on the same press). See R195 §D's amended
    // table for the superseded decision.
    fun pickerSelect() {
        val group = pickerGroups.getOrNull(pickerIdx) ?: return
        if (pickerLevel == 0 && group.versions.size > 1) {
            pickerLevel = 1
            val currentFlat = if (pickerTab == 0) selectedAudio else selectedSub
            pickerVersionIdx = group.versions.indexOfFirst { it.flatIndex == currentFlat }.coerceAtLeast(0)
            wake()
            return
        }
        if (pickerLevel == 0) pickerVersionIdx = 0
        choosePick()
        pickerOpen = false
        pickerLevel = 0
        wake()
    }

    // R195 §D — Back in level 2 returns to level 1 (must NOT close the picker); level 1 closes it,
    // unchanged from pre-R195.
    fun pickerBack() {
        if (pickerLevel == 1) pickerLevel = 0 else pickerOpen = false
        wake()
    }

    // R195 bug fix — the exact same tab-switch logic the D-pad's onLeft/onRight already run,
    // extracted so a touch tap on a PickerTab pill (which has no D-pad to trigger onLeft/onRight)
    // can reach it too. Always resets to level 1 (§D) and re-targets the new tab's group containing
    // the live selection for that axis.
    fun pickerTapTab(tab: Int) {
        pickerTab = tab
        pickerLevel = 0
        pickerIdx = if (tab == 0) {
            audioGroups.indexOfFirst { g -> g.versions.any { it.flatIndex == selectedAudio } }.coerceAtLeast(0)
        } else {
            subGroupsWithOff.indexOfFirst { g -> g.versions.any { it.flatIndex == selectedSub } }.coerceAtLeast(0)
        }
        wake()
    }

    fun scrubStep() = SKIP_BACK_MS.coerceAtMost(maxOf(5_000L, (durationMs * 0.012).toLong()))

    // Single place that arms a playback session, shared by the initial start and by the return-from-
    // background re-arm (PlayerLifecycleEffect's onForeground) so both hand the store the same providers.
    // durationProvider lets the store take the ≥90% mark-played decision itself if it is closed without
    // an explicit stopSession — see PlayerStore.close().
    fun armSession(id: String) {
        // Phase 185/R222 (FR-185-4 client half) — a real StreamTicket negotiation starts the instant
        // startSession below is called, whether this is the episode's initial start or a background/
        // foreground re-arm (both reload the player — see RaviloPlayerAndroid.load()'s own
        // _hasRenderedFirstFrame reset), so both get timed the same way from this one chokepoint.
        negotiationStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        store.startSession(
            id,
            positionProvider = { positionMs },
            isPausedProvider = { !isPlaying },
            durationProvider = { durationMs },
            qoeSnapshotProvider = { player.qoeSnapshot() },  // R216 (FR-R216-4)
            startupMsProvider = { measuredStartupMs },
        )
    }

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
        // R184 (FR-RV-POS1-1): positionMs/durationMs are screen-scoped, not per-episode (this composable
        // is reused across the whole binge — see loadedForItemId's comment above) — reset them the instant
        // a new episode starts loading so nothing can read the outgoing episode's near-the-end values
        // during the round-trip before its own stream is loaded.
        positionMs = 0L
        durationMs = 0L
        positionKnownForItemId = null
        hasRenderedFirstFrame = false  // R218 — the new episode's own cold start, not the outgoing one's
        isBuffering = false
        isSeeking = false
        measuredStartupMs = null  // R185/R222 — a stale prior episode's number must never carry over
        armSession(itemId)
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
        // R192/R194 — feed title/episode-kicker/artwork into the OS media session (TV-only; see
        // RaviloPlayer.load doc). Artwork prefers the current episode's SEASON poster over an episode
        // still (season art reads better at media-session size); falls back to the series' own poster
        // when this season has none, then the movie-path `posterUrl` (no `episodes` list at all).
        // `posterUrl` already carries the series' own poster for episodes (threaded from
        // EpisodePlayContext.seriesPosterUrl) or the movie's poster for movies — a single fallback.
        val artworkUrl = resolveImageUrl(episodes?.getOrNull(currentEpIndex)?.seasonPosterUrl ?: posterUrl)
        player.load(streamUrl, s.ticket.startPositionMs, s.ticket.subtitles, s.ticket.audio, title = itemTitle, subtitle = itemKicker, artworkUrl = artworkUrl)
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
                // Bug fix: gate every next-up/end-of-stream check on the player actually being loaded
                // for the CURRENT itemId — otherwise, right after a manual (or auto) advance, these
                // checks kept reading the OUTGOING episode's near-the-end position/duration during the
                // brief gap before the new episode's stream ticket loads, re-arming next-up and
                // auto-advancing a second time into the episode that had just started. See
                // loadedForItemId's declaration comment above. Uses currentItemId (rememberUpdatedState),
                // not the raw itemId parameter — see that declaration's comment for why this loop
                // specifically needs the live reference.
                val playerLoadedForCurrentItem = loadedForItemId == currentItemId

                // R184 (FR-RV-POS1-1/2): same staleness this loop already guards next-up/end-of-stream
                // checks against also applied to positionMs/durationMs themselves — they were assigned
                // unconditionally every tick, so during this same gap they held the OUTGOING episode's
                // near-the-end values, observable by anything reading them (e.g. a lifecycle stop, see
                // positionKnownForItemId's declaration comment). Only update — and only mark them
                // known-fresh for currentItemId — once the player is actually loaded for it.
                if (playerLoadedForCurrentItem) {
                    positionMs = player.positionMs
                    durationMs = player.durationMs
                    positionKnownForItemId = currentItemId
                    // R218 — same staleness guard as positionMs/durationMs above: only read the live
                    // player's signal once it is actually loaded for THIS item, or a stale
                    // hasRenderedFirstFrame=true from the outgoing episode could suppress moment B here.
                    val renderedNow = player.hasRenderedFirstFrame
                    // Phase 185/R222 (FR-185-4 client half) — the false→true transition this negotiation
                    // was timing. Consumed (negotiationStartMs cleared) so a later re-arm's own transition
                    // is never mistaken for this one, and a session that never renders never reports.
                    if (renderedNow && !hasRenderedFirstFrame) {
                        negotiationStartMs?.let { start ->
                            measuredStartupMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - start
                        }
                        negotiationStartMs = null
                    }
                    hasRenderedFirstFrame = renderedNow
                    isBuffering = player.isBuffering
                    isSeeking = player.isSeeking
                }
                bufferedMs  = player.bufferedMs
                isPlaying   = player.isPlaying
                audioTracks = player.audioTracks
                subtitleTracks = player.subtitleTracks

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
                // Bug fix (auto-advance retry loop): only a marker that plausibly belongs to THIS stream is
                // trusted — positive, inside the known duration, and in its back half (see
                // CREDITS_MARKER_MIN_FRACTION). Anything else falls back to the end-of-file heuristic
                // rather than declaring the episode finished seconds after it started.
                val creditsStart = currentSegments.creditsStartMs?.takeIf {
                    durationMs > 0 && it > 0 && it < durationMs && it >= (durationMs * CREDITS_MARKER_MIN_FRACTION).toLong()
                }
                val creditsReached = if (creditsStart != null) positionMs >= creditsStart
                    else durationMs > 0 && (durationMs - positionMs) in 1..NEXTUP_AT_MS
                val advanceAlreadyRequested = advanceRequestedForItemId == currentItemId
                // R230 (FR-R230-1) — Skip Credits: Off means no mid-playback interruption at all, so this
                // early trigger (segment creditsStartMs, or the NEXTUP_AT_MS heuristic for an unscanned
                // title) is suppressed entirely while Off. The real end of file (below) becomes the only
                // trigger point in that mode.
                if (playerLoadedForCurrentItem && creditsReached && !advanceAlreadyRequested &&
                    !nextUpVisible && !nextUpDismissed && !player.isEnded &&
                    currentSkipCreditsMode != dev.jellystructure.shared.tv.SkipMode.OFF) {
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
                // R230 (FR-R230-2) — while Off, this is the ONLY trigger left, and it no longer just shows
                // the card: a next episode with Autoplay next episode on still gets the ordinary
                // Next-Episode countdown here (re-anchored to the real end instead of the early one, per
                // the owner's decision that Off must not disable Autoplay); anything else — no next
                // episode, or Autoplay off — exits silently via the same fallback skipCredits() already
                // uses, with no card ever shown.
                if (playerLoadedForCurrentItem && player.isEnded && !advanceAlreadyRequested &&
                    !nextUpVisible && !nextUpDismissed) {
                    if (currentSkipCreditsMode == dev.jellystructure.shared.tv.SkipMode.OFF) {
                        if (resolvedNextEpisodeId != null && currentAutoplayNext) {
                            nextUpVisible = true; nuFocus = NuFocus.PLAY
                        } else {
                            skipCredits()
                        }
                    } else {
                        nextUpVisible = true; nuFocus = NuFocus.PLAY
                    }
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

    // R208 — auto-close the episode rail after inactivity. chromeRevision bumps on every D-pad input
    // via wake() (onLeft/onRight/onUp/onDown/onSelect all call it first, including rail nav), so this
    // naturally restarts while the viewer is actively browsing episodes and only fires after a real
    // idle stretch. Mirrors onUp's own manual-close behavior (epRailOpen = false; scheduleHide()) so
    // the ambient chrome-hide timer resumes normally once the rail's own guard is gone.
    LaunchedEffect(epRailOpen, chromeRevision) {
        if (!epRailOpen) return@LaunchedEffect
        delay(EPRAIL_HIDE_MS)
        epRailOpen = false
        scheduleHide()
    }

    // R218 (FR-R218-1) — the one derived buffering moment; see PlBufferMoment's own doc for why this
    // lives here rather than inside PlayerStore. Priority order matters: a wait BEFORE any first frame
    // is always COLD even if isSeeking/isBuffering also happen to be true (there is no "last frame" yet
    // for a seek's lighter treatment to make sense against), then SEEK (a seek's own buffering must
    // never read as an organic STALL), then plain STALL.
    val rawBufferMoment = when {
        sessionState !is PlayerSessionState.Ready -> PlBufferMoment.NONE  // moment A owns this wait
        !hasRenderedFirstFrame -> PlBufferMoment.COLD
        // R220 (FR-R220-5) — ahead of SEEK/isBuffering: a video-output recovery in progress must always
        // read as STALL, even if rung 2's own no-op seek happens to flip isSeeking true mid-ladder.
        videoOutputRecovering -> PlBufferMoment.STALL
        isSeeking -> PlBufferMoment.SEEK
        isBuffering -> PlBufferMoment.STALL
        else -> PlBufferMoment.NONE
    }
    var displayedBufferMoment by remember { mutableStateOf(PlBufferMoment.NONE) }
    // R218 (FR-R218-2) — ~400ms debounce before ANY presentation appears (a direct play that starts
    // immediately must show nothing at all); clearing is immediate — a wait that just ended should stop
    // being shown right away, not linger for its own debounce. Keyed on the raw (undebounced) moment so
    // a rapid COLD→SEEK→COLD flicker restarts the delay each time rather than showing a stale one.
    LaunchedEffect(rawBufferMoment) {
        if (rawBufferMoment == PlBufferMoment.NONE) {
            displayedBufferMoment = PlBufferMoment.NONE
        } else {
            delay(BUFFER_MOMENT_DEBOUNCE_MS)
            displayedBufferMoment = rawBufferMoment
        }
    }
    // R218 (FR-R218-3) — "a wait that passes 60 seconds is pathological... at that point Moment C dims
    // further and raises the centre spinner. The wording does NOT change." Stall only.
    var stallDeepened by remember { mutableStateOf(false) }
    LaunchedEffect(displayedBufferMoment) {
        stallDeepened = false
        if (displayedBufferMoment == PlBufferMoment.STALL) {
            delay(BUFFER_MOMENT_DEEPEN_MS)
            stallDeepened = true
        }
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
    // R230 (FR-R230-3) — Skip Credits: Off only ever reaches this card via the isEnded-trigger's
    // Autoplay branch above, which guarantees resolvedNextEpisodeId != null — Stinger's "skip to scene"
    // and the plain Skip-Credits exit are both meaningless once already at the real end of the file, so
    // neither variant is reachable while Off regardless of what segments/next-episode data says.
    val creditsCardMode = when {
        currentSkipCreditsMode == dev.jellystructure.shared.tv.SkipMode.OFF -> CreditsCardMode.NEXT_EPISODE
        segments.stinger != null -> CreditsCardMode.STINGER
        resolvedNextEpisodeId != null -> CreditsCardMode.NEXT_EPISODE
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

    // Bug fix (auto-advance retry loop): an advance we requested but that never took effect used to be
    // invisible — the credits card simply came back and tried again, forever, on an episode that had
    // already finished playing. A successful advance swaps itemId within a frame, so if we are still on
    // the same item after this grace period the navigation genuinely failed: leave the player (Back is
    // always meaningful — see the Ravilo constitution) instead of retrying or freezing on the last frame.
    LaunchedEffect(advanceRequestedForItemId) {
        val requested = advanceRequestedForItemId ?: return@LaunchedEffect
        delay(ADVANCE_TIMEOUT_MS)
        if (currentItemId == requested) onBack()
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

    // Pause/resume when activity goes to background (Home button) and returns. Bug fix: also END the
    // playback session while we are away instead of leaving it open with a paused heartbeat (phantom
    // "streaming" in the Jellyfin dashboard, and no final resume position if the app is then killed),
    // and re-arm it for the SAME item if the viewer comes back. Uses currentItemId (rememberUpdatedState),
    // not the raw itemId parameter, so a re-arm after a binge starts the episode we are actually on.
    PlayerLifecycleEffect(
        player,
        wasPlaying = { isPlaying },
        // R184 (FR-RV-POS1-2): belt-and-suspenders alongside the itemId-reset above — even if this fires
        // before positionKnownForItemId has ever been set fresh for currentItemId (e.g. ON_STOP landing
        // before the poll loop's first tick for the new episode), never report a position that isn't
        // known to belong to the item store.stopSession is about to close out.
        onBackground = {
            val positionIsFresh = positionKnownForItemId == currentItemId
            store.stopSession(if (positionIsFresh) positionMs else 0L, if (positionIsFresh) durationMs else 0L)
        },
        onForeground = { armSession(currentItemId) },
    )
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
    //
    // Phase 180/R218 bug fix: this called stopSession() directly, which reports the stop but leaves
    // PlayerStore's own `scope` running — the coroutine backing startSession()'s up-to-5-attempt retry
    // loop (~15s worst case) was never cancelled. Pressing Back during that window (R218's abandon-
    // during-negotiation case, exactly what a cold-start wait makes more likely to happen) let a late
    // retry complete AFTER the stop had already been sent and forgotten, silently minting a fresh
    // orphaned Jellyfin session with its own heartbeat loop reading position from an already-released
    // player. close() already does the right thing — armSession's own comment above has said so since
    // before this call site existed (see armSession's durationProvider doc) — it just was never called
    // from here. close() calls stopSession() itself using the same positionProvider/durationProvider
    // (which close over these same positionMs/durationMs, so behaviour for an established session is
    // unchanged) AND cancels `scope`, which is what actually closes the race.
    DisposableEffect(store) {
        onDispose { store.close() }  // R142: ≥90% → mark played (inside stopSession, called by close())
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
                        // R195 §D — switching tab always resets to level 1, focused on whichever
                        // group currently contains the live selection.
                        pickerOpen -> pickerTapTab(0)
                        focus == PlFocus.SEEK_BAR -> {
                            if (!scrubbing) { scrubbing = true; scrubPos = positionMs }
                            scrubPos = (scrubPos - scrubStep()).coerceAtLeast(0L)
                        }
                        else -> {
                            val order = transportOrder(resolvedNextEpisodeId != null, skipIntroPillVisible)
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
                        pickerOpen -> pickerTapTab(1)
                        focus == PlFocus.SEEK_BAR -> {
                            if (!scrubbing) { scrubbing = true; scrubPos = positionMs }
                            scrubPos = (scrubPos + scrubStep()).coerceAtMost(durationMs)
                        }
                        else -> {
                            val order = transportOrder(resolvedNextEpisodeId != null, skipIntroPillVisible)
                            val idx = order.indexOf(focus)
                            if (idx < order.lastIndex) focus = order[idx + 1]
                        }
                    }
                },
                onUp = {
                    wake()
                    when {
                        epRailOpen -> { epRailOpen = false; scheduleHide() }
                        // R195 §D — Up/Down moves within whichever level is active.
                        pickerOpen -> if (pickerLevel == 1) { if (pickerVersionIdx > 0) pickerVersionIdx-- }
                                      else { if (pickerIdx > 0) pickerIdx-- }
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
                        pickerOpen -> if (pickerLevel == 1) {
                            val vsize = pickerGroups.getOrNull(pickerIdx)?.versions?.size ?: 1
                            if (pickerVersionIdx < vsize - 1) pickerVersionIdx++
                        } else {
                            if (pickerIdx < pickerGroups.size - 1) pickerIdx++
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
                        pickerOpen -> pickerSelect()
                        focus == PlFocus.SKIP_INTRO -> skipIntro()
                        focus == PlFocus.SEEK_BAR -> {
                            if (scrubbing) commitScrub() else { scrubbing = true; scrubPos = positionMs }
                        }
                        focus == PlFocus.PLAY     -> togglePlay()
                        focus == PlFocus.SKIP_BACK -> skip(-SKIP_BACK_MS)
                        focus == PlFocus.SKIP_FWD  -> skip(SKIP_FWD_MS)
                        focus == PlFocus.TRACKS    -> {
                            pickerOpen = true
                            pickerLevel = 0
                            val currentFlat = if (pickerTab == 0) selectedAudio else selectedSub
                            pickerIdx = pickerGroups.indexOfFirst { g -> g.versions.any { it.flatIndex == currentFlat } }.coerceAtLeast(0)
                        }
                        focus == PlFocus.NEXT_EP   -> advanceNext()
                        focus == PlFocus.BACK      -> onBack()
                        else -> {}
                    }
                },
                onBack = {
                    when {
                        pickerOpen    -> pickerBack()
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
                        MediaKey.NEXT         -> if (resolvedNextEpisodeId != null) advanceNext() else wake()
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
        // Phase R220 (FR-R220-3 rung 4) — the Android actual's recovery ladder calls this only after
        // re-attach/seek-flush/surface-recreate have all failed while the player is genuinely still
        // playing. Re-arming mirrors PlayerLifecycleEffect's own onForeground path exactly (same
        // re-negotiate-without-a-full-player-rebuild shape) rather than inventing a second one.
        // FR-R220-5 — feeds rawBufferMoment above so a rung running past R218's debounce shows STALL.
        PlayerVideoSurface(
            player,
            Modifier.fillMaxSize(),
            onVideoOutputStuck = { armSession(currentItemId) },
            onVideoOutputRecovering = { videoOutputRecovering = it },
        )

        // ── Dim scrim (deepens when chrome is up, paused, or R218's moment C stalls) ──
        val dimAlpha = when {
            // R218 (FR-R218-3) — "Moment C dims further... because the chrome alone stops being enough
            // of a signal" past the 60s deepen threshold; ~38% before that, per the chosen direction.
            displayedBufferMoment == PlBufferMoment.STALL && stallDeepened -> 0.55f
            displayedBufferMoment == PlBufferMoment.STALL                 -> 0.38f
            chromeVisible && !isPlaying -> 0.50f
            chromeVisible               -> 0.34f
            else                        -> 0f
        }
        if (dimAlpha > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
        }

        // ── R218 moment B: cold start (ticket in hand, no first frame yet) ────
        // Direction B "Grounded": pulse + title context (already-seen catalog data, no delivery
        // information) + an indeterminate sweep so a long wait never looks frozen + the existing
        // undebounced "Loading…" string — the SAME string moment A uses (FR-R218-4: one string,
        // already translated, no drift between moments).
        if (displayedBufferMoment == PlBufferMoment.COLD) {
            // R218 (FR-R218-6) — "same three parts, scaled: 40px spinner, 26px title, 15px label."
            // TV sizes below are the already on-device-verified treatment (stue TV, 2026-08-29);
            // phone gets the design file's own explicit phone-frame numbers.
            val isPhone = LocalHandset.current
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandPulse(
                        colors,
                        dotSize = if (isPhone) 10.dp else 16.dp,
                        gap = if (isPhone) 9.dp else 14.dp,
                    )
                    Spacer(Modifier.height(if (isPhone) 20.dp else 34.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        itemKicker?.let { kicker ->
                            // R180 (FR-RV-ASP1-2) — the kicker/title split already exists for the
                            // chrome's own metadata block (see PlayerChrome below); reused verbatim here,
                            // not a second source of truth for what's playing.
                            Text(
                                kicker.uppercase(), color = colors.accentSecondary,
                                fontSize = if (isPhone) 11.sp else 12.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            )
                            Spacer(Modifier.height(if (isPhone) 4.dp else 6.dp))
                        }
                        Text(
                            itemTitle, color = Color.White,
                            // FR-R218-6's literal number (26px) for phone; TV keeps its own already-
                            // verified 30sp rather than the raw 54px design-canvas value — see this
                            // block's own header comment.
                            fontSize = if (isPhone) 26.sp else 30.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGrotesk, letterSpacing = (-0.8).sp,
                        )
                    }
                    Spacer(Modifier.height(if (isPhone) 20.dp else 34.dp))
                    IndeterminateSweep(
                        colors,
                        width = if (isPhone) 200.dp else 280.dp,
                        height = if (isPhone) 3.dp else 4.dp,
                    )
                    Spacer(Modifier.height(if (isPhone) 18.dp else 28.dp))
                    Text(str("loading"), color = Color.White.copy(0.7f), fontSize = if (isPhone) 15.sp else 18.sp)
                }
            }
        }

        // R218 moment C (deepened, 60s+): the chrome-up spinner (PlayerChrome's play button, forced
        // visible below) stops being enough of a signal on its own — raise a second, centre spinner too.
        if (displayedBufferMoment == PlBufferMoment.STALL && stallDeepened) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { BufferingSpinner(colors) }
        }

        // ── Loading overlay ───────────────────────────────────────────────────
        val sessionLoading = sessionState as? PlayerSessionState.Loading
        if (sessionLoading != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Incidental layout correction: these were direct children of the Box above, which
                // centre-stacks its children — so the 48.dp Spacer did nothing and "Loading..." was
                // drawn ON TOP of the spinner. A Column is what the spacing was always written for,
                // and R237's second line below needs it to be legible rather than a third overlap.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BufferingSpinner(colors)
                    Spacer(Modifier.height(48.dp))
                    // R180 (FR-RV-ASP1-2) — a neutral loading string regardless of cause; naming the PGS
                    // burn-in restream here would leak the delivery method, which the picker keeps invisible.
                    Text(
                        str("loading"),
                        color = Color.White.copy(0.7f), fontSize = 18.sp,
                    )
                    // R237 (FR-R237-5) — additive, and the ONLY change to R218's states: once a first
                    // attempt has already failed this is provably not a normal cold start, and R218's own
                    // deepen-at-60s is far past the point a viewer gives up (measured: ~4s). No new visual
                    // language, no progress count, no attempt number.
                    if (sessionLoading.retrying) {
                        Spacer(Modifier.height(10.dp))
                        Text(str("loading.still_trying"), color = Color.White.copy(0.45f), fontSize = 14.sp)
                    }
                }
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
            // R237 — extracted into its own composable rather than inlined here. PlayerScreen's body is
            // already ~3.5k lines, and inlining this overlay pushed the Compose-generated method for it
            // past what ART's verifier accepts in the R8-minified release build:
            //   java.lang.VerifyError: Verifier rejected class …PlayerScreen…
            //   register vN has type Precise Reference: java.lang.String but expected Integer
            // The player then died the instant it was opened — release build only, so every compile
            // check and unit test passed. Caught by opening an episode on the stue TV, 2026-09-06.
            // Keep new player chrome in its own composable; do not inline it back into this function.
            PlayerSessionErrorOverlay(
                error = sessionError,
                colors = colors,
                onReauthRequired = onReauthRequired,
                onRetry = { store.startSession(itemId, positionProvider = { positionMs }, isPausedProvider = { !isPlaying }) },
                onBack = onBack,
            )
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
        // R218 (FR-R218-1, moment C, Open question #2) — "the chrome comes up on its own" during a
        // stall; forced visible here WITHOUT touching chromeVisible/chromeRevision themselves, so R208's
        // 30s auto-hide timer is never armed or reset by it, and it retracts the instant the stall clears
        // (displayedBufferMoment leaves STALL) rather than needing its own separate hide timer. A chrome
        // the viewer had already raised manually is unaffected either way — this only ever ADDS
        // visibility, never removes it.
        //
        // R218 moment B, bug fix found live on-device (2026-08-29): chromeVisible defaults to true, so
        // without the extra condition below the cold-start overlay (drawn earlier/underneath in this
        // same Box) showed simultaneously WITH the normal transport chrome layered on top of it — top
        // bar and bottom transport visible around the pulse/title/sweep, contradicting "full-screen on
        // black" (moment C's chrome-forcing is the opposite intent: RAISE chrome over a stall so the
        // viewer keeps their position; moment B has no position to keep yet, so it suppresses chrome
        // instead). Moment A (unrelated, out of scope, unchanged) has the same underlying layering but
        // was not reported as a problem and its own overlay is small/centered, not full-black.
        val stallActive = displayedBufferMoment == PlBufferMoment.STALL
        val coldActive = displayedBufferMoment == PlBufferMoment.COLD
        AnimatedVisibility(
            visible = (chromeVisible || stallActive) && !coldActive,
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
                hasNextEp       = resolvedNextEpisodeId != null,
                isSeries        = episodes != null,
                epRailOpen      = epRailOpen,
                pickerOpen      = pickerOpen,
                nextUpVisible   = nextUpVisible,
                directPlay      = (sessionState as? PlayerSessionState.Ready)?.ticket?.directPlay ?: true,
                container       = (sessionState as? PlayerSessionState.Ready)?.ticket?.container ?: "",
                stallActive      = stallActive,
                seekMomentActive = displayedBufferMoment == PlBufferMoment.SEEK,
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
                            pickerLevel = 0
                            val currentFlat = if (pickerTab == 0) selectedAudio else selectedSub
                            pickerIdx = pickerGroups.indexOfFirst { g -> g.versions.any { it.flatIndex == currentFlat } }.coerceAtLeast(0)
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
        // R195 bug fix: on touch (phone/web), there was no way to dismiss the picker without making
        // a selection — no D-pad Back reaches this screen's key handler on a phone (no hardware D-pad),
        // and nothing else called pickerBack(). A full-screen scrim behind the picker gives touch users
        // the same "tap away to back out" a modal is expected to have; tapping it runs the exact same
        // pickerBack() Back already uses (steps out of level 2 first, closes from level 1), so touch and
        // D-pad still can't diverge in behaviour. No ripple — a screen-spanning tap target shouldn't show one.
        AnimatedVisibility(visible = pickerOpen, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
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
        AnimatedVisibility(
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
                selectedAudio    = selectedAudio,
                selectedSub      = selectedSub,
                // R195 — touch parity with the D-pad: a tap just moves the target index then runs the
                // EXACT SAME pickerSelect()/pickerBack() logic Select/Back already use, so touch (phone)
                // and D-pad (TV) can never diverge in behaviour.
                onTapLanguage = { idx -> pickerIdx = idx; pickerSelect() },
                onTapVersion  = { idx -> pickerVersionIdx = idx; pickerSelect() },
                onTapBack     = { pickerBack() },
                onTapTab      = { tab -> pickerTapTab(tab) },
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
/**
 * R195 §3 — the picker is two levels: [pickerLevel] 0 shows one row per [PickerLanguage] in the
 * active tab's group list; [pickerLevel] 1 shows the [pickerIdx]-selected group's own
 * [PickerLanguage.versions]. [onTapLanguage]/[onTapVersion] give touch (phone/web) exact parity with
 * the D-pad — both just move the target index then run through PlayerScreen's SAME pickerSelect() the
 * Select key already uses (see the call site). [audioGroups]/[subGroups] are BOTH needed (not just the
 * active tab's) because the tab bar shows both tabs' flags regardless of which is active.
 */
@Composable
private fun TrackPicker(
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
) {
    val lang = LocalLang.current
    val groups = if (pickerTab == 0) audioGroups else subGroups
    val selectedFlat = if (pickerTab == 0) selectedAudio else selectedSub
    val audioFlag = audioGroups.firstOrNull { g -> g.versions.any { it.flatIndex == selectedAudio } }?.language?.lowercase()?.let { LANG_CC[it] }
    val subFlag = subGroups.firstOrNull { g -> g.versions.any { it.flatIndex == selectedSub } }?.language?.lowercase()?.let { LANG_CC[it] }

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
            if (pickerLevel == 0) {
                // Tabs — only shown at level 1 (§D: switching tab always resets to level 1 anyway).
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PickerTab(str("player.tab_audio"), pickerTab == 0, audioFlag, onTap = { onTapTab(0) })
                    PickerTab(str("player.tab_subtitles"), pickerTab == 1, subFlag, onTap = { onTapTab(1) })
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
            val focusedIdx = if (pickerLevel == 0) pickerIdx else pickerVersionIdx
            val lastIdx = (rowCount - 1).coerceAtLeast(0)

            // R180 — open pre-scrolled to the active row (jump, no animation); R195 — also whenever the
            // level itself changes (entering/leaving level 2 always starts scrolled to the top row).
            LaunchedEffect(pickerTab, pickerLevel) {
                listState.scrollToItem(focusedIdx.coerceIn(0, lastIdx))
            }
            // R180 — keep the D-pad-focused row visible as it moves (10+ track titles genuinely
            // overflow a fixed-height list — verified: a real title has 7 audio + 13 subtitle tracks).
            LaunchedEffect(focusedIdx) {
                listState.animateScrollToItem(focusedIdx.coerceIn(0, lastIdx))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pickerLevel == 0) {
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
        }
    }
}

/** §B — level 2's header bar: the flag sits ONCE here (never repeated per version row), with the
 *  language name and version count. Also hosts the touch Back affordance (§D: hardware Back already
 *  returns to level 1 via PlayerLifecycleEffect/dpadFocusable's onBack; this is the tap equivalent for
 *  a phone/web viewer with no hardware Back key in reach). */
@Composable
private fun PickerCrumbHeader(group: PickerLanguage, colors: RaviloColors, onBack: () -> Unit) {
    val lang = LocalLang.current
    val flagRes = group.language?.lowercase()?.let { LANG_CC[it] }
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

@Composable
private fun PickerTab(label: String, active: Boolean, flagRes: DrawableResource? = null, onTap: (() -> Unit)? = null) {
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
    if (group.language != null) {
        pickerName(group.language, "").takeIf { it.isNotBlank() }?.let { return it }
    }
    return when {
        group.versions.all { it.kind == VariantKind.COMMENTARY } -> t("player.badge_commentary", lang)
        group.versions.all { it.kind == VariantKind.DESCRIBE } -> t("player.badge_describes_action", lang)
        group.language != null -> group.language.uppercase()
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
 * On Helt Sort S07E03 that captioned a correctly-selected `Dansk (CC)` track with the forced track's
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
    onTap: () -> Unit,
) {
    val single = group.versions.size <= 1
    val badges = if (group.isOff) emptyList() else languageRowBadges(group, selectedFlat)
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
            .clickable(onClick = onTap)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PickerTick(selected = selected, colors = colors)
        PickerGlyphBox(language = group.language, isOff = group.isOff, isUnnamed = group.isUnnamed)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val name = if (group.isOff) str("off") else if (group.isUnnamed) str("player.unnamed") else groupDisplayName(group, lang)
                Text(name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
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
    val headerFlag = group.language?.lowercase()?.let { LANG_CC[it] }
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
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) colors.focusRing.copy(0.7f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
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
        if (selected) Text("✓", color = colors.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
private fun PickerGlyphBox(language: String?, isOff: Boolean, isUnnamed: Boolean) {
    val flagRes = if (!isOff && !isUnnamed) language?.lowercase()?.let { LANG_CC[it] } else null
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

/**
 * R218 (FR-R218-1, moment B) — Direction B's "pulsing brand dot cluster": three dots breathing in
 * scale+opacity, staggered 180ms apart, across the accent→accentSecondary gradient (skin-aware, unlike
 * the design file's hardcoded Aurora hex values — the same gradient [PlayPauseButton]'s focus ring and
 * [PlayerScreen]'s progress fill already use). Approximates the design's asymmetric 45%-peak keyframe
 * with a simple symmetric breathe (RepeatMode.Reverse) — visually equivalent for a continuous ambient
 * loop, not a cut corner that changes what it communicates.
 */
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
// R195 (FR-RV §5.1) — widened to also catch a bare "HI" token and the word "hearing" alone (was only
// "sdh" or the full phrase "hard of hearing"); still title-text only — an untitled SDH track needs the
// Bazarr `hi` flag plumbing this phase's dev-review addendum scoped as separate backend work.
// R235 (FR-R235-5) — "cc"/"closed caption" is the same descriptive-subtitle concept as SDH and was
// unmatched (the reported file's full track is titled "Dansk (CC)"), so it grouped as PLAIN with no
// badge and no distinct variant signature from the file's other (forced) Danish track.
private val SDH_RE = Regex("""\bsdh\b|\bhi\b|hard of hearing|hearing impaired|\bhearing\b|\bcc\b|closed caption""", RegexOption.IGNORE_CASE)
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

// ─── R195 — same-language disambiguation: kind/region classification, grouping, provenance suppression ───
//
// Dev-review addendum (2026-08-10) findings this builds on: SDH/region title-text detection already
// existed (SDH_RE/REGION_MARKERS, R180 stage 3) — this section EXTENDS that infrastructure rather than
// replacing it. `trackVariant`/`REGION_MARKERS`/`regionSuffix` above stay as-is (still used for the
// flat single-version case's muted suffix); the grouping/kind/region types below are the new,
// two-level-picker-specific layer.

internal enum class VariantKind { PLAIN, SDH, FORCED, DESCRIBE, COMMENTARY }

private fun variantKind(title: String?, forced: Boolean): VariantKind = when {
    title != null && COMMENTARY_RE.containsMatchIn(title) -> VariantKind.COMMENTARY
    title != null && SDH_RE.containsMatchIn(title) -> VariantKind.SDH
    title != null && AD_RE.containsMatchIn(title) -> VariantKind.DESCRIBE
    forced -> VariantKind.FORCED
    else -> VariantKind.PLAIN
}

/**
 * R195 (FR-RV §5.2) — region synonym table: extends [REGION_MARKERS] (which only ever produced a muted
 * TEXT suffix) with an actual flag + a stable region code for the two-level picker's §C region flags.
 * `flag = null` is a deliberate, honest gap for Brazil/Taiwan: no `flag_br`/`flag_tw` drawable asset
 * exists in this module yet (checked: `composeResources/drawable/flag_*.png` has no br/tw entry) —
 * falls through to "no region flag shown, name text only" rather than show a WRONG flag or block this
 * phase on new artwork. `\b`-bounded, first-match-wins, same discipline as `REGION_MARKERS`.
 */
internal data class RegionInfo(val code: String, val name: String, val flag: DrawableResource?)

private val REGION_TABLE: List<Pair<Regex, RegionInfo>> = listOf(
    Regex("""\bcastilian\b|\bspain\b|\bes[- ]es\b""", RegexOption.IGNORE_CASE) to RegionInfo("es", "España", LANG_CC["es"]),
    Regex("""\blatin american?\b|\bes[- ]419\b""", RegexOption.IGNORE_CASE) to RegionInfo("419", "Latinoamérica", null),
    Regex("""\bbrazil(ian)?\b|\bpt[- ]br\b""", RegexOption.IGNORE_CASE) to RegionInfo("br", "Brasil", null),
    Regex("""\bportugal\b|\biberian\b|\bpt[- ]pt\b""", RegexOption.IGNORE_CASE) to RegionInfo("pt", "Portugal", LANG_CC["pt"]),
    Regex("""\bsimplified\b|\bzh[- ]hans\b|\bzh[- ]cn\b""", RegexOption.IGNORE_CASE) to RegionInfo("cn", "简体", LANG_CC["zh"]),
    Regex("""\btraditional\b|\bzh[- ]hant\b|\bzh[- ]tw\b""", RegexOption.IGNORE_CASE) to RegionInfo("tw", "繁體", null),
    Regex("""\bcanad(a|ian)\b""", RegexOption.IGNORE_CASE) to RegionInfo("ca", "Canada", null),
    Regex("""\beuropean\b""", RegexOption.IGNORE_CASE) to RegionInfo("eu", "European", null),
)

private fun resolveRegion(title: String?): RegionInfo? {
    if (title.isNullOrBlank()) return null
    for ((re, info) in REGION_TABLE) if (re.containsMatchIn(title)) return info
    return null
}

/** R195 (FR-RV §5.3) — release-provenance tokens that are never a viewer-meaningful choice on their
 *  own. Used ONLY to detect a collapse-worthy duplicate in [buildLanguageGroups] (positive evidence
 *  the difference between two otherwise-identical tracks is release plumbing) — never to classify a
 *  track's [VariantKind]. */
private val PROVENANCE_RE = Regex("""\b(bluray|blu-ray|web-?dl|webrip|itunes|amzn|netflix|hdtv|remux|dvdrip)\b""", RegexOption.IGNORE_CASE)
private fun hasProvenanceMarker(title: String?): Boolean = title != null && PROVENANCE_RE.containsMatchIn(title)

/** Per-row input to [buildLanguageGroups] — the shape both audio and subtitle tracks flatten to, so
 *  the grouping logic stays generic over R195's "audio gets the identical treatment for free" non-goal. */
private data class PickerEntryInput(
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
internal fun PickerVersion.signature(): String = "${kind.name.lowercase()}|${region?.code ?: ""}|$ordinal"

internal data class PickerLanguage(
    val language: String?,
    val isOff: Boolean,
    val versions: List<PickerVersion>,
    /** §E — every version in this language is fully indistinguishable (same kind=PLAIN, no region, no
     *  title text at all — the only way that can genuinely happen). Level 1 shows "Unnamed" + a neutral
     *  globe glyph instead of a flag; level 2 numbers them "Version 1"…"Version n". */
    val isUnnamed: Boolean,
)

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
 * `LaunchedEffect(Unit)` poll loop that only ever sees the FIRST composition's [audioGroups]/
 * [subGroups] (both empty/placeholder at that point) because they were plain `remember(...)` vals, not
 * snapshot state — has a test that can actually catch a recurrence. [PlayerScreen] itself is
 * responsible for supplying LIVE group data (via `rememberUpdatedState`); this function has no opinion
 * on how its inputs stay fresh, only on what to do with them.
 *
 * Scope note (unchanged since R181): only matches against native/external [subtitleTracks], never
 * PGS/encode burn-in subs — a remembered language that exists only as a PGS track in this file falls
 * through instead of silently triggering an autoplay transcode; PGS stays a manual pick.
 */
internal fun resolveTrackChoice(
    seriesChoice: RememberedChoice?,
    globalChoice: RememberedChoice?,
    audioGroups: List<PickerLanguage>,
    subGroups: List<PickerLanguage>,
    audioTracks: List<PlayerAudioTrack>,
    subtitleTracks: List<PlayerSubtitleTrack>,
): TrackSelectionResult {
    // Phase 210/R241 — a remembered language must match even when this file tags it at a different
    // ISO-639 granularity than the file the choice was learned from. Confirmed against real production
    // data ("It's Always Sunny in Philadelphia", 182 episodes/16 seasons of mixed release sources):
    // some seasons tag subtitle languages as 3-letter ("dan", "eng"), others as 2-letter ("da", "en") —
    // a plain `.equals(lang, ignoreCase = true)` never matches "da" against a remembered "dan", so the
    // remembered tier silently misses on the very next episode and falls through to the source's own
    // (often unrelated) default track — reported live as "it remembers on this episode, not the next."
    // languageName() (already used for display) doubles as the canonicalizer for free.
    fun sameLanguage(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        val na = languageName(a)
        val nb = languageName(b)
        return if (na != null && nb != null) na == nb else a.equals(b, ignoreCase = true)
    }

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
            // FR-R235-2 — the reported bug exactly: Helt Sort S07E03's forced Danish track is ALSO
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
private fun buildLanguageGroups(entries: List<PickerEntryInput>): List<PickerLanguage> {
    val byLanguage = entries.withIndex().groupBy { (_, e) -> e.language?.lowercase() }
    // Group order = each language's first-occurrence position in the original track list (not
    // alphabetical) — preserves the pre-R195 flat picker's stream order, which callers/track
    // authoring already treat as meaningful (e.g. the source's own primary-language-first ordering).
    return byLanguage.entries.sortedBy { (_, indexed) -> indexed.first().index }.map { (_, indexed) ->
        val language = indexed.first().value.language
        val withMeta = indexed.map { (flatIdx, e) ->
            Triple(flatIdx, e, variantKind(e.title, e.forced) to resolveRegion(e.title))
        }
        // §5.3 — collapse a (kind, region) cluster to ONE only when at least one member carries a
        // provenance marker (positive evidence the only difference is release plumbing) AND every
        // member has SOME title text (never blind-merge untitled tracks — that's §3.8's hard floor).
        // `ordinal`/`clusterSize` are scoped PER CLUSTER (not the whole group) — bug fix: this used to
        // number across the entire flattened group, which both mislabeled "Recording N" and made
        // `clusterSize` impossible to compute at all (see PickerVersion's doc).
        val versions = withMeta.groupBy { it.third }.values.flatMap { cluster ->
            val anyProvenance = cluster.any { hasProvenanceMarker(it.second.title) }
            val allHaveTitles = cluster.all { !it.second.title.isNullOrBlank() }
            val effective = if (cluster.size > 1 && anyProvenance && allHaveTitles) listOf(cluster.first()) else cluster
            effective.mapIndexed { ordinal, (flatIdx, e, kindRegion) ->
                PickerVersion(
                    flatIndex = flatIdx,
                    kind = kindRegion.first,
                    region = kindRegion.second,
                    badges = e.badges,
                    forced = e.forced,
                    isDefault = e.isDefault,
                    hadTitleText = !e.title.isNullOrBlank(),
                    ordinal = ordinal,
                    clusterSize = effective.size,
                )
            }
        }.sortedBy { it.flatIndex } // preserve original stream order within the group after re-flattening clusters
        val isUnnamed = versions.size > 1 && versions.all {
            it.kind == VariantKind.PLAIN && it.region == null && !it.hadTitleText
        }
        PickerLanguage(language, isOff = false, versions = versions, isUnnamed = isUnnamed)
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
    onReauthRequired: (() -> Unit)?,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val errTitle = when (error.kind) {
        PlayerErrorKind.REAUTH -> "error.play.reauth.title"
        PlayerErrorKind.FORBIDDEN -> "error.play.forbidden.title"
        PlayerErrorKind.GONE -> "error.play.gone.title"
        PlayerErrorKind.UNREACHABLE -> "error.play.unreachable.title"
        PlayerErrorKind.GENERIC -> "error.generic"
    }
    val errBody = when (error.kind) {
        PlayerErrorKind.REAUTH -> "error.play.reauth.body"
        PlayerErrorKind.FORBIDDEN -> "error.play.forbidden.body"
        PlayerErrorKind.UNREACHABLE -> "error.play.unreachable.body"
        // GONE has no next step, and GENERIC has no honest sentence to offer beyond its heading.
        PlayerErrorKind.GONE, PlayerErrorKind.GENERIC -> null
    }
    // FR-R237-3 — Retry stays only where trying again can plausibly change the answer. Offering it for
    // a verdict that is deterministic for ten minutes is the same mistake as the retry loop, moved into
    // the viewer's hands. REAUTH gets the action that actually resolves it; 403/404 get Back alone.
    val showRetry = error.kind == PlayerErrorKind.UNREACHABLE || error.kind == PlayerErrorKind.GENERIC
    val showSignIn = error.kind == PlayerErrorKind.REAUTH && onReauthRequired != null
    val retryFR = remember { FocusRequester() }
    val backFR = remember { FocusRequester() }
    LaunchedEffect(error) {
        runCatching { if (showRetry || showSignIn) retryFR.requestFocus() else backFR.requestFocus() }
    }
    var retryFocused by remember { mutableStateOf(false) }
    var backFocused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(str(errTitle), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (errBody != null) {
                Spacer(Modifier.height(10.dp))
                Text(str(errBody), color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showRetry || showSignIn) Box(
                    modifier = Modifier
                        .background(if (retryFocused) Color.White else Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        .border(2.dp, if (retryFocused) colors.focusRing else Color.Transparent, RoundedCornerShape(8.dp))
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
