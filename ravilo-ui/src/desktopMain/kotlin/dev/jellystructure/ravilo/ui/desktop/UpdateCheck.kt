package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.DesktopApp
import dev.jellystructure.ravilo.ui.components.AppUpdate
import dev.jellystructure.ravilo.ui.components.AppUpdateOffer
import dev.jellystructure.ravilo.ui.components.MacInstallState
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.shared.raviloVersion
import dev.jellystructure.shared.tv.RaviloVersion
import dev.jellystructure.shared.tv.parseRaviloRelease
import dev.jellystructure.shared.tv.raviloNewerRelease
import dev.jellystructure.shared.tv.raviloServerFloor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

/**
 * R386 (FR-R386-3/-4) — everything the update block will show, as state: what the last check decided, when it
 * finished, what Sparkle last said, and where the app runs from. Server state and Sparkle state only; nothing in it
 * is derived optimistically (FR-R386-7).
 */
data class UpdateStatus(
    /** Null before the first check has finished. */
    val plan: UpdatePlan? = null,
    /** Epoch ms of the last check that reached GitHub and the server; null before one did. */
    val checkedAt: Long? = null,
    val sparkle: SparkleSnapshot = SparkleSnapshot.UNAVAILABLE,
    /** FR-R386-5 — `TRANSLOCATED` or `ON_VOLUME`: Sparkle is never asked, and state 8 is shown. Otherwise null. */
    val cannotUpdateFrom: MacInstallState? = null,
)

/**
 * R328 (FR-R328-8) — once the app knows its server, and every 24 hours after, it reads the server's version from
 * `GET /api/health` (public by design) and offers the newer release's `.dmg` (R331's file name) in Settings.
 * A dev build on either side offers nothing. A server that does not answer keeps whatever was offered and is asked
 * again a quarter of an hour later.
 *
 * R386 keeps that schedule and adds what the Mac does with it (FR-R386-3): the same read of the server, then
 * [UpdateRules.plan]'s decision, then Sparkle when S > A. The old line above is untouched until the screens task.
 */
object UpdateCheck {
    private const val RELEASES = "https://github.com/jeggy/jellystructure/releases/download"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RETRY_MS = 15L * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { PrefsFile("update") }

    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status
    @Volatile private var checkRequested = false

    /** State 9's toggle, stored on this Mac and handed to Sparkle. Defaults to **on** (FR-R386-4). */
    var autoDownload: Boolean
        get() = prefs.get("auto_download") != "false"
        set(value) {
            prefs.put("auto_download", value.toString())
            if (DesktopPaths.isMac) MacUpdater.setAutoDownload(value)
        }

    /** *Check now*, and *Check for Updates…* (FR-R386-9): asks again within a second instead of at the next tick. */
    fun checkNow() { checkRequested = true }

    /** FR-R386-5 — where this app cannot be updated from; null anywhere else. Reads no state of ours. */
    internal fun placeThatCannotUpdate(): MacInstallState? =
        MacInstall.stateOf(MacInstall.appPath()).takeIf { it == MacInstallState.TRANSLOCATED || it == MacInstallState.ON_VOLUME }

    /** The feed Sparkle is asked about — the debug override, or what the last check decided. Null: nothing to read. */
    private fun feedForSparkle(): String? =
        UpdateRules.feedOverride(System::getenv, System::getProperty)
            ?: (_status.value.plan as? UpdatePlan.AskSparkle)?.feedUrl

    /** State 3's offer form (toggle off), D9: hands over to Sparkle's own window. Never while the app cannot update. */
    fun downloadWithSparkleWindow() {
        if (!DesktopPaths.isMac || placeThatCannotUpdate() != null) return
        feedForSparkle()?.let(MacUpdater::checkWithWindow)
    }

    /** *Restart to update*: Sparkle installs the downloaded update and relaunches. */
    fun restartToUpdate() {
        if (!DesktopPaths.isMac || placeThatCannotUpdate() != null) return
        MacUpdater.installAndRelaunch()
    }

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

    /** FR-R386-3's L, or what `v<S>`'s appcast came to: a 404 is *nothing published*, anything unreadable is offline. */
    private suspend fun readAppcast(url: String): AppcastRead = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            DesktopApp.okHttp.newCall(request).execute().use { r ->
                when {
                    r.code == 404 -> AppcastRead.NotPublished
                    r.isSuccessful -> UpdateRules.versionInAppcast(r.body.string())?.let { AppcastRead.Release(it) } ?: AppcastRead.NotPublished
                    else -> AppcastRead.Unreachable
                }
            }
        }.getOrElse { AppcastRead.Unreachable }
    }

    /**
     * One check on a Mac (FR-R386-3). Sparkle is asked only when the table says S > A, only when the app can update
     * from where it runs (FR-R386-5), and with the debug override in place of the table when a spike build has one.
     * Returns the decision, or null where no decision is made (not a Mac).
     */
    private suspend fun checkMac(server: String?): UpdatePlan? {
        if (!DesktopPaths.isMac) return null
        val app = raviloVersion()
        val a = parseRaviloRelease(app.trim().removePrefix("v"))
        val s = raviloServerFloor(server)
        val feed = if (a != null && s != null && s > a) readAppcast(UpdateRules.appcastOf(s)) else null
        val latest = if (a != null && s != null && s <= a) readAppcast(UpdateRules.LATEST_APPCAST) else null
        return UpdateRules.plan(app, server, feed, latest)
    }

    private fun publish(plan: UpdatePlan?, now: Long) {
        val sparkle = if (DesktopPaths.isMac) MacUpdater.snapshot() else SparkleSnapshot.UNAVAILABLE
        // FR-R386-12 — the engine hands out its changes once; a poll with none writes nothing.
        sparkle.changes.forEach { println(it.logLine()) }
        _status.value = _status.value.copy(
            plan = plan ?: _status.value.plan,
            checkedAt = if (plan != null && plan != UpdatePlan.Offline) now else _status.value.checkedAt,
            sparkle = sparkle,
            cannotUpdateFrom = if (DesktopPaths.isMac) placeThatCannotUpdate() else null,
        )
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        var askedUrl = ""
        var nextAt = 0L
        var sparkleStarted = false
        while (isActive) {
            val url = raviloBaseUrl()
            val now = System.currentTimeMillis()
            if (url != askedUrl) { askedUrl = url; nextAt = 0L; AppUpdate.set(null) }
            // FR-R386-5 — Sparkle is not even created where the app cannot update from.
            if (DesktopPaths.isMac && !sparkleStarted && placeThatCannotUpdate() == null) {
                sparkleStarted = true
                MacUpdater.setAutoDownload(autoDownload)
                MacUpdater.start()
            }
            if (url.isNotBlank() && (now >= nextAt || checkRequested)) {
                checkRequested = false
                val server = serverVersion(url)
                if (server != null) AppUpdate.set(offerFor(raviloVersion(), server, DesktopPaths.isMac))
                val override = UpdateRules.feedOverride(System::getenv, System::getProperty)
                val plan = if (override != null && DesktopPaths.isMac) {
                    // The spike: the appcast this build was pointed at stands in for the whole table.
                    UpdatePlan.AskSparkle(null, override)
                } else checkMac(server)
                val guard = if (DesktopPaths.isMac) placeThatCannotUpdate() else null
                if (plan is UpdatePlan.AskSparkle && guard == null) MacUpdater.checkInBackground(plan.feedUrl)
                // Offline keeps the last check's time; it and an unreachable server ask again in a quarter of an hour.
                publish(plan, now)
                nextAt = now + if (server == null || plan == UpdatePlan.Offline) RETRY_MS else DAY_MS
            }
            publish(null, now)
            // One second, not sixty: the snapshot above follows Sparkle's callbacks, and *Check now* answers promptly.
            delay(1_000)
        }
    }
}
