package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.MediaCard
import kotlinx.coroutines.delay

@Composable
fun HeroCarousel(
    items: List<Hero>,
    focusRequester: FocusRequester,
    heightDp: Dp = 460.dp,
    autoAdvanceSeconds: Int = 6,
    onSelect: (MediaCard) -> Unit = {},
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk

    val tintGradient = remember(colors.background) {
        Brush.horizontalGradient(
            0f   to colors.background.copy(alpha = 0.82f),
            0.55f to colors.background.copy(alpha = 0.35f),
            1f   to Color.Transparent,
        )
    }
    val floorGradient = remember(colors.background) {
        Brush.verticalGradient(
            0f   to Color.Transparent,
            0.55f to colors.background.copy(alpha = 0.6f),
            1f   to colors.background,
        )
    }
    val dotInactiveColor = remember(colors.textSecondary) { colors.textSecondary.copy(alpha = 0.35f) }

    var activeIndex by remember { mutableIntStateOf(0) }
    var resetTick   by remember { mutableIntStateOf(0) }

    if (items.isEmpty()) return
    val active = items[activeIndex]

    // Auto-advance; resets when the user manually changes slide. 0 seconds = off.
    if (autoAdvanceSeconds > 0 && items.size > 1) {
        LaunchedEffect(resetTick, autoAdvanceSeconds) {
            delay(autoAdvanceSeconds * 1_000L)
            activeIndex = (activeIndex + 1) % items.size
            resetTick++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            .dpadFocusable(
                focusRequester = focusRequester,
                onLeft   = {
                    if (activeIndex > 0) { activeIndex--; resetTick++ }
                },
                onRight  = {
                    if (activeIndex < items.lastIndex) { activeIndex++; resetTick++ }
                },
                onUp     = onUp,
                onDown   = onDown,
                onSelect = { onSelect(active.item) },
            ),
    ) {
        // Backdrop image, crossfades between slides
        AnimatedContent(
            targetState = active.backdropUrl,
            transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) },
            label = "heroBg",
        ) { url ->
            RemoteImage(
                url = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                alignment = Alignment.TopCenter,
            )
        }

        // Horizontal tint — pulls the left third dark so text stays readable
        Box(modifier = Modifier.fillMaxSize().background(tintGradient))
        // Vertical floor — fades bottom to bg colour for seamless row entry
        Box(modifier = Modifier.fillMaxSize().background(floorGradient))

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start  = RaviloDimens.heroBodyStart,
                    bottom = RaviloDimens.heroBodyBot,
                    end    = 40.dp,
                ),
        ) {
            // Kicker — tagline from Hero or genre fallback
            val kicker = active.taglineKicker ?: active.item.genre
            if (!kicker.isNullOrEmpty()) {
                Text(
                    text = kicker.uppercase(),
                    color = colors.accentSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = sora,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(10.dp))
            }

            // Title
            Text(
                text = active.item.title,
                color = colors.text,
                fontSize = 46.sp,
                lineHeight = 54.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = spaceGrotesk,
                letterSpacing = (-1).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Meta: year · rating
            val meta = listOfNotNull(
                active.item.year?.toString(),
                active.item.rating,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = meta,
                    color = colors.textDim,
                    fontSize = 17.sp,
                    fontFamily = sora,
                )
            }

            Spacer(Modifier.height(28.dp))

            // Animated page dots
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEachIndexed { i, _ ->
                    val dotWidth: Dp by animateDpAsState(
                        targetValue = if (i == activeIndex) 28.dp else 6.dp,
                        animationSpec = tween(300),
                        label = "dot$i",
                    )
                    Box(
                        modifier = Modifier
                            .size(dotWidth, 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == activeIndex) colors.accent else dotInactiveColor,
                            ),
                    )
                }
            }
        }
    }
}
