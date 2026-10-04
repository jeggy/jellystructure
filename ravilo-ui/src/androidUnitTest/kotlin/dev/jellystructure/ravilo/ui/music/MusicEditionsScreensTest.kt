package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import dev.jellystructure.shared.tv.MusicAlbumCard
import dev.jellystructure.shared.tv.MusicAlbumDetail
import dev.jellystructure.shared.tv.MusicArtistCard
import dev.jellystructure.shared.tv.MusicArtistDetail
import dev.jellystructure.shared.tv.MusicSinglesUnder
import dev.jellystructure.shared.tv.MusicTrackItem
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun initContext() = dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())

@Composable
private fun Desktop(on: Boolean, content: @Composable () -> Unit) {
    if (!on) { content(); return }
    androidx.compose.runtime.CompositionLocalProvider(
        dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily provides dev.jellystructure.ravilo.ui.theme.LayoutFamily.DESKTOP,
    ) { content() }
}

/** R373 (test 7, dev review 4) — the ▾ pick, against the real Android store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumPickStoreTest {
    @Before fun setUp() { initContext(); AlbumPickStore.clear() }

    @Test fun the_pick_is_per_album_and_per_device() {
        AlbumPickStore.set("kw", AlbumPick.EXTRAS)
        assertEquals(AlbumPick.EXTRAS, AlbumPickStore.get("kw"))
        assertEquals(AlbumPick.ALBUM, AlbumPickStore.get("sf"))
        AlbumPickStore.set("kw", AlbumPick.ALBUM)
        assertEquals(AlbumPick.ALBUM, AlbumPickStore.get("kw"))
    }

    @Test fun forget_listening_clears_it() {
        AlbumPickStore.set("kw", AlbumPick.EXTRAS)
        forgetListening()
        assertEquals(AlbumPick.ALBUM, AlbumPickStore.get("kw"))
    }

    @Test fun a_garbled_value_reads_as_the_plain_album() {
        MusicDeviceStore.put("album_pick", "{\"kw\": [not json")
        assertEquals(AlbumPick.ALBUM, AlbumPickStore.get("kw"))
    }
}

/** R373 (test 8, FR-R373-2) — the album page on a phone and on the desktop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h4000dp")
class MusicAlbumEditionsTest {
    @get:Rule val rule = createComposeRule()
    private val played = ArrayList<Pair<List<String>, Boolean>>()
    private var opened: String? = null

    @Before fun setUp() { initContext(); AlbumPickStore.clear(); played.clear() }

    private fun render(d: MusicAlbumDetail, desktop: Boolean = false) {
        rule.setContent {
            Desktop(desktop) {
                MusicAlbumScreen(
                    loader = MusicLoader { d }, onBack = {}, onOpenArtist = {}, onOpenAlbum = { opened = it }, onTrackMore = {},
                    onPlayQueue = { l, _, _, s -> played += l.map { it.id } to s },
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Kite Weather", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun assertKiteWeather() {
        rule.onNodeWithText("11 songs", substring = true).assertExists()
        rule.onNodeWithText("+ 1 extra", substring = true).assertExists()
        rule.onNodeWithText("Extras · Japan").assertExists()
        rule.onNodeWithText("Lantern Swing").assertExists()
        assertTrue(rule.onAllNodesWithText("Bonus").fetchSemanticsNodes().isEmpty(), "no Bonus on the album's own extras")
        assertEquals(5, rule.onAllNodesWithText("· Single", substring = true).fetchSemanticsNodes().size)
        rule.onNodeWithText("13 B-sides").assertExists()
        rule.onNodeWithText("Show").performClick()
        rule.waitForIdle()
        assertEquals(3, rule.onAllNodesWithText("from Northern Line (single)").fetchSemanticsNodes().size)
        rule.onNodeWithText("Hide").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("from Northern Line (single)").fetchSemanticsNodes().isEmpty())
    }

    @Test fun kite_weather_on_a_phone() { render(KiteWeather.detail()); assertKiteWeather() }

    @Test fun the_same_page_on_the_desktop() { render(KiteWeather.detail(), desktop = true); assertKiteWeather() }

    @Test fun the_menu_picks_relabels_and_starts_playback() {
        render(KiteWeather.detail())
        rule.onAllNodesWithContentDescription("More ways to play")[0].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("11 songs · the official order").assertExists()
        rule.onNodeWithText("25 songs · extras, then the B-sides").assertExists()
        rule.onNodeWithText("Play album + extras").performClick()
        rule.waitForIdle()
        assertEquals(25, played.last().first.size); assertEquals(false, played.last().second)
        rule.onNodeWithText("Play album + extras").assertExists()   // the button relabels
        // Shuffle follows the pick: the same 25, shuffled.
        rule.onNodeWithText("Shuffle").performClick()
        rule.waitForIdle()
        assertEquals(25, played.last().first.size); assertEquals(true, played.last().second)
        assertEquals(AlbumPick.EXTRAS, AlbumPickStore.get("kw"), "a fresh composition reads the remembered pick")
        // Shuffle's own ▾ picks too, and starts a shuffle.
        rule.onAllNodesWithContentDescription("More ways to play")[1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Play album").performClick()
        rule.waitForIdle()
        assertEquals(11, played.last().first.size); assertEquals(true, played.last().second)
        assertEquals(AlbumPick.ALBUM, AlbumPickStore.get("kw"))
    }

    @Test fun an_unmatched_album_is_todays_page() {
        render(KiteWeather.detail(officialIds = null).copy(singles = emptyList(), bsideTracks = emptyList()))
        assertTrue(rule.onAllNodesWithContentDescription("More ways to play").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Extras", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Singles & B-sides").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Play").performClick()
        rule.waitForIdle()
        assertEquals(KiteWeather.detail().tracks.map { it.id }, played.last().first)
    }

    @Test fun a_singles_page_says_single_from_and_opens_the_album() {
        val single = MusicAlbumDetail(MusicAlbumCard("s-0", "Northern Line", year = 2005, type = "single"),
            tracks = listOf(KiteWeather.track("s-0-1", "Northern Line", 1, "Northern Line", "s-0")),
            singleFrom = MusicAlbumCard("kw", "Kite Weather", year = 2006))
        rule.setContent { MusicAlbumScreen(MusicLoader { single }, {}, {}, { opened = it }, {}, { _, _, _, _ -> }) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Single from Kite Weather").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Single from Kite Weather").performClick()
        assertEquals("kw", opened)
    }
}

/** R373 (test 9, FR-R373-5/6) — the chips sit just left of the length; Bonus is never folded or cut. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackRowChipsTest {
    @get:Rule val rule = createComposeRule()
    @Before fun setUp() = initContext()

    private val song = MusicTrackItem("kw-12", "Lantern Swing", albumId = "kw", album = "Kite Weather", durationMs = 224_000, versions = listOf("live"), extra = true)

    @Test fun chips_sit_between_the_title_and_the_length() {
        rule.setContent { Box(Modifier.width(400.dp)) { TrackRow(song, onPlay = {}, onMore = {}, number = "") } }
        val title = rule.onNodeWithText("Lantern Swing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bonus = rule.onNodeWithText("Bonus", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val length = rule.onNodeWithText("3:44", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= bonus.left, "title before the chips: $title / $bonus / $length")
        assertTrue(bonus.right <= length.left, "the chips just left of the length")
        assertTrue(length.left - bonus.right < 40f, "no gap of a column between them on a phone")
    }

    @Test fun at_phone_width_the_title_ellipsizes_the_versions_fold_and_bonus_stays() {
        val long = song.copy(title = "Lantern Swing (the long instrumental reprise for the harbour)", versions = listOf("live", "acoustic", "alternate", "session"))
        rule.setContent { Box(Modifier.width(300.dp)) { TrackRow(long, onPlay = {}, onMore = {}, number = "") } }
        val title = rule.onNodeWithText(long.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bonus = rule.onNodeWithText("Bonus", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(title.right <= bonus.left, "the title ellipsizes before Bonus: $title / $bonus")
        // The versions fold to *+N* (the chips speak one sentence, so their words are not separate text nodes).
        assertNotNull(rule.onAllNodesWithContentDescription("Version", substring = true).fetchSemanticsNodes().firstOrNull())
    }

    @Test fun the_queue_panel_shows_bonus() {
        // The phone's queue rows are TrackRows with a cover; Bonus is drawn there.
        rule.setContent { Box(Modifier.width(400.dp)) { TrackRow(song, onPlay = {}, onMore = {}, showCover = true) } }
        rule.onNodeWithText("Bonus").assertExists()
    }

    @Test fun an_albums_own_extras_show_no_bonus() {
        rule.setContent { Box(Modifier.width(400.dp)) { TrackRow(song, onPlay = {}, onMore = {}, number = "", showBonus = false) } }
        assertTrue(rule.onAllNodesWithText("Bonus").fetchSemanticsNodes().isEmpty())
    }
}

/** R373 (test 10, FR-R373-4) — *Also on* at the end of the song's ⋯ sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h2000dp")
class SongSheetAlsoOnTest {
    @get:Rule val rule = createComposeRule()
    private var calls = 0
    private var opened: String? = null
    @Before fun setUp() { initContext(); calls = 0 }

    private val nl = MusicTrackItem("kw-2", "Northern Line", albumId = "kw", album = "Kite Weather", alsoOn = 3)
    private val copies = """{"copies":[
        {"track":{"id":"s-0-1","title":"Northern Line","album_id":"s-0"},"album":{"id":"s-0","title":"Northern Line","year":2006,"type":"single"}},
        {"track":{"id":"tt-1","title":"Northern Line","album_id":"tt"},"album":{"id":"tt","title":"Tide Tables 1999–2012","year":2012,"type":"compilation"}},
        {"track":{"id":"nn-1","title":"Northern Line","album_id":"nn"},"album":{"id":"nn","title":"Nordic Nights Vol. 2","year":2008,"type":"compilation"}}]}"""

    private fun render(t: MusicTrackItem, body: String?) {
        val api = dev.jellystructure.ravilo.ui.screens.fakeTvApiClient { path -> if (path.endsWith("/copies")) { calls++; body } else null }
        rule.setContent {
            TrackActionsSheet(TrackSheetRequest(t), onDismiss = {}, onGoAlbum = { opened = it }, onGoArtist = {}, onFavorite = { _, _ -> },
                loadCopies = { id -> api.getMusicCopies(id) })
        }
        rule.waitForIdle()
    }

    @Test fun northern_line_is_also_on_3_releases() {
        render(nl, copies)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Also on 3 releases").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Single · 2006").assertExists()
        rule.waitForIdle()
        rule.onNodeWithText("Compilation · 2012").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("tt", opened)
    }

    @Test fun one_copy_reads_also_on_1_release() {
        render(nl.copy(alsoOn = 1), """{"copies":[{"track":{"id":"s-0-1","title":"Northern Line"},"album":{"id":"s-0","title":"Northern Line","year":2006,"type":"single"}}]}""")
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Also on 1 release").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun also_on_0_draws_no_section_and_asks_nothing() {
        render(nl.copy(alsoOn = 0), copies)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Also on", substring = true).fetchSemanticsNodes().isEmpty())
        assertEquals(0, calls)
    }

    @Test fun a_failed_copies_call_leaves_the_rest_of_the_sheet() {
        render(nl, null)
        rule.waitUntil(5_000) { calls > 0 }
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Also on", substring = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Play next").assertExists()
    }
}

/** R373 (test 11, FR-R373-7) — the artist page's line, and lists drawn as sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w420dp-h3000dp")
class MusicArtistEditionsTest {
    @get:Rule val rule = createComposeRule()
    private var opened: String? = null
    @Before fun setUp() = initContext()

    private fun render(d: MusicArtistDetail) {
        rule.setContent { MusicArtistScreen(MusicLoader { d }, onBack = {}, onOpenAlbum = { opened = it }, onPlayVideo = {}, onTrackMore = {}) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Harbour Lights").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    @Test fun homed_singles_leave_singles_and_eps() {
        render(MusicArtistDetail(MusicArtistCard("hl", "Harbour Lights"), singlesUnder = listOf(MusicSinglesUnder(MusicAlbumCard("kw", "Kite Weather", year = 2006), 5))))
        rule.onNodeWithText("5 singles live under their album — ", substring = true).assertExists()
        rule.onNodeWithText("Kite Weather").performClick()
        assertEquals("kw", opened)
    }

    @Test fun the_songs_list_renders_what_the_server_sent() {
        val twins = listOf(MusicTrackItem("kw-2", "Northern Line", album = "Kite Weather"), MusicTrackItem("nn-1", "Northern Line", album = "Nordic Nights Vol. 2"))
        render(MusicArtistDetail(MusicArtistCard("hl", "Harbour Lights"), topTracks = twins))
        assertEquals(2, rule.onAllNodesWithText("Northern Line").fetchSemanticsNodes().size, "the app folds nothing")
    }
}
