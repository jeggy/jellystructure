package dev.jellystructure.ravilo.screen

/**
 * DEB-5025 (FR-28, FR-29) — the controls Android TV's `PlayerChrome` draws, rebuilt in DOM. This file holds what has no state
 * of its own: the focus order, the scrub step and the hide delay. The key model itself is [Screen]'s, because every key ends in
 * what the player, the picker and the status report already do.
 */
internal enum class ChromeFocus(val id: String) {
    BAR("ov-seek"), BACK10("ov-b10"), PLAY("ov-play"), FWD30("ov-f30"), TRACKS("ov-tracks"), NEXT("ov-next"),
    /** The Skip Intro pill (R363) is never in the order: it is reached from above the bar, Audio & subtitles and Next. */
    SKIP("skip"),
}

/** FR-29 — seek bar · −10s · Play · +30s · Audio & subtitles · Next; Next only when the next episode is known. */
internal fun chromeOrder(hasNext: Boolean): List<ChromeFocus> =
    listOfNotNull(ChromeFocus.BAR, ChromeFocus.BACK10, ChromeFocus.PLAY, ChromeFocus.FWD30, ChromeFocus.TRACKS, ChromeFocus.NEXT.takeIf { hasNext })

/** FR-29 — one ←/→ on the seek bar: `min(10 s, max(5 s, 1.2 % of the duration))`, Android TV's `scrubStep()`. */
internal fun scrubStepMs(durationMs: Long): Long = minOf(10_000L, maxOf(5_000L, (durationMs * 0.012).toLong()))

/** FR-29 — the controls hide this long after the last key while playing. */
internal const val CHROME_HIDE_MS = 3_600L

/** The −10s and +30s controls and the media keys: Android TV's `SKIP_BACK_MS` and `SKIP_FWD_MS`. */
internal const val SKIP_BACK_MS = 10_000L
internal const val SKIP_FWD_MS = 30_000L
