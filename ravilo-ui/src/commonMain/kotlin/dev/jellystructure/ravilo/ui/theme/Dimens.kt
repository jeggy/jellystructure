package dev.jellystructure.ravilo.ui.theme

import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.unit.dp

object RaviloDimens {
    val appBarHeight  = 60.dp   // overlay nav bar height (R65 tokenized)
    val screenPadH    = 48.dp   // left/right padding on all screens
    val trackPadH     = 48.dp   // LazyRow contentPadding start/end
    val trackPadV     = 20.dp   // LazyRow contentPadding top/bottom (also covers scale-overflow)
    val itemSpacing   = 16.dp   // gap between tiles / channel cards / cast circles
    val rowGap        = 36.dp   // vertical gap between content rows
    val heroBodyStart = 48.dp   // hero text column left inset
    val heroBodyBot   = 44.dp   // hero text column bottom inset (R35: TV-tuned, matches detail)
    val detailBodyBot = 32.dp   // detail page hero text bottom inset
    val rowHeadPadB   = 10.dp   // padding below row header before content
    val sectionPadH   = 48.dp   // horizontal padding for section headers on detail pages

    // Hero backdrop crop framing — biases the cropped image down so subjects sit below the
    // AppBar instead of being clipped at the top (R35). Mirrors the mockup's
    // `object-position: center 26%`: vertical bias = 2*0.26 - 1 = -0.48, horizontal = 0.
    val heroBackdropAlignment: Alignment = BiasAlignment(0f, -0.48f)
}
