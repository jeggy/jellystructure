package dev.jellystructure.ravilo.ui.downloads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * DEB-4071 (DEB-3995 FR-10, amended) — a delete the viewer asked for and has not yet confirmed. [subject] is the title or series it names
 * (null where the sentence names none); [bytes] is what it frees, taken at the moment of asking.
 */
class PendingDelete(val kind: Kind, val subject: String?, val ids: Set<String>, val bytes: Long) {
    enum class Kind { ONE, SELECTION, SERIES, WATCHED, ALL, OTHER_PROFILES }
}

/**
 * FR-10 (amended) — **every swipe and every delete confirms**, naming what it frees, and Cancel puts everything back as it was. Nothing on
 * the page reaches [DownloadsController] without going through [ask…] then [confirm]; [cancel] touches nothing but the pending ask, so the
 * entry, the selection and the totals stay exactly as they were.
 */
class DownloadsPageState(private val controller: DownloadsController) {
    var selecting by mutableStateOf(false)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var pending by mutableStateOf<PendingDelete?>(null)
        private set

    private fun page() = buildDownloadsPage(controller.records.value, controller.phases.value)

    // ─── selecting (FR-10: long-press to select several) ───
    fun startSelecting(itemId: String? = null) { selecting = true; selected = if (itemId != null) setOf(itemId) else emptySet() }
    fun stopSelecting() { selecting = false; selected = emptySet() }
    fun toggle(itemId: String) { selected = if (itemId in selected) selected - itemId else selected + itemId }
    fun toggleAll(ids: Collection<String>) {
        selected = if (selected.containsAll(ids)) selected - ids.toSet() else selected + ids
    }

    /** What multi-select's *Delete · {size}* frees. */
    fun selectedBytes(): Long = page().bytesOf(selected)

    // ─── asking ───
    fun askOne(row: DownloadRow) { pending = PendingDelete(PendingDelete.Kind.ONE, row.record.title, setOf(row.itemId), row.heldBytes) }

    fun askSelection() {
        if (selected.isEmpty()) return
        pending = PendingDelete(PendingDelete.Kind.SELECTION, null, selected, page().bytesOf(selected))
    }

    fun askSeries(group: SeriesGroup) {
        pending = PendingDelete(PendingDelete.Kind.SERIES, group.title, group.episodes.map { it.itemId }.toSet(), group.bytes)
    }

    fun askWatched() {
        val m = page()
        val ids = m.downloaded.filter { it.watched }.map { it.itemId }.toSet()
        if (ids.isEmpty()) return
        pending = PendingDelete(PendingDelete.Kind.WATCHED, null, ids, m.watchedBytes)
    }

    fun askAll() {
        val m = page()
        if (m.isEmpty) return
        pending = PendingDelete(PendingDelete.Kind.ALL, null, controller.records.value.map { it.itemId }.toSet(), m.deleteAllBytes)
    }

    fun askOtherProfiles() {
        val bytes = controller.otherBytes.value
        if (bytes <= 0L) return
        pending = PendingDelete(PendingDelete.Kind.OTHER_PROFILES, null, emptySet(), bytes)
    }

    // ─── answering ───
    /** [ask] is the delete the viewer was shown: a sheet closes (and cancels) before its button runs, so the button carries what it was asked about. */
    fun confirm(ask: PendingDelete? = pending) {
        val p = ask ?: return
        pending = null
        if (p.kind == PendingDelete.Kind.OTHER_PROFILES) controller.deleteOtherProfiles() else controller.deleteMany(p.ids)
        selecting = false
        selected = emptySet()
    }

    fun cancel() { pending = null }
}
