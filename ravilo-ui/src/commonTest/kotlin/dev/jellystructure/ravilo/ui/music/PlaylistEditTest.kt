package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun song(n: Int, entry: String? = "e$n") = MusicTrackItem(id = "s$n", title = "Song $n", entryId = entry)

private fun list(vararg n: Int, mine: Boolean = true) = MusicList(tracks = n.map { song(it) }, total = n.size, mine = mine)

/** A stand-in for the server: answers what it is told to, records what it was asked. */
private class FakeChanges(var fail: Throwable? = null) : PlaylistChanges {
    val moves = ArrayList<Triple<String, String, Int>>()
    val removed = ArrayList<Pair<String, String>>()
    var deleted: String? = null
    var renamed: Pair<String, String>? = null
    /** What the server holds: the answer to a move or a remove is this, after the change. */
    var held: List<MusicTrackItem> = emptyList()
    override suspend fun remove(id: String, entryId: String): MusicList {
        fail?.let { throw it }
        removed += id to entryId
        held = held.filter { it.entryId != entryId }
        return MusicList(tracks = held, total = held.size, mine = true)
    }
    override suspend fun move(id: String, entryId: String, to: Int): MusicList {
        fail?.let { throw it }
        moves += Triple(id, entryId, to)
        held = reordered(held, held.indexOfFirst { it.entryId == entryId }, to)
        return MusicList(tracks = held, total = held.size, mine = true)
    }
    override suspend fun rename(id: String, name: String): MusicPlaylist { fail?.let { throw it }; renamed = id to name; return MusicPlaylist(id, name, held.size, mine = true) }
    override suspend fun delete(id: String) { fail?.let { throw it }; deleted = id }
}

/** DEB-2254 (FR-8 to FR-13) — what a playlist's page offers and how its writes change what it shows. Stand-in names only. */
class PlaylistEditTest {
    private fun order(l: MusicLoader<MusicList>) = ((l.state.value as Load.Ready).value).tracks.map { it.entryId }

    private fun page(vararg n: Int): Pair<MusicLoader<MusicList>, FakeChanges> {
        val l = MusicLoader<MusicList> { list(*n) }
        l.put(list(*n))
        return l to FakeChanges().also { it.held = list(*n).tracks }
    }

    // ── FR-8 ──

    @Test fun a_shared_playlist_draws_no_menu_and_an_own_one_does() {
        assertFalse(playlistMenuShown(mine = false))
        assertTrue(playlistMenuShown(mine = true))
    }

    @Test fun an_empty_playlist_menu_has_no_edit() {
        assertFalse(playlistMenuHasEdit(0))
        assertTrue(playlistMenuHasEdit(1))
    }

    // ── FR-9 ──

    @Test fun a_song_can_be_removed_only_from_an_own_playlist_and_only_with_an_entry() {
        assertTrue(canRemoveFromPlaylist(true, song(1)))
        assertFalse(canRemoveFromPlaylist(false, song(1)))
        assertFalse(canRemoveFromPlaylist(true, song(1, entry = null)))
    }

    @Test fun removing_shows_the_servers_answer() = runTest {
        val (l, c) = page(1, 2, 3)
        assertTrue(PlaylistEditor("p", l, c).remove("e2"))
        assertEquals(listOf("p" to "e2"), c.removed)
        assertEquals(listOf<String?>("e1", "e3"), order(l))
    }

    @Test fun a_failed_remove_leaves_the_list_as_it_was() = runTest {
        val (l, c) = page(1, 2, 3)
        c.fail = TvApiError.Http(502, "no")
        assertFalse(PlaylistEditor("p", l, c).remove("e2"))
        assertEquals(listOf<String?>("e1", "e2", "e3"), order(l))
    }

    // ── FR-10 ──

    @Test fun the_drop_index_is_where_the_row_lands() {
        // 60 px rows: a row dragged 2.4 rows down from 1 lands on 3; one dragged 0.4 of a row has not moved.
        assertEquals(3, dropIndex(1, 144f, 60f, 6))
        assertEquals(1, dropIndex(1, 24f, 60f, 6))
        assertEquals(0, dropIndex(2, -150f, 60f, 6))
        assertEquals(0, dropIndex(1, -9000f, 60f, 6), "never above the top")
        assertEquals(5, dropIndex(4, 9000f, 60f, 6), "never below the end")
    }

    @Test fun reordering_puts_the_row_where_it_was_dropped() {
        assertEquals(listOf(1, 3, 4, 2, 5), reordered(listOf(1, 2, 3, 4, 5), from = 1, to = 3))
        assertEquals(listOf(5, 1, 2, 3, 4), reordered(listOf(1, 2, 3, 4, 5), from = 4, to = 0))
        assertEquals(listOf(1, 2, 3), reordered(listOf(1, 2, 3), from = 1, to = 1))
    }

    @Test fun the_move_sent_carries_the_entry_and_the_drop_position() = runTest {
        val (l, c) = page(1, 2, 3, 4, 5)
        // Song 5 dragged to the top: acceptance 6.
        assertTrue(PlaylistEditor("p", l, c).move(from = 4, to = dropIndex(4, -4 * 62f, 62f, 5)))
        assertEquals(listOf(Triple("p", "e5", 0)), c.moves)
        assertEquals(listOf<String?>("e5", "e1", "e2", "e3", "e4"), order(l))
    }

    @Test fun a_failed_move_puts_the_order_back() = runTest {
        val (l, c) = page(1, 2, 3, 4, 5)
        c.fail = TvApiError.Http(502, "no")
        assertFalse(PlaylistEditor("p", l, c).move(from = 4, to = 0))
        assertEquals(listOf<String?>("e1", "e2", "e3", "e4", "e5"), order(l))
        assertTrue(c.moves.isEmpty())
    }

    @Test fun a_drop_on_the_same_place_writes_nothing() = runTest {
        val (l, c) = page(1, 2, 3)
        assertTrue(PlaylistEditor("p", l, c).move(from = 1, to = 1))
        assertTrue(c.moves.isEmpty())
    }

    @Test fun a_row_with_no_entry_cannot_be_moved() = runTest {
        val l = MusicLoader<MusicList> { list(1) }
        l.put(MusicList(tracks = listOf(song(1, entry = null), song(2)), mine = true))
        val c = FakeChanges()
        assertFalse(PlaylistEditor("p", l, c).move(0, 1))
        assertTrue(c.moves.isEmpty())
    }

    // ── FR-11, FR-12 ──

    @Test fun rename_answers_the_name_the_server_kept() = runTest {
        val (l, c) = page(1)
        assertEquals("Stand-in Mix", PlaylistEditor("p", l, c).rename("Stand-in Mix").name)
        assertEquals("p" to "Stand-in Mix", c.renamed)
    }

    @Test fun a_name_the_server_refuses_is_recognised_so_the_sheet_stays_open() = runTest {
        val (l, c) = page(1)
        c.fail = TvApiError.Http(400, "no")
        val e = runCatching { PlaylistEditor("p", l, c).rename("  ") }.exceptionOrNull()
        assertTrue(e != null && isNameRefusal(e))
    }

    @Test fun delete_calls_the_route_once() = runTest {
        val (l, c) = page(1)
        PlaylistEditor("p", l, c).delete()
        assertEquals("p", c.deleted)
    }
}
