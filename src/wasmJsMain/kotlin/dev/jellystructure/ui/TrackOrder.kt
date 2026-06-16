package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.encodeURIComponent
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.TrackSnap
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

fun renderTrackOrder(container: Element, scope: CoroutineScope, mediaId: String, episodeFilename: String? = null) {
    container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""
    scope.launch {
        val item = MediaApi.get(mediaId)
        if (item == null) {
            container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Item not found.</span>"""
            return@launch
        }
        if (episodeFilename != null) {
            val ep = item.episodes.firstOrNull { it.filename == episodeFilename }
            if (ep == null) {
                container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Episode not found.</span>"""
                return@launch
            }
            renderEpisodeTrackOrderView(container, item, ep, scope)
        } else {
            renderTrackOrderView(container, item, scope)
        }
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
        val defaultBadge = if (t.default) """<span class="badge ok" style="font-size:.68rem;">default</span>""" else "—"
        val langCell = if (t.language != null) {
            """<span class="lang">${t.language.esc()}</span>
               <button class="btn sm ghost set-lang-btn" style="margin-left:6px;font-size:.65rem;padding:1px 6px;"
                 data-specifier="${t.specifier.esc()}" data-current="${t.language.esc()}">edit</button>"""
        } else {
            """<span class="badge bad" style="font-size:.68rem;">untagged</span>
               <button class="btn sm set-lang-btn" style="margin-left:6px;font-size:.65rem;padding:1px 6px;"
                 data-specifier="${t.specifier.esc()}" data-current="">Tag</button>"""
        }
        val defaultAction = if (isMkv) """<button class="btn sm ghost set-default-btn" data-specifier="${t.specifier.esc()}" data-id="${item.id.esc()}">Set default</button>""" else "—"
        return """<tr data-specifier="${t.specifier.esc()}">
                    <td class="num">${t.specifier.esc()}</td>
                    <td>$kind</td>
                    <td>$langCell</td>
                    <td class="num">${t.codec.esc()}</td>
                    <td>${t.title?.esc() ?: """<span class="muted">—</span>"""}</td>
                    <td>$defaultBadge</td>
                    <td>$defaultAction</td>
                  </tr>
                  <tr class="lang-edit-row" data-for="${t.specifier.esc()}" style="display:none;">
                    <td colspan="7" style="padding:6px 12px 10px;background:var(--bg-2,#1a1a2e);">
                      <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
                        <span class="tiny muted">Language tag (BCP-47):</span>
                        <input class="input lang-input" style="width:90px;padding:3px 8px;font-size:.8rem;"
                          placeholder="e.g. en" value="${(t.language ?: "").esc()}"
                          data-specifier="${t.specifier.esc()}" data-id="${item.id.esc()}">
                        <code class="lang-preview mono tiny muted" style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;"></code>
                        <button class="btn sm lang-apply-btn" data-specifier="${t.specifier.esc()}" data-id="${item.id.esc()}">Apply</button>
                        <button class="btn sm ghost lang-cancel-btn" data-specifier="${t.specifier.esc()}">Cancel</button>
                      </div>
                    </td>
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

        <div class="card" id="diff-card" style="display:none;">
          <h4 style="margin:0 0 12px;">Track changes</h4>
          <div class="row" style="gap:16px;">
            <div class="col fill">
              <div class="muted tiny" style="margin-bottom:6px;">Before</div>
              <div id="diff-before"></div>
            </div>
            <div class="col fill">
              <div class="muted tiny" style="margin-bottom:6px;">After</div>
              <div id="diff-after"></div>
            </div>
          </div>
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
                    val diffCard = document.getElementById("diff-card") as? HTMLElement
                    val diffBefore = document.getElementById("diff-before") as? HTMLElement
                    val diffAfter = document.getElementById("diff-after") as? HTMLElement
                    if (plan != null) {
                        planCard?.style?.display = "block"
                        planCommand?.textContent = plan.command
                        planMeta?.innerHTML = """
                            <span class="chip">${plan.tool}</span>
                            <span class="chip">~${plan.estimatedMs}ms</span>
                        """.trimIndent()
                        if (plan.before.isNotEmpty()) {
                            diffCard?.style?.display = "block"
                            diffBefore?.innerHTML = plan.before.joinToString("") { snapRowHtml(it, plan.targetSpecifier) }
                            diffAfter?.innerHTML = plan.after.joinToString("") { snapRowHtml(it, plan.targetSpecifier) }
                        }
                        diffCard?.scrollIntoView()
                    } else {
                        showTrackOrderMsg("Failed to get plan — is this an MKV file?", false)
                    }
                }
            }
        }
    }

    // language edit: toggle expand row
    container.querySelectorAll(".set-lang-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            val specifier = btn.getAttribute("data-specifier") ?: continue
            btn.addEventListener("click") {
                val editRow = container.querySelector(".lang-edit-row[data-for='$specifier']") as? HTMLElement ?: return@addEventListener
                val isOpen = editRow.style.display != "none"
                if (isOpen) {
                    editRow.style.display = "none"
                } else {
                    editRow.style.display = ""
                    val input = editRow.querySelector(".lang-input") as? HTMLInputElement
                    input?.focus()
                    updateLangPreview(editRow, item.path, specifier, input?.value ?: "", isMkv)
                }
            }
        }
    }

    // language edit: live preview
    container.querySelectorAll(".lang-input").let { nodes ->
        for (i in 0 until nodes.length) {
            val input = nodes.item(i) as? HTMLInputElement ?: continue
            val specifier = input.getAttribute("data-specifier") ?: continue
            input.addEventListener("input") {
                val editRow = container.querySelector(".lang-edit-row[data-for='$specifier']") as? HTMLElement ?: return@addEventListener
                updateLangPreview(editRow, item.path, specifier, input.value, isMkv)
            }
        }
    }

    // language edit: cancel
    container.querySelectorAll(".lang-cancel-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            val specifier = btn.getAttribute("data-specifier") ?: continue
            btn.addEventListener("click") {
                val editRow = container.querySelector(".lang-edit-row[data-for='$specifier']") as? HTMLElement ?: return@addEventListener
                editRow.style.display = "none"
            }
        }
    }

    // language edit: apply
    container.querySelectorAll(".lang-apply-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val applyBtn = nodes.item(i) as? HTMLElement ?: continue
            val specifier = applyBtn.getAttribute("data-specifier") ?: continue
            val mediaId = applyBtn.getAttribute("data-id") ?: continue
            applyBtn.addEventListener("click") {
                val editRow = container.querySelector(".lang-edit-row[data-for='$specifier']") as? HTMLElement ?: return@addEventListener
                val input = editRow.querySelector(".lang-input") as? HTMLInputElement ?: return@addEventListener
                val lang = input.value.trim()
                if (lang.isBlank()) return@addEventListener
                applyBtn.setAttribute("disabled", "true")
                applyBtn.textContent = "Applying…"
                scope.launch {
                    val ok = MediaApi.setTrackLanguage(mediaId, specifier, lang)
                    applyBtn.removeAttribute("disabled")
                    applyBtn.textContent = "Apply"
                    showTrackOrderMsg(
                        if (ok) "Language set to '$lang' on $specifier. Reloading…" else "Failed to set language — check server logs.",
                        ok,
                    )
                    if (ok) {
                        delay(800)
                        App.navigate("/track-order?id=$mediaId")
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

private fun updateLangPreview(editRow: HTMLElement, filePath: String, specifier: String, lang: String, isMkv: Boolean) {
    val preview = editRow.querySelector(".lang-preview") as? HTMLElement ?: return
    if (lang.isBlank()) { preview.textContent = ""; return }
    val escaped = filePath.replace("'", "\\'")
    preview.textContent = if (isMkv) {
        "mkvpropedit '$escaped' --edit track:@? --set language=$lang"
    } else {
        "ffmpeg -i '$escaped' -map 0 -c copy -metadata:s:$specifier language=$lang ..."
    }
}

private fun renderEpisodeTrackOrderView(container: Element, item: MediaItem, ep: Episode, scope: CoroutineScope) {
    val ext = ep.path.substringAfterLast('.').lowercase()
    val isMkv = ext == "mkv"

    val audioTracks = ep.tracks.filter { it.kind == TrackKind.AUDIO }
    val subTracks = ep.tracks.filter { it.kind == TrackKind.SUBTITLE }

    val epCode = if (ep.seasonNumber != null && ep.episodeNumber != null) {
        "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
    } else ep.filename.substringBeforeLast('.')

    val toolNote = if (isMkv)
        """<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;"><span class="chip">tool: mkvpropedit</span><span class="chip">est. ~40ms</span><span class="chip">no re-encode</span></div>"""
    else
        """<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;"><span class="chip">tool: ffmpeg -c copy</span><span class="chip">slower remux</span><span class="badge warn" style="font-size:.7rem;">MP4 — re-mux required</span></div>"""

    fun trackRowHtml(t: Track, kind: String): String {
        val defaultBadge = if (t.default) """<span class="badge ok" style="font-size:.68rem;">default</span>""" else "—"
        val langCell = if (t.language != null) """<span class="lang">${t.language.esc()}</span>"""
                       else """<span class="badge bad" style="font-size:.68rem;">untagged</span>"""
        val defaultAction = if (isMkv) """<button class="btn sm ghost ep-set-default-btn" data-specifier="${t.specifier.esc()}">Set default</button>""" else "—"
        return """<tr data-specifier="${t.specifier.esc()}">
                    <td class="num">${t.specifier.esc()}</td><td>$kind</td><td>$langCell</td>
                    <td class="num">${t.codec.esc()}</td>
                    <td>${t.title?.esc() ?: """<span class="muted">—</span>"""}</td>
                    <td>$defaultBadge</td><td>$defaultAction</td>
                  </tr>"""
    }

    val audioRows = audioTracks.joinToString("") { trackRowHtml(it, "audio") }
    val subRows = subTracks.joinToString("") { trackRowHtml(it, "subtitle") }

    container.innerHTML = """
        <div class="pagebar">
          <button id="ep-back-btn" class="btn sm ghost">‹ ${item.title.esc()}</button>
          <h2>Track order</h2>
          <span class="muted">${epCode.esc()} ${ep.title?.esc() ?: ""}</span>
          <span class="spacer"></span>
        </div>
        <p class="page-sub">Select a track to set as default for this episode. MKV edits are instant; MP4 needs a remux.</p>
        <div class="card">$toolNote</div>
        <div class="card">
          <h4 style="margin:0 0 12px;">Audio Tracks</h4>
          ${if (audioTracks.isEmpty()) """<span class="muted tiny">No audio tracks found.</span>""" else """
          <table class="wf-table"><tr><th>#</th><th>Kind</th><th>Lang</th><th>Codec</th><th>Title</th><th>Default</th><th>Action</th></tr>$audioRows</table>"""}
        </div>
        <div class="card">
          <h4 style="margin:0 0 12px;">Subtitle Tracks</h4>
          ${if (subTracks.isEmpty()) """<span class="muted tiny">No subtitle tracks found.</span>""" else """
          <table class="wf-table"><tr><th>#</th><th>Kind</th><th>Lang</th><th>Codec</th><th>Title</th><th>Default</th><th>Action</th></tr>$subRows</table>"""}
        </div>
        <div class="card" id="ep-plan-card" style="display:none;">
          <div class="row center" style="margin-bottom:10px;">
            <h4 style="margin:0;">Command preview</h4>
            <span class="spacer"></span>
            <button id="ep-apply-btn" class="btn primary">Apply</button>
          </div>
          <pre id="ep-plan-command" class="log" style="font-size:.75rem;line-height:1.5;overflow:auto;"></pre>
        </div>
        <div id="ep-track-order-msg" style="display:none;margin-top:14px;"></div>
    """.trimIndent()

    document.getElementById("ep-back-btn")?.addEventListener("click") {
        App.navigate("/media/${item.id}")
    }

    var pendingSpecifier: String? = null

    container.querySelectorAll(".ep-set-default-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            val specifier = btn.getAttribute("data-specifier") ?: continue
            btn.addEventListener("click") {
                pendingSpecifier = specifier
                scope.launch {
                    val plan = MediaApi.getEpisodeTrackPlan(item.id, ep.filename, specifier)
                    val planCard = document.getElementById("ep-plan-card") as? HTMLElement
                    val planCommand = document.getElementById("ep-plan-command") as? HTMLElement
                    if (plan != null) {
                        planCard?.style?.display = "block"
                        planCommand?.textContent = plan.command
                        planCard?.scrollIntoView()
                    } else {
                        showEpTrackOrderMsg("Failed to get plan — is this an MKV file?", false)
                    }
                }
            }
        }
    }

    document.getElementById("ep-apply-btn")?.addEventListener("click") {
        val spec = pendingSpecifier ?: return@addEventListener
        val applyBtn = document.getElementById("ep-apply-btn") as? HTMLElement
        applyBtn?.setAttribute("disabled", "true")
        applyBtn?.textContent = "Applying…"
        scope.launch {
            val ok = MediaApi.setEpisodeDefaultTrack(item.id, ep.filename, spec)
            showEpTrackOrderMsg(
                if (ok) "Default track updated. Reloading…" else "Failed to apply — check server logs.",
                ok,
            )
            applyBtn?.removeAttribute("disabled")
            applyBtn?.textContent = "Apply"
            if (ok) {
                delay(800)
                App.navigate("/track-order?id=${item.id}&ep=${encodeURIComponent(ep.filename)}")
            }
        }
    }
}

private fun showEpTrackOrderMsg(msg: String, ok: Boolean) {
    val el = document.getElementById("ep-track-order-msg") as? HTMLElement ?: return
    el.style.display = "block"
    el.innerHTML = """<span class="badge ${if (ok) "ok" else "bad"}">$msg</span>"""
}

private fun snapRowHtml(snap: TrackSnap, targetSpecifier: String): String {
    val isTarget = snap.specifier == targetSpecifier
    val defaultMark = if (snap.isDefault) """<span class="badge ok" style="font-size:.65rem;padding:1px 5px;">●</span>""" else """<span class="muted tiny">○</span>"""
    val langBadge = snap.language?.let { """<span class="lang">${it.esc()}</span>""" }
        ?: """<span class="badge bad" style="font-size:.65rem;">?</span>"""
    val bg = if (isTarget) "background:var(--hi-soft,rgba(123,110,240,.1));border-radius:4px;" else ""
    return """<div style="display:flex;align-items:center;gap:5px;padding:3px 5px;margin-bottom:2px;$bg">
                $defaultMark
                <span class="mono tiny">${snap.specifier.esc()}</span>
                $langBadge
                <span class="muted tiny">${snap.codec.esc()}</span>
              </div>"""
}
