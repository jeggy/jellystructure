package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.config.PipelineStep
import dev.jellystructure.log.Logger

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

/** Current UTC date as "yyyy-MM-dd" derived from [epochMs] (not the wall clock — keeps this file's
 *  freshness math testable via an injected `now`, matching [yearFromEpochMs]'s own pattern). Same
 *  civil-calendar algorithm as `SonarrEnrichService.todayUtcDateString`/`UpcomingService`'s date
 *  arithmetic, duplicated rather than shared per those files' own comment: small, self-contained,
 *  file-private. */
private fun dateStringFromEpochMs(epochMs: Long): String {
    var d = (epochMs / 86_400_000L).toInt()  // days since 1970-01-01
    var y = 1970
    while (true) {
        val diy = if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 366 else 365
        if (d < diy) break
        d -= diy; y++
    }
    val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
    val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var m = 1
    for (md in monthDays) {
        if (d < md) break
        d -= md; m++
    }
    return "${y}-${m.toString().padStart(2, '0')}-${(d + 1).toString().padStart(2, '0')}"
}

/**
 * Pure age-tiered due/not-due decision (Phase 91/113, extended Phase 181/FR-181-2): picks the cadence
 * tier for [releaseYear] against [currentYear], then compares [lastExaminedMs] against `now - cadence`.
 * Separated from [computeFreshnessFilter]'s [MediaStore] I/O so the tier/threshold logic itself is
 * unit-testable without a live store.
 *
 * [isActivelyAiring] overrides the premiere-year tiering to `refreshThisYear` regardless of how old the
 * title is. Phase 181's Fjollerne incident: a show that premiered in 2005 but has a Sonarr-reported next
 * episode next week was landing in the `refreshOlder` (monthly) tier purely from its premiere year, while
 * jellystructure already held the fact that it was airing. Age is a poor proxy for "does this need
 * checking often" — activity is the actual signal.
 */
fun isDueForRecheck(
    nowMs: Long, lastExaminedMs: Long, releaseYear: Int, currentYear: Int, scanStep: PipelineStep,
    isActivelyAiring: Boolean = false,
): Boolean {
    val cadenceStr = when {
        isActivelyAiring || releaseYear >= currentYear -> scanStep.refreshThisYear
        (currentYear - releaseYear) <= 5                -> scanStep.refresh1To5y
        else                                              -> scanStep.refreshOlder
    }
    // Phase 196 (FR-196-6) — an airing show can never be starved, whatever the configured cadence says.
    // `never` on the this-year tier used to mean a series with an episode landing tomorrow was silently
    // excluded forever; a floor here costs one examination per `refreshThisYear` period at worst, and is
    // cheap insurance against a future variant of the bug this phase fixes.
    val thresh = cadenceMs(cadenceStr)
        ?: return isActivelyAiring && (nowMs - lastExaminedMs) >= AIRING_FLOOR_MS
    return (nowMs - lastExaminedMs) >= if (isActivelyAiring) minOf(thresh, AIRING_FLOOR_MS) else thresh
}

/** Phase 196 (FR-196-6) — the longest an actively-airing title may go unexamined, regardless of config. */
private const val AIRING_FLOOR_MS = 24 * 3_600_000L

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
    val today = dateStringFromEpochMs(now)
    val skipped = mutableListOf<Triple<String, Long, Boolean>>()  // Phase 196 FR-196-5: id, examinedAt, airing
    val skipJellyfinIds = store.allItems().mapNotNull { item ->
        val jid = item.jellyfinId ?: return@mapNotNull null
        // Phase 196 — "when were this title's files last examined", not "when was its row last written".
        // A null here (never examined, or reset by FR-196-4's backfill) always keeps the item.
        val lc = store.lastExaminedAt(item.id) ?: return@mapNotNull null
        val ry = item.year ?: return@mapNotNull null
        // FR-181-2: a title Sonarr says is airing again soon is "hot" regardless of premiere year —
        // ISO date strings compare correctly lexicographically, so no parsing needed.
        val isActivelyAiring = item.sonarrNextAiringDate?.let { it >= today } == true
        if (isDueForRecheck(now, lc, ry, currentYear, scanStep, isActivelyAiring)) {
            null  // due → keep
        } else {
            skipped += Triple(item.id, lc, isActivelyAiring)
            jid  // not due → skip
        }
    }.toSet()
    // Phase 196 (FR-196-5) — "504 items not due for a recheck yet" is true and useless: it cannot
    // distinguish correctly-fresh from hidden-by-a-bookkeeping-bug, which is exactly what made the
    // Fjollerne incident invisible from the outside. Name the items and the age of the value we skipped on.
    // (No DEBUG level exists in this Logger, so this is a single compact INFO line naming the five
    // longest-unexamined skips rather than one line per item — same diagnostic value, no new log level.)
    if (skipped.isNotEmpty()) {
        Logger.info(
            "Freshness: skipped ${skipped.size}; longest unexamined — " +
                skipped.sortedBy { it.second }.take(5).joinToString(", ") { (id, examinedAt, airing) ->
                    "$id ${(now - examinedAt) / 3_600_000}h${if (airing) " (airing)" else ""}"
                },
            "scan",
        )
    }
    return { jItem -> jItem.id !in skipJellyfinIds }
}
