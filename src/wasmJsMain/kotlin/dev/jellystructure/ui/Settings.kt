package dev.jellystructure.ui

import dev.jellystructure.api.AppConfig
import dev.jellystructure.api.ApiKeys
import dev.jellystructure.api.LanguageRules
import dev.jellystructure.api.Behavior
import dev.jellystructure.api.LibraryMapping
import dev.jellystructure.api.JellyfinLibrary
import dev.jellystructure.api.ConfigApi
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

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
              <a href="#sect-connections" class="settings-nav-item" style="padding:5px 8px;border-radius:5px;font-size:.85rem;text-decoration:none;color:var(--ink-soft);">Connections</a>
              <a href="#sect-libraries" class="settings-nav-item" style="padding:5px 8px;border-radius:5px;font-size:.85rem;text-decoration:none;color:var(--ink-soft);">Library mapping</a>
              <a href="#sect-language" class="settings-nav-item" style="padding:5px 8px;border-radius:5px;font-size:.85rem;text-decoration:none;color:var(--ink-soft);">Language</a>
              <a href="#sect-behaviour" class="settings-nav-item" style="padding:5px 8px;border-radius:5px;font-size:.85rem;text-decoration:none;color:var(--ink-soft);">Behaviour</a>
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
                <label>Jellyfin machine token</label>
                <input id="jellyfin-token" class="input" type="text" style="width:100%">
                <span class="hint">Background jobs — not your login token</span>
              </div>
              <div class="field">
                <label>TMDB API key (v3)</label>
                <input id="tmdb-key" class="input" type="text" style="width:100%">
              </div>
              <div id="conn-result" style="display:none;margin-top:8px"></div>
            </div>

            <div class="card" id="sect-libraries">
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:14px">
                <h3 style="font-size:1rem;margin:0">Library mapping</h3>
                <button id="fetch-libraries" class="btn sm ghost">Fetch libraries</button>
              </div>
              <p class="hint" style="margin:0 0 12px">Assign a local path for each Jellyfin library, or mark it as skip.</p>
              <div id="library-mapping-list">
                <span class="muted tiny">Run "Test connections" or click "Fetch libraries" to load.</span>
              </div>
            </div>

            <div class="card" id="sect-language">
              <h3 style="font-size:1rem;margin:0 0 14px">Language</h3>
              <div class="field">
                <label>Fallback language</label>
                <input id="fallback-language" class="input" type="text" placeholder="en" style="width:100%">
                <span class="hint">BCP-47 code used when TMDB has no result in any of the file's track languages</span>
              </div>
            </div>

            <div class="card" id="sect-behaviour">
              <h3 style="font-size:1rem;margin:0 0 14px">Behaviour</h3>
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
                  <span style="font-size:.9rem">Watch library folders for new files</span>
                  <div class="hint" style="margin-top:2px">Polls every 30s; triggers a scan when stable new video files are detected</div>
                </div>
                <span id="watch-enabled-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
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
        val config = ConfigApi.get()
        if (config != null) populateForm(config)
        attachListeners(scope)
    }
}

private var overwriteNfo = false
private var fetchImages = true
private var watchEnabled = false
private var libraryMappings: MutableList<LibraryMapping> = mutableListOf()

private fun populateForm(config: AppConfig) {
    setInputValue("jellyfin-url", config.apiKeys.jellyfinUrl)
    setInputValue("jellyfin-token", config.apiKeys.jellyfinToken)
    setInputValue("tmdb-key", config.apiKeys.tmdbV3Key)
    setInputValue("fallback-language", config.languageRules.fallbackLanguage)

    overwriteNfo = config.behavior.overwriteNfo
    fetchImages = config.behavior.fetchImages
    watchEnabled = config.behavior.watchEnabled
    updateToggle("overwrite-nfo-toggle", overwriteNfo)
    updateToggle("fetch-images-toggle", fetchImages)
    updateToggle("watch-enabled-toggle", watchEnabled)

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

    listOf("jellyfin-url", "jellyfin-token", "tmdb-key", "fallback-language").forEach { id ->
        document.getElementById(id)?.addEventListener("input") {
            refreshTomlPreview(readForm())
        }
    }

    document.getElementById("save-settings")?.addEventListener("click") {
        scope.launch {
            val config = readForm()
            val ok = ConfigApi.save(config)
            showSettingsMsg(if (ok) "Saved." else "Save failed.", ok)
        }
    }

    document.getElementById("fetch-libraries")?.addEventListener("click") {
        scope.launch { fetchAndRenderLibraries() }
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
            if (result?.jellyfin == true) fetchAndRenderLibraries()
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
          <div style="display:flex;gap:6px;align-items:center">
            <span style="font-size:.75rem;color:var(--ink-soft);width:80px;flex-shrink:0">Fallback lang</span>
            <input id="lib-fallback-$i" class="input" type="text" placeholder="(global default)"
              value="${lib.fallbackLanguage ?: ""}" maxlength="10"
              style="width:110px;${if (skipped) "pointer-events:none" else ""}">
            <span style="font-size:.72rem;color:var(--ink-soft)">overrides global fallback for this library</span>
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
            refreshTomlPreview(readForm())
        }
        document.getElementById("lib-path-$i")?.addEventListener("input") {
            val value = (document.getElementById("lib-path-$i") as? HTMLInputElement)?.value?.trim() ?: ""
            libraryMappings[i] = libraryMappings[i].copy(localPath = value)
            refreshTomlPreview(readForm())
        }
        document.getElementById("lib-fallback-$i")?.addEventListener("input") {
            val value = (document.getElementById("lib-fallback-$i") as? HTMLInputElement)?.value?.trim() ?: ""
            libraryMappings[i] = libraryMappings[i].copy(fallbackLanguage = value.ifEmpty { null })
            refreshTomlPreview(readForm())
        }
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
