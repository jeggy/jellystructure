package dev.jellystructure.chart

import dev.jellystructure.shared.tv.ChartListSpec
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.request.header

/**
 * Phase 57 — Netflix via its official **Tudum** engagement feeds. Not HTML scraping: 3 stable TSV
 * files (schema unchanged since 2021); our 5 lists are slices of those 3 files.
 *  - all-weeks-countries.tsv → mov-<region> / tv-<region>  (rank only, no views)
 *  - all-weeks-global.tsv     → mov-global (English) / noneng (Non-English)  (views)
 *  - most-popular.tsv         → alltime  (first-91-day views)
 */
class NetflixTudumProvider : ChartProvider {
    override val id = "netflix"
    override val displayName = "Netflix"
    override val attribution = "Tudum"

    private val http = HttpClient(Curl)
    private val base = "https://www.netflix.com/tudum/top10/data"

    override fun availableLists(region: String): List<ChartListSpec> = listOf(
        ChartListSpec("mov-$region", id, "Top 10 Movies in $region", "country", "film", "rank", region),
        ChartListSpec("tv-$region", id, "Top 10 TV Shows in $region", "country", "series", "rank", region),
        ChartListSpec("mov-global", id, "Global Top 10 Movies", "global", "film", "views"),
        ChartListSpec("noneng", id, "Top 10 Non-English Films", "global", "film", "views"),
        ChartListSpec("alltime", id, "Most Popular of All Time", "alltime", "film", "views91"),
    )

    override suspend fun fetch(spec: ChartListSpec): ChartFetch = when (spec.scope) {
        "country" -> fetchCountry(spec)
        "global" -> fetchGlobal(spec)
        "alltime" -> fetchAlltime()
        else -> ChartFetch("", emptyList())
    }

    private suspend fun fetchCountry(spec: ChartListSpec): ChartFetch {
        val region = spec.region ?: return ChartFetch("", emptyList())
        val cat = if (spec.category == "series") "TV" else "Films"
        val rows = download("all-weeks-countries.tsv").filter { it["country_iso2"] == region && it["category"] == cat }
        val week = rows.maxOfOrNull { it["week"] ?: "" } ?: ""
        val entries = rows.filter { it["week"] == week }
            .sortedBy { it["weekly_rank"]?.toIntOrNull() ?: 99 }.take(10)
            .map {
                val weeks = it["cumulative_weeks_in_top_10"]?.toIntOrNull() ?: 0
                RawChartEntry(it["weekly_rank"]?.toIntOrNull() ?: 0, it["show_title"] ?: "", null, weeks, weeks <= 1)
            }
        return ChartFetch(week, entries)
    }

    private suspend fun fetchGlobal(spec: ChartListSpec): ChartFetch {
        val cat = if (spec.id == "noneng") "Films (Non-English)" else "Films (English)"
        val rows = download("all-weeks-global.tsv").filter { it["category"] == cat }
        val week = rows.maxOfOrNull { it["week"] ?: "" } ?: ""
        val entries = rows.filter { it["week"] == week }
            .sortedBy { it["weekly_rank"]?.toIntOrNull() ?: 99 }.take(10)
            .map {
                val weeks = it["cumulative_weeks_in_top_10"]?.toIntOrNull() ?: 0
                RawChartEntry(it["weekly_rank"]?.toIntOrNull() ?: 0, it["show_title"] ?: "", formatViews(it["weekly_views"]), weeks, weeks <= 1)
            }
        return ChartFetch(week, entries)
    }

    private suspend fun fetchAlltime(): ChartFetch {
        val rows = download("most-popular.tsv").filter { it["category"] == "Films (English)" }
        val entries = rows.sortedBy { it["rank"]?.toIntOrNull() ?: 99 }.take(10)
            .map { RawChartEntry(it["rank"]?.toIntOrNull() ?: 0, it["show_title"] ?: "", formatViews(it["views_first_91_days"])) }
        return ChartFetch("alltime", entries) // no week column; constant key → daily poll is cheap
    }

    private suspend fun download(file: String): List<Map<String, String>> {
        val text = http.get("$base/$file") { header("User-Agent", "Mozilla/5.0") }.body<String>()
        return parseTsv(text)
    }

    private fun parseTsv(text: String): List<Map<String, String>> {
        val lines = text.split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().split("\t").map { it.trim() }
        return lines.drop(1).map { line ->
            val cols = line.split("\t")
            header.mapIndexed { i, h -> h to (cols.getOrNull(i)?.trim() ?: "") }.toMap()
        }
    }

    private fun formatViews(v: String?): String? {
        val n = v?.toLongOrNull() ?: return null
        return when {
            n >= 1_000_000 -> "${(n / 100_000) / 10.0}M"
            n >= 1_000 -> "${n / 1_000}K"
            else -> n.toString()
        }
    }
}
