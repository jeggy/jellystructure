package dev.jellystructure.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class AiPageDto(val tabs: List<String> = emptyList())

/** DEB-1253 (FR-2) — which tabs the admin's AI page has; the server decides, the admin never reads `ai.api_key` for it. */
object AiPageApi {
    sealed interface Answer {
        /** No HTTP response at all: the server is not there (FR-7). */
        data object NoResponse : Answer
        /** A 404, or a 200 with no tab: there is no AI page. */
        data object Absent : Answer
        data class Tabs(val tabs: List<String>) : Answer
    }

    suspend fun ask(): Answer {
        val r = try { httpClient.get("/api/ai/page") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { return Answer.NoResponse }
        if (r.status != HttpStatusCode.OK) return Answer.Absent
        val tabs = runCatching { r.body<AiPageDto>().tabs }.getOrDefault(emptyList())
        return if (tabs.isEmpty()) Answer.Absent else Answer.Tabs(tabs)
    }

    /** The tabs, or null when there is no AI page (or the server did not answer). */
    suspend fun tabs(): List<String>? = (ask() as? Answer.Tabs)?.tabs
}
