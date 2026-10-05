package dev.jellystructure.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 285 — the Dashboard as one overview of what could be fixed. Everything the page shows comes from here:
 * severity, order, the unit a row counts in, the sentence and what fixing means are the server's (FR-285-3);
 * the page renders and never computes. Rows at zero are not sent (FR-285-5).
 */
@Serializable
data class DashboardDto(
    val headline: DashboardHeadline = DashboardHeadline(),
    /** The domains that have at least one row, in the fixed order, with their row counts (the filter chips). */
    val domains: List<DashboardDomain> = emptyList(),
    /** Ordered critical → warning → info, then by count desc. */
    val rows: List<DashboardRow> = emptyList(),
    val since: DashboardSince? = null,
    /** False ⇒ the page shows one line for Jellyfin and this server instead of their rows (FR-285-14). */
    @SerialName("jellyfin_reachable") val jellyfinReachable: Boolean = true,
    @SerialName("first_run") val firstRun: Boolean = false,
    @SerialName("last_scan_at") val lastScanAt: Long? = null,
    @SerialName("next_scan_at") val nextScanAt: Long? = null,
)

@Serializable
data class DashboardHeadline(
    val critical: Int = 0,
    val warnings: Int = 0,
    val info: Int = 0,
    /** How many rows the list holds — the number the sidebar badge and the stat tile show (FR-285-6). */
    val rows: Int = 0,
    /** The things with a problem, summed over the rows that count something (not settings). */
    val things: Int = 0,
)

@Serializable
data class DashboardDomain(val id: String, val label: String, val rows: Int = 0)

/**
 * FR-285-2 — one row grammar: severity · label + one plain sentence · the count of the things with the problem
 * (in [unit]) · the domain · what fixing means ([fix]: `here` · `open` · `elsewhere` · `info`) with at most one
 * action — except 292's *Lyrics on an instrumental*, which amends FR-285-2 with a quieter second one ([action2]). An advisor finding carries its live value ([now]), the path to change it ([where] › [path]), what to set
 * and what you lose, and its own re-check / apply action ([actionKind] on [findingId]).
 */
@Serializable
data class DashboardRow(
    val id: String,
    val domain: String,
    val severity: String,
    val label: String,
    val sentence: String = "",
    val count: Int? = null,
    val unit: String? = null,
    val fix: String = "info",
    val action: String? = null,
    @SerialName("action_id") val actionId: String? = null,
    val href: String? = null,
    val where: String? = null,
    val path: String? = null,
    val now: String? = null,
    val recommendation: String? = null,
    val tradeoff: String? = null,
    @SerialName("action_kind") val actionKind: String? = null,
    @SerialName("finding_id") val findingId: String? = null,
    /** Phase 292 (FR-292-15, dev review 9) — a second, quieter action, also pressed by hand only. Admin-only DTO. */
    val action2: String? = null,
    @SerialName("action2_id") val action2Id: String? = null,
    /** Phase 305 (FR-305-14, dev review 9) — the row's action opens something on the page (`same_songs`: *Listen and
     *  decide*) instead of posting [actionId]. */
    val opens: String? = null,
)

@Serializable
data class DashboardSince(
    /** When this admin last opened the Dashboard (epoch seconds); null on a first visit. */
    val since: Long? = null,
    val items: List<DashboardSinceItem> = emptyList(),
)

@Serializable
data class DashboardSinceItem(val domain: String, val text: String)
