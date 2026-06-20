package dev.jellystructure.ravilo.ui.theme

import androidx.compose.ui.unit.dp

object RaviloDimens {
    val screenPadH    = 48.dp   // left/right padding on all screens
    val trackPadH     = 48.dp   // LazyRow contentPadding start/end
    val trackPadV     = 20.dp   // LazyRow contentPadding top/bottom (also covers scale-overflow)
    val itemSpacing   = 16.dp   // gap between tiles / channel cards / cast circles
    val rowGap        = 36.dp   // vertical gap between content rows
    val heroBodyStart = 48.dp   // hero text column left inset
    val heroBodyBot   = 56.dp   // hero text column bottom inset
    val detailBodyBot = 32.dp   // detail page hero text bottom inset
    val rowHeadPadB   = 10.dp   // padding below row header before content
    val sectionPadH   = 48.dp   // horizontal padding for section headers on detail pages
}
