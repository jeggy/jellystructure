package dev.jellystructure.ui

import dev.jellystructure.api.JellyfinCollectionsApi
import dev.jellystructure.api.MediaApi
import dev.jellystructure.model.JellyfinCollectionDto
import dev.jellystructure.model.JellyfinCollectionMemberDto
import dev.jellystructure.model.JellyfinCollectionSuggestionDto
import dev.jellystructure.model.JellyfinCollectionsPageDto
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent

/**
 * DEB-4075 (DEB-4032, FR-5..FR-7) — the admin's *Jellyfin collections* page and the title page's *Collections* card. Jellyfin is the only store:
 * every press is one call, and the page then redraws from what the server says Jellyfin holds. A refused write shows the route's sentence and
 * leaves the page as the server last described it. The only thing held here that the server did not say is the *New collection* draft, which
 * is nothing until *Create*. The markup is in `JellyfinCollectionsHtml.kt`.
 */
private var jcPage: JellyfinCollectionsPageDto = JellyfinCollectionsPageDto()
private var jcSuggest: List<JellyfinCollectionSuggestionDto> = emptyList()
/** The collection open in the editor, or null. */
private var jcEditing: String? = null
/** The unsaved *New collection*: its name as last read and the titles ticked. Null when no draft is open. */
private var jcDraftName: String? = null
private var jcDraftMembers: MutableList<JellyfinCollectionMemberDto> = mutableListOf()
private var jcError = ""
private var jcQuery = ""
private var jcSearchJob: Job? = null
private var jcResults: List<JellyfinCollectionMemberDto> = emptyList()

fun renderJellyfinCollections(container: Element, scope: CoroutineScope, query: Map<String, String>) {
    jcEditing = null; jcDraftName = null; jcDraftMembers = mutableListOf(); jcError = ""; jcQuery = ""; jcResults = emptyList()
    container.innerHTML = """<div id="jc-root"><div class="pagebar"><h1>Jellyfin collections</h1></div><span class="muted tiny">Loading…</span></div>"""
    val root = container.querySelector("#jc-root") as? HTMLElement ?: return
    root.addEventListener("click") { ev -> (ev.target as? Element)?.closest("[data-jc-act]")?.let { ev.preventDefault(); jcClick(it, scope) } }
    root.addEventListener("change") { ev ->
        val el = ev.target as? HTMLInputElement ?: return@addEventListener
        if (el.id == "jc-name" && el.getAttribute("data-jc-id") != null) scope.launch { jcRename(el.getAttribute("data-jc-id")!!, el.value) }
    }
    root.addEventListener("keydown") { ev ->
        val el = ev.target as? HTMLInputElement ?: return@addEventListener
        if (el.id == "jc-name" && (ev as KeyboardEvent).key == "Enter") { ev.preventDefault(); el.blur() }
    }
    root.addEventListener("input") { ev ->
        val el = ev.target as? HTMLInputElement ?: return@addEventListener
        if (el.id == "jc-search") jcSearch(scope, el.value)
    }
    scope.launch {
        jcReload()
        // From a title page's *New collection*: the editor opens with that title ticked.
        query["new"]?.takeIf { it.isNotBlank() }?.let { mediaId ->
            val item = MediaApi.get(mediaId)
            if (item != null) { jcDraftName = ""; jcDraftMembers = mutableListOf(JellyfinCollectionMemberDto(item.id, item.title, item.year, item.kind)) }
        }
        jcRender()
    }
}

private suspend fun jcReload() {
    JellyfinCollectionsApi.page()?.let { jcPage = it }
    JellyfinCollectionsApi.suggestions()?.let { jcSuggest = it }
}

private fun jcEditingCollection(): JellyfinCollectionDto? = jcEditing?.let { id -> jcPage.collections.firstOrNull { it.id == id } }

private fun jcRender() {
    val root = document.getElementById("jc-root") as? HTMLElement ?: return
    val draft = jcDraftName != null
    val editing = jcEditingCollection()
    root.innerHTML = buildString {
        append("""<div class="pagebar"><h1>Jellyfin collections</h1><span class="spacer"></span><button class="btn" data-jc-act="new">New collection</button></div>""")
        append("""<p class="page-sub">Hand-made lists of films and series, kept in Jellyfin itself. A change here is made in Jellyfin and read back; a collection made in Jellyfin's own dashboard shows here within five minutes. Deleting a collection never deletes a film or a series.</p>""")
        append("""<div id="jc-error" class="badge warn" style="display:${if (jcError.isEmpty()) "none" else "block"};margin-bottom:12px;white-space:normal">${jcError.esc()}</div>""")
        if (draft) append(jcEditorHtml(null, jcDraftName ?: "", jcDraftMembers, 0))
        else if (editing != null) append(jcEditorHtml(editing.id, editing.name, editing.members, editing.unmappedCount))
        append(jcListHtml(jcPage, jcEditing))
        append(jcSuggestionsHtml(jcSuggest))
    }
    if (jcQuery.isNotEmpty() && (draft || editing != null)) {
        (document.getElementById("jc-search") as? HTMLInputElement)?.value = jcQuery
        jcShowResults()
    }
}

private fun jcShowResults() {
    val already = (jcEditingCollection()?.members ?: jcDraftMembers).map { it.id }.toSet()
    (document.getElementById("jc-results") as? HTMLElement)?.innerHTML = if (jcQuery.length < 2) "" else jcResultsHtml(jcResults, already)
}

private fun jcSearch(scope: CoroutineScope, q: String) {
    jcSearchJob?.cancel()
    jcQuery = q.trim()
    if (jcQuery.length < 2) { jcResults = emptyList(); jcShowResults(); return }
    jcSearchJob = scope.launch {
        delay(250)
        // Only a title Jellyfin has can go in a collection, so one it doesn't know is not offered.
        jcResults = (MediaApi.list(search = jcQuery, pageSize = 8)?.items ?: emptyList())
            .filter { !it.jellyfinId.isNullOrBlank() }.map { JellyfinCollectionMemberDto(it.id, it.title, it.year, it.kind) }
        jcShowResults()
    }
}

/** Runs one write, then redraws from the server: its sentence if it refused, the collections as Jellyfin holds them either way. */
private suspend fun jcWrite(write: suspend () -> dev.jellystructure.api.JcAnswer): dev.jellystructure.api.JcAnswer {
    val a = write()
    jcError = a.error ?: ""
    jcReload()
    return a
}

private suspend fun jcRename(id: String, raw: String) {
    val current = jcPage.collections.firstOrNull { it.id == id }
    if (current != null && raw.trim() != current.name) jcWrite { JellyfinCollectionsApi.rename(id, raw) }
    jcRender()
}

private fun jcClick(el: Element, scope: CoroutineScope) {
    val id = el.getAttribute("data-jc-id")
    val media = el.getAttribute("data-jc-media")
    when (el.getAttribute("data-jc-act")) {
        "new" -> { jcEditing = null; jcDraftName = ""; jcDraftMembers = mutableListOf(); jcError = ""; jcRender() }
        "edit" -> { jcDraftName = null; jcEditing = id; jcError = ""; jcQuery = ""; jcRender() }
        "close" -> { jcEditing = null; jcDraftName = null; jcDraftMembers = mutableListOf(); jcQuery = ""; jcRender() }
        "delete" -> {
            val c = jcPage.collections.firstOrNull { it.id == id } ?: return
            if (!window.confirm(jcDeleteConfirm(c.name))) return
            scope.launch {
                jcWrite { JellyfinCollectionsApi.delete(c.id) }
                if (jcEditing == c.id && jcPage.collections.none { it.id == c.id }) jcEditing = null
                jcRender()
            }
        }
        "remove" -> {
            if (id == null || media == null) return
            scope.launch { jcWrite { JellyfinCollectionsApi.remove(id, media) }; jcRender() }
        }
        "add" -> {
            val m = jcResults.firstOrNull { it.id == media } ?: return
            val open = jcEditing
            if (open != null) scope.launch { jcWrite { JellyfinCollectionsApi.add(open, listOf(m.id)) }; jcRender() }
            else if (jcDraftName != null && jcDraftMembers.none { it.id == m.id }) {
                jcDraftName = (document.getElementById("jc-name") as? HTMLInputElement)?.value ?: jcDraftName
                jcDraftMembers.add(m)
                jcRender()
            }
        }
        "suggest" -> {
            val s = el.getAttribute("data-jc-i")?.toIntOrNull()?.let { jcSuggest.getOrNull(it) } ?: return
            // Nothing is made: the editor opens with the name filled in and every film ticked.
            jcEditing = null; jcDraftName = s.name; jcDraftMembers = s.films.toMutableList(); jcError = ""; jcQuery = ""
            jcRender()
            document.getElementById("jc-editor")?.scrollIntoView()
        }
        "create" -> {
            val name = (document.getElementById("jc-name") as? HTMLInputElement)?.value ?: ""
            val ticked = document.querySelectorAll("#jc-members [data-jc-draft]").let { l ->
                (0 until l.length).mapNotNull { i -> (l.item(i) as? HTMLInputElement)?.takeIf { it.checked }?.getAttribute("data-jc-draft") }
            }
            scope.launch {
                val a = jcWrite { JellyfinCollectionsApi.create(name, ticked) }
                if (a.ok) { jcDraftName = null; jcDraftMembers = mutableListOf(); jcEditing = a.collection?.id; jcQuery = "" }
                else jcDraftName = name   // the draft stays as typed
                jcRender()
            }
        }
    }
}

// ── The title page's Collections card (FR-6) ─────────────────────────────────────────────────────────────────────────────────

/** Fills `#jc-section` on a title's page from the server's list, and wires it. A page the server cannot answer leaves the card out. */
internal fun jcWireTitleLine(titleId: String, scope: CoroutineScope) {
    val section = document.getElementById("jc-section") as? HTMLElement ?: return
    suspend fun draw() {
        val page = JellyfinCollectionsApi.page()
        if (page == null || !page.configured) { section.style.display = "none"; return }
        section.style.display = ""
        section.innerHTML = jcTitleLineHtml(titleId, page.collections) + """<div id="jc-title-error" class="tiny" style="color:var(--bad);margin-top:6px;display:none"></div>"""
    }
    fun say(text: String) { (document.getElementById("jc-title-error") as? HTMLElement)?.let { it.textContent = text; it.style.display = if (text.isEmpty()) "none" else "" } }
    section.addEventListener("click") { ev ->
        val el = (ev.target as? Element)?.closest("[data-jc-act=title-remove]") ?: return@addEventListener
        ev.preventDefault()
        val id = el.getAttribute("data-jc-id") ?: return@addEventListener
        scope.launch { val a = JellyfinCollectionsApi.remove(id, titleId); draw(); a.error?.let(::say) }
    }
    section.addEventListener("change") { ev ->
        val sel = ev.target as? HTMLSelectElement ?: return@addEventListener
        if (sel.id != "jc-title-add") return@addEventListener
        val id = sel.value.takeIf { it.isNotEmpty() } ?: return@addEventListener
        if (id == "__new") { dev.jellystructure.Router.navigate("/jellyfin-collections", mapOf("new" to titleId)); return@addEventListener }
        scope.launch { val a = JellyfinCollectionsApi.add(id, listOf(titleId)); draw(); a.error?.let(::say) }
    }
    scope.launch { draw() }
}
