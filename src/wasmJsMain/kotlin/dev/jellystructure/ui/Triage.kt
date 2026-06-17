package dev.jellystructure.ui

import dev.jellystructure.encodeURIComponent
import dev.jellystructure.api.MediaApi
import dev.jellystructure.api.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

@Serializable
data class TriageTrack(
    val specifier: String,
    val streamIndex: Int,
    val kind: String,
    val codec: String,
    val title: String? = null,
)

@Serializable
data class CascadeMismatch(
    val resolvedLanguage: String,
    val expectedDefaultSpecifier: String,
    val actualDefaultLang: String? = null,
)

@Serializable
data class EpisodeTriageItem(
    val filename: String,
    val episodeCode: String,
    val title: String? = null,
    val untaggedTracks: List<TriageTrack>,
    val missingOverview: Boolean,
)

@Serializable
data class TriageItem(
    val mediaId: String,
    val title: String,
    val year: Int? = null,
    val path: String,
    val kind: String = "movie",
    val posterPath: String? = null,
    val originalLanguage: String? = null,
    val untaggedTracks: List<TriageTrack>,
    val cascadeMismatch: CascadeMismatch? = null,
    val episodeIssues: List<EpisodeTriageItem> = emptyList(),
    val resolvedLanguage: String? = null,
    val languageMix: Boolean = false,
)

private val QUICK_LANGS = listOf("en", "da", "is", "fo")
private const val TMDB_IMG_TRIAGE = "https://image.tmdb.org/t/p/w185"

private var triageItems: List<TriageItem> = emptyList()
private var focusedIndex = 0
private var sessionCount = 0
private var triageScope: CoroutineScope? = null
private var triageContainer: Element? = null
private var triageViewMode = "focus"  // "focus" or "table"

fun renderTriage(container: Element, scope: CoroutineScope) {
    triageScope = scope
    triageContainer = container
    focusedIndex = 0
    sessionCount = 0

    container.innerHTML = buildTriageShell()

    container.querySelector("#view-toggle-btn")?.addEventListener("click") {
        triageViewMode = if (triageViewMode == "focus") "table" else "focus"
        updateViewToggleBtn()
        renderTriageList()
    }

    scope.launch { loadTriage() }

    (container as? HTMLElement)?.addEventListener("keydown") { ev ->
        val ke = ev as KeyboardEvent
        val activeTag = document.activeElement?.tagName?.lowercase()
        val inInput = activeTag == "input" || activeTag == "textarea"
        when (ke.key) {
            "ArrowDown", "ArrowRight" -> { ke.preventDefault(); moveFocus(1) }
            "ArrowUp", "ArrowLeft"    -> { ke.preventDefault(); moveFocus(-1) }
            "Enter" -> {
                if (!inInput) {
                    if (triageViewMode == "focus") acceptSuggestion() else focusFirstInput()
                }
            }
            "1" -> if (triageViewMode == "focus" && !inInput) { ke.preventDefault(); quickAssignByIndex(0, QUICK_LANGS[0]) }
            "2" -> if (triageViewMode == "focus" && !inInput) { ke.preventDefault(); quickAssignByIndex(0, QUICK_LANGS[1]) }
            "3" -> if (triageViewMode == "focus" && !inInput) { ke.preventDefault(); quickAssignByIndex(0, QUICK_LANGS[2]) }
            "4" -> if (triageViewMode == "focus" && !inInput) { ke.preventDefault(); quickAssignByIndex(0, QUICK_LANGS[3]) }
        }
    }
    (container as? HTMLElement)?.setAttribute("tabindex", "-1")
}

private fun buildTriageShell(): String = """
    <div class="p-6">
      <div class="flex items-center justify-between mb-6">
        <div>
          <h1 class="text-2xl font-bold text-white">Triage Queue</h1>
          <p class="text-sm text-slate-400 mt-1">Tracks missing a language tag — assign to fix.</p>
        </div>
        <div class="flex items-center gap-3">
          <div id="triage-count" class="text-sm text-slate-400"></div>
          <button id="view-toggle-btn" class="text-xs bg-slate-700 hover:bg-slate-600 text-slate-300 px-3 py-1.5 rounded">
            ${if (triageViewMode == "focus") "Switch to table view" else "Switch to focus queue"}
          </button>
        </div>
      </div>
      <div id="triage-hint" class="mb-4 text-xs text-slate-500">
        Use <kbd class="bg-slate-700 px-1 rounded">↑</kbd> / <kbd class="bg-slate-700 px-1 rounded">↓</kbd> to navigate &nbsp;·&nbsp;
        <kbd class="bg-slate-700 px-1 rounded">Enter</kbd> to focus input in selected item
      </div>
      <div id="bulk-bar" class="hidden flex items-center gap-3 mb-4 p-3 bg-slate-800 rounded-lg border border-slate-700">
        <span class="text-xs text-slate-400 font-medium">Bulk assign all untagged tracks:</span>
        <input id="bulk-lang-input"
          class="bg-slate-900 border border-slate-600 rounded px-2 py-1 text-xs text-white w-24 focus:outline-none focus:ring-1 focus:ring-blue-500"
          placeholder="e.g. eng" maxlength="10">
        <button id="bulk-assign-btn"
          class="bg-blue-600 hover:bg-blue-700 text-white text-xs px-3 py-1 rounded">
          Assign all
        </button>
        <span id="bulk-result" class="text-xs"></span>
      </div>
      <div id="triage-list" class="space-y-3"></div>
      <div id="triage-empty" class="hidden text-center py-16 text-slate-500">
        <div class="text-4xl mb-3">✓</div>
        <div class="text-lg font-medium text-slate-300">No untagged tracks</div>
        <div class="text-sm mt-1">All tracks have language assignments.</div>
      </div>
    </div>
""".trimIndent()

private fun updateViewToggleBtn() {
    val btn = triageContainer?.querySelector("#view-toggle-btn") as? HTMLElement ?: return
    btn.textContent = if (triageViewMode == "focus") "Switch to table view" else "Switch to focus queue"
}

private suspend fun loadTriage() {
    val container = triageContainer ?: return
    val raw = fetchTriage() ?: run {
        container.querySelector("#triage-list")?.innerHTML =
            """<div class="text-red-400 text-sm">Failed to load triage queue.</div>"""
        return
    }
    triageItems = raw
    renderTriageList()
}

private fun renderTriageList() {
    val container = triageContainer ?: return
    val list = container.querySelector("#triage-list") ?: return
    val empty = container.querySelector("#triage-empty")
    val count = container.querySelector("#triage-count")

    val bulkBar = container.querySelector("#bulk-bar")
    if (triageItems.isEmpty()) {
        list.innerHTML = ""
        empty?.classList?.remove("hidden")
        count?.textContent = ""
        bulkBar?.classList?.add("hidden")
        return
    }
    empty?.classList?.add("hidden")
    count?.textContent = "${triageItems.size} item${if (triageItems.size != 1) "s" else ""} need attention"

    val totalUntagged = triageItems.sumOf { it.untaggedTracks.size }
    if (totalUntagged > 0) {
        bulkBar?.classList?.remove("hidden")
        wireUpBulkAssign()
    } else {
        bulkBar?.classList?.add("hidden")
    }

    if (triageViewMode == "focus") {
        renderFocusQueue(list)
        return
    }

    renderTableView(list)
}

private fun renderFocusQueue(list: Element) {
    val item = triageItems.getOrNull(focusedIndex) ?: return
    val total = triageItems.size
    val pct = if (total > 0) ((focusedIndex + 1) * 100 / total) else 100

    val navPrev = if (focusedIndex > 0)
        """<button id="focus-prev" class="bg-slate-700 hover:bg-slate-600 text-white text-xs px-3 py-1.5 rounded">‹ Previous</button>"""
    else
        """<button class="bg-slate-800 text-slate-600 text-xs px-3 py-1.5 rounded cursor-not-allowed" disabled>‹ Previous</button>"""
    val navNext = if (focusedIndex < total - 1)
        """<button id="focus-next" class="bg-slate-700 hover:bg-slate-600 text-white text-xs px-3 py-1.5 rounded">Next ›</button>"""
    else
        """<button class="bg-slate-800 text-slate-600 text-xs px-3 py-1.5 rounded cursor-not-allowed" disabled>Next ›</button>"""

    val progressHtml = """
        <div class="w-full bg-slate-700 rounded-full mb-3" style="height:3px">
          <div class="bg-blue-500 rounded-full" style="height:3px;width:${pct}%;transition:width 0.3s"></div>
        </div>
    """.trimIndent()

    if (item.kind == "tv") {
        list.innerHTML = """
            $progressHtml
            <div class="flex items-center gap-3 mb-3 text-xs text-slate-500">
              $navPrev
              <span>Item ${focusedIndex + 1} of $total</span>
              $navNext
            </div>
            ${seriesTriageCardHtml(item, true)}
        """.trimIndent()
        list.querySelector("#focus-prev")?.addEventListener("click") { moveFocus(-1) }
        list.querySelector("#focus-next")?.addEventListener("click") { moveFocus(1) }
        wireSeriesTriageButtons(list)
        return
    }

    val posterHtml = if (item.posterPath != null) {
        """<img src="$TMDB_IMG_TRIAGE${item.posterPath}" alt=""
             class="rounded flex-shrink-0" style="width:68px;height:102px;object-fit:cover;">"""
    } else {
        """<div class="flex-shrink-0 rounded bg-slate-700 flex items-center justify-center"
              style="width:68px;height:102px;">
             <span class="text-slate-600 text-xs">no art</span>
           </div>"""
    }

    val tracksHtml = item.untaggedTracks.joinToString("") { track ->
        val kindBadge = when (track.kind) {
            "audio"    -> """<span class="bg-blue-900 text-blue-300 text-xs px-2 py-0.5 rounded font-mono">audio</span>"""
            "subtitle" -> """<span class="bg-purple-900 text-purple-300 text-xs px-2 py-0.5 rounded font-mono">sub</span>"""
            else       -> """<span class="bg-slate-700 text-slate-300 text-xs px-2 py-0.5 rounded font-mono">${track.kind}</span>"""
        }
        val label = track.title?.let { ", $it" } ?: ""
        val pillsHtml = QUICK_LANGS.mapIndexed { i, lang ->
            """<button class="lang-pill-btn bg-slate-700 hover:bg-blue-600 text-slate-300 hover:text-white text-xs px-2 py-0.5 rounded"
                 data-media-id="${item.mediaId}" data-specifier="${track.specifier}" data-lang="$lang">
                 <span style="font-size:0.6rem;opacity:0.6;margin-right:1px">${i + 1}</span>$lang
               </button>"""
        }.joinToString("")
        """
        <div class="flex items-center gap-2 py-2 border-t border-slate-700 flex-wrap">
          $kindBadge
          <span class="text-xs text-slate-400 font-mono flex-1 min-w-0 truncate">#${track.streamIndex} ${track.codec}$label</span>
          <div class="flex gap-1 items-center flex-wrap">
            $pillsHtml
            <input class="triage-lang-input bg-slate-800 border border-slate-600 rounded px-2 py-0.5 text-xs text-white w-20 focus:outline-none focus:ring-1 focus:ring-blue-500"
              placeholder="other…"
              data-media-id="${item.mediaId}" data-specifier="${track.specifier}" maxlength="10">
            <button class="triage-assign-btn bg-slate-600 hover:bg-blue-600 text-white text-xs px-2 py-0.5 rounded"
              data-media-id="${item.mediaId}" data-specifier="${track.specifier}">→</button>
            <button class="remove-track-btn bg-red-950 hover:bg-red-800 text-red-400 hover:text-red-200 text-xs px-2 py-0.5 rounded"
              data-media-id="${item.mediaId}" data-specifier="${track.specifier}" title="Remove track (ffmpeg remux)">✕</button>
          </div>
          <span class="triage-result text-xs w-full" data-specifier="${track.specifier}"></span>
        </div>
        """.trimIndent()
    }

    val mismatchHtml = item.cascadeMismatch?.let { m ->
        val fromLang = m.actualDefaultLang ?: "none"
        """
        <div class="flex items-center gap-3 py-2 border-t border-slate-700">
          <span class="bg-yellow-900 text-yellow-300 text-xs px-2 py-0.5 rounded font-mono">cascade</span>
          <span class="text-xs text-slate-400 flex-1">default should be
            <strong class="text-yellow-300">${m.resolvedLanguage}</strong>,
            current default is <strong class="text-slate-300">$fromLang</strong></span>
          <button class="cascade-fix-btn bg-yellow-700 hover:bg-yellow-600 text-white text-xs px-3 py-1 rounded"
            data-media-id="${item.mediaId}" data-specifier="${m.expectedDefaultSpecifier}">Fix default</button>
          <span class="cascade-result text-xs" data-media-id="${item.mediaId}"></span>
        </div>
        """.trimIndent()
    } ?: ""

    val suggestion = item.originalLanguage
    val suggestionHtml = if (suggestion != null) {
        """<div class="mt-3 text-xs text-slate-400 bg-slate-900 rounded px-3 py-2 border border-slate-700">
             TMDB original language: <span class="text-blue-300 font-mono font-semibold">$suggestion</span>
             <span class="text-slate-600 ml-2">— press <kbd class="bg-slate-700 px-1 rounded">Enter</kbd> to apply to first track</span>
           </div>"""
    } else ""

    val upNextItems = triageItems.drop(focusedIndex + 1).take(3)
    val upNextHtml = if (upNextItems.isNotEmpty()) """
        <div class="text-xs text-slate-500 uppercase tracking-wider mb-2">Up next</div>
        ${upNextItems.mapIndexed { i, next ->
            """<div class="text-xs text-slate-400 py-1.5 border-b border-slate-700/50 truncate" title="${next.title.esc()}">
                 <span class="text-slate-600 mr-1">${focusedIndex + i + 2}.</span>${next.title.esc()}${next.year?.let { " ($it)" } ?: ""}
               </div>"""
        }.joinToString("")}
    """.trimIndent() else ""

    val sessionHtml = """
        <div class="mt-4 text-xs text-slate-500">
          This session<br>
          <span class="text-white font-bold" style="font-size:1.4rem">$sessionCount</span>
          <span class="text-slate-500 text-xs"> fixed</span>
        </div>
    """.trimIndent()

    val hintHtml = """
        <div class="text-xs text-slate-600 mt-3">
          Keys: <kbd class="bg-slate-800 px-1 rounded">1</kbd>–<kbd class="bg-slate-800 px-1 rounded">4</kbd> quick-assign first track &nbsp;·&nbsp;
          <kbd class="bg-slate-800 px-1 rounded">Enter</kbd> accept TMDB suggestion &nbsp;·&nbsp;
          <kbd class="bg-slate-800 px-1 rounded">←</kbd><kbd class="bg-slate-800 px-1 rounded">→</kbd> navigate
        </div>
    """.trimIndent()

    list.innerHTML = """
        $progressHtml
        <div class="flex items-center gap-3 mb-3 text-xs text-slate-500">
          $navPrev
          <span>Item ${focusedIndex + 1} of $total</span>
          $navNext
        </div>
        <div class="flex gap-4 items-start">
          <div class="flex-1 min-w-0">
            <div class="triage-item bg-slate-800 rounded-lg p-4 ring-2 ring-blue-500">
              <div class="flex gap-3">
                $posterHtml
                <div class="flex-1 min-w-0">
                  <div class="flex items-start justify-between mb-1">
                    <div>
                      <span class="font-semibold text-white">${item.title.esc()}</span>
                      ${item.year?.let { """<span class="text-slate-400 text-sm ml-2">($it)</span>""" } ?: ""}
                    </div>
                    <span class="text-xs text-slate-500 font-mono truncate ml-4" title="${item.path.esc()}">${item.path.substringAfterLast('/').esc()}</span>
                  </div>
                  <div class="mt-2">$tracksHtml$mismatchHtml</div>
                  $suggestionHtml
                </div>
              </div>
            </div>
            $hintHtml
          </div>
          <div class="w-44 flex-shrink-0">
            $upNextHtml
            $sessionHtml
          </div>
        </div>
    """.trimIndent()

    list.querySelector("#focus-prev")?.addEventListener("click") { moveFocus(-1) }
    list.querySelector("#focus-next")?.addEventListener("click") { moveFocus(1) }

    wireAssignButtons(list)
    wireCascadeButtons(list)
    wireLanguageInputs(list)
    wireLanguagePillButtons(list)
    wireRemoveTrackButtons(list)
}

private fun renderTableView(list: Element) {
    val triageItems = triageItems
    list.innerHTML = triageItems.mapIndexed { idx, item ->
        val isActive = idx == focusedIndex

        if (item.kind == "tv") return@mapIndexed seriesTriageCardHtml(item, isActive)

        val activeClass = if (isActive) "ring-2 ring-blue-500" else ""

        val tracksHtml = item.untaggedTracks.joinToString("") { track ->
            val kindBadge = when (track.kind) {
                "audio"    -> """<span class="bg-blue-900 text-blue-300 text-xs px-2 py-0.5 rounded font-mono">audio</span>"""
                "subtitle" -> """<span class="bg-purple-900 text-purple-300 text-xs px-2 py-0.5 rounded font-mono">sub</span>"""
                else       -> """<span class="bg-slate-700 text-slate-300 text-xs px-2 py-0.5 rounded font-mono">${track.kind}</span>"""
            }
            val label = track.title?.let { ", $it" } ?: ""
            """
            <div class="flex items-center gap-3 py-2 border-t border-slate-700">
              $kindBadge
              <span class="text-xs text-slate-400 font-mono flex-1">#${track.streamIndex} ${track.codec}$label</span>
              <input
                class="triage-lang-input bg-slate-800 border border-slate-600 rounded px-2 py-1 text-xs text-white w-24 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="e.g. eng"
                data-media-id="${item.mediaId}"
                data-specifier="${track.specifier}"
                maxlength="10"
              />
              <button
                class="triage-assign-btn bg-blue-600 hover:bg-blue-700 text-white text-xs px-3 py-1 rounded"
                data-media-id="${item.mediaId}"
                data-specifier="${track.specifier}"
              >Assign</button>
              <span class="triage-result text-xs" data-specifier="${track.specifier}"></span>
            </div>
            """.trimIndent()
        }

        val mismatchHtml = item.cascadeMismatch?.let { m ->
            val fromLang = m.actualDefaultLang ?: "none"
            """
            <div class="flex items-center gap-3 py-2 border-t border-slate-700">
              <span class="bg-yellow-900 text-yellow-300 text-xs px-2 py-0.5 rounded font-mono">cascade</span>
              <span class="text-xs text-slate-400 flex-1">default should be
                <strong class="text-yellow-300">${m.resolvedLanguage}</strong>,
                current default is <strong class="text-slate-300">$fromLang</strong></span>
              <button
                class="cascade-fix-btn bg-yellow-700 hover:bg-yellow-600 text-white text-xs px-3 py-1 rounded"
                data-media-id="${item.mediaId}"
                data-specifier="${m.expectedDefaultSpecifier}"
              >Fix default</button>
              <span class="cascade-result text-xs" data-media-id="${item.mediaId}"></span>
            </div>
            """.trimIndent()
        } ?: ""

        """
        <div class="triage-item bg-slate-800 rounded-lg p-4 cursor-pointer $activeClass" data-idx="$idx">
          <div class="flex items-start justify-between mb-1">
            <div>
              <span class="font-semibold text-white">${item.title}</span>
              ${item.year?.let { """<span class="text-slate-400 text-sm ml-2">($it)</span>""" } ?: ""}
            </div>
            <span class="text-xs text-slate-500 font-mono truncate max-w-xs ml-4" title="${item.path}">${item.path.substringAfterLast('/')}</span>
          </div>
          <div class="mt-2">$tracksHtml$mismatchHtml</div>
        </div>
        """.trimIndent()
    }.joinToString("")

    // Attach click listeners to items for focus change
    list.querySelectorAll(".triage-item").let { nodes ->
        for (idx in 0 until nodes.length) {
            val el = nodes.item(idx) as? HTMLElement ?: continue
            val capturedIdx = idx
            el.addEventListener("click") { ev ->
                val target = ev.target as? HTMLElement
                if (target?.tagName?.lowercase() !in listOf("input", "button")) {
                    focusedIndex = capturedIdx
                    renderTriageList()
                    (triageContainer as? HTMLElement)?.focus()
                }
            }
        }
    }

    wireAssignButtons(list)
    wireCascadeButtons(list)
    wireLanguageInputs(list)
    wireSeriesTriageButtons(list)
}

private fun wireAssignButtons(list: Element) {
    list.querySelectorAll(".triage-assign-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val mediaId = btn.getAttribute("data-media-id") ?: return@addEventListener
                val specifier = btn.getAttribute("data-specifier") ?: return@addEventListener
                val input = list.querySelector(
                    ".triage-lang-input[data-media-id=\"$mediaId\"][data-specifier=\"$specifier\"]"
                ) as? HTMLInputElement ?: return@addEventListener
                val lang = input.value.trim()
                if (lang.isBlank()) { input.focus(); return@addEventListener }
                val resultEl = list.querySelector(".triage-result[data-specifier=\"$specifier\"]")
                resultEl?.textContent = "…"
                triageScope?.launch {
                    val ok = assignLanguage(mediaId, specifier, lang)
                    if (ok) {
                        resultEl?.textContent = "✓"
                        resultEl?.setAttribute("class", "triage-result text-xs text-green-400")
                        triageItems = triageItems.mapNotNull { item ->
                            if (item.mediaId != mediaId) return@mapNotNull item
                            val remaining = item.untaggedTracks.filter { it.specifier != specifier }
                            if (remaining.isEmpty()) null else item.copy(untaggedTracks = remaining)
                        }
                        if (focusedIndex >= triageItems.size) focusedIndex = maxOf(0, triageItems.size - 1)
                        renderTriageList()
                    } else {
                        resultEl?.textContent = "✗ failed"
                        resultEl?.setAttribute("class", "triage-result text-xs text-red-400")
                    }
                }
            }
        }
    }
}

private fun wireLanguageInputs(list: Element) {
    list.querySelectorAll(".triage-lang-input").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLInputElement ?: continue
            el.addEventListener("keydown") { ev ->
                if ((ev as KeyboardEvent).key == "Enter") {
                    ev.preventDefault()
                    val mediaId = el.getAttribute("data-media-id") ?: return@addEventListener
                    val specifier = el.getAttribute("data-specifier") ?: return@addEventListener
                    list.querySelector(
                        ".triage-assign-btn[data-media-id=\"$mediaId\"][data-specifier=\"$specifier\"]"
                    )?.let { (it as? HTMLElement)?.click() }
                }
            }
        }
    }
}

private fun wireLanguagePillButtons(list: Element) {
    list.querySelectorAll(".lang-pill-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val mediaId = btn.getAttribute("data-media-id") ?: return@addEventListener
                val specifier = btn.getAttribute("data-specifier") ?: return@addEventListener
                val lang = btn.getAttribute("data-lang") ?: return@addEventListener
                val resultEl = list.querySelector(".triage-result[data-specifier=\"$specifier\"]")
                resultEl?.textContent = "…"
                triageScope?.launch {
                    val ok = assignLanguage(mediaId, specifier, lang)
                    if (ok) {
                        sessionCount++
                        resultEl?.textContent = "✓ $lang"
                        resultEl?.setAttribute("class", "triage-result text-xs text-green-400 w-full")
                        triageItems = triageItems.mapNotNull { item ->
                            if (item.mediaId != mediaId) return@mapNotNull item
                            val remaining = item.untaggedTracks.filter { t -> t.specifier != specifier }
                            if (remaining.isEmpty()) null else item.copy(untaggedTracks = remaining)
                        }
                        if (focusedIndex >= triageItems.size) focusedIndex = maxOf(0, triageItems.size - 1)
                        renderTriageList()
                    } else {
                        resultEl?.textContent = "✗ failed"
                        resultEl?.setAttribute("class", "triage-result text-xs text-red-400 w-full")
                    }
                }
            }
        }
    }
}

private fun wireRemoveTrackButtons(list: Element) {
    list.querySelectorAll(".remove-track-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val mediaId = btn.getAttribute("data-media-id") ?: return@addEventListener
                val specifier = btn.getAttribute("data-specifier") ?: return@addEventListener
                if (!window.confirm("Remove track $specifier? This requires an ffmpeg remux and cannot be undone.")) return@addEventListener
                btn.setAttribute("disabled", "true")
                btn.textContent = "…"
                triageScope?.launch {
                    val ok = MediaApi.removeTrack(mediaId, specifier)
                    if (ok) {
                        triageItems = triageItems.mapNotNull { item ->
                            if (item.mediaId != mediaId) return@mapNotNull item
                            val remaining = item.untaggedTracks.filter { t -> t.specifier != specifier }
                            if (remaining.isEmpty()) null else item.copy(untaggedTracks = remaining)
                        }
                        if (focusedIndex >= triageItems.size) focusedIndex = maxOf(0, triageItems.size - 1)
                        renderTriageList()
                    } else {
                        btn.removeAttribute("disabled")
                        btn.textContent = "✕"
                    }
                }
            }
        }
    }
}

private fun wireCascadeButtons(list: Element) {
    list.querySelectorAll(".cascade-fix-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val mediaId = btn.getAttribute("data-media-id") ?: return@addEventListener
                val specifier = btn.getAttribute("data-specifier") ?: return@addEventListener
                val resultEl = list.querySelector(".cascade-result[data-media-id=\"$mediaId\"]")
                resultEl?.textContent = "…"
                triageScope?.launch {
                    val ok = fixCascadeDefault(mediaId, specifier)
                    if (ok) {
                        resultEl?.textContent = "✓ fixed"
                        resultEl?.setAttribute("class", "cascade-result text-xs text-green-400")
                        triageItems = triageItems.mapNotNull { item ->
                            if (item.mediaId != mediaId) return@mapNotNull item
                            if (item.untaggedTracks.isEmpty()) null else item.copy(cascadeMismatch = null)
                        }
                        if (focusedIndex >= triageItems.size) focusedIndex = maxOf(0, triageItems.size - 1)
                        renderTriageList()
                    } else {
                        resultEl?.textContent = "✗ failed"
                        resultEl?.setAttribute("class", "cascade-result text-xs text-red-400")
                    }
                }
            }
        }
    }
}

private fun wireUpBulkAssign() {
    val container = triageContainer ?: return
    val btn = container.querySelector("#bulk-assign-btn") as? HTMLElement ?: return
    // Replace the button to remove any previous listener
    val newBtn = btn.cloneNode(true) as HTMLElement
    btn.parentNode?.replaceChild(newBtn, btn)
    newBtn.addEventListener("click") {
        val input = container.querySelector("#bulk-lang-input") as? HTMLInputElement ?: return@addEventListener
        val lang = input.value.trim()
        if (lang.isBlank()) { input.focus(); return@addEventListener }
        val resultEl = container.querySelector("#bulk-result") as? HTMLElement
        resultEl?.textContent = "Working…"
        resultEl?.setAttribute("class", "text-xs text-slate-400")
        newBtn.setAttribute("disabled", "true")
        triageScope?.launch {
            var ok = 0
            var fail = 0
            for (item in triageItems) {
                // Handle movie tracks
                for (track in item.untaggedTracks) {
                    if (assignLanguage(item.mediaId, track.specifier, lang)) ok++ else fail++
                }
                // Handle TV episode tracks
                for (ep in item.episodeIssues) {
                    for (track in ep.untaggedTracks) {
                        if (assignEpisodeLanguage(item.mediaId, ep.filename, track.specifier, lang)) ok++ else fail++
                    }
                }
            }
            // Reload fresh state from server — backend re-probed all files
            val fresh = fetchTriage()
            if (fresh != null) {
                triageItems = fresh
                if (focusedIndex >= triageItems.size) focusedIndex = maxOf(0, triageItems.size - 1)
            }
            resultEl?.textContent = if (fail == 0) "✓ $ok assigned" else "✓ $ok done, $fail failed"
            resultEl?.setAttribute("class", "text-xs ${if (fail == 0) "text-green-400" else "text-yellow-400"}")
            newBtn.removeAttribute("disabled")
            renderTriageList()
        }
    }
}

private fun moveFocus(delta: Int) {
    if (triageItems.isEmpty()) return
    focusedIndex = (focusedIndex + delta).coerceIn(0, triageItems.size - 1)
    renderTriageList()
    // Scroll the focused item into view
    triageContainer?.querySelector(".triage-item[data-idx=\"$focusedIndex\"]")
        ?.let { (it as? HTMLElement)?.scrollIntoView() }
}

private fun focusFirstInput() {
    val item = triageContainer?.querySelector(".triage-item[data-idx=\"$focusedIndex\"]")
    (item?.querySelector(".triage-lang-input") as? HTMLInputElement)?.focus()
}

private fun quickAssignByIndex(trackIndex: Int, lang: String) {
    val item = triageItems.getOrNull(focusedIndex) ?: return
    val track = item.untaggedTracks.getOrNull(trackIndex) ?: return
    val mediaId = item.mediaId
    val specifier = track.specifier
    triageScope?.launch {
        val ok = assignLanguage(mediaId, specifier, lang)
        if (ok) {
            sessionCount++
            triageItems = triageItems.mapNotNull { item2 ->
                if (item2.mediaId != mediaId) return@mapNotNull item2
                val remaining = item2.untaggedTracks.filter { t -> t.specifier != specifier }
                if (remaining.isEmpty()) null else item2.copy(untaggedTracks = remaining)
            }
            if (focusedIndex >= triageItems.size) focusedIndex = maxOf(0, triageItems.size - 1)
            renderTriageList()
        }
    }
}

private fun acceptSuggestion() {
    val item = triageItems.getOrNull(focusedIndex) ?: return
    val suggestion = item.originalLanguage ?: return
    val track = item.untaggedTracks.firstOrNull() ?: return
    val mediaId = item.mediaId
    val specifier = track.specifier
    triageScope?.launch {
        val ok = assignLanguage(mediaId, specifier, suggestion)
        if (ok) {
            sessionCount++
            triageItems = triageItems.mapNotNull { item2 ->
                if (item2.mediaId != mediaId) return@mapNotNull item2
                val remaining = item2.untaggedTracks.filter { t -> t.specifier != specifier }
                if (remaining.isEmpty()) null else item2.copy(untaggedTracks = remaining)
            }
            if (focusedIndex >= triageItems.size) focusedIndex = maxOf(0, triageItems.size - 1)
            renderTriageList()
        }
    }
}

private fun seriesTriageCardHtml(item: TriageItem, isActive: Boolean): String {
    val activeClass = if (isActive) "ring-2 ring-blue-500" else ""
    val langBadge = when {
        item.languageMix -> """<span class="bg-yellow-900 text-yellow-300 text-xs px-2 py-0.5 rounded">Mixed languages</span>"""
        !item.resolvedLanguage.isNullOrBlank() -> """<span class="bg-blue-900 text-blue-300 text-xs px-2 py-0.5 rounded font-mono">${item.resolvedLanguage}</span>"""
        else -> """<span class="bg-slate-700 text-slate-400 text-xs px-2 py-0.5 rounded">No language</span>"""
    }

    val episodesHtml = item.episodeIssues.joinToString("") { ep ->
        val tracksHtml = ep.untaggedTracks.joinToString("") { track ->
            val kindBadge = when (track.kind) {
                "audio"    -> """<span class="bg-blue-900 text-blue-300 text-xs px-2 py-0.5 rounded font-mono">audio</span>"""
                "subtitle" -> """<span class="bg-purple-900 text-purple-300 text-xs px-2 py-0.5 rounded font-mono">sub</span>"""
                else       -> """<span class="bg-slate-700 text-slate-300 text-xs px-2 py-0.5 rounded font-mono">${track.kind}</span>"""
            }
            val label = track.title?.let { ", $it" } ?: ""
            """<div class="flex items-center gap-3 py-1.5">
                 $kindBadge
                 <span class="text-xs text-slate-400 font-mono flex-1">#${track.streamIndex} ${track.codec}$label</span>
                 <input class="ep-triage-lang-input bg-slate-800 border border-slate-600 rounded px-2 py-1 text-xs text-white w-24 focus:outline-none focus:ring-1 focus:ring-blue-500"
                   placeholder="e.g. eng"
                   data-media-id="${item.mediaId}"
                   data-ep-filename="${ep.filename.esc()}"
                   data-specifier="${track.specifier.esc()}"
                   maxlength="10">
                 <button class="ep-triage-assign-btn bg-blue-600 hover:bg-blue-700 text-white text-xs px-3 py-1 rounded"
                   data-media-id="${item.mediaId}"
                   data-ep-filename="${ep.filename.esc()}"
                   data-specifier="${track.specifier.esc()}">Assign</button>
                 <span class="ep-triage-result text-xs" data-ep-filename="${ep.filename.esc()}" data-specifier="${track.specifier.esc()}"></span>
               </div>"""
        }
        val overviewBadge = if (ep.missingOverview)
            """<span class="bg-orange-900 text-orange-300 text-xs px-2 py-0.5 rounded ml-2">no overview</span>"""
        else ""
        """<div class="border-t border-slate-700 pt-2 mt-2">
             <div class="flex items-center gap-2 mb-1">
               <span class="text-xs font-semibold text-slate-300 font-mono">${ep.episodeCode}</span>
               ${ep.title?.let { """<span class="text-xs text-slate-400">${it.esc()}</span>""" } ?: ""}
               $overviewBadge
             </div>
             ${if (tracksHtml.isEmpty()) """<span class="text-xs text-slate-500">No untagged tracks.</span>""" else tracksHtml}
           </div>"""
    }

    val totalUntagged = item.episodeIssues.sumOf { it.untaggedTracks.size }
    val untaggedBadge = if (totalUntagged > 0)
        """<span class="bg-red-900 text-red-300 text-xs px-2 py-0.5 rounded">$totalUntagged untagged track${if (totalUntagged != 1) "s" else ""}</span>"""
    else ""
    val missingOverviewCount = item.episodeIssues.count { it.missingOverview }
    val overviewBadge = if (missingOverviewCount > 0)
        """<span class="bg-orange-900 text-orange-300 text-xs px-2 py-0.5 rounded">$missingOverviewCount missing overview${if (missingOverviewCount != 1) "s" else ""}</span>"""
    else ""

    return """<div class="triage-item bg-slate-800 rounded-lg p-4 $activeClass" data-media-id="${item.mediaId}">
                <div class="flex items-start justify-between mb-2">
                  <div class="flex items-center gap-2 flex-wrap">
                    <span class="font-semibold text-white">${item.title.esc()}</span>
                    ${item.year?.let { """<span class="text-slate-400 text-sm">($it)</span>""" } ?: ""}
                    <span class="bg-slate-600 text-slate-300 text-xs px-2 py-0.5 rounded">TV</span>
                    $langBadge
                    $untaggedBadge
                    $overviewBadge
                  </div>
                </div>
                <div class="series-episodes-body">$episodesHtml</div>
              </div>"""
}

private fun wireSeriesTriageButtons(list: Element) {
    list.querySelectorAll(".ep-triage-assign-btn").let { nodes ->
        for (i in 0 until nodes.length) {
            val btn = nodes.item(i) as? HTMLElement ?: continue
            btn.addEventListener("click") {
                val mediaId = btn.getAttribute("data-media-id") ?: return@addEventListener
                val epFilename = btn.getAttribute("data-ep-filename") ?: return@addEventListener
                val specifier = btn.getAttribute("data-specifier") ?: return@addEventListener
                val input = list.querySelector(
                    ".ep-triage-lang-input[data-media-id=\"$mediaId\"][data-ep-filename=\"$epFilename\"][data-specifier=\"$specifier\"]"
                ) as? HTMLInputElement ?: return@addEventListener
                val lang = input.value.trim()
                if (lang.isBlank()) { input.focus(); return@addEventListener }
                val resultEl = list.querySelector(
                    ".ep-triage-result[data-ep-filename=\"$epFilename\"][data-specifier=\"$specifier\"]"
                )
                resultEl?.textContent = "…"
                triageScope?.launch {
                    val ok = assignEpisodeLanguage(mediaId, epFilename, specifier, lang)
                    if (ok) {
                        resultEl?.textContent = "✓"
                        resultEl?.setAttribute("class", "ep-triage-result text-xs text-green-400")
                        triageItems = triageItems.mapNotNull { item ->
                            if (item.mediaId != mediaId) return@mapNotNull item
                            val updatedEps = item.episodeIssues.mapNotNull { ep ->
                                if (ep.filename != epFilename) return@mapNotNull ep
                                val remaining = ep.untaggedTracks.filter { it.specifier != specifier }
                                if (remaining.isEmpty() && !ep.missingOverview) null
                                else ep.copy(untaggedTracks = remaining)
                            }
                            if (updatedEps.isEmpty()) null else item.copy(episodeIssues = updatedEps)
                        }
                        if (focusedIndex >= triageItems.size) focusedIndex = maxOf(0, triageItems.size - 1)
                        renderTriageList()
                    } else {
                        resultEl?.textContent = "✗ failed"
                        resultEl?.setAttribute("class", "ep-triage-result text-xs text-red-400")
                    }
                }
            }
        }
    }

    list.querySelectorAll(".ep-triage-lang-input").let { nodes ->
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? HTMLInputElement ?: continue
            el.addEventListener("keydown") { ev ->
                if ((ev as KeyboardEvent).key == "Enter") {
                    ev.preventDefault()
                    val mediaId = el.getAttribute("data-media-id") ?: return@addEventListener
                    val epFilename = el.getAttribute("data-ep-filename") ?: return@addEventListener
                    val specifier = el.getAttribute("data-specifier") ?: return@addEventListener
                    list.querySelector(
                        ".ep-triage-assign-btn[data-media-id=\"$mediaId\"][data-ep-filename=\"$epFilename\"][data-specifier=\"$specifier\"]"
                    )?.let { (it as? HTMLElement)?.click() }
                }
            }
        }
    }
}

private suspend fun fetchTriage(): List<TriageItem>? = runCatching {
    httpClient.get("/api/triage").body<List<TriageItem>>()
}.getOrNull()

private suspend fun assignEpisodeLanguage(mediaId: String, epFilename: String, specifier: String, language: String): Boolean =
    runCatching {
        val encoded = encodeURIComponent(epFilename)
        val response = httpClient.post("/api/triage/$mediaId/episodes/$encoded/tracks/$specifier/language") {
            contentType(ContentType.Application.Json)
            setBody("""{"language":"$language"}""")
        }
        response.status == HttpStatusCode.OK
    }.getOrDefault(false)

private suspend fun assignLanguage(mediaId: String, specifier: String, language: String): Boolean =
    runCatching {
        val response = httpClient.post("/api/triage/$mediaId/tracks/$specifier/language") {
            contentType(ContentType.Application.Json)
            setBody("""{"language":"$language"}""")
        }
        response.status == HttpStatusCode.OK
    }.getOrDefault(false)

private suspend fun fixCascadeDefault(mediaId: String, specifier: String): Boolean =
    runCatching {
        val response = httpClient.post("/api/media/$mediaId/tracks/default") {
            contentType(ContentType.Application.Json)
            setBody("""{"specifier":"$specifier"}""")
        }
        response.status.value in 200..299
    }.getOrDefault(false)
