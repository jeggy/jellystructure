package dev.jellystructure.ravilo.ui.seams

/**
 * R240 open question 3 — a system-level "reduce motion" preference falls back to L (the static
 * status line) rather than J (the row-open panel): J's whole mechanism IS the motion (a tile
 * widening, a track sliding, a scroll tween), so there is no reduced version of it to offer — the
 * honest fallback is the direction that never animates at all, not J playing its animations faster
 * or skipped. L keeps every fact J states; only the two-slot crossfade/scroll tween is what a viewer
 * asking for less motion loses.
 *
 * Read once per focus move by the caller (not cached) — cheap on both platforms and a live system
 * setting toggle should take effect on the very next tile a viewer focuses, not require an app
 * restart. Never re-derived inside [dev.jellystructure.ravilo.ui.focus.FocusDetailController] itself,
 * matching FR-R240-4: the controller renders whatever mode it's handed, it doesn't own precedence.
 */
expect fun systemPrefersReducedMotion(): Boolean

/**
 * DEB-5838 (FR-5) — true while a screen reader is driving the device (Android's touch exploration). The phone's film
 * player keeps its chrome up then: a reader cannot find a control that hides after 3 s. Desktop and web are false —
 * the web's accessibility tree is off (FR-4), and the desktop player is driven by keys.
 */
expect fun screenReaderOn(): Boolean
