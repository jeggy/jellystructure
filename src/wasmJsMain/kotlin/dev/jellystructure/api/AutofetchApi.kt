package dev.jellystructure.api

import dev.jellystructure.ui.AfOverview
import dev.jellystructure.ui.AfPreview
import dev.jellystructure.ui.AfRetention
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
private data class SettingsBody(val retention: AfRetention? = null, val schedule: String? = null, val enabled: Boolean? = null)

@Serializable
private data class PreviewBody(val retention: AfRetention)

@Serializable
private data class MomentBody(val at: Int)

/** DEB-2477 (DEB-2474, FR-12) — the Fetchers tab's calls. Every write answers with what the server says, and the tab draws that. */
object AutofetchApi {
    /** What a write came back with: [error] is the server's own sentence when it refused. */
    data class Outcome(val ok: Boolean, val error: String? = null)

    suspend fun overview(): AfOverview? = runCatching {
        httpClient.get("/api/autofetch").takeIf { it.status == HttpStatusCode.OK }?.body<AfOverview>()
    }.getOrNull()

    suspend fun run(id: String): Outcome = outcome { httpClient.post("/api/autofetch/$id/run") }
    suspend fun runAll(): Outcome = outcome { httpClient.post("/api/autofetch/run-all") }
    suspend fun resume(site: String): Outcome = outcome { httpClient.post("/api/autofetch/sites/$site/resume") }
    suspend fun delete(id: String): Outcome = outcome { httpClient.delete("/api/autofetch/$id") }
    suspend fun submit(queueId: Long): Outcome = outcome { httpClient.post("/api/autofetch/queue/$queueId/submitted") }

    suspend fun settings(id: String, retention: AfRetention?, schedule: String?, enabled: Boolean?): Outcome = outcome {
        httpClient.patch("/api/autofetch/$id") { contentType(ContentType.Application.Json); setBody(SettingsBody(retention, schedule, enabled)) }
    }

    suspend fun cutStill(queueId: Long, n: Int, at: Int): Outcome = outcome {
        httpClient.post("/api/autofetch/queue/$queueId/still/$n") { contentType(ContentType.Application.Json); setBody(MomentBody(at)) }
    }

    /** What saving [retention] would delete at the next run, counted by the server (FR-16.1). Null when it did not answer. */
    suspend fun preview(id: String, retention: AfRetention): AfPreview? = runCatching {
        httpClient.post("/api/autofetch/$id/retention/preview") { contentType(ContentType.Application.Json); setBody(PreviewBody(retention)) }
            .takeIf { it.status == HttpStatusCode.OK }?.body<AfPreview>()
    }.getOrNull()

    private suspend fun outcome(call: suspend () -> HttpResponse): Outcome {
        val r = try { call() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { return Outcome(false, "The server did not answer.") }
        if (r.status.value in 200..299) return Outcome(true)
        val text = runCatching { r.bodyAsText() }.getOrDefault("")
        val msg = Regex(""""error"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(text)?.groupValues?.get(1)?.replace("\\\"", "\"")
        return Outcome(false, msg ?: "The server refused (${r.status.value}).")
    }
}
