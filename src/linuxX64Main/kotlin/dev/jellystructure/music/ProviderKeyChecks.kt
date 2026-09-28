package dev.jellystructure.music

import dev.jellystructure.OutboundHttp
import dev.jellystructure.ServerVersion
import dev.jellystructure.log.Logger
import dev.jellystructure.model.ProviderKeyCheck
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Phase 276/281 (2026-09-28 amendment) — the Metadata providers card's dots for the three keyed providers say what the
 * provider **answered**, never merely that a key is saved. Each check is one real, cheap request with the key; the
 * answer is remembered for the key it was made with, so a new, replaced or hand-edited key reads as unchecked until it
 * is checked again. Real calls (a fanart.tv artist, an AcoustID lookup, a Google Books search) record their answer too,
 * so a key revoked later turns the dot red without anyone pressing *Test*. In memory only: after a restart the card
 * checks again on its own.
 */
object ProviderKeyChecks {
    class Memo {
        @kotlin.concurrent.Volatile private var memo: Pair<String, ProviderKeyCheck>? = null
        /** The last answer for exactly this key, or null when this key was never checked. */
        fun last(key: String): ProviderKeyCheck? = memo?.takeIf { key.isNotBlank() && it.first == key.trim() }?.second
        /** Only answers about the key are kept; a provider that did not answer says nothing about it. */
        fun record(key: String, check: ProviderKeyCheck) { if (check.answered) memo = key.trim() to check }
    }

    val fanart = Memo()
    val acoustId = Memo()
    val googleBooks = Memo()

    private val json = Json { ignoreUnknownKeys = true }
    private fun now() = dev.jellystructure.nowEpochSec()
    private fun ok(provider: String) = ProviderKeyCheck(ok = true, answered = true, message = "$provider accepted the key.", checkedAt = now())
    private fun refused(provider: String, why: String?) =
        ProviderKeyCheck(ok = false, answered = true, message = "$provider refused the key" + (why?.let { ": ${it.take(200).trimEnd('.')}." } ?: "."), checkedAt = now())
    private fun noAnswer(provider: String, status: Int?, detail: String? = null) =
        ProviderKeyCheck(ok = false, answered = false, checkedAt = now(), message =
            if (status == null || status >= 500 || status == 429 || detail == null) "$provider didn't answer (${status?.let { "HTTP $it" } ?: "no connection"}), so the key is untested."
            else "$provider answered HTTP $status (${detail.take(160).trimEnd('.')}), so the key is untested.")
    private fun obj(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    private fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull

    /** fanart.tv checks the key before it looks the artist up (a bad key is 401 `{"error":"invalid API key"}`). */
    fun fanartVerdict(status: Int?, body: String?): ProviderKeyCheck = when (status) {
        200 -> ok("fanart.tv")
        401, 403 -> refused("fanart.tv", body?.let { obj(it).str("error") })
        else -> noAnswer("fanart.tv", status, body?.let { obj(it).str("error") })
    }

    /** AcoustID answers 200 `{"status":"ok"}`, or `{"status":"error","error":{"code":4,…}}` for a bad client key. */
    fun acoustIdVerdict(status: Int?, body: String?): ProviderKeyCheck {
        val o = body?.let { obj(it) }
        val error = o?.get("error") as? JsonObject
        return when {
            status == 200 && o.str("status") == "ok" -> ok("AcoustID")
            (error?.get("code") as? JsonPrimitive)?.intOrNull == 4 -> refused("AcoustID", error.str("message"))
            else -> noAnswer("AcoustID", status, error.str("message"))
        }
    }

    /** Google answers 400 `API_KEY_INVALID` for a bad key and 403 when the key may not use the Books API. */
    fun googleBooksVerdict(status: Int?, body: String?): ProviderKeyCheck = when (status) {
        200 -> ok("Google Books")
        400, 401, 403 -> refused("Google Books", body?.let { (obj(it)?.get("error") as? JsonObject).str("message") })
        else -> noAnswer("Google Books", status, body?.let { (obj(it)?.get("error") as? JsonObject).str("message") })
    }

    /** One GET; the status and body whatever the status, or nulls when there was no connection. Never throws. */
    internal suspend fun probe(url: String, context: String): Pair<Int?, String?> = runCatching {
        OutboundHttp.withPermit {
            val r = OutboundHttp.client.get(url) {
                header(HttpHeaders.UserAgent, "jellystructure/${ServerVersion.current.ifBlank { "dev" }} ( https://github.com/jeggy/jellystructure )")
                header(HttpHeaders.Accept, "application/json")
            }
            r.status.value to r.bodyAsText()
        }
    }.getOrElse { e ->
        if (e is CancellationException) throw e
        Logger.warn("$context failed: ${e.message}", "music"); null to null
    }

    // A widely-covered artist, so a working key gets images back (an unknown id answers 200 `{}` either way).
    private const val FANART_TEST_ARTIST = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"
    // AcoustID's own documentation example track id — a lookup by id needs no fingerprint.
    private const val ACOUSTID_TEST_TRACK = "9ff43b6a-4f16-427c-93c2-92307ca505e0"

    suspend fun checkFanart(key: String): ProviderKeyCheck {
        val (status, body) = probe("https://webservice.fanart.tv/v3/music/$FANART_TEST_ARTIST?api_key=${key.trim().encodeURLParameter()}", "fanart.tv check")
        return fanartVerdict(status, body).also { fanart.record(key, it) }
    }

    suspend fun checkAcoustId(key: String): ProviderKeyCheck {
        val (status, body) = probe("https://api.acoustid.org/v2/lookup?client=${key.trim().encodeURLParameter()}&trackid=$ACOUSTID_TEST_TRACK", "AcoustID check")
        return acoustIdVerdict(status, body).also { acoustId.record(key, it) }
    }

    suspend fun checkGoogleBooks(key: String): ProviderKeyCheck {
        val (status, body) = probe("https://www.googleapis.com/books/v1/volumes?q=isbn:9780140449136&maxResults=1&key=${key.trim().encodeURLParameter()}", "Google Books check")
        return googleBooksVerdict(status, body).also { googleBooks.record(key, it) }
    }
}
