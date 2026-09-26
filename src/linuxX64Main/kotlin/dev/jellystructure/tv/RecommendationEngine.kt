package dev.jellystructure.tv

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinPlayItem
import dev.jellystructure.media.GenreCatalog
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.recencyKey
import dev.jellystructure.util.isoToEpochSeconds
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Phase 269 — the recommendation engine, pure: inputs in, an ordered list out, no I/O, no clock of its
 * own (the caller passes `nowSec`). [RecommendationService] fetches the inputs and stores the result.
 *
 * The weights are the ones a prototype was run with over production's data on 2026-09-26 (recorded in
 * the spec), plus what that run taught: genres compared by id (Phase 271), and a quality floor.
 *
 * Deterministic (FR-269-5): every ordering breaks ties by Jellyfin id, and nothing iterates a hash map
 * into an order.
 */
object RecommendationEngine {
    /** FR-269-1 — how many each viewer has (owner, 2026-09-26). */
    const val LIST_SIZE = 50
    /** FR-269-6 — how many a scope's starter list keeps, so a viewer's watched titles can be skipped. */
    const val STARTER_SIZE = 200
    const val HALF_LIFE_DAYS = 120.0
    /** FR-269-5 — never recommended: rated below this by at least [QUALITY_FLOOR_VOTES] voters. */
    const val QUALITY_FLOOR = 5.5
    const val QUALITY_FLOOR_VOTES = 1000L
    const val NEW_DAYS = 30
    /** FR-269-4 — a film started, stopped under 10 % and untouched this long reads as "not for me". */
    const val WEAK_NEGATIVE_DAYS = 30
    /** Below this a scored title is not "like what you watch"; the list is topped up from the starter. */
    const val MIN_RELEVANCE = 0.02

    const val REASON_WATCHED = "watched"
    const val REASON_HOUSEHOLD = "household"
    const val REASON_RATED = "rated"
    const val REASON_NEW = "new"

    // Feature families and their weights (the prototype's), before the inverse-document-frequency factor.
    private const val W_GENRE = 1.0
    private const val W_KEYWORD = 1.5
    private const val W_CAST = 0.8
    private const val W_DIRECTOR = 1.5
    private const val W_STUDIO = 0.6
    private const val W_NETWORK = 0.8
    private const val W_COLLECTION = 1.5
    private const val W_LANGUAGE = 1.0
    private const val W_DECADE = 0.4
    private val BILLING = listOf(1.0, 0.9, 0.8, 0.7, 0.6)
    private val CREATOR_JOBS = setOf("Director", "Creator", "Showrunner")

    // ─── Scope ────────────────────────────────────────────────────────────────

    /**
     * FR-269-7 — one visibility scope: the library allow-list and the tag policy, the same three facts
     * [BrowseService.facets] keys its cache on. Stored in the database, so it must be stable across
     * restarts: a sorted canonical string, FNV-1a hashed, never `hashCode()` of a set.
     */
    fun scopeKey(device: DeviceData): String {
        val canonical = "L:" + (device.allowedLibraries?.sorted()?.joinToString(",") ?: "*") +
            "|T:" + device.allowedTags.sorted().joinToString(",") +
            "|B:" + device.blockedTags.sorted().joinToString(",")
        var h = 0xcbf29ce484222325UL
        for (b in canonical.encodeToByteArray()) { h = h xor b.toUByte().toULong(); h *= 0x100000001b3UL }
        return h.toString(16).padStart(16, '0')
    }

    // ─── Signals (FR-269-4) ───────────────────────────────────────────────────

    /** A viewer's history as weights per library title (Jellyfin id), and the titles never to recommend
     *  them: finished or started, in progress, in My List, or left as a weak negative. */
    data class Signals(val weights: Map<String, Double>, val excluded: Set<String>) {
        val hasHistory: Boolean get() = weights.values.any { it > 0.0 }
    }

    fun decay(ageDays: Double): Double = 0.5.pow(ageDays.coerceAtLeast(0.0) / HALF_LIFE_DAYS)

    private fun ageDays(iso: String?, nowSec: Long): Double =
        iso?.let { isoToEpochSeconds(it) }?.let { (nowSec - it) / 86_400.0 } ?: 365.0

    fun signals(
        played: List<JellyfinPlayItem>,
        resume: List<JellyfinPlayItem>,
        favorites: Set<String>,
        byJellyfinId: Map<String, MediaItem>,
        nowSec: Long,
    ): Signals {
        val w = HashMap<String, Double>()
        val excluded = HashSet<String>()
        fun add(id: String, v: Double) { w[id] = (w[id] ?: 0.0) + v }
        // Films: finished = 1, decayed; a rewatch and a favourite count extra.
        for (p in played) {
            if (p.type != "Movie") continue
            val item = byJellyfinId[p.id] ?: continue
            val u = p.userData
            val v = decay(ageDays(u?.lastPlayedDate, nowSec)) *
                (if ((u?.playCount ?: 0) > 1) 1.3 else 1.0) *
                (if (p.id in favorites) 1.5 else 1.0)
            add(item.jellyfinId ?: continue, v)
            excluded += p.id
        }
        // Episodes count toward their series, by how much of it was watched.
        val episodes = played.filter { it.type == "Episode" && it.seriesId != null }.groupBy { it.seriesId!! }
        for (sid in episodes.keys.sorted()) {
            val series = byJellyfinId[sid] ?: continue
            val eps = episodes.getValue(sid)
            val total = series.episodes.distinctBy { (it.seasonNumber ?: -1) to (it.episodeNumber ?: -1) }.size.coerceAtLeast(1)
            val watched = eps.distinctBy { (it.seasonNumber ?: -1) to (it.episodeNumber ?: -1) }.size
            val share = min(1.0, watched.toDouble() / total)
            val newest = eps.minOf { ageDays(it.userData?.lastPlayedDate, nowSec) }
            val v = (0.3 + 0.7 * share) * decay(newest) *
                (if (eps.any { (it.userData?.playCount ?: 0) > 1 }) 1.3 else 1.0) *
                (if (sid in favorites) 1.5 else 1.0)
            add(sid, v)
            excluded += sid
        }
        // In progress: its share of runtime. A film stopped under 10 % and left 30 days: a weak negative.
        for (r in resume) {
            val target = r.seriesId?.let { byJellyfinId[it] } ?: byJellyfinId[r.id] ?: continue
            val id = target.jellyfinId ?: continue
            val share = ((r.userData?.playedPercentage ?: 0.0) / 100.0).coerceIn(0.0, 1.0)
            val age = ageDays(r.userData?.lastPlayedDate, nowSec)
            if (target.kind != MediaKind.TV_SHOW && share < 0.10 && age > WEAK_NEGATIVE_DAYS) add(id, -0.3 * decay(age))
            else if (target.kind != MediaKind.TV_SHOW) add(id, share * decay(age))
            else if (id !in w) add(id, 0.3 * decay(age))
            excluded += id
        }
        // A favourite nobody has watched yet still says what the viewer is interested in.
        for (f in favorites.sorted()) {
            if (byJellyfinId[f] == null) continue
            if (f !in w) add(f, 0.6)
            excluded += f
        }
        return Signals(w, excluded)
    }

    // ─── Features ─────────────────────────────────────────────────────────────

    /** A title's features and their family weights, before IDF. */
    fun rawFeatures(item: MediaItem): Map<String, Double> {
        val f = HashMap<String, Double>()
        fun put(k: String, v: Double) { if (v > (f[k] ?: 0.0)) f[k] = v }
        val ids = GenreCatalog.ids(item)
        item.genres.forEachIndexed { i, name -> put("g:" + (ids.getOrNull(i)?.toString() ?: TaxonomyKey.key(name)), W_GENRE) }
        item.keywords.orEmpty().forEach { put("k:${it.id}", W_KEYWORD) }
        item.cast.sortedBy { it.order }.take(BILLING.size).forEachIndexed { i, p -> if (p.tmdbId != 0) put("c:${p.tmdbId}", W_CAST * BILLING[i]) }
        item.crew.filter { it.job in CREATOR_JOBS && it.tmdbId != 0 }.forEach { put("d:${it.tmdbId}", W_DIRECTOR) }
        if (item.kind != MediaKind.TV_SHOW) item.studio?.takeIf { it.isNotBlank() }?.let { put("s:" + TaxonomyKey.key(it), W_STUDIO) }
        item.network?.takeIf { it.isNotBlank() }?.let { put("n:" + TaxonomyKey.key(it), W_NETWORK) }
        item.collectionId?.let { put("o:$it", W_COLLECTION) }
        item.originalLanguage?.takeIf { it.isNotBlank() }?.let { put("l:" + it.lowercase(), W_LANGUAGE) }
        item.year?.let { put("y:${it / 10 * 10}", W_DECADE) }
        return f
    }

    /** Every title's weighted feature vector: family weight × ln(N / titles carrying it). A feature every
     *  title has says nothing and drops out; one on six titles says a lot. */
    class Vectors(library: List<MediaItem>) {
        private val raw: Map<String, Map<String, Double>> = library.associate { (it.jellyfinId ?: it.id) to rawFeatures(it) }
        private val idf: Map<String, Double>
        init {
            val df = HashMap<String, Int>()
            for (fs in raw.values) for (k in fs.keys) df[k] = (df[k] ?: 0) + 1
            val n = raw.size.coerceAtLeast(1).toDouble()
            idf = df.mapValues { (_, d) -> ln(n / d) }
        }
        private val cache = HashMap<String, Map<String, Double>>()
        fun of(id: String): Map<String, Double> = cache.getOrPut(id) {
            raw[id].orEmpty().mapNotNull { (k, v) -> (v * (idf[k] ?: 0.0)).takeIf { it > 0.0 }?.let { k to it } }.toMap()
        }
    }

    private fun norm(v: Map<String, Double>): Double = sqrt(v.values.sumOf { it * it })
    private fun dot(a: Map<String, Double>, b: Map<String, Double>): Double {
        val (small, large) = if (a.size <= b.size) a to b else b to a
        var s = 0.0
        for ((k, v) in small) large[k]?.let { s += v * it }
        return s
    }

    // ─── Quality ──────────────────────────────────────────────────────────────

    /** IMDb first; TMDB's own votes where IMDb has no rating. Null = no rating at all. */
    private fun rating(item: MediaItem): Pair<Double, Long>? =
        item.imdbRating?.let { it.aggregateRating to it.voteCount }
            ?: item.tmdbVoteAverage?.takeIf { (item.tmdbVoteCount ?: 0) > 0 }?.let { it to (item.tmdbVoteCount ?: 0).toLong() }

    /** FR-269-5's floor. */
    fun belowFloor(item: MediaItem): Boolean =
        rating(item)?.let { (r, v) -> r < QUALITY_FLOOR && v >= QUALITY_FLOOR_VOTES } == true

    /** The quality prior, damped by vote count: −0.5 … +1.2 for a well-voted title, ~0 for a thinly voted one. */
    fun quality(item: MediaItem): Double = rating(item)?.let { (r, v) ->
        val saturation = if (item.imdbRating != null) 5000.0 else 1000.0
        ((r - 6.5) / 3.0) * min(1.0, v / saturation)
    } ?: 0.0

    private fun isNew(item: MediaItem, nowSec: Long): Boolean = nowSec - item.recencyKey() < NEW_DAYS * 86_400L

    // ─── The list (FR-269-5, FR-269-6) ────────────────────────────────────────

    data class Pick(val jellyfinId: String, val score: Double, val reason: String, val reasonItem: String? = null)

    /**
     * One viewer's list: [candidates] (what they may see) scored against [signals], the relevant ones
     * first, topped up from [starter] (their scope's), diversified, at most [LIST_SIZE]. [library] is the
     * whole live library, for the IDF and for resolving TMDB's recommendation ids.
     */
    fun build(
        signals: Signals,
        candidates: List<MediaItem>,
        library: List<MediaItem>,
        starter: List<Pick>,
        nowSec: Long,
        vectors: Vectors = Vectors(library),
    ): List<Pick> {
        val byTmdb = HashMap<String, MediaItem>()
        for (it in library) it.tmdbId?.let { t -> byTmdb["${kindKey(it.kind)}:$t"] = it }
        val byJf = library.mapNotNull { it.jellyfinId?.let { id -> id to it } }.toMap()

        // The viewer's profile: every history title's vector, by its weight (a weak negative subtracts).
        val history = signals.weights.entries.filter { it.key in byJf }.sortedBy { it.key }
        val profile = HashMap<String, Double>()
        for ((id, w) in history) for ((k, v) in vectors.of(id)) profile[k] = (profile[k] ?: 0.0) + w * v
        val profileNorm = norm(profile)

        // TMDB's own recommendations from what they watched: rank-weighted, 1 / (1 + rank / 10).
        val edge = HashMap<String, Double>()
        val edgeFrom = HashMap<String, Pair<Double, String>>()
        for ((id, w) in history) {
            if (w <= 0.0) continue
            val h = byJf.getValue(id)
            h.tmdbRecommendations.orEmpty().forEachIndexed { rank, tmdbId ->
                val c = byTmdb["${kindKey(h.kind)}:$tmdbId"]?.jellyfinId ?: return@forEachIndexed
                val contribution = w / (1.0 + rank / 10.0)
                edge[c] = (edge[c] ?: 0.0) + contribution
                if (contribution > (edgeFrom[c]?.first ?: 0.0)) edgeFrom[c] = contribution to id
            }
        }

        val eligible = candidates.filter { c ->
            val id = c.jellyfinId
            id != null && id !in signals.excluded && !belowFloor(c)
        }
        val scored = if (!signals.hasHistory) emptyList() else eligible.mapNotNull { c ->
            val id = c.jellyfinId!!
            val v = vectors.of(id)
            val vn = norm(v)
            val sim = if (profileNorm > 0 && vn > 0) dot(profile, v) / (profileNorm * vn) else 0.0
            val e = edge[id] ?: 0.0
            if (sim < MIN_RELEVANCE && e <= 0.0) return@mapNotNull null
            val score = sim + 0.25 * min(1.0, e / 1.5) + 0.12 * quality(c) + (if (isNew(c, nowSec)) 0.05 else 0.0)
            Pick(id, score, REASON_WATCHED, edgeFrom[id]?.second ?: closestHistory(v, history, vectors))
        }.sortedWith(compareByDescending<Pick> { it.score }.thenBy { it.jellyfinId })

        val chosen = LinkedHashMap<String, Pick>()
        for (p in scored) chosen[p.jellyfinId] = p
        val eligibleIds = eligible.mapTo(HashSet()) { it.jellyfinId!! }
        for (s in starter) if (s.jellyfinId in eligibleIds && s.jellyfinId !in chosen) chosen[s.jellyfinId] = s.copy(score = 0.0)
        return diversify(chosen.values.toList(), byJf, LIST_SIZE)
    }

    /** The history title a candidate is most like, for "because you watched …". */
    private fun closestHistory(v: Map<String, Double>, history: List<Map.Entry<String, Double>>, vectors: Vectors): String? =
        history.filter { it.value > 0.0 }
            .map { (id, w) -> id to w * dot(v, vectors.of(id)) }
            .filter { it.second > 0.0 }
            .maxWithOrNull(compareBy<Pair<String, Double>> { it.second }.thenByDescending { it.first })?.first

    private fun kindKey(kind: MediaKind): String = if (kind == MediaKind.TV_SHOW) "tv" else "movie"

    /**
     * FR-269-5's diversity, applied in score order: at most two titles from one collection, no genre over
     * half the list, never three in a row sharing a first genre. When nothing left fits a rule, the rule
     * relaxes (the run rule first, then the half rule) rather than cutting the list short.
     */
    fun diversify(ordered: List<Pick>, byJf: Map<String, MediaItem>, size: Int): List<Pick> {
        val remaining = ordered.toMutableList()
        val out = ArrayList<Pick>()
        val perCollection = HashMap<Int, Int>()
        val perGenre = HashMap<String, Int>()
        fun primaryGenre(p: Pick): String? = byJf[p.jellyfinId]?.let { item ->
            GenreCatalog.ids(item).firstOrNull()?.toString() ?: item.genres.firstOrNull()?.let { TaxonomyKey.key(it) }
        }
        fun collection(p: Pick): Int? = byJf[p.jellyfinId]?.collectionId
        while (out.size < size && remaining.isNotEmpty()) {
            val half = size / 2
            val runGenre = if (out.size >= 2 && primaryGenre(out[out.size - 1]) == primaryGenre(out[out.size - 2])) primaryGenre(out.last()) else null
            fun fitsCollection(p: Pick) = collection(p)?.let { (perCollection[it] ?: 0) < 2 } ?: true
            fun fitsHalf(p: Pick) = primaryGenre(p)?.let { (perGenre[it] ?: 0) < half } ?: true
            fun fitsRun(p: Pick) = runGenre == null || primaryGenre(p) != runGenre
            val idx = remaining.indexOfFirst { fitsCollection(it) && fitsHalf(it) && fitsRun(it) }
                .takeIf { it >= 0 } ?: remaining.indexOfFirst { fitsCollection(it) && fitsHalf(it) }
                .takeIf { it >= 0 } ?: remaining.indexOfFirst { fitsCollection(it) }
                .takeIf { it >= 0 } ?: break
            val p = remaining.removeAt(idx)
            out += p
            collection(p)?.let { perCollection[it] = (perCollection[it] ?: 0) + 1 }
            primaryGenre(p)?.let { perGenre[it] = (perGenre[it] ?: 0) + 1 }
        }
        return out
    }

    /**
     * FR-269-6 — a scope's starter list: what its viewers watch most (anonymous counts, only viewers of
     * this scope), then the best rated with enough votes, then the newest. [visible] is what the scope may
     * see; [householdCounts] is, per Jellyfin id, how many of the scope's viewers have watched it.
     */
    fun starter(visible: List<MediaItem>, householdCounts: Map<String, Int>, nowSec: Long): List<Pick> {
        val pool = visible.filter { it.jellyfinId != null && !belowFloor(it) }
        val out = LinkedHashMap<String, Pick>()
        pool.filter { (householdCounts[it.jellyfinId] ?: 0) > 0 }
            .sortedWith(compareByDescending<MediaItem> { householdCounts[it.jellyfinId] ?: 0 }.thenByDescending { quality(it) }.thenBy { it.jellyfinId })
            .forEach { out.getOrPut(it.jellyfinId!!) { Pick(it.jellyfinId!!, 0.0, REASON_HOUSEHOLD) } }
        pool.filter { rating(it)?.second?.let { v -> v >= QUALITY_FLOOR_VOTES } == true }
            .sortedWith(compareByDescending<MediaItem> { rating(it)!!.first }.thenBy { it.jellyfinId })
            .forEach { out.getOrPut(it.jellyfinId!!) { Pick(it.jellyfinId!!, 0.0, REASON_RATED) } }
        pool.sortedWith(compareByDescending<MediaItem> { it.recencyKey() }.thenBy { it.jellyfinId })
            .forEach { out.getOrPut(it.jellyfinId!!) { Pick(it.jellyfinId!!, 0.0, REASON_NEW) } }
        return out.values.take(STARTER_SIZE)
    }
}
