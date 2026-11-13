package dev.jellystructure.api

import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvChannelUpdate
import dev.jellystructure.shared.tv.LiveTvOverview
import dev.jellystructure.shared.tv.LiveTvReorderRequest
import dev.jellystructure.shared.tv.LiveTvSettingsUpdate
import io.ktor.client.call.body
import io.ktor.client.request.put
import io.ktor.client.request.post
import io.ktor.client.request.get
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/** Phase 147 — admin API client for the Live TV connection/lineup/settings page. */
object LiveTvApi {
    suspend fun overview(): LiveTvOverview? = runCatching { httpClient.get("/api/tv/admin/livetv/overview").body<LiveTvOverview>() }.getOrNull()

    suspend fun sync(): LiveTvOverview? = runCatching { httpClient.post("/api/tv/admin/livetv/sync").body<LiveTvOverview>() }.getOrNull()

    suspend fun channels(): List<LiveTvChannel> = runCatching { httpClient.get("/api/tv/admin/livetv/channels").body<List<LiveTvChannel>>() }.getOrDefault(emptyList())

    suspend fun updateChannel(channelId: String, update: LiveTvChannelUpdate): List<LiveTvChannel> =
        httpClient.put("/api/tv/admin/livetv/channels/$channelId") {
            contentType(ContentType.Application.Json)
            setBody(update)
        }.body()

    suspend fun reorder(channelIds: List<String>): List<LiveTvChannel> =
        httpClient.post("/api/tv/admin/livetv/channels/reorder") {
            contentType(ContentType.Application.Json)
            setBody(LiveTvReorderRequest(channelIds))
        }.body()

    suspend fun showNew(): List<LiveTvChannel> = httpClient.post("/api/tv/admin/livetv/bulk/show-new").body()

    suspend fun removeMissing(): List<LiveTvChannel> = httpClient.post("/api/tv/admin/livetv/bulk/remove-missing").body()

    suspend fun updateSettings(enabled: Boolean? = null): LiveTvOverview =
        httpClient.put("/api/tv/admin/livetv/settings") {
            contentType(ContentType.Application.Json)
            setBody(LiveTvSettingsUpdate(enabled))
        }.body()

    /** DEB-4387 (FR-5.1) — the guide refresh's schedule. A refusal (422) carries the server's sentence of what is wrong; [overview] is null then. */
    suspend fun saveGuideSchedule(schedule: String): GuideScheduleSave = runCatching {
        val response = httpClient.put("/api/tv/admin/livetv/settings") {
            contentType(ContentType.Application.Json)
            setBody(LiveTvSettingsUpdate(epgSchedule = schedule))
        }
        when (response.status) {
            HttpStatusCode.OK -> GuideScheduleSave(response.body<LiveTvOverview>(), null)
            HttpStatusCode.UnprocessableEntity -> GuideScheduleSave(null, runCatching { response.body<GuideScheduleError>().error }.getOrNull() ?: "Invalid schedule")
            else -> GuideScheduleSave(null, "Couldn't save")
        }
    }.getOrDefault(GuideScheduleSave(null, "Couldn't save"))
}

class GuideScheduleSave(val overview: LiveTvOverview?, val error: String?)

@Serializable
private data class GuideScheduleError(val error: String? = null)
