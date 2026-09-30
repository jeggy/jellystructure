package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** R337 — the corner radius of a window Ravilo draws itself (`.gn { border-radius: 12px }`, libadwaita's). */
val WINDOW_CORNER = 12.dp

/** The strip's height: GNOME's header bar (46 dp) over the page; the phone layout on a computer reserves 32 dp. */
private val STRIP = 46.dp

/**
 * Moves the window with the pointer: where the pointer went on screen since the press, the window goes too.
 *
 * Only a press that **no control took** starts it, and only once the pointer has really moved. A header bar is a drag
 * area with buttons on it; a drag detector that takes any press steals a click whose pointer slipped a pixel or two —
 * GNOME's Back needed several clicks (owner, 2026-09-30).
 */
private fun Modifier.dragsWindow(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val startPointer = MouseInfo.getPointerInfo()?.location
        val startWindow = DesktopWindow.awtWindow?.location
        val first = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
        drag(first.id) { change ->
            change.consume()
            val p0 = startPointer ?: return@drag
            val w0 = startWindow ?: return@drag
            val now = MouseInfo.getPointerInfo()?.location ?: return@drag
            SwingUtilities.invokeLater { DesktopWindow.awtWindow?.setLocation(w0.x + now.x - p0.x, w0.y + now.y - p0.y) }
        }
    }
}.pointerInput(Unit) { detectTapGestures(onDoubleTap = { DesktopWindow.toggleMaximized() }) }

/** One of GNOME's window buttons (`.gx`): a small circle on a faint plate — minimise, maximise, close. */
@Composable
private fun ControlButton(control: DesktopWindow.Control, size: androidx.compose.ui.unit.Dp) {
    val colors = RaviloTheme.colors
    val label = str(when (control) {
        DesktopWindow.Control.CLOSE -> "desk.close_window"
        DesktopWindow.Control.MAXIMIZE -> "desk.maximise"
        DesktopWindow.Control.MINIMIZE -> "mac.menu_minimise"
    })
    Box(
        Modifier.size(size).clip(CircleShape).background(colors.fg.copy(alpha = 0.10f))
            .clickable {
                when (control) {
                    DesktopWindow.Control.CLOSE -> DesktopWindow.requestClose()
                    DesktopWindow.Control.MAXIMIZE -> DesktopWindow.toggleMaximized()
                    DesktopWindow.Control.MINIMIZE -> DesktopWindow.requestMinimize()
                }
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val ink = colors.text
        when (control) {
            DesktopWindow.Control.CLOSE -> CloseGlyph(ink, 12.dp)
            DesktopWindow.Control.MAXIMIZE -> Canvas(Modifier.size(10.dp)) {
                drawRoundRect(ink, topLeft = Offset(1.dp.toPx(), 1.dp.toPx()), size = Size(8.dp.toPx(), 8.dp.toPx()),
                    cornerRadius = CornerRadius(1.5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
            DesktopWindow.Control.MINIMIZE -> Canvas(Modifier.size(10.dp)) {
                drawLine(ink, Offset(1.dp.toPx(), 8.dp.toPx()), Offset(9.dp.toPx(), 8.dp.toPx()), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

private fun buttonSize(compact: Boolean) = if (compact) 22.dp else 26.dp
private fun buttonGap(compact: Boolean) = if (compact) 5.dp else 6.dp

@Composable
actual fun WindowControlButtons(start: Boolean, modifier: Modifier, compact: Boolean) {
    if (!DesktopWindow.drawsOwnFrame) return
    val controls = DesktopWindow.controls.collectAsState().value
    val list = if (start) controls.start else controls.end
    if (list.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(buttonGap(compact)), verticalAlignment = Alignment.CenterVertically) {
        list.forEach { ControlButton(it, buttonSize(compact)) }
    }
}

@Composable
actual fun windowControlsWidth(start: Boolean, compact: Boolean): androidx.compose.ui.unit.Dp {
    if (!DesktopWindow.drawsOwnFrame) return 0.dp
    val controls = DesktopWindow.controls.collectAsState().value
    val n = (if (start) controls.start else controls.end).size
    return if (n == 0) 0.dp else buttonSize(compact) * n + buttonGap(compact) * (n - 1)
}

@Composable
actual fun DesktopTitleStrip(modifier: Modifier, startControls: Boolean, drag: Boolean) {
    if (!DesktopWindow.drawsOwnFrame) return
    val startW = if (startControls) windowControlsWidth(true) else 0.dp
    val endW = windowControlsWidth(false)
    Box(modifier.fillMaxWidth().height(STRIP)) {
        // The drag area stops short of the buttons. Only where the page has no bar of its own to move the window by:
        // an area on top of a toolbar would take the clicks meant for the toolbar's buttons.
        if (drag) Box(Modifier.fillMaxHeight().fillMaxWidth()
            .padding(start = if (startW > 0.dp) startW + 20.dp else 0.dp, end = if (endW > 0.dp) endW + 20.dp else 0.dp).dragsWindow())
        if (startControls) WindowControlButtons(true, Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        WindowControlButtons(false, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
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
    // The border follows the window's corners: rounded (12 dp, as libadwaita's) until the window fills the screen.
    val filled = DesktopWindow.filled.collectAsState().value
    Box(Modifier.fillMaxSize().border(1.dp, RaviloTheme.colors.fg.copy(alpha = 0.12f), RoundedCornerShape(if (filled) 0.dp else WINDOW_CORNER))) {
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
