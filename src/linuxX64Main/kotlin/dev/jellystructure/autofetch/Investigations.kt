package dev.jellystructure.autofetch

import dev.jellystructure.ai.AiPricing
import dev.jellystructure.log.Logger
import dev.jellystructure.ops.GateClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** `POST /api/autofetch/investigate`. [fetcherId] is *Re-investigate page*: the url, TMDB id and mode are the fetcher's own. */
@Serializable
data class InvestigateRequest(val url: String = "", val tmdbId: Int? = null, val pageOnly: Boolean = false, val fetcherId: String? = null)

/** What the review screen sends with *Save auto-fetcher* (FR-10.1). The recipe is never sent back: the server holds the one it proved. */
@Serializable
data class InvestigationSave(
    val name: String? = null,
    val libraryPath: String? = null,
    val schedule: String? = null,
    val retention: RetentionSpec? = null,
    val runFirst: Boolean = true,
    /** `mapped` numbering (FR-4.3): the ids of the review's rows the admin ticked. Only those are given a number. */
    val accept: List<String> = emptyList(),
)

sealed class StartResult {
    data class Started(val view: InvestigationView) : StartResult()
    data class Refused(val status: Int, val reason: String) : StartResult()
}

sealed class InvestigationSaveResult {
    data class Saved(val fetcher: Fetcher, val proof: ProofResult, val runFirst: Boolean) : InvestigationSaveResult()
    data class Refused(val status: Int, val reason: String, val proof: ProofResult? = null) : InvestigationSaveResult()
}

/**
 * DEB-2478 (DEB-2474, FR-5.5, FR-12) — the one investigation the server runs at a time, and what it holds afterwards: the proved recipe
 * of a finished one, until the admin saves or discards it. *Stop* ends it, discards what it found and writes nothing. It lives in memory:
 * a restart ends an investigation and costs what its ledger rows already say.
 */
class Investigations(
    private val ai: InvestigationAi,
    private val investigator: Investigator,
    private val service: AutofetchService,
    private val store: AutofetchStore,
    private val world: AutofetchWorld,
    private val scope: CoroutineScope,
    private val newId: () -> String,
) {
    val current = MutableStateFlow<InvestigationView?>(null)
    private var job: Job? = null
    private var held: Held? = null

    fun view(): InvestigationView? = current.value

    /** FR-1.2 — whether *Investigate* is on, and if not, the reason it is shown disabled with. */
    fun gate(): InvestigateGate {
        if (!world.enabled()) return InvestigateGate(false, "Auto-fetch is switched off ([autofetch] enabled in config.toml)")
        val s = ai.settings() ?: return InvestigateGate(false, ai.offReason())
        val spent = ai.spentMicroUsd()
        if (spent >= s.limitMicroUsd) return InvestigateGate(false, "This month's Auto-fetch limit is spent (${AiPricing.usd(spent)} of ${AiPricing.usd(s.limitMicroUsd)})")
        if (current.value?.state == "running") return InvestigateGate(false, "An investigation is already running")
        return InvestigateGate(true)
    }

    suspend fun start(req: InvestigateRequest): StartResult {
        if (!world.enabled()) return StartResult.Refused(409, "Auto-fetch is switched off ([autofetch] enabled in config.toml)")
        ai.settings() ?: return StartResult.Refused(409, ai.offReason())
        if (current.value?.state == "running") return StartResult.Refused(409, "an investigation is already running; Auto-fetch does one at a time")
        val fetcher = req.fetcherId?.let { store.fetcher(it) ?: return StartResult.Refused(404, "no such fetcher") }
        val url = fetcher?.pageUrl ?: req.url.trim()
        val tmdbId = if (fetcher != null) fetcher.tmdbId else req.tmdbId
        val pageOnly = fetcher?.pageOnly ?: req.pageOnly
        if (Urls.siteOf(url) == null) return StartResult.Refused(400, "the page URL is not an http or https address")
        // FR-15.1 — a page-only fetcher is chosen, never inferred.
        if (tmdbId == null && !pageOnly) return StartResult.Refused(400, RecipeRefusal.NO_TMDB_ID)
        if (tmdbId != null && pageOnly) return StartResult.Refused(400, "A page-only fetcher has no TMDB id")
        // FR-3.1 — a series only; checked before a cent is spent.
        if (tmdbId != null) when (world.tmdbKind(tmdbId)) {
            TmdbKind.FILM -> return StartResult.Refused(422, "this id is a film — Auto-fetch needs a series")
            TmdbKind.UNKNOWN -> return StartResult.Refused(422, "TMDB has no series with this id")
            TmdbKind.UNREACHABLE -> return StartResult.Refused(503, "TMDB could not be reached to check the id")
            TmdbKind.SERIES -> {}
        }
        val id = newId()
        held = null
        val first = InvestigationView(
            id = id, state = "running", url = url, host = Urls.host(url).orEmpty(), tmdbId = tmdbId, pageOnly = pageOnly, fetcherId = fetcher?.id,
            steps = Investigator.STEP_LABELS.map { (k, l) -> StepView(k, l) }, startedAt = world.nowSec(),
        )
        current.value = first
        job = scope.launch(GateClass.BACKGROUND) {
            val outcome = try {
                investigator.run(id, url, tmdbId, pageOnly, fetcher?.id) { v -> if (current.value?.id == id && current.value?.state == "running") current.value = v }
            } catch (e: Throwable) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                Logger.warn("auto-fetch: the investigation failed: ${e.message}", "autofetch")
                null
            }
            val now = current.value
            if (now?.id != id || now.state != "running") return@launch   // stopped, or replaced
            if (outcome == null) { current.value = now.copy(state = "failed", reason = "the investigation failed unexpectedly; nothing was written") ; return@launch }
            held = outcome.held
            current.value = outcome.view
        }
        return StartResult.Started(first)
    }

    /** *Stop* (FR-5.5): ends it, discards what it found, writes nothing. */
    fun stop(id: String): Boolean {
        val v = current.value ?: return false
        if (v.id != id || v.state != "running") return false
        job?.cancel()
        held = null
        current.value = v.copy(state = "stopped", reason = "stopped; nothing was written")
        ai.noteRun("stopped · nothing was written · ${AiPricing.usd(v.spentMicroUsd)}")
        return true
    }

    /** *Discard*, or dismissing a failed state: the finished investigation, and the recipe it held, go. */
    fun discard(id: String): Boolean {
        val v = current.value ?: return false
        if (v.id != id || v.state == "running") return false
        held = null
        current.value = null
        return true
    }

    /** FR-16.1 on the review: what Keep would do. Nothing has been placed, so nothing is deleted; the counts are the server's own. */
    fun preview(id: String, retention: RetentionSpec): RetentionPreview? {
        val v = current.value?.takeIf { it.id == id && it.state == "review" } ?: return null
        val onPage = v.review?.found ?: return null
        return RetentionPreview(0, if (retention.keepsAll) onPage else minOf(retention.keep, onPage), onPage)
    }

    suspend fun save(id: String, c: InvestigationSave): InvestigationSaveResult {
        val v = current.value
        val h = held
        if (v == null || v.id != id || v.state != "review" || h == null) return InvestigationSaveResult.Refused(404, "there is no investigation waiting to be saved")
        val result: SaveResult = if (v.fetcherId != null) service.replaceRecipe(v.fetcherId, h.recipe, h.mapping, c.accept)
        else service.save(SaveRequest(
            url = v.url, tmdbId = v.tmdbId, pageOnly = v.pageOnly, recipe = h.recipe, libraryPath = c.libraryPath ?: h.libraryPath.ifBlank { null },
            name = c.name?.takeIf { it.isNotBlank() } ?: h.name, schedule = c.schedule, retention = c.retention, mapping = h.mapping, accept = c.accept,
        ))
        return when (result) {
            is SaveResult.Saved -> {
                if (current.value?.id == id) { held = null; current.value = null }
                InvestigationSaveResult.Saved(result.fetcher, result.proof, c.runFirst)
            }
            is SaveResult.Refused -> InvestigationSaveResult.Refused(result.status, result.reason, result.proof)
        }
    }
}
