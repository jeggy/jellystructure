package dev.jellystructure.ui

import dev.jellystructure.Router
import dev.jellystructure.api.SupportApi
import dev.jellystructure.formatClock
import dev.jellystructure.formatRelativeAgo
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.model.SupportAdminMessage
import dev.jellystructure.model.SupportAdminThread
import dev.jellystructure.model.SupportAdminTicket
import dev.jellystructure.model.SupportAiTranscript
import dev.jellystructure.model.SupportSummary
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

/**
 * DEB-1500 — the Support tab on the AI page (`support-is-a-tab-on-the-ai-page.md`, FR-2…FR-11, FR-16). It renders what the
 * server pushed and asks for the rest: the lane strip is the last summary the server sent (FR-3, FR-4), the list is
 * `GET /api/support/tickets` kept current by `support_ticket` events (FR-6), and a ticket is the last thread the server
 * returned, never what was sent (FR-7…FR-11). **Nothing is derived or optimistic**: a write disables its control until the
 * answer comes back and the page draws that answer.
 *
 * Words and rules that choose them are in [SupportText] (commonMain, tested); the stylesheet is
 * `design/app/support.css` (`.tksup`, FR-17) and this file adds none. A body is set with `textContent`, never as HTML
 * (FR-320-16): the markup carries an empty `data-b` element and the text is put in after.
 */

/**
 * What the shell's socket hears (FR-2): the last summary, and who is listening. The sidebar's count and the tab's are
 * painted from it, and from nothing else.
 */
object SupportLive {
    var summary: SupportSummary? = null
        private set
    /** The signed-in admin's Jellyfin name, for the composer's placeholder (FR-9). Set by the shell. */
    var adminName: String = "Admin"
    private val listeners = LinkedHashMap<String, (JobEvent.SupportTicketChanged) -> Unit>()

    fun listen(key: String, f: (JobEvent.SupportTicketChanged) -> Unit) { listeners[key] = f }
    fun drop(key: String) { listeners.remove(key) }

    /** A summary the server answered with (the shell's mount read, the lane switch's answer). */
    fun seed(s: SupportSummary) { summary = s; paintCount() }

    fun event(e: JobEvent.SupportTicketChanged) {
        summary = e.summary
        paintCount()
        listeners.values.toList().forEach { it(e) }
    }

    /** FR-2 — the count is `open`, hidden at 0 and while the lane is closed. The admin never adds or subtracts on its own. */
    fun paintCount() {
        val s = summary
        val n = if (s != null && s.laneOpen) s.open else 0
        for (id in listOf("ai-count-badge", "ai-tab-count")) {
            val el = document.getElementById(id) as? HTMLElement ?: continue
            el.textContent = if (n > 0) n.toString() else ""
            el.style.display = if (n > 0) "" else "none"
        }
    }
}

private fun nowMsSupport(): Double = js("Date.now()")
private fun nowSecSupport(): Long = (nowMsSupport() / 1000.0).toLong()

private var tkScope: CoroutineScope? = null
private var tkPanel: Element? = null
private var tkGen = 0
private var tkFilter = "all"
private var tkTickets: List<SupportAdminTicket>? = null
private var tkListFailed = false
private var tkOpen: Long? = null
private var tkThread: SupportAdminThread? = null
private var tkGone = false
private var tkDraft = ""
private var tkNote = ""
private var tkDest: String? = null
private var tkSending = false
private var tkWriting = false
private var tkComposerNote: String? = null
private var tkRailNote: String? = null
private var tkClosedByServer = false
private var tkLaneBusy = false
private var tkLaneError = false
private var tkReadUpTo = 0L
private var tkTurns = 0
private var tkComposerKey = ""
private val tkExpanded = mutableSetOf<Long>()
private var tkEsc: ((Event) -> Unit)? = null
/** A list read is out; an event that lands meanwhile makes its answer stale, so it is asked again when it comes back. */
private var tkListInFlight = false
private var tkListDirty = false
/** Each thread read takes a number, and only the newest one's answer is drawn: two reads can cross on the wire. */
private var tkThreadSeq = 0

private fun tkEl(id: String): HTMLElement? = document.getElementById(id) as? HTMLElement

// ── mount ────────────────────────────────────────────────────────────────────────────────────────────────────────────

fun renderSupportTab(panel: Element, scope: CoroutineScope, query: Map<String, String>) {
    tkScope = scope; tkPanel = panel; tkGen++
    tkTickets = null; tkListFailed = false; tkThread = null; tkGone = false; tkOpen = null
    tkClosedByServer = false; tkLaneBusy = false; tkLaneError = false; tkExpanded.clear(); closeSupportDialog()
    tkListInFlight = false; tkListDirty = false
    panel.innerHTML = """<div id="tk-lane"></div><div id="tk-body"></div>"""
    bindSupportPanel(panel)
    SupportLive.listen("tab") { onSupportEvent(it) }
    paintLane()
    val gen = tkGen
    scope.launch {
        SupportApi.summary()?.let { SupportLive.seed(it) }
        if (gen != tkGen) return@launch
        paintLane(); paintSeg()
        if (tkOpen != null) paintComposer(force = true)
    }
    val n = query["ticket"]?.toLongOrNull()?.takeIf { it > 0 }
    if (n != null) openTicket(n, writeUrl = false) else showList()
}

/** Called when the AI page is left or drawn again: the listener goes, and an answer still in flight finds the page changed. */
fun leaveSupportTab() {
    tkGen++
    tkPanel = null
    closeSupportDialog()
    SupportLive.drop("tab")
}

private fun bindSupportPanel(panel: Element) {
    if (panel.getAttribute("data-tk-bound") == "1") return
    panel.setAttribute("data-tk-bound", "1")
    panel.addEventListener("click") { ev ->
        val target = ev.target as? Element ?: return@addEventListener
        target.closest("[data-tk-more]")?.getAttribute("data-tk-more")?.toLongOrNull()?.let { id ->
            tkExpanded += id; paintMain(); return@addEventListener
        }
        target.closest("[data-tk-filter]")?.getAttribute("data-tk-filter")?.let { key ->
            if (key != tkFilter) { tkFilter = key; tkTickets = null; tkListFailed = false; paintSeg(); paintRows(); loadList() }
            return@addEventListener
        }
        target.closest("[data-tk-open]")?.getAttribute("data-tk-open")?.toLongOrNull()?.let { openTicket(it, writeUrl = true); return@addEventListener }
        target.closest("[data-tk-fwd]")?.getAttribute("data-tk-fwd")?.let { tkDest = it; paintRail(); return@addEventListener }
        val action = target.closest("[data-tk]")?.getAttribute("data-tk")
        // The dialog's backdrop closes it; a press inside the dialog does not.
        if (action == null && target.getAttribute("data-tk-dlg") != null) { closeSupportDialog(); return@addEventListener }
        when (action) {
            "lane-open" -> setLane(true)
            "lane-close" -> showCloseDialog()
            "lane-close-yes" -> setLane(false)
            "dlg-cancel" -> closeSupportDialog()
            "back" -> backToList()
            "send" -> sendReply()
            "forward" -> forwardTicket()
            "resolve" -> railWrite { n -> SupportApi.state(n, "RESOLVED") }
            "reopen" -> railWrite { n -> SupportApi.state(n, "OPEN") }
            "flag" -> railWrite { n -> SupportApi.flag(n, true) }
            "unflag" -> railWrite { n -> SupportApi.flag(n, false) }
            "transcript" -> openTranscript()
        }
    }
    panel.addEventListener("input") { ev ->
        when ((ev.target as? Element)?.id) {
            "tk-reply" -> tkDraft = (ev.target as? HTMLTextAreaElement)?.value.orEmpty()
            "tk-note" -> tkNote = (ev.target as? HTMLInputElement)?.value.orEmpty()
        }
    }
    panel.addEventListener("change") { ev ->
        val sel = ev.target as? HTMLSelectElement ?: return@addEventListener
        if (sel.getAttribute("data-tk-class") == null || sel.value.isBlank()) return@addEventListener
        val cls = sel.value
        railWrite { n -> SupportApi.ticketClass(n, cls) }
    }
}

// ── the lane (FR-3) ──────────────────────────────────────────────────────────────────────────────────────────────────

private fun paintLane() {
    val host = tkEl("tk-lane") ?: return
    val s = SupportLive.summary
    val busy = if (tkLaneBusy) " disabled" else ""
    val err = if (tkLaneError) """ <span class="muted tiny">Not saved — try again.</span>""" else ""
    host.innerHTML = when {
        // Nothing is assumed before the server has said: no strip until it has.
        s == null -> ""
        !s.laneOpen -> """<div class="tk-lane closed"><i></i><div><b>Support is closed.</b> Ravilo shows viewers no Support entry, and nothing can be filed. Tickets already filed are kept.$err</div><button type="button" class="btn primary sm" data-tk="lane-open" data-tk-w$busy>Open support to viewers</button></div>"""
        else -> """<div class="tk-lane"><div><b>Open to viewers</b>$err</div><button type="button" class="btn sm" data-tk="lane-close" data-tk-w$busy>Close support</button></div>"""
    }
}

private fun setLane(open: Boolean) {
    if (aiPageLost() || tkLaneBusy) return
    val scope = tkScope ?: return
    val gen = tkGen
    tkLaneBusy = true; tkLaneError = false; paintLane()
    scope.launch {
        val r = SupportApi.setEnabled(open)
        if (gen != tkGen) return@launch
        tkLaneBusy = false
        // The strip renders the server's answer and nothing it assumed.
        if (r is SupportApi.Switched.Ok) { tkClosedByServer = false; SupportLive.seed(r.summary) } else tkLaneError = true
        closeSupportDialog()
        paintLane()
        if (tkOpen == null) { paintSeg(); loadList() } else paintComposer(force = true)
    }
}

private fun showCloseDialog() {
    if (aiPageLost()) return
    val panel = tkPanel ?: return
    closeSupportDialog()
    val back = document.createElement("div") as HTMLElement
    back.className = "modal-back open"
    back.setAttribute("data-tk-dlg", "close")
    back.innerHTML = """<div class="modal" role="dialog" aria-modal="true" aria-label="Close support"><h3>Close support?</h3><p>Viewers lose the Support entry in Ravilo. Their tickets are kept, and come back when you open it again.</p><div class="tk-acts"><button type="button" class="btn primary" data-tk="lane-close-yes" data-tk-w>Close support</button><button type="button" class="btn ghost" data-tk="dlg-cancel">Cancel</button></div></div>"""
    panel.appendChild(back)
    val onKey: (Event) -> Unit = { e -> if (keyOf(e) == "Escape") closeSupportDialog() }
    tkEsc = onKey
    document.addEventListener("keydown", onKey)
}

private fun keyOf(e: Event): String = js("e.key || ''")

private fun closeSupportDialog() {
    tkEsc?.let { document.removeEventListener("keydown", it) }
    tkEsc = null
    tkPanel?.querySelectorAll("[data-tk-dlg]")?.let { all -> for (i in 0 until all.length) all.item(i)?.let { (it as? HTMLElement)?.remove() } }
}

// ── the list (FR-6) ──────────────────────────────────────────────────────────────────────────────────────────────────

private fun showList() {
    tkOpen = null; tkThread = null; tkGone = false
    val body = tkEl("tk-body") ?: return
    body.innerHTML = """
        <div class="tk-bar"><span class="seg" id="tk-seg"></span></div>
        <div class="card flat scrollbox tk-box"><div class="tk-tbl">
          <div class="tk-row hd"><span>#</span><span>Viewer</span><span>Ticket</span><span>Class</span><span>State</span><span>Holder</span><span>Last</span></div>
          <div id="tk-rows"></div>
        </div></div>
    """.trimIndent()
    paintSeg(); paintRows(); loadList()
}

private fun loadList() {
    val scope = tkScope ?: return
    if (tkListInFlight) { tkListDirty = true; return }
    val gen = tkGen
    val path = SupportText.ticketsPath(SupportText.filterOf(tkFilter))
    tkListInFlight = true; tkListDirty = false
    scope.launch {
        val rows = SupportApi.tickets(path)
        if (gen != tkGen) return@launch   // the page was left or drawn again: renderSupportTab reset the flags
        tkListInFlight = false
        if (tkOpen != null) return@launch
        // An event that landed while this read was out may be newer than what it came back with: read again.
        if (tkListDirty) { tkListDirty = false; loadList(); return@launch }
        if (rows != null) { tkTickets = rows; tkListFailed = false } else if (tkTickets == null) tkListFailed = true
        paintRows()
    }
}

private fun paintSeg() {
    val host = tkEl("tk-seg") ?: return
    val s = SupportLive.summary
    host.innerHTML = SupportText.FILTERS.joinToString("") { f ->
        val n = if (s != null) f.count(s) else null
        """<span${if (f.key == tkFilter) """ class="on"""" else ""} data-tk-filter="${f.key}">${f.label}${if (n != null) " · $n" else ""}</span>"""
    }
}

private fun paintRows() {
    val host = tkEl("tk-rows") ?: return
    val rows = tkTickets
    host.innerHTML = when {
        rows == null -> """<div class="tk-empty">${if (tkListFailed) "Couldn’t read the tickets." else "Loading…"}</div>"""
        rows.isEmpty() -> """<div class="tk-empty">${if (tkFilter == "all") "No tickets yet." else "No tickets match this filter."}</div>"""
        else -> rows.joinToString("") { rowHtml(it) }
    }
}

private fun holderHtml(holder: String, seat: String?): String {
    val mark = when (holder) {
        "AI_AGENT" -> """<span class="tk-av sm ai"><i></i></span>"""
        "HERVOR" -> """<span class="tk-av sm hv">H</span>"""
        "ADMIN" -> """<span class="tk-av sm">A</span>"""
        else -> """<span class="tk-av sm none"></span>"""
    }
    return mark + SupportText.holderLabel(holder, seat).esc()
}

private fun rowHtml(t: SupportAdminTicket): String {
    val title = t.englishTitle?.takeIf { it.isNotBlank() }
    val ticketCell = if (title != null) """<span class="tt" title="${title.esc()}">${title.esc()}</span>"""
        else """<span class="tt pv" title="${t.preview.esc()}">${t.preview.esc()}</span>"""
    val unread = t.unread > 0
    val cls = if (t.ticketClass == null) "—" else """<span class="tk-cl">${SupportText.className(t.ticketClass).esc()}</span>"""
    return """<div class="tk-row${if (unread) " un" else ""}" data-tk-open="${t.number}">""" +
        """<span class="mono">${if (unread) "<u></u>" else ""}#${t.number}</span>""" +
        """<span class="vw"><b>${t.ownerName.esc()}</b></span>""" +
        ticketCell +
        """<span class="cls">$cls</span>""" +
        """<span class="st"><span class="badge ${SupportText.stateBadge(t)}">${SupportText.stateLabel(t)}</span></span>""" +
        """<span class="tk-hold">${holderHtml(t.holder, t.holderSeat)}</span>""" +
        """<span class="lt"><span class="mono muted">${formatRelativeAgo(t.lastActivity.toString())}</span>${if (t.overdue) """<span class="tk-od">overdue</span>""" else ""}</span>""" +
        "</div>"
}

private fun onSupportEvent(e: JobEvent.SupportTicketChanged) {
    paintLane()
    val open = tkOpen
    if (open == null) {
        paintSeg()
        if (tkListInFlight) tkListDirty = true
        val rows = tkTickets ?: return
        val t = e.ticket
        tkTickets = when {
            e.deleted || t == null -> rows.filter { it.number != e.number }
            !SupportText.matches(tkFilter, t) -> rows.filter { it.number != e.number }
            rows.any { it.number == t.number } -> rows.map { if (it.number == t.number) t else it }
            else -> listOf(t) + rows
        }
        paintRows()
    } else if (e.number == open) {
        // FR-7 — the ticket is read again on every event for its number; a deleted one is gone.
        if (e.deleted) { tkGone = true; tkThread = null; drawDetail() } else loadThread()
    }
}

// ── one ticket (FR-7, FR-8) ──────────────────────────────────────────────────────────────────────────────────────────

private fun openTicket(n: Long, writeUrl: Boolean) {
    tkOpen = n; tkThread = null; tkGone = false; tkDraft = ""; tkNote = ""; tkDest = null; tkSending = false; tkWriting = false
    tkComposerNote = null; tkRailNote = null; tkClosedByServer = false; tkReadUpTo = 0; tkTurns = 0; tkComposerKey = ""; tkExpanded.clear()
    if (writeUrl) Router.updateQuery(mapOf("tab" to "support", "ticket" to n.toString()), replace = true)
    tkEl("tk-body")?.innerHTML = """<span class="tk-back" data-tk="back">${BACK_SVG}AI · Support</span><p class="muted tiny">Loading…</p>"""
    loadThread()
}

private const val BACK_SVG = """<svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>"""

private fun backToList() {
    Router.updateQuery(mapOf("ticket" to null), replace = true)
    showList()
}

private fun loadThread() {
    val scope = tkScope ?: return
    val n = tkOpen ?: return
    val gen = tkGen
    val seq = ++tkThreadSeq
    scope.launch {
        val r = SupportApi.thread(n)
        if (gen != tkGen || tkOpen != n || seq != tkThreadSeq) return@launch
        when (r) {
            is SupportApi.Read.Found -> { tkThread = r.thread; tkGone = false; drawDetail(); markRead() }
            SupportApi.Read.Gone -> { tkThread = null; tkGone = true; drawDetail() }
            SupportApi.Read.NoResponse -> if (tkThread == null) {
                tkEl("tk-body")?.innerHTML = """<span class="tk-back" data-tk="back">$BACK_SVG AI · Support</span><p class="muted tiny">Couldn’t read this ticket.</p>"""
            }
        }
        if (r is SupportApi.Read.Found) {
            val t = SupportApi.transcript(n)
            if (gen == tkGen && tkOpen == n && t != null && t.turns.size != tkTurns) { tkTurns = t.turns.size; paintRail() }
        }
    }
}

/** FR-7 — on open, and after each read, `read {upTo: <last id>}`; it only ever moves forward, so it cannot loop on its own event. */
private fun markRead() {
    val scope = tkScope ?: return
    val n = tkOpen ?: return
    val last = tkThread?.messages?.maxOfOrNull { it.id } ?: return
    if (last <= tkReadUpTo) return
    tkReadUpTo = last
    scope.launch { SupportApi.read(n, last) }
}

private fun drawDetail() {
    val body = tkEl("tk-body") ?: return
    val thread = tkThread
    if (tkGone || thread == null) {
        body.innerHTML = """<span class="tk-back" data-tk="back">$BACK_SVG AI · Support</span><p class="muted">This ticket no longer exists.</p>"""
        return
    }
    if (tkEl("tk-main") == null) {
        body.innerHTML = """
            <span class="tk-back" data-tk="back">$BACK_SVG AI · Support</span>
            <div class="tk-det"><section><div id="tk-main"></div><div id="tk-composer"></div></section><aside class="tk-rail" id="tk-rail"></aside></div>
        """.trimIndent()
        tkComposerKey = ""
    }
    paintMain(); paintRail(); paintComposer(force = false)
}

private fun timeOf(sec: Long): String = formatClock(sec.toString())

private fun avatarHtml(m: SupportAdminMessage, ownerName: String): String = when (m.author) {
    "AI_AGENT" -> """<span class="tk-av ai"><i></i></span>"""
    "HERVOR" -> """<span class="tk-av hv">H</span>"""
    "ADMIN" -> """<span class="tk-av ad">${(m.authorLabel ?: "A").take(1).uppercase().esc()}</span>"""
    else -> """<span class="tk-av me">${ownerName.take(1).uppercase().esc()}</span>"""
}

private fun whoHtml(m: SupportAdminMessage, ownerName: String): String = when (m.author) {
    "AI_AGENT" -> """<b>AI Agent</b><span class="tk-bdg ai">AI</span>"""
    "HERVOR" -> """<b>Hervør</b><span class="tk-bdg ai">AI</span><span class="tk-bdg">support</span>"""
    "ADMIN" -> """<b>${(m.authorLabel ?: "Admin").esc()}</b><span class="tk-bdg">admin</span>"""
    else -> """<b>${ownerName.esc()}</b>"""
}

private fun paintMain() {
    val host = tkEl("tk-main") ?: return
    val thread = tkThread ?: return
    val t = thread.ticket
    val bodies = HashMap<Long, String>()
    val opened = thread.messages.firstOrNull()?.createdAt ?: t.lastActivity
    val parts = StringBuilder()
    parts.append("""<div class="tk-dh"><div class="kk">${SupportText.threadMeta(t, thread.messages, timeOf(opened)).esc()}</div><h2>${SupportText.threadTitle(t).esc()}</h2><div class="tk-hold">${holderHtml(t.holder, t.holderSeat)}</div></div>""")
    if (t.flagged) parts.append("""<div class="tk-flag"><i></i><div>${SupportText.FLAGGED_BANNER.esc()}</div></div>""")
    parts.append("""<div class="tk-th">""")
    for (m in thread.messages) {
        val adminOnly = m.visibility == "ADMIN_ONLY"
        val tag = if (adminOnly) """<span class="tk-bdg adm">admin only</span>""" else ""
        when (m.kind) {
            "SYSTEM" -> {
                val l = SupportText.systemLine(m.event, m.eventArgs, t.number)
                parts.append("""<div class="tk-sys ${l.tone}"><span><i></i>${l.text.esc()}$tag<time>${timeOf(m.createdAt)}</time></span></div>""")
            }
            "SAME_HERE" -> parts.append("""<div class="tk-sys"><span><i></i>${SupportText.sameHereLine(m.devicePlatform).esc()}<time>${timeOf(m.createdAt)}</time></span></div>""")
            else -> {
                val text = m.body.orEmpty()
                bodies[m.id] = text
                val clamped = SupportText.isClamped(text)
                val open = m.id in tkExpanded
                val mine = m.author == "ADMIN"
                val cls = buildString {
                    append("tk-m ")
                    append(when (m.author) { "AI_AGENT" -> "ai"; "HERVOR" -> "hv"; "ADMIN" -> "ad"; else -> "vw" })
                    if (mine) append(" me")
                    if (adminOnly) append(" adm")
                    if (clamped) append(if (open) " cl op" else " cl")
                }
                val more = if (clamped && !open) """<button type="button" class="more" data-tk-more="${m.id}">Show the rest</button>""" else ""
                parts.append("""<div class="$cls">${avatarHtml(m, t.ownerName)}<div class="b"><div class="w">${whoHtml(m, t.ownerName)}$tag<time>${timeOf(m.createdAt)}</time></div><div class="t"><div class="bd" data-b="${m.id}"></div>$more</div></div></div>""")
            }
        }
    }
    if (thread.aiComposing) parts.append("""<div class="tk-comp">AI Agent is writing…</div>""")
    parts.append("</div>")
    host.innerHTML = parts.toString()
    // FR-8 / FR-320-16 — a body is text. It is put in after the markup, never inside it.
    val slots = host.querySelectorAll("[data-b]")
    for (i in 0 until slots.length) {
        val el = slots.item(i) as? HTMLElement ?: continue
        el.textContent = bodies[el.getAttribute("data-b")?.toLongOrNull()].orEmpty()
    }
}

// ── the composer (FR-9) ──────────────────────────────────────────────────────────────────────────────────────────────

private fun paintComposer(force: Boolean) {
    val host = tkEl("tk-composer") ?: return
    val thread = tkThread ?: return
    val closed = tkClosedByServer || SupportLive.summary?.laneOpen == false
    val mode = when {
        thread.ticket.flagged -> "flagged"
        closed -> "closed"
        tkSending -> "sending"
        else -> "normal"
    }
    val key = mode + "|" + (tkComposerNote ?: "")
    if (!force && key == tkComposerKey) return
    tkComposerKey = key
    host.innerHTML = when (mode) {
        "flagged" -> """<div class="tk-cp"><div class="dead">${SupportText.FLAGGED_COMPOSER}</div></div>"""
        "closed" -> """<div class="tk-cp"><div class="dead">${SupportText.CLOSED_COMPOSER}</div></div>"""
        else -> {
            val busy = mode == "sending"
            // The line under the draft ("Sending…", or why it was not sent) is `.snd`, the composer's own: no styling of ours (FR-17).
            val line = if (busy) "Sending…" else tkComposerNote
            """<div class="tk-cp${if (busy) " busy" else ""}"><textarea id="tk-reply" rows="2" data-tk-w placeholder="${SupportText.composerPlaceholder(SupportLive.adminName).esc()}"></textarea><button type="button" class="btn primary" data-tk="send" data-tk-w>Send</button>${if (line != null) """<span class="snd">${line.esc()}</span>""" else ""}</div>"""
        }
    }
    (tkEl("tk-reply") as? HTMLTextAreaElement)?.value = tkDraft
}

private fun sendReply() {
    if (aiPageLost() || tkSending) return
    val scope = tkScope ?: return
    val n = tkOpen ?: return
    val text = ((tkEl("tk-reply") as? HTMLTextAreaElement)?.value ?: tkDraft).trim()
    if (text.isEmpty()) return
    tkDraft = text
    val gen = tkGen
    tkSending = true; tkComposerNote = null; paintComposer(force = true)
    scope.launch {
        val w = SupportApi.reply(n, text)
        if (gen != tkGen || tkOpen != n) return@launch
        tkSending = false
        applyWrite(w, onOk = { tkDraft = "" }, onFail = { tkComposerNote = it })
        paintComposer(force = true)
    }
}

/** Draws a write's answer: the thread it returned (never what was sent), the closed-lane message, or one line saying it failed. */
private fun applyWrite(w: SupportApi.Write, onOk: () -> Unit = {}, onFail: (String) -> Unit) {
    when (w) {
        is SupportApi.Write.Ok -> { tkThread = w.thread; onOk(); drawDetail(); markRead() }
        SupportApi.Write.Closed -> {
            tkClosedByServer = true
            tkScope?.launch { SupportApi.summary()?.let { SupportLive.seed(it) }; paintLane(); paintComposer(force = true) }
        }
        SupportApi.Write.NoResponse -> onFail("Not sent — the server didn’t answer.")
        is SupportApi.Write.Failed -> onFail(when (w.reason) {
            "body_empty" -> "Write something first."
            "body_too_long" -> "That is longer than the limit."
            else -> "Not sent (${w.status})."
        })
    }
}

// ── the rail (FR-10, FR-11) ──────────────────────────────────────────────────────────────────────────────────────────

private fun paintRail() {
    val host = tkEl("tk-rail") ?: return
    val thread = tkThread ?: return
    val t = thread.ticket
    val summary = SupportLive.summary
    val off = if (tkWriting) " disabled" else ""
    val classes = """<option value=""${if (t.ticketClass == null) " selected" else ""} disabled>—</option>""" +
        SupportText.CLASSES.joinToString("") { c -> """<option value="$c"${if (c == t.ticketClass) " selected" else ""}>${SupportText.className(c)}</option>""" }
    val title = t.englishTitle?.takeIf { it.isNotBlank() }
    val owesNow = if (t.owes == "NONE") "Nobody" else SupportText.owesLabel(t.owes) + " · " + SupportText.age(t.owedSince, nowSecSupport())
    val parts = StringBuilder()
    parts.append("""<div class="tk-rc">""")
    parts.append("""<div class="tk-kv"><s>State</s><b><span class="badge ${SupportText.stateBadge(t)}">${SupportText.stateLabel(t)}</span></b></div>""")
    parts.append("""<div class="tk-kv"><s>Class</s><b><select data-tk-class data-tk-w$off>$classes</select></b></div>""")
    parts.append("""<div class="tk-kv"><s>Title</s><b class="mute">${if (title != null) title.esc() + " · by AI Agent, from the first message" else "none yet"}</b></div>""")
    parts.append("""<div class="tk-kv"><s>Holder</s><b>${holderHtml(t.holder, t.holderSeat)}</b></div>""")
    parts.append("""<div class="tk-kv"><s>Owes</s><b${if (t.overdue) """ class="od"""" else ""}>${owesNow.esc()}</b></div>""")
    t.englishSummary?.takeIf { it.isNotBlank() }?.let { parts.append("""<div class="tk-kv"><s>Summary</s><b class="mute">${it.esc()}</b></div>""") }
    parts.append("""<div class="tk-kv"><s>Viewer sees</s><b class="mute">${SupportText.viewerSees(t, thread.messages, thread.aiComposing).esc()}</b></div>""")
    parts.append("</div>")

    val dests = SupportText.destinations(t, summary)
    val chosen = dests.firstOrNull { it.key == tkDest && !it.disabled } ?: dests.firstOrNull { !it.disabled }
    val rows = dests.joinToString("") { d ->
        """<label class="tk-ro${if (d.key == chosen?.key) " on" else ""}${if (d.disabled) " no" else ""}" data-tk-fwd="${d.key}"><i></i><span>${d.label.esc()}</span></label>"""
    }
    val resolveOrReopen = if (t.state == "RESOLVED") """<button type="button" class="btn sm" data-tk="reopen" data-tk-w$off>Reopen</button>"""
        else """<button type="button" class="btn sm okb" data-tk="resolve" data-tk-w$off>Resolve</button>"""
    parts.append("""<div class="tk-rc${if (t.flagged) " off" else ""}"><h4>Forward to</h4>$rows<input id="tk-note" class="tk-note" type="text" maxlength="2000" placeholder="Note for whoever answers (English, admins only)" data-tk-w><div class="tk-acts"><button type="button" class="btn primary sm" data-tk="forward" data-tk-w${if (chosen == null) " disabled" else off}>Forward</button>$resolveOrReopen</div>${tkRailNote?.let { """<small class="muted">${it.esc()}</small>""" }.orEmpty()}</div>""")

    parts.append("""<div class="tk-rc">""")
    parts.append(if (t.flagged) """<button type="button" class="btn sm wide" data-tk="unflag" data-tk-w$off>Remove flag</button>"""
        else """<button type="button" class="btn sm badb wide" data-tk="flag" data-tk-w$off>Flag as abuse</button><small>Stops every reply, including AI Agent’s. The viewer is told nothing.</small>""")
    parts.append("</div>")
    if (tkTurns > 0) parts.append("""<button type="button" class="btn sm ghost" data-tk="transcript">AI transcript for #${t.number} →</button>""")
    host.innerHTML = parts.toString()
    (tkEl("tk-note") as? HTMLInputElement)?.value = tkNote
}

private fun forwardTicket() {
    val thread = tkThread ?: return
    val dests = SupportText.destinations(thread.ticket, SupportLive.summary)
    val dest = (dests.firstOrNull { it.key == tkDest && !it.disabled } ?: dests.firstOrNull { !it.disabled })?.key ?: return
    val note = ((tkEl("tk-note") as? HTMLInputElement)?.value ?: tkNote).trim()
    railWrite(onOk = { tkNote = ""; tkDest = null }) { n -> SupportApi.forward(n, dest, note) }
}

private fun railWrite(onOk: () -> Unit = {}, call: suspend (Long) -> SupportApi.Write) {
    if (aiPageLost() || tkWriting) return
    val scope = tkScope ?: return
    val n = tkOpen ?: return
    val gen = tkGen
    tkWriting = true; tkRailNote = null; paintRail()
    scope.launch {
        val w = call(n)
        if (gen != tkGen || tkOpen != n) return@launch
        tkWriting = false
        tkRailNote = null
        applyWrite(w, onOk = onOk, onFail = { tkRailNote = it })
        paintRail(); paintComposer(force = false)
    }
}

// ── the transcript (FR-10.9) ─────────────────────────────────────────────────────────────────────────────────────────

private fun openTranscript() {
    val scope = tkScope ?: return
    val n = tkOpen ?: return
    val gen = tkGen
    scope.launch {
        val t = SupportApi.transcript(n) ?: return@launch
        if (gen != tkGen || tkOpen != n || t.turns.isEmpty()) return@launch
        showTranscript(t)
    }
}

private fun showTranscript(t: SupportAiTranscript) {
    val panel = tkPanel ?: return
    closeSupportDialog()
    val back = document.createElement("div") as HTMLElement
    back.className = "modal-back open"
    back.setAttribute("data-tk-dlg", "transcript")
    val turns = t.turns.mapIndexed { i, turn ->
        val verdict = turn.verdict
        val badge = when {
            verdict == null -> """<span class="badge">waiting</span>"""
            verdict.startsWith("SENT") -> """<span class="badge ok">${verdict.esc()}</span>"""
            verdict.startsWith("FLAGGED") -> """<span class="badge bad">${verdict.esc()}</span>"""
            else -> """<span class="badge info">${verdict.esc()}</span>"""
        }
        """<div class="tk-turn"><div class="hd"><time>${timeOf(turn.at)}</time><span class="mdl">${turn.model.esc()}</span>$badge<span class="mono muted">${turn.inputTokens} in · ${turn.outputTokens} out</span><span class="cost">${SupportText.usd(turn.costMicroUsd)}</span></div>""" +
            """<details><summary>Sent to the model</summary><pre data-t="s$i"></pre></details>""" +
            (if (turn.answer != null) """<details><summary>Answer</summary><pre data-t="a$i"></pre></details>""" else "") + "</div>"
    }.joinToString("")
    back.innerHTML = """<div class="modal tk-tr" role="dialog" aria-modal="true" aria-label="AI transcript for #${t.ticket}"><span class="x" data-tk="dlg-cancel">✕</span><h3>AI transcript for #${t.ticket}</h3>$turns</div>"""
    panel.appendChild(back)
    // Set with textContent: a prompt that contains markup is still only text.
    t.turns.forEachIndexed { i, turn ->
        (back.querySelector("[data-t=\"s$i\"]") as? HTMLElement)?.textContent = turn.sent
        (back.querySelector("[data-t=\"a$i\"]") as? HTMLElement)?.textContent = turn.answer.orEmpty()
    }
    val onKey: (Event) -> Unit = { e -> if (keyOf(e) == "Escape") closeSupportDialog() }
    tkEsc = onKey
    document.addEventListener("keydown", onKey)
}
