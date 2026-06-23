package dev.jellystructure.shared.tv

import kotlinx.serialization.Serializable

/**
 * Phase 56 — the one acquisition status vocabulary, shared by backend + admin + Ravilo.
 *
 * `requested`  = told Radarr/Sonarr to add+search; not yet handed to a download client (no %).
 * `queued`     = grabbed, sitting in the download client's queue, not moving yet (no %).
 * `downloading`= actively downloading (%); may also carry the `stalled`/`metadata` flags.
 * `importing`  = downloaded; *arr is renaming/moving into the library (~100, not playable yet).
 * `available`  = present in the Jellyfin library (resolved by Jellystructure).
 * `failed`     = no release found, or the *arr dropped/errored the item. Terminal until retry.
 * `stalled` is never terminal — it's a flag on `downloading`.
 */
enum class AcquisitionStatus {
    NOT_REQUESTED, REQUESTED, QUEUED, DOWNLOADING, IMPORTING, AVAILABLE, FAILED;

    val terminal: Boolean get() = this == AVAILABLE || this == FAILED || this == NOT_REQUESTED
}

@Serializable
data class AcquisitionFlags(
    val stalled: Boolean = false,    // in queue, 0 B/s, no peers — still "downloading"
    val metadata: Boolean = false,   // resolving magnet/metadata
)

/** Per-episode detail for a series acquisition (populated from the Sonarr queue). */
@Serializable
data class AcquisitionEpisodeRec(
    val season: Int,
    val episode: Int,
    val status: AcquisitionStatus,
    val progress: Int = 0,
)

/**
 * One acquisition record. For a movie it is one file → one record. For a **series** it is the parent
 * aggregate over N monitored episodes: `status` is the least-advanced *active* episode (a missing
 * episode never fails a series that has ≥1 available), `progress` is episodes_done/total-weighted,
 * and `firstAvailable` flips once the first monitored episode lands so the TV can start watching.
 */
@Serializable
data class AcquisitionRecord(
    val itemKey: String,                              // JS item id, else "tmdb:<id>"
    val mediaKind: MediaKind,                         // shared {MOVIE, SERIES}
    val status: AcquisitionStatus = AcquisitionStatus.NOT_REQUESTED,
    val tmdbId: Int? = null,
    val title: String = "",
    val progress: Int = 0,                            // 0..100, meaningful for DOWNLOADING / roll-up
    val queuePosition: Int? = null,
    val flags: AcquisitionFlags = AcquisitionFlags(),
    val episodesTotal: Int = 0,                       // series only
    val episodesDone: Int = 0,                        // series only
    val firstAvailable: Boolean = false,             // series only
    val itemId: String? = null,                       // library id once AVAILABLE
    val reason: String? = null,                       // FAILED detail
    val retryable: Boolean = false,
    val eta: String? = null,
    val downloadRate: String? = null,
    val requestedBy: String? = null,
    val episodes: List<AcquisitionEpisodeRec> = emptyList(),
)
