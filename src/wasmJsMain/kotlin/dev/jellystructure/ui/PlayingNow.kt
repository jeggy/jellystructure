package dev.jellystructure.ui

import dev.jellystructure.api.httpClient
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.model.AdminSessionList
import dev.jellystructure.model.AdminSessionRow
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
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
// 304b — each open row's remote detail (the queue, the ops), and the row whose *End* is waiting for its confirm.
private val pnDetails = mutableMapOf<String, dev.jellystructure.shared.tv.SessionDetail>()
private var pnEndArmed: String? = null
private var pnFeedback: Pair<String, String>? = null   // (session id, message) — a refused command, said on its row
// R372 (FR-R372-2) — the owner's places, for the admin's *Move to…*.
private val pnTargets = mutableMapOf<String, List<dev.jellystructure.shared.tv.PlaybackTarget>>()

/** The section's frame; [loadPlayingNow] fills it. */
internal fun playingNowHtml(): String = """
    <div class="row center" style="gap:10px;margin:6px 0 6px"><h2 style="margin:0;font-size:1rem">Playing now</h2><span class="tiny muted" id="ses-count"></span><span class="spacer"></span><label class="tiny" style="display:flex;align-items:center;gap:6px;cursor:pointer" title="Off: each person controls only their own; everyone still sees the others' with their name. The switch never limits you."><input type="checkbox" id="ses-ctl"> Household members can control each other's playing</label></div>
    <div class="tiny muted" style="margin:-2px 0 8px">Click a row for the remote and its history. What you do here shows in the viewer's apps straight away, marked <i>from the admin</i>; <b>End</b> stops it and keeps the resume point.</div>
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
            if (e is JobEvent.PlaybackSessions) {
                pnList = e.list
                // A changed row's detail (its queue, its ops) is read again while it is open.
                e.list.sessions.filter { it.id in pnOpen }.forEach { r -> if (pnDetails[r.id]?.session?.revision != r.revision) loadDetail(scope, r.id) }
                paintPlayingNow(scope)
            }
        }
    }
}

private fun loadDetail(scope: CoroutineScope, id: String) {
    scope.launch {
        runCatching { httpClient.get("/api/tv/admin/playback/sessions/$id").body<dev.jellystructure.shared.tv.SessionDetail>() }.getOrNull()
            ?.let { pnDetails[id] = it; paintPlayingNow(scope) }
        runCatching { httpClient.get("/api/tv/admin/playback/sessions/$id/targets").body<dev.jellystructure.shared.tv.TargetList>() }.getOrNull()
            ?.let { pnTargets[id] = it.targets; paintPlayingNow(scope) }
    }
}

/** 304b (FR-304-2, review item 4) — one command from the admin: the same service as the apps', `source = admin`. */
private fun pnCommand(scope: CoroutineScope, r: AdminSessionRow, body: String) {
    scope.launch {
        val resp = runCatching {
            httpClient.post("/api/tv/admin/playback/sessions/${r.id}/command") { setBody(io.ktor.http.content.TextContent(body, ContentType.Application.Json)) }
        }.getOrNull()
        pnFeedback = when {
            resp == null -> r.id to "Couldn't reach the server."
            resp.status.value == 409 -> r.id to (if ("stale" in runCatching { resp.body<String>() }.getOrDefault("")) "That changed meanwhile — redrawn." else "Can't reach ${r.targetName}.")
            !resp.status.isSuccess() -> r.id to "Refused (${resp.status.value})."
            else -> null
        }
        loadDetail(scope, r.id)
        paintPlayingNow(scope)
    }
}

private fun pnBody(op: String, rev: Long, extra: String = ""): String = "{\"op\":\"$op\",\"revision\":$rev$extra}"

/** R372 (FR-R372-2) — *Move to…* from the admin: the same move as the apps', made for the owner. */
private fun pnMove(scope: CoroutineScope, r: AdminSessionRow, targetId: String) {
    scope.launch {
        val body = "{\"target_id\":\"${targetId.replace("\"", "")}\",\"revision\":${pnDetails[r.id]?.session?.revision ?: r.revision}}"
        val resp = runCatching {
            httpClient.post("/api/tv/admin/playback/sessions/${r.id}/move") { setBody(io.ktor.http.content.TextContent(body, ContentType.Application.Json)) }
        }.getOrNull()
        pnFeedback = when {
            resp == null -> r.id to "Couldn't reach the server."
            resp.status.value == 409 -> r.id to (if ("stale" in runCatching { resp.body<String>() }.getOrDefault("")) "That changed meanwhile — redrawn." else "Can't reach that place.")
            !resp.status.isSuccess() -> r.id to "Can't move it there (${resp.status.value})."
            else -> null
        }
        loadDetail(scope, r.id)
        paintPlayingNow(scope)
    }
}

/** The places a session may move to from the admin: reachable, able to play its kind, not where it is (a book never casts). */
internal fun pnMoveTargets(kind: String, currentTargetId: String, currentCastDeviceId: String?, targets: List<dev.jellystructure.shared.tv.PlaybackTarget>): List<dev.jellystructure.shared.tv.PlaybackTarget> =
    targets.filter { t ->
        t.reachable && t.id != currentTargetId && (currentCastDeviceId == null || t.castDeviceId != currentCastDeviceId) && when (kind) {
            "film", "episode" -> t.capabilities.video
            "audiobook" -> t.kind != "cast" && t.capabilities.book
            else -> t.capabilities.audio
        }
    }

/** 304b — the remote inside an open row: only the ops the target obeys (absent, never greyed). */
private fun pnRemote(r: AdminSessionRow): String {
    if (r.endedAt != null) return ""
    val d = pnDetails[r.id]
    val ops = d?.ops ?: r.ops
    fun btn(op: String, label: String, extra: String = "") = if (op in ops) "<button class=\"btn sm ghost\" data-cmd=\"${r.id.esc()}\" data-op=\"$op\" data-extra='${extra.esc()}'>$label</button>" else ""
    val play = if (r.state == "playing") btn("pause", "⏸ Pause") else btn("play", "▶ Play")
    val dur = r.durationMs
    val seek = if ("seek" in ops && dur != null && dur > 0) "<input type=\"range\" min=\"0\" max=\"$dur\" value=\"${r.positionMs}\" data-seek=\"${r.id.esc()}\">" else ""
    val end = if ("stop" in ops) {
        if (pnEndArmed == r.id) "<button class=\"btn sm\" style=\"color:var(--bad)\" data-cmd=\"${r.id.esc()}\" data-op=\"stop\">End — sure?</button>"
        else "<button class=\"btn sm ghost\" data-arm=\"${r.id.esc()}\">End</button>"
    } else ""
    val queue = d?.queue?.takeIf { it.size > 1 }?.let { q ->
        "<div class=\"ses-h\">Queue</div><div class=\"ses-q\">" + q.mapIndexed { i, e ->
            val idx = d.queueOffset + i
            val on = idx == d.queueIndex
            "<span class=\"${if (on) "on" else ""}\"${if ("jump" in ops && !on) " data-cmd=\"${r.id.esc()}\" data-op=\"jump\" data-extra=',\"index\":$idx'" else ""}>${(e.title ?: "—").esc()}${e.subtitle?.let { " <span class=\"muted\">· ${it.esc()}</span>" } ?: ""}</span>"
        }.joinToString("") + "</div>"
    } ?: ""
    // R371 — Volume (the receiver's own) and, for a group, each room's (through the app holding the Cast link).
    val vol = if ("set_volume" in ops && d?.volume != null) {
        val rooms = d.session.rooms.takeIf { it.size > 1 }.orEmpty()
        "<div class=\"ses-h\">Volume</div><div class=\"ses-v\"><span>${if (rooms.isEmpty()) r.targetName.esc() else "Volume"}</span><input type=\"range\" min=\"0\" max=\"100\" value=\"${d.volume}\" data-vol=\"${r.id.esc()}\" data-room=\"\"><span>${d.volume}</span></div>" +
            rooms.joinToString("") { room ->
                val lv = room.volume
                if (lv == null || "room_volume" !in ops) "<div class=\"ses-v rm\"><span>${room.name.esc()}</span><span class=\"muted tiny\">${room.name.esc()} doesn't report its volume</span><span>—</span></div>"
                else "<div class=\"ses-v rm\"><span>${room.name.esc()}</span><input type=\"range\" min=\"0\" max=\"100\" value=\"$lv\" data-vol=\"${r.id.esc()}\" data-room=\"${room.castDeviceId.esc()}\"><span>$lv</span></div>"
            }
    } else ""
    val note = pnFeedback?.takeIf { it.first == r.id }?.let { "<div class=\"tiny\" style=\"color:var(--bad);margin-top:6px\">${it.second.esc()}</div>" } ?: ""
    // R372 (FR-R372-2) — *Move to…*: the owner's places that can play it; a busy one says who is on it.
    val moveTo = pnMoveTargets(r.kind, r.targetId, pnDetails[r.id]?.session?.target?.castDeviceId, pnTargets[r.id].orEmpty()).takeIf { it.isNotEmpty() && pnDetails[r.id]?.session?.movingTo == null }?.let { ts ->
        "<div class=\"ses-h\">Move to…</div><div class=\"ses-ctl\"><select data-move-sel=\"${r.id.esc()}\">" +
            ts.joinToString("") { t -> "<option value=\"${t.id.esc()}\">${t.name.esc()}${t.busy?.let { b -> " · busy (${b.owner.name.esc()})" } ?: ""}</option>" } +
            "</select><button class=\"btn sm ghost\" data-move=\"${r.id.esc()}\">Move</button></div>"
    } ?: pnDetails[r.id]?.session?.movingTo?.let { "<div class=\"tiny muted\" style=\"margin-top:6px\">Moving to ${it.esc()}…</div>" } ?: ""
    return "<div class=\"ses-h\">Remote</div><div class=\"ses-ctl\">${btn("previous", "⏮")}$play${btn("next", "⏭")}$seek$end</div>$note$vol$moveTo$queue"
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

/** 308 (FR-308-5) — the variant playing now, in words: *1080p · 8 Mbps · stepped down twice*; empty with one variant. */
internal fun pnVariantWords(bps: Long?, height: Int?, down: Int, up: Int, startBps: Long? = null): String {
    if (bps == null || bps <= 0) return ""
    fun rate(b: Long): String {
        val mbps = b / 1_000_000.0
        return if (mbps >= 10) "${kotlin.math.round(mbps).toLong()} Mbps" else "${kotlin.math.round(mbps * 10) / 10.0} Mbps".replace(".0 ", " ")
    }
    fun times(n: Int) = when (n) { 1 -> "once"; 2 -> "twice"; else -> "$n times" }
    // 309 (FR-309-11) — *4 Mbps → 1080p · 8 Mbps · climbing*: where it started, when it is somewhere else now.
    val from = startBps?.takeIf { it > 0 && it != bps }?.let { "${rate(it)} → " } ?: ""
    return from + listOfNotNull(
        height?.let { "${it}p" }, rate(bps),
        down.takeIf { it > 0 }?.let { "stepped down ${times(it)}" },
        "climbing".takeIf { down == 0 && up > 0 },
        up.takeIf { it > 0 && down > 0 }?.let { "up ${times(it)}" },
    ).joinToString(" · ")
}

/** 313 (FR-313-13) — *our encoder · 4 qualities · HEVC HDR* or *Jellyfin* (a direct play says nothing). */
internal fun pnEncoderWords(encoder: String?, detail: String?): String = when (encoder) {
    "ours" -> listOfNotNull("our encoder", detail).joinToString(" · ")
    "jellyfin" -> "Jellyfin"
    else -> ""
}

/** 308 + 313 — the two lines under the state word, joined: who encodes, and the variant it plays. */
internal fun pnPlayWords(r: dev.jellystructure.model.AdminSessionRow): String = listOf(
    pnEncoderWords(r.encoder, r.encoderDetail),
    pnVariantWords(r.variantBps, r.variantHeight, r.variantStepsDown, r.variantStepsUp, r.variantStartBps),
).filter { it.isNotEmpty() }.joinToString(" · ")

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
        <td style="white-space:nowrap">${pnStateWord(r).esc()}${pnPlayWords(r).takeIf { it.isNotEmpty() }?.let { "<div class=\"tiny muted\">${it.esc()}</div>" } ?: ""}</td>
        <td><span class="ses-pr">${pnClock(pos)}$progress</span></td>
        <td class="tiny muted" style="white-space:nowrap">${pnRan(r.createdAt, nowMs)}${if (who.isNotEmpty()) " · $who" else ""}</td>
      </tr>""" + if (open) """<tr class="ses-x"><td colspan="6"><div class="grid2"><div>${pnRemote(r)}</div><div><div class="ses-h">History</div><div class="ses-tl">$timeline</div></div></div></td></tr>""" else ""
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
                if (!pnOpen.add(id)) pnOpen.remove(id) else loadDetail(scope, id)
                paintPlayingNow(scope)
            })
        }
    }
    // 304b — the remote's buttons, the seek line and *End*'s inline confirm.
    fun rowOf(id: String) = list.sessions.firstOrNull { it.id == id }
    el.querySelectorAll("[data-cmd]").let { bs ->
        for (i in 0 until bs.length) {
            val b = bs.item(i) as? HTMLElement ?: continue
            b.addEventListener("click", { ev ->
                ev.stopPropagation()
                val r = rowOf(b.getAttribute("data-cmd") ?: return@addEventListener) ?: return@addEventListener
                val op = b.getAttribute("data-op") ?: return@addEventListener
                pnEndArmed = null
                pnCommand(scope, r, pnBody(op, pnDetails[r.id]?.session?.revision ?: r.revision, b.getAttribute("data-extra").orEmpty()))
            })
        }
    }
    el.querySelectorAll("[data-move-sel]").let { ss ->
        for (i in 0 until ss.length) (ss.item(i) as? HTMLElement)?.addEventListener("click", { ev -> ev.stopPropagation() })
    }
    el.querySelectorAll("[data-move]").let { bs ->
        for (i in 0 until bs.length) {
            val b = bs.item(i) as? HTMLElement ?: continue
            b.addEventListener("click", { ev ->
                ev.stopPropagation()
                val id = b.getAttribute("data-move") ?: return@addEventListener
                val r = rowOf(id) ?: return@addEventListener
                val sel = el.querySelector("[data-move-sel=\"$id\"]") as? org.w3c.dom.HTMLSelectElement ?: return@addEventListener
                pnMove(scope, r, sel.value)
            })
        }
    }
    el.querySelectorAll("[data-arm]").let { bs ->
        for (i in 0 until bs.length) {
            val b = bs.item(i) as? HTMLElement ?: continue
            b.addEventListener("click", { ev -> ev.stopPropagation(); pnEndArmed = b.getAttribute("data-arm"); paintPlayingNow(scope) })
        }
    }
    el.querySelectorAll("[data-seek]").let { ss ->
        for (i in 0 until ss.length) {
            val s = ss.item(i) as? org.w3c.dom.HTMLInputElement ?: continue
            s.addEventListener("click", { ev -> ev.stopPropagation() })
            s.addEventListener("change", { _ ->
                val r = rowOf(s.getAttribute("data-seek") ?: return@addEventListener) ?: return@addEventListener
                pnCommand(scope, r, pnBody("seek", pnDetails[r.id]?.session?.revision ?: r.revision, ",\"position_ms\":${s.value}"))
            })
        }
    }
    el.querySelectorAll("[data-vol]").let { ss ->
        for (i in 0 until ss.length) {
            val s = ss.item(i) as? org.w3c.dom.HTMLInputElement ?: continue
            s.addEventListener("click", { ev -> ev.stopPropagation() })
            s.addEventListener("change", { _ ->
                val r = rowOf(s.getAttribute("data-vol") ?: return@addEventListener) ?: return@addEventListener
                val room = s.getAttribute("data-room").orEmpty()
                pnCommand(scope, r, pnBody("set_volume", pnDetails[r.id]?.session?.revision ?: r.revision,
                    ",\"level\":${s.value}" + if (room.isNotEmpty()) ",\"cast_device_id\":\"$room\"" else ""))
            })
        }
    }
    // FR-304-4 — the household switch, written at once (no global Save).
    (document.getElementById("ses-ctl") as? org.w3c.dom.HTMLInputElement)?.let { box ->
        box.checked = list.householdControl == true
        box.onchange = { _ ->
            val on = box.checked
            scope.launch {
                runCatching { httpClient.put("/api/tv/admin/playback/household-control") { setBody(io.ktor.http.content.TextContent("{\"on\":$on}", ContentType.Application.Json)) } }
            }
        }
    }
}
