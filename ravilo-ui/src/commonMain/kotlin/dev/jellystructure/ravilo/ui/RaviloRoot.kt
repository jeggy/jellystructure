package dev.jellystructure.ravilo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.ravilo.ui.screens.ServerSetupScreen
import dev.jellystructure.shared.tv.TvApiClient

/** Platform-specific: creates the HttpClient configured for the given target. */
expect fun createTvApiClient(baseUrl: String, deviceTokenProvider: () -> String?): TvApiClient

/** Platform-provides the jellystructure server base URL. Returns empty string if not yet configured. */
expect fun raviloBaseUrl(): String

/** Persists a user-entered server URL. */
expect fun saveBaseUrl(url: String)

/** Token storage — platform-specific persist across restarts. */
expect object TokenStore {
    fun get(): String?
    fun set(token: String)
    fun clear()
}

/**
 * Phase 141/R175 — a stable id for this physical device, generated once and persisted forever
 * (unlike the retired pairing flow's server-minted id). The server folds it — together with the
 * signed-in user — into the Jellyfin identity it authenticates under, so re-logins (e.g. `Add user`
 * on a shared TV) are recognized as the same device instead of minting an unrelated one each time.
 */
expect object DeviceIdStore {
    fun get(): String
}

/** A short, human-readable label for this device/platform, sent at login so the server's device list
 *  (Phase 143) shows something friendlier than "Ravilo TV <id>" when nothing better is known. */
expect fun deviceDisplayName(): String

private val HEX_CHARS = "0123456789abcdef"

/** Portable across every KMP target — used by each [DeviceIdStore] actual to mint a fresh id on first
 *  use; storage (where it's persisted) is the only platform-specific part. */
internal fun randomDeviceId(): String {
    val bytes = kotlin.random.Random.nextBytes(16)
    return buildString {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }
}

// ─── R80: Browser history / URL navigation seam ──────────────────────────────

/** Push a hash route into browser history. No-op on non-web targets. */
expect fun pushRoute(route: String)

/** Replace the current history entry's hash without adding a new entry. No-op on non-web targets. */
expect fun replaceRoute(route: String)

/**
 * Install a listener that fires whenever the browser's current hash URL changes (back/forward/manual).
 * Returns an unsubscribe function. No-op (and returns `{}`) on non-web targets.
 */
expect fun installHashListener(onRoute: (String) -> Unit): () -> Unit

/**
 * Bug fix: the app's whole back-navigation stack was driven only by `Key.Back` KeyEvents (RaviloApp's
 * root `.onKeyEvent{}`), which a TV remote's physical back key genuinely sends — but on Android phones
 * with gesture navigation (the default since Android 10, and Pixel's default), a back-swipe is handled
 * entirely by the system's OnBackPressedDispatcher/predictive-back and never reaches Compose as a
 * KeyEvent at all. Confirmed live: on the Pixel 9, back did nothing on the standalone Live TV Guide
 * screen (or any screen without its own explicit back affordance). PlatformBackHandler bridges the
 * platform's native back gesture into the same pop() the D-pad/remote path already uses — a no-op on
 * platforms where system back doesn't bypass Compose's key-event system (web: browser back already
 * goes through installHashListener above).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

@Composable
fun RaviloRoot() {
    var baseUrl by remember { mutableStateOf(raviloBaseUrl()) }

    if (baseUrl.isEmpty()) {
        ServerSetupScreen(onUrlSaved = { url ->
            saveBaseUrl(url)
            baseUrl = url
        })
        return
    }

    val apiClient = remember(baseUrl) {
        createTvApiClient(baseUrl) {
            MultiTokenStore.getActive()?.deviceToken ?: TokenStore.get()
        }
    }
    RaviloApp(
        apiClient = apiClient,
        onChangeServer = {
            saveBaseUrl("")
            baseUrl = ""
        },
    )
}
