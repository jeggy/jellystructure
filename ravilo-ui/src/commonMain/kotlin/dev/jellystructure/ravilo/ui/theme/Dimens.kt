package dev.jellystructure.ravilo.ui.theme

import androidx.compose.ui.unit.dp

object RaviloDimens {
    val screenPadH    = 64.dp   // left/right padding on all screens
    val trackPadH     = 64.dp   // LazyRow contentPadding start/end
    val trackPadV     = 28.dp   // LazyRow contentPadding top/bottom (also covers scale-overflow)
    val itemSpacing   = 22.dp   // gap between tiles / channel cards / cast circles
    val rowGap        = 52.dp   // vertical gap between content rows
    val heroBodyStart = 64.dp   // hero text column left inset
    val heroBodyBot   = 86.dp   // hero text column bottom inset
    val detailBodyBot = 44.dp   // detail page hero text bottom inset
    val rowHeadPadB   = 12.dp   // padding below row header before content
    val sectionPadH   = 64.dp   // horizontal padding for section headers on detail pages
}
