package dev.jellystructure.api

import dev.jellystructure.model.PublishActionResult
import dev.jellystructure.model.PublishIdsRequest
import dev.jellystructure.model.PublishListDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/** Phase 307 — the publish queue: the panel reads one list; every press is one call (nothing is decided here). */
object PublishApi {
    suspend fun list(): PublishListDto? = runCatching { httpClient.get("/api/publish/items").body<PublishListDto>() }.getOrNull()

    private suspend fun post(path: String, ids: List<Long>? = null): PublishActionResult? = runCatching {
        val r = httpClient.post(path) { if (ids != null) { contentType(ContentType.Application.Json); setBody(PublishIdsRequest(ids)) } }
        if (r.status.isSuccess()) r.body<PublishActionResult>() else null
    }.getOrNull()

    suspend fun publish(id: Long) = post("/api/publish/items/$id/publish")
    suspend fun publishAll(ids: List<Long>) = post("/api/publish/items/publish", ids)
    suspend fun dismiss(id: Long) = post("/api/publish/items/$id/dismiss")
    suspend fun dismissAll(ids: List<Long>) = post("/api/publish/items/dismiss", ids)
    suspend fun tryAgain(id: Long) = post("/api/publish/items/$id/try-again")
    suspend fun queueAgain(id: Long) = post("/api/publish/items/$id/queue-again")
}
