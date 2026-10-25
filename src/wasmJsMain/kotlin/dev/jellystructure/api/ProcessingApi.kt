package dev.jellystructure.api

import dev.jellystructure.encodeURIComponent
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

// DEB-1664 (FR-12) — `GET /api/processing`: the nine features with every value and every sentence built on the server.
// Mirrors `media/ProcessingState.kt`'s views field for field; the defaults match the server's, because the server leaves a
// default-valued field out of the body.

@Serializable
data class ProcessingPartView(val id: String, val name: String, val on: Boolean = false, val canRun: Boolean = true, val reason: String? = null)

@Serializable
data class ProcessingFeatureView(
    val id: String,
    val name: String,
    val queuesWork: Boolean = false,
    val enabled: Boolean = false,
    /** Shown on and disabled (Find new & changed). */
    val locked: Boolean = false,
    val canRun: Boolean = true,
    /** `on` | `off` | `unavailable` | `running` | `waiting` (held for a TV). */
    val state: String = "on",
    val reason: String? = null,
    val interval: String = "",
    val lastRanAt: Long? = null,
    val lastRan: String = "",
    val nextAt: Long? = null,
    val next: String = "",
    val waiting: Int = 0,
    val waitingText: String = "",
    val parts: List<ProcessingPartView> = emptyList(),
)

@Serializable
data class ProcessingView(
    val features: List<ProcessingFeatureView> = emptyList(),
    val holdingForPlayback: Boolean = false,
    /** FR-4.2 — which device is playing while the household switch holds; null when nothing is being held. */
    val holdingText: String? = null,
)

// DEB-1664 (FR-5, FR-6, FR-9.1) — `GET /api/processing/scan`: the Scan dialog, every sentence built on the server; `POST /api/processing/scan`
// starts the run. Mirrors `media/ScanDialog.kt` field for field, with the server's defaults.

@Serializable
data class ScanPartView(
    val id: String,
    val name: String,
    val queuesWork: Boolean = false,
    val canRun: Boolean = true,
    val reason: String? = null,
    val settingsLink: Boolean = false,
)

@Serializable
data class ScanFeatureView(
    val id: String,
    val name: String,
    val queuesWork: Boolean = false,
    val locked: Boolean = false,
    val applies: Boolean = true,
    val canRun: Boolean = true,
    val tickId: String? = null,
    val lede: String = "",
    val short: String? = null,
    val reasonHead: String? = null,
    val reason: String? = null,
    val settingsLink: Boolean = false,
    val parts: List<ScanPartView> = emptyList(),
)

@Serializable
data class ScanDialogView(
    val title: String = "Scan library",
    val lede: String = "",
    val ledePhone: String = "",
    val scopeName: String? = null,
    val scopeLibraryId: String? = null,
    val recheckOff: String = "",
    val recheckOn: String = "",
    val recheckNote: String = "",
    val legendHead: String = "",
    val legend: String = "",
    val features: List<ScanFeatureView> = emptyList(),
)

@Serializable
data class ScanRunRequest(val library: String? = null, val skip: List<String> = emptyList(), val recheckAll: Boolean = false)

@Serializable
data class ScanRunStarted(
    val status: String = "started",
    val jobId: String = "",
    val library: String = "all",
    val skipped: List<String> = emptyList(),
    val skippedText: String = "",
    val skippedNote: String = "",
)

/** The feature ids a door can ask the dialog to open on (the server's ids, `ProcessingFeatures`). */
object ScanFeatureIds {
    const val MUSIC = "music"
    const val AUDIOBOOKS = "audiobooks"
}

/** What starting a scan came to: the run, a scan already going, or nothing at all. */
sealed class ScanStart {
    data class Started(val run: ScanRunStarted) : ScanStart()
    data object Busy : ScanStart()
    data object Failed : ScanStart()
}

object ProcessingApi {
    suspend fun get(): ProcessingView? = runCatching { httpClient.get("/api/processing").body<ProcessingView>() }.getOrNull()

    /** The dialog for everything, or for one library (`library` = its Jellyfin id); null when it cannot be read. */
    suspend fun scanDialog(library: String? = null): ScanDialogView? = runCatching {
        val response = httpClient.get(if (library == null) "/api/processing/scan" else "/api/processing/scan?library=${encodeURIComponent(library)}")
        if (response.status == HttpStatusCode.OK) response.body<ScanDialogView>() else null
    }.getOrNull()

    suspend fun startScan(request: ScanRunRequest): ScanStart = runCatching {
        val response = httpClient.post("/api/processing/scan") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        when (response.status) {
            HttpStatusCode.Accepted -> ScanStart.Started(response.body<ScanRunStarted>())
            HttpStatusCode.Conflict -> ScanStart.Busy
            else -> ScanStart.Failed
        }
    }.getOrDefault(ScanStart.Failed)
}
