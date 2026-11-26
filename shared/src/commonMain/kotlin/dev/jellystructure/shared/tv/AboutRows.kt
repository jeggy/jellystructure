package dev.jellystructure.shared.tv

import dev.jellystructure.shared.formatSize

/** A string lookup: the key and its variables, the way `t(key, lang, vars)` takes them. */
typealias AboutStr = (key: String, vars: Map<String, String>) -> String

/**
 * R325 (FR-R325-1) — the rows of a title's *About* block in the spec's order, label already translated through [str], each absent when
 * its fact is. DEB-5699 (DEB-5685, FR-56) moved it here from ravilo-ui's `AboutSection` so the Samsung draws the same rows; each caller
 * passes its own string lookup.
 */
fun aboutRows(a: AboutFacts, series: Boolean, str: AboutStr): List<Pair<String, String>> {
    fun s(key: String, vars: Map<String, String> = emptyMap()) = str(key, vars)
    return buildList {
        a.runtimeMin?.takeIf { it > 0 }?.let {
            add(s("about.runtime") to (if (series) s("about.per_episode", mapOf("min" to it.toString())) else s("about.minutes", mapOf("min" to it.toString()))))
        }
        a.released?.takeIf { it.isNotBlank() }?.let { add((if (series) s("about.first_aired") else s("about.released")) to aboutDate(it, str)) }
        a.director?.takeIf { it.isNotBlank() }?.let { add(s("about.director") to it) }
            ?: a.creator?.takeIf { it.isNotBlank() }?.let { add(s("about.creator") to it) }
        a.studioOrNetwork?.takeIf { it.isNotBlank() }?.let { add((if (series) s("about.network") else s("about.studio")) to it) }
        a.country?.takeIf { it.isNotBlank() }?.let { add(s("about.country") to (countryName(it) ?: it)) }
        a.originalLanguage?.takeIf { it.isNotBlank() }?.let { add(s("about.original_language") to (endonymOf(it) ?: languageName(it) ?: it)) }
        if (series && a.seasons != null && a.episodes != null) {
            add(s("about.seasons") to s("about.seasons_value", mapOf("seasons" to a.seasons.toString(), "episodes" to a.episodes.toString())))
        }
        a.added?.takeIf { it > 0 }?.let { add(s("about.in_library_since") to aboutDate(epochDayIso(it), str)) }
        // DEB-3711 (FR-2/3/5) — last; the admin chip's text; a series' whole total; nothing for unknown or zero.
        a.sizeBytes?.takeIf { it > 0 }?.let { add(s("about.size") to formatSize(it)) }
    }
}

/** `2024-03-08` → `8 March 2024` in the viewer's language (`about.month.N`); a partial date is shown as it is. */
fun aboutDate(iso: String, str: AboutStr): String {
    val parts = iso.split('-')
    val y = parts.getOrNull(0)?.toIntOrNull() ?: return iso
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return iso
    val month = str("about.month.$m", emptyMap())
    val d = parts.getOrNull(2)?.toIntOrNull() ?: return "$month $y"
    return str("about.date", mapOf("day" to d.toString(), "month" to month, "year" to y.toString()))
}

/** Epoch seconds → `YYYY-MM-DD` (UTC), without a platform date library. */
fun epochDayIso(epochSeconds: Long): String {
    var days = epochSeconds / 86_400
    var year = 1970
    while (true) {
        val len = if (isLeap(year)) 366 else 365
        if (days < len) break
        days -= len; year++
    }
    val months = intArrayOf(31, if (isLeap(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 1
    for (len in months) { if (days < len) break; days -= len; month++ }
    return "$year-${month.toString().padStart(2, '0')}-${(days + 1).toString().padStart(2, '0')}"
}

private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
