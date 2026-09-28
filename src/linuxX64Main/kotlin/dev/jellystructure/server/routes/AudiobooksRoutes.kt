package dev.jellystructure.server.routes

import dev.jellystructure.audiobooks.AudiobooksBrowse
import dev.jellystructure.audiobooks.AudiobooksScanner
import dev.jellystructure.audiobooks.AudiobooksStore
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.MusicPipeline
import dev.jellystructure.model.AudiobookApplyRequest
import dev.jellystructure.model.AudiobookAuthorEdit
import dev.jellystructure.model.AudiobookAuthorPageDto
import dev.jellystructure.model.AudiobookChaptersRequest
import dev.jellystructure.model.AudiobookDismissRequest
import dev.jellystructure.model.AudiobookFieldEdit
import dev.jellystructure.model.AudiobookListenerRow
import dev.jellystructure.model.AudiobookOrderRequest
import dev.jellystructure.model.AudiobookPageDto
import dev.jellystructure.model.AudiobookPartRow
import dev.jellystructure.model.AudiobookProvidersDto
import dev.jellystructure.model.AudiobookProvidersUpdate
import dev.jellystructure.model.AudiobookRules
import dev.jellystructure.model.AudiobooksBrowseDto
import dev.jellystructure.model.AudiobooksHealthDto
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicArtUseRequest
import dev.jellystructure.model.MusicArtworkDto
import dev.jellystructure.model.MusicBulkResult
import dev.jellystructure.model.MusicLibraryInfo
import dev.jellystructure.model.MusicLockRequest
import dev.jellystructure.model.MusicStreamDto
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** FR-280-7 — the `/api/health` books block and the Library's status line: counts only. */
fun audiobooksHealth(store: AudiobooksStore, scanner: AudiobooksScanner): AudiobooksHealthDto {
    val h = store.health()
    return AudiobooksHealthDto(h.books, h.parts, h.authors, h.missingParts, h.twoInOne, h.coversMissing, h.noNarrator, h.durationMs, scanner.ebooks.values.sum())
}

/** A part a browser can play as it is (the Parts tab's ▶). */
private fun browserPlays(container: String?): Boolean =
    container?.lowercase()?.split(',')?.any { it.trim() in setOf("mp3", "m4a", "m4b", "mp4", "mov", "flac", "ogg", "opus", "webm") } == true

/**
 * Phases 280/281 — the admin's audiobooks: the Audiobooks kind (browse), the Audiobook and Author pages, the editor,
 * suggestions, the part order, chapters, the cover, Save, and the providers card's audiobook rows.
 */
fun Route.audiobooksRoutes(configStore: ConfigStore, music: MusicPipeline, jellyfinClient: dev.jellystructure.auth.JellyfinClient?, userName: (String) -> String?) {
    val scanner = music.audiobooks ?: return
    val store = scanner.store

    route("/audiobooks") {
        get("/status") {
            val mapped = AudiobooksScanner.audiobookLibraries(configStore.current).isNotEmpty()
            call.respond(if (mapped) audiobooksHealth(store, scanner) else AudiobooksHealthDto())
        }

        /** The Audiobooks kind (FR-280-8). Facets ride `f.<key>=a,b`; everything is counted here. */
        get("/browse") {
            val cfg = configStore.current
            val qp = call.request.queryParameters
            val snap = store.snapshot()
            val hasSeries = snap.books.values.any { it.missingSince == null && !it.series.isNullOrBlank() }
            val views = if (hasSeries) listOf("audiobooks", "authors", "series") else listOf("audiobooks", "authors")
            val view = qp["view"]?.takeIf { it in views } ?: "audiobooks"
            val selected = AudiobooksBrowse.FACETS.mapNotNull { (k, _) -> qp["f.$k"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.let { k to it } }.toMap()
            val libs = AudiobooksScanner.audiobookLibraries(cfg)
            val finished = HashMap<String, Int>()
            snap.books.keys.forEach { id -> store.progressOfBook(id).count { it.second.finishedAt != null }.takeIf { it > 0 }?.let { finished[id] = it } }
            val r = AudiobooksBrowse.browse(snap, view, selected, qp["q"], libs.associate { it.jellyfinId to it.name.ifBlank { it.jellyfinId } }, { finished[it] ?: 0 }, qp["sort"])
            call.respond(AudiobooksBrowseDto(
                mapped = libs.isNotEmpty(), scanned = scanner.lastScanAt != null || snap.books.isNotEmpty(),
                health = if (libs.isNotEmpty()) audiobooksHealth(store, scanner) else null,
                libraries = libs.map { MusicLibraryInfo(it.jellyfinId, it.name, it.jellyfinPath, it.localPath) },
                view = view, views = views, total = r.total, facets = r.facets, books = r.books, authors = r.authors, series = r.series,
            ))
        }

        get("/image/{id}") {
            val b = store.book(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val file = music.audiobooksMedia?.existingCover(b)
            // No cover file, but Jellyfin has art (usually embedded in the first part): show Jellyfin's.
            if (file == null && b.coverState == MusicArt.JELLYFIN && jellyfinClient != null) {
                val cfg = configStore.current
                val ids = listOf(b.id).filter { !it.startsWith("f:") } + store.parts(b.id).sortedBy { it.position }.map { it.id }
                for (id in ids) {
                    val img = jellyfinClient.getItemPrimaryImage(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, id) ?: continue
                    call.response.headers.append("Cache-Control", "private, max-age=60")
                    return@get call.respondBytes(img.first, ContentType.parse(img.second))
                }
                return@get call.respond(HttpStatusCode.NotFound)
            }
            serveFile(call, file)
        }

        // ── Phase 281: the Audiobook page ──

        get("/{id}/page") {
            val cfg = configStore.current
            val b = store.book(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val parts = store.parts(b.id).filter { it.missingSince == null }.sortedBy { it.position }
            // FR-281-5 — Jellyfin's own saved position per part, for the admin looking (read, never written back).
            val session = runCatching { call.attributes[dev.jellystructure.auth.SessionKey] }.getOrNull()
            val jfPos: Map<String, Long> = if (session != null && jellyfinClient != null && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                parts.map { it.id }.chunked(100).flatMap { ids ->
                    jellyfinClient.getUserDataBulk(cfg.apiKeys.jellyfinUrl, session.jellyfinUserToken, session.jellyfinUserId, ids)
                }.mapNotNull { i -> i.userData?.playbackPositionTicks?.takeIf { it > 0 }?.let { i.id to it / 10_000 } }.toMap()
            } else emptyMap()
            val total = b.durationMs.coerceAtLeast(1)
            val listeners = store.progressOfBook(b.id).map { (uid, p) ->
                AudiobookListenerRow(
                    name = userName(uid) ?: "Someone", leftMs = if (p.finishedAt != null) null else (b.durationMs - p.bookPositionMs).coerceAtLeast(0),
                    part = p.partIndex + 1, updatedAt = p.updatedAt, finishedAt = p.finishedAt,
                    progress = if (p.finishedAt != null) 1.0 else (p.bookPositionMs.toDouble() / total).coerceIn(0.0, 1.0),
                )
            }.sortedByDescending { it.updatedAt ?: 0 }
            val media = music.audiobooksMedia
            call.respond(AudiobookPageDto(
                book = b,
                parts = parts.map { p ->
                    AudiobookPartRow(p.id, p.number, p.position, p.title, p.durationMs, p.container?.substringBefore(',')?.uppercase() ?: "",
                        jfPos[p.id], (p.durationMs ?: 0) in 1 until AudiobookRules.JELLYFIN_MIN_RESUME_MS, browserPlays(p.container),
                        album = p.albumTag.takeIf { b.albumTags.size > 1 })
                },
                chapters = AudiobookRules.chapters(b, parts),
                coverUrl = if (b.coverState != MusicArt.NONE) "/api/audiobooks/image/${b.id}?v=${b.updatedAt}" else null,
                listeners = listeners,
                library = cfg.libraries.firstOrNull { it.jellyfinId == b.libraryId }?.name,
                jellyfinUrl = if (b.id.startsWith("f:")) null else jellyfinWebUrl(cfg, b.id),
                writeTags = cfg.audiobooks.writeTags && media?.taggerAvailable() == true,
                splitPreview = media?.splitPreview(b).orEmpty(),
                source = b.origins.values.filter { it != dev.jellystructure.model.AudiobookOrigin.FILES && it != dev.jellystructure.model.AudiobookOrigin.TYPED }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    ?: if (b.origins.values.any { it == dev.jellystructure.model.AudiobookOrigin.TYPED }) "Edited here" else "From the files",
                splitSiblings = if (b.splitFrom == null && !b.splitByAlbum) emptyList() else {
                    val folder = b.splitFrom ?: b.id
                    val sibs = store.snapshot().books.values.filter { it.missingSince == null && it.id != b.id && (it.id == folder || it.splitFrom == folder) }.map { it.id }.toSet()
                    AudiobooksBrowse.browse(store.snapshot(), "audiobooks", emptyMap(), null, emptyMap(), { 0 }, "title").books.filter { it.id in sibs }
                },
            ))
        }

        put("/{id}/field") {
            val media = music.audiobooksMedia ?: return@put call.respond(HttpStatusCode.ServiceUnavailable)
            val req = call.receive<AudiobookFieldEdit>()
            call.respond(media.edit(call.parameters["id"]!!, req.field, req.value) ?: return@put call.respond(HttpStatusCode.NotFound))
        }
        post("/{id}/lock") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(media.setLocked(call.parameters["id"]!!, call.receive<MusicLockRequest>().locked) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/{id}/suggest") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(media.ask(call.parameters["id"]!!, call.request.queryParameters["asin"]) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/{id}/apply") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val req = call.receive<AudiobookApplyRequest>()
            call.respond(media.apply(call.parameters["id"]!!, req.provider, req.fields) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        put("/{id}/order") {
            val media = music.audiobooksMedia ?: return@put call.respond(HttpStatusCode.ServiceUnavailable)
            val ok = media.order(call.parameters["id"]!!, call.receive<AudiobookOrderRequest>().partIds)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, mapOf("ok" to ok))
        }
        put("/{id}/chapters") {
            val media = music.audiobooksMedia ?: return@put call.respond(HttpStatusCode.ServiceUnavailable)
            val req = call.receive<AudiobookChaptersRequest>()
            call.respond(media.chapters(call.parameters["id"]!!, req.source, req.titles) ?: return@put call.respond(HttpStatusCode.NotFound))
        }
        post("/{id}/reread") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(media.reread(call.parameters["id"]!!) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/{id}/sync") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(mapOf("ok" to media.sync(call.parameters["id"]!!)))
        }
        post("/{id}/split") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val id = call.parameters["id"]!!
            // Phase 287 (FR-287-4) — an arranged split arrives as a body; 280's tag split has none.
            val body = call.receiveText()
            val groups = if (body.isBlank()) null else {
                val req = runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(dev.jellystructure.model.AudiobookSplitRequest.serializer(), body) }.getOrNull()
                val clean = req?.let { media.cleanSplit(id, it.groups) }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "each book needs a title and at least one part"))
                clean
            }
            call.respond(media.split(id, groups) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/{id}/join") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(media.join(call.parameters["id"]!!) ?: return@post call.respond(HttpStatusCode.NotFound))
        }
        post("/{id}/dismiss") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(media.dismiss(call.parameters["id"]!!, call.receive<AudiobookDismissRequest>().what) ?: return@post call.respond(HttpStatusCode.NotFound))
        }

        get("/{id}/artwork") {
            val media = music.audiobooksMedia ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            val b = store.book(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val inUse = media.existingCover(b)
            call.respond(MusicArtworkDto(
                inUse = inUse?.substringAfterLast('/') ?: if (b.coverState == MusicArt.JELLYFIN) "embedded" else null,
                inUseBytes = inUse?.let { fileSize(it) }, locked = media.coverLocked(b), source = b.coverSource,
                candidates = media.coverCandidates(b),
            ))
        }
        post("/{id}/artwork/use") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val req = call.receive<MusicArtUseRequest>()
            val ok = media.useCover(call.parameters["id"]!!, req.url, req.source)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, mapOf("ok" to ok))
        }
        post("/{id}/artwork/upload") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val bytes = receiveImage(call) ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "no file"))
            val ok = media.uploadCover(call.parameters["id"]!!, bytes)
            call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest, mapOf("ok" to ok))
        }
        post("/{id}/artwork/clear") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            call.respond(mapOf("ok" to media.clearCover(call.parameters["id"]!!)))
        }
        post("/{id}/artwork/lock") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            media.setCoverLock(call.parameters["id"]!!, call.receive<MusicLockRequest>().locked)
            call.respond(mapOf("ok" to true))
        }

        /** FR-281-8 — Save: `cover.jpg` is already written; tags only with the switch on; `sync` asks Jellyfin to re-read. */
        post("/{id}/save") {
            val media = music.audiobooksMedia ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
            val sync = call.request.queryParameters["sync"] != "false"
            call.respond(MusicBulkResult(media.save(call.parameters["id"]!!, sync)))
        }
        get("/{id}/save-says") {
            val media = music.audiobooksMedia ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
            val b = store.book(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(MusicBulkResult(media.saveSays(b)))
        }

        /** The Parts tab's ▶ — your own Jellyfin session, direct play or nothing. */
        get("/part/{id}/stream") {
            val session = runCatching { call.attributes[dev.jellystructure.auth.SessionKey] }.getOrNull() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val p = store.snapshot().parts[call.parameters["id"]!!] ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!browserPlays(p.container)) return@get call.respond(HttpStatusCode.Conflict, mapOf("error" to "A browser can't direct-play this format"))
            val base = configStore.current.apiKeys.jellyfinUrl.trimEnd('/')
            call.respond(MusicStreamDto(dev.jellystructure.auth.withJellyfinToken("$base/Audio/${p.id}/stream?Static=true&DeviceId=jellystructure-admin-audiobooks", session.jellyfinUserToken)))
        }

        // ── FR-281-9: the Author page ──

        get("/author/{id}/page") {
            val snap = store.snapshot()
            val a = snap.authors[call.parameters["id"]!!] ?: return@get call.respond(HttpStatusCode.NotFound)
            val theirs = snap.books.values.filter { it.missingSince == null && it.authors.any { n -> AudiobookRules.authorId(n) == a.id } }
            val rows = AudiobooksBrowse.browse(snap, "audiobooks", emptyMap(), null, emptyMap(), { 0 }, "series").books.filter { r -> theirs.any { it.id == r.id } }
            val works = if (call.request.queryParameters["ask"] == "1") music.audiobooksMedia?.let { runCatching { dev.jellystructure.audiobooks.AudiobookProviders(configStore).authorWorks(a.name) }.getOrNull() } else null
            call.respond(AudiobookAuthorPageDto(
                author = a, books = rows, pictureUrl = null,
                suggestion = works?.let { n -> if (n > 0) "Open Library credits ${a.name} with $n works — ${theirs.size} of them here." else "Open Library doesn't know ${a.name}." },
            ))
        }
        put("/author/{id}") {
            val media = music.audiobooksMedia ?: return@put call.respond(HttpStatusCode.ServiceUnavailable)
            val req = call.receive<AudiobookAuthorEdit>()
            call.respond(media.editAuthorBio(call.parameters["id"]!!, req.bio) ?: return@put call.respond(HttpStatusCode.NotFound))
        }

        // ── FR-281-4: the providers card's audiobook rows ──

        get("/providers") {
            val cfg = configStore.current
            call.respond(AudiobookProvidersDto(
                itunesStore = cfg.audiobooks.itunesStore, audnexusRegion = cfg.audiobooks.audnexusRegion,
                googleBooksKeySet = cfg.apiKeys.googleBooksKey.isNotBlank(), writeTags = cfg.audiobooks.writeTags,
                taggerAvailable = music.audiobooksMedia?.taggerAvailable() == true,
                googleBooksCheck = dev.jellystructure.music.ProviderKeyChecks.googleBooks.last(cfg.apiKeys.googleBooksKey),
            ))
        }
        // 2026-09-28 amendment — *Test* on a keyed row: one real request with the saved key.
        post("/providers/test/{name}") {
            val key = configStore.current.apiKeys.googleBooksKey
            if (call.parameters["name"] != "googlebooks") return@post call.respond(dev.jellystructure.model.ProviderTestResult("Nothing to test"))
            if (key.isBlank()) return@post call.respond(dev.jellystructure.model.ProviderTestResult("No API key yet"))
            val check = dev.jellystructure.music.ProviderKeyChecks.checkGoogleBooks(key)
            call.respond(dev.jellystructure.model.ProviderTestResult(check.message, check))
        }
        put("/providers") {
            val req = call.receive<AudiobookProvidersUpdate>()
            val cur = configStore.current
            configStore.update(cur.copy(
                audiobooks = cur.audiobooks.copy(
                    itunesStore = req.itunesStore?.trim()?.lowercase()?.takeIf { it.length == 2 } ?: cur.audiobooks.itunesStore,
                    audnexusRegion = req.audnexusRegion?.trim()?.lowercase()?.takeIf { it in setOf("us", "uk", "ca", "au", "fr", "de", "jp", "it", "in", "es") } ?: cur.audiobooks.audnexusRegion,
                    writeTags = req.writeTags ?: cur.audiobooks.writeTags,
                ),
                apiKeys = cur.apiKeys.copy(googleBooksKey = req.googleBooksKey?.trim() ?: cur.apiKeys.googleBooksKey),
            ))
            call.respond(mapOf("ok" to true))
        }
    }
}
