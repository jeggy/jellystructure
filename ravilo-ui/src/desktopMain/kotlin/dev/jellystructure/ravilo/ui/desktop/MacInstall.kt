package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.components.MacDiagnosis
import dev.jellystructure.ravilo.ui.components.MacInstallState
import dev.jellystructure.ravilo.ui.components.ServerHostClass
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * R385 (FR-R385-1/-2) — the two facts behind *why* a Mac cannot reach the server: where macOS is running this app
 * from, and whether the configured server is on the household's own network.
 *
 * Both are read here, in Kotlin, from what the JVM already knows. **No new Swift and no native call**, so the self-
 * test's `library ABI 4` line is untouched (FR-R385-1). Nothing here is shown by itself: `MacDiagnosis` is consulted
 * only once a request has actually failed with no answer (D1), and on Linux `install()` returns having set nothing.
 */
object MacInstall {

    /**
     * FR-R385-1 — the launcher's own path. `jpackage.app-path` is set by the launcher jpackage builds (so it is the
     * real `.app` macOS started, translocated path and all); `compose.application.resources.dir` is the packaged
     * fallback, and points inside the same bundle. Neither is set when the app is run from Gradle, which is the
     * development build and never translocated.
     */
    internal fun appPath(): String? = System.getProperty("jpackage.app-path")?.trim()?.ifBlank { null }
        ?: System.getProperty("compose.application.resources.dir")?.trim()?.ifBlank { null }

    /** FR-R385-1's table, pure: `/AppTranslocation/` anywhere in the path, then `/Volumes/` at its head. */
    internal fun stateOf(path: String?): MacInstallState = when {
        path == null -> MacInstallState.IN_PLACE
        // A quarantined copy runs from /private/var/folders/…/AppTranslocation/<uuid>/d/Ravilo.app — which also
        // satisfies no other rule, so this one is asked first.
        path.contains("/AppTranslocation/") -> MacInstallState.TRANSLOCATED
        path.startsWith("/Volumes/") -> MacInstallState.ON_VOLUME
        else -> MacInstallState.IN_PLACE
    }

    /** The host out of a configured base URL (`https://host:port/` → `host`), or null when there is nothing to read. */
    internal fun hostOf(baseUrl: String): String? = baseUrl.trim().ifBlank { null }
        ?.substringAfter("://")?.substringBefore('/')
        // An IPv6 literal is written in brackets; a port follows the last colon of a name.
        ?.let { if (it.startsWith("[")) it.substringAfter('[').substringBefore(']') else it.substringBefore(':') }
        ?.trim()?.ifBlank { null }

    /**
     * FR-R385-2 — a private or link-local address needs the household's network, and so macOS's Local Network
     * consent. Java's `isSiteLocalAddress` is `10/8`, `172.16/12` and `192.168/16`; the carrier-grade range
     * `100.64/10` and IPv6's `fc00::/7` are not in any JDK predicate, so they are read off the bytes.
     */
    internal fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isAnyLocalAddress) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xFF) in 64..127
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    /**
     * FR-R385-2 — one resolution of the configured host. A host that does not resolve is `UNKNOWN`, which the table
     * treats as `PUBLIC`: an unresolvable name is a DNS answer, not a permission state, and inventing a permission
     * out of it would put a false explanation on an ordinary outage.
     *
     * Any private address among the answers counts: that is the one a connection would be refused on.
     */
    internal fun classify(host: String?): ServerHostClass {
        val name = host ?: return ServerHostClass.UNKNOWN
        val addresses = runCatching { InetAddress.getAllByName(name) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return ServerHostClass.UNKNOWN
        return if (addresses.any(::isPrivate)) ServerHostClass.LAN else ServerHostClass.PUBLIC
    }

    @Volatile private var installed = false

    /**
     * Sets what this platform knows, once. On anything but a Mac it sets nothing at all — `MacDiagnosis.installState`
     * stays null and no failure anywhere is ever refined (there is no such permission model on Linux).
     */
    fun install() {
        if (installed || !DesktopPaths.isMac) return
        installed = true
        MacDiagnosis.installState = stateOf(appPath())
        // FR-R385-4 — R330's own button and pane, now set at startup rather than when the cast sender is first
        // remembered: sign-in is one of the places that offers it, and that is before any sender exists.
        MacDiagnosis.openLocalNetworkSettings = {
            runCatching { ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_LocalNetwork").start() }
            Unit
        }
    }

    /**
     * FR-R385-2 — follows the configured server, resolving it once per address (and asking again every quarter of an
     * hour while it does not resolve, so a server that comes back is noticed). Shaped like [UpdateCheck.start], and
     * off the Mac it only ever sets `IN_PLACE`-equivalent silence.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        install()
        var resolved = ""
        var retryAt = 0L
        while (isActive) {
            val url = raviloBaseUrl()
            val now = System.currentTimeMillis()
            val stale = MacDiagnosis.serverHostClass == ServerHostClass.UNKNOWN && now >= retryAt
            if (url != resolved || stale) {
                resolved = url
                MacDiagnosis.serverHostClass = withContext(Dispatchers.IO) { classify(hostOf(url)) }
                retryAt = now + 15L * 60 * 1000
            }
            delay(30_000)
        }
    }
}
