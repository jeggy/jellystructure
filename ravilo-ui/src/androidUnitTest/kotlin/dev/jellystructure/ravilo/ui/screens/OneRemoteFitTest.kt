package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.jellystructure.ravilo.ui.remote.OneRemote
import dev.jellystructure.ravilo.ui.remote.RemoteExtras
import dev.jellystructure.ravilo.ui.remote.RemoteModel
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R350 (FR-R350-18) — the computer's cast remote keeps every control on screen: at the Mac's 1512 × 859 window (where
 * Audio & Subs · Next · Stop sat below the fold) and at the window's minimum height, 600 dp — with the
 * next-up card up, the tallest the remote gets.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // real text metrics: the legacy mode measures a 14 sp line at about twice its height
class OneRemoteFitTest {
    @get:Rule val rule = createComposeRule()

    private val playing = CastRemoteStatus(
        itemId = "ep", title = "Episode 5", kicker = "S01E05", positionMs = 1_900_000, durationMs = 2_128_000,
        playing = true, loaded = true, hasNext = true, transcoding = true,
    )

    private fun render(status: CastRemoteStatus, desktop: Boolean = true) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val remote = OneRemote(RemoteModel(sessionId = null, placeName = "Kitchen hub", placeIcon = null, status = status, kind = "episode"), NoActions, RemoteExtras())
        rule.setContent { OneRemoteScreen(remote = remote, onBack = {}, onPlayAgain = {}, onPlayHere = { _, _, _ -> }, desktop = desktop) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Stop").fetchSemanticsNode() }.isSuccess }
        rule.waitForIdle()
    }

    private fun bottomOf(text: String): Float {
        val n = rule.onNodeWithText(text).fetchSemanticsNode()
        return n.positionInRoot.y + n.size.height
    }

    private fun assertAllOnScreen() {
        val window = rule.onRoot().fetchSemanticsNode().size.height
        val where = listOf("Kitchen hub", "S01E05", "Episode 5", "Play in 6s", "10 s", "Audio & Subs").joinToString { t ->
            runCatching { val n = rule.onNodeWithText(t).fetchSemanticsNode(); "$t@${n.positionInRoot.y.toInt()}+${n.size.height}" }.getOrDefault("$t:-")
        }
        for (label in listOf("Audio & Subs", "Next", "Stop")) {
            val bottom = bottomOf(label)
            assertTrue(bottom <= window, "$label ends at $bottom px, the window at $window px ($where)")
        }
    }

    @Test @Config(sdk = [34], qualifiers = "w1512dp-h859dp-mdpi")
    fun `the Mac's 1512 x 859 window — every control on screen, the next-up card too`() {
        render(playing.copy(nextUpSecs = 6, nextTitle = "Episode 6"))
        rule.onNodeWithText("Play in 6s").fetchSemanticsNode()
        assertAllOnScreen()
    }

    @Test @Config(sdk = [34], qualifiers = "w1512dp-h859dp-mdpi")
    fun `the Mac's 1512 x 859 window — without the card the art keeps a real size`() {
        render(playing)
        assertAllOnScreen()
    }

    @Test @Config(sdk = [34], qualifiers = "w1000dp-h600dp-mdpi")
    fun `the window's minimum height, 600 dp — every control on screen, the next-up card too`() {
        render(playing.copy(nextUpSecs = 6, nextTitle = "Episode 6"))
        assertAllOnScreen()
    }

    @Test @Config(sdk = [34], qualifiers = "w400dp-h600dp-mdpi")
    fun `a compact window, 400 x 600 — every control on screen`() {
        render(playing)
        assertAllOnScreen()
    }

    @Test fun `the art card's size — 16 by 9, no taller than the room, left out when there is none`() {
        assertEquals(600 to 337, remoteArtSize(1000, 0.6f, room = null, minHeight = 72))
        assertEquals(600 to 337, remoteArtSize(1000, 0.6f, room = 500, minHeight = 72))
        assertEquals(355 to 200, remoteArtSize(1000, 0.6f, room = 200, minHeight = 72))
        assertNull(remoteArtSize(1000, 0.6f, room = 60, minHeight = 72))
        assertNull(remoteArtSize(1000, 0.6f, room = -40, minHeight = 72))
    }
}
