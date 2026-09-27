package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Phase 273 — one sidecar's verdict (mirrors the backend's `SubtitleCheckDto`). */
@Serializable
data class SubtitleCheck(
    @SerialName("sidecar_path") val sidecarPath: String,
    val name: String,
    @SerialName("video_path") val videoPath: String,
    val language: String? = null,
    val hi: Boolean = false,
    val verdict: String,
    val reason: String? = null,
    val words: String,
    val offered: Boolean = true,
    val reference: String? = null,
    val rho: Double? = null,
    val z: Double? = null,
    @SerialName("shift_ms") val shiftMs: Long? = null,
    val scale: Double? = null,
    @SerialName("worst_ms") val worstMs: Long? = null,
    @SerialName("match_label") val matchLabel: String? = null,
    @SerialName("checked_at") val checkedAt: Long = 0,
)

@Serializable
data class SubtitleAction(
    val id: Long,
    val action: String,
    val state: String,
    @SerialName("sidecar_name") val sidecarName: String? = null,
    @SerialName("video_path") val videoPath: String = "",
    val language: String? = null,
    val detail: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

@Serializable
data class TitleSubtitleChecks(
    val checks: List<SubtitleCheck> = emptyList(),
    val waiting: List<SubtitleAction> = emptyList(),
    val actions: List<SubtitleAction> = emptyList(),
)

@Serializable
data class SubtitleSummary(
    val mode: String = "report",
    val counts: Map<String, Long> = emptyMap(),
    @SerialName("not_offered") val notOffered: Int = 0,
    val waiting: List<SubtitleAction> = emptyList(),
    @SerialName("downloads_today") val downloadsToday: Long = 0,
    @SerialName("daily_budget") val dailyBudget: Int = 100,
    @SerialName("hook_last_called_at") val hookLastCalledAt: Long? = null,
    @SerialName("bazarr_connected") val bazarrConnected: Boolean = false,
    val recent: List<SubtitleAction> = emptyList(),
)

@Serializable
data class SubtitleCheckSettings(
    val action: String = "report",
    @SerialName("daily_download_budget") val dailyDownloadBudget: Int = 100,
    @SerialName("bazarr_reach_url") val bazarrReachUrl: String = "",
)

@Serializable
data class BazarrAdvice(
    val configured: Boolean = false,
    val reachable: Boolean = false,
    val findings: List<AdvisorFinding> = emptyList(),
    @SerialName("hook_command") val hookCommand: String? = null,
    @SerialName("hook_last_called_at") val hookLastCalledAt: Long? = null,
)

@Serializable
data class BazarrApplyResult(val applied: Boolean = false, val message: String = "")

/** Phase 273 — the subtitle check's admin routes. */
object SubtitleCheckApi {
    suspend fun forTitle(itemId: String): TitleSubtitleChecks? = runCatching {
        httpClient.get("/api/subtitles/checks") { url { parameters.append("item", itemId) } }.body<TitleSubtitleChecks>()
    }.getOrNull()

    suspend fun summary(): SubtitleSummary? = runCatching { httpClient.get("/api/subtitles/summary").body<SubtitleSummary>() }.getOrNull()

    suspend fun approve(id: Long): Boolean = runCatching { httpClient.post("/api/subtitles/actions/$id/approve").status.isSuccess() }.getOrDefault(false)

    suspend fun dismiss(id: Long): Boolean = runCatching { httpClient.post("/api/subtitles/actions/$id/dismiss").status.isSuccess() }.getOrDefault(false)

    suspend fun settings(): SubtitleCheckSettings? = runCatching { httpClient.get("/api/subtitles/settings").body<SubtitleCheckSettings>() }.getOrNull()

    suspend fun saveSettings(s: SubtitleCheckSettings): SubtitleCheckSettings? = runCatching {
        httpClient.put("/api/subtitles/settings") { contentType(ContentType.Application.Json); setBody(s) }.body<SubtitleCheckSettings>()
    }.getOrNull()

    suspend fun advice(fresh: Boolean = false): BazarrAdvice? = runCatching {
        httpClient.get("/api/bazarr/advisor") { url { if (fresh) parameters.append("fresh", "true") } }.body<BazarrAdvice>()
    }.getOrNull()

    suspend fun apply(findingId: String): BazarrApplyResult? = runCatching {
        httpClient.post("/api/bazarr/advisor/$findingId/apply").body<BazarrApplyResult>()
    }.getOrNull()
}
