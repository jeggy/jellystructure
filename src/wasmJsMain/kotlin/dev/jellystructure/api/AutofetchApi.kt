package dev.jellystructure.api

import dev.jellystructure.ui.AfInvestigation
import dev.jellystructure.ui.AfOverview
import dev.jellystructure.ui.AfPreview
import dev.jellystructure.ui.AfRetention
import dev.jellystructure.ui.afUrlEncode
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
private data class SettingsBody(val retention: AfRetention? = null, val schedule: String? = null, val enabled: Boolean? = null, val ignoreRobots: Boolean? = null)

@Serializable
private data class PreviewBody(val retention: AfRetention)

@Serializable
private data class MomentBody(val at: Int)

@Serializable
private data class InvestigateBody(val url: String = "", val tmdbId: Int? = null, val pageOnly: Boolean = false, val fetcherId: String? = null, val ignoreRobots: Boolean = false)

@Serializable
private data class SaveInvestigationBody(
    val name: String? = null, val libraryPath: String? = null, val schedule: String? = null, val retention: AfRetention? = null, val runFirst: Boolean = true,
    val accept: List<String> = emptyList(),
)

@Serializable
private data class NumberBody(val season: Int, val episode: Int)

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

    suspend fun settings(id: String, retention: AfRetention?, schedule: String?, enabled: Boolean?, ignoreRobots: Boolean? = null): Outcome = outcome {
        httpClient.patch("/api/autofetch/$id") { contentType(ContentType.Application.Json); setBody(SettingsBody(retention, schedule, enabled, ignoreRobots)) }
    }

    suspend fun cutStill(queueId: Long, n: Int, at: Int): Outcome = outcome {
        httpClient.post("/api/autofetch/queue/$queueId/still/$n") { contentType(ContentType.Application.Json); setBody(MomentBody(at)) }
    }

    /** What saving [retention] would delete at the next run, counted by the server (FR-16.1). Null when it did not answer. */
    suspend fun preview(id: String, retention: AfRetention): AfPreview? = runCatching {
        httpClient.post("/api/autofetch/$id/retention/preview") { contentType(ContentType.Application.Json); setBody(PreviewBody(retention)) }
            .takeIf { it.status == HttpStatusCode.OK }?.body<AfPreview>()
    }.getOrNull()

    // ---- DEB-2478 (FR-5, FR-12) — the investigation

    /** The investigation the server holds, or null when there is none. Asked every second or so while one runs. */
    suspend fun investigation(): AfInvestigation? = runCatching {
        httpClient.get("/api/autofetch/investigation").takeIf { it.status == HttpStatusCode.Accepted || it.status == HttpStatusCode.OK }?.body<AfInvestigation>()
    }.getOrNull()

    suspend fun investigate(url: String, tmdbId: Int?, pageOnly: Boolean, fetcherId: String? = null, ignoreRobots: Boolean = false): Outcome = outcome {
        httpClient.post("/api/autofetch/investigate") { contentType(ContentType.Application.Json); setBody(InvestigateBody(url, tmdbId, pageOnly, fetcherId, ignoreRobots)) }
    }

    suspend fun stopInvestigation(id: String): Outcome = outcome { httpClient.post("/api/autofetch/investigate/$id/stop") }
    suspend fun discardInvestigation(id: String): Outcome = outcome { httpClient.delete("/api/autofetch/investigate/$id") }

    suspend fun saveInvestigation(id: String, name: String?, libraryPath: String?, schedule: String?, retention: AfRetention?, runFirst: Boolean, accept: List<String> = emptyList()): Outcome = outcome {
        httpClient.post("/api/autofetch/investigate/$id/save") { contentType(ContentType.Application.Json); setBody(SaveInvestigationBody(name, libraryPath, schedule, retention, runFirst, accept)) }
    }

    // ---- DEB-2479 (FR-4.3, FR-12) — an item that waits for a number. An item id is the page's own and may hold slashes: it goes percent-encoded.

    suspend fun giveNumber(fetcherId: String, itemId: String, season: Int, episode: Int): Outcome = outcome {
        httpClient.post("/api/autofetch/$fetcherId/items/${afUrlEncode(itemId)}/number") { contentType(ContentType.Application.Json); setBody(NumberBody(season, episode)) }
    }

    /** One model call. A `200` is the model's answer (a proposal, or *no episode fits*); the server's next overview carries the proposal. */
    suspend fun propose(fetcherId: String, itemId: String): Outcome {
        val r = try { httpClient.post("/api/autofetch/$fetcherId/items/${afUrlEncode(itemId)}/propose") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { return Outcome(false, "The server did not answer.") }
        val text = runCatching { r.bodyAsText() }.getOrDefault("")
        if (r.status.value in 200..299) {
            if ("\"proposed\":false" !in text) return Outcome(true)
            val why = jsonString(text, "reason")
            return Outcome(false, "The model found no episode that fits this item${if (why.isNullOrBlank()) "" else ": $why"}. Nothing was kept.")
        }
        return Outcome(false, jsonString(text, "error") ?: "The server refused (${r.status.value}).")
    }

    private fun jsonString(text: String, key: String): String? =
        Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text)?.groupValues?.get(1)?.replace("\\\"", "\"")

    /** The review's Keep: what the newest-N rule would keep of the episodes found, counted by the server (FR-16.1). */
    suspend fun previewReview(id: String, retention: AfRetention): AfPreview? = runCatching {
        httpClient.post("/api/autofetch/investigate/$id/retention/preview") { contentType(ContentType.Application.Json); setBody(PreviewBody(retention)) }
            .takeIf { it.status == HttpStatusCode.OK }?.body<AfPreview>()
    }.getOrNull()

    private suspend fun outcome(call: suspend () -> HttpResponse): Outcome {
        val r = try { call() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { return Outcome(false, "The server did not answer.") }
        if (r.status.value in 200..299) return Outcome(true)
        val text = runCatching { r.bodyAsText() }.getOrDefault("")
        return Outcome(false, jsonString(text, "error") ?: "The server refused (${r.status.value}).")
    }
}
