package dev.jellystructure.ui

import dev.jellystructure.api.httpClient
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.model.AdminSessionList
import dev.jellystructure.model.AdminSessionRow
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.WebSocket

// 304a (FR-304-1/-3, dev review items 1, 2, 9) — *Playing now* on Users & devices: every live playback session (all
// owners, every network — owner decision 2) and today's ended ones, each expandable to its timeline. Seeded from
// `GET /api/tv/admin/playback/sessions`, then following `playback_sessions` on `/ws` (constitution: seeded from GET,
// never accumulated from events alone). 304b adds the remote and the household switch.

private val pnJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
private var pnSocket: WebSocket? = null
private var pnList: AdminSessionList? = null
private val pnOpen = mutableSetOf<String>()

/** The section's frame; [loadPlayingNow] fills it. */
internal fun playingNowHtml(): String = """
    <div class="row center" style="gap:10px;margin:6px 0 6px"><h2 style="margin:0;font-size:1rem">Playing now</h2><span class="tiny muted" id="ses-count"></span><span class="spacer"></span><span id="ses-ctl-slot"></span></div>
    <div class="card" id="sessions" style="padding:8px 16px 10px;margin-bottom:18px">
      <div style="overflow-x:auto;"><table class="wf-table usr-table ses-tbl" style="min-width:760px;" id="ses-list"><tr><td class="muted tiny">Loading…</td></tr></table></div>
    </div>
    <h2 style="margin:6px 0 6px;font-size:1rem">People</h2>
""".trimIndent()

internal fun loadPlayingNow(scope: CoroutineScope) {
    scope.launch {
        val list = runCatching { httpClient.get("/api/tv/admin/playback/sessions").body<AdminSessionList>() }.getOrNull()
        if (list != null) { pnList = list; paintPlayingNow(scope) }
        else (document.getElementById("ses-list") as? HTMLElement)?.innerHTML = """<tr><td class="tiny" style="color:var(--bad)">Couldn't load what is playing.</td></tr>"""
    }
    pnSocket?.close()
    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val ws = WebSocket("$proto://${window.location.host}/ws")
    pnSocket = ws
    ws.onmessage = { ev ->
        if (document.getElementById("ses-list") == null) { ws.close(); if (pnSocket == ws) pnSocket = null }
        else runCatching {
            val e = pnJson.decodeFromString<JobEvent>(ev.data.toString())
            if (e is JobEvent.PlaybackSessions) { pnList = e.list; paintPlayingNow(scope) }
        }
    }
}

private fun pnNowMs(): Double = js("Date.now()")

private fun pnIcon(icon: String): String = when (icon) { "phone" -> "📱"; "computer" -> "💻"; "speaker" -> "🔈"; "group" -> "🔈🔈"; else -> "📺" }

private fun pnClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
}

private fun pnRan(fromMs: Long, nowMs: Long): String {
    val min = ((nowMs - fromMs) / 60_000).coerceAtLeast(0)
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

private fun pnStateWord(r: AdminSessionRow): String = when {
    r.endedAt != null -> "Ended · " + when (r.endReason) {
        "stopped" -> if (r.endedBy != null) "stopped by ${r.endedBy}" else "stopped"
        "replaced" -> "replaced"
        "watchdog" -> "the place went silent"
        "no_return_after_restart" -> "didn't come back after the restart"
        "failed" -> "failed"
        "finished" -> "finished"
        else -> r.endReason ?: "ended"
    }
    r.reconnecting -> "Reconnecting after a restart"
    r.offline -> "${r.targetName} is offline · paused"
    r.state == "playing" -> "Playing"
    r.state == "paused" -> "Paused"
    r.state == "starting" || r.state == "buffering" -> "Loading…"
    else -> r.state.replaceFirstChar { it.uppercase() }
}

private fun pnEventWord(what: String, detail: String?): String = when (what) {
    "started" -> "Started"
    "paused" -> "Paused"
    "resumed" -> "Resumed"
    "reconnected" -> "Reconnected after restart"
    "back_online" -> "Back online"
    "offline" -> "${detail ?: "The place"} went offline"
    "moved" -> "Moved to ${detail ?: "another place"}"
    "room_added" -> "${detail ?: "A room"} added"
    "room_removed" -> "${detail ?: "A room"} removed"
    "command" -> detail?.replaceFirstChar { it.uppercase() } ?: "Command"
    "ended" -> "Ended" + (detail?.let { " · " + it.replace('_', ' ') } ?: "")
    else -> what
}

private fun pnRow(r: AdminSessionRow, nowMs: Long, serverNowMs: Long): String {
    val video = r.kind == "film" || r.kind == "episode"
    val art = r.artwork?.let { """<img class="ses-art${if (video) " w" else ""}" src="${it.esc()}" alt="" style="object-fit:cover">""" }
        ?: """<span class="ses-art${if (video) " w" else ""}" style="background:var(--line-2)"></span>"""
    val title = listOfNotNull(r.title, r.subtitle?.takeIf { r.kind == "episode" }).joinToString(" · ").ifBlank { "—" }
    val sub = r.subtitle?.takeIf { r.kind != "episode" }
    val pos = if (r.state == "playing" && r.endedAt == null) r.positionMs + (serverNowMs - r.positionAt).coerceAtLeast(0) else r.positionMs
    val dur = r.durationMs
    val progress = if (dur != null && dur > 0) """<span class="ses-pg"><i style="width:${((pos.toDouble() / dur) * 100).coerceIn(0.0, 100.0)}%"></i></span>""" else ""
    val who = if (r.controllers.isNotEmpty()) r.controllers.joinToString(" · ") else r.startedFrom?.let { "started from ${it.esc()}" } ?: ""
    val open = r.id in pnOpen
    val timeline = r.events.joinToString("") { e ->
        """<div><span class="w">${dev.jellystructure.formatStoredTs((e.at / 1000).toString()).esc()}</span>${pnEventWord(e.what, e.detail).esc()}${e.source?.let { s -> " <span class=\"muted\">· ${if (s == "admin") "from the admin" else "from ${s.esc()}"}</span>" } ?: ""}</div>"""
    }
    return """<tr class="ses-r" data-ses="${r.id.esc()}"${if (r.endedAt != null) " style=\"opacity:.6\"" else ""}>
        <td style="white-space:nowrap"><b>${r.ownerName.esc()}</b></td>
        <td><span class="ses-what">$art<span><b>${title.esc()}</b>${sub?.let { "<span class=\"tiny muted\">${it.esc()}</span>" } ?: ""}</span></span></td>
        <td style="white-space:nowrap">${pnIcon(r.targetIcon)} ${r.targetName.esc()}</td>
        <td style="white-space:nowrap">${pnStateWord(r).esc()}</td>
        <td><span class="ses-pr">${pnClock(pos)}$progress</span></td>
        <td class="tiny muted" style="white-space:nowrap">${pnRan(r.createdAt, nowMs)}${if (who.isNotEmpty()) " · $who" else ""}</td>
      </tr>""" + if (open) """<tr class="ses-x"><td colspan="6"><div class="ses-h">History</div><div class="ses-tl">$timeline</div></td></tr>""" else ""
}

private fun paintPlayingNow(scope: CoroutineScope) {
    val el = document.getElementById("ses-list") as? HTMLElement ?: return
    val list = pnList ?: return
    val now = pnNowMs().toLong()
    val serverNow = list.serverNowMs.takeIf { it > 0 } ?: now
    val live = list.sessions.filter { it.endedAt == null }
    val ended = list.sessions.filter { it.endedAt != null }
    (document.getElementById("ses-count") as? HTMLElement)?.textContent = if (live.isEmpty()) "" else "${live.size} playing"
    if (list.sessions.isEmpty()) {
        el.innerHTML = """<tr><td class="ud-empty muted">Nothing is playing anywhere.</td></tr>"""
        return
    }
    val head = """<tr class="usr-hd"><td>Person</td><td>What</td><td>Place</td><td>State</td><td>Position</td><td>Running</td></tr>"""
    el.innerHTML = head +
        (if (live.isEmpty()) """<tr><td colspan="6" class="ud-empty muted">Nothing is playing anywhere.</td></tr>""" else live.joinToString("") { pnRow(it, now, serverNow) }) +
        (if (ended.isNotEmpty()) """<tr class="usr-hd"><td colspan="6">Ended today</td></tr>""" + ended.joinToString("") { pnRow(it, now, serverNow) } else "")
    el.querySelectorAll("tr.ses-r").let { rows ->
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            row.addEventListener("click", { _ ->
                val id = row.getAttribute("data-ses") ?: return@addEventListener
                if (!pnOpen.add(id)) pnOpen.remove(id)
                paintPlayingNow(scope)
            })
        }
    }
}
