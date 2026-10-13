package dev.jellystructure.filefix

import dev.jellystructure.auth.JellyfinLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 314 (FR-314-15, DEB-875) — test 13's pure half: who gets the versions library excluded from their home
 * screen, who is reported as unable to see it, and which Jellyfin library the configured root belongs to.
 *
 * The facts underneath were measured in a throwaway `jellyfin/jellyfin:12.1`: a new user comes back
 * `EnableAllFolders: true` with `MyMediaExcludes: []`, so access needs no write and the display lists do.
 */
class VersionsLibraryViewersTest {

    private fun viewer(
        id: String,
        all: Boolean = true,
        enabled: List<String> = emptyList(),
        excludes: Boolean = false,
    ) = ViewerVisibility(id = id, name = "viewer-$id", seesAllLibraries = all, enabledLibraries = enabled, excludesVersions = excludes)

    @Test fun `a viewer who can see the versions library gets it excluded - one who cannot is reported`() {
        val lib = "versions-lib"
        val plan = planVersionsLibraryVisibility(
            listOf(
                viewer("fresh"),                                       // the default new user: all folders, no excludes
                viewer("done", excludes = true),                       // already said; nothing to write
                viewer("granted", all = false, enabled = listOf(lib)), // restricted but granted this library
                viewer("kid", all = false, enabled = listOf("films")), // restricted and NOT granted it
            ),
            lib,
        )
        assertEquals(listOf("fresh", "granted"), plan.toExclude.map { it.id })
        assertEquals(listOf("kid"), plan.restricted.map { it.id }, "reported, never granted")
        assertTrue(plan.toExclude.none { it.id == "kid" }, "an account that cannot see it needs no exclude")
    }

    @Test fun `nothing to do once every viewer excludes it`() {
        val lib = "versions-lib"
        val plan = planVersionsLibraryVisibility(listOf(viewer("a", excludes = true), viewer("b", excludes = true)), lib)
        assertTrue(plan.nothingToDo)
    }

    @Test fun `the versions root is matched to the library that holds it`() {
        val libs = listOf(
            JellyfinLibrary(id = "films", name = "Films", collectionType = "movies", locations = listOf("/media/films")),
            JellyfinLibrary(id = "versions", name = "Film versions", collectionType = "movies", locations = listOf("/media/versions/")),
        )
        assertEquals("versions", versionsLibraryIdOf(libs, "/media/versions"))
        assertEquals("versions", versionsLibraryIdOf(libs, "/media/versions/Film (2020)"), "a path inside it counts")
        assertEquals("films", versionsLibraryIdOf(libs, "/media/films"))
        assertNull(versionsLibraryIdOf(libs, "/media/elsewhere"), "no library holds it — the owner has not made one")
        assertNull(versionsLibraryIdOf(emptyList(), "/media/versions"))
        // A sibling whose name merely starts the same is not inside it.
        assertNull(versionsLibraryIdOf(listOf(JellyfinLibrary(id = "x", name = "X", locations = listOf("/media/vers"))), "/media/versions"))
    }

    @Test fun `the local path is translated into Jellyfin's view of it`() {
        val libs = listOf(
            dev.jellystructure.config.LibraryMapping(jellyfinId = "v", name = "Film versions", collectionType = "movies",
                jellyfinPath = "/media/versions", localPath = "/mnt/media/jellyfin/versions", skip = true),
        )
        assertEquals("/media/versions/Film (2020)/Film (2020) - Dolby Vision.mkv",
            jellyfinViewOf("/mnt/media/jellyfin/versions/Film (2020)/Film (2020) - Dolby Vision.mkv", libs))
        assertEquals("/media/versions", jellyfinViewOf("/mnt/media/jellyfin/versions", libs))
        // Unmapped: the path as it is, which is right when both sides mount it the same way.
        assertEquals("/somewhere/else.mkv", jellyfinViewOf("/somewhere/else.mkv", libs))
        assertEquals("/x.mkv", jellyfinViewOf("/x.mkv", emptyList()))
    }
}
