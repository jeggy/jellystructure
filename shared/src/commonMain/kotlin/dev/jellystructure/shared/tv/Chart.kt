package dev.jellystructure.shared.tv

import kotlinx.serialization.Serializable

/** Phase 57 — chart trend badge (computed from weekly history). */
enum class Trend { UP, DOWN, SAME, NEW }

/**
 * One chart list a provider can serve (e.g. "Top 10 Movies in Denmark"). Shared so the Ravilo config
 * editor (R50) and the discover API (R48) can enumerate/select them.
 */
@Serializable
data class ChartListSpec(
    val id: String,            // "mov-DK", "tv-DK", "mov-global", "noneng", "alltime"
    val providerId: String,    // "netflix"
    val title: String,         // human label
    val scope: String,         // "country" | "global" | "alltime"
    val category: String,      // "film" | "series"
    val metric: String,        // "rank" | "views" | "views91"
    val region: String? = null,
)

/**
 * A normalized, resolved chart entry. `views` is non-null only for global/all-time lists — country
 * feeds are ranking-only by construction (Tudum publishes no per-country view counts).
 */
@Serializable
data class ChartEntry(
    val listId: String,
    val rank: Int,
    val title: String,
    val kind: MediaKind,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val tmdbConfidence: Float = 0f,  // low scores flagged for the admin match-picker
    val itemId: String? = null,      // set when already in the library → status=available
    val weeksOnChart: Int = 0,
    val trend: Trend = Trend.SAME,
    val isNew: Boolean = false,
    val views: String? = null,       // null for country scope
    val backdropPath: String? = null,
    val posterPath: String? = null,  // portrait poster for the ranked Top 10 tiles (R49 mockup)
    val overview: String? = null,
)

// R154 — GET /api/discover/coverage?region=CC: which enabled providers/lists actually have ingested
// data for a region, so the config editor's verdict matches what the TV will actually show (same
// ChartStore data the feed itself reads — no separate hard-coded coverage table to drift out of sync).
@Serializable
data class ListCoverage(
    val listId: String,
    val title: String,
    val covered: Boolean,
    // "not_ingested" (never fetched — usually because the region isn't in discover.regions) |
    // "empty_feed" (fetched, but the vendor returned nothing for this region) | null when covered.
    val reason: String? = null,
    val entryCount: Int = 0,
)

@Serializable
data class ProviderCoverage(
    val id: String,
    val displayName: String,
    val covered: Boolean,     // true if at least one of `lists` is covered
    val lists: List<ListCoverage> = emptyList(),
)

@Serializable
data class DiscoverCoverageResponse(
    val region: String,
    val providers: List<ProviderCoverage>,
    val ingestedRegions: List<String>,
    val radarrConnected: Boolean,
)
