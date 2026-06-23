package dev.jellystructure.chart

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.shared.tv.ChartEntry
import dev.jellystructure.shared.tv.MediaKind
import dev.jellystructure.shared.tv.Trend

/** Phase 57 — persistence for resolved chart entries, weekly history and admin TMDB overrides. */
class ChartStore(private val db: JellystructureDb) {
    private val q get() = db.chartQueries

    fun listWeek(listId: String): String? = q.listWeek(listId).executeAsOneOrNull()

    fun entries(listId: String): List<ChartEntry> = q.entriesForList(listId).executeAsList().map(::toEntry)

    fun replaceList(listId: String, week: String, entries: List<ChartEntry>, now: Long) {
        q.transaction {
            q.deleteList(listId)
            for (e in entries) q.insertEntry(
                list_id = listId,
                rank = e.rank.toLong(),
                week = week,
                title = e.title,
                kind = e.kind.name,
                year = e.year?.toLong(),
                tmdb_id = e.tmdbId?.toLong(),
                tmdb_confidence = e.tmdbConfidence.toDouble(),
                item_id = e.itemId,
                weeks_on_chart = e.weeksOnChart.toLong(),
                trend = e.trend.name,
                is_new = if (e.isNew) 1L else 0L,
                views = e.views,
                backdrop_path = e.backdropPath,
                overview = e.overview,
                updated_at = now,
            )
        }
    }

    fun appendHistory(listId: String, week: String, entries: List<ChartEntry>) {
        q.transaction {
            for (e in entries) q.insertHistory(listId, e.title.lowercase(), week, e.rank.toLong(), e.views)
        }
    }

    fun priorRank(listId: String, title: String, week: String): Int? =
        q.priorRank(listId, title.lowercase(), week).executeAsOneOrNull()?.toInt()

    fun getOverride(provider: String, title: String, kind: String): Int? =
        q.getOverride(provider, title.lowercase(), kind).executeAsOneOrNull()?.toInt()

    fun setOverride(provider: String, title: String, kind: String, tmdbId: Int) =
        q.setOverride(provider, title.lowercase(), kind, tmdbId.toLong())

    private fun toEntry(r: dev.jellystructure.db.Chart_entry): ChartEntry = ChartEntry(
        listId = r.list_id,
        rank = r.rank.toInt(),
        title = r.title,
        kind = MediaKind.valueOf(r.kind),
        year = r.year?.toInt(),
        tmdbId = r.tmdb_id?.toInt(),
        tmdbConfidence = r.tmdb_confidence.toFloat(),
        itemId = r.item_id,
        weeksOnChart = r.weeks_on_chart.toInt(),
        trend = runCatching { Trend.valueOf(r.trend) }.getOrDefault(Trend.SAME),
        isNew = r.is_new != 0L,
        views = r.views,
        backdropPath = r.backdrop_path,
        overview = r.overview,
    )
}
