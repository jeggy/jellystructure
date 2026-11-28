package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.jellystructure.shared.tv.MediaCard
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DEB-5841 (FR-2, FR-3, FR-4) — the after-film page. It takes no playback call: its only inputs are a list loader and two callbacks. */
@OptIn(ExperimentalTestApi::class)
class PostFilmScreenTest {
    private fun card(id: String, watched: Boolean = false) =
        MediaCard(id = id, title = "Pick $id", year = null, genre = null, rating = null,
            posterUrl = null, backdropUrl = null, watched = watched)

    @Test fun `FR-3 a list carried from the film's page opens the page and makes no fetch`() = runComposeUiTest {
        var fetched = false; var closed = false
        setContent {
            PostFilmScreen("played", "A Film", null, null, null, listOf(card("a"), card("b")),
                loadRelated = { fetched = true; emptyList() }, onSelect = {}, onClose = { closed = true })
        }
        waitForIdle()
        onNodeWithTag("post-film-kicker").assertExists()
        assertTrue(!fetched && !closed)
    }

    @Test fun `FR-3 no picks closes the page and never shows it`() = runComposeUiTest {
        var closed = 0
        setContent {
            PostFilmScreen("played", "A Film", null, null, null, listOf(card("a", watched = true), card("played")),
                loadRelated = { emptyList() }, onSelect = {}, onClose = { closed++ })
        }
        waitForIdle()
        assertEquals(1, closed)
        onNodeWithTag("post-film-kicker").assertDoesNotExist()
    }

    @Test fun `FR-3 a failed fetch closes the page`() = runComposeUiTest {
        var closed = 0
        setContent {
            PostFilmScreen("played", "A Film", null, null, null, null,
                loadRelated = { error("network") }, onSelect = {}, onClose = { closed++ })
        }
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun `FR-3 a fetch that has not answered in 3 s closes the page`() = runComposeUiTest {
        var closed = 0
        val never = CompletableDeferred<List<MediaCard>>()
        setContent {
            PostFilmScreen("played", "A Film", null, null, null, null,
                loadRelated = { never.await() }, onSelect = {}, onClose = { closed++ })
        }
        waitForIdle()
        assertEquals(0, closed)
        mainClock.advanceTimeBy(POST_FILM_FETCH_TIMEOUT_MS + 100)
        waitForIdle()
        assertEquals(1, closed)
    }

    @Test fun `FR-3 with no list carried, the fetched one is the source`() = runComposeUiTest {
        var closed = 0
        setContent {
            PostFilmScreen("played", "A Film", null, null, null, null,
                loadRelated = { listOf(card("a")) }, onSelect = {}, onClose = { closed++ })
        }
        waitForIdle()
        assertEquals(0, closed)
        onNodeWithTag("post-film-kicker").assertExists()
    }

    @Test fun `FR-5 the page opens with focus on a tile, and FR-4 selecting it hands the title over`() = runComposeUiTest {
        var picked: String? = null
        setContent {
            PostFilmScreen("played", "A Film", null, null, null, listOf(card("a"), card("b")),
                loadRelated = { emptyList() }, onSelect = { picked = it.id }, onClose = {})
        }
        waitForIdle()
        onNode(isFocused()).assertExists()
        onNodeWithTag("post-film").assertExists()
        onNode(isFocused()).performClick()
        waitForIdle()
        assertEquals("a", picked)
    }
}
