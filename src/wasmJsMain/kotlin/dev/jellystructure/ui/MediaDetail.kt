@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.encodeURIComponent
import dev.jellystructure.api.ArtworkStatus
import dev.jellystructure.api.ConfigApi
import dev.jellystructure.api.HistoryEntry
import dev.jellystructure.api.JsTag
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.DriftField
import dev.jellystructure.api.SeedingStatus
import dev.jellystructure.api.TmdbMatchResult
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
import org.w3c.dom.HTMLTextAreaElement

private const val TMDB_IMG_LG = "https://image.tmdb.org/t/p/w500"
private const val MONO_CODE_STYLE = "font-family:'JetBrains Mono',monospace;font-size:.78rem;"

fun renderMediaDetail(container: Element, scope: CoroutineScope, mediaId: String, initialTab: String? = null) {
    container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""
    scope.launch {
        val item = MediaApi.get(mediaId)
        if (item == null) {
            container.innerHTML = """<span class="muted" style="padding:24px;display:block;">Item not found.</span>"""
            return@launch
        }
        val config = ConfigApi.get()
        val fallbackLang = config?.config?.languageRules?.fallbackLanguage ?: "en"
        val jellyfinUrl = config?.config?.apiKeys?.jellyfinUrl?.trimEnd('/') ?: ""
        val tmdbLangs = if (item.tmdbId != null) MediaApi.getTmdbLanguages(item.id) else null
        val jsTags = dev.jellystructure.api.MetadataApi.getAllJsTags() ?: emptyList()
        renderDetailView(container, item, scope, fallbackLang, jellyfinUrl, tmdbLangs, jsTags = jsTags, initialTab = initialTab)
    }
}

private fun buildResolverTrace(item: MediaItem, fallbackLang: String): String {
    if (item.kind == MediaKind.TV_SHOW) return ""
    val audioTracks = item.tracks.filter { it.kind == TrackKind.AUDIO }
    if (audioTracks.isEmpty()) return ""
    val resolved = item.resolvedLanguage
    var winnerFound = false
    val traceLines = audioTracks.joinToString("") { t ->
        val spec = t.specifier.esc()
        when {
            t.language.isNullOrBlank() ->
                """<div class="muted">$spec <span style="font-size:.85em">??</span> untagged → skipped</div>"""
            t.language == resolved && !winnerFound -> {
                winnerFound = true
                """<div>$spec <span class="lang">${t.language.esc()}</span>? <span style="color:var(--ok)">✓ TMDB result → winner</span></div>"""
            }
            t.language == resolved ->
                """<div class="muted">$spec <span class="lang">${t.language.esc()}</span> → duplicate, already resolved</div>"""
            winnerFound ->
                """<div class="muted">$spec <span class="lang">${t.language.esc()}</span> → skipped (winner already found)</div>"""
            else ->
                """<div class="muted">$spec <span class="lang">${t.language.esc()}</span>? → tried, no TMDB result</div>"""
        }
    }
    val fallbackLine = if (!winnerFound && resolved != null) {
        """<div>fallback → <span class="lang">${resolved.esc()}</span> <span style="color:var(--ok)">✓ TMDB result → winner</span></div>"""
    } else if (!winnerFound) {
        """<div class="muted">fallback <span class="lang">${fallbackLang.esc()}</span> → no TMDB match</div>"""
    } else ""
    val resolvedBadge = if (resolved != null)
        """<span class="badge ok lang">${resolved.esc()}</span>"""
    else
        """<span class="badge warn">not resolved</span>"""
    return """
        <div class="override" style="margin-top:16px;">
          <div class="row center">
            <h4 style="margin:0;">Resolved metadata language</h4>
            <span class="spacer"></span>
            <span class="badge info">automatic</span>
          </div>
          <div class="tiny" style="margin-top:8px;">
            The resolver walks audio tracks in physical order and fetches TMDB metadata in the first language that returns a result. Track flags are never changed automatically.
          </div>
          <div class="box flat" style="margin-top:10px;background:var(--fill-2);">
            <div class="mono tiny" style="line-height:2.1;">
              $traceLines
              $fallbackLine
            </div>
            <hr class="dash" style="margin:9px 0;">
            <div class="row center"><span class="tiny">Fetching metadata in</span><span class="spacer"></span>$resolvedBadge</div>
          </div>
          <div class="tiny muted" style="margin-top:8px;">Global fallback is <span class="lang">${fallbackLang.esc()}</span> · <a href="#/settings">Language Settings →</a></div>
        </div>""".trimIndent()
}

private fun renderDetailView(container: Element, item: MediaItem, scope: CoroutineScope, fallbackLang: String = "en", jellyfinUrl: String = "", tmdbLangs: Set<String>? = null, jsTags: List<JsTag> = emptyList(), initialTab: String? = null) {
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

    val directorHtml = """<div class="field">
      <label>${if (item.kind == MediaKind.TV_SHOW) "Network" else "Director"} <button class="diff-trigger" id="diff-edit-director">≠</button></label>
      <input id="edit-director" class="input" value="${(if (item.kind == MediaKind.TV_SHOW) item.network else item.director)?.esc() ?: ""}" style="width:100%;" placeholder="—">
    </div>
    <div class="field">
      <label>Studio <button class="diff-trigger" id="diff-edit-studio">≠</button></label>
      <input id="edit-studio" class="input" value="${item.studio?.esc() ?: ""}" style="width:100%;" placeholder="—">
    </div>"""

    val currentTags = item.tags.toMutableList()
    val jsTagMap = jsTags.associateBy { it.name }
    fun tagChipHtml(tag: String): String {
        val jt = jsTagMap[tag]
        val dot = if (jt != null) """<span style="width:8px;height:8px;border-radius:50%;background:${jt.color};flex-shrink:0;display:inline-block;margin-right:3px;vertical-align:middle"></span>""" else ""
        return """<span class="chip" style="cursor:default;display:inline-flex;align-items:center;">$dot${tag.esc()} <span class="tag-rm" data-tag="${tag.esc()}" style="cursor:pointer;margin-left:4px;color:var(--bad);">✕</span></span>"""
    }
    val tagSuggestions = jsTags.joinToString("") { jt ->
        """<div class="tag-suggest-item" data-tag="${jt.name.esc()}" style="display:flex;align-items:center;gap:7px;padding:5px 10px;cursor:pointer;font-size:.85rem;border-radius:4px" onmouseover="this.style.background='var(--fill-2)'" onmouseout="this.style.background=''"><span style="width:10px;height:10px;border-radius:50%;background:${jt.color};flex-shrink:0;display:inline-block"></span>${jt.name.esc()}</div>"""
    }
    val tagsChipsHtml = """<div class="field" id="tags-section">
      <label>Tags <span class="muted tiny">(written to NFO &lt;tag&gt;)</span> <button class="diff-trigger" id="diff-tags">≠</button></label>
      <div id="tags-chips" style="display:flex;flex-wrap:wrap;gap:5px;margin-bottom:6px;">
        ${currentTags.joinToString("") { tagChipHtml(it) }}
      </div>
      <div style="display:flex;gap:6px;position:relative;flex-wrap:wrap">
        <div style="position:relative">
          <input id="tag-input" class="input" type="text" placeholder="add tag…" maxlength="40" style="width:160px;" autocomplete="off">
          <div id="tag-dropdown" style="display:none;position:absolute;top:calc(100% + 2px);left:0;z-index:50;background:var(--surface);border:1px solid var(--border);border-radius:6px;box-shadow:0 4px 16px rgba(0,0,0,.15);min-width:160px;padding:4px 0">$tagSuggestions</div>
        </div>
        <button id="tag-add-btn" class="btn sm ghost">Add</button>
      </div>
    </div>"""

    val issueBadge = when {
        item.languageMix ->
            """<span class="badge warn">multi-language series</span>"""
        item.issueCount > 0 ->
            """<span class="badge bad">${item.issueCount} untagged track${if (item.issueCount != 1) "s" else ""}</span>"""
        else ->
            """<span class="badge ok">all tracks tagged</span>"""
    }

    val resolvedLangBadge = if (!item.resolvedLanguage.isNullOrBlank()) {
        """<span class="badge" title="TMDB fetch language resolved from track order">lang: ${item.resolvedLanguage.esc()}</span>"""
    } else ""

    val nfoDisabled = item.tmdbId == null || (item.languageMix && item.resolvedLanguage.isNullOrBlank())
    val nfoDisabledReason = when {
        item.languageMix && item.resolvedLanguage.isNullOrBlank() ->
            "No primary language set — save a primary language in the language distribution card first"
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
    val activeTab = if (initialTab != null && tabIds.contains(initialTab)) initialTab else "overview"

    val tabBarHtml = tabItems.joinToString("") { (key, label) ->
        val active = if (key == activeTab) " active" else ""
        """<span class="seg-item$active" data-tab="$key">$label</span>"""
    }

    // Combined series-language card for the left rail — replaces both the old seriesLangCard
    // and the tvOverviewBanner (FR-U1 Phase 12).
    val seriesLangCard = if (isTvShow) {
        val votes = mutableMapOf<String, Int>()
        for (ep in item.episodes) {
            for (track in ep.tracks) {
                if (track.kind == TrackKind.AUDIO) {
                    val l = track.language ?: "?"
                    votes[l] = (votes[l] ?: 0) + 1
                }
            }
        }
        val totalTracks = votes.values.sum()
        val epCount = item.episodes.size
        val hasUntagged = votes.containsKey("?")
        val winner = item.resolvedLanguage
        val statusBadge = if (!item.languageMix)
            """<span class="badge ok" style="font-size:.7rem;padding:1px 6px;">Uniform</span>"""
        else
            """<span class="badge warn" style="font-size:.7rem;padding:1px 6px;">Mixed</span>"""

        val contentHtml = if (votes.isNotEmpty()) {
            val safeTotal = totalTracks.coerceAtLeast(1)
            val barsHtml = votes.toList().sortedByDescending { it.second }.joinToString("") { (lang, count) ->
                val pct = (count * 100) / safeTotal
                val isPrimary = lang == winner
                val barColor = when {
                    isPrimary -> "var(--ok)"
                    lang == "?" -> "var(--bad)"
                    else -> "var(--hi)"
                }
                val codeColor = when {
                    isPrimary -> "color:var(--ok);"
                    lang == "?" -> "color:var(--bad);"
                    else -> ""
                }
                """<div style="display:flex;align-items:center;gap:5px;padding:2px 0;">
                     <span class="mono" style="min-width:24px;font-size:.78rem;${codeColor}">${lang.esc()}</span>
                     <div style="flex:1;height:4px;background:var(--fill-3);border-radius:2px;">
                       <div style="width:$pct%;height:100%;background:$barColor;border-radius:2px;"></div>
                     </div>
                     <span class="tiny muted">$count</span>
                   </div>"""
            }
            """<div class="tiny muted" style="margin-bottom:8px;">${epCount} episode${if (epCount != 1) "s" else ""} · $totalTracks tracks total${if (hasUntagged) " · <span class='badge bad' style='font-size:.7rem;'>untagged — use Episodes tab</span>" else ""}</div>
               $barsHtml"""
        } else {
            """<div class="tiny muted" style="margin-bottom:8px;">No episode data yet.</div>"""
        }

        val overrideVal = (item.resolvedLanguage ?: "").esc()
        """<div class="card" style="margin-top:12px;">
             <div class="row center" style="gap:6px;margin-bottom:4px;flex-wrap:nowrap;">
               <h4 style="margin:0;font-size:.9rem;white-space:nowrap;">Series language</h4>
               $statusBadge
             </div>
             $contentHtml
             <hr style="margin:10px 0 8px;border:none;border-top:1px solid var(--line);">
             <label style="font-size:.75rem;font-weight:600;display:block;margin-bottom:5px;">NFO language</label>
             <input id="lang-override-input" class="input" style="width:100%;padding:3px 8px;font-size:.82rem;"
               placeholder="e.g. en" maxlength="10" value="$overrideVal">
             <div style="display:flex;gap:6px;align-items:center;margin-top:6px;">
               <button id="lang-override-btn" class="btn sm ghost">Save</button>
               <span id="lang-override-msg" class="tiny muted"></span>
             </div>
             <div class="tiny muted" style="margin-top:6px;line-height:1.4;">Used for TMDB metadata and tvshow.nfo writes. Does not affect audio tracks.</div>
           </div>"""
    } else ""

    val tracksHtml = if (!isTvShow) buildTracksTable(item.tracks) else ""

    // Embedded tracks summary for the overview tab (movies only — condensed read-only view)
    val embeddedTracksSummary = if (!isTvShow && item.tracks.any { it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE }) {
        val untagged = item.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null }
        val summaryBadge = if (untagged > 0)
            """<span class="badge bad" style="font-size:.72rem;">$untagged untagged</span>"""
        else
            """<span class="badge ok" style="font-size:.72rem;">all tagged</span>"""
        val trackChips = item.tracks
            .filter { it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE }
            .joinToString("") { t ->
                val lang = t.language?.esc() ?: "?"
                val badStyle = if (t.language == null) "border-color:var(--bad);" else ""
                val defMark = if (t.default) " ★" else ""
                """<span class="chip mono" style="font-size:.72rem;$badStyle">${t.kind.name.lowercase().first()} · $lang · ${t.codec.esc()}$defMark</span>"""
            }
        """<div class="card" style="margin-top:16px;">
             <div class="row center" style="margin-bottom:8px;">
               <h4 style="margin:0;font-size:.9rem;">Embedded tracks</h4>
               <span class="spacer"></span>
               $summaryBadge
               <a href="#/track-order?id=${item.id}" class="btn sm ghost" style="font-size:.72rem;margin-left:8px;">Open track editor ↗</a>
             </div>
             <div style="display:flex;flex-wrap:wrap;gap:5px;">$trackChips</div>
           </div>"""
    } else ""
    val resolverTraceHtml = buildResolverTrace(item, fallbackLang)
    val episodesTabHtml = if (isTvShow) buildEpisodesTab(item) else ""

    val tmdbLinkHtml = if (item.tmdbId != null) {
        val tmdbPath = if (item.kind == MediaKind.TV_SHOW) "tv" else "movie"
        val lang = item.resolvedLanguage
        val langParam = if (!lang.isNullOrBlank()) "?language=$lang" else ""
        """<a href="https://www.themoviedb.org/$tmdbPath/${item.tmdbId}$langParam" target="_blank" rel="noopener" class="btn sm ghost">TMDB ↗</a>"""
    } else ""

    container.innerHTML = """
        <div class="pagebar">
          <button id="back-btn" class="btn sm ghost">‹ Library</button>
          <h2>${item.title.esc()} <span class="muted">${if (item.year != null) "(${item.year})" else ""}</span></h2>
          ${if (item.tmdbId != null) """<span class="badge ok">TMDB matched</span>""" else """<span class="badge warn">No TMDB match</span>"""}
          $resolvedLangBadge
          <span class="spacer"></span>
          ${if (jellyfinUrl.isNotBlank() && item.jellyfinId != null) """<a href="$jellyfinUrl/web/index.html#!/details?id=${item.jellyfinId}" target="_blank" rel="noopener" class="btn sm ghost">Jellyfin ↗</a>""" else ""}
          $tmdbLinkHtml
          <button id="repull-jellyfin-btn" class="btn sm ghost">Re-pull from Jellyfin…</button>
          <button id="repull-btn" class="btn sm ghost">Re-pull from TMDB</button>
          ${if (!isTvShow) """<button id="track-order-btn" class="btn sm ghost">Track order →</button>""" else ""}
          <button id="write-nfo-btn" class="btn ghost" ${if (nfoDisabled) """disabled title="${nfoDisabledReason.esc()}"""" else ""}>Save → disk</button>
          <button id="write-nfo-refresh-btn" class="btn primary" ${if (nfoDisabled) """disabled title="${nfoDisabledReason.esc()}"""" else ""}>Save &amp; tell Jellyfin ↻</button>
        </div>

        <div id="detail-msg" style="display:none;margin-bottom:14px"></div>
        <div id="nfo-perm-banner" style="display:none;margin-bottom:14px"></div>
        <div id="drift-banner" style="display:none;margin-bottom:14px"></div>
        <div id="jf-lock-banner" style="display:${if (item.jellyfinLockData || item.jellyfinLockedFields.isNotEmpty()) "block" else "none"};margin-bottom:14px">
          <div style="background:var(--bad-soft);border:1px solid var(--bad);border-radius:6px;padding:10px 14px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
            <span style="font-size:.9rem;color:var(--bad);font-weight:600;">⚠ Jellyfin field lock detected</span>
            <span style="font-size:.85rem;flex:1;">${
              buildString {
                if (item.jellyfinLockData) append("This item's metadata is locked (lockData=true). ")
                if (item.jellyfinLockedFields.isNotEmpty()) append("Locked fields: ${item.jellyfinLockedFields.joinToString(", ")}.")
              }.esc()
            }</span>
            <button id="jf-lock-recheck-btn" class="btn sm ghost" style="font-size:.8rem;">Re-check ↻</button>
            <span id="jf-lock-recheck-result" style="font-size:.8rem;color:var(--muted)"></span>
          </div>
        </div>

        <div class="seg" id="detail-tabs" style="margin-bottom:16px;">$tabBarHtml</div>

        <div id="tab-overview" ${if (activeTab != "overview") """style="display:none;" """ else ""}>
          $embeddedTracksSummary
          <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap;">
            <div class="col" style="width:220px;flex:none;">
              <div class="card">
                <h4 style="margin:0 0 10px;">Poster</h4>
                $posterHtml
              </div>
              $seriesLangCard
              <div class="card">
                <h4 style="margin:0 0 8px;">Identity</h4>
                <div class="field" style="margin:0 0 6px;">
                  <label>TMDB id</label>
                  <div style="display:flex;gap:6px;align-items:center;">
                    <input id="tmdb-id-input" type="number" class="input" min="1"
                           value="${item.tmdbId ?: ""}" placeholder="—"
                           style="width:120px;flex:none;">
                    <button id="tmdb-id-save-btn" class="btn sm ghost">Save</button>
                    <span id="tmdb-id-msg" class="tiny muted"></span>
                  </div>
                  <div style="margin-top:6px;">
                    <button id="find-tmdb-match-btn" class="btn sm ghost" style="font-size:.8rem;">Find / fix match…</button>
                  </div>
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
                <div class="row center" style="margin-bottom:10px;">
                  <h4 style="margin:0;">Metadata</h4>
                  <span class="spacer"></span>
                  <button id="save-metadata-btn" class="btn sm primary" style="display:none;">Save changes</button>
                  <span id="save-metadata-msg" class="tiny muted" style="margin-left:8px;"></span>
                </div>
                <div class="row">
                  <div class="field fill">
                    <label>Title <button class="diff-trigger" id="diff-edit-title">≠</button></label>
                    <input id="edit-title" class="input" value="${item.title.esc()}" style="width:100%;">
                  </div>
                  <div class="field" style="width:110px;">
                    <label>Year <button class="diff-trigger" id="diff-edit-year">≠</button></label>
                    <input id="edit-year" class="input" type="number" value="${item.year ?: ""}" placeholder="—" style="width:100%;">
                  </div>
                </div>
                <div class="field">
                  <label>Original title <button class="diff-trigger" id="diff-edit-original-title">≠</button></label>
                  <input id="edit-original-title" class="input" value="${(item.originalTitle ?: "").esc()}" style="width:100%;">
                </div>
                <div class="field">
                  <label>Overview <button class="diff-trigger" id="diff-edit-overview">≠</button></label>
                  <textarea id="edit-overview" class="input" rows="4" style="width:100%;resize:vertical;">${item.overview?.esc() ?: ""}</textarea>
                </div>
                $genresHtml
                $directorHtml
                $tagsChipsHtml
                <div class="field" style="margin-top:12px;">
                  <label>File path</label>
                  <div class="input mono" style="font-size:.82rem;word-break:break-all;">${item.path.esc()}</div>
                </div>
              </div>
              $resolverTraceHtml
            </div>
          </div>
        </div>

        ${if (!isTvShow) """
        <div id="tab-tracks" ${if (activeTab != "tracks") """style="display:none;" """ else ""}>
          <div id="seeding-guard-banner" style="display:none;margin-bottom:10px;"></div>
          <div class="card">
            <div class="row center" style="margin-bottom:10px;">
              <h4 style="margin:0;">Embedded tracks</h4>
              <span class="spacer"></span>
              $issueBadge
            </div>
            $tracksHtml
          </div>
        </div>""" else ""}

        ${if (isTvShow) """<div id="tab-episodes" ${if (activeTab != "episodes") """style="display:none;" """ else ""}>${episodesTabHtml}</div>""" else ""}

        <div id="tab-artwork" ${if (activeTab != "artwork") """style="display:none;" """ else ""}>
          <div class="card" id="artwork-card">
            <div class="row center" style="margin-bottom:10px">
              <h4 style="margin:0">Artwork</h4>
              <span class="spacer"></span>
              <button id="upload-poster-btn" class="btn sm ghost">Upload poster</button>
              <button id="upload-fanart-btn" class="btn sm ghost">Upload fanart</button>
              <button id="upload-logo-btn" class="btn sm ghost">Upload logo</button>
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
            <form id="logo-form" method="post" action="/api/media/${item.id}/artwork/upload"
                  enctype="multipart/form-data" target="upload-frame" style="display:none">
              <input type="hidden" name="type" value="logo">
              <input type="file" id="logo-file" name="file" accept="image/png,image/jpeg">
            </form>
          </div>
        </div>

        <div id="tab-nfo" ${if (activeTab != "nfo") """style="display:none;" """ else ""}>
          <div class="card" id="nfo-card">
            <div class="row center" style="margin-bottom:10px;">
              <h4 style="margin:0">NFO (written to disk)</h4>
              <span class="spacer"></span>
              <span class="muted tiny">Click "Save → NFO" to write and view here</span>
            </div>
            <pre class="log" id="nfo-raw" style="font-size:.73rem;line-height:1.5;max-height:420px;overflow:auto"></pre>
          </div>
        </div>

        <div id="tab-history" ${if (activeTab != "history") """style="display:none;" """ else ""}>
          <div class="card" id="history-card">
            <h4 style="margin:0 0 12px;">Action history</h4>
            <div id="history-list"><span class="muted tiny">Loading…</span></div>
          </div>
        </div>
    """.trimIndent()

    installLanguagePickerById("lang-override-input", tmdbLangs)

    document.getElementById("back-btn")?.addEventListener("click") {
        App.navigate("/library")
    }

    document.getElementById("tmdb-id-save-btn")?.addEventListener("click") {
        val input = document.getElementById("tmdb-id-input") as? HTMLInputElement ?: return@addEventListener
        val msgEl = document.getElementById("tmdb-id-msg") as? HTMLElement
        val raw = input.value.trim()
        if (raw.isNotEmpty() && raw.toIntOrNull()?.let { it > 0 } != true) {
            msgEl?.textContent = "Must be a positive integer."
            return@addEventListener
        }
        val newId = raw.toIntOrNull()
        msgEl?.textContent = "Saving…"
        scope.launch {
            val updated = MediaApi.setTmdbId(item.id, newId)
            if (updated == null) {
                msgEl?.textContent = "Save failed."
            } else {
                val config = ConfigApi.get()
                val fallback = config?.config?.languageRules?.fallbackLanguage ?: "en"
                val jellyfinUrl2 = config?.config?.apiKeys?.jellyfinUrl?.trimEnd('/') ?: ""
                val tmdbLangs2 = if (updated.tmdbId != null) MediaApi.getTmdbLanguages(updated.id) else null
                val jsTags2 = dev.jellystructure.api.MetadataApi.getAllJsTags() ?: emptyList()
                renderDetailView(container, updated, scope, fallback, jellyfinUrl2, tmdbLangs2, jsTags = jsTags2)
            }
        }
    }

    document.getElementById("find-tmdb-match-btn")?.addEventListener("click") {
        showTmdbMatchModal(item, container, scope, fallbackLang, jellyfinUrl, tmdbLangs)
    }

    document.getElementById("jf-lock-recheck-btn")?.addEventListener("click") {
        val resultEl = document.getElementById("jf-lock-recheck-result") as? HTMLElement ?: return@addEventListener
        resultEl.textContent = "Checking…"
        scope.launch {
            val locks = MediaApi.jellyfinLocks(item.id)
            val banner = document.getElementById("jf-lock-banner") as? HTMLElement
            if (locks == null) {
                resultEl.textContent = "Re-check failed."
            } else if (!locks.lockData && locks.lockedFields.isEmpty()) {
                resultEl.textContent = "No locks found."
                banner?.style?.display = "none"
            } else {
                resultEl.textContent = "Still locked."
            }
        }
    }

    document.getElementById("track-order-btn")?.addEventListener("click") {
        App.navigate("/track-order?id=${item.id}")
    }

    document.getElementById("repull-btn")?.addEventListener("click") {
        scope.launch { handleRepull(item, container, scope, fallbackLang, jellyfinUrl, prevTmdbLangs = tmdbLangs) }
    }

    document.getElementById("repull-jellyfin-btn")?.addEventListener("click") {
        showRepullJellyfinModal(item, container, scope)
    }

    document.getElementById("lang-override-btn")?.addEventListener("click") {
        val input = document.getElementById("lang-override-input") as? HTMLInputElement ?: return@addEventListener
        val lang = input.value.trim()
        if (lang.isBlank()) return@addEventListener
        val msg = document.getElementById("lang-override-msg") as? HTMLElement
        msg?.textContent = "Saving…"
        scope.launch {
            val updated = MediaApi.overrideLanguage(item.id, lang)
            if (updated != null) {
                msg?.textContent = "Saved ✓"
                delay(600)
                renderMediaDetail(container, scope, item.id)
            } else {
                msg?.textContent = "Failed"
            }
        }
    }

    document.getElementById("write-nfo-btn")?.addEventListener("click") {
        scope.launch { handleWriteNfo(item.id, refresh = false) }
    }
    document.getElementById("write-nfo-refresh-btn")?.addEventListener("click") {
        scope.launch { handleWriteNfo(item.id, refresh = true) }
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
    document.getElementById("upload-logo-btn")?.addEventListener("click") {
        (document.getElementById("logo-file") as? HTMLInputElement)?.click()
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
    document.getElementById("logo-file")?.addEventListener("change") {
        (document.getElementById("logo-form") as? HTMLFormElement)?.submit()
        scope.launch {
            delay(2000)
            loadArtworkStatus(item.id)
            showDetailMsg("Logo upload submitted.", true)
        }
    }

    // Wire up episode row toggles, still uploads, editing, and season sync buttons
    if (isTvShow) {
        wireEpisodeToggles()
        wireEpisodeStillUploads(scope)
        wireEpisodeEditing(item, container, scope)
        val seasonSyncBtns = document.querySelectorAll(".season-sync-btn")
        for (i in 0 until seasonSyncBtns.length) {
            val btn = seasonSyncBtns.item(i) as? HTMLElement ?: continue
            val season = btn.getAttribute("data-season")?.toIntOrNull() ?: continue
            btn.addEventListener("click") { e ->
                e.stopPropagation()
                showSeasonSyncModal(item, season, container, scope)
            }
        }
    }

    // Tab switching — updates URL so tabs are deep-linkable and Back/Forward work
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
                    if (tab == "history") scope.launch { loadHistory(item.id, container, scope) }
                    if (tab == "artwork") scope.launch { loadArtworkStatus(item.id) }
                    if (tab == "tracks") scope.launch { loadSeedingStatus(item.id) }
                    // Update URL — don't add history entry for overview (default), do for others
                    val tabParam = if (tab == "overview") null else tab
                    dev.jellystructure.Router.updateQuery(mapOf("tab" to tabParam), replace = false)
                }
            }
        }
    }

    scope.launch { loadArtworkStatus(item.id) }
    scope.launch { loadDrift(item.id) }
    if (activeTab == "tracks" && !isTvShow) scope.launch { loadSeedingStatus(item.id) }
    if (activeTab == "history") scope.launch { loadHistory(item.id, container, scope) }

    // Inject diff styles once per document lifetime
    injectDiffStyles()

    // Inline metadata editing — per-field dirty indicators + diff triggers
    val editableIds = listOf("edit-title", "edit-year", "edit-original-title", "edit-overview", "edit-director", "edit-studio")
    val origValues = editableIds.associateWith { id ->
        (document.getElementById(id) as? HTMLInputElement)?.value
            ?: (document.getElementById(id) as? HTMLTextAreaElement)?.value ?: ""
    }
    val origTags = item.tags.toSet()

    fun currentTagSet(): Set<String> {
        val chips = document.getElementById("tags-chips")?.querySelectorAll(".tag-rm") ?: return emptySet()
        return (0 until chips.length)
            .mapNotNull { (chips.item(it) as? HTMLElement)?.getAttribute("data-tag") }
            .filter { it.isNotBlank() }.toSet()
    }

    fun setFieldDirty(fieldEl: HTMLElement?, triggerEl: HTMLElement?, dirty: Boolean) {
        val cls = fieldEl?.className ?: ""
        fieldEl?.className = if (dirty) {
            if ("field-dirty" !in cls) "$cls field-dirty".trim() else cls
        } else {
            cls.replace("field-dirty", "").trim()
        }
        triggerEl?.style?.display = if (dirty) "inline-flex" else "none"
    }

    fun checkDirty() {
        var anyDirty = false
        editableIds.forEach { id ->
            val el = document.getElementById(id)
            val current = (el as? HTMLInputElement)?.value ?: (el as? HTMLTextAreaElement)?.value ?: ""
            val dirty = current != (origValues[id] ?: "")
            if (dirty) anyDirty = true
            setFieldDirty(el?.parentElement as? HTMLElement, document.getElementById("diff-$id") as? HTMLElement, dirty)
        }
        val tagsDirty = currentTagSet() != origTags
        if (tagsDirty) anyDirty = true
        setFieldDirty(document.getElementById("tags-section") as? HTMLElement, document.getElementById("diff-tags") as? HTMLElement, tagsDirty)
        (document.getElementById("save-metadata-btn") as? HTMLElement)?.style?.display = if (anyDirty) "inline-flex" else "none"
    }
    editableIds.forEach { id ->
        document.getElementById(id)?.addEventListener("input") { checkDirty() }
    }

    // Wire diff trigger buttons
    val diffLabels = mapOf(
        "edit-title" to "Title",
        "edit-year" to "Year",
        "edit-original-title" to "Original title",
        "edit-overview" to "Overview",
        "edit-director" to (if (item.kind == MediaKind.TV_SHOW) "Network" else "Director"),
        "edit-studio" to "Studio",
    )
    diffLabels.forEach { (fieldId, label) ->
        document.getElementById("diff-$fieldId")?.addEventListener("click") {
            val orig = origValues[fieldId] ?: ""
            val current = (document.getElementById(fieldId) as? HTMLInputElement)?.value
                ?: (document.getElementById(fieldId) as? HTMLTextAreaElement)?.value ?: ""
            showDiffPopup(label, orig, current, isNumeric = fieldId == "edit-year")
        }
    }
    document.getElementById("diff-tags")?.addEventListener("click") {
        showTagsDiffPopup(origTags.sorted(), currentTagSet().sorted())
    }
    // Escape key dismissal for diff popup
    document.addEventListener("keydown") { e ->
        if ((e as? org.w3c.dom.events.KeyboardEvent)?.key == "Escape") {
            document.getElementById("diff-modal-overlay")?.remove()
        }
    }
    document.getElementById("save-metadata-btn")?.addEventListener("click") {
        val title = (document.getElementById("edit-title") as? HTMLInputElement)?.value?.trim()
        val year = (document.getElementById("edit-year") as? HTMLInputElement)?.value?.toIntOrNull()
        val originalTitle = (document.getElementById("edit-original-title") as? HTMLInputElement)?.value?.trim()
        val overview = (document.getElementById("edit-overview") as? HTMLTextAreaElement)?.value
        val directorVal = (document.getElementById("edit-director") as? HTMLInputElement)?.value?.trim()
        val studioVal = (document.getElementById("edit-studio") as? HTMLInputElement)?.value?.trim()
        // Read current tags from state
        val tagChips = document.getElementById("tags-chips")?.querySelectorAll(".tag-rm")
        val tagsVal = if (tagChips != null) {
            (0 until tagChips.length).mapNotNull { (tagChips.item(it) as? HTMLElement)?.getAttribute("data-tag") }.filter { it.isNotBlank() }
        } else null
        val msg = document.getElementById("save-metadata-msg") as? HTMLElement
        msg?.textContent = "Saving…"
        scope.launch {
            val updated = MediaApi.editMetadata(
                id = item.id,
                title = title,
                overview = overview,
                year = year,
                originalTitle = originalTitle,
                tags = tagsVal,
                director = if (item.kind != MediaKind.TV_SHOW) directorVal else null,
                studio = studioVal,
                network = if (item.kind == MediaKind.TV_SHOW) directorVal else null,
            )
            if (updated != null) {
                msg?.textContent = "Saved ✓"
                delay(600)
                renderDetailView(container, updated, scope, fallbackLang, jellyfinUrl, tmdbLangs)
            } else {
                msg?.textContent = "Save failed"
            }
        }
    }

    fun addTagChip(tag: String) {
        val chipsEl = document.getElementById("tags-chips") as? HTMLElement ?: return
        // Avoid duplicate
        val existing = chipsEl.querySelectorAll(".tag-rm")
        for (i in 0 until existing.length) {
            if ((existing.item(i) as? HTMLElement)?.getAttribute("data-tag") == tag) return
        }
        val span = document.createElement("span") as HTMLElement
        span.innerHTML = tagChipHtml(tag)
        chipsEl.appendChild(span.firstElementChild ?: span)
        chipsEl.lastElementChild?.querySelector(".tag-rm")?.addEventListener("click") {
            chipsEl.lastElementChild?.remove(); checkDirty()
        }
        checkDirty()
    }

    // Tag input dropdown wiring
    val tagInput = document.getElementById("tag-input") as? HTMLInputElement
    val tagDropdown = document.getElementById("tag-dropdown") as? HTMLElement
    if (tagInput != null && tagDropdown != null) {
        tagInput.addEventListener("input") { _ ->
            val q = tagInput.value.trim().lowercase()
            val items = tagDropdown.querySelectorAll(".tag-suggest-item")
            var anyVisible = false
            for (i in 0 until items.length) {
                val el = items.item(i) as? HTMLElement ?: continue
                val name = el.getAttribute("data-tag") ?: ""
                val visible = q.isEmpty() || name.lowercase().contains(q)
                (el as? HTMLElement)?.style?.display = if (visible) "" else "none"
                if (visible) anyVisible = true
            }
            tagDropdown.style.display = if (anyVisible) "block" else "none"
        }
        tagInput.addEventListener("focus") { _ ->
            if (jsTags.isNotEmpty()) tagDropdown.style.display = "block"
        }
        tagInput.addEventListener("blur") { _ ->
            // Delay so click on suggestion fires first
            kotlinx.browser.window.setTimeout({ tagDropdown.style.display = "none"; null }, 150)
        }
        tagInput.addEventListener("keydown") { ev ->
            val ke = ev as? org.w3c.dom.events.KeyboardEvent ?: return@addEventListener
            if (ke.key == "Enter") {
                ke.preventDefault()
                val tag = tagInput.value.trim()
                if (tag.isNotBlank()) { addTagChip(tag); tagInput.value = ""; tagDropdown.style.display = "none" }
            } else if (ke.key == "Escape") {
                tagDropdown.style.display = "none"
            }
        }
        // Wire suggestion clicks
        val items = tagDropdown.querySelectorAll(".tag-suggest-item")
        for (i in 0 until items.length) {
            val el = items.item(i) as? HTMLElement ?: continue
            el.addEventListener("mousedown") { ev ->
                ev.preventDefault()
                val name = el.getAttribute("data-tag") ?: return@addEventListener
                addTagChip(name); tagInput.value = ""; tagDropdown.style.display = "none"
            }
        }
    }

    // Tag add button
    document.getElementById("tag-add-btn")?.addEventListener("click") {
        val input = document.getElementById("tag-input") as? HTMLInputElement ?: return@addEventListener
        val tag = input.value.trim()
        if (tag.isBlank()) return@addEventListener
        input.value = ""
        tagDropdown?.style?.display = "none"
        addTagChip(tag)
    }
    // Wire existing tag removes
    document.querySelectorAll("#tags-chips .tag-rm").let { nodes ->
        for (i in 0 until nodes.length) {
            val rm = nodes.item(i) as? HTMLElement ?: continue
            rm.addEventListener("click") {
                rm.parentElement?.remove()
                checkDirty()
            }
        }
    }

    // Proactive write-permission check — runs in background after DOM is ready
    scope.launch {
        val result = MediaApi.checkNfoWritable(item.id)
        if (result != null && !result.writable) {
            showNfoPermBanner(result.error ?: "write permission check failed", result.path)
        }
    }
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
            val rows = eps.mapIndexed { idx, ep -> buildEpisodeRow(ep, season, idx, item.id) }.joinToString("")
            val seasonAttr = if (season != null) """data-season="$season"""" else ""
            """<div style="margin-bottom:20px;">
                 <div class="row center" style="margin-bottom:8px;">
                   <h4 style="margin:0;">${seasonLabel.esc()}</h4>
                   <span class="chip" style="margin-left:8px;font-size:.75rem;">${eps.size} ep</span>
                   $issueSummary
                   <span class="spacer"></span>
                   ${if (season != null) """<button class="btn sm ghost season-sync-btn" $seasonAttr style="padding:3px 9px;font-size:.75rem;" title="Sync season $season">↻</button>""" else ""}
                 </div>
                 $rows
               </div>"""
        }

    // Season summary chips
    val totalUntagged = item.episodes.sumOf { ep -> ep.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null } }
    val missingStill = item.episodes.count { it.stillPath.isNullOrBlank() }
    val missingOverview = item.episodes.count { it.overview.isNullOrBlank() }
    val seasonSummary = buildString {
        append("""<div class="row center" style="gap:8px;flex-wrap:wrap;margin-bottom:16px;">""")
        if (totalUntagged > 0) append("""<span class="badge bad">$totalUntagged untagged track${if (totalUntagged != 1) "s" else ""}</span>""")
        if (missingStill > 0) append("""<span class="badge warn">$missingStill missing still${if (missingStill != 1) "s" else ""}</span>""")
        if (missingOverview > 0) append("""<span class="badge">$missingOverview missing overview${if (missingOverview != 1) "s" else ""}</span>""")
        if (totalUntagged > 0) append("""<span class="tiny muted">Expand each episode row below to assign languages.</span>""")
        append("</div>")
    }

    return """
        <div>
          $seasonSummary
          <div class="row" style="align-items:flex-start;gap:16px;flex-wrap:wrap;">
            $votingCard
            <div class="col fill">$seasonBlocks</div>
          </div>
        </div>"""
}

private fun buildEpisodeRow(ep: Episode, season: Int?, idx: Int, mediaId: String): String {
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

    val encodedFilename = encodeURIComponent(ep.filename)
    val trackOrderHref = "/track-order?id=$mediaId&ep=${encodedFilename.esc()}"
    val bodyId = "ep-body-s${season ?: 0}-$idx"
    val toggleId = "ep-toggle-s${season ?: 0}-$idx"

    val editRows = (audioTracks + subTracks).joinToString("") { t ->
        val langBadge = if (t.language != null)
            """<span class="lang" style="font-size:.75rem;">${t.language.esc()}</span>"""
        else
            """<span class="badge bad" style="font-size:.7rem;">none</span>"""
        val defaultBadge = if (t.default)
            """<span class="badge ok" style="font-size:.7rem;">default</span>"""
        else
            """<button class="btn sm ghost ep-set-default-btn" style="font-size:.7rem;padding:2px 6px;"
                 data-media-id="$mediaId" data-ep-filename="${ep.filename.esc()}" data-specifier="${t.specifier.esc()}">set default ★</button>"""
        val quickLangs = if (t.language == null) """
            <div style="display:flex;gap:4px;flex-wrap:wrap;margin-top:4px;">
              <button class="btn sm ghost ep-lang-quick-btn" data-media-id="$mediaId" data-ep-filename="${ep.filename.esc()}" data-specifier="${t.specifier.esc()}" data-lang="dan" style="font-size:.7rem;padding:2px 6px;">dan</button>
              <button class="btn sm ghost ep-lang-quick-btn" data-media-id="$mediaId" data-ep-filename="${ep.filename.esc()}" data-specifier="${t.specifier.esc()}" data-lang="eng" style="font-size:.7rem;padding:2px 6px;">eng</button>
              <input class="input ep-lang-other-input" type="text" maxlength="8" placeholder="other…"
                     style="width:70px;font-size:.7rem;padding:2px 5px;"
                     data-media-id="$mediaId" data-ep-filename="${ep.filename.esc()}" data-specifier="${t.specifier.esc()}">
              <span class="ep-lang-result tiny muted" data-specifier="${t.specifier.esc()}"></span>
            </div>""" else ""
        """<div style="display:flex;align-items:center;gap:8px;padding:4px 0;border-bottom:1px solid var(--line);flex-wrap:wrap;">
             <span class="num mono" style="font-size:.75rem;min-width:48px;">${t.specifier.esc()}</span>
             <span class="muted tiny">${t.kind.name.lowercase()}</span>
             $langBadge
             <span class="muted tiny">${t.codec.esc()}</span>
             ${if (t.title != null) """<span class="muted tiny">${t.title.esc()}</span>""" else ""}
             $defaultBadge
             $quickLangs
           </div>"""
    }

    val stillThumb = if (!ep.stillPath.isNullOrBlank()) {
        """<div style="width:72px;height:40px;flex-shrink:0;border-radius:3px;overflow:hidden;background:var(--fill-3);">
             <img src="$TMDB_IMG_LG${ep.stillPath}" alt="" style="width:100%;height:100%;object-fit:cover;">
           </div>"""
    } else {
        """<div style="width:72px;height:40px;flex-shrink:0;border-radius:3px;background:var(--fill-3);display:flex;align-items:center;justify-content:center;">
             <span style="font-size:.65rem;color:var(--ink-soft);">no still</span>
           </div>"""
    }

    return """
        <div style="border:1px solid var(--line);border-radius:6px;margin-bottom:6px;overflow:hidden;background:var(--fill-2);">
          <div id="$toggleId" class="ep-toggle-row" data-body="$bodyId"
               style="display:flex;align-items:center;gap:10px;padding:9px 12px;cursor:pointer;">
            $stillThumb
            <span class="num" style="min-width:64px;font-size:.82rem;">${epCode.esc()}</span>
            ${if (!ep.title.isNullOrBlank()) """<span style="font-size:.85rem;font-weight:500;flex:none;max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${ep.title.esc()}">${ep.title.esc()}</span>""" else ""}
            <div style="display:flex;gap:4px;flex-wrap:wrap;flex:1;">$trackChips</div>
            $issueBadge
            <span class="ep-chev" style="color:var(--ink-soft);font-size:.9rem;margin-left:4px;">›</span>
          </div>
          <div id="$bodyId" style="display:none;padding:0 12px 12px;">
            <div style="margin:8px 0 10px;display:flex;flex-direction:column;gap:6px;">
              <div class="field" style="margin:0;">
                <label style="font-size:.75rem;">Title</label>
                <input class="input ep-title-input" type="text" value="${ep.title?.esc() ?: ""}"
                       data-ep-filename="${ep.filename.esc()}" style="width:100%;font-size:.85rem;">
              </div>
              <div class="field" style="margin:0;">
                <label style="font-size:.75rem;">Overview</label>
                <textarea class="input ep-overview-input" rows="3" data-ep-filename="${ep.filename.esc()}"
                          style="width:100%;resize:vertical;font-size:.82rem;">${ep.overview?.esc() ?: ""}</textarea>
              </div>
              <div style="display:flex;gap:8px;align-items:center;">
                <button class="btn sm primary ep-save-btn" data-media-id="$mediaId" data-ep-filename="${ep.filename.esc()}" style="font-size:.8rem;">Save episode</button>
                <button class="btn sm ghost ep-fetch-still-btn" data-media-id="$mediaId" data-ep-filename="${ep.filename.esc()}" style="font-size:.8rem;">Fetch still</button>
                <span class="ep-save-msg tiny muted" data-ep-filename="${ep.filename.esc()}"></span>
              </div>
            </div>
            ${if (editRows.isNotEmpty()) """<div style="margin-bottom:8px;">$editRows</div>""" else ""}
            <div class="row center" style="margin-top:6px;gap:8px;">
              <div class="muted tiny" style="flex:1;font-family:monospace;">${ep.filename.esc()}</div>
              <button class="btn sm ghost ep-still-upload-btn" style="font-size:.72rem;"
                data-form-id="still-form-$bodyId">Upload still</button>
              <a href="#$trackOrderHref" class="btn sm ghost" style="font-size:.72rem;">Track order →</a>
            </div>
            <form id="still-form-$bodyId" method="post"
                  action="/api/media/$mediaId/episodes/${encodedFilename.esc()}/still/upload"
                  enctype="multipart/form-data" target="upload-frame" style="display:none">
              <input type="file" id="still-file-$bodyId" name="file" accept="image/jpeg,image/jpg,image/png"
                class="ep-still-file-input" data-form-id="still-form-$bodyId">
            </form>
          </div>
        </div>"""
}

private fun showSyncModal(item: MediaItem, container: Element, scope: CoroutineScope) {
    document.getElementById("sync-modal-overlay")?.remove()
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "sync-modal-overlay"
    overlay.setAttribute("style", "position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:8000;display:flex;align-items:center;justify-content:center;")
    overlay.innerHTML = """
        <div style="background:var(--fill);border:1px solid var(--line-2);border-radius:var(--radius);padding:24px;max-width:480px;width:90%;box-shadow:var(--shadow);">
          <h4 style="margin:0 0 5px;">Sync series</h4>
          <p class="muted tiny" style="margin:0 0 16px;">Choose how to resync this item.</p>
          <div style="display:flex;flex-direction:column;gap:8px;">
            <div id="sync-opt-series" style="padding:12px 14px;border:1px solid var(--line-2);border-radius:var(--radius-s);cursor:pointer;">
              <div style="font-weight:600;font-size:.9rem;margin-bottom:2px;">Series metadata only</div>
              <div class="tiny muted">Re-fetches TMDB series info. Fast.</div>
            </div>
            <div id="sync-opt-episodes" style="padding:12px 14px;border:1px solid var(--line-2);border-radius:var(--radius-s);cursor:pointer;">
              <div style="font-weight:600;font-size:.9rem;margin-bottom:2px;">Full sync</div>
              <div class="tiny muted">Re-probes all episode files and re-fetches TMDB. May take several minutes for large series.</div>
            </div>
          </div>
          <div style="display:flex;justify-content:flex-end;margin-top:16px;gap:8px;align-items:center;">
            <span id="sync-modal-status" class="tiny muted" style="flex:1;"></span>
            <button id="sync-cancel-btn" class="btn sm ghost">Cancel</button>
          </div>
        </div>"""
    document.body?.appendChild(overlay)

    fun closeModal() { overlay.remove() }
    overlay.addEventListener("click") { e -> if ((e.target as? HTMLElement) == overlay) closeModal() }
    document.getElementById("sync-cancel-btn")?.addEventListener("click") { closeModal() }

    fun doSync(scopeStr: String) {
        val statusEl = document.getElementById("sync-modal-status") as? HTMLElement
        statusEl?.textContent = "Syncing…"
        listOf("sync-opt-series", "sync-opt-episodes", "sync-cancel-btn")
            .forEach { (document.getElementById(it) as? HTMLElement)?.setAttribute("style", "pointer-events:none;opacity:.5;") }
        scope.launch {
            val updated = MediaApi.syncMedia(item.id, scopeStr)
            if (updated != null) {
                closeModal()
                renderMediaDetail(container, scope, item.id)
            } else {
                statusEl?.textContent = "Sync failed — scan may already be running."
                listOf("sync-opt-series", "sync-opt-episodes")
                    .forEach { (document.getElementById(it) as? HTMLElement)?.removeAttribute("style") }
                (document.getElementById("sync-cancel-btn") as? HTMLElement)?.removeAttribute("style")
            }
        }
    }

    document.getElementById("sync-opt-series")?.addEventListener("click") { doSync("series") }
    document.getElementById("sync-opt-episodes")?.addEventListener("click") { doSync("episodes") }
}

private fun showTmdbMatchModal(
    item: MediaItem,
    container: Element,
    scope: CoroutineScope,
    fallbackLang: String,
    jellyfinUrl: String,
    tmdbLangs: Set<String>?,
) {
    document.getElementById("tmdb-match-modal-overlay")?.remove()
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "tmdb-match-modal-overlay"
    overlay.setAttribute("style", "position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:8000;display:flex;align-items:center;justify-content:center;")
    overlay.innerHTML = """
        <div style="background:var(--fill);border:1px solid var(--line-2);border-radius:var(--radius);padding:24px;max-width:560px;width:92%;box-shadow:var(--shadow);display:flex;flex-direction:column;gap:14px;">
          <div style="display:flex;align-items:center;gap:10px;">
            <h4 style="margin:0;flex:1;">Find / fix TMDB match</h4>
            <button id="tmdb-match-close" class="btn sm ghost">✕</button>
          </div>
          <div style="display:flex;gap:8px;">
            <input id="tmdb-match-query" class="input" style="flex:1;" value="${item.title.esc()}" placeholder="Search query…">
            <button id="tmdb-match-search" class="btn sm ghost">Search</button>
          </div>
          <div id="tmdb-match-results" style="display:flex;flex-direction:column;gap:8px;max-height:360px;overflow-y:auto;min-height:40px;">
            <span class="muted tiny">Enter a query and press Search.</span>
          </div>
        </div>"""
    document.body?.appendChild(overlay)

    fun closeModal() { overlay.remove() }
    overlay.addEventListener("click") { e -> if ((e.target as? HTMLElement) == overlay) closeModal() }
    document.getElementById("tmdb-match-close")?.addEventListener("click") { closeModal() }

    fun renderResults(results: List<TmdbMatchResult>) {
        val resultsEl = document.getElementById("tmdb-match-results") as? HTMLElement ?: return
        if (results.isEmpty()) { resultsEl.innerHTML = """<span class="muted tiny">No results found.</span>"""; return }
        resultsEl.innerHTML = results.joinToString("") { r ->
            val yearText = if (r.year.isNotBlank()) " (${r.year.esc()})" else ""
            val overview = if (r.overview.isNotBlank()) """<div class="tiny muted" style="margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${r.overview.esc()}</div>""" else ""
            val current = if (item.tmdbId == r.id) """ <span class="badge ok" style="font-size:.7rem;">current</span>""" else ""
            """<div class="tmdb-match-row" data-id="${r.id}" style="display:flex;align-items:center;gap:10px;padding:8px 10px;border:1px solid var(--line-2);border-radius:var(--radius-s);cursor:pointer;">
                 ${if (r.posterPath != null) """<img src="https://image.tmdb.org/t/p/w92${r.posterPath.esc()}" style="width:36px;height:54px;object-fit:cover;border-radius:3px;flex-shrink:0;">""" else """<div style="width:36px;height:54px;background:var(--fill-2);border-radius:3px;flex-shrink:0;"></div>"""}
                 <div style="flex:1;min-width:0;">
                   <div style="font-weight:600;font-size:.88rem;">${r.title.esc()}$yearText$current</div>
                   $overview
                   <div class="mono tiny muted">TMDB #${r.id}</div>
                 </div>
                 <button class="btn sm ghost" style="flex-shrink:0;font-size:.8rem;">Select</button>
               </div>"""
        }
        val rows = resultsEl.querySelectorAll(".tmdb-match-row")
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val tmdbId = row.getAttribute("data-id")?.toIntOrNull() ?: continue
            row.addEventListener("click") {
                val statusEl = document.getElementById("tmdb-match-results") as? HTMLElement
                statusEl?.innerHTML = """<span class="muted tiny">Saving…</span>"""
                scope.launch {
                    val updated = MediaApi.setTmdbId(item.id, tmdbId)
                    if (updated != null) {
                        closeModal()
                        val config = ConfigApi.get()
                        val fb = config?.config?.languageRules?.fallbackLanguage ?: fallbackLang
                        val jfUrl = config?.config?.apiKeys?.jellyfinUrl?.trimEnd('/') ?: jellyfinUrl
                        val langs = MediaApi.getTmdbLanguages(updated.id)
                        val jsTags = dev.jellystructure.api.MetadataApi.getAllJsTags() ?: emptyList()
                        renderDetailView(container, updated, scope, fb, jfUrl, langs, jsTags = jsTags)
                    } else {
                        statusEl?.innerHTML = """<span style="color:var(--bad);" class="tiny">Save failed — try again.</span>"""
                        renderResults(results)
                    }
                }
            }
        }
    }

    fun doSearch() {
        val q = (document.getElementById("tmdb-match-query") as? HTMLInputElement)?.value?.trim() ?: return
        if (q.isBlank()) return
        val resultsEl = document.getElementById("tmdb-match-results") as? HTMLElement
        resultsEl?.innerHTML = """<span class="muted tiny">Searching…</span>"""
        scope.launch {
            val results = MediaApi.tmdbSearch(item.id, q)
            renderResults(results)
        }
    }

    document.getElementById("tmdb-match-search")?.addEventListener("click") { doSearch() }
    (document.getElementById("tmdb-match-query") as? HTMLInputElement)?.addEventListener("keydown") { e ->
        if ((e as? org.w3c.dom.events.KeyboardEvent)?.key == "Enter") doSearch()
    }
    // Auto-search on open
    scope.launch {
        val results = MediaApi.tmdbSearch(item.id, item.title, item.year)
        renderResults(results)
    }
}

private fun showSeasonSyncModal(item: MediaItem, seasonNumber: Int, container: Element, scope: CoroutineScope) {
    document.getElementById("sync-modal-overlay")?.remove()
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "sync-modal-overlay"
    overlay.setAttribute("style", "position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:8000;display:flex;align-items:center;justify-content:center;")
    overlay.innerHTML = """
        <div style="background:var(--fill);border:1px solid var(--line-2);border-radius:var(--radius);padding:24px;max-width:480px;width:90%;box-shadow:var(--shadow);">
          <h4 style="margin:0 0 5px;">Sync Season $seasonNumber</h4>
          <p class="muted tiny" style="margin:0 0 16px;">Choose what to resync for this season.</p>
          <div style="display:flex;flex-direction:column;gap:8px;">
            <div id="sync-opt-season" style="padding:12px 14px;border:1px solid var(--line-2);border-radius:var(--radius-s);cursor:pointer;">
              <div style="font-weight:600;font-size:.9rem;margin-bottom:2px;">Season metadata only</div>
              <div class="tiny muted">Re-fetches TMDB episode titles and overviews. Fast.</div>
            </div>
            <div id="sync-opt-season-eps" style="padding:12px 14px;border:1px solid var(--line-2);border-radius:var(--radius-s);cursor:pointer;">
              <div style="font-weight:600;font-size:.9rem;margin-bottom:2px;">Season + all episodes</div>
              <div class="tiny muted">Re-probes episode files and re-fetches TMDB metadata.</div>
            </div>
          </div>
          <div style="display:flex;justify-content:flex-end;margin-top:16px;gap:8px;align-items:center;">
            <span id="sync-modal-status" class="tiny muted" style="flex:1;"></span>
            <button id="sync-cancel-btn" class="btn sm ghost">Cancel</button>
          </div>
        </div>"""
    document.body?.appendChild(overlay)

    fun closeModal() { overlay.remove() }
    overlay.addEventListener("click") { e -> if ((e.target as? HTMLElement) == overlay) closeModal() }
    document.getElementById("sync-cancel-btn")?.addEventListener("click") { closeModal() }

    fun doSeasonSync(scopeStr: String) {
        val statusEl = document.getElementById("sync-modal-status") as? HTMLElement
        statusEl?.textContent = "Syncing…"
        listOf("sync-opt-season", "sync-opt-season-eps", "sync-cancel-btn")
            .forEach { (document.getElementById(it) as? HTMLElement)?.setAttribute("style", "pointer-events:none;opacity:.5;") }
        scope.launch {
            val synced = MediaApi.syncSeason(item.id, seasonNumber, scopeStr)
            if (synced != null) {
                closeModal()
                renderMediaDetail(container, scope, item.id)
            } else {
                statusEl?.textContent = "Sync failed — scan may already be running."
                listOf("sync-opt-season", "sync-opt-season-eps")
                    .forEach { (document.getElementById(it) as? HTMLElement)?.removeAttribute("style") }
                (document.getElementById("sync-cancel-btn") as? HTMLElement)?.removeAttribute("style")
            }
        }
    }

    document.getElementById("sync-opt-season")?.addEventListener("click") { doSeasonSync("season") }
    document.getElementById("sync-opt-season-eps")?.addEventListener("click") { doSeasonSync("episodes") }
}

private fun showRepullJellyfinModal(item: MediaItem, container: Element, scope: CoroutineScope) {
    document.getElementById("repull-jf-modal-overlay")?.remove()
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "repull-jf-modal-overlay"
    overlay.setAttribute("style", "position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:8000;display:flex;align-items:center;justify-content:center;")
    overlay.innerHTML = """
        <div style="background:var(--fill);border:1px solid var(--line-2);border-radius:var(--radius);padding:24px;max-width:480px;width:90%;box-shadow:var(--shadow);">
          <h4 style="margin:0 0 8px;">Re-pull from Jellyfin</h4>
          <p style="margin:0 0 20px;font-size:.9rem;color:var(--ink-soft);">Re-fetches this item's name, file path, provider IDs and tracks from Jellyfin, then re-resolves language and TMDB metadata. Use this when the item moved, was renamed, or its Jellyfin match changed.</p>
          <div style="display:flex;justify-content:flex-end;gap:8px;align-items:center;">
            <span id="repull-jf-status" class="tiny muted" style="flex:1;"></span>
            <button id="repull-jf-cancel-btn" class="btn sm ghost">Cancel</button>
            <button id="repull-jf-confirm-btn" class="btn sm primary">Re-pull</button>
          </div>
        </div>"""
    document.body?.appendChild(overlay)

    fun closeModal() { overlay.remove() }
    overlay.addEventListener("click") { e -> if ((e.target as? HTMLElement) == overlay) closeModal() }
    document.getElementById("repull-jf-cancel-btn")?.addEventListener("click") { closeModal() }
    document.getElementById("repull-jf-confirm-btn")?.addEventListener("click") {
        val statusEl = document.getElementById("repull-jf-status") as? HTMLElement
        val confirmBtn = document.getElementById("repull-jf-confirm-btn") as? HTMLElement
        val cancelBtn = document.getElementById("repull-jf-cancel-btn") as? HTMLElement
        statusEl?.textContent = "Re-pulling…"
        confirmBtn?.setAttribute("disabled", "true")
        cancelBtn?.setAttribute("disabled", "true")
        scope.launch {
            val updated = MediaApi.repullFromJellyfin(item.id)
            if (updated != null) {
                closeModal()
                renderMediaDetail(container, scope, item.id)
            } else {
                statusEl?.textContent = "Re-pull failed — scan may be running or item not found in Jellyfin."
                confirmBtn?.removeAttribute("disabled")
                cancelBtn?.removeAttribute("disabled")
            }
        }
    }
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

private fun wireEpisodeStillUploads(scope: CoroutineScope) {
    document.querySelectorAll(".ep-still-upload-btn").let { btns ->
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as? HTMLElement ?: continue
            val formId = btn.getAttribute("data-form-id") ?: continue
            btn.addEventListener("click") {
                val fileInput = document.querySelector("#$formId input[type=file]") as? HTMLInputElement
                fileInput?.click()
            }
        }
    }
    document.querySelectorAll(".ep-still-file-input").let { inputs ->
        for (i in 0 until inputs.length) {
            val input = inputs.item(i) as? HTMLInputElement ?: continue
            val formId = input.getAttribute("data-form-id") ?: continue
            input.addEventListener("change") {
                (document.getElementById(formId) as? HTMLFormElement)?.submit()
                scope.launch {
                    delay(2000)
                    showDetailMsg("Still upload submitted.", true)
                }
            }
        }
    }
}

private fun wireEpisodeEditing(item: MediaItem, container: Element, scope: CoroutineScope) {
    // Save episode metadata (title + overview)
    document.querySelectorAll(".ep-save-btn").let { btns ->
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as? HTMLElement ?: continue
            val mediaId = btn.getAttribute("data-media-id") ?: continue
            val epFilename = btn.getAttribute("data-ep-filename") ?: continue
            btn.addEventListener("click") {
                val msgEl = document.querySelector(".ep-save-msg[data-ep-filename='$epFilename']") as? HTMLElement
                val titleInput = document.querySelector(".ep-title-input[data-ep-filename='$epFilename']") as? HTMLInputElement
                val overviewInput = document.querySelector(".ep-overview-input[data-ep-filename='$epFilename']") as? HTMLTextAreaElement
                val title = titleInput?.value?.trim()
                val overview = overviewInput?.value?.trim()
                btn.setAttribute("disabled", "true")
                msgEl?.textContent = "Saving…"
                scope.launch {
                    val ok = MediaApi.setEpisodeMetadata(mediaId, epFilename, title?.ifEmpty { null }, overview?.ifEmpty { null })
                    if (ok) {
                        msgEl?.textContent = "Saved ✓"
                    } else {
                        msgEl?.textContent = "Failed"
                    }
                    btn.removeAttribute("disabled")
                }
            }
        }
    }

    // Fetch still from TMDB
    document.querySelectorAll(".ep-fetch-still-btn").let { btns ->
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as? HTMLElement ?: continue
            val mediaId = btn.getAttribute("data-media-id") ?: continue
            btn.addEventListener("click") {
                btn.setAttribute("disabled", "true")
                btn.textContent = "Fetching…"
                scope.launch {
                    val result = MediaApi.fetchEpisodeStills(mediaId)
                    if (result != null) {
                        renderMediaDetail(container, scope, mediaId)
                    } else {
                        btn.textContent = "Fetch still"
                        btn.removeAttribute("disabled")
                        showDetailMsg("Fetch still failed.", false)
                    }
                }
            }
        }
    }

    // Set default track for episode
    document.querySelectorAll(".ep-set-default-btn").let { btns ->
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as? HTMLElement ?: continue
            val mediaId = btn.getAttribute("data-media-id") ?: continue
            val epFilename = btn.getAttribute("data-ep-filename") ?: continue
            val specifier = btn.getAttribute("data-specifier") ?: continue
            btn.addEventListener("click") {
                btn.setAttribute("disabled", "true")
                btn.textContent = "…"
                scope.launch {
                    val err = MediaApi.setEpisodeDefaultTrack(mediaId, epFilename, specifier)
                    if (err == null) {
                        renderMediaDetail(container, scope, mediaId)
                    } else {
                        btn.textContent = "set default ★"
                        btn.removeAttribute("disabled")
                        showDetailMsg("Failed: $err", false)
                    }
                }
            }
        }
    }

    // Quick language assignment for episode tracks
    document.querySelectorAll(".ep-lang-quick-btn").let { btns ->
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as? HTMLElement ?: continue
            val mediaId = btn.getAttribute("data-media-id") ?: continue
            val epFilename = btn.getAttribute("data-ep-filename") ?: continue
            val specifier = btn.getAttribute("data-specifier") ?: continue
            val lang = btn.getAttribute("data-lang") ?: continue
            btn.addEventListener("click") {
                val resultEl = document.querySelector(".ep-lang-result[data-specifier='$specifier']") as? HTMLElement
                resultEl?.textContent = "…"
                btn.setAttribute("disabled", "true")
                scope.launch {
                    val err = MediaApi.setEpisodeTrackLanguage(mediaId, epFilename, specifier, lang)
                    if (err == null) {
                        renderMediaDetail(container, scope, mediaId)
                    } else {
                        resultEl?.textContent = "✗ $err"
                        btn.removeAttribute("disabled")
                    }
                }
            }
        }
    }

    // Free-text language assignment for episode tracks
    document.querySelectorAll(".ep-lang-other-input").let { inputs ->
        for (i in 0 until inputs.length) {
            val input = inputs.item(i) as? HTMLInputElement ?: continue
            val mediaId = input.getAttribute("data-media-id") ?: continue
            val epFilename = input.getAttribute("data-ep-filename") ?: continue
            val specifier = input.getAttribute("data-specifier") ?: continue
            input.addEventListener("keydown") { e ->
                val ke = e as? org.w3c.dom.events.KeyboardEvent ?: return@addEventListener
                if (ke.key == "Enter") {
                    val lang = input.value.trim()
                    if (lang.isEmpty()) return@addEventListener
                    val resultEl = document.querySelector(".ep-lang-result[data-specifier='$specifier']") as? HTMLElement
                    resultEl?.textContent = "…"
                    input.setAttribute("disabled", "true")
                    scope.launch {
                        val err = MediaApi.setEpisodeTrackLanguage(mediaId, epFilename, specifier, lang)
                        if (err == null) {
                            renderMediaDetail(container, scope, mediaId)
                        } else {
                            resultEl?.textContent = "✗ $err"
                            input.removeAttribute("disabled")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadHistory(id: String, container: Element? = null, scope: CoroutineScope? = null) {
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
            "metadata_edit" -> "Metadata edited"
            "set_tmdb_id" -> "TMDB ID changed"
            "language_override" -> "Language override"
            "revert" -> "Reverted"
            else -> entry.action
        }
        val revertBtn = if (entry.revertable) {
            """<button class="btn sm ghost history-revert-btn" data-entry-id="${entry.id.esc()}" style="font-size:.72rem;margin-left:8px;">Revert</button>"""
        } else ""
        """<div style="display:flex;gap:10px;padding:6px 0;border-bottom:1px solid var(--border);align-items:center;">
             <span class="muted tiny" style="width:160px;flex-shrink:0;">${formatTimestamp(entry.timestamp.toDouble() * 1000.0)}</span>
             <div style="flex:1;">
               <span class="chip" style="font-size:.72rem;">$actionLabel</span>
               <span class="muted tiny" style="margin-left:6px;">${entry.detail.esc()}</span>
             </div>
             $revertBtn
           </div>"""
    }
    // Wire revert buttons if container + scope are available
    if (container != null && scope != null) {
        val btns = listEl.querySelectorAll(".history-revert-btn")
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as? HTMLElement ?: continue
            val entryId = btn.getAttribute("data-entry-id") ?: continue
            btn.addEventListener("click") {
                btn.setAttribute("disabled", "true")
                btn.textContent = "Reverting…"
                scope.launch {
                    val updated = MediaApi.revertHistoryEntry(id, entryId)
                    if (updated != null) {
                        renderMediaDetail(container, scope, id, initialTab = "history")
                    } else {
                        btn.removeAttribute("disabled")
                        btn.textContent = "Failed"
                    }
                }
            }
        }
    }
}

private suspend fun handleRepull(item: MediaItem, container: Element, scope: CoroutineScope, fallbackLang: String = "en", jellyfinUrl: String = "", prevTmdbLangs: Set<String>? = null) {
    val btn = document.getElementById("repull-btn") as? HTMLElement
    btn?.setAttribute("disabled", "true")
    btn?.textContent = "Pulling…"

    val updated = MediaApi.repull(item.id)

    btn?.removeAttribute("disabled")
    btn?.textContent = "Re-pull from TMDB"

    if (updated != null) {
        showDetailMsg("TMDB data refreshed. Reloading…", true)
        delay(600)
        // Re-fetch languages in case the TMDB match changed
        val tmdbLangs = if (updated.tmdbId != null) MediaApi.getTmdbLanguages(updated.id) else prevTmdbLangs
        renderDetailView(container, updated, scope, fallbackLang, jellyfinUrl, tmdbLangs)
    } else {
        showDetailMsg("Re-pull failed — no TMDB match found.", false)
    }
}

private suspend fun loadSeedingStatus(id: String) {
    val banner = document.getElementById("seeding-guard-banner") as? HTMLElement ?: return
    val status = MediaApi.getSeedingStatus(id) ?: return
    when (status.status) {
        "blocked" -> {
            banner.innerHTML = """<div style="background:var(--bad-soft);border:1px solid var(--bad);border-radius:6px;padding:8px 14px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
              <span style="color:var(--bad);font-weight:600;font-size:.88rem;">🔒 Seeding guard active</span>
              <span class="tiny">This file is currently being seeded by qBittorrent (<b>${(status.torrentName ?: "").esc()}</b>). Track edits are blocked to protect the torrent.</span>
            </div>"""
            banner.style.display = "block"
        }
        "unreachable" -> {
            banner.innerHTML = """<div style="background:var(--warn-soft,#2d220b);border:1px solid var(--warn,#b8860b);border-radius:6px;padding:8px 14px;display:flex;align-items:center;gap:10px;">
              <span style="color:var(--warn,#f59e0b);font-weight:600;font-size:.88rem;">⚠ qBittorrent unreachable</span>
              <span class="tiny">${(status.detail ?: "").esc()} — proceeding with track edits at your own risk.</span>
            </div>"""
            banner.style.display = "block"
        }
        else -> banner.style.display = "none"
    }
}

private suspend fun loadDrift(id: String) {
    val drifts = MediaApi.getDrift(id)
    val banner = document.getElementById("drift-banner") as? HTMLElement ?: return
    if (drifts.isEmpty()) { banner.style.display = "none"; return }
    val fieldNames = mapOf("title" to "Title", "year" to "Year", "tmdbId" to "TMDB ID")
    val rows = drifts.joinToString("") { d ->
        val label = fieldNames[d.field] ?: d.field
        val jf = d.inJellyfin.ifBlank { "—" }
        val db = d.inDb.ifBlank { "—" }
        """<span style="font-size:.85rem;"><b>${label.esc()}</b>: Jellyfin="${jf.esc()}" · DB="${db.esc()}"</span>"""
    }
    banner.innerHTML = """<div style="background:var(--warn-soft,#2d220b);border:1px solid var(--warn,#b8860b);border-radius:6px;padding:10px 14px;display:flex;align-items:center;gap:10px;flex-wrap:wrap;">
      <span style="font-size:.9rem;color:var(--warn,#f59e0b);font-weight:600;">⚠ Jellyfin drift detected</span>
      <span style="flex:1;display:flex;flex-direction:column;gap:3px;">$rows</span>
      <span class="tiny muted" style="font-size:.8rem;">Use "Re-pull from Jellyfin…" to absorb Jellyfin's version.</span>
    </div>"""
    banner.style.display = "block"
}

private suspend fun loadArtworkStatus(id: String) {
    val status = MediaApi.getArtworkStatus(id) ?: return
    renderArtworkStatus(status)
}

private fun renderArtworkStatus(status: ArtworkStatus) {
    val el = document.getElementById("artwork-status") as? HTMLElement ?: return
    fun badge(exists: Boolean, label: String) =
        """<span class="badge ${if (exists) "ok" else "bad"}" style="margin-right:6px">$label ${if (exists) "✓" else "missing"}</span>"""
    val count = listOf(status.posterExists, status.fanartExists, status.logoExists).count { it }
    el.innerHTML = """<span class="muted tiny" style="margin-right:10px;">$count of 3 assets</span>""" +
        badge(status.posterExists, "poster.jpg") +
        badge(status.fanartExists, "fanart.jpg") +
        badge(status.logoExists, "clearlogo.png")
}

private suspend fun handleWriteNfo(id: String, refresh: Boolean = false) {
    val btn1 = document.getElementById("write-nfo-btn") as? HTMLElement
    val btn2 = document.getElementById("write-nfo-refresh-btn") as? HTMLElement
    btn1?.setAttribute("disabled", "true"); btn1?.textContent = "Writing…"
    btn2?.setAttribute("disabled", "true"); btn2?.textContent = "Writing…"

    val (result, error) = MediaApi.writeNfo(id)

    if (result != null) {
        val raw = MediaApi.getNfo(id)
        if (raw != null) {
            (document.getElementById("nfo-raw") as? HTMLElement)?.textContent = raw
        }
        if (refresh) {
            btn2?.textContent = "Syncing artwork…"
            MediaApi.fetchArtwork(id)
            btn2?.textContent = "Notifying Jellyfin…"
            MediaApi.jellyfinRefresh(id)
            showDetailMsg("NFO written, artwork synced, Jellyfin notified ✓", true)
        } else {
            showDetailMsg("NFO written to ${result.path}", true)
        }
    } else {
        showNfoWriteError(error ?: "NFO write failed.")
    }

    btn1?.removeAttribute("disabled"); btn1?.textContent = "Save → disk"
    btn2?.removeAttribute("disabled"); btn2?.textContent = "Save & tell Jellyfin ↻"
}

private fun permCopyBlock(command: String, comment: String? = null): String {
    val attrSafe = command.replace("&", "&amp;").replace("\"", "&quot;")
    val display = if (comment != null) "${command.esc()}<span style='color:var(--ink-soft);'> # ${comment.esc()}</span>" else command.esc()
    return """<div style="display:flex;align-items:stretch;background:var(--fill-3);border:1px solid var(--line);border-radius:4px;overflow:hidden;margin:4px 0 2px;">
      <code style="flex:1;padding:7px 10px;$MONO_CODE_STYLE;white-space:pre-wrap;word-break:break-all;">$display</code>
      <button data-copy="$attrSafe" onclick="navigator.clipboard.writeText(this.dataset.copy);var b=this;b.textContent='Copied!';setTimeout(function(){b.textContent='Copy'},1500);" style="padding:0 12px;background:var(--fill-2);border:none;border-left:1px solid var(--line);cursor:pointer;color:var(--ink-soft);font-size:.75rem;white-space:nowrap;flex-shrink:0;">Copy</button>
    </div>""".trimIndent()
}

private fun buildNfoPermFixHtml(path: String): String {
    val p = if (path.isNotBlank()) path else "/path/to/media"
    val tabActive = "padding:6px 14px;border:none;cursor:pointer;background:none;border-bottom:2px solid var(--hi);font-weight:600;color:var(--ink);font-size:.82rem;"
    val tabInactive = "padding:6px 14px;border:none;cursor:pointer;background:none;border-bottom:2px solid transparent;color:var(--ink-soft);font-size:.82rem;"
    val switchDocker = "document.getElementById('perm-tab-docker').style.display='';document.getElementById('perm-tab-native').style.display='none';document.getElementById('perm-tab-docker-btn').setAttribute('style','$tabActive');document.getElementById('perm-tab-native-btn').setAttribute('style','$tabInactive');"
    val switchNative = "document.getElementById('perm-tab-native').style.display='';document.getElementById('perm-tab-docker').style.display='none';document.getElementById('perm-tab-native-btn').setAttribute('style','$tabActive');document.getElementById('perm-tab-docker-btn').setAttribute('style','$tabInactive');"

    val dockerTab = """
        <div id="perm-tab-docker">
          <p class="tiny muted" style="margin:8px 0 10px;">Run these on your <strong>host machine</strong> (not inside the container).</p>
          <div style="margin-bottom:10px;">
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Step 1</strong> — find the UID:GID that owns the media directory:</div>
            ${permCopyBlock("stat $p")}
            <div class="tiny muted" style="margin-top:3px;">Look for <code style="$MONO_CODE_STYLE">Uid:</code> and <code style="$MONO_CODE_STYLE">Gid:</code> in the output.</div>
          </div>
          <div style="margin-bottom:10px;">
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Step 2a (preferred)</strong> — add a <code style="$MONO_CODE_STYLE">user:</code> line to the jellystructure service in docker-compose.yml, then restart:</div>
            ${permCopyBlock("    user: \"1000:1000\"", "replace with UID:GID from Step 1")}
            ${permCopyBlock("docker compose up -d jellystructure")}
          </div>
          <div>
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Step 2b (alternative)</strong> — change ownership of the media directory on the host:</div>
            ${permCopyBlock("sudo chown -R 1000:1000 $p", "replace 1000:1000 with UID:GID from Step 1")}
          </div>
        </div>
    """.trimIndent()

    val nativeTab = """
        <div id="perm-tab-native" style="display:none;">
          <p class="tiny muted" style="margin:8px 0 10px;">The user running the Jellystructure binary needs write access to the media directory.</p>
          <div style="margin-bottom:10px;">
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Option 1</strong> — change ownership to the current user:</div>
            ${permCopyBlock("sudo chown -R \$(id -u):\$(id -g) $p")}
          </div>
          <div style="margin-bottom:10px;">
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Option 2</strong> — add write permission for the directory's group:</div>
            ${permCopyBlock("sudo chmod -R g+w $p")}
          </div>
          <div>
            <div class="tiny muted" style="margin-bottom:4px;">Check current ownership:</div>
            ${permCopyBlock("stat $p")}
          </div>
        </div>
    """.trimIndent()

    return """
        <div style="margin-top:10px;">
          <div style="display:flex;border-bottom:1px solid var(--line);margin-bottom:10px;">
            <button id="perm-tab-docker-btn" onclick="$switchDocker" style="$tabActive">Docker Compose</button>
            <button id="perm-tab-native-btn" onclick="$switchNative" style="$tabInactive">Native</button>
          </div>
          $dockerTab
          $nativeTab
        </div>
    """.trimIndent()
}

private fun showNfoWriteError(message: String) {
    val el = document.getElementById("detail-msg") as? HTMLElement ?: return
    val isPermission = message.contains("Permission denied", ignoreCase = true)
    val bannerVisible = (document.getElementById("nfo-perm-banner") as? HTMLElement)?.style?.display != "none"
    val extra = when {
        isPermission && bannerVisible -> """<div class="tiny muted" style="margin-top:8px;">See the warning above for fix instructions.</div>"""
        isPermission -> buildNfoPermFixHtml("")
        else -> ""
    }
    el.style.display = "block"
    el.innerHTML = """
        <div style="background:color-mix(in srgb,var(--bad) 10%,transparent);border:1px solid var(--bad);border-radius:var(--radius-s);padding:12px 16px;">
          <div style="display:flex;align-items:flex-start;gap:8px;${if (extra.isNotBlank()) "margin-bottom:4px;" else ""}">
            <span class="badge bad" style="flex-shrink:0;">Write failed</span>
            <span style="$MONO_CODE_STYLE;word-break:break-all;">${message.esc()}</span>
          </div>
          $extra
        </div>
    """.trimIndent()
}

private fun showNfoPermBanner(message: String, path: String) {
    val el = document.getElementById("nfo-perm-banner") as? HTMLElement ?: return
    el.style.display = "block"
    el.innerHTML = """
        <div style="background:color-mix(in srgb,var(--warn) 12%,transparent);border:1px solid var(--warn);border-radius:var(--radius-s);padding:12px 16px;">
          <div style="display:flex;align-items:flex-start;gap:8px;">
            <span class="badge warn" style="flex-shrink:0;">No write access</span>
            <span style="$MONO_CODE_STYLE;word-break:break-all;">${message.esc()}</span>
            <button onclick="document.getElementById('nfo-perm-banner').style.display='none'" style="margin-left:auto;background:none;border:none;cursor:pointer;color:var(--ink-soft);font-size:1rem;padding:0 2px;flex-shrink:0;" title="Dismiss">✕</button>
          </div>
          ${buildNfoPermFixHtml(path)}
        </div>
    """.trimIndent()
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

// ── Diff styles ──────────────────────────────────────────────────────────────

private fun injectDiffStyles() {
    if (document.getElementById("detail-diff-styles") != null) return
    val style = document.createElement("style") as? org.w3c.dom.HTMLStyleElement ?: return
    style.id = "detail-diff-styles"
    style.textContent = """
        .field-dirty > input.input, .field-dirty > textarea.input {
          border-left: 3px solid var(--warn, #f59e0b) !important;
          background: color-mix(in srgb, var(--warn, #f59e0b) 7%, transparent) !important;
        }
        .diff-trigger {
          display: none; align-items: center; justify-content: center;
          width: 16px; height: 16px; border-radius: 3px; margin-left: 5px;
          background: color-mix(in srgb, var(--warn, #f59e0b) 18%, transparent);
          border: 1px solid color-mix(in srgb, var(--warn, #f59e0b) 45%, transparent);
          color: var(--warn, #f59e0b); font-size: .65rem; cursor: pointer;
          vertical-align: middle; padding: 0; line-height: 1;
        }
        .diff-modal-overlay {
          position: fixed; inset: 0; background: rgba(0,0,0,.55); z-index: 1000;
          display: flex; align-items: center; justify-content: center; padding: 16px;
        }
        .diff-modal {
          background: var(--surface, #1a1d27); border: 1px solid var(--line, rgba(255,255,255,.1));
          border-radius: 10px; max-width: 560px; width: 100%; padding: 20px;
          box-shadow: 0 12px 40px rgba(0,0,0,.4); max-height: 80vh; overflow-y: auto;
        }
        .diff-section { margin-top: 14px; }
        .diff-section-label { font-size: .72rem; font-weight: 600; color: var(--ink-soft); margin-bottom: 5px; }
        .diff-content { font-size: .88rem; line-height: 1.7; padding: 8px 12px;
          background: var(--fill-2); border-radius: 6px; }
        .diff-removed { color: var(--bad, #ef4444); text-decoration: line-through;
          background: color-mix(in srgb, var(--bad, #ef4444) 12%, transparent);
          border-radius: 2px; padding: 0 2px; }
        .diff-added { color: var(--ok, #22c55e);
          background: color-mix(in srgb, var(--ok, #22c55e) 12%, transparent);
          border-radius: 2px; padding: 0 2px; }
    """.trimIndent()
    document.head?.appendChild(style)
}

// ── Word diff ─────────────────────────────────────────────────────────────────

private data class DiffEntry(val word: String, val status: String)

private fun computeWordDiff(original: String, current: String): List<DiffEntry> {
    val ow = original.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val cw = current.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val m = ow.size; val n = cw.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 1..m) for (j in 1..n)
        dp[i][j] = if (ow[i-1] == cw[j-1]) dp[i-1][j-1] + 1 else maxOf(dp[i-1][j], dp[i][j-1])
    val result = mutableListOf<DiffEntry>()
    var i = m; var j = n
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && ow[i-1] == cw[j-1] -> { result += DiffEntry(ow[i-1], "same"); i--; j-- }
            j > 0 && (i == 0 || dp[i][j-1] >= dp[i-1][j]) -> { result += DiffEntry(cw[j-1], "added"); j-- }
            else -> { result += DiffEntry(ow[i-1], "removed"); i-- }
        }
    }
    result.reverse()
    return result
}

private fun diffBeforeHtml(diff: List<DiffEntry>): String =
    diff.filter { it.status != "added" }.joinToString(" ") { (w, s) ->
        if (s == "removed") """<span class="diff-removed">${w.esc()}</span>""" else w.esc()
    }.ifBlank { """<span class="muted tiny">empty</span>""" }

private fun diffAfterHtml(diff: List<DiffEntry>): String =
    diff.filter { it.status != "removed" }.joinToString(" ") { (w, s) ->
        if (s == "added") """<span class="diff-added">${w.esc()}</span>""" else w.esc()
    }.ifBlank { """<span class="muted tiny">empty</span>""" }

// ── Diff popups ───────────────────────────────────────────────────────────────

private fun showDiffPopup(label: String, original: String, current: String, isNumeric: Boolean = false) {
    document.getElementById("diff-modal-overlay")?.remove()
    val diff = if (isNumeric) null else computeWordDiff(original, current)
    val beforeHtml = if (isNumeric) original.esc().ifBlank { """<span class="muted tiny">empty</span>""" }
                     else diff?.let { diffBeforeHtml(it) } ?: original.esc()
    val afterHtml  = if (isNumeric) current.esc().ifBlank { """<span class="muted tiny">empty</span>""" }
                     else diff?.let { diffAfterHtml(it) } ?: current.esc()
    renderDiffOverlay("""
        <div class="row center" style="margin-bottom:2px;">
          <strong style="font-size:.95rem;">$label — changes</strong>
          <span class="spacer"></span>
          <button id="diff-modal-close" class="btn sm ghost" style="padding:2px 8px;">✕</button>
        </div>
        <div class="diff-section">
          <div class="diff-section-label">Before</div>
          <div class="diff-content">$beforeHtml</div>
        </div>
        <div class="diff-section">
          <div class="diff-section-label">After</div>
          <div class="diff-content">$afterHtml</div>
        </div>
    """.trimIndent())
}

private fun showTagsDiffPopup(origTags: List<String>, currentTags: List<String>) {
    document.getElementById("diff-modal-overlay")?.remove()
    val origSet = origTags.toSet(); val curSet = currentTags.toSet()
    val removed = origTags.filter { it !in curSet }
    val added = currentTags.filter { it !in origSet }
    val kept = origTags.filter { it in curSet }
    fun chip(tag: String, style: String) = """<span class="chip" style="$style">${tag.esc()}</span>"""
    val rmStyle = "background:color-mix(in srgb,var(--bad)12%,transparent);border-color:var(--bad);color:var(--bad);text-decoration:line-through;"
    val addStyle = "background:color-mix(in srgb,var(--ok)12%,transparent);border-color:var(--ok);color:var(--ok);"
    val beforeHtml = (kept.map { chip(it, "") } + removed.map { chip(it, rmStyle) })
        .joinToString(" ").ifBlank { """<span class="muted tiny">no tags</span>""" }
    val afterHtml = (kept.map { chip(it, "") } + added.map { chip(it, addStyle) })
        .joinToString(" ").ifBlank { """<span class="muted tiny">no tags</span>""" }
    renderDiffOverlay("""
        <div class="row center" style="margin-bottom:2px;">
          <strong style="font-size:.95rem;">Tags — changes</strong>
          <span class="spacer"></span>
          <button id="diff-modal-close" class="btn sm ghost" style="padding:2px 8px;">✕</button>
        </div>
        <div class="diff-section">
          <div class="diff-section-label">Before</div>
          <div class="diff-content" style="display:flex;flex-wrap:wrap;gap:5px;">$beforeHtml</div>
        </div>
        <div class="diff-section">
          <div class="diff-section-label">After</div>
          <div class="diff-content" style="display:flex;flex-wrap:wrap;gap:5px;">$afterHtml</div>
        </div>
    """.trimIndent())
}

private fun renderDiffOverlay(bodyHtml: String) {
    val overlay = document.createElement("div") as? HTMLElement ?: return
    overlay.id = "diff-modal-overlay"
    overlay.className = "diff-modal-overlay"
    overlay.innerHTML = """<div class="diff-modal">$bodyHtml</div>"""
    document.body?.appendChild(overlay)
    overlay.addEventListener("click") { e ->
        if ((e.target as? HTMLElement)?.id == "diff-modal-overlay") overlay.remove()
    }
    document.getElementById("diff-modal-close")?.addEventListener("click") { overlay.remove() }
}
