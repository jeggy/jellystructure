package dev.jellystructure.shared.tv

import kotlinx.serialization.Serializable

/** R48 — a chart entry with its live acquisition status merged in (what the TV renders per tile). */
@Serializable
data class DiscoverEntry(
    val entry: ChartEntry,
    val acquisition: AcquisitionRecord,
)

@Serializable
data class DiscoverRow(
    val spec: ChartListSpec,
    val entries: List<DiscoverEntry> = emptyList(),
)

/**
 * R48 — the whole Discover payload for the signed-in user. `available` is the server-decided gating
 * (discover.enabled + non-empty lists + the serving *arr enabled); the TV shows/hides its tab from it.
 */
@Serializable
data class DiscoverResponse(
    val available: Boolean,
    val source: String,
    val region: String,
    val canRequest: Boolean,
    val rows: List<DiscoverRow> = emptyList(),
)

/** R48 — the dedicated detail payload for one Discover entry (no playback/seasons; request + trailer-less). */
@Serializable
data class DiscoverDetail(
    val entry: ChartEntry,
    val acquisition: AcquisitionRecord,
    val sourceLabel: String,
    val attribution: String,
)

/** Phase 56/R49 — the WS `acquisition_changed` payload envelope (record inline). */
@Serializable
data class AcquisitionChangedEnvelope(val type: String = "", val record: AcquisitionRecord)
