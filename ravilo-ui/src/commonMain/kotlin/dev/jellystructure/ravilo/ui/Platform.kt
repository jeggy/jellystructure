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

/**
 * R256 — "is this a phone", as a pure function. Form factor first, size second: **every Android TV
 * is 960 x 540 dp** (1920x1080 @ density 2.0, 3840x2160 @ 4.0), so its shorter side is *under*
 * Android's own sw600dp phone/tablet line and NO dp threshold can tell a TV from a phone. The
 * dp-only form of this shipped R244's phone player and R243's 2-up walls onto the living-room TV
 * (v1.18). [isTv] is [isTvPlatform]; [density] is px per dp.
 */
fun isHandset(isTv: Boolean, widthPx: Int, heightPx: Int, density: Float): Boolean {
    if (isTv || density <= 0f) return false
    val shortPx = minOf(widthPx, heightPx)
    return shortPx > 0 && shortPx / density < 600f
}
