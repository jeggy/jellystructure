package dev.jellystructure.ravilo.ui.desktop

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * R337 (dev review 12) — one Ravilo per Linux session. Closing the window while music plays keeps the app running
 * without a window (FR-R337-12), so the next launch from the app grid would otherwise start a second app: two engines,
 * two sessions. The first instance listens on a Unix-domain socket in the session's runtime directory (inside the
 * Flatpak, the app's own); a later launch finds it, asks it to show its window, and exits. The JDK's own sockets —
 * nothing to add to the Flatpak. A stale socket (a crashed app) is removed and taken over. Not used on the Mac, where
 * a second `open` reaches the running app through the Dock (R328).
 */
object SingleInstance {
    @Volatile private var onShow: (() -> Unit)? = null
    @Volatile private var showPending = false

    private fun socketFile(): File {
        val runtime = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
        val flatpakDir = runtime?.let { File(it, "app/net.jebster.Ravilo") }?.takeIf { it.isDirectory }
        return when {
            flatpakDir != null -> File(flatpakDir, "ravilo.sock")
            runtime != null -> File(runtime, "ravilo.sock")
            else -> File(System.getProperty("java.io.tmpdir"), "ravilo-${System.getProperty("user.name")}.sock")
        }
    }

    /**
     * Returns false when another Ravilo is running (it has been asked to show itself; the caller exits), true when
     * this one is the instance. Anything unexpected answers true: a lock that fails must never keep Ravilo from starting.
     */
    fun claim(): Boolean {
        if (DesktopPaths.isMac) return true
        val file = socketFile()
        val address = UnixDomainSocketAddress.of(file.toPath())
        if (file.exists()) {
            val reached = runCatching {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                    ch.connect(address)
                    ch.write(ByteBuffer.wrap("show\n".toByteArray()))
                }
            }.isSuccess
            if (reached) return false
            file.delete()
        }
        return runCatching {
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(address) }
            file.deleteOnExit()
            Thread({
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: continue
                    runCatching { client.use { it.read(ByteBuffer.allocate(16)) } }
                    val show = onShow
                    if (show != null) show() else showPending = true
                }
            }, "ravilo-single-instance").apply { isDaemon = true; start() }
            true
        }.getOrDefault(true)
    }

    /** The app's "show the window"; a request that arrived before the window existed runs now. */
    fun onShowRequest(show: () -> Unit) {
        onShow = show
        if (showPending) { showPending = false; show() }
    }
}
