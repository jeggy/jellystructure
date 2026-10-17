package dev.jellystructure.api

import dev.jellystructure.model.SupportAdminThread
import dev.jellystructure.model.SupportAdminTicket
import dev.jellystructure.model.SupportAiTranscript
import dev.jellystructure.model.SupportClassRequest
import dev.jellystructure.model.SupportEnabledRequest
import dev.jellystructure.model.SupportFlagRequest
import dev.jellystructure.model.SupportForwardRequest
import dev.jellystructure.model.SupportHervorRequest
import dev.jellystructure.model.SupportLog
import dev.jellystructure.model.SupportSeatTokenIssued
import dev.jellystructure.model.SupportSeatTokenState
import dev.jellystructure.model.SupportStateRequest
import dev.jellystructure.model.SupportSummary
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * DEB-1500 — the admin's Support calls (`/api/support/…`, FR-320-12/14, FR-3, FR-12). The page renders what these return
 * and keeps nothing of its own: a write answers with the whole thread, and the page draws that and not what it sent.
 */
object SupportApi {
    /** What a write came to. [Closed] is the 409 `support_unavailable` the routes answer while the lane is closed (FR-9). */
    sealed interface Write {
        data class Ok(val thread: SupportAdminThread) : Write
        data object Closed : Write
        /** No HTTP response at all: the server is not there (FR-16). */
        data object NoResponse : Write
        data class Failed(val status: Int, val reason: String?) : Write
    }

    sealed interface Read {
        data class Found(val thread: SupportAdminThread) : Read
        /** FR-7 — a 404: the ticket no longer exists. */
        data object Gone : Read
        data object NoResponse : Read
    }

    sealed interface Switched {
        data class Ok(val summary: SupportSummary) : Switched
        data object NoResponse : Switched
        data class Failed(val status: Int) : Switched
    }

    @Serializable private data class Body(val body: String)
    @Serializable private data class UpTo(val upTo: Long)

    private val lenient = Json { ignoreUnknownKeys = true }

    private suspend fun <T> quietly(block: suspend () -> T?): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }

    suspend fun summary(): SupportSummary? = quietly {
        httpClient.get("/api/support/summary").takeIf { it.status == HttpStatusCode.OK }?.body<SupportSummary>()
    }

    /** [path] is `SupportText.ticketsPath(filter)`; null when the server did not answer. */
    suspend fun tickets(path: String): List<SupportAdminTicket>? = quietly {
        httpClient.get(path).takeIf { it.status == HttpStatusCode.OK }?.body<dev.jellystructure.model.SupportAdminList>()?.tickets
    }

    suspend fun thread(number: Long): Read {
        val r = try { httpClient.get("/api/support/tickets/$number") } catch (e: CancellationException) { throw e } catch (_: Throwable) { return Read.NoResponse }
        return when (r.status) {
            HttpStatusCode.OK -> quietly { r.body<SupportAdminThread>() }?.let { Read.Found(it) } ?: Read.NoResponse
            HttpStatusCode.NotFound -> Read.Gone
            else -> Read.NoResponse
        }
    }

    /** FR-7 — moves the admin's marker forward; the answer is not read. */
    suspend fun read(number: Long, upTo: Long) {
        quietly { httpClient.post("/api/support/tickets/$number/read") { contentType(ContentType.Application.Json); setBody(UpTo(upTo)) }; true }
    }

    suspend fun reply(number: Long, body: String): Write = write("/api/support/tickets/$number/messages", Body(body))

    suspend fun forward(number: Long, holder: String, note: String): Write =
        write("/api/support/tickets/$number/forward", SupportForwardRequest(holder = holder, owes = holder, note = note))

    suspend fun state(number: Long, state: String): Write = write("/api/support/tickets/$number/state", SupportStateRequest(state))

    suspend fun ticketClass(number: Long, ticketClass: String): Write = write("/api/support/tickets/$number/class", SupportClassRequest(ticketClass))

    suspend fun flag(number: Long, flagged: Boolean): Write = write("/api/support/tickets/$number/flag", SupportFlagRequest(flagged))

    private suspend inline fun <reified B : Any> write(path: String, payload: B): Write {
        val r: HttpResponse = try {
            httpClient.post(path) { contentType(ContentType.Application.Json); setBody(payload) }
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { return Write.NoResponse }
        if (r.status == HttpStatusCode.OK || r.status == HttpStatusCode.Created) {
            return quietly { r.body<SupportAdminThread>() }?.let { Write.Ok(it) } ?: Write.Failed(r.status.value, null)
        }
        val text = quietly { r.body<String>() }
        val error = text?.let { runCatching { (lenient.parseToJsonElement(it) as? JsonObject)?.get("error") }.getOrNull() as? JsonPrimitive }?.content
        if (r.status == HttpStatusCode.Conflict && error == "support_unavailable") return Write.Closed
        return Write.Failed(r.status.value, error)
    }

    /** FR-10.9 — every turn `AI Agent` took for this ticket; null when there is none to show or the call failed. */
    suspend fun transcript(number: Long): SupportAiTranscript? = quietly {
        httpClient.get("/api/support/tickets/$number/transcript").takeIf { it.status == HttpStatusCode.OK }?.body<SupportAiTranscript>()
    }

    /** FR-3 — `POST /api/support/enabled`; answers with the new summary and the strip renders that. */
    suspend fun setEnabled(enabled: Boolean): Switched {
        val r = try {
            httpClient.post("/api/support/enabled") { contentType(ContentType.Application.Json); setBody(SupportEnabledRequest(enabled)) }
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { return Switched.NoResponse }
        if (r.status != HttpStatusCode.OK) return Switched.Failed(r.status.value)
        return quietly { r.body<SupportSummary>() }?.let { Switched.Ok(it) } ?: Switched.Failed(r.status.value)
    }

    /** DEB-1508 (FR-2) — what a seat-token write came to. The plaintext is in [Issued] once and the page puts it in the DOM and nowhere else. */
    sealed interface SeatWrite {
        data class Issued(val issued: SupportSeatTokenIssued) : SeatWrite
        data class State(val state: SupportSeatTokenState) : SeatWrite
        data object NoResponse : SeatWrite
        data class Failed(val status: Int) : SeatWrite
    }

    suspend fun seatToken(): SupportSeatTokenState? = quietly {
        httpClient.get("/api/support/seat-token").takeIf { it.status == HttpStatusCode.OK }?.body<SupportSeatTokenState>()
    }

    suspend fun issueSeatToken(): SeatWrite {
        val r = try { httpClient.post("/api/support/seat-token") } catch (e: CancellationException) { throw e } catch (_: Throwable) { return SeatWrite.NoResponse }
        if (r.status != HttpStatusCode.Created) return SeatWrite.Failed(r.status.value)
        return quietly { r.body<SupportSeatTokenIssued>() }?.let { SeatWrite.Issued(it) } ?: SeatWrite.Failed(r.status.value)
    }

    /** One click: revoking also switches her off and hands her tickets back (FR-2, FR-9). A 204 (none was live) reads as no live token. */
    suspend fun revokeSeatToken(): SeatWrite {
        val r = try { httpClient.delete("/api/support/seat-token") } catch (e: CancellationException) { throw e } catch (_: Throwable) { return SeatWrite.NoResponse }
        if (r.status == HttpStatusCode.NoContent) return SeatWrite.State(SupportSeatTokenState())
        if (r.status != HttpStatusCode.OK) return SeatWrite.Failed(r.status.value)
        return quietly { r.body<SupportSeatTokenState>() }?.let { SeatWrite.State(it) } ?: SeatWrite.Failed(r.status.value)
    }

    /** FR-8 — `POST /api/support/hervor`; answers with the new summary and the strip renders that. */
    suspend fun setHervor(enabled: Boolean): Switched {
        val r = try {
            httpClient.post("/api/support/hervor") { contentType(ContentType.Application.Json); setBody(SupportHervorRequest(enabled)) }
        } catch (e: CancellationException) { throw e } catch (_: Throwable) { return Switched.NoResponse }
        if (r.status != HttpStatusCode.OK) return Switched.Failed(r.status.value)
        return quietly { r.body<SupportSummary>() }?.let { Switched.Ok(it) } ?: Switched.Failed(r.status.value)
    }

    /** FR-12 — one page of the log, newest first; [before] is the last entry id of the page already read. */
    suspend fun log(before: Long? = null): SupportLog? = quietly {
        httpClient.get("/api/support/log" + (before?.let { "?before=$it" } ?: "")).takeIf { it.status == HttpStatusCode.OK }?.body<SupportLog>()
    }
}
