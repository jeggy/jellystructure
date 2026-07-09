package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.tv.normalizeGuid
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 142 — the acceptance criteria call for a unit test on the restricted-user filter predicate
 * (admin sees all; a 2-library user sees only those) and on GUID normalization matching Scanner's own
 * library resolution. Run via `./gradlew linuxX64Test`.
 */
class VisibleToTest {

    private fun item(libraryId: String?) = MediaItem(
        id = "m1", title = "m1", year = null, kind = MediaKind.MOVIE, path = "/x", tmdbId = null,
        originalLanguage = null, posterPath = null, overview = null,
        tracks = emptyList(), issueCount = 0, scannedAt = 0L, libraryId = libraryId,
    )

    @Test
    fun `unrestricted user with a null allowed set sees everything including unmapped items`() {
        assertTrue(item("lib-movies").visibleTo(null))
        assertTrue(item(null).visibleTo(null))
    }

    @Test
    fun `restricted user sees only items in their allowed set`() {
        val allowed = setOf(normalizeGuid("lib-movies"), normalizeGuid("lib-series"))
        assertTrue(item("lib-movies").visibleTo(allowed))
        assertTrue(item("lib-series").visibleTo(allowed))
        assertFalse(item("lib-kids").visibleTo(allowed))
    }

    @Test
    fun `restricted user fails closed on an unmapped null-libraryId item`() {
        // Pre-142 rows / a library the path-prefix backfill couldn't resolve — never leak these to a
        // restricted user, even though an all-folders admin still sees them (previous test).
        val allowed = setOf(normalizeGuid("lib-movies"))
        assertFalse(item(null).visibleTo(allowed))
    }

    @Test
    fun `GUID normalization matches regardless of dashing or case`() {
        // Jellyfin's Policy.EnabledFolders and /Library/VirtualFolders ItemId can differ in dashing/case
        // across server versions; both sides must normalize identically or a restricted user's catalog
        // silently goes empty.
        val allowed = setOf(normalizeGuid("AABBCCDD-1122-3344-5566-778899AABBCC"))
        assertTrue(item("aabbccdd112233445566778899aabbcc").visibleTo(allowed))
        assertTrue(item("aabbccdd-1122-3344-5566-778899aabbcc").visibleTo(allowed))
    }

    @Test
    fun `normalizeGuid strips dashes and lowercases`() {
        assertTrue(normalizeGuid("AABB-CCDD") == "aabbccdd")
        assertTrue(normalizeGuid("aabbccdd") == "aabbccdd")
    }
}
