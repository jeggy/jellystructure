package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 259 (dev review items 1 and 7) — skipped releases and the behind count are arithmetic on MAJOR.MINOR. */
class RaviloVersionTest {
    @Test
    fun `a release parses and a dev build does not`() {
        assertEquals(RaviloVersion(1, 38), parseRaviloRelease("1.38"))
        assertEquals(RaviloVersion(1, 38), parseRaviloRelease(" 1.38 "))
        assertNull(parseRaviloRelease("1.37-68-gade0523d"))
        assertNull(parseRaviloRelease("1.37-68-gade0523d-dirty"))
        assertNull(parseRaviloRelease("dev")); assertNull(parseRaviloRelease(null)); assertNull(parseRaviloRelease("1.2.3"))
    }

    @Test
    fun `both git-describe shapes are dev builds and a plain release is not`() {
        assertTrue(isRaviloDevBuild("1.37-68-gade0523d"))
        assertTrue(isRaviloDevBuild("1.37-68-gade0523d-dirty"))
        assertFalse(isRaviloDevBuild("1.38")); assertFalse(isRaviloDevBuild(null))
    }

    @Test
    fun `skipped is the integers between two releases`() {
        assertEquals("1.37", raviloSkippedBetween("1.36", "1.38"))
        assertEquals("1.34 – 1.35", raviloSkippedBetween("1.33", "1.36"))
        assertNull(raviloSkippedBetween("1.37", "1.38"), "consecutive releases skip nothing")
        assertNull(raviloSkippedBetween("1.38", "1.36"), "a step back says nothing")
        assertNull(raviloSkippedBetween("1.36", "2.1"), "nothing is said across a MAJOR change")
        assertNull(raviloSkippedBetween("1.36-3-gabcdef0", "1.38"), "a dev build has no place in the arithmetic")
    }

    @Test
    fun `behind counts releases and never a dev build`() {
        assertEquals(2, raviloReleasesBehind("1.36", "1.38"))
        assertEquals(0, raviloReleasesBehind("1.38", "1.38"))
        assertEquals(0, raviloReleasesBehind("1.39", "1.38"), "ahead is not behind")
        assertNull(raviloReleasesBehind("1.37-68-gade0523d", "1.38"))
        assertNull(raviloReleasesBehind("1.36", "1.38-10-g1234567"), "a dev-build server names no latest")
    }

    @Test
    fun `the update line names a newer plain release and nothing else`() {
        assertEquals(RaviloVersion(1, 46), raviloNewerRelease("1.45", "1.46"))
        assertEquals(RaviloVersion(2, 0), raviloNewerRelease("1.45", "2.0"), "a MAJOR step is still newer")
        assertEquals(RaviloVersion(1, 46), raviloNewerRelease("1.45", "v1.46"), "the tag's own spelling reads the same")
        assertEquals(RaviloVersion(1, 46), raviloNewerRelease("v1.45", " 1.46 "))
        assertNull(raviloNewerRelease("1.46", "1.46"), "the same release offers nothing")
        assertNull(raviloNewerRelease("1.47", "1.46"), "an app ahead of its server offers nothing")
        assertNull(raviloNewerRelease("1.45-3-gabcdef0", "1.46"), "a dev-build app never shows it")
        assertNull(raviloNewerRelease("1.45", "v1.46-49-g429cffe9"), "a dev-build server never shows it")
        assertNull(raviloNewerRelease("dev", "1.46"))
        assertNull(raviloNewerRelease("1.45", null))
        assertNull(raviloNewerRelease("1.45", ""))
    }

    @Test
    fun `the server floor is the release a build sits on`() {
        assertEquals(RaviloVersion(1, 51), raviloServerFloor("v1.51"))
        assertEquals(RaviloVersion(1, 51), raviloServerFloor("1.51"))
        assertEquals(RaviloVersion(1, 51), raviloServerFloor(" 1.51 "))
        assertEquals(RaviloVersion(1, 51), raviloServerFloor("v1.51-238-g188154f2"), "a build between releases is based on 1.51 (D12)")
        assertEquals(RaviloVersion(1, 51), raviloServerFloor("1.51-238-g188154f2-dirty"))
        assertEquals(RaviloVersion(2, 0), raviloServerFloor("v2.0"))
    }

    @Test
    fun `the server floor is unknown for anything that is not a release or a build on one`() {
        assertNull(raviloServerFloor(null))
        assertNull(raviloServerFloor(""))
        assertNull(raviloServerFloor("   "))
        assertNull(raviloServerFloor("dev"))
        assertNull(raviloServerFloor("1.51.2"))
        assertNull(raviloServerFloor("v1.51-beta"))
        assertNull(raviloServerFloor("1.51-238"), "a count without its commit is not a git-describe")
        assertNull(raviloServerFloor("latest"))
    }

    @Test
    fun `the server floor leaves raviloNewerRelease's reading of a dev build alone`() {
        assertNull(raviloNewerRelease("1.45", "v1.46-49-g429cffe9"), "the older function still says nothing about a dev build")
        assertEquals(RaviloVersion(1, 46), raviloServerFloor("v1.46-49-g429cffe9"))
    }
}

