package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
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

    fun missingOverviewCount(item: MediaItem): Int =
        if (item.kind == MediaKind.TV_SHOW) item.episodes.count { it.overview.isNullOrBlank() } else 0
}
