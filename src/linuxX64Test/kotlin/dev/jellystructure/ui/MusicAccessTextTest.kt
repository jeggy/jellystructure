package dev.jellystructure.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-2518 FR-2 — the music access line on Users & devices (`musicAccessLabel`), which sits in
 * `commonMain` so it can be tested here: the admin frontend has no test target of its own.
 */
class MusicAccessTextTest {
    // ── FR-2 — the four cases ────────────────────────────────────────────────────────────────────────

    @Test fun musicAccessLabelReturnsTheCorrectChipForEveryCase() {
        // music = "yes" → "Music: yes" (regardless of allowedTags)
        assertEquals("Music: yes", musicAccessLabel("yes", emptyList()))
        assertEquals("Music: yes", musicAccessLabel("yes", listOf("household")))

        // music = "no_access" + non-empty allowedTags → "Music: no" (tag-filtered, FR-2)
        assertEquals("Music: no", musicAccessLabel("no_access", listOf("household")))
        assertEquals("Music: no", musicAccessLabel("no_access", listOf("damkjær-streaming", "kids")))

        // music = "no_access" + empty allowedTags → "Music: no library access" (FR-275-7, unchanged)
        assertEquals("Music: no library access", musicAccessLabel("no_access", emptyList()))

        // null or any other string → nothing (null)
        assertNull(musicAccessLabel(null, emptyList()))
        assertNull(musicAccessLabel(null, listOf("household")))
        assertNull(musicAccessLabel("other", emptyList()))
        assertNull(musicAccessLabel("other", listOf("household")))
    }
}
