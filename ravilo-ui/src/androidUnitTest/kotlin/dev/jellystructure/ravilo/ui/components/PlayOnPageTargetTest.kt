package dev.jellystructure.ravilo.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.screens.fakeTvApiClient
import dev.jellystructure.ravilo.ui.seams.ActiveCastSender
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.ravilo.ui.seams.CastSender
import dev.jellystructure.ravilo.ui.seams.ScreenSender
import dev.jellystructure.ravilo.ui.sessions.PlayOnStore
import dev.jellystructure.ravilo.ui.sessions.PlaybackSessions
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.RaviloWireJson
import dev.jellystructure.shared.tv.TargetCapabilities
import dev.jellystructure.shared.tv.TargetList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private class NoSender : CastSender {
    override val link: StateFlow<CastLinkState> = MutableStateFlow(CastLinkState.NONE)
    override val deviceName: StateFlow<String?> = MutableStateFlow(null)
    override val status: StateFlow<CastRemoteStatus?> = MutableStateFlow(null)
    override fun setAppId(appId: String) {}
    override fun load(data: CastLoadData) {}
    override fun play() {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
    override fun stop() {}
    override fun selectSubtitle(trackId: Long?) {}
    override fun selectAudio(trackId: Long?) {}
    override fun send(json: String) {}
}

/**
 * R265 FR-R265-7a (found live 2026-10-09 on the Pixel 9 Pro) — the app bar's cast glyph on a film's page opened the sheet
 * join-only, and a tap on a free TV did nothing at all. The page's title is now what the sheet starts; where there is
 * none (Home), the tap says why nothing happened.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class PlayOnPageTargetTest {
    @get:Rule val rule = createComposeRule()

    private val tv = PlaybackTarget("tv1", "app", "Bedroom TV", "tv", TargetCapabilities(video = true, audio = true, display = true))
    private val phone = PlaybackTarget("pixel", "app", "Pixel", "phone", TargetCapabilities(video = true, audio = true, display = true, book = true), here = true)

    private fun controller(): CastController {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val body = RaviloWireJson.encodeToString(TargetList.serializer(), TargetList(listOf(phone, tv), 0L))
        val api = fakeTvApiClient { path -> if (path == "/api/tv/playback/targets") body else null }
        PlayOnStore.api = api
        return CastController(ActiveCastSender(NoSender(), ScreenSender(api)), api, "http://fake.invalid")
    }

    @After fun tearDown() { PlaybackSessions.clear(); PlayOnStore.api = null; PagePlayTarget.current.value = null }

    @Test fun `the app bar's glyph on a detail page carries the page's title, never in music mode`() {
        val cast = controller()
        cast.openSheet()
        assertNull("Home: nothing published, join-only as before", cast.sheet.value?.playContext)
        PagePlayTarget.current.value = ScreenPlayContext("film-1")
        cast.openSheet()
        assertEquals(ScreenPlayContext("film-1"), cast.sheet.value?.playContext)
        cast.openSheet(ScreenPlayContext("ep-2", 42_000L))
        assertEquals("the player's own context wins", ScreenPlayContext("ep-2", 42_000L), cast.sheet.value?.playContext)
        cast.openSheet(music = true)
        assertNull("the music sheet starts the queue, not the page", cast.sheet.value?.playContext)
    }

    @Test fun `a tap on a free TV with nothing to start says so instead of doing nothing`() {
        val cast = controller()
        var closed = 0
        rule.setContent { ScreensSheet(cast = cast, open = true, onClose = { closed++ }, playContext = null) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("playon-tv1").fetchSemanticsNode() }.isSuccess }
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()   // the sheet has settled
        val before = closed   // the sheet may close itself once while the places load (no rows yet): count from the tap
        rule.onNodeWithTag("playon-tv1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)   // the row's own click (a pointer tap does not reach it under Robolectric)
        rule.waitForIdle()
        rule.onNodeWithText(t("screens.nothing_to_start", "en")).assertIsDisplayed()
        assertEquals("the sheet stays open so the hint is read", before, closed)
    }

    @Test fun `with the page's title a tap on a free TV starts it there`() {
        val cast = controller()
        var closed = 0
        rule.setContent { ScreensSheet(cast = cast, open = true, onClose = { closed++ }, playContext = ScreenPlayContext("film-1")) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("playon-tv1").fetchSemanticsNode() }.isSuccess }
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()   // the sheet has settled
        val before = closed   // the sheet may close itself once while the places load (no rows yet): count from the tap
        rule.onNodeWithTag("playon-tv1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)   // the row's own click (a pointer tap does not reach it under Robolectric)
        rule.waitUntil(5_000) { closed > before }   // startOn closes the sheet only once it has a request to send
        rule.onNodeWithText(t("screens.nothing_to_start", "en")).assertDoesNotExist()
    }
}
