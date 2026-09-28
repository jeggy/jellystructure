package dev.jellystructure.suggestions

import kotlin.math.max
import kotlin.math.pow

/**
 * Phase 274 — films the household does not have yet, from the household's own watching. Pure: every input is
 * passed in (the graph from Seerr, the exclusions, the clock's year) and an ordered list comes out, so the rules are
 * tested as written. [SuggestionService] fetches the inputs and stores the result.
 *
 * Deterministic: every ordering breaks ties by TMDB id, and nothing iterates a hash map into an order.
 */
object SuggestionEngine {
    /** FR-274-6 / Q3 — kept, and shown before *Show more*. */
    const val KEEP = 50
    const val SHOWN = 20
    /** R320 — one viewer's Request row. */
    const val PER_VIEWER = 20
    /** How many sources a build asks Seerr about (two requests each): the household's heaviest, and each viewer's own. */
    const val SOURCES_MAX = 60
    const val SOURCES_PER_VIEWER = 20

    // ─── Sources (FR-274-2) ───────────────────────────────────────────────────

    /** A film finished weighs 1, in progress 0.5; watched in the last 60 days ×1.5, longer than 180 days ago ×0.7. */
    fun sourceWeight(finished: Boolean, ageDays: Double): Double =
        (if (finished) 1.0 else 0.5) * when { ageDays <= 60 -> 1.5; ageDays > 180 -> 0.7; else -> 1.0 }

    /** *Already seen it* (FR-274-11) — a source at 0.7. */
    const val ALREADY_SEEN_WEIGHT = 0.7

    data class Source(
        val tmdbId: Int,
        val title: String,
        val weight: Double,
        /** `finished` · `watching` — the *because* line's verb. */
        val verb: String,
        /** When it was watched, epoch seconds — the *because* line names the newest first. */
        val at: Long,
    )

    data class Viewer(val userId: String, val name: String, val sources: List<Source>) {
        val finished: Int get() = sources.count { it.verb == VERB_FINISHED }
    }

    const val VERB_FINISHED = "finished"
    const val VERB_WATCHING = "watching"

    // ─── Candidates (FR-274-3) ────────────────────────────────────────────────

    data class Cand(
        val tmdbId: Int,
        val title: String,
        val year: Int? = null,
        val rating: Double? = null,
        val votes: Int? = null,
        val genreIds: List<Int> = emptyList(),
    )

    /** FR-274-3.5 — TMDB rating ≥ 6.3 with ≥ 400 votes, or ≥ 100 votes for a release this year or last. */
    fun passesFloor(c: Cand, thisYear: Int): Boolean {
        val r = c.rating ?: return false
        val v = c.votes ?: 0
        return r >= 6.3 && (v >= 400 || (isRecent(c, thisYear) && v >= 100))
    }

    fun isRecent(c: Cand, thisYear: Int) = c.year != null && c.year >= thisYear - 1

    /** The tile's *new this year, few ratings yet*: through the lower floor only. */
    fun fresh(c: Cand, thisYear: Int) = isRecent(c, thisYear) && (c.votes ?: 0) < 400

    // ─── Score (FR-274-4) ─────────────────────────────────────────────────────

    /** *Not interested* weighs down what led there (0.8 each, never below 0.4); *Too old* leans newer a step each. */
    data class Leanings(val sourceMultiplier: Map<Int, Double> = emptyMap(), val tooOldSteps: Int = 0)

    fun notInterestedMultiplier(times: Int): Double = max(0.4, 0.8.pow(times))

    data class Because(val userId: String, val viewer: String, val verb: String, val titles: List<String>)

    data class Scored(val cand: Cand, val score: Double, val because: List<Because>, val viewers: Int, val sources: List<Int>)

    /**
     * Every candidate the graph reaches, scored: the weight of the sources pointing at it (each viewer's own), ×1.25
     * per additional viewer who reached it, a mild bonus for rating and for a release this year or last, and the
     * *Too old* lean. [excluded] and the floor leave it out.
     */
    fun score(
        viewers: List<Viewer>,
        graph: Map<Int, List<Cand>>,
        excluded: Set<Int>,
        leanings: Leanings,
        thisYear: Int,
    ): List<Scored> {
        val byCand = LinkedHashMap<Int, Cand>()
        // candidate → viewer → (source → edge weight)
        val edges = HashMap<Int, HashMap<String, HashMap<Int, Double>>>()
        for (v in viewers.sortedBy { it.userId }) for (s in v.sources.sortedBy { it.tmdbId }) {
            val w = s.weight * (leanings.sourceMultiplier[s.tmdbId] ?: 1.0)
            for (c in graph[s.tmdbId].orEmpty()) {
                if (c.tmdbId in excluded || c.tmdbId == s.tmdbId || !passesFloor(c, thisYear)) continue
                if (c.tmdbId !in byCand) byCand[c.tmdbId] = c
                val perViewer = edges.getOrPut(c.tmdbId) { HashMap() }.getOrPut(v.userId) { HashMap() }
                perViewer[s.tmdbId] = max(perViewer[s.tmdbId] ?: 0.0, w)   // recommended *and* similar is one edge
            }
        }
        val viewerById = viewers.associateBy { it.userId }
        return byCand.values.map { c ->
            val per = edges.getValue(c.tmdbId)
            val base = per.values.sumOf { it.values.sum() }
            val reached = per.size
            val year = c.year
            val lean = if (year == null || leanings.tooOldSteps <= 0) 1.0
                else 1.0 / (1.0 + 0.15 * leanings.tooOldSteps * max(0, thisYear - year - 10) / 10.0)
            val score = base * 1.25.pow(reached - 1) *
                (1.0 + max(0.0, (c.rating ?: 0.0) - 6.3) * 0.08) *
                (if (isRecent(c, thisYear)) 1.1 else 1.0) * lean
            val because = per.entries.sortedWith(compareByDescending<Map.Entry<String, HashMap<Int, Double>>> { it.value.values.sum() }.thenBy { it.key })
                .flatMap { (uid, srcs) ->
                    val v = viewerById.getValue(uid)
                    val sources = v.sources.filter { it.tmdbId in srcs }.sortedWith(compareByDescending<Source> { it.at }.thenBy { it.tmdbId })
                    listOf(VERB_FINISHED, VERB_WATCHING).mapNotNull { verb ->
                        sources.filter { it.verb == verb }.takeIf { it.isNotEmpty() }?.let { Because(uid, v.name, verb, it.take(3).map { s -> s.title }) }
                    }
                }
            Scored(c, score, because, reached, per.values.flatMap { it.keys }.distinct().sorted())
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.cand.tmdbId })
    }

    // ─── One per franchise (FR-274-4) ─────────────────────────────────────────

    data class Pick(val scored: Scored, val franchiseOf: String? = null)

    /**
     * One film per TMDB collection: the best-scoring member — except that when the library owns nothing of that
     * collection, the collection's **first** film is offered and the film the graph pointed at becomes the
     * franchise note. [collectionOf] is each candidate's collection; [firstOf] the collection's first film (null when
     * unknown); a first film that is itself excluded falls back to the best member.
     */
    fun onePerFranchise(
        scored: List<Scored>,
        collectionOf: Map<Int, Int?>,
        ownedCollections: Set<Int>,
        firstOf: Map<Int, Cand?>,
        excluded: Set<Int>,
    ): List<Pick> {
        val seen = HashSet<Int>()
        val taken = HashSet<Int>()
        val out = ArrayList<Pick>()
        for (s in scored) {
            val coll = collectionOf[s.cand.tmdbId]
            if (coll == null) { if (taken.add(s.cand.tmdbId)) out += Pick(s); continue }
            if (!seen.add(coll)) continue
            val first = firstOf[coll]
            if (coll !in ownedCollections && first != null && first.tmdbId != s.cand.tmdbId && first.tmdbId !in excluded && first.tmdbId !in taken) {
                taken += first.tmdbId
                out += Pick(s.copy(cand = first), franchiseOf = s.cand.title)
            } else if (taken.add(s.cand.tmdbId)) out += Pick(s)
        }
        return out
    }

    // ─── Clusters (FR-274-5) ──────────────────────────────────────────────────

    data class ClusterDef(val name: String, val genreIds: Set<Int>, val keywordIds: Set<Int> = emptySet())

    /** The fixed six from the trial, by TMDB genre (film and series ids both), in the order a candidate is placed. */
    val FALLBACK = listOf(
        ClusterDef("Horror", setOf(27)),
        ClusterDef("Family & animation", setOf(10751, 16, 10762)),
        ClusterDef("Thriller & crime", setOf(53, 80, 9648)),
        ClusterDef("Sci-fi & action", setOf(878, 28, 12, 14, 10759, 10765)),
        ClusterDef("Comedy", setOf(35)),
        ClusterDef("Drama", setOf(18, 10749, 36, 10752, 99, 10402, 37, 10770, 10768, 10766)),
    )

    /** A candidate's fallback cluster: the first of the six its genres touch; anything else is Drama. */
    fun fallbackCluster(genreIds: Collection<Int>): String =
        FALLBACK.firstOrNull { d -> genreIds.any { it in d.genreIds } }?.name ?: "Drama"

    /** One finished title as the shares read it: a film at its source weight, a series at a tenth of a film per
     *  finished episode, capped at three films' worth (Q6). */
    data class Taste(val genreIds: Set<Int>, val keywordIds: Set<Int>, val weight: Double, val film: Boolean)

    fun seriesWeight(finishedEpisodes: Int): Double = minOf(3.0, 0.1 * finishedEpisodes)

    data class Share(val weight: Double, val finishedFilms: Int)

    /** **Counted by the server, never by the model:** a title's weight goes to every cluster its genres or keywords
     *  fall in, split evenly between them so no title is counted twice. */
    fun shares(defs: List<ClusterDef>, taste: List<Taste>): List<Share> {
        val w = DoubleArray(defs.size)
        val n = IntArray(defs.size)
        for (t in taste) {
            val hit = defs.indices.filter { i -> t.genreIds.any { it in defs[i].genreIds } || t.keywordIds.any { it in defs[i].keywordIds } }
            if (hit.isEmpty()) continue
            for (i in hit) { w[i] += t.weight / hit.size; if (t.film) n[i]++ }
        }
        return defs.indices.map { Share(w[it], n[it]) }
    }

    /**
     * The [total] shown slots, in proportion to [shares] (largest remainder, one at a time to the cluster furthest
     * below its due share), each cluster with a candidate getting at least one, none getting more than it has.
     */
    fun slots(shares: List<Double>, available: List<Int>, total: Int = SHOWN): List<Int> {
        val alloc = IntArray(shares.size)
        val eligible = shares.indices.filter { available[it] > 0 }
        if (eligible.isEmpty()) return alloc.toList()
        val sum = eligible.sumOf { shares[it] }
        val due = { i: Int -> if (sum > 0) total * shares[i] / sum else total.toDouble() / eligible.size }
        var left = total
        for (i in eligible.sortedWith(compareByDescending<Int> { shares[it] }.thenBy { it })) { if (left == 0) break; alloc[i] = 1; left-- }
        while (left > 0) {
            val i = eligible.filter { alloc[it] < available[it] }.maxWithOrNull(compareBy<Int> { due(it) - alloc[it] }.thenBy { -it }) ?: break   // ties: the earlier cluster
            alloc[i]++; left--
        }
        return alloc.toList()
    }

    /** One stored cluster and its candidates in score order. */
    data class Grouped(val name: String, val share: Double, val finishedFilms: Int, val slots: Int, val picks: List<Pick>)

    /**
     * The list as the page shows it: clusters in share order (a cluster with no candidates is absent), each giving
     * its best [Grouped.slots] to the 20 shown; the rest follow under *Show more*. [assign] maps a candidate to a
     * cluster index of [defs].
     */
    fun group(picks: List<Pick>, defs: List<ClusterDef>, shares: List<Share>, assign: (Pick) -> Int, total: Int = SHOWN): List<Grouped> {
        val members = defs.indices.map { i -> picks.filter { assign(it) == i } }
        val alloc = slots(shares.map { it.weight }, members.map { it.size }, total)
        return defs.indices.filter { members[it].isNotEmpty() }
            .sortedWith(compareByDescending<Int> { shares[it].weight }.thenByDescending { members[it].size }.thenBy { it })
            .map { i -> Grouped(defs[i].name, shares[i].weight, shares[i].finishedFilms, alloc[i], members[i]) }
    }

    /** Stored order: the shown ones cluster by cluster, then the rest cluster by cluster. Each with whether it shows. */
    fun ordered(groups: List<Grouped>): List<Triple<Pick, String, Boolean>> =
        groups.flatMap { g -> g.picks.take(g.slots).map { Triple(it, g.name, true) } } +
            groups.flatMap { g -> g.picks.drop(g.slots).map { Triple(it, g.name, false) } }
}
