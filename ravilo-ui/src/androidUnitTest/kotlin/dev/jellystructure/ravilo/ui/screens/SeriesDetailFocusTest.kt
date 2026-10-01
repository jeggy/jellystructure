package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import dev.jellystructure.ravilo.ui.components.SeasonPickerTags
import dev.jellystructure.shared.tv.CardPlayState
import dev.jellystructure.shared.tv.Episode
import dev.jellystructure.shared.tv.MediaCard
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Season
import dev.jellystructure.shared.tv.SeriesDetail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R343 (FR-R343-9, owner: *"make sure that we don't keep falling into these D-pad focus issues that we keep on
 * having"*) — the series page's D-pad path, walked key by key on the JVM under Robolectric, on the fixtures
 * that broke before:
 *
 * - **R138 / R232** — Down from Play must focus the selected season pill on the FIRST press, with the playstate
 *   overlay landing a frame after the first composition (the R232 race ate the focus retry budget).
 * - **R201** — an 11-season series whose active season is the 11th: the selected pill must be attached.
 * - **R296** — one season, no pill row: Down from Play enters the rail, Up from a card returns to Play, Up from a
 *   card's Watched toggle returns to that card.
 * - **This phase** — the header above the pills, Shuffle last in the row, a finished series (Start over), and a
 *   one-season series of 9+ episodes (one Season 1 pill, then Shuffle).
 *
 * A TV-sized configuration, so `LocalCompact` keeps its TV default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class SeriesDetailFocusTest {
    @get:Rule val rule = createComposeRule()

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────────

    private fun ep(s: Int, e: Int) = Episode(
        id = "s${s}e$e", episodeNumber = e, title = "Episode $e", runtime = 11, overview = null, stillUrl = null,
        file = "/tv/show/S${s}E$e.mkv",
    )

    private fun series(seasons: Int, perSeason: Int, shuffle: Boolean, startOver: Boolean = true) = SeriesDetail(
        card = MediaCard(id = "series", kind = MediaKind.SERIES, title = "Stand-in series", year = 2020, genre = null,
            rating = null, posterUrl = null, backdropUrl = null),
        synopsis = null,
        seasons = (1..seasons).map { s -> Season(index = s, name = "Season $s", episodes = (1..perSeason).map { ep(s, it) }) },
        cast = emptyList(),
        related = emptyList(),
        startOver = startOver,
        shuffle = shuffle,
    )

    private fun watched(detail: SeriesDetail, upToSeason: Int): Map<String, CardPlayState> =
        detail.seasons.filter { it.index <= upToSeason }.flatMap { it.episodes }.associate { it.id to CardPlayState(played = true, playedPct = 1f) } +
            mapOf(detail.card.id to CardPlayState())   // never empty: the overlay "has landed"

    private fun render(detail: SeriesDetail, overlay: Map<String, CardPlayState>, overlayLate: Boolean = false) {
        // What the app's Application.onCreate does (the platform seams read it, e.g. the cast state).
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        var shown by mutableStateOf(if (overlayLate) emptyMap() else overlay)
        rule.setContent {
            SeriesDetailLoaded(
                detail = detail, overlay = shown, onBack = {}, onPlay = {}, onMarkEpisode = { _, _ -> },
                onMarkFavorite = {}, onRelatedSelect = {}, onCastSelect = null, onGenreSelect = null,
                displayName = "Olivar", onNavSelect = {}, onProfile = null, onSearch = null,
            )
        }
        if (overlayLate) {
            // R84 — the overlay lands after the catalog paint: one frame after the first composition.
            rule.mainClock.advanceTimeByFrame()
            shown = overlay
        }
        rule.waitForIdle()
    }

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    private fun focusedInside(tag: String) =
        rule.onNode(isFocused() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertExists()

    private val inPillRow = SemanticsMatcher("a season pill or Shuffle") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("season-pill-") || it == SeasonPickerTags.SHUFFLE } == true
    }

    // ── R138 / R232 ────────────────────────────────────────────────────────────────────────────────

    @Test fun `3 seasons, overlay late — Down focuses the selected pill first time, Right reaches Shuffle, Down and Up round-trip`() {
        val d = series(3, 13, shuffle = true)
        render(d, watched(d, upToSeason = 0), overlayLate = true)
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()

        press(Key.DirectionDown)
        rule.onNodeWithTag(SeasonPickerTags.pill(1), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        rule.onNodeWithTag(SeasonPickerTags.pill(3), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(SeasonPickerTags.SHUFFLE, useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionRight)   // the true end of the row: nothing, never the AppBar's avatar
        rule.onNodeWithTag(SeasonPickerTags.SHUFFLE, useUnmergedTree = true).assertIsFocused()

        press(Key.DirectionDown)
        focusedInside(SeriesDetailTags.RAIL)
        press(Key.DirectionUp)
        rule.onNode(isFocused() and inPillRow, useUnmergedTree = true).assertExists()
        press(Key.DirectionUp)
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()
    }

    // ── R201 ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `11 seasons, active season 11 — Down from Play focuses pill 11 on the first press`() {
        val d = series(11, 4, shuffle = true)
        render(d, watched(d, upToSeason = 10))
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(SeasonPickerTags.pill(11), useUnmergedTree = true).assertIsFocused()
    }

    // ── R296 ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `1 season of 6, no pill row — Down enters the rail, Up returns to Play, Up from a toggle returns to its card`() {
        val d = series(1, 6, shuffle = false)
        render(d, watched(d, upToSeason = 0))
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()
        rule.onNodeWithTag(SeasonPickerTags.pill(1), useUnmergedTree = true).assertDoesNotExist()

        press(Key.DirectionDown)
        focusedInside(SeriesDetailTags.card("s1e1"))
        press(Key.DirectionUp)
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()

        press(Key.DirectionDown)
        focusedInside(SeriesDetailTags.card("s1e1"))
        press(Key.DirectionDown)
        rule.onNodeWithTag(SeriesDetailTags.toggle("s1e1"), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionUp)
        focusedInside(SeriesDetailTags.card("s1e1"))
    }

    // ── this phase ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `a finished 3 x 13 series opens on Season 1 and Play reads Start over`() {
        val d = series(3, 13, shuffle = true)
        render(d, watched(d, upToSeason = 3))
        rule.onNodeWithText("Start over · S01E01").assertExists()
        rule.onNodeWithText("All 39 episodes watched").assertExists()
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(SeasonPickerTags.pill(1), useUnmergedTree = true).assertIsFocused()
    }

    @Test fun `an older server that cannot clear reads Play on a finished series`() {
        val d = series(3, 13, shuffle = false, startOver = false)
        render(d, watched(d, upToSeason = 3))
        rule.onNodeWithText("Play · S01E01").assertExists()
        rule.onNodeWithTag(SeasonPickerTags.SHUFFLE, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `1 season of 13 draws one Season 1 pill and Shuffle`() {
        val d = series(1, 13, shuffle = true)
        render(d, watched(d, upToSeason = 0))
        rule.onNodeWithText("Play · S01E01").assertExists()
        press(Key.DirectionDown)
        rule.onNodeWithTag(SeasonPickerTags.pill(1), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionRight)
        rule.onNodeWithTag(SeasonPickerTags.SHUFFLE, useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionDown)
        focusedInside(SeriesDetailTags.RAIL)
    }

    // ── R350 ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `R350-1 — Down from Play focuses the open season even after Shuffle was the last pill`() {
        val d = series(3, 13, shuffle = true)
        render(d, watched(d, upToSeason = 0))
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()
        press(Key.DirectionDown)
        rule.onNodeWithTag(SeasonPickerTags.pill(1), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionRight); press(Key.DirectionRight); press(Key.DirectionRight)
        rule.onNodeWithTag(SeasonPickerTags.SHUFFLE, useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionDown)
        focusedInside(SeriesDetailTags.RAIL)
        // The rail's own memory: Up returns to the pill last focused (Shuffle).
        press(Key.DirectionUp)
        rule.onNodeWithTag(SeasonPickerTags.SHUFFLE, useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionUp)
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()
        // Down from Play: the open season, never the restored Shuffle.
        press(Key.DirectionDown)
        rule.onNodeWithTag(SeasonPickerTags.pill(1), useUnmergedTree = true).assertIsFocused()
    }

    /** The page as the app shows it: gone while the player is on top, rebuilt from scratch on Back (only the top
     *  of the stack is composed), with the store's [SeriesReturnTarget] surviving in between. */
    private fun renderLeavingForThePlayer(detail: SeriesDetail, overlay: Map<String, CardPlayState>, target: SeriesReturnTarget): () -> Unit {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        var shown by mutableStateOf(true)
        rule.setContent {
            if (shown) SeriesDetailLoaded(
                detail = detail, overlay = overlay, onBack = {}, onPlay = { shown = false }, onMarkEpisode = { _, _ -> },
                onMarkFavorite = {}, onRelatedSelect = {}, onCastSelect = null, onGenreSelect = null,
                displayName = "Olivar", onNavSelect = {}, onProfile = null, onSearch = null,
                onShuffle = { shown = false }, returnTarget = target,
            )
        }
        rule.waitForIdle()
        return { shown = true; rule.waitForIdle() }
    }

    @Test fun `R350-2 — Shuffle, the player, Back lands on Shuffle again`() {
        val d = series(3, 13, shuffle = true)
        val back = renderLeavingForThePlayer(d, watched(d, upToSeason = 0), SeriesReturnTarget())
        press(Key.DirectionDown)
        press(Key.DirectionRight); press(Key.DirectionRight); press(Key.DirectionRight)
        rule.onNodeWithTag(SeasonPickerTags.SHUFFLE, useUnmergedTree = true).assertIsFocused()
        press(Key.Enter)
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertDoesNotExist()   // the player is on top
        back()
        rule.onNodeWithTag(SeasonPickerTags.SHUFFLE, useUnmergedTree = true).assertIsFocused()
        // …and the page's own paths still hold from there.
        press(Key.DirectionUp)
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()
    }

    @Test fun `R350-2 — an episode card in Season 2, the player, Back lands on that card, on Season 2`() {
        val d = series(3, 6, shuffle = false)
        val back = renderLeavingForThePlayer(d, watched(d, upToSeason = 0), SeriesReturnTarget())
        press(Key.DirectionDown)
        rule.onNodeWithTag(SeasonPickerTags.pill(1), useUnmergedTree = true).assertIsFocused()
        press(Key.DirectionRight)
        press(Key.Enter)                      // open Season 2
        press(Key.DirectionDown)
        focusedInside(SeriesDetailTags.card("s2e1"))
        press(Key.DirectionRight)
        focusedInside(SeriesDetailTags.card("s2e2"))
        press(Key.Enter)
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertDoesNotExist()
        back()
        focusedInside(SeriesDetailTags.card("s2e2"))
        // A second arrival (the target is read once) is an ordinary one: Play.
        press(Key.DirectionUp)
        rule.onNode(isFocused() and inPillRow, useUnmergedTree = true).assertExists()
    }

    @Test fun `R350-2 — Play, the player, Back lands on Play`() {
        val d = series(3, 6, shuffle = true)
        val back = renderLeavingForThePlayer(d, watched(d, upToSeason = 0), SeriesReturnTarget())
        press(Key.Enter)
        back()
        rule.onNodeWithTag(SeriesDetailTags.PLAY).assertIsFocused()
    }

    @Test fun `R350-3 — Resume names an episode in Season 3 — the page opens there and Down focuses Season 3`() {
        val d = series(3, 13, shuffle = true)
        // Season 1 has unwatched episodes, but Continue Watching names S03E10 (R306) — the button resumes it.
        val overlay = watched(d, upToSeason = 0) + ("series" to CardPlayState(continueEpisodeId = "s3e10")) +
            ("s3e9" to CardPlayState(played = true, playedPct = 1f))
        render(d, overlay)
        rule.onNodeWithText("Resume · S03E10").assertExists()
        press(Key.DirectionDown)
        rule.onNodeWithTag(SeasonPickerTags.pill(3), useUnmergedTree = true).assertIsFocused()
        rule.onNodeWithTag(SeriesDetailTags.card("s3e10"), useUnmergedTree = true).assertExists()   // the rail opened on it
        press(Key.DirectionDown)
        focusedInside(SeriesDetailTags.card("s3e10"))
    }
}
