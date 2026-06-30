package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.Season
import androidx.compose.ui.focus.FocusRequester

@Composable
fun SeasonPicker(
    seasons: List<Season>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    firstFocusRequester: FocusRequester? = null,   // R138: lets the hero hand DOWN-focus straight to the selected season
    watchedSeasons: Set<Int> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val pillShape = remember { RoundedCornerShape(24.dp) }
    val focusSpec = remember { RaviloMotion.softSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.softSpring<Dp>() }

    LazyRow(
        modifier = modifier.focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = raviloHPad),
    ) {
        items(seasons.size, key = { i -> seasons[i].index }) { i ->
            val isSelected = i == selectedIndex
            var focused by remember { mutableStateOf(false) }
            val scale        by animateFloatAsState(if (focused) RaviloMotion.PillFocusScale else 1f, focusSpec, label = "pillScale$i")
            val borderWidth  by animateDpAsState(if (focused && !isSelected) 2.dp else 0.dp, dpSpec, label = "pillBorder$i")
            val glowElevation by animateDpAsState(if (focused) 14.dp else 0.dp, dpSpec, label = "pillShadow$i")

            // Focusable outer keeps a constant layout size; the scale + glow run draw-only on the inner
            // layer so the season picker never chases the focus animation → no viewport jump (R42/R43).
            Box(
                modifier = Modifier.dpadFocusable(
                    focusRequester = if (i == selectedIndex) firstFocusRequester else null,
                    onFocused = { focused = true },
                    onBlurred = { focused = false },
                    onSelect = { onSelect(i) },
                ),
                contentAlignment = Alignment.Center,
            ) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        shadowElevation = glowElevation.toPx()
                        shape = pillShape
                        clip = false
                        ambientShadowColor = colors.focusGlow
                        spotShadowColor = colors.focusGlow
                    }
                    .background(
                        if (isSelected) colors.accent else colors.surfaceVariant,
                        pillShape,
                    )
                    .border(borderWidth, colors.focusRing, pillShape)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val isWatched = seasons[i].index in watchedSeasons
                Text(
                    text = if (isWatched) "${seasons[i].name}  ✓" else seasons[i].name,
                    color = if (isSelected) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = sora,
                )
            }
            }
        }
    }
}
