package dev.jellystructure.ravilo.ui.music

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsNodeInteraction
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.TvApiError
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun initContext() = dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

private fun song(n: Int) = MusicTrackItem(id = "s$n", title = "Song $n", entryId = "e$n")

private class Server(songs: List<MusicTrackItem>) : PlaylistChanges {
    var held = songs
    var fail: Throwable? = null
    val moves = ArrayList<Pair<String, Int>>()
    val removed = ArrayList<String>()
    var renamed: String? = null
    var deleted: String? = null
    override suspend fun remove(id: String, entryId: String): MusicList { fail?.let { throw it }; removed += entryId; held = held.filter { it.entryId != entryId }; return MusicList(tracks = held, mine = true) }
    override suspend fun move(id: String, entryId: String, to: Int): MusicList {
        fail?.let { throw it }
        moves += entryId to to
        held = reordered(held, held.indexOfFirst { it.entryId == entryId }, to)
        return MusicList(tracks = held, mine = true)
    }
    override suspend fun rename(id: String, name: String): MusicPlaylist { fail?.let { throw it }; renamed = name; return MusicPlaylist(id, name, held.size, mine = true) }
    override suspend fun delete(id: String) { fail?.let { throw it }; deleted = id }
}

/** DEB-2254 (FR-8 to FR-13, acceptance 4 to 7) — a playlist's page on the phone layout, over a stand-in server. Stand-in names only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h2000dp")
class PlaylistPageTest {
    @get:Rule val rule = createComposeRule()
    @Before fun setUp() { initContext(); MusicToasts.hide(MusicToasts.message.value?.second ?: -1); PlaylistEditBack.register(null) }

    private var backs = 0
    private var renamedTo: String? = null
    private fun toast() = MusicToasts.message.value?.first
    private fun nodes(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun tap(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag, useUnmergedTree = true).also { it.performSemanticsAction(SemanticsActions.OnClick) }

    /** Sheets slide in on the test clock: step it until [done]. */
    private fun until(done: () -> Boolean) {
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1_000)
        repeat(60) { rule.waitForIdle(); if (done()) { rule.mainClock.advanceTimeBy(600); rule.waitForIdle(); return }; rule.mainClock.advanceTimeBy(100) }
        throw AssertionError("condition not met in 6 s of test time")
    }

    private fun show(server: Server, mine: Boolean, songs: List<MusicTrackItem> = server.held): MusicLoader<MusicList> {
        val loader = MusicLoader { MusicList(tracks = songs, total = songs.size, mine = mine) }
        loader.put(MusicList(tracks = songs, total = songs.size, mine = mine))
        rule.setContent {
            MusicPlaylistScreen(id = "p1", name = "Road Trip", loader = loader, changes = server, onBack = { backs++ }, onTrackMore = {}, onRenamed = { renamedTo = it })
        }
        rule.waitForIdle()
        return loader
    }

    @Test fun a_shared_playlist_draws_no_menu_and_says_who_it_belongs_to() {
        show(Server(listOf(song(1), song(2))), mine = false)
        assertEquals(0, nodes("playlist-more"))
        assertEquals(1, nodes("playlist-shared"))
        assertTrue(rule.onAllNodesWithText("SHARED WITH YOU", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, nodes("playlist-edit-row"))
    }

    @Test fun an_own_playlist_has_the_menu_with_edit_rename_and_delete() {
        show(Server(listOf(song(1), song(2))), mine = true)
        assertEquals(0, nodes("playlist-shared"))
        assertEquals(1, nodes("playlist-more"))
        tap("playlist-more")
        until { nodes("playlist-menu") == 1 }
        assertEquals(1, nodes("playlist-menu-edit"))
        assertEquals(1, nodes("playlist-menu-rename"))
        assertEquals(1, nodes("playlist-menu-delete"))
    }

    @Test fun an_empty_own_playlist_offers_rename_and_delete_only_and_says_no_songs_yet() {
        show(Server(emptyList()), mine = true)
        assertEquals(1, nodes("playlist-empty"))
        assertEquals(1, nodes("playlist-play-disabled"))
        assertEquals(1, nodes("playlist-shuffle-disabled"))
        tap("playlist-more")
        until { nodes("playlist-menu") == 1 }
        assertEquals(0, nodes("playlist-menu-edit"))
        assertEquals(1, nodes("playlist-menu-rename"))
        assertEquals(1, nodes("playlist-menu-delete"))
        assertTrue(rule.onAllNodesWithText("No songs yet", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun delete_asks_first_and_cancel_keeps_the_playlist() {
        val s = Server(listOf(song(1)))
        show(s, mine = true)
        tap("playlist-more"); until { nodes("playlist-menu") == 1 }
        tap("playlist-menu-delete"); until { nodes("playlist-confirm") == 1 }
        assertTrue(rule.onAllNodesWithText("Delete Road Trip?", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("This can't be undone.", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        tap("playlist-confirm-cancel"); until { nodes("playlist-confirm") == 0 }
        assertNull(s.deleted)
        assertEquals(0, backs)
    }

    @Test fun delete_removes_it_toasts_and_goes_back() {
        val s = Server(listOf(song(1)))
        val before = PlaylistFlow.version.value
        show(s, mine = true)
        tap("playlist-more"); until { nodes("playlist-menu") == 1 }
        tap("playlist-menu-delete"); until { nodes("playlist-confirm") == 1 }
        tap("playlist-confirm-delete"); until { toast() != null }
        assertEquals("p1", s.deleted)
        assertEquals("Deleted Road Trip", toast())
        assertEquals(1, backs)
        assertEquals(before + 1, PlaylistFlow.version.value, "the Playlists list reads again")
    }

    @Test fun a_delete_that_fails_changes_nothing_and_says_so() {
        val s = Server(listOf(song(1))).also { it.fail = TvApiError.Http(403, "no") }
        show(s, mine = true)
        tap("playlist-more"); until { nodes("playlist-menu") == 1 }
        tap("playlist-menu-delete"); until { nodes("playlist-confirm") == 1 }
        tap("playlist-confirm-delete"); until { toast() != null }
        assertEquals("Couldn't change the playlist", toast())
        assertEquals(0, backs)
        assertNull(s.deleted)
    }

    @Test fun rename_opens_the_sheet_filled_in_and_saves_the_new_name() {
        val s = Server(listOf(song(1)))
        show(s, mine = true)
        tap("playlist-more"); until { nodes("playlist-menu") == 1 }
        tap("playlist-menu-rename"); until { nodes("playlist-name-field") == 1 }
        rule.onNodeWithTag("playlist-name-field", useUnmergedTree = true).performTextReplacement("  Stand-in Mix ")
        tap("playlist-name-confirm"); until { s.renamed != null }
        assertEquals("Stand-in Mix", s.renamed, "trimmed")
        assertEquals("Stand-in Mix", renamedTo)
        until { nodes("playlist-name-sheet") == 0 }
    }

    @Test fun edit_mode_swaps_play_and_the_menu_for_done_and_done_leaves_it() {
        show(Server(listOf(song(1), song(2), song(3))), mine = true)
        tap("playlist-more"); until { nodes("playlist-menu") == 1 }
        tap("playlist-menu-edit"); until { nodes("playlist-done") == 1 }
        assertEquals(0, nodes("playlist-more"))
        assertEquals(3, nodes("playlist-edit-row"))
        assertEquals(3, nodes("playlist-grip"))
        tap("playlist-done"); until { nodes("playlist-done") == 0 }
        assertEquals(1, nodes("playlist-more"))
        assertEquals(0, nodes("playlist-edit-row"))
    }

    @Test fun back_with_edit_mode_up_leaves_edit_mode_first() {
        show(Server(listOf(song(1), song(2))), mine = true)
        assertFalse(PlaylistEditBack.consume(), "nothing to leave")
        tap("playlist-more"); until { nodes("playlist-menu") == 1 }
        tap("playlist-menu-edit"); until { nodes("playlist-done") == 1 }
        assertTrue(PlaylistEditBack.consume())
        until { nodes("playlist-done") == 0 }
        assertFalse(PlaylistEditBack.consume())
    }

    @Test fun dragging_a_row_by_its_grip_sends_the_position_it_was_dropped_on() {
        val s = Server((1..5).map { song(it) })
        val loader = show(s, mine = true)
        tap("playlist-more"); until { nodes("playlist-menu") == 1 }
        tap("playlist-menu-edit"); until { nodes("playlist-done") == 1 }
        // A row is 62 dp, and a test device's dp is a pixel: 2.1 rows down from the first lands on index 2.
        rule.onAllNodesWithTag("playlist-grip", useUnmergedTree = true)[0].performTouchInput {
            down(center); moveBy(Offset(0f, 65f)); moveBy(Offset(0f, 65f)); up()
        }
        until { s.moves.isNotEmpty() }
        assertEquals(listOf("e1" to 2), s.moves)
        until { ((loader.state.value as Load.Ready).value).tracks.map { it.entryId } == listOf("e2", "e3", "e1", "e4", "e5") }
    }

    @Test fun a_drag_the_server_refuses_puts_the_row_back_and_says_so() {
        val s = Server((1..5).map { song(it) }).also { it.fail = TvApiError.Http(502, "no") }
        val loader = show(s, mine = true)
        tap("playlist-more"); until { nodes("playlist-menu") == 1 }
        tap("playlist-menu-edit"); until { nodes("playlist-done") == 1 }
        rule.onAllNodesWithTag("playlist-grip", useUnmergedTree = true)[0].performTouchInput {
            down(center); moveBy(Offset(0f, 65f)); moveBy(Offset(0f, 65f)); up()
        }
        until { toast() != null }
        assertEquals("Couldn't change the playlist", toast())
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), ((loader.state.value as Load.Ready).value).tracks.map { it.entryId })
        assertNotNull(loader)
    }
}
