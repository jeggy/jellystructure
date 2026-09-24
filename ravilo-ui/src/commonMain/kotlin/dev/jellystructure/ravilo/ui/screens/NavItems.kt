package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
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

/**
 * R170 — the segments folded under the single Discover tab. R243 added the three taxonomy walls.
 *
 * R268 — **reordered**, library first. The enum's own declaration order is the shipped order, so there
 * is only ever one order in this file: anything reaching for `entries` or an ordinal agrees with the
 * bar by construction. Safe to reorder — the segment travels only in `Dest.Discover`, which is
 * in-memory, and `toRoute()` renders Discover as a bare `"/discover"` with no segment component, so no
 * ordinal is persisted or serialised anywhere.
 */
enum class DiscoverSegment { NETWORKS, STUDIOS, GENRES, COMING_SOON, REQUEST }

/**
 * R268 (FR-R268-1) — the one declared order: **Networks · Studios · Genres · Coming Soon · Request**.
 *
 * Why this order, so it is not re-litigated: Networks and Studios are the two walls a viewer browses by
 * habit ("what's on DR?", "the Pixar shelf"); Genres is the widest and least specific of the three, so
 * it follows them; Coming Soon is about titles the household does not have yet, and Request is about
 * asking for one. Left to right the strip runs from what you own to what you don't, and the first chip
 * is the same one on every household.
 */
val DISCOVER_SEGMENT_ORDER: List<DiscoverSegment> = DiscoverSegment.entries.toList()

/** R243 — the segments that index the viewer's own library. Never gated.
 *
 *  R268 (dev review item 2) — a **gating set**, never an order. It used to be a `List` spliced into the
 *  bar's contents, which made it a second place order was decided. */
val TAXONOMY_SEGMENTS: Set<DiscoverSegment> =
    setOf(DiscoverSegment.NETWORKS, DiscoverSegment.STUDIOS, DiscoverSegment.GENRES)

/**
 * The segment bar's contents for this household, in the order the bar shows them.
 *
 * R268 (FR-R268-2) — **gating filters the declared order; it never re-orders and never assembles.**
 * That is a correctness fix as much as a cosmetic one. Three functions used to encode order
 * independently — this one, [defaultDiscoverSegment] and [nextDiscoverSegment] — and they had
 * **already drifted once**: `defaultDiscoverSegment` answered only the first two cases, so a household
 * with neither integration reached Discover and landed on a segment that was not rendered. With one
 * declared list and a filter, that entire class of bug is unrepresentable.
 */
fun discoverSegments(upcomingAvailable: Boolean, discoverAvailable: Boolean): List<DiscoverSegment> =
    DISCOVER_SEGMENT_ORDER.filter { seg ->
        when (seg) {
            DiscoverSegment.COMING_SOON -> upcomingAvailable
            DiscoverSegment.REQUEST -> discoverAvailable
            else -> seg in TAXONOMY_SEGMENTS
        }
    }

/**
 * Where pressing Discover lands: the first **available** chip.
 *
 * R268 (FR-R268-2) — derived from [discoverSegments] rather than re-deciding precedence, which is what
 * makes the two incapable of disagreeing. Since the taxonomy segments cannot be gated off, this is
 * always **Networks** now.
 *
 * ⚠ Real behaviour change for one configuration: a household with neither Sonarr/Radarr nor Seerr used
 * to land on **Studios** and now lands on **Networks**. Intended, and named here because that household
 * gets no other change from this phase and is the one most likely to notice.
 */
fun defaultDiscoverSegment(upcomingAvailable: Boolean, discoverAvailable: Boolean): DiscoverSegment =
    discoverSegments(upcomingAvailable, discoverAvailable).first()

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
 *
 * R262 (dev review item 2) — [onFocusConsumed] fires once, right after the chip actually takes focus,
 * so the caller can clear the press token on the destination that requested it (`focusSegment` lives on
 * the stack entry, not here, so it survives a drill-in/Back round trip — the token itself has to be
 * consumed there too, or a return from a seeded grid re-steals focus from R257's tile restore).
 */
@Composable
fun DiscoverSegmentBar(
    segments: List<DiscoverSegment>,
    active: DiscoverSegment,
    onSelect: (DiscoverSegment) -> Unit,
    focusActiveOnEntry: Boolean = false,
    onFocusConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val activeFR = remember { FocusRequester() }
    // R268 (FR-R268-4) — the strip scrolls rather than clipping. Five chips do not fit a portrait
    // phone, and on a TV they used to run past the `Search on Seerr` pill that shares the row. The
    // scroll is confined to this bar: it never moves the page or the nav row above it.
    val scrollState = rememberScrollState()
    // FR-R268-6/-7 — where each chip sits in the scroller, so a chip can be carried into view. Keyed
    // by segment rather than index so a gated segment appearing or disappearing cannot shift them.
    val chipBounds = remember { mutableStateMapOf<DiscoverSegment, IntRange>() }
    var viewportWidth by remember { mutableStateOf(0) }

    suspend fun revealChip(seg: DiscoverSegment, animate: Boolean) {
        val b = chipBounds[seg] ?: return
        if (viewportWidth <= 0) return
        // Leave a chip's worth of margin so the neighbour peeks — FR-R268-6's affordance, and what
        // keeps a focused chip off the very edge of the screen.
        val margin = 48
        val target = when {
            b.first - margin < scrollState.value -> b.first - margin
            b.last + margin > scrollState.value + viewportWidth -> b.last + margin - viewportWidth
            else -> return
        }.coerceIn(0, scrollState.maxValue)
        if (animate) scrollState.animateScrollTo(target) else scrollState.scrollTo(target)
    }

    LaunchedEffect(focusActiveOnEntry, active, chipBounds[active], viewportWidth) {
        if (!focusActiveOnEntry) return@LaunchedEffect
        // FR-R268-7 + dev review item 5 — SEQUENCE the scroll and the focus request; never race them.
        // This codebase has been bitten by exactly that three times: R232 (the season row's scroll and
        // focus ran as concurrent coroutines, so the first Down only *looked* like it focused), R223
        // (season-picker focus with rapid-Up stranding) and R200/R201 (a FocusRequester whose target
        // was never placed). Await the scroll, then request focus.
        revealChip(active, animate = false)
        runCatching { activeFR.requestFocus() }
        onFocusConsumed()
    }

    Row(
        modifier = modifier
            .background(colors.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .onSizeChanged { viewportWidth = it.width }
            .horizontalScroll(scrollState)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        segments.forEach { seg ->
            var focused by rememberFocusVisual()
            val isCur = seg == active
            val scope = rememberCoroutineScope()
            Text(
                text = discoverSegmentLabel(seg),
                color = if (focused) colors.background else if (isCur) colors.text else colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = if (isCur || focused) FontWeight.SemiBold else FontWeight.Medium,
                fontFamily = Sora,
                // FR-R268-5 — a chip is never shrunk, truncated or ellipsised to make five fit. The
                // strip gets longer, not denser: the 13 sp floor and the 46 dp target win over fitting
                // everything on screen at once.
                maxLines = 1,
                modifier = Modifier
                    .background(if (focused) colors.text else Color.Transparent, RoundedCornerShape(12.dp))
                    // Measured against the scroller's own content, so the bounds are scroll-independent.
                    .onPlaced { chipBounds[seg] = it.positionInParent().x.toInt()..(it.positionInParent().x.toInt() + it.size.width) }
                    .dpadFocusable(
                        focusRequester = if (isCur) activeFR else null,
                        onFocused = {
                            focused = true
                            // FR-R268-6 — D-pad Left/Right carries the newly focused chip into view,
                            // with the neighbour peeking. Focus never lands on an off-screen chip.
                            scope.launch { revealChip(seg, animate = true) }
                        },
                        onBlurred = { focused = false },
                        onSelect = { if (!isCur) onSelect(seg) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
