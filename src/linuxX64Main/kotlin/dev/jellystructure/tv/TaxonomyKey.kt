package dev.jellystructure.tv

/**
 * Phase 216 (FR-216-9) — the ONE normaliser both counting and filtering call for studio, network,
 * genre and tag values.
 *
 * Before this phase the two sides already disagreed: [BrowseService.facets] and the admin Metadata
 * routes grouped with an exact-match `groupBy`, while [BrowseService.browse] and [ConditionEvaluator]
 * compared with `equals(ignoreCase = true)`. So `HBO Nordic` and `HBO  nordic` were two tiles whose
 * grids each returned both titles — a count that its own grid contradicted. Normalisation that lands
 * on only one side moves that disagreement rather than closing it, which is why this is a shared
 * object and not a private helper in either caller. The R243 acceptance test (tile count == grid
 * count, for every value, for every profile) is what proves it.
 *
 * [key] is the grouping/matching identity: trimmed, internal whitespace collapsed, lowercased.
 * [display] is what a viewer sees: trimmed and collapsed, case preserved. When several spellings
 * share a key, [Counter] displays the most frequent one (ties broken by first seen).
 */
object TaxonomyKey {
    private val WS = Regex("\\s+")

    fun display(raw: String): String = raw.trim().replace(WS, " ")
    fun key(raw: String): String = display(raw).lowercase()

    /** `true` when [candidate] names the same value as [wanted] under [key]. */
    fun matches(candidate: String?, wanted: String): Boolean =
        candidate != null && key(candidate) == key(wanted)

    /** One value / count pair after grouping — `name` is the winning spelling (see [Counter]). */
    data class Entry(val name: String, val count: Int)

    /**
     * Groups raw spellings by [key], counts one per [add] call, and remembers which spelling of each
     * key was seen most often so the wall shows `HBO Nordic` rather than whichever variant a scan
     * happened to write first. Blank values are ignored entirely (a blank studio is "no studio").
     */
    class Counter {
        private val counts = HashMap<String, Int>()
        private val spellings = HashMap<String, HashMap<String, Int>>()
        private val order = ArrayList<String>()

        fun add(raw: String?) {
            if (raw == null) return
            val shown = display(raw)
            if (shown.isEmpty()) return
            val k = shown.lowercase()
            if (counts.put(k, (counts[k] ?: 0) + 1) == null) order.add(k)
            val bySpelling = spellings.getOrPut(k) { HashMap() }
            bySpelling[shown] = (bySpelling[shown] ?: 0) + 1
        }

        fun isEmpty() = counts.isEmpty()

        /** Count-descending, display name (case-insensitive) as tie-break — FR-216-1's fixed order. */
        fun entries(): List<Entry> = order.map { k ->
            val best = spellings.getValue(k).entries.maxByOrNull { it.value }!!.key
            Entry(best, counts.getValue(k))
        }.sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.name.lowercase() })
    }
}
