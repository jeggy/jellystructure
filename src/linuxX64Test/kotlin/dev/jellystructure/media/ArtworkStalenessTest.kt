package dev.jellystructure.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 176 — a poster/backdrop left over from a superseded TMDB match must be detected as stale so
 * `ArtworkDownloader.fetch()` re-downloads it and `isArtworkIncomplete()` includes it in a "missing
 * artwork" scoped run. [isStaleArtworkSrc] is the pure decision both call sites share.
 */
class ArtworkStalenessTest {

    /** The reported bug: the match was corrected, but the old file's `.src` still names the old match. */
    @Test
    fun `a recorded src that disagrees with the current match is stale`() {
        assertTrue(isStaleArtworkSrc(recordedSrc = "/spiderman.jpg", expectedSrc = "/hjem.jpg", manual = false))
    }

    @Test
    fun `a recorded src that matches the current match is not stale`() {
        assertFalse(isStaleArtworkSrc(recordedSrc = "/hjem.jpg", expectedSrc = "/hjem.jpg", manual = false))
    }

    /** A file predating Phase 176 has no `.src` sidecar at all — trusted, not forced into re-download. */
    @Test
    fun `no recorded src is trusted and never stale`() {
        assertFalse(isStaleArtworkSrc(recordedSrc = null, expectedSrc = "/hjem.jpg", manual = false))
    }

    /** An operator's manual pick (Phase 151) always wins, even if its `.src` disagrees with the match. */
    @Test
    fun `a manual file is never stale regardless of a disagreeing src`() {
        assertFalse(isStaleArtworkSrc(recordedSrc = "/spiderman.jpg", expectedSrc = "/hjem.jpg", manual = true))
    }

    /** No current match to compare against (e.g. a cleared match) — nothing to call stale relative to. */
    @Test
    fun `no expected src means nothing to compare against so not stale`() {
        assertFalse(isStaleArtworkSrc(recordedSrc = "/spiderman.jpg", expectedSrc = null, manual = false))
    }
}
