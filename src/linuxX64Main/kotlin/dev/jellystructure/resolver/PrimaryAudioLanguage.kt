package dev.jellystructure.resolver

import dev.jellystructure.config.AppConfig
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind

/**
 * The metadata-fetch language implied by a file's current audio-track order.
 *
 * Mirrors the base priority `Scanner.rescanMetadata` derives: the first audio track's language tag
 * (normalized to ISO 639-1) with the per-library (or global) fallback appended, then the head of the
 * list. After an audio reorder this is written to `MediaItem.resolvedLanguage`/`Episode.resolvedLanguage`
 * so the next TMDB re-pull queries in the new primary language. Without it, the stale auto-resolved
 * value is treated as a user override and prepended to the priority list, so reordering audio could
 * never change which language metadata is fetched in.
 *
 * @param path the media file path, used to find its configured library's fallback language.
 */
fun primaryAudioLanguage(config: AppConfig, path: String, tracks: List<Track>): String? {
    val globalFallback = config.languageRules.fallbackLanguage
    val lib = config.libraries.firstOrNull { lib ->
        val prefix = lib.localPath.ifBlank { lib.jellyfinPath }
        prefix.isNotBlank() && path.startsWith(prefix)
    }
    val fallback = lib?.fallbackLanguage?.ifBlank { null } ?: globalFallback
    val audioLangs = tracks.filter { it.kind == TrackKind.AUDIO }.map { it.language }
    return LanguageResolver.priorityList(audioLangs, fallback).firstOrNull()
}
