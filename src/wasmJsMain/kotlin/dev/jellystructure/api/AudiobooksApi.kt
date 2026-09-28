package dev.jellystructure.api

import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookApplyRequest
import dev.jellystructure.model.AudiobookAuthor
import dev.jellystructure.model.AudiobookAuthorEdit
import dev.jellystructure.model.AudiobookAuthorPageDto
import dev.jellystructure.model.AudiobookChaptersRequest
import dev.jellystructure.model.AudiobookDismissRequest
import dev.jellystructure.model.AudiobookFieldEdit
import dev.jellystructure.model.AudiobookOrderRequest
import dev.jellystructure.model.AudiobookPageDto
import dev.jellystructure.model.AudiobookProvidersDto
import dev.jellystructure.model.AudiobookProvidersUpdate
import dev.jellystructure.model.AudiobooksBrowseDto
import dev.jellystructure.model.AudiobooksHealthDto
import dev.jellystructure.model.MusicArtCandidate
import dev.jellystructure.model.MusicArtUseRequest
import dev.jellystructure.model.MusicArtworkDto
import dev.jellystructure.model.MusicBulkResult
import dev.jellystructure.model.MusicLockRequest
import dev.jellystructure.model.MusicStreamDto
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

/** Phases 280/281 — the admin's audiobook calls (`/api/audiobooks/…`). Null / false = the call failed (the page says so). */
object AudiobooksApi {
    private fun id(s: String) = s.encodeURLParameter()

    suspend fun status(): AudiobooksHealthDto? = runCatching { httpClient.get("/api/audiobooks/status").body<AudiobooksHealthDto>() }.getOrNull()

    /** [facets] maps a facet key to the values ticked. */
    suspend fun browse(view: String, query: String?, facets: Map<String, Set<String>>, sort: String? = null): AudiobooksBrowseDto? = runCatching {
        val qs = buildList {
            add("view=$view")
            query?.takeIf { it.isNotBlank() }?.let { add("q=${it.encodeURLParameter()}") }
            sort?.let { add("sort=$it") }
            facets.filterValues { it.isNotEmpty() }.forEach { (k, v) -> add("f.$k=${v.joinToString(",") { it.encodeURLParameter() }}") }
        }.joinToString("&")
        httpClient.get("/api/audiobooks/browse?$qs").body<AudiobooksBrowseDto>()
    }.getOrNull()

    suspend fun page(bookId: String): AudiobookPageDto? = runCatching {
        val r = httpClient.get("/api/audiobooks/${id(bookId)}/page")
        if (r.status.isSuccess()) r.body<AudiobookPageDto>() else null
    }.getOrNull()

    suspend fun edit(bookId: String, field: String, value: String?): Audiobook? = runCatching {
        httpClient.put("/api/audiobooks/${id(bookId)}/field") { contentType(ContentType.Application.Json); setBody(AudiobookFieldEdit(field, value)) }.body<Audiobook>()
    }.getOrNull()

    suspend fun lock(bookId: String, locked: Boolean): Audiobook? = runCatching {
        httpClient.post("/api/audiobooks/${id(bookId)}/lock") { contentType(ContentType.Application.Json); setBody(MusicLockRequest(locked)) }.body<Audiobook>()
    }.getOrNull()

    suspend fun suggest(bookId: String, asin: String?): Audiobook? = runCatching {
        val q = asin?.trim()?.takeIf { it.isNotEmpty() }?.let { "?asin=${it.encodeURLParameter()}" } ?: ""
        httpClient.post("/api/audiobooks/${id(bookId)}/suggest$q").body<Audiobook>()
    }.getOrNull()

    suspend fun apply(bookId: String, provider: String, fields: List<String>): Audiobook? = runCatching {
        httpClient.post("/api/audiobooks/${id(bookId)}/apply") { contentType(ContentType.Application.Json); setBody(AudiobookApplyRequest(provider, fields)) }.body<Audiobook>()
    }.getOrNull()

    suspend fun order(bookId: String, partIds: List<String>): Boolean = runCatching {
        httpClient.put("/api/audiobooks/${id(bookId)}/order") { contentType(ContentType.Application.Json); setBody(AudiobookOrderRequest(partIds)) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun chapters(bookId: String, source: String?, titles: Map<String, String>?): Audiobook? = runCatching {
        httpClient.put("/api/audiobooks/${id(bookId)}/chapters") { contentType(ContentType.Application.Json); setBody(AudiobookChaptersRequest(source, titles)) }.body<Audiobook>()
    }.getOrNull()

    suspend fun dismiss(bookId: String, what: String): Audiobook? = runCatching {
        httpClient.post("/api/audiobooks/${id(bookId)}/dismiss") { contentType(ContentType.Application.Json); setBody(AudiobookDismissRequest(what)) }.body<Audiobook>()
    }.getOrNull()

    suspend fun split(bookId: String): Audiobook? = runCatching {
        val r = httpClient.post("/api/audiobooks/${id(bookId)}/split")
        if (r.status.isSuccess()) r.body<Audiobook>() else null
    }.getOrNull()

    suspend fun join(bookId: String): Audiobook? = runCatching {
        val r = httpClient.post("/api/audiobooks/${id(bookId)}/join")
        if (r.status.isSuccess()) r.body<Audiobook>() else null
    }.getOrNull()

    suspend fun reread(bookId: String): Audiobook? = runCatching {
        val r = httpClient.post("/api/audiobooks/${id(bookId)}/reread")
        if (r.status.isSuccess()) r.body<Audiobook>() else null
    }.getOrNull()

    suspend fun sync(bookId: String): Boolean = runCatching {
        httpClient.post("/api/audiobooks/${id(bookId)}/sync").status.isSuccess()
    }.getOrDefault(false)

    suspend fun artwork(bookId: String): MusicArtworkDto? = runCatching {
        val r = httpClient.get("/api/audiobooks/${id(bookId)}/artwork")
        if (r.status.isSuccess()) r.body<MusicArtworkDto>() else null
    }.getOrNull()

    suspend fun useCover(bookId: String, c: MusicArtCandidate): Boolean = runCatching {
        httpClient.post("/api/audiobooks/${id(bookId)}/artwork/use") { contentType(ContentType.Application.Json); setBody(MusicArtUseRequest(c.url, c.kind, c.source, c.credit)) }.status.isSuccess()
    }.getOrDefault(false)

    suspend fun clearCover(bookId: String): Boolean = runCatching {
        httpClient.post("/api/audiobooks/${id(bookId)}/artwork/clear").status.isSuccess()
    }.getOrDefault(false)

    suspend fun lockCover(bookId: String, locked: Boolean): Boolean = runCatching {
        httpClient.post("/api/audiobooks/${id(bookId)}/artwork/lock") { contentType(ContentType.Application.Json); setBody(MusicLockRequest(locked)) }.status.isSuccess()
    }.getOrDefault(false)

    /** Save — one sentence back (what was written, what was skipped). */
    suspend fun save(bookId: String, sync: Boolean): String? = runCatching {
        httpClient.post("/api/audiobooks/${id(bookId)}/save?sync=$sync").body<MusicBulkResult>().sentence
    }.getOrNull()

    suspend fun saveSays(bookId: String): String? = runCatching {
        httpClient.get("/api/audiobooks/${id(bookId)}/save-says").body<MusicBulkResult>().sentence
    }.getOrNull()

    suspend fun partStream(partId: String): String? = runCatching {
        val r = httpClient.get("/api/audiobooks/part/${id(partId)}/stream")
        if (r.status.isSuccess()) r.body<MusicStreamDto>().url else null
    }.getOrNull()

    suspend fun authorPage(authorId: String, ask: Boolean = false): AudiobookAuthorPageDto? = runCatching {
        val r = httpClient.get("/api/audiobooks/author/${id(authorId)}/page${if (ask) "?ask=1" else ""}")
        if (r.status.isSuccess()) r.body<AudiobookAuthorPageDto>() else null
    }.getOrNull()

    suspend fun editAuthor(authorId: String, bio: String?): AudiobookAuthor? = runCatching {
        httpClient.put("/api/audiobooks/author/${id(authorId)}") { contentType(ContentType.Application.Json); setBody(AudiobookAuthorEdit(bio)) }.body<AudiobookAuthor>()
    }.getOrNull()

    suspend fun providers(): AudiobookProvidersDto? = runCatching { httpClient.get("/api/audiobooks/providers").body<AudiobookProvidersDto>() }.getOrNull()

    suspend fun saveProviders(update: AudiobookProvidersUpdate): Boolean = runCatching {
        httpClient.put("/api/audiobooks/providers") { contentType(ContentType.Application.Json); setBody(update) }.status.isSuccess()
    }.getOrDefault(false)
}
