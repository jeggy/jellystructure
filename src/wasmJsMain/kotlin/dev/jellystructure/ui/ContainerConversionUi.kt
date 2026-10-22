@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import dev.jellystructure.jobs.MediaJobSnapshot
import dev.jellystructure.media.ContainerConversion
import dev.jellystructure.media.ConversionLatest
import dev.jellystructure.media.ConversionNotice
import dev.jellystructure.media.ConversionPreview
import dev.jellystructure.media.ConversionRequested
import dev.jellystructure.media.ConvertView
import dev.jellystructure.media.NoticeState
import dev.jellystructure.media.RowNote
import dev.jellystructure.model.MediaItem
import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.scrollIntoViewSmooth
import dev.jellystructure.resolver.LanguageResolver
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * DEB-2051 (FR-2 … FR-10) — the AVI→MKV conversion's notice above the Tracks card, its preview, its confirmation and the
 * job's whole life, drawn from direction A (`design/app/detail.css`, `.cv-*`).
 *
 * **Server-pushed state only.** Every fact on screen is the server's [ConversionPreview]; what this object holds of its own
 * is navigation (how far into the flow the operator opened it) and which edits this view saw refused. The mapping from
 * payload to the drawn notice is [ConversionNotice.stateOf], tested on its own. A job's progress arrives as the same push
 * Activity ▸ Jobs uses ([onJob]) and is answered by asking the server again, never by counting here.
 *
 * Strings are the `migrate.*` keys of `i18n/<code>.json` (FR-10), read through the shared [t]. The admin has no language
 * setting of its own, so the language is `?lang=` (remembered in `localStorage`), else English.
 */
object ContainerConversionUi {
    private class Session(val mediaId: String, val title: String, val container: Element, val scope: CoroutineScope)

    private var session: Session? = null
    private var preview: ConversionPreview? = null
    private var view = ConvertView.NOTICE
    private var offline = false
    private var dismissed = false
    private var scrim: HTMLElement? = null
    private var refreshScheduled = false
    private var installed = false

    /** The languages this view saw refused, by track specifier (FR-1). Cleared when the page is for another title. */
    private val refusals = LinkedHashMap<String, String>()
    private var refusalsFor: String? = null

    /** The Tracks card re-draws its rows when the notice changes (the row sub-line and the lock follow it). */
    var onChange: (() -> Unit)? = null

    // ── strings ────────────────────────────────────────────────────────────────

    private fun lang(): String {
        val asked = window.location.search.removePrefix("?").split("&").firstOrNull { it.startsWith("lang=") }?.removePrefix("lang=")
        if (!asked.isNullOrBlank()) runCatching { localStorage.setItem("js-admin-lang", asked) }
        return asked?.takeIf { it.isNotBlank() } ?: runCatching { localStorage.getItem("js-admin-lang") }.getOrNull() ?: "en"
    }

    private fun s(key: String, vars: Map<String, String> = emptyMap()) = t("migrate.$key", lang(), vars)

    // ── what the page tells this object ────────────────────────────────────────

    /** Called each time a film's Tracks tab is wired: mounts into `#cv-mount` and asks the server what it has for the file. */
    fun attach(container: Element, scope: CoroutineScope, item: MediaItem) {
        install()
        if (refusalsFor != item.id) { refusals.clear(); refusalsFor = item.id; dismissed = false; view = ConvertView.NOTICE }
        session = Session(item.id, item.title, container, scope)
        val mount = document.getElementById("cv-mount") ?: return
        // The same mount node survives a re-wire of the tab; a listener per attach would answer one press several times.
        if (mount.getAttribute("data-cv-wired") != "1") {
            mount.setAttribute("data-cv-wired", "1")
            mount.addEventListener("click") { e -> (e.target as? Element)?.closest("[data-cv]")?.let { act(it.getAttribute("data-cv") ?: "", e) } }
            mount.addEventListener("keydown") { e ->
                val k = e as? KeyboardEvent ?: return@addEventListener
                if (k.key == "Enter" || k.key == " ") (k.target as? Element)?.closest("[data-cv]")?.let { k.preventDefault(); act(it.getAttribute("data-cv") ?: "", k) }
            }
        }
        scope.launch { fetch(); render(focus = false) }
    }

    /** FR-1 — a language edit on this film was refused: the AVI cannot keep it. Opens the notice for it. */
    fun refused(rows: Map<String, String>) {
        val sess = session ?: return
        refusals.putAll(rows)
        dismissed = false; view = ConvertView.NOTICE
        sess.scope.launch { fetch(); render(focus = true) }
    }

    /** The push Activity ▸ Jobs uses: a conversion job for the title on this page changed. */
    fun onJob(job: MediaJobSnapshot) {
        val sess = session ?: return
        if (job.type != ContainerConversion.JOB_TYPE || job.mediaId != sess.mediaId || refreshScheduled) return
        refreshScheduled = true
        window.setTimeout({
            refreshScheduled = false
            sess.scope.launch { refreshAndRender() }
            null
        }, 800)
    }

    /** The push connection went down or came back. A running job is unaffected on the server; the notice says so. */
    fun connection(up: Boolean) {
        val sess = session ?: return
        offline = !up
        render(focus = false)
        if (up) sess.scope.launch { refreshAndRender() }
    }

    fun locked(): Boolean = state().let { it == NoticeState.RUNNING || it == NoticeState.OFFLINE }

    /** The sub-line under track [specifier] (FR-2, FR-9), or "" — only the tracks the operator asked a language for have one. */
    fun rowNoteHtml(specifier: String): String =
        rowNoteHtml(rowNoteFor(specifier), requested().firstOrNull { it.specifier == specifier }?.language)

    /** An episode's row carries FR-2's sentence without the link (the conversion is films-only). */
    fun episodeRowNoteHtml(language: String?): String =
        rowNoteHtml(ConversionNotice.rowNote(NoticeState.NONE, language != null, episode = true), language)

    /** The classes the Tracks card adds to a row: every row is locked while the job runs; the asked-for one is flagged. */
    fun rowClass(specifier: String): String = when {
        locked() -> "lock"
        rowNoteFor(specifier) != RowNote.NONE -> "flag"
        else -> ""
    }

    private fun rowNoteFor(specifier: String): RowNote =
        if (requested().none { it.specifier == specifier }) RowNote.NONE
        else ConversionNotice.rowNote(state(), true, episode = false)

    // ── state ──────────────────────────────────────────────────────────────────

    private fun refusalSeen() = refusals.isNotEmpty()

    private fun state(): NoticeState {
        if (dismissed && !jobIsActive()) return NoticeState.NONE
        return ConversionNotice.stateOf(preview, refusalSeen(), view, offline, episode = false)
    }

    private fun jobIsActive(): Boolean = preview?.latest?.state.let { it == ContainerConversion.QUEUED || it == ContainerConversion.RUNNING }

    private fun requested(): List<ConversionRequested> =
        preview?.latest?.requested?.takeIf { it.isNotEmpty() && !refusalSeen() } ?: refusals.map { ConversionRequested(it.key, it.value) }

    private suspend fun fetch() {
        val sess = session ?: return
        preview = MediaApi.containerConversion(sess.mediaId, refusals.map { ConversionRequested(it.key, it.value) })
    }

    private suspend fun refreshAndRender() {
        val before = preview?.latest?.state
        fetch()
        val after = preview?.latest?.state
        // The film now lives at the `.mkv` with the language written into it: the page asks the server for the title again.
        if ((before == ContainerConversion.RUNNING || before == ContainerConversion.QUEUED) && after == ContainerConversion.VERIFIED) {
            refusals.clear()
            val sess = session ?: return
            renderMediaDetail(sess.container, sess.scope, sess.mediaId, "tracks")
            return
        }
        render(focus = false)
    }

    // ── actions ────────────────────────────────────────────────────────────────

    private fun act(action: String, e: org.w3c.dom.events.Event) {
        e.preventDefault()
        val sess = session ?: return
        when (action) {
            "review", "again", "retry" -> { view = ConvertView.PREVIEW; dismissed = false; sess.scope.launch { fetch(); render(focus = true) } }
            "confirm" -> { view = ConvertView.CONFIRM; render(focus = false) }
            "close" -> { view = ConvertView.NOTICE; render(focus = false) }
            "keep" -> { view = ConvertView.NOTICE; dismissed = true; render(focus = false); onChange?.invoke() }
            "why" -> { dismissed = false; view = ConvertView.NOTICE; render(focus = true) }
            "seedtab" -> (document.querySelector("[data-tab=\"seeding\"]") as? HTMLElement)?.click()
        }
    }

    private fun start() {
        val sess = session ?: return
        closeDialog(returnFocus = false)
        sess.scope.launch {
            val answer = MediaApi.startContainerConversion(sess.mediaId, requested())
            view = ConvertView.NOTICE
            // A refusal (409) or a job (202) is the server's: ask again and draw what it now says.
            if (answer.block != null) view = ConvertView.PREVIEW
            fetch(); render(focus = true)
        }
    }

    // ── drawing ────────────────────────────────────────────────────────────────

    private fun render(focus: Boolean) {
        val mount = document.getElementById("cv-mount") as? HTMLElement
        if (mount == null) { closeDialog(false); session = null; return }
        val st = state()
        mount.innerHTML = noticeHtml(st)
        if (ConversionNotice.dialogOpen(st, view)) openDialog() else closeDialog(returnFocus = false)
        if (focus) (mount.querySelector("h3") as? HTMLElement)?.focus()
        onChange?.invoke()
    }

    private fun trackLabel(specifier: String): String {
        val parts = specifier.split(':')
        val n = (parts.lastOrNull()?.toIntOrNull() ?: 0) + 1
        return when (parts.getOrNull(1)) { "a" -> "Audio $n"; "s" -> "Subtitle $n"; "v" -> "Video $n"; else -> specifier }
    }

    private fun langLabel(code: String): String {
        val norm = LanguageResolver.normalize(code.trim().lowercase())
        val name = getLanguages().find { it.first == norm || it.first == code }?.second ?: code
        val iso3 = LanguageResolver.toIso6392(code) ?: code
        return "$name ($iso3)"
    }

    private fun fileName(path: String) = path.substringAfterLast('/')

    private fun humanBytes(b: Long): String = when {
        b >= 1L shl 30 -> "${(b * 10 / (1L shl 30)) / 10.0} GB"
        b >= 1L shl 20 -> "${b / (1L shl 20)} MB"
        else -> "${b / 1024} KB"
    }

    private fun humanTime(seconds: Long): String = when {
        seconds < 60 -> s("timeSec", mapOf("n" to seconds.toString()))
        seconds < 3600 -> s("timeMin", mapOf("n" to ((seconds + 30) / 60).toString()))
        else -> s("timeHourMin", mapOf("h" to (seconds / 3600).toString(), "m" to ((seconds % 3600) / 60).toString()))
    }

    private fun estimateText(p: ConversionPreview): String? {
        val sec = p.estimateSeconds; val temp = p.tempBytes
        if (sec == null || temp == null) return null
        return s("estTpl", mapOf("time" to humanTime(sec), "size" to humanBytes(temp)))
    }

    /** `{track}`, `{lang}`, `{file}` … for one drawing: the requested tracks and languages, joined when there are several. */
    private fun fill(text: String, file: String? = null, extra: Map<String, String> = emptyMap()): String {
        val rows = requested()
        val track = rows.joinToString(", ") { trackLabel(it.specifier) }
        val language = rows.joinToString(", ") { langLabel(it.language) }
        var out = text.replace("{track}", track).replace("{lang}", language)
            .replace("{file}", file ?: preview?.sourcePath?.let(::fileName).orEmpty())
            .replace("{title}", session?.title.orEmpty())
        for ((k, v) in extra) out = out.replace("{$k}", v)
        return out
    }

    private fun btn(text: String, cls: String, action: String) =
        """<span class="btn${if (cls.isNotEmpty()) " $cls" else ""}" role="button" tabindex="0" data-cv="$action">${text.esc()}</span>"""

    private fun scopeHtml(file: String) = """<span class="cv-scope">${s("fileScope", mapOf("file" to file)).esc()}</span>"""

    private fun head(icoCls: String, glyph: String, badgeCls: String, badge: String, file: String) =
        """<div class="cv-h"><span class="cv-ico $icoCls">$glyph</span><span class="badge $badgeCls">${badge.esc()}</span>${scopeHtml(file)}</div>"""

    private fun noticeHtml(st: NoticeState): String {
        val p = preview
        val file = p?.sourcePath?.let(::fileName).orEmpty().ifEmpty { p?.destinationPath?.let(::fileName).orEmpty() }
        return when (st) {
            NoticeState.NONE -> ""
            NoticeState.LIMITATION -> limitation(file)
            NoticeState.PREVIEW, NoticeState.ESTIMATE_UNKNOWN -> previewHtml(file, st == NoticeState.ESTIMATE_UNKNOWN)
            NoticeState.BLOCKED_SEEDING -> blocked(file, "⛨", s("seedShort") + ".", s("seedLong"), btn(s("openSeeding"), "ghost", "seedtab"))
            NoticeState.BLOCKED_UNKNOWN -> blocked(file, "?", s("unkShort") + ".", s("unkBody"))
            NoticeState.BLOCKED_NAME_TAKEN -> blocked(file, "!", s("takenShort", mapOf("file" to fileName(p?.destinationPath ?: p?.latest?.message.orEmpty()))), s("takenBody"))
            NoticeState.BLOCKED_NO_ROOM -> blocked(file, "!", s("roomShort", mapOf(
                "need" to humanBytes(p?.block?.neededBytes ?: 0L), "free" to humanBytes(p?.block?.freeBytes ?: 0L))), s("roomBody"))
            NoticeState.BLOCKED_PATH_MISMATCH -> """<section class="cv-card pth" role="region" aria-labelledby="cv-h3">
                ${head("pth", "≠", "bad", s("pathBadge"), file)}
                <h3 id="cv-h3" tabindex="-1">${s("pathTitle").esc()}</h3>
                <p>${s("pathBody").esc()}.</p></section>"""
            NoticeState.RUNNING, NoticeState.OFFLINE -> running(file, st == NoticeState.OFFLINE)
            NoticeState.VERIFIED -> verified()
            NoticeState.FAILED_TOOL -> failedTool(file)
            NoticeState.FAILED_UNVERIFIED -> failedUnverified(file)
        }
    }

    private fun limitation(file: String) = """<section class="cv-card lim" role="region" aria-labelledby="cv-h3">
        ${head("info", "i", "info", s("limBadge"), file)}
        <h3 id="cv-h3" tabindex="-1">${s("limTitle").esc()}</h3>
        <p>${fill(s("limBody")).esc()} <b>${s("streamCopy").esc()}.</b></p>
        <div class="cv-acts">${btn(s("review"), "primary", "review")}${btn(s("keep"), "ghost", "keep")}</div>
        <p class="cv-m">${s("keepNote").esc()}</p></section>"""

    private fun previewHtml(file: String, noEstimate: Boolean): String {
        val p = preview ?: return ""
        val est = estimateText(p)
        val streams = p.streams.joinToString("<br>") { st ->
            val label = "${st.kind.replaceFirstChar { it.uppercase() }} ${st.specifier.substringAfterLast(':').toIntOrNull()?.plus(1) ?: 1}"
            "${label.esc()} · ${if (st.kind == "video") "" else st.codec.uppercase().esc() + " · "}${s("copied").esc()}"
        }
        val langs = requested().joinToString("<br>") { r -> "${trackLabel(r.specifier).esc()}: ${s("unknown").esc()} → <b>${langLabel(r.language).esc()}</b>" }
        return """<section class="cv-card lim" role="region" aria-labelledby="cv-h3">
        ${head("info", "i", "info", s("limBadge"), file)}
        <h3 id="cv-h3" tabindex="-1">${s("pvTitle").esc()}</h3>
        <dl class="cv-pvt">
          <dt>${s("container").esc()}</dt><dd><span class="chip">AVI</span> → <span class="chip">MKV</span></dd>
          <dt>${s("ext").esc()}</dt><dd><span class="mono">.avi</span> → <span class="mono">.mkv</span></dd>
          <dt>${s("streams").esc()}</dt><dd>$streams<div class="cv-m">${s("streamCopy").esc()}</div></dd>
          <dt>${s("langReq").esc()}</dt><dd>$langs</dd>
          <dt>${s("estimate").esc()}</dt><dd>${if (noEstimate || est == null)
              """<span class="cv-unk">${s("estUnk").esc()}</span><div class="cv-m">${s("estUnkBody").esc()}</div>""" else est.esc()}<div class="cv-m">${s("tempWhere").esc()}</div></dd>
          <dt>${s("before").esc()}</dt><dd>${s("beforeVal").esc()}</dd>
          <dt>${s("oldFile").esc()}</dt><dd>${fill(s("oldVal"), file = file).esc()}</dd>
        </dl>
        <div class="cv-acts">${btn(s("cont"), "primary", "confirm")}${btn(t("action.cancel", lang()), "ghost", "close")}</div></section>"""
    }

    private fun blocked(file: String, glyph: String, short: String, body: String, extra: String = "") = """<section class="cv-card blk" role="region" aria-labelledby="cv-h3">
        ${head("warn", glyph, "warn", s("blkBadge"), file)}
        <h3 id="cv-h3" tabindex="-1">${s("blkTitle").esc()}</h3>
        <p><b>${short.esc()}</b> ${body.esc()}</p>
        <p class="cv-m">${fill(s("whyNeeded")).esc()}</p>
        <div class="cv-acts">${btn(s("checkAgain"), "", "again")}$extra${btn(t("action.close", lang()), "ghost", "close")}</div></section>"""

    private fun steps(marks: List<String>, withNotes: Boolean = false): String = """<ol class="cv-steps">${listOf("step1", "step2", "step3").mapIndexed { i, key ->
        val m = marks.getOrElse(i) { "" }
        val note = if (withNotes) when (m) { "dn" -> s("stepDone"); "x" -> fill(s("stepNoTag")); "no" -> s("stepNot"); else -> null } else null
        """<li class="${if (m == "no") "" else m}"><i>${if (m == "x") "✕" else (i + 1).toString()}</i>${s(key).esc()}${note?.let { " <em>· ${it.esc()}</em>" } ?: ""}</li>"""
    }.joinToString("")}</ol>"""

    private fun running(file: String, off: Boolean): String {
        val n = (preview?.latest?.step ?: 1).coerceIn(1, 3)
        val marks = List(3) { i -> if (i + 1 < n) "dn" else if (i + 1 == n) "on" else "" }
        val stepName = s("step$n")
        val leave = s("leave").esc().let { raw ->
            listOf("Activity ▸ Jobs", "Aktivitet ▸ Jobs", "Virksemi ▸ Arbeiði").fold(raw) { acc, label -> acc.replace(label, """<a href="#/activity">$label</a>""") }
        }
        return """<section class="cv-card job${if (off) " off" else ""}" role="region" aria-labelledby="cv-h3" aria-live="polite">
        ${head("hi", "↻", "info", s("runBadge"), file)}
        <h3 id="cv-h3" tabindex="-1">${s("runTitle").esc()}</h3>
        ${if (off) """<div class="cv-offb"><b>${s("offTitle").esc()}</b><span>${s("reconnecting").esc()}</span></div>""" else ""}
        <span class="cv-stp">${(if (off) s("lastKnown", mapOf("step" to stepName)) else s("stepOf", mapOf("n" to n.toString()))).esc()}</span>
        ${steps(marks)}
        <p>${s("orig").esc()}</p>
        <p class="cv-m">${if (off) s("offBody").esc() else leave}</p></section>"""
    }

    private fun verified(): String {
        val p = preview ?: return ""
        val latest = p.latest ?: return ""
        val mkv = fileName(p.destinationPath)
        return """<section class="cv-card okk" role="region" aria-labelledby="cv-h3">
        ${head("ok", "✓", "ok", s("okBadge"), mkv)}
        <h3 id="cv-h3" tabindex="-1">${s("okTitle").esc()}</h3>
        <p>${fill(s("okBody"), file = mkv).esc()}</p>
        <div class="cv-files"><span class="cv-lb">${s("nowDisk").esc()}</span>
          <div class="f"><span class="mono">${p.destinationPath.esc()}</span><span class="badge ok">${s("newIn").esc()}</span></div>
          <div class="f"><span class="mono">${p.keptPath.esc()}</span><span class="badge">${s("origKept").esc()}</span></div>
        </div>
        ${verifiedNext(latest)}
        <p class="cv-m">${s("keptUntil").esc()}</p></section>"""
    }

    private fun verifiedNext(latest: ConversionLatest): String {
        val rows = buildList {
            if (latest.jellyfinPending) add("""<div class="cv-pend"><span class="cv-lb">${s("othersH").esc()}</span><span>${s("others").esc()}</span><span><span class="badge">${s("waitIdx").esc()}</span></span></div>""")
            if (latest.radarrNotTaken) add("""<div class="cv-pend"><span>${s("radarr").esc()}</span><span><span class="badge warn">${s("radarrBadge").esc()}</span></span></div>""")
        }
        return if (rows.isEmpty()) "" else """<div class="cv-next">${rows.joinToString("")}</div>"""
    }

    private fun failedTool(file: String): String {
        val step = (preview?.latest?.step ?: 1).coerceIn(1, 3)
        return """<section class="cv-card fail" role="region" aria-labelledby="cv-h3">
        ${head("bad", "!", "bad", s("failBadge"), file)}
        <h3 id="cv-h3" tabindex="-1">${s("failTitle").esc()}</h3>
        <p>${fill(s("failBody"), file = file).esc()}</p>
        <span class="cv-stp">${s("stoppedAt", mapOf("step" to s("step$step"))).esc()}</span>
        <div class="cv-acts">${btn(s("tryAgain"), "", "retry")}<a class="btn ghost" href="#/activity">${s("showLog").esc()}</a></div></section>"""
    }

    private fun failedUnverified(file: String) = """<section class="cv-card fail" role="region" aria-labelledby="cv-h3">
        ${head("bad", "✕", "bad", s("unvBadge"), file)}
        <h3 id="cv-h3" tabindex="-1">${s("unvTitle").esc()}</h3>
        <p>${fill(s("unvBody"), file = file).esc()}</p>
        ${steps(listOf("dn", "x", "no"), withNotes = true)}
        <div class="cv-acts">${btn(s("tryAgain"), "", "retry")}<a class="btn ghost" href="#/activity">${s("showLog").esc()}</a></div></section>"""

    private fun rowNoteHtml(note: RowNote, language: String?): String {
        val requested = language?.let { s("requested", mapOf("lang" to langLabel(it))) }.orEmpty()
        val why = """<a data-cv="why" role="button" tabindex="0" style="cursor:pointer;text-decoration:underline;">${s("why").esc()} ↑</a>"""
        val above = """<a data-cv="why" role="button" tabindex="0" style="cursor:pointer;text-decoration:underline;">${s("seeAbove").esc()} ↑</a>"""
        fun ico(cls: String, glyph: String) = """<span class="cv-ico s $cls">$glyph</span>"""
        val inner = when (note) {
            RowNote.NONE -> return ""
            RowNote.NOT_KEPT_WITH_LINK -> "${ico("info", "!")}<b>${s("notKept").esc()}</b><span>${requested.esc()}</span>$why"
            RowNote.NOT_KEPT_NO_LINK -> "${ico("info", "!")}<b>${s("notKept").esc()}</b><span>${requested.esc()}</span>"
            RowNote.BLOCKED -> "${ico("warn", "!")}<b>${s("blkRow").esc()}</b>$above"
            RowNote.PATH_MISMATCH -> "${ico("pth", "≠")}<b>${s("pthRow").esc()}</b>$above"
            RowNote.LOCKED -> "<b>${s("locked").esc()}</b><span>${requested.esc()}</span>"
            RowNote.STILL_FAILED -> "${ico("bad", "!")}<b>${s("stillFail").esc()}</b>$above"
            RowNote.STILL_UNVERIFIED -> "${ico("bad", "!")}<b>${s("stillUnv").esc()}</b>$above"
        }
        return """<div class="trk-sub">$inner</div>"""
    }

    // ── the confirmation (FR-6). D3: the file name is text, never a field. ─────

    private fun dialogHtml(): String {
        val p = preview ?: return ""
        val est = estimateText(p)
        val title = session?.title.orEmpty()
        val rows = requested().joinToString("; ") { "${trackLabel(it.specifier)} → <b>${langLabel(it.language).esc()}</b>" }
        return """<div class="modal cv-dlg" role="dialog" aria-modal="true" aria-labelledby="cv-dh">
          <h3 id="cv-dh" tabindex="-1">${s("cfTitle", mapOf("title" to title)).esc()}</h3>
          <div class="cv-cmp">
            <div class="cv-side"><span class="cv-lb">${s("source").esc()}</span><div class="cv-path">${p.sourcePath.esc()}</div>
              <span class="tiny muted">AVI${p.tempBytes?.let { " · ${humanBytes(it)}" } ?: ""}</span><span class="cv-keep">${s("srcStays").esc()}</span></div>
            <div class="cv-arr">→</div>
            <div class="cv-side"><span class="cv-lb">${s("dest").esc()}</span><div class="cv-path">${p.destinationPath.esc()}</div>
              <div class="cv-fld"><label id="cv-fnl">${s("fname").esc()}</label>
                <span class="cv-fname" aria-labelledby="cv-fnl">${fileName(p.destinationPath).esc()}</span></div></div>
          </div>
          <div class="cv-sum">$rows · ${s("streamCopy").esc()} · ${(est ?: s("estUnk")).esc()}</div>
          <div class="cv-acts">${btn(t("action.close", lang()), "ghost", "dlg-close")}${btn(s("start"), "primary", "dlg-start")}</div></div>"""
    }

    private fun openDialog() {
        if (scrim != null) return
        val back = document.createElement("div") as HTMLElement
        back.className = "modal-back cv-back open"
        back.innerHTML = dialogHtml()
        document.body?.appendChild(back)
        scrim = back
        (back.querySelector("h3") as? HTMLElement)?.focus()
        back.addEventListener("click") { e ->
            val a = (e.target as? Element)?.closest("[data-cv]")
            if (e.target === back) { closeDialog(returnFocus = true); view = ConvertView.PREVIEW; return@addEventListener }
            if (a == null) return@addEventListener
            if (a.getAttribute("data-cv") == "dlg-start") start() else { view = ConvertView.PREVIEW; closeDialog(returnFocus = true) }
        }
        back.addEventListener("keydown") { e ->
            val k = e as? KeyboardEvent ?: return@addEventListener
            if (k.key == "Escape") { k.stopPropagation(); view = ConvertView.PREVIEW; closeDialog(returnFocus = true) }
            if ((k.key == "Enter" || k.key == " ") && (k.target as? Element)?.getAttribute("data-cv") != null) { k.preventDefault(); (k.target as? HTMLElement)?.click() }
            if (k.key == "Tab") {
                val f = back.querySelectorAll("[tabindex=\"0\"]")
                val n = f.length
                if (n == 0) return@addEventListener
                val idx = (0 until n).indexOfFirst { f.item(it) === document.activeElement }
                if (k.shiftKey && idx <= 0) { k.preventDefault(); (f.item(n - 1) as? HTMLElement)?.focus() }
                else if (!k.shiftKey && idx == n - 1) { k.preventDefault(); (f.item(0) as? HTMLElement)?.focus() }
            }
        }
    }

    private fun closeDialog(returnFocus: Boolean) {
        val back = scrim ?: return
        back.remove(); scrim = null
        if (returnFocus) (document.querySelector("#cv-mount [data-cv=\"confirm\"]") as? HTMLElement)?.focus()
    }

    // ── the row link lives in the Tracks card, outside the mount ───────────────

    private fun install() {
        if (installed) return
        installed = true
        document.addEventListener("click") { e ->
            val el = (e.target as? Element)?.closest("#trk-list [data-cv=\"why\"]") ?: return@addEventListener
            e.preventDefault()
            act(el.getAttribute("data-cv") ?: "", e)
            document.getElementById("cv-mount")?.let { scrollIntoViewSmooth(it as JsAny) }
        }
        document.addEventListener("keydown") { e ->
            val k = e as? KeyboardEvent ?: return@addEventListener
            if (k.key != "Enter" && k.key != " ") return@addEventListener
            (k.target as? Element)?.closest("#trk-list [data-cv=\"why\"]")?.let { k.preventDefault(); (it as? HTMLElement)?.click() }
        }
    }
}
