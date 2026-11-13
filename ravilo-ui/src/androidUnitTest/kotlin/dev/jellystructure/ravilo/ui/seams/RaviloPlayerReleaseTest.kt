package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jellystructure.ravilo.ui.RaviloAppContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * DEB-4285 — leaving the player must not hold the main thread for the playback thread's codec and audio-track teardown
 * (150-250 ms on the Stue TV). The engine's release wait is bounded, and a release that outruns it is not a failure of the
 * load: Media3 reports the timeout as a player error, which must not leave [RaviloPlayer.playbackFailed] set on a
 * released player. Robolectric has no decoders, so the real wait cannot be reproduced here; the frame histogram on the TV is
 * the QA ticket's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RaviloPlayerReleaseTest {
    private val servers = ArrayList<StubHttpServer>()
    private var player: RaviloPlayer? = null

    @Before
    fun setUp() { RaviloAppContext.init(ApplicationProvider.getApplicationContext<Context>()) }

    @After
    fun tearDown() { player?.release(); servers.forEach { it.close() } }

    @Test
    fun `a released engine reports no failure and the next load builds a new one`() {
        val stream = StubHttpServer { _, _ -> StubReply.Hang }.also { servers += it }
        val p = RaviloPlayer().also { player = it }
        p.load("${stream.base}/api/tv/stream/x/master.m3u8", 0, emptyList(), emptyList(), "Title", null, null)
        assertNotNull(p.loadedMediaItem)

        p.releaseEngine()

        assertNull(p.loadedMediaItem, "the engine is gone")
        assertFalse(p.playbackFailed, "releasing is not a failed load")
        p.load("${stream.base}/api/tv/stream/y/master.m3u8", 0, emptyList(), emptyList(), "Title", null, null)
        assertEquals("${stream.base}/api/tv/stream/y/master.m3u8", p.loadedMediaItem?.localConfiguration?.uri?.toString())
    }
}
