package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.shared.raviloVersion
import dev.jellystructure.shared.tv.RaviloVersion
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.UpdateFirstSeen
import dev.jellystructure.shared.tv.parseRaviloRelease
import dev.jellystructure.shared.tv.raviloUpdateNotice
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * DEB-5851 (FR-3) — the one thing this device remembers about an update: the newest release it has seen the server
 * run, and when it first saw it. Plus, per surface, the release whose dot the viewer has already looked at (FR-5, FR-6, FR-7). A file of its own, apart
 * from the session store, so a sign-out keeps both.
 */
expect object ClientUpdateRecord {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

private const val FIRST_SEEN = "first_seen"

internal fun encodeFirstSeen(v: UpdateFirstSeen?): String? = v?.let { "${it.release}|${it.atMs}" }

internal fun decodeFirstSeen(raw: String?): UpdateFirstSeen? {
    val (release, at) = (raw ?: return null).split('|').takeIf { it.size == 2 } ?: return null
    return UpdateFirstSeen(parseRaviloRelease(release) ?: return null, at.toLongOrNull() ?: return null)
}

/**
 * DEB-5851 (FR-2, FR-3) — the quiet "a newer Ravilo exists" state for Android TV, phone and Linux. [notice] is the
 * release to name, or null. Once Home has drawn, and while no player is open, it asks the server's version at
 * start and every 24 h after; a failed ask keeps the last answer and tries again at the next tick. Server state
 * only: the line is what the last answer and the first-seen record made of it (FR-1).
 */
class ClientUpdateCheck(
    private val appVersion: () -> String,
    private val fetchVersion: suspend () -> String?,
    private val readRecord: () -> String?,
    private val writeRecord: (String?) -> Unit,
    private val nowMs: () -> Long,
    private val playerOpen: StateFlow<Boolean>,
    private val interval: Long = DAY_MS,
) {
    private val _notice = MutableStateFlow<RaviloVersion?>(null)
    val notice: StateFlow<RaviloVersion?> = _notice.asStateFlow()
    private val _homeDrawn = MutableStateFlow(false)

    /** Home has drawn its first content: the first ask may go out. */
    fun homeDrawn() { _homeDrawn.value = true }

    /** Runs until cancelled. Never asks before Home has drawn, nor while a player is open. */
    suspend fun run() {
        _homeDrawn.first { it }
        while (true) {
            playerOpen.first { !it }
            checkOnce()
            delay(interval)
        }
    }

    suspend fun checkOnce() {
        val server = fetchVersion() ?: return
        val outcome = raviloUpdateNotice(appVersion(), server, decodeFirstSeen(readRecord()), nowMs())
        writeRecord(encodeFirstSeen(outcome.record))
        _notice.value = outcome.release
    }

    companion object { const val DAY_MS: Long = 24L * 60 * 60 * 1000 }
}

/** The app-wide instance Android TV, the phone and the Linux desktop share; [start] is called once from the app root. */
object ClientUpdate {
    private var check: ClientUpdateCheck? = null
    private val _notice = MutableStateFlow<RaviloVersion?>(null)
    val notice: StateFlow<RaviloVersion?> = _notice.asStateFlow()
    private var pendingHome = false

    fun homeDrawn() { pendingHome = true; check?.homeDrawn() }

    /** The release each surface's dot was last looked at for; read once from the record, then kept here. */
    private val seen = MutableStateFlow(UpdateSurface.entries.associateWith { ClientUpdateRecord.read(it.key) })

    /** The viewer opened [surface]: its dot stays away until a larger release arrives (the line itself stays). */
    fun markSeen(surface: UpdateSurface) {
        val release = _notice.value?.toString() ?: return
        ClientUpdateRecord.write(surface.key, release)
        seen.value = seen.value + (surface to release)
    }
    /** Tests only. */
    internal fun resetForTest(notice: RaviloVersion?) { _notice.value = notice; seen.value = UpdateSurface.entries.associateWith { null } }
    val seenFlow: StateFlow<Map<UpdateSurface, String?>> get() = seen.asStateFlow()

    /** Suspends for the life of the caller (a `LaunchedEffect`). Never on the Mac or the web (FR-R386, FR-8). */
    suspend fun run(api: TvApiClient, playerOpen: StateFlow<Boolean>, nowMs: () -> Long) {
        val c = ClientUpdateCheck(
            appVersion = { raviloVersion() },
            fetchVersion = { api.serverVersion() },
            readRecord = { ClientUpdateRecord.read(FIRST_SEEN) },
            writeRecord = { ClientUpdateRecord.write(FIRST_SEEN, it) },
            nowMs = nowMs,
            playerOpen = playerOpen,
        )
        check = c
        if (pendingHome) c.homeDrawn()
        coroutineScope {
            val relay = launch { c.notice.collect { _notice.value = it } }
            try { c.run() } finally { relay.cancel(); check = null }
        }
    }
}

/** FR-5 to FR-7 — a surface's dot shows while there is a notice and the viewer has not opened that surface for this release. */
fun updateDotFor(notice: RaviloVersion?, seen: String?): Boolean = notice != null && seen != notice.toString()

/** The surfaces that carry a dot. */
enum class UpdateSurface(val key: String) { Profile("dot_profile"), TvSettings("dot_tv_settings"), LinuxAbout("dot_linux_about") }

/** FR-5 — the releases page a download install is sent to; Play's own page for a Play install. */
const val RAVILO_RELEASES_URL = "https://github.com/jeggy/jellystructure/releases/latest"
fun clientUpdateUrl(source: InstallSource, packageName: String = ownPackageName()): String =
    if (source == InstallSource.GooglePlay && packageName.isNotBlank()) "https://play.google.com/store/apps/details?id=$packageName" else RAVILO_RELEASES_URL

/** FR-4 — the second line's key for [source], and Samsung's own. */
fun clientUpdateSecondLineKey(source: InstallSource): String =
    if (source == InstallSource.GooglePlay) "update.available_play" else "update.available_download"
