package dev.jellystructure.server.routes

import dev.jellystructure.advisor.BazarrAdvisorService
import dev.jellystructure.auth.SessionKey
import dev.jellystructure.auth.constantTimeEquals
import dev.jellystructure.bazarr.BazarrSteering
import dev.jellystructure.bazarr.SubtitleHook
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Subtitle_action
import dev.jellystructure.db.Subtitle_check
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.subtitles.SubtitleCheckService
import dev.jellystructure.subtitles.SubtitleVerdicts
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Phase 273 — one sidecar's verdict for the admin (§F). The admin only; nothing here reaches Ravilo. */
@Serializable
data class SubtitleCheckDto(
    @SerialName("sidecar_path") val sidecarPath: String,
    val name: String,
    @SerialName("video_path") val videoPath: String,
    val language: String?,
    val hi: Boolean,
    val verdict: String,
    val reason: String? = null,
    /** The verdict in the admin's words (FR-273-20). */
    val words: String,
    val offered: Boolean,
    val reference: String? = null,
    val rho: Double? = null,
    val z: Double? = null,
    @SerialName("shift_ms") val shiftMs: Long? = null,
    val scale: Double? = null,
    @SerialName("worst_ms") val worstMs: Long? = null,
    @SerialName("match_label") val matchLabel: String? = null,
    @SerialName("checked_at") val checkedAt: Long,
)

@Serializable
data class SubtitleActionDto(
    val id: Long,
    val action: String,
    val state: String,
    @SerialName("sidecar_name") val sidecarName: String? = null,
    @SerialName("video_path") val videoPath: String,
    val language: String? = null,
    val detail: String,
    @SerialName("created_at") val createdAt: Long,
)

/** FR-273-23 — a language jellystructure emptied and nothing right has arrived for since. */
@Serializable
data class EmptyLanguageDto(
    @SerialName("video_path") val videoPath: String,
    val language: String?,
    val tried: Int,
    val words: String,
)

@Serializable
data class TitleSubtitleChecksDto(
    val checks: List<SubtitleCheckDto>,
    val waiting: List<SubtitleActionDto>,
    val actions: List<SubtitleActionDto>,
    val empty: List<EmptyLanguageDto> = emptyList(),
)

@Serializable
data class SubtitleSummaryDto(
    val mode: String,
    val counts: Map<String, Long>,
    @SerialName("not_offered") val notOffered: Int,
    val waiting: List<SubtitleActionDto>,
    @SerialName("downloads_today") val downloadsToday: Long,
    @SerialName("daily_budget") val dailyBudget: Int,
    @SerialName("hook_last_called_at") val hookLastCalledAt: Long? = null,
    @SerialName("bazarr_connected") val bazarrConnected: Boolean,
    val recent: List<SubtitleActionDto>,
    /** Dev review item 8 — what *Fix it* would do with today's verdicts; sent only while the switch says *Only report*. */
    @SerialName("fix_would") val fixWould: FixWouldDto? = null,
)

@Serializable
data class FixWouldDto(val sync: Int, val replace: Int, val move: Int, val ask: Int, val hide: Int)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun nowSeconds(): Long = platform.posix.time(null)

private fun Subtitle_action.dto() = SubtitleActionDto(
    id, action, state, sidecar_path?.substringAfterLast('/'), video_path, language,
    detail.substringAfter(';', detail).takeIf { detail.startsWith("hash=") } ?: detail, created_at,
)

private fun Subtitle_check.dto(service: SubtitleCheckService) = SubtitleCheckDto(
    sidecarPath = sidecar_path, name = sidecar_path.substringAfterLast('/'), videoPath = video_path, language = language, hi = hi == 1L,
    verdict = verdict, reason = reason, words = service.verdictWords(this), offered = !SubtitleVerdicts.isHidden(this) || SubtitleVerdicts.reportOnly(),
    reference = reference, rho = rho, z = z, shiftMs = shift_ms, scale = scale, worstMs = worst_ms, matchLabel = match_label, checkedAt = checked_at,
)

/**
 * Phase 273 — the Bazarr hook (open, gated by the per-install webhook secret like 165's), the admin's verdicts and
 * *Needs your OK*, and the Bazarr advisor with *Apply in Bazarr*.
 */
fun Route.subtitleCheckRoutes(
    configStore: ConfigStore,
    db: JellystructureDb,
    mediaStore: MediaStore,
    checks: SubtitleCheckService,
    steering: BazarrSteering?,
    hook: SubtitleHook?,
    advisor: BazarrAdvisorService?,
) {
    // FR-273-9 — Bazarr's Custom Post-Processing command posts here after every placed subtitle; queued, answered at once.
    post("/webhooks/bazarr") {
        val secret = configStore.current.ingest.webhookSecret
        val provided = call.request.queryParameters["secret"]
        if (secret.isBlank() || provided == null || !constantTimeEquals(provided, secret)) {
            Logger.warn("Webhook rejected: bad secret (bazarr)", "ingest")
            return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "invalid secret"))
        }
        val h = hook ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Bazarr is not connected"))
        val p = runCatching { call.receiveParameters() }.getOrNull()
        fun v(k: String) = p?.get(k) ?: call.request.queryParameters[k]
        h.accept(SubtitleHook.Call(v("subtitles"), v("language"), v("provider"), v("subtitle_id"), v("series_id"), v("episode_id")))
        call.respond(HttpStatusCode.Accepted, mapOf("queued" to true))
    }

    get("/subtitles/checks") {
        val itemId = call.request.queryParameters["item"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val item = mediaStore.resolve(itemId) ?: return@get call.respond(HttpStatusCode.NotFound)
        val q = db.subtitleCheckQueries
        val acts = q.actionsForItem(item.id).executeAsList()
        val rows = checks.checksForItem(item.id)
        // FR-273-23 — rather nothing than wrong: a language whose wrong subtitles were thrown away and that has no
        // sidecar since says so, with how many were tried.
        val present = rows.map { it.video_path to it.language }.toSet()
        val thrownAway = setOf(BazarrSteering.BLACKLIST, BazarrSteering.REMOVE, BazarrSteering.NEIGHBOUR)
        val empty = acts.filter { it.action in thrownAway && it.state == BazarrSteering.DONE }
            .groupBy { it.video_path to it.language }
            .filterKeys { it !in present }
            .map { (k, tried) -> EmptyLanguageDto(k.first, k.second, tried.size, "No right subtitle found yet · ${tried.size} tried") }
        call.respond(TitleSubtitleChecksDto(
            checks = rows.map { it.dto(checks) },
            waiting = acts.filter { it.state == BazarrSteering.WAITING }.map { it.dto() },
            actions = acts.filter { it.state != BazarrSteering.WAITING }.take(50).map { it.dto() },
            empty = empty,
        ))
    }

    get("/subtitles/summary") {
        val q = db.subtitleCheckQueries
        val now = nowSeconds()
        call.respond(SubtitleSummaryDto(
            mode = configStore.current.subtitleCheck.action,
            counts = q.countByVerdict().executeAsList().associate { it.verdict to it.n },
            notOffered = SubtitleVerdicts.hiddenCount(),
            waiting = q.waiting().executeAsList().map { it.dto() },
            downloadsToday = q.downloadsSince(now - now % 86_400).executeAsOne(),
            dailyBudget = configStore.current.subtitleCheck.dailyDownloadBudget,
            hookLastCalledAt = hook?.lastCalledAtMs(),
            bazarrConnected = configStore.current.bazarr?.let { it.enabled && it.url.isNotBlank() } == true,
            recent = q.recentActions(20).executeAsList().map { it.dto() },
            fixWould = if (!configStore.current.subtitleCheck.reportOnly) null
                else BazarrSteering.preview(q.allChecks().executeAsList()).let { FixWouldDto(it.sync, it.replace, it.move, it.ask, it.hide) },
        ))
    }

    post("/subtitles/actions/{id}/approve") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val s = steering ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Bazarr is not connected"))
        call.respond(if (s.approve(id)) HttpStatusCode.OK else HttpStatusCode.Conflict, mapOf("ok" to true))
    }

    post("/subtitles/actions/{id}/dismiss") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
        val s = steering ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Bazarr is not connected"))
        call.respond(if (s.dismiss(id)) HttpStatusCode.OK else HttpStatusCode.Conflict, mapOf("ok" to true))
    }

    // FR-273-16 — the switch, the budget and where Bazarr reaches this server (the Bazarr card).
    get("/subtitles/settings") { call.respond(configStore.current.subtitleCheck) }

    put("/subtitles/settings") {
        val req = runCatching { call.receive<dev.jellystructure.config.SubtitleCheckConfig>() }.getOrNull()
            ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Body must be {action, daily_download_budget, bazarr_reach_url}"))
        if (req.action !in setOf("fix", "ask", "report")) return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "action must be fix, ask or report"))
        val clean = req.copy(dailyDownloadBudget = req.dailyDownloadBudget.coerceIn(0, 1_000), bazarrReachUrl = req.bazarrReachUrl.trim().trimEnd('/'))
        configStore.update(configStore.current.copy(subtitleCheck = clean))
        advisor?.invalidate()
        Logger.info("Subtitle check: ${clean.action}, budget ${clean.dailyDownloadBudget} a day", "subtitles")
        call.respond(clean)
    }

    get("/bazarr/advisor") {
        val a = advisor ?: return@get call.respond(dev.jellystructure.advisor.BazarrAdvisorResponse(configured = false, reachable = false))
        if (call.request.queryParameters["fresh"] == "true") a.invalidate()
        call.respond(a.advise())
    }

    post("/bazarr/advisor/{finding}/apply") {
        val session = runCatching { call.attributes[SessionKey] }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not logged in"))
        val a = advisor ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Bazarr is not connected"))
        val finding = call.parameters["finding"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        call.respond(a.apply(finding, session.jellyfinUsername))
    }
}
