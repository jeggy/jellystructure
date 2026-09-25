package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.SegmentMarkers
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver

/**
 * Phase 117: the predicates behind every Library `filter=` issue-type value, kept in exact lockstep
 * with `TriageRoutes.kt`'s own detectors (which build the richer per-issue detail objects) so the
 * dashboard breakdown counts and what the Library actually shows for `?filter=<type>` never disagree.
 */
object TriageDetection {
    fun untaggedCount(item: MediaItem): Int = if (item.kind == MediaKind.TV_SHOW) {
        item.episodes.sumOf { ep -> ep.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null } }
    } else {
        item.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
    }

    fun hasCascadeMismatch(item: MediaItem): Boolean {
        if (item.languageMix || item.resolvedLanguage.isNullOrBlank()) return false
        val audioTracks = item.tracks.filter { it.kind == TrackKind.AUDIO }
        val expectedTrack = audioTracks.firstOrNull { LanguageResolver.sameLanguage(it.language, item.resolvedLanguage) } ?: return false
        val currentDefault = audioTracks.firstOrNull { it.default }
        return !(currentDefault != null && currentDefault.specifier == expectedTrack.specifier)
    }

    private fun tracksHaveMultiDefault(tracks: List<Track>): Boolean =
        tracks.count { it.kind == TrackKind.AUDIO && it.default } >= 2

    fun hasMultiDefault(item: MediaItem): Boolean = if (item.kind == MediaKind.TV_SHOW) {
        item.episodes.any { tracksHaveMultiDefault(it.tracks) }
    } else tracksHaveMultiDefault(item.tracks)

    // Phase 121: a missing plot summary isn't a real problem (Ravilo always has a still to show, via
    // TMDB or the screen-grabber) — this counts episodes with NO image on disk at all (neither), the
    // genuine "Ravilo shows a blank episode card" case. `hasStill` is a persisted, scan-refreshed flag
    // (ArtworkDownloader.stampHasStill) so this stays O(1), unlike a per-request filesystem stat.
    fun missingStillCount(item: MediaItem): Int =
        if (item.kind == MediaKind.TV_SHOW) item.episodes.count { !it.hasStill } else 0

    /** Phase 122: a relationship, not a per-item predicate — the set of item ids that share a Jellyfin
     *  id with at least one other item (both would open the same detail page). */
    fun duplicateIds(items: List<MediaItem>): Set<String> =
        items.filter { !it.jellyfinId.isNullOrBlank() }
            .groupBy { it.jellyfinId }
            .values
            .filter { it.size > 1 }
            .flatten()
            .mapTo(mutableSetOf()) { it.id }

    /** Bug fix (Ravilo auto-play-next loop): how many episode entries are redundant copies — two files
     *  claiming the same S__E__ (a folder extracted twice, or two mislabelled release files). Invisible
     *  before: nothing flagged it, while Ravilo built a rail slot for each copy, so the "next episode"
     *  after episode 1 could be episode 1 itself and the player's next-up card re-fired forever. See
     *  [DuplicateEpisodes] for how the scanner/playback API protect themselves; this is the
     *  operator-facing signal that the FILES still need fixing. */
    fun duplicateEpisodeCount(item: MediaItem): Int =
        if (item.kind == MediaKind.TV_SHOW) DuplicateEpisodes.extraCount(item.episodes) else 0

    /** Phase 152/153: an episode jellystructure could confidently number from its own filename parse
     *  (episodeNumber != null) that Jellyfin can't place — either jellystructure never resolved a
     *  jellyfinId for it, or (the far more common case, Phase 153) Jellyfin holds an item for the file
     *  but never assigned it an `IndexNumber` and never retries. Either way the episode silently drops
     *  out of Jellyfin's NextUp/Resume, and so out of Ravilo's Continue Watching. Phase 153's
     *  write_nfo/sync_jellyfin repair fixes these automatically; this count is the visibility for any
     *  that persist (e.g. a file jellystructure numbers but Jellyfin keeps rejecting). */
    fun unresolvedJellyfinIdCount(item: MediaItem): Int =
        if (item.kind == MediaKind.TV_SHOW) {
            item.episodes.count { it.episodeNumber != null && (it.jellyfinId == null || it.jellyfinIndexMissing) }
        } else 0

    /** Phase 128: an item/episode with literally zero audio tracks — most often a corrupt/truncated
     *  file (verified case: ffprobe "moov atom not found") rather than a genuinely audio-less
     *  container. `issueCount`/`untaggedCount` don't catch this — they only count UNTAGGED tracks, and
     *  a zero-track file has none to untag, so it silently looked "clean". */
    fun zeroAudioCount(item: MediaItem): Int = if (item.kind == MediaKind.TV_SHOW) {
        item.episodes.count { ep -> ep.tracks.none { it.kind == TrackKind.AUDIO } }
    } else {
        if (item.tracks.none { it.kind == TrackKind.AUDIO }) 1 else 0
    }

    // Phase 144: still-image codecs that some releases mux as a *video* track (cover art). ffprobe
    // reports these as VIDEO streams; players expose them as `video/x-unknown` and can end up with no
    // working video decoder (see specs/research-reports/ravilo-cover-art-video-track-2026-07-08.md).
    private val IMAGE_VIDEO_CODECS = setOf("png", "mjpeg", "mjpg", "jpeg", "jpg", "bmp", "gif", "webp", "tiff")

    /** Count of image-codec VIDEO tracks muxed alongside a real video track (the cover-art-as-video
     *  signature). 0 when there is no real video to pair with, so a genuinely image-only file (rare)
     *  isn't mislabelled. Attached pictures / MKV attachments are not VIDEO tracks and never match. */
    private fun tracksCoverAsVideo(tracks: List<Track>): Int {
        val videos = tracks.filter { it.kind == TrackKind.VIDEO }
        val hasRealVideo = videos.any { it.codec.lowercase() !in IMAGE_VIDEO_CODECS }
        return if (hasRealVideo) videos.count { it.codec.lowercase() in IMAGE_VIDEO_CODECS } else 0
    }

    fun coverAsVideoCount(item: MediaItem): Int = if (item.kind == MediaKind.TV_SHOW) {
        item.episodes.sumOf { tracksCoverAsVideo(it.tracks) }
    } else tracksCoverAsVideo(item.tracks)

    /** The specifier of the (first) cover image video track in a track list, or null. Used by the
     *  repair to target the exact stream to drop. */
    fun coverVideoSpecifier(tracks: List<Track>): String? {
        val videos = tracks.filter { it.kind == TrackKind.VIDEO }
        if (videos.none { it.codec.lowercase() !in IMAGE_VIDEO_CODECS }) return null
        return videos.firstOrNull { it.codec.lowercase() in IMAGE_VIDEO_CODECS }?.specifier
    }

    // Phase 150 (FR-SEG1-8), rewritten Phase 163 off the media_segment table — two triage rows for
    // Skip Intro/Credits: "worth an eyeball" (a heuristic guess below the trust threshold) and
    // "nothing detected at all" (Ravilo falls back to its fixed end-of-file heuristic).
    private const val LOW_CONFIDENCE_THRESHOLD = 0.60

    private fun isLowConfidenceRow(row: MediaSegmentRow): Boolean =
        row.source == SegmentSource.HEURISTIC && (row.confidence ?: 1.0) < LOW_CONFIDENCE_THRESHOLD

    /** True if any of this episode's/movie's own segment rows is a heuristic guess below the trust
     *  threshold — the per-episode/per-movie triage-row check (pass the result of
     *  [MediaSegmentStore.segmentsForEpisode]/[MediaSegmentStore.segmentsForItem]). */
    fun isLowConfidenceSegments(rows: List<MediaSegmentRow>): Boolean = rows.any(::isLowConfidenceRow)

    /** Episode/movie-level count of heuristic segment guesses below the trust threshold. */
    fun lowConfidenceSegmentsCount(item: MediaItem, segmentStore: MediaSegmentStore): Int = if (item.kind == MediaKind.TV_SHOW) {
        item.episodes.count { ep -> isLowConfidenceSegments(segmentStore.segmentsForEpisode(item.id, ep.filename, ep.episodeNumber ?: 0)) }
    } else if (isLowConfidenceSegments(segmentStore.segmentsForItem(item.id))) 1 else 0

    private fun hasSegmentData(rows: List<MediaSegmentRow>): Boolean =
        rows.any { it.kind == SegmentKind.INTRO || it.kind == SegmentKind.CREDITS }

    /** True once ANY usable marker exists anywhere on the title — the whole series for a TV show (one
     *  episode with data is enough; per-episode granularity isn't useful for a title-level flag), or the
     *  movie itself. Also what `media.has_segments` mirrors — see [MediaSegmentStore]'s own
     *  point-update, which is the actual source of truth for that column now, not this function
     *  (kept here only for triage's own live per-item checks, which don't touch the DB column). */
    fun hasAnySegments(item: MediaItem, segmentStore: MediaSegmentStore): Boolean = if (item.kind == MediaKind.TV_SHOW) {
        item.episodes.any { ep -> hasSegmentData(segmentStore.segmentsForEpisode(item.id, ep.filename, ep.episodeNumber ?: 0)) }
    } else hasSegmentData(segmentStore.segmentsForItem(item.id))

    fun hasNoSegments(item: MediaItem, segmentStore: MediaSegmentStore): Boolean = !hasAnySegments(item, segmentStore)

    /** Phase 201 amendment (2026-09-13): the Tracks-after-Cluster defect (see [MkvLayout]), folded into
     *  the standard triage framework. [broken] is a [MkvHealthCache] snapshot — unlike every other
     *  predicate here this can't be computed from the item's own already-known fields, it needs a file
     *  header read, hence the externally-supplied set rather than an in-place check. */
    fun mkvLayoutBrokenCount(item: MediaItem, broken: Set<String>): Int = if (item.kind == MediaKind.TV_SHOW) {
        item.episodes.count { it.path in broken }
    } else if (item.path in broken) 1 else 0

    /** Phase 254 (FR-254-7) — files a deep check found damaged past the first `Cluster`. Same shape as
     *  [mkvLayoutBrokenCount] for the same reason: the answer is not in the item. */
    fun fileDamageCount(item: MediaItem, damaged: Set<String>): Int = mkvLayoutBrokenCount(item, damaged)

    /** Phase 255 (FR-255-7) — files whose coverage check found a short track (`track_ends_early`) or a
     *  wrong header (`duration_header_wrong`); [flagged] is the matching path set. Same shape again. */
    fun trackCoverageCount(item: MediaItem, flagged: Set<String>): Int = mkvLayoutBrokenCount(item, flagged)
}
