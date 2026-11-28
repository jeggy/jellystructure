package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MovieDetail
import dev.jellystructure.shared.tv.Person
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * DEB-5839 (FR-1) — Back to a film page lands where it was left. Opening a cast face, a related title, a genre chip or Play
 * records the control and the page's scroll in the store's [DetailReturnTarget]; the page rebuilt on Back puts focus back on
 * it, once. A later arrival, or a control the page no longer holds, focuses Play. A TV-sized configuration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class MovieDetailFocusTest {
    @get:Rule val rule = createComposeRule()

    private fun card(id: String) = MediaCard(id = id, title = "Title $id", year = 2020, genre = null, rating = null, posterUrl = null, backdropUrl = null)

    private val detail = MovieDetail(
        card = card("film"), synopsis = null, runtime = 100,
        cast = (1..12).map { Person(id = "p$it", name = "Person $it", role = null, imageUrl = null) },
        related = (1..12).map { card("rel$it") },
        genres = listOf("Drama", "Comedy"),
    )

    private fun render(target: DetailReturnTarget, opened: MutableList<String> = mutableListOf()) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        rule.setContent {
            MovieDetailLoaded(
                detail = detail, overlay = emptyMap(), onBack = {}, onPlay = { opened += "play" }, onMarkPlayed = {}, onMarkFavorite = {},
                onRelatedSelect = { opened += "related:${it.id}" },
                onCastSelect = { p, _ -> opened += "cast:${p.id}" }, onGenreSelect = null,
                displayName = "Olivar", onNavSelect = {}, onProfile = null, onSearch = null, onAnother = null,
                returnTarget = target,
            )
        }
        rule.waitForIdle()
    }

    private fun playFocused() =
        rule.onNode(isFocused() and (hasTestTag("movie-play") or hasAnyAncestor(hasTestTag("movie-play"))), useUnmergedTree = true).assertExists()

    private fun focusedInsideText(text: String) =
        rule.onNode(isFocused() and (hasText(text) or hasAnyAncestor(hasText(text)) or hasAnyDescendant(hasText(text))), useUnmergedTree = true).assertExists()

    @Test fun `a cast face opened and Back lands on that face`() {
        val target = DetailReturnTarget()
        target.remember(DetailReturnFocus.Cast("p9"), ListScroll(1, 0))
        render(target)
        focusedInsideText("Person 9")
    }

    @Test fun `a related title opened and Back lands on that title`() {
        val target = DetailReturnTarget()
        target.remember(DetailReturnFocus.Related("rel8"), ListScroll(2, 0))
        render(target)
        focusedInsideText("Title rel8")
    }

    @Test fun `a first arrival focuses Play`() {
        render(DetailReturnTarget())
        playFocused()
    }

    @Test fun `Play pressed and Back lands on Play`() {
        val target = DetailReturnTarget()
        target.remember(DetailReturnFocus.Play, ListScroll(0, 0))
        render(target)
        playFocused()
    }

    @Test fun `a return target is read once`() {
        val target = DetailReturnTarget()
        target.remember(DetailReturnFocus.Cast("p3"), ListScroll(2, 10))
        assertEquals(DetailReturnFocus.Cast("p3") to ListScroll(2, 10), target.take())
        assertEquals(null, target.take())
    }

    @Test fun `a cast face the page no longer holds falls back to Play`() {
        val target = DetailReturnTarget()
        target.remember(DetailReturnFocus.Cast("gone"), ListScroll(2, 0))
        render(target)
        playFocused()
    }

    @Test fun `a related title the page no longer holds falls back to Play`() {
        val target = DetailReturnTarget()
        target.remember(DetailReturnFocus.Related("gone"), ListScroll(3, 0))
        render(target)
        playFocused()
    }
}
