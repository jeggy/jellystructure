package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

actual val isWebPlatform: Boolean = true

actual val isIOSWebPlatform: Boolean
    get() = jsIsIOS()

@Composable
actual fun rememberIsStandaloneWebApp(): Boolean {
    // Read once: display-mode doesn't change over a page's lifetime (installing/uninstalling reloads
    // the page either way), so no poll is needed here unlike the two below.
    return remember { jsIsStandalone() }
}

@Composable
actual fun rememberInstallPromptAvailable(): Boolean {
    val state = produceState(initialValue = jsInstallPromptAvailable()) {
        while (true) {
            delay(1000)
            value = jsInstallPromptAvailable()
        }
    }
    return state.value
}

actual fun triggerNativeInstall(): Unit = jsTriggerInstall()

@Composable
actual fun rememberUpdateAvailable(): Boolean {
    val state = produceState(initialValue = jsUpdateAvailable()) {
        while (true) {
            delay(1000)
            value = jsUpdateAvailable()
        }
    }
    return state.value
}

private fun jsIsIOS(): Boolean = js(
    "(/iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && (navigator.maxTouchPoints||0) > 1))"
)

private fun jsIsStandalone(): Boolean = js(
    "(window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true)"
)

private fun jsInstallPromptAvailable(): Boolean = js("(!!window.__raviloInstallPrompt)")

private fun jsUpdateAvailable(): Boolean = js("(window.__raviloUpdateAvailable === true)")

private fun jsTriggerInstall(): Unit = js("(window.raviloTriggerInstall && window.raviloTriggerInstall())")

actual fun reloadForUpdate(): Unit = jsReloadForUpdate()

private fun jsReloadForUpdate(): Unit = js(
    """(function() {
        if (navigator.serviceWorker && navigator.serviceWorker.getRegistration) {
            navigator.serviceWorker.getRegistration().then(function(reg) {
                if (reg && reg.waiting) reg.waiting.postMessage('SKIP_WAITING');
                window.location.reload();
            }).catch(function() { window.location.reload(); });
        } else {
            window.location.reload();
        }
    })()"""
)

actual fun installCardDismissed(): Boolean = jsInstallCardDismissed()
actual fun dismissInstallCard(): Unit = jsSetInstallCardDismissed()

private fun jsInstallCardDismissed(): Boolean = js("(localStorage.getItem('ravilo_install_dismissed') === '1')")
private fun jsSetInstallCardDismissed(): Unit = js("localStorage.setItem('ravilo_install_dismissed', '1')")
