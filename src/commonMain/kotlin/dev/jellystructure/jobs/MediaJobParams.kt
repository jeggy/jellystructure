package dev.jellystructure.jobs

import kotlinx.serialization.Serializable

/**
 * Phase 109 — op-specific args for one `media_job` row, JSON-encoded into its `params` column. Flat
 * and nullable-per-field (rather than a sealed hierarchy) so it round-trips through the same permissive
 * `Json { ignoreUnknownKeys = true }` used everywhere else in this codebase with no discriminator setup.
 */
@Serializable
data class MediaJobParams(
    val kind: String? = null,               // "audio" | "subtitle" — track kind for reorder/remove
    val order: List<String>? = null,         // reorder: target specifiers in the new order
    val specifier: String? = null,           // remove: which track specifier to drop
    val episodeFilename: String? = null,     // reorder/remove target: null = the item itself (movie),
                                              // non-null = one episode of a series
    val bulkScope: String? = null,           // bulk_reorder: "series" | "season-N"
    val bulkOptIn: List<String>? = null,     // bulk_reorder: partial-match episode filenames to include
    val bulkSetDefault: Boolean? = null,     // bulk_reorder: also flip disposition:default post-reorder

    // Phase 164 — segments_movie/segments_season/segments_episodes (the segments lane). mediaId (the
    // media_job row's own media_id column) is always the owning movie/series id; these carry the rest
    // of the work unit. segments_movie needs neither field. segments_episodes' keys are the same
    // "filename#episodeNumber" composite PipelineStepOps.detectIntroFingerprintsForSeason's own `key(ep)`
    // uses, so a job runner can match them straight back to Episode objects with no extra lookup shape.
    val segmentSeason: Int? = null,          // segments_season: which season (0 = specials)
    val segmentEpisodeKeys: List<String>? = null, // segments_episodes: "filename#episodeNumber" composite keys
    // false (routine scheduled pass, from the pipeline's own enqueue): re-derive only a still-empty,
    // unlocked kind. true (an operator's explicit "detect again" in the segment editor): re-derive even
    // over an existing unlocked value — PipelineStepOps' own per-kind lock check still applies either way.
    val segmentForce: Boolean = false,
    // Phase 178 §FR-178-2 — true for a segments job enqueued by a scheduled/event-driven pipeline run;
    // the segments-lane worker (MediaJobQueue.segmentsWorkerLoop) skips claiming it while a TV is
    // playing (dev.jellystructure.tv.isPlaybackActive()), picking the next non-deferrable queued job
    // instead. False (the default) for every OTHER enqueue path — most importantly an operator's
    // explicit "detect again" in the segment editor — which per the phase's invariant must never be
    // silently deferred.
    val deferWhilePlaying: Boolean = false,
)
