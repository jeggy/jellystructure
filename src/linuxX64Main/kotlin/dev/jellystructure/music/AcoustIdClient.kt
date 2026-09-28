package dev.jellystructure.music

import dev.jellystructure.OutboundHttp
import dev.jellystructure.ServerVersion
import dev.jellystructure.log.Logger
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class AcoustIdReleaseGroup(val id: String = "", val title: String? = null, val type: String? = null)
@Serializable data class AcoustIdRecording(val id: String = "", val title: String? = null, val releasegroups: List<AcoustIdReleaseGroup> = emptyList())
@Serializable data class AcoustIdResult(val id: String = "", val score: Double = 0.0, val recordings: List<AcoustIdRecording> = emptyList())
@Serializable private data class AcoustIdResponse(val status: String = "", val results: List<AcoustIdResult> = emptyList())

/**
 * Phase 276 (FR-276-3 rung 4) — AcoustID identifies a track by its sound. The fingerprint is Chromaprint's
 * **compressed** form (plain `fpcalc`, not the `-raw` ints 150's intro matching uses), sent by POST with the
 * track's duration. At most 3 requests a second (AcoustID's rule); a client key from acoustid.org is required
 * and non-commercial use is free.
 */
class AcoustIdClient(
    private val clientKey: () -> String,
    val limiter: FixedRateLimiter = FixedRateLimiter(3.0, burst = 1.0),
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    val available: Boolean get() = clientKey().isNotBlank()

    /** Results best-first; empty when the key is missing, the service refused, or nothing matched. */
    suspend fun lookup(fingerprint: String, durationSec: Int): List<AcoustIdResult> {
        val key = clientKey().ifBlank { return emptyList() }
        limiter.acquire()
        return runCatching {
            OutboundHttp.withPermit {
                val resp = OutboundHttp.client.submitForm(
                    url = "https://api.acoustid.org/v2/lookup",
                    formParameters = parameters {
                        append("client", key)
                        append("duration", durationSec.toString())
                        append("fingerprint", fingerprint)
                        append("meta", "recordings releasegroups compress")
                    },
                ) { header(HttpHeaders.UserAgent, "jellystructure/${ServerVersion.current.ifBlank { "dev" }}") }
                if (resp.status == HttpStatusCode.ServiceUnavailable || resp.status == HttpStatusCode.TooManyRequests) limiter.onRefused()
                if (resp.status != HttpStatusCode.OK) null
                else json.decodeFromString(AcoustIdResponse.serializer(), resp.bodyAsText())
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            Logger.warn("AcoustID lookup failed: ${e.message}", "music")
            null
        }?.takeIf { it.status == "ok" }?.results.orEmpty().sortedByDescending { it.score }
    }
}
