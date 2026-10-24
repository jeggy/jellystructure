package dev.jellystructure.ravilo.ui.screens

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DEB-2233 (FR-11) — the desktop's loopback listener on real sockets: it accepts exactly one `/signin` with the right attempt, ignores every other
 * request, answers the *You can close this tab* page and times out with the attempt.
 */
class LoopbackSignInListenerTest {
    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2_000; c.readTimeout = 2_000
        return try { c.responseCode to (if (c.responseCode == 200) c.inputStream.readBytes().decodeToString() else "") } finally { c.disconnect() }
    }

    private class Heard { val returns = CopyOnWriteArrayList<SsoReturn>(); val first = CountDownLatch(1); fun on(r: SsoReturn) { returns += r; first.countDown() } }

    @Test fun `it tells the server a loopback address with its own port`() {
        val l = LoopbackSignInListener("You can close this tab") {}
        try { assertEquals("http://127.0.0.1:${l.port}/signin", l.returnTo); assertTrue(l.port in 1..65535) } finally { l.close() }
    }

    @Test fun `the right attempt is answered with the close page and handed on once`() {
        val heard = Heard()
        val l = LoopbackSignInListener("You can close this tab", onReturn = heard::on)
        l.expect("a1")
        val (status, body) = get("${l.returnTo}?attempt=a1&h=hand-off")
        assertEquals(200, status)
        assertTrue(body.contains("You can close this tab"), body)
        assertTrue(heard.first.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(SsoReturn("a1", "hand-off")), heard.returns.toList())
        // One-shot: it has closed, so a second visit finds nobody there.
        Thread.sleep(100)
        assertFailsWith<java.io.IOException> { get("${l.returnTo}?attempt=a1&h=hand-off") }
        assertEquals(1, heard.returns.size)
    }

    @Test fun `other paths other attempts and half a return are ignored and the listener keeps waiting`() {
        val heard = Heard()
        val l = LoopbackSignInListener("close", onReturn = heard::on)
        try {
            l.expect("a1")
            assertEquals(404, get("http://127.0.0.1:${l.port}/").first)
            assertEquals(404, get("http://127.0.0.1:${l.port}/favicon.ico").first)
            assertEquals(404, get("${l.returnTo}?attempt=a2&h=hand-off").first)
            assertEquals(404, get("${l.returnTo}?attempt=a1").first)
            assertEquals(404, get("${l.returnTo}?h=hand-off").first)
            assertEquals(404, get("http://127.0.0.1:${l.port}/signin/extra?attempt=a1&h=x").first)
            assertTrue(heard.returns.isEmpty())
            assertEquals(200, get("${l.returnTo}?attempt=a1&h=hand-off").first)
            assertTrue(heard.first.await(2, TimeUnit.SECONDS))
        } finally { l.close() }
    }

    @Test fun `until an attempt is named every request is ignored`() {
        val heard = Heard()
        val l = LoopbackSignInListener("close", onReturn = heard::on)
        try {
            assertEquals(404, get("${l.returnTo}?attempt=a1&h=hand-off").first)
            assertTrue(heard.returns.isEmpty())
        } finally { l.close() }
    }

    @Test fun `a request with another Host is ignored`() {
        val heard = Heard()
        val l = LoopbackSignInListener("close", onReturn = heard::on)
        try {
            l.expect("a1")
            Socket("127.0.0.1", l.port).use { s ->
                s.soTimeout = 2_000
                s.getOutputStream().write("GET /signin?attempt=a1&h=x HTTP/1.1\r\nHost: evil.example.net\r\n\r\n".toByteArray())
                assertTrue(s.getInputStream().readBytes().decodeToString().startsWith("HTTP/1.1 404"))
            }
            assertTrue(heard.returns.isEmpty())
        } finally { l.close() }
    }

    @Test fun `a POST is ignored`() {
        val heard = Heard()
        val l = LoopbackSignInListener("close", onReturn = heard::on)
        try {
            l.expect("a1")
            val c = URL("${l.returnTo}?attempt=a1&h=x").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.outputStream.write(ByteArray(0))
            assertEquals(404, c.responseCode)
            assertTrue(heard.returns.isEmpty())
        } finally { l.close() }
    }

    @Test fun `it times out with the attempt and a later visit finds nobody`() {
        val l = LoopbackSignInListener("close", timeoutMs = 300) {}
        l.expect("a1")
        Thread.sleep(800)
        assertFailsWith<java.io.IOException> { get("${l.returnTo}?attempt=a1&h=x") }
    }

    @Test fun `close stops it at once`() {
        val l = LoopbackSignInListener("close") {}
        l.close()
        Thread.sleep(100)
        assertFailsWith<java.io.IOException> { get(l.returnTo) }
    }

    @Test fun `the close text is escaped into the page`() {
        val heard = Heard()
        val l = LoopbackSignInListener("a <b> & c", onReturn = heard::on)
        l.expect("a1")
        val (_, body) = get("${l.returnTo}?attempt=a1&h=x")
        assertTrue(body.contains("a &lt;b&gt; &amp; c"), body)
    }
}
