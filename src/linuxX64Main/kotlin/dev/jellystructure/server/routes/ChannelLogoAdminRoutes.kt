package dev.jellystructure.server.routes

import dev.jellystructure.auth.SessionKey
import dev.jellystructure.shared.tv.ChannelLogoInUse
import dev.jellystructure.tv.ChannelLogoStore
import dev.jellystructure.tv.ChannelLogoUsage
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get

/**
 * DEB-1788 — the admin's stored-logo list (with who uses each) and the removal of one nothing uses. The upload and
 * the public serve route stay with the rest of the channel-logo routes in [tvRoutes].
 */
fun Route.channelLogoAdminRoutes(store: ChannelLogoStore, usage: ChannelLogoUsage) {
    // FR-3 — `usedBy` is computed on each request.
    get("/tv/admin/channel-logos") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val used = usage.byFile()
        call.respond(store.list().map { it.copy(usedBy = used[it.url.substringAfterLast('/')].orEmpty()) })
    }

    // FR-1 — 204 removed, 404 no such stored logo, 409 in use (and nothing is deleted).
    delete("/tv/admin/channel-logos/{name}") {
        runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val name = call.parameters["name"].orEmpty()
        if (!store.exists(name)) return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such logo"))
        val usedBy = usage.byFile()[name].orEmpty()
        if (usedBy.isNotEmpty()) {
            return@delete call.respond(HttpStatusCode.Conflict, ChannelLogoInUse(ChannelLogoUsage.refusal(usedBy), usedBy))
        }
        if (!store.remove(name)) return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "No such logo"))
        call.respond(HttpStatusCode.NoContent)
    }
}
