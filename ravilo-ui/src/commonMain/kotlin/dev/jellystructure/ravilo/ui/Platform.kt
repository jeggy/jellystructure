package dev.jellystructure.ravilo.ui

/**
 * R234 (FR-R234-1) — whether this is a TV, checked at the platform level, never via
 * [dev.jellystructure.ravilo.ui.theme.LocalCompact]/[dev.jellystructure.ravilo.ui.theme.LocalHandset]:
 * a phone in landscape is still a phone, and a 10-foot UI is still a TV at any window size. The
 * `:ravilo-android` module builds one universal (phone + TV) app since R224, so this can't be a
 * compile-time module check either — [dev.jellystructure.ravilo.ui.RaviloAppContext.isTelevision]
 * (R192) already does the real runtime detection on Android; this just exposes it to commonMain
 * screens. `:ravilo-web` is never a TV, so its `actual` is a constant `false`. `:ravilo-tizen` doesn't
 * depend on this module at all (its `ProfileMenuScreen` is a separate hand-written DOM
 * implementation, per R190 §C's precedent), so it needs no `actual` here.
 */
expect val isTvPlatform: Boolean
