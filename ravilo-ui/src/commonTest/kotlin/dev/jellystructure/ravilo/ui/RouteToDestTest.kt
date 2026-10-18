package dev.jellystructure.ravilo.ui

import dev.jellystructure.ravilo.ui.screens.BrowseKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** DEB-1738 (FR-2) — [routeToDest] is the inverse of [Dest.toRoute] for every route a load may open. */
class RouteToDestTest {
    private val name = "Viewer"

    private val restorable: List<Dest> = listOf(
        Dest.Home(name),
        Dest.MovieDetail("m1", name),
        Dest.SeriesDetail("s1", name),
        Dest.UpcomingDetail("u1", name),
        Dest.Settings(name),
        Dest.Profile(name),
        Dest.YourProfile(name),
        Dest.ChangePassword(name),
        Dest.AppLanguage(name),
        Dest.LiveTvGuide(name),
        Dest.SeerrSearch(name),
        Dest.DiscoverItem("movie", 603, name),
        Dest.DiscoverItem("tv", 1399, name),
        Dest.SupportList(name),
        Dest.SupportThread(name, 42L),
    ) + BrowseKind.entries.map { Dest.Browse(it, name) }

    @Test fun everyRestorablePageRoundTrips() {
        for (d in restorable) assertEquals(d, routeToDest(d.toRoute(), name), "route ${d.toRoute()}")
    }

    @Test fun searchAndDiscoverRoundTripOnTypeAndName() {
        val search = routeToDest(Dest.Search(name).toRoute(), name)
        assertIs<Dest.Search>(search)
        assertEquals(name, search.displayName)
        val discover = routeToDest(Dest.Discover(name, null).toRoute(), name)
        assertIs<Dest.Discover>(discover)
        assertEquals(name, discover.displayName)
        assertNull(discover.segment)
    }

    @Test fun theNameComesFromTheCaller() {
        assertEquals(Dest.MovieDetail("m1", "Other"), routeToDest("/movie/m1", "Other"))
    }

    @Test fun playbackRoutesRestoreNothing() {
        for (r in listOf("/player/x", "/livetv/x", "/cast", "/session")) assertNull(routeToDest(r, name), r)
    }

    @Test fun routesThatAreNotAPlaceRestoreNothing() {
        for (r in listOf("/login", "/profiles", "/browse-seed", "/support/new")) assertNull(routeToDest(r, name), r)
    }

    @Test fun musicRoutesRestoreNothing() {
        val music = listOf(
            "/music", "/music/browse", "/music/playing", "/music/queue", "/music/album/x", "/music/artist/x",
            "/music/playlist/x", "/music/audiobook/x", "/music/audiobook-author/x", "/tv/now-playing",
        )
        for (r in music) assertNull(routeToDest(r, name), r)
    }

    @Test fun aChannelNeedsTheListSoItIsNotResolvedHere() {
        assertNull(routeToDest("/channel/c1", name))
        assertEquals("c1", channelIdOf("/channel/c1"))
    }

    @Test fun channelIdOfOnlyReadsAChannelRoute() {
        assertNull(channelIdOf("/movie/c1"))
        assertNull(channelIdOf("/channel/"))
        assertNull(channelIdOf("/channel/a/b"))
        assertNull(channelIdOf(""))
    }

    @Test fun malformedRoutesGiveNull() {
        val bad = listOf(
            "", "/", "home", "movie/x", "/movie", "/movie/", "/movie/a/b", "/home/", "//home", "/unknown", "/unknown/x",
            "/browse/bogus", "/browse/MOVIES", "/discover/movie/abc", "/discover/movie/", "/discover/film/603", "/discover/movie/603/x",
            "/account/other", "/support/abc", "/support/0", "/support/-3", "/discover/other",
        )
        for (r in bad) assertNull(routeToDest(r, name), "route '$r'")
    }
}
