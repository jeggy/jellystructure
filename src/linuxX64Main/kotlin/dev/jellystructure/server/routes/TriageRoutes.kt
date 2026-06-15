package dev.jellystructure.server.routes

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FfprobeRunner
import dev.jellystructure.media.MediaStore
import dev.jellystructure.media.MkvpropeditRunner
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.TrackKind
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class TriageTrack(
    val specifier: String,
    val streamIndex: Int,
    val kind: String,
    val codec: String,
    val title: String? = null,
)

@Serializable
data class CascadeMismatch(
    val resolvedLanguage: String,
    val expectedDefaultSpecifier: String,
    val actualDefaultLang: String? = null,
)

@Serializable
data class TriageItem(
    val mediaId: String,
    val title: String,
    val year: Int?,
    val path: String,
    val untaggedTracks: List<TriageTrack>,
    val cascadeMismatch: CascadeMismatch? = null,
)

@Serializable
private data class AssignLanguageRequest(val language: String)

fun Route.triageRoutes(store: MediaStore, jellyfinClient: JellyfinClient, configStore: ConfigStore) {
    route("/triage") {
        get {
            val items = store.allItems()
                .mapNotNull { it.toTriageItem() }
            call.respond(items)
        }

        post("/{mediaId}/tracks/{specifier}/language") {
            val mediaId = call.parameters["mediaId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val specifier = call.parameters["specifier"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val item = store.get(mediaId)
                ?: return@post call.respond(HttpStatusCode.NotFound)

            val track = item.tracks.firstOrNull { it.specifier == specifier }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "track not found"))

            val req = call.receive<AssignLanguageRequest>()
            val lang = req.language.trim()
            if (lang.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "language is required"))
                return@post
            }

            val ext = item.path.substringAfterLast('.').lowercase()
            val ok = if (ext == "mkv") {
                MkvpropeditRunner.setLanguage(item.path, track.streamIndex, lang)
            } else {
                FfmpegRunner.setLanguage(item.path, track.streamIndex, lang)
            }

            if (!ok) {
                val tool = if (ext == "mkv") "mkvpropedit" else "ffmpeg"
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "$tool failed"))
                return@post
            }

            val newTracks = FfprobeRunner.probe(item.path)
            val newIssueCount = newTracks.count {
                (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null
            }
            val updated = item.copy(tracks = newTracks, issueCount = newIssueCount)
            store.updateOne(updated)

            val cfg = configStore.current
            if (!item.jellyfinId.isNullOrBlank() && cfg.apiKeys.jellyfinUrl.isNotBlank()) {
                jellyfinClient.refreshItem(cfg.apiKeys.jellyfinUrl, cfg.apiKeys.jellyfinToken, item.jellyfinId)
            }

            call.respond(mapOf("ok" to true, "language" to lang))
        }
    }
}

private fun MediaItem.toTriageItem(): TriageItem? {
    val untagged = tracks
        .filter { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
        .map { t ->
            TriageTrack(
                specifier = t.specifier,
                streamIndex = t.streamIndex,
                kind = t.kind.name.lowercase(),
                codec = t.codec,
                title = t.title,
            )
        }
    val mismatch = detectCascadeMismatch()
    if (untagged.isEmpty() && mismatch == null) return null
    return TriageItem(
        mediaId = id,
        title = title,
        year = year,
        path = path,
        untaggedTracks = untagged,
        cascadeMismatch = mismatch,
    )
}

private fun MediaItem.detectCascadeMismatch(): CascadeMismatch? {
    if (languageMix || resolvedLanguage.isNullOrBlank()) return null
    val audioTracks = tracks.filter { it.kind == TrackKind.AUDIO }
    val expectedTrack = audioTracks.firstOrNull { it.language == resolvedLanguage } ?: return null
    val currentDefault = audioTracks.firstOrNull { it.default }
    if (currentDefault != null && currentDefault.specifier == expectedTrack.specifier) return null
    return CascadeMismatch(
        resolvedLanguage = resolvedLanguage,
        expectedDefaultSpecifier = expectedTrack.specifier,
        actualDefaultLang = currentDefault?.language,
    )
}
