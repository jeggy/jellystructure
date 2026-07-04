package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora

/**
 * R170 — the AppBar's section tabs are now a fixed Home/Movies/Series plus exactly one optional
 * tab, Discover (shown when either Sonarr/Radarr's Upcoming calendar or the Seerr Request feeds are
 * available — see [dev.jellystructure.ravilo.ui.screens.HomeStore]). My List moved out of the
 * section-tab row entirely into the avatar's [dev.jellystructure.ravilo.ui.components.ProfileMenu].
 * This collapses R160/R49's old two-optional-tab (Upcoming, Top 10) index math — Discover now folds
 * both destinations into one screen with an internal Coming Soon / Request segment switch.
 */
@Composable
fun raviloNavItems(discoverAvailable: Boolean): List<String> = buildList {
    add(str("nav.home")); add(str("nav.movies")); add(str("nav.series"))
    if (discoverAvailable) add(str("nav.discover"))
}

enum class RaviloNavTarget { HOME, MOVIES, SERIES, DISCOVER }

/** Resolves a clicked nav index (as produced by [raviloNavItems] for the same flag) to a semantic
 *  target — a click always maps to what's actually shown. */
fun raviloNavTarget(index: Int, discoverAvailable: Boolean): RaviloNavTarget = when {
    index == 0 -> RaviloNavTarget.HOME
    index == 1 -> RaviloNavTarget.MOVIES
    index == 2 -> RaviloNavTarget.SERIES
    index == 3 && discoverAvailable -> RaviloNavTarget.DISCOVER
    else -> RaviloNavTarget.HOME
}

/** R170 — the two segments folded under the single Discover tab. */
enum class DiscoverSegment { COMING_SOON, REQUEST }

/** Prefers Coming Soon (real Sonarr/Radarr calendar data) over Request (empty until R171 wires real
 *  Seerr-backed rows) when both segments happen to be available. */
fun defaultDiscoverSegment(upcomingAvailable: Boolean, discoverAvailable: Boolean): DiscoverSegment =
    if (upcomingAvailable) DiscoverSegment.COMING_SOON else DiscoverSegment.REQUEST

/** A small "switch to the other segment" affordance shown at the top of whichever of
 *  [UpcomingScreen]/[DiscoverScreen] is currently active, only when the other segment is also
 *  available (single-segment Discover shows no switcher at all). */
@Composable
fun DiscoverSegmentPill(other: String, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    Text(
        text = "↔ ${str("nav.discover")}: $other",
        color = if (focused) colors.background else colors.textSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = Sora,
        modifier = Modifier
            .background(if (focused) colors.text else colors.surfaceVariant, RoundedCornerShape(20.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(20.dp)) else Modifier)
            .dpadFocusable(
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
