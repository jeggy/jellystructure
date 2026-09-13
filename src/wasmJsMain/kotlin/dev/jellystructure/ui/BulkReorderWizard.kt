@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.BulkPlanEpisode
import dev.jellystructure.api.BulkPlanResponse
import dev.jellystructure.api.MediaApi
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.TrackKind
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

// Phase 96 — Bulk track re-order across a series

fun renderBulkReorderWizard(container: Element, scope: CoroutineScope, mediaId: String) {
    container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""
    scope.launch {
        val item = MediaApi.get(mediaId)
        if (item == null || item.kind != MediaKind.TV_SHOW) {
            container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Series not found.</span>"""
            return@launch
        }
        // Suppress triage dock while wizard is active
        (document.getElementById("triage-dock") as? HTMLElement)?.style?.display = "none"
        val state = WizardState(item = item)
        state.targetOrder.addAll(detectInitialLanguages(item, "audio", "series"))
        renderStep1(container, scope, state)
    }
}

// ── State ─────────────────────────────────────────────────────────────────────

private class WizardState(val item: MediaItem) {
    var kind: String = "audio"          // "audio" | "subtitle"
    var wizScope: String = "series"     // "series" | "season-N"
    var setDefault: Boolean = false
    val targetOrder: MutableList<String> = mutableListOf()
    var plan: BulkPlanResponse? = null
    val optIn: MutableSet<String> = mutableSetOf()   // filenames opted-in from partial
    var showProposed: Boolean = true
    var sortByStatus: Boolean = false
}

private fun trackKindFor(kind: String) = if (kind == "audio") TrackKind.AUDIO else TrackKind.SUBTITLE

private fun scopedEpisodes(item: MediaItem, scope: String) =
    if (scope == "series") item.episodes
    else scope.removePrefix("season-").toIntOrNull()?.let { sn -> item.episodes.filter { it.seasonNumber == sn } } ?: item.episodes

private fun detectInitialLanguages(item: MediaItem, kind: String, scope: String): List<String> {
    val trackKind = trackKindFor(kind)
    val eps = scopedEpisodes(item, scope)
    val seen = linkedSetOf<String>()
    // Phase 209: a sidecar/external track can never actually be reordered (Phase 200 — no container
    // stream to edit), so seeding the target order from one only sets up Step 2 to reject it.
    for (ep in eps) for (t in ep.tracks) {
        if (t.kind == trackKind && !t.external && t.language != null) seen.add(t.language.lowercase())
    }
    return seen.toList()
}

// ── Shared wizard chrome ───────────────────────────────────────────────────────

private fun wizardHeader(item: MediaItem, step: Int): String {
    fun stepClass(n: Int) = when {
        n < step -> "done"
        n == step -> "active"
        else -> ""
    }
    return """
        <div style="display:flex;align-items:center;gap:16px;padding:0 0 20px;border-bottom:1px solid var(--border-soft);margin-bottom:24px;">
          <button id="wiz-back" class="btn sm ghost" style="flex-shrink:0;">‹ Back to ${item.title.esc()}</button>
          <span class="spacer"></span>
          <div class="row center" style="gap:4px;font-size:.8rem;">
            <span class="chip ${stepClass(1)}" style="border-radius:999px;${if (step == 1) "background:var(--hi);color:#fff;" else ""}">1 Set up</span>
            <span class="muted" style="margin:0 4px;">›</span>
            <span class="chip ${stepClass(2)}" style="border-radius:999px;${if (step == 2) "background:var(--hi);color:#fff;" else ""}">2 Review</span>
            <span class="muted" style="margin:0 4px;">›</span>
            <span class="chip ${stepClass(3)}" style="border-radius:999px;${if (step == 3) "background:var(--hi);color:#fff;" else ""}">3 Apply</span>
          </div>
          <span class="spacer"></span>
        </div>
    """.trimIndent()
}

private fun wireBackButton(container: Element, scope: CoroutineScope, state: WizardState) {
    (document.getElementById("wiz-back") as? HTMLElement)?.addEventListener("click") {
        // Restore triage dock
        (document.getElementById("triage-dock") as? HTMLElement)?.style?.removeProperty("display")
        App.navigate("/media/${state.item.id}")
    }
}

// ── Step 1: Set up ────────────────────────────────────────────────────────────

private fun renderStep1(container: Element, scope: CoroutineScope, state: WizardState) {
    val seasons = state.item.episodes.mapNotNull { it.seasonNumber }.distinct().sorted()
    val scopeOptions = buildString {
        append("""<option value="series"${if (state.wizScope == "series") " selected" else ""}>Whole series (${state.item.episodes.size} ep)</option>""")
        for (s in seasons) {
            val cnt = state.item.episodes.count { it.seasonNumber == s }
            val v = "season-$s"
            append("""<option value="$v"${if (state.wizScope == v) " selected" else ""}>Season $s ($cnt ep)</option>""")
        }
    }

    // Phase 209: if this scope has no embedded track of the selected kind anywhere, the wizard is
    // about to be a no-op — say so before Step 2 rather than after, since a sidecar file has no
    // container order to change (Phase 200) and every episode would otherwise land on an unexplained
    // "Nothing to do".
    val trackKind = trackKindFor(state.kind)
    val scopedEps = scopedEpisodes(state.item, state.wizScope)
    val hasEmbedded = scopedEps.any { ep -> ep.tracks.any { it.kind == trackKind && !it.external } }
    val externalOnlyCount = scopedEps.count { ep -> ep.tracks.any { it.kind == trackKind && it.external } }
    val sidecarOnlyWarning = !hasEmbedded && externalOnlyCount > 0
    val kindLabel = if (state.kind == "audio") "audio" else "subtitle"

    container.innerHTML = """
        ${wizardHeader(state.item, 1)}
        <h2 style="margin:0 0 20px;">↕ Re-order $kindLabel tracks across series</h2>

        ${if (sidecarOnlyWarning) """
        <div class="note" style="max-width:640px;margin-bottom:20px;border-color:var(--warn);font-size:.85rem;">
          ⚠ No embedded $kindLabel tracks in this scope — all $externalOnlyCount episode${if (externalOnlyCount != 1) "s" else ""}
          with $kindLabel here store ${if (state.kind == "audio") "it" else "them"} as sidecar files (e.g. <code>.srt</code>),
          which have no container stream order to change. Bulk re-order only affects tracks embedded in the
          video file — there is nothing this tool can do for this scope.
        </div>""" else ""}

        <div style="max-width:640px;display:flex;flex-direction:column;gap:20px;">

          <!-- Track type -->
          <div class="card" style="padding:18px 20px;">
            <div class="row center" style="margin-bottom:6px;">
              <span style="font-weight:600;font-size:.9rem;">Track type</span>
            </div>
            <div class="seg" id="wiz-kind-seg">
              <span ${if (state.kind == "audio") """class="on"""" else ""} data-kind="audio">Audio</span>
              <span ${if (state.kind == "subtitle") """class="on"""" else ""} data-kind="subtitle">Subtitles</span>
            </div>
          </div>

          <!-- Scope -->
          <div class="card" style="padding:18px 20px;">
            <div class="row center" style="margin-bottom:10px;">
              <span style="font-weight:600;font-size:.9rem;">Scope</span>
            </div>
            <select id="wiz-scope-sel" class="inp" style="max-width:280px;">$scopeOptions</select>
          </div>

          <!-- Target order -->
          <div class="card" style="padding:18px 20px;">
            <div class="row center" style="margin-bottom:12px;">
              <span style="font-weight:600;font-size:.9rem;">Target order</span>
              <span class="spacer"></span>
              <span class="tiny muted">Drag or use ▲▼ to arrange</span>
            </div>
            <div id="wiz-order-list" style="display:flex;flex-direction:column;gap:4px;" ondragover="return false;"></div>
            <button id="wiz-add-lang" class="btn sm ghost" style="margin-top:10px;">+ Add language</button>
            <div id="wiz-lang-input-row" style="display:none;margin-top:8px;gap:8px;" class="row">
              <input id="wiz-lang-input" class="inp" type="text" placeholder="e.g. fr, jpn, zh-CN" style="max-width:200px;">
              <button id="wiz-lang-confirm" class="btn sm">Add</button>
              <button id="wiz-lang-cancel" class="btn sm ghost">Cancel</button>
            </div>
            <div class="note" style="margin-top:12px;font-size:.78rem;color:var(--ink-soft);">
              Strays (unlisted languages) and untagged tracks → flagged for manual review, never auto-placed.
              Episodes missing some target languages are still reordered by the tracks they have.
            </div>
          </div>

          <!-- Set default -->
          <div class="card" style="padding:18px 20px;">
            <label class="row center" style="gap:10px;cursor:pointer;">
              <input type="checkbox" id="wiz-set-default" ${if (state.setDefault) "checked" else ""}>
              <span>Also set the default ${if (state.kind == "audio") "audio" else "subtitle"} track to #1 (<span id="wiz-first-lang">${state.targetOrder.firstOrNull()?.esc() ?: "—"}</span>)</span>
            </label>
          </div>

          <!-- Actions -->
          <div class="row center" style="gap:12px;">
            <span class="spacer"></span>
            <span class="tiny muted" id="wiz-next-note"></span>
            <button id="wiz-next" class="btn" style="min-width:140px;">Review plan →</button>
          </div>

        </div>
    """.trimIndent()

    wireBackButton(container, scope, state)
    renderOrderList(state)
    wireStep1Events(container, scope, state)
}

private fun renderOrderList(state: WizardState) {
    val list = document.getElementById("wiz-order-list") as? HTMLElement ?: return
    list.innerHTML = state.targetOrder.mapIndexed { i, lang ->
        """<div class="row center" draggable="true" data-lang="$lang" data-idx="$i"
              style="background:var(--surface);border:1px solid var(--border-soft);border-radius:6px;padding:8px 12px;gap:10px;cursor:grab;user-select:none;">
             <span style="color:var(--ink-dim);font-size:.85rem;cursor:grab;">⠿</span>
             <span class="badge" style="min-width:24px;text-align:center;border-radius:999px;">${i + 1}</span>
             <span style="font-weight:500;">${lang.esc()}</span>
             <span class="spacer"></span>
             <button class="btn sm ghost wiz-move-up" data-idx="$i" style="padding:2px 6px;" ${if (i == 0) "disabled" else ""}>▲</button>
             <button class="btn sm ghost wiz-move-dn" data-idx="$i" style="padding:2px 6px;" ${if (i == state.targetOrder.lastIndex) "disabled" else ""}>▼</button>
             <button class="btn sm ghost wiz-remove-lang" data-lang="$lang" style="padding:2px 6px;" ${if (state.targetOrder.size == 1) "disabled" else ""}>✕</button>
           </div>"""
    }.joinToString("")
    // update first-lang label
    (document.getElementById("wiz-first-lang") as? HTMLElement)?.textContent = state.targetOrder.firstOrNull() ?: "—"
}

private fun wireStep1Events(container: Element, scope: CoroutineScope, state: WizardState) {
    // Kind segmented control
    (document.getElementById("wiz-kind-seg") as? HTMLElement)?.addEventListener("click") { e ->
        val el = (e.target as? HTMLElement)?.closest("[data-kind]") as? HTMLElement ?: return@addEventListener
        val newKind = el.getAttribute("data-kind") ?: return@addEventListener
        if (newKind == state.kind) return@addEventListener
        state.kind = newKind
        state.targetOrder.clear()
        state.targetOrder.addAll(detectInitialLanguages(state.item, state.kind, state.wizScope))
        renderStep1(container, scope, state)
        wireStep1Events(container, scope, state)
    }

    // Scope selector — full re-render, not just the order list, so the Phase 209 sidecar-only
    // warning (computed per-scope in renderStep1) recomputes for the newly selected scope too.
    (document.getElementById("wiz-scope-sel") as? HTMLInputElement)?.addEventListener("change") { e ->
        val newScope = (e.target as? HTMLInputElement)?.value ?: return@addEventListener
        state.wizScope = newScope
        state.targetOrder.clear()
        state.targetOrder.addAll(detectInitialLanguages(state.item, state.kind, state.wizScope))
        renderStep1(container, scope, state)
        wireStep1Events(container, scope, state)
    }

    // Move up/down and remove — delegated on the list
    (document.getElementById("wiz-order-list") as? HTMLElement)?.addEventListener("click") { e ->
        val btn = (e.target as? HTMLElement)?.closest("button") as? HTMLElement ?: return@addEventListener
        when {
            btn.classList.contains("wiz-move-up") -> {
                val idx = btn.getAttribute("data-idx")?.toIntOrNull() ?: return@addEventListener
                if (idx > 0) { val tmp = state.targetOrder[idx]; state.targetOrder[idx] = state.targetOrder[idx - 1]; state.targetOrder[idx - 1] = tmp }
                renderOrderList(state)
            }
            btn.classList.contains("wiz-move-dn") -> {
                val idx = btn.getAttribute("data-idx")?.toIntOrNull() ?: return@addEventListener
                if (idx < state.targetOrder.lastIndex) { val tmp = state.targetOrder[idx]; state.targetOrder[idx] = state.targetOrder[idx + 1]; state.targetOrder[idx + 1] = tmp }
                renderOrderList(state)
            }
            btn.classList.contains("wiz-remove-lang") -> {
                val lang = btn.getAttribute("data-lang") ?: return@addEventListener
                if (state.targetOrder.size > 1) { state.targetOrder.remove(lang); renderOrderList(state) }
            }
        }
    }

    // Drag-to-reorder
    var dragIdx = -1
    (document.getElementById("wiz-order-list") as? HTMLElement)?.addEventListener("dragstart") { e ->
        val item = (e.target as? HTMLElement)?.closest("[data-idx]") as? HTMLElement ?: return@addEventListener
        dragIdx = item.getAttribute("data-idx")?.toIntOrNull() ?: -1
    }
    (document.getElementById("wiz-order-list") as? HTMLElement)?.addEventListener("drop") { e ->
        val target = (e.target as? HTMLElement)?.closest("[data-idx]") as? HTMLElement ?: return@addEventListener
        val dropIdx = target.getAttribute("data-idx")?.toIntOrNull() ?: return@addEventListener
        if (dragIdx >= 0 && dragIdx != dropIdx && dragIdx < state.targetOrder.size) {
            val moved = state.targetOrder.removeAt(dragIdx)
            state.targetOrder.add(dropIdx.coerceIn(0, state.targetOrder.size), moved)
            renderOrderList(state)
        }
        dragIdx = -1
    }

    // Add language
    (document.getElementById("wiz-add-lang") as? HTMLElement)?.addEventListener("click") {
        (document.getElementById("wiz-lang-input-row") as? HTMLElement)?.style?.display = "flex"
        (document.getElementById("wiz-lang-input") as? HTMLInputElement)?.focus()
    }
    (document.getElementById("wiz-lang-cancel") as? HTMLElement)?.addEventListener("click") {
        (document.getElementById("wiz-lang-input-row") as? HTMLElement)?.style?.display = "none"
        (document.getElementById("wiz-lang-input") as? HTMLInputElement)?.value?.let { }
    }
    (document.getElementById("wiz-lang-confirm") as? HTMLElement)?.addEventListener("click") {
        val inp = document.getElementById("wiz-lang-input") as? HTMLInputElement ?: return@addEventListener
        val lang = inp.value.trim().lowercase()
        if (lang.isNotEmpty() && lang !in state.targetOrder) {
            state.targetOrder.add(lang)
            renderOrderList(state)
        }
        inp.value = ""
        (document.getElementById("wiz-lang-input-row") as? HTMLElement)?.style?.display = "none"
    }

    // Set default checkbox
    (document.getElementById("wiz-set-default") as? HTMLInputElement)?.addEventListener("change") { e ->
        state.setDefault = (e.target as? HTMLInputElement)?.checked == true
    }

    // Next → Review
    (document.getElementById("wiz-next") as? HTMLElement)?.addEventListener("click") {
        if (state.targetOrder.isEmpty()) {
            (document.getElementById("wiz-next-note") as? HTMLElement)?.textContent = "Add at least one language to the target order."
            return@addEventListener
        }
        (document.getElementById("wiz-next") as? HTMLElement)?.apply { setAttribute("disabled", "true"); textContent = "Computing plan…" }
        scope.launch {
            val plan = MediaApi.bulkReorderPlan(state.item.id, state.kind, state.wizScope, state.targetOrder, state.setDefault)
            if (plan == null) {
                (document.getElementById("wiz-next") as? HTMLElement)?.apply { removeAttribute("disabled"); textContent = "Review plan →" }
                (document.getElementById("wiz-next-note") as? HTMLElement)?.textContent = "Plan request failed — check server."
                return@launch
            }
            state.plan = plan
            state.optIn.clear()
            renderStep2(container, scope, state)
        }
    }
}

// ── Step 2: Review ─────────────────────────────────────────────────────────────

private fun renderStep2(container: Element, scope: CoroutineScope, state: WizardState) {
    val plan = state.plan ?: return
    val kindLabel = if (state.kind == "audio") "audio" else "subtitle"
    val scopeLabel = if (state.wizScope == "series") "whole series" else "season ${state.wizScope.removePrefix("season-")}"

    val optInCount = plan.episodes.count { it.status == "partial" && it.filename in state.optIn }
    val applyCount = plan.willReorder + optInCount

    val estTotalMin = ((plan.episodes.filter { it.status == "will_reorder" || (it.status == "partial" && it.filename in state.optIn) }.sumOf { it.estSeconds } + 59) / 60).toInt()

    container.innerHTML = """
        ${wizardHeader(state.item, 2)}
        <div class="row center" style="margin-bottom:20px;gap:16px;flex-wrap:wrap;">
          <div>
            <h2 style="margin:0;">Review plan</h2>
            <div class="tiny muted" style="margin-top:4px;">$kindLabel · $scopeLabel · target: ${state.targetOrder.joinToString(" › ")}</div>
          </div>
          <span class="spacer"></span>
          <div class="seg" id="wiz-view-seg">
            <span ${if (!state.showProposed) """class="on"""" else ""} data-view="current">Current</span>
            <span ${if (state.showProposed) """class="on"""" else ""} data-view="proposed">Proposed</span>
          </div>
          <div class="seg" id="wiz-sort-seg">
            <span ${if (!state.sortByStatus) """class="on"""" else ""} data-sort="episode">Episode</span>
            <span ${if (state.sortByStatus) """class="on"""" else ""} data-sort="status">Status</span>
          </div>
        </div>

        <!-- Count cards -->
        <div class="row" style="gap:10px;flex-wrap:wrap;margin-bottom:${if (plan.remuxCount > 0) "0" else "20"}px;">
          ${bucketCard("will_reorder", "Will re-order", plan.willReorder, "ok")}
          ${bucketCard("already_correct", "Already correct", plan.alreadyCorrect, "info")}
          ${bucketCard("partial", "Partial — skipped", plan.partial, "warn")}
          ${bucketCard("needs_review", "Needs review", plan.needsReview, "bad")}
          ${bucketCard("nothing_to_do", "Nothing to do", plan.nothingToDo, "")}
        </div>

        <!-- Remux cost banner -->
        ${if (plan.remuxCount > 0) """
        <div class="note" style="margin:16px 0;font-size:.82rem;">
          ⏱ <b>${plan.remuxCount} episode${if (plan.remuxCount != 1) "s" else ""}</b> need a full ffmpeg <code>-c copy</code> remux
          (~$estTotalMin min estimated). Instant flag-only edits for any ★-default changes.
        </div>""" else ""}

        <!-- Episode table -->
        <div id="wiz-table-wrap" style="overflow-x:auto;margin-bottom:80px;">
          ${buildReviewTable(plan, state)}
        </div>

        <!-- Sticky footer -->
        <div style="position:sticky;bottom:0;background:var(--bg);border-top:1px solid var(--border-soft);padding:14px 0;display:flex;align-items:center;gap:16px;">
          <button id="wiz-back-step1" class="btn ghost">← Back</button>
          <span class="spacer"></span>
          <span class="tiny muted" id="wiz-footer-note">${applyCount} will re-order · ${plan.needsReview} need manual handling</span>
          <button id="wiz-apply-btn" class="btn" style="min-width:180px;" ${if (applyCount == 0) "disabled" else ""}>Apply to $applyCount episode${if (applyCount != 1) "s" else ""} →</button>
        </div>
    """.trimIndent()

    wireBackButton(container, scope, state)
    wireStep2Events(container, scope, state)
}

private fun bucketCard(status: String, label: String, count: Int, badgeKind: String): String {
    val badgeClass = if (badgeKind.isNotEmpty()) "badge $badgeKind" else "badge"
    val opacity = if (count == 0) "opacity:.45;" else ""
    return """<div class="card wiz-bucket-card" data-filter-status="$status" style="padding:12px 16px;cursor:pointer;min-width:110px;flex:1;$opacity">
      <div class="$badgeClass" style="margin-bottom:6px;">$count</div>
      <div style="font-size:.78rem;color:var(--ink-soft);">$label</div>
    </div>"""
}

private fun buildReviewTable(plan: BulkPlanResponse, state: WizardState): String {
    val episodes = if (state.sortByStatus) {
        val order = listOf("will_reorder", "partial", "needs_review", "already_correct", "nothing_to_do")
        plan.episodes.sortedWith(compareBy { order.indexOf(it.status) })
    } else plan.episodes

    val rows = episodes.joinToString("") { ep -> buildEpisodeRow2(ep, state) }
    return """
        <table style="width:100%;border-collapse:collapse;font-size:.83rem;">
          <thead>
            <tr style="border-bottom:2px solid var(--border-soft);text-align:left;">
              <th style="padding:8px 12px;white-space:nowrap;">Episode</th>
              <th style="padding:8px 12px;">Status</th>
              <th style="padding:8px 6px;">1st</th>
              <th style="padding:8px 6px;">2nd</th>
              <th style="padding:8px 6px;">3rd</th>
              <th style="padding:8px 6px;color:var(--ink-dim);">more</th>
              <th style="padding:8px 12px;"></th>
            </tr>
          </thead>
          <tbody id="wiz-ep-tbody">$rows</tbody>
        </table>
    """.trimIndent()
}

private fun buildEpisodeRow2(ep: BulkPlanEpisode, state: WizardState): String {
    val tracks = if (state.showProposed) ep.proposedOrder else ep.currentOrder
    val statusBadge = statusBadge(ep.status)
    val col = { idx: Int ->
        tracks.getOrNull(idx)?.let { t ->
            val stray = if (t.isStray) "style=\"color:var(--bad);font-weight:600;\"" else ""
            val lang = t.language ?: "⚠ und"
            val default = if (t.isDefault && state.showProposed) " ★" else ""
            """<span class="chip" $stray style="font-size:.75rem;">${lang.esc()}$default</span>"""
        } ?: ""
    }
    val moreCount = (tracks.size - 3).coerceAtLeast(0)
    val isOptIn = ep.status == "partial" && ep.filename in state.optIn

    val actionCell = when (ep.status) {
        "partial" -> """<button class="btn sm ${if (isOptIn) "" else "ghost"} wiz-opt-in-btn" data-filename="${ep.filename.esc()}" data-opt="${if (isOptIn) "in" else "out"}">
            ${if (isOptIn) "✓ Apply subset" else "Apply subset"}
          </button>"""
        "needs_review" -> """<a class="btn sm ghost" href="#/media/${state.item.id}?tab=tracks" title="Open per-episode editor">Open editor →</a>"""
        else -> ""
    }

    val bodyId = "wiz-epbody-${ep.filename.replace("[^a-zA-Z0-9]".toRegex(), "_")}"
    // Phase 209: the Reason panel used to be reachable only via the "+N" chip below, which only
    // exists when there are >3 tracks to show — so a "nothing_to_do" row with 0-2 tracks (exactly the
    // sidecar-only case) had no way to reveal why. The status cell itself is now always the toggle.
    val toggleReason = "var e=document.getElementById('$bodyId'); if(e){e.style.display = e.style.display==='none' ? '' : 'none';}"
    return """
        <tr class="wiz-ep-row" data-status="${ep.status}" style="border-bottom:1px solid var(--border-soft);">
          <td style="padding:8px 12px;white-space:nowrap;font-weight:500;">${ep.code.esc()}<br><span class="tiny muted">${(ep.title ?: "").esc().take(28)}</span></td>
          <td style="padding:8px 12px;cursor:pointer;" title="Show reason" onclick="$toggleReason">$statusBadge</td>
          <td style="padding:8px 6px;">${col(0)}</td>
          <td style="padding:8px 6px;">${col(1)}</td>
          <td style="padding:8px 6px;">${col(2)}</td>
          <td style="padding:8px 6px;">${if (moreCount > 0) """<span class="chip muted" style="font-size:.72rem;cursor:pointer;" onclick="$toggleReason">+$moreCount</span>""" else ""}</td>
          <td style="padding:8px 12px;text-align:right;">$actionCell</td>
        </tr>
        <tr id="$bodyId" style="display:none;background:var(--surface-alt);">
          <td colspan="7" style="padding:10px 20px;font-size:.79rem;color:var(--ink-soft);">
            <b>Reason:</b> ${ep.reason.esc()} &nbsp;·&nbsp;
            ${if (ep.estSeconds > 0) "<b>Est:</b> ~${ep.estSeconds.let { if (it < 60) "${it.toInt()}s" else "${(it / 60).toInt()}m ${(it % 60).toInt()}s" }} &nbsp;·&nbsp;" else ""}
            ${if (ep.remux) "<span class='chip'>remux</span> &nbsp;" else ""}
            <b>All tracks:</b> ${tracks.joinToString(" › ") { t ->
                val lang = t.language ?: "⚠ und"
                val default = if (t.isDefault && state.showProposed) " ★" else ""
                val stray = if (t.isStray) " <span style='color:var(--bad);'>(stray)</span>" else ""
                "${lang.esc()}$default$stray"
            }}
          </td>
        </tr>
    """.trimIndent()
}

private fun statusBadge(status: String): String = when (status) {
    "will_reorder" -> """<span class="badge ok">Will re-order</span>"""
    "already_correct" -> """<span class="badge info">Already correct</span>"""
    "partial" -> """<span class="badge warn">Partial</span>"""
    "needs_review" -> """<span class="badge bad">Needs review</span>"""
    "nothing_to_do" -> """<span class="badge">Nothing to do</span>"""
    else -> """<span class="badge">$status</span>"""
}

private fun wireStep2Events(container: Element, scope: CoroutineScope, state: WizardState) {
    // Back to step 1
    (document.getElementById("wiz-back-step1") as? HTMLElement)?.addEventListener("click") {
        renderStep1(container, scope, state)
    }

    // Current / Proposed toggle
    (document.getElementById("wiz-view-seg") as? HTMLElement)?.addEventListener("click") { e ->
        val el = (e.target as? HTMLElement)?.closest("[data-view]") as? HTMLElement ?: return@addEventListener
        state.showProposed = el.getAttribute("data-view") == "proposed"
        refreshTable(state)
    }

    // Episode / Status sort toggle
    (document.getElementById("wiz-sort-seg") as? HTMLElement)?.addEventListener("click") { e ->
        val el = (e.target as? HTMLElement)?.closest("[data-sort]") as? HTMLElement ?: return@addEventListener
        state.sortByStatus = el.getAttribute("data-sort") == "status"
        refreshTable(state)
    }

    // Bucket card filter (click to filter table rows)
    document.querySelectorAll(".wiz-bucket-card").let { cards ->
        for (i in 0 until cards.length) {
            (cards.item(i) as? HTMLElement)?.addEventListener("click") { e ->
                val filterStatus = (e.currentTarget as? HTMLElement)?.getAttribute("data-filter-status")
                document.querySelectorAll(".wiz-ep-row").let { rows ->
                    for (j in 0 until rows.length) {
                        val row = rows.item(j) as? HTMLElement ?: continue
                        row.style.display = if (filterStatus == null || row.getAttribute("data-status") == filterStatus) "" else "none"
                    }
                }
            }
        }
    }

    // Opt-in partial episodes
    (document.getElementById("wiz-ep-tbody") as? HTMLElement)?.addEventListener("click") { e ->
        val btn = (e.target as? HTMLElement)?.closest(".wiz-opt-in-btn") as? HTMLElement ?: return@addEventListener
        val filename = btn.getAttribute("data-filename") ?: return@addEventListener
        val wasIn = btn.getAttribute("data-opt") == "in"
        if (wasIn) state.optIn.remove(filename) else state.optIn.add(filename)
        refreshFooter(state)
        // Toggle button appearance
        btn.setAttribute("data-opt", if (wasIn) "out" else "in")
        if (wasIn) { btn.classList.remove("on"); btn.textContent = "Apply subset" }
        else { btn.classList.add("on"); btn.textContent = "✓ Apply subset" }
    }

    // Apply button
    (document.getElementById("wiz-apply-btn") as? HTMLElement)?.addEventListener("click") {
        val plan = state.plan ?: return@addEventListener
        val applyCount = plan.willReorder + plan.episodes.count { it.status == "partial" && it.filename in state.optIn }
        if (applyCount == 0) return@addEventListener
        (document.getElementById("wiz-apply-btn") as? HTMLElement)?.apply { setAttribute("disabled", "true"); textContent = "Starting…" }
        scope.launch {
            val jobId = MediaApi.startBulkReorder(
                state.item.id, state.kind, state.wizScope, state.targetOrder, state.setDefault,
                state.optIn.toList()
            )
            if (jobId == null) {
                (document.getElementById("wiz-apply-btn") as? HTMLElement)?.apply { removeAttribute("disabled"); textContent = "Apply failed — retry" }
                return@launch
            }
            renderStep3(container, scope, state, jobId, applyCount)
        }
    }
}

private fun refreshTable(state: WizardState) {
    val plan = state.plan ?: return
    (document.getElementById("wiz-ep-tbody") as? HTMLElement)?.innerHTML = (
        if (state.sortByStatus) {
            val order = listOf("will_reorder", "partial", "needs_review", "already_correct", "nothing_to_do")
            plan.episodes.sortedWith(compareBy { order.indexOf(it.status) })
        } else plan.episodes
    ).joinToString("") { buildEpisodeRow2(it, state) }
}

private fun refreshFooter(state: WizardState) {
    val plan = state.plan ?: return
    val applyCount = plan.willReorder + plan.episodes.count { it.status == "partial" && it.filename in state.optIn }
    (document.getElementById("wiz-footer-note") as? HTMLElement)?.textContent =
        "$applyCount will re-order · ${plan.needsReview} need manual handling"
    (document.getElementById("wiz-apply-btn") as? HTMLElement)?.apply {
        if (applyCount == 0) setAttribute("disabled", "true") else removeAttribute("disabled")
        textContent = "Apply to $applyCount episode${if (applyCount != 1) "s" else ""} →"
    }
}

// ── Step 3: Apply + progress + done summary ────────────────────────────────────

private fun renderStep3(container: Element, scope: CoroutineScope, state: WizardState, jobId: String, total: Int) {
    container.innerHTML = """
        ${wizardHeader(state.item, 3)}
        <div id="wiz-progress-wrap" style="max-width:600px;">
          <h2 style="margin:0 0 20px;">Applying…</h2>
          <div style="background:var(--surface);border-radius:8px;height:8px;overflow:hidden;margin-bottom:16px;">
            <div id="wiz-prog-bar" style="background:var(--hi);height:100%;width:0%;transition:width .3s;"></div>
          </div>
          <div class="row center" style="margin-bottom:8px;">
            <span id="wiz-prog-pct" style="font-weight:600;">0%</span>
            <span class="spacer"></span>
            <span id="wiz-prog-count" class="tiny muted">0 / $total</span>
          </div>
          <div id="wiz-prog-current" class="tiny muted" style="margin-bottom:20px;"></div>
          <div id="wiz-prog-log" style="display:flex;flex-direction:column;gap:4px;font-size:.79rem;max-height:300px;overflow-y:auto;"></div>
        </div>
        <div id="wiz-done-wrap" style="display:none;max-width:640px;">
          <h2 style="margin:0 0 8px;">Done</h2>
          <div id="wiz-done-summary" class="tiny muted" style="margin-bottom:20px;"></div>
          <div id="wiz-manual-list"></div>
          <div class="row center" style="gap:12px;margin-top:24px;">
            <button id="wiz-sync-jf" class="btn ghost">Sync Jellyfin ↻</button>
            <span class="spacer"></span>
            <button id="wiz-done-back" class="btn">← Back to series</button>
          </div>
          <div class="note" style="margin-top:16px;font-size:.78rem;color:var(--ink-soft);">
            Tip: fix the flagged episodes (tag untagged tracks, add missing languages) and re-run the wizard to fold them into the clean set.
          </div>
        </div>
    """.trimIndent()

    wireBackButton(container, scope, state)

    var done = 0
    var actualTotal = total // updated from the Started event so the bar reflects the real job size
    val failures = mutableListOf<Pair<String, String>>() // code → reason

    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val ws = WebSocket("$proto://${window.location.host}/ws")

    ws.onmessage = { ev ->
        val text = ev.data.toString()
        runCatching {
            // Simple parse without full deserialization — match on "type" field
            when {
                text.contains("\"started\"") || text.contains("\"Started\"") -> {
                    val t = extractJsonInt(text, "total")
                    if (t != null && t > 0) {
                        actualTotal = t
                        (document.getElementById("wiz-prog-count") as? HTMLElement)?.textContent = "$done / $actualTotal"
                    }
                }
                text.contains("\"file_done\"") || text.contains("\"FileDone\"") -> {
                    val ok = !text.contains("\"ok\":false")
                    val file = extractJsonString(text, "file")
                    val msg = extractJsonString(text, "msg")
                    done++
                    val pct = if (actualTotal > 0) (done * 100 / actualTotal) else 100
                    (document.getElementById("wiz-prog-bar") as? HTMLElement)?.setAttribute("style", "background:var(--hi);height:100%;width:${pct}%;transition:width .3s;")
                    (document.getElementById("wiz-prog-pct") as? HTMLElement)?.textContent = "$pct%"
                    (document.getElementById("wiz-prog-count") as? HTMLElement)?.textContent = "$done / $actualTotal"
                    (document.getElementById("wiz-prog-current") as? HTMLElement)?.textContent = "Done: ${file ?: ""}"
                    val logEl = document.getElementById("wiz-prog-log") as? HTMLElement
                    logEl?.insertAdjacentHTML("afterbegin",
                        """<div style="color:${if (ok) "var(--ok)" else "var(--bad)"};">${if (ok) "✓" else "✗"} ${(file ?: "").esc()}${if (!msg.isNullOrEmpty()) ": $msg" else ""}</div>"""
                    )
                    if (!ok) failures.add((file ?: "?") to (msg ?: "failed"))
                }
                text.contains("\"finished\"") || text.contains("\"Finished\"") -> {
                    ws.close()
                    val succeeded = extractJsonInt(text, "succeeded") ?: 0
                    val failed = extractJsonInt(text, "failed") ?: 0
                    showDone(container, scope, state, succeeded, failed, failures)
                }
            }
        }
    }

    ws.onerror = { _: Event ->
        ws.close()
        showDone(container, scope, state, done, 0, failures)
    }
}

private fun showDone(container: Element, scope: CoroutineScope, state: WizardState, succeeded: Int, failed: Int, failures: List<Pair<String, String>>) {
    val plan = state.plan
    (document.getElementById("wiz-progress-wrap") as? HTMLElement)?.style?.display = "none"
    val doneWrap = document.getElementById("wiz-done-wrap") as? HTMLElement ?: return
    doneWrap.style.display = "block"

    (document.getElementById("wiz-done-summary") as? HTMLElement)?.textContent =
        "$succeeded episode${if (succeeded != 1) "s" else ""} re-ordered successfully" +
        (if (failed > 0) " · $failed failed" else "")

    // Build manual handling list
    val manualItems = mutableListOf<Pair<String, String>>() // code → reason
    plan?.episodes?.forEach { ep ->
        when (ep.status) {
            "needs_review" -> manualItems.add(ep.code to ep.reason)
            "partial" -> if (ep.filename !in state.optIn) manualItems.add(ep.code to "missing languages: ${ep.reason}")
            "nothing_to_do" -> {} // not shown
            "already_correct" -> {} // not shown
        }
    }
    failures.forEach { (code, reason) -> if (manualItems.none { it.first == code }) manualItems.add(code to "failed: $reason") }

    val manualEl = document.getElementById("wiz-manual-list") as? HTMLElement ?: return
    if (manualItems.isEmpty()) {
        manualEl.innerHTML = """<div class="note" style="color:var(--ok);font-size:.85rem;">✓ No episodes need manual handling.</div>"""
    } else {
        manualEl.innerHTML = """
            <h4 style="margin:0 0 10px;">Needs manual handling (${manualItems.size})</h4>
            <div style="display:flex;flex-direction:column;gap:6px;">
              ${manualItems.joinToString("") { (code, reason) ->
                """<div class="card" style="padding:10px 14px;display:flex;align-items:center;gap:12px;">
                     <span style="font-weight:600;font-size:.85rem;">${code.esc()}</span>
                     <span class="tiny muted" style="flex:1;">${reason.esc()}</span>
                     <a class="btn sm ghost" href="#/media/${state.item.id}?tab=tracks">Open editor →</a>
                   </div>"""
              }}
            </div>
        """.trimIndent()
    }

    (document.getElementById("wiz-done-back") as? HTMLElement)?.addEventListener("click") {
        (document.getElementById("triage-dock") as? HTMLElement)?.style?.removeProperty("display")
        App.navigate("/media/${state.item.id}")
    }

    (document.getElementById("wiz-sync-jf") as? HTMLElement)?.addEventListener("click") {
        scope.launch {
            (document.getElementById("wiz-sync-jf") as? HTMLElement)?.textContent = "Syncing…"
            MediaApi.jellyfinRefresh(state.item.id)
            delay(1000)
            (document.getElementById("wiz-sync-jf") as? HTMLElement)?.textContent = "✓ Synced"
        }
    }
}

// ── JSON mini-parser helpers (avoids a full deserialization round-trip for WS events) ──

private fun extractJsonString(json: String, key: String): String? {
    val pattern = "\"$key\":\"".toRegex()
    val start = pattern.find(json)?.range?.last ?: return null
    val end = json.indexOf('"', start + 1).takeIf { it > start } ?: return null
    return json.substring(start + 1, end)
}

private fun extractJsonInt(json: String, key: String): Int? {
    val pattern = "\"$key\":".toRegex()
    val start = pattern.find(json)?.range?.last ?: return null
    val numStr = json.substring(start + 1).takeWhile { it.isDigit() }
    return numStr.toIntOrNull()
}
