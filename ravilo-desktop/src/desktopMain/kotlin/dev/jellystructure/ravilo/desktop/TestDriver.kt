package dev.jellystructure.ravilo.desktop

import androidx.compose.ui.awt.ComposeWindow
import dev.jellystructure.ravilo.ui.components.AppCommand
import dev.jellystructure.ravilo.ui.components.AppCommands
import dev.jellystructure.ravilo.ui.desktop.DesktopLog
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import javax.swing.SwingUtilities

/**
 * A development aid: the app, seen and driven from a terminal. **Off** unless Ravilo is started with
 * `RAVILO_TESTDRIVER=<dir>` (or `-Dravilo.testdriver=<dir>`); then it listens on `<dir>/ctl.sock` and answers one
 * line per command, so a build can be checked on a Mac over SSH — where macOS gives a remote shell neither the
 * screen nor the keyboard — and in the Fedora container, without a screen grabber:
 *
 * ```
 * open -n --env RAVILO_TESTDRIVER=/tmp/ravilo-td Ravilo.app
 * echo "shot home" | nc -U /tmp/ravilo-td/ctl.sock      # → /tmp/ravilo-td/home.png
 * ```
 *
 * - `shot <name>` / `shot2 <name>` — what the window draws, as `<name>.png`: at one pixel per point (so a picture's
 *   coordinates are the ones `click` takes), or at the display's own scale. The last drawn frame, replayed by Skia —
 *   nothing is read from the screen. macOS's own title bar and menu bar are not in it.
 * - `click <x> <y>` · `dclick <x> <y>` · `move <x> <y>` · `scroll <x> <y> <notches>` — the mouse, in points.
 * - `cursor <x> <y>` — moves there and answers with the pointer the app asks for (`Hand Cursor`, `Default Cursor`, …).
 * - `key <name> [meta+shift+…]` — a key by its `KeyEvent.VK_` name (`key ESCAPE`, `key COMMA meta`); `type <text>`.
 * - `cmd <AppCommand>` — what a menu item sends (`cmd MODE_MUSIC`): macOS's menu bar cannot be reached from here.
 * - `quiet` (`quiet off`) — the app plays at nothing; the volume the person set stays shown and remembered.
 * - `cast` — what speaker discovery sees (3 s).
 * - `size <w> <h>` · `front` · `info` · `chrome` (the Mac's traffic lights and title-area hit test, from AppKit).
 *
 * `w2` before a command points it at the Settings window while that is open (`w2 shot settings`, `w2 click 300 40`).
 *
 * Input commands refuse while the person at the computer used the app in the last minute (`info` says `idle=`);
 * `force click …` overrides.
 *
 * Events go to the window's own canvas, so they arrive whether or not the app is in front; keys reach the focused
 * element only while the window has the focus (`front`).
 */
internal object TestDriver {
    private val dir: File? = (System.getProperty("ravilo.testdriver") ?: System.getenv("RAVILO_TESTDRIVER"))
        ?.trim()?.ifBlank { null }?.let(::File)
    @Volatile private var started = false
    private val INPUT = setOf("click", "dclick", "move", "cursor", "scroll", "key", "type", "size", "cmd")

    fun start(window: ComposeWindow) {
        val d = dir ?: return
        if (started) return
        started = true
        runCatching {
            d.mkdirs()
            watchRealInput()
            val sock = File(d, "ctl.sock").also { it.delete() }
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(sock.toPath())) }
            sock.deleteOnExit()
            Thread({
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: continue
                    runCatching {
                        client.use { ch ->
                            val line = Channels.newReader(ch, Charsets.UTF_8).buffered().readLine().orEmpty().trim()
                            val reply = runCatching { handle(window, d, line) }.getOrElse { "err ${it::class.simpleName}: ${it.message}" }
                            Channels.newWriter(ch, Charsets.UTF_8).run { write(reply + "\n"); flush() }
                        }
                    }
                }
            }, "ravilo-test-driver").apply { isDaemon = true; start() }
            println("${DesktopLog.stamp()} test driver: listening on $sock")
        }.onFailure { println("${DesktopLog.stamp()} test driver: not started (${it.message})") }
    }

    // Someone may be using the app the driver is pointed at. Real input (not the driver's own events) is timed, and
    // `click`/`key`/`type`/`scroll`/`size` refuse while the person at the computer touched the app in the last minute —
    // `force` before the command overrides, for a build nobody else is looking at.
    @Volatile private var lastRealInput = 0L
    @Volatile private var synthetic = false
    private fun idleSeconds(): Long = if (lastRealInput == 0L) 9_999 else (System.currentTimeMillis() - lastRealInput) / 1000
    private fun watchRealInput() {
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            if (!synthetic && (e.id == MouseEvent.MOUSE_PRESSED || e.id == KeyEvent.KEY_PRESSED || e.id == MouseEvent.MOUSE_WHEEL)) lastRealInput = System.currentTimeMillis()
        }, java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.KEY_EVENT_MASK or java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK)
    }

    private fun layerOf(c: Component): SkiaLayer? = when (c) {
        is SkiaLayer -> c
        is Container -> c.components.firstNotNullOfOrNull(::layerOf)
        else -> null
    }

    private fun <T> onEdt(block: () -> T): T {
        var out: Result<T>? = null
        SwingUtilities.invokeAndWait { synthetic = true; try { out = runCatching(block) } finally { synthetic = false } }
        return out!!.getOrThrow()
    }

    @Suppress("NAME_SHADOWING")
    private fun handle(window: ComposeWindow, dir: File, line: String): String {
        val words = line.split(' ').filter { it.isNotEmpty() }
        val prefixes = words.takeWhile { it == "force" || it == "w2" }
        val forced = "force" in prefixes
        val parts = words.drop(prefixes.size)
        val window = if ("w2" !in prefixes) window
            else dev.jellystructure.ravilo.ui.desktop.DesktopWindow.secondWindow as? ComposeWindow ?: return "err no second window"
        val verb = parts.firstOrNull() ?: return "err empty"
        if (!forced && verb in INPUT && idleSeconds() < 60) return "err in use: real input ${idleSeconds()} s ago (wait, or prefix with force)"
        val layer = onEdt { layerOf(window) } ?: return "err no canvas yet"
        val target = layer.canvas
        fun int(i: Int) = parts[i].toInt()
        fun mouse(id: Int, x: Int, y: Int, button: Int, clicks: Int, mods: Int) = onEdt {
            target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), mods, x, y, clicks, false, button))
        }
        fun press(x: Int, y: Int, clicks: Int) {
            mouse(MouseEvent.MOUSE_MOVED, x, y, MouseEvent.NOBUTTON, 0, 0)
            Thread.sleep(40)
            mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1, clicks, InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(40)
            mouse(MouseEvent.MOUSE_RELEASED, x, y, MouseEvent.BUTTON1, clicks, 0)
            mouse(MouseEvent.MOUSE_CLICKED, x, y, MouseEvent.BUTTON1, clicks, 0)
        }
        return when (verb) {
            "shot", "shot2" -> {
                val name = parts.getOrNull(1)?.replace(Regex("[^A-Za-z0-9._-]"), "_") ?: return "err name"
                val (w, h) = onEdt { layer.width to layer.height }
                val bitmap = onEdt { layer.screenshot() } ?: return "err nothing drawn yet"
                val full = Image.makeFromBitmap(bitmap)
                val image = if (verb == "shot2" || full.width == w) full else Surface.makeRasterN32Premul(w, h).run {
                    canvas.drawImageRect(full, Rect.makeWH(full.width.toFloat(), full.height.toFloat()),
                        Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true)
                    makeImageSnapshot()
                }
                val file = File(dir, "$name.png")
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                "ok ${file.path} ${image.width}x${image.height}"
            }
            "click" -> { press(int(1), int(2), 1); "ok" }
            "dclick" -> { press(int(1), int(2), 1); Thread.sleep(60); press(int(1), int(2), 2); "ok" }
            "move" -> { mouse(MouseEvent.MOUSE_MOVED, int(1), int(2), MouseEvent.NOBUTTON, 0, 0); "ok" }
            // Which pointer the app asks for at a point — no picture of the canvas shows the cursor.
            "cursor" -> {
                mouse(MouseEvent.MOUSE_MOVED, int(1), int(2), MouseEvent.NOBUTTON, 0, 0)
                Thread.sleep(150)
                onEdt { "ok ${target.cursor.name}" }
            }
            "scroll" -> onEdt {
                target.dispatchEvent(MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, int(1), int(2),
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, int(3)))
                "ok"
            }
            "key" -> {
                val code = KeyEvent::class.java.getField("VK_" + parts[1].uppercase()).getInt(null)
                val mods = parts.getOrNull(2)?.split('+')?.fold(0) { acc, m ->
                    acc or when (m.lowercase()) {
                        "meta", "cmd" -> InputEvent.META_DOWN_MASK
                        "ctrl" -> InputEvent.CTRL_DOWN_MASK
                        "shift" -> InputEvent.SHIFT_DOWN_MASK
                        "alt" -> InputEvent.ALT_DOWN_MASK
                        else -> 0
                    }
                } ?: 0
                onEdt { target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), mods, code, KeyEvent.CHAR_UNDEFINED)) }
                Thread.sleep(30)
                onEdt { target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), mods, code, KeyEvent.CHAR_UNDEFINED)) }
                "ok"
            }
            "type" -> {
                line.substringAfter("type ", "").forEach { ch ->
                    onEdt { target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ch)) }
                    Thread.sleep(15)
                }
                "ok"
            }
            "cmd" -> { AppCommands.send(AppCommand.valueOf(parts[1])); "ok" }
            // Nothing is heard from here on, and the volume the person set is left as it is (shown and remembered).
            "quiet" -> { dev.jellystructure.ravilo.ui.music.MusicVolume.silenced = parts.getOrNull(1) != "off"; "ok" }
            "size" -> onEdt { window.setSize(int(1), int(2)); "ok" }
            "front" -> onEdt { window.toFront(); window.requestFocus(); target.requestFocus(); "ok" }
            "info" -> onEdt {
                "ok window=${window.width}x${window.height} canvas=${layer.width}x${layer.height} scale=${layer.contentScale} " +
                    "focused=${window.isFocused} visible=${window.isVisible} at=${window.x},${window.y} idle=${idleSeconds()}"
            }
            "cast" -> "ok " + dev.jellystructure.ravilo.ui.desktop.DesktopWindow.castDebug()
            // The Mac's own chrome, which no picture of the canvas shows: where the traffic lights are, who takes a click.
            "chrome" -> "ok " + (dev.jellystructure.ravilo.ui.desktop.DesktopWindow.chromeDebug() ?: "none").trim().replace("\n", " | ")
            else -> "err unknown command '$verb'"
        }
    }
}
