@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MediaApi
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.ConditionGroup
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.QueryJoin
import dev.jellystructure.shared.tv.isLive
import dev.jellystructure.shared.tv.migrateFlatQuery
import dev.jellystructure.elemNearViewportBottom
import dev.jellystructure.historyReplaceState
import dev.jellystructure.observeSections
import dev.jellystructure.jobs.JobEvent
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

private val scanJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

private const val TMDB_IMG = "https://image.tmdb.org/t/p/w342"

// Phase 133: posterPath is usually a TMDB file_path fragment ("/abc.jpg"), but a manually-uploaded
// poster with no TMDB equivalent instead holds the jellystructure-served on-disk URL
// ("/tv/image/{id}/poster") — route to it directly instead of prefixing the TMDB CDN.
internal fun posterSrc(posterPath: String, tmdbPrefix: String): String =
    if (posterPath.startsWith("/tv/image/")) posterPath else "$tmdbPrefix$posterPath"

private const val LIB_SLICE = 60  // items fetched per infinite-scroll slice
private const val MIN_SEARCH_LEN = 2  // shorter queries fall back to the unfiltered list, no request
private var libSlice = 0          // slices loaded so far for the current filter set; next fetch = libSlice + 1
private var libLoadedCount = 0    // cards currently in the grid
private var libTotal = 0
private var libEndReached = false
private var libLoading = false
// Bug fix: a reset (filter/kind/search/sort change) that arrives while a background infinite-scroll
// continuation is still in flight used to just no-op (see loadMore's libLoading guard below) — but
// the click handler that triggered it updates the chip's "active" styling synchronously regardless,
// so the UI could show a filter as selected while the grid kept showing the previous filter's items
// until the user re-triggered the action. Remember that a reset was requested and run it immediately
// once the in-flight load finishes, reading whatever filter state is current at that point (the
// module-level lib* vars are already the single source of truth by the time this fires).
private var libPendingReset = false
private var libKind: MediaKind? = null
private var libFilter: String? = null
// Bug fix: id -> rendered card element, so a live-scan append (appendItemToGrid) is an O(1) map
// lookup instead of a `querySelector` scan of the whole grid — at n cards already rendered, a full
// rescan of n items used to cost O(n) DOM work *per scanned item* (existence check + placeholder
// check), i.e. O(n^2) total for the scan, and it gets worse every time the library grows. Cleared
// wherever the grid's contents are thrown away (loadMore reset, scan start) — a stale entry pointing
// at a detached element would make a later `existing.replaceWith(newCard)` silently no-op.
private val libCardsById = HashMap<String, Element>()
// Phase 117: display labels for a dashboard-breakdown deep link's per-type `?filter=` value.
private val ISSUE_FILTER_LABELS = mapOf(
    "untagged" to "Untagged tracks",
    "cascade_mismatch" to "Wrong default audio",
    "multi_default" to "Multiple default audio",
    "language_mix" to "Mixed-language series",
    "missing_from_source" to "No longer in Jellyfin",
    "missing_still" to "Missing episode image",
    "duplicate" to "Duplicate entries",
    "duplicate_episode" to "Duplicate episode files",
    "zero_audio" to "No audio tracks",
    "cover_as_video" to "Cover art muxed as video",   // Phase 144
    "unresolved_jellyfin_id" to "Unresolved Jellyfin ID",   // Phase 152/153
    "segments_lowconf" to "Low-confidence segments",   // Phase 150/163
    "no_segments" to "No intro/credits detected",   // Phase 150/163
    "mkv_track_layout" to "Unplayable in Ravilo (MKV structure)",   // Phase 201 amendment (2026-09-13) — covers both TRACKS_AFTER_CLUSTER and ELEMENT_SIZE_OVERFLOW; the detail page's banner gives the specific one
)
private var libSearch: String? = null
private var libSort: String? = null
private var libScanSocket: WebSocket? = null
private var libScannedCount = 0
private var libSearchJob: Job? = null
private var libPendingScanCount = 0  // items scanned while a filter is active (not shown live)
private var libStudios: List<String> = emptyList()
private var libNetworks: List<String> = emptyList()
private var libGenres: List<String> = emptyList()
private var libAudioLangs: List<String> = emptyList()
private var libTrackTitle: String? = null
private var libAudioCodec: String? = null
private var libUntaggedAudio: Boolean = false
private var libTags: List<String> = emptyList()
private var libMatch: String = "ALL"  // R74: "ALL" | "ANY"
// R87: a handed-off `content_row` gap condition (its `rows` double as the workbench edit context).
// Channel conditions ride the normal per-facet params; this rides a `coverage` param so it survives nav.
private var libCoverageCond: Condition? = null
private var libTracker: String? = null  // Phase 98: "seeded on" tracker filter
// Phase 140 — the full-workbench blocks tree, when it goes beyond what the simple per-facet fields
// above can express (NOT, sub-blocks, cross-facet OR blocks, etc). Opening the workbench REPLACES the
// simple quick-filters with whatever tree the user builds there — they're two tiers of "current
// filter" (quick chips = fast common case, the workbench = the advanced editor), not merged.
private var libQuery: ConditionGroup? = null
private val libCovJson = Json { ignoreUnknownKeys = true }

// -- URL helpers ----------------------------------------------------------

private fun parseLibraryUrl() {
    val hash = window.location.hash.removePrefix("#")
    val query = if ("?" in hash) hash.substringAfter("?") else ""
    fun param(key: String) = query.split("&")
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.let { dev.jellystructure.decodeURIComponent(it) }
        ?.takeIf { it.isNotBlank() }

    libKind          = param("kind")?.let { runCatching { MediaKind.valueOf(it) }.getOrNull() }
    libFilter        = param("filter")
    libSearch        = param("search")
    libSort          = param("sort")
    libStudios       = param("studios")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    libNetworks      = param("networks")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    libGenres        = param("genres")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    libAudioLangs    = param("audioLang")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    libTrackTitle    = param("trackTitle")
    libAudioCodec    = param("audioCodec")
    libUntaggedAudio = param("untaggedAudio") == "true"
    libTags          = param("tags")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
    libMatch         = if (param("match") == "ANY") "ANY" else "ALL"
    // R87: decode the handed-off content_row gap condition (null on a normal library visit).
    libCoverageCond  = param("coverage")?.let { runCatching { libCovJson.decodeFromString(Condition.serializer(), it) }.getOrNull() }
    libTracker       = param("tracker")
    // Phase 140 — the workbench's full tree, when present (folds in what coverage= used to carry).
    libQuery         = param("query")?.let { runCatching { libCovJson.decodeFromString(ConditionGroup.serializer(), it) }.getOrNull() }
}

private fun updateLibraryUrl() {
    val params = buildList {
        libKind?.let { add("kind=${it.name}") }
        libFilter?.let { add("filter=$it") }
        libSearch?.let { add("search=${dev.jellystructure.encodeURIComponent(it)}") }
        libSort?.let { add("sort=$it") }
        if (libAudioLangs.isNotEmpty()) add("audioLang=${libAudioLangs.joinToString(",")}")
        libTrackTitle?.let { add("trackTitle=${dev.jellystructure.encodeURIComponent(it)}") }
        libAudioCodec?.let { add("audioCodec=$it") }
        if (libUntaggedAudio) add("untaggedAudio=true")
        if (libStudios.isNotEmpty()) add("studios=${libStudios.joinToString(",") { dev.jellystructure.encodeURIComponent(it) }}")
        if (libNetworks.isNotEmpty()) add("networks=${libNetworks.joinToString(",") { dev.jellystructure.encodeURIComponent(it) }}")
        if (libGenres.isNotEmpty()) add("genres=${libGenres.joinToString(",") { dev.jellystructure.encodeURIComponent(it) }}")
        if (libTags.isNotEmpty()) add("tags=${libTags.joinToString(",") { dev.jellystructure.encodeURIComponent(it) }}")
        if (libMatch == "ANY") add("match=ANY")
        libCoverageCond?.let { add("coverage=${dev.jellystructure.encodeURIComponent(libCovJson.encodeToString(Condition.serializer(), it))}") }
        libTracker?.let { add("tracker=${dev.jellystructure.encodeURIComponent(it)}") }
        libQuery?.let { add("query=${dev.jellystructure.encodeURIComponent(libCovJson.encodeToString(ConditionGroup.serializer(), it))}") }
    }
    val newHash = if (params.isEmpty()) "#/library" else "#/library?${params.joinToString("&")}"
    historyReplaceState(newHash)
}

// -- Entry point ----------------------------------------------------------

fun renderLibrary(container: Element, scope: CoroutineScope, query: Map<String, String> = emptyMap()) {
    libScanSocket?.close()
    libScanSocket = null
    libScannedCount = 0
    libPendingScanCount = 0
    parseLibraryUrl()
    // R101: reset pagination state on every renderLibrary so a stale libSlice/libEndReached from the
    // previous view can't leak into the new load (the workbench-apply zero-results race root cause).
    libLoading = false; libSlice = 0; libLoadedCount = 0; libTotal = 0; libEndReached = false

    container.innerHTML = """
        <div class="pagebar">
          <h1>Library</h1>
          <span class="spacer"></span>
          <span class="split" id="scan-split">
            <button id="scan-btn" class="btn primary" title="Skips items not due for a recheck yet (Settings ▸ scan_files' cooldown), same as a scheduled run">▶ Scan library</button>
            <span class="btn primary split-caret menu-btn"><span class="caret">▾</span></span>
            <div class="menu">
              <div class="menu-item" id="scan-full"><span class="mi-ic">⟳</span><span>Scan library (full rescan)<span class="mi-sub">No freshness filter — every item is reprocessed</span></span></div>
            </div>
          </span>
          <span class="searchwrap" id="searchwrap">
            <input id="lib-search" class="input" type="search" placeholder="⌕ search title…" style="width:200px;flex-shrink:0;">
          </span>
          <span class="seg" id="kindseg">
            <span id="k-all" class="on">All</span>
            <span id="k-movie">Movies</span>
            <span id="k-tv">TV</span>
            <span id="k-mv">Music videos</span>
          </span>
        </div>
        <p class="page-sub">Everything Jellystructure manages. A red corner means at least one untagged track; an orange one means a mixed-language series. Click any title to open its detail page.</p>

        <div id="scan-banner" style="display:none;margin-bottom:14px"></div>

        <div class="row center" style="margin-bottom:6px;gap:8px;flex-wrap:wrap;">
          <button id="lib-workbench" class="chip">⚙ Add filter</button>
          <span class="muted tiny">filter:</span>
          <button id="f-all" class="chip">All</button>
          <button id="f-attention" class="chip">Needs attention</button>
          <button id="f-artwork" class="chip">Missing artwork</button>
          <button id="lib-clear" class="chip" style="display:none">✕ Clear filters</button>
          <span class="spacer" style="flex:1"></span>
          <select id="lib-sort" class="input" style="width:auto;font-size:.83rem;">
            <option value="">recently added ▾</option>
            <option value="title">title A–Z</option>
            <option value="year">year newest first</option>
          </select>
          <span class="muted tiny" id="lib-total"></span>
        </div>
        <div id="active-chips" class="row center" style="display:none;margin-bottom:8px;gap:6px;flex-wrap:wrap;"></div>

        <div id="poster-grid" class="poster-grid"></div>
        <div class="row center" style="margin-top:18px;justify-content:center;min-height:24px;" id="lib-loadmore"></div>
        <div id="lib-sentinel" style="height:1px;"></div>
    """.trimIndent()

    syncFilterUiToState(scope)
    attachLibraryListeners(scope)
    wireLibraryWorkbench(scope)
    // Bug fix: one delegated listener on the grid instead of one addEventListener per card — card
    // count grows with the library (and with how much of it has been scrolled through), so a
    // per-card listener was unbounded growth for no benefit; #poster-grid itself is recreated fresh
    // by the innerHTML assignment above, so this is re-attached once per Library render.
    document.getElementById("poster-grid")?.addEventListener("click") { ev ->
        val target = (ev.target as? Element)?.closest(".poster[data-id]") ?: return@addEventListener
        val id = target.getAttribute("data-id") ?: return@addEventListener
        App.navigate("/media/$id")
    }
    // Bug fix: a card with no stored posterPath (see posterCardHtml) falls back to attempting the real
    // artwork route rather than assuming there's nothing to show — a genuine miss (item truly has no
    // poster on disk) needs to swap that broken `<img>` for the plain placeholder. `error` events don't
    // bubble, so this delegated listener must run in the capture phase to see them at all.
    document.getElementById("poster-grid")?.addEventListener("error", { ev: Event ->
        val img = ev.target as? HTMLImageElement ?: return@addEventListener
        if (!img.classList.contains("poster-fallback")) return@addEventListener
        val slot = img.parentElement ?: return@addEventListener
        val title = img.alt
        slot.innerHTML = """<div class="x"></div><span></span>"""
        (slot.querySelector("span") as? HTMLElement)?.textContent = title
    }, true)

    // R101: gate the observer so an early reset=false load can't run before the canonical reset=true load
    // completes — the initial observer fire sees initialLoadDone=false and skips (closes the race).
    val initialLoad = object { var done = false }
    observeSections("lib-sentinel", "700px 0px 700px 0px") { _ ->
        if (libScanSocket == null && initialLoad.done) scope.launch { loadMore(scope, reset = false) }
    }

    scope.launch {
        val status = MediaApi.scanStatus()
        if (status?.running == true) {
            libScannedCount = status.processedCount
            setScanRunning(true)
            loadMore(scope, reset = true)
            connectScanSocket(scope)
        } else {
            loadMore(scope, reset = true)
        }
        initialLoad.done = true
    }
}

// Syncs all UI widgets (chips, buttons, inputs) to the current lib* state vars.
private fun syncFilterUiToState(scope: CoroutineScope) {
    val filterActive = when (libFilter) {
        "attention" -> "f-attention"
        "missing_artwork" -> "f-artwork"
        in ISSUE_FILTER_LABELS.keys -> null  // Phase 117: shown as its own removable "Issue: …" chip instead
        else -> "f-all"
    }
    listOf("f-all", "f-attention", "f-artwork").forEach { id ->
        (document.getElementById(id) as? HTMLElement)?.className =
            if (id == filterActive) "chip active-chip" else "chip"
    }
    val kindActive = when (libKind) { MediaKind.MOVIE -> "k-movie"; MediaKind.TV_SHOW -> "k-tv"; MediaKind.MUSIC_VIDEO -> "k-mv"; else -> "k-all" }
    listOf("k-all", "k-movie", "k-tv", "k-mv").forEach { id ->
        (document.getElementById(id) as? HTMLElement)?.className = if (id == kindActive) "on" else ""
    }
    (document.getElementById("lib-search") as? HTMLInputElement)?.value = libSearch ?: ""
    (document.getElementById("lib-sort") as? HTMLSelectElement)?.value = libSort ?: ""
    updateActiveChips(scope)
}

// -- Event wiring ---------------------------------------------------------

private fun attachLibraryListeners(scope: CoroutineScope) {
    fun reload() { scope.launch { loadMore(scope, reset = true) } }

    document.getElementById("scan-btn")?.addEventListener("click") {
        scope.launch { triggerScan(scope) }
    }
    document.getElementById("scan-full")?.addEventListener("click") { e ->
        if ((e.currentTarget as? HTMLElement)?.hasAttribute("disabled") == true) return@addEventListener
        (document.getElementById("scan-split") as? HTMLElement)?.classList?.remove("open")
        scope.launch { triggerScan(scope, full = true) }
    }
    (document.getElementById("scan-split") as? HTMLElement)?.querySelector(".menu-btn")?.let { caret ->
        (caret as? HTMLElement)?.addEventListener("click") { e ->
            e.stopPropagation()
            (document.getElementById("scan-split") as? HTMLElement)?.classList?.toggle("open")
        }
    }
    document.addEventListener("click") { (document.getElementById("scan-split") as? HTMLElement)?.classList?.remove("open") }

    document.getElementById("lib-search")?.addEventListener("input") {
        val v = (document.getElementById("lib-search") as? HTMLInputElement)?.value?.trim()
        // Bug fix: below MIN_SEARCH_LEN, treat it the same as no search (falls back to the unfiltered
        // list) instead of firing a full server-side decode+scan for a 1-char query on every keystroke.
        libSearch = if (v.isNullOrBlank() || v.length < MIN_SEARCH_LEN) null else v  // capture latest value immediately
        libSearchJob?.cancel()
        libSearchJob = scope.launch { delay(250); loadMore(scope, reset = true) }
    }

    document.getElementById("lib-sort")?.addEventListener("change") {
        val v = (document.getElementById("lib-sort") as? HTMLSelectElement)?.value?.trim()
        libSort = if (v.isNullOrBlank()) null else v
        reload()
    }

    mapOf(
        "f-all"       to { libFilter = null },
        "f-attention" to { libFilter = "attention" },
        "f-artwork"   to { libFilter = "missing_artwork" },
    ).forEach { (id, setter) ->
        document.getElementById(id)?.addEventListener("click") {
            setter()
            listOf("f-all", "f-attention", "f-artwork").forEach { chipId ->
                (document.getElementById(chipId) as? HTMLElement)?.className =
                    if (chipId == id) "chip active-chip" else "chip"
            }
            reload()
        }
    }

    mapOf(
        "k-all"   to { libKind = null },
        "k-movie" to { libKind = MediaKind.MOVIE },
        "k-tv"    to { libKind = MediaKind.TV_SHOW },
        "k-mv"    to { libKind = MediaKind.MUSIC_VIDEO },
    ).forEach { (id, setter) ->
        document.getElementById(id)?.addEventListener("click") {
            setter()
            listOf("k-all", "k-movie", "k-tv", "k-mv").forEach { btnId ->
                (document.getElementById(btnId) as? HTMLElement)?.className = if (btnId == id) "on" else ""
            }
            reload()
        }
    }

    // R101: Clear filters — reset all state vars and reload a clean grid.
    document.getElementById("lib-clear")?.addEventListener("click") {
        libSearch = null; libFilter = null
        libStudios = emptyList(); libNetworks = emptyList(); libGenres = emptyList()
        libTags = emptyList(); libAudioLangs = emptyList()
        libTrackTitle = null; libAudioCodec = null; libUntaggedAudio = false
        libCoverageCond = null; libTracker = null; libQuery = null
        libKind = null; libSort = null; libMatch = "ALL"
        syncFilterUiToState(scope)
        scope.launch { loadMore(scope, reset = true) }
    }
}

// -- Active filter chips --------------------------------------------------

private fun updateActiveChips(scope: CoroutineScope? = null) {
    val container = document.getElementById("active-chips") as? HTMLElement ?: return
    container.innerHTML = ""

    val active = buildList {
        libStudios.forEach  { add(Triple("studio:$it",  "Studio",  it)) }
        libNetworks.forEach { add(Triple("network:$it", "Network", it)) }
        libGenres.forEach   { add(Triple("genre:$it",   "Genre",   it)) }
        libTags.forEach     { tag -> add(Triple("tag:$tag", "Tag", tag)) }
        libAudioLangs.forEach { lang -> add(Triple("lang:$lang", "Language", langDisplay(lang))) }
        libTrackTitle?.let  { add(Triple("title",   "Title",    it)) }
        libAudioCodec?.let  { add(Triple("codec",   "Codec",    codecDisplay(it))) }
        if (libUntaggedAudio) add(Triple("untagged", "Audio", "Untagged only"))
        // R87: the handed-off content_row gap condition, as a normal (editable/removable) active chip.
        libCoverageCond?.let { c ->
            val names = c.rows.joinToString(", ") { it.title?.takeIf { t -> t.isNotBlank() } ?: "Untitled" }
            add(Triple("coverage", "Content row " + (if (c.op == "is_none_of") "is none of" else "is any of"), names.ifEmpty { "—" }))
        }
        libTracker?.let { add(Triple("tracker", "Seeded on", it)) }
        // Phase 117: a dashboard-breakdown deep link (?filter=<issue type>) shows as a normal removable
        // chip, same as any other filter — "attention"/"missing_artwork" keep their dedicated quick chips.
        libFilter?.let { f -> ISSUE_FILTER_LABELS[f]?.let { label -> add(Triple("issue:$f", "Issue", label)) } }
        // Phase 140 — the full-workbench tree, as one chip (✕ clears the whole thing).
        libQuery?.takeIf { it.isLive() }?.let { q ->
            add(Triple("query", "Advanced filter", groupSummary(q.toWbGroup(), top = true).ifBlank { "…" }))
        }
    }

    container.style.display = if (active.isEmpty()) "none" else "flex"
    // R101: show/hide the Clear filters button based on whether any filter is active.
    (document.getElementById("lib-clear") as? HTMLElement)?.style?.display =
        if (active.isEmpty() && libSearch == null && libFilter == null && libKind == null && libSort == null) "none" else "inline-flex"

    for ((key, prefix, label) in active) {
        val chip = document.createElement("span") as HTMLElement
        chip.className = "chip active-chip"
        chip.style.fontSize = ".8rem"
        chip.innerHTML = """<span style="opacity:.55;margin-right:3px">$prefix:</span>${label.esc()} <span style="margin-left:4px;opacity:.6;cursor:pointer;font-size:.9em">×</span>"""
        chip.querySelector("span:last-child")?.addEventListener("click") { _ ->
            when {
                key.startsWith("studio:")  -> libStudios  = libStudios  - key.removePrefix("studio:")
                key.startsWith("network:") -> libNetworks = libNetworks - key.removePrefix("network:")
                key.startsWith("genre:")   -> libGenres   = libGenres   - key.removePrefix("genre:")
                key.startsWith("tag:")     -> libTags = libTags - key.removePrefix("tag:")
                key.startsWith("lang:") -> libAudioLangs = libAudioLangs - key.removePrefix("lang:")
                key == "title"          -> libTrackTitle = null
                key == "codec"          -> libAudioCodec = null
                key == "untagged"       -> {
                    libUntaggedAudio = false
                    (document.getElementById("af-untagged") as? HTMLInputElement)?.checked = false
                }
                key == "coverage"       -> libCoverageCond = null  // R87
                key == "tracker"        -> libTracker = null
                key.startsWith("issue:") -> libFilter = null
                key == "query"          -> libQuery = null  // Phase 140
            }
            updateActiveChips(scope)
            scope?.launch { loadMore(scope, reset = true) }
        }
        container.appendChild(chip)
    }
}

// -- Scan -----------------------------------------------------------------

private suspend fun triggerScan(scope: CoroutineScope, full: Boolean = false) {
    val btn = document.getElementById("scan-btn") as? HTMLButtonElement ?: return
    if (btn.disabled) return
    val started = MediaApi.startScan(full)
    if (!started) {
        val banner = document.getElementById("scan-banner") as? HTMLElement ?: return
        banner.style.display = "block"
        banner.innerHTML = """<span class="badge bad">Scan is already running or failed to start.</span>"""
        return
    }
    libScannedCount = 0
    libPendingScanCount = 0
    setScanRunning(true)
    document.getElementById("poster-grid")?.innerHTML = ""
    libCardsById.clear()
    document.getElementById("lib-total")?.textContent = ""
    setLoadMore("")
    // Reset the scroller so the post-scan reload starts a fresh sequence.
    libSlice = 0; libLoadedCount = 0; libTotal = 0; libEndReached = false
    connectScanSocket(scope)
}

private fun isQueryActive() =
    libSearch != null || libFilter != null || libKind != null ||
    libStudios.isNotEmpty() || libNetworks.isNotEmpty() || libGenres.isNotEmpty() ||
    libTags.isNotEmpty() || libAudioLangs.isNotEmpty() || libTrackTitle != null ||
    libAudioCodec != null || libUntaggedAudio

private fun connectScanSocket(scope: CoroutineScope) {
    val proto = if (window.location.protocol == "https:") "wss" else "ws"
    val ws = WebSocket("$proto://${window.location.host}/ws")
    libScanSocket = ws

    ws.onmessage = { ev ->
        val text = ev.data.toString()
        runCatching {
            when (val event = scanJson.decodeFromString<JobEvent>(text)) {
                is JobEvent.ItemScanned -> {
                    libScannedCount++
                    if (isQueryActive()) {
                        libPendingScanCount++
                        updateScanBannerWithPending(libScannedCount, libPendingScanCount, scope)
                    } else {
                        appendItemToGrid(event.item, scope)
                        updateScanBannerCount(libScannedCount)
                    }
                }
                is JobEvent.Finished -> {
                    ws.close()
                    libScanSocket = null
                    setScanRunning(false)
                    val banner = document.getElementById("scan-banner") as? HTMLElement
                    banner?.style?.display = "block"
                    val n = event.succeeded
                    banner?.innerHTML = """<span class="badge ok">Scan complete — $n item${if (n != 1) "s" else ""} found.</span>"""
                    scope.launch { loadMore(scope, reset = true) }
                }
                else -> {}
            }
        }
    }
    ws.onclose = { _: Event -> if (libScanSocket == ws) libScanSocket = null }
}

private fun appendItemToGrid(item: MediaItem, scope: CoroutineScope) {
    val grid = document.getElementById("poster-grid") ?: return
    // Only the very first live-scanned item needs to remove the "Loading…"/placeholder text — once
    // libCardsById is non-empty it's already gone, so skip the (otherwise O(n)) querySelector.
    if (libCardsById.isEmpty()) grid.querySelector(".muted")?.remove()
    val id = item.jellyfinId ?: item.id
    val tmp = document.createElement("div")
    tmp.innerHTML = posterCardHtml(item)
    val newCard = tmp.firstElementChild ?: return
    val existing = libCardsById[id]
    if (existing != null) existing.replaceWith(newCard) else grid.appendChild(newCard)
    libCardsById[id] = newCard
}

private fun updateScanBannerCount(count: Int) {
    val banner = document.getElementById("scan-banner") as? HTMLElement ?: return
    banner.innerHTML = """<span class="badge">Scanning — $count item${if (count != 1) "s" else ""} found so far…</span>"""
}

private fun updateScanBannerWithPending(total: Int, pending: Int, scope: CoroutineScope) {
    val banner = document.getElementById("scan-banner") as? HTMLElement ?: return
    banner.style.display = "block"
    banner.innerHTML = """
        <span class="badge">Scanning — $total item${if (total != 1) "s" else ""} found so far ($pending new hidden by active filter)…</span>
        <button id="scan-refresh-btn" class="btn sm ghost" style="margin-left:8px">Refresh results ($pending new)</button>
    """.trimIndent()
    banner.querySelector("#scan-refresh-btn")?.addEventListener("click") {
        libPendingScanCount = 0
        scope.launch { loadMore(scope, reset = true) }
    }
}

private fun setScanRunning(running: Boolean) {
    val btn = document.getElementById("scan-btn") as? HTMLButtonElement
    val banner = document.getElementById("scan-banner") as? HTMLElement
    // The split button's caret + "full rescan" menu item aren't <button>s, so HTMLButtonElement.disabled
    // doesn't reach them — set/clear the disabled attribute by hand, matching Dashboard's split button.
    val menuBtn = (document.getElementById("scan-split") as? HTMLElement)?.querySelector(".menu-btn") as? HTMLElement
    val fullItem = document.getElementById("scan-full") as? HTMLElement
    for (el in listOfNotNull(menuBtn, fullItem)) {
        if (running) el.setAttribute("disabled", "") else el.removeAttribute("disabled")
    }
    if (running) {
        btn?.disabled = true
        btn?.textContent = "Scanning…"
        banner?.style?.display = "block"
        banner?.innerHTML = """<span class="badge">Scanning — items appear as they are processed.</span>"""
    } else {
        btn?.disabled = false
        btn?.textContent = "▶ Scan library"
    }
}

// -- Audio track filter panel ---------------------------------------------

private fun codecDisplay(codec: String): String = when (codec.lowercase()) {
    "aac"                           -> "AAC"
    "ac3"                           -> "AC-3 (Dolby Digital)"
    "eac3"                          -> "E-AC-3 (Dolby Digital+)"
    "dts"                           -> "DTS"
    "dts-hd", "dts_hd"             -> "DTS-HD"
    "truehd"                        -> "TrueHD (Dolby)"
    "mlp"                           -> "MLP (TrueHD)"
    "flac"                          -> "FLAC"
    "mp3"                           -> "MP3"
    "mp2"                           -> "MP2"
    "opus"                          -> "Opus"
    "vorbis"                        -> "Vorbis"
    "wmav2"                         -> "WMA v2"
    "wmapro"                        -> "WMA Pro"
    "pcm_s16le", "pcm_s24le",
    "pcm_s32le", "pcm_f32le"        -> "PCM"
    else                            -> codec.uppercase()
}

// -- Grid & infinite scroll -----------------------------------------------

/**
 * Loads library slices into `#poster-grid`. With [reset] = true it clears the grid and starts a fresh
 * sequence for the current filter/search/sort; otherwise it appends the next slice. After each slice
 * it keeps fetching while the sentinel is still near the viewport, so a partial last row is never
 * mistaken for the end. Re-entry is guarded by [libLoading]; the sentinel observer and explicit
 * resets all funnel through here.
 */
private suspend fun loadMore(scope: CoroutineScope, reset: Boolean) {
    if (libLoading) {
        if (reset) libPendingReset = true
        return
    }
    libLoading = true
    val grid = document.getElementById("poster-grid")
    if (reset) {
        updateLibraryUrl()
        libSlice = 0; libLoadedCount = 0; libTotal = 0; libEndReached = false
        grid?.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""
        libCardsById.clear()
        setLoadMore("")
    }
    try {
        // Bug fix: hoisted out of the slice loop below — this used to fire once per slice (2-3x on a
        // viewport that needs multiple slices to fill), even though the answer is identical across
        // every slice of the same filter/search.
        val issueType = libFilter?.let { ISSUE_FILTER_LABELS[it] }?.let { libFilter }
        val issueInstances = issueType?.let { MediaApi.getTriageCount()?.types?.firstOrNull { t -> t.key == issueType }?.instances }

        var firstSlice = reset
        while (!libEndReached) {
            if (!firstSlice) setLoadMore("""<span class="muted tiny">Loading more…</span>""")
            // R74: route through condition stack so ANY ORs correctly.
            val libConds = libConditionsFromState()
            val page = MediaApi.list(
                libKind, libFilter, libSearch, libSort, libSlice + 1, LIB_SLICE,
                libStudios, libNetworks, libGenres,
                libAudioLangs, libTrackTitle, libAudioCodec, libUntaggedAudio,
                libTags,
                match = libMatch,
                conditions = libConds.filter { it.values.isNotEmpty() || it.facet == "track_title" || (it.facet == "content_row" && it.rows.isNotEmpty()) }
                    .map { Condition(it.facet, it.op, it.values.toList(), it.rows.toList()) },
                // Phase 140 — the workbench's full tree, when present; takes priority in MediaApi.list
                // over the plain conditions/match above (the fast path stays for the common quick-
                // filter-only case, when this is null).
                query = libEffectiveQuery(),
                tracker = libTracker,
            )
            if (page == null) {
                if (firstSlice) grid?.innerHTML =
                    """<span class="muted" style="padding:24px;display:block;">Failed to load library.</span>"""
                else setLoadMore("""<span class="muted tiny">Failed to load more — scroll to retry.</span>""")
                return
            }

            libTotal = page.total
            // Phase 117: on a dashboard-breakdown deep link, show "N titles · M issues" — the instance
            // count (episode/track-level for untagged/missing_still) alongside the title count, so
            // "200 issues" and "3 titles" are both legible instead of looking contradictory.
            val instanceSuffix = if (issueInstances != null && issueInstances != page.total)
                " · $issueInstances issue${if (issueInstances != 1) "s" else ""}" else ""
            document.getElementById("lib-total")?.textContent =
                "${page.total} item${if (page.total != 1) "s" else ""}$instanceSuffix"

            if (firstSlice) {
                if (page.items.isEmpty()) {
                    grid?.innerHTML = """<span class="muted" style="padding:24px;display:block;">No items found.</span>"""
                } else {
                    grid?.innerHTML = page.items.joinToString("") { posterCardHtml(it) }
                    bindPosterClicks(grid)
                }
            } else if (page.items.isNotEmpty()) {
                appendPosterCards(grid, page.items)
            }

            libSlice++
            libLoadedCount += page.items.size
            libEndReached = page.items.isEmpty() || libLoadedCount >= page.total
            firstSlice = false

            // Stop once the viewport is satisfied; the sentinel observer resumes the sequence on scroll.
            if (libEndReached || !elemNearViewportBottom("lib-sentinel", 700)) break
        }
        setLoadMore("")
    } finally {
        libLoading = false
        if (libPendingReset) {
            libPendingReset = false
            scope.launch { loadMore(scope, reset = true) }
        }
    }
}

private fun setLoadMore(html: String) {
    (document.getElementById("lib-loadmore") as? HTMLElement)?.innerHTML = html
}

// Bug fix: no longer attaches a per-card click listener — clicks are handled by one delegated
// listener on #poster-grid (see renderLibrary). Still indexes each card into libCardsById.
private fun bindPosterClicks(grid: Element?) {
    grid ?: return
    grid.querySelectorAll(".poster[data-id]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            val id = el.getAttribute("data-id") ?: continue
            libCardsById[id] = el
        }
    }
}

private fun appendPosterCards(grid: Element?, items: List<MediaItem>) {
    grid ?: return
    val tmp = document.createElement("div")
    tmp.innerHTML = items.joinToString("") { posterCardHtml(it) }
    while (tmp.firstElementChild != null) {
        val card = tmp.firstElementChild ?: break
        val id = card.getAttribute("data-id")
        grid.appendChild(card)
        if (id != null) libCardsById[id] = card
    }
}

private fun posterCardHtml(item: MediaItem): String {
    val badge = when {
        item.languageMix  -> """<span class="badge warn" style="font-size:.62rem;">lang mix</span>"""
        item.issueCount > 0 -> """<span class="badge bad" style="font-size:.62rem;">${item.issueCount} issue${if (item.issueCount != 1) "s" else ""}</span>"""
        else              -> """<span class="badge ok" style="font-size:.62rem;">ok</span>"""
    }
    // Phase 184 (FR-184-8) — a small chip on the handful of hand-set titles; the automatic majority
    // stays unmarked (no chip at all when metadataLanguage is null).
    val langChip = item.metadataLanguage?.let {
        """ <span class="badge info lang" style="font-size:.62rem;" title="Metadata language manually set to ${it.esc()}">${it.esc()}</span>"""
    } ?: ""
    val imgContent = if (item.posterPath != null) {
        """<img src="${posterSrc(item.posterPath, TMDB_IMG)}" alt="${item.title.esc()}" loading="lazy"
             style="width:100%;height:100%;object-fit:cover;border-radius:4px 4px 0 0;">"""
    } else {
        // Bug fix: posterPath is only ever set from a TMDB match or a manual Artwork-tab save — a real
        // on-disk poster that arrived any other way (bundled with the original download, or Phase 171's
        // per-basename music-video convention) leaves this null even though art genuinely exists. Fall
        // back to the real serving route (the same one the Artwork tab's own "on disk" status comes
        // from) instead of assuming null means missing; a genuine miss swaps to the placeholder via the
        // delegated "error" listener wired in renderLibrary() (img error events don't bubble, only
        // capture, hence the capture-phase listener there rather than a plain click-style one here).
        """<img class="poster-fallback" src="/api/tv/image/${item.jellyfinId ?: item.id}/poster?w=320" alt="${item.title.esc()}" loading="lazy"
             style="width:100%;height:100%;object-fit:cover;border-radius:4px 4px 0 0;">"""
    }
    return """
        <div class="poster" data-id="${item.jellyfinId ?: item.id}" style="cursor:pointer;">
          <div class="imgslot">$imgContent</div>
          <div class="ttl">${item.title.esc()}</div>
          <div class="yr">${item.year ?: "—"} · $badge$langChip</div>
        </div>"""
}

internal fun String.esc(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

// ── R32: Library ↔ workbench round-trip ─────────────────────────────────────────

private fun libConditionsFromState(): List<WbCond> = buildList {
    if (libStudios.isNotEmpty()) add(WbCond("studio", "is_any_of", libStudios.toMutableList()))
    if (libNetworks.isNotEmpty()) add(WbCond("network", "is_any_of", libNetworks.toMutableList()))
    if (libGenres.isNotEmpty()) add(WbCond("genre", "is_any_of", libGenres.toMutableList()))
    if (libTags.isNotEmpty()) add(WbCond("tag", "is_any_of", libTags.toMutableList()))
    val al = libAudioLangs + if (libUntaggedAudio) listOf("untagged") else emptyList()
    if (al.isNotEmpty()) add(WbCond("audio_language", "is_any_of", al.toMutableList()))
    libAudioCodec?.let { add(WbCond("audio_codec", "is_any_of", mutableListOf(it))) }
    libTrackTitle?.let { add(WbCond("track_title", "contains", mutableListOf(it))) }
    // R87: the handed-off content_row gap condition joins the stack (grid + workbench see it).
    libCoverageCond?.let { add(WbCond(it.facet, it.op, it.values.toMutableList(), it.rows.toMutableList())) }
}

/** Phase 140 — the tree to actually send to /api/media: null when only the simple per-facet quick-
 *  filters are active (today's fast path, unaffected), else those AND'd with [libQuery] (the full-
 *  workbench tree, when it's been used). */
private fun libEffectiveQuery(): ConditionGroup? {
    val extra = libQuery?.takeIf { it.isLive() } ?: return null
    val liveConds = libConditionsFromState().filter { nodeLive(it) }.map { it.toShared() as Condition }
    return if (liveConds.isEmpty()) extra
        else ConditionGroup(QueryJoin.AND, children = listOf(migrateFlatQuery(MatchMode.ALL, liveConds), extra))
}

private fun libInclude(): String = when (libKind) {
    MediaKind.MOVIE -> "movies"; MediaKind.TV_SHOW -> "series"; MediaKind.MUSIC_VIDEO -> "musicvideos"; else -> "all"
}

// Phase 105: channel/content-row authoring now lives in the Ravilo config editor (per-user & global
// scope, R51+) — pushing a Library filter into a viewer's layout from here is no longer a supported
// flow, so this only wires the "Add filter" (apply-to-Library) path; the old "Save filter as…" round
// trip (onSaveAs → pickViewerThen → saveFilterToViewer) is gone.
private fun wireLibraryWorkbench(scope: CoroutineScope) {
    fun open(title: String) = openWorkbench(
        scope = scope, title = title, viewer = null,
        initialQuery = libEffectiveQuery() ?: migrateFlatQuery(MatchMode.ALL, libConditionsFromState().filter { nodeLive(it) }.map { it.toShared() as Condition }),
        initialInclude = libInclude(),
        applyLabel = "Apply to Library",
        // R87: offer the content_row facet (and let the handed-off condition be edited) using its own rows.
        rowsContext = libCoverageCond?.rows ?: emptyList(),
        onApply = { query, include -> applyWorkbenchToLibrary(query, include) },
    )
    document.getElementById("lib-workbench")?.addEventListener("click") { open("Library filter") }
}

/** Phase 140 — the workbench's full tree REPLACES the simple per-facet quick-filters entirely (see
 *  [libQuery]'s doc): clears them and carries the tree via the query= URL param, folding in what
 *  coverage= used to carry on its own. */
private fun applyWorkbenchToLibrary(query: ConditionGroup, include: String) {
    libStudios = emptyList(); libNetworks = emptyList(); libGenres = emptyList(); libTags = emptyList()
    libAudioLangs = emptyList(); libUntaggedAudio = false; libAudioCodec = null; libTrackTitle = null
    libCoverageCond = null; libMatch = "ALL"
    val params = buildList {
        when (include) { "movies" -> add("kind=MOVIE"); "series" -> add("kind=TV_SHOW"); "musicvideos" -> add("kind=MUSIC_VIDEO") }
        if (query.isLive()) add("query=${dev.jellystructure.encodeURIComponent(libCovJson.encodeToString(ConditionGroup.serializer(), query))}")
    }
    App.navigate(if (params.isEmpty()) "/library" else "/library?${params.joinToString("&")}")
}

