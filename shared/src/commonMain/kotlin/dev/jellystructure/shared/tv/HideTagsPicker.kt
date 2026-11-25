package dev.jellystructure.shared.tv

/** One entry of the tag catalogue: a tag's name as the library spells it, and how many titles carry it. */
data class TagCount(val name: String, val count: Int)

/**
 * DEB-1264 (FR-7, FR-8) — the rules of the admin's *Hide titles tagged* picker, pulled out of the DOM code so the
 * ranking, the matched-text mark and the toggle can be tested. The server compares a whole tag name
 * case-insensitively (FR-2); everything here follows the same comparison, and a search only *finds* tags.
 */
object HideTagsPicker {
    /** FR-8 — with nothing typed the picker lists this many of the most-used tags. */
    const val MOST_USED = 6

    /**
     * The picker's rows. A blank [query] gives the [MOST_USED] tags with the most titles. Otherwise every tag whose
     * name contains the query, case-insensitively: names that *start with* it first, then by title count, then by
     * name so the order is stable.
     */
    fun results(catalogue: List<TagCount>, query: String): List<TagCount> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return catalogue.sortedWith(byCountThenName).take(MOST_USED)
        return catalogue.filter { it.name.lowercase().contains(q) }
            .sortedWith(compareByDescending<TagCount> { it.name.lowercase().startsWith(q) }.then(byCountThenName))
    }

    /** The span of [name] to mark for [query], or null when nothing should be marked. */
    fun matchRange(name: String, query: String): IntRange? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val lower = name.lowercase()
        if (lower.length != name.length) return null   // a case fold that changes the length would mark the wrong span
        val i = lower.indexOf(q)
        return if (i < 0) null else i until i + q.length
    }

    fun isHidden(list: List<String>, tag: String): Boolean = list.any { it.equals(tag, ignoreCase = true) }

    /** Picking a ticked tag removes it, picking any other adds exactly [tag] (FR-8). */
    fun toggled(list: List<String>, tag: String): List<String> =
        if (isHidden(list, tag)) list.filterNot { it.equals(tag, ignoreCase = true) } else list + tag

    fun removed(list: List<String>, tag: String): List<String> = list.filterNot { it == tag }

    /**
     * A chip's count (FR-7): the titles carrying [tag], 0 for a tag no title carries, and null when there is no
     * catalogue to read it from (FR-9: the chip shows `–`, never a guessed number).
     */
    fun countOf(catalogue: List<TagCount>?, tag: String): Int? =
        catalogue?.firstOrNull { it.name.equals(tag, ignoreCase = true) }?.count ?: catalogue?.let { 0 }

    /** The catalogue the admin's tag views read: the curated tags plus the library's other tags, one entry per name. */
    fun catalogueOf(named: List<TagCount>): List<TagCount> {
        val seen = HashSet<String>()
        return named.filter { it.name.isNotBlank() && seen.add(it.name.lowercase()) }
    }

    private val byCountThenName = compareByDescending<TagCount> { it.count }.thenBy { it.name.lowercase() }
}

/**
 * DEB-5003 (FR-3, FR-7) — where the admin puts the Newly Added *Hide titles tagged* sub-row and when it is greyed out.
 * Pure, so the rule can be tested without the DOM.
 */
object NewlyHideTags {
    /**
     * The row that carries the layout's one Newly Added list: the first `NEWLY_ADDED` row in `order`, switched on or
     * not (FR-3). A later `NEWLY_ADDED` row never carries one.
     */
    fun hostRowId(rows: List<RowConfig>): String? =
        rows.sortedBy { it.order }.firstOrNull { it.kind == RowKind.NEWLY_ADDED }?.id

    /**
     * FR-7 — the Home sub-row is hidden (`off`, tags kept) only when the layout shows no Newly Added row at all:
     * no `NEWLY_ADDED` row switched on, which covers the split row, the typed rows and the merged one, and *Merge* off.
     */
    fun homeSubRowOff(rows: List<RowConfig>, merge: Boolean): Boolean =
        !merge && rows.none { it.kind == RowKind.NEWLY_ADDED && it.enabled }
}
