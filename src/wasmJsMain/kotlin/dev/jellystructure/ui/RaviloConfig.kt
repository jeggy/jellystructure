package dev.jellystructure.ui

import dev.jellystructure.api.AdminChannelConfig
import dev.jellystructure.api.AdminHeroConfig
import dev.jellystructure.api.AdminRaviloConfig
import dev.jellystructure.api.AdminRowConfig
import dev.jellystructure.api.JellyfinUser
import dev.jellystructure.api.RaviloApi
import dev.jellystructure.scrollIntoViewSmooth
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

private var currentUserId: String = ""
private var currentConfig: AdminRaviloConfig = AdminRaviloConfig()
private var users: List<JellyfinUser> = emptyList()

fun renderRaviloConfig(container: Element, scope: CoroutineScope) {
    container.innerHTML = buildLoadingShell()
    scope.launch {
        users = runCatching { RaviloApi.getUsers() }.getOrDefault(emptyList())
        if (users.isEmpty()) {
            container.innerHTML = buildErrorShell("Could not load Jellyfin users — check your connection in Settings.")
            return@launch
        }
        if (currentUserId.isEmpty()) currentUserId = users.first().id
        currentConfig = runCatching { RaviloApi.getConfig(currentUserId) }.getOrDefault(AdminRaviloConfig())
        renderFull(container, scope)
    }
}

private fun renderFull(container: Element, scope: CoroutineScope) {
    container.innerHTML = buildShell()
    wireShell(container, scope)
    renderSections(container)
}

private fun buildLoadingShell() = """
    <div class="pagebar"><h1>Ravilo TV</h1></div>
    <p class="page-sub" style="color:var(--ink-soft)">Loading…</p>
""".trimIndent()

private fun buildErrorShell(msg: String) = """
    <div class="pagebar"><h1>Ravilo TV</h1></div>
    <div class="card" style="padding:24px;color:var(--bad)">$msg</div>
""".trimIndent()

private fun buildShell(): String {
    val userOptions = users.joinToString("") { u ->
        val sel = if (u.id == currentUserId) " selected" else ""
        """<option value="${u.id}"$sel>${u.displayName.htmlEsc()}</option>"""
    }
    return """
    <div class="pagebar">
      <h1>Ravilo TV</h1>
      <span class="spacer"></span>
      <button id="rav-save" class="btn primary">Save</button>
    </div>
    <p class="page-sub">
      Layout stored per Jellyfin user · synced to all their paired TV devices.
    </p>
    <div class="card" style="padding:14px 18px;margin-bottom:18px;display:flex;align-items:center;gap:12px">
      <span style="font-size:.85rem;color:var(--ink-soft)">Editing config for:</span>
      <select id="rav-user-pick" class="input" style="width:220px;font-size:.85rem">$userOptions</select>
    </div>
    <div id="rav-msg" style="display:none;margin-bottom:12px"></div>

    <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap">
      <nav style="width:150px;flex-shrink:0;position:sticky;top:88px">
        <div style="display:flex;flex-direction:column;gap:2px">
          <button data-rav-sect="sect-heroes"   class="rav-nav-item">Heroes</button>
          <button data-rav-sect="sect-channels" class="rav-nav-item">Channels</button>
          <button data-rav-sect="sect-rows"     class="rav-nav-item">Rows</button>
          <button data-rav-sect="sect-behaviour"class="rav-nav-item">Behaviour</button>
        </div>
      </nav>
      <div class="col fill" style="min-width:280px" id="rav-sections">
        <div id="sect-heroes"></div>
        <div id="sect-channels"></div>
        <div id="sect-rows"></div>
        <div id="sect-behaviour"></div>
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
            currentConfig = runCatching { RaviloApi.getConfig(currentUserId) }.getOrDefault(AdminRaviloConfig())
            renderSections(container)
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
}

private fun renderSections(container: Element) {
    renderHeroes(container)
    renderChannels(container)
    renderRows(container)
    renderBehaviour(container)
}

// ── Heroes ────────────────────────────────────────────────────────────────────

private fun renderHeroes(container: Element) {
    val sect = container.querySelector("#sect-heroes") ?: return
    val rows = currentConfig.heroes.mapIndexed { i, h ->
        """
        <div class="row" style="gap:8px;margin-bottom:8px" data-hero="$i">
          <input class="input fill" placeholder="Jellyfin item ID" value="${h.itemId.htmlEsc()}" data-hero-id="$i">
          <input class="input fill" placeholder="Title (display)" value="${h.itemTitle.htmlEsc()}" data-hero-title="$i">
          <button class="btn sm ghost" data-hero-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Heroes</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            Pinned items shown in the hero carousel at the top of the home screen.
          </p>
          $rows
          <button id="hero-add" class="btn sm ghost" style="margin-top:6px">+ Add hero</button>
        </div>
    """.trimIndent()
    sect.querySelector("#hero-add")?.addEventListener("click") { _ ->
        currentConfig = currentConfig.copy(heroes = currentConfig.heroes + AdminHeroConfig())
        renderHeroes(container)
    }
    for (i in currentConfig.heroes.indices) {
        sect.querySelector("[data-hero-del='$i']")?.addEventListener("click") { _ ->
            val list = currentConfig.heroes.toMutableList(); list.removeAt(i)
            currentConfig = currentConfig.copy(heroes = list); renderHeroes(container)
        }
    }
}

// ── Channels ──────────────────────────────────────────────────────────────────

private val CHANNEL_KINDS = listOf("GENRE", "STUDIO", "NETWORK", "TAG")

private fun renderChannels(container: Element) {
    val sect = container.querySelector("#sect-channels") ?: return
    val rows = currentConfig.channels.mapIndexed { i, c ->
        val kindOptions = CHANNEL_KINDS.joinToString("") { k ->
            val sel = if (k == c.kind) " selected" else ""
            """<option value="$k"$sel>${k.lowercase().replaceFirstChar { it.uppercase() }}</option>"""
        }
        """
        <div class="row" style="gap:8px;margin-bottom:8px;flex-wrap:wrap">
          <input class="input" style="width:140px" placeholder="Label" value="${c.label.htmlEsc()}" data-ch-label="$i">
          <select class="input" style="width:110px" data-ch-kind="$i">$kindOptions</select>
          <input class="input fill" placeholder="Filter value (e.g. Action)" value="${c.filter.htmlEsc()}" data-ch-filter="$i">
          <input class="input" style="width:100px" placeholder="#color" value="${(c.color ?: "").htmlEsc()}" data-ch-color="$i">
          <button class="btn sm ghost" data-ch-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Channel buttons</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            Quick-jump buttons shown in the second rail. Each maps to a content filter (genre, studio, network, or tag).
          </p>
          $rows
          <button id="ch-add" class="btn sm ghost" style="margin-top:6px">+ Add channel</button>
        </div>
    """.trimIndent()
    sect.querySelector("#ch-add")?.addEventListener("click") { _ ->
        currentConfig = currentConfig.copy(channels = currentConfig.channels + AdminChannelConfig())
        renderChannels(container)
    }
    for (i in currentConfig.channels.indices) {
        sect.querySelector("[data-ch-del='$i']")?.addEventListener("click") { _ ->
            val list = currentConfig.channels.toMutableList(); list.removeAt(i)
            currentConfig = currentConfig.copy(channels = list); renderChannels(container)
        }
    }
}

// ── Rows ──────────────────────────────────────────────────────────────────────

private val ROW_KINDS = listOf("GENRE", "STUDIO", "NETWORK", "TAG", "CONTINUE_WATCHING", "NEXT_UP", "NEWLY_ADDED")

private fun renderRows(container: Element) {
    val sect = container.querySelector("#sect-rows") ?: return
    val mergeChecked = if (currentConfig.mergeNewlyAdded) " checked" else ""
    val rows = currentConfig.rows.mapIndexed { i, r ->
        val kindOptions = ROW_KINDS.joinToString("") { k ->
            val sel = if (k == r.kind) " selected" else ""
            """<option value="$k"$sel>${k.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }}</option>"""
        }
        val hidCheck = if (r.hidden) " checked" else ""
        """
        <div class="row" style="gap:8px;margin-bottom:8px;flex-wrap:wrap">
          <input class="input" style="width:140px" placeholder="Label" value="${r.label.htmlEsc()}" data-row-label="$i">
          <select class="input" style="width:160px" data-row-kind="$i">$kindOptions</select>
          <input class="input fill" placeholder="Filter value" value="${r.filter.htmlEsc()}" data-row-filter="$i">
          <label style="display:flex;align-items:center;gap:6px;font-size:.85rem;white-space:nowrap">
            <input type="checkbox" data-row-hidden="$i"$hidCheck> Hidden
          </label>
          <button class="btn sm ghost" data-row-del="$i">✕</button>
        </div>
        """.trimIndent()
    }.joinToString("")
    sect.innerHTML = """
        <div class="card" style="padding:18px 20px;margin-bottom:18px">
          <div style="font-weight:600;margin-bottom:10px">Content rows</div>
          <p style="font-size:.82rem;color:var(--ink-soft);margin-bottom:14px">
            Horizontal scroll rows on the home screen.
          </p>
          $rows
          <button id="row-add" class="btn sm ghost" style="margin-top:6px">+ Add row</button>
          <div style="margin-top:16px;padding-top:14px;border-top:1px solid var(--sep)">
            <label style="display:flex;align-items:center;gap:8px;font-size:.9rem">
              <input type="checkbox" id="merge-newly-added"$mergeChecked>
              Merge <em>Continue Watching</em> + <em>Next Up</em> + <em>Newly Added</em> into one combined row
            </label>
          </div>
        </div>
    """.trimIndent()
    sect.querySelector("#row-add")?.addEventListener("click") { _ ->
        currentConfig = currentConfig.copy(rows = currentConfig.rows + AdminRowConfig())
        renderRows(container)
    }
    for (i in currentConfig.rows.indices) {
        sect.querySelector("[data-row-del='$i']")?.addEventListener("click") { _ ->
            val list = currentConfig.rows.toMutableList(); list.removeAt(i)
            currentConfig = currentConfig.copy(rows = list); renderRows(container)
        }
    }
}

// ── Behaviour ─────────────────────────────────────────────────────────────────

private val SKINS = listOf("AURORA", "MIDNIGHT", "NOIR")
private val TILE_SHAPES = listOf("POSTER", "THUMB", "SQUARE")
private val LANGS = listOf("en" to "English", "da" to "Dansk", "fo" to "Føroyskt")

private fun renderBehaviour(container: Element) {
    val sect = container.querySelector("#sect-behaviour") ?: return
    val skinOptions = SKINS.joinToString("") { s ->
        val sel = if (s == currentConfig.defaultSkin) " selected" else ""
        """<option value="$s"$sel>${s.lowercase().replaceFirstChar { it.uppercase() }}</option>"""
    }
    val tileOptions = TILE_SHAPES.joinToString("") { s ->
        val sel = if (s == currentConfig.tileShape) " selected" else ""
        """<option value="$s"$sel>${s.lowercase().replaceFirstChar { it.uppercase() }}</option>"""
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
              <select id="beh-lang" class="input" style="width:140px;font-size:.85rem">$langOptions</select>
            </label>
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Default skin</span>
              <select id="beh-skin" class="input" style="width:140px;font-size:.85rem">$skinOptions</select>
            </label>
            <label style="display:flex;align-items:center;justify-content:space-between;gap:12px">
              <span style="font-size:.9rem">Tile shape</span>
              <select id="beh-tile" class="input" style="width:140px;font-size:.85rem">$tileOptions</select>
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

// ── Collect current form state ────────────────────────────────────────────────

private fun collectConfig(container: Element) {
    // Heroes
    val heroIds = container.querySelectorAll("[data-hero-id]")
    val heroTitles = container.querySelectorAll("[data-hero-title]")
    val heroes = (0 until heroIds.length).map { i ->
        AdminHeroConfig(
            itemId = (heroIds.item(i) as? HTMLInputElement)?.value?.trim() ?: "",
            itemTitle = (heroTitles.item(i) as? HTMLInputElement)?.value?.trim() ?: "",
        )
    }
    // Channels
    val chLabels  = container.querySelectorAll("[data-ch-label]")
    val chKinds   = container.querySelectorAll("[data-ch-kind]")
    val chFilters = container.querySelectorAll("[data-ch-filter]")
    val chColors  = container.querySelectorAll("[data-ch-color]")
    val channels  = (0 until chLabels.length).map { i ->
        val color = (chColors.item(i) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
        AdminChannelConfig(
            label  = (chLabels.item(i)  as? HTMLInputElement)?.value?.trim() ?: "",
            kind   = (chKinds.item(i)   as? HTMLSelectElement)?.value ?: "GENRE",
            filter = (chFilters.item(i) as? HTMLInputElement)?.value?.trim() ?: "",
            color  = color,
        )
    }
    // Rows
    val rowLabels  = container.querySelectorAll("[data-row-label]")
    val rowKinds   = container.querySelectorAll("[data-row-kind]")
    val rowFilters = container.querySelectorAll("[data-row-filter]")
    val rowHiddens = container.querySelectorAll("[data-row-hidden]")
    val rows = (0 until rowLabels.length).map { i ->
        AdminRowConfig(
            label  = (rowLabels.item(i)  as? HTMLInputElement)?.value?.trim() ?: "",
            kind   = (rowKinds.item(i)   as? HTMLSelectElement)?.value ?: "GENRE",
            filter = (rowFilters.item(i) as? HTMLInputElement)?.value?.trim() ?: "",
            hidden = (rowHiddens.item(i) as? HTMLInputElement)?.checked ?: false,
        )
    }
    val mergeNewlyAdded = (container.querySelector("#merge-newly-added") as? HTMLInputElement)?.checked ?: false
    val uiLanguage   = (container.querySelector("#beh-lang")          as? HTMLSelectElement)?.value ?: "en"
    val defaultSkin  = (container.querySelector("#beh-skin")          as? HTMLSelectElement)?.value ?: "AURORA"
    val tileShape    = (container.querySelector("#beh-tile")          as? HTMLSelectElement)?.value ?: "POSTER"
    val allowOverride= (container.querySelector("#beh-skin-override") as? HTMLInputElement)?.checked ?: true
    val showProgress = (container.querySelector("#beh-progress")      as? HTMLInputElement)?.checked ?: true
    currentConfig = AdminRaviloConfig(
        heroes = heroes,
        channels = channels,
        rows = rows,
        mergeNewlyAdded = mergeNewlyAdded,
        uiLanguage = uiLanguage,
        defaultSkin = defaultSkin,
        tileShape = tileShape,
        allowSkinOverride = allowOverride,
        showContinueProgress = showProgress,
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
