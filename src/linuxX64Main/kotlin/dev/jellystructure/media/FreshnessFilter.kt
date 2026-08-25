package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.config.PipelineStep

/** ms duration for a freshness cadence string: "daily", "weekly", "monthly", "6months", "yearly", "never" */
fun cadenceMs(cadence: String): Long? = when (cadence.trim().lowercase()) {
    "daily"   -> 24 * 3_600_000L
    "weekly"  -> 7  * 24 * 3_600_000L
    "monthly" -> 30 * 24 * 3_600_000L
    "6months" -> 180 * 24 * 3_600_000L
    "yearly"  -> 365 * 24 * 3_600_000L
    "never"   -> null  // never recheck
    else      -> 30 * 24 * 3_600_000L  // default monthly
}

/** Approximate current calendar year from epoch ms (leap-year-agnostic, ±1 day error OK). */
fun yearFromEpochMs(epochMs: Long): Int = ((epochMs / 1000L) / 31_557_600L + 1970).toInt()

/**
 * Pure age-tiered due/not-due decision (Phase 91/113): picks the cadence tier for [releaseYear] against
 * [currentYear], then compares [lastCheckedMs] against `now - cadence`. Separated from
 * [computeFreshnessFilter]'s [MediaStore] I/O so the tier/threshold logic itself is unit-testable
 * without a live store.
 */
fun isDueForRecheck(nowMs: Long, lastCheckedMs: Long, releaseYear: Int, currentYear: Int, scanStep: PipelineStep): Boolean {
    val cadenceStr = when {
        releaseYear >= currentYear           -> scanStep.refreshThisYear
        (currentYear - releaseYear) <= 5      -> scanStep.refresh1To5y
        else                                   -> scanStep.refreshOlder
    }
    val thresh = cadenceMs(cadenceStr) ?: return false  // "never" → never due
    return (nowMs - lastCheckedMs) >= thresh
}

/**
 * Phase 175 — the age-tiered freshness/cooldown filter (Phase 91/113), extracted out of
 * `executePipeline` so every [RunTarget.Library] trigger (manual "Scan library" click, startup scan,
 * scheduler, pipeline run — not just a configured pipeline run) can honor it uniformly.
 *
 * Returns `null` (no filtering — process everything) when [fullRun], when [scanStep]'s
 * `recheck_unchanged` toggle is off, or when [target] is a [RunTarget.SingleItem] — a realtime webhook
 * fired because *that exact item* just changed in Jellyfin/Sonarr/Radarr, so "is it due for a periodic
 * recheck" doesn't apply to it.
 *
 * Safe-by-construction for brand-new items: the skip-set below is built by scanning [store]'s
 * already-known items to find Jellyfin IDs to *exclude* from the worklist. An item Jellyfin has that
 * jellystructure doesn't know about yet can never land in that skip-set, so it's always processed.
 */
suspend fun computeFreshnessFilter(
    scanStep: PipelineStep,
    store: MediaStore,
    fullRun: Boolean,
    target: RunTarget,
): ((JellyfinItem) -> Boolean)? {
    if (fullRun || !scanStep.recheckUnchanged || target is RunTarget.SingleItem) return null

    val now = store.nowMs()
    val currentYear = yearFromEpochMs(now)
    val skipJellyfinIds = store.allItems().mapNotNull { item ->
        val jid = item.jellyfinId ?: return@mapNotNull null
        val lc = store.lastChecked(item.id) ?: return@mapNotNull null
        val ry = item.year ?: return@mapNotNull null
        if (isDueForRecheck(now, lc, ry, currentYear, scanStep)) null else jid  // due → keep; not due → skip
    }.toSet()
    return { jItem -> jItem.id !in skipJellyfinIds }
}
