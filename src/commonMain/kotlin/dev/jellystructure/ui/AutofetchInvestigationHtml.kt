package dev.jellystructure.ui

/*
 * DEB-2478 (DEB-2474, FR-5, FR-10.1, FR-10.4, FR-10.6, FR-15.1) — the *New auto-fetcher* card: the form, the investigation's five steps,
 * the failed state, and the review with its proof line. `commonMain` for the reason AutofetchHtml.kt gives. The page draws what the
 * server pushed: every step, count and sentence below comes from the investigation the server holds. What the admin has typed and not
 * sent is the form ([AfForm]) and the review's draft ([AfReviewDraft]).
 */

/** The form's three inputs, as typed and not yet sent. */
data class AfForm(val url: String = "", val tmdb: String = "", val pageOnly: Boolean = false, val starting: Boolean = false)

/** The review's settings, as the admin has set them and not yet saved. */
data class AfReviewDraft(
    val investigation: String,
    val name: String,
    val libraryPath: String,
    val schedule: String,
    val retention: AfRetention,
    val runFirst: Boolean = true,
    val preview: AfPreview? = null,
    val saving: Boolean = false,
    val showAll: Boolean = false,
    /** FR-4.3 — a `mapped` review: the ids of the rows the admin has ticked. All start unticked. */
    val accept: Set<String> = emptySet(),
)

private fun seconds(ms: Long?): String = if (ms == null) "" else "${ms / 1000}.${(ms % 1000) / 100}s"

internal fun afUrlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xff
        if (c in 0x30..0x39 || c in 0x41..0x5a || c in 0x61..0x7a || c == '-'.code || c == '_'.code || c == '.'.code) append(c.toChar())
        else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
}

/** FR-10.4 — *File a bug report on GitHub*: the title and body the server wrote for a failed investigation, URL-encoded and nothing added. No report, no link. */
internal fun afBugReportUrl(inv: AfInvestigation): String? = inv.report?.let { afBugReportLink(it.title, it.body) }

/** FR-10.4 — the new-issue link for [title] and [body]. The server fits the pair to [AF_BUG_REPORT_LINK_MAX] with this same function. */
internal fun afBugReportLink(title: String, body: String): String =
    "https://github.com/jeggy/jellystructure/issues/new?title=${afUrlEncode(title)}&body=${afUrlEncode(body)}"

/** GitHub refuses a new-issue link much over 8 kB. */
internal const val AF_BUG_REPORT_LINK_MAX = 8_000

/** The form (FR-10.6): the page URL, the TMDB id, the page-only tick (FR-15.1), and *Investigate* — or *Stop* while one runs. */
internal fun afFormHtml(form: AfForm, gate: AfGate, running: Boolean): String {
    val off = running || form.starting
    val go = if (running) """<span class="btn" data-af-stop style="color:var(--ink-soft);">Stop</span>"""
    else """<span class="btn primary${if (gate.allowed && !form.starting) "" else " af-dis"}" data-af-go>${if (form.starting) "Starting…" else "⌕ Investigate"}</span>"""
    val tmdbHint = if (form.pageOnly) "The episodes keep the page's own titles and descriptions, and nothing asks TMDB." else "Must resolve to a TV series on TMDB."
    val why = if (!gate.allowed && !running && gate.reason != null) """<div class="af-why" style="margin-top:8px">${afEsc(gate.reason)}</div>""" else ""
    return """<div class="af-form">
      <div class="field"><label>Page URL</label><input class="input mono" data-af-url value="${afEsc(form.url)}" spellcheck="false" placeholder="https://…"${if (off) " disabled" else ""}><span class="hint">The series' own page — the one that lists its episodes.</span></div>
      <div class="field"><label>TMDB id</label><input class="input mono" data-af-tmdb value="${afEsc(if (form.pageOnly) "" else form.tmdb)}" spellcheck="false" inputmode="numeric"${if (off || form.pageOnly) " disabled" else ""}><span class="hint">${afEsc(tmdbHint)}</span></div>
      $go
      <label class="af-opt${if (off) " af-dis" else ""}" data-af-pageonly><span class="af-tick${if (form.pageOnly) " on" else ""}">✓</span><span>This programme is not on TMDB</span></label>
    </div>$why"""
}

private fun stepsHtml(steps: List<AfStep>): String = """<div class="af-steps">${steps.withIndex().joinToString("") { (i, s) ->
    val (cls, glyph) = when (s.state) { "done" -> "done" to "✓"; "fail" -> "fail" to "✕"; "run" -> "run" to (i + 1).toString(); else -> "" to (i + 1).toString() }
    val now = if (s.state == "run") """ <span class="af-now">— now</span>""" else ""
    """<div class="af-step $cls"><span class="ic"><span class="g">$glyph</span></span><div><div class="lb">${afEsc(s.label)}$now</div><div class="fx">${afEsc(s.text)}</div></div><span class="t">${seconds(s.ms)}</span></div>"""
}}</div>"""

private fun failedHtml(inv: AfInvestigation): String {
    val (head, lead) = when {
        inv.state == "stopped" -> "Stopped" to "The investigation was stopped. What it found was discarded and nothing was written."
        inv.limitReached -> "Stopped at this month's limit" to "The next turn could have taken Auto-fetch past its monthly limit, so it was not sent. Nothing was written."
        inv.notASeries -> "Not a series" to "The page could not be identified as a TV series, so nothing was written. Auto-fetch only sets itself up when it is sure — a half-right guess would file files under the wrong title."
        else -> "Can't set up auto-fetch for this URL" to "Nothing was written."
    }
    val link = if (inv.state == "stopped") null else afBugReportUrl(inv)
    val bug = if (link == null) "" else """<div class="row center" style="margin-top:12px;gap:8px;flex-wrap:wrap;">
        <a class="btn sm" href="${afEsc(link)}" target="_blank" rel="noopener" data-stop-click>File a bug report on GitHub ↗</a>
        <span class="tiny muted">The report is pre-filled with how the investigation went: the host, the steps, the turns and the recipe's shape. Never a title, the page's content or the URL's path.</span></div>"""
    return """<div class="af-fail"><h4>${afEsc(head)}</h4><p>${afEsc(lead)}</p>
      ${inv.reason?.let { """<div class="why">reason: ${afMono(it)}</div>""" }.orEmpty()}$bug
      <div class="row center" style="margin-top:12px"><span class="btn sm ghost" data-af-dismissinv>Dismiss</span></div></div>"""
}

private fun proofHtml(r: AfReview): String {
    // FR-4.3 — a mapped proposal is not proved by the engine; the admin's tick on each row is what approves it.
    if (r.numbering == "mapped") return """<span class="af-proof"><span class="says">${afEsc(r.proof)}</span></span>"""
    val means = if (r.pageOnly) "" else if (r.proven)
        """<span class="means">An agreement is a TMDB air date within 7 days of the item's published date, or a TMDB episode name equal to the item's title. Proven needs at least two, with none against.</span>"""
    else """<span class="means">You can save it as it is. Every episode TMDB does not have then waits for your number before it is downloaded — it is never placed on a guess.</span>"""
    val mk = if (r.pageOnly) "✓ checked" else if (r.proven) "✓ proven" else "not proven"
    return """<span class="af-proof${if (!r.pageOnly && !r.proven) " unproven" else ""}"><span class="mk">$mk</span><span class="says">${afEsc(r.proof)}$means</span></span>"""
}

private fun mappedRows(r: AfReview, d: AfReviewDraft): String {
    val shown = if (d.showAll) r.rows else r.rows.take(3)
    val body = shown.joinToString("") { row ->
        val proposed = row.code.isNotEmpty()
        val on = proposed && row.itemId in d.accept
        val lib = if (!proposed) """<span class="badge flat">waiting for a number</span>""" else if (row.have) """<span class="badge ok">have · skipped</span>""" else """<span class="badge warn">missing</span>"""
        """<tr${if (on) " class=\"keep\"" else ""}><td class="tk">${if (proposed) """<span class="af-tick${if (on) " on" else ""}" data-rv-tick="${afEsc(row.itemId)}">✓</span>""" else ""}</td>
          <td class="item">${afEsc(row.title)}</td><td class="arr">→</td><td class="se mono">${afEsc(row.code.ifEmpty { "—" })}</td>
          <td class="tmdb">${afEsc(row.tmdbName.orEmpty())}</td><td class="why">${afEsc(row.why ?: if (proposed) "" else "the model left it out — you give it a number later")}</td><td class="lib">$lib</td></tr>"""
    }
    val proposedCount = r.rows.count { it.code.isNotEmpty() }
    val kept = r.rows.count { it.code.isNotEmpty() && it.itemId in d.accept }
    val more = r.rows.size - shown.size
    val foot = if (more > 0) """<tr><td colspan="7" class="tiny muted" style="padding:8px 10px;">$more more · <a href="#" data-rv-showall>show all ${r.rows.size} items</a></td></tr>"""
    else if (d.showAll && r.rows.size > 3) """<tr><td colspan="7" class="tiny muted" style="padding:8px 10px;"><a href="#" data-rv-showall>show fewer</a></td></tr>""" else ""
    val waits = r.rows.size - kept
    val mapFoot = """<div class="af-mapfoot"><span class="kept">${if (kept == 0) "Nothing ticked" else "<b>$kept of ${r.rows.size} kept</b>"}</span>
      <span class="btn sm ghost${if (proposedCount == 0) " af-dis" else ""}" data-rv-tickall>${if (kept == proposedCount && proposedCount > 0) "Untick all" else "Tick all"}</span><span class="spacer"></span>
      <span class="af-why">The $waits you leave unticked are not refused — they are simply not stored, and they come back as items waiting for a number.</span></div>"""
    return """<table class="af-map"><thead><tr><th class="tk"></th><th>Item on page</th><th class="arr"></th><th>Becomes</th><th>TMDB has</th><th>Why</th><th class="lib">Library</th></tr></thead><tbody>$body$foot</tbody></table>$mapFoot"""
}

private fun mapRows(r: AfReview, all: Boolean): String {
    val shown = if (all) r.rows else r.rows.take(3)
    val body = shown.joinToString("") { row ->
        val becomes = row.code + (if (r.pageOnly) row.date?.let { " · $it" }.orEmpty() else "")
        val badges = if (row.have) """<span class="badge ok">have · skipped</span>""" else
            """<span class="badge warn">missing</span>""" + if (row.notOnTmdb) """ <span class="badge" title="TMDB has no ${afEsc(row.code)} yet — it will be queued under Not on TMDB yet after download">not on TMDB</span>""" else ""
        """<tr${if (row.have) " class=\"have\"" else ""}><td>${afEsc(row.title)}</td><td class="arr">→</td><td class="mono">${afEsc(becomes)}</td><td>$badges</td></tr>"""
    }
    val more = r.rows.size - shown.size
    val foot = if (more > 0) """<tr><td colspan="4" class="tiny muted" style="padding:8px 10px;">$more more · <a href="#" data-rv-showall>show all ${r.rows.size} mappings</a></td></tr>"""
    else if (all && r.rows.size > 3) """<tr><td colspan="4" class="tiny muted" style="padding:8px 10px;"><a href="#" data-rv-showall>show fewer</a></td></tr>""" else ""
    return """<table class="af-map no-tick"><thead><tr><th>Title on page</th><th></th><th>Becomes</th><th>Library</th></tr></thead><tbody>$body$foot</tbody></table>"""
}

private fun reviewHtml(inv: AfInvestigation, r: AfReview, d: AfReviewDraft): String {
    val series = if (r.pageOnly) """<b>${afEsc(r.name)}</b> · page only · not on TMDB"""
    else """<b>${afEsc(r.name)}</b> · TMDB ${r.tmdbId} · <span class="af-conf">✓ confirmed series</span>"""
    val found = """<b>${r.found} episodes</b> — ${r.missing} missing from the library, ${r.have} already there${if (r.notOnTmdb > 0) """ · <b>${r.notOnTmdb} not on TMDB yet</b>""" else ""}"""
    val replacing = r.replaces != null
    val libs = if (r.libraries.size > 1) """<div class="field"><label>Library</label><select class="input" data-rv-lib>${r.libraries.joinToString("") { l ->
        """<option value="${afEsc(l.path)}"${if (l.path == d.libraryPath) " selected" else ""}>${afEsc(l.name.ifBlank { l.path })}</option>""" }}</select></div>""" else ""
    val settings = if (replacing) """<div class="af-why" style="margin-bottom:10px">The fetcher keeps its name, schedule and Keep. Saving replaces its recipe; the old one runs until you do.</div>""" else
        """<div class="field"><label>Name</label><input class="input" data-rv-name value="${afEsc(d.name)}"></div>$libs
        ${scheduleControl(d.schedule)}${afKeepControl(d.retention, d.preview)}
        <div class="af-why" style="margin:-2px 0 10px">Nothing is deleted when you save: retention only ever removes episodes this fetcher has placed itself, and it has placed none yet.</div>
        <div class="field"><label>Run the first time</label><span class="seg"><span data-rv-first="now"${if (d.runFirst) " class=\"on\"" else ""}>Now</span><span data-rv-first="later"${if (d.runFirst) "" else " class=\"on\""}>At next check</span></span></div>"""
    return """<div class="af-review" data-rv="${afEsc(inv.id)}"><div>
      <div class="af-kv">
        <span class="k">Series</span><span class="v">$series</span>
        <span class="k">Source</span><span class="v mono" style="font-size:.8rem;">${afEsc(r.source)}</span>
        <span class="k">Found</span><span class="v">$found</span>
        <span class="k">${if (r.numbering == "mapped") "Numbering" else "Pattern"}</span><span class="v">${if (r.numbering == "mapped") "Mapped. The page's order is not an episode number, so each item is given one by the model's proposal and your tick." else afEsc(r.pattern)}</span>
        <span class="k">Proof</span><span class="v">${proofHtml(r)}</span>
      </div>${if (r.numbering == "mapped") mappedRows(r, d) else mapRows(r, d.showAll)}</div>
      <div class="box flat" style="padding:14px 15px;align-self:start;">$settings
        <div class="row center" style="gap:8px;margin-top:4px;"><span class="btn primary${if (d.saving) " af-dis" else ""}" data-rv-save>${if (d.saving) "Saving…" else if (replacing) "Save the new recipe" else "Save auto-fetcher"}</span><span class="btn sm ghost" data-rv-discard>Discard</span></div></div></div>"""
}

/** The whole *New auto-fetcher* card. */
internal fun afNewCard(o: AfOverview, form: AfForm, rv: AfReviewDraft?): String {
    val inv = o.investigation
    val running = inv?.state == "running"
    val body = when {
        inv == null -> ""
        inv.state == "running" -> stepsHtml(inv.steps) + """<div class="tiny muted" style="margin-top:10px;">Nothing has been written yet. <b>Stop</b> ends the investigation and discards what it found.</div>"""
        inv.state == "review" && inv.review != null && rv != null -> stepsHtml(inv.steps) + reviewHtml(inv, inv.review, rv)
        else -> stepsHtml(inv.steps) + failedHtml(inv)
    }
    val echo = if (inv?.review != null) """<div class="af-echo"><span class="ph"></span><span>${if (inv.review.pageOnly) "<b>${afEsc(inv.review.name)}</b> · page only · the page's own name" else "<b>${afEsc(inv.review.name)}</b> · TV series · TMDB ${inv.review.tmdbId}"}</span></div>""" else ""
    return """<div class="card" id="af-new"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">New auto-fetcher</h3><span class="badge info" style="margin-left:8px;">series only</span><span class="spacer"></span><span class="tiny muted">one investigation at a time</span></div>
      <div class="tiny muted" style="margin:6px 0 14px;">Films, clips and anything that is not a series are refused on purpose — a wrong guess here would file episodes under the wrong title for ever.</div>
      ${afFormHtml(form, o.investigate, running)}$echo$body</div>"""
}
