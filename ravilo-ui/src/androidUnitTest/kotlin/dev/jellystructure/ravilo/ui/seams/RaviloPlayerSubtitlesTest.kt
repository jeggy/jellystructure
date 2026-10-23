package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.shared.tv.SubTrack
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * DEB-2205 (FR-6) — the real [RaviloPlayer] and a real ExoPlayer: a ticket with three text subtitles puts NONE of them on
 * the media item, and no subtitle request is made unless that track is the one shown.
 *
 * The first frame itself cannot be driven here (Robolectric has no decoders), so "a subtitle URL that hangs delays
 * neither the first frame nor the position" rests on two facts this test pins: the media item carries no subtitle
 * configuration, and the only subtitle I/O is [AppFedSubtitles]'s, whose off-thread behaviour against a server that never
 * answers is in [AppFedSubtitlesServerTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RaviloPlayerSubtitlesTest {
    private val servers = ArrayList<StubHttpServer>()
    private var player: RaviloPlayer? = null

    @Before
    fun setUp() { RaviloAppContext.init(ApplicationProvider.getApplicationContext<Context>()) }

    @After
    fun tearDown() { player?.release(); servers.forEach { it.close() } }

    private fun server(reply: StubReply) = StubHttpServer { _, _ -> reply }.also { servers += it }

    private fun waitFor(ms: Long, until: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!until() && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    @Test
    fun `no ticket subtitle is on the media item, and with subtitles off none is requested`() {
        val stream = server(StubReply.Hang)
        val subs = server(StubReply.Hang)   // a subtitle server that never answers
        val tracks = (0..2).map { SubTrack(index = it + 3, language = "da", label = "Track $it", url = "${subs.base}/api/tv/subtitles/item/${it + 3}.vtt") }
        val p = RaviloPlayer().also { player = it }
        p.load("${stream.base}/api/tv/stream/x/master.m3u8", 0, tracks, emptyList(), "Title", null, null)

        val item = assertNotNull(p.loadedMediaItem)
        assertTrue(item.localConfiguration!!.subtitleConfigurations.isEmpty(), "subtitles are not part of the media item's preparation")
        waitFor(3_000) { stream.requests.isNotEmpty() }   // the video's own load is under way
        p.selectSubtitleTrack(-1)                          // the resolver's "off"
        Thread.sleep(500)
        assertEquals(0, subs.requests.size, "subtitles off: no subtitle request at all")
    }

    @Test
    fun `selecting a subtitle requests that track only`() {
        val stream = server(StubReply.Hang)
        val subs = server(StubReply.Hang)
        val tracks = (0..2).map { SubTrack(index = it + 3, language = "da", label = "Track $it", url = "${subs.base}/api/tv/subtitles/item/${it + 3}.vtt") }
        val p = RaviloPlayer().also { player = it }
        p.load("${stream.base}/api/tv/stream/x/master.m3u8", 0, tracks, emptyList(), "Title", null, null)

        p.selectSubtitleTrack(1)   // returns at once though the server never answers
        waitFor(3_000) { subs.requests.isNotEmpty() }
        assertEquals(listOf("/api/tv/subtitles/item/4.vtt"), subs.requests.map { it.path })
        p.selectSubtitleTrack(-1)
        Thread.sleep(300)
        assertEquals(1, subs.requests.size)
    }
}
