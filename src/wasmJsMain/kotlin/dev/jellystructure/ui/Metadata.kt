package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.JsTag
import dev.jellystructure.api.MetadataApi
import dev.jellystructure.api.MetadataEntry
import dev.jellystructure.api.TagsResponse
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

private val TAB_LABELS = listOf("studios", "networks", "genres", "tags")

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
        }
    }
}

private fun renderLogoGrid(entries: List<MetadataEntry>, kind: String, linkPrefix: String): String {
    val singularKind = if (kind == "studios") "studio" else "network"
    if (entries.isEmpty()) return """<p class="muted tiny">No ${singularKind}s found. Run a scan to populate.</p>"""
    val hasAny = entries.any { it.hasLogo }
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
                """<img src="/api/metadata/$kind/$encoded/artwork" alt="${e.name}" style="max-width:80%;max-height:52px;object-fit:contain" loading="lazy">"""
            } else {
                """<span style="font-size:.78rem;font-weight:700;letter-spacing:.03em;text-align:center;color:var(--ink-soft);padding:0 4px;line-height:1.3">${e.name}</span>"""
            }
            append("""<a href="#$linkPrefix$encoded" data-filter-name="${e.name.lowercase()}" style="display:flex;flex-direction:column;text-decoration:none;border-radius:var(--radius-s);overflow:hidden;background:var(--card-bg);border:var(--card-bd);box-shadow:var(--shadow-s);transition:transform .15s,box-shadow .15s,border-color .15s" onmouseover="this.style.transform='translateY(-3px)';this.style.boxShadow='var(--shadow)';this.style.borderColor='var(--hi)'" onmouseout="this.style.transform='';this.style.boxShadow='var(--shadow-s)';this.style.borderColor=''">""")
            append("""<div style="height:80px;display:flex;align-items:center;justify-content:center;background:rgba(160,152,255,.42);border-bottom:1px solid rgba(160,152,255,.25)">$logoHtml</div>""")
            append("""<div style="padding:10px 12px;display:flex;align-items:center;justify-content:space-between;gap:6px">""")
            append("""<span style="font-size:.82rem;font-weight:600;color:var(--ink);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1">${e.name}</span>""")
            append("""<span style="font-size:.72rem;color:var(--ink-soft);flex-shrink:0;background:var(--fill-3);border-radius:99px;padding:2px 8px;border:1px solid var(--line)">${e.count}</span>""")
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
        append("""<div style="display:flex;flex-wrap:wrap;gap:8px;margin-bottom:20px">""")
        for (tag in data.jsTags) {
            val radius = if (tag.description.isNotBlank()) "12px" else "99px"
            append("""<button class="js-tag-card" data-name="${tag.name}" data-filter-name="${tag.name.lowercase()}" style="display:inline-flex;align-items:center;gap:9px;background:var(--card-bg);border:var(--card-bd);border-radius:$radius;padding:8px 14px;cursor:pointer;font-size:.85rem;box-shadow:var(--shadow-s);transition:border-color .12s,box-shadow .12s;text-align:left" onmouseover="this.style.borderColor='var(--hi)';this.style.boxShadow='var(--shadow)'" onmouseout="this.style.borderColor='';this.style.boxShadow='var(--shadow-s)'">""")
            append("""<span style="width:10px;height:10px;border-radius:50%;background:${tag.color};flex-shrink:0;display:inline-block;box-shadow:0 0 0 2px ${tag.color}33"></span>""")
            append("""<span style="display:flex;flex-direction:column;gap:1px">""")
            append("""<span style="color:var(--ink);font-weight:500">${tag.name}</span>""")
            if (tag.description.isNotBlank()) {
                append("""<span style="font-size:.72rem;color:var(--ink-soft);max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${tag.description}</span>""")
            }
            append("</span>")
            append("""<span style="font-size:.72rem;color:var(--ink-soft);background:var(--fill-3);border-radius:99px;padding:1px 7px;border:1px solid var(--line);flex-shrink:0">${tag.count}</span>""")
            append("</button>")
        }
        append("</div>")
    }
    if (data.otherTags.isNotEmpty()) {
        append("""<h3 style="font-size:.9rem;font-weight:600;margin:0 0 10px;color:var(--ink-soft);text-transform:uppercase;letter-spacing:.06em">Other tags</h3>""")
        append("""<div style="display:flex;flex-wrap:wrap;gap:6px">""")
        for (tag in data.otherTags) {
            append("""<span data-filter-name="${tag.name.lowercase()}" style="display:inline-flex;align-items:center;gap:5px;padding:4px 10px;border-radius:99px;background:var(--fill-2);border:1px solid var(--line);font-size:.8rem;color:var(--ink)">${tag.name}<span style="font-size:.72rem;color:var(--ink-soft)">${tag.count}</span></span>""")
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
            showTagModal(content, scope, name)
        }
    }
}

private val PRESET_COLORS = listOf("#6b7280", "#ef4444", "#f97316", "#eab308", "#22c55e", "#3b82f6", "#8b5cf6", "#ec4899")

private fun showTagModal(content: HTMLElement, scope: CoroutineScope, editName: String?) {
    val modal = content.querySelector("#tag-modal") as? HTMLElement ?: return
    val isNew = editName == null
    val title = if (isNew) "New tag" else "Edit tag"
    modal.style.display = "flex"
    val swatches = PRESET_COLORS.joinToString("") { color ->
        """<span class="color-swatch" data-color="$color" style="display:inline-block;width:22px;height:22px;border-radius:50%;background:$color;cursor:pointer;border:2px solid transparent;margin:2px" title="$color"></span>"""
    }
    modal.innerHTML = """
        <div style="background:var(--surface);border-radius:10px;padding:24px;width:340px;max-width:calc(100vw - 32px);box-shadow:0 8px 32px rgba(0,0,0,.25)">
          <h3 style="margin:0 0 16px;font-size:1rem">$title</h3>
          <div class="field">
            <label>Name</label>
            <input id="modal-tag-name" class="input" type="text" placeholder="tag name" style="width:100%" ${if (!isNew) "value=\"$editName\" readonly" else ""}>
          </div>
          <div class="field">
            <label>Color</label>
            <div id="color-swatches">$swatches</div>
            <input id="modal-tag-color" type="hidden" value="#6b7280">
          </div>
          <div class="field">
            <label>Description</label>
            <input id="modal-tag-desc" class="input" type="text" placeholder="optional description" style="width:100%">
          </div>
          <div id="modal-tag-error" class="badge bad" style="display:none;margin-bottom:10px"></div>
          <div style="display:flex;gap:8px;justify-content:flex-end;align-items:center">
            ${if (!isNew) """<button id="modal-delete-btn" class="btn sm ghost" style="color:var(--bad);border-color:var(--bad);margin-right:auto">Delete</button>""" else ""}
            <button id="modal-cancel-btn" class="btn sm ghost">Cancel</button>
            <button id="modal-save-btn" class="btn sm primary">${if (isNew) "Create" else "Save"}</button>
          </div>
        </div>
    """.trimIndent()

    // Wire color swatches
    val swatchEls = modal.querySelectorAll(".color-swatch")
    fun selectSwatch(color: String) {
        (modal.querySelector("#modal-tag-color") as? HTMLInputElement)?.value = color
        for (i in 0 until swatchEls.length) {
            val s = swatchEls.item(i) as? HTMLElement ?: continue
            s.style.border = if (s.getAttribute("data-color") == color) "2px solid var(--ink)" else "2px solid transparent"
        }
    }
    selectSwatch("#6b7280")
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
