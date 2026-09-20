package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinItem
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 251. Two things are pinned here, and the second is the one that made the first invisible.
 *
 * `preserveJellyfinLockState` is the guard; `JellyfinItem`'s **nullable** lock fields are what let it
 * exist at all. Before this phase both fields defaulted to `false`/`emptyList()`, so "Jellyfin did not
 * send this" and "Jellyfin says nothing is locked" were literally the same value — and the product
 * chose the second reading and wrote it to its own database, on every scan and on every visit to a
 * media detail page.
 *
 * Measured against a fresh Jellyfin 12.1.0 on 2026-09-20 with the lock deliberately SET first:
 * `GET /Items/{id}?userId=…` returns `LockData: true, LockedFields: ["Name"]`, while
 * `GET /Items?Ids=…&Fields=…LockData,LockedFields…` returns neither.
 */
class JellyfinLockStateTest {

    private fun item(locked: Boolean, fields: List<String>) = MediaItem(
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
        jellyfinLockData = locked,
        jellyfinLockedFields = fields,
    )

    @Test
    fun `a scan does not clear a lock it cannot see`() {
        // The scan-fresh item is what Scanner builds from the LIST shape, which on 12.1 carries no
        // lock state at all — so it arrives at MediaItem's own defaults.
        val fresh = item(locked = false, fields = emptyList())
        val stored = item(locked = true, fields = listOf("Name", "Overview"))

        val merged = preserveJellyfinLockState(fresh, stored)

        assertTrue(merged.jellyfinLockData, "a scan must not report a locked title as unlocked")
        assertEquals(listOf("Name", "Overview"), merged.jellyfinLockedFields)
    }

    @Test
    fun `a first scan with nothing stored keeps the fresh item unchanged`() {
        val fresh = item(locked = false, fields = emptyList())
        val merged = preserveJellyfinLockState(fresh, null)
        assertEquals(fresh, merged)
    }

    @Test
    fun `the stored value wins even when the fresh one is also set`() {
        // Deliberate: nothing that reaches this guard has read the shape that carries lock state, so
        // a non-default value on the fresh item is a leftover, not knowledge. The one caller that
        // genuinely knows — the jellyfin-locks route — opts out of the guard instead.
        val fresh = item(locked = true, fields = listOf("Genres"))
        val stored = item(locked = false, fields = emptyList())

        val merged = preserveJellyfinLockState(fresh, stored)

        assertTrue(!merged.jellyfinLockData)
        assertEquals(emptyList(), merged.jellyfinLockedFields)
    }

    @Test
    fun `an absent lock field deserializes as null rather than false`() {
        // The whole reason the regression was silent. If these defaults ever go back to
        // false/emptyList(), the distinction disappears again and so does every guard above it.
        val absent = JellyfinItem(id = "x", name = "X", type = "Movie")
        assertNull(absent.lockData, "absent must be distinguishable from 'nothing is locked'")
        assertNull(absent.lockedFields)
        assertNull(absent.dateLastSaved)
    }

    @Test
    fun `a payload that does carry the fields keeps their values`() {
        val carried = JellyfinItem(
            id = "x", name = "X", type = "Movie",
            lockData = true, lockedFields = listOf("Name"),
        )
        assertEquals(true, carried.lockData)
        assertEquals(listOf("Name"), carried.lockedFields)
    }
}
