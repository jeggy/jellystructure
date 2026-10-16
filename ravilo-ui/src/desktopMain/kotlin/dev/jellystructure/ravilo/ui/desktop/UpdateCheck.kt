package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.DesktopApp
import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.ui.components.AppCommand
import dev.jellystructure.ravilo.ui.components.AppCommands
import dev.jellystructure.ravilo.ui.components.MacDiagnosis
import dev.jellystructure.ravilo.ui.components.MacInstallState
import dev.jellystructure.ravilo.ui.components.SoftwareUpdate
import dev.jellystructure.ravilo.ui.components.SoftwareUpdateActions
import dev.jellystructure.ravilo.ui.i18n.t
import dev.jellystructure.ravilo.ui.raviloBaseUrl
import dev.jellystructure.shared.raviloVersion
import dev.jellystructure.shared.tv.parseRaviloRelease
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
 * R386 (FR-R386-3/-4) — everything the update block shows, as state: what the last check decided, when it
 * finished, what Sparkle last said, and where the app runs from. Server state and Sparkle state only; nothing in it
 * is derived optimistically (FR-R386-7). [UpdateView] turns it into the block's one value.
 */
data class UpdateStatus(
    /** Null before the first check has finished. */
    val plan: UpdatePlan? = null,
    /** Epoch ms of the last check that reached GitHub and the server; null before one did. */
    val checkedAt: Long? = null,
    val sparkle: SparkleSnapshot = SparkleSnapshot.UNAVAILABLE,
    /** FR-R386-5 — `TRANSLOCATED` or `ON_VOLUME`: Sparkle is never asked, and state 8 is shown. Otherwise null. */
    val cannotUpdateFrom: MacInstallState? = null,
    /** A check is under way right now (it asks the server and GitHub): true only while it does. */
    val checking: Boolean = false,
)

/**
 * R386 (FR-R386-3) — once the app knows its server, and every 24 hours after, it reads the server's version from
 * `GET /api/health` (public by design); a server that does not answer is asked again a quarter of an hour later.
 * [UpdateRules.plan] decides what the answer means, Sparkle is asked when S > A, and [UpdateView] turns the result and
 * Sparkle's report into the Software update block that Settings ▸ About draws. (R328's one line with the `.dmg` link,
 * which this replaced, went in FR-R386-11.)
 */
object UpdateCheck {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RETRY_MS = 15L * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs by lazy { PrefsFile("update") }

    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status
    @Volatile private var checkRequested = false
    @Volatile private var checking = false
    @Volatile private var menuSeen = 0

    /** State 9's toggle, stored on this Mac and handed to Sparkle. Defaults to **on** (FR-R386-4). */
    var autoDownload: Boolean
        get() = prefs.get("auto_download") != "false"
        set(value) {
            prefs.put("auto_download", value.toString())
            if (DesktopPaths.isMac) MacUpdater.setAutoDownload(value)
            // The toggle answers at once: the stored value is the state (FR-R386-7), not a guess about it.
            publish(null, System.currentTimeMillis())
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
        // FR-R386-9 — *Check for Updates…* was chosen in the Ravilo menu: About opens and the check runs there.
        if (sparkle.menuClicks > menuSeen) { menuSeen = sparkle.menuClicks; AppCommands.send(AppCommand.UPDATE_CHECK) }
        _status.value = _status.value.copy(
            plan = plan ?: _status.value.plan,
            checkedAt = if (plan != null && plan != UpdatePlan.Offline) now else _status.value.checkedAt,
            sparkle = sparkle,
            cannotUpdateFrom = if (DesktopPaths.isMac) placeThatCannotUpdate() else null,
            checking = checking,
        )
        republish()
    }

    /** The block's one value, from the last status and the stored toggle — or the test driver's preview, when it is on. */
    private fun republish() {
        val shown = if (UpdatePreview.active) UpdatePreview.model
        else UpdateView.of(_status.value, autoDownload, raviloVersion(), DesktopPaths.isMac) { UpdateView.checkedAt(it, locale = viewerLocale()) }
        SoftwareUpdate.set(shown)
    }

    private fun viewerLocale(): java.util.Locale = java.util.Locale.forLanguageTag(LastLanguage.read() ?: "en")

    /** The test driver's `update <state>` — see [UpdatePreview]. */
    fun preview(name: String): Boolean = UpdatePreview.set(name).also { if (it) republish() }

    /** The block's controls on a Mac (the common screens call these through [SoftwareUpdate.actions]). */
    private fun installActions() {
        SoftwareUpdate.actions = SoftwareUpdateActions(
            checkNow = ::checkNow,
            restart = ::restartToUpdate,
            download = ::downloadWithSparkleWindow,
            // A failed download starts over: the next check downloads it fresh (FR-R386-7, state 7).
            tryAgain = ::checkNow,
            setAutoDownload = { autoDownload = it },
            quit = { MacDiagnosis.quit?.invoke() },
        )
    }

    fun start(scope: CoroutineScope): Job = scope.launch {
        var askedUrl = ""
        var nextAt = 0L
        var sparkleStarted = false
        var tick = 0
        if (DesktopPaths.isMac) installActions()
        while (isActive) {
            // FR-R386-9 — the Ravilo menu's *Check for Updates…*, kept in place and in the viewer's language (a release
            // build only: where the block is absent, so is the item). Every other second is plenty and is idempotent.
            if (DesktopPaths.isMac && SoftwareUpdate.model.value != null && tick++ % 2 == 0) {
                MacUpdater.installMenuItem(t("mac.update.menu_check", LastLanguage.read() ?: "en"))
            }
            val url = raviloBaseUrl()
            val now = System.currentTimeMillis()
            if (url != askedUrl) { askedUrl = url; nextAt = 0L }
            // FR-R386-5 — Sparkle is not even created where the app cannot update from.
            if (DesktopPaths.isMac && !sparkleStarted && placeThatCannotUpdate() == null) {
                sparkleStarted = true
                MacUpdater.setAutoDownload(autoDownload)
                MacUpdater.start()
            }
            if (url.isNotBlank() && (now >= nextAt || checkRequested)) {
                checkRequested = false
                checking = true
                publish(null, now)   // *Checking for updates…* is on screen for as long as the check asks
                val server = serverVersion(url)
                val override = UpdateRules.feedOverride(System::getenv, System::getProperty)
                val plan = if (override != null && DesktopPaths.isMac) {
                    // The spike: the appcast this build was pointed at stands in for the whole table.
                    UpdatePlan.AskSparkle(null, override)
                } else checkMac(server)
                val guard = if (DesktopPaths.isMac) placeThatCannotUpdate() else null
                if (plan is UpdatePlan.AskSparkle && guard == null) MacUpdater.checkInBackground(plan.feedUrl)
                // Offline keeps the last check's time; it and an unreachable server ask again in a quarter of an hour.
                checking = false
                publish(plan, now)
                nextAt = now + if (server == null || plan == UpdatePlan.Offline) RETRY_MS else DAY_MS
            }
            publish(null, now)
            // One second, not sixty: the snapshot above follows Sparkle's callbacks, and *Check now* answers promptly.
            delay(1_000)
        }
    }
}
