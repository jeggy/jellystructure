package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.theme.raviloItemSpacing
import dev.jellystructure.ravilo.ui.theme.raviloRowGap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import dev.jellystructure.ravilo.ui.i18n.str
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import dev.jellystructure.ravilo.ui.theme.raviloTrackPadV
import dev.jellystructure.ravilo.ui.theme.raviloRowHeadPadB
import dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.screens.homeHeroHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

/**
 * Returns a moving shimmer brush. Must NOT be wrapped in remember(progress) — the
 * brush is created each recomposition but Brush creation is lightweight and using
 * remember(progress) would allocate a new wrapper object every frame instead.
 */
/** The app's one window visibility signal, shared by every skeleton. */
val LocalShimmerOnScreen = staticCompositionLocalOf { true }

@Composable
fun rememberShimmerBrush(): Brush {
    val colors = RaviloTheme.colors
    if (LocalReducedMotion.current || !LocalShimmerOnScreen.current) return SolidColor(colors.surface)
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1.5f,
        targetValue  = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
    return remember(transition, colors.surface, colors.surfaceVariant) {
        SweepBrush(progress = { progress }, base = colors.surface, bright = colors.surfaceVariant)
    }
}

/**
 * DEB-5837 FR-1 — the animated brush carries its progress as a lambda, never a value: [Modifier.skeleton] reads it inside
 * its draw lambda, so no frame recomposes. (A [ShaderBrush] caches its shader per size, so it cannot animate on its own.)
 */
internal class SweepBrush(val progress: () -> Float, val base: Color, val bright: Color) : ShaderBrush() {
    fun at(p: Float): Brush = Brush.linearGradient(
        colors = listOf(base, bright, base),
        start = Offset(p * 800f, 0f),
        end = Offset((p + 1f) * 800f, 400f),
    )

    override fun createShader(size: Size): Shader = LinearGradientShader(
        from = Offset(progress() * 800f, 0f),
        to = Offset((progress() + 1f) * 800f, 400f),
        colors = listOf(base, bright, base),
    )
}

/** DEB-5837 FR-1 — the one skeleton primitive: [shape] filled with the shimmer brush. Never focusable. */
@Composable
fun Modifier.skeleton(shape: Shape, brush: Brush = rememberShimmerBrush()): Modifier =
    if (brush is SweepBrush) clip(shape).drawBehind { drawRect(brush.at(brush.progress())) }
    else clip(shape).background(brush)

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    brush: Brush = rememberShimmerBrush(),
    radius: Float = 10f,
) {
    Box(
        modifier = modifier.skeleton(RoundedCornerShape(radius.dp), brush),
    )
}

/**
 * DEB-4091 FR-1 — the film grids' loading view: poster shimmers in the loaded grid's [columns], as many rows as fill
 * the page. Replaces the centred *Loading* line on the desktop Library and the phone's seeded grids.
 */
@Composable
fun TileShimmerGrid(columns: Int, modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    val colors = RaviloTheme.colors
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val cols = columns.coerceAtLeast(1)
        val width = (maxWidth - raviloHPad * 2 - raviloItemSpacing * (cols - 1)) / cols
        val rowHeight = width * 1.5f + 26.dp + raviloRowGap
        val rows = maxOf(1, (maxHeight / rowHeight).toInt() + 1)
        Column(
            Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true).padding(horizontal = raviloHPad, vertical = raviloTrackPadV),
            verticalArrangement = Arrangement.spacedBy(raviloRowGap),
        ) {
            repeat(rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(raviloItemSpacing)) {
                    repeat(cols) {
                        Column(Modifier.width(width)) {
                            ShimmerBox(Modifier.width(width).height(width * 1.5f), brush, colors.tileRadius.value)
                            Spacer(Modifier.height(8.dp))
                            ShimmerBox(Modifier.width(width * 0.7f).height(18.dp), brush)
                        }
                    }
                }
            }
        }
    }
}

/**
 * DEB-5837 FR-3 — Home's loading page in the loaded page's shape: the hero band at [homeHeroHeight]'s height, then as many
 * rows as fill the screen, each the loaded row's own pieces — a row gap, a heading line, [raviloRowHeadPadB], and a track of
 * `Tile(placeholder = true)` at the poster size inside the track's own padding. The app bar is drawn by the caller.
 */
/** Test seam: the first skeleton row (heading and tiles, not the gap above it). */
internal const val HOME_SKELETON_FIRST_ROW = "home-skeleton-first-row"

@Composable
fun HomeLoadingShell() {
    val brush = rememberShimmerBrush()
    val family = LocalLayoutFamily.current
    val heroHeight = homeHeroHeight()
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val screenHeight = maxHeight
        Column(Modifier.fillMaxWidth().wrapContentHeight(align = Alignment.Top, unbounded = true)) {
            ShimmerBox(modifier = Modifier.fillMaxWidth().height(heroHeight), brush = brush, radius = 0f)
            // A row is at least its gap, heading and a poster tile; one more than fits keeps the page full to the foot.
            val rows = maxOf(1, ((screenHeight - heroHeight) / 240.dp).toInt() + 1)
            repeat(rows) { i ->
                Spacer(Modifier.height(raviloRowGap))
                Column(if (i == 0) Modifier.testTag(HOME_SKELETON_FIRST_ROW) else Modifier) {
                Text(
                    text = " ",
                    fontSize = rowHeadingSize(family),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = SpaceGrotesk,
                    letterSpacing = (-0.3).sp,
                    modifier = Modifier.padding(horizontal = raviloHPad).width(200.dp).skeleton(RoundedCornerShape(10.dp), brush),
                )
                Spacer(Modifier.height(raviloRowHeadPadB))
                Row(
                    Modifier.padding(horizontal = raviloHPad, vertical = raviloTrackPadV),
                    horizontalArrangement = Arrangement.spacedBy(raviloItemSpacing),
                ) {
                    repeat(8) { Tile(title = "", posterUrl = null, variant = TileVariant.POSTER, placeholder = true) }
                }
                }
            }
        }
    }
}

/** Loading shell shown while MovieDetailState.Loading or SeriesDetailState.Loading is active. */
@Composable
fun DetailLoadingShell() {
    val brush = rememberShimmerBrush()

    Column(modifier = Modifier.fillMaxWidth()) {
        // Hero skeleton
        ShimmerBox(modifier = Modifier.fillMaxWidth().height(620.dp), brush = brush, radius = 0f)
        Spacer(Modifier.height(24.dp))
        // Title
        Box(modifier = Modifier.padding(horizontal = raviloHPad)) {
            ShimmerBox(modifier = Modifier.width(400.dp).height(60.dp), brush = brush)
        }
        Spacer(Modifier.height(16.dp))
        // Synopsis lines
        Box(modifier = Modifier.padding(horizontal = raviloHPad)) {
            Column {
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.8f).height(20.dp), brush = brush)
                Spacer(Modifier.height(8.dp))
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(20.dp), brush = brush)
            }
        }
        Spacer(Modifier.height(24.dp))
        // Buttons
        Row(
            modifier = Modifier.padding(horizontal = raviloHPad),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            ShimmerBox(modifier = Modifier.width(160.dp).height(60.dp), brush = brush, radius = 12f)
            ShimmerBox(modifier = Modifier.width(140.dp).height(60.dp), brush = brush, radius = 12f)
        }
    }
}

/** R207 — shared movie/series detail Error state: message + a focusable Retry button, so a failed
 *  load is never a dead end. Mirrors HomeScreen's HomeErrorState, minus its Sign-out action (specific
 *  to Home's auth-invalidation case, not applicable to a single title's detail fetch). */
@Composable
fun DetailErrorState(kind: LoadErrorKind, onRetry: () -> Unit, onBack: (() -> Unit)? = null) {
    LoadErrorState(kind, onRetry = onRetry, onBack = onBack)
}
