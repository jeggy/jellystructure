package dev.jellystructure.ravilo.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// R145: true on a handset-width screen (the phone target). Provided once by RaviloApp from the window
// width. Drives a tighter horizontal gutter + full-width detail content so the TV-tuned 48dp margins
// don't waste a narrow screen. Defaults false → TV/large layouts are completely unchanged.
val LocalCompact = staticCompositionLocalOf { false }

// Bug fix: LocalCompact is deliberately width-only (correct for "narrow window → stack content full
// width" everywhere it's used) but that makes it flip to false the moment a phone rotates to landscape
// for video playback — width becomes the device's long edge. PlayerScreen's tap-to-toggle-chrome relied
// on LocalCompact and so silently stopped working in landscape: tapping the video never brought the
// controls back once they auto-hid. LocalHandset is the orientation-stable form-factor signal (the
// smaller of width/height, i.e. "smallest width" — matches Android's own sw dp qualifier), for the few
// call sites that need "is this actually a phone" rather than "is the window narrow right now".
// R256 — and never on a TV: a TV is 960 x 540 dp, so the size test alone calls it a phone. The provider
// (RaviloApp → isHandset) checks isTvPlatform first.
val LocalHandset = staticCompositionLocalOf { false }

// R145: responsive horizontal content gutter — tight on phones, TV-wide otherwise. Replaces the fixed
// 48dp screenPadH at every content-margin call site.
val raviloHPad: Dp
    @Composable get() = if (LocalCompact.current) 20.dp else RaviloDimens.screenPadH

object RaviloDimens {
    val appBarHeight  = 60.dp   // overlay nav bar height (R65 tokenized)
    /**
     * R267 (FR-R267-12) — the phone's bottom navigation bar, **from one place**.
     *
     * Anything that offsets content by it — a page's bottom padding, the cast mini bar's own offset —
     * reads this value rather than repeating a number. A phone value wrong by one bar is exactly how
     * a heading ends up underneath one, which this project has now paid for twice (R257 FR-R257-5 and
     * R259 FR-R259-2). Excludes the navigation-bar / home-indicator inset, which is applied
     * separately by `safeAreaPadding()`.
     *
     * R274 (FR-R274-1) — **the whole bar, hairline included**, and big enough for what it draws. At
     * 56 dp it was one dp *shorter* than its own cell (8 top + 32 pill + 2 + ~15 label), so the label
     * sat flush against — in fact just past — the bar's bottom edge. The mockup's geometry
     * (`design/ravilo/Ravilo Mobile.html`, `.bnav`/`.bn`: a hairline, 9 px above the pill, a 56 px
     * item centring its content) is what this first followed, at 68 dp.
     *
     * Then, on the device: **74 dp**, to carry a 36 dp pill and a glyph big enough to read at arm's
     * length on a phone the mockup was drawn smaller than. Still under Material's own 80 dp bar, and
     * the label stays at 11.5 sp — R267 FR-R267-6a's fenced exception is not reopened by making the
     * glyphs bigger.
     */
    val bottomNavHeight = 74.dp
    val screenPadH    = 48.dp   // left/right padding on all screens
    val trackPadV     = 20.dp   // LazyRow contentPadding top/bottom (also covers scale-overflow)
    val itemSpacing   = 16.dp   // gap between tiles / channel cards / cast circles
    val rowGap        = 36.dp   // vertical gap between content rows
    val heroBodyBot   = 44.dp   // hero text column bottom inset (R35: TV-tuned, matches detail)
    val rowHeadPadB   = 10.dp   // padding below row header before content

    // Hero backdrop crop framing — biases the cropped image down so subjects sit below the
    // AppBar instead of being clipped at the top (R35). Mirrors the mockup's
    // `object-position: center 26%`: vertical bias = 2*0.26 - 1 = -0.48, horizontal = 0.
    val heroBackdropAlignment: Alignment = BiasAlignment(0f, -0.48f)
}
