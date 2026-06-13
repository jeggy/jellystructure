package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MediaApi
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

fun renderTrackOrder(container: Element, scope: CoroutineScope, mediaId: String) {
    container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""
    scope.launch {
        val item = MediaApi.get(mediaId)
        if (item == null) {
            container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Item not found.</span>"""
            return@launch
        }
        renderTrackOrderView(container, item, scope)
    }
}

private fun renderTrackOrderView(container: Element, item: MediaItem, scope: CoroutineScope) {
    val ext = item.path.substringAfterLast('.').lowercase()
    val isMkv = ext == "mkv"

    val audioTracks = item.tracks.filter { it.kind == TrackKind.AUDIO }
    val subTracks = item.tracks.filter { it.kind == TrackKind.SUBTITLE }
    val allEditable = audioTracks + subTracks

    val untaggedWarning = if (allEditable.any { it.language == null }) {
        """<div class="card" style="border-left:3px solid var(--warn,#f59e0b);padding:12px 16px;margin-bottom:14px;">
             <strong>Untagged track detected</strong> — tracks without a language tag cannot be placed by the cascade.
             Tag them in <a href="#" id="go-triage" style="color:inherit;text-decoration:underline;">Triage</a> first to include them in ordering.
           </div>"""
    } else ""

    val toolNote = if (isMkv) {
        """<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
             <span class="chip">tool: mkvpropedit</span>
             <span class="chip">est. ~40ms</span>
             <span class="chip">no re-encode</span>
             <span class="chip">POSIX perms kept</span>
           </div>"""
    } else {
        """<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
             <span class="chip">tool: ffmpeg -c copy</span>
             <span class="chip">slower remux</span>
             <span class="badge warn" style="font-size:.7rem;">MP4 — re-mux required</span>
           </div>"""
    }

    fun trackRowHtml(t: Track, kind: String): String {
        val langDisplay = t.language ?: """<span class="badge bad" style="font-size:.68rem;">untagged</span>"""
        val defaultBadge = if (t.default) """<span class="badge ok" style="font-size:.68rem;">default</span>""" else "—"
        return """<tr data-specifier="${t.specifier.esc()}">
                    <td class="num">${t.specifier.esc()}</td>
                    <td>$kind</td>
                    <td>${if (t.language != null) """<span class="lang">${t.language.esc()}</span>""" else langDisplay}</td>
                    <td class="num">${t.codec.esc()}</td>
                    <td>${t.title?.esc() ?: """<span class="muted">—</span>"""}</td>
                    <td>$defaultBadge</td>
                    <td>${if (isMkv) """<button class="btn sm ghost set-default-btn" data-specifier="${t.specifier.esc()}" data-id="${item.id.esc()}">Set default</button>""" else "—"}</td>
                  </tr>"""
    }

    val audioRows = audioTracks.joinToString("") { trackRowHtml(it, "audio") }
    val subRows = subTracks.joinToString("") { trackRowHtml(it, "subtitle") }

    container.innerHTML = """
        <div class="pagebar">
          <button id="back-btn" class="btn sm ghost">‹ ${item.title.esc()}</button>
          <h2>Track order</h2>
          <span class="muted">${item.title.esc()} (${item.year ?: "—"})</span>
          <span class="spacer"></span>
        </div>
        <p class="page-sub">Select a track to set as default. MKV header edits are instant; MP4 or physical reorders need a slower remux.</p>

        $untaggedWarning

        <div class="card">
          $toolNote
        </div>

        <div class="card">
          <h4 style="margin:0 0 12px;">Audio Tracks</h4>
          ${if (audioTracks.isEmpty()) """<span class="muted tiny">No audio tracks found.</span>""" else """
          <table class="wf-table">
            <tr><th>#</th><th>Kind</th><th>Lang</th><th>Codec</th><th>Title</th><th>Default</th><th>Action</th></tr>
            $audioRows
          </table>"""}
        </div>

        <div class="card">
          <h4 style="margin:0 0 12px;">Subtitle Tracks</h4>
          ${if (subTracks.isEmpty()) """<span class="muted tiny">No subtitle tracks found.</span>""" else """
          <table class="wf-table">
            <tr><th>#</th><th>Kind</th><th>Lang</th><th>Codec</th><th>Title</th><th>Default</th><th>Action</th></tr>
            $subRows
          </table>"""}
        </div>

        <div class="card" id="plan-card" style="display:none;">
          <div class="row center" style="margin-bottom:10px;">
            <h4 style="margin:0;">Command preview</h4>
            <span class="spacer"></span>
            <button id="apply-btn" class="btn primary">Apply</button>
          </div>
          <pre id="plan-command" class="log" style="font-size:.75rem;line-height:1.5;overflow:auto;"></pre>
          <div id="plan-meta" style="margin-top:8px;display:flex;gap:8px;"></div>
        </div>

        <div id="track-order-msg" style="display:none;margin-top:14px;"></div>
    """.trimIndent()

    document.getElementById("back-btn")?.addEventListener("click") {
        App.navigate("/media/${item.id}")
    }

    document.getElementById("go-triage")?.addEventListener("click") { e ->
        e.preventDefault()
        App.navigate("/triage")
    }

    var pendingSpecifier: String? = null

    container.querySelectorAll(".set-default-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            val specifier = btn.getAttribute("data-specifier") ?: continue
            btn.addEventListener("click") {
                pendingSpecifier = specifier
                scope.launch {
                    val plan = MediaApi.getTrackPlan(item.id, specifier)
                    val planCard = document.getElementById("plan-card") as? HTMLElement
                    val planCommand = document.getElementById("plan-command") as? HTMLElement
                    val planMeta = document.getElementById("plan-meta") as? HTMLElement
                    if (plan != null) {
                        planCard?.style?.display = "block"
                        planCommand?.textContent = plan.command
                        planMeta?.innerHTML = """
                            <span class="chip">${plan.tool}</span>
                            <span class="chip">~${plan.estimatedMs}ms</span>
                        """.trimIndent()
                        planCard?.scrollIntoView()
                    } else {
                        showTrackOrderMsg("Failed to get plan — is this an MKV file?", false)
                    }
                }
            }
        }
    }

    document.getElementById("apply-btn")?.addEventListener("click") {
        val spec = pendingSpecifier ?: return@addEventListener
        val applyBtn = document.getElementById("apply-btn") as? HTMLElement
        applyBtn?.setAttribute("disabled", "true")
        applyBtn?.textContent = "Applying…"
        scope.launch {
            val ok = MediaApi.setDefaultTrack(item.id, spec)
            showTrackOrderMsg(
                if (ok) "Default track updated successfully. Reloading…" else "Failed to apply — check server logs.",
                ok,
            )
            applyBtn?.removeAttribute("disabled")
            applyBtn?.textContent = "Apply"
            if (ok) {
                delay(800)
                App.navigate("/track-order?id=${item.id}")
            }
        }
    }
}

private fun showTrackOrderMsg(msg: String, ok: Boolean) {
    val el = document.getElementById("track-order-msg") as? HTMLElement ?: return
    el.style.display = "block"
    el.innerHTML = """<span class="badge ${if (ok) "ok" else "bad"}">$msg</span>"""
}
