package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import dev.jellystructure.ravilo.ui.i18n.str

/**
 * R160/R49 — shared nav-bar item list + click-index resolver for the two *optional* tabs
 * (Upcoming, Top 10), so every screen's app bar and every `onNavSelect` handler stay in lockstep
 * without hardcoding a fixed index for either. Fixed prefix Home/Movies/Series always occupy
 * 0/1/2; My List is always last; Upcoming (when available) sits directly after Series, ahead of
 * Top 10 (also optional) — R160's "between Series and Top 10" placement.
 */
@Composable
fun raviloNavItems(upcomingAvailable: Boolean, discoverAvailable: Boolean): List<String> = buildList {
    add(str("nav.home")); add(str("nav.movies")); add(str("nav.series"))
    if (upcomingAvailable) add(str("nav.upcoming"))
    if (discoverAvailable) add("Top 10")
    add(str("nav.my_list"))
}

enum class RaviloNavTarget { HOME, MOVIES, SERIES, UPCOMING, DISCOVER, MY_LIST }

/** Resolves a clicked nav index (as produced by [raviloNavItems] for the *same* two flags) to a
 *  semantic target — a click always maps to what's actually shown, regardless of how many of the
 *  two optional tabs are present. */
fun raviloNavTarget(index: Int, upcomingAvailable: Boolean, discoverAvailable: Boolean): RaviloNavTarget {
    val optional = buildList {
        if (upcomingAvailable) add(RaviloNavTarget.UPCOMING)
        if (discoverAvailable) add(RaviloNavTarget.DISCOVER)
    }
    return when {
        index == 0 -> RaviloNavTarget.HOME
        index == 1 -> RaviloNavTarget.MOVIES
        index == 2 -> RaviloNavTarget.SERIES
        index - 3 in optional.indices -> optional[index - 3]
        else -> RaviloNavTarget.MY_LIST
    }
}
