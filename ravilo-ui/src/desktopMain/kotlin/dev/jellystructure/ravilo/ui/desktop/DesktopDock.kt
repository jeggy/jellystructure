package dev.jellystructure.ravilo.ui.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

/**
 * R342 — the Mac's running Dock icon follows the listening mode.
 * - **Music mode:** the music icon (M7b), drawn for the viewer's icon style.
 * - **Films mode:** the installed icon, which macOS styles itself.
 *
 * It follows `inMusic`, what the screen shows, through the `reportListeningMode` seam. It also follows the icon style
 * and the system's light/dark, both read in R338's one-second tick ([DesktopAppearance.iconStyle]).
 *
 * The music pictures are packaged beside the Swift library (`<resources>/dock/`). Where they are absent (a
 * `gradle run`), or the library is absent or older, the Dock keeps the installed icon and nothing else changes. Off the
 * Mac this does nothing: GNOME can't change a running app's icon (Q5).
 */
object DesktopDock {
    private val music = MutableStateFlow<Boolean?>(null)
    @Volatile private var started = false

    /** What was handed to the Dock last, for the log and the test driver. */
    @Volatile var applied: String = "nothing yet"
        private set

    /** From the seam: the mode the screen shows, on every change (and once at first composition). */
    fun report(inMusic: Boolean) { music.value = inMusic }

    /**
     * From `Main.kt`'s first `LaunchedEffect`, once AWT (and so `NSApp`) is up. Until then the Dock shows the installed
     * films icon (about a second or two after the click), which can't be avoided.
     */
    fun start() {
        if (started || !DesktopPaths.isMac || MacNative.lib == null) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(music.filterNotNull(), DesktopAppearance.iconStyle) { m, style -> if (m) "music $style" else "films" }
                .distinctUntilChanged()
                .collect { key -> apply(key.startsWith("music")) }
        }
    }

    /** How many music pictures are packaged (the self-test's line); 14 in a complete build. */
    fun packagedPictures(): Int =
        System.getProperty("compose.application.resources.dir")?.ifBlank { null }
            ?.let { File(it, "dock").listFiles { f -> f.name.endsWith(".png") }?.size } ?: 0

    private fun picturesDir(): File? {
        val resources = System.getProperty("compose.application.resources.dir")?.ifBlank { null } ?: return null
        return File(resources, "dock").takeIf { File(it, "music-default.png").isFile }
    }

    private fun apply(inMusic: Boolean) {
        val lib = MacNative.lib ?: return
        val dir = if (inMusic) picturesDir() else null
        val result = runCatching { lib.ravilo_dock_icon(dir?.absolutePath) }
        applied = when {
            result.isFailure -> "unavailable (${result.exceptionOrNull()?.message})"
            !inMusic -> "films (the installed icon)"
            dir == null -> "films (no music pictures packaged)"
            result.getOrNull() == 1 -> "music · ${DesktopAppearance.iconStyle.value ?: "default"}"
            else -> "films (the music pictures did not load)"
        }
        println("${DesktopLog.stamp()} Dock icon: $applied")
    }

    /** The test driver's `dock <png>`: what was set, and the image the app is giving the Dock, written to [png]. */
    fun snapshot(png: String): String =
        "kotlin=$applied · swift=" + (runCatching { MacNative.take(MacNative.lib?.ravilo_dock_snapshot(png)) }.getOrNull() ?: "none")
}
