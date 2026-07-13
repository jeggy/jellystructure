package dev.jellystructure.ui

import dev.jellystructure.api.LiveTvApi
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.LiveTvChannelUpdate
import dev.jellystructure.shared.tv.LiveTvOverview
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

// Phase 147 — the Live TV admin page (design/app/livetv.html): connection/status, guide cadence, the
// new/unavailable lineup-change banner, and the channel lineup table (show/hide · number · order ·
// category). Everything here reads/writes jellystructure's own presentation overrides — the tuner and
// program guide themselves stay entirely in Jellyfin (non-goal, spec §intro).

private var ltvOverview: LiveTvOverview? = null
private var ltvChannels: List<LiveTvChannel> = emptyList()
private var ltvFilter: String = "all"
private var ltvSearch: String = ""

fun renderLiveTv(container: Element, scope: CoroutineScope) {
    container.innerHTML = """<div class="pagebar"><h1>Live TV</h1></div><p class="page-sub" style="color:var(--ink-soft)">Loading…</p>"""
    scope.launch {
        ltvOverview = LiveTvApi.sync()  // cheap (one Jellyfin GET) — always re-diff on page open, see LiveTvService.sync
        ltvChannels = LiveTvApi.channels()
        ltvFilter = "all"; ltvSearch = ""
        renderLiveTvFull(container, scope)
    }
}

private fun renderLiveTvFull(container: Element, scope: CoroutineScope) {
    val ov = ltvOverview
    container.innerHTML = """
    <style>
      .tvlogo { width:46px;height:32px;border-radius:6px;display:inline-flex;align-items:center;justify-content:center;
        color:#fff;font-weight:700;font-family:'Space Grotesk',sans-serif;font-size:13px;letter-spacing:.5px;flex:none;
        box-shadow:inset 0 0 0 1px rgba(255,255,255,.14);background:linear-gradient(135deg,#3b2a78,#15102e); }
      .lineup { width:100%;border-collapse:collapse; }
      .lineup th { text-align:left;font-size:.72rem;text-transform:uppercase;letter-spacing:.1em;color:var(--ink-soft);
        font-weight:600;padding:8px 10px;border-bottom:1px solid var(--line); }
      .lineup td { padding:10px;border-bottom:1px solid var(--line);vertical-align:middle; }
      .lineup tr:hover td { background:var(--hi-soft); }
      .lineup tr.off td { opacity:.5; }
      .lineup tr.removed td { opacity:.55;background:color-mix(in srgb,var(--bad) 10%,transparent); }
      .lineup .cnum { font-family:'JetBrains Mono',monospace;font-size:.9rem;color:var(--ink);width:54px; }
      .lineup .cname { font-weight:600;font-size:.95rem; }
      .lineup .ord { display:inline-flex;flex-direction:column;gap:1px; }
      .lineup .ord button { width:22px;height:16px;border:1px solid var(--line-2);background:var(--fill-2);color:var(--ink-soft);
        border-radius:5px;cursor:pointer;font-size:.6rem;line-height:1;display:flex;align-items:center;justify-content:center; }
      .lineup .ord button:hover { border-color:var(--hi);color:var(--hi); }
      .lineup .catinput { width:110px;font-size:.78rem;padding:5px 7px;border-radius:6px;border:1px solid var(--line-2);background:var(--fill-2);color:var(--ink); }
      .lineup .nameinput { flex:1;min-width:0;padding:6px 9px;border-radius:6px;border:1px solid var(--line-2);background:var(--fill-2);color:var(--ink); }
      .lineup .catinput:focus, .lineup .nameinput:focus { outline:none;border-color:var(--hi); }
      .ltv-filterrow { display:flex;align-items:center;gap:9px;flex-wrap:wrap;margin:14px 0 6px; }
      .ltv-filterrow .fchip { cursor:pointer; }
      .ltv-filterrow .fchip.on { background:var(--hi);color:#fff;border-color:transparent; }
      .ltv-searchwrap input { font:inherit;font-size:.85rem;padding:8px 12px;border-radius:9px;border:1px solid var(--line-2);
        background:var(--fill-2);color:var(--ink);width:200px; }
      .ltv-kv { display:flex;gap:26px;flex-wrap:wrap; }
      .ltv-kv .k { font-size:.72rem;text-transform:uppercase;letter-spacing:.1em;color:var(--ink-soft); }
      .ltv-kv .v { font-size:1.15rem;font-weight:700;font-family:'Space Grotesk',sans-serif;margin-top:2px; }
    </style>
    <div class="pagebar">
      <h1>Live TV</h1>
      <span class="spacer"></span>
      <span class="badge ${if (ov?.reachable == true) "ok" else "bad"}" title="Live TV is served by Jellyfin; jellystructure organizes how it appears in Ravilo.">
        ${if (ov?.reachable == true) "● connected via Jellyfin" else "● not reachable"}
      </span>
      <button id="ltv-refresh" class="btn sm ghost">⟲ Refresh from Jellyfin</button>
      <span class="chip" style="gap:9px"><span class="tiny muted">Live TV in Ravilo</span><span class="toggle${if (ov?.enabled == true) " on" else ""}" id="ltv-master-toggle"></span></span>
    </div>
    <p class="page-sub">Ravilo watches Live TV through Jellyfin — <b>tuners and the program guide are configured in Jellyfin</b>. Here you choose <b>which channels appear in Ravilo</b>, set their number, logo &amp; order, and how often the guide refreshes. Per-user access (who sees Live TV, and which channels) lives in the <a href="#/ravilo?tab=layout">Ravilo config</a>.</p>

    <div class="row" style="align-items:stretch;gap:16px;margin-top:6px">
      <div class="card fill">
        <div class="row center"><h3 style="margin:0;font-size:1.05rem">Source</h3></div>
        <hr class="dash" style="margin:11px 0">
        <div class="ltv-kv">
          <div><div class="k">Channels discovered</div><div class="v">${ov?.channelsDiscovered ?: 0}</div></div>
          <div><div class="k">Shown in Ravilo</div><div class="v">${ov?.channelsShown ?: 0}</div></div>
          <div><div class="k">Guide data</div><div class="v" style="font-size:.95rem">Jellyfin</div></div>
          <div><div class="k">Last synced</div><div class="v" style="font-size:.95rem">${lastSyncedLabel(ov?.lastSyncedAt ?: 0L)}</div></div>
        </div>
        <div class="note blue" style="margin-top:14px">The tuner (HDHomeRun / M3U) and EPG are managed in <b>Jellyfin ▸ Live TV</b>. Jellystructure reads the resulting channel list and guide — it never touches the tuner.</div>
      </div>
      <div class="card" style="width:340px">
        <h3 style="margin:0 0 4px;font-size:1.05rem">Guide</h3>
        <div class="field" style="margin-top:12px">
          <label>Refresh the guide every</label>
          <select class="select" id="ltv-cadence">
            <option value="15"${if (ov?.epgCadenceMinutes == 15) " selected" else ""}>15 minutes</option>
            <option value="60"${if (ov?.epgCadenceMinutes == 60 || ov == null) " selected" else ""}>1 hour</option>
            <option value="360"${if (ov?.epgCadenceMinutes == 360) " selected" else ""}>6 hours</option>
            <option value="1440"${if (ov?.epgCadenceMinutes == 1440) " selected" else ""}>Daily</option>
          </select>
          <span class="hint">How often jellystructure re-pulls now/next + the full schedule from Jellyfin's own guide.</span>
        </div>
      </div>
    </div>

    ${lineupBannerHtml()}

    <div class="card" style="margin-top:16px">
      <div class="row center"><h3 style="margin:0;font-size:1.05rem">Channel lineup</h3><span class="spacer"></span>
        <span class="ltv-searchwrap"><input id="ltv-search" type="search" placeholder="Search channels…" value="${ltvSearch.esc()}"></span>
      </div>
      <div class="ltv-filterrow" id="ltv-filters">
        <span class="muted tiny">show:</span>
        <span class="chip fchip${if (ltvFilter == "all") " on" else ""}" data-f="all">All</span>
        <span class="chip fchip${if (ltvFilter == "shown") " on" else ""}" data-f="shown">In Ravilo</span>
        <span class="chip fchip${if (ltvFilter == "hidden") " on" else ""}" data-f="hidden">Hidden</span>
        <span class="chip fchip${if (ltvFilter == "new") " on" else ""}" data-f="new">New</span>
        <span class="chip fchip${if (ltvFilter == "removed") " on" else ""}" data-f="removed">Unavailable</span>
      </div>
      <div class="tiny muted" style="margin:4px 0 12px">Drag order with ▲▼ · toggle to show a channel in Ravilo · category is a free-text label (Jellyfin doesn't provide one). Hidden channels stay in Jellyfin — they just don't appear on the TV.</div>
      <table class="lineup">
        <thead><tr><th style="width:44px">Order</th><th style="width:64px">In&nbsp;Ravilo</th><th style="width:60px">#</th><th>Channel</th><th style="width:130px">Category</th><th style="width:90px">Guide</th><th style="width:160px">Status</th></tr></thead>
        <tbody id="ltv-body"></tbody>
      </table>
      <div class="tiny muted" id="ltv-more" style="margin-top:12px"></div>
    </div>
    """.trimIndent()

    renderLineupBody()
    wireLiveTv(container, scope)
}

private fun lastSyncedLabel(epochMs: Long): String {
    if (epochMs <= 0L) return "never"
    return dev.jellystructure.formatRelativeAgo((epochMs / 1000).toString())
}

private fun lineupBannerHtml(): String {
    val ov = ltvOverview ?: return ""
    if (ov.newCount <= 0 && ov.unavailableCount <= 0) return ""
    return """
    <div class="note" id="ltv-banner" style="margin-top:16px;display:flex;gap:12px;align-items:center;background:var(--warn-soft);border-color:rgba(245,181,66,.4)">
      <span class="badge warn" style="flex:none">Lineup changed</span>
      <div class="tiny" style="flex:1;line-height:1.5"><b>${ov.newCount}</b> new channel${if (ov.newCount == 1) "" else "s"} appeared and <b>${ov.unavailableCount}</b> ${if (ov.unavailableCount == 1) "is" else "are"} no longer in Jellyfin since the last sync. New channels stay <b>hidden</b> until you show them, so your Ravilo layout never changes on its own.</div>
      ${if (ov.newCount > 0) """<button id="ltv-show-new" class="btn sm">Show new channels</button>""" else ""}
      ${if (ov.unavailableCount > 0) """<button id="ltv-remove-missing" class="btn sm ghost">Remove missing</button>""" else ""}
    </div>
    """.trimIndent()
}

private fun filteredChannels(): List<LiveTvChannel> {
    var list = ltvChannels.filter { c ->
        when (ltvFilter) {
            "shown" -> c.shown && !c.unavailable
            "hidden" -> !c.shown && !c.unavailable
            "new" -> c.isNew
            "removed" -> c.unavailable
            else -> true
        }
    }
    if (ltvSearch.isNotBlank()) {
        val q = ltvSearch.lowercase()
        list = list.filter { it.name.lowercase().contains(q) || it.number.toString().contains(q) }
    }
    return list
}

private fun statusCellHtml(c: LiveTvChannel): String = when {
    c.isNew -> """<span class="badge info">✦ New since sync</span>"""
    c.unavailable -> """<span class="badge bad">No longer in Jellyfin</span>"""
    else -> """<span class="tiny muted">${if (c.shown) "Shown" else "Hidden"}</span>"""
}

private fun renderLineupBody() {
    val body = document.getElementById("ltv-body") as? HTMLElement ?: return
    val list = filteredChannels()
    body.innerHTML = list.joinToString("") { c ->
        val rowCls = if (c.unavailable) "removed" else if (!c.shown) "off" else ""
        val toggle = if (c.unavailable) """<span class="tiny muted">—</span>"""
            else """<span class="toggle${if (c.shown) " on" else ""}" data-tg="${c.channelId}"></span>"""
        val logoUrl = c.logoUrl
        val logo = if (!logoUrl.isNullOrBlank())
            """<span class="tvlogo"><img src="${logoUrl.esc()}" alt="" style="width:100%;height:100%;object-fit:contain;border-radius:6px"></span>"""
        else """<span class="tvlogo">${c.name.take(3).uppercase().esc()}</span>"""
        val guideBadge = if (c.hasGuide) """<span class="badge ok" style="font-size:.62rem">EPG</span>"""
            else """<span class="badge warn" style="font-size:.62rem">no guide</span>"""
        val actions = if (c.unavailable) """<button class="btn sm" style="background:var(--bad);color:#fff;border-color:var(--bad)" data-rm="${c.channelId}">Remove from layout</button>"""
            else statusCellHtml(c)
        """<tr class="$rowCls" data-id="${c.channelId}">
             <td><span class="ord"><button data-up="${c.channelId}">▲</button><button data-dn="${c.channelId}">▼</button></span></td>
             <td>$toggle</td>
             <td class="cnum">${c.number}</td>
             <td><div class="row center" style="gap:11px">$logo<input class="cname nameinput" data-name="${c.channelId}" value="${c.name.esc()}" placeholder="Channel name"></div></td>
             <td><input class="catinput" data-cat="${c.channelId}" value="${c.category.esc()}" placeholder="Uncategorized"></td>
             <td>$guideBadge</td>
             <td>$actions</td>
           </tr>"""
    }
    val allShownCount = ltvChannels.count { !it.unavailable }
    (document.getElementById("ltv-more") as? HTMLElement)?.textContent =
        if (ltvFilter == "all" && ltvSearch.isBlank()) "Showing all $allShownCount channels Jellyfin currently provides · plus any flagged unavailable."
        else "${list.size} channel${if (list.size == 1) "" else "s"} match."
}

private fun wireLiveTv(container: Element, scope: CoroutineScope) {
    document.getElementById("ltv-refresh")?.addEventListener("click") { _ ->
        scope.launch {
            ltvOverview = LiveTvApi.sync()
            ltvChannels = LiveTvApi.channels()
            renderLiveTvFull(container, scope)
        }
    }
    document.getElementById("ltv-master-toggle")?.addEventListener("click") { _ ->
        scope.launch {
            val nowEnabled = !(ltvOverview?.enabled ?: false)
            ltvOverview = LiveTvApi.updateSettings(enabled = nowEnabled)
            renderLiveTvFull(container, scope)
        }
    }
    (document.getElementById("ltv-cadence") as? HTMLSelectElement)?.addEventListener("change") { _ ->
        val sel = document.getElementById("ltv-cadence") as? HTMLSelectElement ?: return@addEventListener
        val minutes = sel.value.toIntOrNull() ?: return@addEventListener
        scope.launch { ltvOverview = LiveTvApi.updateSettings(epgCadenceMinutes = minutes) }
    }
    document.getElementById("ltv-show-new")?.addEventListener("click") { _ ->
        scope.launch {
            ltvChannels = LiveTvApi.showNew()
            ltvOverview = LiveTvApi.overview()
            renderLiveTvFull(container, scope)
        }
    }
    document.getElementById("ltv-remove-missing")?.addEventListener("click") { _ ->
        scope.launch {
            ltvChannels = LiveTvApi.removeMissing()
            ltvOverview = LiveTvApi.overview()
            renderLiveTvFull(container, scope)
        }
    }
    container.querySelectorAll(".ltv-filterrow .fchip").let { chips ->
        for (i in 0 until chips.length) {
            val chip = chips.item(i) as? HTMLElement ?: continue
            chip.addEventListener("click") { _ ->
                ltvFilter = chip.getAttribute("data-f") ?: "all"
                renderLiveTvFull(container, scope)
            }
        }
    }
    (document.getElementById("ltv-search") as? HTMLInputElement)?.addEventListener("input") { _ ->
        ltvSearch = (document.getElementById("ltv-search") as? HTMLInputElement)?.value ?: ""
        renderLineupBody()
    }

    val body = document.getElementById("ltv-body") ?: return
    body.addEventListener("click") { ev ->
        val target = ev.target as? HTMLElement ?: return@addEventListener
        target.closest("[data-tg]")?.let { el ->
            val id = el.getAttribute("data-tg") ?: return@let
            val cur = ltvChannels.firstOrNull { it.channelId == id } ?: return@let
            scope.launch { ltvChannels = LiveTvApi.updateChannel(id, LiveTvChannelUpdate(shown = !cur.shown)); renderLineupBody() }
            return@addEventListener
        }
        target.closest("[data-rm]")?.let {
            // No dedicated single-channel remove endpoint — "Remove missing" drops every unavailable
            // override at once, which includes this row (removed rows are always unavailable).
            scope.launch {
                LiveTvApi.removeMissing()
                ltvChannels = LiveTvApi.channels()
                ltvOverview = LiveTvApi.overview()
                renderLiveTvFull(container, scope)
            }
            return@addEventListener
        }
        target.closest("[data-up]")?.let { el -> moveChannel(el.getAttribute("data-up"), -1, scope); return@addEventListener }
        target.closest("[data-dn]")?.let { el -> moveChannel(el.getAttribute("data-dn"), 1, scope); return@addEventListener }
    }
    body.addEventListener("change") { ev ->
        val t = ev.target as? HTMLInputElement ?: return@addEventListener
        t.getAttribute("data-cat")?.let { id ->
            scope.launch { ltvChannels = LiveTvApi.updateChannel(id, LiveTvChannelUpdate(category = t.value)) }
            return@addEventListener
        }
        t.getAttribute("data-name")?.let { id ->
            scope.launch { ltvChannels = LiveTvApi.updateChannel(id, LiveTvChannelUpdate(displayName = t.value)) }
        }
    }
}

private fun moveChannel(channelId: String?, direction: Int, scope: CoroutineScope) {
    if (channelId == null) return
    val ordered = ltvChannels.sortedBy { it.order }.map { it.channelId }.toMutableList()
    val i = ordered.indexOf(channelId)
    val j = i + direction
    if (i < 0 || j < 0 || j >= ordered.size) return
    val tmp = ordered[i]; ordered[i] = ordered[j]; ordered[j] = tmp
    scope.launch {
        ltvChannels = LiveTvApi.reorder(ordered)
        renderLineupBody()
    }
}
