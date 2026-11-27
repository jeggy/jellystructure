package dev.jellystructure.server.routes

import dev.jellystructure.auth.DeviceKey
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.samsung.SamsungTvRequests
import dev.jellystructure.samsung.SamsungTvRelease
import dev.jellystructure.samsung.samsungCertificateStatus
import dev.jellystructure.shared.tv.SamsungTvAdminList
import dev.jellystructure.shared.tv.SamsungTvDecision
import dev.jellystructure.shared.tv.SamsungTvRequestCreate
import dev.jellystructure.shared.tv.SamsungTvRequestList
import dev.jellystructure.tv.RaviloDeviceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

private suspend inline fun <reified T : Any> ApplicationCall.receiveSamsung(): T? = try {
    receive<T>()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** DEB-4842 (FR-2/3) — auth is enforced by AuthPlugin before these handlers. */
internal fun Route.samsungTvRoutes(requests: SamsungTvRequests, release: SamsungTvRelease, devices: RaviloDeviceService) {
    get("/tv/samsung-requests") {
        val accountId = call.attributes[DeviceKey].jellyfinUserId.lowercase().replace("-", "")
        call.respond(SamsungTvRequestList(requests.mine(accountId).map { it.viewer() }))
    }
    post("/tv/samsung-requests") {
        val accountId = call.attributes[DeviceKey].jellyfinUserId.lowercase().replace("-", "")
        val input = call.receiveSamsung<SamsungTvRequestCreate>()
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
        when (val result = requests.create(accountId, input)) {
            is SamsungTvRequests.CreateResult.Saved -> call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.row.viewer())
            SamsungTvRequests.CreateResult.Invalid -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
            SamsungTvRequests.CreateResult.AtCap -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "request_limit"))
        }
    }
    delete("/tv/samsung-requests/{id}") {
        val accountId = call.attributes[DeviceKey].jellyfinUserId.lowercase().replace("-", "")
        val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
        val result = requests.withdraw(accountId, id)
        when (result.kind) {
                SamsungTvRequests.Transition.SAVED -> call.respond(result.row!!.viewer())
                SamsungTvRequests.Transition.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
                SamsungTvRequests.Transition.CONFLICT -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_approved"))
                SamsungTvRequests.Transition.INVALID -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
        }
    }
    get("/samsung-tv/requests") {
        val names = devices.allDevices().associate { it.jellyfinUserId.lowercase().replace("-", "") to it.jellyfinUsername }
        val rows = requests.all()
        call.respond(SamsungTvAdminList(
            rows.map { it.admin(names[it.accountId] ?: it.accountId) },
            release.status(), samsungCertificateStatus(), rows.count { it.state == "REQUESTED" },
        ))
    }
    post("/samsung-tv/requests/{id}/decision") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
        val input = call.receiveSamsung<SamsungTvDecision>()
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
        val by = call.attributes[SessionKey].jellyfinUsername
        val result = requests.decide(id, input, by, release.verifiedRelease())
        when (result.kind) {
            SamsungTvRequests.Transition.SAVED -> {
                val names = devices.allDevices().associate { it.jellyfinUserId.lowercase().replace("-", "") to it.jellyfinUsername }
                call.respond(result.row!!.admin(names[result.row.accountId] ?: result.row.accountId))
            }
            SamsungTvRequests.Transition.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
            SamsungTvRequests.Transition.CONFLICT -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "already_decided"))
            SamsungTvRequests.Transition.INVALID -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
        }
    }
}
