@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.TowoApi
import dev.jellystructure.api.TowoPermissionRequest
import dev.jellystructure.api.TowoRunner
import dev.jellystructure.api.TowoSession
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.WebSocket

/**
 * Phase 162 (Towo) — admin UI (spec §8). Scoped under a `.towo` wrapper matching
 * design/app/towo.css, which is already wired into syncDesignAssets. Build-order step 8; a first,
 * functional slice covering Overview/Runners/Sessions/Session-view/Approvals rather than every
 * screen the spec's mockups show — the recovery screen (§G) is folded into the session view instead
 * of its own page, and Settings §A only exposes the enable toggle so far (see Settings.kt).
 */

/** NodeListOf<Element> has no Kotlin-collection asList() in this DOM interop — index it manually,
 *  matching the pattern already used elsewhere in this codebase (see Library.kt). */
private fun forEachEl(list: org.w3c.dom.NodeList, action: (HTMLElement) -> Unit) {
    for (i in 0 until list.length) {
        (list.item(i) as? HTMLElement)?.let(action)
    }
}

private val towoJson = Json { ignoreUnknownKeys = true }
private var towoSocket: WebSocket? = null

private fun connectTowoStream(onEvent: (JsonObject) -> Unit): WebSocket {
    towoSocket?.close()
    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val ws = WebSocket("$proto://${window.location.host}/api/towo/stream")
    towoSocket = ws
    ws.onmessage = { ev ->
        val data = ev.data.toString()
        runCatching { towoJson.parseToJsonElement(data).jsonObject }.getOrNull()?.let(onEvent)
    }
    return ws
}

private fun statusChipHtml(status: String, resumeAt: Long? = null): String {
    val (cls, label) = when (status) {
        "running" -> "st-run" to "Running"
        "awaiting_permission" -> "st-ask" to "Needs you"
        "idle" -> "st-nap" to "Idle"
        "paused_quota" -> "st-nap" to if (resumeAt != null) "Paused · resumes ${formatEpochSec(resumeAt)}" else "Paused on quota"
        "stopped_max_turns" -> "st-err" to "Stopped · turn cap"
        "errored" -> "st-err" to "Error"
        "done" -> "st-done" to "Done"
        "starting" -> "st-run" to "Starting"
        else -> "st-nap" to status
    }
    return """<span class="chip $cls"><span class="dot"></span>$label</span>"""
}

private fun formatEpochSec(epochSec: Long): String = js("new Date(epochSec * 1000).toLocaleString()")

private fun runnerOnlineHtml(runner: TowoRunner): String {
    val lastSeen = runner.lastSeenAt
    val recentlyOnline = lastSeen != null && (nowEpochSecJs() - lastSeen) < 90
    return if (recentlyOnline) {
        """<span class="chip st-run"><span class="dot"></span>Online</span>"""
    } else {
        val label = if (lastSeen != null) "Last seen ${formatEpochSec(lastSeen)}" else "Never connected"
        """<span class="chip st-nap"><span class="dot"></span>$label</span>"""
    }
}

private fun nowMsJs(): Double = js("Date.now()")
private fun nowEpochSecJs(): Long = (nowMsJs() / 1000).toLong()

// ===== Overview =====

fun renderTowoOverview(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="towo">
          <div class="pagebar">
            <h1>Towo</h1>
            <span class="spacer"></span>
            <button id="towo-new-session-btn" class="btn sm">New session</button>
            <a href="#/towo/runners/new" class="btn sm pri">Add a runner</a>
          </div>
          <p class="page-sub">A control plane for Claude Code sessions running on machines you own.</p>
          <div id="towo-body">Loading…</div>
        </div>
    """.trimIndent()

    document.getElementById("towo-new-session-btn")?.addEventListener("click") {
        App.navigate("/towo/sessions?new=1")
    }

    scope.launch { loadOverview(container, scope) }
}

private suspend fun loadOverview(container: Element, scope: CoroutineScope) {
    val runners = TowoApi.runners()
    val sessions = TowoApi.sessions()
    val body = document.getElementById("towo-body") as? HTMLElement ?: return

    if (runners.isEmpty()) {
        body.innerHTML = """
            <div class="card" style="padding:22px">
              <div class="ttl" style="margin-bottom:6px">No runners yet</div>
              <p class="sub">A runner is a small program you run on any machine with Claude Code signed in — it's what actually runs sessions.</p>
              <a href="#/towo/runners/new" class="btn pri" style="margin-top:10px">Add your first runner</a>
            </div>
        """.trimIndent()
        return
    }

    val sessionsByRunner = sessions.groupBy { it.runnerId }
    body.innerHTML = buildString {
        for (runner in runners) {
            append("""<div class="card" style="padding:18px;margin-bottom:14px">""")
            append("""<div class="row" style="align-items:center;gap:10px;margin-bottom:10px">""")
            append("""<div class="ttl" style="font-size:1.05rem">${runner.name.esc()}</div>""")
            append(runnerOnlineHtml(runner))
            if (runner.claudeAuthOk == false) append("""<span class="chip st-err"><span class="dot"></span>Claude not signed in</span>""")
            append("""<span class="spacer"></span>""")
            append("""<a href="#/towo/runners" class="tiny muted">manage runners →</a>""")
            append("</div>")
            val rows = sessionsByRunner[runner.id].orEmpty()
            if (rows.isEmpty()) {
                append("""<p class="sub">No sessions on this runner yet.</p>""")
            } else {
                append("""<div class="rows">""")
                for (s in rows) append(sessionRowHtml(s))
                append("</div>")
            }
            append("</div>")
        }
    }
    forEachEl(body.querySelectorAll(".rw[data-session]")) { el ->
        val id = el.getAttribute("data-session") ?: return@forEachEl
        el.addEventListener("click") { App.navigate("/towo/session/$id") }
    }
}

private fun sessionRowHtml(s: TowoSession): String = """
    <div class="rw" data-session="${s.id}" style="cursor:pointer;grid-template-columns:1fr auto">
      <div>
        <div class="ttl">${(s.title ?: s.folderPath?.substringAfterLast('/') ?: s.id.take(8)).esc()}</div>
        <div class="meta">${s.numTurns} turn${if (s.numTurns != 1L) "s" else ""} · ${s.folderPath?.esc() ?: ""}</div>
      </div>
      ${statusChipHtml(s.status, s.resumeAt)}
    </div>
""".trimIndent()

// ===== Runners list =====

fun renderTowoRunners(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="towo">
          <div class="pagebar">
            <h1>Runners</h1>
            <span class="spacer"></span>
            <a href="#/towo/runners/new" class="btn sm pri">Add a runner</a>
          </div>
          <div id="towo-runners-body">Loading…</div>
        </div>
    """.trimIndent()
    scope.launch { loadRunnersList(container, scope) }
}

private suspend fun loadRunnersList(container: Element, scope: CoroutineScope) {
    val runners = TowoApi.runners()
    val body = document.getElementById("towo-runners-body") as? HTMLElement ?: return
    if (runners.isEmpty()) {
        body.innerHTML = """<p class="sub">No runners enrolled yet.</p>"""
        return
    }
    body.innerHTML = """<div class="rows">""" + runners.joinToString("") { r ->
        """
        <div class="rw" style="grid-template-columns:1fr auto auto">
          <div>
            <div class="ttl">${r.name.esc()}</div>
            <div class="meta">${(r.hostLabel ?: "").esc()} ${r.os?.let { "· $it" } ?: ""} ${r.agentSdkVersion?.let { "· sdk $it" } ?: ""}</div>
          </div>
          ${runnerOnlineHtml(r)}
          <button class="btn sm danger" data-delete-runner="${r.id}">Remove</button>
        </div>
        """.trimIndent()
    } + "</div>"

    forEachEl(body.querySelectorAll("[data-delete-runner]")) { el ->
        val id = el.getAttribute("data-delete-runner") ?: return@forEachEl
        el.addEventListener("click") { ev ->
            ev.stopPropagation()
            if (window.confirm("Remove this runner? Its sessions stay in history but can no longer run.")) {
                scope.launch { TowoApi.deleteRunner(id); loadRunnersList(container, scope) }
            }
        }
    }
}

// ===== Runner enrollment =====

fun renderTowoRunnerNew(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="towo">
          <div class="pagebar"><h1>Add a runner</h1></div>
          <div class="card" style="padding:22px;max-width:640px">
            <div class="field" style="margin-bottom:14px">
              <label class="lbl">Name</label>
              <input id="towo-runner-name" class="input" placeholder="e.g. dev box" style="max-width:320px">
            </div>
            <button id="towo-runner-mint-btn" class="btn pri">Generate command</button>
            <div id="towo-runner-result" style="margin-top:18px"></div>
          </div>
        </div>
    """.trimIndent()

    document.getElementById("towo-runner-mint-btn")?.addEventListener("click") {
        val name = (document.getElementById("towo-runner-name") as? HTMLInputElement)?.value?.trim().orEmpty()
        if (name.isEmpty()) return@addEventListener
        scope.launch { mintAndShowEnrollment(name, scope) }
    }
}

private suspend fun mintAndShowEnrollment(name: String, scope: CoroutineScope) {
    val resultEl = document.getElementById("towo-runner-result") as? HTMLElement ?: return
    resultEl.innerHTML = """<p class="sub">Generating…</p>"""
    val minted = TowoApi.enrollRunner(name)
    if (minted == null) {
        resultEl.innerHTML = """<p class="sub" style="color:var(--bad)">Failed to generate an enrollment command.</p>"""
        return
    }
    resultEl.innerHTML = buildString {
        append("""<p class="sub" style="margin-bottom:10px">Paste this on the machine you want Claude to run on. The token expires in 15 minutes and can only be used once.</p>""")
        append("""<div class="seg" style="margin-bottom:10px" id="towo-cmd-tabs">""")
        append("""<button class="on" data-cmd="npx">npx</button><button data-cmd="installer">installer</button><button data-cmd="docker">docker</button>""")
        append("</div>")
        append("""<div class="code" id="towo-cmd-code">${minted.commands["npx"]?.esc() ?: ""}</div>""")
        append("""<p class="tiny muted" style="margin-top:10px" id="towo-waiting">Waiting for the runner to connect…</p>""")
    }
    val commands = minted.commands
    forEachEl(resultEl.querySelectorAll("#towo-cmd-tabs button")) { btn ->
        btn.addEventListener("click") {
            forEachEl(resultEl.querySelectorAll("#towo-cmd-tabs button")) { it.className = "" }
            btn.className = "on"
            val key = btn.getAttribute("data-cmd") ?: "npx"
            (document.getElementById("towo-cmd-code") as? HTMLElement)?.textContent = commands[key] ?: ""
        }
    }
    // Poll for the runner appearing (spec §B: "waiting for runner" resolves live).
    scope.launch {
        repeat(150) { // 15 min at 6s, matching the token TTL
            delay(6_000)
            val runners = TowoApi.runners()
            if (runners.any { it.name == name }) {
                (document.getElementById("towo-waiting") as? HTMLElement)?.let {
                    it.textContent = "Connected."
                    it.className = "tiny"
                    it.style.color = "var(--ok)"
                }
                return@launch
            }
        }
    }
}

// ===== Sessions list =====

fun renderTowoSessions(container: Element, scope: CoroutineScope, query: Map<String, String>) {
    container.innerHTML = """
        <div class="towo">
          <div class="pagebar"><h1>Sessions</h1></div>
          <div id="towo-sessions-body">Loading…</div>
        </div>
    """.trimIndent()
    scope.launch {
        val sessions = TowoApi.sessions(status = query["status"])
        val body = document.getElementById("towo-sessions-body") as? HTMLElement ?: return@launch
        if (sessions.isEmpty()) {
            body.innerHTML = """<p class="sub">No sessions yet.</p>"""
            return@launch
        }
        body.innerHTML = """<div class="rows">""" + sessions.joinToString("") { sessionRowHtml(it) } + "</div>"
        forEachEl(body.querySelectorAll(".rw[data-session]")) { el ->
            val id = el.getAttribute("data-session") ?: return@forEachEl
            el.addEventListener("click") { App.navigate("/towo/session/$id") }
        }
    }
}

// ===== Session view (live) =====

private var sessionViewCurrentId: String? = null

fun renderTowoSession(container: Element, scope: CoroutineScope, sessionId: String) {
    sessionViewCurrentId = sessionId
    container.innerHTML = """
        <div class="towo">
          <div class="pagebar">
            <a href="#/towo" class="btn sm ghost">← Towo</a>
            <h1 id="towo-sess-title" style="margin-left:8px">Session</h1>
            <span class="spacer"></span>
            <span id="towo-sess-status"></span>
            <button id="towo-sess-interrupt" class="btn sm danger" style="display:none">Interrupt</button>
          </div>
          <div id="towo-sess-recovery"></div>
          <div id="towo-sess-approval"></div>
          <div class="card" id="towo-sess-transcript" style="padding:16px;min-height:200px;max-height:60vh;overflow-y:auto;font-size:.88rem;line-height:1.6"></div>
          <div class="row" style="gap:8px;margin-top:12px">
            <textarea id="towo-sess-composer" class="ta" placeholder="Message this session…" style="flex:1;min-height:44px" disabled></textarea>
            <button id="towo-sess-send" class="btn pri" disabled>Send</button>
          </div>
        </div>
    """.trimIndent()

    document.getElementById("towo-sess-interrupt")?.addEventListener("click") {
        scope.launch { TowoApi.interrupt(sessionId) }
    }
    document.getElementById("towo-sess-send")?.addEventListener("click") {
        val ta = document.getElementById("towo-sess-composer") as? HTMLTextAreaElement ?: return@addEventListener
        val text = ta.value.trim()
        if (text.isEmpty()) return@addEventListener
        ta.value = ""
        scope.launch { TowoApi.sendMessage(sessionId, text) }
    }

    scope.launch { loadSession(sessionId, scope) }
    connectTowoStream { event -> if (sessionViewCurrentId == sessionId) handleSessionEvent(sessionId, event, scope) }
}

private suspend fun loadSession(sessionId: String, scope: CoroutineScope) {
    val session = TowoApi.session(sessionId) ?: return
    updateSessionHeader(session)
    val messages = TowoApi.messages(sessionId)
    val transcript = document.getElementById("towo-sess-transcript") as? HTMLElement
    transcript?.innerHTML = messages.joinToString("") { renderMessageHtml(it) }
    transcript?.let { it.scrollTop = it.scrollHeight.toDouble() }

    val pending = TowoApi.pendingPermissions().firstOrNull { it.sessionId == sessionId }
    if (pending != null) showApprovalBanner(pending, scope) else clearApprovalBanner()
    showRecoveryIfNeeded(session, scope)
}

private fun updateSessionHeader(session: TowoSession) {
    (document.getElementById("towo-sess-title") as? HTMLElement)?.textContent =
        session.title ?: session.folderPath?.substringAfterLast('/') ?: session.id.take(8)
    (document.getElementById("towo-sess-status") as? HTMLElement)?.innerHTML = statusChipHtml(session.status, session.resumeAt)
    (document.getElementById("towo-sess-interrupt") as? HTMLElement)?.style?.display =
        if (session.status == "running") "" else "none"
    val idle = session.status == "idle"
    (document.getElementById("towo-sess-composer") as? HTMLTextAreaElement)?.disabled = !idle
    (document.getElementById("towo-sess-send") as? HTMLButtonElement)?.disabled = !idle
}

private fun showRecoveryIfNeeded(session: TowoSession, scope: CoroutineScope) {
    val el = document.getElementById("towo-sess-recovery") as? HTMLElement ?: return
    el.innerHTML = when (session.status) {
        "paused_quota" -> """
            <div class="card" style="padding:14px 16px;margin-bottom:12px;display:flex;align-items:center;gap:12px">
              <div style="flex:1">
                <div class="ttl" style="font-size:.92rem">Paused on quota</div>
                <div class="sub" style="font-size:.82rem">${session.resumeAt?.let { "Resumes ${formatEpochSec(it)}" } ?: "Waiting for the usage window to reset."}</div>
              </div>
              <label style="display:flex;align-items:center;gap:7px;font-size:.85rem;cursor:pointer">
                <span id="towo-arm-toggle" class="sw${if (session.continueAfterReset) " on" else ""}"></span>
                Continue automatically
              </label>
              <button id="towo-resume-now" class="btn sm">Resume now</button>
            </div>
        """.trimIndent()
        "stopped_max_turns" -> """
            <div class="card" style="padding:14px 16px;margin-bottom:12px;display:flex;align-items:center;gap:12px">
              <div style="flex:1"><div class="ttl" style="font-size:.92rem">Stopped at the turn cap</div><div class="sub" style="font-size:.82rem">Hit ${session.maxTurns} turns. Nothing is re-run — resuming continues with the same transcript.</div></div>
              <button id="towo-raise-cap" class="btn sm">Raise cap and resume</button>
            </div>
        """.trimIndent()
        "errored" -> """
            <div class="card" style="padding:14px 16px;margin-bottom:12px;display:flex;align-items:center;gap:12px">
              <div style="flex:1"><div class="ttl" style="font-size:.92rem">Session errored</div><div class="sub" style="font-size:.82rem">${(session.lastErrorSubtype ?: "").esc()}</div></div>
              <button id="towo-retry" class="btn sm">Retry</button>
            </div>
        """.trimIndent()
        else -> ""
    }
    document.getElementById("towo-arm-toggle")?.addEventListener("click") {
        val newState = !session.continueAfterReset
        scope.launch {
            TowoApi.updateSession(session.id, continueAfterReset = newState)
            loadSession(session.id, scope)
        }
    }
    document.getElementById("towo-resume-now")?.addEventListener("click") {
        scope.launch { TowoApi.resumeNow(session.id) }
    }
    document.getElementById("towo-raise-cap")?.addEventListener("click") {
        scope.launch {
            TowoApi.updateSession(session.id, maxTurns = session.maxTurns + 40)
            TowoApi.resumeNow(session.id, "Continue.")
        }
    }
    document.getElementById("towo-retry")?.addEventListener("click") {
        scope.launch { TowoApi.resumeNow(session.id) }
    }
}

private fun clearApprovalBanner() {
    (document.getElementById("towo-sess-approval") as? HTMLElement)?.innerHTML = ""
}

private fun showApprovalBanner(req: TowoPermissionRequest, scope: CoroutineScope) {
    val el = document.getElementById("towo-sess-approval") as? HTMLElement ?: return
    el.innerHTML = """
        <div class="card" style="padding:14px 16px;margin-bottom:12px;border-color:rgba(123,110,240,.5)">
          <div class="ttl" style="font-size:.92rem;margin-bottom:4px">${(req.title ?: req.displayName ?: req.toolName).esc()}</div>
          <div class="mono sub" style="font-size:.78rem;margin-bottom:10px;word-break:break-all">${req.inputJson.esc()}</div>
          <div style="display:flex;gap:8px">
            <button id="towo-approve-allow" class="btn sm pri">Allow</button>
            <button id="towo-approve-deny" class="btn sm danger">Deny</button>
          </div>
        </div>
    """.trimIndent()
    document.getElementById("towo-approve-allow")?.addEventListener("click") {
        scope.launch { TowoApi.decidePermission(req.id, "allow"); clearApprovalBanner() }
    }
    document.getElementById("towo-approve-deny")?.addEventListener("click") {
        scope.launch { TowoApi.decidePermission(req.id, "deny", "Denied via Towo"); clearApprovalBanner() }
    }
}

private fun handleSessionEvent(sessionId: String, event: JsonObject, scope: CoroutineScope) {
    val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
    val evtSessionId = event["sessionId"]?.jsonPrimitive?.contentOrNull
    when (type) {
        "message.raw" -> {
            if (evtSessionId != sessionId) return
            val msg = event["message"] ?: return
            val transcript = document.getElementById("towo-sess-transcript") as? HTMLElement ?: return
            transcript.insertAdjacentHTML("beforeend", renderMessageHtml(msg))
            transcript.scrollTop = transcript.scrollHeight.toDouble()
        }
        "session.status" -> {
            if (evtSessionId != sessionId) return
            scope.launch { loadSession(sessionId, scope) }
        }
        "permission.requested" -> {
            if (evtSessionId != sessionId) return
            val req = TowoPermissionRequest(
                id = event["requestId"]?.jsonPrimitive?.contentOrNull ?: return,
                sessionId = sessionId,
                toolName = event["toolName"]?.jsonPrimitive?.contentOrNull ?: "",
                inputJson = event["input"]?.toString() ?: "{}",
                title = event["title"]?.jsonPrimitive?.contentOrNull,
                displayName = event["displayName"]?.jsonPrimitive?.contentOrNull,
            )
            showApprovalBanner(req, scope)
        }
        "permission.resolved" -> clearApprovalBanner()
    }
}

/** Compact rendering of a raw SDKMessage — text blocks and a one-line tool-call summary (spec §C's
 *  "one-line tool cards"), never the full raw JSON except inside a collapsed <details>. */
private fun renderMessageHtml(raw: JsonElement): String {
    val obj = raw as? JsonObject ?: return ""
    return when (obj["type"]?.jsonPrimitive?.contentOrNull) {
        "assistant" -> {
            val content = obj["message"]?.jsonObject?.get("content")?.jsonArray ?: return ""
            buildString {
                for (block in content) {
                    val b = block as? JsonObject ?: continue
                    when (b["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> append("""<p style="margin:0 0 10px">${(b["text"]?.jsonPrimitive?.contentOrNull ?: "").esc()}</p>""")
                        "tool_use" -> {
                            val name = b["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                            append("""<div class="chip" style="margin:0 0 10px"><span class="dot"></span>$name</div>""")
                        }
                    }
                }
            }
        }
        "rate_limit_event" -> {
            val info = obj["rate_limit_info"]?.jsonObject ?: return ""
            val status = info["status"]?.jsonPrimitive?.contentOrNull ?: return ""
            if (status == "allowed") "" else """<p class="tiny muted" style="margin:0 0 10px">Quota: $status (${info["rateLimitType"]?.jsonPrimitive?.contentOrNull ?: ""})</p>"""
        }
        "result" -> """<p class="tiny muted" style="margin:0 0 10px">— session turn finished (${obj["subtype"]?.jsonPrimitive?.contentOrNull ?: "success"}) —</p>"""
        else -> ""
    }
}

// ===== Approvals =====

fun renderTowoApprovals(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="towo">
          <div class="pagebar"><h1>Approvals</h1></div>
          <div id="towo-approvals-body">Loading…</div>
        </div>
    """.trimIndent()
    scope.launch { loadApprovals(container, scope) }
}

private suspend fun loadApprovals(container: Element, scope: CoroutineScope) {
    val pending = TowoApi.pendingPermissions()
    val body = document.getElementById("towo-approvals-body") as? HTMLElement ?: return
    if (pending.isEmpty()) {
        body.innerHTML = """<p class="sub">Nothing needs your approval right now.</p>"""
        return
    }
    body.innerHTML = """<div class="rows">""" + pending.joinToString("") { req ->
        """
        <div class="card" style="padding:14px 16px">
          <div class="ttl" style="font-size:.92rem;margin-bottom:4px">${(req.title ?: req.displayName ?: req.toolName).esc()}</div>
          <div class="mono sub" style="font-size:.78rem;margin-bottom:10px;word-break:break-all">${req.inputJson.esc()}</div>
          <div style="display:flex;gap:8px">
            <button class="btn sm pri" data-allow="${req.id}">Allow</button>
            <button class="btn sm danger" data-deny="${req.id}">Deny</button>
            <a href="#/towo/session/${req.sessionId}" class="btn sm ghost">Open session</a>
          </div>
        </div>
        """.trimIndent()
    } + "</div>"
    forEachEl(body.querySelectorAll("[data-allow]")) { el ->
        val id = el.getAttribute("data-allow") ?: return@forEachEl
        el.addEventListener("click") { scope.launch { TowoApi.decidePermission(id, "allow"); loadApprovals(container, scope) } }
    }
    forEachEl(body.querySelectorAll("[data-deny]")) { el ->
        val id = el.getAttribute("data-deny") ?: return@forEachEl
        el.addEventListener("click") { scope.launch { TowoApi.decidePermission(id, "deny", "Denied via Towo"); loadApprovals(container, scope) } }
    }
}
