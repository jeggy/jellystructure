package dev.jellystructure.ui

import dev.jellystructure.api.AppConfig
import dev.jellystructure.api.ApiKeys
import dev.jellystructure.api.LanguageRules
import dev.jellystructure.api.Behavior
import dev.jellystructure.api.ConfigResponse
import dev.jellystructure.api.LibraryMapping
import dev.jellystructure.api.LibraryPathDiag
import dev.jellystructure.api.JellyfinLibrary
import dev.jellystructure.api.ConfigApi
import dev.jellystructure.api.httpClient
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import dev.jellystructure.observeSections
import dev.jellystructure.scrollIntoViewSmooth

fun renderSettings(container: Element, scope: CoroutineScope) {
    container.innerHTML = """
        <div class="pagebar">
          <h1>Settings</h1>
          <span class="spacer"></span>
          <button id="test-connections" class="btn sm ghost">Test connections</button>
          <button id="save-settings" class="btn primary">Save</button>
        </div>
        <p class="page-sub">Configuration is persisted to <code>config.toml</code> on the server.</p>
        <div id="settings-msg" style="display:none;margin-bottom:14px"></div>

        <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap">

          <nav style="width:160px;flex-shrink:0;position:sticky;top:16px;">
            <div style="display:flex;flex-direction:column;gap:2px;">
              <button data-sect="sect-connections" class="settings-nav-item" style="background:none;border:none;text-align:left;padding:5px 8px;border-radius:5px;font-size:.85rem;cursor:pointer;color:var(--ink-soft);">Connections</button>
              <button data-sect="sect-libraries" class="settings-nav-item" style="background:none;border:none;text-align:left;padding:5px 8px;border-radius:5px;font-size:.85rem;cursor:pointer;color:var(--ink-soft);">Library mapping</button>
              <button data-sect="sect-scanning" class="settings-nav-item" style="background:none;border:none;text-align:left;padding:5px 8px;border-radius:5px;font-size:.85rem;cursor:pointer;color:var(--ink-soft);">Scanning</button>
              <button data-sect="sect-metadata" class="settings-nav-item" style="background:none;border:none;text-align:left;padding:5px 8px;border-radius:5px;font-size:.85rem;cursor:pointer;color:var(--ink-soft);">Metadata</button>
              <button data-sect="sect-advanced" class="settings-nav-item" style="background:none;border:none;text-align:left;padding:5px 8px;border-radius:5px;font-size:.85rem;cursor:pointer;color:var(--ink-soft);">Advanced</button>
            </div>
          </nav>

          <div class="col fill" style="min-width:280px">

            <div class="card" id="sect-connections">
              <h3 style="font-size:1rem;margin:0 0 14px">Connections</h3>
              <div class="field">
                <label>Jellyfin URL</label>
                <input id="jellyfin-url" class="input" type="url" placeholder="http://localhost:8096" style="width:100%">
              </div>
              <div class="field">
                <label>Jellyfin machine token <span id="jf-token-badge" style="display:none;margin-left:8px"></span></label>
                <input id="jellyfin-token" class="input" type="password" style="width:100%">
                <span class="hint">Background jobs — not your login token</span>
              </div>
              <div class="field">
                <label>TMDB API key (v3) <span id="tmdb-key-badge" style="display:none;margin-left:8px"></span></label>
                <input id="tmdb-key" class="input" type="password" style="width:100%">
              </div>
              <div id="conn-result" style="display:none;margin-top:8px"></div>
              <div id="path-check-result" style="display:none;margin-top:8px"></div>
            </div>

            <div class="card" id="sect-libraries">
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:14px">
                <h3 style="font-size:1rem;margin:0">Library mapping</h3>
                <button id="fetch-libraries" class="btn sm ghost">Fetch libraries</button>
              </div>
              <p class="hint" style="margin:0 0 8px">Assign a local path for each Jellyfin library, or mark it as skip.</p>
              <details style="margin-bottom:12px;font-size:.82rem;color:var(--ink-soft)">
                <summary style="cursor:pointer;user-select:none;color:var(--ink-soft)">Path mapping help</summary>
                <p style="margin:6px 0 0;line-height:1.5">Jellyfin path is the path as Jellyfin sees the library root inside its container. Local path is the same location from Jellystructure's perspective. If both containers share an identical volume mount, these are the same value. If they differ, set both correctly — Jellyfin path is used to match scanned items, local path is used to access files.</p>
              </details>
              <div id="library-mapping-list">
                <span class="muted tiny">Run "Test connections" or click "Fetch libraries" to load.</span>
              </div>
            </div>

            <div class="card" id="sect-scanning">
              <h3 style="font-size:1rem;margin:0 0 14px">Scanning</h3>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                <div>
                  <span style="font-size:.9rem">Watch library folders for new files</span>
                  <div class="hint" style="margin-top:2px">Polls every 30s; triggers a scan when stable new video files are detected</div>
                </div>
                <span id="watch-enabled-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>
              <div class="field">
                <label>Scan workers</label>
                <input id="scan-workers" class="input" type="number" min="1" max="32" style="width:90px">
                <span class="hint">Number of items processed concurrently. Changing this during a scan takes effect immediately.</span>
              </div>
              <div class="field">
                <label>Scan thread pool size</label>
                <input id="scan-threads" class="input" type="number" min="1" max="32" style="width:90px">
                <span class="hint">Thread pool the workers run on. <strong>Requires an application restart.</strong></span>
              </div>
              <div id="scan-threads-restart-banner" style="display:none;margin-top:10px;padding:8px 12px;border-radius:6px;background:var(--warn-fill,#7c5100);color:var(--warn-ink,#fff);font-size:.83rem"></div>
            </div>

            <div class="card" id="sect-metadata">
              <h3 style="font-size:1rem;margin:0 0 14px">Metadata</h3>
              <div class="field" style="margin-bottom:14px">
                <label>Fallback language</label>
                <input id="fallback-language" class="input" type="text" placeholder="en" style="width:100%">
                <span class="hint">BCP-47 code used when TMDB has no result in any of the file's track languages</span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">Overwrite existing NFO fields</span>
                <span id="overwrite-nfo-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">Fetch artwork automatically</span>
                <span id="fetch-images-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between">
                <div>
                  <span style="font-size:.9rem">Auto-tell Jellyfin to refresh</span>
                  <div class="hint" style="margin-top:2px">After writing NFO or artwork, trigger Jellyfin metadata refresh automatically</div>
                </div>
                <span id="tell-jellyfin-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>
            </div>

            <div class="card" id="sect-advanced">
              <h3 style="font-size:1rem;margin:0 0 10px">Advanced</h3>
              <div style="border:1px solid var(--bad);border-radius:8px;padding:14px 16px">
                <div style="font-size:.9rem;font-weight:600;color:var(--bad);margin-bottom:4px">Danger zone</div>
                <p class="hint" style="margin:0 0 12px">Permanently deletes all scanned media data and resets scan state. Your media files and NFOs on disk are not touched. You will need to run a full scan afterwards.</p>
                <button id="clear-all-data-btn" class="btn sm" style="background:var(--bad);color:#fff;border-color:var(--bad)">Clear all scanned data</button>
                <span id="clear-all-msg" class="tiny muted" style="margin-left:10px;display:none"></span>
              </div>
            </div>

          </div>

          <div class="card" style="width:300px;flex-shrink:0">
            <h3 style="font-size:1rem;margin:0 0 10px">config.toml preview</h3>
            <pre class="log" id="toml-preview" style="max-height:480px;font-size:.73rem;line-height:1.5"></pre>
          </div>
        </div>
    """.trimIndent()

    scope.launch {
        val response = ConfigApi.get()
        if (response != null) populateForm(response)
        installLanguagePickerById("fallback-language")
        attachListeners(scope)
    }

    wireSettingsNav(container)
}

private var overwriteNfo = false
private var fetchImages = true
private var watchEnabled = false
private var tellJellyfin = true
private var scanWorkers = 1
private var scanThreads = 4
private var effectiveScanThreads = 4
private var libraryMappings: MutableList<LibraryMapping> = mutableListOf()

private fun populateForm(response: ConfigResponse) {
    val config = response.config
    effectiveScanThreads = response.effectiveScanThreads

    setInputValue("jellyfin-url", config.apiKeys.jellyfinUrl)
    setInputValue("jellyfin-token", config.apiKeys.jellyfinToken)
    setInputValue("tmdb-key", config.apiKeys.tmdbV3Key)
    setInputValue("fallback-language", config.languageRules.fallbackLanguage)

    overwriteNfo = config.behavior.overwriteNfo
    fetchImages = config.behavior.fetchImages
    watchEnabled = config.behavior.watchEnabled
    tellJellyfin = config.behavior.tellJellyfin
    scanWorkers = config.behavior.scanWorkers
    scanThreads = config.behavior.scanThreads
    updateToggle("overwrite-nfo-toggle", overwriteNfo)
    updateToggle("fetch-images-toggle", fetchImages)
    updateToggle("watch-enabled-toggle", watchEnabled)
    updateToggle("tell-jellyfin-toggle", tellJellyfin)
    setInputValue("scan-workers", scanWorkers.toString())
    setInputValue("scan-threads", scanThreads.toString())
    updateRestartBanner()

    libraryMappings = config.libraries.toMutableList()
    if (libraryMappings.isNotEmpty()) renderLibraryList()

    refreshTomlPreview(config)
}

private fun attachListeners(scope: CoroutineScope) {
    document.getElementById("overwrite-nfo-toggle")?.addEventListener("click") {
        overwriteNfo = !overwriteNfo
        updateToggle("overwrite-nfo-toggle", overwriteNfo)
        refreshTomlPreview(readForm())
    }
    document.getElementById("fetch-images-toggle")?.addEventListener("click") {
        fetchImages = !fetchImages
        updateToggle("fetch-images-toggle", fetchImages)
        refreshTomlPreview(readForm())
    }
    document.getElementById("watch-enabled-toggle")?.addEventListener("click") {
        watchEnabled = !watchEnabled
        updateToggle("watch-enabled-toggle", watchEnabled)
        refreshTomlPreview(readForm())
    }
    document.getElementById("tell-jellyfin-toggle")?.addEventListener("click") {
        tellJellyfin = !tellJellyfin
        updateToggle("tell-jellyfin-toggle", tellJellyfin)
        refreshTomlPreview(readForm())
    }

    listOf("jellyfin-url", "jellyfin-token", "tmdb-key", "fallback-language").forEach { id ->
        document.getElementById(id)?.addEventListener("input") {
            refreshTomlPreview(readForm())
        }
    }

    document.getElementById("scan-workers")?.addEventListener("input") {
        scanWorkers = (document.getElementById("scan-workers") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceIn(1, 32) ?: 1
        refreshTomlPreview(readForm())
    }
    document.getElementById("scan-threads")?.addEventListener("input") {
        scanThreads = (document.getElementById("scan-threads") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceIn(1, 32) ?: 4
        updateRestartBanner()
        refreshTomlPreview(readForm())
    }

    document.getElementById("save-settings")?.addEventListener("click") {
        scope.launch {
            val config = readForm()
            val ok = ConfigApi.save(config)
            showSettingsMsg(if (ok) "Saved." else "Save failed.", ok)
            if (ok) renderPathCheckResult(ConfigApi.pathCheck())
        }
    }

    document.getElementById("fetch-libraries")?.addEventListener("click") {
        scope.launch { fetchAndRenderLibraries() }
    }

    document.getElementById("clear-all-data-btn")?.addEventListener("click") {
        if (window.confirm("This will permanently delete all scanned media data and reset scan state. Your files on disk are not touched. Continue?")) {
            scope.launch {
                val msgEl = document.getElementById("clear-all-msg") as? HTMLElement
                val btn = document.getElementById("clear-all-data-btn") as? HTMLElement
                btn?.setAttribute("disabled", "")
                msgEl?.let { it.style.display = "inline"; it.textContent = "Clearing…" }
                val resp = runCatching { httpClient.delete("/api/media/all") }.getOrNull()
                val ok = resp?.status == HttpStatusCode.NoContent
                msgEl?.textContent = if (ok) "Done — run a scan to repopulate." else "Failed — check server logs."
                btn?.removeAttribute("disabled")
            }
        }
    }

    document.getElementById("test-connections")?.addEventListener("click") {
        scope.launch {
            val result = ConfigApi.testConnections()
            val el = document.getElementById("conn-result") as? HTMLElement ?: return@launch
            el.style.display = "block"
            el.innerHTML = buildString {
                append("""<span class="badge ${if (result?.jellyfin == true) "ok" else "bad"}" style="margin-right:6px">""")
                append(if (result?.jellyfin == true) "Jellyfin ✓" else "Jellyfin ✗")
                append("</span>")
                append("""<span class="badge ${if (result?.tmdb == true) "ok" else "bad"}">""")
                append(if (result?.tmdb == true) "TMDB ✓" else "TMDB ✗")
                append("</span>")
            }
            // Update per-field inline badges
            val jfBadge = document.getElementById("jf-token-badge") as? HTMLElement
            if (jfBadge != null) {
                jfBadge.style.display = "inline"
                jfBadge.innerHTML = if (result?.jellyfin == true) """<span class="badge ok" style="font-size:.72rem">valid ✓</span>""" else """<span class="badge bad" style="font-size:.72rem">invalid ✗</span>"""
            }
            val tmdbBadge = document.getElementById("tmdb-key-badge") as? HTMLElement
            if (tmdbBadge != null) {
                tmdbBadge.style.display = "inline"
                tmdbBadge.innerHTML = if (result?.tmdb == true) """<span class="badge ok" style="font-size:.72rem">valid ✓</span>""" else """<span class="badge bad" style="font-size:.72rem">invalid ✗</span>"""
            }
            if (result?.jellyfin == true) {
                fetchAndRenderLibraries()
                renderPathCheckResult(ConfigApi.pathCheck())
            }
        }
    }
}

private suspend fun fetchAndRenderLibraries() {
    val listEl = document.getElementById("library-mapping-list") as? HTMLElement ?: return
    listEl.innerHTML = """<span class="muted tiny">Loading…</span>"""

    val fetched = ConfigApi.getJellyfinLibraries()
    if (fetched == null) {
        listEl.innerHTML = """<span class="badge bad">Failed to fetch libraries. Check URL and token.</span>"""
        return
    }

    // Merge: keep existing jellyfinPath/localPath/skip for known IDs, add new entries for unknowns
    val existing = libraryMappings.associateBy { it.jellyfinId }
    libraryMappings = fetched.map { lib ->
        existing[lib.id] ?: LibraryMapping(
            jellyfinId = lib.id,
            name = lib.name,
            collectionType = lib.collectionType ?: "",
            jellyfinPath = lib.locations.firstOrNull() ?: "",
            localPath = lib.locations.firstOrNull() ?: "",
            skip = false,
        )
    }.toMutableList()

    renderLibraryList()
    refreshTomlPreview(readForm())
}

private fun renderLibraryList() {
    val listEl = document.getElementById("library-mapping-list") as? HTMLElement ?: return
    if (libraryMappings.isEmpty()) {
        listEl.innerHTML = """<span class="muted tiny">No libraries found.</span>"""
        return
    }

    listEl.innerHTML = libraryMappings.mapIndexed { i, lib ->
        val skipped = lib.skip
        val typeLabel = lib.collectionType.ifEmpty { "?" }
        """
        <div style="border:1.5px solid var(--border);border-radius:6px;padding:10px 12px;margin-bottom:10px;${if (skipped) "opacity:.5" else ""}">
          <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
            <strong style="font-size:.9rem;flex:1">${lib.name}</strong>
            <span class="badge" style="font-size:.72rem;padding:1px 7px;background:var(--fill-2)">$typeLabel</span>
            <label style="display:flex;align-items:center;gap:5px;font-size:.82rem;cursor:pointer">
              <input type="checkbox" id="lib-skip-$i" ${if (skipped) "checked" else ""}>
              Skip
            </label>
          </div>
          <div style="display:flex;gap:6px;align-items:center;margin-bottom:6px">
            <span style="font-size:.75rem;color:var(--ink-soft);width:80px;flex-shrink:0">Jellyfin path</span>
            <input id="lib-jellyfin-path-$i" class="input" type="text" placeholder="/media/movies/"
              value="${lib.jellyfinPath}" style="flex:1;${if (skipped) "pointer-events:none" else ""}">
          </div>
          <div style="display:flex;gap:6px;align-items:center;margin-bottom:6px">
            <span style="font-size:.75rem;color:var(--ink-soft);width:80px;flex-shrink:0">Local path</span>
            <input id="lib-path-$i" class="input" type="text" placeholder="/mnt/host/movies/"
              value="${lib.localPath}" style="flex:1;${if (skipped) "pointer-events:none" else ""}">
          </div>
          <div style="display:flex;gap:6px;align-items:center;margin-bottom:6px">
            <span style="font-size:.75rem;color:var(--ink-soft);width:80px;flex-shrink:0">Fallback lang</span>
            <input id="lib-fallback-$i" class="input" type="text" placeholder="(global default)"
              value="${lib.fallbackLanguage ?: ""}" maxlength="10"
              style="width:110px;${if (skipped) "pointer-events:none" else ""}">
            <span style="font-size:.72rem;color:var(--ink-soft)">overrides global fallback for this library</span>
          </div>
          <div style="display:flex;gap:6px;align-items:center;margin-top:2px">
            <span style="font-size:.75rem;color:var(--ink-soft);width:80px;flex-shrink:0">Match prefix</span>
            <code id="match-prefix-$i" style="font-size:.72rem;color:var(--ink-soft)">${lib.jellyfinPath.ifBlank { lib.localPath }}</code>
          </div>
        </div>
        """.trimIndent()
    }.joinToString("")

    // Attach change listeners after DOM is built
    libraryMappings.forEachIndexed { i, _ ->
        document.getElementById("lib-skip-$i")?.addEventListener("change") {
            val checked = (document.getElementById("lib-skip-$i") as? HTMLInputElement)?.checked ?: false
            val current = libraryMappings[i]
            libraryMappings[i] = current.copy(skip = checked)
            renderLibraryList()
            refreshTomlPreview(readForm())
        }
        document.getElementById("lib-jellyfin-path-$i")?.addEventListener("input") {
            val value = (document.getElementById("lib-jellyfin-path-$i") as? HTMLInputElement)?.value?.trim() ?: ""
            libraryMappings[i] = libraryMappings[i].copy(jellyfinPath = value)
            document.getElementById("match-prefix-$i")?.textContent = value.ifBlank { libraryMappings[i].localPath }
            refreshTomlPreview(readForm())
        }
        document.getElementById("lib-path-$i")?.addEventListener("input") {
            val value = (document.getElementById("lib-path-$i") as? HTMLInputElement)?.value?.trim() ?: ""
            libraryMappings[i] = libraryMappings[i].copy(localPath = value)
            if (libraryMappings[i].jellyfinPath.isBlank()) {
                document.getElementById("match-prefix-$i")?.textContent = value
            }
            refreshTomlPreview(readForm())
        }
        document.getElementById("lib-fallback-$i")?.addEventListener("input") {
            val value = (document.getElementById("lib-fallback-$i") as? HTMLInputElement)?.value?.trim() ?: ""
            libraryMappings[i] = libraryMappings[i].copy(fallbackLanguage = value.ifEmpty { null })
            refreshTomlPreview(readForm())
        }
        installLanguagePickerById("lib-fallback-$i")
    }
}

private fun readForm(): AppConfig = AppConfig(
    apiKeys = ApiKeys(
        jellyfinUrl = getInputValue("jellyfin-url"),
        jellyfinToken = getInputValue("jellyfin-token"),
        tmdbV3Key = getInputValue("tmdb-key"),
    ),
    languageRules = LanguageRules(
        fallbackLanguage = getInputValue("fallback-language").ifEmpty { "en" },
    ),
    behavior = Behavior(
        overwriteNfo = overwriteNfo,
        fetchImages = fetchImages,
        watchEnabled = watchEnabled,
        tellJellyfin = tellJellyfin,
        scanWorkers = scanWorkers,
        scanThreads = scanThreads,
    ),
    libraries = libraryMappings.toList(),
)

private fun refreshTomlPreview(config: AppConfig) {
    val el = document.getElementById("toml-preview") ?: return
    el.textContent = buildToml(config)
}

private fun buildToml(c: AppConfig): String = buildString {
    appendLine("[api_keys]")
    appendLine("""jellyfin_url = "${c.apiKeys.jellyfinUrl}"""")
    appendLine("""jellyfin_token = "${c.apiKeys.jellyfinToken}"""")
    appendLine("""tmdb_v3_key = "${c.apiKeys.tmdbV3Key}"""")
    appendLine()
    appendLine("[language_rules]")
    appendLine("""fallback_language = "${c.languageRules.fallbackLanguage}"""")
    appendLine()
    appendLine("[behavior]")
    appendLine("overwrite_nfo = ${c.behavior.overwriteNfo}")
    appendLine("fetch_images = ${c.behavior.fetchImages}")
    appendLine("watch_enabled = ${c.behavior.watchEnabled}")
    appendLine("tell_jellyfin = ${c.behavior.tellJellyfin}")
    appendLine("scan_workers = ${c.behavior.scanWorkers}")
    appendLine("scan_threads = ${c.behavior.scanThreads}")
    for (lib in c.libraries) {
        appendLine()
        appendLine("[[libraries]]")
        appendLine("""jellyfin_id = "${lib.jellyfinId}"""")
        appendLine("""name = "${lib.name}"""")
        appendLine("""collection_type = "${lib.collectionType}"""")
        if (lib.jellyfinPath.isNotBlank()) appendLine("""jellyfin_path = "${lib.jellyfinPath}"""")
        appendLine("""local_path = "${lib.localPath}"""")
        appendLine("skip = ${lib.skip}")
        if (!lib.fallbackLanguage.isNullOrBlank()) appendLine("""fallback_language = "${lib.fallbackLanguage}"""")
    }
}

private fun showSettingsMsg(msg: String, ok: Boolean) {
    val el = document.getElementById("settings-msg") as? HTMLElement ?: return
    el.style.display = "block"
    el.innerHTML = """<span class="badge ${if (ok) "ok" else "bad"}">$msg</span>"""
}

private fun updateToggle(id: String, on: Boolean) {
    val el = document.getElementById(id) as? HTMLElement ?: return
    if (on) el.className = "toggle on" else el.className = "toggle"
}

private fun setInputValue(id: String, value: String) {
    (document.getElementById(id) as? HTMLInputElement)?.value = value
}

private fun getInputValue(id: String): String =
    (document.getElementById(id) as? HTMLInputElement)?.value?.trim() ?: ""

private fun updateRestartBanner() {
    val banner = document.getElementById("scan-threads-restart-banner") as? HTMLElement ?: return
    if (scanThreads != effectiveScanThreads) {
        banner.style.display = "block"
        banner.innerHTML = "⚠ Thread pool size has changed — restart the application for this to take effect.<br>" +
            "Current: $effectiveScanThreads thread${if (effectiveScanThreads != 1) "s" else ""} · " +
            "Pending: $scanThreads thread${if (scanThreads != 1) "s" else ""}"
    } else {
        banner.style.display = "none"
    }
}

private fun wireSettingsNav(container: Element) {
    val navBtns = container.querySelectorAll(".settings-nav-item[data-sect]")
    for (i in 0 until navBtns.length) {
        val btn = navBtns.item(i) as? HTMLElement ?: continue
        btn.addEventListener("click") { _ ->
            val sectId = btn.getAttribute("data-sect") ?: return@addEventListener
            val target = document.getElementById(sectId) ?: return@addEventListener
            scrollIntoViewSmooth(target)
        }
    }

    // Highlight the nav item whose section is visible at the top of the viewport
    val sections = "sect-connections,sect-libraries,sect-scanning,sect-metadata,sect-advanced"
    observeSections(sections, "-10% 0px -80% 0px") { visibleId ->
        for (k in 0 until navBtns.length) {
            val b = navBtns.item(k) as? HTMLElement ?: continue
            val active = b.getAttribute("data-sect") == visibleId
            b.style.color = if (active) "var(--ink)" else "var(--ink-soft)"
            b.style.fontWeight = if (active) "600" else ""
        }
    }
}

private fun renderPathCheckResult(diags: List<LibraryPathDiag>?) {
    val el = document.getElementById("path-check-result") as? HTMLElement ?: return
    if (diags == null) { el.style.display = "none"; return }
    el.style.display = "block"
    el.innerHTML = buildString {
        append("""<div style="font-size:.82rem;font-weight:600;margin-bottom:6px">Library path check</div>""")
        if (diags.isEmpty()) {
            append("""<span class="hint">No libraries configured.</span>""")
            return@buildString
        }
        for (d in diags) {
            append("""<div style="display:flex;align-items:flex-start;gap:8px;margin-bottom:6px;flex-wrap:wrap">""")
            append("""<span style="font-size:.82rem;min-width:80px;flex-shrink:0">${d.name}</span>""")
            append("""<code style="font-size:.72rem;color:var(--ink-soft);word-break:break-all">${d.matchPrefix.ifEmpty { "(none)" }}</code>""")
            if (d.localExists) {
                append("""<span class="badge ok" style="font-size:.72rem">Local path found ✓</span>""")
            } else {
                append("""<span class="badge bad" style="font-size:.72rem">Local path not found ✗</span>""")
                append("""<span class="hint" style="align-self:center">Check that localPath is mounted correctly in the Jellystructure container</span>""")
            }
            if (d.jellyfinPath.isBlank()) {
                append("""<span class="badge warn" style="font-size:.72rem">jellyfinPath not set — using localPath as match prefix</span>""")
            }
            append("</div>")
        }
    }
}
