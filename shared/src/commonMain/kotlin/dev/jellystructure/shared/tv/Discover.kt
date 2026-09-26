@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.jellystructure.shared.tv

import kotlinx.serialization.Serializable

/**
 * R171 — a plain Seerr discover/search result, replacing the retired chart-shaped `ChartEntry`
 * (Phase 136). No rank, weeks-on-chart, trend or view-count fields — Seerr's discover feeds carry no
 * chart framing, just a catalogue entry plus its live request/availability status.
 */
@Serializable
data class RequestEntry(
    val tmdbId: Int,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS) val mediaKind: MediaKind = MediaKind.MOVIE,  // R318 (FR-R318-1) — a default, so an unknown value from a newer server falls back instead of failing the payload
    val title: String,
    val year: Int? = null,
    val genre: String? = null,
    val rating: String? = null,       // TMDB vote average, one decimal (e.g. "7.4")
    val posterPath: String? = null,   // relative TMDB path — client builds the absolute CDN URL (tmdbImg/tmdbPoster)
    val backdropPath: String? = null,
    val overview: String? = null,
)

/** R171 — one Request row: a configured Seerr feed (Phase 137) + its live entries. */
@Serializable
data class DiscoverRow(
    val feedId: String,
    val feedName: String,
    val entries: List<DiscoverEntry> = emptyList(),
)

/** R171 — a request entry with its live acquisition status merged in (what the TV renders per tile). */
@Serializable
data class DiscoverEntry(
    val entry: RequestEntry,
    val acquisition: AcquisitionRecord,
)

/**
 * R171 — the whole Request payload for the signed-in user. `available` is the server-decided gating
 * (Seerr connected + enabled); the TV shows/hides the Request segment from it. No `source`/`region` —
 * Seerr feeds aren't provider/country scoped like the retired charts were.
 *
 * Phase 139 — [languages] is the request-language catalog (flags/labels only — never the *arr
 * profile/tags/regex internals) the TV needs to render the Original/Nordic picker; empty/single ⇒ the
 * picker is skipped entirely (§A.3). [defaultLanguage] is this viewer's already-resolved default
 * (explicit per-user override → kids default → catalog default) so the client never re-implements that
 * precedence — it only needs to pre-select the matching row.
 */
@Serializable
data class DiscoverResponse(
    val available: Boolean,
    val canRequest: Boolean,
    val rows: List<DiscoverRow> = emptyList(),
    val languages: List<RequestLanguageOption> = emptyList(),
    val defaultLanguage: String? = null,
)

/** Phase 139 — one request-language choice as the TV needs it: just enough to render a flag + endonym
 *  row in the picker. The admin-configured *arr wiring (base profile/match regex/tags) never leaves
 *  the backend. A blank [flag] (used by `original`) means "show the title's own original-language flag
 *  / a globe", not a missing asset. */
@Serializable
data class RequestLanguageOption(
    val id: String,
    val label: String,
    val flag: String = "",
)

/** R171 — the dedicated detail payload for one Request entry (no playback/seasons; request + trailer-less). */
@Serializable
data class DiscoverDetail(
    val entry: RequestEntry,
    val acquisition: AcquisitionRecord,
    // R63 — TMDB enrichment; empty/null when tmdbId absent or TMDB call fails
    val genres: List<String> = emptyList(),
    val runtime: Int? = null,
    val isSeries: Boolean = false,
    val cast: List<Person> = emptyList(),
    // Phase 139 — same catalog/default as DiscoverResponse (see its doc), needed here too since this is
    // what actually backs the Request-press screen.
    val languages: List<RequestLanguageOption> = emptyList(),
    val defaultLanguage: String? = null,
)

/** Phase 56/R49 — the WS `acquisition_changed` payload envelope (record inline). */
@Serializable
data class AcquisitionChangedEnvelope(val type: String = "", val record: AcquisitionRecord)

/** R171 — `GET /tv/search/seerr` results: request tiles, never library items (mirrors [SearchResults]'s
 *  shape so the two search modes stay visually/structurally parallel). */
@Serializable
data class SeerrSearchResults(
    val query: String,
    val items: List<DiscoverEntry> = emptyList(),
)
