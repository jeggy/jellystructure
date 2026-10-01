package dev.jellystructure.audiobooks

import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookAuthorRow
import dev.jellystructure.model.AudiobookRow
import dev.jellystructure.model.AudiobookRules
import dev.jellystructure.model.AudiobookSeriesRow
import dev.jellystructure.model.MusicArt
import dev.jellystructure.model.MusicFacet
import dev.jellystructure.model.MusicFacetValue

/**
 * Phase 280 (FR-280-8) — the Audiobooks kind: Books · Authors · Series (the last only when any book has one, M6·3),
 * with facets counted here against every other active facet, as the Music kind's are.
 */
object AudiobooksBrowse {
    val FACETS = listOf(
        "cover" to "Cover", "narrator" to "Narrator", "description" to "Description", "parts" to "Parts",
        "needs" to "Needs you", "format" to "Format", "language" to "Language", "lib" to "Library",
    )
    private val FIXED = mapOf(
        "cover" to listOf("has" to "has a cover", "missing" to "missing"),
        "narrator" to listOf("has" to "has a narrator", "missing" to "none"),
        "description" to listOf("has" to "has a description", "missing" to "none"),
        "parts" to listOf("single" to "one file", "multi" to "several files"),
        "needs" to listOf("missing_part" to "a part is missing", "two_books" to "two books in one folder", "none" to "nothing"),
    )

    fun flag(b: Audiobook): String? = when {
        b.gap.isNotEmpty() && !b.gapDismissed -> "missing_part"
        b.albumTags.size > 1 && !b.twoInOneDismissed -> "two_books"
        else -> null
    }

    private fun format(container: String?): String = when (container?.lowercase()?.substringBefore(',')) {
        "mp3" -> "MP3"; "m4b", "m4a", "mp4", "mov" -> "M4B"; "flac" -> "FLAC"; null -> "other"; else -> "other"
    }

    class Result(val total: Int, val facets: List<MusicFacet>, val books: List<AudiobookRow>, val authors: List<AudiobookAuthorRow>, val series: List<AudiobookSeriesRow>)

    fun browse(s: AudiobooksStore.Snapshot, view: String, selected: Map<String, Set<String>>, query: String?, libraryNames: Map<String, String>, finishedBy: (String) -> Int, sort: String? = null, triage: String? = null): Result {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        // Phase 293 (FR-293-2) — a Dashboard row's key narrows the books before the facets; an unknown key narrows nothing.
        val tk = triage?.takeIf { it in dev.jellystructure.music.MusicTriage.AUDIOBOOKS }
        val live = s.books.values.filter { it.missingSince == null && (tk == null || dev.jellystructure.music.MusicTriage.book(tk, it)) }
        fun values(b: Audiobook): Map<String, Set<String>> {
            val parts = s.partsByBook[b.id].orEmpty()
            return mapOf(
                "cover" to setOf(if (b.coverState != MusicArt.NONE) "has" else "missing"),
                "narrator" to setOf(if (b.narrators.isNotEmpty()) "has" else "missing"),
                "description" to setOf(if (!b.description.isNullOrBlank()) "has" else "missing"),
                "parts" to setOf(if (b.partCount <= 1) "single" else "multi"),
                "needs" to setOf(flag(b) ?: "none"),
                "format" to parts.map { format(it.container) }.toSet(),
                "language" to setOfNotNull(b.language),
                "lib" to setOfNotNull(b.libraryId),
            )
        }
        val hit = live.filter { b -> q == null || q in b.title.lowercase() || b.authors.any { q in it.lowercase() } || b.narrators.any { q in it.lowercase() } || (b.series?.lowercase()?.contains(q) == true) }
        val vs = hit.map { it to values(it) }
        val active = selected.filterValues { it.isNotEmpty() }
        fun passes(v: Map<String, Set<String>>, skip: String?) = active.all { (k, want) -> k == skip || v[k].orEmpty().any { it in want } }
        val shown = vs.filter { passes(it.second, null) }.map { it.first }
        val facets = FACETS.map { (key, label) ->
            val counts = HashMap<String, Int>(); val universe = HashSet<String>()
            vs.forEach { (_, v) -> val own = v[key].orEmpty(); universe += own; if (passes(v, key)) own.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            val on = selected[key].orEmpty()
            val vals = FIXED[key]?.map { (v, l) -> MusicFacetValue(v, l, counts[v] ?: 0, v in on) }
                ?: (universe + on).sorted().map { v -> MusicFacetValue(v, if (key == "lib") libraryNames[v] ?: v else v, counts[v] ?: 0, v in on) }
            MusicFacet(key, label, vals)
        }
        fun row(b: Audiobook) = AudiobookRow(
            id = b.id, title = b.title, authors = b.authors, durationMs = b.durationMs, parts = b.partCount, cover = b.coverState != MusicArt.NONE,
            v = b.updatedAt, flag = flag(b), missingPart = b.gap.firstOrNull()?.takeIf { flag(b) == "missing_part" }, finishedBy = finishedBy(b.id), series = b.series,
        )
        return when (view) {
            "authors" -> {
                val byAuthor = shown.flatMap { b -> b.authors.map { AudiobookRules.authorId(it) to b } }.groupBy({ it.first }, { it.second })
                val rows = byAuthor.mapNotNull { (id, books) ->
                    val a = s.authors[id] ?: return@mapNotNull null
                    AudiobookAuthorRow(a.id, a.name, books.size, a.imageState != MusicArt.NONE)
                }.sortedBy { (s.authors[it.id]?.sortName ?: it.name).lowercase() }
                Result(rows.size, facets, emptyList(), rows, emptyList())
            }
            "series" -> {
                val rows = shown.filter { !it.series.isNullOrBlank() }.groupBy { it.series!! }.map { (n, l) -> AudiobookSeriesRow(n, l.size) }.sortedBy { it.name.lowercase() }
                Result(rows.size, facets, emptyList(), emptyList(), rows)
            }
            else -> {
                val sorted = when (sort) {
                    "title" -> shown.sortedBy { it.title.lowercase() }
                    "author" -> shown.sortedWith(compareBy({ it.authors.firstOrNull()?.let { a -> AudiobooksIngest.sortName(a) }?.lowercase() ?: "" }, { it.title.lowercase() }))
                    "series" -> shown.sortedWith(compareBy({ it.series?.lowercase() ?: "￿" }, { it.seriesPosition?.toDoubleOrNull() ?: 0.0 }, { it.title.lowercase() }))
                    else -> shown.sortedWith(compareByDescending<Audiobook> { it.addedAt ?: it.createdAt }.thenBy { it.title.lowercase() })
                }
                Result(sorted.size, facets, sorted.map { row(it) }, emptyList(), emptyList())
            }
        }
    }
}
