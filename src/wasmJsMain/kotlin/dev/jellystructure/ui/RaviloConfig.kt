package dev.jellystructure.ui

import dev.jellystructure.api.JellyfinUser
import dev.jellystructure.api.MetadataApi
import dev.jellystructure.api.RaviloApi
import dev.jellystructure.scrollIntoViewSmooth
import dev.jellystructure.shared.tv.ChannelButtonPadding
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.ChannelStyle
import dev.jellystructure.shared.tv.PageHeroConfig
import dev.jellystructure.shared.tv.ChartListSpec
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.HeroConfig
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TileShape
import dev.jellystructure.shared.tv.UiDensity
import kotlinx.browser.document
import kotlinx.browser.window
import dev.jellystructure.api.MediaApi
import dev.jellystructure.model.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
private var discoverSpecs: List<ChartListSpec> = emptyList()  // R50 — available charts for the edited region

private val DISCOVER_SOURCES = listOf(Triple("netflix", "Netflix · via Tudum", false), Triple("disney", "Disney+", true), Triple("max", "Max", true))
private val DISCOVER_REGIONS = listOf("DK" to "Denmark", "NO" to "Norway", "SE" to "Sweden", "FI" to "Finland", "IS" to "Iceland", "GB" to "United Kingdom", "US" to "United States", "DE" to "Germany", "FR" to "France")
private var users: List<JellyfinUser> = emptyList()
private var facets: Map<String, List<String>> = emptyMap() // "NETWORK"/"STUDIO"/"GENRE"/"TAG" -> values

private val CHANNEL_KINDS = listOf("NETWORK", "STUDIO", "GENRE", "TAG")
private val AUTO_ADVANCE_OPTIONS = listOf(0 to "Off", 4 to "4 s", 6 to "6 s", 7 to "7 s", 8 to "8 s", 10 to "10 s")

private fun genId(prefix: String) = "$prefix-${Random.nextInt(100_000, 999_999)}"

private fun wbMode(match: String) = if (match == "ANY") MatchMode.ANY else MatchMode.ALL
private fun wbConds(conds: List<WbCond>): List<Condition> =
    conds.filter { it.values.isNotEmpty() || it.facet == "track_title" }.map { Condition(it.facet, it.op, it.values.toList()) }
private fun wbCondsFrom(conds: List<Condition>): List<WbCond> =
    conds.map { WbCond(it.facet, it.op, it.values.toMutableList()) }

// The jellyfish brand mark (matches design/app/ravilo-config.html).
private const val RAVILO_MARK = """<svg viewBox="12 20 76 76" aria-hidden="true" style="width:1.12em;height:1.12em;flex:none;filter:drop-shadow(0 0 8px rgba(123,110,240,.5))"><defs><linearGradient id="ravJelly" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7b6ef0"/><stop offset="1" stop-color="#3fb6f5"/></linearGradient></defs><path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#ravJelly)"/><g stroke="url(#ravJelly)" stroke-width="4.5" stroke-linecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>"""

fun renderRaviloConfig(container: Element, scope: CoroutineScope) {
    rcScope = scope
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
        discoverSpecs = runCatching { RaviloApi.getDiscoverLists(currentConfig.discover.region) }.getOrDefault(emptyList())
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
    <div class="pagebar">
      <h1 style="display:flex;align-items:center;gap:.4em">$RAVILO_MARK Ravilo TV</h1>
      <span class="badge info">app config</span>
      <span class="spacer"></span>
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
          <button data-rav-sect="sect-pair"     class="rav-nav-item">Pair a TV</button>
          <button data-rav-sect="sect-heroes"   class="rav-nav-item">Hero carousel</button>
          <button data-rav-sect="sect-channels" class="rav-nav-item">Channels</button>
          <button data-rav-sect="sect-rows"     class="rav-nav-item">Content rows</button>
          <button data-rav-sect="sect-discover" class="rav-nav-item">Top 10</button>
          <button data-rav-sect="sect-behaviour"class="rav-nav-item">Behaviour</button>
        </div>
      </nav>
      <div class="col fill" style="min-width:280px" id="rav-sections">
        <div id="sect-pair"></div>
        <div id="sect-heroes"></div>
        <div id="sect-channels"></div>
        <div id="sect-rows"></div>
        <div id="sect-discover"></div>
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

private fun reloadScopeIntoSections(container: Element, scope: CoroutineScope) {
    (container.querySelector("#rav-sections") as? HTMLElement)?.innerHTML =
        """<p class="page-sub" style="color:var(--ink-soft);padding:24px 0">Loading…</p>"""
    scope.launch {
        val resp = runCatching {
            if (currentScopeIsGlobal) RaviloApi.getConfigWithMeta(scope = "global")
            else RaviloApi.getConfigWithMeta(userId = currentUserId)
        }.getOrNull()
        currentConfig = resp?.config ?: RaviloConfig()
        currentHasOverride = resp?.hasOverride ?: false
        discoverSpecs = runCatching { RaviloApi.getDiscoverLists(currentConfig.discover.region) }.getOrDefault(emptyList())
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
    renderDiscover(container)
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

private fun heroGradient(id: String): String {
    val hue = id.fold(0) { acc, c -> acc * 31 + c.code }.absoluteValue % 360
    return "linear-gradient(145deg,hsl($hue 48% 36%),hsl(${(hue + 45) % 360} 52% 14%))"
}

private fun renderHeroes(container: Element) {
    val sect = container.querySelector("#sect-heroes") ?: return
    val last = currentConfig.heroes.lastIndex
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
        <div class="cfg-row$rowCls" style="gap:12px">
          ${reorderButtons("hero", i, last)}
          <div class="hero-thumb" style="$thumbStyle;width:92px;height:52px;border-radius:8px;flex:none;position:relative;overflow:hidden">
            <span style="position:absolute;left:7px;bottom:5px;font-family:var(--font-display,'Space Grotesk',sans-serif);font-weight:700;font-size:.68rem;color:#fff;text-shadow:0 1px 4px rgba(0,0,0,.7)">${shortTitle.htmlEsc()}</span>
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
    wireReorder(container, sect, "hero",
        get = { currentConfig.heroes }, set = { currentConfig = currentConfig.copy(heroes = it) }, ::renderHeroes)
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

// R36: the channel-button chip rendered on each config row + in the live preview, from the channel's
// style/brandColor/logoUrl (brandColor may be a solid color or a CSS linear-gradient).
private fun channelChipHtml(c: ChannelConfig): String {
    val fill = c.brandColor?.takeIf { it.isNotBlank() } ?: "linear-gradient(135deg,#3b2a78,#15102e)"
    val inner = if (c.style == ChannelStyle.LOGO && !c.logoUrl.isNullOrBlank()) {
        """<img src="${c.logoUrl!!.htmlEsc()}" alt="" style="width:100%;height:100%;object-fit:cover">"""
    } else {
        (if (c.style == ChannelStyle.LOGO) c.name.take(3).uppercase() else c.name.take(10).ifEmpty { "Ch" }).htmlEsc()
    }
    return """<div style="background:$fill;width:84px;height:34px;border-radius:8px;display:flex;align-items:center;justify-content:center;color:#fff;font-weight:700;font-size:.7rem;overflow:hidden;flex:none">$inner</div>"""
}

// Seed the popup's condition stack from a legacy single-typed channel so editing preserves its filter.
private fun legacyToConds(c: ChannelConfig): List<WbCond> {
    val (kind, value) = c.kindAndValue()
    if (value.isBlank()) return emptyList()
    return listOf(WbCond(kind.lowercase(), "is_any_of", mutableListOf(value)))
}

private fun renderChannels(container: Element) {
    val sect = container.querySelector("#sect-channels") ?: return
    val last = currentConfig.channels.lastIndex
    val rows = currentConfig.channels.mapIndexed { i, c ->
        val showChecked = if (c.enabled) " checked" else ""
        val summary = if (c.conditions.isNotEmpty()) {
            "${c.conditions.size} condition(s) · ${c.match.name}"
        } else {
            val (kind, value) = c.kindAndValue()
            if (value.isNotBlank()) "${kind.lowercase().replaceFirstChar { it.uppercase() }}: $value" else "No filter yet"
        }
        """
        <div class="cfg-row" style="display:flex;align-items:center;gap:9px;margin-bottom:8px;flex-wrap:wrap">
          ${reorderButtons("ch", i, last)}
          <input type="hidden" data-ch-id="$i" value="${c.id.htmlEsc()}">
          ${channelChipHtml(c)}
          <input class="input" style="width:140px" placeholder="Name" value="${c.name.htmlEsc()}" data-ch-name="$i">
          <span class="badge" style="white-space:nowrap">${summary.htmlEsc()}</span>
          <span class="spacer" style="flex:1"></span>
          <button class="btn sm ghost" data-ch-edit="$i" title="Edit channel button + filter">✎ Edit</button>
          <label style="display:flex;align-items:center;gap:5px;font-size:.8rem;white-space:nowrap"><input type="checkbox" data-ch-enabled="$i"$showChecked> Show</label>
          <button class="btn sm ghost" data-ch-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Channels &amp; collections</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            The logo row under the hero. <b>Click ✎ Edit on a channel</b> to set its filter, choose Logo or Text, pick or upload a brand logo, and set the brand fill (solid or gradient).
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
        sect.querySelector("[data-ch-edit='$i']")?.addEventListener("click") { _ ->
            val scope = rcScope ?: return@addEventListener
            val c = currentConfig.channels.getOrNull(i) ?: return@addEventListener
            openChannelEditorPage(container, scope, i, c)
        }
    }
}

// ── Channel editor page (R53) ─────────────────────────────────────────────────

private fun openChannelEditorPage(container: Element, scope: CoroutineScope, idx: Int, c: ChannelConfig) {
    val seed = if (c.conditions.isNotEmpty()) wbCondsFrom(c.conditions) else legacyToConds(c)
    val styleChecked = { s: String -> if ((if (c.style == ChannelStyle.LOGO) "logo" else "text") == s) " checked" else "" }
    val advOpts = AUTO_ADVANCE_OPTIONS.joinToString("") { (v, l) ->
        val s = if (v == (c.pageHero?.autoAdvanceSeconds ?: 7)) " selected" else ""
        """<option value="$v"$s>$l</option>"""
    }
    val pHero = c.pageHero
    val heroEnabled = pHero?.enabled == true
    val heroHeightVal = pHero?.heroHeightPct ?: 56
    val heroItemCount = pHero?.items?.size ?: 0
    val padLogo = c.paddingLogo
    val padText = c.paddingText

    container.innerHTML = """
        <div class="pagebar" style="margin-bottom:18px">
          <button id="ch-ed-back" class="btn sm ghost">‹ Back to layout</button>
          <h2 style="margin:0;flex:1;text-align:center">${(if (c.name.isBlank()) "Channel" else c.name).htmlEsc()}</h2>
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
              <div style="margin-top:10px">
                <label class="tiny muted">Logo URL (for Logo style)</label>
                <input id="ch-ed-logo" class="input" value="${(c.logoUrl ?: "").htmlEsc()}" placeholder="https://…" style="width:100%;margin-top:4px">
              </div>
              <div style="margin-top:10px">
                <label class="tiny muted">Brand fill (hex or linear-gradient(…))</label>
                <input id="ch-ed-color" class="input" value="${(c.brandColor ?: "").htmlEsc()}" placeholder="#1a1a2e" style="width:100%;margin-top:4px">
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
              <b>Content filter</b>
              <div style="margin-top:10px" id="ch-ed-wb-host"></div>
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
                <div style="display:flex;align-items:center;gap:10px;margin-bottom:10px">
                  <label class="tiny muted">Height</label>
                  <input id="ch-hero-height" type="range" min="40" max="100" value="$heroHeightVal" style="flex:1">
                  <span id="ch-hero-height-val" class="tiny">$heroHeightVal%</span>
                </div>
                <div style="display:flex;align-items:center;gap:10px;margin-bottom:14px">
                  <label class="tiny muted">Auto-advance</label>
                  <select id="ch-hero-advance" class="input" style="width:auto">$advOpts</select>
                </div>
                <div style="font-size:.82rem;color:var(--ink-soft);margin-bottom:8px">Hero items: <b id="ch-hero-count">$heroItemCount</b></div>
                <button id="ch-hero-edit" class="btn sm ghost">✎ Edit hero items</button>
              </div>
            </div>
          </div>
        </div>
    """.trimIndent()

    // Wire back / cancel
    val onBack = {
        currentConfig = currentConfig  // unchanged
        renderFull(container, scope)
    }
    container.querySelector("#ch-ed-back")?.addEventListener("click") { _ -> onBack() }
    container.querySelector("#ch-ed-cancel")?.addEventListener("click") { _ -> onBack() }

    // Hero enable toggle
    container.querySelector("#ch-hero-enabled")?.addEventListener("change") { _ ->
        val cb = container.querySelector("#ch-hero-enabled") as? org.w3c.dom.HTMLInputElement ?: return@addEventListener
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
        val heroEn = (container.querySelector("#ch-hero-enabled") as? org.w3c.dom.HTMLInputElement)?.checked ?: false
        val heroH = (container.querySelector("#ch-hero-height") as? HTMLInputElement)?.value?.toIntOrNull() ?: 56
        val heroAdv = (container.querySelector("#ch-hero-advance") as? HTMLSelectElement)?.value?.toIntOrNull() ?: 7
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
            style = if (styleVal == "TEXT") ChannelStyle.TEXT else ChannelStyle.LOGO,
            brandColor = color,
            logoUrl = logo,
            pageHero = (cur.pageHero ?: PageHeroConfig()).copy(
                enabled = heroEn,
                heroHeightPct = heroH,
                autoAdvanceSeconds = heroAdv,
            ),
            paddingLogo = paddingLogo,
            paddingText = paddingText,
        )
        currentConfig = currentConfig.copy(channels = list)
        renderFull(container, scope)
    }

    // Inline filter workbench host
    injectWorkbenchInline(container.querySelector("#ch-ed-wb-host") as? HTMLElement ?: return, scope, seed, c.match.name, currentUserId)
}

/** Minimal hero-items editor overlay (reuses the existing hero section approach). */
private fun openHeroEditorOverlay(
    container: Element,
    scope: CoroutineScope,
    heroItems: List<HeroConfig>,
    title: String,
    onSave: (List<HeroConfig>) -> Unit,
) {
    val existing = document.getElementById("hero-ov") as? HTMLElement
    existing?.remove()
    val ov = document.createElement("div") as HTMLElement
    ov.id = "hero-ov"
    ov.style.cssText = "position:fixed;inset:0;background:#000a;display:flex;align-items:center;justify-content:center;z-index:9000"
    var editItems = heroItems.toMutableList()
    fun buildHtml(): String = """
        <div style="background:var(--fill);border-radius:14px;padding:24px;max-width:500px;width:100%;max-height:85vh;overflow:auto">
          <div style="display:flex;align-items:center;gap:10px;margin-bottom:16px">
            <b>${title.htmlEsc()}</b><span class="spacer"></span>
            <button id="hero-ov-close" class="btn sm ghost">✕</button>
          </div>
          <div id="hero-ov-items">
            ${editItems.mapIndexed { i, h ->
                """<div class="row" style="gap:8px;margin-bottom:8px;align-items:center">
                    <input class="input" placeholder="Item ID" value="${h.itemId.htmlEsc()}" data-hov-id="$i" style="flex:1">
                    <button class="btn sm ghost" data-hov-del="$i">✕</button>
                   </div>"""
            }.joinToString("")}
          </div>
          <button id="hero-ov-add" class="btn sm ghost" style="margin-bottom:14px">+ Add item</button>
          <div style="display:flex;justify-content:flex-end;gap:8px">
            <button id="hero-ov-cancel" class="btn sm ghost">Cancel</button>
            <button id="hero-ov-save" class="btn primary">Save hero items</button>
          </div>
        </div>
    """.trimIndent()
    ov.innerHTML = buildHtml()
    document.body?.appendChild(ov)

    fun rewire() {
        ov.querySelector("#hero-ov-close")?.addEventListener("click") { _ -> ov.remove() }
        ov.querySelector("#hero-ov-cancel")?.addEventListener("click") { _ -> ov.remove() }
        ov.querySelector("#hero-ov-add")?.addEventListener("click") { _ ->
            editItems.add(HeroConfig(itemId = "")); ov.innerHTML = buildHtml(); rewire()
        }
        ov.querySelector("#hero-ov-save")?.addEventListener("click") { _ ->
            val items = ov.querySelectorAll("[data-hov-id]")
            for (j in 0 until items.length) {
                val inp = items.item(j) as? HTMLInputElement ?: continue
                val jj = inp.getAttribute("data-hov-id")?.toIntOrNull() ?: continue
                if (jj < editItems.size) editItems[jj] = editItems[jj].copy(itemId = inp.value.trim())
            }
            editItems = editItems.filter { it.itemId.isNotBlank() }.toMutableList()
            onSave(editItems)
            ov.remove()
        }
        for (j in editItems.indices) {
            ov.querySelector("[data-hov-del='$j']")?.addEventListener("click") { _ ->
                editItems.removeAt(j); ov.innerHTML = buildHtml(); rewire()
            }
        }
    }
    rewire()
}

/** Inline filter workbench — renders condition rows directly into [host] without a modal. */
private fun injectWorkbenchInline(host: HTMLElement, scope: CoroutineScope, initConds: List<WbCond>, initMatch: String, viewer: String?) {
    var conds = initConds.map { WbCond(it.facet, it.op, it.values.toMutableList()) }.toMutableList()
    if (conds.isEmpty()) conds.add(WbCond("studio", "is_any_of"))
    var match = initMatch

    fun buildHtml(): String = """
        <div>
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
            <span class="tiny muted">Match</span>
            <select id="wb-inline-match" class="input" style="width:auto;font-size:.8rem">
              <option value="ALL"${if (match == "ALL") " selected" else ""}>All conditions</option>
              <option value="ANY"${if (match == "ANY") " selected" else ""}>Any condition</option>
            </select>
          </div>
          ${conds.mapIndexed { i, cond ->
              val facetOpts = listOf("studio","network","genre","tag").joinToString("") { f ->
                  """<option value="$f"${if (f == cond.facet) " selected" else ""}>${f.replaceFirstChar{it.uppercase()}}</option>"""
              }
              val valList = facets[cond.facet.uppercase()]?.joinToString(",") ?: ""
              """<div style="display:flex;gap:5px;align-items:center;margin-bottom:5px;flex-wrap:wrap">
                  <select class="input" style="width:90px;font-size:.78rem" data-wbi-facet="$i">$facetOpts</select>
                  <input class="input" style="flex:1;font-size:.78rem" placeholder="value" value="${cond.values.joinToString(", ").htmlEsc()}" data-wbi-val="$i" list="facet-${cond.facet.lowercase()}">
                  <button class="btn sm ghost" data-wbi-del="$i">✕</button>
                 </div>"""
          }.joinToString("")}
          <button id="wb-inline-add" class="btn sm ghost" style="margin-top:4px;font-size:.78rem">+ Add condition</button>
        </div>
    """.trimIndent()

    fun rewire() {
        host.querySelector("#wb-inline-match")?.addEventListener("change") { _ ->
            match = (host.querySelector("#wb-inline-match") as? HTMLSelectElement)?.value ?: match
        }
        host.querySelector("#wb-inline-add")?.addEventListener("click") { _ ->
            conds.add(WbCond("studio", "is_any_of")); host.innerHTML = buildHtml(); rewire()
        }
        for (j in conds.indices) {
            host.querySelector("[data-wbi-del='$j']")?.addEventListener("click") { _ ->
                conds.removeAt(j); host.innerHTML = buildHtml(); rewire()
            }
            host.querySelector("[data-wbi-facet='$j']")?.addEventListener("change") { _ ->
                val f = (host.querySelector("[data-wbi-facet='$j']") as? HTMLSelectElement)?.value ?: return@addEventListener
                conds[j].facet = f
            }
            host.querySelector("[data-wbi-val='$j']")?.addEventListener("change") { _ ->
                val v = (host.querySelector("[data-wbi-val='$j']") as? HTMLInputElement)?.value ?: return@addEventListener
                conds[j].values.clear()
                conds[j].values.addAll(v.split(",").map { it.trim() }.filter { it.isNotBlank() })
            }
        }
    }

    host.innerHTML = buildHtml()
    rewire()
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
        // R32: a CUSTOM row built from a condition stack shows a read-only summary + Edit, no kind select.
        val bodyCell = if (r.conditions.isNotEmpty()) {
            """<span class="badge ok" style="white-space:nowrap">${r.conditions.size} condition(s) · match ${r.match.name}</span>
               <button class="btn sm ghost" data-row-edit="$i">Edit filter</button>
               <select class="input" style="width:90px" data-row-media="$i">$mediaOptions</select>
               <span class="spacer" style="flex:1"></span>"""
        } else {
            """<select class="input" style="width:140px" data-row-kind="$i">$kindOptions</select>
               <select class="input" style="width:90px" data-row-media="$i">$mediaOptions</select>"""
        }
        """
        <div class="cfg-row" style="display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap">
          ${reorderButtons("row", i, last)}
          $badge
          <input type="hidden" data-row-id="$i" value="${r.id.htmlEsc()}">
          <input class="input" style="width:150px" placeholder="Title" value="${(r.title ?: "").htmlEsc()}" data-row-title="$i"$genreList>
          $bodyCell
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
                        list[i] = list[i].copy(match = wbMode(match), conditions = wbConds(conds), mediaKind = mediaKind)
                        currentConfig = currentConfig.copy(rows = list)
                    }, ::renderRows)
                })
        }
    }
}

// ── Reorder helpers ─────────────────────────────────────────────────────────────

// ── Top 10 / Discover (R50) ─────────────────────────────────────────────────────

private fun renderDiscover(container: Element) {
    val sect = container.querySelector("#sect-discover") ?: return
    val d = currentConfig.discover
    val enabledChecked = if (d.enabled) " checked" else ""
    val canReqChecked = if (d.canRequest) " checked" else ""
    val sourceOptions = DISCOVER_SOURCES.joinToString("") { (id, label, soon) ->
        val sel = if (id == d.source) " selected" else ""
        val dis = if (soon) " disabled" else ""
        """<option value="$id"$sel$dis>$label${if (soon) " (soon)" else ""}</option>"""
    }
    val regionOptions = DISCOVER_REGIONS.joinToString("") { (code, label) ->
        val sel = if (code == d.region) " selected" else ""
        """<option value="$code"$sel>$label</option>"""
    }
    val last = d.lists.lastIndex
    val selectedRows = d.lists.mapIndexed { i, id ->
        val spec = discoverSpecs.firstOrNull { it.id == id }
        val title = spec?.title ?: id
        val rankOnly = spec?.scope == "country"
        val sub = (spec?.let { "${it.scope} · ${it.metric}" } ?: "") + if (rankOnly) " · rank only (no view counts)" else ""
        """
        <div class="cfg-row" style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
          ${reorderButtons("t10", i, last)}
          <div style="flex:1">
            <div style="font-size:.9rem">${title.htmlEsc()}</div>
            <div class="tiny muted">${sub.htmlEsc()}</div>
          </div>
          <button class="btn sm ghost" data-t10-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    val addable = discoverSpecs.filter { it.id !in d.lists }
    val addSelect = if (addable.isNotEmpty())
        """<select id="t10-add" class="input" style="margin-top:6px;font-size:.85rem"><option value="">+ Add list…</option>${addable.joinToString("") { """<option value="${it.id}">${it.title.htmlEsc()}</option>""" }}</select>"""
    else """<p class="tiny muted" style="margin-top:6px">All available charts for this country are added.</p>"""
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
          </p>
          <div id="top10-body" style="display:grid;gap:12px">
            <label style="display:flex;align-items:center;gap:10px;font-size:.9rem">
              <input type="checkbox" id="top10-canrequest"$canReqChecked>
              Allow this user to request downloads <span class="tiny muted">(admins always can)</span>
            </label>
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Source</span>
              <select id="top10-source" class="input" style="width:200px;font-size:.85rem">$sourceOptions</select>
            </label>
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Country</span>
              <select id="top10-region" class="input" style="width:200px;font-size:.85rem">$regionOptions</select>
            </label>
            <div>
              <div style="font-size:.85rem;font-weight:500;margin-bottom:6px">Lists shown to this user <span class="tiny muted">(order with ↑↓)</span></div>
              $selectedRows
              $addSelect
            </div>
            <p class="tiny muted">Country charts are <b>ranking only</b> (no view counts); global &amp; all-time carry real viewership.</p>
          </div>
        </div>
    """.trimIndent()
    wireReorder(container, sect, "t10",
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
                discoverSpecs = runCatching { RaviloApi.getDiscoverLists(currentConfig.discover.region) }.getOrDefault(discoverSpecs)
                val valid = discoverSpecs.map { it.id }.toSet()
                currentConfig = currentConfig.copy(discover = currentConfig.discover.copy(lists = currentConfig.discover.lists.filter { it in valid }))
                renderDiscover(container); renderPreview(container)
            }
        }
    }
}

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

private val TILE_SHAPE_LABELS = mapOf(TileShape.POSTER to "Standard poster", TileShape.LANDSCAPE to "Wide landscape", TileShape.SQUARE to "Square")
private val DENSITY_LABELS = mapOf(UiDensity.COMPACT to "Compact (smaller)", UiDensity.COZY to "Cozy", UiDensity.COMFORTABLE to "Comfortable (default)")
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
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Tile shape</span>
              <select id="beh-tile" class="input" style="width:180px;font-size:.85rem">$tileOptions</select>
            </label>
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
    val heroPct = cfg.heroHeightPct.coerceIn(40, 100)
    val channels = cfg.channels.filter { it.enabled }
    val rowTitles = previewRowTitles(cfg)
    host.innerHTML = buildString {
        append("""<div style="border-radius:10px;overflow:hidden;border:1px solid var(--line);background:#0a0c13;aspect-ratio:16/10;display:flex;flex-direction:column">""")
        append("""<div style="height:$heroPct%;background:linear-gradient(120deg,#7b6ef0,#3fb6f5);display:flex;align-items:flex-end;padding:8px"><span style="color:#fff;font-weight:700;font-size:.68rem;text-shadow:0 1px 4px rgba(0,0,0,.6)">${heroLabel.htmlEsc()}</span></div>""")
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
    // Rows — same overlay approach; CUSTOM rows' conditions/match are preserved.
    val rows = currentConfig.rows.mapIndexed { i, existing ->
        fun q(attr: String) = container.querySelector("[$attr='$i']")
        val titleEl = q("data-row-title") as? HTMLInputElement
        val title   = if (titleEl != null) titleEl.value.trim().ifEmpty { null } else existing.title
        val enabled = (q("data-row-enabled") as? HTMLInputElement)?.checked ?: existing.enabled
        if (existing.conditions.isNotEmpty()) {
            existing.copy(title = title, enabled = enabled, order = i)
        } else {
            val kind  = runCatching { RowKind.valueOf((q("data-row-kind") as? HTMLSelectElement)?.value ?: existing.kind.name) }.getOrDefault(existing.kind)
            val media = (q("data-row-media") as? HTMLSelectElement)?.value?.takeIf { it.isNotEmpty() }
            existing.copy(kind = kind, title = title, enabled = enabled, order = i, mediaKind = media)
        }
    }
    val mergeNewlyAdded = (container.querySelector("#merge-newly-added") as? HTMLInputElement)?.checked ?: false
    val heroHeight   = (container.querySelector("#hero-height") as? HTMLInputElement)?.value?.toIntOrNull() ?: 56
    val autoAdvance  = (container.querySelector("#auto-advance") as? HTMLSelectElement)?.value?.toIntOrNull() ?: 7
    val uiLanguage   = (container.querySelector("#beh-lang") as? HTMLSelectElement)?.value ?: "en"
    val defaultSkin  = runCatching { Skin.valueOf((container.querySelector("#beh-skin") as? HTMLSelectElement)?.value ?: "AURORA") }
        .getOrDefault(Skin.AURORA)
    val tileShape    = runCatching { TileShape.valueOf((container.querySelector("#beh-tile") as? HTMLSelectElement)?.value ?: "POSTER") }
        .getOrDefault(TileShape.POSTER)
    val uiDensity    = runCatching { UiDensity.valueOf((container.querySelector("#beh-density") as? HTMLSelectElement)?.value ?: "COMFORTABLE") }
        .getOrDefault(UiDensity.COMFORTABLE)
    val allowOverride = (container.querySelector("#beh-skin-override") as? HTMLInputElement)?.checked ?: true
    val showProgress  = (container.querySelector("#beh-progress") as? HTMLInputElement)?.checked ?: true
    // Discover (R50) — toggles/selects from the DOM; the ordered `lists` are managed structurally.
    val discover = currentConfig.discover.copy(
        enabled    = (container.querySelector("#top10-enable") as? HTMLInputElement)?.checked ?: currentConfig.discover.enabled,
        canRequest = (container.querySelector("#top10-canrequest") as? HTMLInputElement)?.checked ?: currentConfig.discover.canRequest,
        source     = (container.querySelector("#top10-source") as? HTMLSelectElement)?.value ?: currentConfig.discover.source,
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
