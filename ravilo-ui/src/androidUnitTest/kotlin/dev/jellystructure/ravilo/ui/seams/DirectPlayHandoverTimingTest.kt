package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.screens.DirectPlayHandover
import dev.jellystructure.ravilo.ui.screens.HANDOVER_MARGIN_MS
import dev.jellystructure.ravilo.ui.screens.HandoverDecision
import dev.jellystructure.ravilo.ui.screens.handoverStartMs
import dev.jellystructure.ravilo.ui.screens.handoverSwitchDue
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * DEB-2205 (FR-4, Acceptance 4) — hand-over timing against a throttled local server. A direct play is read over real HTTP from a server
 * that sends a burst and then a trickle (the spec's "TV on a shaped link"); a model of the player drains what was read at the file's
 * bitrate, and [DirectPlayHandover] looks at it every 500 ms as the store does. What is shown: the rule fires ahead of time with picture
 * to spare, H sits 2 s inside what is buffered, the buffer never runs dry up to the switch, and the switch is due within 1.5 s of H.
 */
class DirectPlayHandoverTimingTest {
    /** One request, one stream: [burstBytes] at once, then [bytesPerSecond] until the client goes away. */
    private class ThrottledServer(private val burstBytes: Int, private val bytesPerSecond: Int) : Closeable {
        private val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val url: String get() = "http://127.0.0.1:${server.localPort}/film.mkv"
        init {
            Thread({
                try {
                    while (true) {
                        val socket = server.accept()
                        Thread({
                            try {
                                socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).let { r -> while (r.readLine().let { it != null && it.isNotEmpty() }) { /* the request head */ } }
                                val out = socket.getOutputStream()
                                out.write("HTTP/1.1 200 OK\r\nContent-Type: video/x-matroska\r\nConnection: close\r\n\r\n".toByteArray())
                                val chunk = ByteArray(8 * 1024)
                                var sent = 0
                                while (sent < burstBytes) { val n = minOf(chunk.size, burstBytes - sent); out.write(chunk, 0, n); sent += n }
                                out.flush()
                                val started = System.nanoTime()
                                var trickled = 0L
                                while (true) {
                                    out.write(chunk); out.flush(); trickled += chunk.size
                                    val dueNs = trickled * 1_000_000_000L / bytesPerSecond
                                    val aheadNs = dueNs - (System.nanoTime() - started)
                                    if (aheadNs > 0) Thread.sleep(aheadNs / 1_000_000, (aheadNs % 1_000_000).toInt())
                                }
                            } catch (_: Exception) {
                            } finally { runCatching { socket.close() } }
                        }, "throttle-conn").apply { isDaemon = true }.start()
                    }
                } catch (_: Exception) { /* closed */ }
            }, "throttle-accept").apply { isDaemon = true }.start()
        }
        override fun close() { runCatching { server.close() } }
    }

    /** Reads [url] in the background, counting bytes; [Reader.bytes] is what the "player" has buffered so far. */
    private class Reader(url: String) : Closeable {
        val bytes = AtomicLong()
        @Volatile private var open = true
        private val conn = URL(url).openConnection() as HttpURLConnection
        init {
            Thread({
                try {
                    val buf = ByteArray(16 * 1024)
                    val input = conn.inputStream
                    while (open) { val n = input.read(buf); if (n < 0) break; bytes.addAndGet(n.toLong()) }
                } catch (_: Exception) { /* closed */ }
            }, "reader").apply { isDaemon = true }.start()
        }
        override fun close() { open = false; runCatching { conn.disconnect() } }
    }

    private val streamBps = 8_000_000L                 // the model file: 1 MB of picture per second
    private val bytesPerMediaSecond = (streamBps / 8).toInt()

    private class Run(val firedAtMs: Long, val decision: HandoverDecision, val positionAtFire: Long, val aheadAtFire: Long, val minAheadBefore: Long)

    /** Plays the model against [url] until the rule fires, looking every 500 ms; returns when and what it decided. */
    private fun playUntilDecision(url: String, estimateOf: (Long) -> Long?, maxSeconds: Int = 40): Run {
        val rule = DirectPlayHandover(streamBps)
        val reader = Reader(url)
        try {
            val started = System.nanoTime()
            var minAhead = Long.MAX_VALUE
            while (true) {
                Thread.sleep(500)
                val now = (System.nanoTime() - started) / 1_000_000
                val buffered = reader.bytes.get() * 1000 / bytesPerMediaSecond          // ms of picture read so far
                val position = now                                                          // played at 1x from 0
                val ahead = maxOf(0L, buffered - position)                                 // a player cannot have less than none ahead
                minAhead = minOf(minAhead, ahead)
                rule.observe(now, position, ahead, estimateOf(now), playing = true)?.let { return Run(now, it, position, ahead, minAhead) }
                check(now < maxSeconds * 1_000L) { "the rule never fired in $maxSeconds s (ahead $ahead ms)" }
            }
        } finally {
            reader.close()
        }
    }

    @Test fun `a buffer drained by a throttled link hands over ahead of time and never runs dry`() {
        // 17 s of picture at once, then 1 Mbps: the buffer loses 0.875 s every second, so H sits about 12 s ahead of where it fired.
        ThrottledServer(burstBytes = 17 * bytesPerMediaSecond, bytesPerSecond = 125_000).use { server ->
            val run = playUntilDecision(server.url, estimateOf = { null })
            val d = assertIs<HandoverDecision.Ahead>(run.decision)
            assertEquals("falling", d.reason)
            // Under 15 s from about 2.3 s on; three seconds of history by 3.5 s.
            assertTrue(run.firedAtMs in 3_000..5_000, "fired at ${run.firedAtMs} ms")
            assertTrue(run.aheadAtFire in 8_000..15_000, "ahead of time with ${run.aheadAtFire} ms still buffered")
            // H = position + buffered − 2 s.
            assertEquals(run.positionAtFire + run.aheadAtFire - HANDOVER_MARGIN_MS, d.atMs)
            assertTrue(run.minAheadBefore > 0, "the buffer was never empty before the hand-over (min ${run.minAheadBefore} ms)")

            // What the player does from here: it keeps the direct stream, the buffer keeps draining, and at H it cuts over.
            val bufferEndAtFire = run.positionAtFire + run.aheadAtFire
            val trickleMsPerSecond = 125_000L * 1000 / bytesPerMediaSecond                  // 125 ms of picture arrives per second
            var t = run.firedAtMs; var switchedAt: Long? = null; var reachedHAt: Long? = null
            while (t < run.firedAtMs + 60_000 && switchedAt == null) {
                t += 100
                val position = t
                val bufferEnd = bufferEndAtFire + (t - run.firedAtMs) * trickleMsPerSecond / 1000
                assertTrue(bufferEnd - position > 0, "the direct stream ran dry at $t ms before the switch at H = ${d.atMs}")
                if (reachedHAt == null && position >= d.atMs) reachedHAt = t
                if (handoverSwitchDue(position, d.atMs)) switchedAt = t
            }
            val due = switchedAt ?: error("the switch never came due")
            assertTrue(due - (reachedHAt ?: due) <= 1_500, "the cut is within 1.5 s of H: switched at $due, H = ${d.atMs}")
            assertEquals(d.atMs, handoverStartMs(d.atMs - 100, d.atMs), "the ladder stream starts at H")
            assertTrue(due >= d.atMs - 400 && due <= d.atMs + 100, "the cut comes at H (due $due, H ${d.atMs})")
        }
    }

    @Test fun `a buffer that is nearly dry restreams at once`() {
        // 3.5 s at once, then 1 Mbps: by the time it has fallen for 3 s there is under 1.5 s left.
        ThrottledServer(burstBytes = bytesPerMediaSecond / 1000 * 3_500, bytesPerSecond = 125_000).use { server ->
            val run = playUntilDecision(server.url, estimateOf = { null })
            val d = assertIs<HandoverDecision.Immediate>(run.decision)
            assertEquals("falling", d.reason)
            assertTrue(run.aheadAtFire in 0..3_999, "with ${run.aheadAtFire} ms left")
        }
    }

    @Test fun `a path that measures under the files bitrate hands over with most of the buffer still in hand`() {
        // 25 s at once, then 4 Mbps: the meter reads 4 Mbps (× 0.8 = 3.2) against an 8 Mbps file as soon as it rests on enough transfers.
        ThrottledServer(burstBytes = 25 * bytesPerMediaSecond, bytesPerSecond = 500_000).use { server ->
            val run = playUntilDecision(server.url, estimateOf = { now -> if (now >= 2_000) 4_000_000L else null })
            val d = assertIs<HandoverDecision.Ahead>(run.decision)
            assertEquals("estimate", d.reason)
            // The buffer loses 0.5 s a second, so it has made no gain: six seconds of history, and not before (DEB-2205 FR-4, 2026-10-31).
            assertTrue(run.firedAtMs in 5_750..7_000, "fired at ${run.firedAtMs} ms, once the buffer had gained nothing for 6 s")
            assertTrue(run.aheadAtFire > 20_000, "${run.aheadAtFire} ms still buffered: a quality change, not a stop")
            assertEquals(run.positionAtFire + run.aheadAtFire - HANDOVER_MARGIN_MS, d.atMs)
        }
    }
}
