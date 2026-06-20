package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

/**
 * Returns a moving shimmer brush. Must NOT be wrapped in remember(progress) — the
 * brush is created each recomposition but Brush creation is lightweight and using
 * remember(progress) would allocate a new wrapper object every frame instead.
 */
@Composable
fun rememberShimmerBrush(): Brush {
    val colors = RaviloTheme.colors
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
    val base   = colors.surface
    val bright = colors.surfaceVariant
    val sweep  = 800f
    return Brush.linearGradient(
        colors = listOf(base, bright, base),
        start  = Offset(progress * sweep, 0f),
        end    = Offset((progress + 1f) * sweep, 400f),
    )
}

@Composable
private fun ShimmerBox(
    modifier: Modifier = Modifier,
    brush: Brush = rememberShimmerBrush(),
    radius: Float = 10f,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius.dp))
            .background(brush),
    )
}

/** Skeleton for a single poster tile (210×315). */
@Composable
fun TileShimmer(modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    val colors = RaviloTheme.colors
    Column(modifier = modifier.width(210.dp)) {
        ShimmerBox(
            modifier = Modifier.width(210.dp).height(315.dp),
            brush = brush,
            radius = colors.tileRadius.value,
        )
        Spacer(Modifier.height(8.dp))
        ShimmerBox(modifier = Modifier.width(150.dp).height(18.dp), brush = brush)
    }
}

/** Skeleton for a landscape tile (360×202). */
@Composable
fun LandscapeShimmer(modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    val colors = RaviloTheme.colors
    Column(modifier = modifier.width(360.dp)) {
        ShimmerBox(
            modifier = Modifier.width(360.dp).height(202.dp),
            brush = brush,
            radius = colors.tileRadius.value,
        )
        Spacer(Modifier.height(8.dp))
        ShimmerBox(modifier = Modifier.width(240.dp).height(18.dp), brush = brush)
    }
}

/** Full-screen loading shell shown while HomeState.Loading is active. */
@Composable
fun HomeLoadingShell() {
    val brush = rememberShimmerBrush()
    val colors = RaviloTheme.colors

    Column(modifier = Modifier.fillMaxWidth()) {
        // Hero skeleton
        ShimmerBox(
            modifier = Modifier.fillMaxWidth().height(600.dp),
            brush = brush,
            radius = 0f,
        )
        Spacer(Modifier.height(RaviloDimens.rowGap))

        // Two skeleton rows
        repeat(2) {
            Box(modifier = Modifier.padding(horizontal = RaviloDimens.sectionPadH)) {
                ShimmerBox(modifier = Modifier.width(200.dp).height(29.dp), brush = brush)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.padding(horizontal = RaviloDimens.trackPadH),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(RaviloDimens.itemSpacing),
            ) {
                repeat(5) { TileShimmer() }
            }
            Spacer(Modifier.height(RaviloDimens.rowGap))
        }
    }
}

/** Loading shell shown while MovieDetailState.Loading or SeriesDetailState.Loading is active. */
@Composable
fun DetailLoadingShell() {
    val brush = rememberShimmerBrush()
    val colors = RaviloTheme.colors

    Column(modifier = Modifier.fillMaxWidth()) {
        // Hero skeleton
        ShimmerBox(modifier = Modifier.fillMaxWidth().height(620.dp), brush = brush, radius = 0f)
        Spacer(Modifier.height(24.dp))
        // Title
        Box(modifier = Modifier.padding(horizontal = RaviloDimens.heroBodyStart)) {
            ShimmerBox(modifier = Modifier.width(400.dp).height(60.dp), brush = brush)
        }
        Spacer(Modifier.height(16.dp))
        // Synopsis lines
        Box(modifier = Modifier.padding(horizontal = RaviloDimens.heroBodyStart)) {
            Column {
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.8f).height(20.dp), brush = brush)
                Spacer(Modifier.height(8.dp))
                ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(20.dp), brush = brush)
            }
        }
        Spacer(Modifier.height(24.dp))
        // Buttons
        Row(
            modifier = Modifier.padding(horizontal = RaviloDimens.screenPadH),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            ShimmerBox(modifier = Modifier.width(160.dp).height(60.dp), brush = brush, radius = 12f)
            ShimmerBox(modifier = Modifier.width(140.dp).height(60.dp), brush = brush, radius = 12f)
        }
    }
}
