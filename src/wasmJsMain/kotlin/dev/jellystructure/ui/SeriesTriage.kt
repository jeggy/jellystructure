package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.encodeURIComponent
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.httpClient
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.TrackKind
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

private const val TMDB_BASE_ST = "https://image.tmdb.org/t/p"

private var stItem: MediaItem? = null
private var stActiveStep = 0   // 0 = series, 1+ = episode index (1-based into stItem.episodes)
private var stScope: CoroutineScope? = null
private var stContainer: Element? = null
private var stRailOpen = true
private var stOnlyIssues = false
private var stTrackTab: MutableMap<String, String> = mutableMapOf()   // epFilename -> "audio"|"subtitle"
private var stMetaDirty = false

private val ST_QUICK_LANGS = listOf("fo", "da", "en", "is")

fun renderSeriesTriage(container: Element, scope: CoroutineScope, mediaId: String) {
    stScope = scope
    stContainer = container
    stActiveStep = 0
    stRailOpen = true
    stOnlyIssues = false
    stTrackTab = mutableMapOf()
    stMetaDirty = false
    stItem = null

    container.innerHTML = """
        <div class="pagebar">
          <h1>Series Triage</h1>
        </div>
        <p class="muted" style="padding:24px">Loading series…</p>
    """.trimIndent()

    scope.launch {
        val item = MediaApi.get(mediaId)
        if (item == null) {
            container.innerHTML = """
                <div class="pagebar"><h1>Series Triage</h1></div>
                <p class="muted" style="padding:24px">Failed to load series — check the media ID.</p>
            """.trimIndent()
            return@launch
        }
        stItem = item
        // Jump to first episode with issues, else stay at series step
        val firstIssue = item.episodes.indexOfFirst { it.issueCount > 0 }
        stActiveStep = if (firstIssue >= 0) firstIssue + 1 else 0
        renderSeriesTriagePage()
    }
}

private fun renderSeriesTriagePage() {
    val container = stContainer ?: return
    val item = stItem ?: return
    container.innerHTML = buildSeriesTriageHTML(item)
    wireStListeners(item)
}

private fun buildSeriesTriageHTML(item: MediaItem): String {
    val attnCount = item.episodes.count { it.issueCount > 0 }
    val railHtml = if (stRailOpen) buildRailHtml(item) else ""
    val railStyle = if (stRailOpen)
        "width:288px;flex:none;border-right:1px solid var(--border);overflow:auto;padding:16px 14px;background:var(--bg-2)"
    else "display:none"

    return """
        <div style="display:flex;flex-direction:column;height:100%;overflow:hidden">
          <!-- top bar -->
          <div style="display:flex;align-items:center;gap:12px;padding:14px 20px;border-bottom:1px solid var(--border);background:var(--bg-2);flex:none">
            <button id="st-rail-toggle" class="btn sm ghost" style="width:36px;height:36px;padding:0;font-size:1.1rem" title="Toggle episode list">
              ${if (stRailOpen) "⟨" else "☰"}
            </button>
            <div style="min-width:0">
              <div class="tiny muted" style="cursor:pointer" id="st-breadcrumb">Library / TV / <b style="color:var(--ink)">${item.title.esc()}</b></div>
              <h1 style="margin:2px 0 0;font-size:1.4rem">Series triage</h1>
            </div>
            <span class="spacer"></span>
            <span class="chip"><span class="dot bad"></span> $attnCount episode${if (attnCount != 1) "s" else ""} need attention</span>
            <span id="st-meta-msg" class="tiny muted"></span>
            <button id="st-save-meta" class="btn sm" style="${if (stMetaDirty) "" else "display:none"}">Save metadata</button>
            <button id="st-save-nfo" class="btn ghost">Save → disk</button>
            <button id="st-save-jellyfin" class="btn primary">Save &amp; tell Jellyfin ↻</button>
          </div>

          <!-- rail + main -->
          <div style="display:flex;flex:1;min-height:0;overflow:hidden">
            <aside id="st-rail" style="$railStyle">$railHtml</aside>
            <main id="st-main" style="flex:1;min-width:0;overflow:auto;padding:22px 26px">
              ${buildStepContent(item)}
            </main>
          </div>
        </div>
    """.trimIndent()
}

private fun buildRailHtml(item: MediaItem): String {
    val episodes = item.episodes
    val visibleSteps = buildList {
        add(-1)  // -1 = series step
        episodes.forEachIndexed { i, ep ->
            if (!stOnlyIssues || ep.issueCount > 0) add(i)
        }
    }

    val cards = visibleSteps.joinToString("") { stepIdx ->
        if (stepIdx == -1) {
            val on = stActiveStep == 0
            val border = if (on) "var(--hi)" else "var(--border)"
            val bg = if (on) "var(--hi-soft)" else "var(--fill-2)"
            val posterHtml = if (item.posterPath != null)
                """<img src="$TMDB_BASE_ST/w92${item.posterPath}" style="width:46px;height:64px;object-fit:cover;border-radius:4px">"""
            else
                """<div style="width:46px;height:64px;border-radius:4px;background:var(--fill-3);display:flex;align-items:center;justify-content:center"><span class="tiny muted">poster</span></div>"""
            """<div class="st-rail-card" data-step="0" style="display:flex;gap:11px;padding:10px;border-radius:12px;cursor:pointer;border:1px solid $border;background:$bg;align-items:center;margin-bottom:8px">
                 $posterHtml
                 <div style="min-width:0">
                   <div style="font-weight:700;font-size:.92rem">Series level</div>
                   <div class="tiny muted">${item.title.esc()} · ${item.episodes.size} eps</div>
                   ${if (item.resolvedLanguage != null) """<div style="margin-top:4px"><span class="lang" style="font-size:.64rem">${item.resolvedLanguage}</span></div>""" else ""}
                 </div>
               </div>"""
        } else {
            val ep = episodes[stepIdx]
            val on = stActiveStep == stepIdx + 1
            val border = if (on) "var(--hi)" else "transparent"
            val bg = if (on) "var(--hi-soft)" else "var(--fill-2)"
            val dotColor = if (ep.issueCount > 0) "var(--bad)" else "var(--ok)"
            val dotGlow = if (ep.issueCount > 0) "rgba(255,111,97,.7)" else "rgba(45,212,154,.6)"
            val epCode = ep.seasonNumber?.let { s -> ep.episodeNumber?.let { e ->
                "S${s.toString().padStart(2,'0')}E${e.toString().padStart(2,'0')}" } } ?: ep.filename.take(10)
            val stillHtml = if (ep.stillPath != null)
                """<img src="$TMDB_BASE_ST/w185${ep.stillPath}" style="width:92px;height:52px;object-fit:cover;border-radius:4px">"""
            else
                """<div style="width:92px;height:52px;border-radius:4px;background:var(--fill-3);display:flex;align-items:center;justify-content:center"><span class="tiny muted" style="font-size:.52rem">${if (ep.stillPath == null) "no still" else "still"}</span></div>"""
            val statusHtml = if (ep.issueCount > 0)
                """<div style="margin-top:3px;display:flex;gap:3px;flex-wrap:wrap">${buildEpIssueBadges(ep)}</div>"""
            else
                """<div style="margin-top:2px;font-size:.66rem;color:var(--ok)">✓ complete</div>"""
            """<div class="st-rail-card" data-step="${stepIdx + 1}" style="display:flex;gap:11px;padding:8px;border-radius:12px;cursor:pointer;border:1px solid $border;background:$bg;align-items:center;margin-bottom:8px;position:relative">
                 <div style="position:relative;flex:none">
                   $stillHtml
                   <span style="position:absolute;top:-4px;right:-4px;width:13px;height:13px;border-radius:50%;border:2px solid var(--bg);background:$dotColor;box-shadow:0 0 8px $dotGlow"></span>
                 </div>
                 <div style="min-width:0;flex:1">
                   <div class="row center" style="gap:6px">
                     <span class="num" style="font-size:.74rem">$epCode</span>
                     ${if (ep.resolvedLanguage != null) """<span class="lang" style="font-size:.64rem">${ep.resolvedLanguage}</span>""" else ""}
                   </div>
                   <div style="font-weight:${if (on) 700 else 600};font-size:.86rem;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">${(ep.title ?: ep.filename).esc()}</div>
                   $statusHtml
                 </div>
               </div>"""
        }
    }

    val attnCount = item.episodes.count { it.issueCount > 0 }
    return """
        <div class="row center" style="margin:0 0 10px">
          <h4 style="margin:0">Episodes</h4>
          <span class="spacer"></span>
          <span class="seg" style="font-size:.7rem">
            <span id="st-filter-all" class="${if (!stOnlyIssues) "on" else ""}">All</span>
            <span id="st-filter-issues" class="${if (stOnlyIssues) "on" else ""}">Issues ($attnCount)</span>
          </span>
        </div>
        <div>$cards</div>
    """.trimIndent()
}

private fun buildEpIssueBadges(ep: Episode): String {
    val untagged = ep.tracks.count { it.language == null || it.language.isBlank() }
    val sb = StringBuilder()
    if (untagged > 0) sb.append("""<span class="badge bad" style="font-size:.54rem">untagged</span>""")
    if (ep.stillPath == null) sb.append("""<span class="badge warn" style="font-size:.54rem">no still</span>""")
    if (ep.overview.isNullOrBlank()) sb.append("""<span class="badge warn" style="font-size:.54rem">no overview</span>""")
    return sb.toString()
}

private fun buildStepContent(item: MediaItem): String {
    return if (stActiveStep == 0) buildSeriesStep(item)
    else {
        val ep = item.episodes.getOrNull(stActiveStep - 1)
        if (ep != null) buildEpisodeStep(item, ep) else buildSeriesStep(item)
    }
}

private fun buildSeriesStep(item: MediaItem): String {
    val posterHtml = if (item.posterPath != null)
        """<img src="$TMDB_BASE_ST/w342${item.posterPath}" style="width:120px;height:178px;object-fit:cover;border-radius:6px;flex:none">"""
    else
        """<div style="width:120px;height:178px;border-radius:6px;background:var(--fill-3);display:flex;align-items:center;justify-content:center;flex:none"><span class="muted tiny">no poster</span></div>"""

    val genreChips = item.genres.joinToString("") { g ->
        """<span class="chip">${g.esc()}</span>"""
    }

    // Language distribution
    val langCounts = item.episodes.mapNotNull { it.resolvedLanguage }.groupingBy { it }.eachCount()
    val total = langCounts.values.sum()
    val langDistHtml = if (langCounts.isNotEmpty() && total > 0) {
        val sorted = langCounts.entries.sortedByDescending { it.value }
        val winner = sorted.first().key
        val bars = sorted.joinToString("") { (lang, count) ->
            val pct = count * 100 / total
            val isWinner = lang == winner
            """<div title="$lang · $count/$total" style="width:${pct}%;background:${if (isWinner) "var(--grad,var(--hi))" else "var(--fill-3)"};color:${if (isWinner) "#fff" else "var(--ink-soft)"};display:flex;align-items:center;justify-content:center;font-family:'JetBrains Mono',monospace;font-size:.74rem;font-weight:600">$lang · $count</div>"""
        }
        val legend = sorted.joinToString("") { (lang, count) ->
            """<span><span class="lang">$lang</span><span class="muted tiny"> · $count/$total eps</span></span>"""
        }
        """<div style="height:26px;display:flex;border:1px solid var(--border);border-radius:4px;overflow:hidden">$bars</div>
           <div class="legend" style="margin-top:10px">$legend</div>"""
    } else """<div class="muted tiny">No episode language data yet — run a scan.</div>"""

    val attnCount = item.episodes.count { it.issueCount > 0 }
    val untaggedCount = item.episodes.sumOf { ep -> ep.tracks.count { it.language.isNullOrBlank() } }
    val noStillCount = item.episodes.count { it.stillPath == null }
    val noOverviewCount = item.episodes.count { it.overview.isNullOrBlank() }

    return """
        <div class="col" style="gap:16px">
          <div class="row center" style="gap:10px">
            <span class="kicker2">STEP 1 · SERIES LEVEL</span>
            <span class="badge info">tvshow.nfo</span>
          </div>

          <!-- metadata + poster -->
          <div class="card">
            <div class="row" style="align-items:flex-start;gap:14px">
              $posterHtml
              <div class="fill col" style="gap:10px">
                <div class="row" style="gap:8px">
                  <div class="field fill" style="margin:0"><label>Series title</label>
                    <input class="input" id="st-title" value="${item.title.esc()}"></div>
                  <div class="field" style="margin:0;width:90px"><label>Year</label>
                    <input class="input" id="st-year" value="${item.year ?: ""}"></div>
                </div>
                <div class="field" style="margin:0"><label>Overview</label>
                  <textarea class="input" id="st-overview" style="min-height:72px;align-items:flex-start;resize:vertical">${item.overview?.esc() ?: ""}</textarea>
                </div>
                <div class="field" style="margin:0"><label>Genres</label>
                  <div class="pill-row">$genreChips</div>
                </div>
                ${if (item.network != null) """<div class="field" style="margin:0"><label>Network</label><input class="input" id="st-network" value="${item.network.esc()}"></div>""" else ""}
              </div>
            </div>
          </div>

          <!-- series artwork -->
          <div class="card">
            <div class="row center" style="margin:0 0 10px">
              <h4 style="margin:0">Series artwork</h4>
              <span class="spacer"></span>
              <span class="pill-row">
                <button id="st-fetch-art" class="btn sm">Fetch all</button>
              </span>
            </div>
            <div class="row" style="gap:12px;flex-wrap:wrap">
              ${artSlotHtml("poster", 120, 150, item.posterPath != null)}
              ${artSlotHtml("backdrop", 213, 120, item.backdropPath != null)}
            </div>
            <div id="st-art-result" style="margin-top:8px"></div>
          </div>

          <!-- language across episodes -->
          <div class="override">
            <div class="row center" style="margin:0 0 10px">
              <h4 style="margin:0">Metadata language across episodes</h4>
              <span class="spacer"></span>
              <span class="badge info">majority wins</span>
            </div>
            <div class="tiny" style="margin:0 0 14px">
              Each episode resolves its own TMDB fetch language from its audio tracks.
              The <b>series</b> (tvshow.nfo) uses the language most episodes share.
            </div>
            $langDistHtml
          </div>

          <!-- episode summary -->
          <div class="card">
            <div class="row center" style="margin:0 0 10px">
              <h4 style="margin:0">This season · ${item.episodes.size} episodes</h4>
            </div>
            <div class="row" style="gap:10px;flex-wrap:wrap">
              <span class="chip"><span class="dot bad"></span> $attnCount need attention</span>
              <span class="chip"><span class="dot bad"></span> $untaggedCount untagged track${if (untaggedCount != 1) "s" else ""}</span>
              <span class="chip"><span class="dot warn"></span> $noStillCount missing still${if (noStillCount != 1) "s" else ""}</span>
              <span class="chip"><span class="dot warn"></span> $noOverviewCount missing overview${if (noOverviewCount != 1) "s" else ""}</span>
            </div>
            <div style="margin-top:12px">
              <button id="st-first-issue" class="btn sm ghost">Go to first issue ↓</button>
            </div>
          </div>
        </div>
    """.trimIndent()
}

private fun artSlotHtml(label: String, w: Int, h: Int, exists: Boolean): String {
    val bg = if (!exists) "background:var(--bad-soft);border-color:var(--bad)" else ""
    val text = if (!exists) "$label · missing" else label
    return """<div class="imgslot" style="width:${w}px;height:${h}px;flex:none;$bg"><span${if (!exists) " style=\"color:var(--bad)\"" else ""}>$text</span></div>"""
}

private fun buildEpisodeStep(item: MediaItem, ep: Episode): String {
    val epCode = ep.seasonNumber?.let { s -> ep.episodeNumber?.let { e ->
        "S${s.toString().padStart(2,'0')}E${e.toString().padStart(2,'0')}" } } ?: ep.filename
    val issueCount = ep.issueCount
    val stillHtml = if (ep.stillPath != null) {
        """<img src="$TMDB_BASE_ST/w300${ep.stillPath}" style="width:228px;height:128px;object-fit:cover;border-radius:6px;flex:none">"""
    } else {
        """<div class="imgslot" style="width:228px;height:128px;flex:none;background:var(--bad-soft);border-color:var(--bad)"><span style="color:var(--bad)">no still</span></div>"""
    }

    val overviewHtml = if (!ep.overview.isNullOrBlank()) {
        """<textarea class="input ep-overview-input" style="min-height:64px;align-items:flex-start;resize:vertical" data-ep-filename="${ep.filename.esc()}">${ep.overview.esc()}</textarea>"""
    } else {
        """<textarea class="input ep-overview-input ph" style="min-height:64px;align-items:flex-start;resize:vertical;background:var(--bad-soft);border-color:var(--bad)" placeholder="No overview — fetch from TMDB or write one…" data-ep-filename="${ep.filename.esc()}"></textarea>"""
    }

    val trackManagerHtml = buildTrackManager(item, ep)

    return """
        <div class="col" style="gap:16px">
          <div class="row center" style="gap:10px;flex-wrap:wrap">
            <span class="kicker2">$epCode · ${(ep.title ?: ep.filename).esc()}</span>
            <span class="badge info">episodedetails.nfo</span>
            <span class="spacer"></span>
            ${if (issueCount == 0)
                """<span class="badge ok">✓ complete</span>"""
            else
                """<span class="badge bad">$issueCount to fix</span>"""}
          </div>

          <!-- still + fields -->
          <div class="card">
            <div class="row" style="align-items:flex-start;gap:14px">
              <div style="display:flex;flex-direction:column;gap:8px;flex:none">
                $stillHtml
                <div class="pill-row">
                  <button id="ep-fetch-still" class="btn sm" data-ep-filename="${ep.filename.esc()}">Fetch still</button>
                </div>
              </div>
              <div class="fill col" style="gap:10px">
                <div class="field" style="margin:0"><label>Episode title</label>
                  <input class="input" id="ep-title-input" value="${(ep.title ?: "").esc()}" data-ep-filename="${ep.filename.esc()}">
                </div>
                <div class="field" style="margin:0"><label>Overview</label>
                  $overviewHtml
                </div>
                <div class="pill-row" style="align-items:center">
                  <span class="chip mono">$epCode</span>
                  <button id="ep-save-meta" class="btn sm" style="display:none" data-ep-filename="${ep.filename.esc()}">Save episode</button>
                  <span id="ep-meta-msg" class="tiny muted"></span>
                  <button id="ep-repull" class="btn sm ghost" data-ep-filename="${ep.filename.esc()}">Re-pull from TMDB</button>
                </div>
              </div>
            </div>
          </div>

          <!-- tracks -->
          <div class="card">
            <div class="row center" style="margin:0 0 12px">
              <h4 style="margin:0">
                Tracks — language &amp; order
                ${if (ep.tracks.any { it.language.isNullOrBlank() && it.kind != TrackKind.VIDEO && it.kind != TrackKind.DATA })
                    """<span class="badge bad" style="margin-left:8px">untagged</span>""" else ""}
              </h4>
              <span class="spacer"></span>
              <a class="btn sm ghost" id="open-track-order" href="#" data-media-id="${item.id}" data-ep-filename="${ep.filename.esc()}">Full track order ↗</a>
            </div>
            $trackManagerHtml
          </div>

          <!-- nav row -->
          <div class="row center" style="margin-top:6px;gap:8px">
            <button id="ep-skip" class="btn ghost">Skip</button>
            <span class="spacer"></span>
            <button id="ep-prev" class="btn ghost">‹ Previous</button>
            <button id="ep-next-issue" class="btn primary">Next issue ↓</button>
          </div>
        </div>
    """.trimIndent()
}

private fun buildTrackManager(item: MediaItem, ep: Episode): String {
    val tab = stTrackTab[ep.filename] ?: "audio"
    val audioTracks = ep.tracks.filter { it.kind == TrackKind.AUDIO }
    val subTracks = ep.tracks.filter { it.kind == TrackKind.SUBTITLE }
    val tracks = if (tab == "audio") audioTracks else subTracks

    val audioUntagged = audioTracks.count { it.language.isNullOrBlank() }
    val subUntagged = subTracks.count { it.language.isNullOrBlank() }

    val tabBar = """
        <div class="row center" style="gap:10px;margin-bottom:12px">
          <span class="seg">
            <span id="tm-tab-audio" class="${if (tab == "audio") "on" else ""}" data-ep="${ep.filename.esc()}">
              Audio <span class="mono" style="opacity:.7">· ${audioTracks.size}</span>
              ${if (audioUntagged > 0) """<span style="margin-left:5px;color:var(--bad);font-weight:700">⚠ $audioUntagged</span>""" else ""}
            </span>
            <span id="tm-tab-sub" class="${if (tab == "subtitle") "on" else ""}" data-ep="${ep.filename.esc()}">
              Subtitles <span class="mono" style="opacity:.7">· ${subTracks.size}</span>
              ${if (subUntagged > 0) """<span style="margin-left:5px;color:var(--bad);font-weight:700">⚠ $subUntagged</span>""" else ""}
            </span>
          </span>
          <span class="spacer"></span>
          <span class="muted tiny"><b>★</b> = auto-selected on playback &nbsp;·&nbsp; <b>▲▼</b> = physical order</span>
        </div>
    """.trimIndent()

    if (tracks.isEmpty()) {
        return """$tabBar<div class="box flat tiny muted" style="padding:14px 12px;text-align:center">No ${if (tab == "audio") "audio" else "subtitle"} tracks in this episode.</div>"""
    }

    val trackRows = tracks.joinToString("") { track ->
        val untagged = track.language.isNullOrBlank()
        val bgStyle = if (untagged) "background:var(--bad-soft);border-color:var(--bad)" else ""
        val defStyle = if (track.default) "border-color:rgba(245,184,64,.45)" else ""
        val pillsHtml = ST_QUICK_LANGS.joinToString("") { l ->
            """<span class="lang-assign-btn btn sm" data-media-id="${item.id}" data-ep-filename="${ep.filename.esc()}" data-specifier="${track.specifier.esc()}" data-lang="$l">$l</span>"""
        }
        val langDisplay = if (!untagged) {
            """<span class="lang" style="cursor:pointer" data-specifier="${track.specifier.esc()}">${track.language}</span>"""
        } else {
            """<span class="badge bad">no language</span>"""
        }
        val assignRow = if (untagged) """
            <div class="pill-row" style="margin-top:10px;padding-left:2px">
              <span class="muted tiny" style="margin-right:2px">assign:</span>
              $pillsHtml
              <input class="ep-lang-other input" style="width:70px;font-size:.78rem;padding:2px 6px"
                placeholder="other…" data-media-id="${item.id}" data-ep-filename="${ep.filename.esc()}" data-specifier="${track.specifier.esc()}" maxlength="10">
            </div>
        """.trimIndent() else ""
        val resultId = "tm-result-${track.specifier.replace(":", "-")}"

        """<div class="box flat" id="${resultId}-row" style="padding:9px 11px;margin-bottom:8px;$bgStyle$defStyle">
             <div class="row center" style="gap:11px">
               <span class="num" style="width:30px;text-align:center;font-size:.78rem">${tracks.indexOf(track) + 1}</span>
               <span class="num" style="width:52px;font-size:.74rem">${track.specifier}</span>
               $langDisplay
               <span class="muted tiny mono">${track.codec}${if (!track.title.isNullOrBlank()) " · \"${track.title!!.esc()}\"" else ""}</span>
               <span class="spacer"></span>
               ${if (track.default)
                   """<span class="badge warn">★ default</span>"""
               else
                   """<button class="ep-set-default btn sm ghost" data-media-id="${item.id}" data-ep-filename="${ep.filename.esc()}" data-specifier="${track.specifier.esc()}">set default ★</button>"""}
               <span id="$resultId" class="tiny" style="min-width:2ch;text-align:center"></span>
             </div>
             $assignRow
           </div>"""
    }

    val noteHtml = """
        <div class="note blue" style="margin-top:4px;font-size:.8rem">
          <b>★ Default</b> — auto-selected on playback (written by mkvpropedit, no re-encode).
          <b>▲▼ Order</b> — physical track index; the first audio language wins for metadata.
          Changes apply immediately when you click.
        </div>
    """.trimIndent()

    return "$tabBar<div>$trackRows</div>$noteHtml"
}

private fun wireStListeners(item: MediaItem) {
    val container = stContainer ?: return

    // Rail toggle
    container.querySelector("#st-rail-toggle")?.addEventListener("click") {
        stRailOpen = !stRailOpen
        renderSeriesTriagePage()
    }

    // Breadcrumb nav
    container.querySelector("#st-breadcrumb")?.addEventListener("click") {
        App.navigate("/media/${item.id}")
    }

    // Rail card clicks
    container.querySelectorAll(".st-rail-card").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") {
                val step = el.getAttribute("data-step")?.toIntOrNull() ?: 0
                stActiveStep = step
                renderSeriesTriagePage()
            }
        }
    }

    // Filter toggle
    container.querySelector("#st-filter-all")?.addEventListener("click") {
        stOnlyIssues = false; renderSeriesTriagePage()
    }
    container.querySelector("#st-filter-issues")?.addEventListener("click") {
        stOnlyIssues = true; renderSeriesTriagePage()
    }

    // Series step: fetch artwork
    container.querySelector("#st-fetch-art")?.addEventListener("click") {
        val resultEl = container.querySelector("#st-art-result") as? HTMLElement ?: return@addEventListener
        resultEl.innerHTML = """<span class="muted tiny">Fetching artwork…</span>"""
        stScope?.launch {
            val status = MediaApi.fetchArtwork(item.id)
            resultEl.innerHTML = if (status != null)
                """<span class="badge ok">Artwork fetched</span>"""
            else
                """<span class="badge bad">Artwork fetch failed</span>"""
        }
    }

    // First issue button
    container.querySelector("#st-first-issue")?.addEventListener("click") {
        val idx = item.episodes.indexOfFirst { it.issueCount > 0 }
        if (idx >= 0) { stActiveStep = idx + 1; renderSeriesTriagePage() }
    }

    // Save NFO
    container.querySelector("#st-save-nfo")?.addEventListener("click") {
        stScope?.launch {
            val result = MediaApi.writeNfo(item.id)
            // Could also write episode NFOs
            MediaApi.writeEpisodeNfos(item.id)
        }
    }

    // Save & tell Jellyfin
    container.querySelector("#st-save-jellyfin")?.addEventListener("click") {
        stScope?.launch {
            MediaApi.writeNfo(item.id)
            MediaApi.writeEpisodeNfos(item.id)
            MediaApi.jellyfinRefresh(item.id)
        }
    }

    // Series metadata inputs — mark dirty and reveal save button
    val metaInputIds = listOf("#st-title", "#st-year", "#st-overview", "#st-network")
    for (sel in metaInputIds) {
        container.querySelector(sel)?.addEventListener("input") {
            stMetaDirty = true
            (container.querySelector("#st-save-meta") as? HTMLElement)?.style?.display = ""
        }
    }

    // Save series metadata
    container.querySelector("#st-save-meta")?.addEventListener("click") {
        val msgEl = container.querySelector("#st-meta-msg") as? HTMLElement ?: return@addEventListener
        val title = (container.querySelector("#st-title") as? HTMLInputElement)?.value?.trim() ?: item.title
        val year = (container.querySelector("#st-year") as? HTMLInputElement)?.value?.toIntOrNull()
        val overview = (container.querySelector("#st-overview") as? HTMLElement)?.let {
            (it as? org.w3c.dom.HTMLTextAreaElement)?.value ?: it.textContent ?: ""
        }?.trim()
        val network = (container.querySelector("#st-network") as? HTMLInputElement)?.value?.trim()
        msgEl.textContent = "Saving…"
        stScope?.launch {
            val updated = MediaApi.editMetadata(
                id = item.id,
                title = title.ifEmpty { null },
                year = year,
                overview = overview?.ifEmpty { null },
                network = network?.ifEmpty { null },
            )
            if (updated != null) {
                stItem = updated
                stMetaDirty = false
                (container.querySelector("#st-save-meta") as? HTMLElement)?.style?.display = "none"
                msgEl.textContent = "Saved ✓"
            } else {
                msgEl.textContent = "Save failed"
            }
        }
    }

    wireEpisodeStepListeners(item)
}

private fun wireEpisodeStepListeners(item: MediaItem) {
    val container = stContainer ?: return
    val ep = stItem?.episodes?.getOrNull(stActiveStep - 1) ?: return

    // Tab switcher
    container.querySelector("#tm-tab-audio")?.addEventListener("click") {
        stTrackTab[ep.filename] = "audio"; renderSeriesTriagePage()
    }
    container.querySelector("#tm-tab-sub")?.addEventListener("click") {
        stTrackTab[ep.filename] = "subtitle"; renderSeriesTriagePage()
    }

    // Language assign buttons (quick pills)
    container.querySelectorAll(".lang-assign-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val mediaId = btn.getAttribute("data-media-id") ?: return@addEventListener
                val epFilename = btn.getAttribute("data-ep-filename") ?: return@addEventListener
                val specifier = btn.getAttribute("data-specifier") ?: return@addEventListener
                val lang = btn.getAttribute("data-lang") ?: return@addEventListener
                val resultId = "tm-result-${specifier.replace(":", "-")}"
                (container.querySelector("#$resultId") as? HTMLElement)?.textContent = "…"
                stScope?.launch {
                    val ok = stAssignEpisodeLanguage(mediaId, epFilename, specifier, lang)
                    val resultEl = container.querySelector("#$resultId") as? HTMLElement
                    if (ok) {
                        resultEl?.textContent = "✓"
                        resultEl?.setAttribute("style", "min-width:2ch;text-align:center;color:var(--ok)")
                        // Reload to update issue counts
                        val updated = MediaApi.get(item.id)
                        if (updated != null) { stItem = updated; renderSeriesTriagePage() }
                    } else {
                        resultEl?.textContent = "✗"
                        resultEl?.setAttribute("style", "min-width:2ch;text-align:center;color:var(--bad)")
                    }
                }
            }
        }
    }

    // "Other" language input — assign on Enter
    container.querySelectorAll(".ep-lang-other").let { nodes ->
        for (i in 0 until nodes.length) {
            val input = nodes.item(i) as? HTMLInputElement ?: continue
            input.addEventListener("keydown") { ev ->
                if ((ev as? org.w3c.dom.events.KeyboardEvent)?.key == "Enter") {
                    val lang = input.value.trim()
                    if (lang.isBlank()) return@addEventListener
                    val mediaId = input.getAttribute("data-media-id") ?: return@addEventListener
                    val epFilename = input.getAttribute("data-ep-filename") ?: return@addEventListener
                    val specifier = input.getAttribute("data-specifier") ?: return@addEventListener
                    stScope?.launch {
                        val ok = stAssignEpisodeLanguage(mediaId, epFilename, specifier, lang)
                        if (ok) {
                            val updated = MediaApi.get(item.id)
                            if (updated != null) { stItem = updated; renderSeriesTriagePage() }
                        }
                    }
                }
            }
        }
    }

    // Set default track
    container.querySelectorAll(".ep-set-default").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val mediaId = btn.getAttribute("data-media-id") ?: return@addEventListener
                val epFilename = btn.getAttribute("data-ep-filename") ?: return@addEventListener
                val specifier = btn.getAttribute("data-specifier") ?: return@addEventListener
                stScope?.launch {
                    val ok = MediaApi.setEpisodeDefaultTrack(mediaId, epFilename, specifier)
                    if (ok) {
                        val updated = MediaApi.get(item.id)
                        if (updated != null) { stItem = updated; renderSeriesTriagePage() }
                    }
                }
            }
        }
    }

    // Fetch still
    container.querySelector("#ep-fetch-still")?.addEventListener("click") {
        stScope?.launch {
            MediaApi.fetchEpisodeStills(item.id)
            val updated = MediaApi.get(item.id)
            if (updated != null) { stItem = updated; renderSeriesTriagePage() }
        }
    }

    // Episode metadata inputs — reveal save button on change
    for (sel in listOf("#ep-title-input", ".ep-overview-input")) {
        container.querySelector(sel)?.addEventListener("input") {
            (container.querySelector("#ep-save-meta") as? HTMLElement)?.style?.display = ""
        }
    }

    // Save episode metadata
    container.querySelector("#ep-save-meta")?.addEventListener("click") {
        val epFilename = (container.querySelector("#ep-save-meta") as? HTMLElement)
            ?.getAttribute("data-ep-filename") ?: return@addEventListener
        val msgEl = container.querySelector("#ep-meta-msg") as? HTMLElement ?: return@addEventListener
        val title = (container.querySelector("#ep-title-input") as? HTMLInputElement)?.value?.trim()
        val overview = (container.querySelector(".ep-overview-input") as? org.w3c.dom.HTMLTextAreaElement)?.value?.trim()
        msgEl.textContent = "Saving…"
        stScope?.launch {
            val ok = MediaApi.setEpisodeMetadata(item.id, epFilename, title, overview)
            if (ok) {
                (container.querySelector("#ep-save-meta") as? HTMLElement)?.style?.display = "none"
                msgEl.textContent = "Saved ✓"
                val updated = MediaApi.get(item.id)
                if (updated != null) stItem = updated
            } else {
                msgEl.textContent = "Save failed"
            }
        }
    }

    // Re-pull from TMDB
    container.querySelector("#ep-repull")?.addEventListener("click") {
        stScope?.launch {
            MediaApi.repull(item.id)
            val updated = MediaApi.get(item.id)
            if (updated != null) { stItem = updated; renderSeriesTriagePage() }
        }
    }

    // Open track order
    container.querySelector("#open-track-order")?.addEventListener("click") { ev ->
        ev.preventDefault()
        val mediaId = (ev.target as? HTMLElement)?.getAttribute("data-media-id") ?: item.id
        val epFile = (ev.target as? HTMLElement)?.getAttribute("data-ep-filename") ?: ""
        App.navigate("/track-order?id=$mediaId&ep=$epFile")
    }

    // Prev / Next issue navigation
    container.querySelector("#ep-prev")?.addEventListener("click") {
        if (stActiveStep > 0) { stActiveStep--; renderSeriesTriagePage() }
    }
    container.querySelector("#ep-next-issue")?.addEventListener("click") {
        val it2 = stItem ?: return@addEventListener
        val currentEpIdx = stActiveStep - 1
        val nextIssueEpIdx = it2.episodes.indexOfFirst { ep2 ->
            it2.episodes.indexOf(ep2) > currentEpIdx && ep2.issueCount > 0
        }
        if (nextIssueEpIdx >= 0) { stActiveStep = nextIssueEpIdx + 1; renderSeriesTriagePage() }
        else if (stActiveStep < it2.episodes.size) { stActiveStep++; renderSeriesTriagePage() }
    }

    // Skip
    container.querySelector("#ep-skip")?.addEventListener("click") {
        val it2 = stItem ?: return@addEventListener
        if (stActiveStep < it2.episodes.size) { stActiveStep++; renderSeriesTriagePage() }
    }
}

private suspend fun stAssignEpisodeLanguage(mediaId: String, epFilename: String, specifier: String, language: String): Boolean =
    runCatching {
        val encoded = encodeURIComponent(epFilename)
        val response = httpClient.post("/api/triage/$mediaId/episodes/$encoded/tracks/$specifier/language") {
            contentType(ContentType.Application.Json)
            setBody("""{"language":"$language"}""")
        }
        response.status == HttpStatusCode.OK
    }.getOrDefault(false)
