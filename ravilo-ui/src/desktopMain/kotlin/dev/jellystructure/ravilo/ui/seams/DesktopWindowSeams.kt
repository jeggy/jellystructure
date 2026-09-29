package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import dev.jellystructure.ravilo.ui.desktop.DesktopWindow
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/*
 * R328 (FR-R328-3) — the seams a desktop window answers. See the table in the spec for each answer.
 */

/** R293 — on screen means the window is shown and not in the Dock. Focus is not asked: a film on a second display
 *  while the viewer types elsewhere is on screen. */
@Composable
actual fun rememberAppOnScreen(): Boolean {
    val shown by DesktopWindow.shown.collectAsState()
    val minimised by DesktopWindow.minimised.collectAsState()
    return shown && !minimised
}

/** R293 (FR-R293-6) — `<window>;<focus>;<net>;<os>`, each a plain token the server keeps as it is. */
@Composable
actual fun rememberDeviceStateProbe(): () -> String = remember {
    {
        val window = when {
            DesktopWindow.minimised.value -> "minimised"
            DesktopWindow.shown.value -> "shown"
            else -> "hidden"
        }
        val focus = if (DesktopWindow.focused.value) "focused" else "-"
        val net = detectLinkState().kind.ifBlank { "-" }
        val os = (if (dev.jellystructure.ravilo.ui.desktop.DesktopPaths.isMac) "macos" else "linux") +
            System.getProperty("os.version").orEmpty().take(12)
        "$window;$focus;$net;$os"
    }
}

// ── R263's web install seams: absent on the desktop (FR-R328-8 is the Mac's own update line) ──
actual val isWebPlatform: Boolean = false
actual val isIOSWebPlatform: Boolean = false
@Composable actual fun rememberIsStandaloneWebApp(): Boolean = false
@Composable actual fun rememberInstallPromptAvailable(): Boolean = false
actual fun triggerNativeInstall() {}
@Composable actual fun rememberUpdateAvailable(): Boolean = false
actual fun reloadForUpdate() {}
actual fun installCardDismissed(): Boolean = true
actual fun dismissInstallCard() {}

// ── The player's window ──
actual val playerBackdropColor: Color = Color.Black
/** A click on the picture toggles the chrome (FR-R329-6), the web's convention. */
actual val playerTapTogglesChrome: Boolean = true
actual val playerArrowsSeek: Boolean = true

private val blankCursor: Cursor by lazy {
    Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "ravilo-blank")
}

/** R157 / FR-R329-6 — the cursor goes with the chrome. */
actual fun setPointerCursorHidden(hidden: Boolean) {
    runCatching {
        DesktopWindow.awtWindow?.cursor = if (hidden) blankCursor else Cursor.getDefaultCursor()
    }
}

/**
 * FR-R329-7 — the player never enters full screen on its own (open question 2's lean: windowed first); it records
 * that it is on screen, so F and a double-click mean full screen, and leaving it leaves a full screen it entered.
 */
@Composable
actual fun PlayerImmersiveEffect(followSensor: Boolean) {
    DisposableEffect(Unit) {
        DesktopWindow.playerActive = true
        onDispose { DesktopWindow.leavePlayer() }
    }
}

/** FR-R329-6 — mouse movement wakes the chrome; a double-click on the picture toggles full screen. */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.wakeOnPointerMove(onMove: () -> Unit): Modifier =
    this
        .onPointerEvent(PointerEventType.Move) { onMove() }
        .onPointerEvent(PointerEventType.Press) { event ->
            if (((event.nativeEvent as? java.awt.event.MouseEvent)?.clickCount ?: 0) == 2 && event.changes.none { it.isConsumed }) {
                DesktopWindow.togglePlayerFullScreen()
            }
        }

actual fun systemPrefersReducedMotion(): Boolean = reducedMotion

/** macOS's *Reduce motion* (Accessibility → Display), read once: `defaults read com.apple.universalaccess reduceMotion`. */
private val reducedMotion: Boolean by lazy {
    if (!dev.jellystructure.ravilo.ui.desktop.DesktopPaths.isMac) return@lazy false
    runCatching {
        val p = ProcessBuilder("defaults", "read", "com.apple.universalaccess", "reduceMotion").redirectErrorStream(true).start()
        if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); return@lazy false }
        p.inputStream.bufferedReader().readText().trim() == "1"
    }.getOrDefault(false)
}

/** A desktop window has no insets; the bottom offset still applies. */
@Composable
actual fun Modifier.safeAreaPadding(includeIme: Boolean, plusBottom: Dp): Modifier =
    if (plusBottom > 0.dp) this.padding(bottom = plusBottom) else this

/** The web's hidden-input bridge has no desktop counterpart: the system keyboard types into the field directly. */
actual fun Modifier.reportTextFieldFocus(): Modifier = this

/** R244 — the handset's swipes are a phone's; the Mac's volume keys stay the Mac's (R330 D3). */
@Composable
actual fun rememberHandsetPlayerControls(): HandsetPlayerControls = remember {
    HandsetPlayerControls(
        brightness = null, setBrightness = null,
        volume = null, setVolume = null,
        systemRotationLocked = false,
        setLandscape = {},
    )
}

/** The web's DOM chrome; the desktop draws the TV's chrome in Compose (D4). */
actual object PlayerChromeBridge {
    actual fun show(state: PlayerChromeState, actions: PlayerChromeActions) {}
    actual fun hide() {}
}

/** FR-R329-11 — a trailer opens in the default browser; the player shows nothing of its own. */
@Composable
actual fun TrailerEmbed(site: String, key: String, modifier: Modifier) {
    val uri = LocalUriHandler.current
    DisposableEffect(site, key) {
        runCatching { uri.openUri(trailerWatchUrl(site, key)) }
        onDispose {}
    }
}

/** The site's own page rather than its embed: a browser tab should show the whole page. */
internal fun trailerWatchUrl(site: String, key: String): String = when (site.lowercase()) {
    "youtube" -> "https://www.youtube.com/watch?v=$key"
    "vimeo" -> "https://vimeo.com/$key"
    else -> trailerEmbedUrl(site, key)
}
