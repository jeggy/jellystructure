package dev.jellystructure.ui

/*
 * DEB-2477 (DEB-2474, FR-1, FR-2.5, FR-9, FR-10, FR-11, FR-16) — the Fetchers tab's markup and words.
 *
 * In `commonMain` for the reason `EpisodeOrderHtml.kt` gives: the admin frontend has no test target of its own and the markup is the
 * requirement. Nothing here may touch the DOM. The classes are `design/app/ai.css`'s, which is scoped under `.afx` (the host carries
 * it); the words are English literals, as the rest of the admin's (DEB-2657 FR-13). The page draws what the server pushed: the counts
 * behind a sentence (what a retention would delete, how many the page lists) are the server's, never added up here.
 */

/** Times, in the admin's own formats (`JsInterop.kt`); a test passes fixed ones. */
interface AfClock {
    /** `3h ago`. */
    fun ago(sec: Long): String
    /** `04:00` today, `Oct 12, 04:00` otherwise. */
    fun at(sec: Long): String
    /** `5h 12m` — how long until [sec]. */
    fun until(sec: Long): String
    /** `44:12` / `1:02:03`. */
    fun runtime(sec: Int): String
}

/** What the admin has typed into one open row and not saved yet — the form, not the server's state. */
data class AfDraft(val schedule: String, val retention: AfRetention, val preview: AfPreview? = null, val saving: Boolean = false, val ignoreRobots: Boolean = false)

internal fun afEsc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private val AF_CODES = Regex("""\b(40[13]|429|503|robots\.txt|cf-mitigated)\b""")

/** A stop's reason in plain words with the codes in mono (the design's rule for a reason line). */
internal fun afMono(reason: String): String {
    val out = StringBuilder()
    var last = 0
    for (m in AF_CODES.findAll(reason)) {
        out.append(afEsc(reason.substring(last, m.range.first))).append("<span class=\"mono\">").append(afEsc(m.value)).append("</span>")
        last = m.range.last + 1
    }
    return out.append(afEsc(reason.substring(last))).toString()
}

private val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** `every6h` · `daily@04:00` · `weekly@1@04:00`, as the list row and the select say them. */
internal fun afScheduleWords(s: String): String {
    if (s == "every6h") return "Every 6 h"
    val p = s.split('@')
    return when {
        p.firstOrNull() == "daily" && p.size == 2 -> "Daily · ${p[1]}"
        p.firstOrNull() == "weekly" && p.size == 3 -> "Weekly · ${DAYS.getOrElse(p[1].toIntOrNull() ?: -1) { p[1] }} ${p[2]}"
        else -> s
    }
}

/** The schedule's three parts: kind (`every6h` · `daily` · `weekly`), the weekday (0 = Sunday) and the time. */
internal data class AfScheduleParts(val kind: String, val day: Int, val time: String)

internal fun afScheduleParts(s: String): AfScheduleParts {
    val p = s.split('@')
    return when {
        p.firstOrNull() == "daily" && p.size == 2 -> AfScheduleParts("daily", 1, p[1])
        p.firstOrNull() == "weekly" && p.size == 3 -> AfScheduleParts("weekly", p[1].toIntOrNull() ?: 1, p[2])
        else -> AfScheduleParts("every6h", 1, "04:00")
    }
}

internal fun afScheduleOf(kind: String, day: Int, time: String): String = when (kind) {
    "daily" -> "daily@$time"
    "weekly" -> "weekly@$day@$time"
    else -> "every6h"
}

/** The list row's note under the schedule (FR-10.6): *keeps newest 12*. */
internal fun afKeepsNote(r: AfRetention): String? = if (r.newest) "keeps newest ${r.keep}" else null

/** The list row's notes under the schedule: the retention note, then *ignores robots.txt* when the fetcher's tick is on (FR-10.7). */
internal fun afRowNote(f: AfFetcher): String? = listOfNotNull(afKeepsNote(f.retention), if (f.ignoreRobots) "ignores robots.txt" else null).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** The tick's label and its hint (FR-10.7), on the form and on the fetcher's open row. */
internal const val AF_ROBOTS_LABEL = "Ignore this site's robots.txt"
internal const val AF_ROBOTS_HINT = "Reads pages this site asks crawlers to leave alone. Requests stay paced and say who is asking. If the site blocks them, every fetcher on this site stops."

/** FR-10.7 — the tick; [attr] says where it sits (`data-af-robots` on the form, `data-af-robots-row` on the open row). Ticked, its hint shows. */
internal fun afRobotsTick(attr: String, on: Boolean, disabled: Boolean): String =
    """<label class="af-opt${if (disabled) " af-dis" else ""}" $attr><span class="af-tick${if (on) " on" else ""}">✓</span><span>${afEsc(AF_ROBOTS_LABEL)}</span></label>${if (on) """<span class="hint">${afEsc(AF_ROBOTS_HINT)}</span>""" else ""}"""

/** The hint under Keep (FR-10.6): the cost in plain words, from the server's count of what the page lists. */
internal fun afKeepHint(r: AfRetention, p: AfPreview?): String = when {
    !r.newest -> "Every episode stays. Nothing is ever deleted."
    p == null -> "Older ones are deleted from the library."
    else -> "Keeps ${minOf(r.keep, p.onPage)} of the ${p.onPage} on the page. Older ones are deleted from the library."
}

/** The line that replaces a confirmation dialog (FR-16.1): shown only when the server's preview says a run would delete something. */
internal fun afPreviewLine(r: AfRetention, p: AfPreview?): String? = when {
    !r.newest || p == null || p.deletes <= 0 -> null
    p.deletes == 1 -> "Saving this deletes 1 episode at the next run"
    else -> "Saving this deletes ${p.deletes} episodes at the next run"
}

/** The Keep control (FR-10.6): *All episodes* or *The newest [N]*, and *leaves the page* under the second. */
internal fun afKeepControl(r: AfRetention, p: AfPreview?): String {
    val prev = afPreviewLine(r, p)?.let { """<div class="af-prev"><span class="bi">!</span><span>${afEsc(it)}</span></div>""" }.orEmpty()
    return """<div class="field af-keepf"><label>Keep</label>
      <div class="af-keep"><span class="seg">
        <span data-af-keep="all"${if (r.newest) "" else " class=\"on\""}>All episodes</span>
        <span data-af-keep="newest"${if (r.newest) " class=\"on\"" else ""}>The newest<input class="af-num" data-af-keepn value="${r.keep}" inputmode="numeric" aria-label="1 to 999"></span>
      </span></div>
      ${if (r.newest) """<label class="af-opt" data-af-leaves><span class="af-tick${if (r.leavesPage) " on" else ""}">✓</span><span>Also delete an episode once it leaves the page</span></label>""" else ""}
      <span class="hint">${afEsc(afKeepHint(r, p))}</span>$prev</div>"""
}

// ---- the list ------------------------------------------------------------------------------------------------------------

private fun dot(run: AfRun): String = when {
    run.status == "failed" || run.status == "refused" -> "bad"
    run.status == "stopped" || run.status == "needs_reinvestigation" -> "warn"
    run.status == "waiting" -> "wait"
    run.summary.startsWith("+") || run.summary.contains("−") -> "on"
    else -> "ok"
}

/** FR-10.2 — one status slot: the state a viewer of the page has to act on, or *Up to date*. */
internal fun afStatusCell(f: AfFetcher, c: AfClock): String {
    fun cell(badge: String, cls: String, whenLine: String) = """<div class="af-stat"><span class="badge $cls">${afEsc(badge)}</span><div class="when">$whenLine</div></div>"""
    val last = f.lastRun
    val at = (last?.finishedAt ?: last?.startedAt ?: f.lastRunAt)?.let { c.at(it) }
    return when {
        f.siteStopped -> cell("Site stopped", "bad",
            """<span class="mono">${afEsc(f.site)}</span> · ${afMono(f.siteStoppedReason.orEmpty())}${f.siteStoppedAt?.let { " · " + c.at(it) }.orEmpty()}""")
        f.state == "needs_reinvestigation" -> cell("Needs re-investigation", "warn", afEsc("${f.stateReason.ifBlank { "the page changed" }}${at?.let { " · $it" }.orEmpty()}"))
        f.state == "refused" -> cell("Refused", "bad", afEsc(f.stateReason))
        f.running -> cell("Running", "info", "downloading now")
        !f.enabled -> cell("Paused", "flat", "the recipe and history are kept")
        // FR-10.2 — what a viewer of the page has to act on: items the fetcher will not download until they have a number.
        f.waiting.isNotEmpty() -> cell(afWaitingBadge(f.waiting.size), "warn", "not downloaded until ${if (f.waiting.size == 1) "it has" else "they have"} a number you approved")
        last?.status == "failed" -> cell("Last run failed", "warn", afEsc("${last.summary.removePrefix("failed · ")}${at?.let { " · $it" }.orEmpty()}"))
        last == null -> cell("Not run yet", "flat", f.nextRunAt?.let { "first check in ${c.until(it)}" } ?: "waiting for its schedule")
        else -> cell("Up to date", "ok", afEsc("${last.summary}${at?.let { " · $it" }.orEmpty()}"))
    }
}

/** *3 items waiting for a number* (FR-4.3, FR-10.2). */
internal fun afWaitingBadge(n: Int): String = "$n item${if (n == 1) "" else "s"} waiting for a number"

/**
 * FR-4.3 — the band in the open row: every item the page lists that is not downloaded, the page's own words about it, the model's proposal if the
 * admin asked for one, and the two ways to give it a number — type it, or accept the proposal. Both send `…/number`; *Propose* is one model call.
 */
internal fun afWaitingBand(f: AfFetcher, gate: AfGate): String {
    if (f.waiting.isEmpty()) return ""
    val lead = if (f.numbering == "mapped") "This fetcher uses a <b>mapped</b> number, so a new item on the page is never downloaded on a guess. Give it a season and an episode, or ask for a proposal, and accept it."
    else "The pattern is <b>not proven</b>, so every episode TMDB does not have waits for your number before it is downloaded."
    val proposeOn = gate.allowed && f.tmdbId != null
    val items = f.waiting.withIndex().joinToString("") { (i, w) ->
        val p = w.proposal
        val desc = if (w.description != null) """<p class="desc">${afEsc(w.description)}<span class="src">from the page — the item's description, verbatim</span></p>"""
        else """<p class="desc none">No description for this item on the page<span class="src">nothing is invented; the number is yours to give</span></p>"""
        val prop = p?.let { """<div class="af-prop"><span class="lbl">Proposed</span><span class="se">${afEsc(it.code)}</span>${if (it.note.isNotBlank()) """<span class="why">${afEsc(it.note)}</span>""" else ""}</div>""" }.orEmpty()
        val accept = """<span class="btn primary sm${if (p == null) " af-dis" else ""}" data-wait-accept>Accept</span>"""
        val propose = """<span class="btn sm${if (proposeOn) "" else " af-dis"}" data-wait-propose>✦ Propose</span>"""
        val hint = if (i > 0) "" else if (!proposeOn) """<div class="wide af-why"><b>Propose</b> is off: ${afEsc(gate.reason ?: "this fetcher has no TMDB series")}. The season and episode fields still work — type the number yourself and accept it.</div>"""
        else if (p == null) """<div class="wide af-why"><b>Accept</b> is off until a season and an episode are there.</div>""" else ""
        """<div class="af-item" data-wait="${afEsc(w.itemId)}"><div class="who"><b>${afEsc(w.title ?: w.link)}</b>${w.published?.let { """<span class="since">published ${afEsc(it)}</span>""" }.orEmpty()}<span class="badge flat">not downloaded</span></div>
          $desc$prop
          <div class="ctl"><div class="field"><label>Season</label><input class="input mono" data-wait-s value="${p?.season ?: ""}" placeholder="—" inputmode="numeric"></div>
          <div class="field"><label>Episode</label><input class="input mono" data-wait-e value="${p?.episode ?: ""}" placeholder="—" inputmode="numeric"></div>
          <div class="go">$propose$accept</div></div>$hint</div>"""
    }
    return """<div class="af-wait"><p class="lead">$lead</p>$items</div>"""
}

private fun runSubs(lines: List<AfRunLine>): String = lines.mapNotNull { l ->
    when (l.kind) {
        "placed" -> """<div class="af-sub add">+1 · ${afEsc(l.text)}</div>"""
        "deleted" -> """<div class="af-sub del">${afEsc(l.text)}</div>"""
        "retention", "failed", "stopped", "refused", "changed" -> """<div class="af-sub guard">${afEsc(l.text)}</div>"""
        else -> null
    }
}.joinToString("")

/** FR-6.8 / FR-16.6 — the last runs, a run and its own lines one record. */
internal fun afRuns(f: AfFetcher, c: AfClock): String {
    val rows = f.runs.ifEmpty { listOfNotNull(f.lastRun) }.joinToString("") { r ->
        val at = c.at(r.finishedAt ?: r.startedAt)
        """<div class="af-runx"><div class="af-run"><span class="dot ${dot(r)}"></span><span>${afEsc(r.summary.ifBlank { r.status })}</span><span class="when">${afEsc(at)}</span></div>${runSubs(r.lines)}</div>"""
    }.ifEmpty { """<div class="tiny muted">No run yet.</div>""" }
    val tmdb = f.tmdbId?.let { """ · TMDB <a href="https://www.themoviedb.org/tv/$it" target="_blank" rel="noopener">$it</a>""" } ?: " · page only, no TMDB id"
    return """<div class="af-h">Last runs</div><div class="af-runs">$rows</div>
      <div class="tiny muted" style="margin-top:10px;line-height:1.55">${f.placed} episode${if (f.placed == 1) "" else "s"} in the library from this fetcher$tmdb · full log in <a href="#/activity">Activity</a></div>"""
}

/** FR-4.4 — the recipe, read-only: data, never executed. */
internal fun afRecipe(f: AfFetcher): String = f.recipe.joinToString("\n") { l ->
    val comment = if (l.comment.isBlank()) "" else """  <span class="c"># ${afEsc(l.comment)}</span>"""
    if (l.key == "#") """<span class="c"># ${afEsc(l.text)}</span>"""
    else """<span class="k">${afEsc(l.key.padEnd(9))}</span> ${afEsc(l.text)}$comment"""
}

/** FR-2.5 — the band above the recipe: true of every fetcher on the site, so it sits above them. */
internal fun afStopBand(f: AfFetcher, c: AfClock, resuming: Boolean): String {
    val at = f.siteStoppedAt?.let { c.at(it) }.orEmpty()
    val resume = if (resuming) """<span class="btn primary af-dis">Checking the site…</span>""" else """<span class="btn primary" data-af-resume="${afEsc(f.site)}">Resume site</span>"""
    return """<div class="af-band${if (resuming) " hold" else ""}"><span class="bi">${if (resuming) "↻" else "!"}</span>
      <h4>Site stopped · ${afEsc(f.site)}</h4>
      <div class="rsn">${afMono(f.siteStoppedReason.orEmpty())}</div>
      <div class="at">${afEsc(at)}</div>
      <div class="seal">Nothing runs on ${afEsc(f.site)} until you resume it.</div>
      <div class="af-acts">$resume<span class="af-why">${if (resuming) "The server is making one listing request to ${afEsc(f.site)}. Nothing else runs while it does."
        else "<b>Run now</b> is off for every fetcher on ${afEsc(f.site)} — it would reach the site."}</span></div>
      <div class="no-timer">A stop is never retried on a timer: a site that has blocked a client and sees it come back on a schedule usually blocks it for good.</div></div>"""
}

private val SCHEDULE_KINDS = listOf("every6h" to "Every 6 h", "daily" to "Daily at", "weekly" to "Weekly on")

internal fun scheduleControl(schedule: String): String {
    val p = afScheduleParts(schedule)
    val kinds = SCHEDULE_KINDS.joinToString("") { (k, w) -> """<option value="$k"${if (k == p.kind) " selected" else ""}>$w</option>""" }
    val days = DAYS.withIndex().joinToString("") { (i, d) -> """<option value="$i"${if (i == p.day) " selected" else ""}>$d</option>""" }
    return """<div class="field"><label>Check</label><div class="row center" style="gap:6px;flex-wrap:wrap">
      <select class="input" data-af-sched-kind style="width:120px">$kinds</select>
      <select class="input" data-af-sched-day style="width:80px${if (p.kind == "weekly") "" else ";display:none"}">$days</select>
      <input class="input mono" type="time" data-af-sched-time value="${afEsc(p.time)}" style="width:100px${if (p.kind == "every6h") ";display:none" else ""}"></div></div>"""
}

/** The open row: the stop band when the site is stopped, the recipe and its settings, and the last runs (FR-10.6). */
internal fun afOpenRow(f: AfFetcher, d: AfDraft, c: AfClock, resuming: Boolean, gate: AfGate = AfGate(true)): String {
    val dirty = d.schedule != f.schedule || d.retention != f.retention || d.ignoreRobots != f.ignoreRobots
    // DEB-2478 (FR-1.2, FR-4.4) — *Re-investigate page* writes a new recipe beside this one; shown disabled, with the reason, when a new investigation cannot start.
    val reinv = """<span class="btn sm ghost${if (gate.allowed && !f.siteStopped) "" else " af-dis"}" data-af-reinv>Re-investigate page</span>"""
    val reinvWhy = if (!gate.allowed && gate.reason != null) """<div class="af-why" style="margin-top:6px">${afEsc(gate.reason)}</div>""" else ""
    return """<tr class="af-det"><td colspan="7"><div class="af-open" data-fid="${afEsc(f.id)}">${if (f.siteStopped) afStopBand(f, c, resuming) else ""}
      ${afWaitingBand(f, gate)}
      <div class="af-det-in"><div><div class="af-h">Recipe</div><pre class="af-script">${afRecipe(f)}</pre>
        <div class="af-cfg">${scheduleControl(d.schedule)}${afKeepControl(d.retention, d.preview)}
          <div class="field">${afRobotsTick("data-af-robots-row", d.ignoreRobots, false)}</div>
          <div class="af-cfg-go"><span class="btn sm${if (dirty && !d.saving) "" else " af-dis"}" data-af-save>${if (d.saving) "Saving…" else "Save"}</span>$reinv<span class="spacer"></span>
            <span class="btn sm ghost" style="color:var(--bad)" data-af-delete>Delete</span></div>$reinvWhy</div></div>
        <div>${afRuns(f, c)}</div></div></div></td></tr>"""
}

internal fun afRow(f: AfFetcher, open: Boolean, enabledGlobally: Boolean, c: AfClock): String {
    val blocked = f.siteStopped
    val runOff = blocked || f.running || !enabledGlobally || f.state == "needs_reinvestigation" || f.state == "refused"
    val keeps = afRowNote(f)?.let { """<div class="keep">${afEsc(it)}</div>""" }.orEmpty()
    return """<tr class="af-row${if (open) " open" else ""}${if (blocked) " af-stopped" else ""}" data-af-row="${afEsc(f.id)}">
      <td class="nm"><b>${afEsc(f.name)}</b><div class="src">${afEsc(f.site)}</div></td>
      <td class="pat hide-m">${afEsc(f.pattern)}</td>
      <td class="sched">${afEsc(afScheduleWords(f.schedule))}$keeps</td>
      <td class="st">${afStatusCell(f, c)}</td>
      <td class="q hide-m tiny muted">${afEsc(f.quality)}</td>
      <td class="tg"><span class="toggle${if (f.enabled) " on" else ""}${if (blocked) " af-dis" else ""}" data-af-toggle title="Enabled"></span></td>
      <td class="acts-cell"><div class="acts"><span class="btn sm ghost${if (runOff) " af-dis" else ""}" data-af-run>▶ Run now</span></div></td></tr>"""
}

/** The card of fetchers. [drafts] holds the open row's unsaved form; [now] is the server's clock reading for the *next check* line. */
internal fun afFetchersCard(o: AfOverview, openId: String?, drafts: Map<String, AfDraft>, resuming: Set<String>, now: Long, c: AfClock): String {
    val next = o.fetchers.filter { it.enabled && !it.siteStopped }.mapNotNull { it.nextRunAt }.minOrNull()
    val stopped = o.sites.filter { it.stopped }.joinToString(" · ") { "${it.site} is stopped" }
    val header = listOfNotNull(next?.let { if (it <= now) "next check due" else "next check in ${c.until(it)}" }, stopped.ifBlank { null }).joinToString(" · ")
    val body = if (o.fetchers.isEmpty()) """<tr><td colspan="7"><div class="af-empty"><b>No auto-fetchers yet</b>A fetcher is saved with a recipe and a schedule; saved ones are listed here.</div></td></tr>"""
    else o.fetchers.joinToString("") { f ->
        val open = f.id == openId
        afRow(f, open, o.enabled, c) + if (open) afOpenRow(f, drafts[f.id] ?: AfDraft(f.schedule, f.retention), c, f.site in resuming, o.investigate) else ""
    }
    return """<div class="card" style="margin-top:16px;"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">Auto-fetchers</h3>
      <span class="badge" style="margin-left:8px;">${o.fetchers.size}</span><span class="spacer"></span>
      ${if (header.isNotBlank()) """<span class="tiny muted">${afMono(header)}</span>""" else ""}
      <span class="btn sm ghost${if (o.enabled && o.fetchers.isNotEmpty()) "" else " af-dis"}" data-af-runall>▶ Run all now</span></div>
      <div class="tiny muted" style="margin:6px 0 10px;">Each one is a stored recipe and a schedule. Click a row for the recipe and its last runs. A run that finds nothing new is the normal outcome, not a failure.</div>
      <table class="af-tbl"><thead><tr><th>Series</th><th class="hide-m">Numbering</th><th>Schedule</th><th>State</th><th class="hide-m">Quality</th><th></th><th></th></tr></thead>
      <tbody>$body</tbody></table></div>"""
}

/** `[autofetch] enabled = false` (FR-13): the tab stays, and says why nothing runs. */
internal fun afOffNotice(): String =
    """<div class="card" style="margin-top:16px;"><div class="af-empty"><b>Auto-fetch is switched off</b>No fetcher runs and no investigation starts while <span class="mono">enabled</span> under <span class="mono">[autofetch]</span> in config.toml is false. Saved fetchers and their history are kept.</div></div>"""

// ---- Not on TMDB yet ------------------------------------------------------------------------------------------------------

private fun stillUrl(id: Long, n: Int) = "/api/autofetch/queue/$id/still/$n"

/** `12:05` for a moment in seconds. */
internal fun afMoment(sec: Int): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

/** The admin types `12:05` or `725`; anything else is not a moment. */
internal fun afParseMoment(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(':')
    val nums = parts.map { it.trim().toIntOrNull() ?: return null }
    if (nums.any { it < 0 }) return null
    return when (nums.size) { 1 -> nums[0]; 2 -> nums[0] * 60 + nums[1]; 3 -> nums[0] * 3600 + nums[1] * 60 + nums[2]; else -> null }
}

private fun field(label: String, copy: String?, value: String?, source: String, missing: String): String {
    val lab = """<div class="lab">${afEsc(label)}${if (copy != null) """ <span class="btn sm ghost" data-copy="${afEsc(copy)}">Copy</span>""" else ""}</div>"""
    return if (value != null) """<div class="tq-field">$lab<div class="val">${afEsc(value)}</div><div class="src">${afEsc(source)}</div></div>"""
    else """<div class="tq-field">$lab<div class="val none">${afEsc(missing)}</div><div class="src">${afEsc(source)} — nothing is invented; leave the field empty on TMDB or write it yourself there.</div></div>"""
}

private fun tmdbLink(q: AfQueueEntry): String? = q.tmdbId?.let { "https://www.themoviedb.org/tv/$it/season/${q.season}/episode/${q.episode}/edit" }

internal fun afQueueCopyAll(q: AfQueueEntry): String = listOfNotNull(
    "${q.series} · ${q.code}", q.airDate?.let { "Aired: $it" }, q.title?.let { "Episode name: $it" }, q.overview?.let { "Overview: $it" },
).joinToString("\n")

/** FR-9.2 — one episode: its row, and (open) the page's words, where each came from, and the three stills. */
internal fun afQueueRow(q: AfQueueEntry, open: Boolean, picked: Int, c: AfClock): String {
    val incomplete = q.title == null || q.overview == null
    val badge = if (q.state == "submitted") """<span class="badge ok" title="Your note that it was entered on TMDB; the row stays until TMDB returns it">submitted</span>""" else """<span class="badge warn">not on TMDB</span>"""
    val thumb = if (q.stills.isNotEmpty()) """<span class="thumb" style="background:url(${stillUrl(q.id, 0)}) center/cover"></span>""" else """<span class="thumb"></span>"""
    val row = """<div class="tq-row${if (open) " open" else ""}" data-tq="${q.id}">$thumb
      <div class="nm"><b>${afEsc(q.series)} · <span class="mono">${afEsc(q.code)}</span></b><div class="sub">on the page as “${afEsc(q.pageLabel)}” · downloaded ${afEsc(c.ago(q.filedAt))}${q.runtimeSec?.let { " · " + c.runtime(it) }.orEmpty()}</div></div>
      <div class="st">${if (incomplete) """<span class="badge" title="The source page is missing the episode name and/or overview">source incomplete</span>""" else ""}$badge${tmdbLink(q)?.let { """<a class="btn sm ghost" href="$it" target="_blank" rel="noopener" data-stop-click>TMDB entry →</a>""" }.orEmpty()}</div></div>"""
    if (!open) return row
    val pick = picked.coerceIn(0, maxOf(0, q.stills.size - 1))
    val stills = if (q.stills.isEmpty()) """<div class="tq-big">No frame could be cut from this video</div>""" else
        """<div class="tq-big" style="background:url(${stillUrl(q.id, pick)}) center/cover"></div>
          <div class="tq-stills">${q.stills.indices.joinToString("") { i -> """<div class="tq-still${if (i == pick) " on" else ""}" data-pick="$i" style="background:url(${stillUrl(q.id, i)}) center/cover"><span>${afMoment(q.stills[i])}</span></div>""" }}</div>"""
    val meta = listOfNotNull("${q.series} · ${q.code}", q.airDate?.let { "aired $it" }, q.runtimeSec?.let { c.runtime(it) }).joinToString(" · ")
    val det = """<div class="tq-det" data-tqd="${q.id}"><div class="tq-in"><div>
        <div class="tq-field"><div class="lab">Episode <span class="btn sm ghost" data-copy="${afEsc(q.code)}">Copy</span></div><div class="val mono">${afEsc(meta)}</div><div class="src">Season and episode from the fetcher’s pattern; air date from the page’s publish date.</div></div>
        ${field("Episode name", q.title, q.title, "from the page — the episode’s own heading", "No episode name on the source page")}
        ${field("Overview", q.overview, q.overview, "from the page — the episode’s description, verbatim", "No overview found for this episode on the source page")}
      </div><div>
        <div class="tq-field"><div class="lab">Episode still ${if (q.stills.isNotEmpty()) """<a class="btn sm ghost" href="${stillUrl(q.id, pick)}" download="${afEsc(q.code)}-still.jpg">↓ Download still</a>""" else ""}</div>
          $stills
          <div class="src">${if (q.stills.isNotEmpty()) "Three frames picked from the video by a fixed rule: not black, spread across the runtime, clear of the title card and the credits. Nothing leaves this server. " else ""}Pick a different moment for this frame:</div>
          <div class="row center" style="gap:6px;margin-top:6px"><input class="input mono" data-tq-moment placeholder="12:05" style="width:90px" aria-label="A moment in the video, minutes and seconds"><span class="btn sm" data-tq-cut="$pick">Cut this frame</span></div></div>
      </div></div>
      <div class="tq-foot">${tmdbLink(q)?.let { """<a class="btn sm" href="$it" target="_blank" rel="noopener">Open on TMDB ↗</a>""" }.orEmpty()}<span class="btn sm ghost" data-copy="${afEsc(afQueueCopyAll(q))}">Copy all as text</span><span class="spacer"></span>
        <span class="tiny muted">Once TMDB has it, the daily check picks it up and this row clears itself.</span>
        ${if (q.state == "submitted") """<span class="tiny muted">Marked as submitted${q.submittedAt?.let { " · " + c.ago(it) }.orEmpty()}</span>""" else """<span class="btn sm primary" data-tq-submit>Mark as submitted</span>"""}</div></div>"""
    return row + det
}

/** The queue's card (FR-9.2). Absent when nothing is waiting. */
internal fun afQueueCard(o: AfOverview, openId: Long?, picked: Map<Long, Int>, c: AfClock): String {
    if (o.queue.isEmpty()) return ""
    return """<div class="card" id="af-tmdb-card" style="margin-top:16px;"><div class="row center"><h3 style="margin:0;font-size:1.1rem;">Not on TMDB yet</h3>
      <span class="badge warn" style="margin-left:8px;">${o.queue.size} episode${if (o.queue.size == 1) "" else "s"}</span><span class="spacer"></span>
      <span class="tiny muted">TMDB is asked again about these once a day</span></div>
      <div class="tiny muted" style="margin:6px 0 10px;">Downloaded and placed under the page’s own numbering, but TMDB has no such episode. Open one: the name and overview are taken from the source page as written — when the page has none, that is said plainly, nothing is made up. Nothing is written to TMDB from here — TMDB does not take submissions by API — you copy it across and mark it submitted.</div>
      <div>${o.queue.joinToString("") { afQueueRow(it, it.id == openId, picked[it.id] ?: 0, c) }}</div></div>"""
}

/** The whole panel. */
internal fun afPanelHtml(
    o: AfOverview, openFetcher: String?, openQueue: Long?, drafts: Map<String, AfDraft>, resuming: Set<String>, picked: Map<Long, Int>, now: Long, c: AfClock,
    form: AfForm = AfForm(), review: AfReviewDraft? = null,
): String =
    (if (o.enabled) "" else afOffNotice()) + afQueueCard(o, openQueue, picked, c) + (if (o.enabled) """<div style="margin-top:16px">${afNewCard(o, form, review)}</div>""" else "") + afFetchersCard(o, openFetcher, drafts, resuming, now, c)
