package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    // R151: season.index -> watched episode count, for the partial (1..n-1 watched) w/N badge + sliver.
    // A season complete per [watchedSeasons] takes the ✓ badge instead, regardless of what's here.
    watchedCounts: Map<Int, Int> = emptyMap(),
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
            val scale        by animateFloatAsState(if (focused) RaviloMotion.PILL_FOCUS_SCALE else 1f, focusSpec, label = "pillScale$i")
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
            Column(
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
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // R150/R151: complete → ✓ badge; partial (1..n-1 watched) → w/N count badge + sliver;
                // none watched → plain pill. Empty overlay (watchedSeasons/watchedCounts both empty) shows nothing.
                val isComplete = seasons[i].index in watchedSeasons
                val watchedCount = watchedCounts[seasons[i].index] ?: 0
                val total = seasons[i].episodes.size
                val isPartial = !isComplete && watchedCount > 0 && total > 0
                // On the selected (accent-filled) pill the badge/sliver invert to stay legible, mirroring
                // the design's focused-pill inversion (this app's "selected" state is the highlighted one).
                val badgeBg    = if (isSelected) colors.onAccent.copy(alpha = 0.18f) else colors.progressBg
                val badgeText  = if (isSelected) colors.onAccent else colors.text
                val sliverColor = if (isSelected) colors.onAccent else colors.progressFill

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = seasons[i].name,
                        color = if (isSelected) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected || focused) FontWeight.SemiBold else FontWeight.Normal,
                        fontFamily = sora,
                    )
                    if (isComplete) {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(colors.badgeWatched, RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✓",
                                color = colors.background,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = sora,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    } else if (isPartial) {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(badgeBg, RoundedCornerShape(50)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$watchedCount/$total",
                                color = badgeText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = sora,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (isPartial) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp)) {
                        Box(Modifier.weight(watchedCount.toFloat()).height(3.dp)
                            .background(sliverColor, RoundedCornerShape(3.dp)))
                        Box(Modifier.weight((total - watchedCount).toFloat()))
                    }
                }
            }
            }
        }
    }
}
