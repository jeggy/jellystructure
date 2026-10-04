package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.SkipMode

/*
 * R363 — Skip Intro you can reach with the remote. Every decision is a small top-level function here, outside
 * `PlayerScreen`'s body: that body sits at ART's 256-register limit in the release build (R258), so the player only
 * calls these and keeps its new state in `PlayerBookkeeping`.
 */

/**
 * FR-R363-1 — Up with the controls up: from the seek bar, Audio & Subs or Next (the controls under the pill, which sits
 * bottom-right) to the pill while it is visible; from −10 s, Play and +30 s to the seek bar as today; Up on the seek
 * bar (pill hidden) or on the pill itself does nothing (`null`).
 */
internal fun skipIntroUpTarget(focus: PlFocus, pillVisible: Boolean): PlFocus? = when {
    focus == PlFocus.SKIP_INTRO -> null
    pillVisible && (focus == PlFocus.SEEK_BAR || focus == PlFocus.TRACKS || focus == PlFocus.NEXT_EP) -> PlFocus.SKIP_INTRO
    focus != PlFocus.SEEK_BAR -> PlFocus.SEEK_BAR
    else -> null
}

/** FR-R363-1 — Down from the pill: back to the control it was reached from; Play when the pill took focus itself. */
internal fun skipIntroDownTarget(reachedFrom: PlFocus?): PlFocus = reachedFrom ?: PlFocus.PLAY

/**
 * FR-R363-2 — the pill takes focus only when it appears on its own: at the countdown's arming edge with the controls
 * hidden. A rewind or a scrub into the intro always wakes the controls (`skip()`, `commitScrub()`), so it never grabs.
 */
internal fun skipIntroGrabsFocus(armingEdge: Boolean, chromeVisible: Boolean): Boolean = armingEdge && !chromeVisible

/**
 * Review item 8 — the pill is armed: playback is inside the intro, the mode is Prompt or Auto, and no other modal
 * (the picker, the next-up card, the episode rail) is up.
 */
internal fun skipIntroArmed(insideIntro: Boolean, mode: SkipMode, pickerOpen: Boolean, nextUpVisible: Boolean, epRailOpen: Boolean): Boolean =
    insideIntro && mode != SkipMode.OFF && !pickerOpen && !nextUpVisible && !epRailOpen

/**
 * FR-R363-3 (review item 7) — a key that wakes hidden controls inside the armed window: **Up or OK** puts focus on the
 * pill when the pill had focus when the controls last hid, or the viewer has touched no other control since the intro
 * began. Left/Right keep R350's act-on-first-press (they scrub from a remembered seek bar) and Down only reveals, so
 * they return `null` (the normal path), as does an unarmed window.
 */
internal fun skipIntroWakeFocus(key: PlayerDpadKey, introArmed: Boolean, pillHadFocusWhenHidden: Boolean, touchedOther: Boolean): PlFocus? =
    if (introArmed && (key == PlayerDpadKey.UP || key == PlayerDpadKey.SELECT) && (pillHadFocusWhenHidden || !touchedOther)) PlFocus.SKIP_INTRO
    else null

/** FR-R363-4 — what OK does when it finds the controls hidden. */
internal enum class HiddenSelect { PLAY_PAUSE, REVEAL_TO_PILL, NONE }

/**
 * FR-R363-4 (owner decision 1, amends R178 FR-RV-SEL1-2 and R350 FR-R350-7 for the intro window) — OK on a hidden
 * screen inside the armed intro reveals the controls with the pill focused (a second OK skips) instead of pausing;
 * outside it OK stays R178's play/pause. [wasHidden] is `dpadRevealsOnly(SELECT, …)`; `NONE` = not hidden, so the
 * focused control acts as usual. Play/Pause on the remote is not this (it always toggles).
 */
internal fun hiddenSelect(wasHidden: Boolean, introArmed: Boolean): HiddenSelect = when {
    !wasHidden -> HiddenSelect.NONE
    introArmed -> HiddenSelect.REVEAL_TO_PILL
    else -> HiddenSelect.PLAY_PAUSE
}

/** FR-R363-5 — the countdown ring only where it counts down to something: Auto's automatic skip. */
internal fun skipIntroShowsRing(mode: SkipMode): Boolean = mode == SkipMode.AUTO

/**
 * FR-R363-6 (owner decision 2) — the credits card's first focus: *Watch credits* when the viewer brought it up by
 * skipping or scrubbing into the credits (so the next OK, meant as another skip, does not start the next episode);
 * *Play next* when normal playback reached it.
 */
internal fun nextUpStartsOn(broughtByViewer: Boolean): NuFocus = if (broughtByViewer) NuFocus.STAY else NuFocus.PLAY
