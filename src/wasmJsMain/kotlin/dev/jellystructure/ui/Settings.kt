package dev.jellystructure.ui

import dev.jellystructure.api.AppConfig
import dev.jellystructure.api.ApiKeys
import dev.jellystructure.api.LanguageRules
import dev.jellystructure.api.Behavior
import dev.jellystructure.api.PipelineStep
import dev.jellystructure.api.ScanConfig
import dev.jellystructure.api.ConfigResponse
import dev.jellystructure.api.LibraryMapping
import dev.jellystructure.api.LibraryPathDiag
import dev.jellystructure.api.ConfigApi
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.PipelineRunResult
import dev.jellystructure.api.ArrConfig
import dev.jellystructure.api.QBittorrentConfig
import dev.jellystructure.api.QBittorrentPathMapping
import dev.jellystructure.api.BazarrConfig
import dev.jellystructure.api.SeerrConfig
import dev.jellystructure.api.RequestLanguageConfig
import dev.jellystructure.api.RequestLanguageIntent
import dev.jellystructure.api.ProvisionPlanLine
import dev.jellystructure.api.TowoApi
import dev.jellystructure.api.TowoSettings
import dev.jellystructure.api.MetadataConfig
import dev.jellystructure.resolver.CertificationCatalog
import dev.jellystructure.api.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import dev.jellystructure.Router

fun renderSettings(container: Element, scope: CoroutineScope, query: Map<String, String> = emptyMap()) {
    container.innerHTML = """
        <div class="pagebar" id="set-pagebar">
          <h1>Settings</h1>
          <span class="spacer"></span>
          <button id="test-connections" class="btn sm ghost"><span id="test-conn-label">Test connections</span><span id="test-conn-count" style="display:none;margin-left:6px;background:var(--bad);color:#fff;border-radius:99px;padding:0 7px;font-size:.72rem;font-weight:600"></span></button>
          <button id="save-settings" class="btn primary">Save</button>
        </div>
        <p class="page-sub">Configuration is persisted to <code>config.toml</code> on the server.</p>
        <div id="settings-msg" style="display:none;margin-bottom:14px"></div>

        <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap">

          <nav style="width:160px;flex-shrink:0;position:sticky;top:88px;">
            <div style="display:flex;flex-direction:column;gap:2px;">
              ${settingsNavItemHtml("connections", "Connections")}
              ${settingsNavItemHtml("libraries", "Libraries")}
              ${settingsNavItemHtml("metadata", "Metadata")}
              ${settingsNavItemHtml("downloads", "Download tools")}
              ${settingsNavItemHtml("notifications", "Notifications")}
              ${settingsNavItemHtml("towo", "Towo")}
              ${settingsNavItemHtml("advanced", "Advanced")}
            </div>
          </nav>

          <div class="col fill" style="min-width:280px">

            <div class="card set-section" id="sect-connections" data-tab="connections">
              <h3 style="font-size:1rem;margin:0 0 14px">Connections</h3>
              <div class="field">
                <label>Jellyfin URL</label>
                <input id="jellyfin-url" class="input" type="url" placeholder="http://localhost:8096" style="width:100%">
              </div>
              <div class="field">
                <label>Jellyfin machine token <span id="jf-token-badge" style="display:none;margin-left:8px"></span></label>
                <input id="jellyfin-token" class="input" type="password" placeholder="(unchanged)" style="width:100%">
                <span class="hint">Background jobs — not your login token</span>
              </div>
              <div class="field">
                <label>TMDB API key (v3) <span id="tmdb-key-badge" style="display:none;margin-left:8px"></span></label>
                <input id="tmdb-key" class="input" type="password" placeholder="(unchanged)" style="width:100%">
              </div>
              <div id="conn-result" style="display:none;margin-top:8px"></div>
            </div>

            <div class="card set-section" id="sect-apikeys" data-tab="connections">
              <h3 style="font-size:1rem;margin:0 0 14px">API keys</h3>
              <p class="hint" style="margin:0 0 12px">For external tools (Home Assistant etc.) to control Ravilo devices via <span class="mono">/api/remote/**</span> — a key acts as one Jellyfin user and can list/play/command that user's paired TVs.</p>
              <details style="margin-bottom:12px;font-size:.82rem;color:var(--ink-soft)">
                <summary style="cursor:pointer;user-select:none;color:var(--ink-soft)">What can an API key do?</summary>
                <div style="margin:6px 0 0;line-height:1.5">
                  <p style="margin:0 0 6px">A key is scoped to <b>one Jellyfin user</b> and only ever sees that user's own paired Ravilo devices — never anyone else's, and never the rest of jellystructure's admin/media surface. Send it as either <span class="mono">Authorization: Bearer &lt;key&gt;</span> or <span class="mono">X-JS-Api-Key: &lt;key&gt;</span>. Three endpoints:</p>
                  <ul style="margin:0 0 6px;padding-left:18px">
                    <li><span class="mono">GET /api/remote/devices</span> — this user's paired devices: name, online/offline, what's currently playing.</li>
                    <li><span class="mono">POST /api/remote/play</span> — start a Jellyfin item on one of those devices, optionally at a start position.</li>
                    <li><span class="mono">POST /api/remote/command</span> — send <span class="mono">stop</span> / <span class="mono">pause</span> / <span class="mono">unpause</span> / <span class="mono">home</span> to one of those devices.</li>
                  </ul>
                  <p style="margin:0">The target device must already be online (connected over its own live socket to jellystructure) — playing/commanding an offline device fails rather than queuing. The key itself is shown once at creation and can't be retrieved again; revoke and re-create if it's lost.</p>
                </div>
              </details>
              <div class="row" style="gap:8px;flex-wrap:wrap;align-items:flex-end;margin-bottom:10px">
                <div class="field" style="flex:1;min-width:160px;margin:0">
                  <label>Key name</label>
                  <input id="apikey-name" class="input" type="text" placeholder="Home Assistant" style="width:100%">
                </div>
                <div class="field" style="flex:1;min-width:160px;margin:0">
                  <label>Acts as</label>
                  <select id="apikey-user" class="input" style="width:100%"></select>
                </div>
                <button id="apikey-create-btn" class="btn sm">+ Create key</button>
              </div>
              <div id="apikey-created" style="display:none;margin-bottom:12px;padding:10px 12px;border-radius:6px;background:var(--ok-soft,rgba(45,212,154,.1));border:1px solid var(--ok,#2dd49a)"></div>
              <div id="apikey-list"><span class="muted tiny">Loading…</span></div>
            </div>

            <div class="card set-section" id="sect-libraries" data-tab="libraries">
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

            <div class="card set-section" id="sect-scanning" data-tab="libraries">
              <div style="display:flex;align-items:center;gap:8px;margin-bottom:14px;flex-wrap:wrap">
                <h3 style="font-size:1rem;margin:0">Scanning</h3>
                <span id="tool-status-chip" style="font-size:.75rem;color:var(--ink-soft)"></span>
              </div>
              <details style="margin-bottom:14px;font-size:.84rem">
                <summary style="cursor:pointer;color:var(--ink-soft)">How scanning works</summary>
                <div class="hint" style="margin-top:8px;line-height:1.5">
                  Three things keep your library current:<br>
                  &bull; <b>Scan library</b> — finds new &amp; changed files from Jellyfin, adds/updates titles, and (if &ldquo;Download missing artwork during a scan&rdquo; is on) pulls any missing posters/fanart from TMDB.<br>
                  &bull; <b>The automation (pipeline below)</b> — starts with a scan, then runs the steps you enable (pull TMDB, download artwork, write NFOs, sync Jellyfin&hellip;). Runs on the <b>schedule</b> (at the local time shown) and on demand via <b>Run pipeline now</b>.<br>
                  &bull; <b>Per-title actions</b> — on a movie/series page: <b>Re-pull from TMDB</b>, <b>Sync to Jellyfin</b>. Same engines, one title.<br>
                  Every run is logged and filterable on the <a href="#/activity">Activity</a> page by run.
                </div>
              </details>
              <div class="field">
                <label>Scan workers</label>
                <input id="scan-workers" class="input" type="number" min="1" max="100" style="width:90px">
                <span class="hint">Number of items processed concurrently. Changing this during a scan takes effect immediately.</span>
              </div>
              <div class="field">
                <label>Segment detection workers</label>
                <input id="segment-workers" class="input" type="number" min="1" max="8" style="width:90px">
                <span class="hint">Intro/credits detection runs in its own queue (Activity ▸ Jobs &amp; workers), concurrently with everything else, so a slow scan no longer holds up the rest of the pipeline. Takes effect on the next job dispatch, no restart needed.</span>
              </div>
              <div class="field">
                <label>Scan thread pool size</label>
                <input id="scan-threads" class="input" type="number" min="1" max="100" style="width:90px">
                <span class="hint">Thread pool the workers run on. <strong>Requires an application restart.</strong></span>
              </div>
              <div class="field">
                <label>Episode probe cap per series</label>
                <input id="scan-episode-cap" class="input" type="number" min="0" max="100000" style="width:90px">
                <span class="hint"><strong>0 = unlimited</strong> — probe every episode. Set a positive number to sample only that many files per series on a full scan (very large libraries). The on-demand "Re-probe episode files" button always probes everything.</span>
              </div>
              <div id="scan-threads-restart-banner" style="display:none;margin-top:10px;padding:8px 12px;border-radius:6px;background:var(--warn-fill,#7c5100);color:var(--warn-ink,#fff);font-size:.83rem"></div>
              <div class="row center" style="justify-content:space-between;margin-top:12px;">
                <div>
                  <b>Scheduled scan pipeline</b>
                  <div class="hint" style="margin-top:2px">Compose what runs on each automated scan — always starts with <b>Scan media files</b>, then chain any steps you want.</div>
                </div>
                <span id="pipe-enable" class="toggle" style="cursor:pointer;margin-left:12px;flex-shrink:0"></span>
              </div>
              <div id="pipe-fields" style="display:none;margin-top:14px">
                <div class="pipe-sched">
                  <div class="field" style="margin:0"><label>Runs</label>
                    <span class="seg" id="pipe-freq"><span data-f="daily" class="on">Daily</span><span data-f="weekly">Weekly</span><span data-f="everyN">Every N hours</span><span data-f="custom">Custom</span></span>
                  </div>
                  <div class="field" style="margin:0;width:104px" id="pipe-at-field"><label>At</label>
                    <input id="pipe-at" class="input" type="time" value="03:00">
                  </div>
                  <div class="field" style="margin:0;display:none" id="pipe-everyn-field">
                    <label>Every</label>
                    <div class="row" style="gap:4px;align-items:center">
                      <input id="pipe-everyn-hours" class="input" type="number" min="1" max="23" value="6" style="width:56px">
                      <span class="tiny muted">h · min</span>
                      <input id="pipe-everyn-min" class="input" type="number" min="0" max="59" value="0" style="width:52px">
                    </div>
                  </div>
                  <div class="field" style="margin:0;min-width:160px;flex:1"><label>Cron <span class="muted" id="pipe-cron-label-hint">(derived)</span></label>
                    <input id="pipe-cron" class="input mono" style="padding:5px 10px;width:100%" value="0 3 * * *" readonly>
                  </div>
                  <span class="badge ok" id="pipe-next" style="align-self:flex-end">next · tonight 03:00</span>
                  <span class="split" id="pipe-run-split" style="align-self:flex-end">
                    <button id="pipe-run" class="btn sm primary" title="Run every enabled step below now (not just a file scan)">&#9655; Run pipeline now</button>
                    <span class="btn sm primary split-caret menu-btn"><span class="caret">▾</span></span>
                    <div class="menu">
                      <div class="menu-item" id="pipe-run-full"><span class="mi-ic">⟳</span><span>Run pipeline now (full)<span class="mi-sub">No freshness filter — every step sees the whole library</span></span></div>
                    </div>
                  </span>
                </div>
                <div class="tiny" id="pipe-cron-check" style="margin-top:6px"></div>
                <div class="pipe-recipe" id="pipe-recipe" style="margin-top:12px"></div>
                <div class="pipe-canvas" id="pipe-canvas" style="margin-top:14px"></div>
              </div>
            </div>

            <div class="card set-section" id="sect-metadata" data-tab="metadata">
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
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:2px">
                <span style="font-size:.9rem">Download missing artwork during a scan</span>
                <span id="fetch-images-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div class="hint" style="margin-bottom:8px">Each scanned title also gets its poster/fanart (and series stills + season posters) pulled from TMDB if not already on disk.</div>
              <div style="display:flex;align-items:center;justify-content:space-between">
                <div>
                  <span style="font-size:.9rem">Auto-tell Jellyfin to refresh</span>
                  <div class="hint" style="margin-top:2px">After writing NFO or artwork, trigger Jellyfin metadata refresh automatically</div>
                </div>
                <span id="tell-jellyfin-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>
            </div>

            <div class="card set-section" id="sect-ageratings" data-tab="metadata">
              <div class="row center"><h3 style="font-size:1rem;margin:0;">Age ratings</h3><span class="badge info" style="margin-left:8px;">region cascade</span></div>
              <div class="tiny muted" style="margin:8px 0 2px;line-height:1.6;">
                TMDB carries a separate certification per country. Jellystructure picks the one to store &amp; show by walking this
                <b>region cascade</b> top-to-bottom and using the <b>first region that has a rating</b> for a title — the same
                first-match idea as the language cascade. Reorder with &#9650;&#9660;; the top region wins whenever it has a certification.
                This is fetched from TMDB on scan and stored for Ravilo &amp; the filter workbench to use.
              </div>
              <ol class="rc-list" id="rc-list"></ol>
              <div class="rc-add" id="rc-add-wrap">
                <span class="btn sm" id="rc-add">&#65291; Add region</span>
                <div class="card rc-menu" id="rc-menu"></div>
                <span class="tiny muted">If none of these have a rating, the title's own primary certification is shown as a last resort.</span>
              </div>
            </div>


            <div class="card set-section" id="sect-crossseed" data-tab="downloads">
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
                <div style="margin-top:14px">
                  <div style="font-size:.85rem;font-weight:500;margin-bottom:4px">Snapshot refresh every</div>
                  <div style="display:flex;align-items:center;gap:8px">
                    <input id="qb-cache-ttl" class="input" type="number" min="30" max="3600" step="30" style="width:90px" placeholder="600">
                    <span class="muted tiny">seconds (default 600 = 10 min). The seeding surface reads this shared snapshot — lower = fresher data, more load.</span>
                  </div>
                </div>
                <div class="hint" style="margin-top:12px;color:var(--warn)">When enabled but unreachable, edits are blocked until qBittorrent responds or the guard is disabled.</div>
              </div>
            </div>

            <div class="card set-section" id="sect-arr" data-tab="downloads">
              <div style="display:flex;align-items:center;gap:10px;margin-bottom:6px">
                <h3 style="font-size:1rem;margin:0">Download tools</h3>
                <span class="badge" style="font-size:.7rem;background:var(--fill-2)">Radarr · Sonarr</span>
              </div>
              <p class="hint" style="margin:0 0 14px">Connect Radarr/Sonarr so Jellystructure can import their root folders as library mappings and nudge a rescan after editing a title. <strong>Read + rescan only</strong> — it never adds, grabs, or deletes.</p>
              ${arrBoxHtml("radarr", "Radarr", "movies", "http://radarr:7878")}
              ${arrBoxHtml("sonarr", "Sonarr", "tvshows", "http://sonarr:8989")}
              <div class="hint" style="margin-top:2px">Root-folder paths reconcile with <strong>Library mapping</strong> by longest prefix.</div>
            </div>

            <div class="card set-section" id="sect-seerr" data-tab="downloads">
              <div class="row center"><h3 style="font-size:1.05rem;margin:0;">Jellyseerr / Overseerr</h3><span class="badge info" style="margin-left:8px;">requests</span><span class="spacer"></span><span class="muted tiny">enable</span><span id="seerr-enabled-toggle" class="toggle" style="cursor:pointer"></span></div>
              <div class="tiny muted" style="margin:8px 0 0;">Optional. The <strong>connection</strong> to your Seerr server — Jellystructure uses it to search, browse and place requests from the TV, and shows live request status. Seerr owns request &amp; approval rules and hands approved titles to Radarr / Sonarr. <strong>Where the Request tab appears</strong> and <strong>which discover rows it shows</strong> is set per user in Ravilo config → Request.</div>
              <div id="seerr-on" style="display:none;margin-top:14px">
                <div class="field"><label>Seerr URL</label><input id="seerr-url" class="input" type="url" placeholder="http://jellyseerr:5055" style="width:100%"></div>
                <div class="field"><label>API key <span id="seerr-key-badge" style="display:none;margin-left:8px"></span></label>
                  <div style="display:flex;gap:8px;align-items:center">
                    <input id="seerr-key" class="input" type="password" style="flex:1;min-width:0">
                    <button id="seerr-key-reveal" type="button" class="btn sm ghost" style="flex:none">Show</button>
                  </div>
                  <span class="hint">Jellyseerr/Overseerr → Settings → General → API Key. The saved key is pre-filled — clear it to remove.</span>
                </div>
                <div style="display:flex;align-items:center;gap:8px">
                  <button id="seerr-test-btn" class="btn sm ghost">Test connection</button>
                  <span id="chk-seerr" class="tiny muted"></span>
                </div>
              </div>
            </div>

            <div class="card set-section" id="sect-bazarr" data-tab="downloads">
              <div class="row center"><h3 style="font-size:1.05rem;margin:0;">Bazarr</h3><span class="badge info" style="margin-left:8px;">subtitles</span><span class="spacer"></span><span class="muted tiny">enable</span><span id="bazarr-enabled-toggle" class="toggle" style="cursor:pointer"></span></div>
              <div class="tiny muted" style="margin:8px 0 0;">Optional. Jellystructure <strong>stores nothing about subtitles</strong> — it reads Bazarr live and issues commands to it. Bazarr keeps owning providers, scoring and language profiles; matched to titles by IMDb/TVDB id, same as Radarr/Sonarr.</div>
              <div id="bazarr-on" style="display:none;margin-top:14px">
                <div class="field"><label>Bazarr URL</label><input id="bazarr-url" class="input" type="url" placeholder="http://bazarr:6767" style="width:100%"></div>
                <div class="field"><label>API key <span id="bazarr-key-badge" style="display:none;margin-left:8px"></span></label>
                  <div style="display:flex;gap:8px;align-items:center">
                    <input id="bazarr-key" class="input" type="password" style="flex:1;min-width:0">
                    <button id="bazarr-key-reveal" type="button" class="btn sm ghost" style="flex:none">Show</button>
                  </div>
                  <span class="hint">Bazarr → Settings → General → Security → API Key. The saved key is pre-filled — clear it to remove.</span>
                </div>
                <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px">
                  <button id="bazarr-test-btn" class="btn sm ghost">Test connection</button>
                  <span id="chk-bazarr" class="tiny muted"></span>
                </div>
                <div class="row center" style="margin-top:2px"><span class="tiny">Auto-search when new media is added</span><span class="spacer"></span><span id="bazarr-autosearch-toggle" class="toggle" style="cursor:pointer"></span></div>
                <div class="row center" style="margin-top:8px"><span class="tiny">Show subtitle history on title pages</span><span class="spacer"></span><span id="bazarr-history-toggle" class="toggle" style="cursor:pointer"></span></div>
              </div>
            </div>

            <div class="card set-section" id="sect-request-lang" data-tab="downloads">
              <div class="row center"><h3 style="font-size:1.05rem;margin:0;">Request languages</h3><span class="badge info" style="margin-left:8px;">Nordic / Danish etc.</span></div>
              <div class="tiny muted" style="margin:8px 0 14px;">Lets a viewer request a title in a chosen language (e.g. <b>Dansk / Nordic</b> vs <b>Original</b>) — Jellystructure auto-creates a matching custom format + quality profile in Radarr/Sonarr so the grab lands the right release. See the Ravilo config editor's Request tab for each viewer's default.</div>
              <div id="rl-rows"></div>
              <button id="rl-add" class="btn sm ghost" style="margin-top:8px">+ Add language</button>
              <div class="field" style="margin-top:14px"><label>Kids default</label>
                <select id="rl-kids-default" class="input" style="max-width:260px"></select>
                <span class="hint">Which language a Kids-profile TV requests in, unless the viewer has their own default set.</span>
              </div>
              <div style="display:flex;align-items:center;gap:8px;margin-top:14px">
                <button id="rl-preview-btn" class="btn sm ghost">Preview</button>
                <button id="rl-provision-btn" class="btn sm">Set up profiles in Radarr/Sonarr</button>
                <span id="chk-rl" class="tiny muted"></span>
              </div>
              <div id="rl-plan" style="margin-top:10px;display:none"></div>
            </div>

            <div class="card set-section" id="sect-ingest" data-tab="downloads">
              <div style="display:flex;align-items:center;gap:10px;margin-bottom:6px">
                <h3 style="font-size:1rem;margin:0">Realtime ingest</h3>
                <span id="ingest-status-badge" class="badge" style="font-size:.7rem;background:var(--fill-2)">…</span>
              </div>
              <p class="hint" style="margin:0 0 12px">New imports reach Ravilo within minutes instead of waiting for the next scheduled scan. Jellyfin's own <b>Webhook plugin</b> notifies this install the moment it has finished identifying a new movie or episode.</p>

              <div id="ingest-jellyfin-card"><span class="muted tiny">Loading…</span></div>

              <div class="field" style="margin-top:14px">
                <label>Where Jellyfin should reach this install</label>
                <div style="display:flex;gap:8px">
                  <input id="ingest-reach-url" class="input mono" type="text" placeholder="http://10.0.0.10:9505" style="width:100%;font-size:.78rem">
                  <button id="ingest-reach-url-save" class="btn sm ghost">Save</button>
                </div>
                <span class="hint">The address <b>Jellyfin itself</b> can reach — not necessarily what your browser sees here. Required before "Set up Jellyfin webhook" can complete.</span>
                <span class="tiny" id="ingest-reach-url-msg" style="margin-left:8px"></span>
              </div>

              <div class="hint" id="ingest-webhook-detail" style="margin-top:10px"></div>
              <div class="hint" id="ingest-listener-detail" style="margin-top:4px"></div>
            </div>

            <div class="card set-section" id="sect-notifications" data-tab="notifications">
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
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">Drift detected</span>
                <span id="notif-drift-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between">
                <span style="font-size:.9rem">Server recovered from a crash</span>
                <span id="notif-crash-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
            </div>

            <div class="card set-section" id="sect-towo" data-tab="towo">
              <h3 style="font-size:1rem;margin:0 0 6px">Towo</h3>
              <p class="hint" style="margin:0 0 14px">A control plane for <b>Claude Code</b> sessions running on machines you own — unrelated to media management. Off by default.</p>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:6px">
                <div>
                  <span style="font-size:.9rem;font-weight:500">Enable Towo</span>
                  <div class="hint" style="margin-top:2px">Adds a <b>Towo</b> group to the sidebar. Runners and sessions are untouched either way — this only hides/shows the UI.</div>
                </div>
                <span id="towo-enable-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>
              <p class="tiny muted" style="margin:0 0 16px">Takes effect on next page load.</p>

              <hr class="dash">

              <div class="field" style="max-width:420px;margin-bottom:14px">
                <label>Where runners connect</label>
                <input id="towo-runner-connect-url" class="input" placeholder="auto-detected from this page, e.g. wss://jellystructure.example.com">
                <span class="hint">Leave blank to derive it from how you're viewing this page. Set it if that's wrong — e.g. behind a reverse proxy, or a different external hostname than the one runners should dial. Affects new enrollment commands only.</span>
              </div>

              <div class="field" style="max-width:420px;margin-bottom:14px">
                <label>GitHub token (for the npx enrollment command)</label>
                <input id="towo-github-token" class="input" type="password" placeholder="github_pat_…">
                <span class="hint">A fine-grained personal access token with <b>Contents: Read-only</b> on this repo — needed because this is a private repo and the npx command downloads towo-runner's source from it. Embedded in plain text into every new enrollment command; rotate it here if it leaks.</span>
              </div>

              <hr class="dash">

              <div style="display:flex;align-items:center;justify-content:space-between;margin:14px 0 8px">
                <div>
                  <span style="font-size:.9rem;font-weight:500">Quota watch</span>
                  <div class="hint" style="margin-top:2px">Continues only sessions you armed (per session — "Continue after reset"), at the reset time Claude reported.</div>
                </div>
                <span id="towo-quota-watch-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>
              <div class="field" style="max-width:220px;margin-bottom:14px">
                <label>Check interval</label>
                <select id="towo-quota-interval" class="input">
                  <option value="900000">15 minutes</option>
                  <option value="3600000">1 hour</option>
                  <option value="21600000">6 hours (default)</option>
                  <option value="43200000">12 hours</option>
                  <option value="86400000">24 hours</option>
                </select>
              </div>

              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:14px">
                <div>
                  <span style="font-size:.9rem;font-weight:500">Arm new sessions automatically</span>
                  <div class="hint" style="margin-top:2px">Off by default — a session only ever resumes itself because someone said so, per session.</div>
                </div>
                <span id="towo-arm-default-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
              </div>

              <div class="row" style="gap:14px;margin-bottom:14px;flex-wrap:wrap">
                <div class="field" style="max-width:220px;flex:1">
                  <label>Default permission profile</label>
                  <select id="towo-default-profile" class="input">
                    <option value="read_only">Read-only</option>
                    <option value="normal">Normal</option>
                    <option value="autonomous">Autonomous</option>
                    <option value="unrestricted">Unrestricted</option>
                  </select>
                  <span class="hint">A starting value — every session can override it.</span>
                </div>
                <div class="field" style="max-width:140px">
                  <label>Default turn cap</label>
                  <input id="towo-default-max-turns" class="input" type="number" min="1" step="1">
                </div>
              </div>

              <hr class="dash">

              <div style="font-size:.83rem;font-weight:500;margin:14px 0 8px;color:var(--ink-soft)">Notify me when…</div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">A tool needs approval</span>
                <span id="towo-notify-permission-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">A session pauses on quota</span>
                <span id="towo-notify-paused-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">It picks back up</span>
                <span id="towo-notify-resumed-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">A session errors</span>
                <span id="towo-notify-errored-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
                <span style="font-size:.9rem">A window drops low</span>
                <span id="towo-notify-low-quota-toggle" class="toggle" style="cursor:pointer"></span>
              </div>
              <div class="field" style="max-width:160px;margin-bottom:14px">
                <label>Low-quota threshold (% remaining)</label>
                <input id="towo-low-quota-threshold" class="input" type="number" min="1" max="99" step="1">
              </div>

              <hr class="dash">

              <div style="font-size:.83rem;font-weight:500;margin:14px 0 8px;color:var(--ink-soft)">Permission timeout</div>
              <div class="row" style="gap:14px;margin-bottom:14px;flex-wrap:wrap">
                <div class="field" style="max-width:160px">
                  <label>Auto-deny after (minutes)</label>
                  <input id="towo-permission-timeout-min" class="input" type="number" min="1" step="1">
                  <span class="hint">An ignored approval blocks the session forever otherwise.</span>
                </div>
                <div class="field" style="max-width:340px;flex:1">
                  <label>Reason shown to Claude</label>
                  <input id="towo-permission-timeout-reason" class="input">
                </div>
              </div>
              <p class="tiny muted" style="margin:0">Saved with the page's main <b>Save</b> button, above.</p>
            </div>

            <div class="card set-section" id="sect-advanced" data-tab="advanced">
              <h3 style="font-size:1rem;margin:0 0 10px">Advanced</h3>
              <div class="field">
                <label>TV image cache size (MB)</label>
                <input id="tv-image-cache" class="input" type="number" min="0" max="1000000" style="width:110px">
                <span class="hint">Disk cache for Ravilo TV artwork (the image proxy). <strong>0 = unlimited.</strong> Once over the cap the oldest images are evicted. Applies live — no restart.</span>
              </div>
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
    scope.launch { loadApiKeysCard(scope) }
    scope.launch { loadIngestCard() }

    wireSettingsTabs(container)
    showTab(query["tab"]) // Phase 55 — URL-addressable tab (?tab=…), defaults to connections
}

/** Left-nav button with a hidden failure badge (Phase: health-check bubbling). */
// Phase 54 — one Radarr/Sonarr box (enable toggle + URL + masked key + test chip + import + rescan).
private fun arrBoxHtml(kind: String, label: String, kindBadge: String, urlPlaceholder: String): String = """
              <div class="box" style="border:1px solid var(--line);border-radius:8px;padding:14px 16px;margin-bottom:14px">
                <div style="display:flex;align-items:center;justify-content:space-between">
                  <div style="display:flex;align-items:center;gap:8px">
                    <span style="font-size:.92rem;font-weight:600">$label</span>
                    <span class="badge" style="font-size:.68rem;background:var(--fill-2)">$kindBadge</span>
                  </div>
                  <span id="$kind-enabled-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
                </div>
                <div id="$kind-on" style="display:none;margin-top:12px">
                  <div class="field"><label>$label URL</label><input id="$kind-url" class="input" type="url" placeholder="$urlPlaceholder" style="width:100%"></div>
                  <div class="field"><label>API key <span id="$kind-key-badge" style="display:none;margin-left:8px"></span></label>
                    <div style="display:flex;gap:8px;align-items:center">
                      <input id="$kind-key" class="input" type="password" style="flex:1;min-width:0">
                      <button id="$kind-key-reveal" type="button" class="btn sm ghost" style="flex:none">Show</button>
                    </div>
                    <span class="hint">$label → Settings → General → API Key. The saved key is pre-filled — clear it to remove.</span></div>
                  <div style="display:flex;align-items:center;gap:8px;margin-bottom:10px">
                    <button id="$kind-test-btn" class="btn sm ghost">Test connection</button>
                    <span id="chk-$kind" class="tiny muted"></span>
                  </div>
                  <div style="margin-bottom:10px">
                    <button id="$kind-import-btn" class="btn sm ghost arr-import">Import root folders → libraries</button>
                    <div id="$kind-roots" class="tiny muted" style="margin-top:6px"></div>
                  </div>
                  <div style="display:flex;align-items:center;justify-content:space-between">
                    <div><span style="font-size:.9rem">Rescan in $label after writes</span><div class="hint" style="margin-top:2px">Refreshes $label's MediaInfo after a track edit (best-effort)</div></div>
                    <span id="$kind-rescan-toggle" class="toggle" style="cursor:pointer;flex-shrink:0;margin-left:12px"></span>
                  </div>
                </div>
              </div>
"""

private fun settingsNavItemHtml(tab: String, label: String): String =
    """<button data-tab="$tab" class="settings-nav-item" style="display:flex;align-items:center;gap:6px;background:none;border:none;text-align:left;padding:5px 8px;border-radius:5px;font-size:.85rem;cursor:pointer;color:var(--ink-soft);"><span style="flex:1">$label</span><span class="nav-badge" data-badge="$tab" style="display:none;background:var(--bad);color:#fff;border-radius:99px;padding:0 6px;font-size:.7rem;font-weight:600;flex-shrink:0"></span></button>"""

// Maps a HealthCheck.name to the settings section that owns it, for failure bubbling.
private val HEALTH_CHECK_SECTION = mapOf(
    "Jellyfin" to "sect-connections",
    "TMDB API key" to "sect-connections",
    "Disk space" to "sect-scanning",
    "mkvpropedit" to "sect-scanning",
    "ffprobe" to "sect-scanning",
    "Radarr" to "sect-arr",
    "Sonarr" to "sect-arr",
)
private fun healthCheckSection(checkName: String): String? =
    HEALTH_CHECK_SECTION[checkName]
        ?: if (checkName.startsWith("Jellyfin library:")) "sect-libraries" else null

// Phase 55 — which tab owns each settings section (for tab show/hide + health-badge bubbling).
private val SECTION_TAB = mapOf(
    "sect-connections" to "connections",
    "sect-libraries" to "libraries",
    "sect-scanning" to "libraries",
    "sect-metadata" to "metadata",
    "sect-crossseed" to "downloads",
    "sect-arr" to "downloads",
    "sect-seerr" to "downloads",
    "sect-bazarr" to "downloads",
    "sect-request-lang" to "downloads",
    "sect-notifications" to "notifications",
    "sect-advanced" to "advanced",
)
private val SETTINGS_TABS = listOf("connections", "libraries", "metadata", "downloads", "notifications", "towo", "advanced")

private fun applyHealthFailures(failsBySection: Map<String, Int>) {
    // Phase 55 — bubble section failures up to their owning tab.
    val failsByTab = mutableMapOf<String, Int>()
    for ((sect, n) in failsBySection) {
        if (n <= 0) continue
        val tab = SECTION_TAB[sect] ?: continue
        failsByTab[tab] = (failsByTab[tab] ?: 0) + n
    }
    // Per-tab nav badges
    document.querySelectorAll(".nav-badge").let { nodes ->
        for (i in 0 until nodes.length) {
            val badge = nodes.item(i) as? HTMLElement ?: continue
            val tab = badge.getAttribute("data-badge") ?: continue
            val n = failsByTab[tab] ?: 0
            if (n > 0) { badge.textContent = n.toString(); badge.style.display = "inline-block" }
            else badge.style.display = "none"
        }
    }
    // Top "Test connections" button danger state + count
    val total = failsBySection.values.sum()
    val btn = document.getElementById("test-connections") as? HTMLElement
    val label = document.getElementById("test-conn-label") as? HTMLElement
    val countEl = document.getElementById("test-conn-count") as? HTMLElement
    if (total > 0) {
        label?.textContent = "Test connections — issues found"
        countEl?.let { it.textContent = total.toString(); it.style.display = "inline-block" }
        btn?.style?.setProperty("border-color", "var(--bad)")
        btn?.style?.setProperty("color", "var(--bad)")
    } else {
        label?.textContent = "Test connections"
        countEl?.style?.display = "none"
        btn?.style?.removeProperty("border-color")
        btn?.style?.removeProperty("color")
    }
    // Phase 55 — switch to the first failing tab (was: smooth-scroll to the section).
    if (total > 0) {
        val firstSect = failsBySection.entries.firstOrNull { it.value > 0 }?.key
        firstSect?.let { SECTION_TAB[it] }?.let { showTab(it) }
    }
}

private var overwriteNfo = false
private var fetchImages = true
private var tellJellyfin = true
private var scanWorkers = 1
private var segmentWorkers = 2
private var scanThreads = 4
private var scanEpisodeCap = 0
private var tvImageCacheMb = 2048
private var effectiveScanThreads = 4
private var libraryMappings: MutableList<LibraryMapping> = mutableListOf()
private var qbEnabled = false
private var qbNoAuth = false
private var qbPathMappings: MutableList<QBittorrentPathMapping> = mutableListOf()
private var radarrEnabled = false
private var radarrRescan = true
private var sonarrEnabled = false
private var sonarrRescan = true
private var seerrEnabled = false
private var bazarrEnabled = false
private var bazarrAutoSearch = false
private var bazarrShowHistory = true
// Phase 139 — request-language intents (rebuilt into the DOM on every change; simple list, no
// drag-reorder — order doesn't affect behaviour here the way it does for content rows/feeds).
private val requestLanguageIntents: MutableList<RequestLanguageIntent> = mutableListOf()
private var requestLanguageKidsDefault: String? = null
private var notifScanDone = true
private var notifNoMatch = false
private var notifWriteFailed = true
private var notifDrift = false
private var notifCrash = true
private var pipelineEnabled = false
private var pipelineFreq = "daily"
private val pipelineSteps: MutableList<PipelineStep> = mutableListOf()
private var pipeDragFrom = -1
private var pipeInsertAt = -1
private var settingsScope: CoroutineScope? = null

// Phase 106 — age-rating region cascade (ordered ISO-3166-1 codes; empty = feature off).
private val ageRatingCascade: MutableList<String> = mutableListOf()
private var rcDragFrom: String? = null

private fun populateForm(response: ConfigResponse) {
    val config = response.config
    effectiveScanThreads = response.effectiveScanThreads

    setInputValue("jellyfin-url", config.apiKeys.jellyfinUrl)
    // Security fix (2026-08-02 review, finding H3) — GET /config now masks secrets with a "##KEEP##"
    // sentinel rather than sending the real value, so this must NOT be pushed into the input (it'd
    // show the literal placeholder text instead of the token). Leave the field blank — matching the
    // qb-password/radarr-key/sonarr-key/seerr-key pattern below — and show a "stored" indicator
    // instead; readForm() below already treats a blank field as "keep the stored value".
    setStoredBadgeById("jf-token-badge", config.apiKeys.jellyfinToken.isNotBlank())
    setArrKeyBadge("tmdb", config.apiKeys.tmdbV3Key.isNotBlank())
    setInputValue("fallback-language", config.languageRules.fallbackLanguage)

    ageRatingCascade.clear()
    ageRatingCascade.addAll(config.metadata.ageRatingCascade)
    renderAgeRatingCascade()

    // Phase 136 — Jellyseerr/Overseerr (API key left blank; "(unchanged)" placeholder, ##KEEP## on save)
    val seerr = config.seerr
    seerrEnabled = seerr?.enabled ?: false
    updateToggle("seerr-enabled-toggle", seerrEnabled)
    // Security fix (H3): the apiKey value is now the "##KEEP##" mask sentinel, not the real key — do
    // not push it into the input (see the jellyfin-token/tmdb-key comment above for why).
    if (seerr != null) { setInputValue("seerr-url", seerr.url) }
    setArrKeyBadge("seerr", (seerr?.apiKey ?: "").isNotBlank())
    (document.getElementById("seerr-on") as? HTMLElement)?.style?.display = if (seerrEnabled) "block" else "none"

    // Phase 157 — Bazarr (same "##KEEP##"-mask + blank-key pattern as Seerr above).
    val bazarr = config.bazarr
    bazarrEnabled = bazarr?.enabled ?: false
    bazarrAutoSearch = bazarr?.autoSearchOnAdd ?: false
    bazarrShowHistory = bazarr?.showHistoryOnTitle ?: true
    updateToggle("bazarr-enabled-toggle", bazarrEnabled)
    updateToggle("bazarr-autosearch-toggle", bazarrAutoSearch)
    updateToggle("bazarr-history-toggle", bazarrShowHistory)
    if (bazarr != null) { setInputValue("bazarr-url", bazarr.url) }
    setArrKeyBadge("bazarr", (bazarr?.apiKey ?: "").isNotBlank())
    (document.getElementById("bazarr-on") as? HTMLElement)?.style?.display = if (bazarrEnabled) "block" else "none"

    // Phase 139 — request-language intents (Original/Dansk-Nordic etc.)
    requestLanguageIntents.clear()
    requestLanguageIntents.addAll(config.requestLanguage.intents)
    requestLanguageKidsDefault = config.requestLanguage.kidsDefault
    renderRequestLanguageRows()

    overwriteNfo = config.behavior.overwriteNfo
    fetchImages = config.behavior.fetchImages
    tellJellyfin = config.behavior.tellJellyfin
    scanWorkers = config.behavior.scanWorkers
    segmentWorkers = config.behavior.segmentWorkers
    scanThreads = config.behavior.scanThreads
    scanEpisodeCap = config.behavior.scanEpisodeCap
    tvImageCacheMb = config.behavior.tvImageCacheMb
    updateToggle("overwrite-nfo-toggle", overwriteNfo)
    updateToggle("fetch-images-toggle", fetchImages)
    updateToggle("tell-jellyfin-toggle", tellJellyfin)
    setInputValue("scan-workers", scanWorkers.toString())
    setInputValue("segment-workers", segmentWorkers.toString())
    setInputValue("scan-threads", scanThreads.toString())
    setInputValue("scan-episode-cap", scanEpisodeCap.toString())
    setInputValue("tv-image-cache", tvImageCacheMb.toString())
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
        setInputValue("qb-cache-ttl", if (qb.seedingCacheTtl == 600L) "" else qb.seedingCacheTtl.toString())
    }
    val qbFields = document.getElementById("qb-fields") as? HTMLElement
    qbFields?.style?.display = if (qbEnabled) "block" else "none"
    val qbCredFields = document.getElementById("qb-credential-fields") as? HTMLElement
    qbCredFields?.style?.display = if (qbNoAuth) "none" else "block"
    renderQbPathMappings()

    // Phase 54 — Radarr / Sonarr (API key left blank; "(unchanged)" placeholder, ##KEEP## on save)
    val radarr = config.radarr
    radarrEnabled = radarr?.enabled ?: false
    radarrRescan = radarr?.rescanAfterWrite ?: true
    updateToggle("radarr-enabled-toggle", radarrEnabled)
    updateToggle("radarr-rescan-toggle", radarrRescan)
    // Security fix (H3): apiKey is now the "##KEEP##" mask sentinel — do not push it into the input.
    if (radarr != null) { setInputValue("radarr-url", radarr.url) }
    setArrKeyBadge("radarr", (radarr?.apiKey ?: "").isNotBlank())
    (document.getElementById("radarr-on") as? HTMLElement)?.style?.display = if (radarrEnabled) "block" else "none"
    val sonarr = config.sonarr
    sonarrEnabled = sonarr?.enabled ?: false
    sonarrRescan = sonarr?.rescanAfterWrite ?: true
    updateToggle("sonarr-enabled-toggle", sonarrEnabled)
    updateToggle("sonarr-rescan-toggle", sonarrRescan)
    // Security fix (H3): apiKey is now the "##KEEP##" mask sentinel — do not push it into the input.
    if (sonarr != null) { setInputValue("sonarr-url", sonarr.url) }
    setArrKeyBadge("sonarr", (sonarr?.apiKey ?: "").isNotBlank())
    (document.getElementById("sonarr-on") as? HTMLElement)?.style?.display = if (sonarrEnabled) "block" else "none"

    notifScanDone = config.behavior.notifyOnScanDone
    notifNoMatch = config.behavior.notifyOnNoMatch
    notifWriteFailed = config.behavior.notifyOnWriteFailed
    notifDrift = config.behavior.notifyOnDrift
    notifCrash = config.behavior.notifyOnCrash
    setInputValue("notif-webhook", config.behavior.notificationsWebhook)
    updateToggle("notif-scan-done-toggle", notifScanDone)
    updateToggle("notif-no-match-toggle", notifNoMatch)
    updateToggle("notif-write-failed-toggle", notifWriteFailed)
    updateToggle("notif-drift-toggle", notifDrift)
    updateToggle("notif-crash-toggle", notifCrash)

    // Phase 91 — pipeline
    pipelineEnabled = config.scanSchedule.isNotBlank() || config.scan.pipeline.isNotEmpty()
    updateToggle("pipe-enable", pipelineEnabled)
    (document.getElementById("pipe-fields") as? HTMLElement)?.style?.display = if (pipelineEnabled) "" else "none"
    pipelineSteps.clear()
    if (config.scan.pipeline.isNotEmpty()) {
        pipelineSteps.addAll(config.scan.pipeline)
    } else {
        pipelineSteps.addAll(listOf(
            PipelineStep(step = "scan_files"),
            PipelineStep(step = "sync_jellyfin"),
            PipelineStep(step = "notify"),
        ))
    }
    when {
        config.scanSchedule.matches(Regex("0 (\\d+) \\* \\* 0")) -> {
            pipelineFreq = "weekly"
            val h = config.scanSchedule.split(" ")[1].toIntOrNull() ?: 3
            setInputValue("pipe-at", "${h.toString().padStart(2, '0')}:00")
        }
        config.scanSchedule.matches(Regex("0 (\\d+) \\* \\* \\*")) -> {
            pipelineFreq = "daily"
            val h = config.scanSchedule.split(" ")[1].toIntOrNull() ?: 3
            setInputValue("pipe-at", "${h.toString().padStart(2, '0')}:00")
        }
        config.scanSchedule.matches(Regex("(\\d+) \\*/(\\d+) \\* \\* \\*")) -> {
            pipelineFreq = "everyN"
            val parts = config.scanSchedule.split(" ")
            setInputValue("pipe-everyn-min", (parts[0].toIntOrNull() ?: 0).toString())
            setInputValue("pipe-everyn-hours", (parts[1].removePrefix("*/").toIntOrNull() ?: 6).toString())
        }
        config.scanSchedule.isNotBlank() -> {
            // Phase 166 bug fix: a schedule this page didn't recognise used to be silently rewritten to
            // "0 3 * * *" on the next Save (every unmatched case fell through to the daily preset). Round-
            // trip it verbatim as Custom instead — a hand-edited config.toml schedule must never be
            // destroyed by an unrelated visit to Settings.
            pipelineFreq = "custom"
            setInputValue("pipe-cron", config.scanSchedule)
        }
        else -> pipelineFreq = "daily"
    }
    renderPipelineFreq()
    renderPipeline()

    refreshTomlPreview(config)
}

private suspend fun populateToolChips() {
    val chipEl = document.getElementById("tool-status-chip") as? HTMLElement ?: return
    val report = ConfigApi.getHealthFull() ?: return
    val tools = listOf("ffmpeg", "ffprobe", "mkvpropedit")
    val chips = tools.joinToString("") { name ->
        val check = report.checks.find { it.name == name }
        val cls = if (check?.ok == true) "ok" else "bad"
        """<span class="badge $cls" style="font-size:.72rem;margin-right:4px">$name</span>"""
    }
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
    document.getElementById("tell-jellyfin-toggle")?.addEventListener("click") {
        tellJellyfin = !tellJellyfin
        updateToggle("tell-jellyfin-toggle", tellJellyfin)
        refreshTomlPreview(readForm())
    }
    wireAgeRatingCascade()

    listOf("jellyfin-url", "jellyfin-token", "tmdb-key", "fallback-language").forEach { id ->
        document.getElementById(id)?.addEventListener("input") {
            refreshTomlPreview(readForm())
        }
    }

    document.getElementById("scan-workers")?.addEventListener("input") {
        scanWorkers = (document.getElementById("scan-workers") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceIn(1, 100) ?: 1
        refreshTomlPreview(readForm())
    }
    document.getElementById("segment-workers")?.addEventListener("input") {
        segmentWorkers = (document.getElementById("segment-workers") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceIn(1, 8) ?: 2
        refreshTomlPreview(readForm())
    }
    document.getElementById("scan-threads")?.addEventListener("input") {
        scanThreads = (document.getElementById("scan-threads") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceIn(1, 100) ?: 4
        updateRestartBanner()
        refreshTomlPreview(readForm())
    }
    document.getElementById("scan-episode-cap")?.addEventListener("input") {
        scanEpisodeCap = (document.getElementById("scan-episode-cap") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        refreshTomlPreview(readForm())
    }
    document.getElementById("tv-image-cache")?.addEventListener("input") {
        tvImageCacheMb = (document.getElementById("tv-image-cache") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceAtLeast(0) ?: 2048
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
    listOf("qb-url", "qb-username", "qb-password", "qb-cache-ttl").forEach { id ->
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

    // Phase 162 (Towo) — independent of config.toml, same localStorage-only/immediate-apply pattern
    // as js-theme; app-shell.js reads this key to decide whether to render the sidebar Towo group.
    updateToggle("towo-enable-toggle", window.localStorage.getItem("js-towo") == "1")
    document.getElementById("towo-enable-toggle")?.addEventListener("click") {
        val newState = window.localStorage.getItem("js-towo") != "1"
        window.localStorage.setItem("js-towo", if (newState) "1" else "0")
        updateToggle("towo-enable-toggle", newState)
    }
    wireTowoSettings(scope)

    wireArr(scope, "radarr")
    wireArr(scope, "sonarr")
    wireSeerr(scope)
    wireBazarr(scope)
    wireRequestLanguage(scope)

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
    document.getElementById("notif-crash-toggle")?.addEventListener("click") {
        notifCrash = !notifCrash
        updateToggle("notif-crash-toggle", notifCrash)
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

    wirePipelineBuilder(scope)
    refreshNextRun()   // 93e: show the real next scheduled run on load

    document.getElementById("save-settings")?.addEventListener("click") {
        scope.launch {
            val config = readForm()
            val result = ConfigApi.save(config)
            // Towo settings live in their own backend table (not config.toml) but ride this same
            // button rather than getting a second one — see towoSettingsFromForm's doc comment.
            val towoOk = if (result.ok) TowoApi.updateSettings(towoSettingsFromForm()) else true
            val ok = result.ok && towoOk
            // Phase 166 (FR-166-4) — the save-path backstop: surface the backend's own rejection reason
            // rather than a bare "Save failed" if an invalid schedule somehow reached PUT /api/config
            // (the live check above should already have caught it and disabled this button, but a stale
            // client-side check must never leave the admin without an explanation).
            showSettingsMsg(if (ok) "Saved." else (result.error ?: "Save failed."), ok)
            if (result.field == "scan_schedule") {
                pipeCronValid = false
                (document.getElementById("pipe-cron-check") as? HTMLElement)?.innerHTML =
                    """<span class="badge bad">${(result.error ?: "Invalid schedule").esc()}</span>"""
                updateSaveEnabledForSchedule()
            }
            if (ok) { renderPathCheckInline(ConfigApi.pathCheck()); refreshNextRun() }   // 93e: reflect the saved schedule
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
            // Bubble failures up to the per-section nav badges + the top button (scroll to first failure)
            val failsBySection = mutableMapOf<String, Int>()
            report.checks.filter { !it.ok }.forEach { check ->
                val sect = healthCheckSection(check.name) ?: return@forEach
                failsBySection[sect] = (failsBySection[sect] ?: 0) + 1
            }
            applyHealthFailures(failsBySection)

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
            // Phase 54 — Radarr/Sonarr probes (present only when enabled) drive the key badge
            report.checks.find { it.name == "Radarr" }?.let { setArrKeyBadgeResult("radarr", it.ok) }
            report.checks.find { it.name == "Sonarr" }?.let { setArrKeyBadgeResult("sonarr", it.ok) }
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
        settingsScope ?: return@forEachIndexed
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
        // Security fix (H3): these inputs are now always blank unless the admin is actively typing a
        // new value (populateForm no longer pre-fills them with the real secret) — ifBlank keeps the
        // stored value via the same "##KEEP##" sentinel qbittorrent/radarr/sonarr/seerr already use.
        jellyfinToken = getInputValue("jellyfin-token").ifBlank { "##KEEP##" },
        tmdbV3Key = getInputValue("tmdb-key").ifBlank { "##KEEP##" },
    ),
    languageRules = LanguageRules(
        fallbackLanguage = getInputValue("fallback-language").ifEmpty { "en" },
    ),
    metadata = MetadataConfig(
        ageRatingCascade = ageRatingCascade.toList(),
    ),
    behavior = Behavior(
        overwriteNfo = overwriteNfo,
        fetchImages = fetchImages,
        tellJellyfin = tellJellyfin,
        scanWorkers = scanWorkers,
        segmentWorkers = segmentWorkers,
        scanThreads = scanThreads,
        scanEpisodeCap = scanEpisodeCap,
        tvImageCacheMb = tvImageCacheMb,
        scanIntervalHours = 0,
        notificationsWebhook = getInputValue("notif-webhook"),
        notifyOnScanDone = notifScanDone,
        notifyOnNoMatch = notifNoMatch,
        notifyOnWriteFailed = notifWriteFailed,
        notifyOnDrift = notifDrift,
        notifyOnCrash = notifCrash,
    ),
    libraries = libraryMappings.toList(),
    qbittorrent = if (qbEnabled) QBittorrentConfig(
        enabled = true,
        url = getInputValue("qb-url"),
        noAuth = qbNoAuth,
        username = if (qbNoAuth) "" else getInputValue("qb-username"),
        password = if (qbNoAuth) "" else getInputValue("qb-password").ifBlank { "##KEEP##" },
        pathMappings = qbPathMappings.toList(),
        seedingCacheTtl = getInputValue("qb-cache-ttl").toLongOrNull()?.coerceIn(30L, 86400L) ?: 600L,
    ) else null,
    radarr = if (radarrEnabled) ArrConfig(
        enabled = true,
        url = getInputValue("radarr-url"),
        apiKey = getInputValue("radarr-key").ifBlank { "##KEEP##" },
        rescanAfterWrite = radarrRescan,
    ) else null,
    sonarr = if (sonarrEnabled) ArrConfig(
        enabled = true,
        url = getInputValue("sonarr-url"),
        apiKey = getInputValue("sonarr-key").ifBlank { "##KEEP##" },
        rescanAfterWrite = sonarrRescan,
    ) else null,
    seerr = if (seerrEnabled) SeerrConfig(
        enabled = true,
        url = getInputValue("seerr-url"),
        apiKey = getInputValue("seerr-key").ifBlank { "##KEEP##" },
    ) else null,
    bazarr = if (bazarrEnabled) BazarrConfig(
        enabled = true,
        url = getInputValue("bazarr-url"),
        apiKey = getInputValue("bazarr-key").ifBlank { "##KEEP##" },
        autoSearchOnAdd = bazarrAutoSearch,
        showHistoryOnTitle = bazarrShowHistory,
    ) else null,
    scanSchedule = if (pipelineEnabled) computePipeCron() else "",
    scan = ScanConfig(pipeline = if (pipelineEnabled) pipelineSteps.toList() else emptyList()),
    requestLanguage = RequestLanguageConfig(intents = requestLanguageIntents.toList(), kidsDefault = requestLanguageKidsDefault?.takeIf { it.isNotBlank() }),
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
    appendLine("[metadata]")
    appendLine("age_rating_cascade = [${c.metadata.ageRatingCascade.joinToString(", ") { "\"$it\"" }}]")
    appendLine()
    appendLine("[behavior]")
    appendLine("overwrite_nfo = ${c.behavior.overwriteNfo}")
    appendLine("fetch_images = ${c.behavior.fetchImages}")
    appendLine("tell_jellyfin = ${c.behavior.tellJellyfin}")
    appendLine("scan_workers = ${c.behavior.scanWorkers}")
    appendLine("segment_workers = ${c.behavior.segmentWorkers}")
    appendLine("scan_threads = ${c.behavior.scanThreads}")
    appendLine("tv_image_cache_mb = ${c.behavior.tvImageCacheMb}")
    if (c.scanSchedule.isNotBlank()) {
        appendLine("scan_schedule = \"${c.scanSchedule}\"")
    }
    for (step in c.scan.pipeline) {
        appendLine()
        appendLine("[[scan.pipeline]]")
        appendLine("step = \"${step.step}\"")
        if (!step.enabled) appendLine("enabled = false")
        if (step.step == "scan_files") {
            appendLine("recheck_unchanged = ${step.recheckUnchanged}")
            if (step.recheckUnchanged) {
                appendLine("refresh_this_year = \"${step.refreshThisYear}\"")
                appendLine("refresh_1_5y = \"${step.refresh1To5y}\"")
                appendLine("refresh_older = \"${step.refreshOlder}\"")
            }
        }
        if (step.step in listOf("pull_tmdb", "fetch_artwork")) appendLine("scope = \"${step.scope}\"")
        if (step.step == "write_nfo") appendLine("overwrite = ${step.overwrite}")
        if (step.step == "notify") appendLine("on = \"${step.on}\"")
        if (step.step == "wait") appendLine("minutes = ${step.minutes}")
    }
    if (c.behavior.notificationsWebhook.isNotBlank()) {
        appendLine()
        appendLine("[notifications]")
        appendLine("""webhook = "${c.behavior.notificationsWebhook}"""")
        appendLine("notify_on_scan_done = ${c.behavior.notifyOnScanDone}")
        appendLine("notify_on_no_match = ${c.behavior.notifyOnNoMatch}")
        appendLine("notify_on_write_failed = ${c.behavior.notifyOnWriteFailed}")
        appendLine("notify_on_drift = ${c.behavior.notifyOnDrift}")
        appendLine("notify_on_crash = ${c.behavior.notifyOnCrash}")
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
    c.radarr?.let { r ->
        appendLine()
        appendLine("[radarr]")
        appendLine("enabled = ${r.enabled}")
        appendLine("""url = "${r.url}"""")
        appendLine("""api_key = "***"""")
        appendLine("rescan_after_write = ${r.rescanAfterWrite}")
    }
    c.sonarr?.let { s ->
        appendLine()
        appendLine("[sonarr]")
        appendLine("enabled = ${s.enabled}")
        appendLine("""url = "${s.url}"""")
        appendLine("""api_key = "***"""")
        appendLine("rescan_after_write = ${s.rescanAfterWrite}")
    }
    c.seerr?.let { sr ->
        appendLine()
        appendLine("[seerr]")
        appendLine("enabled = ${sr.enabled}")
        appendLine("""url = "${sr.url}"""")
        appendLine("""api_key = "***"""")
    }
    c.bazarr?.let { bz ->
        appendLine()
        appendLine("[bazarr]")
        appendLine("enabled = ${bz.enabled}")
        appendLine("""url = "${bz.url}"""")
        appendLine("""api_key = "***"""")
        appendLine("auto_search_on_add = ${bz.autoSearchOnAdd}")
        appendLine("show_history_on_title = ${bz.showHistoryOnTitle}")
    }
}

// Phase 54 — wire one Radarr/Sonarr box (enable + rescan toggles, inputs, test, import root folders).
private fun wireArr(scope: CoroutineScope, kind: String) {
    document.getElementById("$kind-enabled-toggle")?.addEventListener("click") {
        val newVal = !(if (kind == "radarr") radarrEnabled else sonarrEnabled)
        if (kind == "radarr") radarrEnabled = newVal else sonarrEnabled = newVal
        updateToggle("$kind-enabled-toggle", newVal)
        (document.getElementById("$kind-on") as? HTMLElement)?.style?.display = if (newVal) "block" else "none"
        refreshTomlPreview(readForm())
    }
    document.getElementById("$kind-rescan-toggle")?.addEventListener("click") {
        val newVal = !(if (kind == "radarr") radarrRescan else sonarrRescan)
        if (kind == "radarr") radarrRescan = newVal else sonarrRescan = newVal
        updateToggle("$kind-rescan-toggle", newVal)
        refreshTomlPreview(readForm())
    }
    listOf("$kind-url", "$kind-key").forEach { id ->
        document.getElementById(id)?.addEventListener("input") { refreshTomlPreview(readForm()) }
    }
    document.getElementById("$kind-test-btn")?.addEventListener("click") {
        scope.launch {
            val el = document.getElementById("chk-$kind") as? HTMLElement ?: return@launch
            if (getInputValue("$kind-key").isBlank()) {
                el.innerHTML = """<span class="badge" style="font-size:.72rem">Key hidden — Save, then use “Test connections” (top) to verify the stored key</span>"""
                return@launch
            }
            el.textContent = "Testing…"
            val r = if (kind == "radarr") ConfigApi.testRadarr(getInputValue("$kind-url"), getInputValue("$kind-key"))
                    else ConfigApi.testSonarr(getInputValue("$kind-url"), getInputValue("$kind-key"))
            when {
                r == null -> el.innerHTML = """<span class="badge bad">Request failed</span>"""
                r.ok -> { el.innerHTML = """<span class="badge ok">${r.detail.esc()}</span>"""; renderArrRoots(kind, r.rootFolders ?: emptyList()) }
                else -> el.innerHTML = """<span class="badge bad">${r.detail.esc()}</span>"""
            }
        }
    }
    document.getElementById("$kind-import-btn")?.addEventListener("click") {
        scope.launch { importArrRoots(kind) }
    }
    document.getElementById("$kind-key-reveal")?.addEventListener("click") {
        val inp = document.getElementById("$kind-key") as? HTMLInputElement ?: return@addEventListener
        val btn = document.getElementById("$kind-key-reveal") as? HTMLElement
        if (inp.type == "password") { inp.type = "text"; btn?.textContent = "Hide" }
        else { inp.type = "password"; btn?.textContent = "Show" }
    }
}

// Phase 136 — wire the Jellyseerr/Overseerr card (enable toggle, inputs, test, key reveal). Simpler
// than wireArr: no rescan toggle, no root-folder import — Seerr isn't a library-mapping source.
private fun wireSeerr(scope: CoroutineScope) {
    document.getElementById("seerr-enabled-toggle")?.addEventListener("click") {
        seerrEnabled = !seerrEnabled
        updateToggle("seerr-enabled-toggle", seerrEnabled)
        (document.getElementById("seerr-on") as? HTMLElement)?.style?.display = if (seerrEnabled) "block" else "none"
        refreshTomlPreview(readForm())
    }
    listOf("seerr-url", "seerr-key").forEach { id ->
        document.getElementById(id)?.addEventListener("input") { refreshTomlPreview(readForm()) }
    }
    document.getElementById("seerr-test-btn")?.addEventListener("click") {
        scope.launch {
            val el = document.getElementById("chk-seerr") as? HTMLElement ?: return@launch
            if (getInputValue("seerr-key").isBlank()) {
                el.innerHTML = """<span class="badge" style="font-size:.72rem">Key hidden — Save, then use “Test connections” (top) to verify the stored key</span>"""
                return@launch
            }
            el.textContent = "Testing…"
            val r = ConfigApi.testSeerr(getInputValue("seerr-url"), getInputValue("seerr-key"))
            when {
                r == null -> el.innerHTML = """<span class="badge bad">Request failed</span>"""
                r.ok -> el.innerHTML = """<span class="badge ok">${r.detail.esc()}</span>"""
                else -> el.innerHTML = """<span class="badge bad">${r.detail.esc()}</span>"""
            }
        }
    }
    document.getElementById("seerr-key-reveal")?.addEventListener("click") {
        val inp = document.getElementById("seerr-key") as? HTMLInputElement ?: return@addEventListener
        val btn = document.getElementById("seerr-key-reveal") as? HTMLElement
        if (inp.type == "password") { inp.type = "text"; btn?.textContent = "Hide" }
        else { inp.type = "password"; btn?.textContent = "Show" }
    }
}

// Phase 157 — wire the Bazarr card. Same shape as wireSeerr (no rescan/root-folder — Bazarr isn't a
// library-mapping source either), plus the two plain-boolean service toggles.
private fun wireBazarr(scope: CoroutineScope) {
    document.getElementById("bazarr-enabled-toggle")?.addEventListener("click") {
        bazarrEnabled = !bazarrEnabled
        updateToggle("bazarr-enabled-toggle", bazarrEnabled)
        (document.getElementById("bazarr-on") as? HTMLElement)?.style?.display = if (bazarrEnabled) "block" else "none"
        refreshTomlPreview(readForm())
    }
    document.getElementById("bazarr-autosearch-toggle")?.addEventListener("click") {
        bazarrAutoSearch = !bazarrAutoSearch
        updateToggle("bazarr-autosearch-toggle", bazarrAutoSearch)
        refreshTomlPreview(readForm())
    }
    document.getElementById("bazarr-history-toggle")?.addEventListener("click") {
        bazarrShowHistory = !bazarrShowHistory
        updateToggle("bazarr-history-toggle", bazarrShowHistory)
        refreshTomlPreview(readForm())
    }
    listOf("bazarr-url", "bazarr-key").forEach { id ->
        document.getElementById(id)?.addEventListener("input") { refreshTomlPreview(readForm()) }
    }
    document.getElementById("bazarr-test-btn")?.addEventListener("click") {
        scope.launch {
            val el = document.getElementById("chk-bazarr") as? HTMLElement ?: return@launch
            if (getInputValue("bazarr-key").isBlank()) {
                el.innerHTML = """<span class="badge" style="font-size:.72rem">Key hidden — Save, then use “Test connections” (top) to verify the stored key</span>"""
                return@launch
            }
            el.textContent = "Testing…"
            val r = ConfigApi.testBazarr(getInputValue("bazarr-url"), getInputValue("bazarr-key"))
            when {
                r == null -> el.innerHTML = """<span class="badge bad">Request failed</span>"""
                r.ok -> el.innerHTML = """<span class="badge ok">${r.detail.esc()}</span>"""
                else -> el.innerHTML = """<span class="badge bad">${r.detail.esc()}</span>"""
            }
        }
    }
    document.getElementById("bazarr-key-reveal")?.addEventListener("click") {
        val inp = document.getElementById("bazarr-key") as? HTMLInputElement ?: return@addEventListener
        val btn = document.getElementById("bazarr-key-reveal") as? HTMLElement
        if (inp.type == "password") { inp.type = "text"; btn?.textContent = "Hide" }
        else { inp.type = "password"; btn?.textContent = "Show" }
    }
}

// Phase 139 — request-language intents (Original/Dansk-Nordic etc.). Plain list, no drag-reorder (order
// doesn't affect behaviour here); rebuilt into the DOM on every add/remove/default-change, with
// event-delegated input/change/click listeners on the container (matches the codebase's `data-act`
// delegation convention, e.g. TrackEditor.kt) so a rebuild never needs to re-bind per-row listeners.
private fun renderRequestLanguageRows() {
    val rows = document.getElementById("rl-rows") as? HTMLElement ?: return
    rows.innerHTML = requestLanguageIntents.mapIndexed { i, intent ->
        """<div class="row" data-i="$i" style="gap:8px;align-items:center;padding:8px 0;border-bottom:1px solid var(--line);flex-wrap:wrap">
          <input class="input rl-f" data-f="id" data-i="$i" value="${intent.id.esc()}" placeholder="id" style="width:90px" title="Sent by the app, e.g. nordic">
          <input class="input rl-f" data-f="label" data-i="$i" value="${intent.label.esc()}" placeholder="Label" style="width:110px" title="Shown on TV, e.g. Dansk">
          <input class="input rl-f" data-f="flag" data-i="$i" value="${intent.flag.esc()}" placeholder="cc" style="width:52px" title="ISO-639-1 language code for the flag, e.g. da — blank = original-language flag">
          <input class="input rl-f" data-f="base_profile" data-i="$i" value="${intent.baseProfile.esc()}" placeholder="Base profile" style="width:130px" title="An existing Radarr/Sonarr quality profile name to clone">
          <input class="input rl-f" data-f="match" data-i="$i" value="${intent.match.esc()}" placeholder="Release-title regex (blank = use base profile as-is)" style="flex:1;min-width:220px;font-family:'JetBrains Mono',monospace;font-size:.82rem">
          <label class="tiny row center" style="gap:4px;white-space:nowrap;cursor:pointer"><input type="checkbox" class="rl-f" data-f="strict" data-i="$i"${if (intent.strict) " checked" else ""}> strict</label>
          <label class="tiny row center" style="gap:4px;white-space:nowrap;cursor:pointer"><input type="radio" name="rl-default" class="rl-f" data-f="default" data-i="$i"${if (intent.default) " checked" else ""}> default</label>
          <button class="btn sm ghost" data-act="rl-remove" data-i="$i" type="button">✕</button>
        </div>"""
    }.joinToString("")

    val kidsSel = document.getElementById("rl-kids-default") as? HTMLSelectElement
    kidsSel?.innerHTML = """<option value="">(none — use each viewer's/global default)</option>""" +
        requestLanguageIntents.joinToString("") { """<option value="${it.id.esc()}"${if (it.id.isNotBlank() && it.id == requestLanguageKidsDefault) " selected" else ""}>${it.label.esc()}</option>""" }
}

private fun wireRequestLanguage(scope: CoroutineScope) {
    document.getElementById("rl-add")?.addEventListener("click") {
        requestLanguageIntents.add(RequestLanguageIntent(default = requestLanguageIntents.isEmpty()))
        renderRequestLanguageRows()
        refreshTomlPreview(readForm())
    }
    document.getElementById("rl-rows")?.addEventListener("input") { ev ->
        val target = ev.target as? HTMLInputElement ?: return@addEventListener
        if (!target.classList.contains("rl-f")) return@addEventListener
        val i = target.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
        val f = target.getAttribute("data-f") ?: return@addEventListener
        val cur = requestLanguageIntents.getOrNull(i) ?: return@addEventListener
        requestLanguageIntents[i] = when (f) {
            "id" -> cur.copy(id = target.value)
            "label" -> cur.copy(label = target.value)
            "flag" -> cur.copy(flag = target.value)
            "base_profile" -> cur.copy(baseProfile = target.value)
            "match" -> cur.copy(match = target.value)
            else -> cur
        }
        refreshTomlPreview(readForm())
    }
    document.getElementById("rl-rows")?.addEventListener("change") { ev ->
        val target = ev.target as? HTMLInputElement ?: return@addEventListener
        val i = target.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
        when (target.getAttribute("data-f")) {
            "strict" -> requestLanguageIntents[i] = requestLanguageIntents[i].copy(strict = target.checked)
            "default" -> {
                for (j in requestLanguageIntents.indices) requestLanguageIntents[j] = requestLanguageIntents[j].copy(default = j == i)
                renderRequestLanguageRows()
            }
        }
        refreshTomlPreview(readForm())
    }
    document.getElementById("rl-rows")?.addEventListener("click") { ev ->
        val btn = (ev.target as? HTMLElement)?.closest("[data-act='rl-remove']") as? HTMLElement ?: return@addEventListener
        val i = btn.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
        requestLanguageIntents.removeAt(i)
        renderRequestLanguageRows()
        refreshTomlPreview(readForm())
    }
    document.getElementById("rl-kids-default")?.addEventListener("change") {
        requestLanguageKidsDefault = (document.getElementById("rl-kids-default") as? HTMLSelectElement)?.value?.takeIf { it.isNotBlank() }
        refreshTomlPreview(readForm())
    }
    document.getElementById("rl-preview-btn")?.addEventListener("click") { scope.launch { runRequestLanguagePlan(preview = true) } }
    document.getElementById("rl-provision-btn")?.addEventListener("click") { scope.launch { runRequestLanguagePlan(preview = false) } }
}

private suspend fun runRequestLanguagePlan(preview: Boolean) {
    val chk = document.getElementById("chk-rl") as? HTMLElement
    val planEl = document.getElementById("rl-plan") as? HTMLElement
    chk?.textContent = if (preview) "Checking…" else "Applying…"
    val lines = if (preview) ConfigApi.previewRequestLanguage() else ConfigApi.provisionRequestLanguage()
    if (lines == null) {
        chk?.innerHTML = """<span class="badge bad">Request failed — check Radarr/Sonarr are connected above</span>"""
        return
    }
    chk?.innerHTML = if (preview) "" else """<span class="badge ok">Done — reload this page to see the stored profile ids</span>"""
    planEl?.style?.display = "block"
    planEl?.innerHTML = if (lines.isEmpty())
        """<span class="tiny muted">Nothing to provision — every intent's "match" is blank (Original-shaped needs no custom format).</span>"""
    else buildString {
        append("""<table class="wf-table"><tbody>""")
        for (l in lines) {
            val cls = when (l.action) { "create", "created" -> "ok"; "failed" -> "bad"; else -> "warn" }
            append("""<tr><td class="muted tiny">${l.arrKind.esc()}</td><td class="tiny">${l.kind.esc()}</td><td>${l.name.esc()}</td><td><span class="badge $cls" style="font-size:.7rem">${l.action.esc()}</span></td></tr>""")
        }
        append("</tbody></table>")
    }
}

// "stored ✓" on load when a key is on file (the field stays masked/blank); "valid/invalid" after a test.
private fun setArrKeyBadge(kind: String, stored: Boolean) = setStoredBadgeById("$kind-key-badge", stored)

// Security fix (H3) — same "stored ✓ / hidden" indicator as setArrKeyBadge, addressable by the
// element's literal id for badges that don't follow the "$kind-key-badge" naming (jf-token-badge).
private fun setStoredBadgeById(badgeId: String, stored: Boolean) {
    val b = document.getElementById(badgeId) as? HTMLElement ?: return
    if (stored) { b.style.display = "inline"; b.innerHTML = """<span class="badge ok" style="font-size:.72rem">stored ✓</span>""" }
    else { b.style.display = "none"; b.innerHTML = "" }
}

private fun setArrKeyBadgeResult(kind: String, ok: Boolean) {
    val b = document.getElementById("$kind-key-badge") as? HTMLElement ?: return
    b.style.display = "inline"
    b.innerHTML = if (ok) """<span class="badge ok" style="font-size:.72rem">valid ✓</span>"""
                  else """<span class="badge bad" style="font-size:.72rem">invalid ✗</span>"""
}

// Phase 106 — age-rating region cascade card (Settings ▸ Metadata). Mirrors design/app/settings.html's
// rc-list/rc-item/rc-add/rc-menu markup: reorder (▲▼ + drag), remove, add-from-catalog.
private fun renderAgeRatingCascade() {
    val listEl = document.getElementById("rc-list") as? HTMLElement ?: return
    listEl.innerHTML = ""
    if (ageRatingCascade.isEmpty()) {
        val empty = document.createElement("div") as HTMLElement
        empty.className = "tiny muted"
        empty.style.padding = "6px 2px"
        empty.textContent = "No regions added — age ratings won't be shown until you add at least one."
        listEl.appendChild(empty)
    }
    ageRatingCascade.forEachIndexed { i, cc ->
        val region = CertificationCatalog.BY_CODE[cc] ?: return@forEachIndexed
        val li = document.createElement("li") as HTMLElement
        li.className = "rc-item" + (if (i == 0) " top" else "")
        li.setAttribute("draggable", "true")

        val grip = document.createElement("span"); grip.className = "rc-grip"; grip.textContent = "⠿"
        val ord = document.createElement("span"); ord.className = "rc-ord"; ord.textContent = (i + 1).toString()
        val ccEl = document.createElement("span"); ccEl.className = "rc-cc"; ccEl.textContent = region.code
        val nameEl = document.createElement("span"); nameEl.className = "rc-name"; nameEl.textContent = region.name
        val sysEl = document.createElement("span"); sysEl.className = "rc-sys"; sysEl.textContent = region.system
        val scaleEl = document.createElement("span"); scaleEl.className = "rc-scale"
        scaleEl.innerHTML = region.scale.joinToString("") { "<span>${it}</span>" }
        li.appendChild(grip); li.appendChild(ord); li.appendChild(ccEl); li.appendChild(nameEl); li.appendChild(sysEl); li.appendChild(scaleEl)
        if (i == 0) {
            val tag = document.createElement("span"); tag.className = "rc-top-tag"; tag.textContent = "shown by default"
            li.appendChild(tag)
        }
        val actions = document.createElement("span"); actions.className = "rc-actions"
        val up = document.createElement("span") as HTMLElement
        up.className = "rc-btn up" + (if (i == 0) " dis" else ""); up.textContent = "▲"; up.title = "Move up"
        up.addEventListener("click") { if (i > 0) { ageRatingCascade.add(i - 1, ageRatingCascade.removeAt(i)); renderAgeRatingCascade() } }
        val down = document.createElement("span") as HTMLElement
        down.className = "rc-btn down" + (if (i == ageRatingCascade.size - 1) " dis" else ""); down.textContent = "▼"; down.title = "Move down"
        down.addEventListener("click") { if (i < ageRatingCascade.size - 1) { ageRatingCascade.add(i + 1, ageRatingCascade.removeAt(i)); renderAgeRatingCascade() } }
        val rm = document.createElement("span") as HTMLElement
        rm.className = "rc-btn rm"; rm.textContent = "✕"; rm.title = "Remove"
        rm.addEventListener("click") { ageRatingCascade.removeAt(i); renderAgeRatingCascade() }
        actions.appendChild(up); actions.appendChild(down); actions.appendChild(rm)
        li.appendChild(actions)

        li.addEventListener("dragstart") { rcDragFrom = cc; li.classList.add("dragging") }
        li.addEventListener("dragend") { rcDragFrom = null; renderAgeRatingCascade() }
        li.addEventListener("dragover") { e -> e.preventDefault(); if (rcDragFrom != null && rcDragFrom != cc) li.classList.add("drag-over") }
        li.addEventListener("dragleave") { li.classList.remove("drag-over") }
        li.addEventListener("drop") { e ->
            e.preventDefault()
            val from = rcDragFrom
            if (from != null && from != cc) {
                ageRatingCascade.remove(from)
                ageRatingCascade.add(ageRatingCascade.indexOf(cc), from)
                renderAgeRatingCascade()
            }
        }
        listEl.appendChild(li)
    }
    renderAgeRatingMenu()
}

private fun renderAgeRatingMenu() {
    val menuEl = document.getElementById("rc-menu") as? HTMLElement ?: return
    val avail = CertificationCatalog.REGIONS.filter { it.code !in ageRatingCascade }
    if (avail.isEmpty()) {
        menuEl.innerHTML = """<div class="tiny muted" style="padding:8px 9px;">All regions added.</div>"""
        return
    }
    menuEl.innerHTML = avail.joinToString("") { r ->
        """<div class="rc-mi" data-add="${r.code}"><span class="rc-cc">${r.code}</span><span class="rc-name">${r.name}</span><span class="rc-sys">${r.system}</span></div>"""
    }
    menuEl.querySelectorAll("[data-add]").let { els ->
        for (i in 0 until els.length) {
            val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val code = el.getAttribute("data-add") ?: return@addEventListener
                ageRatingCascade.add(code)
                (document.getElementById("rc-add-wrap") as? HTMLElement)?.classList?.remove("open")
                renderAgeRatingCascade()
            }
        }
    }
}

private fun wireAgeRatingCascade() {
    val addBtn = document.getElementById("rc-add") as? HTMLElement ?: return
    val addWrap = document.getElementById("rc-add-wrap") as? HTMLElement ?: return
    addBtn.addEventListener("click") { e ->
        e.stopPropagation()
        addWrap.classList.toggle("open")
    }
    document.addEventListener("click") { addWrap.classList.remove("open") }
}

private fun renderArrRoots(kind: String, roots: List<String>) {
    val el = document.getElementById("$kind-roots") as? HTMLElement ?: return
    el.innerHTML = if (roots.isEmpty()) "" else "Root folders: " + roots.joinToString(", ") { """<code>${it.esc()}</code>""" }
}

/** Fetch the *arr's root folders (saved creds) and pre-fill blank local paths of matching-type libraries. */
private suspend fun importArrRoots(kind: String) {
    val rootsEl = document.getElementById("$kind-roots") as? HTMLElement ?: return
    rootsEl.textContent = "Fetching root folders…"
    val roots = ConfigApi.getArrRootFolders(kind)
    if (roots.isEmpty()) { rootsEl.innerHTML = """<span class="badge bad">No root folders — save &amp; test the connection first</span>"""; return }
    renderArrRoots(kind, roots)
    val wantMovies = kind == "radarr"
    var filled = 0
    libraryMappings = libraryMappings.map { lib ->
        val isMovie = lib.collectionType.contains("movie", ignoreCase = true)
        val isTv = lib.collectionType.contains("tv", ignoreCase = true) || lib.collectionType.contains("show", ignoreCase = true)
        val matchesKind = if (wantMovies) isMovie else isTv
        if (matchesKind && lib.localPath.isBlank()) {
            val best = roots.firstOrNull { it.contains(lib.name, ignoreCase = true) } ?: roots.first()
            filled++
            lib.copy(localPath = best)
        } else lib
    }.toMutableList()
    if (libraryMappings.isNotEmpty()) renderLibraryList()
    refreshTomlPreview(readForm())
    if (filled > 0) rootsEl.innerHTML += """ <span class="badge ok">Pre-filled $filled librar${if (filled == 1) "y" else "ies"}</span>"""
}

private fun showSettingsMsg(msg: String, ok: Boolean) {
    val el = document.getElementById("settings-msg") as? HTMLElement ?: return
    el.style.display = "block"
    el.innerHTML = """<span class="badge ${if (ok) "ok" else "bad"}">$msg</span>"""
}

// Phase 111 — Settings ▸ Connections ▸ "API keys" card.
@Serializable
private data class ApiKeySummary(
    val id: String,
    val name: String,
    @SerialName("jellyfin_user_id") val jellyfinUserId: String,
    @SerialName("jellyfin_username") val jellyfinUsername: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_used_at") val lastUsedAt: Long? = null,
)

private var apiKeyUsers: List<dev.jellystructure.api.JellyfinUser> = emptyList()

private suspend fun loadApiKeysCard(scope: CoroutineScope) {
    apiKeyUsers = runCatching { dev.jellystructure.api.RaviloApi.getUsers() }.getOrDefault(emptyList())
    (document.getElementById("apikey-user") as? HTMLSelectElement)?.innerHTML =
        apiKeyUsers.joinToString("") { u -> """<option value="${u.id}" data-name="${u.displayName.esc()}">${u.displayName.esc()}</option>""" }
    refreshApiKeyList(scope)

    document.getElementById("apikey-create-btn")?.addEventListener("click") {
        scope.launch {
            val name = (document.getElementById("apikey-name") as? HTMLInputElement)?.value?.trim().orEmpty()
            val userSel = document.getElementById("apikey-user") as? HTMLSelectElement
            val userId = userSel?.value.orEmpty()
            val userName = apiKeyUsers.firstOrNull { it.id == userId }?.displayName.orEmpty()
            if (name.isBlank() || userId.isBlank()) return@launch
            // Bug fix: this used to hand-build the JSON body via string interpolation, stripping only
            // `"` — a name/username containing a backslash or control character would still produce
            // malformed JSON. A same-type Map<String, String> serializes safely (matches the
            // reach-url-save pattern elsewhere on this page).
            val body = mapOf("name" to name, "jellyfin_user_id" to userId, "jellyfin_username" to userName)
            val token = runCatching {
                val resp = httpClient.post("/api/settings/api-keys") { contentType(ContentType.Application.Json); setBody(body) }
                if (resp.status.value in 200..299) resp.body<Map<String, String>>()["token"] else null
            }.getOrNull()
            if (token != null) {
                (document.getElementById("apikey-created") as? HTMLElement)?.let {
                    it.style.display = "block"
                    it.innerHTML = """<b>Key created — copy it now, it won't be shown again:</b><div class="input mono" style="margin-top:6px;user-select:all;word-break:break-all;">${token.esc()}</div>"""
                }
                (document.getElementById("apikey-name") as? HTMLInputElement)?.value = ""
                refreshApiKeyList(scope)
            }
        }
    }
}

// Bug fix: this used to spin up its own throwaway kotlinx.coroutines.MainScope() on every call instead
// of reusing the page's own scope — every other async action on this page (and the rest of Settings.kt)
// launches on the scope handed to it by the tab's own load function, which gets cancelled when the admin
// navigates away. Now consistent with that.
private fun refreshApiKeyList(scope: CoroutineScope) {
    val listEl = document.getElementById("apikey-list") as? HTMLElement ?: return
    scope.launch {
        val keys = runCatching { httpClient.get("/api/settings/api-keys").body<List<ApiKeySummary>>() }.getOrDefault(emptyList())
        if (keys.isEmpty()) {
            listEl.innerHTML = """<span class="muted tiny">No API keys yet.</span>"""
            return@launch
        }
        listEl.innerHTML = keys.joinToString("") { k ->
            val lastUsed = k.lastUsedAt?.let { dev.jellystructure.formatStoredTs(it.toString()) } ?: "never"
            """<div class="row center" style="padding:8px 0;border-top:1px solid var(--line);">
                 <div style="flex:1;min-width:0;">
                   <b style="font-size:.88rem;">${k.name.esc()}</b>
                   <div class="tiny muted">acts as ${k.jellyfinUsername.esc()} · last used $lastUsed</div>
                 </div>
                 <button class="btn sm ghost apikey-revoke-btn" data-id="${k.id}">Revoke</button>
               </div>"""
        }
        listEl.querySelectorAll(".apikey-revoke-btn").let { nodes ->
            for (i in 0 until nodes.length) {
                val btn = nodes.item(i) as? HTMLElement ?: continue
                btn.addEventListener("click") {
                    val id = btn.getAttribute("data-id") ?: return@addEventListener
                    scope.launch {
                        runCatching { httpClient.delete("/api/settings/api-keys/$id") }
                        refreshApiKeyList(scope)
                    }
                }
            }
        }
    }
}

// Phase 114 — Settings ▸ Download tools ▸ "Realtime ingest" card.
// Phase 165 — mirrors dev.jellystructure.server.routes.JellyfinWebhookStatus/IngestStatus.
@Serializable
private data class JellyfinWebhookStatus(
    val reachable: Boolean,
    @SerialName("plugin_installed") val pluginInstalled: Boolean = false,
    @SerialName("plugin_version") val pluginVersion: String? = null,
    @SerialName("plugin_available_version") val pluginAvailableVersion: String? = null,
    @SerialName("destination_configured") val destinationConfigured: Boolean = false,
    @SerialName("destination_url") val destinationUrl: String? = null,
    @SerialName("restart_pending") val restartPending: Boolean = false,
)

@Serializable
private data class IngestStatus(
    @SerialName("webhook_secret") val webhookSecret: String,
    val realtime: Boolean,
    @SerialName("listener_connected") val listenerConnected: Boolean,
    // Fallback change-feed listener's own last event — see last_webhook_received_at below for the
    // primary Jellyfin-plugin path (2026-08-14 amendment: these used to be conflated on this page,
    // which let a dead primary path hide behind the fallback's own traffic).
    @SerialName("last_event_at") val lastEventAt: Long? = null,
    @SerialName("jellyfin_reach_url") val jellyfinReachUrl: String = "",
    val jellyfin: JellyfinWebhookStatus? = null,
    @SerialName("last_webhook_received_at") val lastWebhookReceivedAt: Long? = null,
)

@Serializable
private data class TestLiveResult(val delivered: Boolean, val message: String)

private suspend fun loadIngestCard() {
    val status = runCatching { httpClient.get("/api/settings/ingest-status").body<IngestStatus>() }.getOrNull() ?: return
    renderIngestCard(status)
    (document.getElementById("ingest-reach-url") as? HTMLInputElement)?.value = status.jellyfinReachUrl

    (document.getElementById("ingest-listener-detail") as? HTMLElement)?.textContent =
        if (status.realtime) "Fallback: also listening for library changes on Jellyfin's own change feed" +
            (if (status.listenerConnected) " (connected" else " (reconnecting") +
            (if (status.lastEventAt != null) ", has seen at least one event)" else ", no events seen yet)")
        else "Realtime ingest is off — new media only appears on the next scheduled scan"

    // 2026-08-14 amendment (FR-165-7) — the fallback line above only ever reflected the change-feed
    // listener; this is the primary Jellyfin-webhook path's own signal, shown separately so a dead
    // primary path can never again hide behind the fallback's own traffic.
    (document.getElementById("ingest-webhook-detail") as? HTMLElement)?.textContent =
        if (status.lastWebhookReceivedAt != null) "Jellyfin webhook: last received ${dev.jellystructure.formatStoredTs(status.lastWebhookReceivedAt.toString())}"
        else "Jellyfin webhook: nothing received yet"

    document.getElementById("ingest-reach-url-save")?.addEventListener("click") {
        settingsScope?.launch {
            val url = (document.getElementById("ingest-reach-url") as? HTMLInputElement)?.value?.trim() ?: return@launch
            val msgEl = document.getElementById("ingest-reach-url-msg") as? HTMLElement
            msgEl?.textContent = "Saving…"
            val ok = runCatching {
                httpClient.post("/api/settings/ingest/reach-url") {
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("url" to url))
                }.status == HttpStatusCode.NoContent
            }.getOrDefault(false)
            msgEl?.textContent = if (ok) "Saved ✓" else "Failed"
            if (ok) loadIngestCard()
        }
    }
}

/** Phase 165 (FR-165-4) — the five states, each with its own single next action. */
private fun renderIngestCard(status: IngestStatus) {
    val badge = document.getElementById("ingest-status-badge") as? HTMLElement
    val card = document.getElementById("ingest-jellyfin-card") as? HTMLElement ?: return
    val jf = status.jellyfin

    fun setBadge(text: String, cls: String) {
        badge?.textContent = text
        badge?.setAttribute("style", "font-size:.7rem;$cls")
    }

    when {
        jf == null || !jf.reachable -> {
            setBadge("Jellyfin unreachable", "background:var(--bad-soft,rgba(240,80,80,.15));color:var(--bad,#f05050)")
            card.innerHTML = """<p class="hint" style="margin:0">Connect Jellyfin first — see the <a href="#/settings?tab=connections">Connections</a> tab.</p>"""
        }
        !jf.pluginInstalled -> {
            setBadge("Plugin not installed", "background:var(--warn-soft,rgba(240,180,60,.15));color:var(--warn,#f0b43c)")
            card.innerHTML = """
                <p class="hint" style="margin:0 0 8px">The Jellyfin <b>Webhook</b> plugin isn't installed${jf.pluginAvailableVersion?.let { " (v$it available)" } ?: " — and isn't offered by this server's repositories; install it manually in Jellyfin's own Dashboard ▸ Plugins ▸ Catalog"}.</p>
                ${if (jf.pluginAvailableVersion != null) """<button id="ingest-install-plugin" class="btn sm primary">Install the Webhook plugin</button>""" else ""}
                <div class="tiny" id="ingest-setup-msg" style="margin-top:6px"></div>"""
            document.getElementById("ingest-install-plugin")?.addEventListener("click") { runIngestSetup() }
        }
        jf.restartPending -> {
            setBadge("Restart pending", "background:var(--warn-soft,rgba(240,180,60,.15));color:var(--warn,#f0b43c)")
            card.innerHTML = """
                <p class="hint" style="margin:0 0 8px">The plugin is installed (v${jf.pluginVersion?.esc() ?: "?"}) but Jellyfin hasn't loaded it yet — it needs a restart. Every stream on the household drops for a moment.</p>
                <button id="ingest-restart-jellyfin" class="btn sm bad">Restart Jellyfin now</button>
                <span class="tiny muted" style="margin-left:8px">or restart it yourself, whenever's convenient</span>
                <div class="tiny" id="ingest-setup-msg" style="margin-top:6px"></div>"""
            document.getElementById("ingest-restart-jellyfin")?.addEventListener("click") { runIngestRestart() }
        }
        !jf.destinationConfigured -> {
            setBadge("Not configured", "background:var(--warn-soft,rgba(240,180,60,.15));color:var(--warn,#f0b43c)")
            card.innerHTML = """
                <p class="hint" style="margin:0 0 8px">Plugin v${jf.pluginVersion?.esc() ?: "?"} is installed but has no destination pointed at this install yet.</p>
                <button id="ingest-setup-jellyfin" class="btn sm primary">Set up Jellyfin webhook</button>
                <div class="tiny" id="ingest-setup-msg" style="margin-top:6px"></div>"""
            document.getElementById("ingest-setup-jellyfin")?.addEventListener("click") { runIngestSetup() }
        }
        else -> {
            setBadge("Configured", "background:var(--ok-soft,rgba(45,212,154,.15));color:var(--ok,#2dd49a)")
            card.innerHTML = """
                <div class="row center" style="gap:8px;flex-wrap:wrap">
                  <span class="mono tiny">${jf.destinationUrl?.esc() ?: ""}</span>
                  <button class="btn sm ghost" id="ingest-test-url">Test delivery now</button>
                </div>
                <div class="tiny" id="ingest-setup-msg" style="margin-top:6px"></div>"""
            // 2026-08-14 amendment (FR-165-8) — replaces the old self-loop test (this server curling its
            // own destination URL, which only ever proved the URL was reachable FROM ITSELF and missed a
            // real live failure where Jellyfin's plugin never delivered despite a correct, reachable
            // config). This asks Jellyfin to send a real webhook through its own runtime and watches for
            // it to actually arrive — see the phase-165 spec amendment for the incident this replaced it.
            document.getElementById("ingest-test-url")?.addEventListener("click") {
                settingsScope?.launch {
                    val msgEl = document.getElementById("ingest-setup-msg") as? HTMLElement
                    msgEl?.textContent = "Asking Jellyfin to send a live webhook — this can take up to 10 seconds…"
                    val response = runCatching { httpClient.post("/api/settings/ingest/test-live") }.getOrNull()
                    val result = response?.let { runCatching { it.body<TestLiveResult>() }.getOrNull() }
                    if (result != null) {
                        msgEl?.textContent = (if (result.delivered) "✓ " else "✗ ") + result.message
                        if (result.delivered) loadIngestCard()
                    } else {
                        val err = response?.let { runCatching { it.body<Map<String, String>>() }.getOrNull() }
                        msgEl?.textContent = err?.get("error") ?: "Couldn't run the test"
                    }
                }
            }
        }
    }
}

private fun runIngestSetup() {
    settingsScope?.launch {
        val msgEl = document.getElementById("ingest-setup-msg") as? HTMLElement
        msgEl?.textContent = "Working…"
        val response = runCatching { httpClient.post("/api/settings/ingest/setup-jellyfin") }.getOrNull()
        if (response != null && response.status.value in 200..299) {
            msgEl?.textContent = "Done ✓"
            loadIngestCard()
        } else {
            val body = response?.let { runCatching { it.body<Map<String, String>>() }.getOrNull() }
            msgEl?.textContent = body?.get("error") ?: "Failed — set it up manually in Jellyfin's own Dashboard ▸ Plugins ▸ Webhook"
        }
    }
}

private fun runIngestRestart() {
    settingsScope?.launch {
        val msgEl = document.getElementById("ingest-setup-msg") as? HTMLElement
        msgEl?.textContent = "Restarting…"
        val ok = runCatching { httpClient.post("/api/settings/ingest/restart-jellyfin").status.value in 200..299 }.getOrDefault(false)
        msgEl?.textContent = if (ok) "Restart requested — checking again in a moment…" else "Failed — restart it yourself"
        if (ok) { kotlinx.coroutines.delay(15_000); loadIngestCard() }
    }
}

private fun updateToggle(id: String, on: Boolean) {
    val el = document.getElementById(id) as? HTMLElement ?: return
    if (on) el.className = "toggle on" else el.className = "toggle"
}

/** Phase 162 (Towo), spec §A — backend-owned settings (towo_settings table), a separate concern
 *  from config.toml: TowoAutoContinueScheduler is a server-side loop with no browser to read
 *  localStorage from, so this section gets its own load + its own "Save Towo settings" button
 *  rather than riding the page's main config-save flow. */
private fun towoToggleOn(id: String): Boolean = (document.getElementById(id) as? HTMLElement)?.className?.contains("on") == true

/** Reads the Towo tab's fields into a [TowoSettings] — shared by [wireTowoSettings]'s toggle wiring
 *  and the page's single global "Save" button (Towo settings live in their own backend table, not
 *  `config.toml`, but ride the same one-button save UX as everything else on this page rather than
 *  getting a second save button). */
private fun towoSettingsFromForm(): TowoSettings = TowoSettings(
    quotaWatchEnabled = towoToggleOn("towo-quota-watch-toggle"),
    quotaWatchIntervalMs = (document.getElementById("towo-quota-interval") as? HTMLSelectElement)?.value?.toLongOrNull() ?: (6L * 60 * 60 * 1000),
    armNewSessionsDefault = towoToggleOn("towo-arm-default-toggle"),
    defaultPermissionProfile = (document.getElementById("towo-default-profile") as? HTMLSelectElement)?.value ?: "normal",
    defaultMaxTurns = (document.getElementById("towo-default-max-turns") as? HTMLInputElement)?.value?.toLongOrNull() ?: 40,
    notifyPermissionRequested = towoToggleOn("towo-notify-permission-toggle"),
    notifyPausedQuota = towoToggleOn("towo-notify-paused-toggle"),
    notifyResumed = towoToggleOn("towo-notify-resumed-toggle"),
    notifyErrored = towoToggleOn("towo-notify-errored-toggle"),
    notifyLowQuota = towoToggleOn("towo-notify-low-quota-toggle"),
    lowQuotaThresholdPct = (document.getElementById("towo-low-quota-threshold") as? HTMLInputElement)?.value?.toLongOrNull()?.coerceIn(1, 99) ?: 20,
    permissionTimeoutMs = (((document.getElementById("towo-permission-timeout-min") as? HTMLInputElement)?.value?.toLongOrNull()?.coerceAtLeast(1)) ?: 30) * 60_000,
    permissionTimeoutReason = (document.getElementById("towo-permission-timeout-reason") as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
        ?: "No response within the timeout — auto-denied by Towo.",
    runnerConnectUrl = (document.getElementById("towo-runner-connect-url") as? HTMLInputElement)?.value?.trim().orEmpty(),
    githubToken = (document.getElementById("towo-github-token") as? HTMLInputElement)?.value?.trim().orEmpty(),
)

private fun wireTowoSettings(scope: CoroutineScope) {
    scope.launch {
        val settings = TowoApi.getSettings()
        updateToggle("towo-quota-watch-toggle", settings.quotaWatchEnabled)
        updateToggle("towo-arm-default-toggle", settings.armNewSessionsDefault)
        updateToggle("towo-notify-permission-toggle", settings.notifyPermissionRequested)
        updateToggle("towo-notify-paused-toggle", settings.notifyPausedQuota)
        updateToggle("towo-notify-resumed-toggle", settings.notifyResumed)
        updateToggle("towo-notify-errored-toggle", settings.notifyErrored)
        updateToggle("towo-notify-low-quota-toggle", settings.notifyLowQuota)
        (document.getElementById("towo-quota-interval") as? HTMLSelectElement)?.value = settings.quotaWatchIntervalMs.toString()
        (document.getElementById("towo-default-profile") as? HTMLSelectElement)?.value = settings.defaultPermissionProfile
        setInputValue("towo-default-max-turns", settings.defaultMaxTurns.toString())
        setInputValue("towo-low-quota-threshold", settings.lowQuotaThresholdPct.toString())
        setInputValue("towo-permission-timeout-min", (settings.permissionTimeoutMs / 60_000).toString())
        setInputValue("towo-permission-timeout-reason", settings.permissionTimeoutReason)
        setInputValue("towo-runner-connect-url", settings.runnerConnectUrl)
        setInputValue("towo-github-token", settings.githubToken)
    }

    for (id in listOf(
        "towo-quota-watch-toggle", "towo-arm-default-toggle", "towo-notify-permission-toggle",
        "towo-notify-paused-toggle", "towo-notify-resumed-toggle", "towo-notify-errored-toggle",
        "towo-notify-low-quota-toggle",
    )) {
        document.getElementById(id)?.addEventListener("click") { updateToggle(id, !towoToggleOn(id)) }
    }
}

// Phase 107 — country-chart multi-select for Discover/Top 10 (design/app/settings.html .reg-grid).


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

// Phase 55 — one panel visible at a time, selected by the rail and addressable in the URL.
private fun showTab(name: String?) {
    val tab = name?.takeIf { it in SETTINGS_TABS } ?: "connections"
    document.querySelectorAll(".settings-nav-item[data-tab]").let { nodes ->
        for (i in 0 until nodes.length) {
            val b = nodes.item(i) as? HTMLElement ?: continue
            val active = b.getAttribute("data-tab") == tab
            b.style.color = if (active) "var(--ink)" else "var(--ink-soft)"
            b.style.fontWeight = if (active) "600" else ""
            b.style.background = if (active) "var(--hi-soft)" else "transparent"
        }
    }
    document.querySelectorAll(".set-section[data-tab]").let { nodes ->
        for (i in 0 until nodes.length) {
            val s = nodes.item(i) as? HTMLElement ?: continue
            if (s.getAttribute("data-tab") == tab) s.classList.add("tab-show") else s.classList.remove("tab-show")
        }
    }
    // ?tab= via replaceState — same Router contract as detail/metadata tabs (Phase 28); no hashchange.
    Router.updateQuery(mapOf("tab" to tab), replace = true)
}

private fun wireSettingsTabs(container: Element) {
    val navBtns = container.querySelectorAll(".settings-nav-item[data-tab]")
    for (i in 0 until navBtns.length) {
        val btn = navBtns.item(i) as? HTMLElement ?: continue
        btn.addEventListener("click") { _ -> btn.getAttribute("data-tab")?.let { showTab(it) } }
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

// ── Phase 91 — pipeline builder ───────────────────────────────────────────────

private data class PipeBlockDef(
    val name: String, val subtitle: String, val color: String, val icon: String,
    val needsArr: Boolean = false,
)

private data class PipeCadRow(val key: String, val nm: String, val yr: String, val cadValue: String)

private const val PIPE_SCAN_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="7" cy="7" r="4.4"/><line x1="10.4" y1="10.4" x2="14" y2="14"/></svg>"""
private const val PIPE_TMDB_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3.5" width="12" height="9" rx="1.6"/><line x1="2" y1="6.4" x2="14" y2="6.4"/><line x1="4.4" y1="9.2" x2="8.4" y2="9.2"/></svg>"""
private const val PIPE_ART_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="2.6" width="12" height="10.8" rx="1.6"/><circle cx="5.6" cy="6" r="1.2"/><polyline points="3,12 6.4,8.6 9,11 11,9 13.4,11.4"/></svg>"""
private const val PIPE_NFO_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 1.9h4.2L12 5.5V14.1H4Z"/><polyline points="8,1.9 8,5.6 12,5.6"/><line x1="6" y1="9.1" x2="10" y2="9.1"/><line x1="6" y1="11.3" x2="10" y2="11.3"/></svg>"""
private const val PIPE_SYNC_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M13 8a5 5 0 1 1-1.5-3.6"/><polyline points="13.2,2.4 13.3,5 10.6,5.2"/></svg>"""
private const val PIPE_ARR_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M2 5.2h4l1.2 1.4h6.8V12.4H2Z"/><path d="M9.5 9.4a2 2 0 1 1-.6-1.5"/><polyline points="10.4,7.3 10.5,9 8.9,9"/></svg>"""
private const val PIPE_DRIFT_IC= """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 1.9 14.6 13.5H1.4Z"/><line x1="8" y1="6.4" x2="8" y2="9.6"/><line x1="8" y1="11.4" x2="8" y2="11.5"/></svg>"""
private const val PIPE_NOTIFY_IC="""<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6.6a4 4 0 0 1 8 0c0 2.8 1.2 3.7 1.2 3.7H2.8S4 9.4 4 6.6Z"/><path d="M6.6 12.6a1.5 1.5 0 0 0 2.8 0"/></svg>"""
private const val PIPE_WAIT_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8.8" r="5.1"/><line x1="8" y1="8.8" x2="8" y2="5.8"/><line x1="8" y1="8.8" x2="10" y2="9.8"/><line x1="6.2" y1="1.9" x2="9.8" y2="1.9"/></svg>"""
private const val PIPE_IMDB_IC = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"><path d="M8 1.6 9.6 5.9l4.5.2-3.6 2.8 1.3 4.4L8 10.6l-3.8 2.7 1.3-4.4-3.6-2.8 4.5-.2Z"/></svg>"""
private const val PIPE_SEG_IC  = """<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3.2v9.6l6-4.8Z"/><line x1="11" y1="3.2" x2="11" y2="12.8"/><line x1="13.4" y1="3.2" x2="13.4" y2="12.8"/></svg>"""

private val PIPE_BLOCKS = mapOf(
    "scan_files"    to PipeBlockDef("Scan media files",          "New & changed files + stale re-checks by release age.", "#7b6ef0", PIPE_SCAN_IC),
    "pull_tmdb"     to PipeBlockDef("Pull TMDB metadata",        "Match titles · metadata · original language.",           "#3fb6f5", PIPE_TMDB_IC),
    "fetch_artwork" to PipeBlockDef("Download artwork",          "Poster · fanart · logo · stills from TMDB.",            "#b15cd0", PIPE_ART_IC),
    "detect_segments" to PipeBlockDef("Detect intro & credits",  "Chapter-title match + ffmpeg black-frame / silence for Skip Intro / Skip Credits.", "#e0954a", PIPE_SEG_IC),
    "write_nfo"     to PipeBlockDef("Write NFO files",           "Write .nfo files to disk.",                             "#2dd49a", PIPE_NFO_IC),
    "sync_jellyfin" to PipeBlockDef("Sync Jellyfin",             "POST /Items/{id}/Refresh so Jellyfin re-reads the NFOs.","#18c2d4", PIPE_SYNC_IC),
    "rescan_arr"    to PipeBlockDef("Rescan in Radarr / Sonarr", "Nudge the *arr that manages each touched title.",       "#f5b542", PIPE_ARR_IC, needsArr = true),
    "detect_drift"  to PipeBlockDef("Detect drift",              "Compare Jellyfin ⇄ NFO and flag differences.",          "#ff6f61", PIPE_DRIFT_IC),
    "sync_imdb_ratings" to PipeBlockDef("Sync IMDb ratings",     "Refresh aggregate rating · votes from imdb.com.",    "#f5c518", PIPE_IMDB_IC),
    "notify"        to PipeBlockDef("Send notification",         "Ping your webhook when the run reaches here.",          "#e0639a", PIPE_NOTIFY_IC),
    "wait"          to PipeBlockDef("Wait",                      "Pause before the next step (let Jellyfin settle).",     "#9aa0b4", PIPE_WAIT_IC),
)
private val PIPE_PALETTE = listOf("pull_tmdb","fetch_artwork","detect_segments","write_nfo","sync_jellyfin","rescan_arr","detect_drift","sync_imdb_ratings","wait","notify")
private val PIPE_SHORT   = mapOf("scan_files" to "Scan","pull_tmdb" to "TMDB","fetch_artwork" to "Artwork","detect_segments" to "Segments","write_nfo" to "NFO","sync_jellyfin" to "Jellyfin","rescan_arr" to "*arr","detect_drift" to "Drift","sync_imdb_ratings" to "IMDb","notify" to "Notify","wait" to "Wait")
private val CAD_VALS     = listOf("daily","weekly","monthly","6months","yearly","never")
private val CAD_LABELS   = listOf("every day","every week","every month","every 6 months","every year","never")
private val WAIT_MINS    = listOf(5, 10, 15, 30, 60)

// Top-level single-expression helpers for Kotlin/WASM js() constraints
private fun pipeElBottom(el: HTMLElement): Double = js("el.getBoundingClientRect().bottom")
private fun pipeElLeft(el: HTMLElement):   Double = js("el.getBoundingClientRect().left")
private fun pipeElWidth(el: HTMLElement):  Double = js("el.getBoundingClientRect().width")
private fun pipeElTop(el: HTMLElement):    Double = js("el.getBoundingClientRect().top")
private fun pipeElOffsetW(el: HTMLElement): Int   = js("el.offsetWidth")
private fun pipeElOffsetH(el: HTMLElement): Int   = js("el.offsetHeight")
private fun pipeWinW(): Double = js("window.innerWidth")
private fun pipeWinH(): Double = js("window.innerHeight")

private fun hexAlpha(hex: String, alpha: Double): String {
    val h = hex.trimStart('#')
    val n = h.toLong(16)
    val r = (n shr 16) and 0xFF; val g = (n shr 8) and 0xFF; val b = n and 0xFF
    return "rgba($r,$g,$b,$alpha)"
}

private fun renderPipelineFreq() {
    val freqEl = document.getElementById("pipe-freq") ?: return
    val nodes = freqEl.childNodes
    for (i in 0 until nodes.length) {
        val s = nodes.item(i) as? HTMLElement ?: continue
        val f = s.getAttribute("data-f") ?: continue
        if (f == pipelineFreq) s.classList.add("on") else s.classList.remove("on")
    }
    updatePipeCron()
}

/** Phase 166 — recomputes the derived cron for the three preset modes (daily/weekly/everyN) into the
 *  (now-editable) #pipe-cron input; Custom mode leaves whatever the admin typed there alone. Every call
 *  re-triggers the debounced live-validation check (FR-166-5) so the description/next-runs preview and
 *  Save's enabled state stay in sync with whatever's actually in the field. */
private fun updatePipeCron() {
    val atField     = document.getElementById("pipe-at-field") as? HTMLElement
    val everyNField = document.getElementById("pipe-everyn-field") as? HTMLElement
    val cronInput   = document.getElementById("pipe-cron") as? HTMLInputElement
    val cronHint    = document.getElementById("pipe-cron-label-hint") as? HTMLElement

    atField?.style?.display     = if (pipelineFreq == "daily" || pipelineFreq == "weekly") "" else "none"
    everyNField?.style?.display = if (pipelineFreq == "everyN") "" else "none"
    cronInput?.readOnly = pipelineFreq != "custom"
    cronHint?.textContent = if (pipelineFreq == "custom") "" else "(derived)"

    if (pipelineFreq != "custom") cronInput?.value = computePipeCron()

    // 93e: the real next-run is computed by the backend (wall-clock) and shown by refreshNextRun();
    // while the schedule is being edited it's not applied yet, so say so rather than guess a time.
    document.getElementById("pipe-next")?.textContent = "next · save to apply"
    refreshTomlPreview(readForm())
    scheduleLiveCronCheck()
}

// Phase 166 (FR-166-5) — debounce idiom matches MediaDetail.kt's checkDirty()/metaSaveJob: cancel any
// in-flight check and relaunch after a short pause, so a burst of keystrokes in Custom mode doesn't fire
// one request per character.
private var pipeCronCheckJob: kotlinx.coroutines.Job? = null
private var pipeCronValid = true

private fun scheduleLiveCronCheck() {
    pipeCronCheckJob?.cancel()
    val cron = (document.getElementById("pipe-cron") as? HTMLInputElement)?.value ?: ""
    pipeCronCheckJob = settingsScope?.launch { delay(400); applyCronCheck(cron) }
}

private suspend fun applyCronCheck(cron: String) {
    val checkEl = document.getElementById("pipe-cron-check") as? HTMLElement
    if (cron.isBlank()) {
        pipeCronValid = true
        checkEl?.innerHTML = ""
        updateSaveEnabledForSchedule()
        return
    }
    val result = ConfigApi.validateSchedule(cron)
    if (result == null) {
        // Couldn't reach the server — don't block Save on a transient network hiccup; PUT /api/config
        // (FR-166-4) still rejects an actually-invalid schedule at save time as the real backstop.
        pipeCronValid = true
        checkEl?.innerHTML = """<span class="muted tiny">Couldn't check the schedule right now — it'll still be validated on save.</span>"""
        updateSaveEnabledForSchedule()
        return
    }
    pipeCronValid = result.valid
    checkEl?.innerHTML = if (result.valid) {
        val preview = result.nextRuns.joinToString(" · ") { dev.jellystructure.formatStoredTs(it.toString()) }
        """<span class="badge ok" style="margin-right:6px">✓ ${(result.description ?: "Valid").esc()}</span>""" +
            (if (preview.isNotBlank()) """<div class="tiny muted" style="margin-top:4px">Next: $preview</div>""" else "")
    } else {
        """<span class="badge bad">${(result.error ?: "Invalid schedule").esc()}</span>"""
    }
    updateSaveEnabledForSchedule()
}

/** Phase 166 (FR-166-5) — "Save disabled while invalid". Only the schedule's own validity gates this;
 *  an unrelated Settings edit made while the cron field happens to be mid-typing is still blocked until
 *  it resolves, matching the spec's literal requirement rather than scoping the disable to just the
 *  pipeline card. */
private fun updateSaveEnabledForSchedule() {
    val btn = document.getElementById("save-settings") as? HTMLElement ?: return
    if (pipelineEnabled && !pipeCronValid) btn.setAttribute("disabled", "") else btn.removeAttribute("disabled")
}

/** 93e: show the backend's actual next scheduled run (or "scheduling off") in the pipe-next badge. */
private fun refreshNextRun() {
    settingsScope?.launch {
        val st = MediaApi.scanStatus()
        val el = document.getElementById("pipe-next") ?: return@launch
        val next = st?.nextScheduledRun
        el.textContent = if (next != null) "next · ${dev.jellystructure.formatStoredTs(next.toString())}" else "scheduling off"
    }
}

/** Keeps the "Run pipeline now" split button truthful — disabled + labeled while a scan/pipeline (from
 *  any trigger: this button, its "(full)" sibling, the Dashboard's Scan, or the schedule) is actually
 *  running, so clicking it never again lands on a surprise "already running" conflict. Polled every few
 *  seconds while Settings is open. */
private suspend fun refreshPipelineRunButton() {
    val btn = document.getElementById("pipe-run") as? HTMLElement ?: return
    val caret = document.getElementById("pipe-run-split")?.querySelector(".menu-btn") as? HTMLElement
    val fullItem = document.getElementById("pipe-run-full") as? HTMLElement
    val running = MediaApi.scanStatus()?.running == true
    if (running) {
        btn.setAttribute("disabled", "")
        btn.textContent = "⏳ Pipeline running…"
        btn.title = "A scan/pipeline is already running — check Activity for progress"
        caret?.setAttribute("disabled", "")
        fullItem?.setAttribute("disabled", "")
        (document.getElementById("pipe-run-split") as? HTMLElement)?.classList?.remove("open")
    } else {
        btn.removeAttribute("disabled")
        btn.textContent = "▷ Run pipeline now"
        btn.title = "Run every enabled step below now (not just a file scan)"
        caret?.removeAttribute("disabled")
        fullItem?.removeAttribute("disabled")
    }
}

/** Shared by the split button's primary face and its "(full)" menu item — full=true bypasses scan_files'
 *  freshness filter (see MediaApi.runPipeline) so every downstream step sees the whole library this run. */
/**
 * Phase 154 (FR-PIPE1-1..5) — pre-run dialog. Lists the steps that will actually run and lets the operator
 * untick any of them for THIS run only; nothing here touches [pipelineSteps] (the Settings edit buffer read
 * by `readForm()` on Save) or persisted config. Reads the pipeline from the SAVED config rather than that
 * buffer, because the saved config is what the server filters — with an explicit note when the two differ,
 * so the dialog can never claim a run will do something it won't.
 */
private const val PIPE_SKIP_KEY = "js-pipeline-skip"

private fun showPipelineRunDialog(full: Boolean, saved: List<PipelineStep>, hasUnsavedEdits: Boolean, onStart: (List<String>) -> Unit) {
    val enabled = saved.filter { it.enabled }
    // FR-PIPE1-5: restore the last choice, but only for steps that still exist and are enabled.
    val remembered = (localStorage.getItem(PIPE_SKIP_KEY) ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
    val skipped = remembered.filterTo(mutableSetOf()) { key -> enabled.any { it.step == key } && key != "scan_files" }

    val back = document.createElement("div") as HTMLElement
    back.className = "modal-back open"
    val rows = enabled.joinToString("") { step ->
        val def = PIPE_BLOCKS[step.step]
        val name = def?.name ?: step.step
        val sub = def?.subtitle ?: ""
        // FR-PIPE1-4: discovery runs regardless of the list it's handed, so its box is ticked and disabled.
        val locked = step.step == "scan_files"
        val checked = if (locked || step.step !in skipped) "checked" else ""
        val note = when {
            locked -> """<div class="muted" style="font-size:.76rem;margin-top:3px;">Always runs — file discovery can't be skipped.</div>"""
            // FR-PIPE1-3: the whole point of the dialog — say plainly why this one is usually safe to drop.
            step.step == "detect_segments" -> """<div style="font-size:.76rem;margin-top:3px;color:var(--warn);">Usually safe to skip — by far the slowest step (hours; it decodes each episode), and it only powers Skip&nbsp;Intro / Skip&nbsp;Credits. Already-detected markers are kept.</div>"""
            else -> ""
        }
        """
        <label class="row" style="align-items:flex-start;gap:10px;padding:9px 2px;border-bottom:1px solid var(--line);cursor:${if (locked) "default" else "pointer"};">
          <input type="checkbox" data-step="${step.step}" $checked ${if (locked) "disabled" else ""} style="margin-top:3px;flex:none;accent-color:#7b6ef0;">
          <span style="min-width:0;">
            <span style="display:block;font-weight:600;">$name</span>
            <span class="muted" style="font-size:.8rem;">$sub</span>
            $note
          </span>
        </label>"""
    }
    val unsavedNote = if (!hasUnsavedEdits) "" else
        """<div class="muted" style="font-size:.8rem;color:var(--warn);margin-bottom:10px;">You have unsaved pipeline edits. This run uses the <b>saved</b> pipeline shown below — save first if you want your changes applied.</div>"""
    back.innerHTML = """
        <div class="modal">
          <span class="x" id="prun-x">✕</span>
          <h3>${if (full) "Run pipeline now (full)" else "Run pipeline now"}</h3>
          <p class="muted" style="margin:0 0 12px;">
            ${if (full) "Every step sees the whole library — no freshness filter. " else ""}Untick anything you want to skip <b>this run only</b>; your saved pipeline isn't changed.
          </p>
          $unsavedNote
          <div style="max-height:46vh;overflow:auto;margin-bottom:14px;">$rows</div>
          <div class="row" style="justify-content:flex-end;gap:8px;">
            <button id="prun-cancel" class="btn ghost">Cancel</button>
            <button id="prun-go" class="btn primary">Start run</button>
          </div>
        </div>""".trimIndent()
    document.body?.appendChild(back)

    var escHandler: ((org.w3c.dom.events.Event) -> Unit)? = null
    fun close() {
        escHandler?.let { document.removeEventListener("keydown", it) }
        back.remove()
    }
    escHandler = { e -> if ((e as? KeyboardEvent)?.key == "Escape") close() }
    document.addEventListener("keydown", escHandler)
    back.querySelector("#prun-x")?.addEventListener("click") { close() }
    back.querySelector("#prun-cancel")?.addEventListener("click") { close() }
    back.addEventListener("click") { e -> if (e.target == back) close() }
    back.querySelector("#prun-go")?.addEventListener("click") {
        val skip = mutableListOf<String>()
        val boxes = back.querySelectorAll("input[type=checkbox][data-step]")
        for (i in 0 until boxes.length) {
            val box = boxes.item(i) as? HTMLInputElement ?: continue
            val key = box.getAttribute("data-step") ?: continue
            if (!box.checked && key != "scan_files") skip.add(key)
        }
        localStorage.setItem(PIPE_SKIP_KEY, skip.joinToString(","))
        close()
        onStart(skip)
    }
}

private suspend fun triggerPipelineRun(full: Boolean, skipSteps: List<String> = emptyList()) {
    val btn = document.getElementById("pipe-run") as? HTMLElement
    btn?.setAttribute("disabled", "")
    val result = runCatching { MediaApi.runPipeline(full, skipSteps) }.getOrDefault(PipelineRunResult.FAILED)
    when (result) {
        PipelineRunResult.STARTED -> showPipelineToast(
            (if (full) "Full pipeline run started" else "Pipeline started") +
                if (skipSteps.isEmpty()) "" else " · skipped ${skipSteps.mapNotNull { PIPE_SHORT[it] ?: it }.joinToString(", ")}"
        )
        PipelineRunResult.ALREADY_RUNNING -> showPipelineToast("A scan/pipeline is already running")
        PipelineRunResult.FAILED -> { btn?.removeAttribute("disabled"); showPipelineToast("Failed to start pipeline") }
    }
    // Either outcome (started, or already-running) means one is now known to be in flight — reflect
    // that on the button immediately rather than waiting for the next poll tick.
    if (result != PipelineRunResult.FAILED) refreshPipelineRunButton()
}

/** Phase 166 — the derived cron for daily/weekly/everyN; Custom returns whatever's typed into
 *  #pipe-cron verbatim (that input IS the source of truth in that mode, not something to recompute). */
private fun computePipeCron(): String {
    val atVal = (document.getElementById("pipe-at") as? HTMLInputElement)?.value ?: "03:00"
    val h = atVal.split(":")[0].toIntOrNull() ?: 3
    return when (pipelineFreq) {
        "weekly" -> "0 $h * * 0"
        "everyN" -> {
            val hours = (document.getElementById("pipe-everyn-hours") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceIn(1, 23) ?: 6
            val min = (document.getElementById("pipe-everyn-min") as? HTMLInputElement)?.value?.toIntOrNull()?.coerceIn(0, 59) ?: 0
            "$min */$hours * * *"
        }
        "custom" -> (document.getElementById("pipe-cron") as? HTMLInputElement)?.value ?: ""
        else -> "0 $h * * *"
    }
}

private fun renderPipeline() {
    val canvas = document.getElementById("pipe-canvas") as? HTMLElement ?: return
    canvas.innerHTML = ""
    pipelineSteps.forEachIndexed { i, step ->
        canvas.appendChild(pipeStepEl(step, i))
        if (i < pipelineSteps.size - 1) canvas.appendChild(pipeConnEl(i + 1))
    }
    val end = document.createElement("div") as HTMLElement
    end.className = "pipe-end"
    val addBtn = document.createElement("button") as HTMLElement
    addBtn.className = "btn sm ghost"; addBtn.textContent = "+ Add step"; addBtn.id = "pipe-addend"
    addBtn.addEventListener("click") { openPipelinePalette(addBtn, pipelineSteps.size) }
    end.appendChild(addBtn); canvas.appendChild(end)
    renderPipelineRecipe()
    refreshTomlPreview(readForm())
}

private fun pipeConnEl(insertAt: Int): Element {
    val c = document.createElement("div") as HTMLElement
    c.className = "pipe-conn"
    val ln  = document.createElement("span"); ln.className = "ln"
    val add = document.createElement("span") as HTMLElement; add.className = "pipe-add"; add.textContent = "+"
    add.title = "Add a step here"
    add.addEventListener("click") { openPipelinePalette(add, insertAt) }
    c.appendChild(ln); c.appendChild(add)
    return c
}

private fun pipeStepEl(step: PipelineStep, idx: Int): Element {
    val d   = PIPE_BLOCKS[step.step] ?: return document.createElement("div")
    val el  = document.createElement("div") as HTMLElement
    val isFirst = idx == 0
    el.className = "pipe-step" + (if (isFirst) " trigger" else "") + (if (!step.enabled) " disabled" else "")
    el.style.setProperty("--c",      d.color)
    el.style.setProperty("--c-soft", hexAlpha(d.color, 0.16))
    el.setAttribute("data-pi", idx.toString())
    if (!isFirst) el.setAttribute("draggable", "true")

    val grip = document.createElement("div"); grip.className = "pipe-grip"; grip.textContent = "⠿"
    val ic   = document.createElement("div"); ic.className = "pipe-ic"; ic.innerHTML = d.icon
    val body = document.createElement("div"); body.className = "pipe-body"
    val row1 = document.createElement("div"); row1.className = "pipe-row1"
    val title= document.createElement("span"); title.className = "pipe-title"; title.textContent = d.name
    row1.appendChild(title)
    if (isFirst) { val t = document.createElement("span"); t.className = "pipe-tag"; t.textContent = "start"; row1.appendChild(t) }
    if (d.needsArr) { val n = document.createElement("span"); n.className = "pipe-need"; n.textContent = "needs *arr"; row1.appendChild(n) }
    val sub  = document.createElement("div"); sub.className = "pipe-sub"; sub.textContent = d.subtitle
    val opts = document.createElement("div"); opts.className = "pipe-opts"
    when (step.step) {
        "scan_files"                      -> opts.appendChild(pipeScanCfgEl(step, idx))
        "pull_tmdb", "fetch_artwork"      -> opts.appendChild(pipeScopeEl(step, idx))
        "detect_segments"                 -> opts.appendChild(pipeSegmentsCfgEl(step, idx))
        "write_nfo"                       -> opts.appendChild(pipeOverwriteEl(step, idx))
        "notify"                          -> opts.appendChild(pipeNotifyEl(step, idx))
        "wait"                            -> opts.appendChild(pipeWaitEl(step, idx))
    }
    body.appendChild(row1); body.appendChild(sub)
    if (opts.childNodes.length > 0) body.appendChild(opts)
    val act  = document.createElement("div"); act.className = "pipe-act"
    val stat = document.createElement("span"); stat.className = "pipe-status"
    act.appendChild(stat)
    if (!isFirst) {
        val tog = document.createElement("span") as HTMLElement
        tog.className = "mini-toggle" + (if (step.enabled) " on" else "")
        tog.title = "Skip this step"
        tog.addEventListener("click") { pipelineSteps[idx] = pipelineSteps[idx].copy(enabled = !pipelineSteps[idx].enabled); renderPipeline() }
        val rm = document.createElement("span") as HTMLElement; rm.className = "pipe-x"; rm.textContent = "✕"; rm.title = "Remove"
        rm.addEventListener("click") { pipelineSteps.removeAt(idx); renderPipeline() }
        act.appendChild(tog); act.appendChild(rm)
    }
    el.appendChild(grip); el.appendChild(ic); el.appendChild(body); el.appendChild(act)

    if (!isFirst) {
        el.addEventListener("dragstart") { pipeDragFrom = idx; el.classList.add("dragging") }
        el.addEventListener("dragend")   { pipeDragFrom = -1; renderPipeline() }
        el.addEventListener("dragover")  { e -> e.preventDefault(); el.classList.add("drop-target") }
        el.addEventListener("dragleave") { el.classList.remove("drop-target") }
        el.addEventListener("drop")      { e -> e.preventDefault(); movePipelineStep(pipeDragFrom, idx) }
    }
    return el
}

private fun movePipelineStep(from: Int, to: Int) {
    if (from < 1 || from == to) return
    val item = pipelineSteps.removeAt(from)
    val dest = (if (from < to) to - 1 else to).coerceIn(1, pipelineSteps.size.coerceAtLeast(1))
    pipelineSteps.add(dest, item)
    renderPipeline()
}

private fun pipeScanCfgEl(step: PipelineStep, idx: Int): Element {
    val year = 2026
    val box  = document.createElement("div") as HTMLElement
    box.className = "scan-cfg" + (if (step.recheckUnchanged) "" else " off")
    val head = document.createElement("label"); head.className = "sc-head"
    val tog  = document.createElement("span")
    tog.className = "mini-toggle" + (if (step.recheckUnchanged) " on" else "")
    val lbl = document.createElement("b"); lbl.textContent = "Refresh unchanged titles on a schedule"
    head.appendChild(tog); head.appendChild(lbl)
    head.addEventListener("click") {
        pipelineSteps[idx] = pipelineSteps[idx].copy(recheckUnchanged = !pipelineSteps[idx].recheckUnchanged)
        renderPipeline()
    }
    val sub  = document.createElement("div"); sub.className = "sc-sub"
    sub.textContent = "Re-sync metadata & artwork for titles whose files never change — often for new releases, rarely for old catalogue."
    val tbl  = document.createElement("div"); tbl.className = "fresh-tbl"
    val hd   = document.createElement("div"); hd.className = "fresh-hd"
    val h1   = document.createElement("span"); h1.textContent = "Release age"
    val h2   = document.createElement("span"); h2.textContent = "Refresh"
    hd.appendChild(h1); hd.appendChild(h2); tbl.appendChild(hd)
    val rows = listOf(
        PipeCadRow("cadY", "Released this year",        "$year",              step.refreshThisYear),
        PipeCadRow("cadM", "Released 1–5 years ago",    "${year-5}–${year-1}", step.refresh1To5y),
        PipeCadRow("cadO", "Released over 5 years ago", "before ${year-5}",   step.refreshOlder),
    )
    for (r in rows) {
        val age  = document.createElement("div"); age.className = "fr-age"
        val nm   = document.createElement("span"); nm.className = "nm"; nm.textContent = r.nm
        val yr   = document.createElement("span"); yr.className = "yr"; yr.textContent = r.yr
        age.appendChild(nm); age.appendChild(yr)
        val cell = document.createElement("div"); cell.className = "fr-cell"
        val isNever = r.cadValue == "never"
        val cad  = document.createElement("span") as HTMLElement
        cad.className = "cad" + (if (isNever) " never" else "")
        val ci = document.createElement("span"); ci.className = "ci"; ci.innerHTML = PIPE_WAIT_IC
        val sel = document.createElement("select") as HTMLSelectElement
        sel.className = "cv-select"
        sel.title = "How often to re-check this age tier"
        CAD_VALS.forEachIndexed { i, v ->
            val opt = document.createElement("option") as org.w3c.dom.HTMLOptionElement
            opt.value = v; opt.textContent = CAD_LABELS[i]
            if (v == r.cadValue) opt.selected = true
            sel.appendChild(opt)
        }
        val capturedKey = r.key
        sel.addEventListener("change") {
            val next = sel.value
            pipelineSteps[idx] = when (capturedKey) {
                "cadY" -> pipelineSteps[idx].copy(refreshThisYear = next)
                "cadM" -> pipelineSteps[idx].copy(refresh1To5y   = next)
                else   -> pipelineSteps[idx].copy(refreshOlder    = next)
            }
            renderPipeline()
        }
        cad.appendChild(ci); cad.appendChild(sel)
        cell.appendChild(cad); tbl.appendChild(age); tbl.appendChild(cell)
    }
    val note = document.createElement("div"); note.className = "sc-state"
    note.innerHTML = "<b>How it works:</b> Jellystructure stores each title's last-checked date and re-processes only titles whose interval is due — new &amp; changed files are always processed immediately."
    box.appendChild(head); box.appendChild(sub); box.appendChild(tbl); box.appendChild(note)
    return box
}

private fun pipeScopeEl(step: PipelineStep, idx: Int): Element {
    val el = document.createElement("span") as HTMLElement; el.className = "opt-seg"
    listOf("missing" to "Missing only", "all" to "All items").forEach { (v, label) ->
        val s = document.createElement("span"); s.textContent = label
        if (step.scope == v) s.classList.add("on")
        val capturedV = v
        s.addEventListener("click") { pipelineSteps[idx] = pipelineSteps[idx].copy(scope = capturedV); renderPipeline() }
        el.appendChild(s)
    }
    return el
}

private fun pipeOverwriteEl(step: PipelineStep, idx: Int): Element {
    val el = document.createElement("span") as HTMLElement; el.className = "opt-tog"
    val t  = document.createElement("span"); t.className = "mini-toggle" + (if (step.overwrite) " on" else "")
    val lbl= document.createElement("span"); lbl.textContent = "Overwrite existing fields"
    el.appendChild(t); el.appendChild(lbl)
    el.addEventListener("click") { pipelineSteps[idx] = pipelineSteps[idx].copy(overwrite = !pipelineSteps[idx].overwrite); renderPipeline() }
    return el
}

// Phase 150 — detect_segments options: cross-episode fingerprinting + trust-stinger-tags toggles, plus
// the chapter-title keyword chip list (design/app/settings.html "Intro & credits detection" card,
// reconciled onto the pipeline-step model per the dev-review addendum — one source of truth, not a
// parallel config).
private fun pipeSegmentsCfgEl(step: PipelineStep, idx: Int): Element {
    val box = document.createElement("div") as HTMLElement
    box.setAttribute("style", "display:flex;flex-direction:column;gap:8px;")

    val fpRow = document.createElement("label") as HTMLElement; fpRow.className = "opt-tog"
    val fpTog = document.createElement("span"); fpTog.className = "mini-toggle" + (if (step.detectFingerprint) " on" else "")
    val fpLbl = document.createElement("span"); fpLbl.textContent = "Cross-episode intro fingerprinting"
    fpRow.appendChild(fpTog); fpRow.appendChild(fpLbl)
    fpRow.addEventListener("click") { pipelineSteps[idx] = pipelineSteps[idx].copy(detectFingerprint = !pipelineSteps[idx].detectFingerprint); renderPipeline() }

    val stRow = document.createElement("label") as HTMLElement; stRow.className = "opt-tog"
    val stTog = document.createElement("span"); stTog.className = "mini-toggle" + (if (step.trustStingerTags) " on" else "")
    val stLbl = document.createElement("span"); stLbl.textContent = "Trust TMDB stinger tags"
    stRow.appendChild(stTog); stRow.appendChild(stLbl)
    stRow.addEventListener("click") { pipelineSteps[idx] = pipelineSteps[idx].copy(trustStingerTags = !pipelineSteps[idx].trustStingerTags); renderPipeline() }

    val kwWrap = document.createElement("div") as HTMLElement
    val kwLbl = document.createElement("div") as HTMLElement; kwLbl.className = "tiny muted"
    kwLbl.textContent = "Extra chapter-title keywords (credits)"
    val kwChips = document.createElement("div") as HTMLElement
    kwChips.setAttribute("style", "display:flex;gap:6px;flex-wrap:wrap;margin-top:6px;")
    step.chapterKeywords.forEachIndexed { ki, kw ->
        val chip = document.createElement("span") as HTMLElement; chip.className = "chip"
        chip.textContent = "$kw ✕"; chip.title = "Click to remove"
        chip.setAttribute("style", "cursor:pointer;")
        chip.addEventListener("click") {
            pipelineSteps[idx] = pipelineSteps[idx].copy(chapterKeywords = pipelineSteps[idx].chapterKeywords.filterIndexed { j, _ -> j != ki })
            renderPipeline()
        }
        kwChips.appendChild(chip)
    }
    val addChip = document.createElement("span") as HTMLElement
    addChip.className = "chip"; addChip.setAttribute("style", "border-style:dashed;cursor:pointer;")
    addChip.textContent = "+ add"
    addChip.addEventListener("click") {
        val word = window.prompt("Chapter-title keyword to match (credits)")?.trim()
        if (!word.isNullOrEmpty()) {
            pipelineSteps[idx] = pipelineSteps[idx].copy(chapterKeywords = pipelineSteps[idx].chapterKeywords + word)
            renderPipeline()
        }
    }
    kwChips.appendChild(addChip)
    kwWrap.appendChild(kwLbl); kwWrap.appendChild(kwChips)

    box.appendChild(fpRow); box.appendChild(stRow); box.appendChild(kwWrap)
    return box
}

private fun pipeNotifyEl(step: PipelineStep, idx: Int): Element {
    val el = document.createElement("span") as HTMLElement; el.className = "opt-seg"
    listOf("summary" to "Run summary", "changes" to "On changes", "errors" to "On errors").forEach { (v, label) ->
        val s = document.createElement("span"); s.textContent = label
        if (step.on == v) s.classList.add("on")
        val capturedV = v
        s.addEventListener("click") { pipelineSteps[idx] = pipelineSteps[idx].copy(on = capturedV); renderPipeline() }
        el.appendChild(s)
    }
    return el
}

private fun pipeWaitEl(step: PipelineStep, idx: Int): Element {
    val el = document.createElement("span") as HTMLElement; el.className = "opt-step"
    val b  = document.createElement("b"); b.textContent = "${step.minutes} min"
    val nx = document.createElement("span"); nx.className = "nx"; nx.textContent = "▸"
    el.appendChild(b); el.appendChild(nx)
    el.addEventListener("click") {
        val next = WAIT_MINS[(WAIT_MINS.indexOf(step.minutes) + 1) % WAIT_MINS.size]
        pipelineSteps[idx] = pipelineSteps[idx].copy(minutes = next)
        renderPipeline()
    }
    return el
}

private fun renderPipelineRecipe() {
    val el = document.getElementById("pipe-recipe") as? HTMLElement ?: return
    el.innerHTML = pipelineSteps.mapIndexed { i, st ->
        val d = PIPE_BLOCKS[st.step] ?: return@mapIndexed ""
        val c = d.color
        val label = PIPE_SHORT[st.step] ?: st.step
        val offCls = if (!st.enabled) " off" else ""
        (if (i > 0) """<span class="arrow">→</span>""" else "") +
            """<span class="rc$offCls" style="background:${hexAlpha(c,0.16)};color:$c">$label</span>"""
    }.joinToString("")
}

private fun openPipelinePalette(anchor: HTMLElement, insertAt: Int) {
    pipeInsertAt = insertAt
    val pal  = document.getElementById("pipe-pal")  as? HTMLElement ?: return
    val back = document.getElementById("pipe-back") as? HTMLElement ?: return
    pal.style.visibility = "hidden"; pal.style.display = "block"; back.style.display = "block"
    val pw = pipeElOffsetW(pal); val ph = pipeElOffsetH(pal)
    var left = pipeElLeft(anchor) + pipeElWidth(anchor) / 2 - pw / 2
    val winW = pipeWinW(); val winH = pipeWinH()
    if (left < 12) left = 12.0; if (left + pw > winW - 12) left = winW - pw - 12
    var top = pipeElBottom(anchor) + 8
    if (top + ph > winH - 12) top = pipeElTop(anchor) - ph - 8
    pal.style.left = "${left.toInt()}px"; pal.style.top = "${top.toInt()}px"
    pal.style.visibility = ""
}

private fun closePipelinePalette() {
    pipeInsertAt = -1
    (document.getElementById("pipe-pal")  as? HTMLElement)?.style?.display = "none"
    (document.getElementById("pipe-back") as? HTMLElement)?.style?.display = "none"
}

private fun wirePipelineBuilder(scope: CoroutineScope) {
    document.getElementById("pipe-enable")?.addEventListener("click") {
        pipelineEnabled = !pipelineEnabled
        updateToggle("pipe-enable", pipelineEnabled)
        (document.getElementById("pipe-fields") as? HTMLElement)?.style?.display = if (pipelineEnabled) "" else "none"
        refreshTomlPreview(readForm())
        updateSaveEnabledForSchedule() // turning the pipeline off must never leave Save stuck disabled
    }
    document.getElementById("pipe-freq")?.let { freqEl ->
        val nodes = freqEl.childNodes
        for (i in 0 until nodes.length) {
            val s = nodes.item(i) as? HTMLElement ?: continue
            val f = s.getAttribute("data-f") ?: continue
            s.addEventListener("click") {
                pipelineFreq = f
                renderPipelineFreq()
            }
        }
    }
    document.getElementById("pipe-at")?.addEventListener("input") { updatePipeCron() }
    // Phase 166 — Every N hours' two numeric inputs recompute the derived cron the same way "At" does.
    document.getElementById("pipe-everyn-hours")?.addEventListener("input") { updatePipeCron() }
    document.getElementById("pipe-everyn-min")?.addEventListener("input") { updatePipeCron() }
    // Phase 166 (FR-166-5) — Custom mode: the cron field IS the input now (not a derived read-only
    // display), so typing in it must re-run live validation the same way changing a preset does.
    document.getElementById("pipe-cron")?.addEventListener("input") {
        refreshTomlPreview(readForm())
        scheduleLiveCronCheck()
    }
    // Phase 154 (FR-PIPE1-1): both faces open the pre-run dialog instead of starting immediately — each is
    // a whole-library, multi-hour run ("full" only drops the freshness filter), so the skip choice matters
    // equally to both. The saved pipeline is fetched here so the dialog lists what the SERVER will run.
    fun openRunDialog(full: Boolean) = scope.launch {
        val saved = ConfigApi.get()?.config?.scan?.pipeline ?: pipelineSteps.toList()
        showPipelineRunDialog(full, saved, hasUnsavedEdits = saved != pipelineSteps.toList()) { skip ->
            scope.launch { triggerPipelineRun(full, skip) }
        }
    }
    document.getElementById("pipe-run")?.addEventListener("click") { openRunDialog(full = false) }
    document.getElementById("pipe-run-full")?.addEventListener("click") { e ->
        if ((e.currentTarget as? HTMLElement)?.hasAttribute("disabled") == true) return@addEventListener
        (document.getElementById("pipe-run-split") as? HTMLElement)?.classList?.remove("open")
        openRunDialog(full = true)
    }
    (document.getElementById("pipe-run-split") as? HTMLElement)?.querySelector(".menu-btn")?.let { caret ->
        (caret as? HTMLElement)?.addEventListener("click") { e ->
            e.stopPropagation()
            if ((e.currentTarget as? HTMLElement)?.hasAttribute("disabled") == true) return@addEventListener
            (document.getElementById("pipe-run-split") as? HTMLElement)?.classList?.toggle("open")
        }
    }
    document.addEventListener("click") { (document.getElementById("pipe-run-split") as? HTMLElement)?.classList?.remove("open") }
    scope.launch {
        while (isActive) {
            refreshPipelineRunButton()
            delay(3_000)
        }
    }
    val back = document.createElement("div") as HTMLElement
    back.id = "pipe-back"; back.style.cssText = "display:none;position:fixed;inset:0;z-index:200"
    back.addEventListener("click") { closePipelinePalette() }
    val pal  = document.createElement("div") as HTMLElement
    pal.id = "pipe-pal"; pal.className = "blk-pal"; pal.style.display = "none"
    val plh = document.createElement("div"); plh.className = "pl-h"; plh.textContent = "Add a step"
    val lst = document.createElement("div"); lst.id = "pipe-list"
    PIPE_PALETTE.forEach { key ->
        val d = PIPE_BLOCKS[key] ?: return@forEach
        val item = document.createElement("div") as HTMLElement; item.className = "blk-item"
        val ic   = document.createElement("div") as HTMLElement; ic.className = "pipe-ic"
        ic.innerHTML = d.icon
        ic.style.setProperty("--c",      d.color)
        ic.style.setProperty("--c-soft", hexAlpha(d.color, 0.16))
        ic.style.setProperty("background", hexAlpha(d.color, 0.16))
        ic.style.setProperty("color", d.color)
        val info = document.createElement("div")
        val nm   = document.createElement("div"); nm.className = "nm"; nm.textContent = d.name
        val ds   = document.createElement("div"); ds.className = "ds"; ds.textContent = d.subtitle
        info.appendChild(nm); info.appendChild(ds)
        item.appendChild(ic); item.appendChild(info)
        item.addEventListener("click") {
            if (pipeInsertAt < 0) return@addEventListener
            pipelineSteps.add(pipeInsertAt, PipelineStep(step = key))
            closePipelinePalette()
            renderPipeline()
        }
        lst.appendChild(item)
    }
    pal.appendChild(plh); pal.appendChild(lst)
    document.body?.appendChild(back); document.body?.appendChild(pal)
    window.addEventListener("keydown") { e ->
        if ((e as? org.w3c.dom.events.KeyboardEvent)?.key == "Escape") closePipelinePalette()
    }
}

private fun showPipelineToast(msg: String) {
    val t = document.createElement("div") as HTMLElement
    t.textContent = msg
    t.style.cssText = "position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:var(--fill-3);border:1px solid var(--line-2);border-radius:8px;padding:8px 16px;font-size:.82rem;z-index:9999;pointer-events:none"
    document.body?.appendChild(t)
    window.setTimeout({ document.body?.removeChild(t); null }, 2000)
}
