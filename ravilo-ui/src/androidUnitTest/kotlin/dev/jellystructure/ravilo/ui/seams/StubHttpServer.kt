package dev.jellystructure.ravilo.ui.seams

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


internal class StubRequest(val path: String, val headers: Map<String, String>)
internal sealed interface StubReply {
    data object Hang : StubReply
    class Respond(val status: Int, val headers: Map<String, String> = emptyMap(), val body: String = "") : StubReply
}

/** A bare HTTP/1.1 server: one thread per connection, `Connection: close`, [handler] decides each request's fate. */
internal class StubHttpServer(private val handler: (n: Int, StubRequest) -> StubReply) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val requests = CopyOnWriteArrayList<StubRequest>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val release = CountDownLatch(1)
    val base: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        Thread({
            try {
                while (true) {
                    val socket = server.accept()
                    sockets += socket
                    Thread({ serve(socket) }, "stub-conn").apply { isDaemon = true }.start()
                }
            } catch (_: Exception) { /* closed */ }
        }, "stub-accept").apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        try {
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = input.readLine() ?: return
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val h = input.readLine() ?: return
                if (h.isEmpty()) break
                h.split(":", limit = 2).takeIf { it.size == 2 }?.let { headers[it[0].trim().lowercase()] = it[1].trim() }
            }
            val req = StubRequest(line.split(" ")[1], headers)
            requests += req
            when (val r = handler(requests.size, req)) {
                is StubReply.Hang -> release.await(5, TimeUnit.MINUTES)   // "answers after 5 minutes"
                is StubReply.Respond -> {
                    val body = r.body.toByteArray()
                    val head = StringBuilder("HTTP/1.1 ${r.status} X\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
                    r.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
                    head.append("\r\n")
                    socket.getOutputStream().apply { write(head.toString().toByteArray()); write(body); flush() }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    override fun close() {
        release.countDown()
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}

