package dev.jellystructure.server.routes

import dev.jellystructure.media.ContainerLanguage
import dev.jellystructure.media.MediaHistory
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * DEB-2051 (FR-1) — the one refusal every language-write route makes for a file whose container cannot keep a
 * per-stream language. Writes nothing: no remux, no work file, no stored change. True when it answered, so the route
 * returns; the refusal is recorded in the title's history like other refused edits.
 */
internal suspend fun ApplicationCall.refuseLanguageEditIfContainerCannot(
    path: String,
    mediaHistory: MediaHistory,
    mediaId: String,
    detail: String,
): Boolean {
    val container = ContainerLanguage.cannotStore(path) ?: return false
    mediaHistory.record(mediaId, "set_language", "$detail REFUSED ($container cannot store a language)")
    respond(HttpStatusCode.UnprocessableEntity, ContainerLanguage.refusal(container))
    return true
}
