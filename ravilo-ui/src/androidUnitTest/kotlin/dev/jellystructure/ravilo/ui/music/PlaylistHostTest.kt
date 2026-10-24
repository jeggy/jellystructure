package dev.jellystructure.ravilo.ui.music

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicPlaylistAdded
import dev.jellystructure.shared.tv.TvApiError
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun initContext() = dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

/** A stand-in for the server: records what the flow wrote, and fails when told to. */
private class FakeWrites(
    var lists: List<MusicPlaylist> = emptyList(),
    var addAnswer: Result<MusicPlaylistAdded> = Result.success(MusicPlaylistAdded(1, 0)),
    var createFails: Throwable? = null,
) : PlaylistWrites {
    val added = ArrayList<Pair<String, List<String>>>()
    val created = ArrayList<Pair<String, List<String>>>()
    var reads = 0
    override suspend fun playlists(): List<MusicPlaylist> { reads++; return lists }
    override suspend fun add(id: String, itemIds: List<String>): MusicPlaylistAdded { added += id to itemIds; return addAnswer.getOrThrow() }
    override suspend fun create(name: String, itemIds: List<String>): MusicPlaylist {
        createFails?.let { throw it }
        created += name to itemIds
        return MusicPlaylist("new-1", name, itemIds.size, emptyList(), mine = true)
    }
}

private fun pl(id: String, name: String, mine: Boolean, n: Int = 3) = MusicPlaylist(id, name, n, emptyList(), mine = mine)

/** DEB-2254 (FR-3, FR-4, FR-13, FR-14) — the picker and the name sheet over a stand-in server, on the phone layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h2000dp")
class PlaylistHostTest {
    @get:Rule val rule = createComposeRule()
    @Before fun setUp() { initContext(); PlaylistFlow.closePicker(); PlaylistFlow.closeName(); MusicToasts.hide(MusicToasts.message.value?.second ?: -1) }

    private val song = PlaylistAddRequest(PlaylistSource.SONG, listOf("kw-2"), "Northern Line — Harbour Lights")
    private fun toast() = MusicToasts.message.value?.first

    /** The sheets slide in on the test clock: step it until [done], so a node is where a tap can reach it. */
    private fun until(done: () -> Boolean) {
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1_000)
        repeat(60) { rule.waitForIdle(); if (done()) { rule.mainClock.advanceTimeBy(600); rule.waitForIdle(); return }; rule.mainClock.advanceTimeBy(100) }
        throw AssertionError("condition not met in 6 s of test time")
    }

    private fun host(w: FakeWrites) { rule.setContent { PlaylistHost(w) } }

    @Test fun the_picker_lists_own_playlists_by_name_and_hides_a_shared_one() {
        val w = FakeWrites(lists = listOf(pl("b", "Shared Mix", false), pl("c", "Late Night", true), pl("a", "Road Trip", true)))
        host(w)
        PlaylistFlow.addTo(song)
        until { rule.onAllNodesWithTag("playlist-row", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        rule.onNodeWithText("Add to playlist", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Northern Line — Harbour Lights", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("New playlist", useUnmergedTree = true).assertExists()
        // A collage with no cover draws the name as a wordmark too, so the name may be on screen more than once.
        for (n in listOf("Late Night", "Road Trip")) assertTrue(rule.onAllNodesWithText(n, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodesWithText("Shared Mix", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Playlists you make show up here.", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals(1, w.reads, "read once, when it showed")
    }

    @Test fun with_none_of_their_own_only_new_playlist_and_the_hint() {
        host(FakeWrites(lists = listOf(pl("b", "Shared Mix", false))))
        PlaylistFlow.addTo(song)
        until { rule.onAllNodesWithTag("playlist-none", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("New playlist", useUnmergedTree = true).assertExists()
        assertTrue(rule.onAllNodesWithTag("playlist-row", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun picking_a_playlist_adds_the_song_and_says_so() {
        val w = FakeWrites(lists = listOf(pl("a", "Stand-in Mix", true)))
        host(w)
        val before = PlaylistFlow.version.value
        PlaylistFlow.addTo(song)
        until { rule.onAllNodesWithTag("playlist-row", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-row", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { toast() != null }
        assertEquals(listOf("a" to listOf("kw-2")), w.added)
        assertEquals("Added to Stand-in Mix", toast())
        assertNull(PlaylistFlow.picker.value)
        assertEquals(before + 1, PlaylistFlow.version.value, "the lists read again (FR-14)")
    }

    @Test fun nothing_added_says_already_in() {
        val w = FakeWrites(lists = listOf(pl("a", "Stand-in Mix", true)), addAnswer = Result.success(MusicPlaylistAdded(0, 1)))
        host(w)
        PlaylistFlow.addTo(song)
        until { rule.onAllNodesWithTag("playlist-row", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-row", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { toast() != null }
        assertEquals("Already in Stand-in Mix", toast())
    }

    @Test fun an_album_says_how_many_songs_went_in() {
        val w = FakeWrites(lists = listOf(pl("a", "Stand-in Mix", true)), addAnswer = Result.success(MusicPlaylistAdded(10, 0)))
        host(w)
        PlaylistFlow.addTo(PlaylistAddRequest(PlaylistSource.ALBUM, (1..10).map { "s$it" }, "Stand-in Album"))
        until { rule.onAllNodesWithTag("playlist-row", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-row", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { toast() != null }
        assertEquals("Added 10 songs to Stand-in Mix", toast())
        assertEquals(10, w.added.single().second.size)
    }

    @Test fun a_refused_write_changes_nothing_and_says_so_without_naming_a_server() {
        val w = FakeWrites(lists = listOf(pl("a", "Stand-in Mix", true)), addAnswer = Result.failure(TvApiError.Http(403, "no")))
        host(w)
        val before = PlaylistFlow.version.value
        PlaylistFlow.addTo(song)
        until { rule.onAllNodesWithTag("playlist-row", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-row", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { toast() != null }
        assertEquals("Couldn't change the playlist", toast())
        assertEquals(before, PlaylistFlow.version.value)
    }

    @Test fun new_playlist_in_the_picker_creates_it_holding_what_was_being_added() {
        val w = FakeWrites()
        host(w)
        PlaylistFlow.addTo(song)
        until { rule.onAllNodesWithTag("playlist-new", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-new", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { rule.onAllNodesWithTag("playlist-name-field", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-name-field", useUnmergedTree = true).performTextInput("  Stand-in Mix ")
        rule.onNodeWithTag("playlist-name-confirm", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { toast() != null }
        assertEquals(listOf("Stand-in Mix" to listOf("kw-2")), w.created, "trimmed, and the song goes in with it")
        assertEquals("Added to Stand-in Mix", toast())
        assertNull(PlaylistFlow.nameSheet.value)
    }

    @Test fun create_is_inert_while_the_name_is_empty() {
        val w = FakeWrites()
        host(w)
        PlaylistFlow.nameIt(PlaylistNameRequest(PlaylistSource.NOTHING, emptyList()))
        until { rule.onAllNodesWithTag("playlist-name-confirm", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-name-confirm", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(w.created.isEmpty())
        assertNotNull(PlaylistFlow.nameSheet.value, "the sheet stays")
    }

    @Test fun a_new_empty_playlist_says_created_and_hands_itself_to_the_chip_to_open() {
        val w = FakeWrites()
        var opened: MusicPlaylist? = null
        host(w)
        PlaylistFlow.nameIt(PlaylistNameRequest(PlaylistSource.NOTHING, emptyList()) { opened = it })
        until { rule.onAllNodesWithTag("playlist-name-field", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-name-field", useUnmergedTree = true).performTextInput("Stand-in Mix")
        rule.onNodeWithTag("playlist-name-confirm", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { toast() != null }
        assertEquals("Created Stand-in Mix", toast())
        assertEquals("new-1", opened?.id)
    }

    @Test fun saving_a_queue_says_how_many_songs() {
        val w = FakeWrites()
        host(w)
        PlaylistFlow.nameIt(PlaylistNameRequest(PlaylistSource.QUEUE, (1..7).map { "q$it" }))
        until { rule.onAllNodesWithTag("playlist-name-field", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-name-field", useUnmergedTree = true).performTextInput("Stand-in Mix")
        rule.onNodeWithTag("playlist-name-confirm", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { toast() != null }
        assertEquals("Saved 7 songs as Stand-in Mix", toast())
        assertEquals((1..7).map { "q$it" }, w.created.single().second, "in queue order")
    }

    @Test fun a_400_on_the_name_keeps_the_sheet_open_with_the_field_marked() {
        val w = FakeWrites(createFails = TvApiError.Http(400, "bad"))
        host(w)
        PlaylistFlow.nameIt(PlaylistNameRequest(PlaylistSource.NOTHING, emptyList()))
        until { rule.onAllNodesWithTag("playlist-name-field", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-name-field", useUnmergedTree = true).performTextInput("Stand-in Mix")
        rule.onNodeWithTag("playlist-name-confirm", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { rule.onAllNodesWithTag("playlist-name-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Give it a name", useUnmergedTree = true).assertExists()
        assertNotNull(PlaylistFlow.nameSheet.value)
        assertNull(toast())
    }

    @Test fun any_other_failure_closes_the_sheet_and_toasts() {
        val w = FakeWrites(createFails = TvApiError.Http(502, "down"))
        host(w)
        PlaylistFlow.nameIt(PlaylistNameRequest(PlaylistSource.NOTHING, emptyList()))
        until { rule.onAllNodesWithTag("playlist-name-field", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-name-field", useUnmergedTree = true).performTextInput("Stand-in Mix")
        rule.onNodeWithTag("playlist-name-confirm", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { toast() != null }
        assertEquals("Couldn't change the playlist", toast())
        assertNull(PlaylistFlow.nameSheet.value)
    }

    @Test fun cancel_closes_the_name_sheet_and_writes_nothing() {
        val w = FakeWrites()
        host(w)
        PlaylistFlow.nameIt(PlaylistNameRequest(PlaylistSource.NOTHING, emptyList()))
        until { rule.onAllNodesWithTag("playlist-name-cancel", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("playlist-name-cancel", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertNull(PlaylistFlow.nameSheet.value)
        assertTrue(w.created.isEmpty())
    }
}
