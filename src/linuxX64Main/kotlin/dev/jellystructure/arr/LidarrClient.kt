package dev.jellystructure.arr

import dev.jellystructure.OutboundHttp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Phase 284 (dev review 4) — Lidarr, read-only, the way `[radarr]`/`[sonarr]` are: never a write to its library, only
 * three reads and one best-effort rescan. Lidarr manages the same folder jellystructure tags, and the two must not
 * fight: what it will rewrite ([tagging]), whether it writes an `album.nfo` of its own, which release it matched an
 * album to ([album]), and a nudge to re-read a folder after a write ([rescanFolders]) so its row agrees before its
 * own watcher notices. Lidarr speaks `/api/v1`, not Radarr's `/api/v3`.
 */
class LidarrClient(private val http: HttpClient = OutboundHttp.client, private val json: Json = Json { ignoreUnknownKeys = true }) {
    private fun base(url: String) = url.trimEnd('/') + "/api/v1"

    private suspend fun get(url: String, apiKey: String, path: String) = OutboundHttp.withPermit {
        http.get(base(url) + path) { header("X-Api-Key", apiKey) }
    }

    suspend fun ping(url: String, apiKey: String): ArrPing = runCatching {
        val r = get(url, apiKey, "/system/status")
        if (r.status == HttpStatusCode.Unauthorized) return@runCatching ArrPing(false, "Reachable, but the API key was rejected")
        if (r.status != HttpStatusCode.OK) return@runCatching ArrPing(false, "HTTP ${r.status.value}")
        val v = runCatching { json.parseToJsonElement(r.bodyAsText()).jsonObject["version"]?.jsonPrimitive?.content }.getOrNull()
        ArrPing(true, "Connected", v)
    }.getOrElse { ArrPing(false, it.message ?: "Unknown error") }

    suspend fun rootFolders(url: String, apiKey: String): List<String> = runCatching {
        val r = get(url, apiKey, "/rootfolder")
        if (r.status != HttpStatusCode.OK) return@runCatching emptyList()
        json.parseToJsonElement(r.bodyAsText()).jsonArray.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.content }
    }.getOrDefault(emptyList())

    /** What Lidarr does to the files it manages — the two settings that make it a second writer. */
    @Serializable
    data class Tagging(
        /** `no` · `newFiles` · `allFiles` · `sync` — anything but `no`/`newFiles` rewrites what jellystructure wrote. */
        @SerialName("writeAudioTags") val writeAudioTags: String = "no",
        @SerialName("scrubAudioTags") val scrubAudioTags: Boolean = false,
        @SerialName("embedCoverArt") val embedCoverArt: Boolean = false,
    )

    suspend fun tagging(url: String, apiKey: String): Tagging? = runCatching {
        val r = get(url, apiKey, "/config/metadataprovider")
        if (r.status == HttpStatusCode.OK) r.body<Tagging>() else null
    }.getOrNull()

    /** The names of the metadata consumers Lidarr has switched on (`Kodi (XBMC) / Emby` writes `album.nfo`/`artist.nfo`). */
    suspend fun enabledConsumers(url: String, apiKey: String): List<String>? = runCatching {
        val r = get(url, apiKey, "/metadata")
        if (r.status != HttpStatusCode.OK) return@runCatching null
        json.parseToJsonElement(r.bodyAsText()).jsonArray.mapNotNull { m ->
            val o = m.jsonObject
            if (o["enable"]?.jsonPrimitive?.content == "true") o["name"]?.jsonPrimitive?.content else null
        }
    }.getOrNull()

    /** The Lidarr album for a MusicBrainz release group, with the release it chose — or null when it does not manage it. */
    data class ManagedAlbum(val id: Int, val title: String, val releaseTitle: String?, val monitored: Boolean)

    suspend fun album(url: String, apiKey: String, releaseGroupMbid: String): ManagedAlbum? = runCatching {
        val r = get(url, apiKey, "/album?foreignAlbumId=$releaseGroupMbid")
        if (r.status != HttpStatusCode.OK) return@runCatching null
        val o = json.parseToJsonElement(r.bodyAsText()).jsonArray.firstOrNull()?.jsonObject ?: return@runCatching null
        val chosen = o["releases"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["monitored"]?.jsonPrimitive?.content == "true" }
        ManagedAlbum(
            id = o["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            title = o["title"]?.jsonPrimitive?.content.orEmpty(),
            releaseTitle = chosen?.let { c -> listOfNotNull(c["title"]?.jsonPrimitive?.content, c["country"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content).joinToString(" · ").ifBlank { null } },
            monitored = o["monitored"]?.jsonPrimitive?.content == "true",
        )
    }.getOrNull()

    /** Best-effort, like the *arr rescans: `RescanFolders` for the album folders just written. */
    suspend fun rescanFolders(url: String, apiKey: String, folders: List<String>): Boolean = runCatching {
        if (folders.isEmpty()) return@runCatching false
        val payload = buildJsonObject { put("name", "RescanFolders"); put("folders", buildJsonArray { folders.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }
        val r = OutboundHttp.withPermit {
            http.post(base(url) + "/command") { header("X-Api-Key", apiKey); contentType(ContentType.Application.Json); setBody(payload.toString()) }
        }
        r.status == HttpStatusCode.Created || r.status == HttpStatusCode.OK
    }.getOrDefault(false)
}
