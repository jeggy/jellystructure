package dev.jellystructure.shared.tv

// DEB-5690 (DEB-5685, FR-55) — moved from ravilo-ui's SeededBrowseScreen so the Samsung grid orders a row's whole list the way Android TV opens it.

/** R318 (FR-R318-2b) — [SOURCE] is the server's own order (the Recommended list's rank), offered only on
 *  a page whose store is source-ordered, and labelled with the page's own title (no new string). */
enum class SortField { SOURCE, RECENT, TITLE, YEAR, MATURITY, IMDB, SIZE }   // R317 — SIZE
enum class SortDir { ASC, DESC }

/** R253 (FR-R253-2) — 225's served `sort_by`/`sort_descending` onto the page's existing (field, direction)
 *  pair. Absent or unknown ⇒ R187's default (`RECENT · DESC`), never an empty chip. Zero new strings. */
fun initialBrowseSort(sortBy: String?, descending: Boolean?): Pair<SortField, SortDir> {
    val field = when (sortBy) { "title" -> SortField.TITLE; "year" -> SortField.YEAR; "added" -> SortField.RECENT; "size" -> SortField.SIZE; "rating" -> SortField.IMDB; else -> return SortField.RECENT to SortDir.DESC }   // R317 (FR-R317-2); DEB-2837 (FR-7) — a best-rated row opens in its order (the sort's label stays *IMDb*)
    val dir = when (descending) { true -> SortDir.DESC; false -> SortDir.ASC; null -> if (field == SortField.TITLE) SortDir.ASC else SortDir.DESC }
    return field to dir
}

/** The page's ordering, pure (R318: testable without a store). */
fun browseOrder(filtered: List<BrowseCard>, field: SortField, dir: SortDir): List<BrowseCard> {
    // R317 (FR-R317-1) — by the size the server sent (268's one rule; the client never derives one).
    // Unknown sizes go last in BOTH directions, and ties read by title either way, so this is not a
    // plain reverse like the other fields.
    if (field == SortField.SIZE) {
        val (known, unknown) = filtered.partition { it.sizeBytes != null }
        val byTitle = compareBy<BrowseCard> { (it.sortName?.takeIf { n -> n.isNotBlank() } ?: it.card.title).lowercase() }
        val sized = known.sortedWith(
            (if (dir == SortDir.DESC) compareByDescending<BrowseCard> { it.sizeBytes } else compareBy { it.sizeBytes }).then(byTitle),
        )
        return sized + unknown.sortedWith(byTitle)
    }
    // DEB-2837 (FR-7) — the server's own rating key (`ratingScore`, else IMDb's rating, which an older server is the only
    // one to send): unrated titles go last in BOTH directions and ties read by title, as the `rating` row order does.
    if (field == SortField.IMDB) {
        val (rated, unrated) = filtered.partition { (it.ratingScore ?: it.imdbRating?.aggregateRating) != null }
        val byTitle = compareBy<BrowseCard> { (it.sortName?.takeIf { n -> n.isNotBlank() } ?: it.card.title).lowercase() }
        val key = { c: BrowseCard -> c.ratingScore ?: c.imdbRating?.aggregateRating }
        val ordered = rated.sortedWith((if (dir == SortDir.DESC) compareByDescending<BrowseCard> { key(it) } else compareBy { key(it) }).then(byTitle))
        return ordered + unrated.sortedWith(byTitle)
    }
    // ASC ordering per field; RECENT's "ascending" base is the server's own newest-first order, so
    // ASC there reads as oldest-first and DESC (the default) as newest-first — both real orders, not
    // one arbitrary order plus its reverse-for-the-sake-of-it.
    val ascending = when (field) {
        // R318 — the server's order is best-first, so like RECENT its "ascending" base is the reverse.
        SortField.SOURCE -> filtered.asReversed()
        SortField.RECENT -> filtered.asReversed()
        // R253 (FR-R253-3) — the SAME key the server lines the row up by (Jellyfin's SortName, else the
        // title), so the row and its page cannot file *The Bear* in two different places.
        SortField.TITLE -> filtered.sortedBy { (it.sortName?.takeIf { n -> n.isNotBlank() } ?: it.card.title).lowercase() }
        SortField.YEAR -> filtered.sortedBy { it.card.year ?: 0 }
        SortField.MATURITY -> filtered.sortedBy { it.card.ageRating }
        SortField.IMDB, SortField.SIZE -> filtered   // handled above
    }
    return if (dir == SortDir.DESC) ascending.asReversed() else ascending
}
