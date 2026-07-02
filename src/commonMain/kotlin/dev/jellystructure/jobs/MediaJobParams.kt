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
)
