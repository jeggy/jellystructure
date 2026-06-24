package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

/**
 * R51 — the Ravilo brand **mark** (stylised jellyfish): one filled dome + four stroked tentacles,
 * tinted with the active skin's accent gradient (constitution brand rule: "the mark tints with the
 * active skin's accent"). Vector-drawn from the design's `ravilo-mark.svg` path data
 * (`viewBox="12 20 76 76"`) so it stays identical on Android + Web and auto-tints per skin — no
 * raster/asset pipeline.
 */
private const val VB_X = 12f
private const val VB_Y = 20f
private const val VB_SIZE = 76f

private const val DOME = "M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z"
private val TENTACLES = listOf(
    "M33 51 q-5 12 1 20 q5 8 0 14" to 0.9f,
    "M44 52 q-4 13 1 21 q4 9 0 13" to 0.72f,
    "M56 52 q4 13 -1 21 q-4 9 0 13" to 0.72f,
    "M67 51 q5 12 -1 20 q-5 8 0 14" to 0.9f,
)

@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val colors = RaviloTheme.colors
    val dome = remember { PathParser().parsePathString(DOME).toPath() }
    val tentacles = remember {
        TENTACLES.map { (d, alpha) -> PathParser().parsePathString(d).toPath() to alpha }
    }
    Canvas(modifier = modifier.size(size)) {
        val scale = this.size.minDimension / VB_SIZE
        // Gradient defined in the SVG's own coordinate space (diagonal, matching x1,y1→x2,y2) so it
        // aligns with the paths under the transform; skin-tinted via accent → accentSecondary.
        val brush = Brush.linearGradient(
            colors = listOf(colors.accent, colors.accentSecondary),
            start = Offset(VB_X, VB_Y),
            end = Offset(VB_X + VB_SIZE, VB_Y + VB_SIZE),
        )
        withTransform({
            scale(scale, scale, pivot = Offset.Zero)
            translate(-VB_X, -VB_Y)
        }) {
            drawPath(dome, brush = brush)
            tentacles.forEach { (path, alpha) ->
                drawPath(
                    path = path,
                    brush = brush,
                    alpha = alpha,
                    style = Stroke(width = 4.5f, cap = StrokeCap.Round),
                )
            }
        }
    }
}
