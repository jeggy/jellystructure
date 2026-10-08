package dev.jellystructure.ui

import dev.jellystructure.api.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.isSuccess
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

// ── Phase 310 (FR-310-7) / 312 (FR-312-5) — *Watch places to put back*: the dry run, and the owner's press ──

@Serializable
private data class RepairActionDto(
    @SerialName("jellyfin_id") val jellyfinId: String,
    val title: String,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("last_played") val lastPlayed: String,
    val source: String,
    val now: String,
)

@Serializable
private data class RepairApplied(@SerialName("put_back") val putBack: Int)

private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "${s / 3600}:${(s % 3600 / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

internal fun openPlaybackRepair(scope: CoroutineScope, after: () -> Unit) {
    prModal("""<h3>Watch places to put back</h3><p class="tiny muted">Loading…</p>""")
    scope.launch {
        val list = runCatching { httpClient.get("/api/dashboard/playback-repair").body<List<RepairActionDto>>() }.getOrNull()
            ?: return@launch prModal("""<h3>Couldn’t read the list</h3><div class="row" style="justify-content:flex-end"><span class="btn ghost" data-prx>Close</span></div>""")
        val rows = list.joinToString("") { a ->
            """<div style="border-top:1px solid var(--line);padding:10px 0"><b>${a.title.esc()}</b>
               <div class="tiny muted">Now ${a.now.esc()} → unwatched at ${clock(a.positionMs)} · ${if (a.source == "310") "writer outage" else "stray stop at 0"}</div></div>"""
        }
        prModal(
            """<h3>Watch places to put back</h3>
               <p class="tiny muted">Nothing has been changed yet. *Put them back* sets each to unwatched at the place it was stopped, with the date it was watched. Anything played again since is not in the list.</p>
               $rows
               <div class="row" style="justify-content:flex-end;gap:8px;margin-top:12px"><span class="btn ghost" data-prx>Close</span>
               ${if (list.isNotEmpty()) """<span class="btn primary" data-prapply>Put them back (${list.size})</span>""" else ""}</div>""",
        ) { t ->
            if (t.closest("[data-prapply]") == null) return@prModal
            if (!window.confirm("Put back ${list.size} watch ${if (list.size == 1) "place" else "places"}?")) return@prModal
            (t as? HTMLElement)?.textContent = "Putting back…"
            scope.launch {
                val r = runCatching { httpClient.post("/api/dashboard/playback-repair/apply").let { if (it.status.isSuccess()) it.body<RepairApplied>() else null } }.getOrNull()
                prModal("""<h3>${if (r != null) "Put back ${r.putBack} of ${list.size}" else "Couldn’t put them back"}</h3><div class="row" style="justify-content:flex-end"><span class="btn ghost" data-prx>Close</span></div>""")
                after()
            }
        }
    }
}

private fun prModal(html: String, onClick: ((Element) -> Unit)? = null) {
    (document.getElementById("pr-modal") as? HTMLElement)?.remove()
    val m = document.createElement("div") as HTMLElement
    m.id = "pr-modal"; m.className = "mu-modal ed-modal on"
    m.innerHTML = """<div class="card" style="max-height:86vh;overflow:auto">$html</div>"""
    document.body?.appendChild(m)
    m.addEventListener("click") { ev ->
        val t = ev.target as? Element ?: return@addEventListener
        if (t == m || t.closest("[data-prx]") != null) { m.remove(); return@addEventListener }
        onClick?.invoke(t)
    }
}
