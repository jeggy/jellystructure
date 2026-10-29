package dev.jellystructure.ravilo.ui

import dev.jellystructure.ravilo.ui.screens.BrowseKind
import dev.jellystructure.ravilo.ui.screens.TvLinkAction
import dev.jellystructure.ravilo.ui.screens.tvLinkAction
import kotlin.test.Test
import kotlin.test.assertEquals

/** DEB-2875 (FR-6, FR-7) — [tvLinkAction] decides what to do with an arriving TV link code. */
class TvLinkArrivalTest {

    private val name = "Viewer"

    @Test fun emptyStackIsHold() {
        assertEquals(TvLinkAction.Hold, tvLinkAction(emptyList()))
    }

    @Test fun loginIsHold() {
        assertEquals(TvLinkAction.Hold, tvLinkAction(listOf(Dest.Login)))
    }

    @Test fun profilePickerIsHold() {
        assertEquals(TvLinkAction.Hold, tvLinkAction(listOf(Dest.ProfilePicker)))
    }

    @Test fun signInTvIsReplace() {
        assertEquals(TvLinkAction.Replace, tvLinkAction(listOf(Dest.SignInTv(name))))
    }

    @Test fun homeIsPush() {
        assertEquals(TvLinkAction.Push, tvLinkAction(listOf(Dest.Home(name))))
    }

    @Test fun browseAllIsPush() {
        assertEquals(TvLinkAction.Push, tvLinkAction(listOf(Dest.Browse(BrowseKind.ALL, name))))
    }

    @Test fun browseMoviesIsPush() {
        assertEquals(TvLinkAction.Push, tvLinkAction(listOf(Dest.Browse(BrowseKind.MOVIES, name))))
    }

    @Test fun searchIsPush() {
        assertEquals(TvLinkAction.Push, tvLinkAction(listOf(Dest.Search(name))))
    }

    @Test fun discoverIsPush() {
        assertEquals(TvLinkAction.Push, tvLinkAction(listOf(Dest.Discover(name, null))))
    }

    @Test fun profileIsPush() {
        assertEquals(TvLinkAction.Push, tvLinkAction(listOf(Dest.Profile(name))))
    }

    @Test fun homeWithDetailIsPush() {
        assertEquals(TvLinkAction.Push, tvLinkAction(listOf(Dest.Home(name), Dest.MovieDetail("m1", name))))
    }

    @Test fun homeWithPlayerIsLeaveIt() {
        assertEquals(TvLinkAction.LeaveIt, tvLinkAction(listOf(Dest.Home(name), Dest.Player(itemId = "m1", title = "Movie", displayName = name))))
    }

    @Test fun homeWithSignInTvIsReplace() {
        assertEquals(TvLinkAction.Replace, tvLinkAction(listOf(Dest.Home(name), Dest.SignInTv(name))))
    }

    @Test fun browseWithSignInTvIsReplace() {
        assertEquals(TvLinkAction.Replace, tvLinkAction(listOf(Dest.Browse(BrowseKind.ALL, name), Dest.SignInTv(name))))
    }

    @Test fun profileWithPlayerIsLeaveIt() {
        assertEquals(TvLinkAction.LeaveIt, tvLinkAction(listOf(Dest.Profile(name), Dest.Player(itemId = "m1", title = "Movie", displayName = name))))
    }

    @Test fun searchWithDetailIsPush() {
        assertEquals(TvLinkAction.Push, tvLinkAction(listOf(Dest.Search(name), Dest.MovieDetail("m1", name))))
    }
}
