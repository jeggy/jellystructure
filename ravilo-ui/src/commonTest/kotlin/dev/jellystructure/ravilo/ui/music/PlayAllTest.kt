package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.MusicSelection
import dev.jellystructure.shared.tv.MusicTrackItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R388 (DEB-1300; FR-R388-5…8, the 2026-10-22 amendment) — what each surface asks for, and the press itself. */
class PlayAllTest {
    private fun t(id: String) = MusicTrackItem(id = id, title = id)
    private val three = listOf(t("a"), t("b"), t("c"))
    private val sorts = mapOf("albums" to "year", "artists" to "title", "songs" to "played")

    // ── the call per surface ──

    @Test
    fun `each surface sends its own scope and sort`() {
        assertEquals(PlayAllRequest("library"), LISTEN_PLAY_ALL)
        assertEquals(PlayAllRequest("albums", sort = "year"), browsePlayAllRequest("albums", sorts, null, ""))
        assertEquals(PlayAllRequest("artists", sort = "title"), browsePlayAllRequest("artists", sorts, null, ""))
        assertEquals(PlayAllRequest("tracks", sort = "played"), browsePlayAllRequest("songs", sorts, null, ""))
    }

    @Test
    fun `a chip with no sort of its own sends the browse route's default`() {
        assertEquals("added", browsePlayAllRequest("albums", emptyMap(), null, "")?.sort)
        assertEquals("added", browsePlayAllRequest("songs", emptyMap(), null, "")?.sort)
    }

    @Test
    fun `a genre sends the genre and no sort`() {
        assertEquals(PlayAllRequest("genre", genre = "Folk"), browsePlayAllRequest("albums", sorts, "Folk", ""))
    }

    @Test
    fun `a query makes it a search with the query as typed, whatever chip is behind it`() {
        val search = PlayAllRequest("search", query = " Harbour  lights ")
        assertEquals(search, browsePlayAllRequest("songs", sorts, null, " Harbour  lights "))
        assertEquals(search, browsePlayAllRequest("albums", sorts, "Folk", " Harbour  lights "))
    }

    @Test
    fun `genres playlists and audiobooks have no pair`() {
        for (chip in listOf("genres", "playlists", "audiobooks")) assertNull(browsePlayAllRequest(chip, sorts, null, ""), chip)
    }

    // ── Playing from ──

    @Test
    fun `the context kind and label follow the scope`() {
        val lib = "Your library"; val search = "Search “kite”"
        for (r in listOf(LISTEN_PLAY_ALL, PlayAllRequest("albums"), PlayAllRequest("artists"), PlayAllRequest("tracks"))) {
            assertEquals(MusicContext("library", lib), r.context(lib, search), r.scope)
        }
        assertEquals(MusicContext("genre", "Folk"), PlayAllRequest("genre", genre = "Folk").context(lib, search))
        assertEquals(MusicContext("search", search), PlayAllRequest("search", query = "kite").context(lib, search))
    }

    // ── the press ──

    private class Played(val tracks: List<MusicTrackItem>, val index: Int, val context: MusicContext, val shuffle: Boolean)

    private class Rig(answer: suspend (PlayAllRequest, Boolean) -> MusicSelection, pick: (Int) -> Int = { 1 }) {
        val asked = mutableListOf<Pair<PlayAllRequest, Boolean>>()
        val played = mutableListOf<Played>()
        val failures = mutableListOf<Unit>()
        val capped = mutableListOf<Pair<Int, Int>>()
        val runner = PlayAll(
            fetch = { r, s -> asked += r to s; answer(r, s) },
            play = { tr, i, c, s -> played += Played(tr, i, c, s) },
            pick = pick,
        )
        suspend fun press(half: PlayAllHalf, request: PlayAllRequest = PlayAllRequest("albums", sort = "added")) =
            runner.run(request, half, MusicContext("library", "Your library"), { failures += Unit }, { shown, total -> capped += shown to total })
    }

    @Test
    fun `Play all asks unshuffled and starts at the first song`() = runTest {
        val rig = Rig({ _, _ -> MusicSelection(three, 3, false) })
        assertTrue(rig.press(PlayAllHalf.PLAY))
        assertEquals(false, rig.asked.single().second)
        val p = rig.played.single()
        assertEquals(0, p.index); assertFalse(p.shuffle); assertEquals(three, p.tracks)
        assertEquals("library", p.context.kind)
    }

    @Test
    fun `Shuffle all asks shuffled and starts at a random song with shuffle on`() = runTest {
        val rig = Rig({ _, _ -> MusicSelection(three, 3, false) }, pick = { size -> size - 1 })
        rig.press(PlayAllHalf.SHUFFLE)
        assertEquals(true, rig.asked.single().second)
        val p = rig.played.single()
        assertEquals(2, p.index); assertTrue(p.shuffle)
    }

    @Test
    fun `the capped toast fires only when capped, with both numbers`() = runTest {
        val whole = Rig({ _, _ -> MusicSelection(three, 3, false) })
        whole.press(PlayAllHalf.PLAY)
        assertTrue(whole.capped.isEmpty())
        val big = Rig({ _, _ -> MusicSelection(three, 10_900, true) })
        big.press(PlayAllHalf.PLAY)
        assertEquals(listOf(3 to 10_900), big.capped)
        assertEquals(1, big.played.size, "a capped answer still plays")
    }

    @Test
    fun `a failure and an empty answer toast the failure and leave the queue alone`() = runTest {
        val broke = Rig({ _, _ -> error("offline") })
        broke.press(PlayAllHalf.PLAY)
        assertEquals(1, broke.failures.size); assertTrue(broke.played.isEmpty())
        val empty = Rig({ _, _ -> MusicSelection(emptyList(), 0, false) })
        empty.press(PlayAllHalf.SHUFFLE)
        assertEquals(1, empty.failures.size); assertTrue(empty.played.isEmpty())
        assertNull(empty.runner.busy.value, "the control is released after a failure")
    }

    @Test
    fun `a second press while one is in flight sends no second request`() = runTest {
        val gate = CompletableDeferred<MusicSelection>()
        val rig = Rig({ _, _ -> gate.await() })
        val first = async { rig.press(PlayAllHalf.PLAY) }
        advanceUntilIdle()
        assertEquals(PlayAllHalf.PLAY, rig.runner.busy.value)
        assertFalse(rig.press(PlayAllHalf.SHUFFLE), "ignored")
        assertEquals(1, rig.asked.size)
        gate.complete(MusicSelection(three, 3, false))
        assertTrue(first.await())
        assertNull(rig.runner.busy.value)
        assertTrue(rig.press(PlayAllHalf.PLAY), "free again once the request answered")
        assertEquals(2, rig.asked.size)
    }

    @Test
    fun `the capped toast groups digits the way the locale does`() {
        assertEquals("1,000", groupedCount(1000, "en"))
        assertEquals("10.900", groupedCount(10_900, "da"))
        assertEquals("10.900", groupedCount(10_900, "fo"))
        assertEquals("385", groupedCount(385, "en"))
        assertEquals("1.234.567", groupedCount(1_234_567, "fo"))
    }
}
