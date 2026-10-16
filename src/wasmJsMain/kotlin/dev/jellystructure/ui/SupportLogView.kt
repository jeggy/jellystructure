package dev.jellystructure.ui

import dev.jellystructure.api.SupportApi
import dev.jellystructure.formatClock
import dev.jellystructure.model.SupportLogEntry
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * DEB-1500 (FR-12) — Activity › **Support log** (`#/activity?view=support`): what every author did to every ticket, newest
 * first. History only: nothing on it is actionable apart from the link to a ticket. The server sends codes and facts and
 * **never a body, note, summary or title** (FR-320-16), so there is none here to draw; [SupportText.logAction] words each
 * entry from its code. Styled by `design/app/support.css` under `.tksup` (the host carries it).
 *
 * It reads the route on open, again on every `support_ticket` event, and once more on **Older** at the foot.
 */
private var slEntries: List<SupportLogEntry> = emptyList()
private var slMore = false
private var slLoaded = false
private var slFailed = false
private var slHost: Element? = null
private var slGen = 0

private const val LOG_PAGE = 50

/** Mounts into [host] (the `#view-support` element) and listens for events until the host leaves the page. */
fun mountSupportLog(host: Element, scope: CoroutineScope) {
    slHost = host; slGen++
    slEntries = emptyList(); slMore = false; slLoaded = false; slFailed = false
    host.classList.add("tksup")
    if (host.getAttribute("data-tkl-bound") != "1") {
        host.setAttribute("data-tkl-bound", "1")
        host.addEventListener("click") { ev ->
            val older = (ev.target as? Element)?.closest("[data-tkl=\"older\"]") ?: return@addEventListener
            older.setAttribute("disabled", "")
            loadOlder(scope)
        }
    }
    SupportLive.listen("log") { e ->
        // The host is gone when Activity was left or drawn again: stop listening rather than paint into nothing.
        if (slHost == null || !document.contains(slHost) || slHost !== host) { SupportLive.drop("log"); return@listen }
        if (e.deleted) slEntries = slEntries.filter { it.ticket != e.number }
        loadLatest(scope)
    }
    paintLog()
    loadLatest(scope)
}

private fun loadLatest(scope: CoroutineScope) {
    val gen = slGen
    scope.launch {
        val page = SupportApi.log()
        if (gen != slGen) return@launch
        if (page == null) { if (!slLoaded) slFailed = true; paintLog(); return@launch }
        // A short page is the whole log. A full one is the newest 50: what was read earlier and is older than its last
        // entry stays, so a refresh on an event does not throw away the pages the admin has already scrolled through.
        val last = page.entries.lastOrNull()
        slEntries = if (last == null || page.entries.size < LOG_PAGE) page.entries
            else page.entries + slEntries.filter { it.at < last.at || (it.at == last.at && it.id < last.id) }
        if (!slLoaded) slMore = page.entries.size >= LOG_PAGE
        slLoaded = true; slFailed = false
        paintLog()
    }
}

private fun loadOlder(scope: CoroutineScope) {
    val gen = slGen
    val before = slEntries.lastOrNull()?.id ?: return
    scope.launch {
        val page = SupportApi.log(before)
        if (gen != slGen) return@launch
        if (page != null) {
            val seen = slEntries.mapTo(HashSet()) { it.id }
            slEntries = slEntries + page.entries.filter { it.id !in seen }
            slMore = page.entries.size >= LOG_PAGE
        }
        paintLog()
    }
}

private fun markHtml(e: SupportLogEntry): String = when (SupportText.actorMark(e)) {
    "ai" -> """<span class="tk-av sm ai"><i></i></span>"""
    "hv" -> """<span class="tk-av sm hv">H</span>"""
    "ad" -> """<span class="tk-av sm ad">${SupportText.actorName(e).take(1).uppercase().esc()}</span>"""
    "me" -> """<span class="tk-av sm me">${SupportText.actorName(e).take(1).uppercase().esc()}</span>"""
    else -> """<span class="tk-av sm none"></span>"""
}

private fun paintLog() {
    val host = slHost as? HTMLElement ?: return
    val rows = when {
        !slLoaded && slFailed -> """<div class="tk-empty">Couldn’t read the log.</div>"""
        !slLoaded -> """<div class="tk-empty">Loading…</div>"""
        slEntries.isEmpty() -> """<div class="tk-empty">Nothing has happened on a ticket yet.</div>"""
        else -> slEntries.joinToString("") { e ->
            """<div class="tk-lg"><time>${formatClock(e.at.toString())}</time><b>${markHtml(e)}${SupportText.actorName(e).esc()}</b>""" +
                """<span class="ac">${SupportText.logAction(e).esc()}</span><a class="tk" href="#/ai?tab=support&amp;ticket=${e.ticket}">#${e.ticket}</a></div>"""
        }
    }
    host.innerHTML = """<p class="page-sub">History only — what every author did to every ticket, newest first. Nothing here is actionable; the tickets themselves live under <a href="#/ai?tab=support">AI › Support</a>.</p>""" +
        """<div class="card flat tk-logbox"><div class="tk-log">$rows</div></div>""" +
        (if (slMore) """<button type="button" class="btn sm ghost" data-tkl="older">Older</button>""" else "")
}
