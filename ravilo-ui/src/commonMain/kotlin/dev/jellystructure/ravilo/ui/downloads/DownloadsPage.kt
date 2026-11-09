package dev.jellystructure.ravilo.ui.downloads

/**
 * DEB-4071 (DEB-3995 FR-9, FR-10) — the Downloads page as data: what is shown, in what order, with which totals. Pure, so grouping,
 * ordering and the per-profile figures are proved without a screen. Device state only: nothing here reaches the server.
 */
data class DownloadRow(val record: DownloadRecord, val phase: DownloadPhase) {
    val itemId: String get() = record.itemId
    /** What a delete of this entry frees: the whole size when it is on the phone, the part on the disk while it runs. */
    val heldBytes: Long get() = heldBytes(record, phase)
    val watched: Boolean get() = record.watched
    val gone: Boolean get() = record.gone
}

/** One season of a series on this phone: its episodes in episode order. [season] is null for an episode the server gave no season. */
data class SeasonGroup(val season: Int?, val episodes: List<DownloadRow>) {
    val bytes: Long get() = episodes.sumOf { it.heldBytes }
}

data class SeriesGroup(val seriesId: String, val title: String, val seasons: List<SeasonGroup>) {
    val episodes: List<DownloadRow> get() = seasons.flatMap { it.episodes }
    val bytes: Long get() = seasons.sumOf { it.bytes }
    val episodeCount: Int get() = seasons.sumOf { it.episodes.size }
}

data class DownloadsPageModel(
    /** FR-9 (amended) — the block at the top: running, queued, waiting, paused and failed entries, in queue order. Empty hides the block. */
    val downloading: List<DownloadRow>,
    /** Films fully on the phone. */
    val films: List<DownloadRow>,
    /** Series with at least one episode fully on the phone. */
    val series: List<SeriesGroup>,
) {
    val filmsBytes: Long get() = films.sumOf { it.heldBytes }
    val seriesBytes: Long get() = series.sumOf { it.bytes }
    /** Every title fully on the phone, in the order the page lists them. */
    val downloaded: List<DownloadRow> get() = films + series.flatMap { it.episodes }
    /** Nothing at all for this profile: no block and no list (FR-9's empty state). */
    val isEmpty: Boolean get() = downloading.isEmpty() && films.isEmpty() && series.isEmpty()
    /** This profile's total: what the title bar, Profile's row and Delete all name (FR-9 amended). */
    val profileBytes: Long get() = downloading.sumOf { it.heldBytes } + filmsBytes + seriesBytes
    /** What *Delete watched* frees. */
    val watchedBytes: Long get() = downloaded.filter { it.watched }.sumOf { it.heldBytes }
    /** What *Delete all* frees: the same figure as the title bar. */
    val deleteAllBytes: Long get() = profileBytes
}

fun heldBytes(record: DownloadRecord, phase: DownloadPhase): Long = when (phase) {
    DownloadPhase.Downloaded -> record.bytes
    is DownloadPhase.Running -> record.bytes * phase.percent.coerceIn(0, 100) / 100
    is DownloadPhase.Paused -> record.bytes * phase.percent.coerceIn(0, 100) / 100
    else -> 0L
}.coerceAtLeast(0L)

/**
 * FR-9 — group a viewer's [records] by their [phases]. A record with no engine phase is **queued** (it has no ticket yet), unless the
 * engine has nothing for a record already handed over (a download that vanished), which is left out rather than drawn as a ghost.
 */
fun buildDownloadsPage(records: List<DownloadRecord>, phases: Map<String, DownloadPhase>): DownloadsPageModel {
    val rows = records.mapNotNull { r ->
        val p = phases[r.itemId] ?: if (r.state == RecordState.QUEUED) DownloadPhase.Queued else return@mapNotNull null
        DownloadRow(r, p)
    }
    val (done, active) = rows.partition { it.phase == DownloadPhase.Downloaded }
    val films = done.filter { it.record.kind != "episode" }.sortedBy { it.record.title.lowercase() }
    val series = done.filter { it.record.kind == "episode" }
        .groupBy { it.record.seriesId ?: it.record.seriesTitle ?: it.itemId }
        .map { (id, eps) ->
            SeriesGroup(
                seriesId = eps.first().record.seriesId ?: id,
                title = eps.firstNotNullOfOrNull { it.record.seriesTitle } ?: eps.first().record.title,
                seasons = eps.groupBy { it.record.season }.entries
                    .sortedBy { it.key ?: Int.MAX_VALUE }   // a season-less episode last
                    .map { (s, list) -> SeasonGroup(s, list.sortedBy { it.record.episode ?: Int.MAX_VALUE }) },
            )
        }
        .sortedBy { it.title.lowercase() }
    return DownloadsPageModel(downloading = active.sortedBy { it.record.queuedAt }, films = films, series = series)
}

/** The storage bar's three parts (FR-9): Ravilo's, other apps' and free, as fractions of the volume. */
data class StorageBar(val ravilo: Long, val otherApps: Long, val free: Long) {
    val total: Long get() = ravilo + otherApps + free
    fun fraction(part: Long): Float = if (total <= 0L) 0f else (part.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * FR-9 (amended) — [raviloBytes] is **everything Ravilo holds**, other profiles' included, because that is the space the app uses;
 * what is neither Ravilo's nor free is other apps'. An unknown volume ([totalBytes] 0) draws an empty bar rather than a wrong one.
 */
fun storageBar(raviloBytes: Long, freeBytes: Long, totalBytes: Long): StorageBar {
    if (totalBytes <= 0L) return StorageBar(0, 0, 0)
    val free = freeBytes.coerceIn(0L, totalBytes)
    val ravilo = raviloBytes.coerceIn(0L, totalBytes - free)
    return StorageBar(ravilo, totalBytes - free - ravilo, free)
}

/** What deleting [ids] frees: every confirm (a swipe, a selection, a series) names its size from this one place. */
fun DownloadsPageModel.bytesOf(ids: Set<String>): Long =
    (downloading + downloaded).filter { it.itemId in ids }.sumOf { it.heldBytes }
