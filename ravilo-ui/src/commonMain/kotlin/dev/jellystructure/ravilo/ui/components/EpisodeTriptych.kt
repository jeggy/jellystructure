package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.seams.RemoteImage

/**
 * Renders 1-3 still images as one seamed strip: a single URL fills the whole box; 2-3 share it with a
 * diagonal-cut seam between neighbours. Shared by [MultiEpisodeCard] (series-detail episode rail) and
 * the in-player episode picker's rail card, so a multi-episode-file group reads the same wherever it
 * appears — the picker used to show only the group's first episode's still as a flat image, which read
 * as "the images weren't fixed here" even after the detail-rail triptych was fixed.
 *
 * Each panel after the first is widened by [seamSlant] on its leading edge and shifted left by the same
 * amount so it overlaps the previous panel, then clipped with a diagonal shape that trims that overlap
 * to a slant — revealing the previous (unclipped, already-drawn) panel underneath through a real
 * diagonal cut. Panels compose left-to-right and Compose draws later Box children on top, so the reveal
 * falls out of draw order with no zIndex needed. A rotated divider Box can't do this — Compose clips it
 * to its own unrotated bounds, so a "diagonal" rotated line degenerates into a near-straight fragment.
 */
@Composable
fun EpisodeTriptych(
    stillUrls: List<String?>,
    modifier: Modifier = Modifier,
    contentDescriptions: List<String?> = emptyList(),
) {
    val panels = stillUrls.take(3)
    BoxWithConstraints(modifier = modifier) {
        val panelWidth = maxWidth / panels.size.coerceAtLeast(1)
        val seamSlant = 14.dp
        panels.forEachIndexed { i, url ->
            val bleedLeft = i > 0
            val bleedRight = i < panels.size - 1
            val boxWidth = panelWidth +
                (if (bleedLeft) seamSlant else 0.dp) +
                (if (bleedRight) seamSlant else 0.dp)
            val xOffset = panelWidth * i - (if (bleedLeft) seamSlant else 0.dp)
            Box(
                modifier = Modifier
                    .offset(x = xOffset)
                    .width(boxWidth)
                    .fillMaxHeight()
                    .then(if (bleedLeft) Modifier.clip(diagonalSeamShape(seamSlant)) else Modifier),
            ) {
                if (url != null) {
                    RemoteImage(
                        url = url,
                        contentDescription = contentDescriptions.getOrNull(i),
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

/** Clips a panel's leading edge on a slant (top cut `slant` further right than the bottom), trimming
 *  its overlap with the previous panel down to a diagonal reveal. See the triptych comment above. */
@Composable
private fun diagonalSeamShape(slant: Dp): Shape {
    val slantPx = with(LocalDensity.current) { slant.toPx() }
    return remember(slantPx) {
        GenericShape { size, _ ->
            val s = slantPx.coerceIn(0f, size.width)
            moveTo(s, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
    }
}
