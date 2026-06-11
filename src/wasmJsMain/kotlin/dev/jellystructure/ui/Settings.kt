package dev.jellystructure.ui

import dev.jellystructure.api.AppConfig
import dev.jellystructure.api.ApiKeys
import dev.jellystructure.api.Paths
import dev.jellystructure.api.LanguageRules
import dev.jellystructure.api.Behavior
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
          <div class="col fill" style="min-width:280px">

            <div class="card">
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

            <div class="card">
              <h3 style="font-size:1rem;margin:0 0 14px">Media paths</h3>
              <div class="field">
                <label>Movies directory</label>
                <input id="movies-dir" class="input" type="text" placeholder="/media/movies" style="width:100%">
              </div>
              <div class="field">
                <label>TV directory</label>
                <input id="tv-dir" class="input" type="text" placeholder="/media/tv" style="width:100%">
              </div>
            </div>

            <div class="card">
              <h3 style="font-size:1rem;margin:0 0 14px">Language cascade defaults</h3>
              <div class="field">
                <label>Audio cascade</label>
                <input id="audio-cascade" class="input" type="text" placeholder="en, da, fo" style="width:100%">
                <span class="hint">Comma-separated BCP-47 codes, highest priority first</span>
              </div>
              <div class="field">
                <label>Subtitle cascade</label>
                <input id="sub-cascade" class="input" type="text" placeholder="en, da" style="width:100%">
              </div>
            </div>

            <div class="card">
              <h3 style="font-size:1rem;margin:0 0 14px">Behaviour</h3>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">Overwrite existing NFO fields</span>
                <span id="overwrite-nfo-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between">
                <span style="font-size:.9rem">Fetch artwork automatically</span>
                <span id="fetch-images-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
            </div>

          </div>

          <div class="card" style="width:340px;flex-shrink:0">
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

private fun populateForm(config: AppConfig) {
    setInputValue("jellyfin-url", config.apiKeys.jellyfinUrl)
    setInputValue("jellyfin-token", config.apiKeys.jellyfinToken)
    setInputValue("tmdb-key", config.apiKeys.tmdbV3Key)
    setInputValue("movies-dir", config.paths.moviesDir)
    setInputValue("tv-dir", config.paths.tvDir)
    setInputValue("audio-cascade", config.languageRules.audioCascade.joinToString(", "))
    setInputValue("sub-cascade", config.languageRules.subCascade.joinToString(", "))

    overwriteNfo = config.behavior.overwriteNfo
    fetchImages = config.behavior.fetchImages
    updateToggle("overwrite-nfo-toggle", overwriteNfo)
    updateToggle("fetch-images-toggle", fetchImages)

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

    listOf("jellyfin-url", "jellyfin-token", "tmdb-key", "movies-dir", "tv-dir", "audio-cascade", "sub-cascade")
        .forEach { id ->
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
        }
    }
}

private fun readForm(): AppConfig = AppConfig(
    apiKeys = ApiKeys(
        jellyfinUrl = getInputValue("jellyfin-url"),
        jellyfinToken = getInputValue("jellyfin-token"),
        tmdbV3Key = getInputValue("tmdb-key"),
    ),
    paths = Paths(
        moviesDir = getInputValue("movies-dir"),
        tvDir = getInputValue("tv-dir"),
    ),
    languageRules = LanguageRules(
        audioCascade = getInputValue("audio-cascade").split(",").map { it.trim() }.filter { it.isNotEmpty() },
        subCascade = getInputValue("sub-cascade").split(",").map { it.trim() }.filter { it.isNotEmpty() },
    ),
    behavior = Behavior(
        overwriteNfo = overwriteNfo,
        fetchImages = fetchImages,
    ),
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
    appendLine("[paths]")
    appendLine("""movies_dir = "${c.paths.moviesDir}"""")
    appendLine("""tv_dir = "${c.paths.tvDir}"""")
    appendLine()
    appendLine("[language_rules]")
    appendLine("""audio_cascade = [${c.languageRules.audioCascade.joinToString(", ") { "\"$it\"" }}]""")
    appendLine("""sub_cascade = [${c.languageRules.subCascade.joinToString(", ") { "\"$it\"" }}]""")
    appendLine()
    appendLine("[behavior]")
    appendLine("overwrite_nfo = ${c.behavior.overwriteNfo}")
    appendLine("fetch_images = ${c.behavior.fetchImages}")
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
