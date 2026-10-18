package dev.jellystructure.ravilo.desktop

import dev.jellystructure.ravilo.ui.desktop.MacNative
import kotlinx.coroutines.delay

/**
 * R383 (FR-R383-3) — whether the Mac has the app hidden, and the window's visibility that follows from it.
 *
 * `MacAppHooks` hears the hide *event*, and AWT raises that only for a hide that happens while the app runs. An app
 * launched hidden (`open -g -j`) never gets one, so it read `shown=true` and drew 67 frames a second, then 19.8, into a
 * window nobody could see (DEB-1510, DEB-1523). The app asks AppKit for the state itself, once at composition and again
 * a few times while it starts, because the launch's hide may land after the first read.
 */
internal object AppHidden {
    /** AppKit's answer; false off a Mac, with no Swift library, with a library from before the export, or before NSApp exists. */
    fun now(): Boolean = MacNative.appHidden()

    /** The window is visible when the viewer has not closed it to the background and the Mac has not hidden the app. */
    fun windowVisible(shown: Boolean, appHidden: Boolean): Boolean = shown && !appHidden

    /** Reads again after each of these (ms) — about ten seconds, the length of a slow launch. */
    val STARTUP_REREADS: List<Long> = listOf(250, 500, 1_000, 2_000, 2_000, 2_000, 2_000)

    /** Tells [onHidden] what [read] says now and after each of [rereads]; every answer is the truth, so a late one wins. */
    suspend fun readAtStartup(rereads: List<Long> = STARTUP_REREADS, read: () -> Boolean = ::now, onHidden: (Boolean) -> Unit) {
        onHidden(read())
        for (wait in rereads) {
            delay(wait)
            onHidden(read())
        }
    }
}
