package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.DownloadOption

/** One row of the quality sheet (FR-3). A row that does not fit the phone is [enabled] = false and carries [shortfallBytes]. */
data class QualityRow(
    val option: DownloadOption,
    val enabled: Boolean,
    val selected: Boolean,
    /** How much must be freed for this row to fit; 0 when it does. Feeds `download.no_room`'s `{size}`. */
    val shortfallBytes: Long,
    /** What pressing the button downloads at this row: the row's size for a title; for a season, only the episodes not already on the phone. */
    val downloadBytes: Long = option.bytes,
)

/**
 * The sheet as numbers (FR-3, FR-5). [buttonBytes] is what pressing the action downloads: the selected row's size for a title,
 * and for a season only the episodes not already on the phone. [canDownload] is false when no row fits.
 */
data class QualitySheetModel(
    val rows: List<QualityRow>,
    val freeBytes: Long,
    val buttonBytes: Long,
    /** A season only: how many of its episodes are already on this phone, and how many it has. */
    val haveEpisodes: Int = 0,
    val allEpisodes: Int = 0,
) {
    val selected: QualityRow? get() = rows.firstOrNull { it.selected }
    val canDownload: Boolean get() = selected?.enabled == true
    /** A season with every episode already on the phone has nothing left to download. */
    val isSeason: Boolean get() = allEpisodes > 0
}

private fun fits(bytes: Long, freeBytes: Long) = bytes <= freeBytes - DOWNLOAD_RESERVE_BYTES

private fun rowsFor(options: List<DownloadOption>, freeBytes: Long, lastPick: String?): List<QualityRow> {
    // Original first, then the smaller copies from the largest down: the order the server lists them in, normalised.
    val order = listOf("original", "high", "medium", "small")
    val sorted = options.sortedBy { order.indexOf(it.quality).let { i -> if (i < 0) order.size else i } }
    // The last pick wins when it is still there and fits; otherwise the first row that fits (FR-3: two taps, never a trap).
    val pick = sorted.firstOrNull { it.quality == lastPick && fits(it.bytes, freeBytes) }
        ?: sorted.firstOrNull { fits(it.bytes, freeBytes) }
    return sorted.map { o ->
        val ok = fits(o.bytes, freeBytes)
        QualityRow(o, enabled = ok, selected = o === pick, shortfallBytes = if (ok) 0L else o.bytes - (freeBytes - DOWNLOAD_RESERVE_BYTES), downloadBytes = o.bytes)
    }
}

/** FR-3 — the sheet for one film or episode. [lastPick] is the last quality picked for a title of the same kind (FR-17: device-local). */
fun buildQualitySheet(options: List<DownloadOption>, freeBytes: Long, lastPick: String?): QualitySheetModel {
    val rows = rowsFor(options, freeBytes, lastPick)
    return QualitySheetModel(rows, freeBytes, buttonBytes = rows.firstOrNull { it.selected }?.option?.bytes ?: 0L)
}

/** One episode of a season, as the sheet needs it: what the server offers for it and whether it is already on the phone. */
data class SeasonEpisode(val itemId: String, val onPhone: Boolean, val options: List<DownloadOption>)

/**
 * FR-5 — the season's sheet. A quality is listed only when **every** episode offers it, so pressing it never leaves a hole. Each
 * row shows the season's total at that quality (every episode, so the viewer sees what the season costs); the **button** names
 * what pressing it downloads, which counts only the episodes not already on the phone. A copy's total is an estimate, so a row
 * is exact only when every episode's is.
 */
fun buildSeasonSheet(episodes: List<SeasonEpisode>, freeBytes: Long, lastPick: String?): QualitySheetModel {
    if (episodes.isEmpty()) return QualitySheetModel(emptyList(), freeBytes, 0L)
    val common = episodes.map { e -> e.options.map { it.quality }.toSet() }.reduce { a, b -> a intersect b }
    val totals = common.map { q ->
        val opts = episodes.map { e -> e.options.first { it.quality == q } }
        DownloadOption(
            quality = q,
            height = opts.mapNotNull { it.height }.maxOrNull(),
            bytes = opts.sumOf { it.bytes },
            exact = opts.all { it.exact },
        )
    }
    // Fit is judged on what would actually be downloaded, not on the season's total: a half-downloaded season still fits.
    val toFetch = episodes.filter { !it.onPhone }
    fun fetchBytes(q: String) = toFetch.sumOf { e -> e.options.first { it.quality == q }.bytes }
    val order = listOf("original", "high", "medium", "small")
    val sorted = totals.sortedBy { order.indexOf(it.quality).let { i -> if (i < 0) order.size else i } }
    val pick = sorted.firstOrNull { it.quality == lastPick && fits(fetchBytes(it.quality), freeBytes) }
        ?: sorted.firstOrNull { fits(fetchBytes(it.quality), freeBytes) }
    val rows = sorted.map { o ->
        val need = fetchBytes(o.quality)
        val ok = fits(need, freeBytes)
        QualityRow(o, enabled = ok, selected = o === pick, shortfallBytes = if (ok) 0L else need - (freeBytes - DOWNLOAD_RESERVE_BYTES), downloadBytes = need)
    }
    return QualitySheetModel(
        rows = rows,
        freeBytes = freeBytes,
        buttonBytes = pick?.let { fetchBytes(it.quality) } ?: 0L,
        haveEpisodes = episodes.count { it.onPhone },
        allEpisodes = episodes.size,
    )
}
