package dev.jellystructure.ravilo.ui.focus

/**
 * R240 open question 3, resolved — a system "reduce motion" preference downgrades the server-resolved
 * `"rowOpen"` to `"line"`; `"line"` and `"none"` are returned unchanged. J's whole mechanism IS motion
 * (a tile widening, a track sliding, a scroll tween — see `seams/ReducedMotion.kt`'s doc for why there
 * is no reduced version of it to offer instead), so the honest fallback is the direction that never
 * animates at all, not J with its animations skipped or shortened.
 *
 * Pure so it's unit-testable without a Compose test rule; [HomeScreen.kt]'s `HomeLoaded` (the
 * household-level reveal effect / reserved-band sizing) and `ContentRowItem` (what's actually handed
 * to [FocusDetailController.onFocus]) both call this so they can never disagree about which mode is
 * really in effect for a given focus move.
 */
/**
 * R314 (FR-R314-1) — focus detail exists only on a TV: off a TV the answer is `"none"` for every resolved
 * mode, so the web app and a phone at any size draw no line, reserve no band, run no dwell timer and
 * open no row. It completes R254 (J is TV-only, which left the web and tablet-sized phones on the line)
 * and R298 (no focus detail on a handset: no remote moves focus there, so the line only ever described
 * whichever card focus had been restored to — as true in a desktop browser). [isTv] is `isTvPlatform`
 * (R234's seam) — never LocalCompact/LocalHandset (R256: a TV is 960 x 540 dp; a phone in landscape is
 * still a phone). Client-side like reduced motion; the server's per-user resolution is untouched, and on
 * a TV the household's setting applies exactly as configured.
 */
fun effectiveFocusDetailMode(resolvedMode: String, reduceMotion: Boolean, isTv: Boolean): String = when {
    !isTv -> "none"
    resolvedMode == "rowOpen" && reduceMotion -> "line"
    else -> resolvedMode
}
