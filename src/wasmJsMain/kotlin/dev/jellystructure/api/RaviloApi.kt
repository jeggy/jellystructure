package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─── Ravilo config DTOs (admin frontend mirror of shared/tv Models) ───────────

@Serializable
data class AdminRaviloConfig(
    val heroes: List<AdminHeroConfig> = emptyList(),
    val channels: List<AdminChannelConfig> = emptyList(),
    val rows: List<AdminRowConfig> = emptyList(),
    @SerialName("merge_newly_added") val mergeNewlyAdded: Boolean = false,
    @SerialName("default_skin") val defaultSkin: String = "AURORA",
    @SerialName("allow_skin_override") val allowSkinOverride: Boolean = true,
    @SerialName("show_continue_progress") val showContinueProgress: Boolean = true,
    @SerialName("tile_shape") val tileShape: String = "POSTER",
    @SerialName("ui_language") val uiLanguage: String = "en",
)

@Serializable
data class AdminHeroConfig(
    @SerialName("item_id") val itemId: String = "",
    @SerialName("item_title") val itemTitle: String = "",
)

@Serializable
data class AdminChannelConfig(
    val label: String = "",
    val kind: String = "GENRE",
    val filter: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    val color: String? = null,
)

@Serializable
data class AdminRowConfig(
    val label: String = "",
    val kind: String = "GENRE",
    val filter: String = "",
    val hidden: Boolean = false,
)

@Serializable
data class JellyfinUser(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

object RaviloApi {
    suspend fun getUsers(): List<JellyfinUser> =
        httpClient.get("/api/jellyfin/users").body()

    suspend fun getConfig(userId: String): AdminRaviloConfig =
        httpClient.get("/api/tv/admin/config?userId=$userId").body()

    suspend fun putConfig(userId: String, config: AdminRaviloConfig) {
        httpClient.put("/api/tv/admin/config?userId=$userId") {
            contentType(ContentType.Application.Json)
            setBody(config)
        }
    }
}
