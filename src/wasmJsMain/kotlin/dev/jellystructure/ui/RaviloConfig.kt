package dev.jellystructure.ui

import dev.jellystructure.api.JellyfinUser
import dev.jellystructure.api.MetadataApi
import dev.jellystructure.api.RaviloApi
import dev.jellystructure.scrollIntoViewSmooth
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.ChannelStyle
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TileShape
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

// The editor binds directly to the shared `RaviloConfig` (Constitution Invariant 2): the bytes this
// screen PUTs are the same bytes the TV reads via `GET /api/tv/config`.

private var currentUserId: String = ""
private var currentConfig: RaviloConfig = RaviloConfig()
private var rcScope: CoroutineScope? = null
private var users: List<JellyfinUser> = emptyList()
private var facets: Map<String, List<String>> = emptyMap() // "NETWORK"/"STUDIO"/"GENRE"/"TAG" -> values

private val CHANNEL_KINDS = listOf("NETWORK", "STUDIO", "GENRE", "TAG")
private val AUTO_ADVANCE_OPTIONS = listOf(0 to "Off", 4 to "4 s", 6 to "6 s", 8 to "8 s", 10 to "10 s")

private fun genId(prefix: String) = "$prefix-${Random.nextInt(100_000, 999_999)}"

private fun wbMode(match: String) = if (match == "ANY") MatchMode.ANY else MatchMode.ALL
private fun wbConds(conds: List<WbCond>): List<Condition> =
    conds.filter { it.values.isNotEmpty() || it.facet == "track_title" }.map { Condition(it.facet, it.op, it.values.toList()) }

// The jellyfish brand mark (matches design/app/ravilo-config.html).
private const val RAVILO_MARK = """<svg viewBox="0 0 100 100" aria-hidden="true" style="width:30px;height:30px;vertical-align:middle;filter:drop-shadow(0 0 8px rgba(123,110,240,.5))"><defs><linearGradient id="ravJelly" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7b6ef0"/><stop offset="1" stop-color="#3fb6f5"/></linearGradient></defs><path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#ravJelly)"/><g stroke="url(#ravJelly)" stroke-width="4.5" stroke-linecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>"""

fun renderRaviloConfig(container: Element, scope: CoroutineScope) {
    rcScope = scope
    container.innerHTML = buildLoadingShell()
    scope.launch {
        users = runCatching { RaviloApi.getUsers() }.getOrDefault(emptyList())
        if (users.isEmpty()) {
            container.innerHTML = buildErrorShell("Could not load Jellyfin users — check your connection in Settings.")
            return@launch
        }
        if (currentUserId.isEmpty()) currentUserId = users.first().id
        facets = loadFacets()
        currentConfig = runCatching { RaviloApi.getConfig(currentUserId) }.getOrDefault(RaviloConfig())
        renderFull(container, scope)
    }
}

private suspend fun loadFacets(): Map<String, List<String>> = runCatching {
    mapOf(
        "NETWORK" to (MetadataApi.getNetworks()?.map { it.name } ?: emptyList()),
        "STUDIO"  to (MetadataApi.getStudios()?.map { it.name } ?: emptyList()),
        "GENRE"   to (MetadataApi.getGenres()?.map { it.name } ?: emptyList()),
        "TAG"     to (MetadataApi.getTags()?.let { it.jsTags.map { t -> t.name } + it.otherTags.map { t -> t.name } } ?: emptyList()),
    )
}.getOrDefault(emptyMap())

private fun renderFull(container: Element, scope: CoroutineScope) {
    container.innerHTML = buildShell()
    wireShell(container, scope)
    renderSections(container, scope)
}

private fun buildLoadingShell() = """
    <div class="pagebar"><h1>Ravilo TV</h1></div>
    <p class="page-sub" style="color:var(--ink-soft)">Loading…</p>
""".trimIndent()

private fun buildErrorShell(msg: String) = """
    <div class="pagebar"><h1>Ravilo TV</h1></div>
    <div class="card" style="padding:24px;color:var(--bad)">$msg</div>
""".trimIndent()

private fun datalistsHtml(): String = buildString {
    facets.forEach { (kind, values) ->
        append("""<datalist id="facet-${kind.lowercase()}">""")
        values.forEach { append("""<option value="${it.htmlEsc()}">""") }
        append("</datalist>")
    }
}

private fun buildShell(): String {
    val userOptions = users.joinToString("") { u ->
        val sel = if (u.id == currentUserId) " selected" else ""
        """<option value="${u.id}"$sel>${u.displayName.htmlEsc()}</option>"""
    }
    return """
    ${datalistsHtml()}
    <div class="pagebar">
      <h1>$RAVILO_MARK Ravilo TV</h1>
      <span class="badge info">app config</span>
      <span class="spacer"></span>
      <span class="badge ok" id="rav-synced">saved · synced</span>
      <button id="rav-save" class="btn primary">Save</button>
    </div>
    <p class="page-sub">
      Lay out the Ravilo home screen your viewers see on their TVs. Ravilo connects only to
      Jellystructure — browsing, search and this layout are served by us; media streams from Jellyfin.
    </p>
    <div class="note blue" style="margin-bottom:18px;display:flex;gap:12px;align-items:center">
      <span class="badge info" style="flex:none">per Jellyfin user</span>
      <div class="tiny" style="flex:1">Stored against the selected Jellyfin user, so every TV they sign into shows the same thing. Changes sync to all their devices.</div>
      <select id="rav-user-pick" class="input" style="width:auto;min-width:180px;font-size:.85rem">$userOptions</select>
    </div>
    <div id="rav-msg" style="display:none;margin-bottom:12px"></div>

    <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap">
      <nav style="width:150px;flex-shrink:0;position:sticky;top:88px">
        <div style="display:flex;flex-direction:column;gap:2px">
          <button data-rav-sect="sect-pair"     class="rav-nav-item">Pair a TV</button>
          <button data-rav-sect="sect-heroes"   class="rav-nav-item">Hero carousel</button>
          <button data-rav-sect="sect-channels" class="rav-nav-item">Channels</button>
          <button data-rav-sect="sect-rows"     class="rav-nav-item">Content rows</button>
          <button data-rav-sect="sect-behaviour"class="rav-nav-item">Behaviour</button>
        </div>
      </nav>
      <div class="col fill" style="min-width:280px" id="rav-sections">
        <div id="sect-pair"></div>
        <div id="sect-heroes"></div>
        <div id="sect-channels"></div>
        <div id="sect-rows"></div>
        <div id="sect-behaviour"></div>
      </div>
      <div class="card" style="width:280px;flex:none;position:sticky;top:88px;padding:12px">
        <div class="row center" style="margin:2px 4px 10px"><h4 style="margin:0">Live preview</h4><span class="spacer"></span><span class="badge ok" style="font-size:.6rem">this user</span></div>
        <div id="rav-preview"></div>
        <div class="tiny muted" style="margin-top:8px;text-align:center">Reflects the current edits</div>
      </div>
    </div>
    """.trimIndent()
}

private fun wireShell(container: Element, scope: CoroutineScope) {
    // User picker
    container.querySelector("#rav-user-pick")?.addEventListener("change") { _ ->
        val sel = container.querySelector("#rav-user-pick") as? HTMLSelectElement ?: return@addEventListener
        currentUserId = sel.value
        scope.launch {
            currentConfig = runCatching { RaviloApi.getConfig(currentUserId) }.getOrDefault(RaviloConfig())
            renderSections(container, scope)
        }
    }

    // Save
    container.querySelector("#rav-save")?.addEventListener("click") { _ ->
        collectConfig(container)
        scope.launch {
            val msg = container.querySelector("#rav-msg") as? HTMLElement ?: return@launch
            runCatching { RaviloApi.putConfig(currentUserId, currentConfig) }.fold(
                onSuccess = { showMsg(msg, "Saved.", ok = true) },
                onFailure = { showMsg(msg, "Save failed: ${it.message}", ok = false) },
            )
        }
    }

    // Smooth-scroll nav
    container.querySelectorAll(".rav-nav-item").let { btns ->
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as? HTMLElement ?: continue
            val sectId = btn.getAttribute("data-rav-sect") ?: continue
            btn.addEventListener("click") { _ ->
                container.querySelector("#$sectId")?.let { scrollIntoViewSmooth(it) }
            }
        }
    }

    // Delegated listeners on the sections host: keep the live preview in sync with edits.
    val sections = container.querySelector("#rav-sections")
    sections?.addEventListener("input") { ev ->
        val t = ev.target
        if (t is HTMLInputElement && t.id == "hero-height") {
            container.querySelector("#hero-height-val")?.textContent = "${t.value}%"
            collectConfig(container); renderPreview(container)
        }
    }
    sections?.addEventListener("change") { ev ->
        val t = ev.target
        // Switch the value input's facet suggestions when a channel/row kind changes.
        if (t is HTMLSelectElement && t.hasAttribute("data-ch-kind")) {
            val idx = t.getAttribute("data-ch-kind")
            (container.querySelector("[data-ch-filter='$idx']") as? HTMLInputElement)
                ?.setAttribute("list", "facet-${t.value.lowercase()}")
        }
        if (t is HTMLSelectElement && t.hasAttribute("data-row-kind")) {
            val idx = t.getAttribute("data-row-kind")
            val title = container.querySelector("[data-row-title='$idx']") as? HTMLInputElement
            if (t.value == "GENRE") title?.setAttribute("list", "facet-genre") else title?.removeAttribute("list")
        }
        collectConfig(container); renderPreview(container)
    }
}

private fun renderSections(container: Element, scope: CoroutineScope) {
    renderPair(container, scope)
    renderHeroes(container)
    renderChannels(container)
    renderRows(container)
    renderBehaviour(container)
    renderPreview(container)
}

/** Capture current DOM edits, apply a structural change, then re-render the given section + preview. */
private fun structural(container: Element, mutate: () -> Unit, rerender: (Element) -> Unit) {
    collectConfig(container)
    mutate()
    rerender(container)
    renderPreview(container)
}

private fun <T> List<T>.swapped(i: Int, j: Int): List<T> {
    if (i !in indices || j !in indices) return this
    val m = toMutableList(); val t = m[i]; m[i] = m[j]; m[j] = t; return m
}

// ── Pair a TV ─────────────────────────────────────────────────────────────────

private fun renderPair(container: Element, scope: CoroutineScope) {
    val sect = container.querySelector("#sect-pair") ?: return
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:6px">Pair a TV</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            Enter the 6-character code shown on your Ravilo TV app to link it to your account.
          </p>
          <div class="row" style="gap:8px;align-items:center">
            <input id="pair-code" class="input" style="width:160px;letter-spacing:.15em;text-transform:uppercase"
              maxlength="6" placeholder="ABC123" autocomplete="off" spellcheck="false">
            <button id="pair-btn" class="btn primary">Pair</button>
            <span id="pair-msg" style="font-size:.85rem"></span>
          </div>
        </div>
    """.trimIndent()

    val codeInput = sect.querySelector("#pair-code") as? HTMLInputElement ?: return
    val pairBtn   = sect.querySelector("#pair-btn")  as? HTMLElement ?: return
    val pairMsg   = sect.querySelector("#pair-msg")  as? HTMLElement ?: return

    fun submit() {
        val code = codeInput.value.trim().uppercase()
        if (code.length != 6) { pairMsg.textContent = "Enter the full 6-character code."; return }
        pairMsg.textContent = "Pairing…"
        scope.launch {
            runCatching { RaviloApi.approvePairing(code) }.fold(
                onSuccess = {
                    pairMsg.textContent = "✓ TV paired successfully!"
                    pairMsg.setAttribute("style", "font-size:.85rem;color:var(--ok)")
                    codeInput.value = ""
                },
                onFailure = {
                    pairMsg.textContent = "Failed: ${it.message ?: "unknown error"}"
                    pairMsg.setAttribute("style", "font-size:.85rem;color:var(--bad)")
                },
            )
        }
    }

    pairBtn.addEventListener("click") { _ -> submit() }
    codeInput.addEventListener("keydown") { ev ->
        if ((ev as? org.w3c.dom.events.KeyboardEvent)?.key == "Enter") submit()
    }
}

// ── Heroes ────────────────────────────────────────────────────────────────────

private fun renderHeroes(container: Element) {
    val sect = container.querySelector("#sect-heroes") ?: return
    val last = currentConfig.heroes.lastIndex
    val rows = currentConfig.heroes.mapIndexed { i, h ->
        val showChecked = if (h.enabled) " checked" else ""
        """
        <div class="cfg-row" style="display:flex;align-items:center;gap:10px;margin-bottom:8px">
          ${reorderButtons("hero", i, last)}
          <input class="input fill" placeholder="Jellyfin item ID" value="${h.itemId.htmlEsc()}" data-hero-id="$i">
          <label style="display:flex;align-items:center;gap:5px;font-size:.8rem;white-space:nowrap"><input type="checkbox" data-hero-enabled="$i"$showChecked> Show</label>
          <button class="btn sm ghost" data-hero-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    val autoOptions = AUTO_ADVANCE_OPTIONS.joinToString("") { (v, label) ->
        val sel = if (v == currentConfig.autoAdvanceSeconds) " selected" else ""
        """<option value="$v"$sel>$label</option>"""
    }
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Hero carousel</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            Pinned items shown in the banner at the top of the home screen. Reorder with the arrows; the carousel auto-advances on the TV.
          </p>
          $rows
          <button id="hero-add" class="btn sm ghost" style="margin-top:6px">+ Add hero</button>
          <hr class="dash" style="margin:16px 0">
          <div class="row" style="gap:24px;flex-wrap:wrap;align-items:flex-end">
            <div style="flex:1;min-width:220px">
              <label style="display:block;font-size:.85rem;margin-bottom:4px">Hero height <span class="mono" id="hero-height-val">${currentConfig.heroHeightPct}%</span> of screen</label>
              <input type="range" id="hero-height" min="30" max="70" value="${currentConfig.heroHeightPct}" style="width:100%">
            </div>
            <div style="width:160px">
              <label style="display:block;font-size:.85rem;margin-bottom:4px">Auto-advance</label>
              <select id="auto-advance" class="input" style="width:100%;font-size:.85rem">$autoOptions</select>
            </div>
          </div>
        </div>
    """.trimIndent()
    sect.querySelector("#hero-add")?.addEventListener("click") { _ ->
        structural(container, { currentConfig = currentConfig.copy(heroes = currentConfig.heroes + HeroConfig(itemId = "")) }, ::renderHeroes)
    }
    wireReorder(container, sect, "hero",
        get = { currentConfig.heroes }, set = { currentConfig = currentConfig.copy(heroes = it) }, ::renderHeroes)
    for (i in currentConfig.heroes.indices) {
        sect.querySelector("[data-hero-del='$i']")?.addEventListener("click") { _ ->
            structural(container, {
                val list = currentConfig.heroes.toMutableList(); list.removeAt(i)
                currentConfig = currentConfig.copy(heroes = list)
            }, ::renderHeroes)
        }
    }
}

// ── Channels ──────────────────────────────────────────────────────────────────

/** Derive the editor's "kind + value" pair from whichever typed filter is set on the shared config. */
private fun ChannelConfig.kindAndValue(): Pair<String, String> = when {
    filterNetwork != null -> "NETWORK" to filterNetwork!!
    filterStudio  != null -> "STUDIO"  to filterStudio!!
    filterGenre   != null -> "GENRE"   to filterGenre!!
    filterTag     != null -> "TAG"     to filterTag!!
    else                  -> "GENRE"   to ""
}

private fun renderChannels(container: Element) {
    val sect = container.querySelector("#sect-channels") ?: return
    val last = currentConfig.channels.lastIndex
    val rows = currentConfig.channels.mapIndexed { i, c ->
        val (kind, value) = c.kindAndValue()
        val kindOptions = CHANNEL_KINDS.joinToString("") { k ->
            val sel = if (k == kind) " selected" else ""
            """<option value="$k"$sel>${k.lowercase().replaceFirstChar { it.uppercase() }}</option>"""
        }
        val styleOptions = ChannelStyle.entries.joinToString("") { s ->
            val sel = if (s == c.style) " selected" else ""
            """<option value="${s.name}"$sel>${s.name.lowercase().replaceFirstChar { it.uppercase() }}</option>"""
        }
        val showChecked = if (c.enabled) " checked" else ""
        val color = c.brandColor?.takeIf { it.startsWith("#") } ?: "#7b6ef0"
        """
        <div class="cfg-row" style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
          ${reorderButtons("ch", i, last)}
          <input type="hidden" data-ch-id="$i" value="${c.id.htmlEsc()}">
          <input class="input" style="width:130px" placeholder="Name" value="${c.name.htmlEsc()}" data-ch-name="$i">
          <select class="input" style="width:100px" data-ch-kind="$i">$kindOptions</select>
          <input class="input fill" style="min-width:120px" placeholder="Filter value" value="${value.htmlEsc()}" data-ch-filter="$i" list="facet-${kind.lowercase()}">
          <select class="input" style="width:80px" data-ch-style="$i">$styleOptions</select>
          <input type="color" data-ch-color="$i" value="$color" title="Brand color" style="width:34px;height:30px;padding:0;border:none;background:none">
          <label style="display:flex;align-items:center;gap:5px;font-size:.8rem;white-space:nowrap"><input type="checkbox" data-ch-enabled="$i"$showChecked> Show</label>
          <button class="btn sm ghost" data-ch-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Channels &amp; collections</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            The logo row under the hero. Each maps to one Jellyfin filter (network, studio, genre, or tag) and opens a channel-scoped view.
          </p>
          $rows
          <button id="ch-add" class="btn sm ghost" style="margin-top:6px">+ Add channel</button>
          <button id="ch-workbench" class="btn sm ghost" style="margin-top:6px">⚙ Build with workbench</button>
        </div>
    """.trimIndent()
    sect.querySelector("#ch-add")?.addEventListener("click") { _ ->
        structural(container, { currentConfig = currentConfig.copy(channels = currentConfig.channels + ChannelConfig(id = genId("ch"))) }, ::renderChannels)
    }
    sect.querySelector("#ch-workbench")?.addEventListener("click") { _ ->
        val scope = rcScope ?: return@addEventListener
        openWorkbench(scope, "New channel — condition workbench", viewer = currentUserId, applyLabel = "Create channel",
            onApply = { match, _, conds ->
                val label = conds.firstOrNull { it.values.isNotEmpty() }?.values?.firstOrNull() ?: "Channel"
                structural(container, {
                    currentConfig = currentConfig.copy(channels = currentConfig.channels +
                        ChannelConfig(id = genId("ch"), name = label, match = wbMode(match), conditions = wbConds(conds)))
                }, ::renderChannels)
            })
    }
    wireReorder(container, sect, "ch",
        get = { currentConfig.channels }, set = { currentConfig = currentConfig.copy(channels = it) }, ::renderChannels)
    for (i in currentConfig.channels.indices) {
        sect.querySelector("[data-ch-del='$i']")?.addEventListener("click") { _ ->
            structural(container, {
                val list = currentConfig.channels.toMutableList(); list.removeAt(i)
                currentConfig = currentConfig.copy(channels = list)
            }, ::renderChannels)
        }
    }
}

// ── Rows ──────────────────────────────────────────────────────────────────────

private val MEDIA_KINDS = listOf("" to "All", "MOVIE" to "Movies", "SERIES" to "Series")

private fun RowKind.isSystem() = this == RowKind.CONTINUE || this == RowKind.NEWLY_ADDED

private fun renderRows(container: Element) {
    val sect = container.querySelector("#sect-rows") ?: return
    val mergeChecked = if (currentConfig.mergeNewlyAdded) " checked" else ""
    val last = currentConfig.rows.lastIndex
    val rows = currentConfig.rows.mapIndexed { i, r ->
        val system = r.kind.isSystem()
        val kindOptions = RowKind.entries.joinToString("") { k ->
            val sel = if (k == r.kind) " selected" else ""
            """<option value="${k.name}"$sel>${k.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}</option>"""
        }
        val mediaOptions = MEDIA_KINDS.joinToString("") { (code, label) ->
            val sel = if (code == (r.mediaKind ?: "")) " selected" else ""
            """<option value="$code"$sel>$label</option>"""
        }
        val showChecked = if (r.enabled) " checked" else ""
        val genreList = if (r.kind == RowKind.GENRE) """ list="facet-genre"""" else ""
        val badge = if (system) """<span class="badge ok" style="flex:none;font-size:.6rem">system</span>""" else """<span class="badge info" style="flex:none;font-size:.6rem">${r.kind.name.lowercase()}</span>"""
        val delBtn = if (system) "" else """<button class="btn sm ghost" data-row-del="$i">✕</button>"""
        """
        <div class="cfg-row" style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
          ${reorderButtons("row", i, last)}
          $badge
          <input type="hidden" data-row-id="$i" value="${r.id.htmlEsc()}">
          <input class="input" style="width:150px" placeholder="Title" value="${(r.title ?: "").htmlEsc()}" data-row-title="$i"$genreList>
          <select class="input" style="width:140px" data-row-kind="$i">$kindOptions</select>
          <select class="input" style="width:90px" data-row-media="$i">$mediaOptions</select>
          <label style="display:flex;align-items:center;gap:5px;font-size:.8rem;white-space:nowrap"><input type="checkbox" data-row-enabled="$i"$showChecked> Show</label>
          $delBtn
        </div>
        """.trimIndent()
    }.joinToString("")
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Content rows</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            The vertical stack on Home. System rows (Continue Watching, Newly Added) can be hidden and reordered but not removed.
          </p>
          <div class="box flat" style="background:var(--hi-soft);border:1px solid rgba(123,110,240,.3);border-radius:8px;padding:10px 12px;margin-bottom:14px">
            <label style="display:flex;align-items:center;gap:10px;font-size:.9rem">
              <input type="checkbox" id="merge-newly-added"$mergeChecked>
              <span><b>Merge “Newly Added” movies &amp; series into one row.</b> <span class="tiny muted">Off = two rows like Jellyfin; on = a single combined “Newly Added”.</span></span>
            </label>
          </div>
          $rows
          <button id="row-add" class="btn sm ghost" style="margin-top:6px">+ Add row</button>
          <button id="row-workbench" class="btn sm ghost" style="margin-top:6px">⚙ Build with workbench</button>
        </div>
    """.trimIndent()
    sect.querySelector("#row-add")?.addEventListener("click") { _ ->
        structural(container, { currentConfig = currentConfig.copy(rows = currentConfig.rows + RowConfig(id = genId("row"), kind = RowKind.GENRE)) }, ::renderRows)
    }
    sect.querySelector("#row-workbench")?.addEventListener("click") { _ ->
        val scope = rcScope ?: return@addEventListener
        openWorkbench(scope, "New content row — condition workbench", viewer = currentUserId, applyLabel = "Create row",
            onApply = { match, include, conds ->
                val label = conds.firstOrNull { it.values.isNotEmpty() }?.values?.firstOrNull() ?: "Custom row"
                val mediaKind = when (include) { "movies" -> "MOVIE"; "series" -> "SERIES"; else -> null }
                structural(container, {
                    currentConfig = currentConfig.copy(rows = currentConfig.rows +
                        RowConfig(id = genId("row"), kind = RowKind.CUSTOM, title = label, mediaKind = mediaKind, match = wbMode(match), conditions = wbConds(conds)))
                }, ::renderRows)
            })
    }
    wireReorder(container, sect, "row",
        get = { currentConfig.rows }, set = { currentConfig = currentConfig.copy(rows = it) }, ::renderRows)
    for (i in currentConfig.rows.indices) {
        sect.querySelector("[data-row-del='$i']")?.addEventListener("click") { _ ->
            structural(container, {
                val list = currentConfig.rows.toMutableList(); list.removeAt(i)
                currentConfig = currentConfig.copy(rows = list)
            }, ::renderRows)
        }
    }
}

// ── Reorder helpers ─────────────────────────────────────────────────────────────

private fun reorderButtons(prefix: String, i: Int, last: Int): String {
    val upDis = if (i == 0) " disabled" else ""
    val dnDis = if (i == last) " disabled" else ""
    return """<span style="display:flex;flex-direction:column;gap:1px">
        <button class="btn sm ghost" style="padding:0 6px;line-height:1.2" data-$prefix-up="$i"$upDis>↑</button>
        <button class="btn sm ghost" style="padding:0 6px;line-height:1.2" data-$prefix-down="$i"$dnDis>↓</button>
    </span>""".trimIndent()
}

private fun <T> wireReorder(
    container: Element,
    sect: Element,
    prefix: String,
    get: () -> List<T>,
    set: (List<T>) -> Unit,
    rerender: (Element) -> Unit,
) {
    for (i in get().indices) {
        sect.querySelector("[data-$prefix-up='$i']")?.addEventListener("click") { _ ->
            structural(container, { set(get().swapped(i, i - 1)) }, rerender)
        }
        sect.querySelector("[data-$prefix-down='$i']")?.addEventListener("click") { _ ->
            structural(container, { set(get().swapped(i, i + 1)) }, rerender)
        }
    }
}

// ── Behaviour ─────────────────────────────────────────────────────────────────

private val TILE_SHAPE_LABELS = mapOf(TileShape.POSTER to "Posters (recommended)", TileShape.LANDSCAPE to "All landscape")
private val LANGS = listOf("en" to "English", "da" to "Dansk", "fo" to "Føroyskt")

private fun renderBehaviour(container: Element) {
    val sect = container.querySelector("#sect-behaviour") ?: return
    val skinOptions = Skin.entries.joinToString("") { s ->
        val sel = if (s == currentConfig.defaultSkin) " selected" else ""
        """<option value="${s.name}"$sel>${s.name.lowercase().replaceFirstChar { it.uppercase() }}</option>"""
    }
    val tileOptions = TileShape.entries.joinToString("") { s ->
        val sel = if (s == currentConfig.tileShape) " selected" else ""
        """<option value="${s.name}"$sel>${TILE_SHAPE_LABELS[s] ?: s.name}</option>"""
    }
    val overrideChecked = if (currentConfig.allowSkinOverride) " checked" else ""
    val progressChecked = if (currentConfig.showContinueProgress) " checked" else ""
    val langOptions = LANGS.joinToString("") { (code, label) ->
        val sel = if (code == currentConfig.uiLanguage) " selected" else ""
        """<option value="$code"$sel>$label</option>"""
    }
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:14px">Behaviour</div>
          <div style="display:grid;gap:14px">
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Interface language</span>
              <select id="beh-lang" class="input" style="width:160px;font-size:.85rem">$langOptions</select>
            </label>
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Default skin</span>
              <select id="beh-skin" class="input" style="width:160px;font-size:.85rem">$skinOptions</select>
            </label>
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Tile shape</span>
              <select id="beh-tile" class="input" style="width:180px;font-size:.85rem">$tileOptions</select>
            </label>
            <label style="display:flex;align-items:center;gap:10px;font-size:.9rem">
              <input type="checkbox" id="beh-skin-override"$overrideChecked>
              Allow users to override skin on their device
            </label>
            <label style="display:flex;align-items:center;gap:10px;font-size:.9rem">
              <input type="checkbox" id="beh-progress"$progressChecked>
              Show progress bar on Continue Watching tiles
            </label>
          </div>
        </div>
    """.trimIndent()
}

// ── Live preview (schematic) ───────────────────────────────────────────────────

private fun defaultRowTitle(kind: RowKind) = when (kind) {
    RowKind.CONTINUE    -> "Continue Watching"
    RowKind.NEWLY_ADDED -> "Newly Added"
    RowKind.GENRE       -> "Genre"
    RowKind.CUSTOM      -> "Custom"
}

private fun previewRowTitles(cfg: RaviloConfig): List<String> {
    val enabled = cfg.rows.filter { it.enabled }.sortedBy { it.order }
    val out = mutableListOf<String>()
    var mergedAdded = false
    for (r in enabled) {
        if (cfg.mergeNewlyAdded && r.kind == RowKind.NEWLY_ADDED) {
            if (!mergedAdded) { out.add("Newly Added"); mergedAdded = true }
            continue
        }
        out.add(r.title?.takeIf { it.isNotBlank() } ?: defaultRowTitle(r.kind))
    }
    return out
}

private fun renderPreview(container: Element) {
    val host = container.querySelector("#rav-preview") ?: return
    val cfg = currentConfig
    val heroLabel = cfg.heroes.firstOrNull { it.enabled }?.itemId?.takeIf { it.isNotBlank() } ?: "Hero"
    val heroPct = cfg.heroHeightPct.coerceIn(30, 70)
    val channels = cfg.channels.filter { it.enabled }
    val rowTitles = previewRowTitles(cfg)
    host.innerHTML = buildString {
        append("""<div style="border-radius:10px;overflow:hidden;border:1px solid var(--line);background:#0a0c13;aspect-ratio:16/10;display:flex;flex-direction:column">""")
        append("""<div style="height:$heroPct%;background:linear-gradient(120deg,#7b6ef0,#3fb6f5);display:flex;align-items:flex-end;padding:8px"><span style="color:#fff;font-weight:700;font-size:.68rem;text-shadow:0 1px 4px rgba(0,0,0,.6)">${heroLabel.htmlEsc()}</span></div>""")
        if (channels.isNotEmpty()) {
            append("""<div style="display:flex;gap:4px;padding:6px 8px;overflow:hidden">""")
            channels.take(5).forEach { c ->
                val bg = (c.brandColor?.takeIf { it.startsWith("#") } ?: "#1b2031")
                append("""<span style="background:$bg;color:#fff;font-size:.52rem;padding:2px 6px;border-radius:5px;white-space:nowrap">${c.name.ifBlank { "Channel" }.htmlEsc()}</span>""")
            }
            append("</div>")
        }
        append("""<div style="flex:1;padding:4px 8px;overflow:hidden">""")
        rowTitles.take(5).forEach { append("""<div style="color:#aeb4cb;font-size:.58rem;margin-bottom:5px">${it.htmlEsc()} <span style="opacity:.35">▦ ▦ ▦</span></div>""") }
        append("</div>")
        append("</div>")
    }
}

// ── Collect current form state into the shared RaviloConfig ────────────────────

private fun collectConfig(container: Element) {
    // Heroes
    val heroIds      = container.querySelectorAll("[data-hero-id]")
    val heroEnabled  = container.querySelectorAll("[data-hero-enabled]")
    val heroes = (0 until heroIds.length).map { i ->
        HeroConfig(
            itemId  = (heroIds.item(i) as? HTMLInputElement)?.value?.trim() ?: "",
            enabled = (heroEnabled.item(i) as? HTMLInputElement)?.checked ?: true,
            order   = i,
        )
    }
    // Channels — map (kind + value) to one typed filter; preserve/seed ids.
    val chIds     = container.querySelectorAll("[data-ch-id]")
    val chNames   = container.querySelectorAll("[data-ch-name]")
    val chKinds   = container.querySelectorAll("[data-ch-kind]")
    val chFilters = container.querySelectorAll("[data-ch-filter]")
    val chStyles  = container.querySelectorAll("[data-ch-style]")
    val chColors  = container.querySelectorAll("[data-ch-color]")
    val chEnabled = container.querySelectorAll("[data-ch-enabled]")
    val channels  = (0 until chNames.length).map { i ->
        val kind  = (chKinds.item(i)   as? HTMLSelectElement)?.value ?: "GENRE"
        val value = (chFilters.item(i) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
        val style = runCatching { ChannelStyle.valueOf((chStyles.item(i) as? HTMLSelectElement)?.value ?: "TEXT") }
            .getOrDefault(ChannelStyle.TEXT)
        val id    = (chIds.item(i) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: genId("ch")
        ChannelConfig(
            id            = id,
            name          = (chNames.item(i) as? HTMLInputElement)?.value?.trim() ?: "",
            style         = style,
            brandColor    = (chColors.item(i) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() },
            filterNetwork = if (kind == "NETWORK") value else null,
            filterStudio  = if (kind == "STUDIO")  value else null,
            filterGenre   = if (kind == "GENRE")   value else null,
            filterTag     = if (kind == "TAG")     value else null,
            enabled       = (chEnabled.item(i) as? HTMLInputElement)?.checked ?: true,
            order         = i,
        )
    }
    // Rows
    val rowIds      = container.querySelectorAll("[data-row-id]")
    val rowTitles   = container.querySelectorAll("[data-row-title]")
    val rowKinds    = container.querySelectorAll("[data-row-kind]")
    val rowMedia    = container.querySelectorAll("[data-row-media]")
    val rowEnabled  = container.querySelectorAll("[data-row-enabled]")
    val rows = (0 until rowTitles.length).map { i ->
        val kind = runCatching { RowKind.valueOf((rowKinds.item(i) as? HTMLSelectElement)?.value ?: "GENRE") }
            .getOrDefault(RowKind.GENRE)
        val media = (rowMedia.item(i) as? HTMLSelectElement)?.value?.takeIf { it.isNotEmpty() }
        val id = (rowIds.item(i) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: genId("row")
        RowConfig(
            id        = id,
            kind      = kind,
            title     = (rowTitles.item(i) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() },
            enabled   = (rowEnabled.item(i) as? HTMLInputElement)?.checked ?: true,
            order     = i,
            mediaKind = media,
        )
    }
    val mergeNewlyAdded = (container.querySelector("#merge-newly-added") as? HTMLInputElement)?.checked ?: false
    val heroHeight   = (container.querySelector("#hero-height") as? HTMLInputElement)?.value?.toIntOrNull() ?: 56
    val autoAdvance  = (container.querySelector("#auto-advance") as? HTMLSelectElement)?.value?.toIntOrNull() ?: 6
    val uiLanguage   = (container.querySelector("#beh-lang") as? HTMLSelectElement)?.value ?: "en"
    val defaultSkin  = runCatching { Skin.valueOf((container.querySelector("#beh-skin") as? HTMLSelectElement)?.value ?: "AURORA") }
        .getOrDefault(Skin.AURORA)
    val tileShape    = runCatching { TileShape.valueOf((container.querySelector("#beh-tile") as? HTMLSelectElement)?.value ?: "POSTER") }
        .getOrDefault(TileShape.POSTER)
    val allowOverride = (container.querySelector("#beh-skin-override") as? HTMLInputElement)?.checked ?: true
    val showProgress  = (container.querySelector("#beh-progress") as? HTMLInputElement)?.checked ?: true
    currentConfig = RaviloConfig(
        heroes = heroes,
        channels = channels,
        rows = rows,
        mergeNewlyAdded = mergeNewlyAdded,
        defaultSkin = defaultSkin,
        allowSkinOverride = allowOverride,
        // Operator-level layout only; preserve the viewer's own skin choice across admin saves.
        viewerSkinOverride = currentConfig.viewerSkinOverride,
        showContinueProgress = showProgress,
        tileShape = tileShape,
        uiLanguage = uiLanguage,
        heroHeightPct = heroHeight,
        autoAdvanceSeconds = autoAdvance,
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun String.htmlEsc() = replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

private fun showMsg(el: HTMLElement, msg: String, ok: Boolean) {
    el.textContent = msg
    el.className = if (ok) "msg ok" else "msg bad"
    el.style.display = "block"
    window.setTimeout({ el.style.display = "none"; null }, 4_000)
}
