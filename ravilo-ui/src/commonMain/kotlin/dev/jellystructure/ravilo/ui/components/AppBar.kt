package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * navFR — entry-point FocusRequester; callers focus this to bring focus into the bar.
 *          It is used as the first nav item's focusRequester so requesting it lands
 *          directly on the first item.
 * onDown — called when D-pad DOWN is pressed from any nav item; typically returns
 *           focus to the screen's hero/content area.
 */
@Composable
fun AppBar(
    navItems: List<String> = listOf("Home", "Movies", "Series", "My List", "Search"),
    activeNav: Int = 0,
    onNavSelect: (Int) -> Unit = {},
    navFR: FocusRequester = remember { FocusRequester() },
    onDown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    val barGradient = remember {
        Brush.verticalGradient(
            0f to Color(0x8C000000),
            1f to Color.Transparent,
        )
    }

    val otherFRs = remember(navItems.size) { List(maxOf(navItems.size - 1, 0)) { FocusRequester() } }
    val allFRs: List<FocusRequester> = remember(navFR, otherFRs) { listOf(navFR) + otherFRs }
    var focusedIdx by remember { mutableIntStateOf(-1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(barGradient),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RaviloDimens.screenPadH)
                .matchParentSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Wordmark
            Text(
                text = "Ravilo",
                color = colors.accent,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = spaceGrotesk,
                letterSpacing = (-1).sp,
            )

            navItems.forEachIndexed { i, label ->
                val isFocused = focusedIdx == i
                val isActive  = i == activeNav

                val bgColor by animateColorAsState(
                    targetValue = if (isFocused) colors.text else Color.Transparent,
                    animationSpec = tween(120),
                    label = "navBg$i",
                )
                val textColor by animateColorAsState(
                    targetValue = when {
                        isFocused -> colors.background
                        isActive  -> colors.text
                        else      -> colors.textSecondary
                    },
                    animationSpec = tween(120),
                    label = "navText$i",
                )

                Text(
                    text = label,
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = if (isFocused || isActive) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = sora,
                    modifier = Modifier
                        .onFocusChanged { state ->
                            focusedIdx = if (state.isFocused) i
                                         else if (focusedIdx == i) -1
                                         else focusedIdx
                        }
                        .background(bgColor, RoundedCornerShape(11.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .dpadFocusable(
                            focusRequester = allFRs[i],
                            onFocused  = { focusedIdx = i },
                            onLeft     = { if (i > 0) allFRs[i - 1].requestFocus() },
                            onRight    = { if (i < navItems.lastIndex) allFRs[i + 1].requestFocus() },
                            onDown     = onDown,
                            onSelect   = { onNavSelect(i) },
                        ),
                )
            }

            Box(modifier = Modifier.weight(1f))
            ClockDisplay()
        }
    }
}

@Composable
private fun ClockDisplay() {
    val colors = RaviloTheme.colors
    val sora = Sora
    var timeStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            timeStr = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
            delay(30_000L)
        }
    }

    Text(
        text = timeStr,
        color = colors.textSecondary,
        fontSize = 15.sp,
        fontFamily = sora,
        textAlign = TextAlign.End,
    )
}
