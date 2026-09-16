package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora

/**
 * R170 — the AppBar's section tabs are a fixed Home/Movies/Series plus Discover. My List moved out of
 * the section-tab row entirely into the avatar's [dev.jellystructure.ravilo.ui.components.ProfileMenu].
 * This collapses R160/R49's old two-optional-tab (Upcoming, Top 10) index math — Discover folds
 * both destinations into one screen with an internal segment switch.
 *
 * R243 (FR-R243-1) — Discover is no longer optional. Its Coming Soon / Request segments stay gated on
 * their integrations, but the three taxonomy segments (Studios · Networks · Genres) index the library
 * the viewer already has, so the tab is always present. The old `discoverAvailable` gate is gone from
 * this function and from [raviloNavTarget]; [defaultDiscoverSegment] and [discoverSegments] are where
 * availability still matters.
 */
@Composable
fun raviloNavItems(): List<String> = listOf(str("nav.home"), str("nav.movies"), str("nav.series"), str("nav.discover"))

enum class RaviloNavTarget { HOME, MOVIES, SERIES, DISCOVER }

/** Resolves a clicked nav index (as produced by [raviloNavItems]) to a semantic target. */
fun raviloNavTarget(index: Int): RaviloNavTarget = when (index) {
    0 -> RaviloNavTarget.HOME
    1 -> RaviloNavTarget.MOVIES
    2 -> RaviloNavTarget.SERIES
    3 -> RaviloNavTarget.DISCOVER
    else -> RaviloNavTarget.HOME
}

/** R170 — the segments folded under the single Discover tab. R243 added the three taxonomy walls. */
enum class DiscoverSegment { COMING_SOON, REQUEST, STUDIOS, NETWORKS, GENRES }

/** R243 — the three segments that index the viewer's own library (never gated). */
val TAXONOMY_SEGMENTS: List<DiscoverSegment> = listOf(DiscoverSegment.STUDIOS, DiscoverSegment.NETWORKS, DiscoverSegment.GENRES)

/** The segment bar's contents for this household, in the order the bar shows them (FR-R243-1). */
fun discoverSegments(upcomingAvailable: Boolean, discoverAvailable: Boolean): List<DiscoverSegment> = buildList {
    if (upcomingAvailable) add(DiscoverSegment.COMING_SOON)
    if (discoverAvailable) add(DiscoverSegment.REQUEST)
    addAll(TAXONOMY_SEGMENTS)
}

/** Where pressing Discover lands: Coming Soon if it exists, else Request, else Studios (FR-R243-1).
 *  R243 dev review — this used to answer only the first two, so a household with neither integration
 *  would have reached Discover and landed on a segment that is not rendered. */
fun defaultDiscoverSegment(upcomingAvailable: Boolean, discoverAvailable: Boolean): DiscoverSegment = when {
    upcomingAvailable -> DiscoverSegment.COMING_SOON
    discoverAvailable -> DiscoverSegment.REQUEST
    else -> DiscoverSegment.STUDIOS
}

/** The segment after [current] in [segments] (wrapping) — what the Discover nav button does while a
 *  Discover screen is already showing, so the button is never inert under focus. */
fun nextDiscoverSegment(segments: List<DiscoverSegment>, current: DiscoverSegment): DiscoverSegment? {
    if (segments.size < 2) return null
    val i = segments.indexOf(current)
    return segments[(i + 1).mod(segments.size)]
}

@Composable
fun discoverSegmentLabel(seg: DiscoverSegment): String = when (seg) {
    DiscoverSegment.COMING_SOON -> str("seg.coming")
    DiscoverSegment.REQUEST -> str("seg.request")
    DiscoverSegment.STUDIOS -> str("seg.studios")
    DiscoverSegment.NETWORKS -> str("seg.networks")
    DiscoverSegment.GENRES -> str("seg.genres")
}

/**
 * R243 (FR-R243-1/7) — the Discover segment bar: one focusable chip per available segment, the
 * current one marked. Replaces R170's single "↔ Discover: <other>" pill now that there are up to five
 * segments. Switching tabs keeps focus on the tab you pressed — the screen that renders next passes
 * [focusActiveOnEntry] and the bar re-focuses its current chip, so the segment stays steerable and
 * Down drops into the content. Left/Right along the bar is plain Compose focus order.
 */
@Composable
fun DiscoverSegmentBar(
    segments: List<DiscoverSegment>,
    active: DiscoverSegment,
    onSelect: (DiscoverSegment) -> Unit,
    focusActiveOnEntry: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val activeFR = remember { FocusRequester() }
    LaunchedEffect(focusActiveOnEntry, active) {
        if (focusActiveOnEntry) runCatching { activeFR.requestFocus() }
    }
    Row(
        modifier = modifier
            .background(colors.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        segments.forEach { seg ->
            var focused by remember { mutableStateOf(false) }
            val isCur = seg == active
            Text(
                text = discoverSegmentLabel(seg),
                color = if (focused) colors.background else if (isCur) colors.text else colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = if (isCur || focused) FontWeight.SemiBold else FontWeight.Medium,
                fontFamily = Sora,
                maxLines = 1,
                modifier = Modifier
                    .background(if (focused) colors.text else Color.Transparent, RoundedCornerShape(12.dp))
                    .dpadFocusable(
                        focusRequester = if (isCur) activeFR else null,
                        onFocused = { focused = true },
                        onBlurred = { focused = false },
                        onSelect = { if (!isCur) onSelect(seg) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
