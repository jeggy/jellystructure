package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import dev.jellystructure.ravilo.ui.remote.OneRemote
import dev.jellystructure.ravilo.ui.remote.RemoteActions
import dev.jellystructure.ravilo.ui.remote.RemoteExtras
import dev.jellystructure.ravilo.ui.remote.RemoteModel
import dev.jellystructure.ravilo.ui.remote.RemoteOnScreen
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.shared.tv.CastTrack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a press did, in order — the remote's actions are the only thing under test here. */
internal class RecordingActions : RemoteActions {
    val calls = mutableListOf<String>()
    override fun play() { calls += "play" }
    override fun pause() { calls += "pause" }
    override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
    override fun next() { calls += "next" }
    override fun stop() { calls += "stop" }
    override fun selectAudio(trackId: Long?) { calls += "audio:$trackId" }
    override fun selectSubtitle(trackId: Long?) { calls += "sub:$trackId" }
    override fun nextUpPlay() { calls += "nextup_play" }
    override fun nextUpCancel() { calls += "nextup_cancel" }
    override fun subtitleSize(size: Char) { calls += "subsize:$size" }
    override fun retry() { calls += "retry" }
    override fun previous() { calls += "previous" }
    override fun setShuffle(on: Boolean) { calls += "shuffle:$on" }
    override fun setRepeat(mode: String) { calls += "repeat:$mode" }
}

internal object NoActions : RemoteActions {
    override fun play() {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
    override fun next() {}
    override fun stop() {}
    override fun selectAudio(trackId: Long?) {}
    override fun selectSubtitle(trackId: Long?) {}
    override fun nextUpPlay() {}
    override fun nextUpCancel() {}
    override fun subtitleSize(size: Char) {}
    override fun retry() {}
}

/**
 * DEB-2185 (FR-1, FR-2, FR-5, FR-7, FR-8) — the one remote, drawn from a [RemoteModel] whichever driver built it: the
 * same tree from this app's own link and from the server, the controls a place cannot do absent, someone else's
 * playback view-only, Stop that says Stop and asks first on someone else's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-mdpi")
class OneRemoteTest {
    @get:Rule val rule = createComposeRule()

    private val film = CastRemoteStatus(
        itemId = "f", title = "Sommeren", positionMs = 2_890_000, durationMs = 6_730_000, playing = true, loaded = true,
        audioTracks = listOf(CastTrack(0, "English", "en", trackId = 0), CastTrack(1, "Dansk", "da", trackId = 1)),
        subtitleTracks = listOf(CastTrack(0, "English", "en", trackId = 0)),
    )

    private fun draw(model: RemoteModel, actions: RemoteActions = NoActions) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val remote = OneRemote(model, actions, RemoteExtras())
        rule.setContent {
            OneRemoteScreen(remote = remote, onBack = {}, onPlayAgain = {}, onPlayHere = { _, _, _ -> }, desktop = false)
        }
        rule.waitForIdle()
    }

    private fun modelOf(place: String, status: CastRemoteStatus = film, ops: Set<String>? = null, view: Boolean = false) = RemoteModel(
        sessionId = "s", placeName = place, placeIcon = "tv", status = status, kind = "film", ops = ops,
        viewOnlyOwner = if (view) "Olivar" else null, ownerName = "Olivar",
        line = if (view) "Olivar is watching" else null,
    )

    /** Every tagged node and every text on screen, with the place's name blanked: the screen's shape, not its words. */
    private fun shapeWithout(place: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.TestTag)?.let { out += "tag:$it" }
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text.replace(place, "{place}") }?.let { out += "text:$it" }
            n.children.forEach(::walk)
        }
        walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    @Test fun `the same playback from both drivers is the same screen`() {
        // FR-1, acceptance 1 — only the place's name differs. One model is what this app's link builds (every control,
        // ops null), one what the server's detail does (every op listed); the swap is drawn in the same window.
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val allOps = setOf("play", "pause", "seek", "next", "set_audio", "set_subtitle", "stop")
        val link = OneRemote(modelOf("Stue TV").copy(leavesOnStop = true, retryable = false), NoActions, RemoteExtras())
        val server = OneRemote(modelOf("Soveværelse TV", ops = allOps), NoActions, RemoteExtras())
        val shown = androidx.compose.runtime.mutableStateOf(link)
        rule.setContent { OneRemoteScreen(remote = shown.value, onBack = {}, onPlayAgain = {}, onPlayHere = { _, _, _ -> }, desktop = false) }
        rule.waitForIdle()
        val fromLink = shapeWithout("Stue TV")
        shown.value = server
        rule.waitForIdle()
        val fromServer = shapeWithout("Soveværelse TV")
        assertTrue(fromLink.isNotEmpty() && "tag:remote-stop" in fromLink && "tag:remote-tracks" in fromLink)
        assertEquals(fromLink, fromServer)
    }

    @Test fun `Stop says Stop and never Stop casting`() {
        draw(modelOf("Stue TV"))
        rule.onNodeWithText("Stop").assertExists()
        assertTrue(runCatching { rule.onNodeWithText("Stop casting").assertExists() }.isFailure)
    }

    @Test fun `your own Stop never asks`() {
        val rec = RecordingActions()
        draw(modelOf("Stue TV"), rec)
        rule.onNodeWithTag("remote-stop").performClick()
        assertEquals(listOf("stop"), rec.calls)
        assertTrue(runCatching { rule.onNodeWithTag("remote-stop-confirm").assertExists() }.isFailure)
    }

    @Test fun `someone elses Stop asks first and only the confirm stops`() {
        val rec = RecordingActions()
        draw(modelOf("Soveværelse TV").copy(confirmStop = true), rec)
        rule.onNodeWithTag("remote-stop").performClick()
        assertEquals(emptyList(), rec.calls)
        rule.onNodeWithTag("remote-stop-confirm").assertExists()
        rule.onNodeWithTag("remote-stop-no").performClick()
        assertEquals(emptyList(), rec.calls)
        rule.onNodeWithTag("remote-stop").performClick()
        rule.onNodeWithTag("remote-stop-yes").performClick()
        assertEquals(listOf("stop"), rec.calls)
    }

    @Test fun `someone elses playback with the switch off is view-only`() {
        draw(modelOf("Soveværelse TV", ops = emptySet(), view = true))
        for (tag in listOf("remote-transport", "remote-play-pause", "remote-stop", "remote-tracks", "remote-next")) {
            assertTrue(runCatching { rule.onNodeWithTag(tag).assertExists() }.isFailure, "$tag must be absent")
        }
        rule.onNodeWithText("Olivar is watching").assertExists()
        rule.onAllNodesWithText("Sommeren")[0].assertExists()
    }

    @Test fun `a control the place cannot do is absent`() {
        draw(modelOf("Stue TV", ops = setOf("play", "pause", "stop")))
        rule.onNodeWithTag("remote-play-pause").assertExists()
        rule.onNodeWithTag("remote-stop").assertExists()
        assertTrue(runCatching { rule.onNodeWithTag("remote-skip-back").assertExists() }.isFailure, "no seek op: no skip")
        assertTrue(runCatching { rule.onNodeWithTag("remote-tracks").assertExists() }.isFailure, "no track op: no Audio & Subs")
    }

    @Test fun `the transport reaches the actions`() {
        val rec = RecordingActions()
        draw(modelOf("Stue TV"), rec)
        rule.onNodeWithTag("remote-play-pause").performClick()
        rule.onNodeWithTag("remote-skip-back").performClick()
        rule.onNodeWithTag("remote-skip-fwd").performClick()
        assertEquals(listOf("pause", "seek:2880000", "seek:2920000"), rec.calls)
    }

    private fun absent(tag: String) = runCatching { rule.onNodeWithTag(tag).assertExists() }.isFailure

    private val songServerOps = setOf("play", "pause", "seek", "next", "previous", "set_shuffle", "set_repeat", "set_audio", "set_subtitle", "stop")

    private fun checkSong(ops: Set<String>?) {
        // DEB-2611 (FR-1, FR-10) — the file carries two audio streams and a text stream; a song still has no tracks control.
        val rec = RecordingActions()
        draw(modelOf("Stue", ops = ops).copy(kind = "music"), rec)
        for (tag in listOf("remote-shuffle", "remote-prev", "remote-play-pause", "remote-next-song", "remote-repeat", "remote-stop")) rule.onNodeWithTag(tag).assertExists()
        assertTrue(absent("remote-tracks"), "a song has no Audio & Subs, ops=$ops")
        assertTrue(absent("remote-skip-back"), "a song has no −10 s, ops=$ops")
        rule.onNodeWithTag("remote-shuffle").performClick()
        rule.onNodeWithTag("remote-repeat").performClick()
        assertEquals(listOf("shuffle:true", "repeat:all"), rec.calls.filter { it.startsWith("shuffle") || it.startsWith("repeat") })
    }

    @Test fun `a song over the link draws shuffle previous play next repeat and Stop and no Audio and Subs`() = checkSong(null)

    @Test fun `a song through the server draws the same and no Audio and Subs`() = checkSong(songServerOps)

    @Test fun `a book draws minus 30 s play plus 30 s and Stop, and no Audio and Subs`() {
        val rec = RecordingActions()
        draw(modelOf("Stue").copy(kind = "audiobook"), rec)
        rule.onNodeWithTag("remote-play-pause").assertExists()
        rule.onNodeWithTag("remote-stop").assertExists()
        rule.onNodeWithTag("remote-skip-back").performClick()
        rule.onNodeWithTag("remote-skip-fwd").performClick()
        assertEquals(listOf("seek:2860000", "seek:2920000"), rec.calls.filter { it.startsWith("seek") })
        assertTrue(absent("remote-tracks") && absent("remote-shuffle") && absent("remote-prev"))
    }

    @Test fun `Lost contact says so with Try again and nothing else greyed away`() {
        val rec = RecordingActions()
        draw(modelOf("Stue TV").copy(unreachable = true, retryable = true), rec)
        rule.onNodeWithTag("remote-lost").assertExists()
        rule.onNodeWithTag("remote-retry").performClick()
        assertEquals(listOf("retry"), rec.calls)
    }

    @Test fun `Audio and Subs opens the shared sheet through the one flag the root reads`() {
        draw(modelOf("Stue TV"))
        assertFalse(RemoteOnScreen.trackSheetOpen.value)
        rule.onNodeWithTag("remote-tracks").performClick()
        rule.waitForIdle()
        assertTrue(RemoteOnScreen.trackSheetOpen.value)
        assertEquals("Stue TV", RemoteOnScreen.remote.value?.model?.placeName)
    }
}
