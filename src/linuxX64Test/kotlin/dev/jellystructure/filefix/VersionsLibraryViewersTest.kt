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

    /**
     * FR-314-16 test 14 (DEB-898) — the live namespaces: the backend writes under `/mnt/media/jellyfin`; Jellyfin
     * and the arrs both name the same folders under `/media`. The overlap guard only means something on the
     * translated root — and a root with no translation is refused before it is compared at all.
     */
    @Test fun `FR-314-16 - the overlap guard is fed the translated root and an unmapped root has no translation`() {
        fun mapping(id: String, jf: String, local: String) = dev.jellystructure.config.LibraryMapping(
            jellyfinId = id, name = "library-$id", collectionType = "movies", jellyfinPath = jf, localPath = local, skip = true)
        val libs = listOf(mapping("m", "/media/movies/", "/mnt/media/jellyfin/movies/"), mapping("v", "/media/versions", "/mnt/media/jellyfin/versions"))
        val arrRoots = listOf("/media/movies", "/media/series")   // as `GET /api/v3/rootfolder` reports them

        // Acceptance 12 — the mistake the guard exists to prevent is Radarr's own root under the backend's name.
        val inside = "/mnt/media/jellyfin/movies"
        assertNull(versionsRootOverlapsArrRoot(inside, arrRoots), "raw it cannot fire: the two namespaces never meet")
        assertEquals("/media/movies", versionsRootOverlapsArrRoot(jellyfinViewOrNull(inside, libs)!!, arrRoots), "translated it does")

        // The root the owner's step 3 names translates clear of both arr roots.
        val ok = "/mnt/media/jellyfin/versions"
        assertEquals("/media/versions", jellyfinViewOrNull(ok, libs))
        assertNull(versionsRootOverlapsArrRoot(jellyfinViewOrNull(ok, libs)!!, arrRoots))

        // Acceptance 11 — no mapping covers it: refused up front rather than compared raw or written and then lost.
        assertNull(jellyfinViewOrNull("/mnt/media/jellyfin/elsewhere", libs), "no library row holds it")
        val unfilled = listOf(libs[0], mapping("v", "/media/versions", ""))
        assertNull(jellyfinViewOrNull(ok, unfilled), "a library row whose local_path is still empty is the same thing")
        assertEquals(ok, jellyfinViewOf(ok, unfilled), "jellyfinViewOf keeps its fallback for every other caller")
    }
}
