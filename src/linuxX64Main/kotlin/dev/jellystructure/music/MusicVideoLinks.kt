package dev.jellystructure.music

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.MusicArtist

/**
 * Phase 277 (FR-277-9) — an artist's music videos and concert films, which stay video-mode content in the `media`
 * table (phase 168, owner decision 2026-09-27). The link is the artist: a `MUSIC_VIDEO` row's artist (168's
 * `Artist - Title` filename parse, stored in `director`) against the artist's name, sort name or MusicBrainz aliases,
 * compared on letters and digits only. The two storage models are never merged; this is a read.
 */
object MusicVideoLinks {
    private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    fun forArtist(artist: MusicArtist, items: Collection<MediaItem>): List<MediaItem> {
        val names = (listOf(artist.name, artist.sortName, artist.mbSortName) + artist.aliases).map { norm(it) }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return emptyList()
        return items.filter { it.kind == MediaKind.MUSIC_VIDEO && !it.missingFromSource && norm(it.director) in names }
            .sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { it.title }))
    }
}
