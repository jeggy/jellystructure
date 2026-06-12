package dev.jellystructure.server.routes

import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.jobs.WsBroadcaster
import dev.jellystructure.media.ArtworkDownloader
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.Scanner
import dev.jellystructure.media.ScanTracker
import dev.jellystructure.model.MediaKind
import dev.jellystructure.nfo.NfoWriter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun Route.mediaRoutes(
    store: MediaStore,
    scanner: Scanner,
    artwork: ArtworkDownloader,
    appScope: CoroutineScope,
    scanTracker: ScanTracker,
    broadcaster: WsBroadcaster,
) {
    route("/media") {
        get {
            val kindStr = call.request.queryParameters["kind"]
            val kind = kindStr?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
            val filter = call.request.queryParameters["filter"]
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()
                ?.coerceIn(1, 100) ?: 20
            call.respond(store.list(kind, filter, page, pageSize))
        }

        route("/{id}") {
            get {
                val id = call.parameters["id"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val item = store.get(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(item)
            }

            route("/nfo") {
                get {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    val raw = NfoWriter.readRaw(item)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respondText(raw, ContentType.Text.Xml)
                }

                post {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    NfoWriter.write(item)
                        .onSuccess { path -> call.respond(mapOf("path" to path)) }
                        .onFailure { e ->
                            println("[ERROR] NFO write failed for $id: ${e.message}")
                            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "write failed")))
                        }
                }
            }

            route("/artwork") {
                get {
                    val id = call.parameters["id"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
                        ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.respond(artwork.check(item))
                }

                post {
                    val id = call.parameters["id"]
                        ?: return@post call.respond(HttpStatusCode.BadRequest)
                    val item = store.get(id)
                        ?: return@post call.respond(HttpStatusCode.NotFound)
                    call.respond(artwork.fetch(item))
                }
            }
        }
    }

    post("/scan") {
        if (scanTracker.running) {
            call.respond(HttpStatusCode.Conflict, mapOf("error" to "scan already running"))
            return@post
        }
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        val jobId = "scan-${platform.posix.time(null)}"
        appScope.launch {
            scanTracker.running = true
            val allItems = mutableListOf<dev.jellystructure.model.MediaItem>()
            var succeeded = 0
            try {
                println("[INFO] Library scan started (background) jobId=$jobId")
                broadcaster.broadcast(JobEvent.Started(jobId, -1))
                scanner.scan { item ->
                    allItems += item
                    store.addOrUpdate(item)
                    succeeded++
                    scanTracker.lastCount = succeeded
                    broadcaster.broadcast(JobEvent.ItemScanned(jobId, item))
                }
                store.update(allItems)
                scanTracker.lastCount = succeeded
                println("[INFO] Library scan complete — $succeeded items")
                broadcaster.broadcast(JobEvent.Finished(jobId, succeeded, 0))
            } catch (e: Exception) {
                println("[ERROR] Scan failed: ${e.message}")
                broadcaster.broadcast(JobEvent.Finished(jobId, succeeded, 1))
            } finally {
                scanTracker.running = false
            }
        }
        call.respond(HttpStatusCode.Accepted, mapOf("status" to "started"))
    }

    get("/scan/status") {
        call.respond(scanTracker.status())
    }

    get("/stats") {
        call.respond(
            mapOf(
                "movies" to store.movieCount(),
                "issues" to store.totalIssueCount(),
            )
        )
    }
}
