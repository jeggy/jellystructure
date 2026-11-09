package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOptions
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.DownloadTicket
import kotlinx.coroutines.flow.StateFlow

/** The two routes the phone calls (FR-4). [dev.jellystructure.shared.tv.TvApiClient] is the real one; tests fake it. */
interface DownloadApi {
    suspend fun options(itemId: String, mediaSourceId: String?): DownloadOptions
    suspend fun start(request: DownloadStartRequest): DownloadTicket
}

/** Stable id of one viewer's download: the engine's key, so two profiles on a phone never share one. */
fun downloadId(userId: String, itemId: String): String = "$userId|$itemId"

fun downloadItemId(downloadId: String): String = downloadId.substringAfter('|')

fun downloadUserId(downloadId: String): String = downloadId.substringBefore('|')

/** One ticket handed to the engine. A second job with the same [id] is a **fresh ticket for a download already there**: it resumes where it stopped. */
data class DownloadJob(
    val id: String,
    val record: DownloadRecord,
    val ticket: DownloadTicket,
    /** The server's base URL, for a ticket whose URL is server-relative (a smaller copy's playlist). */
    val baseUrl: String,
)

/**
 * The platform half (FR-7): Media3's `DownloadManager` on the Android phone, nothing anywhere else. The engine runs **one download at
 * a time** in the order enqueued, only on an unmetered network while Wi-Fi only is on, and keeps its files under `filesDir`.
 */
interface DownloadEngine {
    /** The phase of every download this engine holds, by [downloadId]. Includes other profiles' (the controller filters). */
    val phases: StateFlow<Map<String, DownloadPhase>>
    fun enqueue(job: DownloadJob)
    fun pause(id: String)
    fun resume(id: String)
    /** Cancel a running download or delete a finished one; the space comes back at once. */
    fun remove(id: String)
    /** Bytes free on the volume `filesDir` is on. */
    fun freeBytes(): Long
    /** Size of that volume: the storage bar's whole (FR-9). 0 when it cannot be read. */
    fun totalBytes(): Long
    /** The ids the engine is running or has queued (not paused, not finished). */
    fun busy(): Boolean
}

/** FR-17 — device-local: depends on this phone's network and habits, not the viewer. Never synced, never in the server config. */
interface DownloadPrefs {
    var wifiOnly: Boolean
    fun lastQuality(kind: String): String?
    fun setLastQuality(kind: String, quality: String)
}

class InMemoryDownloadPrefs(override var wifiOnly: Boolean = true) : DownloadPrefs {
    private val q = HashMap<String, String>()
    override fun lastQuality(kind: String) = q[kind]
    override fun setLastQuality(kind: String, quality: String) { q[kind] = quality }
}
