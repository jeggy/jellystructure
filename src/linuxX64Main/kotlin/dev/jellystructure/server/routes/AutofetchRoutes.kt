package dev.jellystructure.server.routes

import dev.jellystructure.autofetch.AutofetchOverview
import dev.jellystructure.autofetch.AutofetchRuntime
import dev.jellystructure.autofetch.InvestigateGate
import dev.jellystructure.autofetch.InvestigateRequest
import dev.jellystructure.autofetch.InvestigationSave
import dev.jellystructure.autofetch.InvestigationSaveResult
import dev.jellystructure.autofetch.StartResult
import dev.jellystructure.autofetch.RetentionSpec
import dev.jellystructure.autofetch.SaveRequest
import dev.jellystructure.autofetch.SaveResult
import dev.jellystructure.autofetch.SiteView
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.io.FileIo
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

@Serializable
private data class SettingsPatch(val retention: RetentionSpec? = null, val schedule: String? = null, val enabled: Boolean? = null)

@Serializable
private data class PreviewRequest(val retention: RetentionSpec)

@Serializable
private data class PreviewAnswer(val deletes: Int, val keeps: Int, val onPage: Int)

@Serializable
private data class MomentRequest(val at: Int)

@Serializable
private data class SaveAnswer(val fetcher: dev.jellystructure.autofetch.FetcherView, val proven: Boolean, val agreements: Int, val tmdbChecked: Int, val items: Int)

@Serializable
private data class RefusedAnswer(val error: String, val notASeries: Boolean = false, val disagreements: List<String> = emptyList())

/**
 * DEB-2476 (DEB-2474, FR-12) — Auto-fetch's routes, admin-only, behind the admin's existing auth and not on the Ravilo wire.
 * The list (with the queue and the sites' state), save, settings, delete, run, run-all, the retention preview, *Resume site* and *Not on
 * TMDB yet* (DEB-2477: its stills, *Mark as submitted* and *Pick a different moment*); the investigation and the numbering of waiting
 * items belong to the slices that build them.
 */
fun Route.autofetchRoutes(configStore: ConfigStore, runtime: AutofetchRuntime?) {
    get("/autofetch") {
        val rt = runtime ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        call.respond(AutofetchOverview(
            enabled = configStore.current.autofetch.enabled,
            fetchers = rt.store.fetchers().map { rt.view(it) },
            sites = rt.store.sites().map { st -> SiteView(st.site, st.stopped, st.stoppedReason, st.stoppedAt, st.backoffUntil, rt.store.fetchers().count { it.site == st.site }) },
            queue = rt.queueViews(),
            investigate = rt.investigations?.gate() ?: InvestigateGate(false, "The investigation is not running"),
            investigation = rt.investigations?.view(),
        ))
    }

    // ---- DEB-2478 (FR-5, FR-12) — the investigation ----------------------------------------------------------------------------

    post("/autofetch/investigate") {
        val inv = runtime?.investigations ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "The investigation is not running"))
        val req = runCatching { call.receive<InvestigateRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, RefusedAnswer("the request is not a valid investigation"))
        when (val r = inv.start(req)) {
            is StartResult.Started -> call.respond(HttpStatusCode.Accepted, r.view)
            is StartResult.Refused -> call.respond(HttpStatusCode.fromValue(r.status), RefusedAnswer(r.reason))
        }
    }

    get("/autofetch/investigation") {
        val inv = runtime?.investigations ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "The investigation is not running"))
        val v = inv.view() ?: return@get call.respond(HttpStatusCode.NoContent)
        call.respond(v)
    }

    post("/autofetch/investigate/{id}/stop") {
        val inv = runtime?.investigations ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "The investigation is not running"))
        if (inv.stop(call.parameters["id"].orEmpty())) call.respond(HttpStatusCode.OK, mapOf("stopped" to true))
        else call.respond(HttpStatusCode.NotFound, mapOf("error" to "that investigation is not running"))
    }

    delete("/autofetch/investigate/{id}") {
        val inv = runtime?.investigations ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "The investigation is not running"))
        if (inv.discard(call.parameters["id"].orEmpty())) call.respond(HttpStatusCode.NoContent)
        else call.respond(HttpStatusCode.NotFound, mapOf("error" to "that investigation is not finished, or is gone"))
    }

    post("/autofetch/investigate/{id}/retention/preview") {
        val inv = runtime?.investigations ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "The investigation is not running"))
        val req = runCatching { call.receive<PreviewRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "not a valid retention"))
        val n = inv.preview(call.parameters["id"].orEmpty(), req.retention) ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "there is no review to preview"))
        call.respond(PreviewAnswer(n.deletes, n.keeps, n.onPage))
    }

    post("/autofetch/investigate/{id}/save") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val inv = rt.investigations ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "The investigation is not running"))
        val req = runCatching { call.receive<InvestigationSave>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, RefusedAnswer("the request is not a valid save"))
        when (val r = inv.save(call.parameters["id"].orEmpty(), req)) {
            is InvestigationSaveResult.Saved -> {
                // FR-6.2 — *Run the first time: Now* is the press that starts a download; saving alone never does.
                if (r.runFirst && configStore.current.autofetch.enabled) rt.runNow(r.fetcher.id)
                call.respond(HttpStatusCode.Created, SaveAnswer(rt.view(r.fetcher), r.proof.proven, r.proof.agreements, r.proof.tmdbChecked, r.proof.items.size))
            }
            is InvestigationSaveResult.Refused -> call.respond(HttpStatusCode.fromValue(r.status), RefusedAnswer(r.reason, disagreements = r.proof?.disagreements.orEmpty()))
        }
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
        call.respond(PreviewAnswer(n.deletes, n.keeps, n.onPage))
    }

    post("/autofetch/sites/{site}/resume") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val problem = rt.service.resume(call.parameters["site"].orEmpty())
        if (problem == null) call.respond(HttpStatusCode.OK, mapOf("resumed" to true)) else call.respond(HttpStatusCode.Conflict, mapOf("error" to problem))
    }

    // FR-9.4 — a still: a JPEG cut on this server, kept in the application's data directory and served to the admin only.
    get("/autofetch/queue/{id}/still/{n}") {
        val rt = runtime ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val id = call.parameters["id"]?.toLongOrNull()
        val n = call.parameters["n"]?.toIntOrNull()
        if (id == null || n == null || rt.store.queueRow(id) == null) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such still"))
        val bytes = runCatching { FileIo.readBytes(Path(rt.service.stillPath(id, n))) }.getOrNull()
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "that still was not cut"))
        // A frame cut again at another moment keeps its address, so the browser must not hold the old one.
        call.response.headers.append("Cache-Control", "no-store")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }

    // FR-9.4 — *Pick a different moment*: one slot is cut again at the timestamp the admin gives.
    post("/autofetch/queue/{id}/still/{n}") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val id = call.parameters["id"]?.toLongOrNull()
        val n = call.parameters["n"]?.toIntOrNull()
        val req = runCatching { call.receive<MomentRequest>() }.getOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "not a valid moment"))
        if (id == null || n == null || rt.store.queueRow(id) == null) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such still"))
        if (rt.engine.queue.cutAgain(id, n, req.at)) call.respond(HttpStatusCode.OK, mapOf("at" to req.at))
        else call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to "no frame could be cut there"))
    }

    // FR-9.3 — *Mark as submitted* is a note that the admin entered it on TMDB. Nothing is sent anywhere.
    post("/autofetch/queue/{id}/submitted") {
        val rt = runtime ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Auto-fetch is not running"))
        val id = call.parameters["id"]?.toLongOrNull()
        if (id == null || rt.store.queueRow(id) == null) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such episode"))
        if (rt.engine.queue.markSubmitted(id)) call.respond(HttpStatusCode.OK, mapOf("submitted" to true))
        else call.respond(HttpStatusCode.Conflict, mapOf("error" to "TMDB already has that episode"))
    }
}
