package dev.jellystructure.api

import dev.jellystructure.shared.tv.ChannelLogo
import dev.jellystructure.shared.tv.ChannelLogoUpload
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.ResolvedBehaviour
import dev.jellystructure.shared.tv.ViewerSettingsRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The Ravilo layout DTOs are defined once in `:shared` (`dev.jellystructure.shared.tv`) and reused by
// the backend, the TV client, and this admin frontend (Constitution Invariant 2). This file only
// adds the admin-only transport types that are not part of the shared layout model.

@Serializable
data class JellyfinUser(
    val id: String,
    @SerialName("display_name") val displayName: String,
)

/** R51 — GET /tv/admin/config response envelope. */
@Serializable
data class AdminConfigResponse(
    val config: RaviloConfig,
    @SerialName("hasOverride") val hasOverride: Boolean = false,
    @SerialName("isGlobal") val isGlobal: Boolean = false,
)

object RaviloApi {
    suspend fun getUsers(): List<JellyfinUser> =
        httpClient.get("/api/jellyfin/users").body()

    suspend fun getConfig(userId: String): RaviloConfig =
        httpClient.get("/api/tv/admin/config?userId=$userId").body<AdminConfigResponse>().config

    suspend fun getConfigWithMeta(scope: String? = null, userId: String? = null): AdminConfigResponse {
        val q = when {
            scope == "global" -> "?scope=global"
            userId != null -> "?userId=$userId"
            else -> ""
        }
        return httpClient.get("/api/tv/admin/config$q").body()
    }

    suspend fun removeUserConfig(userId: String) {
        val r = httpClient.delete("/api/tv/admin/config?userId=$userId")
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
    }

    // R162 — the field-level behaviour & preferences overlay, independent of the layout config above.
    suspend fun getBehaviour(userId: String): ResolvedBehaviour =
        httpClient.get("/api/tv/admin/behaviour?userId=$userId").body()

    suspend fun setBehaviour(userId: String, req: ViewerSettingsRequest): ResolvedBehaviour {
        val r = httpClient.put("/api/tv/admin/behaviour?userId=$userId") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
        return r.body()
    }

    suspend fun resetBehaviourField(userId: String, field: String): ResolvedBehaviour {
        val r = httpClient.delete("/api/tv/admin/behaviour?userId=$userId&field=$field")
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
        return r.body()
    }

    suspend fun putConfig(userId: String, config: RaviloConfig) {
        val r = httpClient.put("/api/tv/admin/config?userId=$userId") {
            contentType(ContentType.Application.Json)
            setBody(config)
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
    }

    suspend fun putGlobalConfig(config: RaviloConfig) {
        val r = httpClient.put("/api/tv/admin/config?scope=global") {
            contentType(ContentType.Application.Json)
            setBody(config)
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
    }

    suspend fun listChannelLogos(): List<ChannelLogo> =
        httpClient.get("/api/tv/admin/channel-logos").body()

    suspend fun uploadChannelLogo(filename: String, dataBase64: String): ChannelLogo {
        val r = httpClient.post("/api/tv/admin/channel-logos") {
            contentType(ContentType.Application.Json)
            setBody(ChannelLogoUpload(filename, dataBase64))
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
        return r.body()
    }

    suspend fun approvePairing(code: String) {
        val r = httpClient.post("/api/tv/pair/approve") {
            contentType(ContentType.Application.Json)
            setBody("""{"code":${code.jsonQuote()}}""")
        }
        if (!r.status.isSuccess()) throw Exception(r.body<String>())
    }
}

private fun String.jsonQuote() = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
