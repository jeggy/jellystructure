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
 * R254 (FR-R254-1) — and J is a TV direction: `rowOpen` becomes `line` when the platform is not a TV
 * **or** the system prefers reduced motion. [isTv] is `isTvPlatform` (R234's seam) — never
 * LocalCompact/LocalHandset (R256: a TV is 960 x 540 dp; a phone in landscape is still a phone).
 * Client-side like reduced motion; the wire and the server's per-user resolution are untouched.
 */
fun effectiveFocusDetailMode(resolvedMode: String, reduceMotion: Boolean, isTv: Boolean): String =
    if (resolvedMode == "rowOpen" && (reduceMotion || !isTv)) "line" else resolvedMode
