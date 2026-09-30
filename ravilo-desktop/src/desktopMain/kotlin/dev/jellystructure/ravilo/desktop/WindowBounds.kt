package dev.jellystructure.ravilo.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPosition
import dev.jellystructure.ravilo.ui.desktop.PrefsFile
import java.awt.GraphicsEnvironment
import java.awt.Rectangle

/**
 * FR-R328-5 — the window opens where it was left, at the size it was left. A position that is no longer on any
 * display (a monitor was unplugged) opens centred instead; a full-screen or minimised window saves nothing.
 */
internal object WindowBounds {
    private val file by lazy { PrefsFile("ravilo_window") }
    private val DEFAULT_SIZE = DpSize(1280.dp, 800.dp)

    class Bounds(val position: WindowPosition, val size: DpSize)

    fun load(): Bounds {
        val x = file.get("x")?.toFloatOrNull()
        val y = file.get("y")?.toFloatOrNull()
        val w = file.get("w")?.toFloatOrNull()?.coerceAtLeast(360f)   // R337 FR-R337-11 — the minimum is 360 × 600 now
        val h = file.get("h")?.toFloatOrNull()?.coerceAtLeast(600f)
        val size = if (w != null && h != null) DpSize(w.dp, h.dp) else DEFAULT_SIZE
        val position = when {
            x != null && y != null && onSomeDisplay(x.toInt(), y.toInt()) -> WindowPosition(x.dp, y.dp)
            // A session with no display yet (a remote desktop nobody is connected to — seen in GNOME's remote login,
            // 2026-09-30): centred on a 0 × 0 screen the window sat at −640, −376, its header off the display that
            // appeared later. Near the origin it is on that display when it comes.
            !anyDisplay() -> WindowPosition(48.dp, 48.dp)
            else -> WindowPosition(Alignment.Center)
        }
        return Bounds(position, size)
    }

    fun save(position: WindowPosition, size: DpSize) {
        if (!position.isSpecified || !size.isSpecified) return
        file.put("x", position.x.value.toString())
        file.put("y", position.y.value.toString())
        file.put("w", size.width.value.toString())
        file.put("h", size.height.value.toString())
    }

    private fun anyDisplay(): Boolean = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { !it.defaultConfiguration.bounds.isEmpty }
    }.getOrDefault(true)

    /** The title bar's top-left 100 × 30 must be on a display, or the window could not be dragged back. */
    private fun onSomeDisplay(x: Int, y: Int): Boolean = runCatching {
        val grip = Rectangle(x, y, 100, 30)
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { it.defaultConfiguration.bounds.intersects(grip) }
    }.getOrDefault(false)
}
