package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.jellystructure.ravilo.ui.screens.LoopbackSignInListener
import dev.jellystructure.ravilo.ui.screens.SsoListener
import dev.jellystructure.ravilo.ui.screens.SsoPlatform
import dev.jellystructure.ravilo.ui.screens.ssoReturnInbox

@Composable
actual fun rememberSsoPlatform(): SsoPlatform? = remember { DesktopSsoPlatform() }

/** The default browser, and a one-shot listener on `127.0.0.1` for its return (RFC 8252 §7.3). The attempt lives in the screen, in memory. */
internal class DesktopSsoPlatform : SsoPlatform {
    override fun listen(closeTabText: String): SsoListener =
        LoopbackSignInListener(closeTabText) { ssoReturnInbox.offer(it) }

    override fun open(authorizeUrl: String) {
        val uri = java.net.URI(authorizeUrl)
        val viaAwt = runCatching {
            java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)
        }.getOrDefault(false)
        if (viaAwt) { java.awt.Desktop.getDesktop().browse(uri); return }
        // A Linux session without a desktop service the JVM can reach: the freedesktop opener, then the Mac's.
        val opener = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) "open" else "xdg-open"
        ProcessBuilder(opener, uri.toString()).redirectErrorStream(true).start()
    }
}
