package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.DetectedTrackerGroup
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.MetadataApi
import dev.jellystructure.api.MetadataEntry
import dev.jellystructure.api.TagsResponse
import dev.jellystructure.api.TrackerEntry
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

private val TAB_LABELS = listOf("studios", "networks", "genres", "tags", "trackers")

fun renderMetadata(container: Element, scope: CoroutineScope, initialTab: String = "studios") {
    val activeTab = if (initialTab in TAB_LABELS) initialTab else "studios"
    container.innerHTML = buildMetadataShell(activeTab)
    wireMetadataTabs(container, scope, activeTab)
    loadTab(container, scope, activeTab, "count")
}

private fun buildMetadataShell(activeTab: String): String {
    val tabs = TAB_LABELS.joinToString("") { tab ->
        val label = tab.replaceFirstChar { it.uppercase() }
        val active = if (tab == activeTab) " on" else ""
        """<span class="$active" data-tab="$tab">$label</span>"""
    }
    return """
        <div class="pagebar">
          <h1>Metadata</h1>
          <span class="spacer"></span>
          <input id="metadata-filter" class="input" type="search" placeholder="Filter…" autocomplete="off" style="width:160px;font-size:.85rem">
          <select id="metadata-sort" class="input" style="width:130px;font-size:.85rem">
            <option value="count">Most items</option>
            <option value="name">A–Z</option>
          </select>
        </div>
        <div class="tabs2" id="metadata-tabs">$tabs</div>
        <p class="page-sub">Browse the library by studio, network, genre and tag. Studios &amp; networks pull their logos from TMDB; tags you define here survive metadata re-syncs.</p>
        <div id="metadata-content"></div>
    """.trimIndent()
}

private fun applyFilter(container: Element) {
    val q = (container.querySelector("#metadata-filter") as? HTMLInputElement)?.value?.trim()?.lowercase() ?: ""
    val items = container.querySelectorAll("#metadata-content [data-filter-name]")
    var shown = 0
    for (i in 0 until items.length) {
        val el = items.item(i) as? HTMLElement ?: continue
        val name = el.getAttribute("data-filter-name") ?: ""
        val visible = q.isEmpty() || name.contains(q)
        el.style.display = if (visible) "" else "none"
        if (visible) shown++
    }
    // Show/hide the empty state message
    val empty = container.querySelector("#metadata-filter-empty") as? HTMLElement
    if (empty != null) empty.style.display = if (shown == 0 && q.isNotEmpty()) "block" else "none"
}

private fun wireMetadataTabs(container: Element, scope: CoroutineScope, activeTab: String) {
    val tabs = container.querySelectorAll("#metadata-tabs [data-tab]")
    for (i in 0 until tabs.length) {
        val btn = tabs.item(i) as? HTMLElement ?: continue
        btn.addEventListener("click") { _ ->
            val tab = btn.getAttribute("data-tab") ?: return@addEventListener
            for (j in 0 until tabs.length) {
                val b = tabs.item(j) as? HTMLElement ?: continue
                b.className = if (b.getAttribute("data-tab") == tab) "on" else ""
            }
            // Clear filter on tab switch
            (container.querySelector("#metadata-filter") as? HTMLInputElement)?.value = ""
            val sort = (container.querySelector("#metadata-sort") as? HTMLInputElement)?.value ?: "count"
            // Phase 28 — reflect the active tab in the URL so it is deep-linkable / Back-Forward works.
            dev.jellystructure.Router.updateQuery(mapOf("tab" to tab), replace = true)
            loadTab(container, scope, tab, sort)
        }
    }
    container.querySelector("#metadata-sort")?.addEventListener("change") { _ ->
        val tab = container.querySelector("#metadata-tabs [data-tab].on")?.getAttribute("data-tab") ?: activeTab
        val sort = (container.querySelector("#metadata-sort") as? HTMLInputElement)?.value ?: "count"
        loadTab(container, scope, tab, sort)
    }
    container.querySelector("#metadata-filter")?.addEventListener("input") { _ ->
        applyFilter(container)
    }
}

private fun loadTab(container: Element, scope: CoroutineScope, tab: String, sort: String) {
    val content = container.querySelector("#metadata-content") as? HTMLElement ?: return
    content.innerHTML = """<span class="muted tiny">Loading…</span>"""
    scope.launch {
        when (tab) {
            "studios" -> {
                val entries = MetadataApi.getStudios(sort)
                content.innerHTML = if (entries == null) errorHtml() else renderLogoGrid(entries, "studios", "/library?studios=")
                if (entries != null) wireLogoFetch(container, scope, "studios")
            }
            "networks" -> {
                val entries = MetadataApi.getNetworks(sort)
                content.innerHTML = if (entries == null) errorHtml() else renderLogoGrid(entries, "networks", "/library?networks=")
                if (entries != null) wireLogoFetch(container, scope, "networks")
            }
            "genres" -> {
                val entries = MetadataApi.getGenres(sort)
                content.innerHTML = if (entries == null) errorHtml() else renderGenreChips(entries)
            }
            "tags" -> {
                val tags = MetadataApi.getTags(sort)
                content.innerHTML = if (tags == null) errorHtml() else renderTagsTab(tags)
                if (tags != null) wireTagsTab(content, scope)
            }
            "trackers" -> {
                val trackers = MediaApi.getTrackers()
                val groups = MediaApi.getUnmappedTrackers()
                content.innerHTML = renderTrackersTab(trackers, groups)
                wireTrackersTab(content, scope, trackers, groups)
            }
        }
    }
}

private fun renderLogoGrid(entries: List<MetadataEntry>, kind: String, linkPrefix: String): String {
    val singularKind = if (kind == "studios") "studio" else "network"
    if (entries.isEmpty()) return """<p class="muted tiny">No ${singularKind}s found. Run a scan to populate.</p>"""
    val missingCount = entries.count { !it.hasLogo }
    return buildString {
        if (kind == "networks") {
            append("""<p class="hint" style="margin:0 0 14px">TMDB has no network search endpoint — networks are discovered only from the items already scanned. Logos are matched by network name; some may not resolve.</p>""")
        }
        append("""<div style="display:flex;align-items:center;gap:10px;margin-bottom:14px">""")
        append("""<span class="muted tiny">${entries.size} ${singularKind}s · $missingCount without logo</span>""")
        append("""<span style="flex:1"></span>""")
        append("""<button id="fetch-logos-btn" class="btn sm ghost" ${if (missingCount == 0) "disabled" else ""}>Fetch missing logos</button>""")
        append("""<span id="fetch-logos-status" style="font-size:.8rem;color:var(--ink-soft)"></span>""")
        append("</div>")
        append("""<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:16px" data-logo-kind="$kind">""")
        for (e in entries) {
            val encoded = dev.jellystructure.encodeURIComponent(e.name)
            val logoHtml = if (e.hasLogo) {
                """<img src="/api/metadata/$kind/$encoded/artwork" alt="${e.name}" style="max-width:90%;max-height:46px;object-fit:contain" loading="lazy">"""
            } else {
                """<span style="font-size:.72rem;font-weight:600;color:var(--ink-soft);text-align:center;padding:2px 4px;line-height:1.3">${e.name}</span>"""
            }
            append("""<a href="#$linkPrefix$encoded" data-filter-name="${e.name.lowercase()}" style="display:flex;align-items:center;gap:13px;padding:14px 15px;text-decoration:none;border-radius:var(--radius-s);background:var(--card-bg);border:var(--card-bd);box-shadow:var(--shadow-s);color:var(--ink);transition:transform .15s,box-shadow .15s,border-color .15s" onmouseover="this.style.transform='translateY(-2px)';this.style.boxShadow='var(--shadow)';this.style.borderColor='var(--hi)'" onmouseout="this.style.transform='';this.style.boxShadow='var(--shadow-s)';this.style.borderColor=''">""")
            append("""<div style="width:54px;height:54px;flex:none;border-radius:10px;display:flex;align-items:center;justify-content:center;background:rgba(160,152,255,.42);overflow:hidden">$logoHtml</div>""")
            append("""<div>""")
            append("""<span style="font-size:.9rem;font-weight:600;display:block">${e.name}</span>""")
            append("""<span class="tiny muted">${e.count} items</span>""")
            append("</div>")
            append("</a>")
        }
        append("</div>")
        append("""<p id="metadata-filter-empty" class="muted tiny" style="display:none;margin-top:16px">No matches.</p>""")
    }
}

private fun wireLogoFetch(container: Element, scope: CoroutineScope, kind: String) {
    val content = container.querySelector("#metadata-content") as? HTMLElement ?: return
    val btn = content.querySelector("#fetch-logos-btn") as? HTMLElement ?: return
    val statusEl = content.querySelector("#fetch-logos-status") as? HTMLElement ?: return
    btn.addEventListener("click") { _ ->
        btn.setAttribute("disabled", "")
        statusEl.textContent = "Fetching…"
        scope.launch {
            val result = MetadataApi.fetchLogoBatch(kind)
            if (result != null) {
                statusEl.textContent = "Done: ${result.fetched} fetched, ${result.skipped} already cached, ${result.failed} failed"
                val sort = (container.querySelector("#metadata-sort") as? HTMLInputElement)?.value ?: "count"
                loadTab(container, scope, kind, sort)
            } else {
                statusEl.textContent = "Failed — check server connection."
                btn.removeAttribute("disabled")
            }
        }
    }
}

private fun renderGenreChips(entries: List<MetadataEntry>): String {
    if (entries.isEmpty()) return """<p class="muted tiny">No genres found.</p>"""
    return buildString {
        append("""<div style="display:flex;flex-wrap:wrap;gap:8px">""")
        for (e in entries) {
            val encoded = dev.jellystructure.encodeURIComponent(e.name)
            append("""<a href="#/library?genres=$encoded" data-filter-name="${e.name.lowercase()}" style="text-decoration:none"><span style="display:inline-flex;align-items:center;gap:6px;padding:6px 14px;border-radius:99px;background:var(--fill-3);border:1px solid var(--line-2);font-size:.85rem;color:var(--ink);cursor:pointer;transition:background .12s,border-color .12s" onmouseover="this.style.background='var(--hi-soft)';this.style.borderColor='var(--hi)'" onmouseout="this.style.background='var(--fill-3)';this.style.borderColor='var(--line-2)'">${e.name}<span style="font-size:.75rem;color:var(--ink-soft)">${e.count}</span></span></a>""")
        }
        append("</div>")
        append("""<p id="metadata-filter-empty" class="muted tiny" style="display:none;margin-top:16px">No matches.</p>""")
    }
}

private fun renderTagsTab(data: TagsResponse): String = buildString {
    append("""<p class="hint" style="margin:0 0 16px">Jellystructure tags are structured labels you define here with a color and description. They survive metadata re-syncs — when pulling fresh data from TMDB, Jellystructure tags on an item are always preserved. All other tags come from TMDB or were added manually and may be overwritten on resync.</p>""")
    append("""<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px"><h3 style="font-size:.95rem;margin:0">Jellystructure Tags</h3><button id="new-tag-btn" class="btn sm">+ New tag</button></div>""")
    if (data.jsTags.isEmpty()) {
        append("""<p class="muted tiny" style="margin-bottom:20px">No tags defined yet.</p>""")
    } else {
        append("""<div style="display:flex;flex-wrap:wrap;gap:12px;margin-bottom:20px">""")
        for (tag in data.jsTags) {
            append("""<div class="card tag-card js-tag-card" data-name="${tag.name}" data-color="${tag.color}" data-desc="${tag.description.replace("\"", "&quot;")}" data-filter-name="${tag.name.lowercase()}" style="min-width:220px;transition:border-color .12s,box-shadow .12s" onmouseover="this.style.borderColor='var(--hi)';this.style.boxShadow='var(--shadow)'" onmouseout="this.style.borderColor='';this.style.boxShadow='var(--shadow-s)'">""")
            append("""<span class="tag-dot-lg" style="background:${tag.color};box-shadow:0 0 0 2px ${tag.color}33"></span>""")
            append("""<span style="flex:1;min-width:0;display:flex;flex-direction:column;gap:1px">""")
            append("""<span style="color:var(--ink);font-weight:600">${tag.name}</span>""")
            if (tag.description.isNotBlank()) {
                append("""<span class="tiny muted" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:200px">${tag.description}</span>""")
            }
            append("</span>")
            val enc102 = dev.jellystructure.encodeURIComponent(tag.name)
            append("""<a href="#/library?tags=$enc102" onclick="event.stopPropagation()" class="badge info" style="flex-shrink:0;cursor:pointer;text-decoration:none">${tag.count}</a>""")
            append("</div>")
        }
        append("</div>")
    }
    if (data.otherTags.isNotEmpty()) {
        append("""<h3 style="font-size:.9rem;font-weight:600;margin:0 0 10px;color:var(--ink-soft);text-transform:uppercase;letter-spacing:.06em">Other tags</h3>""")
        append("""<div style="display:flex;flex-wrap:wrap;gap:6px">""")
        for (tag in data.otherTags) {
            val encOther = dev.jellystructure.encodeURIComponent(tag.name)
            append("""<a href="#/library?tags=$encOther" style="display:inline-flex;align-items:center;gap:5px;padding:4px 10px;border-radius:99px;background:var(--fill-2);border:1px solid var(--line);font-size:.8rem;color:var(--ink);text-decoration:none;cursor:pointer">${tag.name}<span style="font-size:.72rem;color:var(--ink-soft)">${tag.count}</span></a>""")
        }
        append("</div>")
    }
    append("""<p id="metadata-filter-empty" class="muted tiny" style="display:none;margin-top:16px">No matches.</p>""")
    // Modal placeholder
    append("""<div id="tag-modal" style="display:none;position:fixed;inset:0;z-index:200;background:rgba(0,0,0,.45);align-items:center;justify-content:center"></div>""")
}

private fun wireTagsTab(content: HTMLElement, scope: CoroutineScope) {
    content.querySelector("#new-tag-btn")?.addEventListener("click") { _ ->
        showTagModal(content, scope, null)
    }
    val cards = content.querySelectorAll(".js-tag-card")
    for (i in 0 until cards.length) {
        val card = cards.item(i) as? HTMLElement ?: continue
        card.addEventListener("click") { _ ->
            val name = card.getAttribute("data-name") ?: return@addEventListener
            val color = card.getAttribute("data-color")
            val desc = card.getAttribute("data-desc")
            showTagModal(content, scope, name, color, desc)
        }
    }
}

private val PRESET_COLORS = listOf("#7b6ef0", "#2dd49a", "#f5b542", "#3fb6f5", "#ff6f61", "#b15cd0", "#6b7280", "#e0639a")

private fun showTagModal(content: HTMLElement, scope: CoroutineScope, editName: String?, editColor: String? = null, editDesc: String? = null) {
    val modal = content.querySelector("#tag-modal") as? HTMLElement ?: return
    val isNew = editName == null
    val initialColor = editColor?.takeIf { it.isNotBlank() } ?: "#7b6ef0"
    val initialDesc = editDesc?.takeIf { it.isNotBlank() } ?: ""
    val title = if (isNew) "New tag" else "Edit tag"
    modal.style.display = "flex"
    val swatches = PRESET_COLORS.joinToString("") { color ->
        """<span class="swatch color-swatch" data-color="$color" style="background:$color" title="$color"></span>"""
    }
    val libLink = if (!isNew) {
        val enc = dev.jellystructure.encodeURIComponent(editName!!)
        """<a href="#/library?tags=$enc" class="btn sm ghost" id="modal-lib-btn">View in library →</a>"""
    } else ""
    modal.innerHTML = """
        <div style="background:var(--card-bg);border-radius:10px;padding:24px;width:340px;max-width:calc(100vw - 32px);box-shadow:0 8px 32px rgba(0,0,0,.35);border:1px solid var(--line-2)">
          <h3 style="margin:0 0 16px;font-size:1rem">$title</h3>
          <div class="field">
            <label>Name</label>
            <input id="modal-tag-name" class="input" type="text" placeholder="tag name" style="width:100%" ${if (!isNew) "value=\"${editName!!.replace("\"", "&quot;")}\" readonly" else ""}>
          </div>
          <div class="field">
            <label>Color</label>
            <div id="color-swatches" class="swatches">$swatches</div>
            <input id="modal-tag-color" type="hidden" value="$initialColor">
          </div>
          <div class="field">
            <label>Description</label>
            <input id="modal-tag-desc" class="input" type="text" placeholder="optional description" style="width:100%" value="${initialDesc.replace("\"", "&quot;")}">
          </div>
          <div id="modal-tag-error" class="badge bad" style="display:none;margin-bottom:10px"></div>
          <div style="display:flex;flex-wrap:wrap;gap:8px;justify-content:space-between;align-items:center">
            <div style="display:flex;gap:8px;align-items:center">
              ${if (!isNew) """<button id="modal-delete-btn" class="btn sm ghost" style="color:var(--bad);border-color:var(--bad)">Delete</button>""" else ""}
              $libLink
            </div>
            <div style="display:flex;gap:8px;align-items:center">
              <button id="modal-cancel-btn" class="btn sm ghost">Cancel</button>
              <button id="modal-save-btn" class="btn sm primary">${if (isNew) "Create" else "Save"}</button>
            </div>
          </div>
        </div>
    """.trimIndent()

    // Wire color swatches
    val swatchEls = modal.querySelectorAll(".color-swatch")
    fun selectSwatch(color: String) {
        (modal.querySelector("#modal-tag-color") as? HTMLInputElement)?.value = color
        for (i in 0 until swatchEls.length) {
            val s = swatchEls.item(i) as? HTMLElement ?: continue
            s.classList.toggle("on", s.getAttribute("data-color") == color)
        }
    }
    selectSwatch(initialColor)

    // Wire "View in library" link — navigate via SPA router and close modal
    modal.querySelector("#modal-lib-btn")?.addEventListener("click") { ev ->
        ev.preventDefault()
        modal.style.display = "none"
        val href = (ev.target as? HTMLElement)?.getAttribute("href") ?: return@addEventListener
        App.navigate(href.removePrefix("#"))
    }
    for (i in 0 until swatchEls.length) {
        val s = swatchEls.item(i) as? HTMLElement ?: continue
        s.addEventListener("click") { _ -> selectSwatch(s.getAttribute("data-color") ?: "#6b7280") }
    }

    modal.querySelector("#modal-cancel-btn")?.addEventListener("click") { _ -> modal.style.display = "none" }
    modal.addEventListener("click") { ev ->
        if ((ev.target as? Element) == modal) modal.style.display = "none"
    }

    modal.querySelector("#modal-save-btn")?.addEventListener("click") { _ ->
        scope.launch {
            val name = (modal.querySelector("#modal-tag-name") as? HTMLInputElement)?.value?.trim() ?: ""
            val color = (modal.querySelector("#modal-tag-color") as? HTMLInputElement)?.value ?: "#6b7280"
            val desc = (modal.querySelector("#modal-tag-desc") as? HTMLInputElement)?.value?.trim() ?: ""
            val errEl = modal.querySelector("#modal-tag-error") as? HTMLElement
            if (name.isBlank()) { errEl?.let { it.style.display = "block"; it.textContent = "Name is required" }; return@launch }
            val ok = if (isNew) {
                MetadataApi.createTag(name, color, desc) != null
            } else {
                MetadataApi.updateTag(name, color, desc) != null
            }
            if (!ok) { errEl?.let { it.style.display = "block"; it.textContent = if (isNew) "Tag name already exists" else "Save failed" }; return@launch }
            modal.style.display = "none"
            // Reload tags tab
            val sort = (content.querySelector("#metadata-sort") as? HTMLInputElement)?.value ?: "count"
            val tags = MetadataApi.getTags(sort)
            content.innerHTML = if (tags == null) errorHtml() else renderTagsTab(tags)
            if (tags != null) wireTagsTab(content, scope)
        }
    }

    modal.querySelector("#modal-delete-btn")?.addEventListener("click") { _ ->
        if (editName != null && window.confirm("Delete tag \"$editName\"? The tag will be removed from the definition list. Items that have it will keep the tag string until their next sync.")) {
            scope.launch {
                MetadataApi.deleteTag(editName)
                modal.style.display = "none"
                val sort = (content.querySelector("#metadata-sort") as? HTMLInputElement)?.value ?: "count"
                val tags = MetadataApi.getTags(sort)
                content.innerHTML = if (tags == null) errorHtml() else renderTagsTab(tags)
                if (tags != null) wireTagsTab(content, scope)
            }
        }
    }
}

private fun errorHtml() = """<span class="badge bad">Failed to load — check server connection.</span>"""

// ---------- Phase 98: Tracker registry tab ----------

private fun renderTrackersTab(trackers: List<TrackerEntry>, groups: List<DetectedTrackerGroup>): String = buildString {
    append("""<div class="note blue" style="margin-bottom:18px;">A torrent announces to one <b>tracker</b>, but that tracker often exposes several announce URLs (mirrors). Define each tracker here with a name and the set of announce <b>hosts</b> that belong to it — the seeding view resolves every torrent to a single named tracker, regardless of which mirror URL it carries.</div>""")
    append("""<div class="row center" style="margin-bottom:12px;"><h3 style="margin:0;font-size:1.15rem;">Trackers</h3>""")
    if (trackers.isNotEmpty()) append("""<span class="muted tiny" style="margin-left:10px;">${trackers.size} defined</span>""")
    append("""<span class="spacer"></span><button id="new-tracker-btn" class="btn sm primary">＋ New tracker</button></div>""")
    if (trackers.isEmpty()) {
        append("""<p class="muted tiny">No trackers defined yet. Name an auto-detected host below or add one manually.</p>""")
    } else {
        append("""<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:14px;">""")
        trackers.forEach { t -> append(trackerCardHtml(t)) }
        append("</div>")
    }
    if (groups.isNotEmpty()) {
        append("""<h3 style="margin:28px 0 10px;font-size:1.05rem;">Unmapped announce hosts <span class="tiny muted" style="font-weight:400;">— seen in torrents but not yet assigned to a tracker</span></h3>""")
        append("""<div class="col" style="gap:8px;">""")
        groups.forEach { g ->
            val hostsAttr = g.hosts.joinToString("|") { it.esc() }
            val hostsDisplay = if (g.hosts.size == 1) {
                """<span class="host-name">${g.hosts.first().esc()}</span>"""
            } else {
                """<span class="host-name" style="display:flex;flex-direction:column;gap:2px">${g.hosts.joinToString("") { """<span>${it.esc()}</span>""" }}</span>"""
            }
            append("""<div class="unmapped-row" data-hosts="$hostsAttr">""")
            append(hostsDisplay)
            append("""<span class="tiny muted">seen in <b>${g.torrentCount}</b> torrent${if (g.torrentCount != 1) "s" else ""}</span>""")
            append("""<span class="spacer"></span>""")
            if (trackers.isNotEmpty()) {
                append("""<select class="input trk-assign-select" style="font-size:.78rem;padding:2px 8px;height:auto;width:auto" data-hosts="$hostsAttr">""")
                append("""<option value="">Assign to…</option>""")
                trackers.forEach { t -> append("""<option value="${t.name.esc()}">${t.name.esc()}</option>""") }
                append("</select>")
            }
            append("""<button class="btn sm unmapped-name-btn" data-hosts="$hostsAttr">＋ Name as new tracker</button>""")
            append("</div>")
        }
        append("</div>")
    }
}

private fun trackerCardHtml(t: TrackerEntry): String = buildString {
    val encName = dev.jellystructure.encodeURIComponent(t.name)
    val privClass = if (t.isPrivate) "private" else "public"
    append("""<div class="card trk-card" data-tracker="${t.name.esc()}">""")
    // Header row
    append("""<div class="trk-top">""")
    append("""<span class="nm">${t.name.esc()}</span>""")
    append("""<span class="pvt-badge $privClass">$privClass</span>""")
    append("""<span class="spacer"></span>""")
    append("""<button class="btn sm ghost edit-tracker-btn">Edit</button>""")
    append("</div>")
    // Inline host list using design classes
    append("""<div class="host-list">""")
    if (t.hosts.isEmpty()) {
        append("""<span class="muted tiny">No announce hosts — add one below.</span>""")
    } else {
        t.hosts.forEach { h ->
            append("""<div class="host"><span>${h.esc()}</span><span class="hx trk-rm-host" data-host="${h.esc()}" title="Remove mirror">✕</span></div>""")
        }
    }
    append("</div>")
    // Add mirror host field
    append("""<div class="add-host"><input class="input trk-add-host-input" placeholder="add mirror host… e.g. t.newmirror.org" style="flex:1;"><button class="btn sm ghost trk-add-host-btn">Add</button></div>""")
    // Library link with count
    if (t.torrentCount > 0) {
        append("""<div class="trk-meta"><a class="trk-link" href="#/library?tracker=$encName"><b>${t.torrentCount}</b> torrent${if (t.torrentCount != 1) "s" else ""} seeded →</a></div>""")
    } else {
        append("""<div class="trk-meta"><a class="trk-link" href="#/library?tracker=$encName">Library →</a></div>""")
    }
    append("</div>")
}

private fun wireTrackersTab(content: HTMLElement, scope: CoroutineScope, trackers: List<TrackerEntry>, groups: List<DetectedTrackerGroup>) {
    fun reload() { loadTab(content.parentElement ?: content, scope, "trackers", "count") }

    content.querySelector("#new-tracker-btn")?.addEventListener("click") { _ ->
        showTrackerModal(content, scope, null, null)
    }

    // Per-card wiring
    val cards = content.querySelectorAll(".trk-card")
    for (i in 0 until cards.length) {
        val card = cards.item(i) as? HTMLElement ?: continue
        val tname = card.getAttribute("data-tracker") ?: continue
        val t = trackers.firstOrNull { it.name == tname } ?: continue

        // Edit button — opens modal for name/type changes
        card.querySelector(".edit-tracker-btn")?.addEventListener("click") { _ ->
            showTrackerModal(content, scope, t, t.name)
        }

        // ✕ per host (span.hx.trk-rm-host)
        val rmSpans = card.querySelectorAll(".trk-rm-host")
        for (j in 0 until rmSpans.length) {
            val span = rmSpans.item(j) as? HTMLElement ?: continue
            val host = span.getAttribute("data-host") ?: continue
            span.addEventListener("click") { _ ->
                scope.launch {
                    MediaApi.updateTracker(t.name, null, null, t.hosts.filter { it != host })
                    reload()
                }
            }
        }

        // Add mirror host
        val addInput = card.querySelector(".trk-add-host-input") as? HTMLInputElement ?: continue
        val addBtn = card.querySelector(".trk-add-host-btn") as? HTMLElement ?: continue
        addBtn.addEventListener("click") { _ ->
            val newHost = addInput.value.trim()
            if (newHost.isBlank()) return@addEventListener
            scope.launch {
                MediaApi.updateTracker(t.name, null, null, (t.hosts + newHost).distinct())
                reload()
            }
        }
    }

    // Unmapped: "Assign to existing tracker" dropdown
    val assignSelects = content.querySelectorAll(".trk-assign-select")
    for (i in 0 until assignSelects.length) {
        val sel = assignSelects.item(i) as? HTMLElement ?: continue
        val hostsAttr = sel.getAttribute("data-hosts") ?: continue
        val groupHosts = hostsAttr.split("|").filter { it.isNotBlank() }
        sel.addEventListener("change") { _ ->
            val selectedName = (sel as? HTMLInputElement)?.value?.takeIf { it.isNotBlank() } ?: return@addEventListener
            val tracker = trackers.firstOrNull { it.name == selectedName } ?: return@addEventListener
            scope.launch {
                val newHosts = (tracker.hosts + groupHosts).distinct()
                MediaApi.updateTracker(selectedName, null, null, newHosts)
                reload()
            }
        }
    }

    // Unmapped: "Name as new tracker" button
    val nameBtns = content.querySelectorAll(".unmapped-name-btn")
    for (i in 0 until nameBtns.length) {
        val btn = nameBtns.item(i) as? HTMLElement ?: continue
        val hostsAttr = btn.getAttribute("data-hosts") ?: continue
        val groupHosts = hostsAttr.split("|").filter { it.isNotBlank() }
        btn.addEventListener("click") { _ ->
            val stub = TrackerEntry(name = "", isPrivate = true, hosts = groupHosts)
            showTrackerModal(content, scope, stub, null)
        }
    }
}

private fun showTrackerModal(content: HTMLElement, scope: CoroutineScope, existing: TrackerEntry?, editName: String?) {
    val isEdit = editName != null
    val back = document.createElement("div") as HTMLElement
    back.className = "modal-back open"
    val hostLines = (existing?.hosts ?: emptyList()).joinToString("\n") { it.esc() }
    back.innerHTML = """
        <div class="modal">
          <span class="x" id="trk-x">✕</span>
          <h3>${if (isEdit) "Edit tracker" else "New tracker"}</h3>
          <div class="field"><label>Name</label><input id="trk-name" class="input" style="width:100%" placeholder="e.g. NordicHD" value="${(existing?.name ?: "").esc()}"></div>
          <div class="field"><label>Type</label>
            <div class="seg">
              <span id="trk-priv-btn" ${if (existing?.isPrivate != false) """class="on" """ else ""}>Private</span>
              <span id="trk-pub-btn" ${if (existing?.isPrivate == false) """class="on" """ else ""}>Public</span>
            </div>
            <input type="hidden" id="trk-priv-val" value="${if (existing?.isPrivate != false) "true" else "false"}">
          </div>
          <div class="field">
            <label>Announce hosts <span class="tiny muted">— one per line; all mirrors of this tracker</span></label>
            <textarea id="trk-hosts" class="input" rows="3" style="font-family:var(--font-mono);font-size:.78rem;resize:vertical;width:100%" placeholder="t.nordicswarm.org&#10;t.polarswarm.org">$hostLines</textarea>
          </div>
          <div class="tiny muted" style="margin:-4px 0 8px;line-height:1.5;">Match is by host only — the passkey in the path differs per user and is ignored.</div>
          ${if (isEdit) """<div style="margin-bottom:10px"><button id="trk-delete" class="btn sm ghost" style="color:var(--bad)">Delete tracker</button></div>""" else ""}
          <div class="row" style="justify-content:flex-end;gap:8px;margin-top:6px;">
            <button id="trk-cancel" class="btn ghost">Cancel</button>
            <button id="trk-save" class="btn primary">${if (isEdit) "Save" else "Create tracker"}</button>
          </div>
          <span id="trk-msg" class="muted tiny" style="display:block;margin-top:6px"></span>
        </div>""".trimIndent()
    document.body?.appendChild(back)

    // Private/Public toggle via .seg spans
    val privBtn = back.querySelector("#trk-priv-btn") as? HTMLElement
    val pubBtn = back.querySelector("#trk-pub-btn") as? HTMLElement
    val privVal = back.querySelector("#trk-priv-val") as? HTMLInputElement
    privBtn?.addEventListener("click") { _ ->
        privBtn.className = "on"; pubBtn?.className = ""; privVal?.value = "true"
    }
    pubBtn?.addEventListener("click") { _ ->
        pubBtn.className = "on"; privBtn?.className = ""; privVal?.value = "false"
    }

    fun close() { back.remove() }
    back.querySelector("#trk-x")?.addEventListener("click") { _ -> close() }
    back.querySelector("#trk-cancel")?.addEventListener("click") { _ -> close() }
    back.addEventListener("click") { e -> if (e.target == back) close() }

    back.querySelector("#trk-delete")?.addEventListener("click") { _ ->
        if (editName != null && window.confirm("Delete tracker '$editName'?")) {
            scope.launch { MediaApi.deleteTracker(editName); close(); loadTab(content.parentElement ?: content, scope, "trackers", "count") }
        }
    }

    back.querySelector("#trk-save")?.addEventListener("click") { _ ->
        val name = (back.querySelector("#trk-name") as? HTMLInputElement)?.value?.trim() ?: ""
        val priv = privVal?.value != "false"
        val hostsRaw = (back.querySelector("#trk-hosts") as? HTMLTextAreaElement)?.value ?: ""
        val hosts = hostsRaw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        val msgEl = back.querySelector("#trk-msg") as? HTMLElement
        if (name.isEmpty()) { msgEl?.textContent = "Name is required."; return@addEventListener }
        scope.launch {
            val ok = if (isEdit) MediaApi.updateTracker(editName!!, name.takeIf { it != editName }, priv, hosts)
                     else MediaApi.createTracker(name, priv, hosts)
            if (ok) { close(); loadTab(content.parentElement ?: content, scope, "trackers", "count") }
            else msgEl?.textContent = if (isEdit) "Save failed." else "Name already exists."
        }
    }
}
