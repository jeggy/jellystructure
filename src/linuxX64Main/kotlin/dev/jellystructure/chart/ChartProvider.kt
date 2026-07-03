package dev.jellystructure.chart

import dev.jellystructure.shared.tv.ChartListSpec
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_REALTIME
import platform.posix.clock_gettime
import platform.posix.timespec

/** Raw, pre-resolution entry as scraped/parsed from a vendor feed. */
data class RawChartEntry(
    val rank: Int,
    val title: String,
    val views: String? = null,    // formatted, non-null only for global/all-time
    val weeksOnChart: Int = 0,
    val isNew: Boolean = false,
)

/** A fetched list plus the feed's week (for week-gated refresh). */
data class ChartFetch(val week: String, val entries: List<RawChartEntry>)

/**
 * Phase 57 — the only place a chart vendor's quirks live. Adding Disney+/Max later = a new provider
 * + registration; nothing downstream changes (it all speaks [ChartListSpec] / [RawChartEntry]).
 */
interface ChartProvider {
    val id: String           // "netflix"
    val displayName: String  // "Netflix"
    val attribution: String  // "Tudum"
    fun availableLists(region: String): List<ChartListSpec>
    suspend fun fetch(spec: ChartListSpec): ChartFetch
}

class ChartRegistry(providers: List<ChartProvider>) {
    private val byId = providers.associateBy { it.id }
    fun get(id: String): ChartProvider? = byId[id]
    fun all(): List<ChartProvider> = byId.values.toList()
    fun enabled(ids: List<String>): List<ChartProvider> = ids.mapNotNull { byId[it] }
}

// Phase 132: relocated from the now-deleted StreamingAvailabilityProvider (RapidAPI removal) —
// JustWatchProvider also week-gates its refresh on this.
@OptIn(ExperimentalForeignApi::class)
internal fun weekKey(): String = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_REALTIME, ts.ptr)
    "W${ts.tv_sec / (7L * 24 * 3600)}"
}
