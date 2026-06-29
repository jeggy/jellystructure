package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient

enum class TileVariant { POSTER, LANDSCAPE, SQUARE }

/** Map the operator's configured tile shape (R32 §F) to a tile variant. */
fun dev.jellystructure.shared.tv.TileShape.toTileVariant(): TileVariant = when (this) {
    dev.jellystructure.shared.tv.TileShape.LANDSCAPE -> TileVariant.LANDSCAPE
    dev.jellystructure.shared.tv.TileShape.SQUARE -> TileVariant.SQUARE
    else -> TileVariant.POSTER
}

private val POSTER_W    = 155.dp
private val POSTER_H    = 232.dp
private val LANDSCAPE_W = 256.dp
private val LANDSCAPE_H = 144.dp
private val SQUARE_W    = 180.dp
private val SQUARE_H    = 180.dp

// R96: backdrop request width for LANDSCAPE tiles — covers a 256dp slot up to ~tileScale 1.25 on a
// 2× panel while staying ~9× smaller in memory/decode than the 1920px proxy default.
private const val LANDSCAPE_IMAGE_W = 640

/** R96: the proxy `?w=` a tile of [variant] requests, or null for poster/square (already ~320px).
 *  Exposed so a row's prefetch resolver requests the SAME width the tile will (shared cache key). */
fun tileRequestedWidth(variant: TileVariant): Int? =
    if (variant == TileVariant.LANDSCAPE) LANDSCAPE_IMAGE_W else null

@Composable
fun Tile(
    title: String,
    posterUrl: String?,
    focusRequester: FocusRequester? = null,
    variant: TileVariant = TileVariant.POSTER,
    subtitle: String? = null,
    progressPct: Float = 0f,
    watched: Boolean = false,
    isNew: Boolean = false,
    /** R113: small season/episode indicator (e.g. "S1:E3") overlaid on the image for TV shows in
     *  Continue Watching. Null = no badge. */
    episodeBadge: String? = null,
    onFocused: () -> Unit = {},
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    // R87: per-title placeholder tint — the poster crossfades in over a related color, not blank.
    val fallbackHue = remember(title) { (title.hashCode().toLong() and 0xFFFFFFFFL) % 360L }
    val posterPlaceholder = remember(fallbackHue) { Color.hsl(fallbackHue.toFloat(), 0.30f, 0.18f) }
    var focused by remember { mutableStateOf(false) }
    // Snappier focus feel (R43): StiffnessMedium settles fast; soft StiffnessMediumLow read laggy.
    val focusSpec = remember { RaviloMotion.focusSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.focusSpring<Dp>() }
    val scale         by animateFloatAsState(if (focused) RaviloMotion.TileFocusScale else 1f, focusSpec, label = "tileScale")
    val ringWidth     by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "tileBorder")
    val glowElevation by animateDpAsState(if (focused) 24.dp else 0.dp, dpSpec, label = "tileShadow")
    val tileShape = remember(colors.tileRadius) { RoundedCornerShape(colors.tileRadius) }

    // Operator-configured content size (R: ui_density) scales every grid/row tile uniformly.
    val tileScale = dev.jellystructure.ravilo.ui.LocalTileScale.current
    val (w, h) = when (variant) {
        TileVariant.POSTER -> POSTER_W to POSTER_H
        TileVariant.LANDSCAPE -> LANDSCAPE_W to LANDSCAPE_H
        TileVariant.SQUARE -> SQUARE_W to SQUARE_H
    }.let { (bw, bh) -> bw * tileScale to bh * tileScale }

    // R54: the focusable wraps the WHOLE tile (poster + label) so the vertical bring-into-view reveals
    // the title/subtitle below the poster instead of clipping it. R42 still holds — the Column's layout
    // size is fixed (the focus scale is a draw-only graphicsLayer on the inner poster box, so the lazy
    // list's focused-bounds tracking never chases the scale animation → no viewport jump while focusing).
    Column(
        modifier = Modifier
            .width(w)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true; onFocused() },
                onBlurred = { focused = false },
                onSelect = onSelect,
            ),
    ) {
        Box(
            modifier = Modifier
                .width(w)
                .height(h),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // R43: the whole focus animation runs in the draw phase — scale + shadow in the
                    // graphicsLayer lambda, the ring in drawWithCache — so no animated value is read at
                    // composition and the tile never recomposes per frame while focusing.
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        this.shadowElevation = glowElevation.toPx()
                        shape = tileShape
                        clip = true
                        ambientShadowColor = colors.focusGlow
                        spotShadowColor = colors.focusGlow
                    }
                    .drawWithCache {
                        val radius = CornerRadius(colors.tileRadius.toPx())
                        onDrawWithContent {
                            drawContent()
                            val bw = ringWidth.toPx()
                            if (bw > 0f) drawRoundRect(
                                color = colors.focusRing,
                                cornerRadius = radius,
                                style = Stroke(width = bw),
                                topLeft = Offset(bw / 2f, bw / 2f),
                                size = Size(size.width - bw, size.height - bw),
                            )
                        }
                    },
            ) {
            if (posterUrl != null) {
                RemoteImage(
                    url = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.matchParentSize(),
                    placeholderColor = posterPlaceholder,
                    // R96: LANDSCAPE tiles render a backdrop (proxy default 1920px) into a ~256dp slot —
                    // request ~640px so Coil decodes ~0.9 MB, not ~8.3 MB. POSTER/SQUARE already use
                    // poster-type proxy images sized to ~320px, so they pass null (backend default).
                    requestedWidth = tileRequestedWidth(variant),
                )
            } else {
                // HSL-style gradient fallback derived from title hash
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

            // R142: dim a watched poster (~brightness .62 via a dark scrim); un-dims on focus.
            if (watched && !focused) {
                Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.38f)))
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

            // R142: watched ✓ badge (top-end — opposite the top-start NEW / episode badges so they never collide).
            if (watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
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

            // R113: season/episode badge (top-start) — small dark pill over the image for TV shows in
            // Continue Watching. Neutral translucent black so it reads on any backdrop. (Continue rows
            // never set isNew, so it won't collide with the NEW badge.)
            if (episodeBadge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = episodeBadge,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = sora,
                        letterSpacing = 0.3.sp,
                    )
                }
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
