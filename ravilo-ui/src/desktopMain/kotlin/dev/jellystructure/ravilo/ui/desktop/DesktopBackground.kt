package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.ui.DesktopApp
import dev.jellystructure.ravilo.ui.i18n.t
import javax.swing.SwingUtilities

/**
 * R337 (FR-R337-12) — closing the window while music plays keeps it playing (R328 D7). On Linux the first such close
 * asks the Background portal once (*Keep playing in the background?*); the answer is remembered. Allowed, Ravilo keeps
 * playing under *Background Apps* in GNOME's Quick Settings until it is quit there; refused, this close and every later
 * one closes the app and the music stops. No portal (a desktop without one): keep playing, as the Mac does.
 */
object DesktopBackground {
    private const val KEY = "background_answer"

    /**
     * Whether closing the window hides it rather than quitting. Found live 2026-10-09 (Mac, R328): the check looked
     * only at the Mac's own player, so ⌘W while a song played on a speaker quit Ravilo — the cast is ours to control
     * and to rejoin, so a linked cast (playing or paused) keeps the app running too.
     */
    fun keepsRunningOnClose(localPlaying: Boolean, castLinked: Boolean): Boolean = localPlaying || castLinked

    fun onCloseWhilePlaying(quit: () -> Unit) {
        if (DesktopPaths.isMac) return
        when (DesktopApp.prefs.get(KEY)) {
            "no" -> { quit(); return }
            "yes" -> return
        }
        Thread({
            LinuxPortal.requestBackground(t("desk.bg_keep", LastLanguage.read() ?: "en")) { allowed ->
                when (allowed) {
                    true -> DesktopApp.prefs.put(KEY, "yes")
                    false -> { DesktopApp.prefs.put(KEY, "no"); SwingUtilities.invokeLater(quit) }
                    null -> Unit   // no portal: keep playing, ask again next time
                }
            }
        }, "ravilo-background-ask").apply { isDaemon = true; start() }
    }
}
