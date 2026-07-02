@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.jellystructure.ui

import dev.jellystructure.App
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.RaviloApi
import dev.jellystructure.shared.tv.ChannelConfig
import dev.jellystructure.shared.tv.Condition
import dev.jellystructure.shared.tv.MatchMode
import dev.jellystructure.shared.tv.RowConfig
import dev.jellystructure.shared.tv.RowKind
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
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.WebSocket
import org.w3c.dom.events.Event

private val scanJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }

private const val TMDB_IMG = "https://image.tmdb.org/t/p/w342"

private const val LIB_SLICE = 60  // items fetched per infinite-scroll slice
private var libSlice = 0          // slices loaded so far for the current filter set; next fetch = libSlice + 1
private var libLoadedCount = 0    // cards currently in the grid
private var libTotal = 0
private var libEndReached = false
private var libLoading = false
private var libKind: MediaKind? = null
private var libFilter: String? = null
// Phase 117: display labels for a dashboard-breakdown deep link's per-type `?filter=` value.
private val ISSUE_FILTER_LABELS = mapOf(
    "untagged" to "Untagged tracks",
    "cascade_mismatch" to "Wrong default audio",
    "multi_default" to "Multiple default audio",
    "language_mix" to "Mixed-language series",
    "missing_from_source" to "No longer in Jellyfin",
    "missing_overview" to "Missing overview",
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
}

private fun updateLibraryUrl() {
    val params = buildList<String> {
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
          <button id="scan-btn" class="btn primary">▶ Scan library</button>
        </div>
        <p class="page-sub">Everything Jellystructure manages. A red corner means at least one untagged track; an orange one means a mixed-language series. Click any title to open its detail page.</p>

        <div id="scan-banner" style="display:none;margin-bottom:14px"></div>

        <div class="row center" style="margin-bottom:6px;gap:8px;flex-wrap:wrap;">
          <input id="lib-search" class="input" type="search" placeholder="⌕ search title…" style="width:200px;flex-shrink:0;">
          <span class="muted tiny">filter:</span>
          <button id="f-all" class="chip">All</button>
          <button id="f-attention" class="chip">Needs attention</button>
          <button id="f-artwork" class="chip">Missing artwork</button>
          <button id="lib-workbench" class="chip">⚙ Add filter</button>
          <button id="lib-saveas" class="chip">★ Save filter as…</button>
          <button id="lib-clear" class="chip" style="display:none">✕ Clear filters</button>
          <span class="spacer" style="flex:1"></span>
          <select id="lib-sort" class="input" style="width:auto;font-size:.83rem;">
            <option value="">recently added ▾</option>
            <option value="title">title A–Z</option>
            <option value="year">year newest first</option>
          </select>
          <span class="seg" id="kindseg">
            <span id="k-all" class="on">All</span>
            <span id="k-movie">Movies</span>
            <span id="k-tv">TV</span>
          </span>
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
    val kindActive = when (libKind) { MediaKind.MOVIE -> "k-movie"; MediaKind.TV_SHOW -> "k-tv"; else -> "k-all" }
    listOf("k-all", "k-movie", "k-tv").forEach { id ->
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

    document.getElementById("lib-search")?.addEventListener("input") {
        val v = (document.getElementById("lib-search") as? HTMLInputElement)?.value?.trim()
        libSearch = if (v.isNullOrBlank()) null else v  // capture latest value immediately
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
    ).forEach { (id, setter) ->
        document.getElementById(id)?.addEventListener("click") {
            setter()
            listOf("k-all", "k-movie", "k-tv").forEach { btnId ->
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
        libCoverageCond = null; libTracker = null
        libKind = null; libSort = null; libMatch = "ALL"
        syncFilterUiToState(scope)
        scope.launch { loadMore(scope, reset = true) }
    }
}

// -- Active filter chips --------------------------------------------------

private fun updateActiveChips(scope: CoroutineScope? = null) {
    val container = document.getElementById("active-chips") as? HTMLElement ?: return
    container.innerHTML = ""

    val active = buildList<Triple<String, String, String>> {
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
            }
            updateActiveChips(scope)
            if (scope != null) scope.launch { loadMore(scope, reset = true) }
        }
        container.appendChild(chip)
    }
}

// -- Scan -----------------------------------------------------------------

private suspend fun triggerScan(scope: CoroutineScope) {
    val btn = document.getElementById("scan-btn") as? HTMLButtonElement ?: return
    if (btn.disabled) return
    val started = MediaApi.startScan()
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
            val event = scanJson.decodeFromString<JobEvent>(text)
            when (event) {
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
    grid.querySelector(".muted")?.remove()
    val existing = grid.querySelector(".poster[data-id=\"${item.jellyfinId ?: item.id}\"]")
    val html = posterCardHtml(item)
    if (existing != null) {
        val tmp = document.createElement("div")
        tmp.innerHTML = html
        val newCard = tmp.firstElementChild ?: return
        existing.replaceWith(newCard)
        (newCard as? HTMLElement)?.addEventListener("click") { App.navigate("/media/${item.jellyfinId ?: item.id}") }
    } else {
        val tmp = document.createElement("div")
        tmp.innerHTML = html
        val newCard = tmp.firstElementChild ?: return
        grid.appendChild(newCard)
        (newCard as? HTMLElement)?.addEventListener("click") { App.navigate("/media/${item.jellyfinId ?: item.id}") }
    }
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
    if (libLoading) return
    libLoading = true
    val grid = document.getElementById("poster-grid")
    if (reset) {
        updateLibraryUrl()
        libSlice = 0; libLoadedCount = 0; libTotal = 0; libEndReached = false
        grid?.innerHTML = """<span class="muted" style="padding:24px;display:block;">Loading…</span>"""
        setLoadMore("")
    }
    try {
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
                    .map { dev.jellystructure.shared.tv.Condition(it.facet, it.op, it.values.toList(), it.rows.toList()) },
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
            // count (episode/track-level for untagged/missing_overview) alongside the title count, so
            // "200 issues" and "3 titles" are both legible instead of looking contradictory.
            val issueType = libFilter?.let { ISSUE_FILTER_LABELS[it] }?.let { libFilter }
            val instanceSuffix = if (issueType != null) {
                val type = MediaApi.getTriageCount()?.types?.firstOrNull { it.key == issueType }
                if (type != null && type.instances != page.total) " · ${type.instances} issue${if (type.instances != 1) "s" else ""}" else ""
            } else ""
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
    }
}

private fun setLoadMore(html: String) {
    (document.getElementById("lib-loadmore") as? HTMLElement)?.innerHTML = html
}

private fun bindPosterClicks(grid: Element?) {
    grid ?: return
    grid.querySelectorAll(".poster[data-id]").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLElement ?: continue
            val id = el.getAttribute("data-id") ?: continue
            el.addEventListener("click") { App.navigate("/media/$id") }
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
        if (id != null) (card as? HTMLElement)?.addEventListener("click") { App.navigate("/media/$id") }
    }
}

private fun posterCardHtml(item: MediaItem): String {
    val badge = when {
        item.languageMix  -> """<span class="badge warn" style="font-size:.62rem;">lang mix</span>"""
        item.issueCount > 0 -> """<span class="badge bad" style="font-size:.62rem;">${item.issueCount} issue${if (item.issueCount != 1) "s" else ""}</span>"""
        else              -> """<span class="badge ok" style="font-size:.62rem;">ok</span>"""
    }
    val imgContent = if (item.posterPath != null) {
        """<img src="$TMDB_IMG${item.posterPath}" alt="${item.title.esc()}" loading="lazy"
             style="width:100%;height:100%;object-fit:cover;border-radius:4px 4px 0 0;">"""
    } else {
        """<div class="x"></div><span>${item.title.esc()}</span>"""
    }
    return """
        <div class="poster" data-id="${item.jellyfinId ?: item.id}" style="cursor:pointer;">
          <div class="imgslot">$imgContent</div>
          <div class="ttl">${item.title.esc()}</div>
          <div class="yr">${item.year ?: "—"} · $badge</div>
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

private fun libInclude(): String = when (libKind) {
    MediaKind.MOVIE -> "movies"; MediaKind.TV_SHOW -> "series"; else -> "all"
}

private fun wireLibraryWorkbench(scope: CoroutineScope) {
    fun open(title: String) = openWorkbench(
        scope = scope, title = title, viewer = null,
        initialMatch = libMatch, initialInclude = libInclude(), initialConds = libConditionsFromState(),
        applyLabel = "Apply to Library",
        // R87: offer the content_row facet (and let the handed-off condition be edited) using its own rows.
        rowsContext = libCoverageCond?.rows ?: emptyList(),
        onApply = { match, include, conds -> applyWorkbenchToLibrary(match, include, conds) },
        onSaveAs = { target, match, include, conds -> pickViewerThen(scope) { uid, name -> scope.launch { saveFilterToViewer(uid, name, target, match, include, conds) } } },
    )
    document.getElementById("lib-workbench")?.addEventListener("click") { open("Library filter") }
    document.getElementById("lib-saveas")?.addEventListener("click") { open("Save filter as…") }
}

/** Only the is_any_of / contains subset maps to the Library's URL filter; that is what the grid serves. */
private fun applyWorkbenchToLibrary(match: String, include: String, conds: List<WbCond>) {
    fun vals(f: String) = conds.filter { it.facet == f && it.op == "is_any_of" }.flatMap { it.values }.distinct()
    val params = buildList {
        when (include) { "movies" -> add("kind=MOVIE"); "series" -> add("kind=TV_SHOW") }
        vals("studio").takeIf { it.isNotEmpty() }?.let { add("studios=${it.joinToString(",") { v -> dev.jellystructure.encodeURIComponent(v) }}") }
        vals("network").takeIf { it.isNotEmpty() }?.let { add("networks=${it.joinToString(",") { v -> dev.jellystructure.encodeURIComponent(v) }}") }
        vals("genre").takeIf { it.isNotEmpty() }?.let { add("genres=${it.joinToString(",") { v -> dev.jellystructure.encodeURIComponent(v) }}") }
        vals("tag").takeIf { it.isNotEmpty() }?.let { add("tags=${it.joinToString(",") { v -> dev.jellystructure.encodeURIComponent(v) }}") }
        val al = vals("audio_language")
        al.filter { it != "untagged" }.takeIf { it.isNotEmpty() }?.let { add("audioLang=${it.joinToString(",")}") }
        if (al.contains("untagged")) add("untaggedAudio=true")
        vals("audio_codec").firstOrNull()?.let { add("audioCodec=$it") }
        conds.firstOrNull { it.facet == "track_title" && it.op == "contains" }?.values?.firstOrNull()?.let { add("trackTitle=${dev.jellystructure.encodeURIComponent(it)}") }
        if (match == "ANY") add("match=ANY")
        // R87: carry an edited content_row gap condition back into the URL so it survives the navigate.
        conds.firstOrNull { it.facet == "content_row" && it.rows.isNotEmpty() }?.let { c ->
            val cond = Condition(c.facet, c.op, c.values.toList(), c.rows.toList())
            add("coverage=${dev.jellystructure.encodeURIComponent(libCovJson.encodeToString(Condition.serializer(), cond))}")
        }
    }
    App.navigate(if (params.isEmpty()) "/library" else "/library?${params.joinToString("&")}")
}

private fun wbToConditions(conds: List<WbCond>): List<Condition> =
    conds.filter { it.values.isNotEmpty() || it.facet == "track_title" }.map { Condition(it.facet, it.op, it.values.toList()) }

private suspend fun saveFilterToViewer(userId: String, name: String, target: String, match: String, include: String, conds: List<WbCond>) {
    val cfg = runCatching { RaviloApi.getConfig(userId) }.getOrNull() ?: return
    val mode = if (match == "ANY") MatchMode.ANY else MatchMode.ALL
    val conditions = wbToConditions(conds)
    val label = conds.firstOrNull { it.values.isNotEmpty() }?.values?.firstOrNull() ?: "Custom filter"
    val mediaKind = when (include) { "movies" -> "MOVIE"; "series" -> "SERIES"; else -> null }
    val newCfg = if (target == "channel") {
        cfg.copy(channels = cfg.channels + ChannelConfig(id = "ch-${(0..999999).random()}", name = label, match = mode, conditions = conditions))
    } else {
        cfg.copy(rows = cfg.rows + RowConfig(id = "row-${(0..999999).random()}", kind = RowKind.CUSTOM, title = label, mediaKind = mediaKind, match = mode, conditions = conditions))
    }
    val ok = runCatching { RaviloApi.putConfig(userId, newCfg); true }.getOrDefault(false)
    libToast(if (ok) "Saved ${if (target == "channel") "channel" else "content row"} to ${name}'s layout." else "Save failed.")
}

private fun libToast(msg: String) {
    val banner = document.getElementById("scan-banner") as? HTMLElement ?: return
    banner.style.display = "block"
    banner.innerHTML = """<span class="badge ok">$msg</span>"""
}

/** Minimal viewer picker modal (radio list of Jellyfin users). */
private fun pickViewerThen(scope: CoroutineScope, onPick: (String, String) -> Unit) {
    scope.launch {
        val users = runCatching { RaviloApi.getUsers() }.getOrDefault(emptyList())
        if (users.isEmpty()) { libToast("No Jellyfin users available."); return@launch }
        val existing = document.getElementById("viewer-pick-overlay")
        existing?.parentElement?.removeChild(existing)
        val overlay = document.createElement("div") as HTMLElement
        overlay.id = "viewer-pick-overlay"
        overlay.setAttribute("style", "position:fixed;inset:0;background:#000a;display:flex;align-items:center;justify-content:center;z-index:1100;")
        val rows = users.joinToString("") { u ->
            """<label style="display:flex;gap:8px;align-items:center;padding:7px 4px;cursor:pointer;"><input type="radio" name="vp" value="${u.id}" data-name="${u.displayName.esc()}"> ${u.displayName.esc()}</label>"""
        }
        overlay.innerHTML = """
            <div style="background:var(--fill);color:var(--ink);border:1px solid var(--line);border-radius:14px;padding:18px;min-width:280px;box-shadow:var(--shadow);">
              <h3 style="margin:0 0 10px;">For which viewer?</h3>
              <div style="max-height:300px;overflow:auto;">$rows</div>
              <div class="row center" style="margin-top:14px;gap:8px;justify-content:flex-end;">
                <button id="vp-cancel" class="btn sm ghost">Cancel</button>
                <button id="vp-ok" class="btn sm">Save</button>
              </div>
            </div>"""
        document.body?.appendChild(overlay)
        document.getElementById("vp-cancel")?.addEventListener("click") { overlay.parentElement?.removeChild(overlay) }
        document.getElementById("vp-ok")?.addEventListener("click") {
            val sel = document.querySelector("#viewer-pick-overlay input[name=vp]:checked") as? HTMLInputElement
            if (sel != null) { onPick(sel.value, sel.getAttribute("data-name") ?: sel.value) }
            overlay.parentElement?.removeChild(overlay)
        }
    }
}
