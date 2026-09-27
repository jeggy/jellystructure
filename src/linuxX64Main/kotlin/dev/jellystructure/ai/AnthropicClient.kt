package dev.jellystructure.ai

import dev.jellystructure.OutboundHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * Phase 270 (FR-270-1/3, dev review items 3-4) — the few Anthropic calls the AI jobs make, as plain HTTPS:
 * the backend is Kotlin/Native, for which there is no Anthropic SDK. Headers `x-api-key` and
 * `anthropic-version: 2023-06-01` on every call. The HTTP itself sits behind [Transport] so the tests can
 * stand in for Anthropic.
 */
class AnthropicClient(private val transport: Transport = KtorTransport()) {
    data class Response(val status: Int, val body: String)

    interface Transport {
        suspend fun get(url: String, apiKey: String): Response
        suspend fun post(url: String, apiKey: String, body: String): Response
    }

    enum class KeyCheck { VALID, INVALID, UNREACHABLE }

    /** FR-270-1 — *Test key*: `GET /v1/models` costs nothing. 200 valid, 401/403 invalid, anything else
     *  (no answer, a 5xx) *can't reach Anthropic*. */
    suspend fun testKey(apiKey: String): KeyCheck {
        val r = runCatching { transport.get("$BASE/v1/models", apiKey) }.getOrNull() ?: return KeyCheck.UNREACHABLE
        return when (r.status) {
            200 -> KeyCheck.VALID
            401, 403 -> KeyCheck.INVALID
            else -> KeyCheck.UNREACHABLE
        }
    }

    data class Batch(val id: String, val processingStatus: String, val resultsUrl: String?)

    /** `POST /v1/messages/batches` with [requestsJson] (a JSON array of `{custom_id, params}`). */
    suspend fun createBatch(apiKey: String, requestsJson: String): Batch =
        parseBatch(withBackoff { transport.post("$BASE/v1/messages/batches", apiKey, """{"requests":$requestsJson}""") })

    /** `GET /v1/messages/batches/{id}`: `processing_status` is `in_progress`, `canceling` or `ended`. */
    suspend fun getBatch(apiKey: String, id: String): Batch =
        parseBatch(withBackoff { transport.get("$BASE/v1/messages/batches/$id", apiKey) })

    /** The batch's results: one JSON object per line, each with its `custom_id` (never read by position). */
    suspend fun results(apiKey: String, resultsUrl: String): List<JsonObject> =
        withBackoff { transport.get(resultsUrl, apiKey) }.body.lineSequence()
            .map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .toList()

    class ApiException(val status: Int, message: String) : Exception(message)

    private fun parseBatch(r: Response): Batch {
        if (r.status !in 200..299) throw ApiException(r.status, "Anthropic answered ${r.status}: ${r.body.take(200)}")
        val o = json.parseToJsonElement(r.body).jsonObject
        return Batch(
            id = o["id"]!!.jsonPrimitive.content,
            processingStatus = o["processing_status"]?.jsonPrimitive?.contentOrNull ?: "in_progress",
            resultsUrl = o["results_url"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** Dev review item 3 — a 429 or 5xx backs off with jittered exponential delays (Phase 183's rule), then
     *  gives up; any other status is returned for the caller to judge. */
    private suspend fun withBackoff(call: suspend () -> Response): Response {
        var attempt = 0
        while (true) {
            val r = call()
            if (r.status != 429 && r.status < 500) return r
            if (++attempt >= MAX_ATTEMPTS) throw ApiException(r.status, "Anthropic answered ${r.status} after $attempt tries")
            val base = 1_000L shl attempt
            delay(base + Random.nextLong(base / 2 + 1))
        }
    }

    /** The real transport: [OutboundHttp]'s shared client and permits (the caller runs on the BACKGROUND
     *  gate class, so a batch never takes a permit a TV is waiting for). */
    class KtorTransport : Transport {
        override suspend fun get(url: String, apiKey: String): Response = OutboundHttp.withPermit {
            val r = OutboundHttp.client.get(url) { headers(apiKey) }
            Response(r.status.value, r.bodyAsText())
        }

        override suspend fun post(url: String, apiKey: String, body: String): Response = OutboundHttp.withPermit {
            val r = OutboundHttp.client.post(url) {
                headers(apiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            Response(r.status.value, r.bodyAsText())
        }

        private fun io.ktor.client.request.HttpRequestBuilder.headers(apiKey: String) {
            header("x-api-key", apiKey)
            header("anthropic-version", "2023-06-01")
        }
    }

    companion object {
        const val BASE = "https://api.anthropic.com"
        private const val MAX_ATTEMPTS = 5
        private val json = Json { ignoreUnknownKeys = true }
    }
}
