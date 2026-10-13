package dev.jellystructure.filefix

import dev.jellystructure.arr.RadarrFileRow
import dev.jellystructure.model.MediaItem

/** Which `moviefile` row Radarr has the film pointing at: `0` is *Wanted/Missing* with the file still on disk. */
data class RadarrMovieLink(val movieId: Int, val fileId: Int)

/**
 * FR-314-12 (DEB-868) — the narrow, **read-only** Radarr port the kind C write path needs.
 *
 * Read-only is not a simplification: Radarr has no API that re-points a movie at a file. `PUT /movie` applies the
 * body through `Movie.ApplyChanges`, which assigns path, quality profile, monitored, minimum availability, root
 * folder, tags and add options and nothing else; `PUT /moviefile` writes quality, languages, edition, release group
 * and scene name; `DELETE /moviefile` deletes the file from disk. So the write path reads what Radarr did and says
 * so — the repair is a person in Radarr's UI (spec amendment 2026-10-13, second).
 */
interface RadarrLink {
    /** Radarr's movie id for [item] and the row it points at; null when Radarr isn't set up, the film has no TMDB
     *  id, or Radarr doesn't know it. */
    suspend fun read(item: MediaItem): RadarrMovieLink?

    /** Every `moviefile` row Radarr holds for the movie — including one it has orphaned. */
    suspend fun files(movieId: Int): List<RadarrFileRow>
}

/**
 * FR-314-12 — what Radarr has the film's file as, said in one sentence, or null when it is the original (or when
 * nothing can be read).
 *
 * [originalPath] is the film's own file and [versionPath] the version beside it. Rows are compared **by file name**:
 * Radarr's view of the library is mapped (`LooseFilmsService.toJellyfinView`), so its paths are not ours. There is
 * deliberately no "it is where it was, leave it" shortcut: after *Remove*, a link still pointing at the version
 * **is** unchanged, and is exactly what has to be said out loud.
 */
suspend fun radarrLinkDrift(
    link: RadarrLink?,
    item: MediaItem,
    originalPath: String,
    versionPath: String,
): String? {
    val now = (link ?: return null).read(item) ?: return null
    val originalName = originalPath.substringAfterLast('/')
    val versionName = versionPath.substringAfterLast('/')
    val rows = link.files(now.movieId)
    if (now.fileId == 0) {
        val ours = rows.firstOrNull { it.path.substringAfterLast('/') == originalName }
        return "Radarr has no file for this film (movieFileId 0)" +
            if (ours != null) ", though its own file row ${ours.id} still names $originalName" else ""
    }
    val linked = rows.firstOrNull { it.id == now.fileId } ?: return null   // it points at a row we can't read
    val linkedName = linked.path.substringAfterLast('/')
    return when (linkedName) {
        originalName -> null
        versionName -> "Radarr took jellystructure's version ($versionName) for the film's file, row ${linked.id}"
        else -> "Radarr's file for this film is $linkedName (row ${linked.id}), not $originalName"
    }
}
