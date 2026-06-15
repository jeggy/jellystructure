package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.ArtworkStatus
import dev.jellystructure.api.MediaApi
import dev.jellystructure.model.MediaItem
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
    val posterHtml = if (item.posterPath != null) {
        """<img src="$TMDB_IMG_LG${item.posterPath}" alt="${item.title.esc()}"
             style="width:100%;height:auto;border-radius:4px;">"""
    } else {
        """<div class="imgslot" style="height:260px;"><div class="x"></div><span>No poster</span></div>"""
    }

    val tracksHtml = buildTracksTable(item.tracks)

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

    val tvSeriesSectionHtml = if (item.kind.name == "TV_SHOW") {
        if (item.languageMix) {
            """<div class="card" style="border-left:3px solid var(--warn,#f59e0b);padding:14px 16px;">
                 <div class="row center" style="gap:8px;margin-bottom:6px;">
                   <span class="badge warn">Language Mix Detected</span>
                 </div>
                 <p style="margin:0;font-size:.88rem;">Audio tracks differ across sampled episodes — this series cannot be uniformly language-resolved. NFO and artwork writes are blocked until the inconsistency is resolved or you assign a language override in Triage.</p>
               </div>"""
        } else {
            val audioLangs = item.tracks.filter { it.kind.name == "AUDIO" }.mapNotNull { it.language }
            val langsDisplay = if (audioLangs.isNotEmpty()) audioLangs.joinToString(", ") else "—"
            """<div class="card" style="border-left:3px solid var(--ok,#22c55e);padding:14px 16px;">
                 <div class="row center" style="gap:8px;margin-bottom:6px;">
                   <span class="badge ok">Uniform Audio Languages</span>
                 </div>
                 <p style="margin:0;font-size:.88rem;">Sampled up to 5 episodes — audio language set is consistent across samples: <strong>$langsDisplay</strong>.${if (!item.resolvedLanguage.isNullOrBlank()) " Resolved TMDB fetch language: <strong>${item.resolvedLanguage.esc()}</strong>." else ""}</p>
               </div>"""
        }
    } else ""

    container.innerHTML = """
        <div class="pagebar">
          <button id="back-btn" class="btn sm ghost">‹ Library</button>
          <h2>${item.title.esc()} <span class="muted">${if (item.year != null) "(${item.year})" else ""}</span></h2>
          ${if (item.tmdbId != null) """<span class="badge ok">TMDB matched</span>""" else """<span class="badge warn">No TMDB match</span>"""}
          $resolvedLangBadge
          <span class="spacer"></span>
          <button id="repull-btn" class="btn sm ghost">Re-pull from TMDB</button>
          <button id="track-order-btn" class="btn sm ghost">Track order →</button>
          <button id="write-nfo-btn" class="btn primary" ${if (nfoDisabled) """disabled title="${nfoDisabledReason.esc()}"""" else ""}>Save → NFO</button>
        </div>

        <div id="detail-msg" style="display:none;margin-bottom:14px"></div>

        $tvSeriesSectionHtml

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

            <div class="card">
              <div class="row center" style="margin-bottom:10px;">
                <h4 style="margin:0;">Embedded tracks</h4>
                <span class="spacer"></span>
                $issueBadge
              </div>
              $tracksHtml
            </div>

            <div class="card" id="nfo-card" style="display:none">
              <h4 style="margin:0 0 10px">NFO (written to disk)</h4>
              <pre class="log" id="nfo-raw" style="font-size:.73rem;line-height:1.5;max-height:360px;overflow:auto"></pre>
            </div>
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

    scope.launch { loadArtworkStatus(item.id) }
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
            val card = document.getElementById("nfo-card") as? HTMLElement
            val pre = document.getElementById("nfo-raw") as? HTMLElement
            card?.style?.display = "block"
            pre?.textContent = raw
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
