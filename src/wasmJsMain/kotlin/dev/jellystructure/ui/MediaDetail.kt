package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.MediaItem
import dev.jellystructure.api.Track
import dev.jellystructure.api.TrackKind
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element

private const val TMDB_IMG_LG = "https://image.tmdb.org/t/p/w500"

fun renderMediaDetail(container: Element, scope: CoroutineScope, mediaId: String) {
    container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""
    scope.launch {
        val item = MediaApi.get(mediaId)
        if (item == null) {
            container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Item not found.</span>"""
            return@launch
        }
        renderDetailView(container, item)
    }
}

private fun renderDetailView(container: Element, item: MediaItem) {
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

    val issueBadge = if (item.issueCount > 0) {
        """<span class="badge bad">${item.issueCount} untagged track${if (item.issueCount != 1) "s" else ""}</span>"""
    } else {
        """<span class="badge ok">all tracks tagged</span>"""
    }

    container.innerHTML = """
        <div class="pagebar">
          <button id="back-btn" class="btn sm ghost">‹ Library</button>
          <h2>${item.title.esc()} <span class="muted">${if (item.year != null) "(${item.year})" else ""}</span></h2>
          ${if (item.tmdbId != null) """<span class="badge ok">TMDB matched</span>""" else """<span class="badge warn">No TMDB match</span>"""}
          <span class="spacer"></span>
          <span class="btn primary disabled" title="Coming in P3">Save → disk</span>
        </div>

        <div class="pill-row" style="margin-bottom:14px;">
          <span class="chip" style="background:var(--hi);">Overview</span>
          <span class="chip">Tracks</span>
          <span class="chip">Artwork</span>
        </div>

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
              <div class="field">
                <label>File path</label>
                <div class="input mono" style="font-size:.82rem;word-break:break-all;">${item.path.esc()}</div>
              </div>
            </div>

            <div class="card">
              <div class="row center" style="margin-bottom:10px;">
                <h4 style="margin:0;">Embedded tracks</h4>
                <span class="spacer"></span>
                $issueBadge
              </div>
              $tracksHtml
            </div>
          </div>
        </div>
    """.trimIndent()

    document.getElementById("back-btn")?.addEventListener("click") {
        App.navigate("/library")
    }
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

