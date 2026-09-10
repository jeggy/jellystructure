package dev.jellystructure.media

import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 199 (FR-199-4) — one test per path the audit walked: constitution invariant #6's five actual
 * promises. `preserveJsTags`/`mergeRepullTags` were the last two write guards still private and
 * unreachable from a test, which is exactly why FR-199-1's one-word divergence (`existing` instead of
 * `old`) survived three phases of adjacent work getting their own parameter right.
 */
class JsTagLockTest {

    private fun item(tags: List<String>) = MediaItem(
        id = "girl-missing-2026",
        title = "Girl Missing",
        originalTitle = "Girl Missing",
        year = 2026,
        kind = MediaKind.MOVIE,
        path = "/mnt/media/movies/Girl Missing (2026)/Girl Missing.mkv",
        tmdbId = null,
        originalLanguage = "en",
        posterPath = null,
        overview = null,
        tracks = emptyList(),
        issueCount = 0,
        scannedAt = 0,
        tags = tags,
    )

    private val jsNames = setOf("børne-tv", "non-kids", "damkjær-streaming")

    /** FR-199-1's regression: a slug rename's predecessor is the stale row, not the fresh id's own row —
     *  `existing` is null on exactly that rename, so the guard must be called with `old`, never `existing`. */
    @Test
    fun `a slug rename keeps JS tags when keyed off the stale predecessor`() {
        val old = item(tags = listOf("Action", "non-kids"))
        val fresh = item(tags = listOf("Action", "Thriller")) // scan-fresh: Jellyfin tags only

        val merged = preserveJsTags(fresh, old, jsNames)

        assertEquals(setOf("Action", "Thriller", "non-kids"), merged.tags.toSet())
    }

    /** The failure this spec exists to describe: keying off `existing` (null across a rename) preserves
     *  nothing at all — this pins the wrong behavior so a future edit can't silently reintroduce it. */
    @Test
    fun `keying off a null predecessor preserves nothing`() {
        val fresh = item(tags = listOf("Action", "Thriller"))

        val merged = preserveJsTags(fresh, null, jsNames)

        assertEquals(listOf("Action", "Thriller"), merged.tags)
    }

    /** A manual removal through `PATCH /media/{id}/metadata` must stick — the guard is never applied on
     *  that write, so an operator's explicit tag removal isn't fought by the very next write. */
    @Test
    fun `a manual removal is not re-added when the guard is skipped for the metadata route`() {
        val existing = item(tags = listOf("Action", "non-kids"))
        val manuallyEdited = item(tags = listOf("Action")) // operator removed non-kids via the metadata route

        // respectJsTags = false on that route means preserveJsTags is never called at all.
        val stored = manuallyEdited

        assertEquals(listOf("Action"), stored.tags)
        assertEquals(setOf("Action", "non-kids"), existing.tags.toSet()) // sanity: it really was present before
    }

    /** TMDB re-pull: TMDB keywords become the non-JS tags; JS tags survive; stale Jellyfin-only tags drop. */
    @Test
    fun `a TMDB re-pull keeps JS tags and drops stale Jellyfin-only tags`() {
        val existingTags = listOf("Action", "non-kids", "damkjær-streaming")
        val tmdbKeywords = listOf("revenge", "heist")

        val merged = mergeRepullTags(tmdbKeywords, existingTags, jsNames)

        assertEquals(setOf("revenge", "heist", "non-kids", "damkjær-streaming"), merged.toSet())
        assertEquals(4, merged.size)
    }

    /** Re-pull from Jellyfin is additive — covered here at the union level `preserveJsTags` itself
     *  contributes to (the additive union of `fresh` + kept JS tags, never a subtraction). */
    @Test
    fun `preserveJsTags is additive and never drops a fresh tag`() {
        val old = item(tags = listOf("non-kids"))
        val fresh = item(tags = listOf("Action", "Thriller", "Horror"))

        val merged = preserveJsTags(fresh, old, jsNames)

        assertEquals(setOf("Action", "Thriller", "Horror", "non-kids"), merged.tags.toSet())
    }

    /** A tag not in `nameSet()` — e.g. one whose definition was since deleted — is not preserved. */
    @Test
    fun `a tag no longer in the JS-tag name set is not preserved`() {
        val old = item(tags = listOf("retired-tag", "non-kids"))
        val fresh = item(tags = listOf("Action"))

        val merged = preserveJsTags(fresh, old, jsNames) // "retired-tag" no longer in jsNames

        assertEquals(setOf("Action", "non-kids"), merged.tags.toSet())
    }

    @Test
    fun `no predecessor and no kept tags returns the fresh item unchanged`() {
        val fresh = item(tags = listOf("Action"))

        assertEquals(fresh, preserveJsTags(fresh, null, jsNames))
    }

    @Test
    fun `mergeRepullTags with no existing JS tags is just the TMDB keywords`() {
        val merged = mergeRepullTags(listOf("revenge"), listOf("Action"), jsNames)

        assertEquals(listOf("revenge"), merged)
    }
}
