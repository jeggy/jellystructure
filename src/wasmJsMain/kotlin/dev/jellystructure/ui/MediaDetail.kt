@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.encodeURIComponent
import dev.jellystructure.api.ArtworkCandidate
import dev.jellystructure.api.ArtworkCandidatesResponse
import dev.jellystructure.api.ConfigApi
import dev.jellystructure.api.JsTag
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.PersonSearchResult
import dev.jellystructure.api.DriftField
import dev.jellystructure.api.TmdbMatchResult
import dev.jellystructure.model.Episode
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.NfoFileNode
import dev.jellystructure.model.NfoFileTree
import dev.jellystructure.model.Person
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LangStepOutcome
import dev.jellystructure.resolver.LanguageResolver
import dev.jellystructure.resolver.CascadeStepOutcome
import dev.jellystructure.resolver.CertificationCatalog
import dev.jellystructure.resolver.CertificationResolver
import kotlin.js.JsAny
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

private const val TMDB_IMG_LG = "https://image.tmdb.org/t/p/w500"
private const val MONO_CODE_STYLE = "font-family:'JetBrains Mono',monospace;font-size:.78rem;"

// Phase 144: still-image codecs some releases mux as a *video* track (cover art) — kept in lockstep
// with the backend's TriageDetection.IMAGE_VIDEO_CODECS.
private val IMAGE_VIDEO_CODECS = setOf("png", "mjpeg", "mjpg", "jpeg", "jpg", "bmp", "gif", "webp", "tiff")

/** The specifier of a cover-image track muxed as video (alongside a real video), or null. */
private fun coverVideoSpecifier(tracks: List<Track>): String? {
    val videos = tracks.filter { it.kind == TrackKind.VIDEO }
    if (videos.none { it.codec.lowercase() !in IMAGE_VIDEO_CODECS }) return null
    return videos.firstOrNull { it.codec.lowercase() in IMAGE_VIDEO_CODECS }?.specifier
}

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
        val ageRatingCascade = config?.config?.metadata?.ageRatingCascade ?: emptyList()
        val tmdbLangs = if (item.tmdbId != null) MediaApi.getTmdbLanguages(item.id) else null
        val jsTags = dev.jellystructure.api.MetadataApi.getAllJsTags() ?: emptyList()
        renderDetailView(container, item, scope, fallbackLang, jellyfinUrl, tmdbLangs, jsTags = jsTags, initialTab = initialTab, ageRatingCascade = ageRatingCascade)
    }
}

private fun buildResolverTrace(item: MediaItem, fallbackLang: String, tmdbLangs: Set<String>?): String {
    if (item.kind == MediaKind.TV_SHOW) return ""
    if (item.tracks.none { it.kind == TrackKind.AUDIO }) return ""
    // One shared resolver drives both the real backend decision and this visualisation, fed with the
    // languages TMDB actually has (tmdbLangs) so the trace matches what a re-pull will fetch.
    val resolution = LanguageResolver.resolve(item.tracks, fallbackLang, tmdbLangs)
    val resolved = resolution.language
    val traceLines = resolution.steps.filter { !it.fallback }.joinToString("") { step ->
        val spec = (step.specifier ?: "").esc()
        val lang = (step.language ?: "").esc()
        when (step.outcome) {
            LangStepOutcome.UNTAGGED ->
                """<div class="muted">$spec <span style="font-size:.85em">??</span> untagged → skipped</div>"""
            LangStepOutcome.WINNER ->
                """<div>$spec <span class="lang">$lang</span>? <span style="color:var(--ok)">✓ TMDB result → winner</span></div>"""
            LangStepOutcome.DUPLICATE ->
                """<div class="muted">$spec <span class="lang">$lang</span> → duplicate, already resolved</div>"""
            LangStepOutcome.SKIPPED ->
                """<div class="muted">$spec <span class="lang">$lang</span> → skipped (winner already found)</div>"""
            LangStepOutcome.NO_RESULT ->
                """<div class="muted">$spec <span class="lang">$lang</span>? → tried, no TMDB result</div>"""
        }
    }
    val fallbackStep = resolution.steps.firstOrNull { it.fallback }
    val fallbackLine = when (fallbackStep?.outcome) {
        LangStepOutcome.WINNER ->
            """<div>fallback → <span class="lang">${(fallbackStep.language ?: "").esc()}</span> <span style="color:var(--ok)">✓ TMDB result → winner</span></div>"""
        LangStepOutcome.NO_RESULT ->
            """<div class="muted">fallback <span class="lang">${(fallbackStep.language ?: "").esc()}</span> → no TMDB match</div>"""
        else -> ""
    }
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

/** Phase 106: pagebar `.cert` chip — the resolved region+code, colour-coded by maturity tier. Empty when
 *  the item has no certification data at all (never re-runs the cascade client-side; server-resolved). */
private fun buildAgeRatingBadge(item: MediaItem, ageRatingCascade: List<String>): String {
    val cert = CertificationResolver.resolve(ageRatingCascade, item.certifications) ?: return ""
    val region = CertificationCatalog.BY_CODE[cert.region]
    val title = buildString {
        append(region?.let { "${it.name} · ${it.system}" } ?: cert.region)
        if (cert.fallback) append(" — via region cascade (no cascade region had a rating)")
    }
    return """<span class="cert lvl-${cert.tier}" title="${title.esc()}"><span class="cert-rg">${cert.region.esc()}</span><span class="cert-code">${cert.code.esc()}</span></span>"""
}

/** Phase 106: "Age rating" sidebar trace card — mirrors the language-cascade trace card
 *  ([buildResolverTrace]) so admins meet one consistent mental model for both cascades. */
private fun buildAgeRatingTrace(item: MediaItem, ageRatingCascade: List<String>): String {
    if (item.certifications.isEmpty()) return ""
    if (ageRatingCascade.isEmpty()) {
        return """
        <div class="override" style="margin-top:16px;">
          <div class="row center"><h4 style="margin:0;">Age rating</h4><span class="spacer"></span><span class="badge info">region cascade</span></div>
          <div class="tiny muted" style="margin-top:8px;">No region cascade configured — <a href="#/settings?tab=metadata">Settings → Metadata →</a></div>
        </div>""".trimIndent()
    }
    val trace = CertificationResolver.trace(ageRatingCascade, item.certifications)
    val rows = trace.joinToString("") { step ->
        val region = CertificationCatalog.BY_CODE[step.region]
        val label = region?.let { "${it.name} · ${it.system}" } ?: step.region
        when (step.outcome) {
            CascadeStepOutcome.USED -> {
                val c = step.certification!!
                """<div class="rc-tr use"><span class="rc-cc">${step.region.esc()}</span><span class="rc-tn">${label.esc()}</span><span class="cert lvl-${c.tier}"><span class="cert-rg">${c.region.esc()}</span><span class="cert-code">${c.code.esc()}</span></span></div>"""
            }
            CascadeStepOutcome.SKIPPED ->
                """<div class="rc-tr skip"><span class="rc-cc">${step.region.esc()}</span><span class="rc-tn">${label.esc()}</span><span class="rc-tv">no certification</span></div>"""
            CascadeStepOutcome.NOT_REACHED ->
                """<div class="rc-tr rest"><span class="rc-cc">${step.region.esc()}</span><span class="rc-tn">${label.esc()}</span><span class="rc-tv">not reached</span></div>"""
        }
    }
    val notInCascade = item.certifications
        .filterKeys { key -> ageRatingCascade.none { it.equals(key, ignoreCase = true) } }
        .filterValues { it.isNotBlank() }
        .entries.sortedBy { it.key }
    val notInCascadeLine = if (notInCascade.isEmpty()) "" else {
        val list = notInCascade.joinToString(" · ") { (region, code) -> "${region.uppercase().esc()} ${code.esc()}" }
        """<div class="tiny muted" style="margin-top:8px;">TMDB also has: $list — add a region to your cascade to use one.</div>"""
    }
    return """
    <div class="override" style="margin-top:16px;">
      <div class="row center"><h4 style="margin:0;">Age rating</h4><span class="spacer"></span><span class="badge info">region cascade</span></div>
      <div class="tiny" style="margin-top:8px;">The certification shown in Ravilo is resolved by walking the global <b>region cascade</b> and using the first region TMDB has a rating for. <a href="#/settings?tab=metadata">Settings → Metadata →</a></div>
      <div class="box flat" style="margin-top:10px;background:var(--bg-2);">
        <div class="rc-trace">$rows</div>
      </div>
      $notInCascadeLine
      <div class="tiny muted" style="margin-top:8px;">Stored on the item &amp; filterable in the <a href="#/library">Library workbench</a>.</div>
    </div>""".trimIndent()
}

private fun tsAbs(epochSec: Long): String = dev.jellystructure.formatFullDateTime(epochSec.toString())
private fun tsAgo(epochSec: Long): String = dev.jellystructure.formatRelativeAgo(epochSec.toString())
private fun tsRow(label: String, epochSec: Long?, first: Boolean = false, helpTip: String? = null): String {
    val v = if (epochSec != null) """${tsAbs(epochSec)} <span class="ago">· ${tsAgo(epochSec)}</span>""" else """<span class="muted">—</span>"""
    val help = if (helpTip != null) """<span class="help-dot">?<span class="tip">$helpTip</span></span>""" else ""
    return """<div class="ts-row${if (first) " first" else ""}"><span class="ts-k">${label.esc()}$help</span><span class="ts-v">$v</span></div>"""
}

/** Phase 131: abbreviate a vote count for the compact pagebar pill (28,431 → "28K"); the full count
 *  is still shown in the tooltip and the Overview card. */
private fun abbrevVotes(n: Long): String = when {
    n >= 1_000_000 -> "${(n / 100_000) / 10.0}M".replace(".0M", "M")
    n >= 1_000     -> "${n / 1000}K"
    else           -> n.toString()
}

/** Phase 131: pagebar `.imdb-pill` — compact IMDb ★ rating · votes, linking to imdb.com. Hidden
 *  entirely when the item has no rating yet (no imdbId, or not synced). */
private fun buildImdbPillHtml(item: MediaItem): String {
    val imdbId = item.imdbId?.takeIf { it.isNotBlank() } ?: return ""
    val rating = item.imdbRating ?: return ""
    val ratingStr = (kotlin.math.round(rating.aggregateRating * 10) / 10).let { if (it == it.toLong().toDouble()) "${it.toLong()}.0" else it.toString() }
    val syncedAgo = dev.jellystructure.formatRelativeAgo(rating.syncedAt.toString())
    return """<a class="imdb-pill" href="https://www.imdb.com/title/$imdbId/" target="_blank" rel="noopener" title="IMDb $ratingStr/10 · ${rating.voteCount} votes · synced $syncedAgo"><span class="imdb-wm"><span class="imdb-star">★</span>IMDb</span><b>$ratingStr</b><span class="imdb-votes">${abbrevVotes(rating.voteCount)}</span></a>"""
}

/** Phase 131: Overview "IMDb rating" card — score, votes, id link, synced-ago, Re-sync. Empty state
 *  (no card) when the item has no imdbId yet. */
private fun buildImdbCard(item: MediaItem): String {
    val imdbId = item.imdbId?.takeIf { it.isNotBlank() } ?: return ""
    val rating = item.imdbRating
    if (rating == null) {
        return """
        <div class="card" id="imdb-card" style="margin-top:16px;">
          <div class="row center"><h4 style="margin:0;">IMDb rating</h4></div>
          <hr class="dash" style="margin:10px 0 14px;">
          <div class="tiny muted">Not synced yet.</div>
          <div class="pill-row" style="margin-top:11px;">
            <span class="btn sm ghost" id="imdb-resync">Re-sync from IMDb</span>
          </div>
        </div>"""
    }
    val ratingStr = (kotlin.math.round(rating.aggregateRating * 10) / 10).let { if (it == it.toLong().toDouble()) "${it.toLong()}.0" else it.toString() }
    val syncedAgo = dev.jellystructure.formatRelativeAgo(rating.syncedAt.toString())
    return """
    <div class="card" id="imdb-card" style="margin-top:16px;">
      <div class="row center"><h4 style="margin:0;">IMDb rating</h4><span class="spacer"></span><span class="badge info">synced</span></div>
      <div class="imdb-big" style="margin-top:10px;">
        <span class="imdb-wm"><span class="imdb-star">★</span>IMDb</span>
        <span class="imdb-score"><span>$ratingStr</span><span class="imdb-max">/10</span></span>
      </div>
      <div class="tiny muted" style="margin-top:5px;">${rating.voteCount} votes</div>
      <hr class="dash" style="margin:10px 0;">
      <div class="row center tiny" style="justify-content:space-between;gap:7px;">
        <span><span class="muted">id</span> <a href="https://www.imdb.com/title/$imdbId/" target="_blank" rel="noopener" class="mono">$imdbId</a></span>
        <span class="muted">synced $syncedAgo</span>
      </div>
      <div class="pill-row" style="margin-top:10px;"><span class="btn sm fill" id="imdb-resync" style="justify-content:center;">Re-sync from IMDb</span></div>
    </div>"""
}

/** Phase 130: "Trailer" card on the Overview tab — the one official trailer ingested from TMDB's
 *  `videos` section (YouTube/Vimeo). Mirrors design's `#trailer-card`. Empty state when the item has
 *  no trailer (nothing usable on TMDB, or never re-fetched since scan). */
private fun buildTrailerCard(item: MediaItem): String {
    val trailer = item.trailer
    if (trailer == null) {
        return """
        <div class="card" id="trailer-card" style="margin-top:16px;">
          <div class="row center"><h4 style="margin:0;">Trailer</h4><span class="badge info" style="margin-left:8px;">from TMDB</span></div>
          <hr class="dash" style="margin:10px 0 14px;">
          <div class="tiny muted">No trailer — TMDB had no usable video.</div>
          <div class="pill-row" style="margin-top:11px;">
            <span class="btn sm ghost" id="trailer-refetch">Re-fetch from TMDB</span>
          </div>
        </div>"""
    }
    val isYoutube = trailer.site == "youtube"
    val watchUrl = if (isYoutube) "https://www.youtube.com/watch?v=${trailer.key.esc()}" else "https://vimeo.com/${trailer.key.esc()}"
    val thumbUrl = if (isYoutube) "https://img.youtube.com/vi/${trailer.key.esc()}/hqdefault.jpg" else trailer.thumb
    val thumbHtml = if (thumbUrl != null)
        """<img src="${thumbUrl.esc()}" alt="${item.title.esc()} trailer thumbnail" loading="lazy">"""
    else ""
    val srcBadge = if (isYoutube) """<span class="tr-src yt">YouTube</span>""" else """<span class="tr-src vm">Vimeo</span>"""
    val name = trailer.name.ifBlank { "Trailer" }
    return """
    <div class="card" id="trailer-card" style="margin-top:16px;">
      <div class="row center"><h4 style="margin:0;">Trailer</h4><span class="badge info" style="margin-left:8px;">from TMDB</span><span class="spacer"></span><span class="tiny muted">TMDB <b>videos</b> → shown as ▷ Trailer in Ravilo</span></div>
      <hr class="dash" style="margin:10px 0 14px;">
      <div class="row" style="gap:16px;align-items:flex-start;">
        <a class="tr-thumb" href="$watchUrl" target="_blank" rel="noopener" title="Open trailer on ${if (isYoutube) "YouTube" else "Vimeo"}">
          $thumbHtml
          <span class="tr-play">▶</span>
        </a>
        <div style="flex:1;min-width:0;">
          <div class="row center" style="gap:9px;"><b>${name.esc()}</b>$srcBadge</div>
          <div class="pill-row" style="margin-top:11px;">
            <a class="btn sm" href="$watchUrl" target="_blank" rel="noopener">▷ Preview ↗</a>
            <span class="btn sm ghost" id="trailer-refetch">Re-fetch from TMDB</span>
            <span class="btn sm ghost" id="trailer-clear">Clear</span>
          </div>
          <div class="mono tiny muted" style="margin-top:9px;">${trailer.site.esc()} · ${trailer.key.esc()}</div>
        </div>
      </div>
    </div>"""
}

/** Phase 108: full-width "Timestamps" card at the bottom of the Overview tab — everything we know about
 *  when a title was created/updated/scanned, in Jellystructure and in Jellyfin. Mirrors design's #ts-card. */
private fun buildTimestampsCard(item: MediaItem): String {
    val createdHelp = "<b>Created in Jellystructure</b> — when this title was first added to your library. " +
        (if (item.kind == MediaKind.TV_SHOW)
            "Ravilo's <b>Newly Added</b> rows sort a series by its <b>most-recently-added episode</b> (see Seasons &amp; episodes)."
        else "This is the timestamp Ravilo's <b>Newly Added</b> rows sort by.")
    return """
    <div class="card" id="ts-card" style="margin-top:16px;">
      <div class="row center"><h4 style="margin:0;">Timestamps</h4><span class="tiny muted" style="margin-left:8px;">when this ${if (item.kind == MediaKind.TV_SHOW) "series" else "title"} was created, updated &amp; scanned — in Jellystructure and in Jellyfin</span></div>
      <hr class="dash" style="margin:10px 0 14px;">
      <div class="ts-cols">
        <div>
          <div class="ts-h">Jellystructure</div>
          ${tsRow("Created", item.createdAt, first = true, helpTip = createdHelp)}
          ${tsRow("Updated", item.updatedAt)}
          ${tsRow("Last scanned", item.scannedAt)}
        </div>
        <div>
          <div class="ts-h">Jellyfin</div>
          ${tsRow("Created", item.addedAt, first = true)}
          ${tsRow("Updated", item.jellyfinUpdatedAt)}
        </div>
      </div>
    </div>"""
}

private fun renderDetailView(container: Element, item: MediaItem, scope: CoroutineScope, fallbackLang: String = "en", jellyfinUrl: String = "", tmdbLangs: Set<String>? = null, jsTags: List<JsTag> = emptyList(), initialTab: String? = null, ageRatingCascade: List<String> = emptyList()) {
    val isTvShow = item.kind == MediaKind.TV_SHOW

    val posterHtml = if (item.posterPath != null) {
        """<img src="${posterSrc(item.posterPath, TMDB_IMG_LG)}" alt="${item.title.esc()}"
             style="width:100%;height:auto;border-radius:4px;">"""
    } else {
        """<div class="imgslot" style="height:260px;"><div class="x"></div><span>No poster</span></div>"""
    }

    // Phase 94: genre provenance, derived from the TMDB baseline (item.tmdbGenres). A user-added genre
    // (present but not in the baseline) gets an accent dot; TMDB genres the user removed (in the baseline,
    // gone from genres) show as greyed "restore" chips. Empty baseline (pre-first-sync) → no markers.
    val tmdbBaseline = item.tmdbGenres
    val userAddedGenres = if (tmdbBaseline.isNotEmpty()) item.genres.filterNot { it in tmdbBaseline }.toSet() else emptySet()
    val userRemovedGenres = tmdbBaseline.filterNot { it in item.genres }
    fun genreChipHtml(genre: String): String {
        val dot = if (genre in userAddedGenres) """<span title="Added by you — kept across TMDB sync" style="display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--hi);margin-right:5px;flex:0 0 auto;"></span>""" else ""
        return """<span class="chip" style="display:inline-flex;align-items:center;">$dot${genre.esc()} <span class="genre-rm" data-genre="${genre.esc()}" style="cursor:pointer;margin-left:4px;color:var(--bad);">✕</span></span>"""
    }
    fun genreRestoreHtml(genre: String): String =
        """<span class="chip genre-restore" data-genre="${genre.esc()}" title="Removed by you — TMDB still lists this genre. Click ↺ to restore." style="display:inline-flex;align-items:center;cursor:pointer;opacity:.5;text-decoration:line-through;">${genre.esc()} <span style="margin-left:4px;text-decoration:none;">↺</span></span>"""
    val genresHtml = """<div class="field" id="genres-section">
             <label>Genres <button class="diff-trigger" id="diff-genres">≠</button></label>
             <div id="genres-chips" style="display:flex;flex-wrap:wrap;gap:5px;align-items:center;">
               ${item.genres.joinToString("") { genreChipHtml(it) }}
               ${userRemovedGenres.joinToString("") { genreRestoreHtml(it) }}
               <span id="genre-add-chip" class="chip ghost" style="cursor:pointer;">＋ add</span>
             </div>
             <div class="tiny muted" style="margin-top:6px;">Your genre edits survive TMDB re-syncs — <span style="color:var(--acc-ink)">●</span> added by you${if (userRemovedGenres.isNotEmpty()) "; strikethrough = removed, TMDB still has it" else ""}.</div>
             <div id="genre-add-row" style="display:none;position:relative;gap:6px;margin-top:6px;align-items:center;">
               <input id="genre-input" class="input" type="text" placeholder="pick or type a genre…" maxlength="40" style="width:200px;" autocomplete="off">
               <button id="genre-add-btn" class="btn sm ghost">Add</button>
               <div id="genre-suggest" style="display:none;flex-wrap:wrap;gap:5px;align-content:flex-start;position:absolute;top:100%;left:0;z-index:60;margin-top:4px;width:300px;max-height:230px;overflow:auto;background:var(--fill);border:1px solid var(--line);border-radius:8px;box-shadow:0 10px 30px rgba(0,0,0,.5);padding:8px;"></div>
             </div>
           </div>"""

    val directorFieldLabel = when (item.kind) {
        MediaKind.TV_SHOW -> "Network"
        MediaKind.MUSIC_VIDEO -> "Artist"  // Phase 168 (FR-168-1): still writes to `director` — no schema change
        MediaKind.MOVIE -> "Director"
    }
    val directorHtml = """<div class="field">
      <label>$directorFieldLabel <button class="diff-trigger" id="diff-edit-director">≠</button></label>
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
        val dot = if (jt != null) """<span class="tag-dot" style="background:${jt.color};"></span>""" else ""
        return """<span class="chip tag-chip" style="cursor:default;">$dot${tag.esc()} <span class="rm tag-rm" data-tag="${tag.esc()}">✕</span></span>"""
    }
    val tagSuggestions = jsTags.joinToString("") { jt ->
        """<div class="tag-suggest-item" data-tag="${jt.name.esc()}" style="display:flex;align-items:center;gap:7px;padding:5px 10px;cursor:pointer;font-size:.85rem;border-radius:6px" onmouseover="this.style.background='var(--fill-2)'" onmouseout="this.style.background=''"><span class="tag-dot" style="background:${jt.color};"></span>${jt.name.esc()}</div>"""
    }
    val tagsChipsHtml = """<div class="card" id="tags-section">
      <div class="row center"><h4 style="margin:0;">Tags</h4><span class="spacer"></span><span class="tiny muted">written to NFO &lt;tag&gt; · <a href="#/metadata?tab=tags">manage tags →</a> <button class="diff-trigger" id="diff-tags">≠</button></span></div>
      <hr class="dash" style="margin:10px 0;">
      <div id="tags-chips" class="pill-row" style="margin-bottom:10px;">
        ${currentTags.joinToString("") { tagChipHtml(it) }}
      </div>
      <div class="row center" style="gap:8px;position:relative;flex-wrap:wrap;">
        <div style="position:relative">
          <input id="tag-input" class="input" type="text" placeholder="＋ add a tag…" maxlength="40" style="max-width:260px;" autocomplete="off">
          <div id="tag-dropdown" class="card" style="display:none;position:absolute;top:calc(100% + 4px);left:0;z-index:50;width:240px;padding:6px;box-shadow:var(--shadow);">$tagSuggestions</div>
        </div>
        <button id="tag-add-btn" class="btn sm ghost">Add</button>
        <span class="tiny muted">Jellystructure tags (dotted) survive re-syncs.</span>
      </div>
    </div>"""

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
            "seeding" to "Seeding",
            "artwork" to "Artwork",
            "cast" to "Cast &amp; crew",
            "nfo" to "NFO (raw)",
            "history" to "History",
        )
    } else {
        listOf(
            "overview" to "Overview",
            "tracks" to "Tracks &amp; subtitles",
            "seeding" to "Seeding",
            "artwork" to "Artwork",
            "cast" to "Cast &amp; crew",
            "nfo" to "NFO (raw)",
            "history" to "History",
        )
    }
    val tabIds = tabItems.map { it.first }
    val activeTab = if (initialTab != null && tabIds.contains(initialTab)) initialTab else "overview"

    val tabBarHtml = tabItems.joinToString("") { (key, label) ->
        val cls = if (key == activeTab) """ class="on"""" else ""
        """<span$cls data-tab="$key">$label</span>"""
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

    val tracksHtml = if (!isTvShow) buildUnifiedTrackEditorShell("trk", item.path) + movieSegmentsCardShellHtml(item.id) + bazarrMovieCardShellHtml() else ""

    val resolverTraceHtml = buildResolverTrace(item, fallbackLang, tmdbLangs)
    val ageRatingTraceHtml = buildAgeRatingTrace(item, ageRatingCascade)
    val ageRatingBadgeHtml = buildAgeRatingBadge(item, ageRatingCascade)
    val episodesTabHtml = if (isTvShow) buildEpisodesTab(item) else ""

    // Phase 144: cover-art-muxed-as-video banner + one-click repair. Movie → drop its cover video
    // stream; series → drop it from every affected episode file. Each drop is a queued ffmpeg remux.
    val movieCoverSpec = if (!isTvShow) coverVideoSpecifier(item.tracks) else null
    val coverEpisodes = if (isTvShow) item.episodes.mapNotNull { ep -> coverVideoSpecifier(ep.tracks)?.let { ep.filename to it } } else emptyList()
    val hasCoverIssue = movieCoverSpec != null || coverEpisodes.isNotEmpty()
    val coverBannerHtml = if (!hasCoverIssue) "" else {
        val detail = if (isTvShow) "${coverEpisodes.size} episode${if (coverEpisodes.size != 1) "s" else ""} have"
                     else "This file has"
        val btnLabel = if (isTvShow) "Fix all (${coverEpisodes.size})" else "Fix cover track"
        """
        <div id="cover-banner" style="margin-bottom:14px">
          <div style="background:var(--warn-soft);border:1px solid var(--warn);border-radius:6px;padding:10px 14px;display:flex;align-items:flex-start;gap:12px;flex-wrap:wrap;">
            <span class="badge warn" style="flex:none;margin-top:1px;">⚠ Cover art muxed as video</span>
            <div style="flex:1;min-width:200px;">
              <b style="font-size:.9rem;">$detail a still image (cover art) muxed as a video track.</b>
              <div class="tiny muted" style="margin-top:5px;line-height:1.6;">
                Players can open the file but never start the video (it reads as an unknown extra video
                stream). Repair drops just the cover stream via a fast <code>ffmpeg -c copy</code> remux —
                no re-encode, audio and subtitles untouched.
              </div>
              <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;">
                <button id="cover-fix-btn" class="btn sm warn">$btnLabel</button>
              </div>
            </div>
          </div>
        </div>"""
    }

    // Detail topbar external links (design media.html: grouped into an "External links ▾" menu).
    val tmdbUrl: String? = if (item.tmdbId != null) {
        val tmdbPath = if (item.kind == MediaKind.TV_SHOW) "tv" else "movie"
        val lang = item.resolvedLanguage
        val langParam = if (!lang.isNullOrBlank()) "?language=$lang" else ""
        "https://www.themoviedb.org/$tmdbPath/${item.tmdbId}$langParam"
    } else null
    val jellyfinItemUrl: String? =
        if (jellyfinUrl.isNotBlank() && item.jellyfinId != null) "$jellyfinUrl/web/index.html#!/details?id=${item.jellyfinId}" else null

    // Left-rail cards (poster / series-language / identity). For TV shows these are lifted out of
    // the overview panel so they persist across every tab (design series.html).
    val leftRailHtml = """
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
            </div>"""

    // The right-hand "Metadata" editing column shown on the overview tab.
    val overviewMainHtml = """
            <div class="col fill">
              <div class="card">
                <div class="row center" style="margin-bottom:10px;">
                  <h4 style="margin:0;">Metadata</h4>
                  <span class="spacer"></span>
                  <span class="tiny muted">saved automatically</span>
                  <span id="save-metadata-msg" class="tiny" style="margin-left:8px;color:var(--ok);"></span>
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
                  ${if (item.overview.isNullOrBlank() && !item.resolvedLanguage.isNullOrBlank())
                    """<div class="tiny warn" style="margin-top:4px;">No ${langDisplay(item.resolvedLanguage!!)} overview on TMDB — you can write one above</div>"""
                  else ""}
                </div>
                $genresHtml
                $directorHtml
                <div class="field" style="margin-top:12px;">
                  <label>File path</label>
                  <div class="input mono" style="font-size:.82rem;word-break:break-all;">${item.path.esc()}</div>
                </div>
              </div>
              $tagsChipsHtml
              $resolverTraceHtml
              $ageRatingTraceHtml
            </div>"""

    // Both movies and TV shows keep the left rail inside the overview panel so other tabs
    // (Tracks / Episodes / Artwork / NFO / History) are full-width.
    val overviewPanelInner = """
          <div class="row" style="align-items:flex-start;gap:22px;flex-wrap:wrap;">
            $leftRailHtml
            $overviewMainHtml
          </div>
          ${buildTrailerCard(item)}
          ${buildImdbCard(item)}
          ${buildTimestampsCard(item)}"""

    // The tab bar + all tab panels.
    val tabsAndPanelsHtml = """
        <div class="tabs2" id="detail-tabs">$tabBarHtml</div>

        <div id="tab-overview" ${if (activeTab != "overview") """style="display:none;" """ else ""}>
          $overviewPanelInner
        </div>

        ${if (!isTvShow) """
        <div id="tab-tracks" ${if (activeTab != "tracks") """style="display:none;" """ else ""}>
          $tracksHtml
        </div>""" else ""}

        ${if (isTvShow) """<div id="tab-episodes" ${if (activeTab != "episodes") """style="display:none;" """ else ""}>${episodesTabHtml}</div>""" else ""}

        <div id="tab-cast" ${if (activeTab != "cast") """style="display:none;" """ else ""}>
          ${if (isTvShow) renderSeriesCastTabHtml(item) else renderMovieCastTabHtml(item)}
        </div>

        <div id="tab-artwork" ${if (activeTab != "artwork") """style="display:none;" """ else ""}>
          <div class="art-mgr">
            <div class="card art-rail" id="art-rail"><span class="muted tiny">Loading…</span></div>
            <div class="card art-gallery" id="art-gallery"><span class="muted tiny">Select an asset on the left.</span></div>
          </div>
          <iframe id="upload-frame" name="upload-frame" style="display:none"></iframe>
          <form id="art-upload-form" method="post" action="/api/media/${item.id}/artwork/upload"
                enctype="multipart/form-data" target="upload-frame" style="display:none">
            <input type="hidden" name="type" id="art-upload-type" value="poster">
            <input type="file" id="art-upload-file" name="file" accept="image/jpeg,image/jpg,image/png,image/webp">
          </form>
        </div>

        <div id="tab-nfo" ${if (activeTab != "nfo") """style="display:none;" """ else ""}>
          <div class="card" id="nfo-card">
            <div class="row center" style="margin-bottom:10px;">
              <h4 style="margin:0">NFO on disk</h4>
              <span class="spacer"></span>
              <span class="muted tiny">Read-only — NFO is generated from the other tabs and written on Save.</span>
            </div>
            <div class="nfo-layout">
              <div id="nfo-tree" class="nfo-tree"><span class="muted tiny">Loading…</span></div>
              <div class="nfo-viewer">
                <div class="mono tiny muted" id="nfo-path" style="margin-bottom:6px;word-break:break-all;"></div>
                <pre class="log" id="nfo-raw" style="font-size:.73rem;line-height:1.5;max-height:480px;overflow:auto"></pre>
              </div>
            </div>
          </div>
        </div>

        <div id="tab-history" ${if (activeTab != "history") """style="display:none;" """ else ""}>
          <div class="card" id="history-card">
            <h4 style="margin:0 0 12px;">Action history</h4>
            <div id="history-list"><span class="muted tiny">Loading…</span></div>
          </div>
        </div>

        <div id="tab-seeding" ${if (activeTab != "seeding") """style="display:none;" """ else ""}>
          <div id="seeding-root"><span class="muted tiny">Loading…</span></div>
        </div>"""

    // Tabs at top level; left rail is inside the overview panel — other tabs are full-width.
    val bodyHtml = tabsAndPanelsHtml

    container.innerHTML = """
        <div class="pagebar">
          <button id="back-btn" class="btn sm ghost">‹ Library</button>
          <h2>${item.title.esc()} <span class="muted">${if (item.year != null) "(${item.year})" else ""}</span></h2>
          ${if (item.tmdbId != null) """<span class="badge ok" id="match-badge">TMDB matched</span>""" else """<span class="badge warn" id="match-badge">No TMDB match</span>"""}
          <span class="audio-flags" id="audio-flags">${audioFlagsHtml(item.tracks)}</span>
          $ageRatingBadgeHtml
          ${buildImdbPillHtml(item)}
          <span id="seeding-pill" style="display:none;cursor:pointer;" title="Click to open Seeding tab"></span>
          <span class="spacer"></span>
          ${run {
              val imdbUrl = item.imdbId?.takeIf { it.isNotBlank() }?.let { "https://www.imdb.com/title/$it/" }
              val tvdbUrl = if (item.kind == MediaKind.TV_SHOW) item.tvdbId?.let { "https://www.thetvdb.com/?id=$it&tab=series" } else null
              if (jellyfinItemUrl != null || tmdbUrl != null || imdbUrl != null || tvdbUrl != null) """
          <span class="menu-wrap" id="links-menu">
            <span class="btn sm ghost menu-btn">External links <span class="caret">▾</span></span>
            <div class="menu">
              ${if (jellyfinItemUrl != null) """<a class="menu-item" href="$jellyfinItemUrl" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>Open in Jellyfin<span class="mi-sub">Library item in the Jellyfin web UI</span></span></a>""" else ""}
              ${if (tmdbUrl != null) """<a class="menu-item" href="$tmdbUrl" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>View on TMDB<span class="mi-sub">themoviedb.org</span></span></a>""" else ""}
              ${if (imdbUrl != null) """<a class="menu-item" href="$imdbUrl" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>View on IMDb<span class="mi-sub">imdb.com</span></span></a>""" else ""}
              ${if (tvdbUrl != null) """<a class="menu-item" href="$tvdbUrl" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>View on TheTVDB<span class="mi-sub">thetvdb.com</span></span></a>""" else ""}
            </div>
          </span>""" else ""
          }}
          <span class="menu-wrap" id="repull-menu">
            <span class="btn sm ghost menu-btn">Re-pull <span class="caret">▾</span></span>
            <div class="menu">
              <div class="menu-item" id="repull-jellyfin-btn"><span class="mi-ic">⟲</span><span>From Jellyfin…<span class="mi-sub">Re-discover name, path, IDs &amp; tracks</span></span></div>
              <div class="menu-item" id="repull-btn"><span class="mi-ic">⟲</span><span>From TMDB<span class="mi-sub">Re-fetch metadata &amp; artwork</span></span></div>
            </div>
          </span>
          ${if (nfoDisabled) """<button id="write-nfo-refresh-btn" class="btn primary" disabled title="${nfoDisabledReason.esc()}">Save &amp; sync to Jellyfin ↻</button>""" else """
          <span class="split" id="save-split">
            <button class="btn primary" id="write-nfo-refresh-btn">Save &amp; sync to Jellyfin ↻</button>
            <span class="btn primary split-caret menu-btn"><span class="caret">▾</span></span>
            <div class="menu">
              <div class="menu-item" id="write-nfo-refresh-btn-2"><span class="mi-ic">↻</span><span>Save &amp; sync to Jellyfin<span class="mi-sub">Write NFO &amp; artwork, then trigger a Jellyfin refresh</span></span></div>
              <div class="menu-item" id="write-nfo-btn"><span class="mi-ic">↓</span><span>Save → disk<span class="mi-sub">Write NFO &amp; artwork only — no Jellyfin refresh</span></span></div>
            </div>
          </span>"""}
        </div>

        <div id="detail-msg" style="display:none;margin-bottom:14px"></div>
        <div id="nfo-perm-banner" style="display:none;margin-bottom:14px"></div>
        ${if (item.missingFromSource) """
        <div id="missing-banner" style="margin-bottom:14px">
          <div style="background:var(--bad-soft);border:1px solid var(--bad);border-radius:6px;padding:10px 14px;display:flex;align-items:flex-start;gap:12px;flex-wrap:wrap;">
            <span class="badge bad" style="flex:none;margin-top:1px;">⚠ No longer in Jellyfin</span>
            <div style="flex:1;min-width:200px;">
              <b style="font-size:.9rem;">Jellyfin no longer has this title.</b>
              <div class="tiny muted" style="margin-top:5px;line-height:1.6;">
                ${item.missingSince?.let { "Missing since ${tsAgo(it)}. " } ?: ""}It's kept here so you can review
                it, but it's hidden from Ravilo. If this was intentional — deleted, moved, or reorganized in
                Jellyfin — you can remove it from Jellystructure too. It'll only come back if Jellyfin has it
                again on a future scan.
              </div>
              <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;">
                <button id="missing-remove-btn" class="btn sm bad">Remove from Jellystructure</button>
              </div>
            </div>
          </div>
        </div>""" else ""}
        $coverBannerHtml
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

        $bodyHtml
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
                val ageRatingCascade2 = config?.config?.metadata?.ageRatingCascade ?: emptyList()
                val tmdbLangs2 = if (updated.tmdbId != null) MediaApi.getTmdbLanguages(updated.id) else null
                val jsTags2 = dev.jellystructure.api.MetadataApi.getAllJsTags() ?: emptyList()
                renderDetailView(container, updated, scope, fallback, jellyfinUrl2, tmdbLangs2, jsTags = jsTags2, ageRatingCascade = ageRatingCascade2)
            }
        }
    }

    document.getElementById("find-tmdb-match-btn")?.addEventListener("click") {
        showTmdbMatchModal(item, container, scope, fallbackLang, jellyfinUrl, tmdbLangs)
    }

    // Bug/feature: permanently remove an item Jellyfin no longer has (Phase 95 triage's "review &
    // remove if intended", finally wired up). It'll only come back if Jellyfin has it again on a scan.
    document.getElementById("missing-remove-btn")?.addEventListener("click") {
        val ok = kotlinx.browser.window.confirm(
            "Remove \"${item.title}\" from Jellystructure? This can't be undone here — it will only " +
                "come back if Jellyfin has it again on a future scan.",
        )
        if (!ok) return@addEventListener
        scope.launch {
            when (MediaApi.deleteItem(item.id)) {
                true -> App.navigate("/library?filter=missing_from_source")
                false -> showDetailMsg("Still present in Jellyfin — can't remove.", false)
                null -> showDetailMsg("Couldn't remove it — try again.", false)
            }
        }
    }

    // Phase 144: "Fix cover track" — drop the cover-image-muxed-as-video stream via queued ffmpeg
    // remux(es). Movie = one job; series = one job per affected episode file.
    document.getElementById("cover-fix-btn")?.addEventListener("click") {
        val btn = document.getElementById("cover-fix-btn")
        btn?.setAttribute("disabled", "true")
        scope.launch {
            if (movieCoverSpec != null) {
                val job = MediaApi.removeTrack(item.id, movieCoverSpec)
                if (job != null) showDetailMsg("Fixing cover track — remux queued. Track & progress in Activity ▸ Jobs.", true)
                else { showDetailMsg("Couldn't queue the repair — is the file seeding?", false); btn?.removeAttribute("disabled") }
            } else {
                var queued = 0
                for ((epFilename, spec) in coverEpisodes) {
                    if (MediaApi.removeEpisodeTrack(item.id, epFilename, spec) != null) queued++
                }
                if (queued > 0) showDetailMsg("Fixing cover track on $queued episode${if (queued != 1) "s" else ""} — remuxes queued. Progress in Activity ▸ Jobs.", true)
                else { showDetailMsg("Couldn't queue the repairs — are the files seeding?", false); btn?.removeAttribute("disabled") }
            }
        }
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

    document.getElementById("repull-btn")?.addEventListener("click") {
        scope.launch { handleRepull(item, container, scope, fallbackLang, jellyfinUrl, prevTmdbLangs = tmdbLangs) }
    }

    document.getElementById("repull-jellyfin-btn")?.addEventListener("click") {
        showRepullJellyfinModal(item, container, scope)
    }

    document.getElementById("trailer-refetch")?.addEventListener("click") {
        scope.launch { MediaApi.refetchTrailer(item.id); renderMediaDetail(container, scope, item.id) }
    }
    document.getElementById("trailer-clear")?.addEventListener("click") {
        scope.launch { MediaApi.clearTrailer(item.id); renderMediaDetail(container, scope, item.id) }
    }
    document.getElementById("imdb-resync")?.addEventListener("click") {
        scope.launch { MediaApi.syncImdbRating(item.id); renderMediaDetail(container, scope, item.id) }
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
        scope.launch { handleWriteNfo(item.id, refresh = false, scope = scope) }
    }
    document.getElementById("write-nfo-refresh-btn")?.addEventListener("click") {
        scope.launch { handleWriteNfo(item.id, refresh = true, scope = scope) }
    }
    document.getElementById("write-nfo-refresh-btn-2")?.addEventListener("click") {
        scope.launch { handleWriteNfo(item.id, refresh = true, scope = scope) }
    }
    wirePagebarMenus()

    // Artwork tab (Phase 47) is built lazily by loadArtworkTab() on first show / tab switch.

    // Wire up episode row toggles, still uploads, editing, and season sync buttons
    if (isTvShow) {
        wireEpisodeToggles()
        wireSeasonSelector(item.id, scope)
        // Load the Bazarr card for whichever season block starts visible (works for both the
        // multi-season picker and the single-season case, which has no picker at all).
        val visibleSeasonBlock = document.querySelectorAll(".ep-season-block").let { nl ->
            (0 until nl.length).map { nl.item(it) as HTMLElement }
                .firstOrNull { it.style.display != "none" }
        }
        visibleSeasonBlock?.getAttribute("data-season-block")?.toIntOrNull()?.let { s ->
            scope.launch { loadBazarrSeasonCard(item.id, s, scope) }
        }
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
        // Phase 49: on-demand uncapped re-probe of every episode file on disk.
        document.getElementById("rescan-episodes-btn")?.addEventListener("click") {
            showSyncModal(item, container, scope)
        }
        // Phase 96: open the bulk track re-order wizard.
        document.getElementById("bulk-reorder-btn")?.addEventListener("click") {
            App.navigate("/media/${item.id}/bulk-reorder")
        }
    }

    // Tab switching — updates URL so tabs are deep-linkable and Back/Forward work
    document.getElementById("detail-tabs")?.let { tabBar ->
        tabBar.querySelectorAll("span[data-tab]").let { segItems ->
            for (i in 0 until segItems.length) {
                val segItem = segItems.item(i) as? HTMLElement ?: continue
                segItem.addEventListener("click") {
                    val tab = segItem.getAttribute("data-tab") ?: return@addEventListener
                    for (j in 0 until segItems.length) {
                        (segItems.item(j) as? HTMLElement)?.className = ""
                    }
                    segItem.className = "on"
                    tabIds.forEach { id ->
                        val panel = document.getElementById("tab-$id") as? HTMLElement
                        panel?.style?.display = if (id == tab) "block" else "none"
                    }
                    if (tab == "history") {
                        document.getElementById("history-list")?.innerHTML =
                            """<span class="muted tiny">Loading…</span>"""
                        scope.launch { loadHistory(item.id, container, scope) }
                    }
                    if (tab == "artwork") scope.launch { loadArtworkTab(item, scope) }
                    if (tab == "tracks" && !isTvShow) {
                        wireUnifiedTrackEditor("trk", item.tracks, item.id, null, scope, item.resolvedLanguage, item.path)
                        scope.launch { loadBazarrMovieCard(item.id, scope) }
                        scope.launch { loadMovieSegmentsCard(item.id) }
                    }
                    if (tab == "seeding") scope.launch { loadSeedingTab(item.id, item.kind == MediaKind.TV_SHOW) }
                    if (tab == "nfo") scope.launch { loadNfoTab(item, scope) }
                    if (tab == "cast") wireCastTab(item, container, scope)
                    // Update URL silently via replaceState — no hashchange fired, no page re-render
                    val tabParam = if (tab == "overview") null else tab
                    dev.jellystructure.Router.updateQuery(mapOf("tab" to tabParam), replace = true)
                }
            }
        }
    }

    if (activeTab == "artwork") scope.launch { loadArtworkTab(item, scope) }
    scope.launch { loadDrift(item.id, scope) }
    scope.launch { loadSeedingReport(item.id, item.kind == MediaKind.TV_SHOW) }
    if (activeTab == "tracks" && !isTvShow) {
        wireUnifiedTrackEditor("trk", item.tracks, item.id, null, scope, item.resolvedLanguage, item.path)
        scope.launch { loadBazarrMovieCard(item.id, scope) }
        scope.launch { loadMovieSegmentsCard(item.id) }
    }
    if (activeTab == "seeding") scope.launch { loadSeedingTab(item.id, item.kind == MediaKind.TV_SHOW) }
    if (activeTab == "history") scope.launch { loadHistory(item.id, container, scope) }
    if (activeTab == "nfo") scope.launch { loadNfoTab(item, scope) }
    if (activeTab == "cast") wireCastTab(item, container, scope)

    // Inject styles once per document lifetime
    injectDiffStyles()
    injectTrackEditorStyles()

    // Inline metadata editing — per-field dirty indicators + diff triggers
    val editableIds = listOf("edit-title", "edit-year", "edit-original-title", "edit-overview", "edit-director", "edit-studio")
    val origGenres = item.genres

    fun currentTagSet(): Set<String> {
        val chips = document.getElementById("tags-chips")?.querySelectorAll(".tag-rm") ?: return emptySet()
        return (0 until chips.length)
            .mapNotNull { (chips.item(it) as? HTMLElement)?.getAttribute("data-tag") }
            .filter { it.isNotBlank() }.toSet()
    }

    fun currentGenreList(): List<String> {
        val chips = document.getElementById("genres-chips")?.querySelectorAll(".genre-rm") ?: return emptyList()
        return (0 until chips.length)
            .mapNotNull { (chips.item(it) as? HTMLElement)?.getAttribute("data-genre") }
            .filter { it.isNotBlank() }
    }

    // Phase 74: write-through — every metadata edit persists to the DB immediately (debounced),
    // with a "Saved ✓" toast. No Save button, no amber dirty borders, no diff popup. The NFO write
    // + Jellyfin refresh happen only via the explicit "Save → NFO" / "Save & sync" action.
    val metaMsg = document.getElementById("save-metadata-msg") as? HTMLElement
    var metaSaveJob: kotlinx.coroutines.Job? = null

    fun saveMetaNow() {
        val title = (document.getElementById("edit-title") as? HTMLInputElement)?.value?.trim()
        val year = (document.getElementById("edit-year") as? HTMLInputElement)?.value?.toIntOrNull()
        val originalTitle = (document.getElementById("edit-original-title") as? HTMLInputElement)?.value?.trim()
        val overview = (document.getElementById("edit-overview") as? HTMLTextAreaElement)?.value
        val directorVal = (document.getElementById("edit-director") as? HTMLInputElement)?.value?.trim()
        val studioVal = (document.getElementById("edit-studio") as? HTMLInputElement)?.value?.trim()
        val tagsVal = currentTagSet().toList()
        val genresVal = currentGenreList()
        scope.launch {
            metaMsg?.textContent = "Saving…"
            val updated = MediaApi.editMetadata(
                id = item.id, title = title, overview = overview, year = year,
                originalTitle = originalTitle, tags = tagsVal, genres = genresVal,
                director = if (item.kind != MediaKind.TV_SHOW) directorVal else null,
                studio = studioVal,
                network = if (item.kind == MediaKind.TV_SHOW) directorVal else null,
            )
            metaMsg?.textContent = if (updated != null) "Saved ✓" else "Save failed"
            if (updated != null) {
                FacetsCache.invalidate()
                delay(1500); metaMsg?.textContent = ""
            }
        }
    }

    // `checkDirty` name retained for the tag/genre mutation call sites; it now debounces a write-through save.
    fun checkDirty() {
        metaSaveJob?.cancel()
        metaSaveJob = scope.launch { delay(500); saveMetaNow() }
    }
    editableIds.forEach { id ->
        document.getElementById(id)?.addEventListener("input") { checkDirty() }
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
            val ke = ev as? KeyboardEvent ?: return@addEventListener
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

    // ── Genres editor (Phase 33 / design media.html, series.html) ──────────────
    fun wireGenreRemove(rm: HTMLElement) {
        rm.addEventListener("click") { rm.parentElement?.remove(); checkDirty() }
    }
    fun addGenreChip(genre: String) {
        val chipsEl = document.getElementById("genres-chips") as? HTMLElement ?: return
        val addChip = document.getElementById("genre-add-chip")
        val existing = chipsEl.querySelectorAll(".genre-rm")
        for (i in 0 until existing.length) {
            if ((existing.item(i) as? HTMLElement)?.getAttribute("data-genre")?.equals(genre, ignoreCase = true) == true) return
        }
        val tmp = document.createElement("span") as HTMLElement
        tmp.innerHTML = genreChipHtml(genre)
        val chip = tmp.firstElementChild ?: return
        if (addChip != null) chipsEl.insertBefore(chip, addChip) else chipsEl.appendChild(chip)
        (chip.querySelector(".genre-rm") as? HTMLElement)?.let { wireGenreRemove(it) }
        checkDirty()
    }
    // R128: themed picker of existing library genres (deduped + count-sorted by the API). Click one to add;
    // filtered by what you type; genres already on this item are hidden so you only see what's addable.
    var allGenres: List<Pair<String, Int>> = emptyList()
    fun renderGenreSuggest() {
        val box = document.getElementById("genre-suggest") as? HTMLElement ?: return
        val have = currentGenreList().map { it.lowercase() }.toSet()
        val filter = ((document.getElementById("genre-input") as? HTMLInputElement)?.value ?: "").trim().lowercase()
        val opts = allGenres.filter { it.first.lowercase() !in have && (filter.isEmpty() || it.first.lowercase().contains(filter)) }
        box.innerHTML = if (opts.isEmpty())
            """<span class="muted tiny" style="padding:4px 6px">${if (allGenres.isEmpty()) "Loading…" else "No matching genres"}</span>"""
        else opts.joinToString("") {
            """<span class="chip genre-sug" data-g="${it.first.esc()}" style="cursor:pointer">${it.first.esc()} <span class="muted tiny">${it.second}</span></span>"""
        }
        box.querySelectorAll(".genre-sug").let { nodes ->
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? HTMLElement ?: continue
                // mousedown (not click) so it fires before the input's blur — keeps the dropdown open for multi-add.
                el.addEventListener("mousedown") { e ->
                    e.preventDefault()
                    el.getAttribute("data-g")?.let { addGenreChip(it) }
                    val inp = document.getElementById("genre-input") as? HTMLInputElement
                    inp?.value = ""
                    renderGenreSuggest()
                    inp?.focus()
                }
            }
        }
    }
    fun showGenreSuggest(show: Boolean) {
        (document.getElementById("genre-suggest") as? HTMLElement)?.style?.display = if (show) "flex" else "none"
        if (show) renderGenreSuggest()
    }
    document.querySelectorAll("#genres-chips .genre-rm").let { nodes ->
        for (i in 0 until nodes.length) (nodes.item(i) as? HTMLElement)?.let { wireGenreRemove(it) }
    }
    // Phase 94: restore a user-removed TMDB genre — re-add it as a chip and drop the restore marker.
    document.querySelectorAll("#genres-chips .genre-restore").let { nodes ->
        for (i in 0 until nodes.length) (nodes.item(i) as? HTMLElement)?.let { el ->
            el.addEventListener("click") {
                val g = el.getAttribute("data-genre") ?: return@addEventListener
                addGenreChip(g)
                el.remove()
            }
        }
    }
    document.getElementById("genre-add-chip")?.addEventListener("click") {
        val row = document.getElementById("genre-add-row") as? HTMLElement ?: return@addEventListener
        row.style.display = if (row.style.display == "none") "flex" else "none"
        if (row.style.display == "flex") (document.getElementById("genre-input") as? HTMLInputElement)?.focus()
    }
    document.getElementById("genre-add-btn")?.addEventListener("click") {
        val input = document.getElementById("genre-input") as? HTMLInputElement ?: return@addEventListener
        val g = input.value.trim()
        if (g.isNotBlank()) addGenreChip(g)
        input.value = ""
    }
    (document.getElementById("genre-input") as? HTMLInputElement)?.addEventListener("keydown") { e ->
        val ke = e as? KeyboardEvent ?: return@addEventListener
        if (ke.key == "Enter") {
            ke.preventDefault()
            val input = document.getElementById("genre-input") as? HTMLInputElement ?: return@addEventListener
            val g = input.value.trim()
            if (g.isNotBlank()) addGenreChip(g)
            input.value = ""
        }
    }
    (document.getElementById("genre-input") as? HTMLInputElement)?.let { inp ->
        inp.addEventListener("focus") { showGenreSuggest(true) }
        inp.addEventListener("input") { renderGenreSuggest() }
        inp.addEventListener("blur") { showGenreSuggest(false) }
    }
    document.getElementById("diff-genres")?.addEventListener("click") {
        showTagsDiffPopup(origGenres.sorted(), currentGenreList().sorted(), label = "Genres")
    }
    // R128: load the library's distinct genres for the picker (deduped + count-sorted by the API).
    // Bug fix: routed through the shared FacetsCache (also used by the Library workbench) instead of
    // an uncached fetch on every single detail-page open — arguably the most-visited screen in the app.
    scope.launch {
        allGenres = FacetsCache.meta()?.genres?.map { it.value to it.count } ?: emptyList()
        if ((document.getElementById("genre-suggest") as? HTMLElement)?.style?.display == "flex") renderGenreSuggest()
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
        return """<div class="card"><span class="muted tiny">No episode data available — run a scan to populate.</span> <button id="rescan-episodes-btn" class="btn sm ghost" style="margin-left:8px;">Re-probe episode files ↻</button></div>"""
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

    // Phase 108: which episode is Ravilo's Newly-Added sort key for this series (max episode.createdAt).
    val newestEpisode = item.episodes.filter { it.createdAt != null }.maxByOrNull { it.createdAt!! }
    val newestEpisodeCard = if (newestEpisode != null) {
        val code = if (newestEpisode.seasonNumber != null && newestEpisode.episodeNumber != null)
            "S${newestEpisode.seasonNumber.toString().padStart(2, '0')}E${newestEpisode.episodeNumber.toString().padStart(2, '0')}"
        else newestEpisode.filename.substringBeforeLast('.')
        val title = newestEpisode.title?.takeIf { it.isNotBlank() } ?: code
        val helpTip = "<b>Created in Jellystructure</b> — when an episode was added to your library. A series' spot in Ravilo's <b>Newly Added</b> row uses its <b>most-recently-added episode</b>, shown here."
        """<div class="override" style="width:220px;flex:none;">
          <div class="row center"><h4 style="margin:0;white-space:nowrap;">Newest episode</h4><span class="help-dot">?<span class="tip">$helpTip</span></span></div>
          <div class="ne-ep"><span class="num mono">${code.esc()}</span>${title.esc()}</div>
          <div style="margin-top:6px;">
            ${tsRow("Added · JS", newestEpisode.createdAt, first = true)}
            ${tsRow("Scanned", item.scannedAt)}
            ${tsRow("Created · JF", newestEpisode.jellyfinCreatedAt)}
          </div>
          <div class="tiny muted" style="margin-top:10px;line-height:1.5;">This is what places <b>${item.title.esc()}</b> in Ravilo's <b>Newly Added</b>.</div>
        </div>"""
    } else ""

    // Group episodes by season
    val bySeason = item.episodes.groupBy { it.seasonNumber }
    val sortedSeasons = bySeason.toList().sortedBy { it.first ?: 999 }
    val seasonKeys = sortedSeasons.map { it.first }
    val firstSeasonKey = seasonKeys.firstOrNull()
    fun seasonSelKey(season: Int?): String = season?.toString() ?: "none"

    // Season head with prev/next buttons and searchable dropdown (design series-simpsons.html).
    val seasonSelectorHtml = if (seasonKeys.size > 1) {
        val firstLabel = if (firstSeasonKey != null) "Season $firstSeasonKey" else "Unsorted"
        val firstEpCount = sortedSeasons.firstOrNull()?.second?.size ?: 0
        val firstEpWord = if (firstEpCount == 1) "episode" else "episodes"
        val menuItems = seasonKeys.joinToString("") { s ->
            val lbl = if (s != null) "Season $s" else "Unsorted"
            val ec = sortedSeasons.find { it.first == s }?.second?.size ?: 0
            val issues = sortedSeasons.find { it.first == s }?.second?.sumOf { it.issueCount } ?: 0
            val curCls = if (s == firstSeasonKey) " cur" else ""
            val attDot = if (issues > 0) """<span class="att"></span>""" else ""
            """<div class="sp-opt$curCls" data-season-sel="${seasonSelKey(s)}"><span class="nm">${lbl.esc()}</span><span class="ec">$ec ep${if (ec != 1) "s" else ""}</span>$attDot</div>"""
        }
        """<div class="season-head">
             <h4 style="margin:0;white-space:nowrap;" id="season-title">${firstLabel.esc()}</h4>
             <span class="sp-step" id="sp-prev" title="Previous season" disabled>&#x2039;</span>
             <span style="position:relative;">
               <span class="sp-btn" id="sp-btn">${firstLabel.esc()} <span class="cnt" id="sp-btn-cnt">$firstEpCount $firstEpWord</span> <span class="muted" style="font-size:.7rem;">&#x25BE;</span></span>
               <span class="sp-menu" id="sp-menu">
                 <span class="sp-search"><span class="muted">&#x2315;</span><input type="text" id="sp-q" placeholder="Jump to season&#x2026;" autocomplete="off" inputmode="numeric"></span>
                 <span class="sp-list" id="sp-list">$menuItems</span>
               </span>
             </span>
             <span class="sp-step" id="sp-next" title="Next season">&#x203A;</span>
             <span class="spacer" style="flex:1"></span>
             <span class="btn sm ghost" id="expand-issues">Expand all issues</span>
           </div>
           <hr class="dash" style="margin:12px 0;">"""
    } else {
        // Single season: just show the Expand all issues button
        """<div class="season-head">
             <span class="spacer" style="flex:1"></span>
             <span class="btn sm ghost" id="expand-issues">Expand all issues</span>
           </div>
           <hr class="dash" style="margin:12px 0;">"""
    }

    val seasonBlocks = sortedSeasons
        .joinToString("") { (season, eps) ->
            val seasonLabel = if (season != null) "Season $season" else "Unsorted"
            val seasonIssues = eps.sumOf { it.issueCount }
            val issueSummary = if (seasonIssues > 0)
                """<span class="badge bad" style="font-size:.72rem;">$seasonIssues untagged</span>"""
            else ""
            // Phase 149: group by shared file first — a multi-episode file (`S01E01E02E03.mkv`) renders
            // as ONE combined row (buildMultiEpisodeGroupRow) instead of N separate rows; a lone episode
            // (the overwhelming majority) renders exactly as before via buildEpisodeRow.
            val fileGroups = eps.groupBy { it.path }.values.sortedBy { g -> g.minOf { it.episodeNumber ?: Int.MAX_VALUE } }
            val rows = fileGroups.mapIndexed { idx, group ->
                if (group.size > 1) buildMultiEpisodeGroupRow(group, season, idx, item.id)
                else buildEpisodeRow(group.first(), season, idx, item.id, item.scannedAt)
            }.joinToString("")
            val seasonAttr = if (season != null) """data-season="$season"""" else ""
            val hidden = if (seasonKeys.size > 1 && season != firstSeasonKey) "display:none;" else ""
            """<div class="ep-season-block" data-season-block="${seasonSelKey(season)}" style="margin-bottom:20px;$hidden">
                 <div class="row center" style="margin-bottom:8px;">
                   <h4 style="margin:0;">${seasonLabel.esc()}</h4>
                   <span class="chip" style="margin-left:8px;font-size:.75rem;">${eps.size} ep</span>
                   $issueSummary
                   <span class="spacer"></span>
                   ${if (season != null) """<button class="btn sm ghost season-sync-btn" $seasonAttr style="padding:3px 9px;font-size:.75rem;" title="Sync season $season">↻</button>""" else ""}
                 </div>
                 $rows
                 ${if (season != null) bazarrSeasonCardShellHtml(season) else ""}
               </div>"""
        }

    // Season summary chips
    val totalUntagged = item.episodes.sumOf { ep -> ep.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null } }
    // Phase 121: on-disk truth (TMDB still OR screen-grab), not TMDB-metadata-only stillPath —
    // a screen-grabbed episode has a real image and shouldn't be flagged. Missing plot text
    // (the old `missingOverview`) is no longer treated as an issue at all.
    val missingStill = item.episodes.count { !it.hasStill }
    val seasonSummary = buildString {
        append("""<div class="row center" style="gap:8px;flex-wrap:wrap;margin-bottom:16px;">""")
        if (totalUntagged > 0) append("""<span class="badge bad">$totalUntagged untagged track${if (totalUntagged != 1) "s" else ""}</span>""")
        if (missingStill > 0) append("""<span class="badge warn">$missingStill missing still${if (missingStill != 1) "s" else ""}</span>""")
        if (totalUntagged > 0) append("""<span class="tiny muted">Expand each episode row below to assign languages.</span>""")
        append("</div>")
    }

    return """
        <div>
          <div class="row center" style="margin-bottom:12px;">
            <span class="tiny muted">${item.episodes.size} episode(s) stored</span>
            <span class="spacer"></span>
            <button id="bulk-reorder-btn" class="btn sm ghost" title="Open bulk track re-order wizard">↕ Re-order audio across series</button>
            <button id="rescan-episodes-btn" class="btn sm ghost" title="Re-probe every episode file on disk (uncapped)" style="margin-left:8px;">Re-probe episode files ↻</button>
          </div>
          $seasonSummary
          <div class="row" style="align-items:flex-start;gap:16px;flex-wrap:wrap;">
            <div class="col" style="width:220px;flex:none;gap:14px;">
              $votingCard
              $newestEpisodeCard
            </div>
            <div class="col fill">
              $seasonSelectorHtml
              $seasonBlocks
            </div>
          </div>
        </div>"""
}

private fun buildEpisodeRow(ep: Episode, season: Int?, idx: Int, mediaId: String, itemScannedAt: Long): String {
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

    // Phase 128: zero audio tracks (usually a corrupt/truncated file) is distinct from "untagged" —
    // there's nothing to tag, and issueCount is 0, so it would otherwise look clean.
    val zeroAudioBadge = if (audioTracks.isEmpty())
        """<span class="badge bad" style="font-size:.7rem;" title="No audio tracks detected — open Tracks &amp; order for diagnosis">⚠ no audio</span>"""
    else ""

    // Phase 21 — flag episodes with more than one default audio track.
    val multiDefaultAudio = ep.tracks.count { it.kind == TrackKind.AUDIO && it.default } > 1
    val multiDefaultBadge = if (multiDefaultAudio)
        """<span class="badge bad" style="font-size:.7rem;">multiple default audio</span>"""
    else ""
    val multiDefaultNote = if (multiDefaultAudio)
        """<div class="note red" style="margin:0 0 10px;background:var(--bad-soft);border:1px solid var(--bad);border-radius:var(--radius-s);padding:9px 12px;">
             <b style="color:var(--bad);">Multiple default audio tracks.</b>
             <div class="tiny" style="margin-top:4px;line-height:1.5;">A file should have exactly one default audio track. Open the track editor to pick the one to keep — the others are cleared (<span class="mono">mkvpropedit</span>).</div>
           </div>"""
    else ""

    val encodedFilename = encodeURIComponent(ep.filename)
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
            ${if (ep.createdAt != null) """<span class="tiny ep-added">added ${tsAbs(ep.createdAt).substringBefore(' ')}</span>""" else ""}
            ${if (!ep.resolvedLanguage.isNullOrBlank()) """<span class="lang" style="font-size:.72rem;flex:none;">${ep.resolvedLanguage.esc()}</span>""" else ""}
            <div style="display:flex;gap:4px;flex-wrap:wrap;flex:1;">$trackChips</div>
            $multiDefaultBadge
            $issueBadge
            $zeroAudioBadge
            <span class="ep-chev" style="color:var(--ink-soft);font-size:.9rem;margin-left:4px;">›</span>
          </div>
          <div id="$bodyId" style="display:none;padding:0 12px 12px;">
            $multiDefaultNote
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
              <button class="btn sm ghost ep-trk-btn" style="font-size:.72rem;"
                data-media-id="$mediaId" data-ep-filename="${ep.filename.esc()}">Edit tracks &amp; order →</button>
            </div>
            <form id="still-form-$bodyId" method="post"
                  action="/api/media/$mediaId/episodes/${encodedFilename.esc()}/still/upload"
                  enctype="multipart/form-data" target="upload-frame" style="display:none">
              <input type="file" id="still-file-$bodyId" name="file" accept="image/jpeg,image/jpg,image/png"
                class="ep-still-file-input" data-form-id="still-form-$bodyId">
            </form>
            ${buildEpisodeTimestamps(ep, itemScannedAt)}
            ${segmentsEditorLinkHtml(mediaId, ep, encodedFilename)}
          </div>
        </div>"""
}

// Phase 163 — the old inline Skip Intro/Credits scrubber (buildSegmentEditor/wireSegmentEditors) is
// gone; the real editor is the fullscreen /segments tool (season sheet + trim view). Multi-episode
// files (partCount > 1) aren't supported there yet (detect_segments already skips them, Phase 150) —
// shown as a plain note rather than a dead link.
private fun segmentsEditorLinkHtml(mediaId: String, ep: Episode, encodedFilename: String): String {
    if (ep.partCount > 1) {
        return """<div class="row center" style="margin-top:8px;"><span class="tiny muted">Intro &amp; credits editor: not supported yet for multi-episode files.</span></div>"""
    }
    return """<div class="row center" style="margin-top:8px;">
        <a class="btn sm ghost" href="#/segments?series=$mediaId&episode=$encodedFilename&episodeNumber=${ep.episodeNumber ?: 0}">Intro &amp; credits — open the editor →</a>
      </div>"""
}

private fun msToClock(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "${m}:${s.toString().padStart(2, '0')}"
}

/**
 * Phase 149 — one row per multi-episode FILE (Option B: `design/app/series-johnnybravo.html`), replacing
 * what would otherwise be [episodes].size separate [buildEpisodeRow] rows for a file whose name spans an
 * episode range (`S01E01E02E03.mkv`). Expandable via the same `.ep-toggle-row`/`data-body` mechanism
 * [buildEpisodeRow] already uses. Sub-rows are deliberately simpler than a lone episode's row (no inline
 * title/overview/still-upload form) — per-episode metadata editing for a shared file isn't wired up yet
 * (tracked as a follow-up); "Edit tracks" opens the existing whole-file track editor, which already
 * applies correctly to every contained episode.
 */
private fun buildMultiEpisodeGroupRow(episodes: List<Episode>, season: Int?, groupIdx: Int, mediaId: String): String {
    val ordered = episodes.sortedBy { it.partIndex }
    val first = ordered.first()
    val last = ordered.last()
    fun code(ep: Episode) = if (season != null && ep.episodeNumber != null)
        "S${season.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}" else ep.filename
    val rangeLabel = if (season != null && first.episodeNumber != null && last.episodeNumber != null)
        "${code(first)}–E${last.episodeNumber.toString().padStart(2, '0')}"
    else first.filename.substringBeforeLast('.')

    // Bug fix: each panel is now a genuine 1/3-width flex child (background-size:cover scoped to its
    // OWN box), not a full-width div revealed by clip-path — the old layout made background-size:cover
    // scale each still to cover the WHOLE triptych, so panel 1 showed its still's left edge and panel 3
    // its right edge instead of each still's own center. See detail.css's .eptrip rules.
    val panels = ordered.take(3).joinToString("") { ep ->
        val bg = if (!ep.stillPath.isNullOrBlank())
            "background-image:url('$TMDB_IMG_LG${ep.stillPath}');background-size:cover;background-position:center;"
        else "background:var(--fill-3);"
        val num = (ep.episodeNumber ?: 0).toString().padStart(2, '0')
        """<div class="tp" style="$bg"><span class="tn">$num</span></div>"""
    }

    val totalRuntime = ordered.sumOf { it.runtime ?: 0 }
    val hasChapters = ordered.any { it.hasChapters }
    val chapterBadge = if (hasChapters) """<span class="badge ok" style="font-size:.7rem;">chapters</span>"""
    else """<span class="badge" style="font-size:.7rem;">no chapters — continuous</span>"""
    val untagged = ordered.sumOf { ep -> ep.tracks.count { (it.kind == TrackKind.AUDIO || it.kind == TrackKind.SUBTITLE) && it.language == null } }
    val issueBadge = if (untagged > 0) """<span class="badge bad" style="font-size:.7rem;">$untagged untagged</span>""" else ""
    val missingStillCount = ordered.count { !it.hasStill }
    val stillBadge = if (missingStillCount > 0)
        """<span class="badge warn" style="font-size:.7rem;">$missingStillCount missing still${if (missingStillCount != 1) "s" else ""}</span>"""
    else ""

    val bodyId = "mep-body-s${season ?: 0}-$groupIdx"
    val toggleId = "mep-toggle-s${season ?: 0}-$groupIdx"

    val subRows = ordered.joinToString("") { ep ->
        val c = code(ep)
        val thumb = if (!ep.stillPath.isNullOrBlank())
            """<img class="sth" src="$TMDB_IMG_LG${ep.stillPath}" alt="">"""
        else """<div class="sth"></div>"""
        val chapterInfo = if (ep.hasChapters && ep.chapterStartMs != null) "chapter ${msToClock(ep.chapterStartMs)}" else null
        val durInfo = ep.runtime?.let { "$it min" }
        val meta = listOfNotNull(chapterInfo, durInfo).joinToString(" · ")
        """<div class="sub-ep">
             <span class="sn">${c.esc()}</span>
             $thumb
             <div class="sm"><div class="stt">${(ep.title?.takeIf { it.isNotBlank() } ?: c).esc()}</div><div class="std">${meta.esc()}</div></div>
             <button class="btn sm ghost ep-trk-btn" style="font-size:.72rem;"
               data-media-id="$mediaId" data-ep-filename="${ep.filename.esc()}">Edit tracks &amp; order →</button>
           </div>"""
    }

    return """
        <div style="border:1px solid var(--line);border-radius:6px;margin-bottom:6px;overflow:hidden;background:var(--fill-2);">
          <div id="$toggleId" class="ep-toggle-row" data-body="$bodyId"
               style="display:flex;align-items:center;gap:10px;padding:9px 12px;cursor:pointer;">
            <div style="width:72px;height:40px;flex-shrink:0;"><div class="eptrip">$panels</div></div>
            <span class="num" style="min-width:96px;font-size:.82rem;">${rangeLabel.esc()}</span>
            <span class="badge acc" style="font-size:.7rem;">${ordered.size} in 1 file</span>
            <span class="tiny muted">${totalRuntime}m</span>
            $chapterBadge
            $issueBadge
            $stillBadge
            <span class="ep-chev" style="color:var(--ink-soft);font-size:.9rem;margin-left:4px;">›</span>
          </div>
          <div id="$bodyId" style="display:none;padding:0 12px 12px;">
            <div class="filechip" style="margin:8px 0;">${first.filename.esc()}</div>
            <div style="margin-bottom:6px;">$subRows</div>
            <div class="note-slim"><b>Multi-episode file.</b> One file → ${ordered.size} <span class="filechip">episodedetails.nfo</span> blocks. A track edit (reorder/default/forced) applies to the <b>whole shared file</b> — all ${ordered.size} episodes above; each episode's still and overview are still tracked independently.</div>
          </div>
        </div>"""
}

/** Phase 108: the timestamps we know about one episode file — mirrors the design's `.ep-ts` block.
 *  There's no per-episode "scanned" timestamp (episodes are scanned as part of one series scan), so
 *  that row shows the series' own last-scanned time rather than a fabricated per-episode value. */
private fun buildEpisodeTimestamps(ep: Episode, itemScannedAt: Long): String {
    fun grp(label: String, epochSec: Long?): String {
        val v = if (epochSec != null) tsAbs(epochSec) else "—"
        return """<div class="grp"><span class="k">${label.esc()}</span><span class="v">${v.esc()}</span></div>"""
    }
    return """<div class="ep-ts">
        ${grp("Added · JS", ep.createdAt)}
        ${grp("Scanned", itemScannedAt)}
        ${grp("Created · Jellyfin", ep.jellyfinCreatedAt)}
      </div>"""
}

// Phase 163 — movie "Intro & credits" summary card, replacing the old inline scrubber (removed this
// phase). Shell renders immediately; loadMovieSegmentsCard fills in the live summary from the real
// /segments editor's own data, matching the Bazarr card's lazy-load pattern right below.
private fun movieSegmentsCardShellHtml(mediaId: String): String = """
    <div class="card" id="segments-movie-card" style="margin-top:16px;">
      <div class="row center"><h3 style="margin:0;font-size:1.02rem;">Intro &amp; credits</h3></div>
      <div id="segments-movie-summary" style="margin-top:8px;"><span class="muted tiny">Loading…</span></div>
      <div class="row center" style="margin-top:10px;">
        <a class="btn sm" href="#/segments?movie=$mediaId">Open the editor →</a>
      </div>
    </div>
""".trimIndent()

private suspend fun loadMovieSegmentsCard(mediaId: String) {
    val el = document.getElementById("segments-movie-summary") ?: return
    val data = dev.jellystructure.api.SegmentApi.movieTrim(mediaId)
    el.innerHTML = when {
        data == null || data.segments.isEmpty() -> """<span class="badge bad">not detected</span> <span class="tiny muted" style="margin-left:6px;">Ravilo falls back to a fixed end-of-file guess.</span>"""
        else -> buildString {
            if (data.segments.any { it.kind == "intro" }) append("""<span class="badge ok">Intro</span> """)
            if (data.segments.any { it.kind == "credits" }) append("""<span class="badge ok">Credits</span> """)
            if (data.segments.any { it.locked }) append("""<span class="badge">🔒 locked</span> """)
            append(if (data.checked) """<span class="tiny muted">checked</span>""" else """<span class="tiny muted">not checked yet</span>""")
        }
    }
}

// Phase 157 — movie "Subtitles — Bazarr" card (FR-BZ1-4). Shell renders immediately; the card
// fetches live state and hides itself entirely if Bazarr isn't connected/matched, per the spec's
// "off ⇒ no subtitle surfaces appear anywhere" rule.
private fun bazarrMovieCardShellHtml(): String = """
    <div class="card" id="bazarr-movie-card" style="display:none;margin-top:16px;">
      <div class="row center"><h3 style="font-size:1.02rem;margin:0;">Subtitles — Bazarr</h3><span class="tiny muted" style="margin-left:8px;">sidecar files, not embedded tracks</span></div>
      <div id="bazarr-movie-rows" style="margin-top:10px;"><span class="muted tiny">Loading…</span></div>
      <div class="row center" style="margin-top:10px;gap:8px;">
        <button class="btn sm ghost" id="bazarr-movie-search-all">Search all wanted</button>
        <span class="spacer"></span>
        <a href="javascript:void(0)" id="bazarr-movie-history-link" class="tiny">View in History →</a>
      </div>
    </div>
""".trimIndent()

private fun bazarrLangRowHtml(mediaId: String, row: dev.jellystructure.api.BazarrLanguageRow): String {
    val present = row.present
    val badges = buildString {
        if (row.forced) append("""<span class="badge info" style="margin-left:6px;">Signs only</span>""")
        if (row.hi) append("""<span class="badge info" style="margin-left:6px;">Sound described</span>""")
    }
    val dataAttrs = """data-lang="${row.code2}" data-forced="${row.forced}" data-hi="${row.hi}" data-path="${(present?.path ?: "").esc()}""""
    return if (present != null) {
        """<div class="crew-row" $dataAttrs>
             <span class="lang">${row.code2.uppercase()}</span>
             <span style="flex:1;min-width:0;">${row.language.esc()}$badges<span class="tiny muted" style="display:block;">${present.name.esc()}</span></span>
             <button class="btn sm ghost bz-act" data-act="sync">Sync</button>
             <button class="btn sm ghost bz-act" data-act="upgrade">Upgrade</button>
             <button class="btn sm ghost bz-act" data-act="delete">Delete</button>
           </div>"""
    } else {
        """<div class="crew-row" $dataAttrs>
             <span class="lang">${row.code2.uppercase()}</span>
             <span style="flex:1;min-width:0;">${row.language.esc()}$badges<span class="tiny muted" style="display:block;">Wanted — no subtitle yet</span></span>
             <button class="btn sm ghost bz-act" data-act="download">Search</button>
           </div>"""
    }
}

private suspend fun loadBazarrMovieCard(mediaId: String, scope: CoroutineScope) {
    val card = document.getElementById("bazarr-movie-card") as? HTMLElement ?: return
    val state = dev.jellystructure.api.BazarrApi.titleState(mediaId)
    if (state == null || !state.connected) { card.style.display = "none"; return }
    card.style.display = "block"
    // Wired unconditionally (match-state independent) but guarded against re-binding — this function
    // re-runs after every action, and the link/button are static shell markup, never re-rendered.
    val historyLink = document.getElementById("bazarr-movie-history-link") as? HTMLElement
    if (historyLink?.getAttribute("data-wired") != "true") {
        historyLink?.setAttribute("data-wired", "true")
        historyLink?.addEventListener("click") { e ->
            e.preventDefault()
            (document.querySelector("#detail-tabs span[data-tab='history']") as? HTMLElement)?.click()
        }
    }
    if (!state.matched) {
        document.getElementById("bazarr-movie-rows")?.innerHTML = """<span class="muted tiny">Not matched in Bazarr yet — it may not have imported this title from Radarr.</span>"""
        document.getElementById("bazarr-movie-search-all")?.setAttribute("disabled", "true")
        return
    }
    val rowsEl = document.getElementById("bazarr-movie-rows") ?: return
    rowsEl.innerHTML = if (state.languages.isEmpty()) """<span class="muted tiny">No language profile configured in Bazarr.</span>"""
        else state.languages.joinToString("") { bazarrLangRowHtml(mediaId, it) }

    val bzActNodes = rowsEl.querySelectorAll(".bz-act")
    for (i in 0 until bzActNodes.length) {
        val btn = bzActNodes.item(i) as? HTMLElement ?: continue
        btn.addEventListener("click") { _ ->
            val row = btn.closest(".crew-row") as? HTMLElement ?: return@addEventListener
            val lang = row.getAttribute("data-lang") ?: return@addEventListener
            val forced = row.getAttribute("data-forced") == "true"
            val hi = row.getAttribute("data-hi") == "true"
            val path = row.getAttribute("data-path") ?: ""
            val act = btn.getAttribute("data-act")
            btn.setAttribute("disabled", "true")
            scope.launch {
                val ok = when (act) {
                    "download" -> dev.jellystructure.api.BazarrApi.download(mediaId, lang, forced, hi)
                    "sync" -> dev.jellystructure.api.BazarrApi.sync(mediaId, lang, path)
                    "upgrade" -> dev.jellystructure.api.BazarrApi.upgrade(mediaId, lang, forced, hi)
                    "delete" -> dev.jellystructure.api.BazarrApi.delete(mediaId, lang, forced, hi, path)
                    else -> false
                }
                if (ok) loadBazarrMovieCard(mediaId, scope) else btn.removeAttribute("disabled")
            }
        }
    }
    val searchAllBtn = document.getElementById("bazarr-movie-search-all") as? HTMLElement
    if (searchAllBtn?.getAttribute("data-wired") != "true") {
        searchAllBtn?.setAttribute("data-wired", "true")
        searchAllBtn?.addEventListener("click") { _ ->
            scope.launch { dev.jellystructure.api.BazarrApi.search(mediaId); loadBazarrMovieCard(mediaId, scope) }
        }
    }
}

// Phase 157 — series season-scoped "Subtitles — Bazarr" card (FR-BZ1-5). One shell per season block;
// loaded lazily when its season becomes the visible one (see wireSeasonSelector / the initial-load
// call in renderMediaDetail), not eagerly for every season up front.
private fun bazarrSeasonCardShellHtml(season: Int): String = """
    <div class="card" id="bazarr-season-card-$season" data-bazarr-season="$season" style="display:none;margin-top:10px;">
      <div class="row center"><h4 style="margin:0;font-size:.95rem;">Subtitles — Bazarr</h4></div>
      <div id="bazarr-season-rows-$season" style="margin-top:8px;"><span class="muted tiny">Loading…</span></div>
      <div class="row center" style="margin-top:8px;gap:8px;">
        <button class="btn sm ghost bz-season-search-all" data-season="$season">Search all wanted</button>
      </div>
    </div>
""".trimIndent()

private fun bazarrEpisodeRowHtml(mediaId: String, season: Int, row: dev.jellystructure.api.BazarrEpisodeRow): String {
    val presentLangs = row.subtitles.joinToString(", ") { it.code2.uppercase() }.ifBlank { "—" }
    val wantedLangs = row.missingSubtitles.joinToString(", ") { it.code2.uppercase() }
    val dataAttrs = """data-season="$season" data-episode="${row.episode}""""
    return """<div class="crew-row" $dataAttrs>
        <span class="mono" style="min-width:34px;">E${row.episode.toString().padStart(2, '0')}</span>
        <span style="flex:1;min-width:0;">${row.title.esc()}<span class="tiny muted" style="display:block;">have: $presentLangs${if (wantedLangs.isNotBlank()) " · wanted: $wantedLangs" else ""}</span></span>
        ${if (wantedLangs.isNotBlank()) """<button class="btn sm ghost bz-ep-act" data-act="download" data-lang="${row.missingSubtitles.first().code2}">Search</button>""" else ""}
      </div>"""
}

private suspend fun loadBazarrSeasonCard(mediaId: String, season: Int, scope: CoroutineScope) {
    val card = document.getElementById("bazarr-season-card-$season") as? HTMLElement ?: return
    val state = dev.jellystructure.api.BazarrApi.titleState(mediaId)
    if (state == null || !state.connected || !state.matched) { card.style.display = "none"; return }
    card.style.display = "block"
    val rows = dev.jellystructure.api.BazarrApi.seasonEpisodes(mediaId, season)
    val rowsEl = document.getElementById("bazarr-season-rows-$season") ?: return
    rowsEl.innerHTML = if (rows.isEmpty()) """<span class="muted tiny">No episode data from Bazarr for this season.</span>"""
        else rows.sortedBy { it.episode }.joinToString("") { bazarrEpisodeRowHtml(mediaId, season, it) }

    val epActNodes = rowsEl.querySelectorAll(".bz-ep-act")
    for (i in 0 until epActNodes.length) {
        val btn = epActNodes.item(i) as? HTMLElement ?: continue
        btn.addEventListener("click") { _ ->
            val row = btn.closest(".crew-row") as? HTMLElement ?: return@addEventListener
            val ep = row.getAttribute("data-episode")?.toIntOrNull() ?: return@addEventListener
            val lang = btn.getAttribute("data-lang") ?: return@addEventListener
            btn.setAttribute("disabled", "true")
            scope.launch {
                dev.jellystructure.api.BazarrApi.download(mediaId, lang, forced = false, hi = false, episodeSeason = season, episodeNumber = ep)
                loadBazarrSeasonCard(mediaId, season, scope)
            }
        }
    }
    document.getElementById("bazarr-season-card-$season")?.querySelector(".bz-season-search-all")
        ?.addEventListener("click") { _ -> scope.launch { dev.jellystructure.api.BazarrApi.search(mediaId); loadBazarrSeasonCard(mediaId, season, scope) } }
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
                        val ageRatingCascade3 = config?.config?.metadata?.ageRatingCascade ?: emptyList()
                        val langs = MediaApi.getTmdbLanguages(updated.id)
                        val jsTags = dev.jellystructure.api.MetadataApi.getAllJsTags() ?: emptyList()
                        renderDetailView(container, updated, scope, fb, jfUrl, langs, jsTags = jsTags, ageRatingCascade = ageRatingCascade3)
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
        if ((e as? KeyboardEvent)?.key == "Enter") doSearch()
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

private fun wireSeasonSelector(mediaId: String, scope: CoroutineScope) {
    // Season picker: prev/next step buttons + dropdown with search.
    val spMenu = document.getElementById("sp-menu") as? HTMLElement ?: return
    val spBtn = document.getElementById("sp-btn") as? HTMLElement ?: return
    val spList = document.getElementById("sp-list") as? HTMLElement ?: return
    val spSearch = document.getElementById("sp-q") as? HTMLInputElement
    val spPrev = document.getElementById("sp-prev") as? HTMLElement
    val spNext = document.getElementById("sp-next") as? HTMLElement
    val expandIssues = document.getElementById("expand-issues") as? HTMLElement

    fun allOpts(): List<HTMLElement> {
        val nl = spList.querySelectorAll("[data-season-sel]")
        return (0 until nl.length).mapNotNull { nl.item(it) as? HTMLElement }
    }

    fun showSeason(sel: String) {
        // Update option highlights
        val opts = allOpts()
        opts.forEach { it.classList.remove("cur") }
        val chosen = opts.firstOrNull { it.getAttribute("data-season-sel") == sel }
        chosen?.classList?.add("cur")
        // Update label + episode count in the button
        val chosenName = (chosen?.querySelector(".nm") as? HTMLElement)?.textContent ?: "Season"
        val chosenEc = (chosen?.querySelector(".ec") as? HTMLElement)?.textContent ?: ""
        document.getElementById("season-title")?.textContent = chosenName
        (spBtn.querySelector("#sp-btn-cnt") as? HTMLElement)?.textContent = chosenEc
        spBtn.firstChild?.let { if (it.nodeType == 3.toShort()) it.nodeValue = "$chosenName " }
        // Prev/next disabled state
        val selIdx = opts.indexOfFirst { it.getAttribute("data-season-sel") == sel }
        spPrev?.let { if (selIdx <= 0) it.setAttribute("disabled", "") else it.removeAttribute("disabled") }
        spNext?.let { if (selIdx >= opts.size - 1) it.setAttribute("disabled", "") else it.removeAttribute("disabled") }
        // Show/hide season blocks
        val blocks = document.querySelectorAll(".ep-season-block")
        for (k in 0 until blocks.length) {
            val block = blocks.item(k) as? HTMLElement ?: continue
            block.style.display = if (block.getAttribute("data-season-block") == sel) "" else "none"
        }
        spMenu.classList.remove("open")
        sel.toIntOrNull()?.let { scope.launch { loadBazarrSeasonCard(mediaId, it, scope) } }
    }

    spBtn.addEventListener("click") { _ ->
        spMenu.classList.toggle("open")
        if (spMenu.classList.contains("open")) {
            spSearch?.focus()
            spSearch?.value = ""
            allOpts().forEach { (it as? HTMLElement)?.style?.display = "" }
        }
    }

    spSearch?.addEventListener("input") { _ ->
        val q = spSearch.value.trim().lowercase()
        allOpts().forEach { opt ->
            val nm = (opt.querySelector(".nm") as? HTMLElement)?.textContent?.lowercase() ?: ""
            opt.style.display = if (q.isEmpty() || nm.contains(q)) "" else "none"
        }
    }

    allOpts().forEach { opt ->
        opt.addEventListener("click") { _ ->
            val sel = opt.getAttribute("data-season-sel") ?: return@addEventListener
            showSeason(sel)
        }
    }

    spPrev?.addEventListener("click") { _ ->
        val opts = allOpts()
        val idx = opts.indexOfFirst { it.classList.contains("cur") }
        if (idx > 0) showSeason(opts[idx - 1].getAttribute("data-season-sel") ?: return@addEventListener)
    }

    spNext?.addEventListener("click") { _ ->
        val opts = allOpts()
        val idx = opts.indexOfFirst { it.classList.contains("cur") }
        if (idx < opts.size - 1) showSeason(opts[idx + 1].getAttribute("data-season-sel") ?: return@addEventListener)
    }

    // Close menu on outside click
    document.addEventListener("click") { ev ->
        if (spMenu.classList.contains("open")) {
            val target = ev.target as? HTMLElement
            if (target != spBtn && !spBtn.contains(target) && target != spMenu && !spMenu.contains(target)) {
                spMenu.classList.remove("open")
            }
        }
    }

    // Expand all issues button
    expandIssues?.addEventListener("click") { _ ->
        val issueEps = document.querySelectorAll(".ep-toggle-row")
        for (k in 0 until issueEps.length) {
            val toggle = issueEps.item(k) as? HTMLElement ?: continue
            val bodyId = toggle.getAttribute("data-body") ?: continue
            val body = document.getElementById(bodyId) as? HTMLElement ?: continue
            val hasBadge = toggle.querySelector(".badge.bad") != null
            if (hasBadge && body.style.display == "none") toggle.click()
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
                    val res = MediaApi.setEpisodeTrackLanguage(mediaId, epFilename, specifier, lang)
                    if (res.error == null) {
                        renderMediaDetail(container, scope, mediaId)
                    } else {
                        resultEl?.textContent = "✗ ${res.error}"
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
                val ke = e as? KeyboardEvent ?: return@addEventListener
                if (ke.key == "Enter") {
                    val lang = input.value.trim()
                    if (lang.isEmpty()) return@addEventListener
                    val resultEl = document.querySelector(".ep-lang-result[data-specifier='$specifier']") as? HTMLElement
                    resultEl?.textContent = "…"
                    input.setAttribute("disabled", "true")
                    scope.launch {
                        val res = MediaApi.setEpisodeTrackLanguage(mediaId, epFilename, specifier, lang)
                        if (res.error == null) {
                            renderMediaDetail(container, scope, mediaId)
                        } else {
                            resultEl?.textContent = "✗ ${res.error}"
                            input.removeAttribute("disabled")
                        }
                    }
                }
            }
        }
    }

    // Episode track editor modal
    document.querySelectorAll(".ep-trk-btn").let { btns ->
        for (i in 0 until btns.length) {
            val btn = btns.item(i) as? HTMLElement ?: continue
            val epFilename = btn.getAttribute("data-ep-filename") ?: continue
            val ep = item.episodes.find { it.filename == epFilename } ?: continue
            btn.addEventListener("click") { _ ->
                openEpisodeTrackModal(ep, item.id, container, scope)
            }
        }
    }
}

private fun openEpisodeTrackModal(
    ep: Episode,
    mediaId: String,
    container: Element,
    scope: CoroutineScope,
) {
    document.getElementById("te-modal-back")?.remove()
    injectTrackEditorStyles()

    val epCode = if (ep.seasonNumber != null && ep.episodeNumber != null)
        "S${ep.seasonNumber.toString().padStart(2, '0')}E${ep.episodeNumber.toString().padStart(2, '0')}"
    else ep.filename.substringBeforeLast('.')

    val backdrop = document.createElement("div") as HTMLElement
    backdrop.id = "te-modal-back"
    backdrop.setAttribute("style", "position:fixed;inset:0;z-index:130;background:rgba(8,10,16,.66);-webkit-backdrop-filter:blur(3px);backdrop-filter:blur(3px);display:flex;align-items:center;justify-content:center;padding:24px;")
    backdrop.innerHTML = """
        <div style="width:760px;max-width:100%;max-height:90vh;overflow-y:auto;background:var(--fill);border:1px solid var(--line-2);border-radius:var(--radius);box-shadow:var(--shadow);padding:22px 24px;">
          <div class="row center" style="margin-bottom:14px;">
            <h3 style="margin:0;">Tracks &amp; order</h3>
            <span class="badge info" style="margin-left:8px;">${epCode.esc()}</span>
            <span class="spacer"></span>
            <span id="te-x" style="cursor:pointer;color:var(--ink-soft);font-size:1.1rem;margin-left:14px;">✕</span>
          </div>
          ${buildUnifiedTrackEditorShell("te", ep.path)}
        </div>
    """.trimIndent()
    document.body?.appendChild(backdrop)

    fun close() { document.getElementById("te-modal-back")?.remove() }
    document.getElementById("te-x")?.addEventListener("click") { _ -> close() }
    backdrop.addEventListener("click") { e -> if (e.target === backdrop) close() }
    document.addEventListener("keydown") { e ->
        if ((e as? KeyboardEvent)?.key == "Escape") close()
    }

    wireUnifiedTrackEditor(
        prefix = "te",
        tracks = ep.tracks,
        mediaId = mediaId,
        epFilename = ep.filename,
        scope = scope,
        resolvedLanguage = ep.resolvedLanguage,
        filePath = ep.path,
        postApply = {
            close()
            scope.launch { renderMediaDetail(container, scope, mediaId) }
        },
    )
}

// Phase 157 — Bazarr's per-title log is merged into this tab at RENDER TIME ONLY, never written to
// mediaHistoryQueries (jellystructure's own persisted, revertable audit log) — see the phase-157
// addendum on FR-BZ1-1. Fetched fresh on every History tab open/refresh, movies only (see BazarrApi).
private fun bazarrHistoryRowHtml(ev: dev.jellystructure.api.BazarrHistoryEvent): String {
    val actionLabel = when (ev.action) { 0 -> "Downloaded"; 1 -> "Deleted"; 2 -> "Manually uploaded"; 3 -> "Upgraded"; else -> "Subtitle event" }
    return """<div style="display:flex;gap:10px;padding:6px 0;border-bottom:1px solid var(--border);align-items:center;">
             <span class="muted tiny" style="width:160px;flex-shrink:0;">${(ev.timestamp ?: "").esc()}</span>
             <div style="flex:1;">
               <span class="chip" style="font-size:.72rem;">Bazarr · $actionLabel</span>
               <span class="muted tiny" style="margin-left:6px;">${(ev.language ?: "").esc()}${ev.provider?.let { " · ${it.esc()}" } ?: ""}${ev.score?.let { " · score $it" } ?: ""}</span>
             </div>
           </div>"""
}

private suspend fun loadHistory(id: String, container: Element? = null, scope: CoroutineScope? = null) {
    val listEl = document.getElementById("history-list") as? HTMLElement ?: return
    val entries = MediaApi.getHistory(id)
    val bazarrEvents = dev.jellystructure.api.BazarrApi.titleHistory(id)
    val bazarrHtml = if (bazarrEvents.isNotEmpty())
        """<div class="tiny muted" style="margin:14px 0 4px;">Subtitles (Bazarr — live, not stored here)</div>""" + bazarrEvents.joinToString("") { bazarrHistoryRowHtml(it) }
    else ""
    if (entries.isEmpty()) {
        listEl.innerHTML = """<span class="muted tiny">No history yet — write an NFO or change a track default to create entries.</span>$bazarrHtml"""
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
    } + bazarrHtml
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
        // Certifications were just re-pulled (Phase 106) — re-fetch the cascade so the badge/trace update.
        val ageRatingCascade4 = ConfigApi.get()?.config?.metadata?.ageRatingCascade ?: emptyList()
        renderDetailView(container, updated, scope, fallbackLang, jellyfinUrl, tmdbLangs, ageRatingCascade = ageRatingCascade4)
    } else {
        showDetailMsg("Re-pull failed — no TMDB match found.", false)
    }
}

private val seedingJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private suspend fun loadSeedingReport(id: String, isTvShow: Boolean) {
    val report = MediaApi.getSeedingReport(id) ?: return
    if (!report.guard.configured) return
    val pill = document.getElementById("seeding-pill") as? HTMLElement ?: return
    val torrentsJson = seedingJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(dev.jellystructure.api.TorrentRef.serializer()), report.torrents)
    val pillHtml = dev.jellystructure.seedingPillHtml(torrentsJson)
    if (pillHtml.isNotBlank()) {
        pill.innerHTML = pillHtml
        pill.style.display = ""
        pill.onclick = { _ ->
            val tabs = document.querySelectorAll(".detail-tabs span[data-tab='seeding']")
            if (tabs.length > 0) (tabs.item(0) as? HTMLElement)?.click()
        }
    }
}

private suspend fun loadSeedingTab(id: String, isTvShow: Boolean) {
    val root = document.getElementById("seeding-root") as? HTMLElement ?: return
    val report = MediaApi.getSeedingReport(id) ?: run {
        root.innerHTML = """<span class="muted tiny">Seeding data unavailable.</span>"""
        return
    }
    val trackers = MediaApi.getTrackers()
    val reportJson = seedingJson.encodeToString(dev.jellystructure.api.SeedingReport.serializer(), report)
    val trackersJson = seedingJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(dev.jellystructure.api.TrackerEntry.serializer()), trackers)
    if (isTvShow) {
        dev.jellystructure.seedingRenderSeries(root, reportJson, trackersJson)
    } else {
        dev.jellystructure.seedingRenderMovie(root, reportJson, trackersJson)
    }
}

private val DRIFT_FIELD_NAMES = mapOf(
    "title" to "Title", "year" to "Year", "tmdbId" to "TMDB ID",
    "overview" to "Overview", "genres" to "Genres", "studio" to "Studio", "network" to "Network",
)

// Phase 115 — three-state sync banner. State 1/2 are the write-through model working as designed (not
// drift); only state 3 is the real "someone edited this outside jellystructure" warning.
private suspend fun loadDrift(id: String, scope: CoroutineScope) {
    val result = MediaApi.getDrift(id)
    val banner = document.getElementById("drift-banner") as? HTMLElement ?: return
    if (result == null || result.state == "converged") { banner.style.display = "none"; return }

    val (badgeCls, badgeText, ctaId, ctaLabel) = when (result.state) {
        "nfo_stale" -> listOf("badge", "○ Not saved yet", "drift-cta-btn", "Save → NFO")
        "jellyfin_behind" -> listOf("badge warn", "⏳ Syncing…", "drift-cta-btn", "Sync Jellyfin")
        else -> listOf("badge warn", "⇄ Drift detected", "drift-cta-btn", "Re-assert NFO → Jellyfin")
    }
    val n = result.fields.size
    val detail = when (result.state) {
        "nfo_stale" -> "The stored metadata has changed since the NFO was last written — click Save → NFO (or Save & Sync) to write it out."
        "jellyfin_behind" -> "The NFO on disk is current, but Jellyfin hasn't re-read it yet. This usually clears itself within a few seconds."
        else -> "Someone edited this item in Jellyfin (or another tool touched the NFO). <b>$n field${if (n != 1) "s" else ""}</b> differ from your last write — Jellystructure is the source of truth, so re-assert to restore it."
    }
    val reviewBtn = if (result.state == "external_drift") """<button id="drift-review-btn" class="btn sm ghost">Review differences</button>""" else ""
    banner.innerHTML = """<div style="background:var(--warn-soft,#2d220b);border:1px solid var(--warn,#b8860b);border-radius:6px;padding:10px 14px;display:flex;align-items:flex-start;gap:12px;flex-wrap:wrap;">
      <span class="$badgeCls" style="flex:none;margin-top:1px;">${badgeText.esc()}</span>
      <div style="flex:1;min-width:200px;">
        <b style="font-size:.9rem;">${result.message.esc()}</b>
        <div class="tiny muted" style="margin-top:5px;line-height:1.6;">$detail</div>
        <div style="display:flex;gap:8px;margin-top:10px;flex-wrap:wrap;">
          $reviewBtn
          <button id="$ctaId" class="btn sm">${ctaLabel.esc()}</button>
        </div>
      </div>
      <span id="drift-dismiss-btn" class="x" style="cursor:pointer;color:var(--ink-soft);">✕</span>
    </div>"""
    banner.style.display = "block"

    document.getElementById("drift-dismiss-btn")?.addEventListener("click") { banner.style.display = "none" }
    document.getElementById("drift-review-btn")?.addEventListener("click") { showDriftModal(id, result.fields, scope) }
    document.getElementById(ctaId)?.addEventListener("click") {
        scope.launch {
            when (result.state) {
                "nfo_stale" -> handleWriteNfo(id, refresh = false, scope = scope)
                else -> reassertDrift(id, scope)
            }
        }
    }
}

/** Phase 115 (FR E) — after a sync-triggering action, Jellyfin's refresh is async: poll the drift state
 *  every 3s for up to ~30s so the banner clears itself without a manual page reload. */
private suspend fun pollDriftUntilConverged(id: String, scope: CoroutineScope, attempts: Int = 10) {
    repeat(attempts) {
        delay(3000)
        val result = MediaApi.getDrift(id)
        loadDrift(id, scope)
        if (result == null || result.state == "converged") return
    }
}

/** Re-asserts the NFO Jellystructure holds (source of truth) back onto disk + tells Jellyfin to refresh,
 *  then polls until the banner confirms convergence (Phase 115 FR E). */
private suspend fun reassertDrift(id: String, scope: CoroutineScope) {
    document.getElementById("drift-modal-overlay")?.remove()
    showDetailMsg("Re-asserting NFO → Jellyfin…", true)
    val (result, error) = MediaApi.writeNfo(id)
    if (result == null) {
        showNfoWriteError(error ?: "NFO write failed.")
        return
    }
    MediaApi.jellyfinRefresh(id)
    showDetailMsg("NFO re-asserted — Jellyfin refresh requested ✓", true)
    pollDriftUntilConverged(id, scope)
}

private fun showDriftModal(id: String, drifts: List<DriftField>, scope: CoroutineScope) {
    document.getElementById("drift-modal-overlay")?.remove()
    val rows = drifts.joinToString("") { d ->
        val label = DRIFT_FIELD_NAMES[d.field] ?: d.field
        val jf = d.inJellyfin.ifBlank { "—" }
        val db = d.inDb.ifBlank { "—" }
        """<div style="display:flex;gap:10px;align-items:center;padding:9px 0;border-bottom:1px solid var(--line);">
             <div style="flex:1;min-width:0;">
               <div class="tiny muted">${label.esc()}</div>
               <div class="tiny"><b>NFO:</b> ${db.esc()}</div>
               <div class="tiny"><b>Jellyfin:</b> ${jf.esc()}</div>
             </div>
             <button class="btn sm drift-field-reassert" data-field="${d.field.esc()}">Re-assert</button>
           </div>"""
    }
    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "drift-modal-overlay"
    overlay.setAttribute("style", "position:fixed;inset:0;background:rgba(0,0,0,.55);z-index:8000;display:flex;align-items:center;justify-content:center;padding:16px;")
    overlay.innerHTML = """
        <div style="background:var(--fill);border:1px solid var(--line-2);border-radius:var(--radius);padding:24px;max-width:560px;width:100%;box-shadow:var(--shadow);max-height:82vh;overflow-y:auto;">
          <div class="row center" style="margin-bottom:6px;">
            <h3 style="margin:0;flex:1;">Jellyfin ⇄ NFO differences</h3>
            <button id="drift-modal-close" class="btn sm ghost" style="padding:2px 8px;">✕</button>
          </div>
          <p class="tiny muted" style="margin:0 0 10px;">Jellystructure is the source of truth. <b>Re-assert</b> writes the NFO back to disk and tells Jellyfin to refresh. To take Jellyfin's values instead, use <b>Re-pull from Jellyfin…</b> in the page bar.</p>
          $rows
          <div class="row" style="justify-content:flex-end;gap:8px;margin-top:14px;align-items:center;">
            <span id="drift-modal-status" class="tiny muted" style="flex:1;"></span>
            <button id="drift-modal-close-2" class="btn ghost">Close</button>
            <button id="drift-reassert-all-btn" class="btn primary">Re-assert all → Jellyfin</button>
          </div>
        </div>"""
    document.body?.appendChild(overlay)

    fun close() { overlay.remove() }
    overlay.addEventListener("click") { e -> if ((e.target as? HTMLElement)?.id == "drift-modal-overlay") close() }
    document.getElementById("drift-modal-close")?.addEventListener("click") { close() }
    document.getElementById("drift-modal-close-2")?.addEventListener("click") { close() }
    // Per-field and global re-assert both write the (wholesale) NFO from the source-of-truth DB state.
    document.getElementById("drift-reassert-all-btn")?.addEventListener("click") { scope.launch { reassertDrift(id, scope) } }
    overlay.querySelectorAll(".drift-field-reassert").let { nodes ->
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? HTMLElement)?.addEventListener("click") { scope.launch { reassertDrift(id, scope) } }
        }
    }
}

// ── Artwork manager (Phase 47) ──────────────────────────────────────────────────
// Asset rail + inline TMDB candidate gallery with resolved-first language fallback
// (no-language `xx` distinct from All), Prefer textless/with-text, hi-res/sort,
// stage + Save to disk, dropzone / Upload / Paste-URL. Generalised so the series
// season-poster + episode-still targets (added on the series detail) reuse it.

private const val TMDB_IMG_THUMB = "https://image.tmdb.org/t/p/w342"

private class ArtTarget(
    val asset: String,          // poster | backdrop | clearlogo — also the upload "type"
    val label: String,
    val aspect: String,         // CSS aspect-ratio for cards/slots
    val kind: String = "asset", // asset | season | episode
    val season: Int = -1,
    val epFilename: String = "",
    // Phase 149: disambiguates which episode when several share epFilename (a multi-episode file) —
    // always populated for "episode" targets (harmless/unused for the non-ambiguous single-episode case).
    val epNum: Int? = null,
    var onDisk: Boolean = false,
    var source: String? = null,   // R131: "tmdb" | "screengrab" | "manual" — for stills
)

private var artId = ""
private var artStillBust = 0   // R131: cache-buster so the still preview reloads after a regenerate
private var artItem: MediaItem? = null
private var artScope: CoroutineScope? = null
private var artTargets: List<ArtTarget> = emptyList()
private var artSel = 0
private var artResp: ArtworkCandidatesResponse? = null
private var artLang = ""        // unified language/text filter (Phase 48):
                                // "" = All · "textless" = no text · "withtext" = any language · else a language code
private var artHiRes = false
private var artSort = "vote"    // vote | res
private var lbCandidates: List<ArtworkCandidate> = emptyList()
private var lbIdx = 0

/** Build a multipart upload via the browser FormData + fetch (drag-drop / file pick). */
private fun jsUpload(url: String, type: String, file: JsAny): Unit =
    js("{ const fd = new FormData(); fd.append('type', type); fd.append('file', file); fetch(url, { method:'POST', body: fd, credentials:'same-origin' }); }")

private fun fmt1(d: Double): String = ((d * 10).toInt() / 10.0).toString()

private fun langLabel(code: String?): String = when {
    code == null || code == "xx" -> "No language"
    else -> code.uppercase()
}

/** Readable label for the unified artwork language/text filter (Phase 48). */
private fun artFilterLabel(sel: String): String = when (sel) {
    "" -> "all languages"
    "textless" -> "textless"
    "withtext" -> "with-text (any language)"
    else -> sel.uppercase()
}

private suspend fun loadArtworkTab(item: MediaItem, scope: CoroutineScope) {
    injectArtworkStyles()
    artId = item.id
    artItem = item
    artScope = scope
    // Proactive permission check — surfaces the same banner the NFO tab uses
    val perm = MediaApi.checkNfoWritable(item.id)
    if (perm != null && !perm.writable) {
        showNfoPermBanner(perm.error ?: "write permission check failed", perm.path)
    }
    artTargets = buildArtTargets(item)
    if (artTargets.isEmpty()) return
    artSel = 0
    renderArtRail()
    wireArtRail()
    selectArtTarget(0)
}

/** Item-level assets, plus (for series) per-season posters and per-episode stills. */
private suspend fun buildArtTargets(item: MediaItem): List<ArtTarget> {
    val targets = mutableListOf(
        ArtTarget("poster", "Poster", "2 / 3"),
        ArtTarget("backdrop", "Backdrop", "16 / 9"),
        ArtTarget("clearlogo", "Clearlogo", "16 / 9"),
    )
    val status = MediaApi.getArtworkStatus(item.id)
    if (status != null) for (t in targets) t.onDisk = when (t.asset) {
        "poster" -> status.posterExists
        "backdrop" -> status.fanartExists
        "clearlogo" -> status.logoExists
        else -> t.onDisk
    }
    if (item.kind == MediaKind.TV_SHOW) {
        MediaApi.getSeasons(item.id)?.forEach { s ->
            val label = if (s.season == 0) "Specials" else "Season ${s.season}"
            targets.add(ArtTarget("poster", label, "2 / 3", kind = "season", season = s.season, onDisk = s.posterExists))
        }
        // Bug fix (Phase 149): a multi-episode file's episodes all share `filename`, so keying this
        // lookup by filename alone collapsed a group's N statuses down to whichever one associate()
        // kept last. Key by (filename, episodeNumber) — the backend already returns one distinct status
        // per episode (see EpisodeStillStatusDto's episodeNumber field).
        val stillStatus = MediaApi.getEpisodeStillStatuses(item.id)
            ?.associateBy { it.filename to it.episodeNumber } ?: emptyMap()
        item.episodes.forEach { ep ->
            val s = ep.seasonNumber
            val e = ep.episodeNumber
            val code = if (s != null && e != null) "S${s.toString().padStart(2, '0')}E${e.toString().padStart(2, '0')}" else ep.filename
            // Phase 149: flag which shared file this episode belongs to so the rail's flat list stays
            // legible once several rows come from the same multi-episode file. Plain text (not HTML) —
            // the whole label gets `.esc()`-ed as one string when rendered.
            val groupTag = if (ep.partCount > 1) " (shared file)" else ""
            val label = (if (!ep.title.isNullOrBlank()) "$code · ${ep.title}" else code) + groupTag
            val ss = stillStatus[ep.filename to ep.episodeNumber]
            targets.add(ArtTarget("still", label, "16 / 9", kind = "episode", epFilename = ep.filename, epNum = ep.episodeNumber, onDisk = ss?.stillExists ?: false, source = ss?.source))
        }
    }
    return targets
}

private fun renderArtRail() {
    val rail = document.getElementById("art-rail") as? HTMLElement ?: return
    val item = artItem
    val sb = StringBuilder()
    // Head — batch actions + language policy note (series).
    sb.append("""<div class="art-rail-head"><b>Assets</b></div>""")
    if (item?.kind == MediaKind.TV_SHOW) {
        val mix = if (item.languageMix) """<span class="badge warn">Mixed languages</span>""" else """<span class="badge ok">Uniform language</span>"""
        sb.append("""<div class="art-rail-note tiny muted">$mix<div style="margin-top:4px;">Stills fetched in each episode's own language, then no-language.</div></div>""")
        sb.append("""<button id="art-fetch-missing" class="btn sm ghost" style="width:100%;margin-bottom:8px;">Fetch all missing</button>""")
    }
    var lastKind = ""
    artTargets.forEachIndexed { i, t ->
        if (t.kind != lastKind) {
            val header = when (t.kind) {
                "season" -> "Season posters"
                "episode" -> "Episode stills"
                else -> "Item artwork"
            }
            sb.append("""<div class="art-rail-group">$header</div>""")
            lastKind = t.kind
        }
        val dot = if (t.onDisk) "ok" else "bad"
        val sub = when (t.kind) {
            "season" -> if (t.onDisk) "on disk" else "missing"
            "episode" -> if (t.onDisk) "still on disk" else "no still"
            else -> "${t.aspect.replace(" ", "")} · ${if (t.onDisk) "on disk" else "missing"}"
        }
        sb.append("""<div class="art-rail-row${if (i == artSel) " sel" else ""}" data-i="$i">
              <span class="dot $dot"></span>
              <div><div class="art-rail-label">${t.label.esc()}</div><div class="tiny muted">$sub</div></div>
           </div>""")
    }
    rail.innerHTML = sb.toString()
}

private fun wireArtRail() {
    document.getElementById("art-fetch-missing")?.addEventListener("click") {
        val scope = artScope ?: return@addEventListener
        val btn = document.getElementById("art-fetch-missing") as? HTMLElement
        btn?.setAttribute("disabled", "true"); btn?.textContent = "Fetching…"
        scope.launch {
            val result = MediaApi.fetchArtwork(artId)
            if (result != null) {
                showDetailMsg("Fetched missing artwork + stills.", true)
                artItem?.let { loadArtworkTab(it, scope) }
            } else {
                val perm = MediaApi.checkNfoWritable(artId)
                if (perm != null && !perm.writable) {
                    showNfoPermBanner(perm.error ?: "write permission check failed", perm.path)
                } else {
                    showDetailMsg("Fetch failed.", false)
                }
                btn?.removeAttribute("disabled"); btn?.textContent = "Fetch all missing"
            }
        }
    }
    document.querySelectorAll("#art-rail .art-rail-row").let { rows ->
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            row.addEventListener("click") {
                val idx = row.getAttribute("data-i")?.toIntOrNull() ?: return@addEventListener
                artSel = idx
                renderArtRail(); wireArtRail()
                artScope?.launch { selectArtTarget(idx) }
            }
        }
    }
}

private suspend fun galleryFetch(t: ArtTarget): ArtworkCandidatesResponse? = when (t.kind) {
    "season" -> MediaApi.getSeasonPosterCandidates(artId, t.season)
    "episode" -> MediaApi.getEpisodeStillCandidates(artId, t.epFilename, t.epNum)
    else -> MediaApi.getArtworkCandidates(artId, t.asset)
}

private suspend fun gallerySave(t: ArtTarget, source: String): Boolean = when (t.kind) {
    "season" -> MediaApi.saveSeasonPoster(artId, t.season, source)
    "episode" -> MediaApi.saveEpisodeStill(artId, t.epFilename, source, t.epNum)
    else -> MediaApi.saveArtworkCandidate(artId, t.asset, source) != null
}

private suspend fun selectArtTarget(i: Int) {
    val gallery = document.getElementById("art-gallery") as? HTMLElement ?: return
    val t = artTargets.getOrNull(i) ?: return
    gallery.innerHTML = """<span class="muted tiny">Loading candidates…</span>"""
    artResp = galleryFetch(t)
    artHiRes = false; artSort = "vote"
    // Resolved-first, never-empty fallback: resolved lang → textless → All.
    val cands = artResp?.candidates ?: emptyList()
    val resolved = artResp?.resolvedLanguage
    artLang = when {
        resolved != null && cands.any { it.lang == resolved } -> resolved
        cands.any { it.lang == null } -> "textless"
        else -> ""
    }
    renderArtGallery()
    wireArtGallery()
}

private fun filteredCandidates(): List<ArtworkCandidate> {
    val all = artResp?.candidates ?: emptyList()
    // Phase 48: one single-select filter on iso_639_1 (textless = lang null, withtext = any language).
    val list0 = when (artLang) {
        "" -> all
        "textless" -> all.filter { it.lang == null }
        "withtext" -> all.filter { it.lang != null }
        else -> all.filter { it.lang == artLang }
    }
    val list = if (artHiRes) list0.filter { it.width >= 1000 || it.height >= 1000 } else list0
    val byVote = compareByDescending<ArtworkCandidate> { it.voteAverage }.thenByDescending { it.width }
    val byRes = compareByDescending<ArtworkCandidate> { it.width }.thenByDescending { it.voteAverage }
    return list.sortedWith(if (artSort == "res") byRes else byVote)
}

private fun renderArtGallery() {
    val gallery = document.getElementById("art-gallery") as? HTMLElement ?: return
    val t = artTargets.getOrNull(artSel) ?: return
    val resp = artResp
    if (resp == null) { gallery.innerHTML = """<span class="muted tiny">Failed to load candidates.</span>"""; return }
    val all = resp.candidates
    val resolved = resp.resolvedLanguage

    // Phase 48: one single-select filter on iso_639_1 — All · Textless · With text · <languages>.
    val langCounts = all.groupingBy { it.lang ?: "xx" }.eachCount()
    val noLangCount = langCounts["xx"] ?: 0
    val withTextCount = all.size - noLangCount
    fun chip(code: String, label: String, count: Int?): String {
        val active = artLang == code
        val c = if (count != null) " <span class=\"tiny muted\">$count</span>" else ""
        return """<span class="art-chip${if (active) " on" else ""}" data-lang="$code">${label.esc()}$c</span>"""
    }
    val langChips = StringBuilder()
    langChips.append(chip("", "All", all.size))
    if (noLangCount > 0) langChips.append(chip("textless", "Textless", noLangCount))
    if (withTextCount > 0) langChips.append(chip("withtext", "With text", withTextCount))
    langCounts.keys.filter { it != "xx" }.sorted().forEach { langChips.append(chip(it, it.uppercase(), langCounts[it])) }

    val shown = filteredCandidates()
    val hidden = all.size - shown.size

    // Explainer line — amber when a fallback is in effect (resolved language had none).
    val fellBack = resolved != null && artLang != resolved && all.none { it.lang == resolved } && all.isNotEmpty()
    val explainer = when {
        all.isEmpty() -> "TMDB has no ${t.label.lowercase()} candidates for this title."
        fellBack -> """No ${if (resolved != null) resolved.uppercase() + " " else ""}${t.label.lowercase()} on TMDB. Falling back to ${artFilterLabel(artLang)} (${shown.size}).${if (hidden > 0) " <a class=\"art-showall\">$hidden other candidate(s) hidden — show all →</a>" else ""}"""
        else -> """Showing ${artFilterLabel(artLang)} (${shown.size}).${if (hidden > 0) " <a class=\"art-showall\">$hidden hidden — show all →</a>" else ""}"""
    }

    val cards = if (shown.isEmpty()) {
        """<div class="muted tiny" style="padding:18px 0;">No candidates for this filter.</div>"""
    } else {
        lbCandidates = shown
        shown.mapIndexed { idx, c ->
            val onDisk = c.onDisk
            val cls = "art-card" + (if (onDisk) " ondisk" else "")
            """<div class="$cls" data-path="${c.filePath.esc()}" data-lbidx="$idx" style="aspect-ratio:${t.aspect};">
                  <img src="$TMDB_IMG_THUMB${c.filePath}" loading="lazy" alt="">
                  ${if (onDisk) """<span class="art-ribbon">ON DISK</span>""" else ""}
                  <button class="art-zoom" data-lbidx="$idx" title="Zoom">⤢</button>
                  <div class="art-card-meta">
                    <span class="art-pill">${langLabel(c.lang)}</span>
                    <span class="art-pill">★ ${fmt1(c.voteAverage)}</span>
                    <span class="art-pill">${c.width}×${c.height}</span>
                  </div>
               </div>"""
        }.joinToString("")
    }
    val footer = ""

    val canUpload = t.kind == "asset" || t.kind == "episode"
    // R131: current on-disk still preview + provenance badge (episodes only).
    val currentPreview = if (t.kind == "episode" && t.onDisk) {
        val (badgeCls, badgeTxt) = when (t.source) {
            "screengrab" -> "warn" to "Screen grab · placeholder (a TMDB still will replace it automatically)"
            "manual"     -> "ok" to "Manual"
            else          -> "ok" to "From TMDB"
        }
        """<div class="row center" style="margin-bottom:10px;gap:10px;">
             <img src="/api/media/$artId/episodes/${encodeURIComponent(t.epFilename)}/still/file?b=$artStillBust${if (t.epNum != null) "&ep=${t.epNum}" else ""}" style="height:64px;aspect-ratio:16/9;object-fit:cover;border-radius:6px;border:1px solid var(--line)" alt="current still">
             <div><div class="tiny" style="font-weight:600;margin-bottom:2px;">Current still on disk</div><span class="badge $badgeCls" style="font-size:.62rem;">${badgeTxt.esc()}</span></div>
           </div>"""
    } else ""
    gallery.innerHTML = """
      <div class="row center" style="margin-bottom:8px;">
        <h4 style="margin:0;">${t.label.esc()} <span class="tiny muted">· ${all.size} TMDB candidate(s)</span></h4>
        <span class="spacer"></span>
        ${if (t.kind == "episode") """<button id="art-screengrab-btn" class="btn sm ghost" title="Grab a frame from the video file as a placeholder still">&#9635; Generate frame</button>""" else ""}
        ${if (canUpload) """<button id="art-upload-btn" class="btn sm ghost">Upload</button>""" else ""}
        <button id="art-url-btn" class="btn sm ghost">Paste URL</button>
      </div>
      $currentPreview
      <div class="art-explain ${if (fellBack) "warn" else ""}">$explainer</div>
      <div class="art-filterbar">
        <div class="art-chips">$langChips</div>
        <span class="spacer"></span>
        <label class="art-hires"><input type="checkbox" id="art-hires" ${if (artHiRes) "checked" else ""}> Hi-res</label>
        <span class="seg art-sort">
          <span class="${if (artSort == "vote") "on" else ""}" data-sort="vote">Vote ★</span>
          <span class="${if (artSort == "res") "on" else ""}" data-sort="res">Resolution</span>
        </span>
      </div>
      <div class="art-dropzone" id="art-dropzone">Drag an image here, or use Upload / Paste URL</div>
      <div class="art-grid">$cards</div>
      $footer
    """.trimIndent()
}

private fun wireArtGallery() {
    val scope = artScope ?: return
    val t = artTargets.getOrNull(artSel) ?: return

    document.querySelectorAll("#art-gallery .art-chip[data-lang]").let { els ->
        for (i in 0 until els.length) {
            val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { artLang = el.getAttribute("data-lang") ?: ""; renderArtGallery(); wireArtGallery() }
        }
    }
    document.querySelectorAll("#art-gallery .art-sort span").let { els ->
        for (i in 0 until els.length) {
            val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { artSort = el.getAttribute("data-sort") ?: "vote"; renderArtGallery(); wireArtGallery() }
        }
    }
    (document.getElementById("art-hires") as? HTMLInputElement)?.addEventListener("change") {
        artHiRes = (document.getElementById("art-hires") as? HTMLInputElement)?.checked ?: false
        renderArtGallery(); wireArtGallery()
    }
    (document.querySelector("#art-gallery .art-showall") as? HTMLElement)?.addEventListener("click") {
        artLang = ""; renderArtGallery(); wireArtGallery()
    }
    // Card click → write-through save immediately
    document.querySelectorAll("#art-gallery .art-card").let { els ->
        for (i in 0 until els.length) {
            val el = els.item(i) as? HTMLElement ?: continue
            el.addEventListener("click") { ev ->
                // Don't trigger if the zoom button was clicked
                if ((ev.target as? HTMLElement)?.classList?.contains("art-zoom") == true) return@addEventListener
                val path = el.getAttribute("data-path") ?: return@addEventListener
                scope.launch {
                    el.style.opacity = "0.6"
                    showDetailMsg("Saving ${t.label}…", true)
                    val ok = gallerySave(t, path)
                    el.style.opacity = ""
                    if (ok) {
                        showDetailMsg("${t.label} saved to disk.", true)
                        t.onDisk = true; renderArtRail(); wireArtRail(); selectArtTarget(artSel)
                    } else {
                        val perm = MediaApi.checkNfoWritable(artId)
                        if (perm != null && !perm.writable) {
                            showNfoPermBanner(perm.error ?: "write permission check failed", perm.path)
                        } else {
                            showDetailMsg("Save failed.", false)
                        }
                    }
                }
            }
        }
    }
    // Zoom button → open lightbox
    document.querySelectorAll("#art-gallery .art-zoom").let { els ->
        for (i in 0 until els.length) {
            val btn = els.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") { ev ->
                ev.stopPropagation()
                val idx = btn.getAttribute("data-lbidx")?.toIntOrNull() ?: 0
                openArtLightbox(idx, t, scope, artId)
            }
        }
    }
    document.getElementById("art-url-btn")?.addEventListener("click") {
        val url = kotlinx.browser.window.prompt("Image URL (https://…)")?.trim()
        if (!url.isNullOrBlank()) {
            scope.launch {
                showDetailMsg("Saving ${t.label}…", true)
                val ok = gallerySave(t, url)
                if (ok) {
                    showDetailMsg("${t.label} saved to disk.", true)
                    t.onDisk = true; renderArtRail(); wireArtRail(); selectArtTarget(artSel)
                } else {
                    showDetailMsg("Save failed.", false)
                }
            }
        }
    }
    document.getElementById("art-screengrab-btn")?.addEventListener("click") {
        scope.launch {
            val btn = document.getElementById("art-screengrab-btn") as? HTMLElement
            btn?.setAttribute("disabled", "")
            showDetailMsg("Grabbing a frame…", true)
            val st = MediaApi.screengrabStill(artId, t.epFilename, t.epNum)
            btn?.removeAttribute("disabled")
            if (st != null && st.stillExists) {
                t.onDisk = true; t.source = st.source; artStillBust++
                showDetailMsg("Frame grabbed.", true)
                renderArtRail(); wireArtRail(); renderArtGallery(); wireArtGallery()
            } else {
                showDetailMsg("Could not grab a frame from the video.", false)
            }
        }
    }
    // Upload (asset / episode kinds): drive the hidden multipart form for asset, FormData for episode.
    val uploadUrl = if (t.kind == "episode")
        "/api/media/$artId/episodes/${encodeURIComponent(t.epFilename)}/still/upload${if (t.epNum != null) "?ep=${t.epNum}" else ""}"
    else "/api/media/$artId/artwork/upload"
    document.getElementById("art-upload-btn")?.addEventListener("click") {
        (document.getElementById("art-upload-file") as? HTMLInputElement)?.let { input ->
            (document.getElementById("art-upload-type") as? HTMLInputElement)?.value = t.asset
            input.click()
        }
    }
    (document.getElementById("art-upload-file") as? HTMLInputElement)?.addEventListener("change") {
        val input = document.getElementById("art-upload-file") as? HTMLInputElement ?: return@addEventListener
        val file = input.files?.item(0) ?: return@addEventListener
        jsUpload(uploadUrl, t.asset, file)
        scope.launch { delay(2200); showDetailMsg("Upload submitted.", true); t.onDisk = true; renderArtRail(); wireArtRail(); selectArtTarget(artSel) }
    }
    val dz = document.getElementById("art-dropzone") as? HTMLElement
    dz?.addEventListener("click") { (document.getElementById("art-upload-btn") as? HTMLElement)?.click() }
    dz?.addEventListener("dragover") { e -> e.preventDefault(); dz.classList.add("over") }
    dz?.addEventListener("dragleave") { dz.classList.remove("over") }
    dz?.addEventListener("drop") { e ->
        e.preventDefault(); dz.classList.remove("over")
        val file = (e as? org.w3c.dom.DragEvent)?.dataTransfer?.files?.item(0) ?: return@addEventListener
        jsUpload(uploadUrl, t.asset, file)
        scope.launch { delay(2200); showDetailMsg("Upload submitted.", true); t.onDisk = true; renderArtRail(); wireArtRail(); selectArtTarget(artSel) }
    }
}

private fun openArtLightbox(startIdx: Int, t: ArtTarget, scope: CoroutineScope, artId: String) {
    lbIdx = startIdx.coerceIn(0, (lbCandidates.size - 1).coerceAtLeast(0))

    val existing = document.getElementById("art-lightbox")
    existing?.remove()

    val overlay = document.createElement("div") as HTMLElement
    overlay.id = "art-lightbox"
    overlay.innerHTML = buildLightboxHtml(t)
    document.body?.appendChild(overlay)

    fun update() {
        val c = lbCandidates.getOrNull(lbIdx) ?: return
        val imgEl = document.getElementById("lb-img") as? HTMLElement
        val infoEl = document.getElementById("lb-info") as? HTMLElement
        val countEl = document.getElementById("lb-count") as? HTMLElement
        val imgSrc = "$TMDB_IMG_LG${c.filePath}"
        imgEl?.setAttribute("src", imgSrc)
        infoEl?.innerHTML = """
            <span class="art-pill">${langLabel(c.lang)}</span>
            <span class="art-pill">★ ${fmt1(c.voteAverage)}</span>
            <span class="art-pill">${c.width}×${c.height}</span>
            ${if (c.onDisk) """<span class="art-pill" style="background:var(--ok,#22c55e);color:#04210f;">ON DISK</span>""" else ""}
        """.trimIndent()
        countEl?.textContent = "${lbIdx + 1} / ${lbCandidates.size}"
        document.getElementById("lb-use")?.removeAttribute("disabled")
    }
    update()

    overlay.addEventListener("click") { ev ->
        val target = ev.target as? HTMLElement ?: return@addEventListener
        when {
            target.id == "art-lightbox" || target.closest("#lb-close") != null -> overlay.remove()
            target.closest("#lb-prev") != null -> {
                lbIdx = if (lbIdx > 0) lbIdx - 1 else lbCandidates.lastIndex; update()
            }
            target.closest("#lb-next") != null -> {
                lbIdx = if (lbIdx < lbCandidates.lastIndex) lbIdx + 1 else 0; update()
            }
            target.closest("#lb-use") != null -> {
                val c = lbCandidates.getOrNull(lbIdx) ?: return@addEventListener
                val btn = document.getElementById("lb-use") as? HTMLElement
                btn?.setAttribute("disabled", "true"); btn?.textContent = "Saving…"
                scope.launch {
                    val ok = gallerySave(t, c.filePath)
                    if (ok) {
                        overlay.remove()
                        showDetailMsg("${t.label} saved to disk.", true)
                        t.onDisk = true; renderArtRail(); wireArtRail(); selectArtTarget(artSel)
                    } else {
                        val perm = MediaApi.checkNfoWritable(artId)
                        if (perm != null && !perm.writable) {
                            showNfoPermBanner(perm.error ?: "write permission check failed", perm.path)
                        } else {
                            showDetailMsg("Save failed.", false)
                        }
                        btn?.removeAttribute("disabled"); btn?.textContent = "Use this artwork"
                    }
                }
            }
        }
    }

    overlay.setAttribute("tabindex", "-1")
    overlay.focus()
    overlay.addEventListener("keydown") { ev ->
        val key = (ev as? KeyboardEvent)?.key ?: return@addEventListener
        when (key) {
            "Escape" -> overlay.remove()
            "ArrowLeft" -> { lbIdx = if (lbIdx > 0) lbIdx - 1 else lbCandidates.lastIndex; update() }
            "ArrowRight" -> { lbIdx = if (lbIdx < lbCandidates.lastIndex) lbIdx + 1 else 0; update() }
        }
    }
}

private fun buildLightboxHtml(t: ArtTarget): String = """
    <div id="lb-panel">
      <div style="display:flex;align-items:center;gap:10px;margin-bottom:14px;">
        <span id="lb-count" class="tiny muted"></span>
        <span class="spacer"></span>
        <button id="lb-close" class="btn sm ghost">✕ Close</button>
      </div>
      <div style="position:relative;display:flex;align-items:center;justify-content:center;gap:12px;">
        <button id="lb-prev" class="btn sm ghost" style="flex:none;">‹</button>
        <img id="lb-img" src="" alt="" style="max-width:70vw;max-height:72vh;border-radius:10px;object-fit:contain;display:block;">
        <button id="lb-next" class="btn sm ghost" style="flex:none;">›</button>
      </div>
      <div id="lb-info" style="display:flex;gap:6px;flex-wrap:wrap;margin-top:12px;"></div>
      <div style="margin-top:14px;display:flex;gap:10px;justify-content:flex-end;">
        <button id="lb-use" class="btn">Use this artwork</button>
      </div>
    </div>
""".trimIndent()

private fun injectArtworkStyles() {
    if (document.getElementById("art-mgr-styles") != null) return
    val style = document.createElement("style") as? org.w3c.dom.HTMLStyleElement ?: return
    style.id = "art-mgr-styles"
    style.textContent = """
        .art-mgr { display:grid; grid-template-columns: 240px 1fr; gap:14px; align-items:start; }
        @media (max-width:760px){ .art-mgr{ grid-template-columns:1fr; } }
        .art-rail-head { font-size:.85rem; margin-bottom:8px; opacity:.8; }
        .art-rail-note { margin-bottom:8px; }
        .art-rail-group { font-size:.7rem; text-transform:uppercase; letter-spacing:.05em; opacity:.55; margin:10px 0 4px; }
        .art-rail { max-height:560px; overflow:auto; }
        .art-rail-row { display:flex; gap:9px; align-items:center; padding:8px; border-radius:9px; cursor:pointer; }
        .art-rail-row:hover { background:color-mix(in srgb, var(--hi,#7c5cff) 9%, transparent); }
        .art-rail-row.sel { background:color-mix(in srgb, var(--hi,#7c5cff) 16%, transparent); }
        .art-rail-label { font-weight:600; font-size:.9rem; }
        .art-rail-row .dot { width:9px; height:9px; border-radius:50%; flex:none; }
        .art-rail-row .dot.ok { background:var(--ok,#22c55e); } .art-rail-row .dot.bad { background:var(--bad,#ef4444); }
        .art-explain { font-size:.8rem; opacity:.85; margin-bottom:10px; }
        .art-explain.warn { color:var(--warn,#f59e0b); opacity:1; }
        .art-explain .art-showall { cursor:pointer; text-decoration:underline; }
        .art-filterbar { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-bottom:10px; }
        .art-chips { display:flex; flex-wrap:wrap; gap:6px; }
        .art-chip { padding:3px 9px; border-radius:20px; border:1px solid var(--line,#333); cursor:pointer; font-size:.78rem; }
        .art-chip.on { background:var(--hi,#7c5cff); border-color:transparent; color:#fff; }
        .art-hires { font-size:.78rem; display:flex; gap:4px; align-items:center; }
        .art-sort { font-size:.76rem; }
        .art-dropzone { border:1.5px dashed var(--line,#444); border-radius:11px; padding:12px; text-align:center; font-size:.78rem; opacity:.7; margin-bottom:12px; cursor:pointer; }
        .art-dropzone.over { border-color:var(--hi,#7c5cff); opacity:1; }
        .art-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(120px,1fr)); gap:11px; }
        .art-card { position:relative; border-radius:10px; overflow:hidden; cursor:pointer; border:2px solid transparent; background:#0006; transition:opacity .15s; }
        .art-card img { width:100%; height:100%; object-fit:cover; display:block; }
        .art-card.ondisk { border-color:var(--ok,#22c55e); }
        .art-card:hover .art-zoom { opacity:1; }
        .art-zoom { position:absolute; top:5px; right:5px; background:#000b; color:#fff; border:none; border-radius:5px; padding:2px 5px; font-size:.72rem; cursor:pointer; opacity:0; transition:opacity .15s; line-height:1.3; }
        .art-ribbon { position:absolute; top:6px; left:6px; background:var(--ok,#22c55e); color:#04210f; font-size:.62rem; font-weight:700; padding:1px 6px; border-radius:5px; }
        .art-card-meta { position:absolute; bottom:0; left:0; right:0; display:flex; gap:4px; flex-wrap:wrap; padding:5px; background:linear-gradient(transparent, #000b); }
        .art-pill { font-size:.6rem; background:#000a; padding:1px 5px; border-radius:5px; }
        #art-lightbox { position:fixed; inset:0; background:#000c; display:flex; align-items:center; justify-content:center; z-index:9999; outline:none; }
        #lb-panel { background:var(--fill,#1a1a2e); border-radius:14px; padding:24px; max-width:90vw; max-height:92vh; overflow:auto; }
    """.trimIndent()
    document.head?.appendChild(style)
}

// ── NFO raw viewer (Phase 44) ───────────────────────────────────────────────────

private const val NFO_EMPTY_FILE = "No NFO on disk yet — written when you Save on the metadata tab."

/** Fetches the item's NFO file tree, renders the sidebar, and selects the first existing file. */
private suspend fun loadNfoTab(item: MediaItem, scope: CoroutineScope) {
    val treeEl = document.getElementById("nfo-tree") as? HTMLElement ?: return
    val pathEl = document.getElementById("nfo-path") as? HTMLElement
    val rawEl = document.getElementById("nfo-raw") as? HTMLElement
    treeEl.innerHTML = """<span class="muted tiny">Loading…</span>"""
    val tree = MediaApi.getNfoFiles(item.id)
    if (tree == null) {
        treeEl.innerHTML = """<span class="muted tiny">Failed to load NFO files.</span>"""
        return
    }
    renderNfoTree(treeEl, tree, scope)
    val firstEl = (treeEl.querySelector(".nfo-node:not(.missing)") ?: treeEl.querySelector(".nfo-node")) as? HTMLElement
    if (firstEl != null) {
        selectNfoElement(treeEl, firstEl, scope)
    } else {
        pathEl?.textContent = ""
        rawEl?.textContent = NFO_EMPTY_FILE
    }
}

private fun renderNfoTree(treeEl: HTMLElement, tree: NfoFileTree, scope: CoroutineScope) {
    fun nodeHtml(n: NfoFileNode): String {
        val missing = if (!n.exists) """ <span class="muted tiny">· not written</span>""" else ""
        val cls = "nfo-node" + if (!n.exists) " missing" else ""
        return """<div class="$cls" data-url="${n.readUrl.esc()}" data-path="${n.path.esc()}" data-exists="${n.exists}">${n.label.esc()}$missing</div>"""
    }

    treeEl.innerHTML = if (tree.kind == MediaKind.TV_SHOW) {
        val sb = StringBuilder()
        tree.files.firstOrNull()?.let { sb.append(nodeHtml(it)) }  // tvshow.nfo pinned at top
        val seasons = LinkedHashMap<Int?, MutableList<NfoFileNode>>()
        tree.files.drop(1).forEach { seasons.getOrPut(it.season) { mutableListOf() }.add(it) }
        seasons.forEach { (season, eps) ->
            val title = season?.let { "Season $it" } ?: "Other"
            sb.append("""<div class="nfo-season"><div class="nfo-season-hd"><span class="nfo-caret">▾</span> ${title.esc()} <span class="muted tiny">(${eps.size})</span></div><div class="nfo-season-body">""")
            eps.forEach { sb.append(nodeHtml(it)) }
            sb.append("""</div></div>""")
        }
        sb.toString()
    } else {
        tree.files.joinToString("") { nodeHtml(it) }
    }

    // Collapsible season groups
    treeEl.querySelectorAll(".nfo-season-hd").let { nodes ->
        for (i in 0 until nodes.length) {
            val hd = nodes.item(i) as? HTMLElement ?: continue
            hd.addEventListener("click") {
                val body = hd.nextElementSibling as? HTMLElement ?: return@addEventListener
                val collapsed = body.style.display == "none"
                body.style.display = if (collapsed) "block" else "none"
                (hd.querySelector(".nfo-caret") as? HTMLElement)?.textContent = if (collapsed) "▾" else "▸"
            }
        }
    }

    // Node selection
    treeEl.addEventListener("click") { e ->
        val nodeEl = (e.target as? HTMLElement)?.closest(".nfo-node") as? HTMLElement ?: return@addEventListener
        selectNfoElement(treeEl, nodeEl, scope)
    }
}

private fun selectNfoElement(treeEl: HTMLElement, nodeEl: HTMLElement, scope: CoroutineScope) {
    treeEl.querySelectorAll(".nfo-node").let { nodes ->
        for (i in 0 until nodes.length) (nodes.item(i) as? HTMLElement)?.classList?.remove("sel")
    }
    nodeEl.classList.add("sel")

    val url = nodeEl.getAttribute("data-url") ?: return
    val exists = nodeEl.getAttribute("data-exists") == "true"
    val path = nodeEl.getAttribute("data-path") ?: ""
    val pathEl = document.getElementById("nfo-path") as? HTMLElement
    val rawEl = document.getElementById("nfo-raw") as? HTMLElement
    pathEl?.textContent = path
    if (!exists) {
        rawEl?.textContent = NFO_EMPTY_FILE
        return
    }
    rawEl?.textContent = "Loading…"
    scope.launch {
        // textContent (never innerHTML): the XML displays literally and is never parsed as markup.
        rawEl?.textContent = MediaApi.getNfoRaw(url) ?: "Could not read this file."
    }
}

/** ISO-639-1 language code → ISO-3166-1-alpha-2 country code for flag-icons assets (Phase 87). */
// ISO 639-1 (2-letter) AND ISO 639-2/B + /T (3-letter) → ISO 3166-1-alpha-2 country code.
// ffprobe and Jellyfin MediaStreams use 3-letter codes; include both so lookup never silently fails.
private val LANG_CC = mapOf(
    "en" to "gb", "eng" to "gb",
    "fr" to "fr", "fra" to "fr", "fre" to "fr",
    "de" to "de", "deu" to "de", "ger" to "de",
    "es" to "es", "spa" to "es",
    "da" to "dk", "dan" to "dk",
    "fo" to "fo", "fao" to "fo",
    "is" to "is", "isl" to "is", "ice" to "is",
    "no" to "no", "nor" to "no", "nob" to "no", "nno" to "no",
    "sv" to "se", "swe" to "se",
    "fi" to "fi", "fin" to "fi",
    "nl" to "nl", "nld" to "nl", "dut" to "nl",
    "it" to "it", "ita" to "it",
    "pt" to "pt", "por" to "pt",
    "pl" to "pl", "pol" to "pl",
    "ru" to "ru", "rus" to "ru",
    "ja" to "jp", "jpn" to "jp",
    "ko" to "kr", "kor" to "kr",
    "zh" to "cn", "zho" to "cn", "chi" to "cn",
    "ar" to "sa", "ara" to "sa",
    "hi" to "in", "hin" to "in",
    "cs" to "cz", "ces" to "cz", "cze" to "cz",
    "tr" to "tr", "tur" to "tr",
    "uk" to "ua", "ukr" to "ua",
    "el" to "gr", "ell" to "gr", "gre" to "gr",
    "hu" to "hu", "hun" to "hu",
    "ro" to "ro", "ron" to "ro", "rum" to "ro",
    "sk" to "sk", "slk" to "sk", "slo" to "sk",
    "hr" to "hr", "hrv" to "hr",
    "he" to "il", "heb" to "il",
    "th" to "th", "tha" to "th",
    "vi" to "vn", "vie" to "vn",
)
private const val AUDIO_FLAG_MAX = 5

/**
 * Build the `.audio-flags` pagebar strip HTML from the item's ordered audio tracks.
 * Physical track order, untagged tracks skipped, max 5 flags then a +N pill.
 * Hidden entirely when no audio track has a language that maps to a flag.
 */
private fun audioFlagsHtml(tracks: List<Track>): String {
    val audioTracks = tracks.filter { it.kind == TrackKind.AUDIO }
    // Phase 128: zero audio tracks at all (usually a corrupt/truncated file) is distinct from having
    // audio tracks with no recognized language — show an explicit "?" rather than silently showing
    // nothing, so the pagebar never reads as "clean" for a file that isn't.
    if (audioTracks.isEmpty()) {
        return """<span class="af-label">Audio</span><span class="af-row"><span class="badge bad" style="font-size:.68rem;" title="No audio tracks detected">?</span></span>"""
    }
    val langs = audioTracks
        .mapNotNull { t -> t.language?.lowercase()?.let { l -> LANG_CC[l]?.let { cc -> l to cc } } }
        .distinctBy { (_, cc) -> cc }  // one flag per country code; drop duplicate audio tracks
    if (langs.isEmpty()) return ""
    val shown = langs.take(AUDIO_FLAG_MAX)
    val extra = langs.size - shown.size
    val flags = shown.joinToString("") { (l, cc) ->
        """<span class="fi fi-$cc" title="${langName(l)}" aria-label="${langName(l)}"></span>"""
    }
    val more = if (extra > 0) """<span class="af-more" title="$extra more">+$extra</span>""" else ""
    return """<span class="af-label">Audio</span><span class="af-row">$flags$more</span>"""
}

/** Human-readable name for an ISO-639-1 or ISO-639-2 code used in the audio-flag tooltips. */
private fun langName(code: String): String = when (code) {
    "en", "eng" -> "English"
    "fr", "fra", "fre" -> "French"
    "de", "deu", "ger" -> "German"
    "es", "spa" -> "Spanish"
    "da", "dan" -> "Danish"
    "fo", "fao" -> "Faroese"
    "is", "isl", "ice" -> "Icelandic"
    "no", "nor", "nob", "nno" -> "Norwegian"
    "sv", "swe" -> "Swedish"
    "fi", "fin" -> "Finnish"
    "nl", "nld", "dut" -> "Dutch"
    "it", "ita" -> "Italian"
    "pt", "por" -> "Portuguese"
    "pl", "pol" -> "Polish"
    "ru", "rus" -> "Russian"
    "ja", "jpn" -> "Japanese"
    "ko", "kor" -> "Korean"
    "zh", "zho", "chi" -> "Chinese"
    "ar", "ara" -> "Arabic"
    "hi", "hin" -> "Hindi"
    "cs", "ces", "cze" -> "Czech"
    "tr", "tur" -> "Turkish"
    "uk", "ukr" -> "Ukrainian"
    "el", "ell", "gre" -> "Greek"
    "hu", "hun" -> "Hungarian"
    "ro", "ron", "rum" -> "Romanian"
    "sk", "slk", "slo" -> "Slovak"
    "hr", "hrv" -> "Croatian"
    "he", "heb" -> "Hebrew"
    "th", "tha" -> "Thai"
    "vi", "vie" -> "Vietnamese"
    else -> code
}

/** Toggle the detail topbar dropdown/split menus (design media.html): a `.menu-btn` opens its menu;
 *  clicking a `.menu-item`, outside, or Esc closes. Buttons are re-wired each render; the document-level
 *  outside-click + Esc handlers are installed once. */
private var pagebarMenuGlobalWired = false
private fun wirePagebarMenus() {
    val wraps = document.querySelectorAll(".menu-wrap, .split")
    for (i in 0 until wraps.length) {
        val w = wraps.item(i) as? HTMLElement ?: continue
        (w.querySelector(".menu-btn") as? HTMLElement)?.addEventListener("click") { ev ->
            ev.stopPropagation()
            val wasOpen = w.classList.contains("open")
            closeAllPagebarMenus()
            if (!wasOpen) w.classList.add("open")
        }
        val items = w.querySelectorAll(".menu-item")
        for (j in 0 until items.length) (items.item(j) as? HTMLElement)?.addEventListener("click") { closeAllPagebarMenus() }
    }
    if (!pagebarMenuGlobalWired) {
        pagebarMenuGlobalWired = true
        document.addEventListener("click") { ev ->
            if ((ev.target as? Element)?.closest(".menu-wrap, .split") == null) closeAllPagebarMenus()
        }
        document.addEventListener("keydown") { ev ->
            if ((ev as? KeyboardEvent)?.key == "Escape") closeAllPagebarMenus()
        }
    }
}

private fun closeAllPagebarMenus() {
    val open = document.querySelectorAll(".menu-wrap.open, .split.open")
    for (i in 0 until open.length) (open.item(i) as? HTMLElement)?.classList?.remove("open")
}

private suspend fun handleWriteNfo(id: String, refresh: Boolean = false, scope: CoroutineScope? = null) {
    // The Save split's primary face shows progress; "Save → disk" is now a menu item (a div), so we
    // only drive the primary button's state here.
    val face = document.getElementById("write-nfo-refresh-btn") as? HTMLElement
    face?.setAttribute("disabled", "true"); face?.textContent = "Writing…"

    val (result, error) = MediaApi.writeNfo(id)

    if (result != null) {
        // The NFO raw tab re-fetches the on-disk files when activated (Phase 44), so no need to
        // push the freshly-written XML into the viewer here.
        if (refresh) {
            face?.textContent = "Syncing artwork…"
            MediaApi.fetchArtwork(id)
            face?.textContent = "Notifying Jellyfin…"
            MediaApi.jellyfinRefresh(id)
            showDetailMsg("NFO written, artwork synced, Jellyfin notified ✓", true)
            // Phase 115 (FR E) — Jellyfin's refresh is async; poll the drift state so the banner clears
            // itself within seconds instead of needing a manual page reload.
            if (scope != null) pollDriftUntilConverged(id, scope)
        } else {
            showDetailMsg("NFO written to ${result.path}", true)
            if (scope != null) loadDrift(id, scope)
        }
    } else {
        showNfoWriteError(error ?: "NFO write failed.")
    }

    face?.removeAttribute("disabled"); face?.textContent = "Save & sync to Jellyfin ↻"
}

private fun permCopyBlock(command: String, comment: String? = null): String {
    val attrSafe = command.replace("&", "&amp;").replace("\"", "&quot;")
    val display = if (comment != null) "${command.esc()}<span style='color:var(--ink-soft);'> # ${comment.esc()}</span>" else command.esc()
    return """<div style="display:flex;align-items:stretch;background:var(--fill-3);border:1px solid var(--line);border-radius:4px;overflow:hidden;margin:4px 0 2px;">
      <code style="flex:1;padding:7px 10px;$MONO_CODE_STYLE;white-space:pre-wrap;word-break:break-all;">$display</code>
      <button data-copy="$attrSafe" onclick="(function(t){try{navigator.clipboard.writeText(t)}catch(e){var a=document.createElement('textarea');a.value=t;a.style.position='fixed';a.style.opacity='0';document.body.appendChild(a);a.focus();a.select();try{document.execCommand('copy')}catch(_){}document.body.removeChild(a)}})(this.dataset.copy);var b=this;b.textContent='Copied!';setTimeout(function(){b.textContent='Copy'},1500);" style="padding:0 12px;background:var(--fill-2);border:none;border-left:1px solid var(--line);cursor:pointer;color:var(--ink-soft);font-size:.75rem;white-space:nowrap;flex-shrink:0;">Copy</button>
    </div>""".trimIndent()
}

private fun buildNfoPermFixHtml(path: String): String {
    val p = path.ifBlank { "/path/to/media" }
    val tabActive = "padding:6px 14px;border:none;cursor:pointer;background:none;border-bottom:2px solid var(--hi);font-weight:600;color:var(--ink);font-size:.82rem;"
    val tabInactive = "padding:6px 14px;border:none;cursor:pointer;background:none;border-bottom:2px solid transparent;color:var(--ink-soft);font-size:.82rem;"
    val switchDocker = "document.getElementById('perm-tab-docker').style.display='';document.getElementById('perm-tab-native').style.display='none';document.getElementById('perm-tab-docker-btn').setAttribute('style','$tabActive');document.getElementById('perm-tab-native-btn').setAttribute('style','$tabInactive');"
    val switchNative = "document.getElementById('perm-tab-native').style.display='';document.getElementById('perm-tab-docker').style.display='none';document.getElementById('perm-tab-native-btn').setAttribute('style','$tabActive');document.getElementById('perm-tab-docker-btn').setAttribute('style','$tabInactive');"

    val dockerTab = """
        <div id="perm-tab-docker">
          <p class="tiny muted" style="margin:8px 0 10px;">Run these on your <strong>host machine</strong> (not inside the container).</p>
          <div style="margin-bottom:10px;">
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Step 1</strong> — find the UID:GID that owns the media directory:</div>
            ${permCopyBlock("stat '$p'")}
            <div class="tiny muted" style="margin-top:3px;">Look for <code style="$MONO_CODE_STYLE">Uid:</code> and <code style="$MONO_CODE_STYLE">Gid:</code> in the output.</div>
          </div>
          <div style="margin-bottom:10px;">
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Step 2a (preferred)</strong> — add a <code style="$MONO_CODE_STYLE">user:</code> line to the jellystructure service in docker-compose.yml, then restart:</div>
            ${permCopyBlock("    user: \"1000:1000\"", "replace with UID:GID from Step 1")}
            ${permCopyBlock("docker compose up -d jellystructure")}
          </div>
          <div>
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Step 2b (alternative)</strong> — change ownership of the media directory on the host:</div>
            ${permCopyBlock("sudo chown -R 1000:1000 '$p'", "replace 1000:1000 with UID:GID from Step 1")}
          </div>
        </div>
    """.trimIndent()

    val nativeTab = """
        <div id="perm-tab-native" style="display:none;">
          <p class="tiny muted" style="margin:8px 0 10px;">The user running the Jellystructure binary needs write access to the media directory.</p>
          <div style="margin-bottom:10px;">
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Option 1</strong> — change ownership to the current user:</div>
            ${permCopyBlock("sudo chown -R \$(id -u):\$(id -g) '$p'")}
          </div>
          <div style="margin-bottom:10px;">
            <div class="tiny muted" style="margin-bottom:4px;"><strong>Option 2</strong> — add write permission for the directory's group:</div>
            ${permCopyBlock("sudo chmod -R g+w '$p'")}
          </div>
          <div>
            <div class="tiny muted" style="margin-bottom:4px;">Check current ownership:</div>
            ${permCopyBlock("stat '$p'")}
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


internal fun showDetailMsg(msg: String, ok: Boolean) {
    val el = document.getElementById("detail-msg") as? HTMLElement ?: return
    el.style.display = "block"
    el.innerHTML = """<span class="badge ${if (ok) "ok" else "bad"}">$msg</span>"""
}

private fun formatTimestamp(epochMs: Double): String = js("new Date(epochMs).toLocaleString()")


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

// ── Diff popups ───────────────────────────────────────────────────────────────

private fun showTagsDiffPopup(origTags: List<String>, currentTags: List<String>, label: String = "Tags") {
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
          <strong style="font-size:.95rem;">$label — changes</strong>
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

// --- Phase 75: Cast & crew tab ---

private fun renderMovieCastTabHtml(item: MediaItem): String = buildString {
    append("""<div class="card" style="margin-bottom:16px;"><div class="row center" style="margin-bottom:14px;"><h4 style="margin:0;">Cast &amp; crew</h4><span class="spacer"></span><button class="btn sm ghost" id="cast-fetch-btn">↻ Fetch from TMDB</button></div>""")
    append("""<h5 style="margin:0 0 10px;color:var(--fg-2);font-size:.8rem;letter-spacing:.06em;text-transform:uppercase;">Cast</h5>""")
    append("""<div class="cast-grid" id="cast-grid">${renderCastGridHtml(item.cast)}</div>""")
    append("""<div style="margin-top:14px;display:flex;gap:8px;align-items:center;"><button class="btn sm ghost" id="add-cast-btn">＋ Add person</button></div>""")
    append("""<hr class="dash" style="margin:18px 0;">""")
    append("""<h5 style="margin:0 0 10px;color:var(--fg-2);font-size:.8rem;letter-spacing:.06em;text-transform:uppercase;">Crew</h5>""")
    append("""<div id="crew-list">${renderCrewHtml(item.crew)}</div>""")
    append("""<div style="margin-top:14px;"><button class="btn sm ghost" id="add-crew-btn">＋ Add crew member</button></div></div>""")
}

private fun renderSeriesCastTabHtml(item: MediaItem): String = buildString {
    append("""<div class="row center" style="margin-bottom:14px;gap:10px;flex-wrap:wrap;">""")
    append("""<h4 style="margin:0;">Cast &amp; crew</h4>""")
    append("""<div class="seg-pill" id="cast-scope"><button data-scope="series" class="on">Series</button><button data-scope="season">Season</button><button data-scope="episode">Episode</button></div>""")
    append("""<span class="spacer"></span>""")
    append("""<button class="btn sm ghost" id="cast-fetch-btn">↻ Fetch from TMDB</button>""")
    append("""</div>""")
    append("""<div id="cast-body">${renderSeriesScopeHtml(item)}</div>""")
}

private fun renderSeriesScopeHtml(item: MediaItem): String = buildString {
    append("""<div class="card" style="margin-bottom:16px;">""")
    append("""<div class="row center"><h4 style="margin:0;">Cast</h4><span class="badge info" style="margin-left:7px;">${item.cast.size}</span><span class="spacer"></span><span class="tiny muted">drag to reorder · written to tvshow.nfo</span></div>""")
    append("""<hr class="dash" style="margin:10px 0 14px;">""")
    append("""<div class="cast-grid" id="cast-grid">${renderCastGridHtml(item.cast, showEpBadge = true)}</div>""")
    append("""<div style="margin-top:14px;"><button class="btn sm ghost" id="add-cast-btn">＋ Add cast</button></div>""")
    append("""</div>""")
    append("""<div class="note blue" style="display:flex;gap:10px;align-items:flex-start;padding:11px 13px;margin-bottom:16px;border-radius:var(--radius-s);background:var(--hi-soft);border:1px solid rgba(99,179,237,.18);">""")
    append("""<span style="flex:none;">ⓘ</span><div class="tiny" style="line-height:1.55;"><b>Main cast comes straight from TMDB.</b> It's <code>aggregate_credits</code> in TMDB's billing order; the <code>▸ N eps</code> badge is each role's total episode count. Written to <code>tvshow.nfo</code> and inherited by every episode. Manual edits are preserved across re-fetches.</div>""")
    append("""</div>""")
    append("""<div class="card">""")
    append("""<div class="row center"><h4 style="margin:0;">Crew</h4><span class="badge info" style="margin-left:7px;">${item.crew.size}</span><span class="spacer"></span><button class="btn sm ghost" id="add-crew-btn">＋ Add crew</button></div>""")
    append("""<hr class="dash" style="margin:10px 0 14px;">""")
    append("""<div id="crew-list">${renderCrewHtml(item.crew)}</div>""")
    append("""</div>""")
}

private fun renderSeasonMatrixHtml(item: MediaItem, matrixView: String): String = buildString {
    val seasons = item.episodes.mapNotNull { it.seasonNumber }.distinct().sorted()
    if (seasons.isEmpty()) {
        append("""<div class="muted tiny">No episodes available.</div>""")
        return@buildString
    }
    val selSeason = matrixView.toIntOrNull() ?: 0
    // Build guest name index
    data class GuestKey(val name: String, val role: String?)
    val allGuests = mutableListOf<GuestKey>()
    for (ep in item.episodes) {
        for (g in ep.guestStars) {
            val key = GuestKey(g.name, g.character ?: g.role)
            if (allGuests.none { it.name == g.name }) allGuests.add(key)
        }
    }

    // Scope sub-picker
    append("""<div class="seg-pill" id="matrix-view" style="margin-bottom:14px;">""")
    append("""<button data-mview="all" class="${if (matrixView == "all") "on" else ""}">All seasons</button>""")
    for (s in seasons) {
        append("""<button data-mview="$s" class="${if (matrixView == s.toString()) "on" else ""}">S${s.toString().padStart(2, '0')}</button>""")
    }
    append("""</div>""")
    append("""<div class="tiny muted" style="margin-bottom:10px;line-height:1.5;">ⓘ TMDB resolves recurring cast <b>per season</b> (counts shown) and guest stars <b>per episode</b>. In a season view, recurring cast default to present across the season — tap a cell to refine an exact episode list (saved as an override).</div>""")

    append("""<div style="overflow-x:auto;"><table class="matrix"><thead><tr><th class="name" style="text-align:left;">Actor</th>""")
    if (selSeason == 0) {
        // All seasons view: columns = S1, S2, ..., Total
        for (s in seasons) append("""<th>S${s.toString().padStart(2, '0')}</th>""")
        append("""<th>Total</th>""")
        append("""</tr></thead><tbody>""")
        // Main cast rows — Phase 80: real per-season counts from TMDB (seasonEpisodeCounts);
        // an explicit operator override (episodePresence) wins. A season the actor isn't in renders "·".
        for (p in item.cast) {
            append("""<tr><td class="name">${p.name.esc()} <span class="r">· ${(p.character ?: p.role ?: "").esc()}</span></td>""")
            var total = 0
            for (s in seasons) {
                val count = p.episodePresence[s.toString()]?.size ?: p.seasonEpisodeCounts[s.toString()] ?: 0
                total += count
                val cls = if (count > 0) "on" else "off"
                append("""<td><span class="ndot $cls">${if (count > 0) count.toString() else "·"}</span></td>""")
            }
            append("""<td class="mono muted">$total</td></tr>""")
        }
        // Guest rows
        for (gk in allGuests) {
            append("""<tr><td class="name">${gk.name.esc()} <span class="r">· ${(gk.role ?: "guest").esc()}</span></td>""")
            var total = 0
            for (s in seasons) {
                val count = item.episodes.count { ep -> ep.seasonNumber == s && ep.guestStars.any { it.name == gk.name } }
                total += count
                val cls = if (count > 0) "g" else "off"
                append("""<td><span class="ndot $cls">${if (count > 0) count.toString() else "·"}</span></td>""")
            }
            append("""<td class="mono muted">$total</td></tr>""")
        }
    } else {
        // Single season: columns = E1, E2, ...
        val seasonEps = item.episodes.filter { it.seasonNumber == selSeason }.sortedBy { it.episodeNumber ?: 0 }
        for (ep in seasonEps) append("""<th>E${ep.episodeNumber}</th>""")
        append("""</tr></thead><tbody>""")
        // Phase 80: only show recurring cast who are in THIS season (TMDB season-level presence).
        // Their episodes default to present (season member); an explicit override narrows per-episode.
        val sn = selSeason.toString()
        for ((pi, p) in item.cast.withIndex()) {
            val override = p.episodePresence[sn]
            val inSeason = override != null || p.seasonEpisodeCounts.containsKey(sn)
            if (!inSeason) continue
            append("""<tr><td class="name">${p.name.esc()} <span class="r">· ${(p.character ?: p.role ?: "").esc()}</span></td>""")
            for (ep in seasonEps) {
                val en = ep.episodeNumber ?: 0
                val present = override?.contains(en) ?: true
                val cls = if (present) "on" else "off"
                append("""<td class="cell" data-cell="main:$pi:$selSeason:$en"><span class="dot $cls"></span></td>""")
            }
            append("""</tr>""")
        }
        for (gk in allGuests) {
            append("""<tr><td class="name">${gk.name.esc()} <span class="r">· ${(gk.role ?: "guest").esc()} (guest)</span></td>""")
            for (ep in seasonEps) {
                val on = ep.guestStars.any { it.name == gk.name }
                val cls = if (on) "g" else "off"
                val encoded = gk.name.esc()
                append("""<td class="cell" data-cell="guest:${encoded}:$selSeason:${ep.episodeNumber}"><span class="dot $cls"></span></td>""")
            }
            append("""</tr>""")
        }
    }
    append("""</tbody></table></div>""")
    append("""<div class="mlegend"><span><span class="dot on"></span>recurring</span><span><span class="dot g"></span>guest star</span>${if (selSeason == 0) "<span>number = episodes in that season</span>" else "<span><span class=\"dot off\"></span>not in episode</span>"}</div>""")
}

private fun renderEpisodeScopeHtml(item: MediaItem, selSeason: Int, selEp: Int): String = buildString {
    val seasons = item.episodes.mapNotNull { it.seasonNumber }.distinct().sorted()
    val curSeason = selSeason.takeIf { it > 0 } ?: seasons.firstOrNull() ?: 1
    val seasonEps = item.episodes.filter { it.seasonNumber == curSeason }.sortedBy { it.episodeNumber ?: 0 }
    val curEp = selEp.takeIf { it > 0 } ?: seasonEps.firstOrNull()?.episodeNumber ?: 1
    val ep = seasonEps.firstOrNull { it.episodeNumber == curEp } ?: seasonEps.firstOrNull()

    // Season + episode picker
    append("""<div class="row center" style="gap:10px;margin-bottom:14px;flex-wrap:wrap;">""")
    append("""<div class="seg-pill" id="ep-season-pick">""")
    for (s in seasons) append("""<button data-epseason="$s" class="${if (s == curSeason) "on" else ""}">S${s.toString().padStart(2, '0')}</button>""")
    append("""</div>""")
    append("""<div class="eppick" id="ep-num-pick">""")
    for (e in seasonEps) append("""<button data-epnum="${e.episodeNumber}" class="${if (e.episodeNumber == curEp) "on" else ""}">E${e.episodeNumber}</button>""")
    append("""</div>""")
    if (ep != null) {
        append("""<span class="spacer"></span><button class="btn sm ghost" data-ep-cast-fetch="${ep.filename}">↻ Fetch this episode</button>""")
    }
    append("""</div>""")

    if (ep == null) {
        append("""<div class="muted tiny">No episodes in this season.</div>""")
        return@buildString
    }

    // Inherited main cast
    val sn = curSeason.toString()
    val presentMainCast = item.cast.filter { p ->
        p.episodePresence.isEmpty() || p.episodePresence[sn]?.contains(curEp) == true
    }
    append("""<div class="card" style="margin-bottom:16px;">""")
    append("""<div class="row center"><h4 style="margin:0;">Main cast</h4><span class="tiny muted" style="margin-left:8px;">inherited from series</span><span class="spacer"></span><span class="tiny muted">tvshow.nfo → episode</span></div>""")
    append("""<hr class="dash" style="margin:10px 0 14px;">""")
    append("""<div class="cast-grid">${presentMainCast.joinToString("") { p -> renderPersonCardHtml(p, inherited = true) }}</div>""")
    if (presentMainCast.isEmpty()) append("""<div class="muted tiny">No main-cast members tagged for this episode — toggle them in the Season matrix.</div>""")
    append("""<div class="tiny muted" style="margin-top:9px;font-size:.72rem;">Inherited members are read-only — edit them on the <b>Series</b> scope.</div>""")
    append("""</div>""")

    // Guest stars
    append("""<div class="card" style="margin-bottom:16px;">""")
    append("""<div class="row center"><h4 style="margin:0;">Guest stars</h4><span class="tiny muted" style="margin-left:8px;">this episode only</span><span class="spacer"></span><span class="tiny muted">episodedetails.nfo</span></div>""")
    append("""<hr class="dash" style="margin:10px 0 14px;">""")
    append("""<div class="cast-grid" id="ep-guest-grid">""")
    ep.guestStars.forEachIndexed { i, g -> append(renderPersonCardHtml(g, guest = true, removeAttr = "data-rm-guest=\"$i\"")) }
    append("""<div class="person add" id="guest-add">＋ Add guest star</div>""")
    append("""</div></div>""")

    // Episode crew
    append("""<div class="card">""")
    append("""<div class="row center"><h4 style="margin:0;">Episode crew</h4><span class="spacer"></span><span class="tiny muted">episodedetails.nfo · &lt;director&gt;/&lt;writer&gt;</span></div>""")
    append("""<hr class="dash" style="margin:10px 0 14px;">""")
    append("""<div id="ep-crew-list">${renderCrewHtml(ep.crew)}</div>""")
    append("""<div style="margin-top:10px;"><button class="btn sm ghost" id="epcrew-add">＋ Add crew</button></div>""")
    append("""</div>""")
}

private fun renderPersonCardHtml(p: Person, showEpBadge: Boolean = false, inherited: Boolean = false, guest: Boolean = false, removeAttr: String = ""): String = buildString {
    val pal = listOf("#7b6ef0","#2dd49a","#f5b542","#3fb6f5","#e36588","#5b8def","#19d6c6","#b15cd0")
    val colorIndex = p.name.fold(0) { acc, c -> acc + c.code } % pal.size
    val color = pal[colorIndex]
    val initials = p.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
    val imgSrc = if (!p.profilePath.isNullOrBlank()) "/api/people/${p.tmdbId}/image" else ""
    val inh = if (inherited) " inh" else ""
    val drag = if (!inherited && !guest) """ draggable="true"""" else ""
    // Phase 79: lower-left photo overlays (episode count / inherited tag) live INSIDE .ph so
    // they anchor to the image, not the variable-height text body below it.
    val phBadges = buildString {
        if (showEpBadge && p.episodeCount > 0) append("""<span class="epb">▸ ${p.episodeCount} eps</span>""")
        if (inherited) append("""<span class="tag">⤓ inherited</span>""")
    }
    append("""<div class="person$inh"$drag>""")
    if (guest) append("""<span class="gtag">guest</span>""")
    if (removeAttr.isNotBlank()) append("""<button class="prm" $removeAttr>✕</button>""")
    else if (!inherited) append("""<button class="prm" data-rm-cast="${p.tmdbId}">✕</button>""")
    if (imgSrc.isNotBlank()) {
        // onerror removes only the <img> and appends initials text — it must NOT clear .ph (that would wipe phBadges)
        append("""<div class="ph" style="background:$color;">$phBadges<img src="$imgSrc" alt="" loading="lazy" decoding="async" style="width:100%;height:100%;object-fit:cover;" onerror="var p=this.parentElement;p.style.background='$color';this.remove();p.insertAdjacentText('beforeend','$initials')"></div>""")
    } else {
        append("""<div class="ph" style="background:$color;color:#fff;">$phBadges$initials</div>""")
    }
    val charOrRole = (p.character ?: p.role)?.takeIf { it.isNotBlank() } ?: ""
    append("""<div class="pbody"><div class="pname">${p.name.esc()}</div><div class="prole">${charOrRole.esc().ifEmpty { """<span class="muted">＋ role</span>""" }}</div></div>""")
    append("""</div>""")
}

private fun renderCastGridHtml(cast: List<Person>, showEpBadge: Boolean = false): String {
    if (cast.isEmpty()) return """<span class="muted tiny">No cast — click "Fetch from TMDB" to populate.</span>"""
    return cast.joinToString("") { p -> renderPersonCardHtml(p, showEpBadge = showEpBadge) }
}

private fun renderCrewHtml(crew: List<Person>): String {
    if (crew.isEmpty()) return """<span class="muted tiny">No crew — click "Fetch from TMDB" to populate.</span>"""
    val pal = listOf("#7b6ef0","#2dd49a","#f5b542","#3fb6f5","#e36588","#5b8def","#19d6c6","#b15cd0")
    val byDept = crew.groupBy { person -> person.department?.takeIf { it.isNotBlank() } ?: "Other" }
    return byDept.entries.joinToString("") { (dept, members) ->
        val rows = members.joinToString("") { p ->
            val ci = p.name.fold(0) { acc, c -> acc + c.code } % pal.size
            val av = p.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
            """<div class="crew-row" data-person-id="${p.tmdbId}">
                 <span class="crew-av" style="background:${pal[ci]};">$av</span>
                 <div style="flex:1;min-width:0;">
                   <div class="crew-name">${p.name.esc()}</div>
                   <div class="crew-job">${(p.job ?: "").esc()}</div>
                 </div>
                 <span class="crew-rm prow-rm-crew" data-tmdb-id="${p.tmdbId}" data-job="${(p.job ?: "").esc()}">✕</span>
               </div>"""
        }
        """<div class="crew-dept">${dept.esc()}</div>$rows"""
    }
}

private fun wireCastTab(item: MediaItem, container: Element, scope: CoroutineScope) {
    val mutableCast = item.cast.toMutableList()
    val mutableCrew = item.crew.toMutableList()
    if (item.kind == MediaKind.TV_SHOW) {
        wireSeriesCastTab(item, mutableCast, mutableCrew, scope)
    } else {
        wireMovieCastTab(item, mutableCast, mutableCrew, scope)
    }
}

private fun wireMovieCastTab(item: MediaItem, mutableCast: MutableList<Person>, mutableCrew: MutableList<Person>, scope: CoroutineScope) {
    fun wireCastRemove() {
        document.querySelectorAll(".prm[data-rm-cast]").let { btns ->
            for (i in 0 until btns.length) {
                val btn = btns.item(i) as? HTMLElement ?: continue
                val tmdbId = btn.getAttribute("data-rm-cast")?.toIntOrNull() ?: continue
                btn.addEventListener("click") {
                    mutableCast.removeAll { it.tmdbId == tmdbId }
                    scope.launch { MediaApi.patchCast(item.id, mutableCast) }
                    document.getElementById("cast-grid")?.innerHTML = renderCastGridHtml(mutableCast)
                    wireCastRemove()
                }
            }
        }
    }
    fun wireCrewRemove() {
        document.querySelectorAll(".prow-rm-crew").let { btns ->
            for (i in 0 until btns.length) {
                val btn = btns.item(i) as? HTMLElement ?: continue
                val tmdbId = btn.getAttribute("data-tmdb-id")?.toIntOrNull() ?: continue
                val job = btn.getAttribute("data-job") ?: ""
                btn.addEventListener("click") {
                    mutableCrew.removeAll { it.tmdbId == tmdbId && it.job == job }
                    scope.launch { MediaApi.patchCrew(item.id, mutableCrew) }
                    document.getElementById("crew-list")?.innerHTML = renderCrewHtml(mutableCrew)
                    wireCrewRemove()
                }
            }
        }
    }
    wireCastRemove()
    wireCrewRemove()

    document.getElementById("cast-fetch-btn")?.addEventListener("click") {
        scope.launch {
            val updated = MediaApi.fetchCastFromTmdb(item.id) ?: return@launch
            document.getElementById("cast-grid")?.innerHTML = renderCastGridHtml(updated.cast)
            document.getElementById("crew-list")?.innerHTML = renderCrewHtml(updated.crew)
            mutableCast.clear(); mutableCast.addAll(updated.cast)
            mutableCrew.clear(); mutableCrew.addAll(updated.crew)
            wireCastRemove(); wireCrewRemove()
        }
    }
    document.getElementById("add-cast-btn")?.addEventListener("click") {
        showPersonSearchModal(scope, isCrew = false) { result ->
            if (mutableCast.none { it.tmdbId == result.tmdbId }) {
                mutableCast.add(Person(tmdbId = result.tmdbId, name = result.name, profilePath = result.profilePath, order = mutableCast.size, type = "Actor"))
                scope.launch { MediaApi.patchCast(item.id, mutableCast) }
                document.getElementById("cast-grid")?.innerHTML = renderCastGridHtml(mutableCast)
                wireCastRemove()
            }
        }
    }
    document.getElementById("add-crew-btn")?.addEventListener("click") {
        showPersonSearchModal(scope, isCrew = true) { result ->
            if (mutableCrew.none { it.tmdbId == result.tmdbId }) {
                mutableCrew.add(Person(tmdbId = result.tmdbId, name = result.name, profilePath = result.profilePath, department = result.knownForDepartment, type = "Director"))
                scope.launch { MediaApi.patchCrew(item.id, mutableCrew) }
                document.getElementById("crew-list")?.innerHTML = renderCrewHtml(mutableCrew)
                wireCrewRemove()
            }
        }
    }
}

private fun wireSeriesCastTab(item: MediaItem, mutableCast: MutableList<Person>, mutableCrew: MutableList<Person>, scope: CoroutineScope) {
    var castScope = "series"
    var matrixView = "all"
    var selSeason = item.episodes.mapNotNull { it.seasonNumber }.minOrNull() ?: 1
    var selEp = item.episodes.filter { it.seasonNumber == selSeason }.mapNotNull { it.episodeNumber }.minOrNull() ?: 1
    val mutableGuests = mutableListOf<Person>()
    val mutableEpCrew = mutableListOf<Person>()
    val mutableEpisodes = item.episodes.toMutableList()  // Phase 76: matrix guest toggles update this for live re-render

    fun currentEp() = item.episodes.firstOrNull { it.seasonNumber == selSeason && it.episodeNumber == selEp }
    fun syncEpState() {
        val ep = currentEp()
        mutableGuests.clear(); if (ep != null) mutableGuests.addAll(ep.guestStars)
        mutableEpCrew.clear(); if (ep != null) mutableEpCrew.addAll(ep.crew)
    }

    fun refreshBody() {
        val bodyEl = document.getElementById("cast-body") as? HTMLElement ?: return
        bodyEl.innerHTML = when (castScope) {
            "series" -> renderSeriesScopeHtml(item.copy(cast = mutableCast, crew = mutableCrew))
            "season" -> """<div class="card">${renderSeasonMatrixHtml(item.copy(cast = mutableCast, crew = mutableCrew, episodes = mutableEpisodes), matrixView)}</div>"""
            else -> renderEpisodeScopeHtml(item, selSeason, selEp)
        }
    }

    // Scope switcher (outside cast-body, persists across re-renders)
    document.getElementById("cast-scope")?.addEventListener("click") { e ->
        val btn = (e.target as? HTMLElement)?.closest("[data-scope]") as? HTMLElement ?: return@addEventListener
        val s = btn.getAttribute("data-scope") ?: return@addEventListener
        castScope = s
        document.getElementById("cast-scope")?.querySelectorAll("button")?.let { btns ->
            for (i in 0 until btns.length) {
                val b = btns.item(i) as? HTMLElement ?: continue
                b.classList.toggle("on", b.getAttribute("data-scope") == castScope)
            }
        }
        if (castScope == "episode") syncEpState()
        refreshBody()
    }

    // Fetch from TMDB (outside cast-body, persists)
    document.getElementById("cast-fetch-btn")?.addEventListener("click") {
        scope.launch {
            val updated = MediaApi.fetchCastFromTmdb(item.id) ?: return@launch
            mutableCast.clear(); mutableCast.addAll(updated.cast)
            mutableCrew.clear(); mutableCrew.addAll(updated.crew)
            refreshBody()
        }
    }

    // Event delegation on cast-body — handles all dynamic clicks
    val castBody = document.getElementById("cast-body") as? HTMLElement
    castBody?.addEventListener("click") { e ->
        val target = e.target as? HTMLElement ?: return@addEventListener

        // Cast remove (series scope)
        target.closest(".prm[data-rm-cast]")?.let { el ->
            val tmdbId = (el as HTMLElement).getAttribute("data-rm-cast")?.toIntOrNull() ?: return@let
            mutableCast.removeAll { it.tmdbId == tmdbId }
            scope.launch { MediaApi.patchCast(item.id, mutableCast) }
            refreshBody()
        }
        // Crew remove (series scope)
        target.closest(".prow-rm-crew")?.let { el ->
            el as HTMLElement
            val tmdbId = el.getAttribute("data-tmdb-id")?.toIntOrNull() ?: return@let
            val job = el.getAttribute("data-job") ?: ""
            mutableCrew.removeAll { it.tmdbId == tmdbId && it.job == job }
            scope.launch { MediaApi.patchCrew(item.id, mutableCrew) }
            refreshBody()
        }
        // Guest remove (episode scope)
        target.closest("[data-rm-guest]")?.let { el ->
            val idx = (el as HTMLElement).getAttribute("data-rm-guest")?.toIntOrNull() ?: return@let
            if (idx < mutableGuests.size) {
                mutableGuests.removeAt(idx)
                val ep = currentEp() ?: return@let
                scope.launch { MediaApi.patchEpisodeCast(item.id, ep.filename, mutableGuests) }
                refreshBody()
            }
        }
        // Episode fetch
        target.closest("[data-ep-cast-fetch]")?.let { el ->
            val filename = (el as HTMLElement).getAttribute("data-ep-cast-fetch") ?: return@let
            scope.launch {
                val updated = MediaApi.fetchEpisodeCastFromTmdb(item.id, filename) ?: return@launch
                val ep = updated.episodes.firstOrNull { it.filename == filename } ?: return@launch
                mutableGuests.clear(); mutableGuests.addAll(ep.guestStars)
                mutableEpCrew.clear(); mutableEpCrew.addAll(ep.crew)
                refreshBody()
            }
        }
        // Episode season picker
        target.closest("[data-epseason]")?.let { el ->
            selSeason = (el as HTMLElement).getAttribute("data-epseason")?.toIntOrNull() ?: return@let
            selEp = item.episodes.filter { it.seasonNumber == selSeason }.mapNotNull { it.episodeNumber }.minOrNull() ?: 1
            syncEpState()
            refreshBody()
        }
        // Episode number picker
        target.closest("[data-epnum]")?.let { el ->
            selEp = (el as HTMLElement).getAttribute("data-epnum")?.toIntOrNull() ?: return@let
            syncEpState()
            refreshBody()
        }
        // Matrix view picker
        target.closest("[data-mview]")?.let { el ->
            matrixView = (el as HTMLElement).getAttribute("data-mview") ?: return@let
            refreshBody()
        }
        // Phase 76: presence-matrix cell toggle (per-season E1..En view).
        // main:pi:season:ep → flip episodePresence override on cast[pi]; guest:name:season:ep → add/remove from episode guestStars.
        target.closest("[data-cell]")?.let { el ->
            val cell = (el as HTMLElement).getAttribute("data-cell") ?: return@let
            val parts = cell.split(":")
            if (parts.size < 4) return@let
            val sn = parts[parts.size - 2]
            val en = parts.last().toIntOrNull() ?: return@let
            when (parts[0]) {
                "main" -> {
                    val pi = parts[1].toIntOrNull() ?: return@let
                    if (pi !in mutableCast.indices) return@let
                    val p = mutableCast[pi]
                    val seasonEps = mutableEpisodes.filter { it.seasonNumber?.toString() == sn }.mapNotNull { it.episodeNumber }
                    // explicit override if set, else the season-member default = every episode in the season
                    val cur = (p.episodePresence[sn]?.toMutableSet() ?: seasonEps.toMutableSet())
                    if (en in cur) cur.remove(en) else cur.add(en)
                    val newPresence = p.episodePresence.toMutableMap().apply { put(sn, cur.sorted()) }
                    mutableCast[pi] = p.copy(episodePresence = newPresence)
                    scope.launch { MediaApi.patchCast(item.id, mutableCast) }
                    refreshBody()
                }
                "guest" -> {
                    val name = parts.subList(1, parts.size - 2).joinToString(":")
                    val epIdx = mutableEpisodes.indexOfFirst { it.seasonNumber?.toString() == sn && it.episodeNumber == en }
                    if (epIdx < 0) return@let
                    val ep = mutableEpisodes[epIdx]
                    val guests = ep.guestStars.toMutableList()
                    val at = guests.indexOfFirst { it.name == name }
                    if (at >= 0) guests.removeAt(at)
                    else guests.add(mutableEpisodes.flatMap { it.guestStars }.firstOrNull { it.name == name }
                        ?: Person(tmdbId = 0, name = name, type = "Actor"))
                    mutableEpisodes[epIdx] = ep.copy(guestStars = guests)
                    scope.launch { MediaApi.patchEpisodeCast(item.id, ep.filename, guests) }
                    refreshBody()
                }
            }
        }
        // Add cast (series scope)
        if (target.closest("#mc-add") != null || target.closest("#add-cast-btn") != null) {
            showPersonSearchModal(scope, isCrew = false) { result ->
                if (mutableCast.none { it.tmdbId == result.tmdbId }) {
                    mutableCast.add(Person(tmdbId = result.tmdbId, name = result.name, profilePath = result.profilePath, order = mutableCast.size, type = "Actor"))
                    scope.launch { MediaApi.patchCast(item.id, mutableCast) }
                    refreshBody()
                }
            }
        }
        // Add crew (series scope)
        if (target.closest("#crew-add") != null || target.closest("#add-crew-btn") != null) {
            showPersonSearchModal(scope, isCrew = true) { result ->
                if (mutableCrew.none { it.tmdbId == result.tmdbId }) {
                    mutableCrew.add(Person(tmdbId = result.tmdbId, name = result.name, profilePath = result.profilePath, department = result.knownForDepartment, type = "Director"))
                    scope.launch { MediaApi.patchCrew(item.id, mutableCrew) }
                    refreshBody()
                }
            }
        }
        // Add guest star (episode scope)
        if (target.closest("#guest-add") != null) {
            showPersonSearchModal(scope, isCrew = false) { result ->
                if (mutableGuests.none { it.tmdbId == result.tmdbId }) {
                    mutableGuests.add(Person(tmdbId = result.tmdbId, name = result.name, profilePath = result.profilePath, type = "Actor"))
                    val ep = currentEp() ?: return@showPersonSearchModal
                    scope.launch { MediaApi.patchEpisodeCast(item.id, ep.filename, mutableGuests) }
                    refreshBody()
                }
            }
        }
        // Add episode crew
        if (target.closest("#epcrew-add") != null) {
            showPersonSearchModal(scope, isCrew = true) { result ->
                mutableEpCrew.add(Person(tmdbId = result.tmdbId, name = result.name, profilePath = result.profilePath, department = result.knownForDepartment, type = "Director"))
                val ep = currentEp() ?: return@showPersonSearchModal
                scope.launch { MediaApi.patchEpisodeCrew(item.id, ep.filename, mutableEpCrew) }
                refreshBody()
            }
        }
    }

    refreshBody()
}

private fun showPersonSearchModal(scope: CoroutineScope, isCrew: Boolean, onSelect: (PersonSearchResult) -> Unit) {
    val existing = document.getElementById("person-modal-overlay")
    existing?.remove()
    val overlay = document.createElement("div") as? HTMLElement ?: return
    overlay.id = "person-modal-overlay"
    overlay.className = "diff-modal-overlay"
    overlay.innerHTML = """
        <div class="diff-modal" style="max-width:480px;">
          <div class="row center" style="margin-bottom:14px;">
            <h4 style="margin:0;">Search person</h4>
            <span class="spacer"></span>
            <button id="person-modal-close" class="btn sm ghost">✕</button>
          </div>
          <input id="person-search-input" class="input" type="text" placeholder="Search TMDB for a person…" style="width:100%;margin-bottom:10px;">
          <div id="person-search-results" style="max-height:320px;overflow:auto;"></div>
        </div>"""
    document.body?.appendChild(overlay)
    overlay.addEventListener("click") { e ->
        if ((e.target as? HTMLElement)?.id == "person-modal-overlay") overlay.remove()
    }
    document.getElementById("person-modal-close")?.addEventListener("click") { overlay.remove() }
    val searchInput = document.getElementById("person-search-input") as? HTMLInputElement
    searchInput?.focus()
    searchInput?.addEventListener("input") {
        val q = searchInput.value.trim()
        if (q.length < 2) return@addEventListener
        scope.launch {
            val results = MediaApi.searchPeople(q)
            val resultsEl = document.getElementById("person-search-results") as? HTMLElement ?: return@launch
            resultsEl.innerHTML = results.joinToString("") { r ->
                val dept = if (r.knownForDepartment.isNotBlank()) " · ${r.knownForDepartment.esc()}" else ""
                """<div class="menu-item person-result" data-tmdb-id="${r.tmdbId}" style="cursor:pointer;padding:8px 10px;border-radius:7px;">
                     ${r.name.esc()}$dept
                   </div>"""
            }
            resultsEl.querySelectorAll(".person-result").let { items ->
                for (i in 0 until items.length) {
                    val el = items.item(i) as? HTMLElement ?: continue
                    val tmdbId = el.getAttribute("data-tmdb-id")?.toIntOrNull() ?: continue
                    el.addEventListener("click") {
                        val r = results.firstOrNull { it.tmdbId == tmdbId } ?: return@addEventListener
                        overlay.remove()
                        onSelect(r)
                    }
                }
            }
        }
    }
}
