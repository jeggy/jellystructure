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
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.QBittorrentConfig
import dev.jellystructure.api.QBittorrentPathMapping
import dev.jellystructure.api.httpClient
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import dev.jellystructure.observeSections
import dev.jellystructure.scrollIntoViewSmooth

fun renderSettings(container: Element, scope: CoroutineScope, query: Map<String, String> = emptyMap()) {
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
              <button data-sect="sect-crossseed" class="settings-nav-item" style="background:none;border:none;text-align:left;padding:5px 8px;border-radius:5px;font-size:.85rem;cursor:pointer;color:var(--ink-soft);">Cross-seed safety</button>
              <button data-sect="sect-notifications" class="settings-nav-item" style="background:none;border:none;text-align:left;padding:5px 8px;border-radius:5px;font-size:.85rem;cursor:pointer;color:var(--ink-soft);">Notifications</button>
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
              <div style="display:flex;align-items:center;gap:8px;margin-bottom:14px;flex-wrap:wrap">
                <h3 style="font-size:1rem;margin:0">Scanning</h3>
                <span id="tool-status-chip" style="font-size:.75rem;color:var(--ink-soft)"></span>
              </div>
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
              <div style="display:flex;align-items:center;justify-content:space-between;margin-top:12px">
                <div>
                  <span style="font-size:.9rem">Scheduled rescan</span>
                  <div class="hint" style="margin-top:2px">Automatically re-scan on a fixed interval</div>
                </div>
                <span id="scheduled-rescan-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>
              <div id="scheduled-rescan-fields" style="display:none;margin-top:10px;display:flex;gap:10px;align-items:center;flex-wrap:wrap">
                <select id="rescan-frequency" class="input" style="width:auto">
                  <option value="daily">Daily</option>
                  <option value="weekly">Weekly</option>
                </select>
                <span class="hint" style="margin:0">at</span>
                <input id="rescan-time" class="input" type="time" value="03:00" style="width:auto">
              </div>
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

            <div class="card" id="sect-crossseed">
              <div style="display:flex;align-items:center;gap:10px;margin-bottom:14px">
                <h3 style="font-size:1rem;margin:0">Cross-seed safety</h3>
                <span class="badge" style="font-size:.7rem;background:var(--fill-2)">qBittorrent</span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                <div>
                  <span style="font-size:.9rem;font-weight:500">Enable seeding guard</span>
                  <div class="hint" style="margin-top:2px">When off, no edit is ever blocked on qBittorrent's account</div>
                </div>
                <span id="qb-enabled-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>
              <div id="qb-fields" style="display:none">
                <div class="field">
                  <label>qBittorrent URL</label>
                  <input id="qb-url" class="input" type="url" placeholder="http://localhost:8080" style="width:100%">
                </div>
                <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:12px">
                  <div>
                    <span style="font-size:.9rem">No authentication</span>
                    <div class="hint" style="margin-top:2px">Skip login — for instances with auth disabled or localhost bypass</div>
                  </div>
                  <span id="qb-no-auth-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
                </div>
                <div id="qb-credential-fields">
                <div class="field">
                  <label>Username</label>
                  <input id="qb-username" class="input" type="text" style="width:100%">
                </div>
                <div class="field">
                  <label>Password</label>
                  <input id="qb-password" class="input" type="password" placeholder="(unchanged)" style="width:100%">
                  <span class="hint">Leave blank to keep the stored password</span>
                </div>
                </div>
                <div style="margin-bottom:12px">
                  <div style="display:flex;align-items:center;gap:8px;margin-bottom:6px">
                    <button id="qb-test-btn" class="btn sm ghost">Test connection</button>
                    <span id="qb-test-result" class="tiny muted"></span>
                  </div>
                </div>
                <div>
                  <div style="font-size:.85rem;font-weight:500;margin-bottom:6px">Path mappings <span class="muted tiny">(longest match wins)</span></div>
                  <p class="hint" style="margin:0 0 8px">Map local paths to the paths qBittorrent sees. Leave empty if paths are identical.</p>
                  <div id="qb-path-mappings"></div>
                  <button id="qb-add-mapping" class="btn sm ghost" style="margin-top:6px">+ Add mapping</button>
                </div>
                <div class="hint" style="margin-top:12px;color:var(--warn)">When enabled but unreachable, edits are blocked until qBittorrent responds or the guard is disabled.</div>
              </div>
            </div>

            <div class="card" id="sect-notifications">
              <h3 style="font-size:1rem;margin:0 0 14px">Notifications</h3>
              <div class="field">
                <label>Webhook URL</label>
                <input id="notif-webhook" class="input" type="url" placeholder="https://…/webhook" style="width:100%">
                <span class="hint">POST with JSON body; leave blank to disable webhooks</span>
              </div>
              <div style="margin-bottom:12px">
                <button id="notif-test-btn" class="btn sm ghost">Send test notification</button>
                <span id="notif-test-result" class="tiny muted" style="margin-left:8px"></span>
              </div>
              <div style="font-size:.83rem;font-weight:500;margin-bottom:8px;color:var(--ink-soft)">Fire for…</div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">Scan finished</span>
                <span id="notif-scan-done-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <div>
                  <span style="font-size:.9rem">No TMDB match found</span>
                  <div class="hint" style="margin-top:2px">Fires once per scan if any items remain unmatched</div>
                </div>
                <span id="notif-no-match-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">Track write failed</span>
                <span id="notif-write-failed-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between">
                <span style="font-size:.9rem">Drift detected</span>
                <span id="notif-drift-toggle" class="toggle" style="cursor:pointer"></span>
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

    // Scroll to section from ?sect= query param
    val sectParam = query["sect"]
    if (sectParam != null) {
        val target = document.getElementById(sectParam) ?: document.getElementById("sect-$sectParam")
        target?.let { scrollIntoViewSmooth(it) }
    }
}

private var overwriteNfo = false
private var fetchImages = true
private var watchEnabled = false
private var tellJellyfin = true
private var scanWorkers = 1
private var scanThreads = 4
private var effectiveScanThreads = 4
private var libraryMappings: MutableList<LibraryMapping> = mutableListOf()
private var qbEnabled = false
private var qbNoAuth = false
private var qbPathMappings: MutableList<QBittorrentPathMapping> = mutableListOf()
private var notifScanDone = true
private var notifNoMatch = false
private var notifWriteFailed = true
private var notifDrift = false
private var scheduledRescanEnabled = false
private var settingsScope: CoroutineScope? = null

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

    val qb = config.qbittorrent
    qbEnabled = qb?.enabled ?: false
    qbNoAuth = qb?.noAuth ?: false
    qbPathMappings = (qb?.pathMappings ?: emptyList()).toMutableList()
    updateToggle("qb-enabled-toggle", qbEnabled)
    updateToggle("qb-no-auth-toggle", qbNoAuth)
    if (qb != null) {
        setInputValue("qb-url", qb.url)
        setInputValue("qb-username", qb.username)
    }
    val qbFields = document.getElementById("qb-fields") as? HTMLElement
    qbFields?.style?.display = if (qbEnabled) "block" else "none"
    val qbCredFields = document.getElementById("qb-credential-fields") as? HTMLElement
    qbCredFields?.style?.display = if (qbNoAuth) "none" else "block"
    renderQbPathMappings()

    notifScanDone = config.behavior.notifyOnScanDone
    notifNoMatch = config.behavior.notifyOnNoMatch
    notifWriteFailed = config.behavior.notifyOnWriteFailed
    notifDrift = config.behavior.notifyOnDrift
    setInputValue("notif-webhook", config.behavior.notificationsWebhook)
    updateToggle("notif-scan-done-toggle", notifScanDone)
    updateToggle("notif-no-match-toggle", notifNoMatch)
    updateToggle("notif-write-failed-toggle", notifWriteFailed)
    updateToggle("notif-drift-toggle", notifDrift)

    scheduledRescanEnabled = config.behavior.scanIntervalHours > 0
    updateToggle("scheduled-rescan-toggle", scheduledRescanEnabled)
    val rescanFields = document.getElementById("scheduled-rescan-fields") as? HTMLElement
    rescanFields?.style?.display = if (scheduledRescanEnabled) "flex" else "none"
    if (config.behavior.scanIntervalHours >= 168) {
        (document.getElementById("rescan-frequency") as? HTMLSelectElement)?.value = "weekly"
    }

    refreshTomlPreview(config)
}

private suspend fun populateToolChips() {
    val chipEl = document.getElementById("tool-status-chip") as? HTMLElement ?: return
    val report = ConfigApi.getHealthFull() ?: return
    val tools = listOf("ffmpeg", "ffprobe", "mkvpropedit")
    val chips = tools.map { name ->
        val check = report.checks.find { it.name == name }
        val cls = if (check?.ok == true) "ok" else "bad"
        """<span class="badge $cls" style="font-size:.72rem;margin-right:4px">$name</span>"""
    }.joinToString("")
    chipEl.innerHTML = chips
}

private fun attachListeners(scope: CoroutineScope) {
    settingsScope = scope
    scope.launch { populateToolChips() }
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

    document.getElementById("qb-enabled-toggle")?.addEventListener("click") {
        qbEnabled = !qbEnabled
        updateToggle("qb-enabled-toggle", qbEnabled)
        val qbFields = document.getElementById("qb-fields") as? HTMLElement
        qbFields?.style?.display = if (qbEnabled) "block" else "none"
        refreshTomlPreview(readForm())
    }
    document.getElementById("qb-no-auth-toggle")?.addEventListener("click") {
        qbNoAuth = !qbNoAuth
        updateToggle("qb-no-auth-toggle", qbNoAuth)
        val qbCredFields = document.getElementById("qb-credential-fields") as? HTMLElement
        qbCredFields?.style?.display = if (qbNoAuth) "none" else "block"
        refreshTomlPreview(readForm())
    }
    listOf("qb-url", "qb-username", "qb-password").forEach { id ->
        document.getElementById(id)?.addEventListener("input") { refreshTomlPreview(readForm()) }
    }
    document.getElementById("qb-test-btn")?.addEventListener("click") {
        scope.launch {
            val resultEl = document.getElementById("qb-test-result") as? HTMLElement ?: return@launch
            resultEl.textContent = "Testing…"
            val url = getInputValue("qb-url")
            val username = getInputValue("qb-username")
            val password = getInputValue("qb-password")
            val r = ConfigApi.testQBittorrent(url, username, password)
            if (r == null) {
                resultEl.innerHTML = """<span class="badge bad">Request failed</span>"""
            } else if (r.ok) {
                resultEl.innerHTML = """<span class="badge ok">Connected — ${r.torrentCount ?: 0} torrents</span>"""
            } else {
                resultEl.innerHTML = """<span class="badge bad">${r.detail.esc()}</span>"""
            }
        }
    }
    document.getElementById("qb-add-mapping")?.addEventListener("click") {
        qbPathMappings.add(QBittorrentPathMapping())
        renderQbPathMappings()
        refreshTomlPreview(readForm())
    }

    document.getElementById("notif-scan-done-toggle")?.addEventListener("click") {
        notifScanDone = !notifScanDone
        updateToggle("notif-scan-done-toggle", notifScanDone)
        refreshTomlPreview(readForm())
    }
    document.getElementById("notif-no-match-toggle")?.addEventListener("click") {
        notifNoMatch = !notifNoMatch
        updateToggle("notif-no-match-toggle", notifNoMatch)
        refreshTomlPreview(readForm())
    }
    document.getElementById("notif-write-failed-toggle")?.addEventListener("click") {
        notifWriteFailed = !notifWriteFailed
        updateToggle("notif-write-failed-toggle", notifWriteFailed)
        refreshTomlPreview(readForm())
    }
    document.getElementById("notif-drift-toggle")?.addEventListener("click") {
        notifDrift = !notifDrift
        updateToggle("notif-drift-toggle", notifDrift)
        refreshTomlPreview(readForm())
    }
    document.getElementById("notif-webhook")?.addEventListener("input") { refreshTomlPreview(readForm()) }
    document.getElementById("notif-test-btn")?.addEventListener("click") {
        scope.launch {
            val resultEl = document.getElementById("notif-test-result") as? HTMLElement ?: return@launch
            resultEl.textContent = "Sending…"
            val webhookUrl = getInputValue("notif-webhook")
            if (webhookUrl.isBlank()) { resultEl.textContent = "No URL set."; return@launch }
            val ok = runCatching {
                httpClient.post(webhookUrl) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"event":"test","source":"jellystructure"}""")
                }.status.value in 200..299
            }.getOrDefault(false)
            resultEl.innerHTML = if (ok) """<span class="badge ok">Delivered</span>""" else """<span class="badge bad">Failed</span>"""
        }
    }

    document.getElementById("scheduled-rescan-toggle")?.addEventListener("click") {
        scheduledRescanEnabled = !scheduledRescanEnabled
        updateToggle("scheduled-rescan-toggle", scheduledRescanEnabled)
        val rescanFields = document.getElementById("scheduled-rescan-fields") as? HTMLElement
        rescanFields?.style?.display = if (scheduledRescanEnabled) "flex" else "none"
        refreshTomlPreview(readForm())
    }
    listOf("rescan-frequency", "rescan-time").forEach { id ->
        document.getElementById(id)?.addEventListener("change") { refreshTomlPreview(readForm()) }
    }

    document.getElementById("save-settings")?.addEventListener("click") {
        scope.launch {
            val config = readForm()
            val ok = ConfigApi.save(config)
            showSettingsMsg(if (ok) "Saved." else "Save failed.", ok)
            if (ok) renderPathCheckInline(ConfigApi.pathCheck())
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
        val el = document.getElementById("conn-result") as? HTMLElement ?: return@addEventListener
        el.style.display = "block"
        el.innerHTML = """<span class="muted tiny">Running health checks…</span>"""
        scope.launch {
            val report = ConfigApi.getHealthFull()
            el.style.display = "block"
            if (report == null) {
                el.innerHTML = """<span class="badge bad">Health check failed — server unreachable</span>"""
                return@launch
            }
            el.innerHTML = report.checks.joinToString("") { check ->
                val cls = if (check.ok) "ok" else "bad"
                val icon = if (check.ok) "✓" else "✗"
                """<div style="display:flex;align-items:center;gap:8px;padding:4px 0;">
                     <span class="badge $cls" style="min-width:80px;text-align:center;">$icon</span>
                     <span style="font-weight:600;font-size:.85rem;">${check.name.esc()}</span>
                     <span class="muted tiny">${check.detail.esc()}</span>
                   </div>"""
            }
            val jellyfinOk = report.checks.find { it.name == "Jellyfin" }?.ok == true
            // Update per-field inline badges
            val jfBadge = document.getElementById("jf-token-badge") as? HTMLElement
            if (jfBadge != null) {
                jfBadge.style.display = "inline"
                jfBadge.innerHTML = if (jellyfinOk) """<span class="badge ok" style="font-size:.72rem">valid ✓</span>""" else """<span class="badge bad" style="font-size:.72rem">invalid ✗</span>"""
            }
            val tmdbOk = report.checks.find { it.name == "TMDB API key" }?.ok == true
            val tmdbBadge = document.getElementById("tmdb-key-badge") as? HTMLElement
            if (tmdbBadge != null) {
                tmdbBadge.style.display = "inline"
                tmdbBadge.innerHTML = if (tmdbOk) """<span class="badge ok" style="font-size:.72rem">valid ✓</span>""" else """<span class="badge bad" style="font-size:.72rem">invalid ✗</span>"""
            }
            if (jellyfinOk) {
                fetchAndRenderLibraries()
                renderPathCheckInline(ConfigApi.pathCheck())
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

private fun buildLibraryCardHtml(i: Int, lib: LibraryMapping): String {
    val skipped = lib.skip
    val typeLabel = lib.collectionType.ifEmpty { "?" }
    return """
    <div style="border:1.5px solid var(--border);border-radius:6px;padding:10px 12px;margin-bottom:10px;${if (skipped) "opacity:.5" else ""}">
      <div style="display:flex;align-items:center;gap:8px;margin-bottom:8px">
        <strong style="font-size:.9rem;flex:1">${lib.name}</strong>
        <span class="badge" style="font-size:.72rem;padding:1px 7px;background:var(--fill-2)">$typeLabel</span>
        <span id="lib-path-status-$i"></span>
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
      ${if (!skipped) """
      <div style="display:flex;gap:6px;margin-top:8px;padding-top:8px;border-top:1px solid var(--border)">
        <button class="btn sm ghost lib-scan-btn" data-lib-id="${lib.jellyfinId}" data-lib-idx="$i">Scan</button>
        <button class="btn sm ghost lib-push-btn" data-lib-idx="$i">Push all to Jellyfin</button>
        <span id="lib-action-result-$i" class="tiny muted"></span>
      </div>""" else ""}
    </div>
    """.trimIndent()
}

private fun renderLibraryList() {
    val listEl = document.getElementById("library-mapping-list") as? HTMLElement ?: return
    if (libraryMappings.isEmpty()) {
        listEl.innerHTML = """<span class="muted tiny">No libraries found.</span>"""
        return
    }

    val activeCards = libraryMappings.mapIndexedNotNull { i, lib -> if (!lib.skip) buildLibraryCardHtml(i, lib) else null }.joinToString("")
    val skippedEntries = libraryMappings.mapIndexedNotNull { i, lib -> if (lib.skip) Pair(i, lib) else null }
    val skippedCards = if (skippedEntries.isEmpty()) "" else buildString {
        append("""<details style="margin-top:4px"><summary style="cursor:pointer;font-size:.82rem;color:var(--ink-soft);user-select:none;padding:4px 2px">Skipped libraries (${skippedEntries.size})</summary><div style="margin-top:8px">""")
        for ((i, lib) in skippedEntries) append(buildLibraryCardHtml(i, lib))
        append("</div></details>")
    }
    listEl.innerHTML = activeCards + skippedCards

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
        val scope = settingsScope ?: return@forEachIndexed
        val lib = libraryMappings[i]
        if (!lib.skip) {
            document.getElementById("lib-scan-btn")?.let { } // no-op; use querySelectorAll below
        }
    }
    // Wire scan/push buttons
    listEl.querySelectorAll(".lib-scan-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            val jellyfinId = btn.getAttribute("data-lib-id") ?: continue
            val idx = btn.getAttribute("data-lib-idx")?.toIntOrNull() ?: continue
            btn.addEventListener("click") {
                val scope = settingsScope ?: return@addEventListener
                scope.launch {
                    val resultEl = document.getElementById("lib-action-result-$idx") as? HTMLElement ?: return@launch
                    btn.setAttribute("disabled", "")
                    resultEl.textContent = "Starting scan…"
                    val ok = runCatching { MediaApi.startLibraryScan(jellyfinId) }.getOrDefault(false)
                    resultEl.innerHTML = if (ok) """<span class="badge ok">Scan started</span>""" else """<span class="badge bad">Failed</span>"""
                    btn.removeAttribute("disabled")
                }
            }
        }
    }
    listEl.querySelectorAll(".lib-push-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            val idx = btn.getAttribute("data-lib-idx")?.toIntOrNull() ?: continue
            btn.addEventListener("click") {
                val scope = settingsScope ?: return@addEventListener
                scope.launch {
                    val resultEl = document.getElementById("lib-action-result-$idx") as? HTMLElement ?: return@launch
                    btn.setAttribute("disabled", "")
                    resultEl.textContent = "Pushing…"
                    val ok = runCatching { MediaApi.batchJellyfinPush() }.getOrDefault(false)
                    resultEl.innerHTML = if (ok) """<span class="badge ok">Push queued</span>""" else """<span class="badge bad">Failed</span>"""
                    btn.removeAttribute("disabled")
                }
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
        scanIntervalHours = if (scheduledRescanEnabled) {
            val freq = (document.getElementById("rescan-frequency") as? HTMLSelectElement)?.value ?: "daily"
            if (freq == "weekly") 168 else 24
        } else 0,
        notificationsWebhook = getInputValue("notif-webhook"),
        notifyOnScanDone = notifScanDone,
        notifyOnNoMatch = notifNoMatch,
        notifyOnWriteFailed = notifWriteFailed,
        notifyOnDrift = notifDrift,
    ),
    libraries = libraryMappings.toList(),
    qbittorrent = if (qbEnabled) QBittorrentConfig(
        enabled = true,
        url = getInputValue("qb-url"),
        noAuth = qbNoAuth,
        username = if (qbNoAuth) "" else getInputValue("qb-username"),
        password = if (qbNoAuth) "" else getInputValue("qb-password").ifBlank { "##KEEP##" },
        pathMappings = qbPathMappings.toList(),
    ) else null,
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
    if (c.behavior.scanIntervalHours > 0) appendLine("scan_interval_hours = ${c.behavior.scanIntervalHours}")
    if (c.behavior.notificationsWebhook.isNotBlank()) {
        appendLine()
        appendLine("[notifications]")
        appendLine("""webhook = "${c.behavior.notificationsWebhook}"""")
        appendLine("notify_on_scan_done = ${c.behavior.notifyOnScanDone}")
        appendLine("notify_on_no_match = ${c.behavior.notifyOnNoMatch}")
        appendLine("notify_on_write_failed = ${c.behavior.notifyOnWriteFailed}")
        appendLine("notify_on_drift = ${c.behavior.notifyOnDrift}")
    }
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
    val qb = c.qbittorrent
    if (qb != null) {
        appendLine()
        appendLine("[qbittorrent]")
        appendLine("""url = "${qb.url}"""")
        appendLine("enabled = ${qb.enabled}")
        appendLine("no_auth = ${qb.noAuth}")
        if (!qb.noAuth) {
            appendLine("""username = "${qb.username}"""")
            appendLine("""password = "***"""")
        }
        for (m in qb.pathMappings) {
            appendLine()
            appendLine("[[qbittorrent.path_mappings]]")
            appendLine("""local = "${m.local}"""")
            appendLine("""remote = "${m.remote}"""")
        }
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
    val sections = "sect-connections,sect-libraries,sect-scanning,sect-metadata,sect-advanced,sect-crossseed,sect-notifications"
    observeSections(sections, "-10% 0px -80% 0px") { visibleId ->
        for (k in 0 until navBtns.length) {
            val b = navBtns.item(k) as? HTMLElement ?: continue
            val active = b.getAttribute("data-sect") == visibleId
            b.style.color = if (active) "var(--ink)" else "var(--ink-soft)"
            b.style.fontWeight = if (active) "600" else ""
        }
    }
}

private fun renderQbPathMappings() {
    val container = document.getElementById("qb-path-mappings") as? HTMLElement ?: return
    if (qbPathMappings.isEmpty()) {
        container.innerHTML = """<span class="muted tiny">No mappings — paths are passed through unchanged.</span>"""
        return
    }
    container.innerHTML = qbPathMappings.mapIndexed { i, m ->
        """<div style="display:flex;gap:6px;align-items:center;margin-bottom:6px;" data-qb-mapping="$i">
             <input class="input qb-local" style="flex:1;font-size:.82rem;" placeholder="local path" value="${m.local.esc()}">
             <span class="muted tiny">→</span>
             <input class="input qb-remote" style="flex:1;font-size:.82rem;" placeholder="remote path" value="${m.remote.esc()}">
             <button class="btn sm ghost qb-remove-mapping" data-idx="$i" style="flex-shrink:0;">✕</button>
           </div>"""
    }.joinToString("")
    container.querySelectorAll(".qb-local,.qb-remote").let { nodes ->
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? HTMLInputElement)?.addEventListener("input") {
                syncQbMappingsFromDom()
                refreshTomlPreview(readForm())
            }
        }
    }
    container.querySelectorAll(".qb-remove-mapping").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val idx = btn.getAttribute("data-idx")?.toIntOrNull() ?: return@addEventListener
                qbPathMappings.removeAt(idx)
                renderQbPathMappings()
                refreshTomlPreview(readForm())
            }
        }
    }
}

private fun syncQbMappingsFromDom() {
    val container = document.getElementById("qb-path-mappings") as? HTMLElement ?: return
    container.querySelectorAll("[data-qb-mapping]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            val local = (el.querySelector(".qb-local") as? HTMLInputElement)?.value ?: ""
            val remote = (el.querySelector(".qb-remote") as? HTMLInputElement)?.value ?: ""
            if (i < qbPathMappings.size) qbPathMappings[i] = QBittorrentPathMapping(local, remote)
        }
    }
}

private fun renderPathCheckInline(diags: List<LibraryPathDiag>?) {
    if (diags == null) return
    val diagsByName = diags.associateBy { it.name }
    libraryMappings.forEachIndexed { i, lib ->
        val statusEl = document.getElementById("lib-path-status-$i") as? HTMLElement ?: return@forEachIndexed
        val diag = diagsByName[lib.name] ?: return@forEachIndexed
        statusEl.innerHTML = if (diag.localExists) {
            """<span class="badge ok" style="font-size:.7rem">path ✓</span>"""
        } else {
            """<span class="badge bad" style="font-size:.7rem" title="Local path not found — check mount">path ✗</span>"""
        }
    }
}
