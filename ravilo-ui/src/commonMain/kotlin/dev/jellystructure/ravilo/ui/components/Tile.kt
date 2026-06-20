package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
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
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient

enum class TileVariant { POSTER, LANDSCAPE }

private val POSTER_W    = 155.dp
private val POSTER_H    = 232.dp
private val LANDSCAPE_W = 256.dp
private val LANDSCAPE_H = 144.dp

@Composable
fun Tile(
    title: String,
    posterUrl: String?,
    focusRequester: FocusRequester,
    variant: TileVariant = TileVariant.POSTER,
    subtitle: String? = null,
    progressPct: Float = 0f,
    watched: Boolean = false,
    isNew: Boolean = false,
    onFocused: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    var focused by remember { mutableStateOf(false) }
    val focusSpec = remember { spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val dpSpec    = remember { spring<Dp>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow) }
    val scale          by animateFloatAsState(if (focused) 1.10f else 1f, focusSpec, label = "tileScale")
    val borderWidth    by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "tileBorder")
    val shadowElevation by animateDpAsState(if (focused) 24.dp else 0.dp, dpSpec, label = "tileShadow")
    val tileShape = remember(colors.tileRadius) { RoundedCornerShape(colors.tileRadius) }

    val (w, h) = if (variant == TileVariant.POSTER) POSTER_W to POSTER_H else LANDSCAPE_W to LANDSCAPE_H

    Column(modifier = Modifier.width(w).scale(scale)) {
        Box(
            modifier = Modifier
                .width(w)
                .height(h)
                .shadow(
                    elevation = shadowElevation,
                    shape = tileShape,
                    clip = false,
                    ambientColor = colors.focusGlow,
                    spotColor = colors.focusGlow,
                )
                .clip(tileShape)
                .border(borderWidth, colors.focusRing, tileShape)
                .dpadFocusable(
                    focusRequester = focusRequester,
                    onFocused = { focused = true; onFocused() },
                    onBlurred = { focused = false },
                    onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
                ),
        ) {
            if (posterUrl != null) {
                RemoteImage(
                    url = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                // HSL-style gradient fallback derived from title hash
                val fallbackHue = remember(title) { (title.hashCode().toLong() and 0xFFFFFFFFL) % 360L }
                val fallbackGradient = remember(fallbackHue, colors.surface) {
                    Brush.verticalGradient(
                        listOf(
                            Color.hsl(fallbackHue.toFloat(), 0.38f, 0.22f),
                            colors.surface,
                        )
                    )
                }
                Box(
                    modifier = Modifier.matchParentSize().background(fallbackGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title.take(2).uppercase(),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = sora,
                    )
                }
            }

            // Progress bar (bottom, overlaid)
            if (progressPct > 0f && !watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(colors.progressBg),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressPct)
                            .height(4.dp)
                            .background(colors.progressFill),
                    )
                }
            }

            // Watched circle badge (bottom-end)
            if (watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(colors.badgeWatched, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // "NEW" gradient badge (top-start)
            if (isNew && !watched) {
                val newGradient = remember(colors.accent, colors.accentSecondary) {
                    colors.accentGradient
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(newGradient, RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "NEW",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = sora,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            color = if (focused) colors.text else colors.textSecondary,
            fontSize = 18.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = sora,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(w),
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                color = colors.textDim,
                fontSize = 14.sp,
                fontFamily = sora,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(w),
            )
        }
    }
}
