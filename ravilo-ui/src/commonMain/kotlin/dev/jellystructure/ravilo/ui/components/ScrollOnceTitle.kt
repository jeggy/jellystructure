package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.theme.LocalReducedMotion
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** DEB-2204 (FR-6) — the numbers of a long title's one run across a TV grid tile. */
internal object TitleScroll {
    /** Focus must rest this long before the full title replaces the ellipsis. */
    const val START_DELAY_MS = 1_000L
    /** The slowest the title may move (dp/s). */
    const val MIN_SPEED_DP_PER_S = 40f
    /** The longest the move to the last character may take, however long the title is. */
    const val MAX_TRAVEL_MS = 3_500   // so a long title moves faster than MIN_SPEED_DP_PER_S rather than longer
    const val HOLD_MS = 1_000L
    const val RETURN_MS = 300
}

/**
 * DEB-2204 (FR-6) — a tile's title that scrolls once per focus arrival when it does not fit [width].
 *
 * - **Rest:** the one-line ellipsised title, exactly what `Tile` drew before.
 * - **Run:** after [TitleScroll.START_DELAY_MS] of resting focus, if the title overflowed, the full title replaces the
 *   ellipsis (clipped to [width]), moves left until its last character shows, holds [TitleScroll.HOLD_MS], returns over
 *   [TitleScroll.RETURN_MS] on [RaviloMotion.EASE], and the ellipsis comes back. It does not repeat while focus rests.
 * - **Leaving:** when [focused] goes false the ellipsis is back in that same composition and the run is cancelled, so
 *   a pending start never fires.
 * - **Draw-only (FR-3):** the movement is a `graphicsLayer` translation of a float read only in that lambda. The
 *   composition changes twice per run — the swap to the full title and the swap back — never per frame. This is not
 *   `basicMarquee`, which ignores reduced motion.
 * - **Reduced motion (FR-4):** under [LocalReducedMotion] it never starts.
 *
 */
@Composable
internal fun ScrollOnceTitle(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily,
    width: Dp,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val armed = focused && !reduced
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
    val minSpeedPx = with(density) { TitleScroll.MIN_SPEED_DP_PER_S.dp.toPx() }

    // Written from layout, read only by the run below: neither one recomposes anything.
    var overflows by remember(text) { mutableStateOf(false) }
    var fullWidthPx by remember(text) { mutableIntStateOf(0) }
    // The one composition-visible state: true between the swap to the full title and the swap back.
    var running by remember(text) { mutableStateOf(false) }
    // 0..1 along the travel; read only in the graphicsLayer lambda.
    val progress = remember(text) { mutableFloatStateOf(0f) }

    LaunchedEffect(armed, text) {
        progress.floatValue = 0f
        if (!armed) return@LaunchedEffect
        delay(TitleScroll.START_DELAY_MS)
        if (!overflows) return@LaunchedEffect          // a title that fits never moves
        try {
            running = true
            val full = snapshotFlow { fullWidthPx }.first { it > 0 }
            val travelPx = (full - widthPx).coerceAtLeast(0f)
            if (travelPx > 0f) {
                val ms = (travelPx / minSpeedPx * 1000f).toInt().coerceIn(1, TitleScroll.MAX_TRAVEL_MS)
                animate(0f, 1f, animationSpec = tween(ms, easing = LinearEasing)) { v, _ -> progress.floatValue = v }
                delay(TitleScroll.HOLD_MS)
                animate(1f, 0f, animationSpec = tween(TitleScroll.RETURN_MS, easing = RaviloMotion.EASE)) { v, _ -> progress.floatValue = v }
            }
        } finally {
            progress.floatValue = 0f
            running = false
        }
    }

    if (armed && running) {
        Box(modifier.width(width).clipToBounds()) {
            Text(
                text = text,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                fontFamily = fontFamily,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                onTextLayout = { fullWidthPx = it.size.width },
                modifier = Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -progress.floatValue * (fullWidthPx - widthPx).coerceAtLeast(0f) },
            )
        }
    } else {
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Any of the three: which one reports an ellipsised line differs by platform (desktop: hasVisualOverflow only).
            onTextLayout = { overflows = it.hasVisualOverflow || it.isLineEllipsized(0) || it.getLineEnd(0, visibleEnd = true) < text.length },
            modifier = modifier.width(width),
        )
    }
}
