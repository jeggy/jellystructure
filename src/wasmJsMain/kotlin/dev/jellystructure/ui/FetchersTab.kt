package dev.jellystructure.ui

import dev.jellystructure.Router
import dev.jellystructure.api.AutofetchApi
import dev.jellystructure.formatClock
import dev.jellystructure.formatRelativeAgo
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * DEB-2477 (DEB-2474, FR-1, FR-9, FR-10, FR-11) — the Fetchers tab on the AI page: the fetchers and their sites, the open row's
 * recipe, settings and last runs, and *Not on TMDB yet*. It renders what the server pushed (`GET /api/autofetch`, asked again every
 * 5 s while the tab is shown) and nothing it worked out for itself: a write is sent, and the page draws the server's next answer.
 * What the admin has typed into the open row and not saved is the form, kept in [drafts]. The markup and the words are in
 * `AutofetchHtml.kt` (commonMain, tested); the stylesheet is `design/app/ai.css`, scoped under `.afx`, which the panel carries.
 */

private object AfClockJs : AfClock {
    override fun ago(sec: Long) = formatRelativeAgo(sec.toString())
    override fun at(sec: Long) = formatClock(sec.toString())
    override fun until(sec: Long): String {
        val s = (sec - nowSec()).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${maxOf(m, 1)}m"
    }
    override fun runtime(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
    }
}

private fun nowMs(): Double = js("Date.now()")

private fun nowSec(): Long = (nowMs() / 1000).toLong()

private var afGen = 0
private var afPanel: Element? = null
private var afOverview: AfOverview? = null
private var afSnapshot = ""
private var afOpenFetcher: String? = null
private var afOpenQueue: Long? = null
private val afDrafts = HashMap<String, AfDraft>()
private val afResuming = HashSet<String>()
private val afPicked = HashMap<Long, Int>()
private var afNote: String? = null
private var afPoll: Job? = null
private var afPreviewJob: Job? = null
// DEB-2478 — the form's three inputs and the review's settings: what the admin typed and has not sent. The investigation is the server's.
private var afForm = AfForm()
private var afReview: AfReviewDraft? = null
private var afInvPoll: Job? = null

/** Called when the AI page is left or drawn again: the poll stops and an answer still in flight finds the page changed. */
fun leaveFetchersTab() {
    afGen++
    afPoll?.cancel(); afPoll = null
    afPreviewJob?.cancel(); afPreviewJob = null
    afInvPoll?.cancel(); afInvPoll = null
    afForm = AfForm(); afReview = null
    afPanel = null; afOverview = null; afSnapshot = ""
    afOpenFetcher = null; afOpenQueue = null
    afDrafts.clear(); afResuming.clear(); afPicked.clear(); afNote = null
}

fun renderFetchersTab(panel: Element, scope: CoroutineScope) {
    leaveFetchersTab()
    afPanel = panel
    val gen = afGen
    panel.innerHTML = """<span class="muted tiny">Loading…</span>"""
    bind(panel, scope)
    scope.launch { refresh(scope, gen, force = true) }
    afPoll = scope.launch {
        while (true) {
            delay(5_000)
            if (gen != afGen) return@launch
            if (Router.currentPath() != "/ai" || Router.currentQuery()["tab"] != "fetchers") continue
            // A repaint under the admin's hands would take the caret out of a field they are typing in.
            if (editing(panel)) continue
            refresh(scope, gen, force = false)
        }
    }
}

private fun editing(panel: Element): Boolean {
    val a = document.activeElement ?: return false
    return panel.contains(a) && (a is HTMLInputElement || a is HTMLSelectElement)
}

private suspend fun refresh(scope: CoroutineScope, gen: Int, force: Boolean) {
    val o = AutofetchApi.overview()
    if (gen != afGen) return
    val panel = afPanel ?: return
    if (o == null) {
        if (afOverview == null) panel.innerHTML = """<div class="card"><div class="af-empty"><b>Could not read the fetchers</b>The server did not answer. This page asks again every few seconds.</div></div>"""
        return
    }
    afOverview = o
    seedReview(scope, o.investigation)
    watchInvestigation(scope, gen, o.investigation)
    // The open row's draft is seeded from what the server holds the first time it is opened, and dropped when it is closed.
    val snap = o.toString()
    if (!force && snap == afSnapshot) return
    afSnapshot = snap
    paint()
}

/** The review's settings start at what the server proposed, once per investigation; the admin's changes are the draft after that. */
private fun seedReview(scope: CoroutineScope, inv: AfInvestigation?) {
    val r = inv?.review
    if (inv == null || inv.state != "review" || r == null) { afReview = null; return }
    if (afReview?.investigation == inv.id) return
    afReview = AfReviewDraft(inv.id, r.name, r.libraryPath, r.schedule, r.retention)
    repaintReview(scope)
}

/** While an investigation runs, its steps are asked for every 1.5 s (the overview's 5 s is too slow to watch five steps); it stops itself when the server's state is no longer `running`. */
private fun watchInvestigation(scope: CoroutineScope, gen: Int, inv: AfInvestigation?) {
    if (inv?.state != "running") { afInvPoll?.cancel(); afInvPoll = null; return }
    if (afInvPoll?.isActive == true) return
    afInvPoll = scope.launch {
        while (true) {
            delay(1_500)
            if (gen != afGen) return@launch
            val now = AutofetchApi.investigation()
            if (gen != afGen) return@launch
            val o = afOverview ?: return@launch
            if (now == null) { afInvPoll = null; refresh(scope, gen, force = true); return@launch }
            afOverview = o.copy(investigation = now)
            if (now.state != "running") { afInvPoll = null; refresh(scope, gen, force = true); return@launch }
            if (!editing(afPanel ?: return@launch)) { afSnapshot = afOverview.toString(); paint() }
        }
    }
}

/** FR-16.1 — what the review's Keep would do, counted by the server. */
private fun repaintReview(scope: CoroutineScope) {
    paint()
    afPreviewJob?.cancel()
    val gen = afGen
    val d = afReview ?: return
    afPreviewJob = scope.launch {
        val p = AutofetchApi.previewReview(d.investigation, d.retention)
        if (gen != afGen) return@launch
        val now = afReview ?: return@launch
        if (now.investigation != d.investigation || now.retention != d.retention) return@launch
        afReview = now.copy(preview = p)
        paint()
    }
}

private fun paint() {
    val panel = afPanel ?: return
    val o = afOverview ?: return
    val note = afNote?.let { """<div class="af-prev" style="margin-bottom:12px"><span class="bi">!</span><span>${afEsc(it)}</span><span class="spacer"></span><span class="btn sm ghost" data-af-dismiss>OK</span></div>""" }.orEmpty()
    panel.innerHTML = note + afPanelHtml(o, afOpenFetcher, afOpenQueue, afDrafts, afResuming, afPicked, nowSec(), AfClockJs, afForm, afReview)
}

private fun fetcherOf(el: Element): AfFetcher? {
    val id = el.closest("[data-fid]")?.getAttribute("data-fid") ?: el.closest("[data-af-row]")?.getAttribute("data-af-row") ?: return null
    return afOverview?.fetchers?.firstOrNull { it.id == id }
}

private fun bind(panel: Element, scope: CoroutineScope) {
    panel.addEventListener("click", { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        if (t.closest("[data-copy]") != null || t.closest("[data-stop-click]") != null) return@addEventListener   // the document-level handler / a plain link
        t.closest("[data-rv-showall]")?.let { ev.preventDefault(); afReview = afReview?.let { d -> d.copy(showAll = !d.showAll) }; paint(); return@addEventListener }
        if (t.closest("input,select,a") != null) return@addEventListener   // a field being used is not a click on the row around it
        if (t.closest(".af-dis") != null) return@addEventListener
        val gen = afGen

        // ---- DEB-2478: the form, the investigation and its review
        t.closest("[data-af-pageonly]")?.let { afForm = afForm.copy(pageOnly = !afForm.pageOnly, tmdb = if (!afForm.pageOnly) "" else afForm.tmdb); paint(); return@addEventListener }
        t.closest("[data-af-go]")?.let {
            val tmdb = afForm.tmdb.trim()
            if (!afForm.pageOnly && tmdb.isNotEmpty() && tmdb.toIntOrNull() == null) { afNote = "A TMDB id is a number."; paint(); return@addEventListener }
            afForm = afForm.copy(starting = true); paint()
            val f = afForm
            act(scope, gen) { AutofetchApi.investigate(f.url.trim(), if (f.pageOnly) null else tmdb.toIntOrNull(), f.pageOnly).also { afForm = afForm.copy(starting = false) } }
            return@addEventListener
        }
        t.closest("[data-af-reinv]")?.let { b ->
            val f = fetcherOf(b) ?: return@let
            act(scope, gen) { AutofetchApi.investigate(f.pageUrl, f.tmdbId, f.pageOnly, f.id).also { document.getElementById("af-new")?.scrollIntoView() } }
            return@addEventListener
        }
        t.closest("[data-af-stop]")?.let { act(scope, gen) { AutofetchApi.stopInvestigation(afOverview?.investigation?.id.orEmpty()) }; return@addEventListener }
        t.closest("[data-af-dismissinv]")?.let { act(scope, gen) { AutofetchApi.discardInvestigation(afOverview?.investigation?.id.orEmpty()) }; return@addEventListener }
        t.closest("[data-rv]")?.let { host ->
            val d = afReview ?: return@let
            t.closest("[data-af-keep]")?.let { b ->
                val newest = b.getAttribute("data-af-keep") == "newest"
                // FR-16.1 — a TMDB fetcher's *leaves the page* starts off, a page-only fetcher's on.
                val pageOnly = afOverview?.investigation?.review?.pageOnly == true
                afReview = d.copy(retention = if (newest) d.retention.copy(mode = "newest", leavesPage = if (d.retention.newest) d.retention.leavesPage else pageOnly) else d.retention.copy(mode = "all", leavesPage = false), preview = null)
                repaintReview(scope); return@addEventListener
            }
            t.closest("[data-af-leaves]")?.let { afReview = d.copy(retention = d.retention.copy(leavesPage = !d.retention.leavesPage), preview = null); repaintReview(scope); return@addEventListener }
            t.closest("[data-rv-first]")?.let { b -> afReview = d.copy(runFirst = b.getAttribute("data-rv-first") == "now"); paint(); return@addEventListener }
            t.closest("[data-rv-discard]")?.let { act(scope, gen) { AutofetchApi.discardInvestigation(d.investigation).also { afReview = null } }; return@addEventListener }
            t.closest("[data-rv-save]")?.let {
                val replacing = afOverview?.investigation?.review?.replaces != null
                afReview = d.copy(saving = true); paint()
                act(scope, gen) {
                    AutofetchApi.saveInvestigation(d.investigation, d.name.takeIf { !replacing }, d.libraryPath.takeIf { !replacing && it.isNotBlank() }, d.schedule.takeIf { !replacing }, d.retention.takeIf { !replacing }, d.runFirst)
                        .also { if (!it.ok) afReview = afReview?.copy(saving = false) }
                }
                return@addEventListener
            }
            host.let { return@addEventListener }
        }

        t.closest("[data-af-dismiss]")?.let { afNote = null; paint(); return@addEventListener }
        t.closest("[data-af-runall]")?.let { act(scope, gen) { AutofetchApi.runAll() }; return@addEventListener }
        t.closest("[data-af-resume]")?.let { b ->
            val site = b.getAttribute("data-af-resume") ?: return@let
            afResuming += site; paint()
            act(scope, gen) { AutofetchApi.resume(site).also { afResuming -= site } }
            return@addEventListener
        }
        // ---- the open row's controls
        t.closest("[data-af-keep]")?.let { b ->
            val f = fetcherOf(b) ?: return@let
            val d = draft(f)
            val newest = b.getAttribute("data-af-keep") == "newest"
            // FR-16.1 — a TMDB fetcher's *leaves the page* starts off, a page-only fetcher's on.
            afDrafts[f.id] = d.copy(retention = if (newest) d.retention.copy(mode = "newest", leavesPage = if (d.retention.newest) d.retention.leavesPage else f.pageOnly) else d.retention.copy(mode = "all", leavesPage = false), preview = null)
            repaintOpenRow(scope, f); return@addEventListener
        }
        t.closest("[data-af-leaves]")?.let { b ->
            val f = fetcherOf(b) ?: return@let
            val d = draft(f)
            afDrafts[f.id] = d.copy(retention = d.retention.copy(leavesPage = !d.retention.leavesPage), preview = null)
            repaintOpenRow(scope, f); return@addEventListener
        }
        t.closest("[data-af-save]")?.let { b ->
            val f = fetcherOf(b) ?: return@let
            val d = draft(f)
            afDrafts[f.id] = d.copy(saving = true); paint()
            act(scope, gen) { AutofetchApi.settings(f.id, d.retention.takeIf { it != f.retention }, d.schedule.takeIf { it != f.schedule }, null).also { afDrafts.remove(f.id) } }
            return@addEventListener
        }
        t.closest("[data-af-delete]")?.let { b ->
            val f = fetcherOf(b) ?: return@let
            // Delete removes the fetcher, its recipe and its history, never a file (FR-6.8): the confirmation says so.
            if (!confirmDelete(f.name)) return@addEventListener
            act(scope, gen) { AutofetchApi.delete(f.id).also { afOpenFetcher = null; afDrafts.remove(f.id) } }
            return@addEventListener
        }
        // ---- the list
        t.closest("[data-af-toggle]")?.let { b ->
            val f = fetcherOf(b) ?: return@let
            act(scope, gen) { AutofetchApi.settings(f.id, null, null, !f.enabled) }; return@addEventListener
        }
        t.closest("[data-af-run]")?.let { b ->
            val f = fetcherOf(b) ?: return@let
            act(scope, gen) { AutofetchApi.run(f.id) }; return@addEventListener
        }
        t.closest("[data-af-row]")?.let { r ->
            val id = r.getAttribute("data-af-row")
            afOpenFetcher = if (afOpenFetcher == id) null else id
            if (afOpenFetcher != null) afOverview?.fetchers?.firstOrNull { it.id == id }?.let { f -> afDrafts[f.id] = AfDraft(f.schedule, f.retention) }
            paint(); return@addEventListener
        }
        // ---- Not on TMDB yet
        t.closest("[data-tq-submit]")?.let { b ->
            val id = b.closest("[data-tqd]")?.getAttribute("data-tqd")?.toLongOrNull() ?: return@let
            act(scope, gen) { AutofetchApi.submit(id) }; return@addEventListener
        }
        t.closest("[data-pick]")?.let { b ->
            val id = b.closest("[data-tqd]")?.getAttribute("data-tqd")?.toLongOrNull() ?: return@let
            afPicked[id] = b.getAttribute("data-pick")?.toIntOrNull() ?: 0; paint(); return@addEventListener
        }
        t.closest("[data-tq-cut]")?.let { b ->
            val det = b.closest("[data-tqd]") ?: return@let
            val id = det.getAttribute("data-tqd")?.toLongOrNull() ?: return@let
            val n = b.getAttribute("data-tq-cut")?.toIntOrNull() ?: 0
            val at = afParseMoment((det.querySelector("[data-tq-moment]") as? HTMLInputElement)?.value.orEmpty())
            if (at == null) { afNote = "Type a moment as minutes and seconds, like 12:05."; paint(); return@addEventListener }
            act(scope, gen) { AutofetchApi.cutStill(id, n, at) }
            return@addEventListener
        }
        t.closest("[data-tq]")?.let { r ->
            val id = r.getAttribute("data-tq")?.toLongOrNull()
            afOpenQueue = if (afOpenQueue == id) null else id; paint(); return@addEventListener
        }
    })

    // The open row's schedule, and the number in Keep: the form, sent only by Save.
    // The form's two fields are kept as typed; nothing repaints under the caret (the poll skips a panel being edited).
    panel.addEventListener("input", { ev ->
        val t = ev.target as? HTMLInputElement ?: return@addEventListener
        when {
            t.hasAttribute("data-af-url") -> afForm = afForm.copy(url = t.value)
            t.hasAttribute("data-af-tmdb") -> afForm = afForm.copy(tmdb = t.value)
            t.hasAttribute("data-rv-name") -> afReview = afReview?.copy(name = t.value)
        }
    })

    panel.addEventListener("change", { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        // DEB-2478 — the review's settings are a draft of their own, sent only by *Save auto-fetcher*.
        t.closest("[data-rv]")?.let { host ->
            val d = afReview ?: return@addEventListener
            when {
                t.hasAttribute("data-rv-lib") -> afReview = d.copy(libraryPath = (t as? HTMLSelectElement)?.value.orEmpty())
                t.hasAttribute("data-af-keepn") -> {
                    val n = (t as? HTMLInputElement)?.value?.trim()?.toIntOrNull()
                    if (n == null || n !in 1..999) { afNote = "Keep the newest N takes a number from 1 to 999."; paint(); return@addEventListener }
                    afReview = d.copy(retention = d.retention.copy(mode = "newest", keep = n), preview = null); repaintReview(scope)
                }
                t.hasAttribute("data-af-sched-kind") || t.hasAttribute("data-af-sched-day") || t.hasAttribute("data-af-sched-time") -> {
                    val kind = (host.querySelector("[data-af-sched-kind]") as? HTMLSelectElement)?.value ?: "every6h"
                    val day = (host.querySelector("[data-af-sched-day]") as? HTMLSelectElement)?.value?.toIntOrNull() ?: 1
                    val time = (host.querySelector("[data-af-sched-time]") as? HTMLInputElement)?.value?.ifBlank { null } ?: "04:00"
                    afReview = d.copy(schedule = afScheduleOf(kind, day, time)); paint()
                }
            }
            return@addEventListener
        }
        val f = fetcherOf(t) ?: return@addEventListener
        val d = draft(f)
        val host = t.closest("[data-fid]") ?: return@addEventListener
        when {
            t.hasAttribute("data-af-keepn") -> {
                val n = (t as? HTMLInputElement)?.value?.trim()?.toIntOrNull()
                if (n == null || n !in 1..999) { afNote = "Keep the newest N takes a number from 1 to 999."; paint(); return@addEventListener }
                afDrafts[f.id] = d.copy(retention = d.retention.copy(mode = "newest", keep = n), preview = null)
                repaintOpenRow(scope, f)
            }
            t.hasAttribute("data-af-sched-kind") || t.hasAttribute("data-af-sched-day") || t.hasAttribute("data-af-sched-time") -> {
                val kind = (host.querySelector("[data-af-sched-kind]") as? HTMLSelectElement)?.value ?: "every6h"
                val day = (host.querySelector("[data-af-sched-day]") as? HTMLSelectElement)?.value?.toIntOrNull() ?: 1
                val time = (host.querySelector("[data-af-sched-time]") as? HTMLInputElement)?.value?.ifBlank { null } ?: "04:00"
                afDrafts[f.id] = d.copy(schedule = afScheduleOf(kind, day, time))
                paint()
            }
        }
    })
}

private fun draft(f: AfFetcher): AfDraft = afDrafts.getOrPut(f.id) { AfDraft(f.schedule, f.retention) }

/** Redraws, and asks the server what the draft's retention would delete — the screen shows that count and never one of its own (FR-16.1). */
private fun repaintOpenRow(scope: CoroutineScope, f: AfFetcher) {
    paint()
    afPreviewJob?.cancel()
    val gen = afGen
    val d = afDrafts[f.id] ?: return
    afPreviewJob = scope.launch {
        val p = AutofetchApi.preview(f.id, d.retention)
        if (gen != afGen) return@launch
        val now = afDrafts[f.id] ?: return@launch
        if (now.retention != d.retention) return@launch
        afDrafts[f.id] = now.copy(preview = p)
        paint()
    }
}

/** A write: send it, say what the server refused with, and draw the server's next answer. */
private fun act(scope: CoroutineScope, gen: Int, call: suspend () -> AutofetchApi.Outcome) {
    scope.launch {
        val r = call()
        if (gen != afGen) return@launch
        afNote = r.error
        refresh(scope, gen, force = true)
    }
}

private fun confirmDelete(name: String): Boolean = confirmJs("Delete the fetcher $name? Its recipe and history go; the episodes it placed stay in the library.")

private fun confirmJs(message: String): Boolean = js("window.confirm(message)")
