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
import androidx.compose.foundation.layout.width
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
import dev.jellystructure.ravilo.ui.i18n.str
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
    onPlay: (MediaCard) -> Unit = {},
    onMoreInfo: (MediaCard) -> Unit = {},
    onMyList: (MediaCard) -> Unit = {},
    onUp: (() -> Unit)? = null,
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

    // Action-button focus requesters. The Play button is the hero's entry point (the passed-in
    // focusRequester); Left/Right move between buttons and page the carousel at the row edges.
    val moreInfoFR = remember { FocusRequester() }
    val myListFR   = remember { FocusRequester() }

    if (items.isEmpty()) return
    // The heroes list can change size under us (R33 live config push). Never index past its end —
    // coerce on read (guards the frame before the effect runs) and snap a now-stale cursor back to
    // the start so the dots / auto-advance stay consistent.
    LaunchedEffect(items.size) { if (activeIndex > items.lastIndex) { activeIndex = 0; resetTick++ } }
    val active = items[activeIndex.coerceIn(0, items.lastIndex)]

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
            .height(heightDp),
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
                alignment = RaviloDimens.heroBackdropAlignment,
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = sora,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Title
            Text(
                text = active.item.title,
                color = colors.text,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = spaceGrotesk,
                letterSpacing = (-0.5).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Meta: year · rating
            val meta = listOfNotNull(
                active.item.year?.toString(),
                active.item.rating,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = meta,
                    color = colors.textDim,
                    fontSize = 15.sp,
                    fontFamily = sora,
                )
            }

            // Synopsis — overview from the hero item (server-provided)
            val synopsis = active.synopsis
            if (!synopsis.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = synopsis,
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = sora,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(560.dp),
                )
            }

            Spacer(Modifier.height(18.dp))

            // Action buttons: Play · More Info · + My List
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RaviloButton(
                    label = "▶  ${str("action.play")}",
                    focusRequester = focusRequester,
                    style = ButtonStyle.PRIMARY,
                    onLeft  = { if (activeIndex > 0) { activeIndex--; resetTick++ } },
                    onRight = { moreInfoFR.requestFocus() },
                    onUp    = onUp,
                    onSelect = { onPlay(active.item) },
                )
                RaviloButton(
                    label = str("action.more_info"),
                    focusRequester = moreInfoFR,
                    style = ButtonStyle.GHOST,
                    onLeft  = { focusRequester.requestFocus() },
                    onRight = { myListFR.requestFocus() },
                    onUp    = onUp,
                    onSelect = { onMoreInfo(active.item) },
                )
                RaviloButton(
                    label = "+ ${str("nav.my_list")}",
                    focusRequester = myListFR,
                    style = ButtonStyle.GHOST,
                    onLeft  = { moreInfoFR.requestFocus() },
                    onRight = { if (activeIndex < items.lastIndex) { activeIndex++; resetTick++ } },
                    onUp    = onUp,
                    onSelect = { onMyList(active.item) },
                )
            }

            Spacer(Modifier.height(18.dp))

            // Animated page dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { i, _ ->
                    val dotWidth: Dp by animateDpAsState(
                        targetValue = if (i == activeIndex) 24.dp else 6.dp,
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
