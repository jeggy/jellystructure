package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.jellystructure.ravilo.ui.components.CloseGlyph
import dev.jellystructure.ravilo.ui.desktop.DesktopPaths
import dev.jellystructure.ravilo.ui.desktop.DesktopWindow
import dev.jellystructure.ravilo.ui.desktop.MacNative
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.SystemUiFont
import kotlinx.coroutines.delay
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.SwingUtilities

private val WIDTH = 600.dp          // `.setw { width: 600px }`
private val MAX_HEIGHT = 680.dp     // taller than this and the window scrolls
/** The close button's centre in the Settings window: `.setw .tb .lights { left: 16px; top: 16px }`, 12-point lights. */
private const val LIGHTS = 22.0

@Composable
actual fun DesktopSettingsWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val mac = DesktopPaths.isMac
    val ownFrame = DesktopWindow.drawsOwnFrame
    val main = DesktopWindow.awtWindow
    val state = rememberWindowState(
        size = DpSize(WIDTH, 420.dp),
        // Over the app's window, a little below its top — where a Mac puts a Settings window the first time.
        position = main?.let { WindowPosition((it.x + (it.width - WIDTH.value.toInt()) / 2).dp, (it.y + 72).dp) }
            ?: WindowPosition(Alignment.Center),
    )
    // Said before the window exists, so its lights are in place the first time AppKit lays it out.
    if (mac) remember(title) { runCatching { MacNative.lib?.ravilo_window_lights_titled(title, LIGHTS, LIGHTS) } }
    Window(
        onCloseRequest = onClose,
        state = state,
        title = title,
        resizable = false,
        undecorated = ownFrame,
        transparent = ownFrame,   // GNOME: the window's corners are round, so what is outside them shows through
        onPreviewKeyEvent = { ev ->
            val close = ev.type == KeyEventType.KeyDown && ev.key == Key.W && (ev.isMetaPressed || ev.isCtrlPressed)
            if (close) onClose()
            close
        },
        // Esc closes the window only when nothing inside took it: an open list of choices closes first.
        onKeyEvent = { ev ->
            val close = ev.type == KeyEventType.KeyDown && ev.key == Key.Escape
            if (close) onClose()
            close
        },
    ) {
        val colors = RaviloTheme.colors
        LaunchedEffect(Unit) {
            DesktopWindow.secondWindow = window
            main?.iconImages?.takeIf { it.isNotEmpty() }?.let { window.iconImages = it }
            if (mac) {
                // The main window's shape: the content runs up under a transparent title bar, the lights over the tabs.
                window.rootPane.run {
                    putClientProperty("apple.awt.fullWindowContent", true)
                    putClientProperty("apple.awt.transparentTitleBar", true)
                    putClientProperty("apple.awt.windowTitleVisible", false)
                }
                delay(120)   // AppKit has the full-size title bar by now; the lights are laid out again over it
                runCatching { MacNative.lib?.ravilo_window_lights_titled(title, LIGHTS, LIGHTS) }
            }
        }
        DisposableEffect(Unit) { onDispose { if (DesktopWindow.secondWindow === window) DesktopWindow.secondWindow = null } }
        // R338 — the window's own appearance follows the drawn theme, as the main window's does (its lights, its shadow).
        val light = colors.isLight
        if (mac) LaunchedEffect(light) {
            window.rootPane.putClientProperty("apple.awt.windowAppearance", if (light) "NSAppearanceNameAqua" else "NSAppearanceNameDarkAqua")
        }
        val density = LocalDensity.current
        var headPx by remember { mutableIntStateOf(0) }
        var bodyPx by remember { mutableIntStateOf(0) }
        // As tall as what it holds: a tab with less in it is a shorter window, as a Mac's Settings is. The size goes to
        // the window itself as well as to its state — the state alone left GNOME's window at its first height.
        LaunchedEffect(headPx, bodyPx) {
            if (bodyPx == 0) return@LaunchedEffect
            val wanted = with(density) { (headPx + bodyPx).toDp() }.coerceIn(200.dp, MAX_HEIGHT)
            if (kotlin.math.abs((wanted - state.size.height).value) > 0.5f) {
                state.size = DpSize(WIDTH, wanted)
                window.setSize(WIDTH.value.toInt(), wanted.value.toInt())
            }
        }
        val corner = androidx.compose.foundation.shape.RoundedCornerShape(if (ownFrame) WINDOW_CORNER else 0.dp)
        // A dialog on GNOME has the close button alone, on the side the desktop keeps it.
        val closeAtStart = ownFrame && DesktopWindow.controls.collectAsState().value.start.contains(DesktopWindow.Control.CLOSE)
        Box(Modifier.fillMaxSize().clip(corner).background(colors.background)) {
            Column(Modifier.fillMaxSize()) {
                if (ownFrame) {
                    // GNOME's header bar (`.gn.setw .hb`): the title in the middle, the close button alone on the right.
                    Box(Modifier.fillMaxWidth().height(46.dp).onSizeChanged { headPx = it.height }) {
                        Box(Modifier.fillMaxSize().padding(start = if (closeAtStart) 46.dp else 0.dp, end = if (closeAtStart) 0.dp else 46.dp).pointerInput(Unit) {
                            var p0: Point? = null
                            var w0: Point? = null
                            detectDragGestures(
                                onDragStart = { p0 = MouseInfo.getPointerInfo()?.location; w0 = window.location },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val p = p0 ?: return@detectDragGestures
                                    val w = w0 ?: return@detectDragGestures
                                    val now = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                                    SwingUtilities.invokeLater { window.setLocation(w.x + now.x - p.x, w.y + now.y - p.y) }
                                },
                            )
                        })
                        Text(title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = SystemUiFont,
                            modifier = Modifier.align(Alignment.Center))
                        Box(
                            Modifier.align(if (closeAtStart) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 10.dp).size(26.dp).clip(CircleShape)
                                .background(colors.fg.copy(alpha = 0.10f)).clickable(onClick = onClose),
                            contentAlignment = Alignment.Center,
                        ) { CloseGlyph(colors.text, 12.dp, description = str("desk.close_window")) }
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    Column(Modifier.fillMaxWidth().onSizeChanged { bodyPx = it.height }) { content() }
                }
            }
            if (ownFrame) Box(Modifier.matchParentSize().border(1.dp, colors.fg.copy(alpha = 0.12f), corner))
        }
    }
}
