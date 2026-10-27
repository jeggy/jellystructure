package dev.jellystructure.autofetch

/**
 * DEB-2476 (DEB-2474, FR-16) — retention as pure decisions. The planner is given what the fetcher knows and answers what a
 * run may download and what it may delete; it does no I/O, so FR-16's guards are tested as plain data. The service that
 * calls it is the only code that deletes a file, and only what this returns.
 */
data class RetentionRow(
    val itemId: String,
    /** `yyyy-MM-dd`, the item's published date, or null. Older sorts last. */
    val published: String?,
    /** Listing order at the last read; ties on the date go by it. */
    val order: Int,
    /** The placed path, or null for an item not placed. */
    val placedPath: String? = null,
    /** True when [placedPath] still holds a file. */
    val fileExists: Boolean = false,
    /** Epoch seconds of the first full read that lacked this item, or null while it is on the page. */
    val missingSince: Long? = null,
    /** True when the current listing read has this item. */
    val onPage: Boolean = false,
)

enum class DeleteReason { NEWEST_KEPT, LEFT_PAGE }

data class PlannedDelete(val row: RetentionRow, val reason: DeleteReason)

/** What the read of the listing was worth (FR-16.5). */
data class ListingEvidence(
    /** A `2xx`, parsed by the recipe, every page of a paged listing read, and the run was not cut short. */
    val fullRead: Boolean,
    val itemCount: Int,
)

data class DeletePlan(val deletes: List<PlannedDelete>, val released: List<RetentionRow>, val note: String?)

object RetentionPlanner {
    const val SIX_HOURS = 6 * 3600L

    /** Newest first by published date, ties by listing order, an undated item last. */
    val NEWEST_FIRST: Comparator<RetentionRow> = Comparator { a, b ->
        val c = compareValues(b.published ?: "", a.published ?: "")
        if (c != 0) c else a.order.compareTo(b.order)
    }

    /**
     * FR-16.4 — the items a run may download: the ones not yet placed that are among the newest N of everything the
     * fetcher knows (placed rows whose file is still there, plus the page's items). With retention `all`, every missing item.
     */
    fun downloadable(rows: List<RetentionRow>, retention: RetentionSpec): List<RetentionRow> {
        val known = rows.filter { (it.placedPath != null && it.fileExists) || it.onPage }
            .distinctBy { it.itemId }
        val candidates = known.filter { it.placedPath == null || !it.fileExists }.filter { it.onPage }
        if (retention.keepsAll) return candidates.sortedWith(NEWEST_FIRST)
        val keep = known.sortedWith(NEWEST_FIRST).take(retention.keep).map { it.itemId }.toSet()
        return candidates.filter { it.itemId in keep }.sortedWith(NEWEST_FIRST)
    }

    /**
     * FR-16.3/16.5/16.7 — what the end of a run deletes. [library] is every placed row whose file is there after the
     * run's own downloads (so a failed download never makes room by deleting an older one). Rows whose file is gone are
     * released and nothing is deleted for them (FR-16.2).
     */
    fun deletions(rows: List<RetentionRow>, retention: RetentionSpec, evidence: ListingEvidence, now: Long): DeletePlan {
        val placed = rows.filter { it.placedPath != null }
        val released = placed.filter { !it.fileExists }
        val library = placed.filter { it.fileExists }
        if (retention.keepsAll) return DeletePlan(emptyList(), released, null)
        if (!evidence.fullRead) {
            val wants = wantsToDelete(library, retention, evidence = null, now)
            return DeletePlan(emptyList(), released, if (wants) "retention not applied, the listing could not be read in full" else null)
        }
        val deletes = LinkedHashMap<String, PlannedDelete>()
        // The newest N rule needs no page evidence beyond a successful read.
        library.sortedWith(NEWEST_FIRST).drop(retention.keep).forEach { deletes[it.itemId] = PlannedDelete(it, DeleteReason.NEWEST_KEPT) }
        var note: String? = null
        if (retention.leavesPage) {
            val overlap = library.count { it.onPage }
            val blockedBy = when {
                evidence.itemCount < 1 -> "the listing was empty"
                overlap < 1 -> "the listing shares no episode with the library"
                else -> null
            }
            val gone = library.filter { !it.onPage }
            if (blockedBy != null) {
                if (gone.isNotEmpty()) note = "left the page: not deleted, $blockedBy"
            } else {
                for (r in gone) {
                    val since = r.missingSince
                    if (since != null && now - since >= SIX_HOURS) if (r.itemId !in deletes) deletes[r.itemId] = PlannedDelete(r, DeleteReason.LEFT_PAGE)
                }
            }
        }
        return DeletePlan(deletes.values.toList(), released, note)
    }

    private fun wantsToDelete(library: List<RetentionRow>, retention: RetentionSpec, evidence: ListingEvidence?, now: Long): Boolean =
        library.size > retention.keep || (retention.leavesPage && library.any { !it.onPage })
}
