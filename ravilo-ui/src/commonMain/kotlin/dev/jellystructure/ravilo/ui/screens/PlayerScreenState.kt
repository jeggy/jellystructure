package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import dev.jellystructure.ravilo.ui.focus.MediaKey
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerFullscreen
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.ravilo.ui.seams.languageName
import dev.jellystructure.ravilo.ui.seams.pictureInPictureAvailable
import dev.jellystructure.ravilo.ui.seams.togglePictureInPicture
import dev.jellystructure.ravilo.ui.seams.togglePlayerFullscreen
import dev.jellystructure.ravilo.ui.isTvPlatform
import kotlinx.coroutines.delay

/*
 * DEB-2392 — PlayerScreen split into a state holder.
 *
 * PlayerScreen used to be one ~2 000-line composable: forty `var … by remember` locals, twenty-five local
 * functions closing over them, every effect, the key handlers and the whole layer stack. In the R8 release
 * build its single dex method needed 251–270 registers against ART's 256 cliff (scripts/check-player-dex.sh),
 * and each hoist of one local function bought nothing that survived the next unrelated commit (DEB-2281).
 *
 * The split: this file holds the state and the logic (a holder object, as PlayerBookkeeping already was for
 * sixteen of the locals); PlayerScreen.kt keeps the composable as a thin shell that builds the holder, feeds it the
 * composition's inputs and calls the effects and the layers. Each layer is its own composable that takes the holder,
 * so no method has to carry the whole player's state in registers. Behaviour is unchanged: every property is the
 * snapshot state the local was, every method is the local function it replaces.
 */

/**
 * What the composition hands the holder each time it runs: PlayerScreen's parameters and the few values it reads from
 * composition locals. The holder reads the LIVE value, which replaces the `rememberUpdatedState` mirrors the poll loop
 * used (an effect launched once must not see the first composition's `itemId` or `store`).
 */
internal data class PlayerScreenInputs(
    val itemId: String,
    val itemTitle: String,
    val itemKicker: String?,
    val nextEpisodeId: String?,
    val nextEpisodeLabel: String?,
    val nextEpisodeTitle: String?,
    val episodes: List<PlayerEpisodeEntry>?,
    val currentEpIndex: Int,
    val seriesId: String?,
    val originalLanguage: String?,
    val posterUrl: String?,
    val segments: dev.jellystructure.shared.tv.TvSegmentMarkers,
    val logoUrl: String?,
    val logoInk: String?,
    val seriesName: String?,
    val resume: PlayerResumeStore,
    val store: PlayerStore,
    val onBack: () -> Unit,
    val onNavigateToEpisode: ((String) -> Unit)?,
    val startAtMs: Long?,
    val sessionState: PlayerSessionState,
    val serverBaseUrl: String,
    val handset: Boolean,
    val lang: String,
    val haptics: HapticFeedback,
    val castLink: CastLinkState,
)

internal class PlayerScreenState(initial: PlayerScreenInputs) {
    var inputs by mutableStateOf(initial)

    // ─── The composition's inputs, read live ─────────────────────────────────
    val itemId: String get() = inputs.itemId
    val itemTitle: String get() = inputs.itemTitle
    val itemKicker: String? get() = inputs.itemKicker
    val episodes: List<PlayerEpisodeEntry>? get() = inputs.episodes
    val currentEpIndex: Int get() = inputs.currentEpIndex
    val segments: dev.jellystructure.shared.tv.TvSegmentMarkers get() = inputs.segments
    val store: PlayerStore get() = inputs.store
    val handset: Boolean get() = inputs.handset
    val sessionState: PlayerSessionState get() = inputs.sessionState
    fun onBack() = inputs.onBack()

    /**
     * Bug fix (auto-advance retry loop): a "next episode" id that IS the episode already playing is not a next
     * episode — navigating to it can only be a no-op, while the credits card kept offering it and the countdown kept
     * re-firing it forever. Resolved once here so every consumer (credits-card mode, transport order, the Next Episode
     * control, MediaKey.NEXT) agrees on whether a next episode exists.
     */
    val resolvedNextEpisodeId: String? get() = inputs.nextEpisodeId?.takeIf { it != inputs.itemId }

    // ─── Objects the screen owns ─────────────────────────────────────────────
    val player = RaviloPlayer()
    val bk = PlayerBookkeeping(initial.castLink)
    val playerFR = FocusRequester()
    /** R380 (FR-R380-7) — a cast remote's view of this player (TV only): fields, not parameters (R258). */
    val castSource = CastVideoSource()

    /** R244 — the phone's brightness, volume and rotation controls; one instance, shared by the gesture layer and the chrome. Null off a handset. */
    var handsetControls: dev.jellystructure.ravilo.ui.seams.HandsetPlayerControls? = null

    // ─── Phone-local chrome state (R244); none of it exists on a TV ──────────
    var locked by mutableStateOf(false)          // FR-R244-8
    var fillMode by mutableStateOf(false)        // FR-R244-6 — never persists across titles
    var subtitleSize by mutableStateOf('M')      // FR-R244-10 — phone-local
    var landscapeForced by mutableStateOf(false) // FR-R244-7 — only while the system is locked

    // ─── Playback state — polled every 500 ms from the player ────────────────
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
    var bufferedMs by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var audioTracks by mutableStateOf<List<PlayerAudioTrack>>(emptyList())
    var subtitleTracks by mutableStateOf<List<PlayerSubtitleTrack>>(emptyList())

    // R218 — the same poll loop's signal for the four buffering moments (see PlBufferMoment).
    var hasRenderedFirstFrame by mutableStateOf(false)
    /**
     * DEB-3103 (DEB-3093 FR-6) — when a replacement stream (the viewer did not ask for it) was handed to the player, until its first
     * frame is out. While it holds ([replacementHolding]) the last frame stays on screen: no shutter, no start screen, no cold-start moment.
     */
    var replacementHeldSinceMs by mutableStateOf<Long?>(null)
    var isBuffering by mutableStateOf(false)
    var isSeeking by mutableStateOf(false)
    // Phase R220 (FR-R220-5) — true for the whole time the Android actual's video-output recovery ladder is running;
    // folded into rawBufferMoment so a rung past R218's own debounce shows the existing STALL presentation instead of a
    // silent frozen frame (isPlaying/isBuffering/isSeeking all read healthy throughout a video-output stall).
    var videoOutputRecovering by mutableStateOf(false)
    var displayedBufferMoment by mutableStateOf(PlBufferMoment.NONE)

    // Chrome visibility — bumping chromeRevision restarts the auto-hide timer.
    // R290 (FR-R290-1, dev review item 1) — starts FALSE: the chrome is raised, and its timer armed, on the first frame
    // the viewer will keep (the latch in the poll loop), never on a stream loading.
    var chromeVisible by mutableStateOf(false)
    // R251 (FR-R251-4) — the bottom transport band's measured height, reported by PlayerChrome.
    var transportBandPx by mutableIntStateOf(0)
    var chromeRevision by mutableLongStateOf(0L)
    // R157 (FR-R157-3.2) — bumping restarts the cursor auto-hide timer, independently of chrome.
    var pointerActivityRevision by mutableLongStateOf(0L)

    var focus by mutableStateOf(PlFocus.PLAY)

    // R258 — a scrub preview belongs to the episode it was started on, so an episode switch (rail / Next / auto-advance)
    // drops a pending preview instead of freezing the elapsed label at the OLD episode's target and drawing its tick on
    // the new bar (stue TV, 2026-09-17). It was `remember(itemId)`; here it is the item the scrub began on.
    private var scrubbingFor by mutableStateOf<String?>(null)
    var scrubbing: Boolean
        get() = scrubbingFor == itemId
        set(value) { scrubbingFor = if (value) itemId else null }
    var scrubPos by mutableLongStateOf(0L)

    // Track picker
    var pickerOpen by mutableStateOf(false)
    var pickerTab by mutableIntStateOf(0)   // 0=audio, 1=subs, 2=picture
    // R195 — two levels: pickerIdx indexes PickerLanguage groups (level 1); pickerVersionIdx indexes
    // groups[pickerIdx].versions (level 2, only meaningful while pickerLevel == 1).
    var pickerIdx by mutableIntStateOf(0)
    var pickerLevel by mutableIntStateOf(0)        // 0 = language list, 1 = version list
    var pickerVersionIdx by mutableIntStateOf(0)
    var selectedAudio by mutableIntStateOf(0)
    var selectedSub by mutableIntStateOf(-1) // -1 = off

    // Next-up card
    var nextUpVisible by mutableStateOf(false)
    // R111: once the viewer picks "Watch credits" we latch it dismissed so the near-end poll doesn't re-show the card on
    // the very next tick. Reset per-episode in the item effect.
    var nextUpDismissed by mutableStateOf(false)
    var countdown by mutableIntStateOf(DEFAULT_SKIP_SECS)
    var nuFocus by mutableStateOf(NuFocus.PLAY)

    // Episode rail
    var epRailOpen by mutableStateOf(false)
    var focusedEpIdx by mutableIntStateOf(initial.currentEpIndex)

    // ─── Derived: the picker's groups ────────────────────────────────────────

    // R56: encode subs (PGS) from the server-pushed ticket, appended after native tracks in the picker.
    val encodeSubTracks: List<PlayerSubtitleTrack> by derivedStateOf {
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

    // R195 §3 — language groups for the two-level picker. `flatIndex` on each PickerVersion is an index into `audioTracks`
    // (audio) or `subtitleTracks + encodeSubTracks` starting at 0 (subtitles — Off is its own pseudo-language, never
    // grouped), so choosePick/resolveTrackSelection keep working off the same indices.
    val audioGroups: List<PickerLanguage> by derivedStateOf {
        val effectiveAudio = audioTracks.ifEmpty { listOf(PlayerAudioTrack(0, "Default", null)) }
        val originalLanguage = inputs.originalLanguage
        val lang = inputs.lang
        // Phase 314b — an added copy rides its source's row.
        foldedAudioGroups(effectiveAudio) { list ->
            buildLanguageGroups(list.map {
                PickerEntryInput(it.language, it.label, forced = false, isDefault = it.isDefault, badges = audioBadges(it, originalLanguage, lang))
            })
        }
    }
    val subVersionOptions: List<PlayerSubtitleTrack> by derivedStateOf { subtitleTracks + encodeSubTracks }
    private val subGroups: List<PickerLanguage> by derivedStateOf {
        val lang = inputs.lang
        buildLanguageGroups(subVersionOptions.map {
            PickerEntryInput(it.language, it.label, forced = it.forced, isDefault = it.isDefault, badges = subtitleBadges(it, lang))
        })
    }
    // Off is a permanent, always-single-version pseudo-language ahead of every real group (§A: "Off is the first row, as
    // today"). flatIndex -1 is a sentinel `group.isOff`/choosePick() checks for.
    val subGroupsWithOff: List<PickerLanguage> by derivedStateOf {
        val offVersion = PickerVersion(flatIndex = -1, kind = VariantKind.PLAIN, region = null, badges = emptyList(), forced = false, isDefault = false, hadTitleText = false, ordinal = 0, clusterSize = 1)
        listOf(PickerLanguage(language = null, isOff = true, versions = listOf(offVersion), isUnnamed = false)) + subGroups
    }
    // Phase 314c — the Picture tab: one row per version, offered only when the film has two or more.
    val pickerGroups: List<PickerLanguage>
        get() = when (pickerTab) { 0 -> audioGroups; 1 -> subGroupsWithOff; else -> pictureVersionGroups(bk.videoVersions) }

    /** The audio / subtitle row the player is on now, in the picker's flat indexing. */
    val shownAudioNow: Int get() = shownAudio(selectedAudio, audioTracks)
    val shownSubNow: Int get() = shownSelectedSub(selectedSub, subtitleTracks.size, encodeSubTracks, bk.burnedSubIndex)

    // ─── Derived: what the screen is showing ─────────────────────────────────

    // R218 (FR-R218-1) — the one derived buffering moment. Priority order matters: a wait BEFORE any first frame is
    // always COLD even if isSeeking/isBuffering also happen to be true (there is no "last frame" yet for a seek's
    // lighter treatment to make sense against), then SEEK (a seek's own buffering must never read as an organic STALL),
    // then plain STALL.
    /** FR-6 — a replacement's first frame is not out yet, and the hold ([REPLACEMENT_HOLD_MS]) has not run out. */
    val replacementHolding: Boolean
        get() = replacementHeldSinceMs?.let { !hasRenderedFirstFrame && kotlin.time.Clock.System.now().toEpochMilliseconds() - it <= REPLACEMENT_HOLD_MS } == true

    val rawBufferMoment: PlBufferMoment
        get() = when {
            sessionState !is PlayerSessionState.Ready -> PlBufferMoment.NONE  // moment A owns this wait
            !hasRenderedFirstFrame && replacementHolding -> PlBufferMoment.NONE   // DEB-3103 (FR-6) — a short wait does not look like a problem
            !hasRenderedFirstFrame -> PlBufferMoment.COLD
            // R220 (FR-R220-5) — ahead of SEEK/isBuffering: a video-output recovery in progress must always read as
            // STALL, even if rung 2's own no-op seek happens to flip isSeeking true mid-ladder.
            videoOutputRecovering -> PlBufferMoment.STALL
            isSeeking -> PlBufferMoment.SEEK
            isBuffering -> PlBufferMoment.STALL
            else -> PlBufferMoment.NONE
        }

    /** R290 — the one derived start phase (dev review item 7: one val from a pure helper). */
    val startPhaseNow: StartPhase get() = startPhase(latched = bk.latchedForItemId == itemId, startScreenDue = bk.startScreenDue)

    val stallActive: Boolean get() = displayedBufferMoment == PlBufferMoment.STALL
    val coldActive: Boolean get() = displayedBufferMoment == PlBufferMoment.COLD

    // R182 (FR-RV-SKIP1-1) — visible once inside [introStartMs, introEndMs), for the brief grace countdown OR whenever
    // chrome itself is up (matching the design prototype's own refreshSkipIntro() — reappears on wake(), no separate
    // latch), and never over another modal overlay.
    val skipIntroPillVisible: Boolean
        get() {
            val start = segments.introStartMs
            val end = segments.introEndMs
            val inside = start != null && end != null && end > start && positionMs in start until end
            return inside && bk.skipIntroMode != dev.jellystructure.shared.tv.SkipMode.OFF &&
                (bk.skipIntroCountingDown || chromeVisible) && !pickerOpen && !nextUpVisible && !epRailOpen
        }

    // R182 (FR-RV-SKIP1-2) — priority: a stinger always wins (never auto-skip past it), else a real next episode, else
    // plain skip-credits/exit. R230 (FR-R230-3) — Skip Credits: Off only ever reaches this card via the isEnded-trigger's
    // Autoplay branch, which guarantees resolvedNextEpisodeId != null — Stinger's "skip to scene" and the plain
    // Skip-Credits exit are both meaningless once already at the real end of the file.
    val creditsCardMode: CreditsCardMode
        get() = when {
            bk.skipCreditsMode == dev.jellystructure.shared.tv.SkipMode.OFF -> CreditsCardMode.NEXT_EPISODE
            segments.stinger != null -> CreditsCardMode.STINGER
            resolvedNextEpisodeId != null -> CreditsCardMode.NEXT_EPISODE
            else -> CreditsCardMode.SKIP_CREDITS
        }

    // ─── Chrome ──────────────────────────────────────────────────────────────

    fun wake() { chromeVisible = true; chromeRevision++ }
    fun scheduleHide() { chromeRevision++ }
    // Every chrome-hide goes through this single choke point rather than a bare `chromeVisible = false`.
    // R350 (FR-R350-7) — it no longer moves `focus` (R178 FR-RV-SEL1-1 parked it on PLAY): the control the viewer last
    // focused is still focused when the chrome comes back, and Left/Right move from it. A Select that finds the chrome
    // hidden still never fires that control (R178 FR-RV-SEL1-2, see `dpadRevealsOnly`).
    fun hideChrome() { chromeVisible = false }

    // FR-R244-14 — a light tick on skip, lock and seek release; nothing on play/pause. Compose's own haptic primitive: a
    // no-op on web, so no seam was needed.
    fun tick() { if (handset) runCatching { inputs.haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) } }

    // ─── Transport ───────────────────────────────────────────────────────────

    fun seekTo(ms: Long) {
        val clamped = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(clamped)
        positionMs = clamped
        wake()
    }

    fun skip(ms: Long) {
        val newPos = (positionMs + ms).coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(newPos)
        positionMs = newPos
        bk.viewerSeek = true   // R363 (FR-R363-6)
        tick()
        wake()
    }

    /** R244 (FR-R244-5) — a double-tap seek on the handset gesture layer: no chrome, no viewerSeek. */
    fun doubleTapSeek(inc: Long) {
        val newPos = (positionMs + inc).coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(newPos)
        positionMs = newPos
        tick()
    }

    fun commitScrub() {
        player.seekTo(scrubPos)
        positionMs = scrubPos
        bk.viewerSeek = true   // R363 (FR-R363-6)
        scrubbing = false
        tick()   // FR-R244-9 — on release, never during the drag
        wake()
    }

    fun beginScrubAt(ms: Long) { wake(); focus = PlFocus.SEEK_BAR; scrubbing = true; scrubPos = ms }

    fun togglePlay() {
        if (isPlaying) {
            player.pause(); isPlaying = false
            bk.pauseFlashIsPlay = false
        } else {
            player.play(); isPlaying = true
            bk.pauseFlashIsPlay = true
        }
        bk.pauseFlash = true
        wake()
    }

    fun scrubStep() = SKIP_BACK_MS.coerceAtMost(maxOf(5_000L, (durationMs * 0.012).toLong()))

    fun previousEpisode() { episodes?.getOrNull(currentEpIndex - 1)?.id?.let { inputs.onNavigateToEpisode?.invoke(it) } }

    fun advanceNext() {
        nextUpVisible = false
        val nextId = resolvedNextEpisodeId
        val navigate = inputs.onNavigateToEpisode
        if (nextId == null || navigate == null) {
            // Nothing to advance to. Latch the card dismissed so the poll loop can't re-show it (and, with it, re-run
            // this countdown) every tick for the rest of the file.
            nextUpDismissed = true
            return
        }
        // R142: a genuinely finished episode is marked played as we advance, so up-next stays correct; a manual skip
        // mid-episode is not (it stays in-progress with its resume sliver). R347 (FR-R347-3) — finished is past 90 % OR
        // past a trusted credits marker: a short episode's card fires at its credits (often ~86 %), and the bare 90 %
        // test left it unticked with a resume point there.
        if (dev.jellystructure.shared.tv.playbackFinished(positionMs, durationMs, segments.creditsStartMs)) store.markWatched(itemId)
        // Latched BEFORE the call: see advanceRequestedForItemId — exactly one advance attempt per episode, never a
        // retry loop.
        bk.advanceRequestedForItemId = itemId
        navigate.invoke(nextId)
    }

    // R111: "Watch credits" — hide the card AND latch it so the near-end poll won't immediately re-show it. It can still
    // re-appear at the true end of the file (the isEnded branch ignores the latch).
    fun stayThrough() { nextUpVisible = false; nextUpDismissed = true; chromeVisible = true; scheduleHide() }

    // R182 (FR-RV-SKIP1-2) — CreditsCardMode.STINGER's primary action. Stinger.atMs is null in every title today (TMDB's
    // during/aftercreditsstinger keywords carry no timestamp); "never blindly skip past it" means we can't just jump to
    // the natural end either, so without a known position this lands 2s before the file's actual end — past the long
    // credits ROLL (whose start we DO know) while still leaving the tail of the file, where a mid/post-credits scene
    // always sits, to play out normally.
    fun skipToScene() {
        val target = segments.stinger?.atMs ?: (durationMs - 2_000L).coerceAtLeast(positionMs)
        player.seekTo(target)
        positionMs = target
        nextUpVisible = false
        wake()
    }

    // R182 (FR-RV-SKIP1-2) — CreditsCardMode.SKIP_CREDITS's primary action: a movie or a series' last episode with no
    // stinger has nothing to seek forward TO, so this is simply "I'm done".
    fun skipCredits() {
        nextUpVisible = false
        if (dev.jellystructure.shared.tv.playbackFinished(positionMs, durationMs, segments.creditsStartMs)) store.markWatched(itemId)   // R347
        onBack()
    }

    /** The credits card's primary action, by mode. */
    fun creditsPrimary() {
        when (creditsCardMode) {
            CreditsCardMode.STINGER -> skipToScene()
            CreditsCardMode.NEXT_EPISODE -> advanceNext()
            CreditsCardMode.SKIP_CREDITS -> skipCredits()
        }
    }

    // R182 (FR-RV-SKIP1-1) — fires on an explicit OK press on the pill AND from the countdown-elapsed auto-trigger in
    // Auto mode — same action either way.
    fun skipIntro() {
        val end = segments.introEndMs ?: return
        player.seekTo(end)
        positionMs = end
        bk.skipIntroCountingDown = false
        bk.skipIntroCountdownDone = true
        if (focus == PlFocus.SKIP_INTRO) focus = PlFocus.PLAY
        wake()
    }

    fun chooseEpisode() {
        if (focusedEpIdx == currentEpIndex) { epRailOpen = false; return }
        episodes?.getOrNull(focusedEpIdx)?.id?.let { inputs.onNavigateToEpisode?.invoke(it) }
    }

    // ─── Tracks ──────────────────────────────────────────────────────────────

    // R181 — client-local only (never sent to the jellystructure backend), keyed per profile via
    // MultiTokenStore.getActive()?.userId, same store family as the auth-session cache. Read-modify-write against whatever's
    // already persisted so an audio-only (or subtitle-only) pick doesn't clobber the other axis's remembered choice. Off is a
    // real persisted state (subtitlesOff = true), not "no preference" (null subtitleLanguage with subtitlesOff = false).
    fun persistChoice(
        newAudioLanguage: String? = null, newSubtitleLanguage: String? = null, newSubtitlesOff: Boolean? = null,
        // R195 (FR-RV §5.4) — which same-language VERSION, not just the language. Cleared/carried forward alongside its own
        // axis's language (a stale signature must never survive onto a DIFFERENT language than the one it was computed for).
        newAudioVariant: String? = null, newSubtitleVariant: String? = null,
    ) {
        val profileId = MultiTokenStore.getActive()?.userId ?: return
        val seriesKey = inputs.seriesId ?: itemId
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

    // R376 (FR-R376-3) — the audio a player could not select in place is asked of the server instead: the stream cut with
    // that track (R284's restream; on a composed master it becomes the master's default).
    fun restreamForAudio(flatIndex: Int) {
        val wanted = bk.ticketAudio.getOrNull(flatIndex) ?: return
        bk.rearmResolveOnLoad = true
        store.restreamWithSub(itemId, bk.burnedSubIndex ?: -1, player.positionMs, wanted.index)
    }

    // R181 (FR-RV-TRK1) — layered resolution, run once per item the first tick after track discovery completes (see the
    // poll loop): per-series remembered choice → learned global-language preference → source default → first-track/Off.
    // Matches by language (and, since R195, by exact remembered variant signature first — see resolveTrackChoice's doc),
    // never by ExoPlayer track index (indices differ across episodes/files). Scope note: only matches against
    // native/external subtitleTracks, never encodeSubTracks (PGS burn-in) — a remembered language that exists only as a PGS
    // track in this file falls through instead of silently triggering an autoplay transcode; PGS stays a manual pick.
    //
    // R196 (FR-RV-TRK2-1/2/4) — the tier logic lives in the pure, unit-tested top-level resolveTrackChoice(). R246
    // (FR-R246-1/2) — it is handed the TRACK LISTS the calling poll tick read and builds its own groups from them, so no
    // caller can hand it a group list that is one composition behind.
    fun resolveTrackSelection(tickAudio: List<PlayerAudioTrack>, tickSubs: List<PlayerSubtitleTrack>) {
        val profileId = MultiTokenStore.getActive()?.userId
        val seriesKey = inputs.seriesId ?: itemId
        val seriesChoice = profileId?.let { PlaybackPrefsStore.getSeriesChoice(it, seriesKey) }
        val globalChoice = profileId?.let { PlaybackPrefsStore.getGlobalChoice(it) }

        val result = resolveTrackChoice(seriesChoice, globalChoice, tickAudio, tickSubs)
        // R284 (FR-R284-3) — on a single-audio session the player has nothing to select: if the stream does not carry the
        // resolved track, ask the server for it — once per item — else show the truth.
        val carried = carriedAudioPosition(bk.sessionAudio, bk.sessionAudioIndex)
        val wanted = bk.sessionAudio.getOrNull(result.audioIndex)
        if (carried == null) {
            val play = playableAudio(result.audioIndex, tickAudio)   // phase 314b — a copy where the source can't be decoded
            selectedAudio = play
            // R376 (FR-R376-3) — a player that could not switch says so, and the stream is cut with that audio instead,
            // once per item, rather than nothing happening.
            if (!player.selectAudioTrack(play) && bk.audioRestreamTriedFor != itemId) {
                bk.audioRestreamTriedFor = itemId
                bk.startRestreamPending = true   // R290 (FR-R290-4)
                restreamForAudio(play)
            }
        } else if (wanted != null && result.audioIndex != carried && bk.audioRestreamTriedFor != itemId) {
            bk.audioRestreamTriedFor = itemId
            selectedAudio = result.audioIndex
            bk.rearmResolveOnLoad = true
            bk.startRestreamPending = true   // R290 (FR-R290-4) — this stream is not the one the viewer will keep
            store.restreamWithSub(itemId, bk.burnedSubIndex ?: -1, player.positionMs, wanted.index)
        } else {
            selectedAudio = carried
        }
        // R282 (FR-R282-2) — while a subtitle is burned in, nothing automatic may add a text track.
        val subIndex = if (bk.burnedSubIndex != null) -1 else result.subIndex
        player.selectSubtitleTrack(subIndex)
        selectedSub = subIndex
        bk.resolvedTrackSig = trackSetSignature(tickAudio, tickSubs)
        bk.resolvedTrackCount = tickAudio.size + tickSubs.size
        bk.manualPickSinceResolve = false
    }

    // R380 (FR-R380-7) — the pick itself, for [tab] (0 = audio, 1 = subtitles) — the picker's OK and a cast remote's
    // `audio`/`subtitle` command (castChannelVideo) choose through this one door.
    fun applyPick(tab: Int, group: PickerLanguage, version: PickerVersion) {
        bk.manualPickSinceResolve = true   // R246 (FR-R246-5) — a grown track set never overrides a manual pick
        if (tab == 2) {
            // Phase 314c — another picture version: the same item at the playhead from that version's file. The audio and
            // subtitle choices are resolved again from the viewer's remembered choice once the new stream loads.
            val v = bk.videoVersions.getOrNull(version.flatIndex) ?: return
            if (!v.current) {
                bk.rearmResolveOnLoad = true
                store.restreamVersion(itemId, v.id, player.positionMs, bk.burnedSubIndex ?: -1, bk.sessionAudio.getOrNull(selectedAudio)?.index)
            }
            return
        }
        if (tab == 0) {
            persistChoice(newAudioLanguage = group.language, newAudioVariant = version.signature())
            // R284 (FR-R284-2) — a single-audio stream changes audio by restream (keeping the burn-in); re-picking the
            // carried track does nothing. Direct play selects in the player, as always.
            val carried = carriedAudioPosition(bk.sessionAudio, bk.sessionAudioIndex)
            val wanted = bk.sessionAudio.getOrNull(version.flatIndex)
            if (carried == null) {
                val play = playableAudio(version.flatIndex, audioTracks)   // phase 314b
                selectedAudio = play
                if (!player.selectAudioTrack(play)) restreamForAudio(play)   // R376 (FR-R376-3)
            } else if (wanted != null && version.flatIndex != carried) {
                selectedAudio = version.flatIndex
                bk.rearmResolveOnLoad = true
                store.restreamWithSub(itemId, bk.burnedSubIndex ?: -1, player.positionMs, wanted.index)
            }
        } else {
            // R282 (FR-R282-4) — persisted FIRST: an un-burn re-resolves from this choice. (R181: a PGS pick is still worth
            // remembering — it helps other titles and a rewatch where the language exists as a native track — though the
            // resolver never auto-starts a burn-in.)
            val sub = if (group.isOff) null else subVersionOptions.getOrNull(version.flatIndex)
            if (group.isOff) persistChoice(newSubtitlesOff = true)
            else persistChoice(newSubtitleLanguage = group.language, newSubtitlesOff = false, newSubtitleVariant = version.signature())
            when (subPickAction(sub?.deliveryMethod == "encode", sub?.jellyfinStreamIndex, bk.burnedSubIndex)) {
                SubPickAction.NONE -> Unit
                // R56: PGS burn-in — restream with the subtitle baked into the Jellyfin transcode.
                SubPickAction.BURN_IN -> store.restreamWithSub(itemId, sub?.jellyfinStreamIndex ?: -1, player.positionMs, restreamAudioIndex(bk.sessionAudioIndex, bk.ticketAudio, selectedAudio))
                // Off or a text track while one is burned in: only a restream can take it out again.
                SubPickAction.UNBURN -> { bk.rearmResolveOnLoad = true; store.restreamWithSub(itemId, -1, player.positionMs, restreamAudioIndex(bk.sessionAudioIndex, bk.ticketAudio, selectedAudio)) }
                SubPickAction.SELECT -> {
                    selectedSub = if (group.isOff) -1 else version.flatIndex
                    player.selectSubtitleTrack(selectedSub)
                }
            }
        }
    }

    /** One call for what `castSource` shows a cast remote — the closures are built here so the screen body carries none. */
    fun bindCastSource() {
        castSource.title = { itemTitle }
        castSource.kicker = { itemKicker }
        castSource.lists = { castVideoLists(audioGroups, subGroupsWithOff, audioTracks, subVersionOptions, shownAudioNow, shownSubNow) }
        castSource.subSize = { subtitleSize }
        castSource.hasNext = { resolvedNextEpisodeId != null }
        castSource.applyPick = { tab, g, v -> applyPick(tab, g, v) }
        // R245 FR-R245-8 — the remote's S · M · L is applied here, on the TV app, not only reported back; the screen's own effect is phone-only.
        castSource.setSubSize = { subtitleSize = it; player.setSubtitleScale(subtitleScale()) }
        castSource.next = { if (resolvedNextEpisodeId != null) advanceNext() }
    }

    // ─── The picker ──────────────────────────────────────────────────────────

    // R195 §3 — applies whichever version is currently targeted (level-1's implicit single version, or level-2's focused
    // pickerVersionIdx). Never touches pickerOpen/pickerLevel itself — callers (pickerSelect(), touch taps) decide whether
    // picking should close the picker or leave it open.
    fun choosePick() {
        val group = pickerGroups.getOrNull(pickerIdx) ?: return
        val version = group.versions.getOrNull(if (pickerLevel == 1) pickerVersionIdx else 0) ?: return
        applyPick(pickerTab, group, version)
    }

    /** Opens the picker on level 1, on the group that holds the live selection of the current tab. */
    fun openPicker() {
        pickerOpen = true
        pickerLevel = 0
        val currentFlat = if (pickerTab == 0) shownAudioNow else shownSubNow
        pickerIdx = pickerGroups.indexOfFirst { g -> g.versions.any { it.flatIndex == currentFlat } }.coerceAtLeast(0)
    }

    // R195 §A / R197 (FR-RV-PICK1-1) — OK on a level-1 row: a single-version language selects immediately and closes; a
    // multi-version language instead ENTERS level 2, focused on whichever version is already playing. OK on a level-2
    // row: select AND CLOSE, same as level 1. R195 originally shipped level 2 as "select, stay open" so a viewer could
    // audition versions without reopening the picker — reversed by R197 (live report: with the video still covered and
    // nothing on screen distinguishing "applied, still open" from "didn't take", a many-version language felt broken next
    // to a one-version language, which dismisses on the same press).
    fun pickerSelect() {
        val group = pickerGroups.getOrNull(pickerIdx) ?: return
        if (pickerLevel == 0 && group.versions.size > 1) {
            pickerLevel = 1
            val currentFlat = if (pickerTab == 0) shownAudioNow else shownSubNow
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

    // R195 §D — Back in level 2 returns to level 1 (must NOT close the picker); level 1 closes it, unchanged from
    // pre-R195.
    fun pickerBack() {
        if (pickerLevel == 1) pickerLevel = 0 else pickerOpen = false
        wake()
    }

    // R195 bug fix — the exact same tab-switch logic the D-pad's onLeft/onRight already run, extracted so a touch tap on a
    // PickerTab pill (which has no D-pad to trigger onLeft/onRight) can reach it too. Always resets to level 1 (§D) and
    // re-targets the new tab's group containing the live selection for that axis.
    fun pickerTapTab(tab: Int) {
        pickerTab = tab
        pickerLevel = 0
        pickerIdx = if (tab == 2) {
            bk.videoVersions.indexOfFirst { it.current }.coerceAtLeast(0)
        } else if (tab == 0) {
            audioGroups.indexOfFirst { g -> g.versions.any { it.flatIndex == shownAudioNow } }.coerceAtLeast(0)
        } else {
            subGroupsWithOff.indexOfFirst { g -> g.versions.any { it.flatIndex == shownSubNow } }.coerceAtLeast(0)
        }
        wake()
    }

    // ─── The session ─────────────────────────────────────────────────────────

    // Single place that arms a playback session, shared by the initial start and by the return-from-background re-arm
    // (PlayerLifecycleEffect's onForeground) so both hand the store the same providers. durationProvider lets the store
    // take the ≥90% mark-played decision itself if it is closed without an explicit stopSession — see PlayerStore.close().
    fun armSession(id: String, startPositionMs: Long? = null) {
        // Phase 185/R222 (FR-185-4 client half) — a real StreamTicket negotiation starts the instant startSession below is
        // called, whether this is the episode's initial start or a background/foreground re-arm (both reload the player —
        // see RaviloPlayerAndroid.load()'s own _hasRenderedFirstFrame reset), so both get timed the same way from this one
        // chokepoint.
        bk.negotiationStartMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
        // R291 (FR-R291-1) — the FIRST negotiation asks for the audio R181's resolver would pick (series choice, else
        // global choice), so a remembered non-default audio no longer costs a second start.
        val remembered = MultiTokenStore.getActive()?.userId?.let { pid ->
            PlaybackPrefsStore.getSeriesChoice(pid, inputs.seriesId ?: id) ?: PlaybackPrefsStore.getGlobalChoice(pid)
        }
        store.startSession(
            id,
            startPositionMs = startPositionMs,   // R292 (FR-R292-2) — the record's position on a return, else Jellyfin's
            audioLanguage = remembered?.audioLanguage, audioVariant = remembered?.audioVariant,   // R291
            positionProvider = { positionMs },
            isPausedProvider = { !isPlaying },
            durationProvider = { durationMs },
            qoeSnapshotProvider = { player.qoeSnapshot() },  // R216 (FR-R216-4)
            startupMsProvider = { bk.measuredStartupMs },
            failedProvider = { player.playbackFailed },   // R306 (FR-R306-3)
            // DEB-2205 (FR-1, FR-5) — the poll loop keeps bk.watch; the screen only hands the holder over.
            watchProvider = bk::watch,
        )
    }

    /** The item effect: a fresh start for [itemId] (replaceTop keeps this screen across a whole binge). */
    suspend fun startItem() {
        // Bug fix: also reset the next-up card/countdown here, not just nextUpDismissed — the poll loop is gated on
        // loadedForItemId, but this clears any next-up state left over from the outgoing episode the instant a new one
        // starts loading, rather than waiting for the (already-gated) poll loop to notice.
        nextUpDismissed = false  // R111: each episode starts fresh
        nextUpVisible = false
        countdown = bk.skipSecs
        scrubbing = false
        // R184 (FR-RV-POS1-1): positionMs/durationMs are screen-scoped, not per-episode (this composable is reused across
        // the whole binge) — reset them the instant a new episode starts loading so nothing can read the outgoing
        // episode's near-the-end values during the round-trip before its own stream is loaded.
        positionMs = 0L
        durationMs = 0L
        bk.positionKnownForItemId = null
        hasRenderedFirstFrame = false  // R218 — the new episode's own cold start, not the outgoing one's
        isBuffering = false
        isSeeking = false
        bk.measuredStartupMs = null  // R185/R222 — a stale prior episode's number must never carry over
        // R290 — a fresh start for this item: black for R218's 400 ms, then the start screen, until the latch.
        bk.startScreenDue = false
        bk.startRestreamPending = false
        bk.firstFrameAtMs = null
        bk.playHeldForLatch = false
        // R292 (FR-R292-6) — the app composed from saved state with a record for THIS item: start from it, exactly as a
        // return from the background does, and count the restore.
        val resume = inputs.resume
        val restored = resume.recordFor(itemId)?.takeIf { resume.consumeRestored() }
        if (restored != null) {
            bk.playOnLatch = resumePlayIntent(restored, kotlin.time.Clock.System.now().toEpochMilliseconds())
            bk.returnRecord = restored
            bk.reapplyChoicesForItemId = itemId
            selectedAudio = restored.audioIndex
            selectedSub = restored.subIndex
            player.recordRestoredAfterRecreate()
            armSession(itemId, restored.positionMs)
        } else {
            bk.playOnLatch = true
            // 2026-10-05 — a film moved here from the phone at 0:45 began at 0:00: the place it was sent with goes in.
            armSession(itemId, inputs.startAtMs?.takeIf { it > 0 })
        }
        delay(BUFFER_MOMENT_DEBOUNCE_MS)
        bk.startScreenDue = true   // FR-R290-2 — the start screen fades in once, and stays up across a restream
    }

    /** R182 — skip behaviour, resolved in parallel with the session start (never blocks or breaks playback). */
    suspend fun loadConfig() {
        store.getConfig()?.let { cfg ->
            bk.skipIntroMode = cfg.skipIntro
            bk.skipCreditsMode = cfg.skipCredits
            bk.skipSecs = cfg.skipSecs
            bk.autoplayNextEnabled = cfg.autoplayNext
        }
    }

    /** Load the player when the StreamTicket is ready (initial load or R56 restream). */
    fun loadTicket(state: PlayerSessionState) {
        val s = state as? PlayerSessionState.Ready ?: return
        // R271 (FR-R271-2) — a client selects, it never composes. A null stream URL is a real failure and is reported as one
        // rather than papered over with a URL that would not have worked either. Unreachable: PlayerStore rejects a ticket
        // with no stream URL before it can become Ready.
        val streamUrl = s.ticket.hlsUrl ?: return
        // R192/R194 — feed title/episode-kicker/artwork into the OS media session (TV-only; see RaviloPlayer.load doc).
        // Artwork prefers the current episode's SEASON poster over an episode still (season art reads better at
        // media-session size); falls back to the series' own poster when this season has none, then the movie-path
        // `posterUrl` (no `episodes` list at all). `posterUrl` already carries the series' own poster for episodes (threaded
        // from EpisodePlayContext.seriesPosterUrl) or the movie's poster for movies — a single fallback.
        val artworkUrl = resolvePlayerImageUrl(inputs.serverBaseUrl, episodes?.getOrNull(currentEpIndex)?.seasonPosterUrl ?: inputs.posterUrl)
        // 2026-09-24 — a new stream has no frame yet, from THIS moment: the poll tick would otherwise keep the outgoing
        // stream's `true` for up to POLL_MS, and VideoShutter would let the decoder's reconfiguration show through (a green
        // frame with a quarter-size stale picture, soveværelse TV).
        hasRenderedFirstFrame = false
        replacementHeldSinceMs = if (s.replacement) kotlin.time.Clock.System.now().toEpochMilliseconds() else null   // DEB-3103 (FR-6)
        bk.firstFrameAtMs = null
        bk.startRestreamPending = false   // R290 — the restream the resolver asked for is this ticket
        // R290 (FR-R290-3, dev review item 3) — the first position shown is the target, never the poll's 0.
        positionMs = s.ticket.startPositionMs
        // 308 (FR-308-3) — an adaptive stream starts from what this device measured, never from a guess about where it is.
        player.seedBandwidthEstimate(s.ticket.measuredBandwidthBps?.takeIf { s.ticket.adaptive })
        s.ticket.measuredBandwidthBps?.takeIf { it > 0 }?.let { dev.jellystructure.ravilo.ui.seams.LinkEstimate.bps = it }   // DEB-2627 (FR-3) — Linux's live start rung
        // 309 / 313 (FR-313-14) — on our own encoder every rung is already running: a climb waits only the stock buffer.
        dev.jellystructure.ravilo.ui.seams.PlayerLadderHints.oursEncoder = s.ticket.encoder == "ours"
        // R381 (FR-R381-1) — the QoE counters are this item's: a new item resets them, a restream of it keeps them.
        player.beginQoeItem(itemId)
        player.load(streamUrl, s.ticket.startPositionMs, s.ticket.subtitles, s.ticket.audio, title = itemTitle, subtitle = itemKicker, artworkUrl = artworkUrl)
        // R282 (FR-R282-1/-4) — relate the two subtitle mechanisms, here, on every ticket. A burn-in ticket turns the text
        // renderer OFF unconditionally: the reload reuses this ExoPlayer, whose text selection survives load(), so a text
        // track that was on kept rendering over the burned-in one ("two subtitles at once"). An un-burn ticket re-arms the
        // resolver.
        bk.burnedSubIndex = s.ticket.burnedSubtitleIndex
        // R284 (FR-R284-1) — on a single-audio session the ticket, not the player, knows the tracks.
        // R291 (FR-R291-2) — a master with every audio track as a rendition carries no single track: an audio pick is a
        // player track selection, exactly as on direct play, never a restream.
        bk.sessionAudioIndex = s.ticket.audioStreamIndex.takeIf { !s.ticket.directPlay && !s.ticket.audioRenditions }
        bk.videoVersions = s.ticket.versions   // phase 314c
        bk.sessionAudio = if (bk.sessionAudioIndex != null) s.ticket.audio else emptyList()
        bk.ticketAudio = s.ticket.audio   // R376 (FR-R376-3)
        carriedAudioPosition(bk.sessionAudio, bk.sessionAudioIndex)?.let { selectedAudio = it }
        if (s.ticket.burnedSubtitleIndex != null) {
            player.selectSubtitleTrack(-1)
            selectedSub = -1
        } else if (bk.rearmResolveOnLoad) {
            bk.resolvedForItemId = null
        }
        bk.rearmResolveOnLoad = false
        // R292 (FR-R292-5) — a return's ticket comes from the server's rules; the viewer's burn-in or single-audio choice is
        // restored by ONE restream, invisibly under R290's start screen.
        bk.returnRecord?.let { r ->
            bk.returnRecord = null
            val wantAudio = r.sessionAudioIndex
            val needsRestream = (r.burnedSubIndex != null && s.ticket.burnedSubtitleIndex != r.burnedSubIndex) ||
                (wantAudio != null && !s.ticket.directPlay && s.ticket.audioStreamIndex != wantAudio)
            if (needsRestream) {
                bk.startRestreamPending = true
                store.restreamWithSub(itemId, r.burnedSubIndex ?: -1, s.ticket.startPositionMs, wantAudio)
            }
        }
        if (bk.latchedForItemId == itemId) {
            // A restream after the start (a subtitle burn-in, a manual audio pick): as before R290.
            player.play()
            isPlaying = true
            wake()
        } else {
            // R290 (FR-R290-4, dev review item 4) — prepare, resolve, then play: ExoPlayer renders the first frame on READY
            // whether or not it is playing, and the tracks arrive at prepare, so the resolver's verdict and the frame are
            // both in hand before a sample is heard. The poll loop's latch calls play() — a stream the resolver sends back
            // is never played at all.
            bk.playHeldForLatch = true
        }
        bk.loadedForItemId = itemId   // Bug fix: see loadedForItemId's declaration comment in PlayerBookkeeping.
    }

    // ─── The poll loop ───────────────────────────────────────────────────────

    /**
     * Polls the player every [POLL_MS]. Launched once per screen and deliberately never restarted across an episode
     * transition (the same `player` instance is reused so the outgoing stream doesn't visibly restart), so it reads the
     * item, the store and the next episode LIVE from [inputs] — a coroutine that captured the first composition's values
     * went permanently stale after the first auto-advance and silently disabled next-up/auto-advance/end-of-stream
     * detection for the rest of the screen's life.
     */
    suspend fun pollLoop() {
        while (true) {
            delay(POLL_MS)
            try {
                pollTick()
            } catch (e: Throwable) {
                // Bug fix: an exception on any single tick (e.g. a transient native-player getter failure) used to kill this
                // whole polling loop for the rest of the PlayerScreen's lifetime — silently ending all next-up/auto-advance/
                // end-of-stream detection with no crash and no visible symptom beyond "playback just stops responding". Keep
                // polling instead of dying on one bad tick.
            }
        }
    }

    private fun pollTick() {
        // Bug fix: gate every next-up/end-of-stream check on the player actually being loaded for the CURRENT itemId —
        // otherwise, right after a manual (or auto) advance, these checks kept reading the OUTGOING episode's near-the-end
        // position/duration during the brief gap before the new episode's stream ticket loads, re-arming next-up and
        // auto-advancing a second time into the episode that had just started.
        val playerLoadedForCurrentItem = bk.loadedForItemId == itemId

        // R184 (FR-RV-POS1-1/2): same staleness this loop already guards next-up/end-of-stream checks against also applied to
        // positionMs/durationMs themselves — only update, and only mark them known-fresh for the item, once the player is
        // actually loaded for it.
        if (playerLoadedForCurrentItem) {
            positionMs = player.positionMs
            durationMs = player.durationMs
            bk.positionKnownForItemId = itemId
            // R218 — same staleness guard as positionMs/durationMs above: only read the live player's signal once it is
            // actually loaded for THIS item, or a stale hasRenderedFirstFrame=true from the outgoing episode could suppress
            // moment B here.
            val renderedNow = player.hasRenderedFirstFrame
            // Phase 185/R222 (FR-185-4 client half) — the false→true transition this negotiation was timing. Consumed
            // (negotiationStartMs cleared) so a later re-arm's own transition is never mistaken for this one, and a session
            // that never renders never reports.
            if (renderedNow && !hasRenderedFirstFrame) {
                bk.negotiationStartMs?.let { start ->
                    bk.measuredStartupMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - start
                }
                bk.negotiationStartMs = null
                bk.firstFrameAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds()   // R290
            }
            hasRenderedFirstFrame = renderedNow
            if (renderedNow) replacementHeldSinceMs = null
            isBuffering = player.isBuffering
            isSeeking = player.isSeeking
        }
        bufferedMs = player.bufferedMs
        isPlaying = player.isPlaying
        // DEB-2205 (FR-1, FR-5) — what the stop measure reads: this loop's own values, never the engine from another thread.
        bk.watchState = PlayerWatch(
            positionMs, isPlaying, isBuffering || isSeeking, hasRenderedFirstFrame,
            if (bufferedMs > 0) (bufferedMs - positionMs).coerceAtLeast(0) else -1L, videoOutputRecovering,
        )
        audioTracks = sessionAudioTracks(bk.sessionAudio, bk.sessionAudioIndex, player.audioTracks)
        subtitleTracks = player.subtitleTracks

        // R181 — resolve once per item, the first tick after the (now-current) stream's tracks are actually discovered.
        // Gated on playerLoadedForCurrentItem for the same reason as the next-up checks below: right after an advance,
        // audioTracks/subtitleTracks can still briefly reflect the OUTGOING episode until the new load swaps in.
        // R246 (FR-R246-2) — resolved against the exact lists this tick read, never against groups composed earlier.
        val tickAudio = audioTracks
        val tickSubs = subtitleTracks
        // R292 (FR-R292-5) — the viewer's own choice, re-applied to the NEW engine once its tracks are known; the resolver
        // is not re-run for this item (it already ran before the background).
        if (playerLoadedForCurrentItem && bk.reapplyChoicesForItemId == itemId && tickAudio.isNotEmpty()) {
            bk.reapplyChoicesForItemId = null
            if (carriedAudioPosition(bk.sessionAudio, bk.sessionAudioIndex) == null) player.selectAudioTrack(selectedAudio)
            player.selectSubtitleTrack(if (bk.burnedSubIndex != null) -1 else selectedSub)
        }
        if (playerLoadedForCurrentItem && bk.resolvedForItemId != itemId && tickAudio.isNotEmpty()) {
            resolveTrackSelection(tickAudio, tickSubs)
            bk.resolvedForItemId = itemId
        } else if (playerLoadedForCurrentItem && bk.resolvedForItemId == itemId && !bk.manualPickSinceResolve && selectedSub == -1 && bk.burnedSubIndex == null) {
            // R246 (FR-R246-5) — the set grew after the first resolve: run once more, keyed on the set's signature, only
            // while nothing is selected that a grown set could change.
            val sig = trackSetSignature(tickAudio, tickSubs)
            if (bk.resolvedTrackSig != null && sig != bk.resolvedTrackSig && tickSubs.size + tickAudio.size > bk.resolvedTrackCount) {
                resolveTrackSelection(tickAudio, tickSubs)
            }
        }

        // R290 (FR-R290-1/4) — the latch: the first frame of the stream the viewer will keep. Opens once per item, AFTER
        // this tick's resolver ran (so a restream it asked for is already pending); the held play() runs, the chrome is
        // raised and its hide timer armed — here, and never on a load.
        if (playerLoadedForCurrentItem && bk.latchedForItemId != itemId) {
            val renderedFor = bk.firstFrameAtMs?.let { kotlin.time.Clock.System.now().toEpochMilliseconds() - it } ?: 0L
            if (startLatchOpens(sessionState is PlayerSessionState.Ready, hasRenderedFirstFrame, bk.resolvedForItemId == itemId, bk.startRestreamPending, renderedFor)) {
                bk.latchedForItemId = itemId
                // R292 (open question 2) — a return after more than 30 minutes lands paused at the position.
                if (bk.playHeldForLatch) { if (bk.playOnLatch) { player.play(); isPlaying = true }; bk.playHeldForLatch = false }
                wake()
            }
        }

        // Credits card trigger (R111/R182 FR-RV-SKIP1-2): the real creditsStartMs when known, else R384 (FR-R384-2) — 1 s
        // before the end (`NEXT_UP_NO_MARKER_MS`). The countdown then runs its full length over the finished picture:
        // nothing below ends or restarts it when the file ends, and the natural-end trigger starts it if the last second
        // was never seen. A movie/last-episode gets the same card, with a Skip credits action, instead of being silently
        // kicked out at the natural end.
        // Bug fix (auto-advance retry loop): only a marker that plausibly belongs to THIS stream is trusted — positive,
        // inside the known duration, and in its back half (see CREDITS_MARKER_MIN_FRACTION). Anything else falls back to the
        // end-of-file heuristic rather than declaring the episode finished seconds after it started.
        val creditsStart = dev.jellystructure.shared.tv.trustedCreditsStartMs(segments.creditsStartMs, durationMs)
        val creditsReached = if (creditsStart != null) positionMs >= creditsStart
            else durationMs > 0 && (durationMs - positionMs) in 1..dev.jellystructure.shared.tv.NEXT_UP_NO_MARKER_MS
        val advanceAlreadyRequested = bk.advanceRequestedForItemId == itemId
        // R230 (FR-R230-1) — Skip Credits: Off means no mid-playback interruption at all, so this early trigger (segment
        // creditsStartMs, or R384's last second for an unscanned title) is suppressed entirely while Off. The real end of
        // file (below) becomes the only trigger point in that mode.
        if (playerLoadedForCurrentItem && creditsReached && !advanceAlreadyRequested &&
            !nextUpVisible && !nextUpDismissed && !player.isEnded &&
            bk.skipCreditsMode != dev.jellystructure.shared.tv.SkipMode.OFF) {
            nextUpVisible = true
            nuFocus = nextUpStartsOn(bk.viewerSeek)   // R363 (FR-R363-6)
        }

        // R381 (owner, 2026-10-08) — preload the next episode at the credits marker (or 60 s before the end with no trusted
        // marker), autoplay on or off; a seek back out of the credits drops it again.
        // R384 (FR-R384-6) — never before half way: on a 1-minute episode "60 s before the end" was its first second, and the
        // preload competed with the episode's own start.
        val prefetchAt = creditsStart ?: maxOf(durationMs - NEXT_PREFETCH_BEFORE_END_MS, durationMs / 2)
        // The live next episode and the live store, never the ones this loop was launched with: an auto-advance swaps in a
        // new episode and a new PlayerStore (closing the old one, its scope cancelled). Holding the first episode's values,
        // every later episode prepared nothing — the call went to a closed store (found on the Pixel 2026-10-08).
        val nextForPrefetch = inputs.nextEpisodeId?.takeIf { it != itemId }
        if (playerLoadedForCurrentItem && nextForPrefetch != null && durationMs > 0) {
            if (bk.prefetchedForItemId != itemId && positionMs >= prefetchAt) {
                bk.prefetchedForItemId = itemId
                store.prepareNext(nextForPrefetch)
            } else if (bk.prefetchedForItemId == itemId && positionMs < prefetchAt - 5_000) {
                bk.prefetchedForItemId = null
                store.discardNext()
            }
        }

        // Natural end — safety net for a title whose duration/creditsStartMs never satisfied the check above (e.g. bad
        // metadata); still shows the card rather than exiting silently. Bug fix: this needs the same !nextUpDismissed guard
        // the trigger above has, or a movie/last-episode's credits card becomes un-dismissable.
        // R230 (FR-R230-2) — while Off, this is the ONLY trigger left, and it no longer just shows the card: a next episode
        // with Autoplay next episode on still gets the ordinary Next-Episode countdown here (re-anchored to the real end of
        // the file, per the owner's decision that Off must not disable Autoplay); anything else — no next episode, or
        // Autoplay off — exits silently via the same fallback skipCredits() already uses, with no card ever shown.
        if (playerLoadedForCurrentItem && player.isEnded && !advanceAlreadyRequested &&
            !nextUpVisible && !nextUpDismissed) {
            if (bk.skipCreditsMode == dev.jellystructure.shared.tv.SkipMode.OFF) {
                if (resolvedNextEpisodeId != null && bk.autoplayNextEnabled) {
                    nextUpVisible = true; nuFocus = NuFocus.PLAY
                } else {
                    skipCredits()
                }
            } else {
                nextUpVisible = true; nuFocus = NuFocus.PLAY
            }
        }

        // R372 (FR-R372-10, amendment 2026-10-12) — the item ran out and there is nothing to advance to: the session ends
        // here and the heartbeat stops. Deliberately NOT guarded on `nextUpVisible` the way the branch above is: with the
        // credits card already up from the early trigger, the end of the file was never acted on at all, and the 10 s
        // heartbeat went on posting `paused` at the end position for as long as the app lived. The card is left exactly
        // where it is — the spec says it may stay — and the picture with it (see PlayerStore.endOfPlay).
        if (bk.endOfPlay.observe(
                itemId = itemId,
                loaded = playerLoadedForCurrentItem,
                ended = player.isEnded,
                // advanceNext's own test, with the live values.
                hasNext = nextForPrefetch != null && inputs.onNavigateToEpisode != null,
                advanceRequested = advanceAlreadyRequested,
            )
        ) {
            store.endOfPlay(positionMs, durationMs)
        }

        // R182 (FR-RV-SKIP1-1) — Skip Intro pill. Entering [introStartMs, introEndMs) for the first time this episode arms
        // the brief grace-window countdown (skipIntroCountingDown); beyond that the pill's visibility rides chromeVisible
        // (see skipIntroPillVisible) rather than anything tracked here. Leaving the window (playback moved past introEndMs,
        // or a fresh episode with no intro at all) resets the once-per-window latch so a later intro can arm again.
        val iStart = segments.introStartMs
        val iEnd = segments.introEndMs
        val insideIntro = playerLoadedForCurrentItem && iStart != null && iEnd != null && iEnd > iStart &&
            positionMs in iStart until iEnd
        bk.skipIntroInside = insideIntro   // R363 — read by the key handlers' `skipIntroArmed`
        if (insideIntro && bk.skipIntroMode != dev.jellystructure.shared.tv.SkipMode.OFF &&
            !bk.skipIntroCountingDown && !bk.skipIntroCountdownDone) {
            bk.skipIntroCountingDown = true
            // R363 (FR-R363-2/3) — the intro began: the pill's focus effect sees this edge once.
            bk.skipIntroArmingEdge = true
            bk.skipIntroReturn = null
            bk.skipIntroHadFocus = false
            bk.skipIntroTouchedOther = false
        }
        if (!insideIntro && (bk.skipIntroCountingDown || bk.skipIntroCountdownDone)) {
            bk.skipIntroCountingDown = false
            bk.skipIntroCountdownDone = false
            bk.skipIntroArmingEdge = false
            bk.skipIntroHadFocus = false
            if (focus == PlFocus.SKIP_INTRO) focus = PlFocus.PLAY
        }
        bk.viewerSeek = false   // R363 (FR-R363-6) — only the tick right after a skip/scrub counts
    }

    // ─── The timers ──────────────────────────────────────────────────────────

    /**
     * Auto-hide chrome timer (restarted every time chromeRevision bumps).
     * R244 (FR-R244-3) — a handset hides after 3 000 ms, holds open while a sheet is up or a seek drag is in progress, keeps
     * its chrome while paused, and never runs while locked (the lock overlay is what shows then). The TV's 3 600 ms rule is
     * unchanged.
     */
    suspend fun chromeHideTimer() {
        if (chromeRevision == 0L) return
        delay(if (handset) HANDSET_CHROME_HIDE_MS else PLAYER_CHROME_HIDE_MS)
        // R350 (FR-R350-7) — the TV keeps its chrome while paused (and while scrubbing) too; the film going from
        // paused to playing re-arms the hide by any road (ChromeHideEffect keys on isPlaying as well as the wake).
        val hold = scrubbing || locked || !isPlaying
        if (!pickerOpen && !nextUpVisible && !epRailOpen && !hold) hideChrome()
    }

    // R208 — auto-close the episode rail after inactivity. chromeRevision bumps on every D-pad input via wake(), so this
    // naturally restarts while the viewer is actively browsing episodes and only fires after a real idle stretch. Mirrors
    // onUp's own manual-close behavior (epRailOpen = false; scheduleHide()) so the ambient chrome-hide timer resumes normally
    // once the rail's own guard is gone.
    suspend fun epRailHideTimer() {
        if (!epRailOpen) return
        delay(EPRAIL_HIDE_MS)
        epRailOpen = false
        scheduleHide()
    }

    // R218 (FR-R218-2) — ~400ms debounce before ANY presentation appears (a direct play that starts immediately must show
    // nothing at all); clearing is immediate — a wait that just ended should stop being shown right away, not linger for its
    // own debounce. Keyed on the raw (undebounced) moment so a rapid COLD→SEEK→COLD flicker restarts the delay each time
    // rather than showing a stale one.
    suspend fun settleBufferMoment(raw: PlBufferMoment) {
        if (raw == PlBufferMoment.NONE) {
            displayedBufferMoment = PlBufferMoment.NONE
        } else {
            delay(bufferMomentDebounceMs(raw))   // DEB-3102 (FR-5) — a stall waits 2 s, the cold start and a seek 400 ms
            displayedBufferMoment = raw
        }
    }

    // R218 (FR-R218-3) — "a wait that passes 60 seconds is pathological... at that point Moment C dims further and raises
    // the centre spinner. The wording does NOT change." Stall only.
    suspend fun deepenStall() {
        bk.stallDeepened = false
        if (displayedBufferMoment == PlBufferMoment.STALL) {
            delay(BUFFER_MOMENT_DEEPEN_MS)
            bk.stallDeepened = true
        }
    }

    // R363 (FR-R363-2) — the pill takes focus only when it appears on its own (the countdown's arming edge with the controls
    // hidden), never because the viewer is already using the controls. It still releases focus the instant it is gone (so a
    // later Select never dispatches on a control that's no longer shown) — back to where it was reached from, and remembering
    // that it had focus (FR-R363-3).
    fun skipIntroPillChanged(visible: Boolean) {
        if (visible) {
            if (bk.skipIntroArmingEdge) {
                bk.skipIntroArmingEdge = false
                if (skipIntroGrabsFocus(armingEdge = true, chromeVisible = chromeVisible)) { focus = PlFocus.SKIP_INTRO; bk.skipIntroReturn = null }
            }
        } else if (focus == PlFocus.SKIP_INTRO) {
            bk.skipIntroHadFocus = true
            focus = skipIntroDownTarget(bk.skipIntroReturn)
        }
    }

    // R157 (FR-R157-3.2) — cursor auto-hides after a couple of seconds of no pointer movement during playback (no-op on
    // Android/TV); reappears immediately on the next move via the restart.
    suspend fun cursorHideTimer() {
        dev.jellystructure.ravilo.ui.seams.setPointerCursorHidden(false)
        delay(CURSOR_HIDE_MS)
        if (isPlaying) dev.jellystructure.ravilo.ui.seams.setPointerCursorHidden(true)
    }

    // Next-up countdown.
    // R182 (FR-RV-SKIP1-2): "the auto-advance countdown ring appears ONLY in the Next Episode case" — the stinger case pauses
    // auto-skip entirely (no countdown) and skip-credits simply waits for input.
    suspend fun nextUpCountdown() {
        if (!nextUpVisible || creditsCardMode != CreditsCardMode.NEXT_EPISODE) return
        countdown = bk.skipSecs
        repeat(bk.skipSecs) {
            delay(1_000)
            countdown--
        }
        // R182 (dev-review addendum §2) — autoplayNext gates only the countdown's OWN auto-invocation; a manual pick (Select
        // on the PLAY button, the transport's Next Episode control, or MediaKey.NEXT) still calls advanceNext() directly
        // through their own existing call sites. With autoplayNext off the card simply sits at 0 waiting for one of those.
        if (nextUpVisible && bk.autoplayNextEnabled) advanceNext()
    }

    // Bug fix (auto-advance retry loop): an advance we requested but that never took effect used to be invisible — the
    // credits card simply came back and tried again, forever, on an episode that had already finished playing. A successful
    // advance swaps itemId within a frame, so if we are still on the same item after this grace period the navigation
    // genuinely failed: leave the player (Back is always meaningful — see the Ravilo constitution) instead of retrying or
    // freezing on the last frame.
    suspend fun advanceTimeout() {
        val requested = bk.advanceRequestedForItemId ?: return
        delay(ADVANCE_TIMEOUT_MS)
        if (itemId == requested) onBack()
    }

    // R182 — Skip Intro pill's own brief grace-window countdown (FR-RV-SKIP1-1): governs the pill's INITIAL visibility only
    // (see skipIntroPillVisible, which takes over via chromeVisible once this elapses) — Auto mode additionally seeks past the
    // intro once the countdown runs out, unless the viewer already pressed OK sooner (skipIntro() sets
    // skipIntroCountingDown = false itself).
    suspend fun skipIntroCountdown() {
        if (!bk.skipIntroCountingDown) return
        bk.skipIntroCountdownSecs = bk.skipSecs
        repeat(bk.skipSecs) {
            delay(1_000)
            bk.skipIntroCountdownSecs--
        }
        // Cancelled (not reaching here) if skipIntro() already flipped skipIntroCountingDown to false — same
        // cancel-on-key-change idiom the next-up countdown effect relies on.
        bk.skipIntroCountingDown = false
        bk.skipIntroCountdownDone = true
        if (bk.skipIntroMode == dev.jellystructure.shared.tv.SkipMode.AUTO) skipIntro()
    }

    suspend fun pauseFlashDismiss() {
        if (!bk.pauseFlash) return
        delay(550)
        bk.pauseFlash = false
    }

    // ─── The lifecycle ───────────────────────────────────────────────────────

    // R292 (FR-R292-2, dev review item 3) — the record is captured from the ENGINE (exact, not a tick stale) under R184's
    // guard: during a binge replaceTop the engine may already be loading the next episode, and (episode 2, position of
    // episode 1) is that bug in a new place — then nothing is recorded and nothing is reported. Stop and record carry the same
    // number, so they cannot disagree.
    fun onBackground(wasPlaying: Boolean) {
        val positionIsFresh = bk.loadedForItemId == itemId && bk.positionKnownForItemId == itemId
        val enginePos = if (positionIsFresh) player.positionMs else 0L
        if (positionIsFresh) inputs.resume.capture(ResumeRecord(
            itemId = itemId, positionMs = enginePos, playIntent = wasPlaying,
            awayAtMs = kotlin.time.Clock.System.now().toEpochMilliseconds(),
            audioIndex = selectedAudio, subIndex = selectedSub, burnedSubIndex = bk.burnedSubIndex,
            sessionAudioIndex = bk.sessionAudioIndex, subtitleScale = subtitleScale(),
            title = itemTitle, kicker = itemKicker, seriesId = inputs.seriesId, originalLanguage = inputs.originalLanguage,
            posterUrl = inputs.posterUrl, logoUrl = inputs.logoUrl, logoInk = inputs.logoInk, seriesName = inputs.seriesName,
            nextEpId = inputs.nextEpisodeId, nextEpLabel = inputs.nextEpisodeLabel, nextEpTitle = inputs.nextEpisodeTitle,
            displayName = inputs.resume.record?.displayName ?: "",
            startOver = store.startOver, shuffle = store.shuffle,   // R343
        ))
        store.stopSession(enginePos, if (positionIsFresh) durationMs else 0L)
    }

    // R292 (FR-R292-3) — coming back is a START, not a re-attach: a new engine, a fresh ticket cut at the record's position,
    // R290's one start moment (the latch is re-armed), play only if the intent says so — and never after the screen went off
    // (FR-R292-8a, found live 2026-10-09).
    fun onForeground(afterStandby: Boolean) {
        val r = inputs.resume.recordFor(itemId)
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        bk.playOnLatch = resumePlayOn(r, now, afterStandby)
        bk.returnRecord = r
        bk.reapplyChoicesForItemId = itemId
        bk.latchedForItemId = null
        isPlaying = false
        armSession(itemId, r?.positionMs)
    }

    /** FR-R244-10 — the caption renderer's scale for the S · M · L the viewer chose. */
    fun subtitleScale(): Float = when (subtitleSize) { 'S' -> 0.85f; 'L' -> 1.25f; else -> 1f }

    // R245 (FR-R245-4) — a NEW connection while this player is up is the hand-off; a session that was already connected when
    // the player opened is not (the app routes play to the remote in that case).
    fun castLinkChanged(castLink: CastLinkState, castHandoff: ((Long) -> Unit)?) {
        val was = bk.castLinkSeen
        bk.castLinkSeen = castLink
        if (handset && castLink == CastLinkState.CONNECTED && was != CastLinkState.CONNECTED && castHandoff != null) {
            player.pause(); isPlaying = false
            castHandoff(positionMs)
        }
    }

    // ─── Back and the keys ───────────────────────────────────────────────────

    // R260 (FR-R260-1) — the TV remote/keyboard entrance (dpadFocusable's onBack) and the phone's gesture/button entrance
    // (playerBackGesture — Android's system back never arrives as a KeyEvent) run the exact same decision.
    fun playerBack() {
        when {
            // R290 (FR-R290-5) — Back while nothing is playing yet (negotiating, or the start screen) leaves at once.
            startPhaseNow != StartPhase.PLAYING || sessionState !is PlayerSessionState.Ready || displayedBufferMoment == PlBufferMoment.COLD -> onBack()
            // R295 (FR-R295-2) — a phone's sheet sits over the chrome it was opened from; Back out of it goes back to the
            // picture, not to the chrome (which cost a fourth Back to leave from the versions list). The TV keeps its focus on
            // Audio & Subs, as before.
            pickerOpen && handset && pickerLevel == 0 -> { pickerOpen = false; hideChrome() }
            pickerOpen    -> pickerBack()
            epRailOpen    -> { epRailOpen = false; wake() }
            nextUpVisible -> stayThrough()
            scrubbing     -> { scrubbing = false; wake() }
            // R112: if the controls are showing, Back just hides them → fullscreen video. Only Back with nothing on screen
            // leaves the player (so it takes two presses).
            chromeVisible -> hideChrome()
            else          -> onBack()
        }
    }

    fun dpadLeft() {
        // R329 (FR-R329-6) — on the Mac the arrows seek by the TV's steps; next-up, the rail and the picker keep them for
        // moving.
        if (dev.jellystructure.ravilo.ui.seams.playerArrowsSeek && !nextUpVisible && !epRailOpen && !pickerOpen) { skip(-SKIP_BACK_MS); return }
        // R251 / R350 (FR-R350-7) — captured before wake(), like onSelect's. Left/Right/Up act on the first press from the
        // remembered control; see `dpadRevealsOnly`.
        val revealOnly = dpadRevealsOnly(PlayerDpadKey.LEFT, chromeVisible, focus, nextUpVisible, epRailOpen, pickerOpen)
        wake()
        if (!revealOnly && focus != PlFocus.SKIP_INTRO) bk.skipIntroTouchedOther = true   // R363
        if (!revealOnly) when {
            nextUpVisible -> nuFocus = NuFocus.PLAY
            epRailOpen -> focusedEpIdx = (focusedEpIdx - 1).coerceAtLeast(0)
            // R195 §D — switching tab always resets to level 1, focused on whichever group currently contains the live
            // selection.
            pickerOpen -> pickerTapTab(if (pickerTab == 2) 1 else 0)   // phase 314c — Picture sits right of Subtitles
            focus == PlFocus.SEEK_BAR -> {
                if (!scrubbing) { scrubbing = true; scrubPos = positionMs }
                scrubPos = (scrubPos - scrubStep()).coerceAtLeast(0L)
            }
            // R363 (review item 3) — Left/Right on the pill do nothing (it is not in the order).
            focus == PlFocus.SKIP_INTRO -> {}
            else -> {
                val order = transportOrder(resolvedNextEpisodeId != null, TvSpeakers.available, player.pictureInPictureAvailable(), PlayerFullscreen.available)
                val idx = order.indexOf(focus)
                if (idx > 0) focus = order[idx - 1]
            }
        }
    }

    fun dpadRight() {
        if (dev.jellystructure.ravilo.ui.seams.playerArrowsSeek && !nextUpVisible && !epRailOpen && !pickerOpen) { skip(SKIP_FWD_MS); return }   // R329
        val revealOnly = dpadRevealsOnly(PlayerDpadKey.RIGHT, chromeVisible, focus, nextUpVisible, epRailOpen, pickerOpen)  // R251
        wake()
        if (!revealOnly && focus != PlFocus.SKIP_INTRO) bk.skipIntroTouchedOther = true   // R363
        if (!revealOnly) when {
            nextUpVisible -> nuFocus = NuFocus.STAY
            epRailOpen -> episodes?.let { focusedEpIdx = (focusedEpIdx + 1).coerceAtMost(it.size - 1) }
            pickerOpen -> pickerTapTab(if (pickerTab >= 1 && bk.videoVersions.size >= 2) 2 else 1)   // phase 314c
            focus == PlFocus.SEEK_BAR -> {
                if (!scrubbing) { scrubbing = true; scrubPos = positionMs }
                scrubPos = (scrubPos + scrubStep()).coerceAtMost(durationMs)
            }
            focus == PlFocus.SKIP_INTRO -> {}   // R363 (review item 3)
            else -> {
                val order = transportOrder(resolvedNextEpisodeId != null, TvSpeakers.available, player.pictureInPictureAvailable(), PlayerFullscreen.available)
                val idx = order.indexOf(focus)
                if (idx < order.lastIndex) focus = order[idx + 1]
            }
        }
    }

    fun dpadUp() {
        val revealOnly = dpadRevealsOnly(PlayerDpadKey.UP, chromeVisible, focus, nextUpVisible, epRailOpen, pickerOpen)  // R251
        // R363 (FR-R363-3) — Up that wakes hidden controls inside the armed intro offers the skip first.
        val wakeTo = if (chromeVisible) null else skipIntroWakeFocus(PlayerDpadKey.UP,
            skipIntroArmed(bk.skipIntroInside, bk.skipIntroMode, pickerOpen, nextUpVisible, epRailOpen), bk.skipIntroHadFocus, bk.skipIntroTouchedOther)
        wake()
        if (wakeTo != null) { focus = wakeTo; bk.skipIntroReturn = null }
        else if (!revealOnly) when {
            epRailOpen -> { epRailOpen = false; scheduleHide() }
            // R195 §D — Up/Down moves within whichever level is active.
            pickerOpen -> if (pickerLevel == 1) { if (pickerVersionIdx > 0) pickerVersionIdx-- }
                          else { if (pickerIdx > 0) pickerIdx-- }
            nextUpVisible -> {}
            // R363 (FR-R363-1) — the seek bar, Audio & Subs and Next reach the pill above them.
            else -> skipIntroUpTarget(focus, skipIntroPillVisible)?.let { t ->
                if (t == PlFocus.SKIP_INTRO) bk.skipIntroReturn = focus else bk.skipIntroTouchedOther = true
                focus = t
            }
        }
    }

    fun dpadDown() {
        val revealOnly = dpadRevealsOnly(PlayerDpadKey.DOWN, chromeVisible, focus, nextUpVisible, epRailOpen, pickerOpen)  // R251
        wake()
        if (!revealOnly) when {
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
            // R363 (FR-R363-1) — back to the control the pill was reached from (Play if it took focus itself).
            focus == PlFocus.SKIP_INTRO -> focus = skipIntroDownTarget(bk.skipIntroReturn)
            episodes != null -> { epRailOpen = true; chromeVisible = true }
            else -> {}
        }
    }

    fun dpadSelect() {
        // R178 (FR-RV-SEL1-2): captured *before* wake() (which flips chromeVisible to true unconditionally) — this is the
        // only way to know whether chrome was actually hidden when Select was pressed. Defense in depth alongside
        // hideChrome() (FR-RV-SEL1-1): even if some future path hides chrome without resetting `focus`, Select still can't
        // re-trigger a hidden control's action while nothing is visibly focused.
        //
        // Bug fix: the Skip Intro pill (skipIntroPillVisible) and the Next-up/credits card (nextUpVisible) both render
        // independent of chromeVisible by design — chrome can still be auto-hidden (CHROME_HIDE_MS) when either shows, since
        // neither forces chromeVisible = true. The plain !chromeVisible check treated that as "everything's hidden, just
        // wake" and swallowed the real Select into a togglePlay(), even though the pill/card was visibly focused on screen
        // the whole time. Both are genuinely interactive whenever they're showing, regardless of chrome.
        // R251 (FR-R251-1) — the same rule the four direction keys now use.
        // R363 (FR-R363-4) — inside the armed intro, OK on a hidden screen reveals the controls with the pill focused (a
        // second OK skips) instead of pausing.
        val hidden = hiddenSelect(
            dpadRevealsOnly(PlayerDpadKey.SELECT, chromeVisible, focus, nextUpVisible, epRailOpen, pickerOpen),
            skipIntroArmed(bk.skipIntroInside, bk.skipIntroMode, pickerOpen, nextUpVisible, epRailOpen),
        )
        wake()
        if (hidden == HiddenSelect.REVEAL_TO_PILL) { focus = PlFocus.SKIP_INTRO; bk.skipIntroReturn = null }
        else if (hidden == HiddenSelect.PLAY_PAUSE) {
            // FR-RV-SEL1-3: togglePlay() already calls wake(), so chrome is revealed too.
            // R350 (FR-R350-7) — and the highlight lands on Play, the control OK just acted as.
            focus = PlFocus.PLAY
            togglePlay()
        } else when {
            nextUpVisible -> {
                if (nuFocus == NuFocus.STAY) stayThrough() else creditsPrimary()
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
            focus == PlFocus.TRACKS    -> openPicker()
            focus == PlFocus.NEXT_EP   -> advanceNext()
            focus == PlFocus.SPEAKERS  -> TvSpeakers.open = true
            focus == PlFocus.PIP       -> player.togglePictureInPicture()   // R376 (FR-R376-8)
            focus == PlFocus.FULLSCREEN -> togglePlayerFullscreen()   // R376 (FR-R376-4b)
            focus == PlFocus.BACK      -> onBack()
            else -> {}
        }
    }

    // R44: physical remote / keyboard transport keys → playback actions, regardless of which on-screen control is focused and
    // even when the chrome is hidden.
    fun onMediaKey(mk: MediaKey) {
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
    }

    /**
     * R157 (FR-R157-2.4) — on web, a click on empty space toggles chrome instead of activating the focused control (the web
     * convention; a click has no D-pad "focus" concept to act on). R376 (FR-R376-4b) — and a second click on the picture
     * within a double-click's span toggles full screen where the page can (a control that took the click never reaches this
     * root).
     */
    fun pictureTap() {
        if (PlayerFullscreen.available && bk.pictureClickIsDouble(kotlin.time.Clock.System.now().toEpochMilliseconds())) togglePlayerFullscreen()
        else if (chromeVisible) hideChrome() else wake()
    }

    /** R157 — PlayerChrome is stateless: it reports which logical control was clicked and this does the work. */
    fun controlClicked(clicked: PlFocus) {
        wake()
        when (clicked) {
            PlFocus.BACK      -> onBack()
            PlFocus.SKIP_BACK -> skip(-SKIP_BACK_MS)
            PlFocus.PLAY      -> togglePlay()
            PlFocus.SKIP_FWD  -> skip(SKIP_FWD_MS)
            PlFocus.TRACKS    -> openPicker()
            PlFocus.NEXT_EP   -> advanceNext()
            PlFocus.SPEAKERS  -> TvSpeakers.open = true
            PlFocus.PIP       -> player.togglePictureInPicture()   // R376 (FR-R376-8)
            PlFocus.FULLSCREEN -> togglePlayerFullscreen()   // R376 (FR-R376-4b)
            else -> {}
        }
    }

    /** The handset chrome's side rail. */
    fun handsetRail(item: HandsetRailItem) {
        wake()
        when (item) {
            HandsetRailItem.SUBTITLES -> { pickerOpen = true; pickerTapTab(1) }
            HandsetRailItem.NEXT -> advanceNext()
            HandsetRailItem.EPISODES -> { epRailOpen = true; chromeVisible = true }
            HandsetRailItem.LOCK -> { locked = true; tick(); hideChrome() }
            HandsetRailItem.GUIDE -> {}
        }
    }
}
