package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.LocalCompact
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

/**
 * R257 (FR-R257-4) — ONE definition of how a hero picture is darkened for the text on it, always in the
 * page's own colour, never black. Home's hero has had [tint] + [homeFloor] since R24; the two detail
 * heroes only ever had a floor, so on bright artwork (Tomgang's sunlit shop front, stue TV 2026-09-17)
 * the app bar, the facts and the synopsis sat on the picture. Same artwork, same text column, two
 * treatments — this file is so the three cannot drift again.
 */
class HeroScrims(val tint: Brush, val homeFloor: Brush, val detailFloor: Brush, val head: Brush)

@Composable
fun rememberHeroScrims(): HeroScrims {
    val bg = RaviloTheme.colors.background
    return remember(bg) {
        HeroScrims(
            // Left-to-right: the text column is the left 60 %; the right of the picture stays clear.
            tint = Brush.horizontalGradient(0f to bg.copy(alpha = 0.82f), 0.55f to bg.copy(alpha = 0.35f), 1f to Color.Transparent),
            homeFloor = Brush.verticalGradient(0f to Color.Transparent, 0.55f to bg.copy(alpha = 0.6f), 1f to bg),
            detailFloor = Brush.verticalGradient(0f to Color.Transparent, 0.45f to bg.copy(alpha = 0.55f), 1f to bg),
            // Under the (transparent-at-rest) app bar, so the nav reads over a bright sky.
            head = Brush.verticalGradient(0f to bg.copy(alpha = 0.70f), 1f to Color.Transparent),
        )
    }
}

/** The detail hero's layers, in order, over a `matchParentSize` backdrop. Compact (a phone in portrait)
 *  keeps the floor only: its text column is full-width and a side tint would cover the whole picture. */
@Composable
fun BoxScope.DetailHeroScrims() {
    val s = rememberHeroScrims()
    if (!LocalCompact.current) {
        Box(Modifier.matchParentSize().background(s.tint))
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(RaviloDimens.appBarHeight + 40.dp).background(s.head))
    }
    Box(Modifier.matchParentSize().background(s.detailFloor))
}
