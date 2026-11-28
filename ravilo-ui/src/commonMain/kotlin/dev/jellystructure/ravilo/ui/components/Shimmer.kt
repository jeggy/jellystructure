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

/** Test seam: the first skeleton row (heading and tiles, not the gap above it). */
internal const val HOME_SKELETON_FIRST_ROW = "home-skeleton-first-row"

/**
 * DEB-5837 FR-3 — Home's loading page in the loaded page's shape: the hero band at [homeHeroHeight]'s height, then as many
 * rows as fill the screen, each the loaded row's own pieces — a row gap, a heading line, [raviloRowHeadPadB], and a track of
 * `Tile(placeholder = true)` at the poster size inside the track's own padding. The app bar is drawn by the caller.
 */
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

/** Test seams: the detail skeleton's hero band and its action line. */
internal const val DETAIL_SKELETON_HERO = "detail-skeleton-hero"
internal const val DETAIL_SKELETON_ACTIONS = "detail-skeleton-actions"

/**
 * Loading shell shown while MovieDetailState.Loading or SeriesDetailState.Loading is active.
 *
 * DEB-5837 FR-3 — the loaded page's own shape: a hero band that fills the visible height (R295, no fixed height), its text
 * column bottom-aligned with the loaded hero's 44 dp bottom padding, and the title, meta and action lines in that column's
 * gutters (title, 8 dp, meta, 18 dp, actions). The app bar is drawn by the caller.
 */
@Composable
fun DetailLoadingShell(card: dev.jellystructure.shared.tv.MediaCard? = null) {
    val brush = rememberShimmerBrush()
    val compact = dev.jellystructure.ravilo.ui.theme.LocalCompact.current
    Box(Modifier.fillMaxSize().testTag(DETAIL_SKELETON_HERO)) {
        ShimmerBox(modifier = Modifier.matchParentSize(), brush = brush, radius = 0f)
        // DEB-5839 (FR-4) — opened from a tile: its backdrop (warmed on open, R100), then the loaded hero's own scrims. The card
        // is shown verbatim and draws no Play, progress, watched mark or badge; the detail answer owns those.
        val backdropUrl = card?.let { it.backdropUrl ?: it.posterUrl }
        if (backdropUrl != null) dev.jellystructure.ravilo.ui.seams.RemoteImage(
            url = backdropUrl,
            contentDescription = null,
            modifier = Modifier.matchParentSize(),
            alignment = dev.jellystructure.ravilo.ui.theme.RaviloDimens.heroBackdropAlignment,
        )
        if (card != null) DetailHeroScrims()
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 44.dp)) {
            Column(Modifier.fillMaxWidth(if (compact) 1f else 0.6f).padding(horizontal = raviloHPad)) {
                // Title (the loaded title's 40 sp line, or the 80 dp logo's room is the taller of the two)
                if (card != null) TitleLogoOrText(logoUrl = null, title = card.title, logoModifier = Modifier)
                else Box(Modifier.width(400.dp).height(40.dp).skeleton(RoundedCornerShape(10.dp), brush))
                Spacer(Modifier.height(8.dp))
                // Meta line — the card's year as text, the rest (runtime, badges) when the detail lands
                val year = card?.year
                if (year != null) Text(year.toString(), color = dev.jellystructure.ravilo.ui.theme.RaviloTheme.colors.textSecondary, fontSize = 15.sp, maxLines = 1, softWrap = false)
                else Box(Modifier.width(240.dp).height(20.dp).skeleton(RoundedCornerShape(10.dp), brush))
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.padding(horizontal = raviloHPad).testTag(DETAIL_SKELETON_ACTIONS),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(Modifier.width(160.dp).height(60.dp).skeleton(RoundedCornerShape(12.dp), brush))
                Box(Modifier.width(140.dp).height(60.dp).skeleton(RoundedCornerShape(12.dp), brush))
            }
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

internal const val SETTINGS_SKELETON = "settings-skeleton"
internal const val TV_CODE_SKELETON = "tv-code-skeleton"
internal const val SEARCH_SKELETON = "search-skeleton"
internal const val MUSIC_SEARCH_SKELETON = "music-search-skeleton"

/** DEB-5837 FR-2 — Settings' wait: setting rows at the loaded row's 52 dp, under the page title (and the desktop tabs) the caller draws. */
@Composable
fun SettingsLoadingRows(rows: Int = 6) {
    val brush = rememberShimmerBrush()
    Column(Modifier.fillMaxWidth().testTag(SETTINGS_SKELETON), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { ShimmerBox(Modifier.fillMaxWidth().height(52.dp), brush) }
    }
}

/**
 * DEB-5837 FR-2 — the TV code screen's wait: the code's box at its loaded size. The footer button is the caller's and stays.
 * The numbers mirror `CodeBody` with its QR: 222 dp QR + 36 dp gap + a text column up to 530 dp, about 233 dp tall.
 */
@Composable
fun TvCodeLoadingBox() {
    ShimmerBox(Modifier.width(222.dp + 36.dp + 530.dp).height(233.dp).testTag(TV_CODE_SKELETON))
}

/** DEB-5837 FR-2 — a first query's wait: the result grid in the loaded [columns]. */
@Composable
fun SearchLoadingGrid(columns: Int) {
    Box(Modifier.fillMaxSize().testTag(SEARCH_SKELETON)) { TileShimmerGrid(columns) }
}

/** DEB-5837 FR-2 — music search's wait: the result rows of its first group. */
@Composable
fun MusicSearchLoadingRows() {
    val brush = rememberShimmerBrush()
    Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad).testTag(MUSIC_SEARCH_SKELETON), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { ShimmerBox(Modifier.fillMaxWidth().height(56.dp), brush) }
    }
}

/** DEB-5837 FR-2 — what a search last showed, so the next query loads under it and only a first query shows the skeleton. */
class LastShown<T> {
    var items: List<T> = emptyList()

    /**
     * The list a search draws. A loaded list is drawn as it is, but only a non-blank query's results are remembered (a blank
     * query's are the suggestions, which a first typed query must not load under); [loading] draws what was remembered; an error clears it.
     */
    fun resolve(loading: Boolean, loaded: List<T>?, loadedQuery: String?): List<T> {
        if (loaded != null) {
            items = if (loadedQuery.isNullOrBlank()) emptyList() else loaded
            return loaded
        }
        if (!loading) items = emptyList()
        return items
    }
}
