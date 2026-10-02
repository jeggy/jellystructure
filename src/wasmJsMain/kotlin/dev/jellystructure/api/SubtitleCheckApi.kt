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

/** A language jellystructure emptied because every subtitle for it was wrong (rather nothing than wrong). */
@Serializable
data class EmptyLanguage(
    @SerialName("video_path") val videoPath: String,
    val language: String? = null,
    val tried: Int = 0,
    val words: String = "",
)

@Serializable
data class TitleSubtitleChecks(
    val checks: List<SubtitleCheck> = emptyList(),
    val waiting: List<SubtitleAction> = emptyList(),
    val actions: List<SubtitleAction> = emptyList(),
    val empty: List<EmptyLanguage> = emptyList(),
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
    @SerialName("fix_would") val fixWould: FixWould? = null,
)

/** What *Fix it* would do with today's verdicts, while the switch says *Only report*. */
@Serializable
data class FixWould(val sync: Int = 0, val replace: Int = 0, val move: Int = 0, val ask: Int = 0, val hide: Int = 0)

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

/** Phase 302 — one cause and its count (mirrors the backend's `FitGroupDto`). */
@Serializable
data class FitGroupInfo(
    val id: String,
    val label: String,
    val sentence: String = "",
    val action: String = "",
    val count: Int = 0,
    val severity: String = "warning",
    val running: FitRunInfo? = null,
)

@Serializable
data class FitRunInfo(val total: Int = 0, val done: Int = 0, @SerialName("not_done") val notDone: Int = 0)

@Serializable
data class FitRow(
    @SerialName("sidecar_path") val sidecarPath: String,
    val name: String,
    @SerialName("item_id") val itemId: String,
    val title: String,
    val episode: String? = null,
    val language: String? = null,
    val words: String,
    val against: String? = null,
    val offered: Boolean = true,
    @SerialName("video_ms") val videoMs: Long? = null,
    @SerialName("runtime_min") val runtimeMin: Int? = null,
)

@Serializable
data class SubtitleFit(val groups: List<FitGroupInfo> = emptyList(), val group: String? = null, val rows: List<FitRow> = emptyList(), val total: Int = 0)

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

    /** Phase 302 — the causes, and one cause's subtitles. */
    suspend fun fit(group: String?): SubtitleFit? = runCatching {
        httpClient.get("/api/subtitles/fit") { url { if (group != null) parameters.append("group", group) } }.body<SubtitleFit>()
    }.getOrNull()

    /** Phase 302 — fix a whole group through Bazarr; the number queued, or null (already running, no Bazarr). */
    suspend fun fixGroup(group: String): Int? = runCatching {
        val r = httpClient.post("/api/subtitles/fit/$group/fix")
        if (r.status.isSuccess()) r.body<Map<String, Int>>()["queued"] else null
    }.getOrNull()

    suspend fun apply(findingId: String): BazarrApplyResult? = runCatching {
        httpClient.post("/api/bazarr/advisor/$findingId/apply").body<BazarrApplyResult>()
    }.getOrNull()
}
