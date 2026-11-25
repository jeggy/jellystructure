package dev.jellystructure.ui

import dev.jellystructure.model.JellyfinCollectionDto
import dev.jellystructure.model.JellyfinCollectionMemberDto
import dev.jellystructure.model.JellyfinCollectionSuggestionDto
import dev.jellystructure.model.JellyfinCollectionsPageDto
import dev.jellystructure.model.MediaKind

/*
 * DEB-4075 (DEB-4032, FR-6, FR-7) — the markup of the admin's *Jellyfin collections* page and of the title page's *Collections* card.
 *
 * In `commonMain` for the reason `TagScopeHtml.kt` gives: the admin frontend has no test target of its own and what these draw is the
 * requirement. Nothing here touches the DOM, and every function draws what the routes answered and nothing it worked out itself
 * (CLAUDE.md: server-pushed state only). Built from controls the admin already has — `card`, `cfg-row`, `btn`, `input`, `chip`,
 * `badge` — so no stylesheet changes. A control carries `data-jc-act`; the page's one delegated listener reads it.
 */

/** `2 films · 1 series` — the kinds a collection holds, in that order, the ones it has none of left out. */
internal fun jcCountsText(members: List<JellyfinCollectionMemberDto>): String {
    val films = members.count { it.kind == MediaKind.MOVIE }
    val series = members.count { it.kind == MediaKind.TV_SHOW }
    val videos = members.count { it.kind == MediaKind.MUSIC_VIDEO }
    return listOfNotNull(
        "$films ${if (films == 1) "film" else "films"}",
        "$series series",
        if (videos > 0) "$videos music ${if (videos == 1) "video" else "videos"}" else null,
    ).joinToString(" · ")
}

/** FR-6 — said of a collection that holds members jellystructure does not; empty when it holds none. They are never removed. */
internal fun jcUnmappedText(unmapped: Int): String =
    if (unmapped <= 0) "" else "Also holds $unmapped ${if (unmapped == 1) "item" else "items"} jellystructure doesn't manage."

/** FR-6 — what *Delete* asks. */
internal fun jcDeleteConfirm(name: String): String = "Delete \"$name\"? The collection goes; its films and series stay."

/** FR-7 — `Name — 3 films`. */
internal fun jcSuggestionLine(s: JellyfinCollectionSuggestionDto): String = "${s.name} — ${s.films.size} ${if (s.films.size == 1) "film" else "films"}"

private fun memberLabel(m: JellyfinCollectionMemberDto): String = m.title + (m.year?.let { " ($it)" } ?: "")

/** FR-6 — the list: one row per collection, then the *New collection* button above it. [openId] marks the row the editor is open on. */
internal fun jcListHtml(page: JellyfinCollectionsPageDto, openId: String?): String = buildString {
    if (!page.configured) {
        append("""<div class="card"><p class="muted tiny" style="margin:0">Jellyfin isn't set up yet, so there are no collections to show. Connect it in <a href="#/settings">Settings</a>.</p></div>""")
        return@buildString
    }
    if (page.collections.isEmpty()) {
        append("""<div class="card"><p class="muted tiny" style="margin:0">No collections yet. Press <b>New collection</b>, or make one from a suggestion below.</p></div>""")
        return@buildString
    }
    for (c in page.collections) {
        val open = if (c.id == openId) " on" else ""
        val unmapped = jcUnmappedText(c.unmappedCount)
        append("""<div class="cfg-row$open" data-jc-row="${c.id.esc()}" style="gap:10px;margin-bottom:6px">""")
        append("""<div style="flex:1;min-width:0"><div class="nm" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${c.name.esc()}</div>""")
        append("""<div class="src">${jcCountsText(c.members).esc()}${if (unmapped.isEmpty()) "" else " · " + unmapped.esc()}</div></div>""")
        append("""<button class="btn sm" data-jc-act="edit" data-jc-id="${c.id.esc()}">Edit</button>""")
        append("""<button class="btn sm ghost" data-jc-act="delete" data-jc-id="${c.id.esc()}" style="color:var(--bad)">Delete</button>""")
        append("</div>")
    }
}

/**
 * The editor. [id] is the collection it edits, or null for a new one; a new one is a draft, nothing is made until *Create*.
 * An existing collection's members are the server's, each with ✕; a draft's members are ticked boxes the operator may untick.
 */
internal fun jcEditorHtml(id: String?, name: String, members: List<JellyfinCollectionMemberDto>, unmapped: Int): String = buildString {
    append("""<div class="card" id="jc-editor" style="margin-bottom:16px">""")
    append("""<div class="row center"><h4 style="margin:0">${if (id == null) "New collection" else "Edit collection"}</h4><span class="spacer"></span>""")
    append("""<button class="btn sm ghost" data-jc-act="close">${if (id == null) "Cancel" else "Done"}</button></div><hr class="dash" style="margin:10px 0">""")
    val idAttr = if (id == null) "" else " data-jc-id=\"${id.esc()}\""
    append("""<div class="field"><label>Name</label><input id="jc-name" class="input" type="text" maxlength="200" autocomplete="off" value="${name.esc()}"$idAttr></div>""")
    append("""<div class="field"><label>${if (id == null) "Titles" else "Members"}</label><div id="jc-members">${jcMembersHtml(id, members)}</div></div>""")
    val unmappedText = jcUnmappedText(unmapped)
    if (unmappedText.isNotEmpty()) append("""<p class="tiny muted" id="jc-unmapped" style="margin:0 0 10px">${unmappedText.esc()}</p>""")
    append("""<div class="field"><label>Add films and series</label><input id="jc-search" class="input" type="search" placeholder="Search titles…" autocomplete="off"><div id="jc-results" style="margin-top:6px"></div></div>""")
    if (id == null) append("""<div class="row" style="justify-content:flex-end"><button class="btn" data-jc-act="create">Create</button></div>""")
    append("</div>")
}

/** The members block. A saved collection: a chip each, ✕ removes it from Jellyfin. A draft: a ticked box each. */
internal fun jcMembersHtml(id: String?, members: List<JellyfinCollectionMemberDto>): String {
    if (members.isEmpty()) return """<span class="tiny muted">No titles yet. Search below to add some.</span>"""
    return if (id == null) members.joinToString("") { m ->
        """<label class="cfg-row" style="gap:8px;margin-bottom:4px;cursor:pointer"><input type="checkbox" checked data-jc-draft="${m.id.esc()}"><span class="nm" style="flex:1;min-width:0">${memberLabel(m).esc()}</span></label>"""
    } else """<div class="pill-row">""" + members.joinToString("") { m ->
        """<span class="chip">${memberLabel(m).esc()} <a href="#" class="rm" data-jc-act="remove" data-jc-id="${id.esc()}" data-jc-media="${m.id.esc()}" title="Remove from this collection" aria-label="Remove ${m.title.esc()}">✕</a></span>"""
    } + "</div>"
}

/** The title search's results; one already in the collection (or the draft) is shown, not offered. */
internal fun jcResultsHtml(results: List<JellyfinCollectionMemberDto>, already: Set<String>): String {
    if (results.isEmpty()) return """<div class="tiny muted">No results found.</div>"""
    return results.joinToString("") { m ->
        val kind = if (m.kind == MediaKind.TV_SHOW) "Series" else if (m.kind == MediaKind.MUSIC_VIDEO) "Music video" else "Film"
        if (m.id in already) """<div class="cfg-row" style="gap:10px;margin-bottom:4px;opacity:.55"><div class="nm" style="flex:1;min-width:0">${memberLabel(m).esc()}</div><span class="tiny muted">$kind · added</span></div>"""
        else """<div class="cfg-row" style="gap:10px;margin-bottom:4px"><div class="nm" style="flex:1;min-width:0">${memberLabel(m).esc()}</div><span class="tiny muted">$kind</span><button class="btn sm" data-jc-act="add" data-jc-media="${m.id.esc()}">Add</button></div>"""
    }
}

/** FR-7 — the page's second section. Nothing is made by pressing *Create collection*: it opens the editor filled in. */
internal fun jcSuggestionsHtml(suggestions: List<JellyfinCollectionSuggestionDto>): String = buildString {
    append("""<h3 style="font-size:.95rem;margin:24px 0 8px">Suggestions</h3>""")
    append("""<p class="page-sub" style="margin:0 0 10px">Franchises of two or more films you hold that no collection covers yet, from TMDB.</p>""")
    if (suggestions.isEmpty()) { append("""<p class="muted tiny">Nothing to suggest.</p>"""); return@buildString }
    suggestions.forEachIndexed { i, s ->
        append("""<div class="cfg-row" style="gap:10px;margin-bottom:6px"><div class="nm" style="flex:1;min-width:0">${jcSuggestionLine(s).esc()}</div>""")
        append("""<button class="btn sm" data-jc-act="suggest" data-jc-i="$i">Create collection</button></div>""")
    }
}

/** FR-6 — the title page's *Collections* card: a chip for each collection the title is in (✕ removes it), and *Add to collection…*. */
internal fun jcTitleLineHtml(titleId: String, all: List<JellyfinCollectionDto>): String = buildString {
    val mine = all.filter { c -> c.members.any { it.id == titleId } }
    val others = all.filter { c -> c.members.none { it.id == titleId } }
    append("""<div class="row center"><h4 style="margin:0;">Collections</h4><span class="spacer"></span><span class="tiny muted"><a href="#/jellyfin-collections">manage collections →</a></span></div>""")
    append("""<hr class="dash" style="margin:10px 0;">""")
    append("""<div class="pill-row" style="margin-bottom:10px;">""")
    if (mine.isEmpty()) append("""<span class="tiny muted">In no collection.</span>""")
    for (c in mine) {
        append("""<span class="chip">${c.name.esc()} <a href="#" class="rm" data-jc-act="title-remove" data-jc-id="${c.id.esc()}" title="Remove from this collection" aria-label="Remove from ${c.name.esc()}">✕</a></span>""")
    }
    append("</div>")
    append("""<select id="jc-title-add" class="input" style="max-width:260px"><option value="">Add to collection…</option>""")
    for (c in others) append("""<option value="${c.id.esc()}">${c.name.esc()}</option>""")
    append("""<option value="__new">New collection</option></select>""")
}
