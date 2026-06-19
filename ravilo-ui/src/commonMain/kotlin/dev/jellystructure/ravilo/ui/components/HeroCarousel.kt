package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.MediaCard

@Composable
fun HeroCarousel(
    items: List<MediaCard>,
    focusRequester: FocusRequester,
    onSelect: (MediaCard) -> Unit = {},
    onDown: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var activeIndex by remember { mutableIntStateOf(0) }
    var isFocused by remember { mutableIntStateOf(0) } // shadow of focus for styling

    if (items.isEmpty()) return

    val active = items[activeIndex]

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(440.dp)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { isFocused = 1 },
                onLeft  = { if (activeIndex > 0) activeIndex-- },
                onRight = { if (activeIndex < items.lastIndex) activeIndex++ },
                onDown  = onDown,
                onSelect = { onSelect(active) },
            ),
    ) {
        AnimatedContent(
            targetState = active.backdropUrl ?: active.posterUrl,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "heroBg",
        ) { url ->
            if (url != null) {
                RemoteImage(
                    url = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(colors.surfaceVariant))
            }
        }

        // Gradient vignette
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.4f to colors.overlay.copy(alpha = 0.4f),
                    1f to colors.background,
                )
            )
        )

        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(40.dp, 40.dp),
        ) {
            Text(
                text = active.title,
                color = colors.text,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            val meta = listOfNotNull(
                active.year?.toString(),
                active.genre,
                active.rating,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, color = colors.textSecondary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(20.dp))
            // Page dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { i, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (i == activeIndex) 20.dp else 6.dp, 6.dp)
                            .clip(CircleShape)
                            .background(if (i == activeIndex) colors.accent else colors.textSecondary.copy(alpha = 0.4f)),
                    )
                }
            }
        }
    }
}
