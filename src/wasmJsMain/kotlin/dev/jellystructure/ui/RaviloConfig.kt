package dev.jellystructure.ui

import dev.jellystructure.api.BatchCountRequest
import dev.jellystructure.api.JellyfinUser
import dev.jellystructure.api.AdminConfigResponse
import dev.jellystructure.api.RaviloApi
import dev.jellystructure.Router
import dev.jellystructure.historyPushState
import dev.jellystructure.historyReplaceState
import dev.jellystructure.scrollIntoViewSmooth
import dev.jellystructure.shared.tv.ChannelButtonPadding
import dev.jellystructure.shared.tv.ChannelButtonSpec
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.ChannelRowsConfig
import dev.jellystructure.shared.tv.ChannelSystemRows
import dev.jellystructure.shared.tv.ChannelStyle
import dev.jellystructure.shared.tv.PageHeroConfig
import dev.jellystructure.shared.tv.ChartListSpec
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.PortraitConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TileShape
import dev.jellystructure.shared.tv.UiDensity
import kotlin.js.JsString
import kotlinx.browser.document
import kotlinx.browser.window
import dev.jellystructure.api.MediaApi
import org.w3c.files.File
import org.w3c.files.FileReader
import dev.jellystructure.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.random.Random
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

// The editor binds directly to the shared `RaviloConfig` (Constitution Invariant 2): the bytes this
// screen PUTs are the same bytes the TV reads via `GET /api/tv/config`.

private const val GLOBAL_SCOPE = "__global__"

private var currentUserId: String = GLOBAL_SCOPE   // R51: default to global scope
private var currentScopeIsGlobal: Boolean = true
private var currentHasOverride: Boolean = false
private var currentConfig: RaviloConfig = RaviloConfig()
private var rcScope: CoroutineScope? = null
private var rcContainerRef: Element? = null
private var popstateWired = false
private var discoverSpecs: List<ChartListSpec> = emptyList()  // R50 — available charts for the edited region
private var discoverCoverage: dev.jellystructure.shared.tv.DiscoverCoverageResponse? = null  // R154

private suspend fun loadDiscoverForRegion(region: String) {
    discoverSpecs = runCatching { RaviloApi.getDiscoverLists(region) }.getOrDefault(discoverSpecs)
    discoverCoverage = runCatching { RaviloApi.getDiscoverCoverage(region) }.getOrNull()
}

// Provider display names for the Top 10 list UI — id matches the backend ChartProvider id
private val DISCOVER_PROVIDER_NAMES = mapOf(
    "netflix" to "Netflix", "max" to "Max", "disney" to "Disney+",
    "prime" to "Amazon Prime", "apple" to "Apple TV+",
    "viaplay" to "Viaplay", "paramount" to "Paramount+", "skyshowtime" to "SkyShowtime",
)
private val DISCOVER_REGIONS = listOf("DK" to "Denmark", "NO" to "Norway", "SE" to "Sweden", "FI" to "Finland", "IS" to "Iceland", "GB" to "United Kingdom", "US" to "United States", "DE" to "Germany", "FR" to "France")
private var users: List<JellyfinUser> = emptyList()
private var facets: Map<String, List<String>> = emptyMap() // "NETWORK"/"STUDIO"/"GENRE"/"TAG" -> values
// Client-side scope config cache: avoids a server round-trip when switching between scopes the user
// has already visited. Invalidated immediately before each successful Save.
private val scopeConfigCache = HashMap<String, AdminConfigResponse>()

private val AUTO_ADVANCE_OPTIONS = listOf(0 to "Off", 4 to "4 s", 6 to "6 s", 7 to "7 s", 8 to "8 s", 10 to "10 s")
private val BRAND_COLOR_PRESETS = listOf(
    "HBO"         to "linear-gradient(135deg,#3b2a78,#15102e)",
    "Netflix"     to "linear-gradient(135deg,#e50914,#831010)",
    "Disney+"     to "linear-gradient(135deg,#003399,#001a66)",
    "Apple TV+"   to "linear-gradient(135deg,#1c1c1e,#000000)",
    "TV 2"        to "linear-gradient(135deg,#e3122b,#7d0a1a)",
    "DR"          to "linear-gradient(135deg,#1455d8,#0a2766)",
    "Teal"        to "linear-gradient(135deg,#0a93a6,#063d47)",
    "Amber"       to "linear-gradient(135deg,#f5a623,#8a620d)",
    "Forest"      to "linear-gradient(135deg,#1a7a3e,#0d3d1e)",
    "Dark"        to "linear-gradient(135deg,#1a1a2e,#0a0a14)",
)

private fun genId(prefix: String) = "$prefix-${Random.nextInt(100_000, 999_999)}"

private fun wbMode(match: String) = if (match == "ANY") MatchMode.ANY else MatchMode.ALL
private fun wbConds(conds: List<WbCond>): List<Condition> =
    conds.filter { it.values.isNotEmpty() || it.facet == "track_title" || (it.facet == "content_row" && it.rows.isNotEmpty()) }
        .map { Condition(it.facet, it.op, it.values.toList(), it.rows.toList()) }
private fun wbCondsFrom(conds: List<Condition>): List<WbCond> =
    conds.map { WbCond(it.facet, it.op, it.values.toMutableList(), it.rows.toMutableList()) }

// The jellyfish brand mark (matches design/app/ravilo-config.html).
private const val RAVILO_MARK = """<svg viewBox="12 20 76 76" aria-hidden="true" style="width:1.12em;height:1.12em;flex:none;filter:drop-shadow(0 0 8px rgba(123,110,240,.5))"><defs><linearGradient id="ravJelly" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7b6ef0"/><stop offset="1" stop-color="#3fb6f5"/></linearGradient></defs><path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#ravJelly)"/><g stroke="url(#ravJelly)" stroke-width="4.5" stroke-linecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>"""

fun renderRaviloConfig(container: Element, scope: CoroutineScope) {
    rcScope = scope
    rcContainerRef = container
    container.innerHTML = buildLoadingShell()
    scope.launch {
        users = runCatching { RaviloApi.getUsers() }.getOrDefault(emptyList())
        if (users.isEmpty()) {
            container.innerHTML = buildErrorShell("Could not load Jellyfin users — check your connection in Settings.")
            return@launch
        }
        facets = loadFacets()
        // R51: always start in global scope
        currentUserId = GLOBAL_SCOPE; currentScopeIsGlobal = true; currentHasOverride = false
        val resp = runCatching { RaviloApi.getConfigWithMeta(scope = "global") }.getOrNull()
        currentConfig = resp?.config ?: RaviloConfig()
        loadDiscoverForRegion(currentConfig.discover.region)
        if (!popstateWired) {
            popstateWired = true
            window.addEventListener("popstate") { _ ->
                val c = rcContainerRef ?: return@addEventListener
                val s = rcScope ?: return@addEventListener
                handleRaviloRoute(c, s)
            }
        }
        handleRaviloRoute(container, scope)
    }
}

private fun handleRaviloRoute(container: Element, scope: CoroutineScope) {
    val channelId = Router.currentQuery()["channel"]
    if (!channelId.isNullOrBlank()) {
        val idx = currentConfig.channels.indexOfFirst { it.id == channelId }
        if (idx >= 0) {
            openChannelEditorPage(container, scope, idx, currentConfig.channels[idx])
            return
        }
    }
    renderFull(container, scope)
}

private suspend fun loadFacets(): Map<String, List<String>> = runCatching {
    // /api/media/meta-facets returns all four in one call (one SQLite pass).
    val f = MediaApi.metaFacets() ?: return@runCatching emptyMap()
    mapOf(
        "NETWORK" to f.networks.map { it.value },
        "STUDIO"  to f.studios.map { it.value },
        "GENRE"   to f.genres.map { it.value },
        "TAG"     to f.tags.map { it.value },
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
    return """
    <style>
      /* Sticky section nav (anchored side menu) — mirrors wf.css .navitem, with button-chrome reset. */
      .rav-nav-item {
        display:block; width:100%; text-align:left; background:none; border:none; cursor:pointer;
        font-family:inherit; font-size:.9rem; font-weight:500; color:var(--ink-soft);
        padding:9px 11px; border-radius:var(--radius-s);
        transition:background .15s ease, color .15s ease;
      }
      .rav-nav-item:hover { background:var(--fill-3); color:var(--ink); }
      .rav-nav-item.active { background:var(--hi-soft); color:var(--acc-ink); font-weight:600; }
    </style>
    ${datalistsHtml()}
    <div class="pagebar" style="position:sticky;top:0;z-index:50;background:var(--bg);border-bottom:1px solid var(--line);margin-bottom:18px">
      <h1 style="display:flex;align-items:center;gap:.4em">$RAVILO_MARK Ravilo TV</h1>
      <span class="badge info">app config</span>
      <span class="spacer"></span>
      <button id="pair-tv-btn" class="btn sm"><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:5px;vertical-align:-2px"><rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/></svg>Pair a TV</button>
      <span class="badge ok" id="rav-synced">saved · synced</span>
      <button id="rav-save" class="btn primary">Save</button>
    </div>
    <p class="page-sub">
      Lay out the Ravilo home screen your viewers see on their TVs. Ravilo connects only to
      Jellystructure — browsing, search and this layout are served by us; media streams from Jellyfin.
    </p>
    <!-- R51 scope switcher -->
    <div class="note blue" style="margin-bottom:18px;display:flex;gap:12px;align-items:center;flex-wrap:wrap">
      <div style="display:flex;gap:6px;flex:none">
        <button id="rav-scope-global" class="btn sm${if (currentScopeIsGlobal) " primary" else " ghost"}">🌐 Global · all users</button>
        <button id="rav-scope-user"   class="btn sm${if (!currentScopeIsGlobal) " primary" else " ghost"}">👤 A specific user</button>
      </div>
      ${if (!currentScopeIsGlobal) {
          val selOpts = users.joinToString("") { u ->
              val sel = if (u.id == currentUserId) " selected" else ""
              """<option value="${u.id}"$sel>${u.displayName.htmlEsc()}</option>"""
          }
          """<select id="rav-user-pick" class="input" style="width:auto;min-width:180px;font-size:.85rem">$selOpts</select>"""
      } else ""}
      <div class="tiny" id="rav-scope-hint" style="flex:1;color:var(--ink-soft)">
        ${if (currentScopeIsGlobal) "Default layout for all users. Any user without a custom layout sees this."
          else if (currentHasOverride) "Custom layout — overrides the global for this user only. <b>Global changes won't reach them.</b>"
          else "This user uses the global layout."}
      </div>
      ${if (!currentScopeIsGlobal && currentHasOverride) """<button id="rav-remove-override" class="btn sm ghost" style="color:var(--bad)">Remove custom layout</button>""" else ""}
    </div>
    ${if (!currentScopeIsGlobal && !currentHasOverride) """
    <div class="card" id="rav-lock-card" style="margin-bottom:18px;padding:18px 20px;display:flex;align-items:center;gap:14px;">
      <span style="font-size:1.4rem">🔒</span>
      <div class="col" style="flex:1">
        <b>${users.find { it.id == currentUserId }?.displayName?.htmlEsc() ?: currentUserId} uses the global layout</b>
        <div class="tiny muted">Create a custom layout to give them their own personalised Ravilo home.</div>
      </div>
      <button id="rav-create-override" class="btn">Create custom layout</button>
    </div>
    """ else ""}
    <div id="rav-msg" style="display:none;margin-bottom:12px"></div>

    <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap">
      <nav style="width:150px;flex-shrink:0;position:sticky;top:88px">
        <div style="display:flex;flex-direction:column;gap:2px">
          <button data-rav-sect="sect-heroes"   class="rav-nav-item">Hero carousel</button>
          <button data-rav-sect="sect-channels" class="rav-nav-item">Channels</button>
          <button data-rav-sect="sect-rows"     class="rav-nav-item">Content rows</button>
          <button data-rav-sect="sect-discover" class="rav-nav-item">Top 10</button>
          <button data-rav-sect="sect-behaviour"class="rav-nav-item">Behaviour</button>
          <button data-rav-sect="sect-portrait" class="rav-nav-item">Portrait screen</button>
        </div>
      </nav>
      <div class="col fill" style="min-width:280px" id="rav-sections">
        <div id="sect-pair"></div>
        <div id="sect-heroes"></div>
        <div id="sect-channels"></div>
        <div id="sect-rows"></div>
        <div id="sect-discover"></div>
        <div id="sect-behaviour"></div>
        <div id="sect-portrait"></div>
      </div>
      <div class="card" style="width:280px;flex:none;position:sticky;top:88px;padding:12px">
        <div class="row center" style="margin:2px 4px 10px"><h4 style="margin:0">Live preview</h4><span class="spacer"></span><span class="badge ok" style="font-size:.6rem">this user</span></div>
        <div id="rav-preview"></div>
        <div class="tiny muted" style="margin-top:8px;text-align:center">Reflects the current edits</div>
      </div>
    </div>
    """.trimIndent()
}

private fun reloadScopeIntoSections(container: Element, scope: CoroutineScope) {
    val cacheKey = if (currentScopeIsGlobal) "global" else currentUserId
    val cached = scopeConfigCache[cacheKey]
    if (cached != null) {
        currentConfig = cached.config
        currentHasOverride = cached.hasOverride
        renderFull(container, scope)
        return
    }
    (container.querySelector("#rav-sections") as? HTMLElement)?.innerHTML =
        """<p class="page-sub" style="color:var(--ink-soft);padding:24px 0">Loading…</p>"""
    scope.launch {
        val resp = runCatching {
            if (currentScopeIsGlobal) RaviloApi.getConfigWithMeta(scope = "global")
            else RaviloApi.getConfigWithMeta(userId = currentUserId)
        }.getOrNull()
        if (resp != null) scopeConfigCache[cacheKey] = resp
        currentConfig = resp?.config ?: RaviloConfig()
        currentHasOverride = resp?.hasOverride ?: false
        loadDiscoverForRegion(currentConfig.discover.region)
        renderFull(container, scope)
    }
}

private fun wireShell(container: Element, scope: CoroutineScope) {
    // R51 scope switcher — global
    container.querySelector("#rav-scope-global")?.addEventListener("click") { _ ->
        if (currentScopeIsGlobal) return@addEventListener
        currentScopeIsGlobal = true; currentUserId = GLOBAL_SCOPE; currentHasOverride = false
        reloadScopeIntoSections(container, scope)
    }

    // R51 scope switcher — specific user
    container.querySelector("#rav-scope-user")?.addEventListener("click") { _ ->
        if (!currentScopeIsGlobal) return@addEventListener
        currentScopeIsGlobal = false
        val firstUser = users.firstOrNull()?.id ?: return@addEventListener
        if (currentUserId == GLOBAL_SCOPE) currentUserId = firstUser
        reloadScopeIntoSections(container, scope)
    }

    // User picker (only present when scope = specific user)
    container.querySelector("#rav-user-pick")?.addEventListener("change") { _ ->
        val sel = container.querySelector("#rav-user-pick") as? HTMLSelectElement ?: return@addEventListener
        currentUserId = sel.value; currentScopeIsGlobal = false
        reloadScopeIntoSections(container, scope)
    }

    // Create custom layout — copy global into user record
    container.querySelector("#rav-create-override")?.addEventListener("click") { _ ->
        val uId = currentUserId
        scope.launch {
            val global = runCatching { RaviloApi.getConfigWithMeta(scope = "global") }.getOrNull()?.config ?: RaviloConfig()
            runCatching { RaviloApi.putConfig(uId, global) }
            currentHasOverride = true
            renderFull(container, scope)
        }
    }

    // Remove custom layout
    container.querySelector("#rav-remove-override")?.addEventListener("click") { _ ->
        val uId = currentUserId
        scope.launch {
            val msg = container.querySelector("#rav-msg") as? HTMLElement ?: return@launch
            runCatching { RaviloApi.removeUserConfig(uId) }.fold(
                onSuccess = {
                    currentHasOverride = false
                    val resp = runCatching { RaviloApi.getConfigWithMeta(scope = "global") }.getOrNull()
                    currentConfig = resp?.config ?: RaviloConfig()
                    renderFull(container, scope)
                },
                onFailure = { showMsg(msg, "Failed: ${it.message}", ok = false) },
            )
        }
    }

    // Save
    container.querySelector("#rav-save")?.addEventListener("click") { _ ->
        if (!currentScopeIsGlobal && !currentHasOverride) return@addEventListener  // lock state
        collectConfig(container)
        scope.launch {
            val msg = container.querySelector("#rav-msg") as? HTMLElement ?: return@launch
            runCatching {
                if (currentScopeIsGlobal) RaviloApi.putGlobalConfig(currentConfig)
                else RaviloApi.putConfig(currentUserId, currentConfig)
            }.fold(
                onSuccess = {
                    // Invalidate cache so the next scope switch re-fetches fresh data
                    scopeConfigCache.remove(if (currentScopeIsGlobal) "global" else currentUserId)
                    showMsg(msg, "Saved.", ok = true)
                },
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
                for (j in 0 until btns.length) (btns.item(j) as? HTMLElement)?.classList?.remove("active")
                btn.classList.add("active")
                container.querySelector("#$sectId")?.let { scrollIntoViewSmooth(it) }
            }
        }
    }

    // Delegated listeners on the sections host: keep the live preview in sync with edits.
    val sections = container.querySelector("#rav-sections")
    sections?.addEventListener("input") { ev ->
        val t = ev.target
        if (t is HTMLInputElement && t.id == "hero-height") {
            val pct = t.value
            container.querySelector("#hero-height-val")?.textContent = "$pct%"
            // Targeted update: only move the hero bar in the schematic preview — no full rebuild
            val prevHero = container.querySelector("#rav-prev-hero") as? HTMLElement
            if (prevHero != null) {
                prevHero.style.height = "$pct%"
                collectConfig(container)
            } else {
                collectConfig(container); renderPreview(container)
            }
        }
    }
    sections?.addEventListener("change") { ev ->
        val t = ev.target
        if (t is HTMLSelectElement && t.hasAttribute("data-row-kind")) {
            val idx = t.getAttribute("data-row-kind")
            val title = container.querySelector("[data-row-title='$idx']") as? HTMLInputElement
            if (t.value == "GENRE") title?.setAttribute("list", "facet-genre") else title?.removeAttribute("list")
        }
        collectConfig(container); renderPreview(container)
    }
    sections?.addEventListener("click") { ev ->
        val t = ev.target as? HTMLElement ?: return@addEventListener
        if (!t.hasAttribute("data-tile-shape")) return@addEventListener
        val pill = container.querySelector("#beh-tile-pill") ?: return@addEventListener
        val btns = pill.querySelectorAll("button")
        for (k in 0 until btns.length) (btns.item(k) as? HTMLElement)?.classList?.remove("on")
        t.classList.add("on")
        collectConfig(container); renderPreview(container)
    }
    // R159 — portrait hero-height slider: live label + thumbnail (full renderPreview — the portrait
    // thumbnail is small and infrequent to drag, so the landscape slider's targeted-update optimization
    // isn't worth mirroring here).
    sections?.addEventListener("input") { ev ->
        val t = ev.target
        if (t is HTMLInputElement && t.id == "portrait-hero-height") {
            container.querySelector("#portrait-hero-height-val")?.textContent = "${t.value}%"
            collectConfig(container); renderPreview(container)
        }
    }
    // R159 — the toggle enables/disables the slider and flips the live-preview thumbnail.
    sections?.addEventListener("change") { ev ->
        val t = ev.target
        if (t is HTMLInputElement && t.id == "portrait-enable") {
            (container.querySelector("#portrait-hero-height") as? HTMLInputElement)?.disabled = !t.checked
        }
    }
}

private fun renderSections(container: Element, scope: CoroutineScope) {
    renderPair(container, scope)
    renderHeroes(container)
    renderChannels(container)
    renderRows(container)
    renderDiscover(container)
    renderBehaviour(container)
    renderPortrait(container)
    renderPreview(container)
}

/** Capture current DOM edits, apply a structural change, then re-render the given section + preview. */
private fun structural(container: Element, mutate: () -> Unit, rerender: (Element) -> Unit) {
    collectConfig(container)
    mutate()
    rerender(container)
    renderPreview(container)
}

// ── Pair a TV (modal, opened from sticky pagebar button) ──────────────────────

private fun renderPair(container: Element, scope: CoroutineScope) {
    document.getElementById("pair-back")?.let { it.parentElement?.removeChild(it) }
    container.querySelector("#sect-pair")?.let { (it as? HTMLElement)?.style?.display = "none" }

    val userOptions = if (users.isEmpty()) {
        """<option value="">No users found</option>"""
    } else {
        users.joinToString("") { u -> """<option value="${u.id.htmlEsc()}">${u.displayName.htmlEsc()}</option>""" }
    }

    val modal = document.createElement("div") as HTMLElement
    modal.id = "pair-back"
    modal.className = "pair-back"
    modal.innerHTML = """
        <div class="card pair-modal">
          <div class="row center" style="gap:11px;margin-bottom:4px">
            <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>
            </svg>
            <h3 style="margin:0">Pair a TV</h3>
            <span class="spacer"></span><span class="x" id="pair-x">&#x2715;</span>
          </div>
          <p class="tiny muted" style="line-height:1.55;margin:4px 0 16px">On the TV, open <b>Ravilo &#x2192; Add user</b>. Choose which Jellyfin user this TV signs in as, then enter the 6-character code shown on screen.</p>
          <div class="field" style="margin-bottom:15px">
            <label>Sign in as</label>
            <select id="pair-user-sel" class="input" style="width:100%">$userOptions</select>
          </div>
          <div class="field" style="margin-bottom:0">
            <label>Pairing code</label>
            <div class="code-inputs" id="pair-code-inputs">
              <input maxlength="1" data-ci="0" autocomplete="off" aria-label="code 1" spellcheck="false">
              <input maxlength="1" data-ci="1" autocomplete="off" aria-label="code 2" spellcheck="false">
              <input maxlength="1" data-ci="2" autocomplete="off" aria-label="code 3" spellcheck="false">
              <input maxlength="1" data-ci="3" autocomplete="off" aria-label="code 4" spellcheck="false">
              <input maxlength="1" data-ci="4" autocomplete="off" aria-label="code 5" spellcheck="false">
              <input maxlength="1" data-ci="5" autocomplete="off" aria-label="code 6" spellcheck="false">
            </div>
          </div>
          <div class="tiny muted" style="margin-top:9px">The code expires after a few minutes.</div>
          <div id="pair-result" style="margin-top:10px;font-size:.85rem;display:none"></div>
          <div class="row" style="justify-content:flex-end;gap:8px;margin-top:18px">
            <button class="btn ghost" id="pair-cancel">Cancel</button>
            <button class="btn primary" id="pair-go" disabled>Pair TV</button>
          </div>
        </div>
    """.trimIndent()
    document.body?.appendChild(modal)

    fun closeModal() { modal.classList.remove("open") }
    container.querySelector("#pair-tv-btn")?.addEventListener("click") { _ -> modal.classList.add("open") }
    modal.querySelector("#pair-x")?.addEventListener("click") { _ -> closeModal() }
    modal.querySelector("#pair-cancel")?.addEventListener("click") { _ -> closeModal() }
    modal.addEventListener("click") { ev -> if (ev.target == modal) closeModal() }

    val codeInputs = modal.querySelectorAll("#pair-code-inputs input")
    val pairGoBtn = modal.querySelector("#pair-go") as? HTMLElement
    val resultEl = modal.querySelector("#pair-result") as? HTMLElement

    fun getCode(): String = buildString {
        for (k in 0 until codeInputs.length) append((codeInputs.item(k) as? HTMLInputElement)?.value?.uppercase() ?: "")
    }
    fun updatePairBtn() {
        if (getCode().length == 6) pairGoBtn?.removeAttribute("disabled")
        else pairGoBtn?.setAttribute("disabled", "true")
    }
    for (k in 0 until codeInputs.length) {
        val inp = codeInputs.item(k) as? HTMLInputElement ?: continue
        inp.addEventListener("input") { _ ->
            inp.value = inp.value.uppercase().take(1)
            if (inp.value.isNotEmpty() && k < codeInputs.length - 1) (codeInputs.item(k + 1) as? HTMLInputElement)?.focus()
            updatePairBtn()
        }
        inp.addEventListener("keydown") { ev ->
            if ((ev as? org.w3c.dom.events.KeyboardEvent)?.key == "Backspace" && inp.value.isEmpty() && k > 0)
                (codeInputs.item(k - 1) as? HTMLInputElement)?.focus()
        }
    }
    pairGoBtn?.addEventListener("click") { _ ->
        val code = getCode(); if (code.length != 6) return@addEventListener
        pairGoBtn.setAttribute("disabled", "true")
        resultEl?.textContent = "Pairing…"; resultEl?.style?.display = "block"
        scope.launch {
            runCatching { RaviloApi.approvePairing(code) }.fold(
                onSuccess = {
                    resultEl?.textContent = "✓ TV paired successfully!"
                    resultEl?.setAttribute("style", "margin-top:10px;font-size:.85rem;color:var(--ok);display:block")
                    for (k in 0 until codeInputs.length) (codeInputs.item(k) as? HTMLInputElement)?.value = ""
                    pairGoBtn.setAttribute("disabled", "true")
                },
                onFailure = { err ->
                    resultEl?.textContent = "Failed: ${err.message ?: "unknown error"}"
                    resultEl?.setAttribute("style", "margin-top:10px;font-size:.85rem;color:var(--bad);display:block")
                    pairGoBtn.removeAttribute("disabled")
                },
            )
        }
    }
}

// ── Heroes ────────────────────────────────────────────────────────────────────

private fun heroGradient(id: String): String {
    val hue = id.fold(0) { acc, c -> acc * 31 + c.code }.absoluteValue % 360
    return "linear-gradient(145deg,hsl($hue 48% 36%),hsl(${(hue + 45) % 360} 52% 14%))"
}

/** Resolve display hints for any hero items that predate the display-hint fields. One-shot. */
private var heroHintsResolving = false
private fun resolveHeroDisplayHints(container: Element) {
    if (heroHintsResolving) return
    val toResolve = currentConfig.heroes.withIndex()
        .filter { (_, h) -> h.displayTitle == null && h.itemId.isNotBlank() }
    if (toResolve.isEmpty()) return
    val scope = rcScope ?: return
    heroHintsResolving = true
    scope.launch {
        try {
            // One parallel GET /api/media/{id} per hero item — typically 3–10 calls total vs
            // O(library/100) sequential pages that the previous paginated approach used.
            val fetched = toResolve.map { (idx, h) ->
                scope.async { idx to MediaApi.get(h.itemId) }
            }.awaitAll()
            val results = fetched.toMap()
            val updated = currentConfig.heroes.mapIndexed { i, h ->
                val item = results[i] ?: return@mapIndexed h
                val kindStr = if (item.kind.name == "TV_SHOW") "Series" else "Film"
                val meta = listOfNotNull(kindStr, item.network ?: item.studio, item.year?.toString()).joinToString(" · ")
                h.copy(displayTitle = item.title, displayMeta = meta, displayBackdrop = item.backdropPath)
            }
            if (updated != currentConfig.heroes) {
                currentConfig = currentConfig.copy(heroes = updated)
                renderHeroes(container)
            }
        } finally {
            heroHintsResolving = false
        }
    }
}

private fun renderHeroes(container: Element) {
    val sect = container.querySelector("#sect-heroes") ?: return
    val rows = currentConfig.heroes.mapIndexed { i, h ->
        val title = h.displayTitle ?: h.itemId.take(24)
        val meta = h.displayMeta ?: ""
        val bg = heroGradient(h.itemId.ifBlank { "$i" })
        val thumbStyle = if (!h.displayBackdrop.isNullOrBlank())
            "background:url('https://image.tmdb.org/t/p/w300${h.displayBackdrop}') center/cover,$bg"
        else "background:$bg"
        val badgeHtml = h.badge?.takeIf { it.isNotBlank() }?.let {
            """<span class="badge" style="font-size:.6rem;vertical-align:middle">${it.htmlEsc()}</span>"""
        } ?: ""
        val tagHtml = h.tagline?.takeIf { it.isNotBlank() }?.let {
            """<div class="tiny" style="color:var(--ink-soft);margin-top:1px;font-style:italic;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${it.htmlEsc()}</div>"""
        } ?: ""
        val toggleCls = if (h.enabled) " on" else ""
        val rowCls = if (h.enabled) "" else " off"
        val shortTitle = title.take(12) + if (title.length > 12) "…" else ""
        """
        <div class="cfg-row$rowCls" draggable="true" data-hero-i="$i">
          <span class="drag-handle" style="cursor:grab;user-select:none;flex-shrink:0">⠿</span>
          <div class="hero-thumb" style="$thumbStyle">
            <span class="t">${shortTitle.htmlEsc()}</span>
          </div>
          <div style="flex:1;min-width:0">
            <div class="nm" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${title.htmlEsc()} $badgeHtml</div>
            <div class="src">${meta.htmlEsc()}</div>
            $tagHtml
          </div>
          <button class="btn sm ghost" data-hero-edit="$i" title="Edit badge &amp; tagline" style="flex-shrink:0;padding:4px 8px">✎</button>
          <span class="muted tiny" style="flex-shrink:0;font-size:.75rem">${if (h.enabled) "show" else "hidden"}</span>
          <span class="toggle$toggleCls" data-hero-tog="$i" style="cursor:pointer;flex-shrink:0"></span>
          <button class="btn sm ghost" data-hero-del="$i" style="flex-shrink:0">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("\n")
    val autoOptions = AUTO_ADVANCE_OPTIONS.joinToString("") { (v, label) ->
        val sel = if (v == currentConfig.autoAdvanceSeconds) " selected" else ""
        """<option value="$v"$sel>$label</option>"""
    }
    val emptyHint = if (currentConfig.heroes.isEmpty())
        """<div class="tiny muted" style="padding:10px 2px">No hero items yet — click <b>+ Add hero item</b> to pin titles to the top banner.</div>"""
    else ""
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div class="row center" style="margin-bottom:8px">
            <div style="font-weight:600;font-size:1.02rem">Home hero carousel</div>
            <span class="badge info" style="margin-left:8px;flex-shrink:0">1–10 items</span>
            <span class="spacer"></span>
            <button id="hero-add" class="btn sm ghost">+ Add hero item</button>
          </div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin:0 0 14px">
            The top banner on the Home page. Drag to reorder; the carousel auto-advances on the TV.
            Each channel can have its own hero too — set it in the channel's editor.
          </p>
          <div id="herolist" style="display:flex;flex-direction:column;gap:9px">
            $rows
            $emptyHint
          </div>
          <hr class="dash" style="margin:16px 0">
          <div class="row" style="gap:24px;flex-wrap:wrap;align-items:flex-end">
            <div style="flex:1;min-width:220px">
              <label style="display:block;font-size:.85rem;margin-bottom:4px">Hero height <span class="mono" id="hero-height-val">${currentConfig.heroHeightPct}%</span> of screen</label>
              <input type="range" id="hero-height" min="40" max="100" value="${currentConfig.heroHeightPct}" style="width:100%;accent-color:var(--acc,#7b6ef0)">
              <span style="font-size:.76rem;color:var(--ink-soft)">How much of the TV screen the banner fills (40–100%).</span>
            </div>
            <div style="width:180px">
              <label style="display:block;font-size:.85rem;margin-bottom:4px">Auto-advance</label>
              <select id="auto-advance" class="input" style="width:100%;font-size:.85rem">$autoOptions</select>
            </div>
          </div>
        </div>
    """.trimIndent()
    sect.querySelector("#hero-add")?.addEventListener("click") { _ -> openHeroAddPicker(container) }
    // Drag-and-drop reorder
    val heroList = sect.querySelector("#herolist") as? HTMLElement
    if (heroList != null) {
        var dragFromIdx = -1
        fun clearHeroDragHighlights() {
            val nl = heroList.querySelectorAll("[data-hero-i]")
            for (j in 0 until nl.length) {
                (nl.item(j) as? HTMLElement)?.classList?.remove("dragging", "drop-before", "drop-after")
            }
        }
        heroList.addEventListener("dragstart") { e ->
            val row = (e.target as? HTMLElement)?.closest("[data-hero-i]") as? HTMLElement ?: return@addEventListener
            dragFromIdx = row.getAttribute("data-hero-i")?.toIntOrNull() ?: -1
            row.classList.add("dragging")
        }
        heroList.addEventListener("dragend") { _ ->
            dragFromIdx = -1
            clearHeroDragHighlights()
        }
        heroList.addEventListener("dragover") { e ->
            e.preventDefault()
            val row = (e.target as? HTMLElement)?.closest("[data-hero-i]") as? HTMLElement ?: return@addEventListener
            val toIdx = row.getAttribute("data-hero-i")?.toIntOrNull() ?: return@addEventListener
            clearHeroDragHighlights()
            if (toIdx != dragFromIdx) row.classList.add(if (toIdx < dragFromIdx) "drop-before" else "drop-after")
        }
        heroList.addEventListener("drop") { e ->
            e.preventDefault()
            val row = (e.target as? HTMLElement)?.closest("[data-hero-i]") as? HTMLElement ?: return@addEventListener
            val toIdx = row.getAttribute("data-hero-i")?.toIntOrNull() ?: return@addEventListener
            val fromIdx = dragFromIdx
            dragFromIdx = -1
            if (fromIdx < 0 || fromIdx == toIdx) return@addEventListener
            val list = currentConfig.heroes.toMutableList()
            val item = list.removeAt(fromIdx)
            list.add(toIdx, item)
            currentConfig = currentConfig.copy(heroes = list)
            renderHeroes(container)
        }
    }
    sect.querySelector("#hero-height")?.addEventListener("input") { _ ->
        val v = (sect.querySelector("#hero-height") as? HTMLInputElement)?.value?.toIntOrNull() ?: return@addEventListener
        currentConfig = currentConfig.copy(heroHeightPct = v)
        (sect.querySelector("#hero-height-val") as? HTMLElement)?.textContent = "$v%"
    }
    sect.querySelector("#auto-advance")?.addEventListener("change") { _ ->
        val v = (sect.querySelector("#auto-advance") as? HTMLSelectElement)?.value?.toIntOrNull() ?: return@addEventListener
        currentConfig = currentConfig.copy(autoAdvanceSeconds = v)
    }
    for (i in currentConfig.heroes.indices) {
        sect.querySelector("[data-hero-del='$i']")?.addEventListener("click") { _ ->
            val list = currentConfig.heroes.toMutableList(); list.removeAt(i)
            currentConfig = currentConfig.copy(heroes = list)
            renderHeroes(container)
        }
        sect.querySelector("[data-hero-tog='$i']")?.addEventListener("click") { _ ->
            val list = currentConfig.heroes.toMutableList()
            list[i] = list[i].copy(enabled = !list[i].enabled)
            currentConfig = currentConfig.copy(heroes = list)
            renderHeroes(container)
        }
        sect.querySelector("[data-hero-edit='$i']")?.addEventListener("click") { _ ->
            openHeroEditModal(container, i)
        }
    }
    // Backfill display hints for heroes configured before hint fields were added.
    resolveHeroDisplayHints(container)
}

private fun openHeroAddPicker(container: Element) {
    val scope = rcScope ?: return
    document.getElementById("hero-add-ov")?.remove()
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "hero-add-ov"
    overlay.setAttribute("style", "position:fixed;inset:0;background:#000b;display:flex;align-items:center;justify-content:center;z-index:1200;padding:20px")
    overlay.innerHTML = """
        <div style="background:var(--fill);color:var(--ink);border:1px solid var(--line);border-radius:16px;padding:20px;width:min(520px,100%);box-shadow:var(--shadow);display:flex;flex-direction:column;gap:0;max-height:90vh">
          <div style="font-weight:700;font-size:1.05rem;margin-bottom:12px">Add hero item</div>
          <input id="hero-pick-q" class="input" style="width:100%;margin-bottom:10px" placeholder="Search your library…" autocomplete="off">
          <div id="hero-pick-results" style="flex:1;overflow-y:auto;min-height:80px;max-height:260px;display:flex;flex-direction:column;gap:4px"></div>
          <div id="hero-pick-form" style="display:none;border-top:1px solid var(--line);margin-top:14px;padding-top:14px">
            <div class="tiny muted" style="margin-bottom:10px">Optional — add a badge or tagline for this hero:</div>
            <div class="row" style="gap:10px;flex-wrap:wrap;align-items:center">
              <select id="hero-pick-badge" class="input" style="width:148px;font-size:.85rem">
                <option value="">No badge</option>
                <option>New Season</option><option>4K</option><option>Top 10</option><option>Premiere</option>
              </select>
              <input id="hero-pick-tag" class="input" style="flex:1;min-width:140px" placeholder="Tagline / kicker…">
              <label style="display:flex;gap:6px;align-items:center;font-size:.82rem;white-space:nowrap;flex-shrink:0"><input type="checkbox" id="hero-pick-logo" checked> Logo overlay</label>
            </div>
          </div>
          <div class="row" style="gap:8px;justify-content:flex-end;margin-top:14px;flex-shrink:0">
            <button id="hero-pick-cancel" class="btn sm ghost">Cancel</button>
            <button id="hero-pick-add" class="btn sm" disabled style="opacity:.45">Add to carousel</button>
          </div>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)

    var selectedItem: MediaItem? = null
    val addBtn = overlay.querySelector("#hero-pick-add") as? HTMLElement
    val form = overlay.querySelector("#hero-pick-form") as? HTMLElement
    val resultsDiv = overlay.querySelector("#hero-pick-results") as? HTMLElement
    val qInput = overlay.querySelector("#hero-pick-q") as? HTMLInputElement

    fun showResults(items: List<MediaItem>) {
        val content = if (items.isEmpty())
            """<div class="tiny muted" style="padding:10px 2px">No results found.</div>"""
        else items.joinToString("") { item ->
            val bg = heroGradient(item.jellyfinId ?: item.id)
            val thumbStyle = if (!item.backdropPath.isNullOrBlank())
                "background:url('https://image.tmdb.org/t/p/w300${item.backdropPath}') center/cover,$bg"
            else "background:$bg"
            val kindStr = if (item.kind.name == "TV_SHOW") "Series" else "Film"
            val metaParts = listOfNotNull(kindStr, item.network ?: item.studio, item.year?.toString())
            val meta = metaParts.joinToString(" · ")
            val pickId = item.jellyfinId ?: item.id
            val short = item.title.take(10) + if (item.title.length > 10) "…" else ""
            """<div data-pick-jid="${pickId.htmlEsc()}" class="cfg-row" style="padding:10px 12px;cursor:pointer;gap:10px;flex-shrink:0;transition:background .1s">
                 <div style="$thumbStyle;width:76px;height:44px;border-radius:6px;flex:none;position:relative;overflow:hidden">
                   <span style="position:absolute;left:5px;bottom:4px;font-weight:700;font-size:.62rem;color:#fff;text-shadow:0 1px 3px rgba(0,0,0,.8)">${short.htmlEsc()}</span>
                 </div>
                 <div style="flex:1;min-width:0">
                   <div class="nm" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${item.title.htmlEsc()}</div>
                   <div class="src">${meta.htmlEsc()}</div>
                 </div>
               </div>"""
        }
        resultsDiv?.innerHTML = content
        val nodeList = resultsDiv?.querySelectorAll("[data-pick-jid]") ?: return
        for (j in 0 until nodeList.length) {
            val el = nodeList.item(j) as? HTMLElement ?: continue
            val pickId = el.getAttribute("data-pick-jid") ?: continue
            val item = items.firstOrNull { (it.jellyfinId ?: it.id) == pickId } ?: continue
            el.addEventListener("click") { _ ->
                // Clear previously selected highlight
                val all = resultsDiv?.querySelectorAll("[data-pick-jid]")
                val n = all?.length ?: 0
                for (k in 0 until n) (all?.item(k) as? HTMLElement)?.setAttribute("style", "padding:10px 12px;cursor:pointer;gap:10px;flex-shrink:0;transition:background .1s")
                el.setAttribute("style", "padding:10px 12px;cursor:pointer;gap:10px;flex-shrink:0;background:var(--hi-soft);border-color:var(--hi)")
                selectedItem = item
                addBtn?.removeAttribute("disabled")
                addBtn?.setAttribute("style", "")
                form?.style?.display = "block"
            }
        }
    }

    scope.launch {
        val recent = MediaApi.list(pageSize = 8)?.items ?: emptyList()
        showResults(recent)
    }
    var searchJob: Job? = null
    qInput?.addEventListener("input") { _ ->
        searchJob?.cancel()
        val q = qInput.value.trim()
        searchJob = scope.launch {
            delay(250)
            val results = if (q.length >= 2) MediaApi.list(search = q, pageSize = 8)?.items ?: emptyList()
                          else MediaApi.list(pageSize = 8)?.items ?: emptyList()
            showResults(results)
            selectedItem = null
            addBtn?.setAttribute("disabled", ""); addBtn?.setAttribute("style", "opacity:.45")
            form?.style?.display = "none"
        }
    }
    overlay.querySelector("#hero-pick-cancel")?.addEventListener("click") { _ -> overlay.remove() }
    addBtn?.addEventListener("click") { _ ->
        val item = selectedItem ?: return@addEventListener
        val badge = (overlay.querySelector("#hero-pick-badge") as? HTMLSelectElement)?.value?.ifEmpty { null }
        val tag = (overlay.querySelector("#hero-pick-tag") as? HTMLInputElement)?.value?.ifEmpty { null }
        val logo = (overlay.querySelector("#hero-pick-logo") as? HTMLInputElement)?.checked ?: true
        val itemId = item.jellyfinId ?: item.id
        val kindStr = if (item.kind.name == "TV_SHOW") "Series" else "Film"
        val meta = listOfNotNull(kindStr, item.network ?: item.studio, item.year?.toString()).joinToString(" · ")
        val newHero = HeroConfig(itemId = itemId, enabled = true, badge = badge, tagline = tag,
            clearlogoOverlay = logo, displayTitle = item.title, displayMeta = meta, displayBackdrop = item.backdropPath)
        val list = currentConfig.heroes.toMutableList()
        val existing = list.indexOfFirst { it.itemId == itemId }
        if (existing >= 0) list[existing] = newHero else list.add(newHero)
        currentConfig = currentConfig.copy(heroes = list)
        overlay.remove()
        renderHeroes(container)
    }
    qInput?.focus()
}

private fun openHeroEditModal(container: Element, idx: Int) {
    val h = currentConfig.heroes.getOrNull(idx) ?: return
    document.getElementById("hero-edit-ov")?.remove()
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "hero-edit-ov"
    overlay.setAttribute("style", "position:fixed;inset:0;background:#000b;display:flex;align-items:center;justify-content:center;z-index:1200;padding:20px")
    val badgeOpts = listOf("", "New Season", "4K", "Top 10", "Premiere").joinToString("") { v ->
        val sel = if ((h.badge ?: "") == v) " selected" else ""
        """<option value="$v"$sel>${if (v.isEmpty()) "No badge" else v.htmlEsc()}</option>"""
    }
    val logoChecked = if (h.clearlogoOverlay) " checked" else ""
    val title = h.displayTitle ?: h.itemId
    overlay.innerHTML = """
        <div style="background:var(--fill);color:var(--ink);border:1px solid var(--line);border-radius:16px;padding:20px;width:min(420px,100%);box-shadow:var(--shadow)">
          <div style="font-weight:700;margin-bottom:14px">Edit — ${title.htmlEsc()}</div>
          <div class="field"><label>Badge</label><select id="heo-badge" class="input">$badgeOpts</select></div>
          <div class="field"><label>Tagline / kicker</label><input id="heo-tag" class="input" placeholder="e.g. The saga concludes" value="${(h.tagline ?: "").htmlEsc()}"></div>
          <label style="display:flex;gap:7px;align-items:center;margin-bottom:16px;font-size:.85rem"><input type="checkbox" id="heo-logo"$logoChecked> Clearlogo overlay (text-title fallback)</label>
          <div class="row" style="gap:8px;justify-content:flex-end">
            <button id="heo-cancel" class="btn sm ghost">Cancel</button>
            <button id="heo-save" class="btn sm">Save</button>
          </div>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)
    overlay.querySelector("#heo-cancel")?.addEventListener("click") { _ -> overlay.remove() }
    overlay.querySelector("#heo-save")?.addEventListener("click") { _ ->
        val badge = (overlay.querySelector("#heo-badge") as? HTMLSelectElement)?.value?.ifEmpty { null }
        val tag = (overlay.querySelector("#heo-tag") as? HTMLInputElement)?.value?.ifEmpty { null }
        val logo = (overlay.querySelector("#heo-logo") as? HTMLInputElement)?.checked ?: true
        val list = currentConfig.heroes.toMutableList()
        list[idx] = list[idx].copy(badge = badge, tagline = tag, clearlogoOverlay = logo)
        currentConfig = currentConfig.copy(heroes = list)
        overlay.remove()
        renderHeroes(container)
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

// R36/R66: the channel-button chip rendered on each config row + in the live preview.
// Scaled to a visible chip size while keeping the canonical 224:94 aspect ratio and 13/224 corner ratio.
private fun channelChipHtml(c: ChannelConfig): String {
    val chipW = 110; val chipH = (chipW / ChannelButtonSpec.ASPECT_RATIO).toInt()
    val chipR = (chipW * ChannelButtonSpec.CORNER_RATIO).toInt()
    val fill = channelFillCss(c.brandColor)
    val logoPad = c.paddingLogo?.let { p ->
        "padding:${p.top}px ${p.right}px ${p.bottom}px ${p.left}px;"
    } ?: ""
    val inner = if (c.style == ChannelStyle.LOGO && !c.logoUrl.isNullOrBlank()) {
        // R66: object-fit:contain matches the TV's ContentScale.Fit (no cropping)
        """<img src="${c.logoUrl!!.htmlEsc()}" alt="" style="width:100%;height:100%;object-fit:${ChannelButtonSpec.LOGO_FIT_CSS};$logoPad">"""
    } else {
        (if (c.style == ChannelStyle.LOGO) c.name.take(3).uppercase() else c.name.take(10).ifEmpty { "Ch" }).htmlEsc()
    }
    return """<div style="background:$fill;width:${chipW}px;height:${chipH}px;border-radius:${chipR}px;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700;font-size:.7rem;overflow:hidden;flex:none;position:relative">$inner</div>"""
}

/** Convert a channel brandColor (solid hex or linear-gradient) to a CSS background value, with the
 *  TV's forced-diagonal direction (R66: the TV ignores the authored angle; we match it). */
private fun channelFillCss(brandColor: String?): String {
    val t = brandColor?.trim()?.takeIf { it.isNotBlank() }
        ?: return "linear-gradient(135deg,#3b2a78,#15102e)"
    if (t.startsWith("linear-gradient", ignoreCase = true)) {
        // Extract colour stops (skip the angle/direction token); force diagonal 135deg to match TV.
        val inner = t.substringAfter('(').substringBeforeLast(')')
        val stops = inner.split(',').map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith("deg") && !it.startsWith("to ") }
        if (stops.isNotEmpty()) return "linear-gradient(135deg,${stops.joinToString(",")})"
    }
    return t // solid hex passes through
}

// Seed the popup's condition stack from a legacy single-typed channel so editing preserves its filter.
private fun legacyToConds(c: ChannelConfig): List<WbCond> {
    val (kind, value) = c.kindAndValue()
    if (value.isBlank()) return emptyList()
    return listOf(WbCond(kind.lowercase(), "is_any_of", mutableListOf(value)))
}

private fun renderChannels(container: Element) {
    val sect = container.querySelector("#sect-channels") ?: return
    val rows = currentConfig.channels.mapIndexed { i, c ->
        val showChecked = if (c.enabled) " checked" else ""
        val summary = if (c.conditions.isNotEmpty()) {
            "${c.conditions.size} condition(s) · ${c.match.name}"
        } else {
            val (kind, value) = c.kindAndValue()
            if (value.isNotBlank()) "${kind.lowercase().replaceFirstChar { it.uppercase() }}: $value" else "No filter yet"
        }
        """
        <div class="cfg-row" draggable="true" data-ch-i="$i" style="display:flex;align-items:center;gap:9px;margin-bottom:8px;flex-wrap:wrap">
          <span class="drag-handle" style="cursor:grab;user-select:none;flex-shrink:0">⠿</span>
          <input type="hidden" data-ch-id="$i" value="${c.id.htmlEsc()}">
          ${channelChipHtml(c)}
          <input class="input" style="width:140px" placeholder="Name" value="${c.name.htmlEsc()}" data-ch-name="$i">
          <span class="badge" style="white-space:nowrap">${summary.htmlEsc()}</span>
          <span id="ch-count-$i" class="badge" style="white-space:nowrap;font-size:.75rem;color:var(--ink-soft)">…</span>
          ${if (c.rows?.mode == "custom") """<span class="badge" style="white-space:nowrap;background:var(--fill-2);font-size:.7rem">▤ custom rows</span>""" else ""}
          <span class="spacer" style="flex:1"></span>
          <button class="btn sm ghost" data-ch-edit="$i" title="Edit channel button + filter">✎ Edit</button>
          <label style="display:flex;align-items:center;gap:5px;font-size:.8rem;white-space:nowrap"><input type="checkbox" data-ch-enabled="$i"$showChecked> Show</label>
          <button class="btn sm ghost" data-ch-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Channels</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            The logo row under the hero. <b>Click ✎ Edit on a channel</b> to set its filter, choose Logo or Text, pick or upload a brand logo, and set the brand fill (solid or gradient).
          </p>
          <div id="ch-list">$rows</div>
          <button id="ch-add" class="btn sm ghost" style="margin-top:6px">+ Add channel</button>
          <button id="ch-workbench" class="btn sm ghost" style="margin-top:6px">⚙ Build with workbench</button>
        </div>
    """.trimIndent()
    // R73/86: single batch-count call instead of N parallel API calls (one per channel).
    val scope = rcScope
    if (scope != null && currentConfig.channels.isNotEmpty()) {
        scope.launch {
            val requests = currentConfig.channels.mapIndexed { i, c ->
                val conds = if (c.conditions.isNotEmpty()) wbCondsFrom(c.conditions) else legacyToConds(c)
                BatchCountRequest(index = i, match = c.match.name, conditions = wbConds(conds))
            }
            val results = MediaApi.batchCount(requests) ?: return@launch
            for (result in results) {
                val badge = sect.querySelector("#ch-count-${result.index}") as? HTMLElement ?: continue
                badge.innerHTML = "${result.total} items"
            }
        }
    }
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
    wireDragReorder(container, sect, "ch", "ch-list",
        get = { currentConfig.channels }, set = { currentConfig = currentConfig.copy(channels = it) }, ::renderChannels)
    for (i in currentConfig.channels.indices) {
        sect.querySelector("[data-ch-del='$i']")?.addEventListener("click") { _ ->
            structural(container, {
                val list = currentConfig.channels.toMutableList(); list.removeAt(i)
                currentConfig = currentConfig.copy(channels = list)
            }, ::renderChannels)
        }
        sect.querySelector("[data-ch-edit='$i']")?.addEventListener("click") { _ ->
            val scope = rcScope ?: return@addEventListener
            val c = currentConfig.channels.getOrNull(i) ?: return@addEventListener
            openChannelEditorPage(container, scope, i, c)
        }
    }
}

// ── Channel editor page (R53) ─────────────────────────────────────────────────

private fun openChannelEditorPage(container: Element, scope: CoroutineScope, idx: Int, c: ChannelConfig) {
    historyPushState("#/ravilo?channel=${c.id}")
    val styleChecked = { s: String -> if ((if (c.style == ChannelStyle.LOGO) "logo" else "text") == s) " checked" else "" }
    val pHero = c.pageHero
    val heroEnabled = pHero?.enabled == true
    val heroItemCount = pHero?.items?.size ?: 0
    val rowsCustom = c.rows?.mode == "custom"
    // R143: per-channel system rows (Continue Watching, Newly Added).
    val chSys = c.rows?.system ?: ChannelSystemRows()
    val contShow = chSys.cont.show; val contScope = chSys.cont.scope
    val newlyShow = chSys.newly.show; val newlyScope = chSys.newly.scope; val newlyMerge = chSys.newly.merge
    fun scopeSeg(group: String, current: String): String {
        val allOn = if (current == "all") " class=\"on\"" else ""
        val chOn = if (current == "channel") " class=\"on\"" else ""
        return "<span class=\"seg cf-sysscope\" data-sys=\"$group\" style=\"flex:none;font-size:.78rem\"><span data-scope=\"all\"$allOn>All titles</span><span data-scope=\"channel\"$chOn>This channel</span></span>"
    }
    val rowsCustomItems = c.rows?.items ?: emptyList()
    val chRowsListHtml = rowsCustomItems.mapIndexed { i, r ->
        val condSrc = if (r.conditions.isNotEmpty()) "${r.conditions.size} condition(s) · match ${r.match.name.lowercase()}" else "No filter — shows all media"
        val name = r.title?.takeIf { it.isNotBlank() } ?: "Custom row"
        """<div class="cfg-row" style="margin-bottom:6px"><div style="flex:1;min-width:0"><input class="input" style="width:100%;max-width:220px;font-size:.84rem;padding:3px 8px;height:auto" placeholder="Row title" value="${name.htmlEsc()}" data-row-ch-title="$i"><div class="src" style="margin-top:3px">${condSrc.htmlEsc()}</div></div><button class="btn sm ghost" data-row-ch-edit="$i" style="white-space:nowrap">Edit filter</button><button class="btn sm ghost" data-row-ch-del="$i" style="color:var(--bad)">&#x2715;</button></div>"""
    }.joinToString("")
    val padLogo = c.paddingLogo
    val padText = c.paddingText
    val previewBg = c.brandColor?.takeIf { it.isNotBlank() } ?: "linear-gradient(135deg,#3b2a78,#15102e)"
    val solidHex = c.brandColor?.let { Regex("#[0-9a-fA-F]{3,8}").find(it)?.value } ?: "#3b2a78"
    val swatchHtml = BRAND_COLOR_PRESETS.joinToString("") { (name, grad) ->
        val ring = if (c.brandColor == grad) "outline:2px solid var(--hi);outline-offset:2px;" else ""
        """<button title="${name.htmlEsc()}" data-color-preset="${grad.htmlEsc()}" style="width:26px;height:26px;border-radius:6px;border:none;cursor:pointer;background:$grad;box-shadow:inset 0 0 0 1px rgba(255,255,255,.14);$ring"></button>"""
    }

    container.innerHTML = """
        <div class="pagebar" style="margin-bottom:18px">
          <button id="ch-ed-back" class="btn sm ghost">‹ Back to layout</button>
          <h2 style="margin:0;flex:1;text-align:center">${c.name.ifBlank { "Channel" }.htmlEsc()}</h2>
          <button id="ch-ed-cancel" class="btn sm ghost">Cancel</button>
          <button id="ch-ed-save" class="btn primary">Save changes</button>
        </div>
        <div id="ch-ed-msg" style="display:none;margin-bottom:12px"></div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:18px;flex-wrap:wrap">

          <!-- Left: Button + Filter -->
          <div>
            <div class="card" style="padding:18px 20px;margin-bottom:14px">
              <b>Channel button</b>
              <div style="margin-top:12px;display:flex;gap:10px;align-items:center;flex-wrap:wrap">
                <label><input type="radio" name="ch-ed-style" value="logo"${styleChecked("logo")}> Logo</label>
                <label><input type="radio" name="ch-ed-style" value="text"${styleChecked("text")}> Text</label>
              </div>
              <div style="margin-top:10px">
                <label class="tiny muted">Name</label>
                <input id="ch-ed-name" class="input" value="${c.name.htmlEsc()}" placeholder="Channel name" style="width:100%;margin-top:4px">
              </div>
              <div style="margin-top:10px" id="ch-logo-section">
                <label class="tiny muted">Logo (for Logo style)</label>
                ${(c.logoUrl ?: "").let { ls -> if (ls.isNotBlank()) """<div style="margin:6px 0 8px;height:44px;display:flex;align-items:center;justify-content:center;background:var(--fill-2);border-radius:8px;overflow:hidden;padding:4px"><img id="ch-logo-preview-img" src="${ls.htmlEsc()}" alt="" style="max-height:36px;max-width:140px;object-fit:contain"></div>""" else """<div id="ch-logo-preview-img" style="display:none"></div>""" }}
                <div style="display:flex;gap:6px;margin-top:6px;align-items:center">
                  <input id="ch-ed-logo" class="input" style="flex:1;font-size:.8rem" value="${(c.logoUrl ?: "").htmlEsc()}" placeholder="https://… or upload below">
                  <button id="ch-logo-upload-btn" class="btn" title="Upload image file" style="flex:none;padding:0 10px;height:34px;font-size:.8rem">⬆ Upload</button>
                  <input type="file" id="ch-logo-file" accept="image/png,image/svg+xml,image/jpeg,image/webp" style="display:none">
                </div>
                <div id="ch-logo-library" style="margin-top:8px"></div>
              </div>
              <div style="margin-top:14px">
                <label class="tiny muted">Brand fill</label>
                <div id="ch-color-preview" style="background:$previewBg;width:100%;aspect-ratio:${ChannelButtonSpec.ASPECT_RATIO};border-radius:${(ChannelButtonSpec.CORNER_RATIO * 100).toInt()}%;margin:6px 0 10px;display:flex;align-items:center;justify-content:center;color:#fff;font-family:var(--font-display,'Space Grotesk',sans-serif);font-weight:700;font-size:.82rem;overflow:hidden;max-height:80px">${c.name.take(12).ifEmpty { "Channel" }.htmlEsc()}</div>
                <div style="display:flex;gap:5px;flex-wrap:wrap;margin-bottom:10px">$swatchHtml</div>
                <div style="display:flex;gap:8px;align-items:center">
                  <input type="color" id="ch-color-native" value="$solidHex" title="Custom colour" style="width:36px;height:36px;padding:2px;border:1px solid var(--line);border-radius:8px;cursor:pointer;background:transparent;flex:none">
                  <input id="ch-ed-color" class="input" style="flex:1;font-size:.8rem" value="${(c.brandColor ?: "").htmlEsc()}" placeholder="#1a1a2e or linear-gradient(…)">
                </div>
              </div>

              <!-- R53 padding per display mode -->
              <details style="margin-top:14px">
                <summary class="tiny" style="cursor:pointer;user-select:none">
                  Padding
                  ${if (padLogo != null && (padLogo.top + padLogo.right + padLogo.bottom + padLogo.left) > 0)
                      """<span class="badge" style="font-size:.6rem">${padLogo.top}/${padLogo.right}/${padLogo.bottom}/${padLogo.left} logo</span>"""
                  else ""}
                  ${if (padText != null && (padText.top + padText.right + padText.bottom + padText.left) > 0)
                      """<span class="badge" style="font-size:.6rem">${padText.top}/${padText.right}/${padText.bottom}/${padText.left} text</span>"""
                  else ""}
                </summary>
                <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:10px">
                  <div>
                    <div class="tiny muted" style="margin-bottom:4px">Logo mode (px)</div>
                    <div style="display:flex;gap:5px;flex-wrap:wrap">
                      ${listOf("T" to "pad-logo-top", "R" to "pad-logo-right", "B" to "pad-logo-bottom", "L" to "pad-logo-left").joinToString("") { (lbl, id) ->
                          val cur = when(lbl) { "T" -> padLogo?.top ?: 0; "R" -> padLogo?.right ?: 0; "B" -> padLogo?.bottom ?: 0; else -> padLogo?.left ?: 0 }
                          """<label class="tiny" style="display:flex;flex-direction:column;align-items:center;gap:2px">$lbl<input id="$id" type="number" class="input" style="width:48px" min="0" max="40" value="$cur"></label>"""
                      }}
                    </div>
                  </div>
                  <div>
                    <div class="tiny muted" style="margin-bottom:4px">Text mode (px)</div>
                    <div style="display:flex;gap:5px;flex-wrap:wrap">
                      ${listOf("T" to "pad-text-top", "R" to "pad-text-right", "B" to "pad-text-bottom", "L" to "pad-text-left").joinToString("") { (lbl, id) ->
                          val cur = when(lbl) { "T" -> padText?.top ?: 0; "R" -> padText?.right ?: 0; "B" -> padText?.bottom ?: 0; else -> padText?.left ?: 0 }
                          """<label class="tiny" style="display:flex;flex-direction:column;align-items:center;gap:2px">$lbl<input id="$id" type="number" class="input" style="width:48px" min="0" max="40" value="$cur"></label>"""
                      }}
                    </div>
                  </div>
                </div>
              </details>
            </div>

            <div class="card" style="padding:18px 20px">
              <div style="display:flex;align-items:center;gap:10px;margin-bottom:10px">
                <b>Content filter</b>
                <button id="ch-ed-filter-edit" class="btn sm ghost" style="margin-left:auto">⚙ Edit filter</button>
              </div>
              <div id="ch-ed-filter-summary"></div>
            </div>
          </div>

          <!-- Right: Page hero (R52) -->
          <div>
            <div class="card" style="padding:18px 20px">
              <div style="display:flex;align-items:center;gap:10px;margin-bottom:12px">
                <b>Page hero</b>
                <label style="display:flex;align-items:center;gap:5px;font-size:.82rem;margin-left:auto">
                  <input type="checkbox" id="ch-hero-enabled"${if (heroEnabled) " checked" else ""}> Enabled
                </label>
              </div>
              <div id="ch-hero-body" style="${if (!heroEnabled) "opacity:.45;pointer-events:none;" else ""}">
                <p class="tiny muted" style="margin:0 0 10px">Height and auto-advance follow the global Home hero settings.</p>
                <div style="font-size:.82rem;color:var(--ink-soft);margin-bottom:8px">Hero items: <b id="ch-hero-count">$heroItemCount</b></div>
                <button id="ch-hero-edit" class="btn sm ghost">✎ Edit hero items</button>
              </div>
            </div>
          </div>
        </div>

        <!-- R59: Content rows (Same as Home / Custom) -->
        <div class="card" style="padding:18px 20px;margin-top:14px">
          <div style="display:flex;align-items:center;gap:12px;margin-bottom:10px">
            <b>Content rows</b>
            <label style="display:flex;align-items:center;gap:5px;font-size:.82rem">
              <input type="radio" name="ch-rows-mode" value="inherit"${if (!rowsCustom) " checked" else ""}> Same as Home
            </label>
            <label style="display:flex;align-items:center;gap:5px;font-size:.82rem">
              <input type="radio" name="ch-rows-mode" value="custom"${if (rowsCustom) " checked" else ""}> Custom
            </label>
          </div>
          <div id="ch-rows-custom-body" style="${if (!rowsCustom) "display:none;" else ""}">
            <p class="tiny muted" style="margin:0 0 10px">Custom rows for this channel. <b>System rows</b> (Continue Watching, Newly Added) sit on top; your filter rows follow below.</p>
            <!-- R143: per-channel system rows -->
            <div class="cf-sysrows" style="border:1px solid var(--line);border-radius:8px;padding:12px 14px;margin-bottom:14px">
              <div style="font-size:.7rem;font-weight:600;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-soft);margin-bottom:8px">System rows</div>
              <div class="cf-sysrow ${if (!contShow) "off" else ""}" style="display:flex;align-items:center;gap:10px;margin-bottom:8px;${if (!contShow) "opacity:.5" else ""}">
                <span class="badge ok" style="flex:none;font-size:.62rem">system</span>
                <div style="flex:1;min-width:0"><div style="font-size:.88rem;font-weight:500">Continue Watching</div><div class="tiny muted">${if (contScope == "channel") "In-progress titles from this channel" else "Your whole Continue + Next Up row"}</div></div>
                ${scopeSeg("continue", contScope)}
                <span class="toggle${if (contShow) " on" else ""}" data-systog="continue" style="cursor:pointer;flex:none"></span>
              </div>
              <div class="cf-sysrow ${if (!newlyShow) "off" else ""}" style="display:flex;align-items:center;gap:10px;${if (!newlyShow) "opacity:.5" else ""}">
                <span class="badge ok" style="flex:none;font-size:.62rem">system</span>
                <div style="flex:1;min-width:0"><div style="font-size:.88rem;font-weight:500">Newly Added</div><div class="tiny muted">${if (newlyScope == "channel") "Newest titles in this channel" else "Newest titles library-wide"}${if (newlyMerge) " · combined" else " · Movies + Series"}</div></div>
                ${scopeSeg("newly", newlyScope)}
                <span class="toggle${if (newlyShow) " on" else ""}" data-systog="newly" style="cursor:pointer;flex:none"></span>
              </div>
              <label class="cf-sysmerge ${if (!newlyShow) "off" else ""}" style="display:flex;align-items:center;gap:8px;margin-top:8px;padding-top:8px;border-top:1px solid var(--line);font-size:.82rem;cursor:pointer;${if (!newlyShow) "opacity:.45" else ""}">
                <span class="toggle${if (newlyMerge) " on" else ""}" data-sysmerge style="cursor:pointer;flex:none"></span>
                Merge movies &amp; series into one Newly Added row
              </label>
            </div>
            <div style="font-size:.7rem;font-weight:600;letter-spacing:.06em;text-transform:uppercase;color:var(--ink-soft);margin-bottom:8px">Filter rows</div>
            <div id="ch-rows-list" style="margin-bottom:8px">$chRowsListHtml</div>
            <button id="ch-rows-add" class="btn sm ghost">+ Add row</button>
            <!-- R87: row-coverage gap panel (filled async by renderCoverage) -->
            <div id="ch-rows-coverage" style="margin-top:14px"></div>
          </div>
          <p id="ch-rows-inherit-note" class="tiny muted" style="margin:0;${if (rowsCustom) "display:none;" else ""}">Shows the global Home rows scoped to this channel — the default behaviour.</p>
        </div>
    """.trimIndent()

    // Wire back / cancel — history.back() pops the pushState entry and fires popstate → handleRaviloRoute → renderFull
    container.querySelector("#ch-ed-back")?.addEventListener("click") { _ -> window.history.back() }
    container.querySelector("#ch-ed-cancel")?.addEventListener("click") { _ -> window.history.back() }

    // R60: re-render the channel editor after mutating rows (full re-render keeps state consistent).
    fun reRenderChannelRows() {
        val ch = currentConfig.channels.getOrNull(idx) ?: return
        openChannelEditorPage(container, scope, idx, ch)
    }
    // R143: mutate the channel's system-rows block then re-render (descriptions + merge enablement update).
    fun mutateSystem(f: (ChannelSystemRows) -> ChannelSystemRows) {
        val list = currentConfig.channels.toMutableList()
        val cur = list[idx]
        val existing = cur.rows ?: ChannelRowsConfig(mode = "custom")
        list[idx] = cur.copy(rows = existing.copy(mode = "custom", system = f(existing.system)))
        currentConfig = currentConfig.copy(channels = list)
        reRenderChannelRows()
    }
    container.querySelectorAll("[data-systog]").let { tg ->
        for (i in 0 until tg.length) {
            val el = tg.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { _ ->
                when (el.getAttribute("data-systog")) {
                    "continue" -> mutateSystem { it.copy(cont = it.cont.copy(show = !it.cont.show)) }
                    "newly"    -> mutateSystem { it.copy(newly = it.newly.copy(show = !it.newly.show)) }
                }
            }
        }
    }
    container.querySelector("[data-sysmerge]")?.addEventListener("click") { _ ->
        mutateSystem { it.copy(newly = it.newly.copy(merge = !it.newly.merge)) }
    }
    container.querySelectorAll(".cf-sysscope span[data-scope]").let { sc ->
        for (i in 0 until sc.length) {
            val el = sc.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { _ ->
                val group = (el.parentElement?.getAttribute("data-sys")) ?: return@addEventListener
                val scopeVal = el.getAttribute("data-scope") ?: return@addEventListener
                when (group) {
                    "continue" -> mutateSystem { it.copy(cont = it.cont.copy(scope = scopeVal)) }
                    "newly"    -> mutateSystem { it.copy(newly = it.newly.copy(scope = scopeVal)) }
                }
            }
        }
    }
    // R87: a catch-all (no-condition) row matches everything scoped to the channel (R59) — closes the gap.
    fun addCatchAllRow() {
        val list = currentConfig.channels.toMutableList()
        val cur = list[idx]
        val existing = cur.rows ?: ChannelRowsConfig(mode = "custom")
        val catchAll = RowConfig(id = genId("row"), kind = RowKind.CUSTOM, title = "All other titles",
            match = MatchMode.ALL, conditions = emptyList())
        list[idx] = cur.copy(rows = existing.copy(mode = "custom", items = existing.items + catchAll))
        currentConfig = currentConfig.copy(channels = list)
        reRenderChannelRows()
    }
    // R87: row-coverage gap = the channel filter AND `content_row is_none_of <the channel's own rows>`
    // (the shared evaluator's content_row facet, R87a). Rendered into #ch-rows-coverage; recomputed on
    // each (re-)render and when switching to Custom mode. System rows are time-based and excluded.
    fun renderCoverage() {
        val host = container.querySelector("#ch-rows-coverage") as? HTMLElement ?: return
        val ch = currentConfig.channels.getOrNull(idx)
        if (ch == null || ch.rows?.mode != "custom") { host.innerHTML = ""; return }
        val rows = ch.rows?.items?.filter { it.enabled } ?: emptyList()
        val channelConds = ch.conditions.ifEmpty { wbConds(legacyToConds(ch)) }
        host.innerHTML = """<div class="tiny muted">Checking coverage…</div>"""
        scope.launch {
            val pool = MediaApi.list(viewer = currentUserId, match = ch.match.name, conditions = channelConds, pageSize = 1)?.total ?: 0
            val gapConds = channelConds + Condition(facet = "content_row", op = "is_none_of", rows = rows)
            val page = MediaApi.list(viewer = currentUserId, match = "ALL", conditions = gapConds, pageSize = 24)
            val n = page?.total ?: 0
            if (n == 0) {
                host.innerHTML = """<div class="card" style="padding:12px 14px;background:rgba(56,161,105,.10);border:1px solid rgba(56,161,105,.30)"><div style="display:flex;align-items:center;gap:8px"><b style="flex:1">Not shown by any row</b><span class="badge" style="background:rgba(56,161,105,.22);color:#38a169">0 of $pool</span></div><div class="tiny" style="margin-top:6px;color:var(--ink-soft)">✓ Every title in this channel appears in at least one row — nothing falls through the gaps.</div></div>""".trimIndent()
                return@launch
            }
            val tiles = (page?.items ?: emptyList()).joinToString("") { m ->
                val img = if (!m.posterPath.isNullOrBlank()) """<img src="https://image.tmdb.org/t/p/w185${m.posterPath}" alt="" style="width:100%;height:100%;object-fit:cover">""" else """<div style="display:flex;align-items:center;justify-content:center;width:100%;height:100%;font-size:.7rem;color:var(--ink-soft)">${m.title.take(2).htmlEsc()}</div>"""
                """<div title="${m.title.htmlEsc()}" style="aspect-ratio:2/3;border-radius:6px;overflow:hidden;background:var(--fill-2)">$img</div>"""
            }
            val lead = if (rows.isEmpty()) "No rows yet — all <b>$n</b> titles that match this channel would be unreachable." else "These <b>$n</b> titles match the channel filter but <b>aren’t shown by any content row</b>, so viewers browsing this channel won’t find them."
            host.innerHTML = """<div class="card" style="padding:12px 14px;background:rgba(214,158,46,.10);border:1px solid rgba(214,158,46,.30)"><div style="display:flex;align-items:center;gap:8px"><b style="flex:1">Not shown by any row</b><span class="badge" style="background:rgba(214,158,46,.22);color:#d69e2e">$n of $pool</span></div><div class="tiny" style="margin:6px 0 10px;line-height:1.5;color:var(--ink-soft)">$lead <span class="muted">System rows (Continue, Newly Added) aren’t counted.</span></div><div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(56px,1fr));gap:7px;max-height:200px;overflow:auto;margin-bottom:11px">$tiles</div><div style="display:flex;gap:8px;flex-wrap:wrap"><button type="button" id="cov-addcatchall" class="btn sm ghost">＋ Add a catch-all row for these</button><button type="button" id="cov-openlib" class="btn sm ghost">Open these $n in Library ↗</button></div></div>""".trimIndent()
            host.querySelector("#cov-addcatchall")?.addEventListener("click") { _ -> addCatchAllRow() }
            // R87d: hand the coverage filter to the Library — channel conds as per-facet params + the
            // content_row gap condition as the `coverage` param (which carries the rows for editing).
            host.querySelector("#cov-openlib")?.addEventListener("click") { _ ->
                fun pf(facet: String, key: String): String? =
                    channelConds.filter { it.facet == facet && it.op == "is_any_of" }.flatMap { it.values }.distinct()
                        .takeIf { it.isNotEmpty() }?.let { "$key=" + it.joinToString(",") { v -> dev.jellystructure.encodeURIComponent(v) } }
                val coverCond = Condition(facet = "content_row", op = "is_none_of", rows = rows)
                val covParam = "coverage=" + dev.jellystructure.encodeURIComponent(
                    kotlinx.serialization.json.Json.encodeToString(Condition.serializer(), coverCond))
                val params = listOfNotNull(
                    pf("studio", "studios"), pf("network", "networks"), pf("genre", "genres"), pf("tag", "tags"),
                    if (ch.match.name == "ANY") "match=ANY" else null,
                    covParam,
                )
                dev.jellystructure.App.navigate("/library?" + params.joinToString("&"))
            }
        }
    }
    renderCoverage()

    // Brand colour picker
    val colorInput = container.querySelector("#ch-ed-color") as? HTMLInputElement
    val nativeInput = container.querySelector("#ch-color-native") as? HTMLInputElement
    fun updateColorPreview(color: String) {
        val preview = container.querySelector("#ch-color-preview") as? HTMLElement ?: return
        val bg = channelFillCss(color.ifBlank { null })
        preview.setAttribute("style", "background:$bg;width:100%;aspect-ratio:${ChannelButtonSpec.ASPECT_RATIO};border-radius:${(ChannelButtonSpec.CORNER_RATIO * 100).toInt()}%;margin:6px 0 10px;display:flex;align-items:center;justify-content:center;color:#fff;font-family:var(--font-display,'Space Grotesk',sans-serif);font-weight:700;font-size:.82rem;overflow:hidden;max-height:80px")
        preview.textContent = ((container.querySelector("#ch-ed-name") as? HTMLInputElement)?.value?.take(12) ?: c.name.take(12)).ifEmpty { "Channel" }
    }
    nativeInput?.addEventListener("input") { _ ->
        val hex = nativeInput.value
        colorInput?.value = hex
        updateColorPreview(hex)
    }
    colorInput?.addEventListener("input") { _ ->
        val v = colorInput.value
        updateColorPreview(v)
        Regex("#[0-9a-fA-F]{3,8}").find(v)?.value?.let { nativeInput?.value = it }
    }
    container.querySelector("#ch-ed-name")?.addEventListener("input") { _ ->
        updateColorPreview(colorInput?.value ?: "")
    }
    val swatchNodes = container.querySelectorAll("[data-color-preset]")
    for (si in 0 until swatchNodes.length) {
        val sw = swatchNodes.item(si) as? HTMLElement ?: continue
        val preset = sw.getAttribute("data-color-preset") ?: continue
        sw.addEventListener("click") { _ ->
            colorInput?.value = preset
            updateColorPreview(preset)
            Regex("#[0-9a-fA-F]{3,8}").find(preset)?.value?.let { nativeInput?.value = it }
        }
    }

    // Logo upload + library
    fun setLogoUrl(url: String) {
        (container.querySelector("#ch-ed-logo") as? HTMLInputElement)?.value = url
        val previewEl = container.querySelector("#ch-logo-preview-img") as? HTMLElement
        if (previewEl != null) {
            if (url.isNotBlank()) {
                previewEl.setAttribute("src", url)
                previewEl.setAttribute("style", "max-height:36px;max-width:140px;object-fit:contain")
                previewEl.parentElement?.setAttribute("style", "margin:6px 0 8px;height:44px;display:flex;align-items:center;justify-content:center;background:var(--fill-2);border-radius:8px;overflow:hidden;padding:4px")
            } else {
                previewEl.setAttribute("style", "display:none")
            }
        }
    }
    fun renderLogoLibrary(logos: List<dev.jellystructure.shared.tv.ChannelLogo>, currentUrl: String) {
        val lib = container.querySelector("#ch-logo-library") as? HTMLElement ?: return
        if (logos.isEmpty()) { lib.innerHTML = ""; return }
        lib.innerHTML = """<div class="tiny muted" style="margin-bottom:6px">Uploaded logos — click to use</div><div style="display:flex;gap:6px;flex-wrap:wrap">""" +
            logos.joinToString("") { logo ->
                val ring = if (logo.url == currentUrl) "outline:2px solid var(--hi);outline-offset:1px;" else ""
                """<button data-logo-pick="${logo.url.htmlEsc()}" title="${logo.label.htmlEsc()}" style="width:60px;height:36px;border:1px solid var(--line);border-radius:7px;background:var(--fill-2);cursor:pointer;overflow:hidden;display:flex;align-items:center;justify-content:center;padding:3px;$ring"><img src="${logo.url.htmlEsc()}" alt="${logo.label.htmlEsc()}" style="max-width:100%;max-height:100%;object-fit:contain"></button>"""
            } + "</div>"
        lib.querySelectorAll("[data-logo-pick]").let { nodes ->
            for (i in 0 until nodes.length) {
                val btn = nodes.item(i) as? HTMLElement ?: continue
                val url = btn.getAttribute("data-logo-pick") ?: continue
                btn.addEventListener("click") { _ ->
                    setLogoUrl(url)
                    renderLogoLibrary(logos, url)
                }
            }
        }
    }
    val fileInput = container.querySelector("#ch-logo-file") as? HTMLInputElement
    container.querySelector("#ch-logo-upload-btn")?.addEventListener("click") { _ -> fileInput?.click() }
    fileInput?.addEventListener("change") { _ ->
        val file: File = fileInput.files?.item(0) ?: return@addEventListener
        val reader = FileReader()
        reader.onload = { _ ->
            val dataUrl = (reader.result as? JsString)?.toString() ?: ""
            if (dataUrl.isNotEmpty()) scope.launch {
                runCatching { RaviloApi.uploadChannelLogo(file.name, dataUrl) }.onSuccess { logo ->
                    setLogoUrl(logo.url)
                    val logos = runCatching { RaviloApi.listChannelLogos() }.getOrDefault(emptyList())
                    renderLogoLibrary(logos, logo.url)
                }.onFailure {
                    (container.querySelector("#ch-ed-msg") as? HTMLElement)?.let { msg ->
                        msg.innerHTML = """<div class="alert-bad">Upload failed: ${it.message?.htmlEsc()}</div>"""
                        msg.setAttribute("style", "display:block;margin-bottom:12px")
                    }
                }
            }
        }
        reader.readAsDataURL(file)
    }
    scope.launch {
        val logos = runCatching { RaviloApi.listChannelLogos() }.getOrDefault(emptyList())
        renderLogoLibrary(logos, c.logoUrl ?: "")
    }

    // Hero enable toggle
    container.querySelector("#ch-hero-enabled")?.addEventListener("change") { _ ->
        val cb = container.querySelector("#ch-hero-enabled") as? HTMLInputElement ?: return@addEventListener
        val body = container.querySelector("#ch-hero-body") as? HTMLElement ?: return@addEventListener
        body.style.opacity = if (cb.checked) "1" else ".45"
        body.setAttribute("style", "transition:opacity .15s;opacity:${if (cb.checked) "1" else ".45"};${if (!cb.checked) "pointer-events:none;" else ""}")
    }

    // Hero height slider
    container.querySelector("#ch-hero-height")?.addEventListener("input") { _ ->
        val h = (container.querySelector("#ch-hero-height") as? HTMLInputElement)?.value ?: return@addEventListener
        (container.querySelector("#ch-hero-height-val") as? HTMLElement)?.textContent = "$h%"
    }

    // Edit hero items — open the same hero-item workbench used by the home hero
    container.querySelector("#ch-hero-edit")?.addEventListener("click") { _ ->
        val ch = currentConfig.channels.getOrNull(idx) ?: return@addEventListener
        val heroItems = ch.pageHero?.items ?: emptyList()
        openHeroEditorOverlay(container, scope, heroItems, title = "Page hero — ${ch.name.ifBlank { "channel" }}") { updatedItems ->
            val list = currentConfig.channels.toMutableList()
            val cur = list[idx]
            list[idx] = cur.copy(pageHero = (cur.pageHero ?: PageHeroConfig()).copy(items = updatedItems))
            currentConfig = currentConfig.copy(channels = list)
            val countEl = container.querySelector("#ch-hero-count") as? HTMLElement
            countEl?.textContent = "${updatedItems.size}"
        }
    }

    // Save changes
    container.querySelector("#ch-ed-save")?.addEventListener("click") { _ ->
        val name = (container.querySelector("#ch-ed-name") as? HTMLInputElement)?.value?.trim() ?: c.name
        val logo = (container.querySelector("#ch-ed-logo") as? HTMLInputElement)?.value?.trim()?.ifBlank { null }
        val color = (container.querySelector("#ch-ed-color") as? HTMLInputElement)?.value?.trim()?.ifBlank { null }
        val styleVal = (container.querySelector("input[name='ch-ed-style']:checked") as? HTMLInputElement)?.value ?: "logo"
        val heroEn = (container.querySelector("#ch-hero-enabled") as? HTMLInputElement)?.checked ?: false
        val rowsMode = (container.querySelector("input[name='ch-rows-mode']:checked") as? HTMLInputElement)?.value ?: "inherit"
        fun padOf(t: String, r: String, b: String, l: String): ChannelButtonPadding? {
            val top = (container.querySelector("#$t") as? HTMLInputElement)?.value?.toIntOrNull() ?: 0
            val right = (container.querySelector("#$r") as? HTMLInputElement)?.value?.toIntOrNull() ?: 0
            val btm = (container.querySelector("#$b") as? HTMLInputElement)?.value?.toIntOrNull() ?: 0
            val left = (container.querySelector("#$l") as? HTMLInputElement)?.value?.toIntOrNull() ?: 0
            return if (top + right + btm + left > 0) ChannelButtonPadding(top, right, btm, left) else null
        }
        val paddingLogo = padOf("pad-logo-top", "pad-logo-right", "pad-logo-bottom", "pad-logo-left")
        val paddingText = padOf("pad-text-top", "pad-text-right", "pad-text-bottom", "pad-text-left")

        val list = currentConfig.channels.toMutableList()
        val cur = list[idx]
        list[idx] = cur.copy(
            name = name,
            style = if (styleVal == "text") ChannelStyle.TEXT else ChannelStyle.LOGO,
            brandColor = color,
            logoUrl = logo,
            pageHero = if (heroEn || cur.pageHero?.items?.isNotEmpty() == true)
                (cur.pageHero ?: PageHeroConfig()).copy(enabled = heroEn)
            else cur.pageHero,
            paddingLogo = paddingLogo,
            paddingText = paddingText,
            // R143: the system block is already in cur.rows (mutated live by the system-row toggles); copy preserves it.
            rows = if (rowsMode == "custom") (cur.rows ?: ChannelRowsConfig()).copy(mode = "custom") else null,
        )
        currentConfig = currentConfig.copy(channels = list)
        historyReplaceState("#/ravilo")
        renderFull(container, scope)
    }

    // R59: rows mode toggle shows/hides custom body
    container.querySelectorAll("input[name='ch-rows-mode']").let { radios ->
        for (i in 0 until radios.length) {
            (radios.item(i) as? HTMLInputElement)?.addEventListener("change") { _ ->
                val isCustom = (container.querySelector("input[name='ch-rows-mode']:checked") as? HTMLInputElement)?.value == "custom"
                (container.querySelector("#ch-rows-custom-body") as? HTMLElement)?.style?.display = if (isCustom) "" else "none"
                (container.querySelector("#ch-rows-inherit-note") as? HTMLElement)?.style?.display = if (isCustom) "none" else ""
                if (isCustom) renderCoverage()  // R87: surface the gap as soon as Custom is chosen
            }
        }
    }

    // R60: channel base-scope conds passed to row workbench so results are pre-scoped to the channel filter
    fun chBaseConds(): List<WbCond> {
        val ch = currentConfig.channels.getOrNull(idx) ?: return emptyList()
        return if (ch.conditions.isNotEmpty()) wbCondsFrom(ch.conditions) else emptyList()
    }
    fun chBaseMatch(): String = currentConfig.channels.getOrNull(idx)?.match?.name ?: "ALL"

    // R59/R60: "Add row" for channel custom rows
    container.querySelector("#ch-rows-add")?.addEventListener("click") { _ ->
        openWorkbench(scope, "New row — ${c.name.ifBlank { "Channel" }}", viewer = currentUserId,
            applyLabel = "Add row",
            baseConds = chBaseConds(), baseMatch = chBaseMatch(),
            onApply = { match, include, conds ->
                val label = conds.firstOrNull { it.values.isNotEmpty() }?.values?.firstOrNull() ?: "Custom row"
                val mediaKind = when (include) { "movies" -> "MOVIE"; "series" -> "SERIES"; else -> null }
                val newRow = RowConfig(id = genId("row"), kind = RowKind.CUSTOM, title = label,
                    mediaKind = mediaKind, match = wbMode(match), conditions = wbConds(conds))
                val list = currentConfig.channels.toMutableList()
                val cur = list[idx]
                val existing = cur.rows ?: ChannelRowsConfig(mode = "custom")
                list[idx] = cur.copy(rows = existing.copy(mode = "custom", items = existing.items + newRow))
                currentConfig = currentConfig.copy(channels = list)
                reRenderChannelRows()
            })
    }

    // R60: wire edit-filter and delete buttons on each existing channel row
    val chRowItems = currentConfig.channels.getOrNull(idx)?.rows?.items ?: emptyList()
    for (ri in chRowItems.indices) {
        container.querySelector("[data-row-ch-edit='$ri']")?.addEventListener("click") { _ ->
            val row = currentConfig.channels.getOrNull(idx)?.rows?.items?.getOrNull(ri) ?: return@addEventListener
            val inc = when (row.mediaKind) { "MOVIE" -> "movies"; "SERIES" -> "series"; else -> "all" }
            openWorkbench(scope, "Edit row — ${(row.title ?: "Custom row").htmlEsc()}", viewer = currentUserId,
                initialMatch = row.match.name, initialInclude = inc, initialConds = wbCondsFrom(row.conditions),
                applyLabel = "Update row",
                baseConds = chBaseConds(), baseMatch = chBaseMatch(),
                onApply = { match, inc2, conds ->
                    val mediaKind = when (inc2) { "movies" -> "MOVIE"; "series" -> "SERIES"; else -> null }
                    val list = currentConfig.channels.toMutableList()
                    val cur = list[idx]
                    val items = cur.rows?.items?.toMutableList() ?: return@openWorkbench
                    items[ri] = items[ri].copy(kind = RowKind.CUSTOM, match = wbMode(match), conditions = wbConds(conds), mediaKind = mediaKind)
                    list[idx] = cur.copy(rows = cur.rows!!.copy(items = items))
                    currentConfig = currentConfig.copy(channels = list)
                    reRenderChannelRows()
                })
        }
        container.querySelector("[data-row-ch-del='$ri']")?.addEventListener("click") { _ ->
            val list = currentConfig.channels.toMutableList()
            val cur = list[idx]
            val items = cur.rows?.items?.toMutableList() ?: return@addEventListener
            items.removeAt(ri)
            list[idx] = cur.copy(rows = cur.rows!!.copy(items = items))
            currentConfig = currentConfig.copy(channels = list)
            reRenderChannelRows()
        }
        container.querySelector("[data-row-ch-title='$ri']")?.addEventListener("change") { _ ->
            val input = container.querySelector("[data-row-ch-title='$ri']") as? HTMLInputElement ?: return@addEventListener
            val list = currentConfig.channels.toMutableList()
            val cur = list[idx]
            val items = cur.rows?.items?.toMutableList() ?: return@addEventListener
            items[ri] = items[ri].copy(title = input.value.ifBlank { null })
            list[idx] = cur.copy(rows = cur.rows!!.copy(items = items))
            currentConfig = currentConfig.copy(channels = list)
        }
    }

    // Filter summary + workbench popup
    renderFilterSummary(container, idx)
    container.querySelector("#ch-ed-filter-edit")?.addEventListener("click") { _ ->
        val ch = currentConfig.channels.getOrNull(idx) ?: return@addEventListener
        val initSeed = if (ch.conditions.isNotEmpty()) wbCondsFrom(ch.conditions) else legacyToConds(ch)
        openWorkbench(scope, "Filter — ${ch.name.ifBlank { "Channel" }}", viewer = currentUserId,
            initialMatch = ch.match.name,
            initialConds = initSeed,
            applyLabel = "Apply filter",
            onApply = { match, _, conds ->
                val list = currentConfig.channels.toMutableList()
                list[idx] = list[idx].copy(match = wbMode(match), conditions = wbConds(conds))
                currentConfig = currentConfig.copy(channels = list)
                renderFilterSummary(container, idx)
            }
        )
    }
}

/** Rich hero-items editor overlay: thumbnail rows + DnD reorder + search picker for adding. */
private fun openHeroEditorOverlay(
    container: Element,
    scope: CoroutineScope,
    heroItems: List<HeroConfig>,
    title: String,
    onSave: (List<HeroConfig>) -> Unit,
) {
    document.getElementById("hero-ov")?.remove()
    val ov = document.createElement("div") as HTMLElement
    ov.id = "hero-ov"
    ov.style.cssText = "position:fixed;inset:0;background:#000a;display:flex;align-items:center;justify-content:center;z-index:9000"
    val editItems = heroItems.toMutableList()

    fun rowHtml(i: Int, h: HeroConfig): String {
        val bg = heroGradient(h.itemId)
        val thumbStyle = if (!h.displayBackdrop.isNullOrBlank())
            "background:url('https://image.tmdb.org/t/p/w300${h.displayBackdrop}') center/cover,$bg"
        else "background:$bg"
        val displayTitle = h.displayTitle?.ifBlank { null } ?: h.itemId.take(14)
        val displayMeta = h.displayMeta ?: ""
        val shortTitle = displayTitle.take(8) + if (displayTitle.length > 8) "…" else ""
        return """
            <div class="cfg-row" draggable="true" data-hov-i="$i" style="display:flex;align-items:center;gap:10px;margin-bottom:8px">
              <span class="drag-handle" style="cursor:grab;user-select:none;flex-shrink:0">⠿</span>
              <div style="${thumbStyle};width:76px;height:44px;border-radius:6px;flex:none;position:relative;overflow:hidden">
                <span style="position:absolute;left:5px;bottom:4px;font-weight:700;font-size:.62rem;color:#fff;text-shadow:0 1px 3px rgba(0,0,0,.8)">${shortTitle.htmlEsc()}</span>
              </div>
              <div style="flex:1;min-width:0">
                <div class="nm" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${displayTitle.htmlEsc()}</div>
                <div class="src">${displayMeta.htmlEsc()}</div>
              </div>
              <button class="btn sm ghost" data-hov-del="$i">✕</button>
            </div>
        """.trimIndent()
    }

    fun buildHtml(): String = """
        <div style="background:var(--fill);border-radius:14px;padding:24px;max-width:500px;width:100%;max-height:85vh;overflow:auto">
          <div style="display:flex;align-items:center;gap:10px;margin-bottom:16px">
            <b>${title.htmlEsc()}</b><span class="spacer"></span>
            <button id="hero-ov-close" class="btn sm ghost">✕</button>
          </div>
          <div id="hero-ov-list">
            ${if (editItems.isEmpty()) """<p class="tiny muted" style="padding:4px 0 12px">No items yet — click Add to pick from your library.</p>"""
              else editItems.mapIndexed { i, h -> rowHtml(i, h) }.joinToString("")}
          </div>
          <button id="hero-ov-add" class="btn sm ghost" style="margin-bottom:16px">⊕ Add item</button>
          <div style="display:flex;justify-content:flex-end;gap:8px">
            <button id="hero-ov-cancel" class="btn sm ghost">Cancel</button>
            <button id="hero-ov-save" class="btn primary">Save hero items</button>
          </div>
        </div>
    """.trimIndent()

    fun rewire() {
        ov.querySelector("#hero-ov-close")?.addEventListener("click") { _ -> ov.remove() }
        ov.querySelector("#hero-ov-cancel")?.addEventListener("click") { _ -> ov.remove() }
        ov.querySelector("#hero-ov-save")?.addEventListener("click") { _ ->
            onSave(editItems.toList()); ov.remove()
        }
        ov.querySelector("#hero-ov-add")?.addEventListener("click") { _ ->
            openItemSearchPicker(scope) { picked ->
                val kindStr = if (picked.kind.name == "TV_SHOW") "Series" else "Film"
                val meta = listOfNotNull(kindStr, picked.network ?: picked.studio, picked.year?.toString()).joinToString(" · ")
                val itemId = picked.jellyfinId ?: picked.id
                if (editItems.none { it.itemId == itemId }) {
                    editItems.add(HeroConfig(itemId = itemId, enabled = true,
                        displayTitle = picked.title, displayMeta = meta, displayBackdrop = picked.backdropPath))
                }
                ov.innerHTML = buildHtml(); rewire()
            }
        }
        for (j in editItems.indices) {
            ov.querySelector("[data-hov-del='$j']")?.addEventListener("click") { _ ->
                editItems.removeAt(j); ov.innerHTML = buildHtml(); rewire()
            }
        }
        val listEl = ov.querySelector("#hero-ov-list") as? HTMLElement ?: return
        var dragFrom = -1
        fun clearH() {
            val nl = listEl.querySelectorAll("[data-hov-i]")
            for (j in 0 until nl.length) (nl.item(j) as? HTMLElement)?.classList?.remove("dragging", "drop-before", "drop-after")
        }
        listEl.addEventListener("dragstart") { e ->
            val row = (e.target as? HTMLElement)?.closest("[data-hov-i]") as? HTMLElement ?: return@addEventListener
            dragFrom = row.getAttribute("data-hov-i")?.toIntOrNull() ?: -1; row.classList.add("dragging")
        }
        listEl.addEventListener("dragend") { _ -> dragFrom = -1; clearH() }
        listEl.addEventListener("dragover") { e ->
            e.preventDefault()
            val row = (e.target as? HTMLElement)?.closest("[data-hov-i]") as? HTMLElement ?: return@addEventListener
            val toIdx = row.getAttribute("data-hov-i")?.toIntOrNull() ?: return@addEventListener
            clearH()
            if (toIdx != dragFrom) row.classList.add(if (toIdx < dragFrom) "drop-before" else "drop-after")
        }
        listEl.addEventListener("drop") { e ->
            e.preventDefault()
            val row = (e.target as? HTMLElement)?.closest("[data-hov-i]") as? HTMLElement ?: return@addEventListener
            val toIdx = row.getAttribute("data-hov-i")?.toIntOrNull() ?: return@addEventListener
            val fromIdx = dragFrom; dragFrom = -1
            if (fromIdx < 0 || fromIdx == toIdx) return@addEventListener
            val item = editItems.removeAt(fromIdx); editItems.add(toIdx, item)
            ov.innerHTML = buildHtml(); rewire()
        }
    }

    ov.innerHTML = buildHtml()
    document.body?.appendChild(ov)
    rewire()
}

/** Search-and-select overlay for picking a single media item from the library. */
private fun openItemSearchPicker(scope: CoroutineScope, onPick: (MediaItem) -> Unit) {
    document.getElementById("item-pick-ov")?.remove()
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "item-pick-ov"
    overlay.setAttribute("style", "position:fixed;inset:0;background:#000b;display:flex;align-items:center;justify-content:center;z-index:9500;padding:20px")
    overlay.innerHTML = """
        <div style="background:var(--fill);color:var(--ink);border:1px solid var(--line);border-radius:16px;padding:20px;width:min(520px,100%);box-shadow:var(--shadow);display:flex;flex-direction:column;gap:0;max-height:80vh">
          <div style="font-weight:700;font-size:1.05rem;margin-bottom:12px">Pick an item</div>
          <input id="item-pick-q" class="input" style="width:100%;margin-bottom:10px" placeholder="Search your library…" autocomplete="off">
          <div id="item-pick-results" style="flex:1;overflow-y:auto;min-height:80px;max-height:320px;display:flex;flex-direction:column;gap:4px"></div>
          <div style="display:flex;justify-content:flex-end;margin-top:14px;flex-shrink:0">
            <button id="item-pick-cancel" class="btn sm ghost">Cancel</button>
          </div>
        </div>
    """.trimIndent()
    document.body?.appendChild(overlay)

    val resultsDiv = overlay.querySelector("#item-pick-results") as? HTMLElement
    val qInput = overlay.querySelector("#item-pick-q") as? HTMLInputElement

    fun showResults(items: List<MediaItem>) {
        resultsDiv?.innerHTML = if (items.isEmpty())
            """<div class="tiny muted" style="padding:10px 2px">No results found.</div>"""
        else items.joinToString("") { item ->
            val bg = heroGradient(item.jellyfinId ?: item.id)
            val thumbStyle = if (!item.backdropPath.isNullOrBlank())
                "background:url('https://image.tmdb.org/t/p/w300${item.backdropPath}') center/cover,$bg"
            else "background:$bg"
            val kindStr = if (item.kind.name == "TV_SHOW") "Series" else "Film"
            val meta = listOfNotNull(kindStr, item.network ?: item.studio, item.year?.toString()).joinToString(" · ")
            val pickId = item.jellyfinId ?: item.id
            val short = item.title.take(10) + if (item.title.length > 10) "…" else ""
            """<div data-ipick-jid="${pickId.htmlEsc()}" class="cfg-row" style="padding:10px 12px;cursor:pointer;gap:10px;flex-shrink:0;transition:background .1s">
                 <div style="$thumbStyle;width:76px;height:44px;border-radius:6px;flex:none;position:relative;overflow:hidden">
                   <span style="position:absolute;left:5px;bottom:4px;font-weight:700;font-size:.62rem;color:#fff;text-shadow:0 1px 3px rgba(0,0,0,.8)">${short.htmlEsc()}</span>
                 </div>
                 <div style="flex:1;min-width:0">
                   <div class="nm" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${item.title.htmlEsc()}</div>
                   <div class="src">${meta.htmlEsc()}</div>
                 </div>
               </div>"""
        }
        val nodeList = resultsDiv?.querySelectorAll("[data-ipick-jid]") ?: return
        for (j in 0 until nodeList.length) {
            val el = nodeList.item(j) as? HTMLElement ?: continue
            val pickId = el.getAttribute("data-ipick-jid") ?: continue
            val item = items.firstOrNull { (it.jellyfinId ?: it.id) == pickId } ?: continue
            el.addEventListener("click") { _ -> overlay.remove(); onPick(item) }
        }
    }

    scope.launch { showResults(MediaApi.list(pageSize = 8)?.items ?: emptyList()) }
    var searchJob: Job? = null
    qInput?.addEventListener("input") { _ ->
        searchJob?.cancel()
        val q = qInput.value.trim()
        searchJob = scope.launch {
            delay(250)
            showResults(if (q.length >= 2) MediaApi.list(search = q, pageSize = 8)?.items ?: emptyList()
                        else MediaApi.list(pageSize = 8)?.items ?: emptyList())
        }
    }
    overlay.querySelector("#item-pick-cancel")?.addEventListener("click") { _ -> overlay.remove() }
}

private fun renderFilterSummary(container: Element, channelIdx: Int) {
    val host = container.querySelector("#ch-ed-filter-summary") as? HTMLElement ?: return
    val ch = currentConfig.channels.getOrNull(channelIdx)
    if (ch == null) { host.innerHTML = ""; return }
    val conds = ch.conditions
    host.innerHTML = if (conds.isEmpty()) {
        """<span class="tiny muted">No filter — shows all content</span>"""
    } else {
        val matchLabel = if (ch.match == MatchMode.ANY) "ANY" else "ALL"
        val pills = conds.joinToString("") { cond ->
            val facetLabel = cond.facet.replaceFirstChar { it.uppercase() }
            val valStr = cond.values.take(3).joinToString(", ").let {
                if (cond.values.size > 3) "$it +${cond.values.size - 3}" else it
            }.ifBlank { "…" }
            """<span class="badge" style="margin-right:4px;margin-bottom:4px">${facetLabel.htmlEsc()}: ${valStr.htmlEsc()}</span>"""
        }
        """<div style="font-size:.8rem;color:var(--ink-soft);margin-bottom:6px">Match <b>$matchLabel</b> of:</div><div>$pills</div>"""
    }
}

/** Inline filter workbench — renders condition rows directly into [host] without a modal. */
// ── Rows ──────────────────────────────────────────────────────────────────────

private fun RowKind.isSystem() = this == RowKind.CONTINUE || this == RowKind.NEWLY_ADDED

private fun systemRowSource(r: RowConfig): String = when (r.kind) {
    RowKind.CONTINUE -> "Continue + Next Up, merged"
    RowKind.NEWLY_ADDED -> when (r.mediaKind) {
        "MOVIE" -> "kind = movie · sort newest"
        "SERIES" -> "kind = series · sort newest"
        else -> "movies + series combined · sort newest"
    }
    else -> ""
}

private val SYSTEM_ROW_DEFAULTS = listOf(
    RowConfig(id = "continue",  kind = RowKind.CONTINUE,    title = "Continue Watching", enabled = true, order = 0),
    RowConfig(id = "newly-all", kind = RowKind.NEWLY_ADDED, title = "Newly Added",       enabled = true, order = 1),
)

private fun normalizedRows(rows: List<RowConfig>): List<RowConfig> {
    val existingIds = rows.map { it.id }.toSet()
    val missing = SYSTEM_ROW_DEFAULTS.filter { it.id !in existingIds }
    if (missing.isEmpty()) return rows
    return (missing + rows).mapIndexed { i, r -> r.copy(order = i) }
}

private fun renderRows(container: Element) {
    val sect = container.querySelector("#sect-rows") ?: return
    val merging = currentConfig.mergeNewlyAdded
    val mergeChecked = if (merging) " checked" else ""

    val normalRows = normalizedRows(currentConfig.rows)
    if (normalRows.size != currentConfig.rows.size) currentConfig = currentConfig.copy(rows = normalRows)

    val rows = currentConfig.rows.mapIndexed { i, r ->
        val system = r.kind.isSystem()
        // Only grey out typed NEWLY_ADDED rows (MOVIE/SERIES) when merge is ON — they're superseded.
        // The all-media (mediaKind=null) row is always active: it shows as 1 row (merge ON) or 2 (OFF).
        val isMergedOut = merging && r.kind == RowKind.NEWLY_ADDED && r.mediaKind != null
        val rowOpacity = if (isMergedOut) "opacity:0.45;" else ""
        val showChecked = if (r.enabled) " checked" else ""
        val showLabel = if (r.enabled) "show" else "hidden"
        val toggleHtml = """<label style="display:flex;align-items:center;gap:4px;font-size:.8rem;white-space:nowrap;margin-left:auto;cursor:pointer"><span class="muted tiny">$showLabel</span><span class="toggle${if (r.enabled) " on" else ""}" data-row-toggle="$i" style="margin-left:4px"></span><input type="checkbox" data-row-enabled="$i"$showChecked style="display:none"></label>"""

        if (system) {
            val srcLine = systemRowSource(r)
            val name = r.title?.takeIf { it.isNotBlank() } ?: defaultRowTitle(r.kind, r.mediaKind)
            """<div class="cfg-row" draggable="true" data-row-i="$i" style="${rowOpacity}transition:opacity .2s"><span class="grab" style="cursor:grab;user-select:none;flex-shrink:0">&#x2807;</span><span class="badge ok" style="flex:none;font-size:.65rem">system</span><div style="flex:1;min-width:0"><div class="nm">${name.htmlEsc()}</div>${if (srcLine.isNotEmpty()) """<div class="src">${srcLine.htmlEsc()}</div>""" else ""}</div>$toggleHtml</div>"""
        } else {
            val badgeLabel = if (r.kind == RowKind.GENRE) "genre" else "filter"
            val condSrc = if (r.conditions.isNotEmpty()) "${r.conditions.size} condition(s) · match ${r.match.name.lowercase()}" else "No filter — shows all media"
            val name = r.title?.takeIf { it.isNotBlank() } ?: "Custom row"
            """<div class="cfg-row" draggable="true" data-row-i="$i" style="${rowOpacity}transition:opacity .2s"><span class="grab" style="cursor:grab;user-select:none;flex-shrink:0">&#x2807;</span><span class="badge info" style="flex:none;font-size:.65rem">$badgeLabel</span><div style="flex:1;min-width:0"><input class="input" style="width:100%;max-width:200px;font-size:.84rem;padding:3px 8px;height:auto" placeholder="Row title" value="${name.htmlEsc()}" data-row-title="$i"><div class="src" style="margin-top:3px">${condSrc.htmlEsc()}</div></div><button class="btn sm ghost" data-row-edit="$i" style="white-space:nowrap">Edit filter</button>$toggleHtml<button class="btn sm ghost" data-row-del="$i" style="color:var(--bad)">&#x2715;</button></div>"""
        }
    }.joinToString("")

    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Content rows</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">The vertical stack on Home. System rows can be hidden and reordered but not removed.</p>
          <div class="box flat" style="background:var(--hi-soft);border:1px solid rgba(255,180,0,.25);border-radius:8px;padding:12px 14px;margin-bottom:14px">
            <label style="display:flex;align-items:center;gap:10px;font-size:.9rem;cursor:pointer">
              <input type="checkbox" id="merge-newly-added"$mergeChecked>
              <div><b>Merge newly added</b> <span style="font-size:.8rem;color:var(--ink-soft)">&mdash; ${if (merging) "one combined row (all media)" else "two rows: Movies + Series"}</span>
                <div class="tiny muted" style="margin-top:3px">ON: one "Newly Added" row for all media. OFF: separate "Movies — Newly Added" and "Series — Newly Added" rows. Applies to Home and to channels set to "Same as Home"; each channel can override this in its own editor.</div></div>
            </label>
          </div>
          <div id="row-list">$rows</div>
          <button id="row-add" class="btn sm ghost" style="margin-top:6px">+ Add row</button>
        </div>
    """.trimIndent()

    sect.querySelectorAll("[data-row-toggle]").let { toggles ->
        for (k in 0 until toggles.length) {
            val tog = toggles.item(k) as? HTMLElement ?: continue
            val idx = tog.getAttribute("data-row-toggle") ?: continue
            tog.addEventListener("click") { _ ->
                val cb = sect.querySelector("[data-row-enabled='$idx']") as? HTMLInputElement ?: return@addEventListener
                cb.checked = !cb.checked; tog.classList.toggle("on", cb.checked)
                val lbl = tog.previousElementSibling as? HTMLElement; lbl?.textContent = if (cb.checked) "show" else "hidden"
                collectConfig(container); renderPreview(container)
            }
        }
    }
    sect.querySelector("#merge-newly-added")?.addEventListener("change") { _ ->
        val checked = (sect.querySelector("#merge-newly-added") as? HTMLInputElement)?.checked ?: false
        structural(container, { currentConfig = currentConfig.copy(mergeNewlyAdded = checked) }, ::renderRows)
    }
    sect.querySelector("#row-add")?.addEventListener("click") { _ ->
        val scope = rcScope ?: return@addEventListener
        openWorkbench(scope, "New content row", viewer = currentUserId, applyLabel = "Add row",
            onApply = { match, include, conds ->
                val label = conds.firstOrNull { it.values.isNotEmpty() }?.values?.firstOrNull() ?: "Custom row"
                val mediaKind = when (include) { "movies" -> "MOVIE"; "series" -> "SERIES"; else -> null }
                structural(container, {
                    currentConfig = currentConfig.copy(rows = currentConfig.rows +
                        RowConfig(id = genId("row"), kind = RowKind.CUSTOM, title = label, mediaKind = mediaKind, match = wbMode(match), conditions = wbConds(conds)))
                }, ::renderRows)
            })
    }
    wireDragReorder(container, sect, "row", "row-list",
        get = { currentConfig.rows }, set = { currentConfig = currentConfig.copy(rows = it) }, ::renderRows)
    for (i in currentConfig.rows.indices) {
        sect.querySelector("[data-row-del='$i']")?.addEventListener("click") { _ ->
            structural(container, {
                val list = currentConfig.rows.toMutableList(); list.removeAt(i)
                currentConfig = currentConfig.copy(rows = list)
            }, ::renderRows)
        }
        sect.querySelector("[data-row-edit='$i']")?.addEventListener("click") { _ ->
            val scope = rcScope ?: return@addEventListener
            val r = currentConfig.rows.getOrNull(i) ?: return@addEventListener
            val include = when (r.mediaKind) { "MOVIE" -> "movies"; "SERIES" -> "series"; else -> "all" }
            openWorkbench(scope, "Edit row — ${(r.title ?: "custom row")}", viewer = currentUserId,
                initialMatch = r.match.name, initialInclude = include, initialConds = wbCondsFrom(r.conditions), applyLabel = "Update row",
                onApply = { match, inc, conds ->
                    val mediaKind = when (inc) { "movies" -> "MOVIE"; "series" -> "SERIES"; else -> null }
                    structural(container, {
                        val list = currentConfig.rows.toMutableList()
                        list[i] = list[i].copy(kind = RowKind.CUSTOM, match = wbMode(match), conditions = wbConds(conds), mediaKind = mediaKind)
                        currentConfig = currentConfig.copy(rows = list)
                    }, ::renderRows)
                })
        }
    }
}

// ── Reorder helpers ─────────────────────────────────────────────────────────────

// ── Top 10 / Discover (R50) ─────────────────────────────────────────────────────

// R154 — which selected list rows can't actually serve data right now, keyed by ChartListSpec id.
// Missing from the map (coverage not loaded yet) is treated as "unknown", not "broken".
private fun discoverCoverageByListId(): Map<String, dev.jellystructure.shared.tv.ListCoverage> =
    discoverCoverage?.providers?.flatMap { it.lists }?.associateBy { it.listId } ?: emptyMap()

// R154 (FR-R154-1/2) — the status banner: errors before warnings, or a single "✓ verified" line when
// nothing's wrong. Reads the same coverage data the row-level warning chips use, so the two can never
// disagree with each other or with what the TV actually shows.
private fun buildDiscoverIssuesHtml(
    d: dev.jellystructure.shared.tv.DiscoverConfig,
    countryIngested: Boolean,
    coverageByListId: Map<String, dev.jellystructure.shared.tv.ListCoverage>,
): String {
    if (!d.enabled) return ""
    val coverage = discoverCoverage
    val countryLabel = DISCOVER_REGIONS.firstOrNull { it.first == d.region }?.second ?: d.region
    data class Issue(val level: String, val html: String)
    val issues = mutableListOf<Issue>()

    if (coverage != null && !coverage.radarrConnected) {
        issues += Issue("err", """Top 10 needs <b>Radarr</b> connected to fetch requested titles. <span class="fix" data-goto="/settings?tab=downloads">Connect Radarr →</span>""")
    }
    if (d.lists.isEmpty()) {
        issues += Issue("warn", "No lists selected — this user's Top 10 tab will be empty.")
    } else if (!countryIngested) {
        issues += Issue("warn", "<b>$countryLabel</b> isn't ingested by <a href=\"#/settings?tab=discover\">Settings → Discover</a> — every country list here will stay empty until it's added.")
    } else if (coverage != null) {
        // Per-provider: a provider whose every SELECTED country list is broken for this region.
        val selectedSpecs = d.lists.mapNotNull { id -> discoverSpecs.firstOrNull { it.id == id } }
        val byProvider = selectedSpecs.filter { it.scope == "country" }.groupBy { it.providerId }
        for ((pid, specs) in byProvider) {
            val allBroken = specs.isNotEmpty() && specs.all { coverageByListId[it.id]?.covered == false }
            if (allBroken) {
                val pname = DISCOVER_PROVIDER_NAMES[pid] ?: pid
                issues += Issue("warn", "<b>$pname</b> has no chart for $countryLabel — those rows are locked.")
            }
        }
        val shown = d.lists.count { id -> coverageByListId[id]?.covered != false }
        if (shown == 0) issues += Issue("warn", "No lists work for this source × country combination.")
    }

    if (issues.isEmpty()) {
        if (d.lists.isEmpty()) return ""
        val sourceNames = d.lists.mapNotNull { id -> discoverSpecs.firstOrNull { it.id == id }?.providerId }
            .distinct().mapNotNull { DISCOVER_PROVIDER_NAMES[it] }.joinToString(", ")
        return """<div class="t10-issue ok"><span class="ic">✓</span><div>Verified — ${sourceNames.htmlEsc()} for ${countryLabel.htmlEsc()} (${d.lists.size} list${if (d.lists.size != 1) "s" else ""} shown).</div></div>"""
    }
    return issues.sortedBy { if (it.level == "err") 0 else 1 }.joinToString("") { issue ->
        """<div class="t10-issue ${issue.level}"><span class="ic">⚠</span><div>${issue.html}</div></div>"""
    }
}

private fun renderDiscover(container: Element) {
    val sect = container.querySelector("#sect-discover") ?: return
    val d = currentConfig.discover
    val enabledChecked = if (d.enabled) " checked" else ""
    val canReqChecked = if (d.canRequest) " checked" else ""
    val coverage = discoverCoverage
    val countryIngested = coverage == null || d.region in coverage.ingestedRegions
    val regionOptions = DISCOVER_REGIONS.joinToString("") { (code, label) ->
        val sel = if (code == d.region) " selected" else ""
        """<option value="$code"$sel>$label</option>"""
    }
    val coverageByListId = discoverCoverageByListId()
    val selectedRows = d.lists.mapIndexed { i, id ->
        val spec = discoverSpecs.firstOrNull { it.id == id }
        val title = spec?.title ?: id
        val rankOnly = spec?.scope == "country"
        val providerName = DISCOVER_PROVIDER_NAMES[spec?.providerId ?: ""] ?: spec?.providerId ?: ""
        val sub = providerName + (spec?.let { " · ${it.scope} · ${it.metric}" } ?: "") + if (rankOnly) " · rank only" else ""
        // Only flag a row once coverage data has actually loaded — an unresolved id (spec == null,
        // e.g. mid region-switch) isn't itself a coverage problem.
        val cov = coverageByListId[id]
        val broken = coverage != null && cov != null && !cov.covered
        val warnCls = if (broken) " warn" else ""
        val reasonText = when (cov?.reason) {
            "empty_feed" -> "No results this week"
            "not_ingested" -> "No chart for ${DISCOVER_REGIONS.firstOrNull { it.first == d.region }?.second ?: d.region}"
            else -> "Unavailable"
        }
        val rowWarn = if (broken) """<span class="rowwarn show">⚠ ${reasonText.htmlEsc()}</span>""" else ""
        """
        <div class="cfg-row$warnCls" draggable="true" data-t10-i="$i" style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
          <span class="drag-handle" style="cursor:grab;user-select:none;flex-shrink:0">⠿</span>
          <div style="flex:1">
            <div style="font-size:.9rem">${title.htmlEsc()}</div>
            <div class="tiny muted">${sub.htmlEsc()}</div>
          </div>
          $rowWarn
          <button class="btn sm ghost" data-t10-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    val addable = discoverSpecs.filter { it.id !in d.lists }
    // Group addable specs by provider for a cleaner dropdown
    val addOptions = addable.groupBy { it.providerId }.entries.joinToString("") { (pid, specs) ->
        val pname = DISCOVER_PROVIDER_NAMES[pid] ?: pid
        """<optgroup label="$pname">${specs.joinToString("") { spec ->
            val cov = coverageByListId[spec.id]
            val flag = if (coverage != null && cov != null && !cov.covered) " ⚠" else ""
            """<option value="${spec.id}">${spec.title.htmlEsc()}$flag</option>"""
        }}</optgroup>"""
    }
    val addSelect = if (addable.isNotEmpty())
        """<select id="t10-add" class="input" style="margin-top:6px;font-size:.85rem"><option value="">+ Add list…</option>$addOptions</select>"""
    else if (discoverSpecs.isEmpty())
        """<p class="tiny muted" style="margin-top:6px">No providers enabled — enable chart sources in <a href="#/settings?tab=discover">Settings → Discover</a>.</p>"""
    else
        """<p class="tiny muted" style="margin-top:6px">All available charts for this country are added.</p>"""
    val countryWarnCls = if (!countryIngested) " warn" else ""
    val countryHint = if (!countryIngested)
        """<div class="fhint-warn">Not in <a href="#/settings?tab=discover">Settings → Discover</a>'s ingested countries — these lists will stay empty until it's added there.</div>"""
    else ""
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
            <div style="font-weight:600">Top 10</div>
            <span class="badge info" style="font-size:.6rem">Discover</span>
            <span style="flex:1"></span>
            <label style="display:flex;align-items:center;gap:6px;font-size:.85rem"><input type="checkbox" id="top10-enable"$enabledChecked> show this tab</label>
          </div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:12px">
            Requires the *arr serving the selected lists (movies → Radarr, TV → Sonarr) connected in
            <a href="#/settings?tab=downloads">Settings → Download tools</a> — otherwise the tab won't appear.
            Enable chart providers in <a href="#/settings?tab=discover">Settings → Discover</a>.
          </p>
          <div id="top10-body" style="display:grid;gap:12px">
            <label style="display:flex;align-items:center;gap:10px;font-size:.9rem">
              <input type="checkbox" id="top10-canrequest"$canReqChecked>
              Allow this user to request downloads <span class="tiny muted">(admins always can)</span>
            </label>
            <div class="field$countryWarnCls" style="display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0;">
              <span style="font-size:.9rem">Country</span>
              <select id="top10-region" class="input" style="width:200px;font-size:.85rem">$regionOptions</select>
            </div>
            $countryHint
            <div>
              <div style="font-size:.85rem;font-weight:500;margin-bottom:6px">Lists shown to this user</div>
              <div id="t10-list">$selectedRows</div>
              $addSelect
            </div>
            <p class="tiny muted">Country charts are <b>ranking only</b> (no view counts); Netflix global &amp; all-time lists carry real viewership hours.</p>
            <div class="t10-status" id="t10-status">${buildDiscoverIssuesHtml(d, countryIngested, coverageByListId)}</div>
          </div>
        </div>
    """.trimIndent()
    sect.querySelector("#t10-status")?.querySelectorAll(".fix[data-goto]")?.let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { dev.jellystructure.App.navigate(el.getAttribute("data-goto") ?: return@addEventListener) }
        }
    }
    wireDragReorder(container, sect, "t10", "t10-list",
        get = { currentConfig.discover.lists },
        set = { currentConfig = currentConfig.copy(discover = currentConfig.discover.copy(lists = it)) },
        ::renderDiscover)
    for (i in d.lists.indices) {
        sect.querySelector("[data-t10-del='$i']")?.addEventListener("click") { _ ->
            structural(container, {
                val l = currentConfig.discover.lists.toMutableList(); l.removeAt(i)
                currentConfig = currentConfig.copy(discover = currentConfig.discover.copy(lists = l))
            }, ::renderDiscover)
        }
    }
    (sect.querySelector("#t10-add") as? HTMLSelectElement)?.let { add ->
        add.addEventListener("change") { _ ->
            val v = add.value
            if (v.isNotBlank()) structural(container, {
                currentConfig = currentConfig.copy(discover = currentConfig.discover.copy(lists = currentConfig.discover.lists + v))
            }, ::renderDiscover)
        }
    }
    (sect.querySelector("#top10-region") as? HTMLSelectElement)?.let { reg ->
        reg.addEventListener("change") { _ ->
            collectConfig(container) // capture the new region (+ other edits)
            val scope = rcScope ?: return@addEventListener
            scope.launch {
                loadDiscoverForRegion(currentConfig.discover.region)
                val valid = discoverSpecs.map { it.id }.toSet()
                currentConfig = currentConfig.copy(discover = currentConfig.discover.copy(lists = currentConfig.discover.lists.filter { it in valid }))
                renderDiscover(container); renderPreview(container)
            }
        }
    }
}

private fun <T> wireDragReorder(
    container: Element,
    sect: Element,
    prefix: String,
    listContainerId: String,
    get: () -> List<T>,
    set: (List<T>) -> Unit,
    rerender: (Element) -> Unit,
) {
    val listEl = sect.querySelector("#$listContainerId") as? HTMLElement ?: return
    var dragFromIdx = -1
    fun clearHighlights() {
        val nl = listEl.querySelectorAll("[data-$prefix-i]")
        for (j in 0 until nl.length) {
            (nl.item(j) as? HTMLElement)?.classList?.remove("dragging", "drop-before", "drop-after")
        }
    }
    listEl.addEventListener("dragstart") { e ->
        val row = (e.target as? HTMLElement)?.closest("[data-$prefix-i]") as? HTMLElement ?: return@addEventListener
        dragFromIdx = row.getAttribute("data-$prefix-i")?.toIntOrNull() ?: -1
        row.classList.add("dragging")
    }
    listEl.addEventListener("dragend") { _ ->
        dragFromIdx = -1
        clearHighlights()
    }
    listEl.addEventListener("dragover") { e ->
        e.preventDefault()
        val row = (e.target as? HTMLElement)?.closest("[data-$prefix-i]") as? HTMLElement ?: return@addEventListener
        val toIdx = row.getAttribute("data-$prefix-i")?.toIntOrNull() ?: return@addEventListener
        clearHighlights()
        if (toIdx != dragFromIdx) row.classList.add(if (toIdx < dragFromIdx) "drop-before" else "drop-after")
    }
    listEl.addEventListener("drop") { e ->
        e.preventDefault()
        val row = (e.target as? HTMLElement)?.closest("[data-$prefix-i]") as? HTMLElement ?: return@addEventListener
        val toIdx = row.getAttribute("data-$prefix-i")?.toIntOrNull() ?: return@addEventListener
        val fromIdx = dragFromIdx
        dragFromIdx = -1
        if (fromIdx < 0 || fromIdx == toIdx) return@addEventListener
        structural(container, {
            val list = get().toMutableList()
            val item = list.removeAt(fromIdx)
            list.add(toIdx, item)
            set(list)
        }, rerender)
    }
}

// ── Behaviour ─────────────────────────────────────────────────────────────────

private val TILE_SHAPE_LABELS = mapOf(TileShape.POSTER to "Standard poster", TileShape.LANDSCAPE to "Wide landscape", TileShape.SQUARE to "Square")
private val DENSITY_LABELS = mapOf(UiDensity.COMPACT to "Compact (smaller)", UiDensity.COZY to "Cozy", UiDensity.COMFORTABLE to "Comfortable (default)")
private val LANGS = listOf("en" to "English", "da" to "Dansk", "fo" to "Føroyskt")

private fun renderBehaviour(container: Element) {
    val sect = container.querySelector("#sect-behaviour") ?: return
    val skinOptions = Skin.entries.joinToString("") { s ->
        val sel = if (s == currentConfig.defaultSkin) " selected" else ""
        """<option value="${s.name}"$sel>${s.name.lowercase().replaceFirstChar { it.uppercase() }}</option>"""
    }
    val tileButtons = TileShape.entries.joinToString("") { s ->
        val onAttr = if (s == currentConfig.tileShape) " class=\"on\"" else ""
        """<button data-tile-shape="${s.name}"$onAttr>${TILE_SHAPE_LABELS[s] ?: s.name}</button>"""
    }
    val densityOptions = UiDensity.entries.joinToString("") { d ->
        val sel = if (d == currentConfig.uiDensity) " selected" else ""
        """<option value="${d.name}"$sel>${DENSITY_LABELS[d] ?: d.name}</option>"""
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
            <div style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <div><span style="font-size:.9rem">Tile shape</span><div class="tiny muted">How rows render on the TV. Continue Watching stays landscape.</div></div>
              <span class="seg-pill" id="beh-tile-pill">$tileButtons</span>
            </div>
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Content size <span class="tiny muted">· grid/row tiles on the TV</span></span>
              <select id="beh-density" class="input" style="width:180px;font-size:.85rem">$densityOptions</select>
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

// R159 — "Portrait screen" section: overrides applied only when the viewer's app is in portrait
// (phone held upright, or a portrait browser window). This phase's only override is hero height; the
// section is deliberately the home for future portrait-only settings, not a one-off toggle.
private fun renderPortrait(container: Element) {
    val sect = container.querySelector("#sect-portrait") ?: return
    val portraitHero = currentConfig.portrait?.heroHeightPct
    val enabled = portraitHero != null
    val checkedAttr = if (enabled) " checked" else ""
    val disabledAttr = if (!enabled) " disabled" else ""
    val sliderVal = portraitHero ?: 30
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:6px">Portrait screen</div>
          <p class="tiny muted" style="margin:0 0 14px">Ravilo detects two display modes automatically: landscape (TVs, desktop web) and portrait
            (a phone held upright, or a resized browser window). Overrides set here apply <strong>only</strong> in portrait — landscape is
            untouched. This section is the home for future portrait-only settings.</p>
          <label style="display:flex;align-items:center;gap:10px;font-size:.9rem;margin-bottom:12px">
            <input type="checkbox" id="portrait-enable"$checkedAttr>
            Override hero height in portrait
          </label>
          <label style="display:block;font-size:.85rem;margin-bottom:4px">Hero height <span class="mono" id="portrait-hero-height-val">${sliderVal}%</span> of screen</label>
          <input type="range" id="portrait-hero-height" min="20" max="100" value="$sliderVal"$disabledAttr style="width:100%;accent-color:var(--acc,#7b6ef0)">
        </div>
    """.trimIndent()
}

// ── Live preview (schematic) ───────────────────────────────────────────────────

private fun defaultRowTitle(kind: RowKind, mediaKind: String? = null) = when (kind) {
    RowKind.CONTINUE    -> "Continue Watching"
    RowKind.NEWLY_ADDED -> when (mediaKind) {
        "MOVIE"  -> "Movies — Newly Added"
        "SERIES" -> "Series — Newly Added"
        else     -> "Newly Added"
    }
    RowKind.GENRE       -> "Genre"
    RowKind.CUSTOM      -> "Custom"
}

private fun previewRowTitles(cfg: RaviloConfig): List<String> {
    val enabled = cfg.rows.filter { it.enabled }.sortedBy { it.order }
    val out = mutableListOf<String>()
    var mergedAdded = false
    for (r in enabled) {
        if (r.kind == RowKind.NEWLY_ADDED) {
            if (cfg.mergeNewlyAdded) {
                // merge ON → one combined row
                if (!mergedAdded) { out.add(r.title?.takeIf { it.isNotBlank() } ?: "Newly Added"); mergedAdded = true }
                continue
            }
            if (r.mediaKind == null) {
                // merge OFF + all-media row → show as two typed rows (matches feed behaviour)
                val base = r.title?.takeIf { it.isNotBlank() }
                out.add(base?.let { "$it — Movies" } ?: "Movies — Newly Added")
                out.add(base?.let { "$it — Series" } ?: "Series — Newly Added")
                continue
            }
        }
        out.add(r.title?.takeIf { it.isNotBlank() } ?: defaultRowTitle(r.kind, r.mediaKind))
    }
    return out
}

private fun renderPreview(container: Element) {
    val host = container.querySelector("#rav-preview") ?: return
    val cfg = currentConfig
    val firstHero = cfg.heroes.firstOrNull { it.enabled }
    val heroLabel = firstHero?.displayTitle?.takeIf { it.isNotBlank() }
        ?: firstHero?.itemId?.takeIf { it.isNotBlank() } ?: "Hero"
    val heroBg = firstHero?.displayBackdrop?.takeIf { it.isNotBlank() }
        ?.let { "url('https://image.tmdb.org/t/p/w780$it') center/cover,${heroGradient(firstHero.itemId)}" }
        ?: heroGradient(firstHero?.itemId ?: "")
    val heroPct = cfg.heroHeightPct.coerceIn(40, 100)
    val channels = cfg.channels.filter { it.enabled }
    val rowTitles = previewRowTitles(cfg)
    val portraitPct = cfg.portrait?.heroHeightPct
    host.innerHTML = buildString {
        append("""<div style="display:flex;gap:10px;align-items:flex-start">""")
        append("""<div style="flex:1;border-radius:10px;overflow:hidden;border:1px solid var(--line);background:#0a0c13;aspect-ratio:16/10;display:flex;flex-direction:column">""")
        append("""<div id="rav-prev-hero" style="height:$heroPct%;background:$heroBg;display:flex;align-items:flex-end;padding:8px"><span style="color:#fff;font-weight:700;font-size:.68rem;text-shadow:0 1px 4px rgba(0,0,0,.6)">${heroLabel.htmlEsc()}</span></div>""")
        if (channels.isNotEmpty()) {
            append("""<div style="display:flex;gap:4px;padding:6px 8px;overflow:hidden">""")
            channels.take(5).forEach { c ->
                val bg = c.brandColor?.takeIf { it.isNotBlank() } ?: "#1b2031"
                append("""<span style="background:$bg;color:#fff;font-size:.52rem;padding:2px 6px;border-radius:5px;white-space:nowrap">${c.name.ifBlank { "Channel" }.htmlEsc()}</span>""")
            }
            append("</div>")
        }
        append("""<div style="flex:1;padding:4px 8px;overflow:hidden">""")
        rowTitles.take(5).forEach { append("""<div style="color:#aeb4cb;font-size:.58rem;margin-bottom:5px">${it.htmlEsc()} <span style="opacity:.35">▦ ▦ ▦</span></div>""") }
        append("</div>")
        append("</div>")
        // R159 — portrait thumbnail: only shown when an override is actually set, so it never implies
        // portrait behaviour exists when the toggle is off.
        if (portraitPct != null) {
            append("""<div style="flex:none;width:64px" title="Portrait preview">""")
            append("""<div style="border-radius:8px;overflow:hidden;border:1px solid var(--line);background:#0a0c13;aspect-ratio:9/16;display:flex;flex-direction:column">""")
            append("""<div style="height:${portraitPct.coerceIn(20, 100)}%;background:$heroBg"></div>""")
            append("""<div style="flex:1"></div>""")
            append("</div>")
            append("""<div class="tiny muted" style="text-align:center;margin-top:4px">Portrait</div>""")
            append("</div>")
        }
        append("</div>")
    }
}

// ── Collect current form state into the shared RaviloConfig ────────────────────

private fun collectConfig(container: Element) {
    // Heroes are managed fully in-memory — each add/remove/toggle/edit updates currentConfig.heroes
    // directly, so no DOM collection is needed here.
    val heroes = currentConfig.heroes.mapIndexed { i, h -> h.copy(order = i) }
    // Channels — overlay DOM edits onto the existing entries (render order == currentConfig order),
    // so workbench-built `match`/`conditions` survive a collect (R32; was dropped — see P0-1).
    // Channel name + Show are edited inline; style / brandColor / logoUrl / conditions are set via the
    // channel-button popup (R36) directly into currentConfig, so they ride along on `existing`.
    val channels = currentConfig.channels.mapIndexed { i, existing ->
        fun q(attr: String) = container.querySelector("[$attr='$i']")
        existing.copy(
            name = (q("data-ch-name") as? HTMLInputElement)?.value?.trim() ?: existing.name,
            enabled = (q("data-ch-enabled") as? HTMLInputElement)?.checked ?: existing.enabled,
            order = i,
        )
    }
    val rows = currentConfig.rows.mapIndexed { i, existing ->
        fun q(attr: String) = container.querySelector("[$attr='$i']")
        val titleEl = q("data-row-title") as? HTMLInputElement
        val title   = if (titleEl != null) titleEl.value.trim().ifEmpty { null } else existing.title
        val enabled = (q("data-row-enabled") as? HTMLInputElement)?.checked ?: existing.enabled
        existing.copy(title = title, enabled = enabled, order = i)
    }
    val mergeNewlyAdded = (container.querySelector("#merge-newly-added") as? HTMLInputElement)?.checked ?: currentConfig.mergeNewlyAdded
    val heroHeight   = (container.querySelector("#hero-height") as? HTMLInputElement)?.value?.toIntOrNull() ?: 56
    val autoAdvance  = (container.querySelector("#auto-advance") as? HTMLSelectElement)?.value?.toIntOrNull() ?: 7
    val uiLanguage   = (container.querySelector("#beh-lang") as? HTMLSelectElement)?.value ?: "en"
    val defaultSkin  = runCatching { Skin.valueOf((container.querySelector("#beh-skin") as? HTMLSelectElement)?.value ?: "AURORA") }
        .getOrDefault(Skin.AURORA)
    val tileShape = run {
        val btns = container.querySelectorAll("[data-tile-shape]")
        var shapeName = "POSTER"
        for (k in 0 until btns.length) {
            val btn = btns.item(k) as? HTMLElement ?: continue
            if (btn.classList.contains("on")) { shapeName = btn.getAttribute("data-tile-shape") ?: "POSTER"; break }
        }
        runCatching { TileShape.valueOf(shapeName) }.getOrDefault(TileShape.POSTER)
    }
    val uiDensity    = runCatching { UiDensity.valueOf((container.querySelector("#beh-density") as? HTMLSelectElement)?.value ?: "COMFORTABLE") }
        .getOrDefault(UiDensity.COMFORTABLE)
    val allowOverride = (container.querySelector("#beh-skin-override") as? HTMLInputElement)?.checked ?: true
    val showProgress  = (container.querySelector("#beh-progress") as? HTMLInputElement)?.checked ?: true
    // R159 — toggled off saves null (no override; portrait behaves exactly like landscape).
    val portraitEnabled = (container.querySelector("#portrait-enable") as? HTMLInputElement)?.checked ?: (currentConfig.portrait?.heroHeightPct != null)
    val portraitHeroHeight = (container.querySelector("#portrait-hero-height") as? HTMLInputElement)?.value?.toIntOrNull()
    val portrait = if (portraitEnabled) PortraitConfig(heroHeightPct = portraitHeroHeight ?: currentConfig.portrait?.heroHeightPct ?: 30) else null
    // Discover (R50) — toggles/selects from the DOM; the ordered `lists` are managed structurally.
    // R154: `sources` is derived from the selected lists' providers (this editor is list-first, not
    // source-first — see renderDiscover) so it stays accurate for any other consumer without its own UI
    // control; `source` (singular) is kept in sync too for pre-R154 clients reading the legacy field.
    val derivedSources = currentConfig.discover.lists
        .mapNotNull { id -> discoverSpecs.firstOrNull { it.id == id }?.providerId }
        .distinct()
    val discover = currentConfig.discover.copy(
        enabled    = (container.querySelector("#top10-enable") as? HTMLInputElement)?.checked ?: currentConfig.discover.enabled,
        canRequest = (container.querySelector("#top10-canrequest") as? HTMLInputElement)?.checked ?: currentConfig.discover.canRequest,
        source     = derivedSources.firstOrNull() ?: currentConfig.discover.source,
        sources    = derivedSources.ifEmpty { currentConfig.discover.sources },
        region     = (container.querySelector("#top10-region") as? HTMLSelectElement)?.value ?: currentConfig.discover.region,
    )
    currentConfig = RaviloConfig(
        heroes = heroes,
        channels = channels,
        rows = rows,
        mergeNewlyAdded = mergeNewlyAdded,
        defaultSkin = defaultSkin,
        allowSkinOverride = allowOverride,
        // Preserve viewer-set fields that are not exposed in the admin UI.
        viewerSkinOverride = currentConfig.viewerSkinOverride,
        autoplayNext = currentConfig.autoplayNext,
        showContinueProgress = showProgress,
        tileShape = tileShape,
        uiDensity = uiDensity,
        uiLanguage = uiLanguage,
        heroHeightPct = heroHeight,
        autoAdvanceSeconds = autoAdvance,
        discover = discover,
        portrait = portrait,
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
