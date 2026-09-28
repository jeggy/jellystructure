package dev.jellystructure.ui

import dev.jellystructure.api.SuggestionsApi
import dev.jellystructure.model.SuggestionDismissedDto
import dev.jellystructure.model.SuggestionItemDto
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
    val forWhom = it.requestedFor?.let { f -> " · suggested for ${f.esc()}" } ?: ""
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
    t.closest("[data-dl]")?.let { b ->
        if (b.classList.contains("is-off")) return
        val id = b.getAttribute("data-dl")?.toIntOrNull() ?: return
        b.classList.add("is-off")
        scope.launch {
            val r = SuggestionsApi.download(id)
            sgToast(r?.sentence ?: "The server didn’t answer")
            r?.item?.let { upd -> sgPage = sgPage?.let { pg -> pg.copy(items = pg.items.map { if (it.tmdbId == id) upd else it }) } }
            sgRender()
        }
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
