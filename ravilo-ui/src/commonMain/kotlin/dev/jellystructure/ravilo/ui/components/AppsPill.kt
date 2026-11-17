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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.focusRing
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.raviloAppBar

/**
 * DEB-4912 (FR-10) — what the TV bar's *Apps* pill does: open Apps. Null where there is no pill: not a TV, a TV whose home-screen hook
 * is off, or a platform that never registered Apps (FR-13). The frame provides it, so every screen's own `AppBar(…)` call stays as it is.
 */
val LocalOpenApps = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * FR-10 — the bar's labelled pill, direction A2 of `design/incoming/2026-11-17-tv-home-screen-apps/`: four rounded squares and `nav.apps`
 * in the right cluster, the shape *Surprise me* already has. A pill with a word reads as an action rather than a fifth tab.
 */
@Composable
fun AppsPill(
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
        AppsGlyph(ink, Modifier.size(g.navType.value.dp + 2.dp))
        Text(
            text = str("nav.apps"),
            color = ink,
            fontSize = g.navType,
            fontWeight = FontWeight.SemiBold,
            fontFamily = Sora,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Four rounded squares in a 2×2 grid, drawn in a 24×24 box. Squares are the one shape Ravilo's own content never uses (design round). */
@Composable
fun AppsGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val side = 8.5f * s
        for (cx in listOf(3f, 12.5f)) for (cy in listOf(3f, 12.5f)) {
            drawRoundRect(tint, Offset(cx * s, cy * s), Size(side, side), CornerRadius(2.2f * s))
        }
    }
}
