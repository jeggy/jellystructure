package dev.jellystructure.ravilo.ui.seams

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-2205 (FR-6) — the controller and the OkHttp source against a REAL socket, on real threads. The server that
 * "hangs 5 minutes" is a [ServerSocket] that reads the request and never writes: the claim under test is that this can
 * only ever hold the subtitle worker, never the caller, and that a cancel frees it at once.
 */
class AppFedSubtitlesServerTest {
    private val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
    private val workers = SubtitleWorkers()
    private val toClose = ArrayList<Closeable>()

    @AfterTest
    fun tearDown() { toClose.forEach { runCatching { it.close() } }; workers.shutdown() }

    private fun server(handler: (Int, StubRequest) -> StubReply) = StubHttpServer(handler).also { toClose += it }

    private class Probe : AppFedSubtitles.Listener {
        val shown = AtomicReference<CueTimeline?>()
        val arrived = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<String>()
        override fun onShown(timeline: CueTimeline?) { shown.set(timeline); if (timeline != null) arrived.countDown() }
        override fun onAttemptFailed(url: String) { failures += url }
    }

    /** The real source, plus a latch that opens when a call's [SubtitleCall.execute] has returned. */
    private fun watched(real: SubtitleSource, finished: CountDownLatch): SubtitleSource {
        return SubtitleSource { url ->
            val call = real.newCall(url)
            object : SubtitleCall {
                override fun execute(): SubtitleFetch = try { call.execute() } finally { finished.countDown() }
                override fun cancel() = call.cancel()
            }
        }
    }

    @Test
    fun `a subtitle URL that never answers delays nothing - select returns at once and the caller is never blocked`() {
        val s = server { _, _ -> StubReply.Hang }
        val probe = Probe()
        val finished = CountDownLatch(1)
        val c = AppFedSubtitles(watched(OkHttpSubtitleSource(), finished), workers, probe)
        c.reset(listOf("${s.base}/sub/0.vtt"))

        val t0 = System.nanoTime()
        c.select(0)
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(tookMs < 200, "select() took $tookMs ms: it must not wait on the server")

        // The request does reach the server (it is on a worker), and stays pending without blocking anyone.
        val deadline = System.currentTimeMillis() + 3_000
        while (s.requests.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, s.requests.size)
        assertEquals(1, c.pendingLoads)
        assertEquals(1, finished.count, "still waiting on the hung server")
        c.select(-1)   // leave it tidy
    }

    @Test
    fun `cancelling a hung request frees its worker at once and is not a failure`() {
        val s = server { _, _ -> StubReply.Hang }
        val probe = Probe()
        val finished = CountDownLatch(1)
        val c = AppFedSubtitles(watched(OkHttpSubtitleSource(), finished), workers, probe)
        c.reset(listOf("${s.base}/sub/0.vtt"))
        c.select(0)
        val deadline = System.currentTimeMillis() + 3_000
        while (s.requests.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, s.requests.size)

        c.select(-1)
        assertTrue(finished.await(3, TimeUnit.SECONDS), "the blocked read was interrupted by the cancel")
        assertEquals(0, c.pendingLoads)
        assertTrue(probe.failures.isEmpty(), "a cancel is not a subtitle load error")
        assertNull(probe.shown.get())
    }

    @Test
    fun `with subtitles off - or just listed - no request reaches the server`() {
        val s = server { _, _ -> StubReply.Respond(200, body = vtt) }
        val c = AppFedSubtitles(OkHttpSubtitleSource(), workers, Probe())
        c.reset(listOf("${s.base}/a.vtt", "${s.base}/b.vtt", "${s.base}/c.vtt"))
        c.select(-1)
        Thread.sleep(400)
        assertEquals(0, s.requests.size)
    }

    @Test
    fun `a track that is not shown is never requested`() {
        val s = server { _, _ -> StubReply.Respond(200, body = vtt) }
        val probe = Probe()
        val c = AppFedSubtitles(OkHttpSubtitleSource(), workers, probe)
        c.reset(listOf("${s.base}/a.vtt", "${s.base}/b.vtt", "${s.base}/c.vtt"))
        c.select(1)
        assertTrue(probe.arrived.await(3, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(listOf("/b.vtt"), s.requests.map { it.path })
    }

    @Test
    fun `202 with Retry-After is retried in the background and the cues arrive`() {
        val s = server { n, _ -> if (n < 2) StubReply.Respond(202, mapOf("Retry-After" to "1")) else StubReply.Respond(200, body = vtt) }
        val probe = Probe()
        val c = AppFedSubtitles(OkHttpSubtitleSource(), workers, probe)
        c.reset(listOf("${s.base}/sub/0.vtt"))
        val t0 = System.nanoTime()
        c.select(0)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 200, "select() does not wait for the 202")
        assertTrue(probe.arrived.await(6, TimeUnit.SECONDS), "the subtitle arrived after the retry")
        assertEquals(2, s.requests.size)
        assertEquals("Hello", probe.shown.get()!!.activeAt(1_500).single().text)
        assertTrue(probe.failures.isEmpty())
        assertEquals(0, c.pendingLoads)
    }

    @Test
    fun `our endpoint gets the device token and nobody else does`() {
        val s = server { _, _ -> StubReply.Respond(200, body = vtt) }
        val base = s.base
        val bearer: (String) -> String? = { url -> SubtitleRequest.bearer(url, base, "tok-123") }
        val probe = Probe()
        val finished = CountDownLatch(2)
        val c = AppFedSubtitles(watched(OkHttpSubtitleSource(bearerFor = bearer), finished), workers, probe)
        c.reset(listOf("$base/api/tv/subtitles/abc/3.vtt", "$base/Videos/x/Subtitles/2/0/Stream.vtt?api_key=jf"))
        c.select(0)
        assertTrue(probe.arrived.await(3, TimeUnit.SECONDS))
        c.select(1)
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals("Bearer tok-123", s.requests[0].headers["authorization"])
        assertNull(s.requests[1].headers["authorization"], "Jellyfin's sidecar URL carries its own token and no more")
    }

    @Test
    fun `statuses and Retry-After map to the fetch outcomes`() {
        val s = server { n, _ ->
            when (n) {
                1 -> StubReply.Respond(200, body = vtt)
                2 -> StubReply.Respond(202, mapOf("Retry-After" to "7"))
                3 -> StubReply.Respond(202)
                4 -> StubReply.Respond(404)
                else -> StubReply.Respond(500)
            }
        }
        val src = OkHttpSubtitleSource()
        val outcomes = (1..5).map { src.newCall("${s.base}/x$it").execute() }
        assertEquals(vtt, (outcomes[0] as SubtitleFetch.Body).text)
        assertEquals(7_000L, (outcomes[1] as SubtitleFetch.NotReady).retryAfterMs)
        assertNull((outcomes[2] as SubtitleFetch.NotReady).retryAfterMs)
        assertEquals("HTTP 404", (outcomes[3] as SubtitleFetch.Failed).detail)
        assertEquals("HTTP 500", (outcomes[4] as SubtitleFetch.Failed).detail)
        assertNotNull(OkHttpSubtitleSource.retryAfterMs("3"))
        assertNull(OkHttpSubtitleSource.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `a refused connection is a failure the controller counts`() {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { it.close() }
        val result = OkHttpSubtitleSource().newCall("http://127.0.0.1:${dead.localPort}/x.vtt").execute()
        assertTrue(result is SubtitleFetch.Failed)
    }

    @Test
    fun `the request path and token rules`() {
        assertEquals("http://h:8/api/tv/subtitles/a/1.vtt", SubtitleRequest.absolute("/api/tv/subtitles/a/1.vtt", "http://h:8/"))
        assertEquals("https://jf/Videos/x/Subtitles/2/0/Stream.vtt?k=v", SubtitleRequest.absolute("https://jf/Videos/x/Subtitles/2/0/Stream.vtt?k=v", "http://h:8"))
        assertNull(SubtitleRequest.absolute("/api/tv/subtitles/a/1.vtt", ""))
        assertEquals("t", SubtitleRequest.bearer("http://h:8/api/tv/subtitles/a/1.vtt", "http://h:8", "t"))
        assertNull(SubtitleRequest.bearer("http://other/api/tv/subtitles/a/1.vtt", "http://h:8", "t"))
        assertNull(SubtitleRequest.bearer("http://h:8/Videos/x", "http://h:8", "t"))
        assertNull(SubtitleRequest.bearer("http://h:8/api/tv/subtitles/a/1.vtt", "http://h:8", null))
    }
}
