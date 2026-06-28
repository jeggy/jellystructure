package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora

/**
 * R135 — a focusable detail synopsis. Truncated to [collapsedMaxLines] by default; SELECT toggles the full
 * text inline (no modal, so it reuses the page's own scroll and adds no new focus path on this
 * focus-sensitive screen). A chevron marks "more/less" when there's hidden text; focus brightens the text
 * and the chevron (draw-only scale, R47 — no viewport jump). [onUp]/[onDown] thread it into the hero's
 * vertical focus order (typically AppBar ↑ / Play ↓).
 */
@Composable
fun DetailSynopsis(
    text: String,
    collapsedMaxLines: Int,
    focusRequester: FocusRequester? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    val spec = remember { RaviloMotion.softSpring<Float>() }
    val scale by animateFloatAsState(if (focused) 1.012f else 1f, spec, label = "synopsisScale")

    Column(
        modifier = modifier.dpadFocusable(
            focusRequester = focusRequester,
            onFocused = { focused = true },
            onBlurred = { focused = false },
            onUp = onUp,
            onDown = onDown,
            onSelect = { if (truncated || expanded) expanded = !expanded },
        ),
    ) {
        Text(
            text = text,
            color = if (focused) colors.text else colors.textSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow },
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        )
        if (truncated || expanded) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = if (expanded) "▴ less" else "▾ more",
                color = if (focused) colors.accent else colors.textSecondary.copy(alpha = 0.6f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Sora,
            )
        }
    }
}
