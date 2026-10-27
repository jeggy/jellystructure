package dev.jellystructure.server.routes

import dev.jellystructure.autofetch.AutofetchOverview
import dev.jellystructure.autofetch.AutofetchRuntime
import dev.jellystructure.autofetch.RetentionSpec
import dev.jellystructure.autofetch.SaveRequest
import dev.jellystructure.autofetch.SaveResult
import dev.jellystructure.autofetch.SiteView
import dev.jellystructure.config.ConfigStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
private data class SettingsPatch(val retention: RetentionSpec? = null, val schedule: String? = null, val enabled: Boolean? = null)

@Serializable
private data class PreviewRequest(val retention: RetentionSpec)

@Serializable
private data class PreviewAnswer(val deletes: Int)

@Serializable
private data class SaveAnswer(val fetcher: dev.jellystructure.autofetch.FetcherView, val proven: Boolean, val agreements: Int, val tmdbChecked: Int, val items: Int)

@Serializable
private data class RefusedAnswer(val error: String, val notASeries: Boolean = false, val disagreements: List<String> = emptyList())

/**
 * DEB-2476 (DEB-2474, FR-12) — Auto-fetch's routes, admin-only, behind the admin's existing auth and not on the Ravilo wire.
 * This slice carries the list, save, settings, delete, run, run-all, the retention preview and *Resume site*; the
 * investigation, the numbering of waiting items and the queue belong to the slices that build them.
 */
fun Route.autofetchRoutes(configStore: ConfigStore, runtime: AutofetchRuntime?) {
    get("/autofetch") {
        val rt = runtime ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        call.respond(AutofetchOverview(
            enabled = configStore.current.autofetch.enabled,
            fetchers = rt.store.fetchers().map { rt.view(it) },
            sites = rt.store.sites().map { SiteView(it.site, it.stopped, it.stoppedReason, it.stoppedAt, it.backoffUntil) },
        ))
    }

    post("/autofetch") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val req = runCatching { call.receive<SaveRequest>() }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, RefusedAnswer("the request is not a valid save request"))
        when (val r = rt.service.save(req)) {
            is SaveResult.Saved -> call.respond(HttpStatusCode.Created, SaveAnswer(rt.view(r.fetcher), r.proof.proven, r.proof.agreements, r.proof.tmdbChecked, r.proof.items.size))
            is SaveResult.Refused -> call.respond(HttpStatusCode.fromValue(r.status), RefusedAnswer(r.reason, r.notASeries, r.proof?.disagreements.orEmpty()))
        }
    }

    patch("/autofetch/{id}") {
        val rt = runtime ?: return@patch call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val id = call.parameters["id"].orEmpty()
        val p = runCatching { call.receive<SettingsPatch>() }.getOrNull() ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "not a valid settings change"))
        val problem = rt.service.update(id, p.retention, p.schedule, p.enabled)
        if (problem == null) call.respond(rt.view(rt.store.fetcher(id)!!))
        else call.respond(if (problem == "no such fetcher") HttpStatusCode.NotFound else HttpStatusCode.BadRequest, mapOf("error" to problem))
    }

    delete("/autofetch/{id}") {
        val rt = runtime ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        if (rt.service.delete(call.parameters["id"].orEmpty())) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such fetcher"))
    }

    post("/autofetch/{id}/run") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        if (!configStore.current.autofetch.enabled) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Auto-fetch is switched off ([autofetch] enabled)"))
        if (rt.runNow(call.parameters["id"].orEmpty())) call.respond(HttpStatusCode.Accepted, mapOf("started" to true))
        else call.respond(HttpStatusCode.Conflict, mapOf("error" to "that fetcher does not exist or is already running"))
    }

    post("/autofetch/run-all") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        if (!configStore.current.autofetch.enabled) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "Auto-fetch is switched off ([autofetch] enabled)"))
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to rt.runAll()))
    }

    post("/autofetch/{id}/retention/preview") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val req = runCatching { call.receive<PreviewRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "not a valid retention"))
        val n = rt.service.preview(call.parameters["id"].orEmpty(), req.retention) ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such fetcher"))
        call.respond(PreviewAnswer(n))
    }

    post("/autofetch/sites/{site}/resume") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val problem = rt.service.resume(call.parameters["site"].orEmpty())
        if (problem == null) call.respond(HttpStatusCode.OK, mapOf("resumed" to true)) else call.respond(HttpStatusCode.Conflict, mapOf("error" to problem))
    }
}
