package dev.jellystructure.ravilo.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
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
import dev.jellystructure.shared.tv.SessionList
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.TargetCapabilities
import dev.jellystructure.shared.tv.TargetList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** No Cast session; nothing it is asked goes anywhere. */
private class IdleSender : CastSender {
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
 * R370 (found on the Pixel 9 Pro) — *Play on…* in music mode with three *Playing everywhere* rows, this phone, four free
 * speakers and four not reachable is taller than the sheet: the body scrolls, so the last tier's rows can be reached
 * (they were never laid out, and swipes did nothing).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class PlayOnSheetScrollTest {
    @get:Rule val rule = createComposeRule()

    private val speaker = TargetCapabilities(audio = true)
    private val targets = listOf(PlaybackTarget("pixel", "app", "Pixel", "phone", TargetCapabilities(video = true, audio = true, display = true, book = true), here = true)) +
        (1..4).map { PlaybackTarget("cast:f$it", "cast", "Free room $it", "speaker", speaker, castDeviceId = "f$it") } +
        (1..4).map { PlaybackTarget("cast:z$it", "cast", "Far room $it", "speaker", speaker, reachable = false, castDeviceId = "z$it") }
    private fun session(i: Int) = SessionView("s$i", 1, SessionOwner("u", "Anna"), mine = true, kind = "music", title = "Song $i",
        target = SessionTarget("cast", "rx-$i", "Room $i", "speaker"), state = "playing", controllable = true)

    @After fun tearDown() { PlaybackSessions.clear(); PlayOnStore.api = null }

    @Test fun `the sheet scrolls to the last not reachable row`() {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val body = RaviloWireJson.encodeToString(TargetList.serializer(), TargetList(targets, 0L))
        val api = fakeTvApiClient { path -> if (path == "/api/tv/playback/targets") body else null }
        PlayOnStore.api = api
        PlaybackSessions.onList(SessionList((1..3).map { session(it) }, 1L, 0L))
        val cast = CastController(ActiveCastSender(IdleSender(), ScreenSender(api)), api, "http://fake.invalid")
        rule.setContent { ScreensSheet(cast = cast, open = true, onClose = {}, playContext = null, music = true) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("playon-cast:f1").fetchSemanticsNode() }.isSuccess }
        rule.onNodeWithTag("playon-cast:z4").performScrollTo().assertIsDisplayed()
    }
}
