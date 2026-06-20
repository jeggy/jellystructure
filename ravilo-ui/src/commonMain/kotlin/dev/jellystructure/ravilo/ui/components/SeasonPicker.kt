package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.Season

@Composable
fun SeasonPicker(
    seasons: List<Season>,
    selectedIndex: Int,
    focusedIndex: Int,
    focusRequesters: List<FocusRequester>,
    onSelect: (Int) -> Unit,
    onLeft: (Int) -> Unit,
    onRight: (Int) -> Unit,
    onDown: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val pillShape = remember { RoundedCornerShape(24.dp) }
    val focusSpec = remember { spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val dpSpec    = remember { spring<Dp>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = RaviloDimens.trackPadH),
    ) {
        items(seasons.size, key = { i -> seasons[i].index }) { i ->
            val isSelected = i == selectedIndex
            var focused by remember { mutableStateOf(false) }
            val scale        by animateFloatAsState(if (focused) 1.06f else 1f, focusSpec, label = "pillScale$i")
            val borderWidth  by animateDpAsState(if (focused && !isSelected) 2.dp else 0.dp, dpSpec, label = "pillBorder$i")
            val shadowElevation by animateDpAsState(if (focused) 14.dp else 0.dp, dpSpec, label = "pillShadow$i")

            Box(
                modifier = Modifier
                    .scale(scale)
                    .shadow(
                        elevation = shadowElevation,
                        shape = pillShape,
                        clip = false,
                        ambientColor = colors.focusGlow,
                        spotColor = colors.focusGlow,
                    )
                    .background(
                        if (isSelected) colors.accent else colors.surfaceVariant,
                        pillShape,
                    )
                    .border(borderWidth, colors.focusRing, pillShape)
                    .dpadFocusable(
                        focusRequester = focusRequesters[i],
                        onFocused = { focused = true },
                        onBlurred = { focused = false },
                        onLeft  = { onLeft(i) },
                        onRight = { onRight(i) },
                        onDown  = onDown,
                        onSelect = { onSelect(i) },
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = seasons[i].name,
                    color = if (isSelected) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                    fontSize = 17.sp,
                    fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = sora,
                )
            }
        }
    }
}
