package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.Episode

@Composable
fun EpisodeCard(
    episode: Episode,
    focusRequester: FocusRequester? = null,
    isResumeEpisode: Boolean = false,
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    var focused by remember { mutableStateOf(false) }
    val focusSpec = remember { spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val dpSpec    = remember { spring<Dp>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val scale           by animateFloatAsState(if (focused) 1.06f else 1f, focusSpec, label = "epScale")
    val borderWidth     by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "epBorder")
    val glowElevation   by animateDpAsState(if (focused) 20.dp else 0.dp, dpSpec, label = "epShadow")
    val cardShape = remember { RoundedCornerShape(12.dp) }

    val isWatched = episode.playback.watched
    val pct = episode.playback.pct

    val upNextGradient = remember(colors.accent, colors.accentSecondary) { colors.accentGradient }

    // Focusable at a FIXED layout size; the focus scale + glow run draw-only on the inner layer so the
    // episode rail's focused-bounds tracking never chases the scale animation → no viewport jump (R42/R43,
    // previously only on Tile/ChannelCard — now applied to the detail rails).
    Box(
        modifier = Modifier
            .width(320.dp)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
            ),
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                shadowElevation = glowElevation.toPx()
                shape = cardShape
                clip = true
                ambientShadowColor = colors.focusGlow
                spotShadowColor = colors.focusGlow
            }
            .background(colors.card)
            .border(borderWidth, colors.focusRing, cardShape)
            .alpha(if (isWatched) 0.62f else 1f),
    ) {
        // Still — full-width 16:9
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(colors.surface),
        ) {
            val stillUrl = episode.stillUrl
            if (stillUrl != null) {
                RemoteImage(
                    url = stillUrl,
                    contentDescription = episode.title,
                    modifier = Modifier.matchParentSize(),
                )
            }

            // Episode number overlay (top-start)
            Text(
                text = "${episode.episodeNumber}",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = spaceGrotesk,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )

            // Duration badge (top-end)
            if (episode.runtime > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "${episode.runtime}m",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = sora,
                    )
                }
            }

            // "Up Next" ribbon (bottom-start) if this is the resume episode
            if (isResumeEpisode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(upNextGradient, RoundedCornerShape(topEnd = 8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "UP NEXT",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = sora,
                        letterSpacing = 0.5.sp,
                    )
                }
            }

            // Watched circle (bottom-end)
            if (isWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .background(colors.badgeWatched, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Progress bar (bottom, full-width overlay)
            if (pct > 0f && !isWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(colors.progressBg),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct)
                            .height(4.dp)
                            .background(colors.progressFill),
                    )
                }
            }
        }

        // Text area
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "E${episode.episodeNumber} · ${episode.title}",
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = sora,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            episode.overview?.let { overview ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = overview,
                    color = colors.textDim,
                    fontSize = 13.sp,
                    fontFamily = sora,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}
