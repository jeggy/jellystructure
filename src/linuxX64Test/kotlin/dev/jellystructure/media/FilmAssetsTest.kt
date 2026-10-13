package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 314 (FR-314-13) — what the write path reads back off the folder before it writes anything. The names are the
 * ones on disk, so the step the admin sees says what a viewer would miss, and a whole folder reads as nothing to do.
 */
class FilmAssetsTest {
    private val root = "/lib/films"
    private var saved: List<String> = emptyList()

    @BeforeTest fun setRoots() { saved = LibraryRootsRegistry.roots; LibraryRootsRegistry.roots = listOf(root) }
    @AfterTest fun restoreRoots() { LibraryRootsRegistry.roots = saved }

    private fun film(path: String) = MediaItem(
        id = "f", title = "x", year = null, kind = MediaKind.MOVIE, path = path,
        tmdbId = null, originalLanguage = null, posterPath = null, overview = null,
        tracks = emptyList(), issueCount = 0, scannedAt = 0,
    )

    private val inFolder = "$root/Some Film (2023)/Some Film (2023).mkv"

    @Test fun `a film in its own folder misses the folder names`() {
        assertEquals(listOf("poster.jpg", "fanart.jpg", "clearlogo.png", "movie.nfo"), missingFilmAssets(film(inFolder)) { false })
        assertEquals(emptyList(), missingFilmAssets(film(inFolder)) { true })
    }

    @Test fun `only what is gone is listed`() {
        val gone = setOf("$root/Some Film (2023)/clearlogo.png", "$root/Some Film (2023)/movie.nfo")
        assertEquals(listOf("clearlogo.png", "movie.nfo"), missingFilmAssets(film(inFolder)) { it !in gone })
    }

    /** FR-316-1 — a film loose in a library root carries Jellyfin's per-file names; the folder names there would be
     *  the whole library's images, so those are the files to look for and the names to report. Its NFO is not listed
     *  at all: that path is the ROOT's `movie.nfo`, which is no more ours to write than the root's poster is. */
    @Test fun `a film loose in a library root misses its per-file names and no NFO`() {
        assertEquals(
            listOf("Some.Film.2023-poster.jpg", "Some.Film.2023-backdrop.jpg", "Some.Film.2023-logo.png"),
            missingFilmAssets(film("$root/Some.Film.2023.mkv")) { false },
        )
    }
}
