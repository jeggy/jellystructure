package dev.jellystructure.ui

import dev.jellystructure.api.PublishApi
import dev.jellystructure.model.PublishItemDto
import dev.jellystructure.model.PublishListDto
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

// ── Phase 307 — *Waiting to publish*: the Dashboard's panel (opened by the row's `opens = publish_queue`) ──
//
// FR-307-3: what each target is and what publishing means, every waiting item with its label, reason, when and by
// what it was queued, its payload field by field and *Show what is sent* for the raw JSON, Publish / Don't publish,
// and Publish all n / Don't publish any. FR-307-4: failed items with *Try again*. FR-307-5/6: the foot's
// *Published* (the last 50) and *Dismissed (n)* with *Queue again*. Everything shown is the server's; nothing here
// sends anything but a press.

private var pqData: PublishListDto? = null
private var pqTab = "waiting"
private val pqRaw = HashSet<Long>()
private var pqBusy = false

internal fun openPublishQueue(scope: CoroutineScope, after: () -> Unit) {
    pqTab = "waiting"; pqRaw.clear()
    pqModal("""<h3>Waiting to publish</h3><p class="tiny muted">Loading…</p>""")
    scope.launch { pqReload(scope, after) }
}

private suspend fun pqReload(scope: CoroutineScope, after: () -> Unit) {
    val d = PublishApi.list() ?: return pqModal("""<h3>Couldn’t read the queue</h3><div class="row" style="justify-content:flex-end"><span class="btn ghost" data-pqx>Close</span></div>""")
    pqData = d
    pqPaint(scope, after)
    // FR-307-4 — while the worker sends, the panel follows it (one item at a time).
    if (d.publishing.isNotEmpty()) window.setTimeout({
        if (document.getElementById("pq-modal") != null) scope.launch { pqReload(scope, after); if (pqData?.publishing.isNullOrEmpty()) after() }
        null
    }, 2000)
}

private fun pqWhen(sec: Long?): String = sec?.let { dev.jellystructure.formatWeekdayClock(it.toString()) } ?: "—"

private fun pqItemHtml(i: PublishItemDto): String = buildString {
    append("""<div style="border-top:1px solid var(--line);padding:12px 0">""")
    append("""<div class="row center" style="gap:8px;flex-wrap:wrap"><b>${i.label.esc()}</b>""")
    when (i.state) {
        "publishing" -> append("""<span class="badge">Publishing…</span>""")
        "failed" -> append("""<span class="badge warn">Not published</span>""")
        "published" -> append("""<span class="badge ok">Published</span>""")
        "dismissed" -> append("""<span class="badge">Not to be published</span>""")
    }
    append("""<span class="spacer" style="flex:1"></span>""")
    when (i.state) {
        "waiting" -> append("""<span class="btn sm primary" data-pqpub="${i.id}">Publish</span><span class="btn sm ghost" data-pqno="${i.id}">Don’t publish</span>""")
        "failed" -> append("""<span class="btn sm" data-pqretry="${i.id}">Try again</span><span class="btn sm ghost" data-pqno="${i.id}">Don’t publish</span>""")
        "dismissed" -> append("""<span class="btn sm ghost" data-pqagain="${i.id}">Queue again</span>""")
    }
    append("</div>")
    append("""<div class="tiny muted" style="margin-top:2px">${i.reason.esc()}</div>""")
    val whenLine = when (i.state) {
        "published" -> "Published ${pqWhen(i.sentAt)}" + (i.decidedBy?.let { " · pressed by ${it.esc()}" } ?: "") + " · queued ${pqWhen(i.queuedAt)}"
        "dismissed" -> "Declined ${pqWhen(i.decidedAt)}" + (i.decidedBy?.let { " by ${it.esc()}" } ?: "")
        else -> "Queued ${pqWhen(i.queuedAt)} by ${i.queuedBy.esc()}"
    }
    append("""<div class="tiny muted">$whenLine</div>""")
    if (i.state == "failed" && !i.answer.isNullOrBlank()) append("""<div class="tiny" style="color:var(--warn);margin-top:4px">${i.answer.esc()} · tried ${pqWhen(i.sentAt)}</div>""")
    if (i.state == "published" && !i.answer.isNullOrBlank()) append("""<div class="tiny" style="margin-top:4px">${i.target.esc()} answered: ${i.answer.esc()}</div>""")
    // FR-307-3 — the exact payload, field by field, and the raw JSON on demand.
    append("""<div style="display:grid;grid-template-columns:max-content 1fr;gap:2px 12px;margin-top:8px;font-size:.84rem">""")
    for (f in i.fields) append("""<span class="muted">${f.label.esc()}</span><span>${f.value.esc()}</span>""")
    append("</div>")
    val open = i.id in pqRaw
    append("""<div style="margin-top:6px"><a class="tiny" style="cursor:pointer" data-pqraw="${i.id}">${if (open) "Hide what is sent" else "Show what is sent"}</a></div>""")
    if (open) append("""<pre class="mono" style="white-space:pre-wrap;word-break:break-all;font-size:.74rem;margin:6px 0 0;padding:8px;border:1px solid var(--line);border-radius:8px">${i.payload.esc()}</pre>""")
    append("</div>")
}

private fun pqPaint(scope: CoroutineScope, after: () -> Unit) {
    val d = pqData ?: return
    val html = buildString {
        append("""<div class="row center" style="gap:10px"><h3 style="margin:0">Waiting to publish</h3><span class="spacer" style="flex:1"></span><span class="btn sm ghost" data-pqx>Close</span></div>""")
        // FR-307-3 — per target, one plain sentence of what publishing means, and its own page.
        for (t in d.targets) append("""<p class="tiny" style="margin-top:8px"><b>${t.name.esc()}</b> · <a href="${t.url.esc()}" target="_blank" rel="noopener">${t.url.removePrefix("https://").esc()} ↗</a> — ${t.sentence.esc()}</p>""")
        when (pqTab) {
            "published" -> {
                append("""<p class="tiny muted">What this household has put into a public database — the last ${d.published.size}.</p>""")
                if (d.published.isEmpty()) append("""<p class="tiny">Nothing published yet.</p>""")
                d.published.forEach { append(pqItemHtml(it)) }
            }
            "dismissed" -> {
                append("""<p class="tiny muted">You said not to publish these. They are not proposed again unless what would be sent changes.</p>""")
                if (d.dismissed.isEmpty()) append("""<p class="tiny">Nothing declined.</p>""")
                d.dismissed.forEach { append(pqItemHtml(it)) }
            }
            else -> {
                val n = d.waiting.size
                if (n > 0) append("""<div class="row center" style="gap:8px;margin:10px 0 4px"><span class="btn sm primary" data-pqall>Publish all $n</span><span class="btn sm ghost" data-pqnone>Don’t publish any</span><span class="tiny muted" style="margin-left:6px">Sent one at a time.</span></div>""")
                else if (d.publishing.isEmpty() && d.failed.isEmpty()) append("""<p class="tiny">Nothing is waiting.</p>""")
                (d.publishing + d.failed + d.waiting).forEach { append(pqItemHtml(it)) }
            }
        }
        // FR-307-5/6 — the foot: the receipt and the declined.
        append("""<div class="row center" style="gap:10px;margin-top:12px;border-top:1px solid var(--line);padding-top:10px"><span class="seg">""")
        for ((k, label) in listOf("waiting" to "Waiting · ${d.waiting.size + d.failed.size + d.publishing.size}", "published" to "Published · ${d.published.size}", "dismissed" to "Dismissed (${d.dismissed.size})"))
            append("""<span class="${if (pqTab == k) "on" else ""}" data-pqtab="$k">${label.esc()}</span>""")
        append("</span></div>")
    }
    pqModal(html) { t -> pqClick(t, scope, after) }
}

private fun pqClick(t: Element, scope: CoroutineScope, after: () -> Unit) {
    t.closest("[data-pqtab]")?.let { pqTab = it.getAttribute("data-pqtab") ?: "waiting"; pqPaint(scope, after); return }
    t.closest("[data-pqraw]")?.let { b ->
        val id = b.getAttribute("data-pqraw")?.toLongOrNull() ?: return
        if (!pqRaw.add(id)) pqRaw.remove(id)
        pqPaint(scope, after); return
    }
    val d = pqData ?: return
    fun act(call: suspend () -> dev.jellystructure.model.PublishActionResult?) {
        if (pqBusy) return
        pqBusy = true
        scope.launch {
            val r = call()
            pqBusy = false
            muToast(r?.sentence ?: "That didn’t go through")
            pqReload(scope, after); after()
        }
    }
    t.closest("[data-pqpub]")?.getAttribute("data-pqpub")?.toLongOrNull()?.let { id -> act { PublishApi.publish(id) }; return }
    t.closest("[data-pqno]")?.getAttribute("data-pqno")?.toLongOrNull()?.let { id -> act { PublishApi.dismiss(id) }; return }
    t.closest("[data-pqretry]")?.getAttribute("data-pqretry")?.toLongOrNull()?.let { id -> act { PublishApi.tryAgain(id) }; return }
    t.closest("[data-pqagain]")?.getAttribute("data-pqagain")?.toLongOrNull()?.let { id -> act { PublishApi.queueAgain(id) }; return }
    if (t.closest("[data-pqall]") != null) { act { PublishApi.publishAll(d.waiting.map { it.id }) }; return }
    if (t.closest("[data-pqnone]") != null) { act { PublishApi.dismissAll(d.waiting.map { it.id }) }; return }
}

private fun pqModal(html: String, onClick: ((Element) -> Unit)? = null) {
    val old = document.getElementById("pq-modal") as? HTMLElement
    val scroll = (old?.querySelector(".card") as? HTMLElement)?.scrollTop ?: 0.0
    old?.remove()
    val m = document.createElement("div") as HTMLElement
    m.id = "pq-modal"; m.className = "mu-modal ed-modal on"
    m.innerHTML = """<div class="card" style="max-height:86vh;overflow:auto">$html</div>"""
    document.body?.appendChild(m)
    (m.querySelector(".card") as? HTMLElement)?.scrollTop = scroll
    m.addEventListener("click") { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        if (t == m || t.closest("[data-pqx]") != null) { m.remove(); return@addEventListener }
        onClick?.invoke(t)
    }
}
