package dev.jellystructure.ravilo.ui.downloads

/**
 * DEB-4070 (DEB-3995 FR-7, FR-8) — where one download is, as the phone's own disk and queue know it. Device state, not catalog
 * state (the spec's constitution reading): nothing here is sent anywhere.
 */
sealed interface DownloadPhase {
    /** Not on this phone, not asked for. */
    data object None : DownloadPhase
    /** Asked for and waiting its turn: the queue runs one download at a time, in the order they were asked for. */
    data object Queued : DownloadPhase
    data class Running(val percent: Int) : DownloadPhase
    data class Paused(val percent: Int) : DownloadPhase
    /** Wi-Fi only is on and no unmetered network is up (FR-7). */
    data object WaitingWifi : DownloadPhase
    /** The server is playing something and a smaller copy yields (FR-6): `503 waiting`, resumes on its own. */
    data object WaitingServer : DownloadPhase
    data object Downloaded : DownloadPhase
    /** [httpStatus] is the answer that ended it when there was one (a ticket that expired is 401/403/410). */
    data class Failed(val httpStatus: Int? = null) : DownloadPhase
}

/** FR-8 — the control's five states. A waiting or paused download keeps the control's word for where it is. */
enum class ControlState { DOWNLOAD, QUEUED, PERCENT, DOWNLOADED, RETRY }

fun DownloadPhase.controlState(): ControlState = when (this) {
    DownloadPhase.None -> ControlState.DOWNLOAD
    DownloadPhase.Queued, DownloadPhase.WaitingWifi, DownloadPhase.WaitingServer -> ControlState.QUEUED
    is DownloadPhase.Running, is DownloadPhase.Paused -> ControlState.PERCENT
    DownloadPhase.Downloaded -> ControlState.DOWNLOADED
    is DownloadPhase.Failed -> ControlState.RETRY
}

/** The progress that fills the control (0 to 100); 0 when the state has none. */
fun DownloadPhase.percent(): Int = when (this) {
    is DownloadPhase.Running -> percent
    is DownloadPhase.Paused -> percent
    else -> 0
}.coerceIn(0, 100)

/** The line that says why a download is not moving, as a string key; null when nothing needs saying (FR-6, FR-7, FR-8). */
fun DownloadPhase.statusKey(): String? = when (this) {
    DownloadPhase.WaitingWifi -> "download.waiting_wifi"
    DownloadPhase.WaitingServer -> "download.waiting_server"
    is DownloadPhase.Failed -> "download.failed"
    else -> null
}

/** Whether the phase still holds a place in the queue (a delete or cancel has something to remove). */
fun DownloadPhase.isActive(): Boolean = when (this) {
    DownloadPhase.None, DownloadPhase.Downloaded, is DownloadPhase.Failed -> false
    else -> true
}
