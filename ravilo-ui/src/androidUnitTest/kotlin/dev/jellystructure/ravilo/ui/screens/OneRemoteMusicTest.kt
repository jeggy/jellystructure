package dev.jellystructure.ravilo.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.jellystructure.ravilo.ui.remote.MusicExtras
import dev.jellystructure.ravilo.ui.remote.MusicFace
import dev.jellystructure.ravilo.ui.remote.OneRemote
import dev.jellystructure.ravilo.ui.remote.RemoteActions
import dev.jellystructure.ravilo.ui.remote.RemoteExtras
import dev.jellystructure.ravilo.ui.remote.RemoteModel
import dev.jellystructure.ravilo.ui.remote.UpNextRow
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.shared.tv.MusicArtistRef
import dev.jellystructure.shared.tv.MusicTrackItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-2225 (one-remote FR-3, FR-4; acceptance 8, 11) — the music remote, drawn from a [RemoteModel] whichever driver built it:
 * the Playing page's functions are in it (the place pill, *Playing from*, the title with ♥, the artist, ⋯, the transport, *Up
 * next* with a tap that jumps, *Stop*), a place reached through the server has the queue and no more, nothing carries a
 * subtitle control, and the Playing tab draws no back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-mdpi")
class OneRemoteMusicTest {
    @get:Rule val rule = createComposeRule()

    private val song = MusicTrackItem(
        id = "t1", title = "Cannery Lights", albumId = "al1", album = "Harbour Lights", artists = listOf(MusicArtistRef("ar1", "Marra Quay")),
        durationMs = 221_000, versions = listOf("remastered", "live"),
    )
    private val status = CastRemoteStatus(itemId = "t1", title = "Cannery Lights", kicker = "Marra Quay", positionMs = 72_000, durationMs = 221_000, playing = true, loaded = true, hasNext = true, music = true)
    private val queue = listOf(UpNextRow(3, "t4", "Net Mender", "Marra Quay", 242_000), UpNextRow(4, "t5", "Rope and Salt", "Marra Quay", 198_000))
    private val localFace = MusicFace(track = song, now = UpNextRow(2, "t1", "Cannery Lights", "Marra Quay", 221_000), upNext = queue, local = true)

    private class Doors {
        val calls = mutableListOf<String>()
        fun extras() = MusicExtras(
            api = fakeTvApiClient { null }, onTrackMore = { calls += "more:${it.id}" }, onFavorite = { t, f -> calls += "fav:${t.id}:$f" },
            onOpenAlbum = { calls += "album:$it" }, onOpenArtist = { calls += "artist:$it" },
        )
    }

    private fun draw(model: RemoteModel, actions: RemoteActions = NoActions, doors: Doors = Doors(), embedded: Boolean = false, withExtras: Boolean = true) {
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        val remote = OneRemote(model, actions, RemoteExtras(music = if (withExtras) doors.extras() else null))
        rule.setContent { OneRemoteScreen(remote = remote, onBack = {}, onPlayAgain = {}, onPlayHere = { _, _, _ -> }, desktop = false, embedded = embedded) }
        rule.waitForIdle()
    }

    private fun model(face: MusicFace?, here: Boolean = false, ops: Set<String>? = null, place: String = "Stue") = RemoteModel(
        sessionId = "s", placeName = place, placeIcon = "speaker", status = status, kind = "music", here = here, ops = ops, music = face,
    )

    private fun absent(vararg tags: String) = tags.forEach { rule.onAllNodesWithTag(it).assertCountEquals(0) }

    @Test fun `a song this app holds draws the Playing pages functions in the one remote`() {
        draw(model(localFace.copy(context = dev.jellystructure.ravilo.ui.music.MusicContext("album", "Harbour Lights"))))
        for (tag in listOf("remote-place-pill", "remote-playing-from", "remote-cover", "remote-title", "remote-heart", "remote-artist", "remote-transport", "remote-up-next", "remote-stop",
            "remote-shuffle", "remote-prev", "remote-play-pause", "remote-next-song", "remote-repeat")) rule.onNodeWithTag(tag).assertExists()
        rule.onAllNodesWithText("Playing from Harbour Lights").assertCountEquals(1)
        absent("remote-tracks", "remote-lyrics")   // this song has no lyrics, and no song ever has Audio & Subs
    }

    @Test fun `the heart and the artist and the album open what the Playing page opened`() {
        val doors = Doors()
        draw(model(localFace), doors = doors)
        rule.onNodeWithTag("remote-heart").performClick()
        rule.onNodeWithTag("remote-artist").performClick()
        rule.onNodeWithTag("remote-album").performClick()
        assertEquals(listOf("fav:t1:true", "artist:ar1", "album:al1"), doors.calls)
    }

    @Test fun `Up next lists the queue and a tap on a row jumps to its place`() {
        val rec = RecordingActions()
        draw(model(localFace), rec)
        assertEquals(0, rule.onAllNodesWithTag("remote-up-next-sheet", useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.onNodeWithTag("remote-up-next").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("remote-up-next-sheet", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("Net Mender").assertCountEquals(1)
        rule.onAllNodesWithText("Rope and Salt").assertCountEquals(1)
        rule.onNodeWithTag("remote-up-next-4").performClick()
        assertEquals(listOf("jump:4"), rec.calls)
    }

    @Test fun `a song on this device says Playing here and the Playing tab draws no back`() {
        draw(model(localFace, here = true, place = "This phone"), embedded = true)
        rule.onAllNodesWithText("Playing here").assertCountEquals(1)
        rule.onAllNodesWithText("Playing on This phone").assertCountEquals(0)
        rule.onNodeWithTag("remote-place-pill").assertExists()
    }

    @Test fun `a song on a speaker says Playing on the speaker`() {
        draw(model(localFace))
        rule.onAllNodesWithText("Playing on Stue").assertCountEquals(1)
    }

    @Test fun `a place reached through the server has the queue and no heart or menu`() {
        val ops = setOf("play", "pause", "seek", "stop", "next", "previous", "jump", "set_shuffle", "set_repeat")
        val rec = RecordingActions()
        draw(model(MusicFace(now = UpNextRow(2, "t1", "Cannery Lights"), upNext = queue), ops = ops), rec)
        rule.onNodeWithTag("remote-title").assertExists()
        rule.onNodeWithTag("remote-artist").assertExists()
        rule.onNodeWithTag("remote-up-next").assertExists()
        absent("remote-heart", "remote-lyrics", "remote-tracks")
        rule.onNodeWithTag("remote-up-next").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("remote-up-next-3").performClick()
        assertEquals(listOf("jump:3"), rec.calls)
    }

    @Test fun `a place that cannot jump still lists the queue`() {
        draw(model(MusicFace(now = UpNextRow(2, "t1", "x"), upNext = queue), ops = setOf("play", "pause", "stop")))
        rule.onNodeWithTag("remote-up-next").assertExists()
    }

    @Test fun `without a queue Up next is absent`() {
        draw(model(MusicFace()))
        absent("remote-up-next")
    }

    @Test fun `the last song of a queue this app holds has no next`() {
        draw(RemoteModelFor(status.copy(hasNext = false), localFace))
        absent("remote-next-song")
        rule.onNodeWithTag("remote-prev").assertExists()
    }

    private fun RemoteModelFor(s: CastRemoteStatus, face: MusicFace) =
        RemoteModel(sessionId = "s", placeName = "Stue", placeIcon = "speaker", status = s, kind = "music", music = face)

    @Test fun `someone elses song with the switch off is view-only`() {
        draw(model(localFace).copy(viewOnlyOwner = "Olivar", ownerName = "Olivar"))
        absent("remote-transport", "remote-stop", "remote-up-next", "remote-heart")
        rule.onNodeWithTag("remote-title").assertExists()
    }

    @Test fun `Stop says Stop and stops`() {
        val rec = RecordingActions()
        draw(model(localFace), rec)
        rule.onAllNodesWithText("Stop").assertCountEquals(1)
        rule.onNodeWithTag("remote-stop").performClick()
        assertEquals(listOf("stop"), rec.calls)
    }

    @Test fun `a book reached through the server draws the chapter and minus 30 plus 30 and nothing a book does not have`() {
        draw(RemoteModelFor(status.copy(music = false, title = "Chapter 7", kicker = "The Long Ferry"), MusicFace()).copy(kind = "audiobook"))
        for (tag in listOf("remote-skip-back", "remote-skip-fwd", "remote-play-pause", "remote-stop")) rule.onNodeWithTag(tag).assertExists()
        absent("remote-shuffle", "remote-repeat", "remote-tracks", "remote-lyrics", "remote-up-next")
        assertTrue(rule.onAllNodesWithText("Chapter 7").fetchSemanticsNodes().isNotEmpty())
    }
}
