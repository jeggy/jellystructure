package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.jellystructure.ravilo.ui.components.CloseGlyph
import dev.jellystructure.ravilo.ui.desktop.DesktopWindow
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities

/** The strip's height: GNOME's header bar (46 dp) over the page; the phone layout on a computer reserves 32 dp. */
private val STRIP = 46.dp

/** Moves the window with the pointer: where the pointer went on screen since the press, the window goes too. */
private fun Modifier.dragsWindow(): Modifier = pointerInput(Unit) {
    var startPointer: Point? = null
    var startWindow: Point? = null
    detectDragGestures(
        onDragStart = {
            startPointer = MouseInfo.getPointerInfo()?.location
            startWindow = DesktopWindow.awtWindow?.location
        },
        onDrag = { change, _ ->
            change.consume()
            val p0 = startPointer ?: return@detectDragGestures
            val w0 = startWindow ?: return@detectDragGestures
            val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
            SwingUtilities.invokeLater { DesktopWindow.awtWindow?.setLocation(w0.x + now.x - p0.x, w0.y + now.y - p0.y) }
        },
    )
}.pointerInput(Unit) { detectTapGestures(onDoubleTap = { DesktopWindow.toggleMaximized() }) }

@Composable
actual fun DesktopTitleStrip(modifier: Modifier) {
    if (!DesktopWindow.drawsOwnFrame) return
    val colors = RaviloTheme.colors
    Box(modifier.fillMaxWidth().height(STRIP)) {
        // The drag area stops short of the right edge, where the close button is.
        Box(Modifier.fillMaxHeight().fillMaxWidth().padding(end = 52.dp).dragsWindow())
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(26.dp).clip(CircleShape)
                .background(colors.fg.copy(alpha = 0.10f)).clickable { DesktopWindow.requestClose() },
            contentAlignment = Alignment.Center,
        ) { CloseGlyph(colors.text, 12.dp, description = str("desk.close_window")) }
    }
}

/** One edge (or corner): dragging it moves that side of the window. */
private fun Modifier.resizes(left: Boolean, top: Boolean, right: Boolean, bottom: Boolean, cursor: Int): Modifier =
    pointerHoverIcon(PointerIcon(Cursor(cursor))).pointerInput(Unit) {
        var p0: Point? = null
        var b0: Rectangle? = null
        detectDragGestures(
            onDragStart = { p0 = MouseInfo.getPointerInfo()?.location; b0 = DesktopWindow.awtWindow?.bounds },
            onDrag = { change, _ ->
                change.consume()
                val start = p0 ?: return@detectDragGestures
                val b = b0 ?: return@detectDragGestures
                val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                val dx = now.x - start.x
                val dy = now.y - start.y
                val win = DesktopWindow.awtWindow ?: return@detectDragGestures
                val min = win.minimumSize
                var x = b.x; var y = b.y; var w = b.width; var h = b.height
                if (right) w = maxOf(min.width, b.width + dx)
                if (bottom) h = maxOf(min.height, b.height + dy)
                if (left) { w = maxOf(min.width, b.width - dx); x = b.x + b.width - w }
                if (top) { h = maxOf(min.height, b.height - dy); y = b.y + b.height - h }
                SwingUtilities.invokeLater { win.setBounds(x, y, w, h) }
            },
        )
    }

@Composable
actual fun DesktopWindowFrame() {
    if (!DesktopWindow.drawsOwnFrame) return
    val edge = 6.dp
    Box(Modifier.fillMaxSize().border(1.dp, RaviloTheme.colors.fg.copy(alpha = 0.12f))) {
        Box(Modifier.align(Alignment.CenterStart).width(edge).fillMaxHeight().resizes(true, false, false, false, Cursor.W_RESIZE_CURSOR))
        Box(Modifier.align(Alignment.CenterEnd).width(edge).fillMaxHeight().resizes(false, false, true, false, Cursor.E_RESIZE_CURSOR))
        Box(Modifier.align(Alignment.TopCenter).height(edge).fillMaxWidth().resizes(false, true, false, false, Cursor.N_RESIZE_CURSOR))
        Box(Modifier.align(Alignment.BottomCenter).height(edge).fillMaxWidth().resizes(false, false, false, true, Cursor.S_RESIZE_CURSOR))
        Box(Modifier.align(Alignment.BottomEnd).size(12.dp).resizes(false, false, true, true, Cursor.SE_RESIZE_CURSOR))
        Box(Modifier.align(Alignment.BottomStart).size(12.dp).resizes(true, false, false, true, Cursor.SW_RESIZE_CURSOR))
        Box(Modifier.align(Alignment.TopEnd).size(12.dp).resizes(false, true, true, false, Cursor.NE_RESIZE_CURSOR))
        Box(Modifier.align(Alignment.TopStart).size(12.dp).resizes(true, true, false, false, Cursor.NW_RESIZE_CURSOR))
    }
}

actual fun showAboutWindow() = DesktopWindow.showAbout()

actual fun Modifier.windowDragArea(): Modifier =
    if (!dev.jellystructure.ravilo.ui.desktop.DesktopPaths.isMac) dragsWindow()
    // The Mac: AppKit moves the window (snapping and tiling included) from the press it already has; the app only says
    // that this press was on empty chrome. A press a control took never gets here (requireUnconsumed).
    else pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            runCatching { dev.jellystructure.ravilo.ui.desktop.MacNative.lib?.ravilo_window_drag() }
        }
    }

actual fun placeWindowControls(x: Float, y: Float) {
    runCatching { dev.jellystructure.ravilo.ui.desktop.MacNative.lib?.ravilo_window_lights(x.toDouble(), y.toDouble()) }
}
