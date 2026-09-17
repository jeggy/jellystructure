package dev.jellystructure.shared.tv

/**
 * Phase 225 — THE resolver for how a content row lines up. One function, used by the server to build the
 * row and by the admin editor to preview it, so the two cannot disagree. No comparator exists anywhere
 * else (FR-225-2).
 *
 *     pinsInOrder(pinned ∩ matches)  ++  (matches \ pins) sorted by (sort.by, sort.descending) then name
 *
 * then `take(limit)`. An absent [RowSort] is TODAY'S order exactly — newest first, ties by plain title —
 * which is what makes an untouched config produce the identical row (acceptance 1). An explicit sort
 * breaks ties on the sort name (Jellyfin's `SortName`, falling back to the title — FR-225-3).
 */
object RowOrder {
    const val DEFAULT_LIMIT = 30
    const val MIN_LIMIT = 3
    val KEYS = listOf("added", "title", "year")

    fun effectiveLimit(limit: Int?): Int = limit?.takeIf { it in MIN_LIMIT..DEFAULT_LIMIT } ?: DEFAULT_LIMIT

    fun <T> resolve(
        matches: List<T>,
        sort: RowSort?,
        pinned: List<String>,
        limit: Int?,
        id: (T) -> String?,
        added: (T) -> Long,
        year: (T) -> Int?,
        title: (T) -> String,
        sortName: (T) -> String?,
    ): List<T> {
        val name: (T) -> String = { (sortName(it)?.takeIf { s -> s.isNotBlank() } ?: title(it)).lowercase() }
        val comparator: Comparator<T> = when {
            sort == null -> compareByDescending<T> { added(it) }.thenBy { title(it) }          // today, byte for byte
            sort.by == "title" -> if (sort.descending) compareByDescending<T> { name(it) } else compareBy<T> { name(it) }
            sort.by == "year" -> (if (sort.descending) compareByDescending<T> { year(it) ?: 0 } else compareBy<T> { year(it) ?: 0 }).thenBy { name(it) }
            else -> (if (sort.descending) compareByDescending<T> { added(it) } else compareBy<T> { added(it) }).thenBy { name(it) }
        }
        val cap = effectiveLimit(limit)
        if (pinned.isEmpty()) return matches.sortedWith(comparator).take(cap)
        val byId = HashMap<String, T>()
        for (m in matches) id(m)?.let { if (it !in byId) byId[it] = m }
        val pins = pinned.distinct().mapNotNull { byId[it] }              // FR-225-5: a stale id is simply absent here
        val pinnedIds = pins.mapNotNull(id).toSet()
        return (pins + matches.filter { id(it) !in pinnedIds }.sortedWith(comparator)).take(cap)
    }

    /** FR-225-11 — `null` when fine, else the sentence the config route answers 400 with. */
    fun problem(row: RowConfig): String? {
        val touched = row.sort != null || row.pinned.isNotEmpty() || row.limit != null
        if (!touched) return null
        val label = row.title ?: row.id
        if (row.kind == RowKind.CONTINUE || row.kind == RowKind.NEWLY_ADDED) return "Row '$label' is a system row and cannot be given an order."   // FR-225-7
        row.sort?.let { if (it.by !in KEYS) return "Row '$label': sort must be one of added, title, year." }
        row.limit?.let { if (it !in MIN_LIMIT..DEFAULT_LIMIT) return "Row '$label': a row shows between $MIN_LIMIT and $DEFAULT_LIMIT titles." }
        if (row.pinned.distinct().size > effectiveLimit(row.limit)) return "Row '$label': more hand-picks than the row shows (${effectiveLimit(row.limit)})."
        return null
    }

    /** The order in words — never "asc"/"desc" (FR-225-9/10). */
    fun directionWords(by: String, descending: Boolean): String = when (by) {
        "title" -> if (descending) "Z → A" else "A → Z"
        "year" -> if (descending) "newest release first" else "oldest release first"
        else -> if (descending) "newest first" else "oldest first"
    }

    /** FR-225-10 — the row-list suffix; empty for the default order and count. */
    fun summary(row: RowConfig): String {
        val sort = row.sort ?: RowSort()
        val pins = row.pinned.distinct().size
        val order = when {
            pins > 0 -> "$pins hand-picked, then " + (if (sort.by == "title") "title " else "") + directionWords(sort.by, sort.descending)
            sort == RowSort() -> ""
            sort.by == "title" -> "title " + directionWords(sort.by, sort.descending)
            else -> directionWords(sort.by, sort.descending)
        }
        val shows = row.limit?.takeIf { it != DEFAULT_LIMIT }?.let { "shows $it" }.orEmpty()
        return listOf(order, shows).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
