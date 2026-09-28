package dev.jellystructure.suggestions

import dev.jellystructure.ai.AiJobs
import dev.jellystructure.ai.AiRequests
import dev.jellystructure.auth.DeviceData
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.SuggestionsStep
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger
import dev.jellystructure.media.MediaStore
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.model.SuggestionActionResult
import dev.jellystructure.model.SuggestionDownloadRequest
import dev.jellystructure.model.SuggestionProfile
import dev.jellystructure.model.SuggestionRadarrServer
import dev.jellystructure.model.SuggestionRequestOptions
import dev.jellystructure.model.SuggestionBecause
import dev.jellystructure.model.SuggestionClusterDto
import dev.jellystructure.model.SuggestionDismissedDto
import dev.jellystructure.model.SuggestionItemDto
import dev.jellystructure.model.SuggestionViewerDto
import dev.jellystructure.model.SuggestionsPageDto
import dev.jellystructure.model.SuggestionsSummaryDto
import dev.jellystructure.ops.GateClass
import dev.jellystructure.resolver.CertificationResolver
import dev.jellystructure.seerr.MOVIE_GENRES
import dev.jellystructure.seerr.SeerrCatalogResult
import dev.jellystructure.seerr.SeerrClient
import dev.jellystructure.seerr.SeerrDiscoverService
import dev.jellystructure.seerr.SeerrMovieDetails
import dev.jellystructure.seerr.TV_GENRES
import dev.jellystructure.shared.tv.AcquisitionStatus
import dev.jellystructure.tv.RecommendationEngine
import dev.jellystructure.tv.RecommendationService
import dev.jellystructure.util.isoToEpochSeconds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.localtime_r
import platform.posix.time
import platform.posix.time_t
import platform.posix.time_tVar
import platform.posix.tm
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference

/**
 * Phase 274 — the household's suggestions from Seerr: [build] fetches the inputs (269's viewers and their
 * Jellyfin history, Seerr's recommendations and similar per source, the blocklist), runs [SuggestionEngine], and
 * stores the list — never scored on a request (FR-274-6). The page, the Dashboard card and R320's Request row read
 * what the last build stored.
 *
 * Absent without Seerr (FR-274-1): every entry point answers as if the feature did not exist when Seerr is not
 * configured or is disabled.
 */
class SuggestionService(
    private val db: JellystructureDb,
    private val configStore: ConfigStore,
    private val seerrClient: SeerrClient,
    private val mediaStore: MediaStore,
    private val jellyfinClient: JellyfinClient,
) {
    /** The TV's Request service, whose `request()` *Download* reuses (Q1). Set by the server once it exists. */
    var discover: SeerrDiscoverService? = null
    /** 269's service: its recently seen viewers and their history (dev review 4). Set by Main. */
    var recommendations: RecommendationService? = null
    /** The AI runner (FR-274-5). Set by Main; null = no AI. */
    var ai: AiJobs? = null

    private val buildLock = Mutex()
    private val building = AtomicInt(0)
    private var scope: CoroutineScope? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun start(scope: CoroutineScope) { this.scope = scope }

    private fun seerr() = configStore.current.seerr?.takeIf { it.enabled && it.url.isNotBlank() && it.apiKey.isNotBlank() }
    fun available(): Boolean = seerr() != null

    // ─── What a build stores ─────────────────────────────────────────────────

    @Serializable
    data class StoredItem(
        val tmdbId: Int, val title: String, val year: Int? = null, val rating: Double? = null, val votes: Int? = null,
        val runtime: Int? = null, val cert: String? = null, val age: Int? = null, val synopsis: String? = null,
        val poster: String? = null, val backdrop: String? = null, val releaseDate: String? = null,
        val genreIds: List<Int> = emptyList(), val keywordIds: List<Int> = emptyList(),
        val because: List<SuggestionBecause> = emptyList(), val viewers: Int = 1, val franchiseOf: String? = null,
        val fresh: Boolean = false, val shown: Boolean = true, val sources: List<Int> = emptyList(), val score: Double = 0.0,
    )

    @Serializable
    data class BuildInfo(
        val viewers: List<SuggestionViewerDto> = emptyList(),
        val finishedFilms: Int = 0,
        val noHistory: Boolean = false,
        /** The previous build's ids and when it ran: *{n} new since {weekday}* (FR-274-14). */
        val prevIds: List<Int> = emptyList(),
        val prevBuiltAt: Long? = null,
        /** What a later AI answer regroups against (FR-274-5): the household's watching as ids, and the weights. */
        val taste: List<AiRequests.TasteLine> = emptyList(),
        val tasteFilms: List<Boolean> = emptyList(),
    )

    // ─── Build (FR-274-7) ─────────────────────────────────────────────────────

    /** *Rebuild now* and the first save of the Seerr connection: queued in the background, never inline. */
    fun queueBuild(reason: String, by: String): Boolean {
        if (!available() || building.value != 0) return false
        val s = scope ?: return false
        s.launch(GateClass.BACKGROUND) { runCatching { build(reason, by) }.onFailure { Logger.warn("Suggestions: build failed: ${it.message}", "suggestions") } }
        return true
    }

    fun lastBuiltAt(): Long? = db.suggestionsQueries.build().executeAsOneOrNull()?.built_at

    /** One build; returns the pipeline step's one-line summary. */
    suspend fun build(reason: String = REASON_WEEKLY, by: String = AiJobs.BY_SYSTEM): String = buildLock.withLock {
        building.value = 1
        try { buildLocked(reason, by) } finally { building.value = 0 }
    }

    private suspend fun buildLocked(reason: String, by: String): String {
        val seerr = seerr() ?: return "not available — Seerr isn't connected"
        val rs = recommendations ?: return "not available"
        val now = nowSec()
        val thisYear = yearOf(now)
        val library = mediaStore.liveItems()
        val films = library.filter { it.kind == MediaKind.MOVIE }
        val libTmdb = films.mapNotNull { it.tmdbId }.toSet()
        val byJf = library.mapNotNull { m -> m.jellyfinId?.let { it to m } }.toMap()
        val ownedCollections = films.mapNotNull { it.collectionId }.toSet()
        val dismissals = db.suggestionsQueries.dismissals().executeAsList()
        val requested = db.suggestionsQueries.requests().executeAsList().map { it.tmdb_id.toInt() }.toSet()

        // Kids profiles (R320 FR-R320-4): each viewer's own Jellyfin parental cap, read once.
        val cfg = configStore.current
        val base = cfg.apiKeys.jellyfinUrl.trimEnd('/')
        val caps: Map<String, Int?> = runCatching {
            jellyfinClient.getUsersOrNull(base, cfg.apiKeys.jellyfinToken).orEmpty().associate { key(it.id) to it.policy.maxParentalRating }
        }.getOrDefault(emptyMap())

        // Sources and taste (FR-274-2, Q6).
        val viewers = ArrayList<SuggestionEngine.Viewer>()
        val devices = HashMap<String, DeviceData>()
        val taste = ArrayList<AiRequests.TasteLine>()
        val tasteFilms = ArrayList<Boolean>()
        for (device in rs.viewers().filterNot { SuggestionEngine.isTestAccount(it.jellyfinUsername) }) {
            val h = rs.historyOf(device) ?: continue
            val sources = LinkedHashMap<Int, SuggestionEngine.Source>()
            fun add(s: SuggestionEngine.Source) { val prev = sources[s.tmdbId]; if (prev == null || prev.weight < s.weight) sources[s.tmdbId] = s }
            for (p in h.played) {
                if (p.type != "Movie") continue
                val item = byJf[p.id]?.takeIf { it.kind == MediaKind.MOVIE } ?: continue
                val tmdb = item.tmdbId ?: continue
                val at = p.userData?.lastPlayedDate?.let { isoToEpochSeconds(it) } ?: 0L
                val w = SuggestionEngine.sourceWeight(true, ageDays(at, now))
                add(SuggestionEngine.Source(tmdb, item.title, w, SuggestionEngine.VERB_FINISHED, at))
                taste += tasteLine(item, "film", w); tasteFilms += true
            }
            for (r in h.resume) {
                if (r.type != "Movie") continue
                val item = byJf[r.id]?.takeIf { it.kind == MediaKind.MOVIE } ?: continue
                val tmdb = item.tmdbId ?: continue
                val at = r.userData?.lastPlayedDate?.let { isoToEpochSeconds(it) } ?: 0L
                add(SuggestionEngine.Source(tmdb, item.title, SuggestionEngine.sourceWeight(false, ageDays(at, now)), SuggestionEngine.VERB_WATCHING, at))
            }
            for ((sid, eps) in h.played.filter { it.type == "Episode" && it.seriesId != null }.groupBy { it.seriesId!! }.entries.sortedBy { it.key }) {
                val series = byJf[sid] ?: continue
                val n = eps.distinctBy { (it.seasonNumber ?: -1) to (it.episodeNumber ?: -1) }.size
                taste += tasteLine(series, "series", SuggestionEngine.seriesWeight(n)); tasteFilms += false
            }
            // *Already seen it* (FR-274-11): a source at 0.7 for the viewer the tile named.
            for (d in dismissals.filter { it.reason == REASON_ALREADY_SEEN && (it.for_user == null || it.for_user == device.jellyfinUserId) }) {
                add(SuggestionEngine.Source(d.tmdb_id.toInt(), d.title, SuggestionEngine.ALREADY_SEEN_WEIGHT, SuggestionEngine.VERB_FINISHED, d.at))
            }
            devices[device.jellyfinUserId] = device
            viewers += SuggestionEngine.Viewer(device.jellyfinUserId, device.jellyfinUsername, sources.values.toList())
        }
        val prev = db.suggestionsQueries.build().executeAsOneOrNull()
        val prevIds = db.suggestionsQueries.all().executeAsList().map { it.tmdb_id.toInt() }
        val viewerDtos = viewers.map { SuggestionViewerDto(it.name, it.finished) }
        if (viewers.all { it.sources.isEmpty() }) {
            db.transaction {
                db.suggestionsQueries.deleteAll(); db.suggestionsQueries.deleteClusters(); db.suggestionsQueries.deleteViewers()
                db.suggestionsQueries.putBuild(now, SOURCE_FALLBACK, "", json.encodeToString(BuildInfo.serializer(),
                    BuildInfo(viewerDtos, 0, noHistory = true, prevIds = prevIds, prevBuiltAt = prev?.built_at)))
            }
            return "nothing to build from — nobody has finished or started a film yet"
        }

        // Which sources Seerr is asked about: the household's heaviest, and each viewer's own heaviest.
        val household = viewers.flatMap { it.sources }.groupBy { it.tmdbId }.mapValues { (_, v) -> v.sumOf { it.weight } }
        val ask = (household.entries.sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key }).take(SuggestionEngine.SOURCES_MAX).map { it.key } +
            viewers.flatMap { v -> v.sources.sortedWith(compareByDescending<SuggestionEngine.Source> { it.weight }.thenBy { it.tmdbId }).take(SuggestionEngine.SOURCES_PER_VIEWER).map { it.tmdbId } }).distinct()

        val meta = HashMap<Int, SeerrCatalogResult>()
        val graph = HashMap<Int, List<SuggestionEngine.Cand>>()
        var answered = 0
        for (src in ask) {
            val rec = seerrClient.movieRecommendations(seerr.url, seerr.apiKey, src)
            val sim = seerrClient.movieSimilar(seerr.url, seerr.apiKey, src)
            if (rec == null && sim == null) continue
            answered++
            val results = (rec?.results.orEmpty() + sim?.results.orEmpty()).filter { it.mediaType.isBlank() || it.mediaType == "movie" }
            results.forEach { r -> meta.putIfMissing(r.id, r) }
            graph[src] = results.distinctBy { it.id }.map { cand(it) }
        }
        if (answered == 0) return "Seerr didn't answer — the last list stands"

        // FR-274-3.3 — the blocklist, whoever put a title there; a dismissal whose write has not landed still counts.
        val blocklist = seerrClient.blacklist(seerr.url, seerr.apiKey)?.map { it.tmdbId }?.toSet()
        if (blocklist != null) blocklistCache.value = blocklist to now
        retryPendingBlocklist()
        val excluded = HashSet<Int>().apply {
            addAll(libTmdb); addAll(blocklist ?: blocklistCache.value?.first.orEmpty()); addAll(dismissals.map { it.tmdb_id.toInt() }); addAll(requested)
            meta.values.filter { (it.mediaInfo?.status ?: 0) in 2..5 }.forEach { add(it.id) }   // requested, processing or available in Seerr
            viewers.forEach { v -> v.sources.forEach { add(it.tmdbId) } }
        }

        // Leanings (FR-274-11): *Not interested* weighs down what led there; *Too old* leans a step newer each.
        val notInterested = HashMap<Int, Int>()
        dismissals.filter { it.reason == REASON_NOT_INTERESTED }.forEach { d -> d.sources.split('|').mapNotNull { it.toIntOrNull() }.forEach { notInterested[it] = (notInterested[it] ?: 0) + 1 } }
        val leanings = SuggestionEngine.Leanings(notInterested.mapValues { SuggestionEngine.notInterestedMultiplier(it.value) }, dismissals.count { it.reason == REASON_TOO_OLD })

        val scored = SuggestionEngine.score(viewers, graph, excluded, leanings, thisYear)
        val details = HashMap<Int, SeerrMovieDetails?>()
        suspend fun detail(id: Int): SeerrMovieDetails? = details.getOrPut(id) { seerrClient.movieDetails(seerr.url, seerr.apiKey, id) }
        val top = scored.take(DETAILS_MAX)
        top.forEach { detail(it.cand.tmdbId) }
        // Details can say what the catalogue page did not: Seerr knows a request or the file by now.
        val stillOpen = top.filter { (details[it.cand.tmdbId]?.mediaInfo?.status ?: 0) !in 2..5 }
        val collectionOf = stillOpen.associate { it.cand.tmdbId to details[it.cand.tmdbId]?.collection?.id?.takeIf { c -> c > 0 } }
        val firstOf = HashMap<Int, SuggestionEngine.Cand?>()
        for (coll in collectionOf.values.filterNotNull().distinct().filter { it !in ownedCollections }) {
            val parts = seerrClient.collection(seerr.url, seerr.apiKey, coll)?.parts.orEmpty().filter { !it.releaseDate.isNullOrBlank() }.sortedBy { it.releaseDate }
            val first = parts.firstOrNull()
            if (first != null) { meta.putIfMissing(first.id, first); firstOf[coll] = cand(first) }
        }
        val picks = SuggestionEngine.onePerFranchise(stillOpen, collectionOf, ownedCollections, firstOf, excluded)
            .filter { p -> p.franchiseOf == null || (detail(p.scored.cand.tmdbId)?.mediaInfo?.status ?: 0) !in 2..5 }
            .take(SuggestionEngine.KEEP)

        // Clusters (FR-274-5): the fixed six now; an AI answer regroups the same list in place later.
        val tasteRows = taste.mapIndexed { i, t -> SuggestionEngine.Taste(t.genres.toSet(), t.keywords.toSet(), t.weight, tasteFilms[i]) }
        val defs = SuggestionEngine.FALLBACK
        val shares = SuggestionEngine.shares(defs, tasteRows)
        val groups = SuggestionEngine.group(picks, defs, shares, { p -> defs.indexOfFirst { it.name == SuggestionEngine.fallbackCluster(p.scored.cand.genreIds) } })
        val ordered = SuggestionEngine.ordered(groups)

        // R320 — each viewer's own list, from their sources only; a kids profile sees only rated films its age allows.
        val cascade = cfg.metadata.ageRatingCascade
        val ageMap = cfg.metadata.ageRatingMap.ifEmpty { CertificationResolver.AGE_SEED }
        fun certsOf(d: SeerrMovieDetails?): Map<String, String> =
            d?.releases?.results.orEmpty().mapNotNull { r -> r.releaseDates.firstNotNullOfOrNull { it.certification?.trim()?.takeIf { c -> c.isNotEmpty() } }?.let { r.country.uppercase() to it } }.toMap()
        fun ageOf(d: SeerrMovieDetails?): Int? = certsOf(d).takeIf { it.isNotEmpty() }?.let { c ->
            CertificationResolver.resolve(cascade, c)?.let { ageMap[it.code] }
        }
        val perViewer = LinkedHashMap<String, List<Int>>()
        for (v in viewers) {
            val cap = caps[key(v.userId)]
            val own = SuggestionEngine.score(listOf(v), graph, excluded, leanings, thisYear).take(VIEWER_DETAILS_MAX)
            own.forEach { detail(it.cand.tmdbId) }
            val ownColl = own.associate { it.cand.tmdbId to details[it.cand.tmdbId]?.collection?.id?.takeIf { c -> c > 0 } }
            val list = SuggestionEngine.onePerFranchise(own.filter { (details[it.cand.tmdbId]?.mediaInfo?.status ?: 0) !in 2..5 }, ownColl, ownedCollections, firstOf, excluded)
                .filter { p -> cap == null || (ageOf(detail(p.scored.cand.tmdbId)) ?: return@filter false) <= cap }   // no rating ⇒ never for a kids profile (155)
                .take(SuggestionEngine.PER_VIEWER)
            perViewer[v.userId] = list.map { it.scored.cand.tmdbId }
        }

        fun stored(p: SuggestionEngine.Pick, shown: Boolean): StoredItem {
            val c = p.scored.cand
            val d = details[c.tmdbId]
            val m = meta[c.tmdbId]
            val certs = certsOf(d)
            val cert = CertificationResolver.resolve(cascade, certs)?.code
            return StoredItem(
                tmdbId = c.tmdbId, title = d?.title?.ifBlank { null } ?: c.title, year = c.year, rating = c.rating, votes = c.votes,
                runtime = d?.runtime?.takeIf { it > 0 }, cert = cert, age = cert?.let { ageMap[it] }, synopsis = (d?.overview ?: m?.overview)?.takeIf { it.isNotBlank() },
                poster = d?.posterPath ?: m?.posterPath, backdrop = d?.backdropPath ?: m?.backdropPath, releaseDate = d?.releaseDate ?: m?.releaseDate,
                genreIds = c.genreIds.ifEmpty { d?.genres.orEmpty().mapNotNull { it.id } }, keywordIds = d?.keywords.orEmpty().map { it.id },
                because = p.scored.because.map { SuggestionBecause(it.viewer, it.verb, it.titles) }, viewers = p.scored.viewers,
                franchiseOf = p.franchiseOf, fresh = SuggestionEngine.fresh(c, thisYear), shown = shown, sources = p.scored.sources, score = p.scored.score,
            )
        }
        val storedAll = ordered.map { (p, cluster, shown) -> Triple(stored(p, shown), cluster, shown) }
        val finishedFilms = viewers.flatMap { v -> v.sources.filter { it.verb == SuggestionEngine.VERB_FINISHED }.map { it.tmdbId } }.distinct().size
        val info = BuildInfo(viewerDtos, finishedFilms, noHistory = false, prevIds = prevIds, prevBuiltAt = prev?.built_at, taste = taste, tasteFilms = tasteFilms)
        db.transaction {
            db.suggestionsQueries.deleteAll(); db.suggestionsQueries.deleteClusters(); db.suggestionsQueries.deleteViewers()
            storedAll.forEachIndexed { rank, (item, cluster, _) ->
                db.suggestionsQueries.put(rank.toLong(), item.tmdbId.toLong(), cluster, item.score, json.encodeToString(StoredItem.serializer(), item), now)
            }
            groups.forEachIndexed { i, g -> db.suggestionsQueries.putCluster(i.toLong(), g.name, g.share, g.finishedFilms.toLong(), g.slots.toLong()) }
            for ((uid, ids) in perViewer) {
                val device = devices[uid] ?: continue
                val scopeKey = RecommendationEngine.scopeKey(device)
                ids.forEachIndexed { rank, id ->
                    val row = viewerRow(id, meta[id], details[id]) ?: return@forEachIndexed
                    db.suggestionsQueries.putViewer(uid, scopeKey, rank.toLong(), id.toLong(), json.encodeToString(SeerrCatalogResult.serializer(), row))
                }
            }
            db.suggestionsQueries.putBuild(now, SOURCE_FALLBACK, NOTE_OFF, json.encodeToString(BuildInfo.serializer(), info))
        }

        // The AI's groups: asked after the list is stored, so a late answer regroups it in place (FR-274-5).
        val note = ai?.takeIf { it.clustersOn() }?.let { runner ->
            val input = AiRequests.ClustersInput(
                builtAt = now,
                genres = (MOVIE_GENRES + TV_GENRES),
                keywords = keywordNames(library, details.values.filterNotNull()),
                taste = taste,
                candidates = storedAll.map { (it, _, _) -> AiRequests.CandLine(it.tmdbId, it.genreIds, it.keywordIds) },
            )
            runCatching { runner.enqueueClusters(input, reason, by) }.getOrElse { Logger.warn("Suggestions: AI groups not queued: ${it.message}", "suggestions"); NOTE_OFF }
        } ?: NOTE_OFF
        db.suggestionsQueries.setClusterSource(SOURCE_FALLBACK, note, now)
        val shownCount = storedAll.count { it.third }
        return "${storedAll.size} films ($shownCount shown) from ${viewers.count { it.sources.isNotEmpty() }} viewer${if (viewers.size == 1) "" else "s"}, " +
            "${groups.size} groups by genre" + (if (note == NOTE_WAITING) " — AI groups asked for" else "") + (if (answered < ask.size) " · ${ask.size - answered} sources unanswered" else "")
    }

    private fun <K, V> HashMap<K, V>.putIfMissing(k: K, v: V) { if (k !in this) this[k] = v }

    private fun cand(r: SeerrCatalogResult) = SuggestionEngine.Cand(
        r.id, r.title ?: r.name ?: "", r.releaseDate?.take(4)?.toIntOrNull(), r.voteAverage, r.voteCount, r.genreIds,
    )

    private fun tasteLine(item: MediaItem, kind: String, weight: Double) =
        AiRequests.TasteLine(kind, weight, item.genreIds.filterNotNull().distinct(), item.keywords.orEmpty().map { it.id }.distinct().take(12))

    private fun keywordNames(library: List<MediaItem>, details: Collection<SeerrMovieDetails>): Map<Int, String> {
        val out = HashMap<Int, String>()
        library.forEach { m -> m.keywords.orEmpty().forEach { out.putIfMissing(it.id, it.name) } }
        details.forEach { d -> d.keywords.forEach { out.putIfMissing(it.id, it.name) } }
        return out
    }

    /** R320 — a viewer's row as a Seerr catalogue result, so the Request feed renders it with its own tile code. */
    private fun viewerRow(id: Int, m: SeerrCatalogResult?, d: SeerrMovieDetails?): SeerrCatalogResult? {
        if (m == null && d == null) return null
        return SeerrCatalogResult(
            id = id, mediaType = "movie", title = d?.title?.ifBlank { null } ?: m?.title, posterPath = d?.posterPath ?: m?.posterPath,
            backdropPath = d?.backdropPath ?: m?.backdropPath, overview = d?.overview ?: m?.overview, releaseDate = d?.releaseDate ?: m?.releaseDate,
            voteAverage = d?.voteAverage ?: m?.voteAverage, genreIds = m?.genreIds?.takeIf { it.isNotEmpty() } ?: d?.genres.orEmpty().mapNotNull { it.id },
        )
    }

    // ─── The AI's answer (FR-274-5) ──────────────────────────────────────────

    /** Wired to [AiJobs.clustersSink] by Main. */
    val clustersSink = object : AiJobs.ClustersSink {
        override suspend fun apply(builtAt: Long, clusters: List<AiRequests.Cluster>, candidates: List<Int>) = regroup(builtAt, clusters, candidates)
        override suspend fun failed(builtAt: Long, why: String) {
            val b = db.suggestionsQueries.build().executeAsOneOrNull() ?: return
            if (b.built_at != builtAt || b.cluster_source == SOURCE_AI) return
            db.suggestionsQueries.setClusterSource(SOURCE_FALLBACK, NOTE_BAD, builtAt)
            Logger.info("Suggestions: AI groups not used — $why", "suggestions")
        }
    }

    /** An accepted answer for the current build: its groups, their shares counted here from the stored watching,
     *  and the 20 slots given out again. `built_at` does not change; an answer for an older build is dropped. */
    private suspend fun regroup(builtAt: Long, clusters: List<AiRequests.Cluster>, candidates: List<Int>) {
        val b = db.suggestionsQueries.build().executeAsOneOrNull() ?: return
        if (b.built_at != builtAt) return
        val info = runCatching { json.decodeFromString(BuildInfo.serializer(), b.json) }.getOrNull() ?: return
        val rows = db.suggestionsQueries.all().executeAsList().mapNotNull { r -> runCatching { json.decodeFromString(StoredItem.serializer(), r.json) }.getOrNull() }
        val groupOf = HashMap<Int, Int>()
        clusters.forEachIndexed { gi, c -> c.candidates.forEach { idx -> candidates.getOrNull(idx)?.let { groupOf[it] = gi } } }
        val defs = clusters.map { c ->
            SuggestionEngine.ClusterDef(c.name, c.sources.filter { it.startsWith("g") }.mapNotNull { it.drop(1).toIntOrNull() }.toSet(),
                c.sources.filter { it.startsWith("k") }.mapNotNull { it.drop(1).toIntOrNull() }.toSet())
        }
        val taste = info.taste.mapIndexed { i, t -> SuggestionEngine.Taste(t.genres.toSet(), t.keywords.toSet(), t.weight, info.tasteFilms.getOrElse(i) { true }) }
        val shares = SuggestionEngine.shares(defs, taste)
        val picks = rows.sortedWith(compareByDescending<StoredItem> { it.score }.thenBy { it.tmdbId }).map { r ->
            SuggestionEngine.Pick(SuggestionEngine.Scored(SuggestionEngine.Cand(r.tmdbId, r.title, r.year, r.rating, r.votes, r.genreIds), r.score,
                emptyList(), r.viewers, r.sources), r.franchiseOf)
        }
        val byId = rows.associateBy { it.tmdbId }
        val groups = SuggestionEngine.group(picks, defs, shares, { p -> groupOf[p.scored.cand.tmdbId] ?: -1 })
        val ordered = SuggestionEngine.ordered(groups)
        db.transaction {
            db.suggestionsQueries.deleteAll(); db.suggestionsQueries.deleteClusters()
            ordered.forEachIndexed { rank, (p, cluster, shown) ->
                val item = byId.getValue(p.scored.cand.tmdbId).copy(shown = shown)
                db.suggestionsQueries.put(rank.toLong(), item.tmdbId.toLong(), cluster, item.score, json.encodeToString(StoredItem.serializer(), item), builtAt)
            }
            groups.forEachIndexed { i, g -> db.suggestionsQueries.putCluster(i.toLong(), g.name, g.share, g.finishedFilms.toLong(), g.slots.toLong()) }
            db.suggestionsQueries.setClusterSource(SOURCE_AI, "", builtAt)
        }
        Logger.info("Suggestions: grouped by AI — ${groups.joinToString { "${it.name} (${it.picks.size})" }}", "suggestions")
    }

    // ─── Seerr's reachability and blocklist (FR-274-1, FR-274-16) ─────────────

    private val seerrState = AtomicReference<Pair<Boolean, Long>?>(null)   // (ok, checked at)
    private val seerrDownSince = AtomicReference<Long?>(null)
    private val blocklistCache = AtomicReference<Pair<Set<Int>, Long>?>(null)

    private suspend fun seerrOk(): Boolean {
        val s = seerr() ?: return false
        val now = nowSec()
        seerrState.value?.let { (ok, at) -> if (now - at < 60) return ok }
        val ok = seerrClient.ping(s.url, s.apiKey).ok
        seerrState.value = ok to now
        seerrDownSince.value = if (ok) null else (seerrDownSince.value ?: now)
        return ok
    }

    /** FR-274-16 — every title no Request row may show: Seerr's blocklist (refreshed at most every 10 minutes) and
     *  our own dismissals, including one whose blocklist write has not landed yet. */
    suspend fun hiddenTmdbIds(): Set<Int> {
        val s = seerr() ?: return emptySet()
        val now = nowSec()
        val cached = blocklistCache.value
        val list = if (cached != null && now - cached.second < BLOCKLIST_TTL_SEC) cached.first
            else seerrClient.blacklist(s.url, s.apiKey)?.map { it.tmdbId }?.toSet()?.also { blocklistCache.value = it to now } ?: cached?.first.orEmpty()
        return list + db.suggestionsQueries.dismissals().executeAsList().map { it.tmdb_id.toInt() }
    }

    /** FR-274-11 — a dismissal made while Seerr was down is written to the blocklist with the next Seerr call. */
    private suspend fun retryPendingBlocklist() {
        val s = seerr() ?: return
        for (d in db.suggestionsQueries.dismissals().executeAsList().filter { it.in_seerr == 0L }) {
            if (seerrClient.addToBlacklist(s.url, s.apiKey, d.tmdb_id.toInt(), "movie", d.title)) db.suggestionsQueries.setInSeerr(1L, d.tmdb_id)
        }
    }

    // ─── The page (FR-274-8..13) ─────────────────────────────────────────────

    suspend fun page(): SuggestionsPageDto {
        val ok = seerrOk()
        if (ok) retryPendingBlocklist()
        val b = db.suggestionsQueries.build().executeAsOneOrNull()
            ?: return SuggestionsPageDto(built = false, seerrOk = ok, seerrDownSince = seerrDownSince.value, building = building.value != 0)
        val info = runCatching { json.decodeFromString(BuildInfo.serializer(), b.json) }.getOrDefault(BuildInfo())
        val hidden = if (ok) hiddenTmdbIds() else db.suggestionsQueries.dismissals().executeAsList().map { it.tmdb_id.toInt() }.toSet() + blocklistCache.value?.first.orEmpty()
        val requests = db.suggestionsQueries.requests().executeAsList().associateBy { it.tmdb_id.toInt() }
        val s = seerr()
        val rows = db.suggestionsQueries.all().executeAsList()
        val items = rows.mapNotNull { r ->
            val it = runCatching { json.decodeFromString(StoredItem.serializer(), r.json) }.getOrNull() ?: return@mapNotNull null
            if (it.tmdbId in hidden) return@mapNotNull null
            var dto = itemDto(it, r.cluster)
            val req = requests[it.tmdbId]
            if (req != null) {
                val state = if (ok && s != null) stateOf(it.tmdbId) else null
                dto = dto.copy(state = state?.first ?: "requested", progress = state?.second, requestedFor = req.requested_for,
                    requestedIn = req.profile_name?.let { n -> if (req.server_name?.contains("4K", true) == true) "$n · 4K" else n })
            }
            dto
        }
        val clusters = db.suggestionsQueries.clusters().executeAsList().map { c ->
            SuggestionClusterDto(c.name, items.count { it.cluster == c.name && it.shown }, items.count { it.cluster == c.name && !it.shown }, c.finished_count.toInt())
        }.filter { it.shown + it.more > 0 }
        var note = b.cluster_note
        if (b.cluster_source != SOURCE_AI && note == NOTE_WAITING && ai?.clustersInFlight() != true) note = NOTE_BAD
        val every = cadenceSec()
        return SuggestionsPageDto(
            built = true, builtAt = b.built_at, nextBuildAt = b.built_at + every, clusterSource = b.cluster_source, clusterNote = note,
            seerrOk = ok, seerrDownSince = seerrDownSince.value, noHistory = info.noHistory, building = building.value != 0,
            finishedFilms = info.finishedFilms, viewers = info.viewers, clusters = clusters, items = items,
            dismissedCount = dismissed(ok).size,
        )
    }

    private fun itemDto(it: StoredItem, cluster: String) = SuggestionItemDto(
        tmdbId = it.tmdbId, title = it.title, year = it.year, rating = it.rating, runtimeMin = it.runtime, cert = it.cert,
        synopsis = it.synopsis, poster = it.poster, cluster = cluster, because = it.because, viewers = it.viewers,
        franchiseOf = it.franchiseOf, fresh = it.fresh, shown = it.shown,
    )

    /** The tile's state after *Download*, from Seerr (186's acquisition record): requested → approved → downloading
     *  → in the library. */
    private suspend fun stateOf(tmdbId: Int): Pair<String, Int?>? {
        val s = seerr() ?: return null
        if (mediaStore.liveItems().any { it.kind == MediaKind.MOVIE && it.tmdbId == tmdbId }) return "library" to 100
        val mi = seerrClient.movieDetails(s.url, s.apiKey, tmdbId)?.mediaInfo ?: return "requested" to null
        return when (mi.status) {
            2 -> "requested" to null
            3 -> {
                val size = mi.downloadStatus.sumOf { it.size }
                if (mi.downloadStatus.isEmpty() || size <= 0) "approved" to null
                else "downloading" to (((size - mi.downloadStatus.sumOf { it.sizeLeft }).toDouble() / size) * 100).toInt().coerceIn(0, 99)
            }
            4, 5 -> "library" to 100
            else -> "requested" to null
        }
    }

    // ─── Actions (FR-274-10..12) ──────────────────────────────────────────────

    private fun storedItem(tmdbId: Int): Pair<StoredItem, String>? =
        db.suggestionsQueries.all().executeAsList().firstOrNull { it.tmdb_id.toInt() == tmdbId }?.let { r ->
            runCatching { json.decodeFromString(StoredItem.serializer(), r.json) }.getOrNull()?.let { it to r.cluster }
        }

    /** *Download* — a request as the signed-in admin's own Seerr user (Q1); *Suggested for {viewer}* is kept here
     *  because Seerr keeps no note on a request (dev review 1). */
    /** FR-274-10a — what the confirm dialog offers, cached a minute so a re-opened dialog costs nothing. */
    /** Seerr's permission bits (`server/lib/permissions.ts`): ADMIN and REQUEST_ADVANCED. */
    private val SEERR_PERMISSION_ADMIN = 2
    private val SEERR_PERMISSION_REQUEST_ADVANCED = 8192
    private var optionsCache: Pair<Long, SuggestionRequestOptions>? = null
    private var optionsFor: String? = null

    suspend fun requestOptions(adminUserId: String): SuggestionRequestOptions {
        optionsCache?.takeIf { optionsFor == adminUserId && nowSec() - it.first < 60 }?.let { return it.second }
        if (!seerrOk()) return SuggestionRequestOptions(seerrOk = false)
        val s = seerr() ?: return SuggestionRequestOptions(seerrOk = false)
        val servers = seerrClient.radarrServers(s.url, s.apiKey) ?: return SuggestionRequestOptions(seerrOk = false)
        val detailed = servers.map { srv ->
            val d = seerrClient.radarrServer(s.url, s.apiKey, srv.id)
            SuggestionRadarrServer(
                id = srv.id, name = srv.name.ifBlank { "Radarr" }, is4k = srv.is4k, isDefault = srv.isDefault, activeProfileId = srv.activeProfileId,
                profiles = d?.profiles.orEmpty().map { SuggestionProfile(it.id, it.name) },
                folders = d?.rootFolders.orEmpty().map { it.path }.filter { it.isNotBlank() },
                activeFolder = srv.activeDirectory?.takeIf { it.isNotBlank() },
            )
        }
        // Seerr honours the request's profile/server/folder only for a user with *Advanced requests* (or an admin):
        // the admin's own Seerr user (Q1), read from Seerr itself. Unknown ⇒ offered — the fields are harmless.
        val seerrUserId = seerrClient.resolveUserId(s.url, s.apiKey, adminUserId)
        val perms = seerrUserId?.let { seerrClient.userPermissions(s.url, s.apiKey, it) }
        val canChoose = perms == null || (perms and SEERR_PERMISSION_ADMIN) != 0 || (perms and SEERR_PERMISSION_REQUEST_ADVANCED) != 0
        val steered = discover?.steeredProfile(adminUserId)
        val opts = SuggestionRequestOptions(seerrOk = true, servers = detailed, canChoose = canChoose, steeredProfileId = steered?.first, steeredLabel = steered?.second)
        optionsCache = nowSec() to opts; optionsFor = adminUserId
        return opts
    }

    /** *Download* — a request as the signed-in admin's own Seerr user (Q1); *Suggested for {viewer}* is kept here
     *  because Seerr keeps no note on a request (dev review 1). FR-274-10a: [choice] is the confirm dialog's pick. */
    suspend fun download(tmdbId: Int, adminUserId: String, adminName: String, choice: SuggestionDownloadRequest? = null): SuggestionActionResult {
        if (!seerrOk()) return SuggestionActionResult(false, "Seerr can't be reached right now — this waits until it's back")
        val (item, cluster) = storedItem(tmdbId) ?: return SuggestionActionResult(false, "That film isn't in the list any more")
        val disc = discover ?: return SuggestionActionResult(false, "Seerr isn't connected")
        val opts = if (choice != null) requestOptions(adminUserId) else null
        val server = opts?.servers?.firstOrNull { it.id == choice?.serverId }
        val profileName = server?.profiles?.firstOrNull { it.id == choice?.profileId }?.name
        val requestChoice = choice?.let { SeerrDiscoverService.RequestChoice(serverId = server?.id, profileId = it.profileId, rootFolder = it.rootFolder, is4k = server?.is4k == true) }
        val rec = disc.request(adminUserId, isAdmin = true, mediaType = "movie", tmdbId = tmdbId, title = item.title, choice = requestChoice)
        if (rec.status == AcquisitionStatus.FAILED) return SuggestionActionResult(false, "Seerr didn't take it: ${rec.reason ?: "no reason given"}", itemDto(item, cluster).copy(state = "refused", note = rec.reason))
        val forWhom = item.because.firstOrNull()?.viewer ?: "the household"
        val requestedIn = profileName?.let { if (server?.is4k == true) "$it · 4K" else it }
        db.suggestionsQueries.putRequest(tmdbId.toLong(), forWhom, adminName, nowSec(), profileName, server?.name)
        val state = stateOf(tmdbId)
        return SuggestionActionResult(true, if (requestedIn != null) "Requested in $requestedIn · suggested for $forWhom" else "Requested in Seerr · suggested for $forWhom",
            itemDto(item, cluster).copy(state = state?.first ?: "requested", progress = state?.second, requestedFor = forWhom, requestedIn = requestedIn))
    }

    /** *No thanks* — out here at once, and onto Seerr's blocklist (Q4); retried with the next Seerr call if it's down. */
    suspend fun dismiss(tmdbId: Int, reason: String, note: String?, who: String): SuggestionActionResult {
        if (reason !in REASONS) return SuggestionActionResult(false, "Unknown reason")
        val (item, _) = storedItem(tmdbId) ?: return SuggestionActionResult(false, "That film isn't in the list any more")
        val forUser = item.because.firstOrNull()?.viewer?.let { name -> recommendations?.viewers()?.firstOrNull { it.jellyfinUsername == name }?.jellyfinUserId }
        db.suggestionsQueries.putDismissal(tmdbId.toLong(), item.title, item.year?.toLong(), reason, note?.trim()?.takeIf { it.isNotEmpty() }, who, nowSec(), 0L,
            item.sources.joinToString("|"), forUser)
        val s = seerr()
        val landed = s != null && seerrClient.addToBlacklist(s.url, s.apiKey, tmdbId, "movie", item.title)
        if (landed) db.suggestionsQueries.setInSeerr(1L, tmdbId.toLong())
        blocklistCache.value = null
        return SuggestionActionResult(true, if (landed) "Dismissed · ${item.title}" else "Dismissed · ${item.title} · not in Seerr's blocklist yet")
    }

    /** *Undo* and *Bring back* — the local dismissal and the blocklist entry both go (FR-274-11/12). */
    suspend fun bringBack(tmdbId: Int): SuggestionActionResult {
        val s = seerr()
        val removed = s == null || seerrClient.removeFromBlacklist(s.url, s.apiKey, tmdbId)
        if (!removed) return SuggestionActionResult(false, "Seerr didn't answer — it's still on the blocklist")
        db.suggestionsQueries.deleteDismissal(tmdbId.toLong())
        blocklistCache.value = null
        return SuggestionActionResult(true, "Removed from Seerr's blocklist · may return on the next build")
    }

    suspend fun dismissed(ok: Boolean? = null): List<SuggestionDismissedDto> {
        val local = db.suggestionsQueries.dismissals().executeAsList().map {
            SuggestionDismissedDto(it.tmdb_id.toInt(), it.title, it.year?.toInt(), it.reason, it.note, it.who, it.at, inSeerr = it.in_seerr != 0L)
        }
        val s = seerr()
        val fromSeerr = if (s != null && (ok ?: seerrOk())) seerrClient.blacklist(s.url, s.apiKey).orEmpty()
            .filter { e -> e.mediaType == "movie" && local.none { it.tmdbId == e.tmdbId } }
            .map { e -> SuggestionDismissedDto(e.tmdbId, e.title ?: "#${e.tmdbId}", null, "seerr", null, e.user?.displayName.orEmpty(), e.createdAt?.let { isoToEpochSeconds(it) }, inSeerr = true, fromSeerr = true) }
            else emptyList()
        return local + fromSeerr
    }

    /** FR-274-14 — the Dashboard card. */
    fun summary(): SuggestionsSummaryDto? {
        val b = db.suggestionsQueries.build().executeAsOneOrNull() ?: return null
        val info = runCatching { json.decodeFromString(BuildInfo.serializer(), b.json) }.getOrDefault(BuildInfo())
        val hidden = db.suggestionsQueries.dismissals().executeAsList().map { it.tmdb_id.toInt() }.toSet() + blocklistCache.value?.first.orEmpty() +
            db.suggestionsQueries.requests().executeAsList().map { it.tmdb_id.toInt() }
        val ids = db.suggestionsQueries.all().executeAsList().map { it.tmdb_id.toInt() }.filter { it !in hidden }
        val prev = info.prevIds.toSet()
        return SuggestionsSummaryDto(
            waiting = ids.size, newCount = if (info.prevBuiltAt == null) 0 else ids.count { it !in prev }, since = info.prevBuiltAt,
            // FR-274-14 — the two largest groups: the most of the 20 slots, share order breaking a tie.
            topClusters = db.suggestionsQueries.clusters().executeAsList().sortedWith(compareByDescending<dev.jellystructure.db.Suggestion_cluster> { it.slots }.thenBy { it.position })
                .take(2).map { it.name }, builtAt = b.built_at,
        )
    }

    // ─── R320: a viewer's Request row ────────────────────────────────────────

    /** The stored list for [userId] in [scopeKey] (any scope when it has none of its own yet), as catalogue results. */
    fun viewerRow(userId: String, scopeKey: String?): List<SeerrCatalogResult> {
        val rows = scopeKey?.let { db.suggestionsQueries.forViewer(userId, it).executeAsList() }.orEmpty()
            .ifEmpty { db.suggestionsQueries.forViewerAnyScope(userId).executeAsList().let { all -> all.filter { it.scope_key == all.firstOrNull()?.scope_key } } }
        return rows.mapNotNull { runCatching { json.decodeFromString(SeerrCatalogResult.serializer(), it.json) }.getOrNull() }
    }

    private fun cadenceSec(): Long {
        val step = dev.jellystructure.media.effectivePipeline(configStore.current).firstOrNull { it.step == SuggestionsStep.STEP }
        return SuggestionsStep.CADENCES[step?.rebuildEvery] ?: SuggestionsStep.CADENCES.getValue(SuggestionsStep.DEFAULT_CADENCE)
    }

    companion object {
        /** The process's one instance, for the pipeline step. Set by Main. */
        var current: SuggestionService? = null
        const val SOURCE_AI = "ai"
        const val SOURCE_FALLBACK = "fallback"
        const val NOTE_OFF = "off"
        const val NOTE_LIMIT = "limit"
        const val NOTE_BAD = "bad"
        const val NOTE_WAITING = "waiting"
        const val REASON_NOT_INTERESTED = "not_interested"
        const val REASON_ALREADY_SEEN = "already_seen"
        const val REASON_TOO_OLD = "too_old"
        const val REASON_OTHER = "other"
        val REASONS = setOf(REASON_NOT_INTERESTED, REASON_ALREADY_SEEN, REASON_TOO_OLD, REASON_OTHER)
        const val REASON_WEEKLY = "weekly build"
        const val REASON_BY_HAND = "Rebuild now"
        const val REASON_FIRST = "Seerr connected"
        /** How many of the best candidates a build reads the details of (collection, keywords, certification). */
        private const val DETAILS_MAX = 120
        private const val VIEWER_DETAILS_MAX = 40
        private const val BLOCKLIST_TTL_SEC = 600L

        private fun key(id: String) = id.lowercase().replace("-", "")
        private fun ageDays(at: Long, now: Long): Double = if (at <= 0) 365.0 else (now - at) / 86_400.0

        @OptIn(ExperimentalForeignApi::class)
        fun nowSec(): Long = time(null)

        @OptIn(ExperimentalForeignApi::class)
        fun yearOf(epochSec: Long): Int = memScoped {
            val t = alloc<time_tVar>().apply { value = epochSec.convert<time_t>() }
            val out = alloc<tm>()
            localtime_r(t.ptr, out.ptr)
            out.tm_year + 1900
        }
    }
}
