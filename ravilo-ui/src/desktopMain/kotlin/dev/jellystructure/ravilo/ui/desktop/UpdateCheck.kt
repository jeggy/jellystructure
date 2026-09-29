package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.DesktopApp
import dev.jellystructure.ravilo.ui.components.AppUpdate
import dev.jellystructure.ravilo.ui.components.AppUpdateOffer
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.shared.raviloVersion
import dev.jellystructure.shared.tv.RaviloVersion
import dev.jellystructure.shared.tv.raviloNewerRelease
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * R328 (FR-R328-8) — once the app knows its server, and every 24 hours after, it reads the server's version from
 * `GET /api/health` (public by design) and offers the newer release's `.dmg` (R331's file name) in Settings.
 * A dev build on either side offers nothing. A server that does not answer keeps whatever was offered and is asked
 * again a quarter of an hour later.
 */
object UpdateCheck {
    private const val RELEASES = "https://github.com/jeggy/jellystructure/releases/download"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RETRY_MS = 15L * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }

    /** R331 D4 — `ravilo-mac-<N>.dmg` on the `v<N>` release. */
    fun downloadUrl(version: RaviloVersion): String = "$RELEASES/v$version/ravilo-mac-$version.dmg"

    /** Only the Mac has a `.dmg`; the Linux development build is never offered one. */
    fun offerFor(app: String?, server: String?, isMac: Boolean): AppUpdateOffer? {
        if (!isMac) return null
        val newer = raviloNewerRelease(app, server) ?: return null
        return AppUpdateOffer(newer.toString(), downloadUrl(newer))
    }

    /** The `version` field of `/api/health`, or null when the server cannot be asked. */
    fun parseHealth(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["version"]?.jsonPrimitive?.content?.trim()?.ifBlank { null }
    }.getOrNull()

    private suspend fun serverVersion(baseUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/health").get().build()
            DesktopApp.okHttp.newCall(request).execute().use { r -> if (r.isSuccessful) parseHealth(r.body.string()) else null }
        }.getOrNull()
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        var askedUrl = ""
        var nextAt = 0L
        while (isActive) {
            val url = raviloBaseUrl()
            val now = System.currentTimeMillis()
            if (url != askedUrl) { askedUrl = url; nextAt = 0L; AppUpdate.set(null) }
            if (url.isNotBlank() && now >= nextAt) {
                val server = serverVersion(url)
                if (server != null) {
                    AppUpdate.set(offerFor(raviloVersion(), server, DesktopPaths.isMac))
                    nextAt = now + DAY_MS
                } else nextAt = now + RETRY_MS
            }
            delay(60_000)
        }
    }
}
