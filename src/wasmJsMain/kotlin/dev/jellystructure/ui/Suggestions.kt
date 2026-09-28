package dev.jellystructure.ui

import dev.jellystructure.api.SuggestionsApi
import dev.jellystructure.model.SuggestionDismissedDto
import dev.jellystructure.model.SuggestionDownloadRequest
import dev.jellystructure.model.SuggestionItemDto
import dev.jellystructure.model.SuggestionRequestOptions
import dev.jellystructure.model.SuggestionsPageDto
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * Phase 274 (FR-274-8..13) — films the household doesn't have yet, from Seerr. The server builds the list and says
 * everything this page shows; the page sends two actions, *Download* and *No thanks*, and renders what comes back.
 * Styles: `design/app/suggestions.css` (sg-*), shared with the mockup.
 */
private var sgPage: SuggestionsPageDto? = null
private var sgShowMore = false
private var sgPick: Int? = null
private var sgOther: Int? = null
private var sgView = "list"
private var sgDismissed: List<SuggestionDismissedDto>? = null
private var sgUndoTimer = 0
private var sgPolling = false
/** FR-274-10a — the open confirm dialog, if any: which film, Seerr's options, the picks so far. */
private var sgDl: SgDl? = null
private class SgDl(val id: Int) {
    var opts: SuggestionRequestOptions? = null
    var loading = true
    var srv = 0
    var profileId: Int? = null
    var folder: String? = null
    var more = false
    var sending = false
}

private val REASONS = listOf(
    Triple("not_interested", "Not interested", "Out for good. What led here counts for a little less next time."),
    Triple("already_seen", "Already seen it", "Out, and counted as watched — it becomes something to suggest from."),
    Triple("too_old", "Too old", "Out, and the next build leans a step newer."),
    Triple("other", "Other", "Out. Your note is kept with it under Dismissed."),
)

fun renderSuggestions(container: Element, scope: CoroutineScope, query: Map<String, String>) {
    sgView = if (query["view"] == "dismissed") "dismissed" else "list"
    sgPick = null; sgOther = null; sgShowMore = false
    container.innerHTML = """
        <div id="sg-root">
        <div class="pagebar">
          <h1>Suggestions</h1>
          <span class="badge ok" id="sg-conn" style="margin-left:8px;">● Seerr connected</span>
          <span class="spacer"></span>
          <span class="tiny muted" id="sg-built"></span>
          <a class="chip" href="#/suggestions?view=dismissed" id="sg-dlink">Dismissed</a>
          <span class="btn sm" id="sg-rebuild">⟲ Rebuild now</span>
        </div>
        <p class="page-sub">Films Seerr can get that the library doesn't have, picked from what the household <b>finished</b> — each kind given room in proportion to how much of it gets watched. Nothing is requested until someone presses <b>Download</b>. The same list, per viewer, is a row on Ravilo's Request tab (<a href="#/ravilo?tab=requests">Requests</a>).</p>
        <div class="sg-who" id="sg-who"></div>
        <div class="sg-who" id="sg-grp"></div>
        <div id="sg-banner"></div>
        <div id="sg-main"><span class="muted tiny">Loading…</span></div>
        </div>
    """.trimIndent()
    // On this page's own root, which is replaced with the page: the shared container outlives every route.
    document.getElementById("sg-root")?.addEventListener("click", { ev -> (ev.target as? Element)?.let { sgClick(it, scope) } })
    scope.launch { sgLoad() }
}

private suspend fun sgLoad() {
    val main = document.getElementById("sg-main") as? HTMLElement ?: return
    val p = SuggestionsApi.page()
    if (p == null) {
        main.innerHTML = if (!SuggestionsApi.available())
            sgEmpty("Suggestions need Seerr", "Connect Seerr in <a href=\"#/settings?tab=downloads\">Settings → Download tools</a> and the first list is built in the background.", "")
        else sgEmpty("The server didn't answer", "Try again in a moment.", """<span class="btn" id="sg-retry">Try again</span>""")
        return
    }
    sgPage = p
    if (sgView == "dismissed") sgDismissed = SuggestionsApi.dismissed()
    sgRender()
}

private fun sgEmpty(h: String, p: String, btn: String) =
    """<div class="card sg-empty"><h3>$h</h3>${if (p.isNotEmpty()) "<p>$p</p>" else ""}$btn</div>"""

private fun sgWhen(sec: Long?) = sec?.let { dev.jellystructure.formatWeekdayClock(it.toString()) } ?: ""

private fun sgRender() {
    val p = sgPage ?: return
    val shown = p.items.count { it.shown }
    (document.getElementById("sg-built") as? HTMLElement)?.textContent = when {
        p.building -> "Building…"
        !p.built -> "Not built yet"
        else -> "Built ${sgWhen(p.builtAt)} · $shown shown of ${p.items.size}"
    }
    (document.getElementById("sg-dlink") as? HTMLElement)?.textContent = "Dismissed (${p.dismissedCount})"
    (document.getElementById("sg-conn") as? HTMLElement)?.let {
        it.className = if (p.seerrOk) "badge ok" else "badge warn"
        it.textContent = if (p.seerrOk) "● Seerr connected" else "● Seerr unreachable"
    }
    val who = document.getElementById("sg-who") as? HTMLElement
    who?.innerHTML = if (!p.built || p.noHistory) "" else "<span>Built from ${p.finishedFilms} finished film${if (p.finishedFilms == 1) "" else "s"}:</span>" +
        p.viewers.joinToString("") { v ->
            if (v.finished > 0) """<span class="chip"><b>${v.finished}</b> ${v.name.esc()}</span>"""
            else """<span class="chip none">${v.name.esc()} · nothing finished yet</span>"""
        }
    (document.getElementById("sg-grp") as? HTMLElement)?.innerHTML = when {
        !p.built || p.noHistory || p.items.isEmpty() -> ""
        p.clusterSource == "ai" -> """<span class="dot ok"></span> Grouped by AI this build · <a href="#/activity?view=jobs">see the answer</a>"""
        else -> """<span class="dot warn"></span> Grouped by genre — ${when (p.clusterNote) {
            "limit" -> "this month’s AI limit was reached"
            "bad" -> "the AI answer couldn’t be used · <a href=\"#/activity?view=jobs\">why</a>"
            "waiting" -> "waiting for the AI’s answer; the list regroups when it comes"
            else -> "AI was off"
        }}"""
    }
    (document.getElementById("sg-banner") as? HTMLElement)?.innerHTML = if (p.seerrOk || !p.built) "" else
        """<div class="note warn" style="margin-top:14px;display:flex;gap:12px;align-items:center;"><span class="badge warn" style="flex:none;">Last built list</span><span class="tiny" style="flex:1;line-height:1.5;">Built ${sgWhen(p.builtAt)} · Seerr couldn’t be reached${p.seerrDownSince?.let { " since " + dev.jellystructure.formatClock(it.toString()) } ?: ""}. The list is still true; <b>Download</b> waits until Seerr answers again.</span><span class="btn sm ghost" id="sg-retry">Try again</span></div>"""
    val main = document.getElementById("sg-main") as? HTMLElement ?: return
    if (sgView == "dismissed") { main.innerHTML = sgDismissedHtml(); return }
    main.innerHTML = when {
        !p.built -> sgEmpty("Nothing built yet", "The first list is built in the background from what the household has finished. It takes a few minutes; the page fills in when it is done.",
            if (p.building) """<span class="btn is-off">Building…</span>""" else """<span class="btn primary" data-build="1">Build now</span>""")
        p.noHistory -> sgEmpty("Suggestions appear once someone has finished a few films.", "", "")
        p.items.isEmpty() -> sgEmpty("Nothing new right now · next build ${sgWhen(p.nextBuildAt)}", "Everything suggested has been downloaded, requested or dismissed.", """<span class="btn" data-build="1">⟲ Rebuild now</span>""")
        else -> buildString {
            append(sgBuckets(p, shownPart = true))
            val more = p.items.count { !it.shown }
            if (more > 0) {
                if (sgShowMore) { append("""<div class="sg-fold">$more weaker match${if (more == 1) "" else "es"}</div>"""); append(sgBuckets(p, shownPart = false)) }
                else append("""<div class="sg-more"><span class="btn" id="sg-showmore">Show $more more</span><span class="tiny muted">weaker matches from the same build — the ${shown} above are the ones sized to the household</span></div>""")
            }
        }
    }
}

private fun sgBuckets(p: SuggestionsPageDto, shownPart: Boolean): String = buildString {
    for (c in p.clusters) {
        val items = p.items.filter { it.cluster == c.name && it.shown == shownPart }
        if (items.isEmpty()) continue
        val evidence = if (p.clusterSource == "ai") "the household finished ${c.finished} film${if (c.finished == 1) "" else "s"} like these"
            else "the household finished ${c.finished} ${c.name.lowercase().replace(" & ", " and ")} film${if (c.finished == 1) "" else "s"}"
        append("""<section class="sg-bucket"><div class="sg-bh"><h3>${c.name.esc()}</h3><span class="n">${items.size}</span><span class="ev">$evidence</span></div><div class="sg-grid">""")
        items.forEach { append(sgTile(p, it)) }
        append("</div></section>")
    }
}

private fun sgAnd(titles: List<String>): String {
    val t = titles.take(3).map { "<i>${it.esc()}</i>" }
    return if (t.size < 2) t.joinToString("") else t.dropLast(1).joinToString(", ") + " and " + t.last()
}

private fun sgBecause(it: SuggestionItemDto): String =
    "because " + it.because.joinToString("; ") { b -> "${b.viewer.esc()} ${if (b.verb == "watching") "is watching" else "finished"} ${sgAnd(b.titles)}" }

private fun sgMins(m: Int) = if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"

private fun sgState(it: SuggestionItemDto): String? {
    val forWhom = (it.requestedIn?.let { q -> " · in ${q.esc()}" } ?: "") + (it.requestedFor?.let { f -> " · suggested for ${f.esc()}" } ?: "")
    return when (it.state) {
        "requested" -> """<div class="sg-state"><span class="badge warn">Requested</span><span class="tiny muted">waiting for approval in Seerr$forWhom</span></div>"""
        "approved" -> """<div class="sg-state"><span class="badge info">Approved</span><span class="tiny muted">Radarr is looking for it$forWhom</span></div>"""
        "downloading" -> """<div class="sg-state"><span class="badge info">Downloading · ${it.progress ?: 0}%</span><span class="sg-bar"><i style="width:${it.progress ?: 0}%"></i></span></div>"""
        "library" -> """<div class="sg-state"><span class="badge ok">✓ In the library</span><span class="tiny muted">leaves this list on the next build</span></div>"""
        else -> null
    }
}

private fun sgTile(p: SuggestionsPageDto, it: SuggestionItemDto): String {
    val st = sgState(it)
    val picking = sgPick == it.tmdbId && st == null
    val poster = it.poster?.let { "background:url('https://image.tmdb.org/t/p/w185${it}') center/cover" }
        ?: "background:linear-gradient(160deg, hsl(${(it.tmdbId * 37) % 360} 40% 30%), hsl(${(it.tmdbId * 37 + 30) % 360} 50% 14%))"
    val viewersChip = if (it.viewers > 1) """<span class="chip sg-multi">${it.viewers} viewers</span>""" else ""
    val acts = if (st != null) """<div class="sg-acts">$viewersChip<span class="spacer"></span>$st</div>""" else buildString {
        append("""<div class="sg-acts">$viewersChip<span class="spacer"></span>""")
        append("""<span class="btn sm ghost" data-no="${it.tmdbId}">No thanks</span>""")
        append("""<span class="btn sm primary${if (!p.seerrOk) " is-off" else ""}" data-dl="${it.tmdbId}">Download</span>""")
        if (!p.seerrOk) append("""<span class="sg-dis">Seerr can’t be reached right now — this waits until it’s back.</span>""")
        if (it.state == "refused") append("""<span class="sg-dis">${(it.note ?: "Seerr didn’t take it").esc()}</span>""")
        append("</div>")
    }
    return buildString {
        append("""<div class="sg-tile${if (picking) " picking" else ""}" data-tile="${it.tmdbId}">""")
        append("""<div class="sg-post" style="$poster">${if (it.poster == null) "<span>${it.title.esc()}</span>" else ""}</div>""")
        append("""<div class="sg-body"><div class="sg-t">${it.title.esc()}${it.year?.let { y -> """<span class="y">$y</span>""" } ?: ""}</div>""")
        append("""<div class="sg-meta">""")
        val meta = listOfNotNull(it.rating?.let { r -> "★ ${(kotlin.math.round(r * 10) / 10.0)}" }, it.runtimeMin?.let { m -> sgMins(m) })
        append(meta.joinToString("<span>·</span>") { m -> "<span>$m</span>" })
        it.cert?.let { c -> append("""<span class="sg-cert">${c.esc()}</span>""") }
        if (it.fresh) append("""<span class="tiny muted">· new this year, few ratings yet</span>""")
        append("</div>")
        it.synopsis?.let { s -> append("""<div class="sg-syn">${s.esc()}</div>""") }
        if (it.because.isNotEmpty()) append("""<div class="sg-why">${sgBecause(it)}</div>""")
        it.franchiseOf?.let { f -> append("""<div class="sg-fr">First of a series you don’t have — the match was <b>${f.esc()}</b>.</div>""") }
        append(acts)
        append("</div>")
        if (picking) append(sgPickHtml(it))
        append("</div>")
    }
}

private fun sgPickHtml(it: SuggestionItemDto): String = buildString {
    append("""<div class="sg-pick"><div class="ph">Why not?<span class="spacer" style="flex:1"></span><span class="btn sm ghost" data-cancel="1">Cancel</span></div>""")
    append("""<div class="sg-rs" style="grid-template-columns:repeat(auto-fit,minmax(min(100%,220px),1fr))">""")
    for ((key, label, what) in REASONS) append("""<button class="sg-r${if (sgOther == it.tmdbId && key == "other") " on" else ""}" data-reason="$key" data-id="${it.tmdbId}"><b>$label</b><span>$what</span></button>""")
    append("</div>")
    if (sgOther == it.tmdbId) append("""<div class="sg-other"><textarea id="sg-note" placeholder="Optional — a word for whoever looks later"></textarea><span class="btn sm" data-other="${it.tmdbId}">Dismiss</span></div>""")
    append("""<div class="sg-foot">Also added to Seerr’s blocklist — gone from every Request row on every TV, for everyone.</div></div>""")
}

private fun sgReason(r: String) = REASONS.firstOrNull { it.first == r }?.second ?: if (r == "seerr") "Blocklisted in Seerr" else r

private fun sgDismissedHtml(): String {
    val list = sgDismissed ?: return """<span class="muted tiny">Loading…</span>"""
    return buildString {
        append("""<div class="card" style="margin-top:18px;"><div class="row center"><h3 style="margin:0;font-size:1.05rem;">Dismissed</h3><span class="badge" style="margin-left:8px;">${list.size}</span><span class="spacer"></span><a class="btn sm ghost" href="#/suggestions">Close</a></div>""")
        append("""<p class="tiny muted" style="margin:6px 0 0;">Everything said no to, and why — and anything blocklisted in Seerr directly. None of it is suggested again; <b>Bring back</b> also takes it off Seerr’s blocklist.</p>""")
        if (list.isEmpty()) append("""<p class="tiny muted">Nothing dismissed.</p>""")
        else {
            append("""<table class="sg-dtable"><thead><tr><th>Title</th><th>Reason</th><th>Who</th><th>When</th><th></th></tr></thead><tbody>""")
            for (d in list) {
                append("""<tr><td><b>${d.title.esc()}</b> <span class="muted">${d.year ?: ""}</span></td><td>${sgReason(d.reason)}""")
                d.note?.let { append("""<div class="tiny muted">“${it.esc()}”</div>""") }
                if (!d.inSeerr) append("""<div class="tiny muted">not in Seerr’s blocklist yet</div>""")
                append("""</td><td>${d.who.esc()}</td><td class="tiny muted">${sgWhen(d.at)}</td><td style="text-align:right"><span class="btn sm ghost" data-back="${d.tmdbId}">Bring back</span></td></tr>""")
            }
            append("</tbody></table>")
        }
        append("</div>")
    }
}

private fun sgToast(msg: String) {
    val t = document.createElement("div") as HTMLElement
    t.className = "toast"; t.textContent = msg
    document.body?.appendChild(t)
    window.setTimeout({ t.remove(); null }, 2600)
}

/** FR-274-11 — *Dismissed · {title} · Undo* for 6 s; Undo also takes it off the blocklist. */
private fun sgUndo(scope: CoroutineScope, item: SuggestionItemDto) {
    document.querySelectorAll(".sg-undo").let { for (i in 0 until it.length) (it.item(i) as? HTMLElement)?.remove() }
    if (sgUndoTimer != 0) window.clearTimeout(sgUndoTimer)
    val u = document.createElement("div") as HTMLElement
    u.className = "sg-undo"
    u.innerHTML = """<span>Dismissed · ${item.title.esc()}</span><span class="btn sm" id="sg-undo-btn">Undo</span>"""
    document.body?.appendChild(u)
    u.querySelector("#sg-undo-btn")?.addEventListener("click", {
        u.remove()
        scope.launch {
            val r = SuggestionsApi.bringBack(item.tmdbId)
            if (r?.ok == true) sgLoad() else sgToast(r?.sentence ?: "That didn’t go through")
        }
    })
    sgUndoTimer = window.setTimeout({ u.remove(); null }, 6000)
}

/** While a build runs, the page asks again every few seconds — and stops the moment the page is left. */
private fun sgPollWhileBuilding(scope: CoroutineScope) {
    if (sgPolling) return
    sgPolling = true
    scope.launch {
        try {
            repeat(60) {
                delay(5_000)
                if (!dev.jellystructure.Router.currentPath().startsWith("/suggestions")) return@launch
                sgLoad()
                if (sgPage?.building != true) return@launch
            }
        } finally { sgPolling = false }
    }
}

private fun sgClick(t: Element, scope: CoroutineScope) {
    val p = sgPage
    if (t.closest("#sg-retry") != null) { scope.launch { sgLoad() }; return }
    if (t.closest("#sg-showmore") != null) { sgShowMore = true; sgRender(); return }
    if (t.closest("#sg-rebuild") != null || t.closest("[data-build]") != null) {
        scope.launch {
            if (SuggestionsApi.rebuild()) { sgToast("Queued · the list rebuilds in the background"); sgLoad(); sgPollWhileBuilding(scope) }
            else sgToast("A build is already running")
        }
        return
    }
    p ?: return
    t.closest("[data-back]")?.let { b ->
        val id = b.getAttribute("data-back")?.toIntOrNull() ?: return
        scope.launch {
            val r = SuggestionsApi.bringBack(id)
            sgToast(r?.sentence ?: "That didn’t go through")
            if (r?.ok == true) { sgDismissed = SuggestionsApi.dismissed(); sgLoad() }
        }
        return
    }
    // FR-274-10a — Download asks first: the dialog, then the request from its own button.
    if (t.closest("#sg-dlroot") != null) { sgDlClick(t, scope); return }
    t.closest("[data-dl]")?.let { b ->
        if (b.classList.contains("is-off")) return
        val id = b.getAttribute("data-dl")?.toIntOrNull() ?: return
        sgDlOpen(scope, id)
        return
    }
    t.closest("[data-no]")?.let { b -> sgPick = b.getAttribute("data-no")?.toIntOrNull(); sgOther = null; sgRender(); return }
    if (t.closest("[data-cancel]") != null) { sgPick = null; sgOther = null; sgRender(); return }
    t.closest("[data-reason]")?.let { b ->
        val id = b.getAttribute("data-id")?.toIntOrNull() ?: return
        val reason = b.getAttribute("data-reason") ?: return
        if (reason == "other") { sgOther = id; sgRender(); (document.getElementById("sg-note") as? HTMLElement)?.focus(); return }
        sgDismiss(scope, id, reason, null)
        return
    }
    t.closest("[data-other]")?.let { b ->
        val id = b.getAttribute("data-other")?.toIntOrNull() ?: return
        sgDismiss(scope, id, "other", (document.getElementById("sg-note") as? HTMLTextAreaElement)?.value?.trim()?.takeIf { it.isNotEmpty() })
    }
}

private fun sgDismiss(scope: CoroutineScope, id: Int, reason: String, note: String?) {
    val item = sgPage?.items?.firstOrNull { it.tmdbId == id } ?: return
    scope.launch {
        val r = SuggestionsApi.dismiss(id, reason, note)
        if (r?.ok != true) { sgToast(r?.sentence ?: "That didn’t go through"); return@launch }
        (document.querySelector("[data-tile=\"$id\"]") as? HTMLElement)?.classList?.add("leaving")
        delay(220)
        sgPick = null; sgOther = null
        sgPage = sgPage?.let { pg -> pg.copy(items = pg.items.filter { it.tmdbId != id }, dismissedCount = pg.dismissedCount + 1) }
        sgRender()
        sgUndo(scope, item)
        if (r.sentence.contains("not in Seerr")) sgToast("Not in Seerr’s blocklist yet — it is written when Seerr answers")
    }
}


// ── FR-274-10a — Download asks first ─────────────────────────────────────────────────────────────────────────

/** Opens the confirm dialog for [id] and asks Seerr for its options (cached server-side for a minute). */
private fun sgDlOpen(scope: CoroutineScope, id: Int) {
    val d = SgDl(id); sgDl = d; sgDlRender()
    scope.launch {
        val o = SuggestionsApi.requestOptions()
        if (sgDl !== d) return@launch
        d.opts = o; d.loading = false
        o?.servers?.let { servers ->
            d.srv = servers.indexOfFirst { it.isDefault }.coerceAtLeast(0)
            sgDlPickDefaults(d)
        }
        sgDlRender()
    }
}

/** Pre-selects 139's steered profile when the server has it, else the server's active one, else its first; the active folder. */
private fun sgDlPickDefaults(d: SgDl) {
    val o = d.opts ?: return
    val srv = o.servers.getOrNull(d.srv) ?: return
    d.profileId = o.steeredProfileId?.takeIf { s -> srv.profiles.any { it.id == s } } ?: srv.activeProfileId?.takeIf { a -> srv.profiles.any { it.id == a } } ?: srv.profiles.firstOrNull()?.id
    d.folder = srv.activeFolder ?: srv.folders.firstOrNull()
    d.more = false
}

private fun sgDlClose() { sgDl = null; document.getElementById("sg-dlroot")?.remove() }

private fun sgDlRender() {
    val d = sgDl ?: run { document.getElementById("sg-dlroot")?.remove(); return }
    val root = document.getElementById("sg-dlroot") as? HTMLElement ?: (document.createElement("div") as HTMLElement).also { it.id = "sg-dlroot"; document.body?.appendChild(it) }
    val it = (sgPage?.items.orEmpty()).firstOrNull { x -> x.tmdbId == d.id } ?: run { sgDlClose(); return }
    val o = d.opts
    val down = !d.loading && (o == null || !o.seerrOk)
    val servers = o?.servers.orEmpty()
    val srv = servers.getOrNull(d.srv)
    val prof = srv?.profiles?.firstOrNull { p -> p.id == d.profileId } ?: srv?.profiles?.firstOrNull()
    val fixed = o?.canChoose == false
    val body = buildString {
        when {
            d.loading -> append("""<div class="sg-dls"><div class="lb">Quality</div><div class="sg-skel"><i></i><i></i><i></i></div><div class="tiny muted">Asking Seerr which qualities Radarr has…</div></div>""")
            down -> append("""<div class="sg-dldown"><span class="dot warn"></span><span>Seerr can’t be reached right now — this waits until it’s back.</span><span class="btn sm ghost" data-dlretry="1">Try again</span></div>""")
            srv == null -> append("""<div class="sg-dls"><div class="tiny muted">Seerr has no Radarr server set up, so it would use nothing — check Seerr’s settings.</div></div>""")
            else -> {
                if (servers.size > 1) {
                    append("""<div class="sg-dls"><div class="lb">Server <span>Seerr has ${servers.size} Radarr servers</span></div><div class="sg-srvs">""")
                    servers.forEachIndexed { i, s ->
                        append("""<button class="sg-srv${if (i == d.srv) " on" else ""}" data-dlsrv="$i"><b>${s.name.esc()}</b>${if (s.is4k) """<span class="sg-4k">4K</span>""" else ""}<span class="tiny muted">${s.profiles.size} qualit${if (s.profiles.size == 1) "y" else "ies"}${if (s.isDefault) " · Seerr’s default" else ""}</span></button>""")
                    }
                    append("</div></div>")
                }
                append("""<div class="sg-dls"><div class="lb">Quality <span>as Radarr names them${if (servers.size > 1) "" else " · to <b>${srv.name.esc()}</b>"}</span></div>""")
                fun tag(p: dev.jellystructure.model.SuggestionProfile) = when {
                    o?.steeredProfileId == p.id -> """<span class="sg-def">your ${o.steeredLabel?.esc() ?: "language"} rule</span>"""
                    srv.activeProfileId == p.id -> """<span class="sg-def">Seerr’s default</span>"""
                    else -> ""
                }
                if (fixed || srv.profiles.size <= 1) {
                    append("""<div class="sg-fact"><b>${prof?.name?.esc() ?: "Seerr’s default"}</b>${prof?.let { tag(it) } ?: ""}</div>""")
                    append("""<div class="tiny muted" style="margin-top:6px;line-height:1.45">${if (fixed) "Your Seerr user may not choose a quality, so Seerr uses its default. Seerr’s <i>Advanced requests</i> permission lets it choose." else "This is the only quality Radarr has on ${srv.name.esc()}."}</div>""")
                } else {
                    append("""<div class="sg-opts" role="radiogroup">""")
                    srv.profiles.forEach { p -> append("""<button class="sg-opt${if (p.id == prof?.id) " on" else ""}" role="radio" aria-checked="${p.id == prof?.id}" data-dlprof="${p.id}"><i></i><b>${p.name.esc()}</b>${tag(p)}</button>""") }
                    append("</div>")
                }
                append("</div>")
                if (srv.folders.size > 1 && !fixed) {
                    val f = d.folder ?: srv.folders.first()
                    append("""<div class="sg-dls"><button class="sg-morebtn" data-dlmore="1">${if (d.more) "▾" else "▸"} More <span class="tiny muted">folder · ${f.esc()}</span></button>""")
                    if (d.more) {
                        append("""<div class="sg-opts" style="margin-top:8px">""")
                        srv.folders.forEach { x -> append("""<button class="sg-opt${if (x == f) " on" else ""}" data-dlfold="${x.esc()}"><i></i><b class="mono">${x.esc()}</b>${if (x == srv.activeFolder) """<span class="sg-def">Seerr’s default</span>""" else ""}</button>""") }
                        append("</div>")
                    }
                    append("</div>")
                }
            }
        }
        val who = it.because.firstOrNull()?.viewer ?: "the household"
        append("""<div class="sg-dlnote"><span class="lb">Kept with the request</span><span>Suggested for ${who.esc()}</span><span class="tiny muted">Seerr has no note field, so this stays on this page (under the tile and in Dismissed / Requested).</span></div>""")
    }
    val primary = when { d.sending -> "Sending…"; d.loading -> "Asking Seerr…"; down || srv == null -> "Download"; else -> "Download in ${prof?.name?.esc() ?: "Seerr’s default"}${if (srv.is4k) " · 4K" else ""}" }
    val disabled = d.loading || down || srv == null || d.sending
    val poster = it.poster?.let { p -> "background:url('https://image.tmdb.org/t/p/w185$p') center/cover" } ?: "background:linear-gradient(160deg, hsl(${(it.tmdbId * 37) % 360} 40% 30%), hsl(${(it.tmdbId * 37 + 30) % 360} 50% 14%))"
    root.innerHTML = """<div class="sg-dlb as-dialog" data-dlback="1"><div class="sg-dl" role="dialog" aria-modal="true" aria-label="Download ${it.title.esc()}">
        <div class="sg-grab"></div>
        <div class="sg-dlh"><div class="sg-post" style="width:64px;$poster"><span>${it.title.esc()}</span></div>
        <div style="min-width:0"><div class="sg-t">${it.title.esc()}<span class="y">${it.year ?: ""}</span>${it.cert?.let { c -> """<span class="sg-cert" style="margin-left:8px">${c.esc()}</span>""" } ?: ""}</div><div class="sg-why" style="margin-top:5px">${sgBecause(it)}</div></div></div>
        $body
        <div class="sg-dlf">${if (down) """<span class="tiny muted" style="flex:1">Nothing is sent while Seerr is away.</span>""" else """<span style="flex:1"></span>"""}<span class="btn ghost" data-dlcancel="1">Cancel</span><span class="btn primary" data-dlgo="1"${if (disabled) """ aria-disabled="true" style="opacity:.55"""" else ""}>$primary</span></div>
        </div></div>"""
    if (!disabled) (root.querySelector("[data-dlgo]") as? HTMLElement)?.focus()
}

private fun sgDlClick(t: Element, scope: CoroutineScope) {
    val d = sgDl ?: return
    if (t.closest("[data-dlcancel]") != null || (t.closest("[data-dlback]") != null && t.closest(".sg-dl") == null)) { sgDlClose(); return }
    t.closest("[data-dlsrv]")?.let { b -> d.srv = b.getAttribute("data-dlsrv")?.toIntOrNull() ?: 0; sgDlPickDefaults(d); sgDlRender(); return }
    t.closest("[data-dlprof]")?.let { b -> d.profileId = b.getAttribute("data-dlprof")?.toIntOrNull(); sgDlRender(); return }
    t.closest("[data-dlfold]")?.let { b -> d.folder = b.getAttribute("data-dlfold"); d.more = false; sgDlRender(); return }
    if (t.closest("[data-dlmore]") != null) { d.more = !d.more; sgDlRender(); return }
    if (t.closest("[data-dlretry]") != null) { sgDlOpen(scope, d.id); return }
    if (t.closest("[data-dlgo]") != null) {
        val o = d.opts ?: return
        val srv = o.servers.getOrNull(d.srv) ?: return
        if (d.loading || d.sending || !o.seerrOk) return
        d.sending = true; sgDlRender()
        val id = d.id
        val choice = SuggestionDownloadRequest(serverId = srv.id, profileId = if (o.canChoose) d.profileId else null, rootFolder = if (o.canChoose && srv.folders.size > 1) d.folder else null)
        scope.launch {
            val r = SuggestionsApi.download(id, choice)
            if (sgDl === d) sgDlClose()
            sgToast(r?.sentence ?: "The server didn’t answer")
            r?.item?.let { upd -> sgPage = sgPage?.let { pg -> pg.copy(items = pg.items.map { if (it.tmdbId == id) upd else it }) } }
            sgRender()
        }
    }
}
