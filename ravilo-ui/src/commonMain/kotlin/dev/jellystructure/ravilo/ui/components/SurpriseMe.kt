package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora

/**
 * DEB-3984 (FR-12, FR-13), DEB-4273 — what Home's closing *Surprise me* button does: ask the server for a title and open it.
 * Null where there is no button: not a TV, or a server that does not send `surprise_me` (an older one has no route to ask).
 * The frame provides it; only Home reads it (the bar has no Surprise me since DEB-4273).
 */
val LocalSurpriseMe = staticCompositionLocalOf<(() -> Unit)?> { null }

/** The four-point sparkle with its small companion, drawn in a 24×24 box. Deliberately not the shuffle arrows `detail.shuffle` owns. */
@Composable
fun SparkleGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        fun DrawScope.star(cx: Float, cy: Float, r: Float) {
            val k = r * 0.22f   // the waist: how far in the diagonal pinch sits
            val p = Path().apply {
                moveTo(cx, cy - r)
                quadraticTo(cx + k, cy - k, cx + r, cy)
                quadraticTo(cx + k, cy + k, cx, cy + r)
                quadraticTo(cx - k, cy + k, cx - r, cy)
                quadraticTo(cx - k, cy - k, cx, cy - r)
                close()
            }
            drawPath(p, tint)
        }
        star(10f * s, 13f * s, 9f * s)
        star(19f * s, 5f * s, 4f * s)
    }
}

/** FR-12 — the small `detail.kicker_surprise` line above the title on a page Surprise me opened. */
@Composable
fun SurpriseKicker(modifier: Modifier = Modifier) {
    Text(
        text = str("detail.kicker_surprise"),
        color = RaviloTheme.colors.accentSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = Sora,
        letterSpacing = 2.sp,
        maxLines = 1,
        modifier = modifier.padding(bottom = 8.dp),
    )
}
