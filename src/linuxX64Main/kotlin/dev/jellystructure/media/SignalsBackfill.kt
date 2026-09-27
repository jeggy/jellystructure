package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.needsRecommendationSignals

/**
 * Phase 269 (FR-269-3a) — titles whose recommendation signals were never fetched, from OUTSIDE a run's
 * freshness-filtered working set, for `pull_tmdb` to backfill: at most [limit] a run, the longest-unscanned
 * first. Without this the backfill moved at the speed of the recheck tiers (months for an older film).
 */
object SignalsBackfill {
    const val PER_RUN = 40

    data class Batch(val items: List<MediaItem>, val remaining: Int)

    fun pick(all: List<MediaItem>, workingSet: List<MediaItem>, limit: Int = PER_RUN): Batch {
        val inRun = workingSet.mapTo(HashSet()) { it.id }
        val candidates = all.filter { it.id !in inRun && it.needsRecommendationSignals() }.sortedBy { it.scannedAt }
        val batch = candidates.take(limit)
        return Batch(batch, candidates.size - batch.size)
    }
}
