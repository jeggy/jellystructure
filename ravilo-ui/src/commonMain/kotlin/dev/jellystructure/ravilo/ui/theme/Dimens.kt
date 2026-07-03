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

// R145: responsive horizontal content gutter — tight on phones, TV-wide otherwise. Replaces the fixed
// 48dp screenPadH at every content-margin call site.
val raviloHPad: Dp
    @Composable get() = if (LocalCompact.current) 20.dp else RaviloDimens.screenPadH

object RaviloDimens {
    val appBarHeight  = 60.dp   // overlay nav bar height (R65 tokenized)
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
