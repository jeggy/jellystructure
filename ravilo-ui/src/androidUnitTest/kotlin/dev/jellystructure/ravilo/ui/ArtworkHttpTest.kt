package dev.jellystructure.ravilo.ui

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * DEB-4396 (DEB-4390, FR-2, FR-4.2) — artwork's own OkHttp lane, and a disk hit that never revalidates.
 * The "server" is a socket that answers `/img` at once and never answers `/slow`.
 */
class ArtworkHttpTest {
    private val rest = OkHttpClient()

    private fun server(): Pair<ServerSocket, Thread> {
        val ss = ServerSocket(0)
        val t = Thread {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                Thread {
                    runCatching {
                        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                        if ("/img" in line) {
                            s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                            s.getOutputStream().flush(); s.close()
                        } // /slow: hold the socket open and say nothing
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true; start() }
        return ss to t
    }

    private fun park(client: OkHttpClient, url: String, n: Int) = repeat(n) {
        client.newCall(Request.Builder().url("$url/slow").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    private fun imageArrives(client: OkHttpClient, url: String, withinMs: Long): Boolean {
        val got = CountDownLatch(1)
        client.newCall(Request.Builder().url("$url/img").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close(); got.countDown() }
        })
        return got.await(withinMs, TimeUnit.MILLISECONDS)
    }

    @Test fun `REST calls parked on a slow server do not delay an image request`() {
        val (ss, _) = server()
        try {
            val url = "http://127.0.0.1:${ss.localPort}"
            val images = artworkClient(rest)
            park(rest, url, 5)   // every per-host slot the REST client has
            Thread.sleep(300)
            // The control: on the REST client's own dispatcher the image would queue behind them.
            assertFalse(imageArrives(rest, url, 1_000), "the REST client's slots are full — this is what the lane avoids")
            assertTrue(imageArrives(images, url, 3_000), "the artwork lane answers while REST is parked")
        } finally { ss.close(); rest.dispatcher.cancelAll() }
    }

    @Test fun `the other way round — parked images do not hold a REST call`() {
        val (ss, _) = server()
        try {
            val url = "http://127.0.0.1:${ss.localPort}"
            val images = artworkClient(rest)
            park(images, url, 8)
            Thread.sleep(300)
            assertTrue(imageArrives(rest, url, 3_000))
        } finally { ss.close(); images().dispatcher.cancelAll() }
    }

    private fun images() = artworkClient(rest)

    @Test fun `the lane shares REST's pool and has eight slots and a 15 second call timeout`() {
        val lane = artworkClient(rest)
        assertSame(rest.connectionPool, lane.connectionPool)
        assertEquals(8, lane.dispatcher.maxRequestsPerHost)
        assertEquals(15_000, lane.callTimeoutMillis)
        assertEquals(5, rest.dispatcher.maxRequestsPerHost)
        assertEquals(0, rest.callTimeoutMillis)
    }
}
