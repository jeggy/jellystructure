package dev.jellystructure.ui

import dev.jellystructure.model.TagScope

/*
 * DEB-3701 (FR-2, FR-3, FR-4) — the markup a tag's scope is drawn with on Metadata → Tags and in the add-tag pickers.
 *
 * In `commonMain` for the reason `MusicVersionBulkHtml.kt` gives: the admin frontend has no test target of its own,
 * and the chips' state is the requirement (Acceptance 10). Nothing here touches the DOM. The tick chips are the
 * Music pages' own `mu-gc` (design/app/music.css), so no served stylesheet changes.
 */

/** FR-2 — the modal's *Used for* row: the *All* chip, then the five groups. [selected] empty = *All*. */
internal fun tagScopeRowHtml(selected: List<String>): String = buildString {
    append("""<div class="mu-gchips" id="modal-tag-scopes">""")
    append(tagScopeChipHtml("", "All", selected.isEmpty()))
    for (k in TagScope.KEYS) append(tagScopeChipHtml(k, TagScope.label(k), k in selected))
    append("</div>")
}

private fun tagScopeChipHtml(key: String, label: String, on: Boolean): String =
    """<span class="mu-gc${if (on) " on" else ""}" data-scope="${key.esc()}" role="checkbox" aria-checked="$on"><span class="bx">${if (on) "✓" else ""}</span>${label.esc()}</span>"""

/** FR-2 — the card's line under the description: *All*, or the groups joined by ` · `. */
internal fun tagScopeLineHtml(scopes: List<String>): String =
    """<span class="tiny muted tag-scope-line">${TagScope.summary(scopes).esc()}</span>"""

/** FR-4 — after a save, the card says how many items still carry the tag outside its scope, and links to them. */
internal fun tagOutsideBadgeHtml(tag: String, outside: Int, encodedName: String): String =
    if (outside <= 0) ""
    else """<a href="#/library?tags=$encodedName&filter=outside_scope" data-stop-click class="tiny tag-outside" style="color:var(--warn,var(--ink-soft))">${outside} outside its scope</a>"""

/** FR-4 — the modal's line before a save. Empty when nothing is outside the new scope. */
internal fun tagOutsideNoticeText(outside: Int, byKind: Map<String, Int>): String =
    if (outside <= 0) ""
    else "$outside ${if (outside == 1) "item outside the new scope keeps" else "items outside the new scope keep"} this tag: ${TagScope.outsideSummary(byKind)}"

/** FR-4 — the label of the explicit removal. */
internal fun tagRemoveOutsideLabel(outside: Int): String = "Remove from the $outside outside its scope"

/**
 * FR-3 — the detail page's tag chip. A tag the item already carries outside its tag's scope keeps its chip and its ✕, with
 * a dashed border and the title *outside this tag's scope*. [color] is the jellystructure tag's dot, `null` for any other tag.
 */
internal fun tagChipWithScopeHtml(tag: String, color: String?, outOfScope: Boolean): String {
    val dot = if (color != null) """<span class="tag-dot" style="background:${color.esc()};"></span>""" else ""
    val oos = if (outOfScope) """ title="outside this tag's scope" data-out-of-scope="true"""" else ""
    val border = if (outOfScope) "cursor:default;border-style:dashed;" else "cursor:default;"
    return """<span class="chip tag-chip"$oos style="$border">$dot${tag.esc()} <span class="rm tag-rm" data-tag="${tag.esc()}">✕</span></span>"""
}
