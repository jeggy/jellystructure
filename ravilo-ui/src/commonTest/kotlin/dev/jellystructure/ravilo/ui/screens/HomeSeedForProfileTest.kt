package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.HomeFeed
import dev.jellystructure.shared.tv.Skin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** DEB-5839 (FR-3) — Home opens on this profile's own rows: the seed and the kept store are per user id. */
class HomeSeedForProfileTest {
    private fun snap(feed: HomeFeed) = HomeSnapshot(
        feed = feed, uiLanguage = "en", skin = Skin.AURORA, tileScale = 1f,
        gridColumns = 6, portraitGridColumns = 2, savedAtEpochMs = 0L,
    )

    private val feedA = HomeFeed(heroes = emptyList(), channels = emptyList(), rows = emptyList())
    private val feedB = HomeFeed(heroes = emptyList(), channels = emptyList(), rows = emptyList())
    private val snapshots = mapOf("user-a" to snap(feedA), "user-b" to snap(feedB))

    @Test
    fun theSeedIsTheOpeningProfilesOwnFeed() {
        assertSame(feedB, homeSeedFeed("user-b", snapshots::get))
        assertSame(feedA, homeSeedFeed("user-a", snapshots::get))
    }

    @Test
    fun aProfileWithNoSnapshotOfItsOwnStartsAtLoading() {
        assertNull(homeSeedFeed("user-c", snapshots::get))
        assertNull(homeSeedFeed(null, snapshots::get))
    }

    @Test
    fun twoProfilesWithTheSameNameDoNotShareOneStore() {
        assertNotEquals(homeStoreKey("user-a"), homeStoreKey("user-b"))
        assertEquals(homeStoreKey("user-a"), homeStoreKey("user-a"))
    }
}
