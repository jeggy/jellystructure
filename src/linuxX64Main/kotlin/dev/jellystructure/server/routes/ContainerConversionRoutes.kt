package dev.jellystructure.server.routes

import dev.jellystructure.media.ContainerConversion
import dev.jellystructure.media.ContainerLanguage
import dev.jellystructure.media.ConversionPreview
import dev.jellystructure.media.ConversionRequested
import dev.jellystructure.media.ConversionStream
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.media.MediaJobQueue
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.StartConversionRequest
import dev.jellystructure.media.StartConversionResponse
import dev.jellystructure.media.WorkFiles
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.resolver.LanguageResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** DEB-2051 — only an AVI *film* has a conversion to offer (an episode's Sonarr identity move has no port yet). */
internal fun MediaItem.convertibleAvi(): Boolean = kind == MediaKind.MOVIE && ContainerLanguage.cannotStore(path) != null

/**
 * DEB-2051 (FR-4, FR-6) — the conversion's preview and its Start. Both are the admin's (the `/api` session), and both ask
 * FR-5 on the server: the preview shows the verdict, Start never trusts it.
 */
fun Route.containerConversionRoutes(store: MediaStore, mediaJobQueue: MediaJobQueue, mediaHistory: MediaHistory) {
    route("/media/{id}/container-conversion") {
        // GET — read-only: what the notice, the preview and the confirmation show, and the file's latest job.
        get {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@get call.respond(HttpStatusCode.NotFound)
            val tracks = call.request.queryParameters.getAll("track").orEmpty()
            val languages = call.request.queryParameters.getAll("language").orEmpty()
            val requested = tracks.zip(languages).map { (t, l) -> ConversionRequested(t, l) }
            call.respond(preview(item, requested, store, mediaJobQueue))
        }

        // POST — FR-5 again, then one job. A job already queued or running for the title is returned, not a second one.
        post {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest)
            val item = store.resolve(id) ?: return@post call.respond(HttpStatusCode.NotFound)
            val req = call.receive<StartConversionRequest>()
            if (!item.convertibleAvi()) {
                return@post call.respond(HttpStatusCode.UnprocessableEntity, StartConversionResponse(error = "Only an AVI film can be converted to MKV."))
            }
            val converter = mediaJobQueue.containerConverter
                ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, StartConversionResponse(error = "Conversion isn't set up on this server."))
            val bad = req.requested.firstOrNull { r -> item.tracks.none { it.specifier == r.specifier && !it.external } || LanguageResolver.toIso6392(r.language) == null }
            if (req.requested.isEmpty() || bad != null) {
                return@post call.respond(HttpStatusCode.BadRequest, StartConversionResponse(error =
                    if (bad != null) "Track ${bad.specifier} or its language isn't valid." else "Name the track and language to carry into the MKV."))
            }
            // A second press while one is queued or running returns that job; it does not start a second or refuse.
            mediaJobQueue.latestConversion(id)?.takeIf { it.state == ContainerConversion.QUEUED || it.state == ContainerConversion.RUNNING }?.let {
                return@post call.respond(HttpStatusCode.Accepted, StartConversionResponse(jobId = it.jobId))
            }
            converter.safety(item)?.let { block ->
                mediaHistory.record(id, "container_convert", "REFUSED: ${block.message}")
                return@post call.respond(HttpStatusCode.Conflict, StartConversionResponse(block = block, error = block.message))
            }
            val job = mediaJobQueue.enqueueContainerConversion(id, item.title, req.requested)
            mediaHistory.record(id, "container_convert", "queued: AVI → MKV carrying ${req.requested.joinToString(", ") { "${it.specifier}=${it.language}" }}")
            call.respond(HttpStatusCode.Accepted, StartConversionResponse(jobId = job.id))
        }
    }
}

private suspend fun preview(item: MediaItem, requested: List<ConversionRequested>, store: MediaStore, queue: MediaJobQueue): ConversionPreview {
    val latest = queue.latestConversion(item.id)
    val converter = queue.containerConverter
    if (!item.convertibleAvi() || converter == null) {
        // After a verified conversion the stored path is the `.mkv`: no offer, but the notice still reads the job.
        if (latest == null) return ConversionPreview(available = false)
        val base = item.path.substringBeforeLast('.')
        return ConversionPreview(
            available = false, destinationPath = item.path, keptPath = "${WorkFiles.dirFor(item.path)}/${base.substringAfterLast('/')}.avi",
            latest = latest,
        )
    }
    val source = item.path
    val size = SystemFileSystem.metadataOrNull(Path(source))?.size
    val seconds = ContainerConversion.estimateSeconds(size, queue.recentRemuxThroughputs())
    return ConversionPreview(
        available = true,
        sourcePath = source,
        destinationPath = ContainerConversion.destinationOf(source),
        keptPath = ContainerConversion.keptPathOf(source),
        streams = item.tracks.filter { !it.external }.map { ConversionStream(it.specifier, it.kind.name.lowercase(), it.codec) },
        requested = requested,
        estimateSeconds = seconds,
        tempBytes = size?.takeIf { seconds != null },
        block = converter.safety(item),
        latest = latest,
    )
}
