package dev.jellystructure.ui

import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.ProcessingApi
import dev.jellystructure.api.ScanDialogView
import dev.jellystructure.api.ScanFeatureView
import dev.jellystructure.api.ScanRunRequest
import dev.jellystructure.api.ScanRunStarted
import dev.jellystructure.api.ScanStart
import dev.jellystructure.jobs.JobEvent
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/*
 * DEB-1664 (FR-5) — the one Scan dialog behind every door: ▶ Scan library and its full face (Dashboard, Library), the palette's
 * Start full scan, the Music and Audiobooks empty states' Scan now, a library card's Scan (scoped to that library) and the
 * Dashboard's Start new scan. Two panes (the features, then the chosen feature's parts); at 640 px and under, a drill-in.
 *
 * Everything on it is the server's (`GET /api/processing/scan`, FR-12.1): the names, every sentence and every reason. The page
 * composes one number, *n of m*, because it moves with each tick. What a person unticks is remembered per part, for every page,
 * and is always shown (FR-5.7). `.pfx` is on the scrim, because a dialog is rendered at body level and is nobody's descendant.
 */

private const val SKIP_KEY = "js-processing-skip"

/** The key the step-based dialog wrote. A step id is a part id, so what it held is the same list. */
private const val OLD_SKIP_KEY = "js-pipeline-skip"
private const val SCRIM_ID = "scan-dialog-scrim"

private fun isPhoneWidth(): Boolean = js("window.matchMedia('(max-width: 640px)').matches")

/**
 * Opens the dialog for everything, or for the library [library] (its Jellyfin id). [recheck] opens it with *Re-check everything* on
 * (the *(full rescan)* face, the palette's *Start full scan*). [select] is the feature the right pane opens on. [onStarted] runs after the run has started and the dialog has closed.
 * If the dialog cannot be read, nothing starts: starting blind would skip the preview the person asked for.
 */
internal fun openScanDialog(scope: CoroutineScope, library: String? = null, recheck: Boolean = false, select: String? = null, onStarted: (ScanRunStarted) -> Unit = {}) {
    if (document.getElementById(SCRIM_ID) != null) return
    scope.launch {
        val model = ProcessingApi.scanDialog(library)
        if (model == null) {
            ScanToasts.problem("Couldn't read what a scan would do — nothing was started.")
            return@launch
        }
        ScanDialog(scope, model, recheck, select, onStarted).open()
    }
}

private fun remembered(): MutableSet<String> {
    val raw = localStorage.getItem(SKIP_KEY) ?: localStorage.getItem(OLD_SKIP_KEY) ?: ""
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
}

/** The ids a tick on this feature stands for: its one tick, or every part that can be ticked. */
private fun ScanFeatureView.tickables(): List<String> =
    if (locked || !canRun) emptyList() else tickId?.let { listOf(it) } ?: parts.filter { it.canRun }.map { it.id }

private class ScanDialog(
    private val scope: CoroutineScope,
    private val m: ScanDialogView,
    private var recheck: Boolean,
    select: String?,
    private val onStarted: (ScanRunStarted) -> Unit,
) {
    private val scrim = document.createElement("div") as HTMLElement
    private val unticked: MutableSet<String> = remembered()
    private var selected: String = m.features.firstOrNull { it.id == select }?.id
        ?: (m.features.firstOrNull { it.canRun && it.parts.isNotEmpty() } ?: m.features.firstOrNull())?.id ?: ""
    private var drilled = false
    private var starting = false
    private var phone = isPhoneWidth()
    private var onKey: ((Event) -> Unit)? = null
    private var onResize: ((Event) -> Unit)? = null

    fun open() {
        scrim.id = SCRIM_ID
        scrim.className = "pf-scrim pfx open"
        scrim.setAttribute("role", "dialog")
        scrim.setAttribute("aria-modal", "true")
        scrim.setAttribute("aria-label", m.title)
        document.body?.appendChild(scrim)
        render()
        scrim.addEventListener("click") { e -> onClick(e) }
        scrim.addEventListener("keydown") { e ->
            val k = e as? KeyboardEvent ?: return@addEventListener
            val t = k.target as? HTMLElement ?: return@addEventListener
            if ((k.key == "Enter" || k.key == " ") && t.getAttribute("data-act") != null && t.tagName != "BUTTON" && t.tagName != "A") { k.preventDefault(); t.click() }
        }
        val keys: (Event) -> Unit = { e -> if ((e as? KeyboardEvent)?.key == "Escape") close() }
        onKey = keys
        document.addEventListener("keydown", keys)
        val resized: (Event) -> Unit = { _ ->
            val now = isPhoneWidth()
            if (now != phone) { phone = now; render() }
        }
        onResize = resized
        window.addEventListener("resize", resized)
        scrim.setAttribute("tabindex", "-1")
        scrim.focus()
    }

    private fun close() {
        onKey?.let { document.removeEventListener("keydown", it) }
        onResize?.let { window.removeEventListener("resize", it) }
        scrim.remove()
    }

    private fun feature(id: String) = m.features.firstOrNull { it.id == id }

    // ── what a click does ───────────────────────────────────────────────────────────────────────────────────────────

    private fun onClick(e: Event) {
        val target = e.target as? HTMLElement ?: return
        if (target == scrim) { close(); return }
        val el = target.closest("[data-act]") as? HTMLElement ?: return
        val id = el.getAttribute("data-id") ?: ""
        when (el.getAttribute("data-act")) {
            "cancel" -> close()
            "link" -> close()   // Settings ▸ Processing and Activity are ordinary links: the dialog gets out of their way
            "recheck" -> { recheck = !recheck; render() }
            "pick" -> {
                val f = feature(id) ?: return
                selected = id
                if (phone && f.parts.isNotEmpty()) drilled = true
                render()
            }
            "tick" -> {
                val f = feature(id) ?: return
                val ids = f.tickables()
                if (ids.isEmpty()) return
                if (ids.all { it !in unticked }) unticked.addAll(ids) else unticked.removeAll(ids.toSet())
                render()
            }
            "part" -> {
                if (el.getAttribute("aria-disabled") == "true") return
                if (!unticked.add(id)) unticked.remove(id)
                render()
            }
            "back" -> { e.preventDefault(); drilled = false; render() }
            "start" -> start()
        }
    }

    private fun start() {
        if (starting) return
        starting = true
        render()
        val visible = m.features.flatMap { it.tickables() }.toSet()
        val skip = visible.filter { it in unticked }
        scope.launch {
            when (val r = ProcessingApi.startScan(ScanRunRequest(m.scopeLibraryId, skip, recheck))) {
                is ScanStart.Started -> {
                    // What the dialog could not offer (a part of a feature not in this library, say) keeps what it was remembered as; an id that is
                    // no part any more (the earlier dialog's `sync_jellyfin`, `wait`) is dropped here.
                    val known = m.features.flatMap { f -> listOfNotNull(f.tickId) + f.parts.map { it.id } }.toSet()
                    val kept = remembered().filter { it !in visible && it in known }
                    localStorage.setItem(SKIP_KEY, (kept + skip).distinct().joinToString(","))
                    close()
                    ScanToasts.started(r.run)
                    onStarted(r.run)
                }
                ScanStart.Busy -> { close(); ScanToasts.problem("A scan is already running.") }
                ScanStart.Failed -> { starting = false; render(); ScanToasts.problem("The scan didn't start — check the server connection.") }
            }
        }
    }

    // ── the picture ─────────────────────────────────────────────────────────────────────────────────────────────────

    private fun render() {
        // A redraw replaces every element, so the one that has focus is found again by what it does and what it is for.
        val focused = document.activeElement as? HTMLElement
        val focusAct = focused?.getAttribute("data-act")
        val focusId = focused?.getAttribute("data-id")
        val left = scrim.querySelector(".left") as? HTMLElement
        val right = scrim.querySelector(".right, .pf-dlg-body") as? HTMLElement
        val leftTop = left?.scrollTop ?: 0.0
        val rightTop = right?.scrollTop ?: 0.0
        val f = feature(selected)
        val parts = phone && drilled && f != null
        scrim.innerHTML = if (parts) partsScreen(f!!) else if (phone) listScreen() else twoPanes()
        (scrim.querySelector(".left") as? HTMLElement)?.scrollTop = leftTop
        (scrim.querySelector(".right, .pf-dlg-body") as? HTMLElement)?.scrollTop = rightTop
        if (focusAct != null) {
            val sel = if (focusId != null) "[data-act=\"$focusAct\"][data-id=\"$focusId\"]" else "[data-act=\"$focusAct\"]"
            (scrim.querySelector(sel) as? HTMLElement)?.focus()
        }
    }

    private fun head(title: String, lede: String, back: Boolean = false, chip: Boolean = false) = """
      <div class="pf-dlg-head">
        ${if (back) """<div class="sd-back"><a href="#" data-act="back" style="color:inherit">‹ All features</a></div>""" else ""}
        <h2>${title.esc()}</h2>
        <p>${lede.esc()}</p>
        ${if (chip && m.scopeName != null) """<div class="pill-row" style="margin-top:10px"><span class="chip act">${m.scopeName.esc()}</span></div>""" else ""}
      </div>"""

    private fun recheckBlock() = """
      <div class="sd-top">
        <div class="pf-all${if (recheck) " on" else ""}">
          <span class="toggle${if (recheck) " on" else ""}" role="switch" aria-checked="$recheck" tabindex="0" data-act="recheck"></span>
          <div class="fill"><div class="t">Re-check everything, not only what is due</div>
            <div class="s">${(if (recheck) m.recheckOn else m.recheckOff).esc()}</div></div>
        </div>
        ${if (recheck) """<div class="pf-note warn" style="margin:0 0 10px">${m.recheckNote.esc()}</div>""" else ""}
      </div>"""

    private fun legend() = """
      <div class="sd-legend"><div class="pf-note q"><b>${m.legendHead.esc()}</b> ${m.legend.esc()} <a href="#/activity" data-act="link">Activity</a>.</div></div>"""

    private fun foot() = """
      <div class="pf-dlg-foot">
        <span class="spacer" style="flex:1"></span>
        <button type="button" class="btn ghost" data-act="cancel">Cancel</button>
        <button type="button" class="btn primary" data-act="start"${if (starting) " disabled" else ""}>Start scan</button>
      </div>"""

    private fun twoPanes() = """
      <div class="pf-dlg wide">
        ${head(m.title, m.lede, chip = true)}
        ${recheckBlock()}
        <div class="sd-two">
          <div class="left">${m.features.joinToString("") { pick(it) }}</div>
          <div class="right">${pane(feature(selected))}</div>
        </div>
        ${legend()}
        ${foot()}
      </div>"""

    private fun listScreen() = """
      <div class="pf-dlg">
        ${head(m.title, m.ledePhone, chip = true)}
        ${recheckBlock()}
        <div class="sd-two one"><div class="left">${m.features.joinToString("") { pick(it) }}</div></div>
        ${legend()}
        ${foot()}
      </div>"""

    private fun partsScreen(f: ScanFeatureView) = """
      <div class="pf-dlg">
        ${head(f.name, "Tick the parts to run this time. Back keeps whatever you tick here.", back = true)}
        <div class="pf-dlg-body"><div class="sd-one-pane">${pane(f)}</div></div>
        ${foot()}
      </div>"""

    /** The tick's own state: `on`, `part` (some parts unticked), blank (none), `dis` (cannot be ticked) or `lock` (always). */
    private fun tickState(f: ScanFeatureView): String {
        if (f.locked) return "lock"
        val ids = f.tickables()
        if (ids.isEmpty()) return "dis"
        val n = ids.count { it !in unticked }
        return when (n) { ids.size -> "on"; 0 -> ""; else -> "part" }
    }

    private fun tickHtml(state: String, act: String?, id: String, label: String): String {
        val glyph = if (state == "part") "–" else "✓"
        val checked = when (state) { "on", "lock" -> "true"; "part" -> "mixed"; else -> "false" }
        val live = act != null && state != "dis" && state != "lock"
        return """<span class="tick${if (state.isEmpty()) "" else " $state"}" role="checkbox" aria-checked="$checked" aria-label="${label.esc()}"${if (live) """ tabindex="0" data-act="$act" data-id="${id.esc()}"""" else """ aria-disabled="true""""}>$glyph</span>"""
    }

    /** *n of m*: the parts ticked of the parts there are. The one figure the page composes. */
    private fun countOf(f: ScanFeatureView): Int = f.tickables().count { it !in unticked }

    private fun pick(f: ScanFeatureView): String {
        val state = tickState(f)
        val line = when {
            f.locked -> "always"
            !f.canRun -> if (f.short == "Off in Settings") """<a href="#/settings?tab=processing" data-act="link">Off in Settings →</a>""" else (f.short ?: "").esc()
            f.tickId != null || f.parts.isEmpty() -> "one tick"
            else -> "${countOf(f)} of ${f.parts.size}"
        } + if (f.queuesWork && f.canRun) """ · <span class="q">queues work</span>""" else ""
        val on = !phone && f.id == selected
        return """
        <div class="pick${if (on) " on" else ""}${if (!f.canRun && !f.locked) " off" else ""}" data-act="pick" data-id="${f.id.esc()}" tabindex="0">
          ${tickHtml(state, "tick", f.id, f.name)}
          <div><div class="n">${f.name.esc()}</div><div class="c">$line</div></div>
          ${if (f.parts.isNotEmpty()) """<span class="go">›</span>""" else "<span></span>"}
        </div>"""
    }

    private fun pane(f: ScanFeatureView?): String {
        if (f == null) return ""
        val count = when {
            f.locked -> "always runs"
            !f.applies -> "not here"
            !f.canRun -> "off"
            f.tickId != null || f.parts.isEmpty() -> "one tick"
            else -> "${countOf(f)} of ${f.parts.size} ticked"
        }
        val note = if (!f.canRun && !f.locked && f.reason != null) {
            val link = if (f.settingsLink) """ <a href="#/settings?tab=processing" data-act="link">Settings ▸ Processing →</a>""" else ""
            """<div class="pf-note flat" style="margin-bottom:12px">${f.reasonHead?.let { "<b>${it.esc()}</b> " } ?: ""}${f.reason.esc()}$link</div>"""
        } else ""
        val rows = if (f.parts.isEmpty()) """<div class="sd-none">No parts to tick — the whole feature is the one tick on the left.</div>""" else f.parts.joinToString("") { p ->
            val state = when { !p.canRun -> "dis"; p.id in unticked -> ""; else -> "on" }
            val why = p.reason?.let { r ->
                val link = if (p.settingsLink) """ <a href="#/settings?tab=processing" data-act="link">Settings ▸ Processing →</a>""" else ""
                """<div class="tiny muted" style="margin-top:4px">${r.esc()}$link</div>"""
            } ?: ""
            """
            <div class="sd-one"${if (p.canRun) """ data-act="part" data-id="${p.id.esc()}" tabindex="0" style="cursor:pointer"""" else """ aria-disabled="true""""}>
              ${tickHtml(state, null, p.id, p.name)}
              <div class="n">${p.name.esc()}${if (p.queuesWork) """ <span class="badge info">queues work</span>""" else ""}$why</div>
            </div>"""
        }
        return """
          <div class="sd-pane-head"><h3>${f.name.esc()}</h3><span class="c">${count.esc()}</span></div>
          <div class="sd-lede">${f.lede.esc()}</div>
          $note
          <div class="sd-parts${if (!f.canRun && !f.locked) " off" else ""}">$rows</div>"""
    }
}

/**
 * DEB-1664 (FR-5.10) — what the run says afterwards: *started* (naming what was skipped), *finished* (what it queued, and a link to
 * Activity) and *cancelled* (how far it got, and *Continue scan*). The second and third are for a run **this browser started**: an
 * hourly run in an open tab must not toast. `.pfx` is on the stack, which is where `.pf-toast` is scoped.
 */
internal object ScanToasts {
    private var ownJobId: String? = null

    private fun stack(): HTMLElement {
        (document.getElementById("pf-toasts") as? HTMLElement)?.let { return it }
        val el = document.createElement("div") as HTMLElement
        el.id = "pf-toasts"
        el.className = "pfx"
        el.setAttribute("style", "position:fixed;bottom:20px;left:50%;transform:translateX(-50%);z-index:9999;display:flex;flex-direction:column;gap:10px;align-items:center;max-width:calc(100vw - 24px)")
        document.body?.appendChild(el)
        return el
    }

    private fun show(kind: String, glyph: String, html: String, ms: Int): HTMLElement {
        val t = document.createElement("div") as HTMLElement
        t.className = "pf-toast${if (kind.isEmpty()) "" else " $kind"}"
        t.setAttribute("role", "status")
        t.innerHTML = """<span class="ic">$glyph</span><div>$html</div>"""
        stack().appendChild(t)
        window.setTimeout({ t.remove(); null }, ms)
        return t
    }

    fun started(run: ScanRunStarted) {
        ownJobId = run.jobId.ifBlank { null }
        val skipped = if (run.skippedText.isEmpty()) "" else " · skipped <b>${run.skippedText.esc()}</b>"
        val note = if (run.skippedNote.isEmpty()) "" else """<div class="s">${run.skippedNote.esc()}</div>"""
        show("", "✓", "<div>Scan started$skipped</div>$note", 6_000)
    }

    fun problem(message: String) { show("warn", "▮", "<div>${message.esc()}</div>", 6_000) }

    /** A `finished` push: toasts when the run is the one this browser started. A cancelled run also ends with one; the server says which. */
    fun finished(event: JobEvent.Finished, scope: CoroutineScope) {
        if (ownJobId == null || event.jobId != ownJobId) return
        ownJobId = null
        scope.launch {
            val status = MediaApi.scanStatus()
            if (status?.status == "CANCELLED") {
                val n = status.processedCount
                val t = show("warn", "▮", """<div>Scan cancelled</div><div class="s">${if (n > 0) "$n ${if (n == 1) "item was" else "items were"} looked at. " else ""}<a href="#" data-pf-continue>Continue scan</a></div>""", 14_000)
                t.querySelector("[data-pf-continue]")?.addEventListener("click") { e ->
                    e.preventDefault()
                    t.remove()
                    scope.launch { if (!MediaApi.resumeScan()) problem("The scan didn't continue.") }
                }
            } else if (event.queued.isEmpty()) {
                show("info", "◴", """<div>Scan finished</div><div class="s">Nothing was queued.</div>""", 8_000)
            } else {
                show("info", "◴", """<div>Scan finished · <b>${event.queued.joinToString(", ").esc()}</b></div><div class="s">Queued work is not done yet — the reading is happening now. <a href="#/activity">Activity →</a></div>""", 14_000)
            }
        }
    }
}
