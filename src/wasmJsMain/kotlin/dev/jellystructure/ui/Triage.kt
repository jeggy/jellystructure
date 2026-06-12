package dev.jellystructure.ui

import dev.jellystructure.api.httpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
data class TriageItem(
    val mediaId: String,
    val title: String,
    val year: Int? = null,
    val path: String,
    val untaggedTracks: List<TriageTrack>,
)

private val json = Json { ignoreUnknownKeys = true }
private var triageItems: List<TriageItem> = emptyList()
private var focusedIndex = 0
private var triageScope: CoroutineScope? = null
private var triageContainer: Element? = null

fun renderTriage(container: Element, scope: CoroutineScope) {
    triageScope = scope
    triageContainer = container
    focusedIndex = 0

    container.innerHTML = """
        <div class="p-6">
          <div class="flex items-center justify-between mb-6">
            <div>
              <h1 class="text-2xl font-bold text-white">Triage Queue</h1>
              <p class="text-sm text-slate-400 mt-1">Tracks missing a language tag — assign to fix.</p>
            </div>
            <div id="triage-count" class="text-sm text-slate-400"></div>
          </div>
          <div id="triage-hint" class="mb-4 text-xs text-slate-500">
            Use <kbd class="bg-slate-700 px-1 rounded">↑</kbd> / <kbd class="bg-slate-700 px-1 rounded">↓</kbd> to navigate items &nbsp;·&nbsp;
            <kbd class="bg-slate-700 px-1 rounded">Enter</kbd> to focus first input in selected item
          </div>
          <div id="triage-list" class="space-y-3"></div>
          <div id="triage-empty" class="hidden text-center py-16 text-slate-500">
            <div class="text-4xl mb-3">✓</div>
            <div class="text-lg font-medium text-slate-300">No untagged tracks</div>
            <div class="text-sm mt-1">All tracks have language assignments.</div>
          </div>
        </div>
    """.trimIndent()

    scope.launch { loadTriage() }

    (container as? HTMLElement)?.addEventListener("keydown") { ev ->
        val ke = ev as KeyboardEvent
        when (ke.key) {
            "ArrowDown" -> { ke.preventDefault(); moveFocus(1) }
            "ArrowUp"   -> { ke.preventDefault(); moveFocus(-1) }
            "Enter"     -> focusFirstInput()
        }
    }
    (container as? HTMLElement)?.setAttribute("tabindex", "-1")
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

    if (triageItems.isEmpty()) {
        list.innerHTML = ""
        empty?.classList?.remove("hidden")
        count?.textContent = ""
        return
    }
    empty?.classList?.add("hidden")
    count?.textContent = "${triageItems.size} item${if (triageItems.size != 1) "s" else ""} need attention"

    list.innerHTML = triageItems.mapIndexed { idx, item ->
        val isActive = idx == focusedIndex
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
        """
        <div class="triage-item bg-slate-800 rounded-lg p-4 cursor-pointer $activeClass" data-idx="$idx">
          <div class="flex items-start justify-between mb-1">
            <div>
              <span class="font-semibold text-white">${item.title}</span>
              ${item.year?.let { """<span class="text-slate-400 text-sm ml-2">($it)</span>""" } ?: ""}
            </div>
            <span class="text-xs text-slate-500 font-mono truncate max-w-xs ml-4" title="${item.path}">${item.path.substringAfterLast('/')}</span>
          </div>
          <div class="mt-2">$tracksHtml</div>
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

    // Attach assign button listeners
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
                if (lang.isBlank()) {
                    input.focus()
                    return@addEventListener
                }
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

    // Enter key in language input triggers assign
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

private suspend fun fetchTriage(): List<TriageItem>? = runCatching {
    httpClient.get("/api/triage").body<List<TriageItem>>()
}.getOrNull()

private suspend fun assignLanguage(mediaId: String, specifier: String, language: String): Boolean =
    runCatching {
        val response = httpClient.post("/api/triage/$mediaId/tracks/$specifier/language") {
            contentType(ContentType.Application.Json)
            setBody("""{"language":"$language"}""")
        }
        response.status == HttpStatusCode.OK
    }.getOrDefault(false)
