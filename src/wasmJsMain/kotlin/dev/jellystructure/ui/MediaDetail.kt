package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.ArtworkStatus
import dev.jellystructure.api.HistoryEntry
import dev.jellystructure.api.MediaApi
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement

private const val TMDB_IMG_LG = "https://image.tmdb.org/t/p/w500"

fun renderMediaDetail(container: Element, scope: CoroutineScope, mediaId: String) {
    container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""
    scope.launch {
        val item = MediaApi.get(mediaId)
        if (item == null) {
            container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Item not found.</span>"""
            return@launch
        }
        renderDetailView(container, item, scope)
    }
}

private fun renderDetailView(container: Element, item: MediaItem, scope: CoroutineScope) {
    val isTvShow = item.kind == MediaKind.TV_SHOW

    val posterHtml = if (item.posterPath != null) {
        """<img src="$TMDB_IMG_LG${item.posterPath}" alt="${item.title.esc()}"
             style="width:100%;height:auto;border-radius:4px;">"""
    } else {
        """<div class="imgslot" style="height:260px;"><div class="x"></div><span>No poster</span></div>"""
    }

    val overviewHtml = if (!item.overview.isNullOrBlank()) {
        """<div class="field">
             <label>Plot</label>
             <div class="input" style="min-height:70px;align-items:flex-start;">${item.overview.esc()}</div>
           </div>"""
    } else ""

    val genresHtml = if (item.genres.isNotEmpty()) {
        """<div class="field">
             <label>Genres</label>
             <div style="display:flex;flex-wrap:wrap;gap:5px">
               ${item.genres.joinToString("") { """<span class="chip">${it.esc()}</span>""" }}
             </div>
           </div>"""
    } else ""

    val issueBadge = when {
        item.languageMix ->
            """<span class="badge warn">language mix — writes blocked</span>"""
        item.issueCount > 0 ->
            """<span class="badge bad">${item.issueCount} untagged track${if (item.issueCount != 1) "s" else ""}</span>"""
        else ->
            """<span class="badge ok">all tracks tagged</span>"""
    }

    val resolvedLangBadge = if (!item.resolvedLanguage.isNullOrBlank()) {
        """<span class="badge" title="TMDB fetch language resolved from track order">lang: ${item.resolvedLanguage.esc()}</span>"""
    } else ""

    val nfoDisabled = item.tmdbId == null || item.languageMix
    val nfoDisabledReason = when {
        item.languageMix -> "Language mix — resolve track languages first"
        item.tmdbId == null -> "No TMDB match"
        else -> ""
    }

    // Tab list differs for TV shows vs movies
    val tabItems = if (isTvShow) {
        listOf(
            "overview" to "Overview",
            "episodes" to "Seasons &amp; episodes",
            "artwork" to "Artwork",
            "nfo" to "NFO raw",
            "history" to "History",
        )
    } else {
        listOf(
            "overview" to "Overview",
            "tracks" to "Tracks &amp; order",
            "artwork" to "Artwork",
            "nfo" to "NFO raw",
            "history" to "History",
        )
    }
    val tabIds = tabItems.map { it.first }

    val tabBarHtml = tabItems.joinToString("") { (key, label) ->
        val active = if (key == "overview") " active" else ""
        """<span class="seg-item$active" data-tab="$key">$label</span>"""
    }

    // TV overview banner (replaces the old tvSeriesSectionHtml in overview tab)
    val tvOverviewBanner = if (isTvShow) {
        if (item.languageMix) {
            """<div class="card" style="border-left:3px solid var(--warn,#f59e0b);padding:14px 16px;">
                 <div class="row center" style="gap:8px;margin-bottom:6px;">
                   <span class="badge warn">Language Mix Detected</span>
                 </div>
                 <p style="margin:0;font-size:.88rem;">Audio tracks differ across sampled episodes — this series cannot be uniformly language-resolved. NFO and artwork writes are blocked until the inconsistency is resolved.</p>
               </div>"""
        } else {
            val epCount = item.episodes.size
            val countNote = if (epCount > 0) " Probed $epCount episodes." else ""
            """<div class="card" style="border-left:3px solid var(--ok,#22c55e);padding:14px 16px;">
                 <div class="row center" style="gap:8px;margin-bottom:6px;">
                   <span class="badge ok">Uniform Audio Languages</span>
                   ${if (!item.resolvedLanguage.isNullOrBlank()) """<span class="badge">lang: ${item.resolvedLanguage.esc()}</span>""" else ""}
                 </div>
                 <p style="margin:0;font-size:.88rem;">Audio languages are consistent across all probed episodes.$countNote${if (!item.resolvedLanguage.isNullOrBlank()) " TMDB metadata fetched in <strong>${item.resolvedLanguage.esc()}</strong>." else ""}</p>
               </div>"""
        }
    } else ""

    val tracksHtml = if (!isTvShow) buildTracksTable(item.tracks) else ""
    val episodesTabHtml = if (isTvShow) buildEpisodesTab(item) else ""

    container.innerHTML = """
        <div class="pagebar">
          <button id="back-btn" class="btn sm ghost">‹ Library</button>
          <h2>${item.title.esc()} <span class="muted">${if (item.year != null) "(${item.year})" else ""}</span></h2>
          ${if (item.tmdbId != null) """<span class="badge ok">TMDB matched</span>""" else """<span class="badge warn">No TMDB match</span>"""}
          $resolvedLangBadge
          <span class="spacer"></span>
          <button id="repull-btn" class="btn sm ghost">Re-pull from TMDB</button>
          ${if (!isTvShow) """<button id="track-order-btn" class="btn sm ghost">Track order →</button>""" else ""}
          <button id="write-nfo-btn" class="btn primary" ${if (nfoDisabled) """disabled title="${nfoDisabledReason.esc()}"""" else ""}>Save → NFO</button>
        </div>

        <div id="detail-msg" style="display:none;margin-bottom:14px"></div>

        <div class="seg" id="detail-tabs" style="margin-bottom:16px;">$tabBarHtml</div>

        <div id="tab-overview">
          $tvOverviewBanner
          <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap;">
            <div class="col" style="width:220px;flex:none;">
              <div class="card">
                <h4 style="margin:0 0 10px;">Poster</h4>
                $posterHtml
              </div>
              <div class="card">
                <h4 style="margin:0 0 8px;">Identity</h4>
                <div class="field" style="margin:0 0 6px;">
                  <label>TMDB id</label>
                  <div class="input">${item.tmdbId ?: "—"}</div>
                </div>
                <div class="field" style="margin:0 0 6px;">
                  <label>Original language</label>
                  <div class="input">${item.originalLanguage ?: "—"}</div>
                </div>
                <div class="field" style="margin:0;">
                  <label>Kind</label>
                  <div class="input">${item.kind.name.lowercase().replace('_', ' ')}</div>
                </div>
              </div>
            </div>
            <div class="col fill">
              <div class="card">
                <div class="row">
                  <div class="field fill">
                    <label>Title</label>
                    <div class="input">${item.title.esc()}</div>
                  </div>
                  <div class="field" style="width:110px;">
                    <label>Year</label>
                    <div class="input">${item.year ?: "—"}</div>
                  </div>
                </div>
                $overviewHtml
                $genresHtml
                <div class="field">
                  <label>File path</label>
                  <div class="input mono" style="font-size:.82rem;word-break:break-all;">${item.path.esc()}</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        ${if (!isTvShow) """
        <div id="tab-tracks" style="display:none;">
          <div class="card">
            <div class="row center" style="margin-bottom:10px;">
              <h4 style="margin:0;">Embedded tracks</h4>
              <span class="spacer"></span>
              $issueBadge
            </div>
            $tracksHtml
          </div>
        </div>""" else ""}

        ${if (isTvShow) """<div id="tab-episodes" style="display:none;">$episodesTabHtml</div>""" else ""}

        <div id="tab-artwork" style="display:none;">
          <div class="card" id="artwork-card">
            <div class="row center" style="margin-bottom:10px">
              <h4 style="margin:0">Artwork</h4>
              <span class="spacer"></span>
              <button id="upload-poster-btn" class="btn sm ghost">Upload poster</button>
              <button id="upload-fanart-btn" class="btn sm ghost">Upload fanart</button>
              <button id="fetch-artwork-btn" class="btn sm ghost">Download from TMDB</button>
            </div>
            <div id="artwork-status"><span class="muted tiny">Checking…</span></div>
            <iframe id="upload-frame" name="upload-frame" style="display:none"></iframe>
            <form id="poster-form" method="post" action="/api/media/${item.id}/artwork/upload"
                  enctype="multipart/form-data" target="upload-frame" style="display:none">
              <input type="hidden" name="type" value="poster">
              <input type="file" id="poster-file" name="file" accept="image/jpeg,image/jpg,image/png">
            </form>
            <form id="fanart-form" method="post" action="/api/media/${item.id}/artwork/upload"
                  enctype="multipart/form-data" target="upload-frame" style="display:none">
              <input type="hidden" name="type" value="fanart">
              <input type="file" id="fanart-file" name="file" accept="image/jpeg,image/jpg,image/png">
            </form>
          </div>
        </div>

        <div id="tab-nfo" style="display:none;">
          <div class="card" id="nfo-card">
            <div class="row center" style="margin-bottom:10px;">
              <h4 style="margin:0">NFO (written to disk)</h4>
              <span class="spacer"></span>
              <span class="muted tiny">Click "Save → NFO" to write and view here</span>
            </div>
            <pre class="log" id="nfo-raw" style="font-size:.73rem;line-height:1.5;max-height:420px;overflow:auto"></pre>
          </div>
        </div>

        <div id="tab-history" style="display:none;">
          <div class="card" id="history-card">
            <h4 style="margin:0 0 12px;">Action history</h4>
            <div id="history-list"><span class="muted tiny">Loading…</span></div>
          </div>
        </div>
    """.trimIndent()

    document.getElementById("back-btn")?.addEventListener("click") {
        App.navigate("/library")
    }

    document.getElementById("track-order-btn")?.addEventListener("click") {
        App.navigate("/track-order?id=${item.id}")
    }

    document.getElementById("repull-btn")?.addEventListener("click") {
        scope.launch { handleRepull(item, container, scope) }
    }

    document.getElementById("write-nfo-btn")?.addEventListener("click") {
        scope.launch { handleWriteNfo(item.id) }
    }

    document.getElementById("fetch-artwork-btn")?.addEventListener("click") {
        scope.launch { handleFetchArtwork(item.id) }
    }

    document.getElementById("upload-poster-btn")?.addEventListener("click") {
        (document.getElementById("poster-file") as? HTMLInputElement)?.click()
    }
    document.getElementById("upload-fanart-btn")?.addEventListener("click") {
        (document.getElementById("fanart-file") as? HTMLInputElement)?.click()
    }
    document.getElementById("poster-file")?.addEventListener("change") {
        (document.getElementById("poster-form") as? HTMLFormElement)?.submit()
        scope.launch {
            delay(2000)
            loadArtworkStatus(item.id)
            showDetailMsg("Poster upload submitted.", true)
        }
    }
    document.getElementById("fanart-file")?.addEventListener("change") {
        (document.getElementById("fanart-form") as? HTMLFormElement)?.submit()
        scope.launch {
            delay(2000)
            loadArtworkStatus(item.id)
            showDetailMsg("Fanart upload submitted.", true)
        }
    }

    // Wire up episode row toggles
    if (isTvShow) wireEpisodeToggles()

    // Tab switching
    document.getElementById("detail-tabs")?.let { tabBar ->
        tabBar.querySelectorAll(".seg-item").let { segItems ->
            for (i in 0 until segItems.length) {
                val segItem = segItems.item(i) as? HTMLElement ?: continue
                segItem.addEventListener("click") {
                    val tab = segItem.getAttribute("data-tab") ?: return@addEventListener
                    for (j in 0 until segItems.length) {
                        (segItems.item(j) as? HTMLElement)?.className = "seg-item"
                    }
                    segItem.className = "seg-item active"
                    tabIds.forEach { id ->
                        val panel = document.getElementById("tab-$id") as? HTMLElement
                        panel?.style?.display = if (id == tab) "block" else "none"
                    }
                    if (tab == "history") scope.launch { loadHistory(item.id) }
                    if (tab == "artwork") scope.launch { loadArtworkStatus(item.id) }
                }
            }
        }
    }

    scope.launch { loadArtworkStatus(item.id) }
}

private fun buildEpisodesTab(item: MediaItem): String {
    if (item.episodes.isEmpty()) {
        return """<div class="card"><span class="muted tiny">No episode data available — run a scan to populate.</span></div>"""
    }

    // Compute language voting across all episodes
    val votes = mutableMapOf<String, Int>()
    for (ep in item.episodes) {
        val lang = ep.resolvedLanguage ?: "?"
        votes[lang] = (votes[lang] ?: 0) + 1
    }
    val total = item.episodes.size
    val sortedVotes = votes.toList().sortedByDescending { it.second }
    val winner = sortedVotes.firstOrNull()?.first

    val voteRows = sortedVotes.joinToString("") { (lang, count) ->
        val pct = (count * 100) / total
        val isWinner = lang == winner && sortedVotes.size > 1
        val langColor = if (isWinner) "var(--ok,#22c55e)" else "var(--ink-soft)"
        val winnerTag = if (isWinner) """<span class="badge ok" style="font-size:.65rem;">winner</span>""" else ""
        """<div style="display:flex;align-items:center;gap:8px;padding:4px 0;">
             <span class="mono" style="min-width:28px;color:$langColor;font-size:.85rem;">${lang.esc()}</span>
             <div style="flex:1;height:5px;background:var(--fill-3);border-radius:3px;">
               <div style="width:$pct%;height:100%;background:var(--hi);border-radius:3px;"></div>
             </div>
             <span class="muted tiny" style="min-width:42px;text-align:right;">$count/$total</span>
             $winnerTag
           </div>"""
    }

    val votingCard = """
        <div class="card" style="width:220px;flex:none;">
          <h4 style="margin:0 0 10px;">Language voting</h4>
          $voteRows
          <div class="field" style="margin-top:12px;margin-bottom:0;">
            <label>tvshow.nfo language</label>
            <div class="input mono" style="font-size:.88rem;">${(item.resolvedLanguage ?: winner ?: "—").esc()}</div>
          </div>
        </div>"""

    // Group episodes by season
    val bySeason = item.episodes.groupBy { it.seasonNumber }
    val seasonBlocks = bySeason.toList()
        .sortedBy { it.first ?: 999 }
        .joinToString("") { (season, eps) ->
            val seasonLabel = if (season != null) "Season $season" else "Unsorted"
            val seasonIssues = eps.sumOf { it.issueCount }
            val issueSummary = if (seasonIssues > 0)
                """<span class="badge bad" style="font-size:.72rem;">$seasonIssues untagged</span>"""
            else ""
            val rows = eps.mapIndexed { idx, ep -> buildEpisodeRow(ep, season, idx) }.joinToString("")
            """<div style="margin-bottom:20px;">
                 <div class="row center" style="margin-bottom:8px;">
                   <h4 style="margin:0;">${seasonLabel.esc()}</h4>
                   <span class="chip" style="margin-left:8px;font-size:.75rem;">${eps.size} ep</span>
                   $issueSummary
                   <span class="spacer"></span>
                 </div>
                 $rows
               </div>"""
        }

    return """
        <div class="row" style="align-items:flex-start;gap:16px;flex-wrap:wrap;">
          $votingCard
          <div class="col fill">$seasonBlocks</div>
        </div>"""
}

private fun buildEpisodeRow(ep: Episode, season: Int?, idx: Int): String {
    val epCode = if (season != null && ep.episodeNumber != null) {
        "S${season.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
    } else ep.filename.substringBeforeLast('.')

    val audioTracks = ep.tracks.filter { it.kind == TrackKind.AUDIO }
    val subTracks = ep.tracks.filter { it.kind == TrackKind.SUBTITLE }

    val trackChips = (audioTracks + subTracks).joinToString("") { t ->
        val lang = t.language?.esc() ?: "?"
        val defaultMark = if (t.default) " ★" else ""
        val badStyle = if (t.language == null) "border-color:var(--bad);background:var(--bad-soft);" else ""
        """<span class="chip mono" style="font-size:.72rem;$badStyle">$lang · ${t.codec.esc()}$defaultMark</span>"""
    }

    val issueBadge = if (ep.issueCount > 0)
        """<span class="badge bad" style="font-size:.7rem;">${ep.issueCount} untagged</span>"""
    else ""

    val trackTableRows = (audioTracks + subTracks).joinToString("") { t ->
        val langCell = if (t.language != null)
            """<span class="lang">${t.language.esc()}</span>"""
        else
            """<span class="badge bad" style="font-size:.7rem;">none</span>"""
        val rowClass = if (t.language == null) """ class="attn"""" else ""
        """<tr$rowClass>
             <td class="num">${t.specifier.esc()}</td>
             <td>${t.kind.name.lowercase()}</td>
             <td>$langCell</td>
             <td>${t.title?.esc() ?: """<span class="muted">—</span>"""}</td>
             <td class="num">${t.codec.esc()}</td>
             <td>${if (t.default) """<span class="badge ok">default</span>""" else "—"}</td>
             <td>${if (t.forced) "yes" else "—"}</td>
           </tr>"""
    }

    val bodyId = "ep-body-s${season ?: 0}-$idx"
    val toggleId = "ep-toggle-s${season ?: 0}-$idx"

    return """
        <div style="border:1px solid var(--line);border-radius:6px;margin-bottom:6px;overflow:hidden;background:var(--fill-2);">
          <div id="$toggleId" class="ep-toggle-row" data-body="$bodyId"
               style="display:flex;align-items:center;gap:10px;padding:9px 12px;cursor:pointer;">
            <span class="num" style="min-width:64px;font-size:.82rem;">${epCode.esc()}</span>
            <div style="display:flex;gap:4px;flex-wrap:wrap;flex:1;">$trackChips</div>
            $issueBadge
            <span class="ep-chev" style="color:var(--ink-soft);font-size:.9rem;margin-left:4px;">›</span>
          </div>
          <div id="$bodyId" style="display:none;padding:0 12px 12px;">
            ${if (trackTableRows.isNotEmpty()) """
            <table class="wf-table" style="margin:0 0 6px;">
              <tr><th>#</th><th>Kind</th><th>Lang</th><th>Title</th><th>Codec</th><th>Default</th><th>Forced</th></tr>
              $trackTableRows
            </table>""" else """<span class="muted tiny">No audio/subtitle tracks.</span>"""}
            <div class="muted tiny" style="margin-top:4px;font-family:monospace;">${ep.filename.esc()}</div>
          </div>
        </div>"""
}

private fun wireEpisodeToggles() {
    document.querySelectorAll(".ep-toggle-row").let { rows ->
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val bodyId = row.getAttribute("data-body") ?: continue
            row.addEventListener("click") {
                val body = document.getElementById(bodyId) as? HTMLElement ?: return@addEventListener
                val chev = row.querySelector(".ep-chev") as? HTMLElement
                if (body.style.display == "none") {
                    body.style.display = "block"
                    chev?.textContent = "⌄"
                } else {
                    body.style.display = "none"
                    chev?.textContent = "›"
                }
            }
        }
    }
}

private suspend fun loadHistory(id: String) {
    val listEl = document.getElementById("history-list") as? HTMLElement ?: return
    val entries = MediaApi.getHistory(id)
    if (entries.isEmpty()) {
        listEl.innerHTML = """<span class="muted tiny">No history yet — write an NFO or change a track default to create entries.</span>"""
        return
    }
    listEl.innerHTML = entries.joinToString("") { entry ->
        val actionLabel = when (entry.action) {
            "nfo_write" -> "NFO written"
            "artwork_fetch" -> "Artwork downloaded"
            "set_default" -> "Track default changed"
            "assign_language" -> "Language assigned"
            else -> entry.action
        }
        """<div style="display:flex;gap:10px;padding:6px 0;border-bottom:1px solid var(--border);">
             <span class="muted tiny" style="width:160px;flex-shrink:0;padding-top:1px;">${formatTimestamp(entry.timestamp.toDouble() * 1000.0)}</span>
             <div>
               <span class="chip" style="font-size:.72rem;">$actionLabel</span>
               <span class="muted tiny" style="margin-left:6px;">${entry.detail.esc()}</span>
             </div>
           </div>"""
    }
}

private suspend fun handleRepull(item: MediaItem, container: Element, scope: CoroutineScope) {
    val btn = document.getElementById("repull-btn") as? HTMLElement
    btn?.setAttribute("disabled", "true")
    btn?.textContent = "Pulling…"

    val updated = MediaApi.repull(item.id)

    btn?.removeAttribute("disabled")
    btn?.textContent = "Re-pull from TMDB"

    if (updated != null) {
        showDetailMsg("TMDB data refreshed. Reloading…", true)
        delay(600)
        renderDetailView(container, updated, scope)
    } else {
        showDetailMsg("Re-pull failed — no TMDB match found.", false)
    }
}

private suspend fun loadArtworkStatus(id: String) {
    val status = MediaApi.getArtworkStatus(id) ?: return
    renderArtworkStatus(status)
}

private fun renderArtworkStatus(status: ArtworkStatus) {
    val el = document.getElementById("artwork-status") as? HTMLElement ?: return
    fun badge(exists: Boolean, label: String) =
        """<span class="badge ${if (exists) "ok" else "bad"}" style="margin-right:6px">$label ${if (exists) "✓" else "missing"}</span>"""
    el.innerHTML = badge(status.posterExists, "poster.jpg") + badge(status.fanartExists, "fanart.jpg")
}

private suspend fun handleWriteNfo(id: String) {
    val btn = document.getElementById("write-nfo-btn") as? HTMLElement
    btn?.setAttribute("disabled", "true")
    btn?.textContent = "Writing…"

    val result = MediaApi.writeNfo(id)

    showDetailMsg(if (result != null) "NFO written to ${result.path}" else "NFO write failed.", result != null)
    btn?.removeAttribute("disabled")
    btn?.textContent = "Save → NFO"

    if (result != null) {
        val raw = MediaApi.getNfo(id)
        if (raw != null) {
            (document.getElementById("nfo-raw") as? HTMLElement)?.textContent = raw
        }
    }
}

private suspend fun handleFetchArtwork(id: String) {
    val btn = document.getElementById("fetch-artwork-btn") as? HTMLElement
    btn?.setAttribute("disabled", "true")
    btn?.textContent = "Downloading…"

    val status = MediaApi.fetchArtwork(id)
    if (status != null) renderArtworkStatus(status)
    showDetailMsg(
        if (status != null) "Artwork download complete." else "Artwork download failed.",
        status != null,
    )

    btn?.removeAttribute("disabled")
    btn?.textContent = "Download from TMDB"
}

private fun showDetailMsg(msg: String, ok: Boolean) {
    val el = document.getElementById("detail-msg") as? HTMLElement ?: return
    el.style.display = "block"
    el.innerHTML = """<span class="badge ${if (ok) "ok" else "bad"}">$msg</span>"""
}

private fun formatTimestamp(epochMs: Double): String = js("new Date(epochMs).toLocaleString()")

private fun buildTracksTable(tracks: List<Track>): String {
    val displayed = tracks.filter { it.kind != TrackKind.VIDEO && it.kind != TrackKind.DATA }
    if (displayed.isEmpty()) return """<span class="muted tiny">No audio or subtitle tracks found.</span>"""

    val rows = displayed.joinToString("") { track ->
        val langCell = if (track.language != null) {
            """<span class="lang">${track.language.esc()}</span>"""
        } else {
            """<span class="badge bad" style="font-size:.7rem;">none</span>"""
        }
        val defaultCell = if (track.default) """<span class="badge ok">default</span>""" else "—"
        val rowClass = if (track.language == null) """ class="attn"""" else ""
        val titleCell = track.title?.esc() ?: """<span class="muted">—</span>"""
        """<tr$rowClass>
             <td class="num">${track.specifier}</td>
             <td>${track.kind.name.lowercase()}</td>
             <td>$langCell</td>
             <td>$titleCell</td>
             <td class="num">${track.codec}</td>
             <td>$defaultCell</td>
             <td>${if (track.forced) "yes" else "—"}</td>
           </tr>"""
    }

    return """
        <table class="wf-table">
          <tr><th>#</th><th>Kind</th><th>Lang</th><th>Title</th><th>Codec</th><th>Default</th><th>Forced</th></tr>
          $rows
        </table>"""
}
