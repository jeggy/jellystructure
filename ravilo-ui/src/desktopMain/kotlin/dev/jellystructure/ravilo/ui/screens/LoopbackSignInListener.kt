package dev.jellystructure.ravilo.ui.screens

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * DEB-2233 (FR-11) — the desktop's one-shot listener for the identity provider's return (RFC 8252 §7.3). It binds `127.0.0.1` on a port the
 * system picks, so nothing off this machine can reach it, and tells the server `http://127.0.0.1:<port>/signin` as `returnTo`.
 *
 *  - It accepts exactly one `GET /signin?attempt=<the attempt>&h=<hand-off>`: that answers the *You can close this tab* page, hands the return
 *    to [onReturn] and closes.
 *  - Anything else — another path, another method, another attempt, a missing hand-off, a `Host` that is not this listener's own address (a
 *    page in the viewer's browser aimed at the port) — gets a bare 404 and changes nothing. Until [expect] names an attempt, everything is that.
 *  - It stops at [timeoutMs] (the attempt's own ten minutes) and on [close], whichever is first.
 */
class LoopbackSignInListener(
    closeTabText: String,
    private val timeoutMs: Long = SSO_ATTEMPT_MS,
    private val onReturn: (SsoReturn) -> Unit,
) : SsoListener {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort
    override val returnTo: String = "http://127.0.0.1:$port/signin"

    @Volatile private var expected: String? = null
    @Volatile private var done = false
    private val page = closeTabPage(closeTabText)

    init {
        thread(isDaemon = true, name = "sso-loopback-$port") { serve() }
    }

    override fun expect(attemptId: String) { expected = attemptId }

    override fun close() {
        done = true
        runCatching { server.close() }
    }

    private fun serve() {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        try {
            while (!done) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) break
                server.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { break }
                if (handle(socket)) break
            }
        } catch (_: Exception) {
            // Closed from outside (cancel, or the attempt claimed): nothing to report.
        } finally {
            close()
        }
    }

    /** True when this connection was the return. */
    private fun handle(socket: Socket): Boolean = socket.use { s ->
        s.soTimeout = 5_000
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = runCatching { reader.readLine() }.getOrNull().orEmpty()
        var host: String? = null
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Host:", ignoreCase = true)) host = line.substringAfter(':').trim()
        }
        val parts = requestLine.split(' ')
        val target = parts.getOrNull(1).orEmpty()
        val ret = if (parts.getOrNull(0) == "GET" && target.substringBefore('?') == "/signin" && host == "127.0.0.1:$port") {
            ssoReturnFromQuery(target.substringAfter('?', ""), "attempt")?.takeIf { it.attemptId == expected }
        } else null
        val out = s.getOutputStream()
        if (ret == null) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return@use false
        }
        val body = page.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        out.write(body)
        out.flush()
        onReturn(ret)
        true
    }

    private fun closeTabPage(text: String): String {
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>$safe</title><style>html{color-scheme:dark}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;" +
            "background:#080b12;color:#eef0f6;font:600 22px/1.3 system-ui,sans-serif;text-align:center}</style></head><body><p>$safe</p></body></html>"
    }
}
