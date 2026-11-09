package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.focusRing
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.raviloAppBar

/**
 * DEB-3984 (FR-12, FR-13) — what the TV bar's *Surprise me* pill does: ask the server for a title and open it. Null where
 * there is no pill: not a TV, or a server that does not send `surprise_me` (an older one has no route to ask). The frame
 * provides it, so every screen's own `AppBar(…)` call stays as it is.
 */
val LocalSurpriseMe = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * FR-12 — the bar's labelled pill: a sparkle glyph and `nav.surprise`. At rest a hairline outline on the bar; focused, the
 * ink fills it (the now-playing pill's treatment). Sized by the header style, like the tabs beside it.
 */
@Composable
fun SurprisePill(
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onDown: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val g = raviloAppBar
    var focused by rememberFocusVisual()
    val shape = RoundedCornerShape(g.navCorner + 6.dp)
    val ink = if (focused) colors.background else colors.text
    Row(
        modifier = modifier
            .background(if (focused) colors.text else Color.Transparent, shape)
            .border(1.dp, colors.textSecondary.copy(alpha = if (focused) 0f else 0.4f), shape)
            .focusRing(focused, shape, colors.focusRing, 2.dp, outset = BAR_RING_OUTSET, over = false)
            .padding(horizontal = g.navPadH + 2.dp, vertical = g.navPadV)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onLeft = onLeft,
                onRight = onRight,
                onDown = onDown,
                onSelect = onSelect,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        SparkleGlyph(ink, Modifier.size(g.navType.value.dp + 2.dp))
        Text(
            text = str("nav.surprise"),
            color = ink,
            fontSize = g.navType,
            fontWeight = FontWeight.SemiBold,
            fontFamily = Sora,
            maxLines = 1,
            softWrap = false,
        )
    }
}

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
