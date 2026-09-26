package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.model.MediaItem
import dev.jellystructure.model.MediaKind
import dev.jellystructure.ops.SpinLock
import dev.jellystructure.tv.TaxonomyKey
import kotlin.concurrent.AtomicReference

/**
 * Phase 271 — a genre is a TMDB id, with a label per language.
 *
 * Before this phase only the genre's NAME was kept, in whatever language the title's metadata was
 * fetched in, so one genre was several (production, 2026-09-26: 41 names for 24 genres — *Comedy*,
 * *Komedie*, *Commedia* all genre 35). This object is the one place that knows:
 *
 *  - every label TMDB has given for an id, per language (FR-271-2), held in memory and in `genre_label`;
 *  - how a stored name maps back to its id ([idFor], [ids]) — through the title's own TMDB pairing first,
 *    then through the label table in any language;
 *  - the one identity counting and filtering compare under ([keyOf]/[keys], FR-271-4): `#<id>` for a TMDB
 *    genre, [TaxonomyKey.key] of the name for a genre the admin added by hand;
 *  - which label a viewer sees ([label], FR-271-3).
 *
 * **Labels come from TMDB's genre lists only**, never from a title's details response. Measured
 * 2026-09-26: `/movie/{id}?language=fo` answers `Comedy`/`Family` — TMDB falls back to English where it
 * has no translation — while `/genre/movie/list?language=fo` honestly answers `name: null`. Recording
 * from details would file English names as Faroese and break the fallback rule for a Faroese viewer. A
 * details response still tells us which ids exist and which list (movie/tv) they belong to.
 *
 * A global object rather than an injected class for the same reason as [TaxonomyKey]: it is called from
 * [dev.jellystructure.tv.ConditionEvaluator] (itself an object, reached from a dozen call sites) and from
 * inside [MediaStore]'s write path. The state is one immutable [Snapshot] swapped atomically.
 */
object GenreCatalog {
    const val KIND_MOVIE = "movie"
    const val KIND_TV = "tv"

    /** The fallback language after the viewer's and the title's own (FR-271-3 step 3). */
    const val ENGLISH = "en"

    class Snapshot(
        /** id → (language → label). */
        val labels: Map<Int, Map<String, String>>,
        /** id → which of TMDB's lists carry it (`movie`, `tv`). */
        val kinds: Map<Int, Set<String>>,
    ) {
        /** [TaxonomyKey.key] of every label in every language → the ids carrying it, ascending. */
        val byKey: Map<String, List<Int>> = HashMap<String, MutableList<Int>>().also { m ->
            for ((id, byLang) in labels) for (label in byLang.values) {
                val ids = m.getOrPut(TaxonomyKey.key(label)) { mutableListOf() }
                if (id !in ids) ids.add(id)
            }
            m.values.forEach { it.sort() }
        }
    }

    private val snap = AtomicReference(Snapshot(emptyMap(), emptyMap()))
    private val dbRef = AtomicReference<JellystructureDb?>(null)
    private val writeLock = SpinLock()

    fun snapshot(): Snapshot = snap.value

    /** Loads the table into memory and makes later [recordList]/[observeIds] calls persist. Once, at startup. */
    fun attach(db: JellystructureDb) {
        val labels = HashMap<Int, HashMap<String, String>>()
        db.genreLabelQueries.allLabels().executeAsList().forEach {
            labels.getOrPut(it.genre_id.toInt()) { HashMap() }[it.language] = it.label
        }
        val kinds = HashMap<Int, HashSet<String>>()
        db.genreLabelQueries.allKinds().executeAsList().forEach { kinds.getOrPut(it.genre_id.toInt()) { HashSet() }.add(it.kind) }
        dbRef.value = db
        snap.value = Snapshot(labels, kinds)
    }

    /** Test seam: an in-memory catalog, nothing persisted. */
    fun replaceForTest(labels: Map<Int, Map<String, String>>, kinds: Map<Int, Set<String>> = emptyMap()) {
        dbRef.value = null
        snap.value = Snapshot(labels, kinds)
    }

    /** `da-DK` → `da`; blank or null (TMDB's default) → `en`. */
    fun normLang(lang: String?): String =
        lang?.trim()?.substringBefore('-')?.lowercase()?.takeIf { it.isNotEmpty() } ?: ENGLISH

    fun kindOf(kind: MediaKind): String = if (kind == MediaKind.TV_SHOW) KIND_TV else KIND_MOVIE

    /**
     * FR-271-2 — one of TMDB's genre lists, in [language]. Null or blank names (TMDB has no translation
     * in that language, e.g. every Faroese genre) are skipped: the fallback chain covers them. Returns how
     * many labels were new or changed.
     */
    fun recordList(kind: String, language: String, genres: List<Pair<Int, String?>>): Int {
        val lang = normLang(language)
        val named = genres.mapNotNull { (id, name) ->
            name?.let { TaxonomyKey.display(it) }?.takeIf { it.isNotEmpty() }?.let { id to it }
        }
        val (newLabels, newKinds) = writeLock.withLock {
            val cur = snap.value
            val labelsChanged = named.filter { (id, label) -> cur.labels[id]?.get(lang) != label }
            val kindsAdded = genres.map { it.first }.distinct().filter { kind !in cur.kinds[it].orEmpty() }
            if (labelsChanged.isNotEmpty() || kindsAdded.isNotEmpty()) {
                val labels = cur.labels.mapValues { it.value.toMutableMap() }.toMutableMap()
                for ((id, label) in labelsChanged) labels.getOrPut(id) { mutableMapOf() }[lang] = label
                val kinds = cur.kinds.mapValues { it.value.toMutableSet() }.toMutableMap()
                for (id in kindsAdded) kinds.getOrPut(id) { mutableSetOf() }.add(kind)
                snap.value = Snapshot(labels, kinds)
            }
            labelsChanged to kindsAdded
        }
        persist(kind, lang, newLabels, newKinds)
        return newLabels.size
    }

    /** A details response's genre ids: which list they belong to, never their names (see the class doc). */
    fun observeIds(kind: String, ids: List<Int>) {
        if (ids.isEmpty()) return
        if (ids.all { kind in snap.value.kinds[it].orEmpty() }) return
        val added = writeLock.withLock {
            val cur = snap.value
            val missing = ids.distinct().filter { kind !in cur.kinds[it].orEmpty() }
            if (missing.isNotEmpty()) {
                val kinds = cur.kinds.mapValues { it.value.toMutableSet() }.toMutableMap()
                for (id in missing) kinds.getOrPut(id) { mutableSetOf() }.add(kind)
                snap.value = Snapshot(cur.labels, kinds)
            }
            missing
        }
        persist(kind, ENGLISH, emptyList(), added)
    }

    // Never inside [writeLock]: a SpinLock section must stay a reference swap, never I/O. Two threads
    // writing the same row race harmlessly (INSERT OR REPLACE / OR IGNORE of the same value).
    private fun persist(kind: String, lang: String, labels: List<Pair<Int, String>>, kinds: List<Int>) {
        if (labels.isEmpty() && kinds.isEmpty()) return
        val db = dbRef.value ?: return
        runCatching {
            db.transaction {
                for ((id, label) in labels) db.genreLabelQueries.putLabel(id.toLong(), lang, label)
                for (id in kinds) db.genreLabelQueries.putKind(id.toLong(), kind)
            }
        }.onFailure { println("[WARN] GenreCatalog: could not store genre labels ($lang/$kind): ${it.message}") }
    }

    /** The languages whose lists we hold at least one label for. */
    fun languages(): Set<String> = snap.value.labels.values.flatMapTo(HashSet()) { it.keys }

    /** Every label an id has, language → label. */
    fun labelsOf(id: Int): Map<String, String> = snap.value.labels[id].orEmpty()

    /**
     * The id a stored genre NAME names, in any language (FR-271-4). When a name is a label of two ids
     * (none on production today — TMDB's movie and tv lists share ids for the genres they share, and
     * name the rest differently), the one in [kind]'s list wins, then the lowest id.
     */
    fun idFor(name: String, kind: MediaKind? = null): Int? {
        val ids = snap.value.byKey[TaxonomyKey.key(name)] ?: return null
        if (ids.size == 1 || kind == null) return ids.first()
        val k = kindOf(kind)
        return ids.firstOrNull { k in snap.value.kinds[it].orEmpty() } ?: ids.first()
    }

    /**
     * The id of each entry in [MediaItem.genres], aligned by index. The title's own pairing
     * ([MediaItem.tmdbGenres] ↔ [MediaItem.tmdbGenreIds], one details response) wins over the label
     * table, since it is exact even for a language whose list we have not fetched yet.
     */
    fun ids(item: MediaItem): List<Int?> {
        // A null is either a hand-added genre (rare) or a name written before its language's list was
        // fetched — re-deriving costs a few map lookups and keeps the second case right before the
        // backfill (FR-271-6) has stored it.
        if (item.genreIds.size == item.genres.size && item.genreIds.none { it == null }) return item.genreIds
        return deriveIds(item)
    }

    private fun deriveIds(item: MediaItem): List<Int?> {
        val own = if (item.tmdbGenreIds.size == item.tmdbGenres.size) {
            HashMap<String, Int>().also { m ->
                item.tmdbGenres.forEachIndexed { i, name -> item.tmdbGenreIds[i]?.let { m[TaxonomyKey.key(name)] = it } }
            }
        } else emptyMap()
        return item.genres.map { name -> own[TaxonomyKey.key(name)] ?: idFor(name, item.kind) }
    }

    /** MediaStore's write-path step: [MediaItem.genreIds] re-derived from [MediaItem.genres], and the
     *  TMDB baseline's ids filled in where a pre-271 row lacks them. Idempotent. */
    fun normalize(item: MediaItem): MediaItem {
        val tmdbIds = if (item.tmdbGenreIds.size == item.tmdbGenres.size) item.tmdbGenreIds
            else item.tmdbGenres.map { idFor(it, item.kind) }
        val withTmdb = if (tmdbIds == item.tmdbGenreIds) item else item.copy(tmdbGenreIds = tmdbIds)
        val ids = deriveIds(withTmdb)
        return if (ids == withTmdb.genreIds) withTmdb else withTmdb.copy(genreIds = ids)
    }

    /** `true` when [normalize] would change something — the backfill's (FR-271-6) filter. */
    fun needsNormalize(item: MediaItem): Boolean = normalize(item) != item

    /** FR-271-4 — the grouping/matching identity of one genre: `#35` for a TMDB genre, the name's
     *  [TaxonomyKey.key] for a hand-added one. */
    fun keyFor(id: Int?, name: String): String = if (id != null) "#$id" else TaxonomyKey.key(name)

    /** [keyFor] for a free-standing value — a filter's saved value, a request parameter — resolved in any language. */
    fun keyOf(value: String, kind: MediaKind? = null): String {
        parseKey(value)?.let { return "#$it" }
        return keyFor(idFor(value, kind), value)
    }

    /** `#35` → 35 (the form the admin's Metadata page links with). */
    fun parseKey(value: String): Int? =
        value.trim().takeIf { it.startsWith("#") }?.drop(1)?.toIntOrNull()

    /** Every genre identity a title carries (FR-271-4). */
    fun keys(item: MediaItem): Set<String> {
        val ids = ids(item)
        return item.genres.mapIndexedTo(HashSet()) { i, name -> keyFor(ids.getOrNull(i), name) }
    }

    /** One genre of one title, id (null when hand-added) and the name stored on the title. */
    data class Ref(val id: Int?, val name: String)

    /** The title's genres in stored (TMDB) order, one per identity — a title carrying both *Comedy* and
     *  *Komedie* (a hand-added duplicate) has one comedy. */
    fun refs(item: MediaItem): List<Ref> {
        val ids = ids(item)
        val seen = HashSet<String>()
        return item.genres.mapIndexedNotNull { i, name ->
            if (name.isBlank()) return@mapIndexedNotNull null
            val id = ids.getOrNull(i)
            if (seen.add(keyFor(id, name))) Ref(id, name) else null
        }
    }

    /**
     * FR-271-3 — the label for [id]: the viewer's app language, then [titleLanguages] (the title's
     * resolved metadata language, then its original language), then English, then any label the table
     * has. Null only when the table holds nothing for the id at all.
     */
    fun label(id: Int, appLanguage: String?, titleLanguages: List<String?> = emptyList()): String? {
        val byLang = snap.value.labels[id] ?: return null
        val order = buildList {
            appLanguage?.let { add(normLang(it)) }
            titleLanguages.forEach { l -> l?.takeIf { it.isNotBlank() }?.let { add(normLang(it)) } }
            add(ENGLISH)
        }
        for (lang in order) byLang[lang]?.let { return it }
        return byLang.entries.minByOrNull { it.key }?.value
    }

    /** The languages a title's own genre names are in, for [label]'s step 2. */
    fun titleLanguages(item: MediaItem): List<String?> =
        listOf(item.metadataLanguage, item.resolvedLanguage, item.originalLanguage)

    /**
     * What a surface shows for a title's genres (FR-271-5): one label per genre, in stored order.
     * [withTitle] = a surface about this one title (the detail page), which consults the title's own
     * languages (step 2); every surface showing genres ACROSS titles (cards, browse, facts) passes false,
     * so one genre reads the same on every card. A hand-added genre shows as it was typed.
     */
    fun displayNames(item: MediaItem, appLanguage: String?, withTitle: Boolean): List<String> {
        val titleLangs = if (withTitle) titleLanguages(item) else emptyList()
        return refs(item).map { r -> r.id?.let { label(it, appLanguage, titleLangs) } ?: TaxonomyKey.display(r.name) }
    }

    /**
     * A genre as a cached, language-neutral payload stores it (its English label, or a hand-added name)
     * → the same genre's label in [appLanguage]. What lets Home's cache stay unkeyed by language (FR-271-5):
     * the feed is built in English and relabelled per request, one map lookup per card.
     */
    fun relabel(name: String, appLanguage: String?, kind: MediaKind? = null): String =
        idFor(name, kind)?.let { label(it, appLanguage) } ?: name

    /** The ids alongside [displayNames], aligned — the additive wire field (FR-271-5). */
    fun displayIds(item: MediaItem): List<Int?> = refs(item).map { it.id }

    /** Every label of every genre a title has, in any language, plus hand-added names — for the legacy
     *  substring GENRE row, so a saved term matches whatever language the title's names came in. */
    fun allLabelsOf(item: MediaItem): List<String> = refs(item).flatMap { r ->
        listOf(r.name) + (r.id?.let { labelsOf(it).values } ?: emptyList())
    }.distinct()

    /**
     * Phase 94's provenance merge, by identity instead of by spelling: a genre the admin added survives a
     * re-sync, a genre the admin removed stays removed — even when the re-sync fetched the title in a
     * different language, so the same genre comes back under another name.
     */
    fun mergeUserGenres(prior: MediaItem, fresh: List<Pair<Int, String>>): List<String> {
        val priorIds = ids(prior)
        val baselineIds = if (prior.tmdbGenreIds.size == prior.tmdbGenres.size) prior.tmdbGenreIds
            else prior.tmdbGenres.map { idFor(it, prior.kind) }
        val baselineKeys = prior.tmdbGenres.mapIndexedTo(HashSet()) { i, n -> keyFor(baselineIds.getOrNull(i), n) }
        val currentKeys = prior.genres.mapIndexedTo(HashSet()) { i, n -> keyFor(priorIds.getOrNull(i), n) }
        val removed = baselineKeys - currentKeys
        val userAdded = prior.genres.filterIndexed { i, n -> keyFor(priorIds.getOrNull(i), n) !in baselineKeys }
        val freshKept = fresh.filter { (id, name) -> keyFor(id, name) !in removed }
        val freshKeys = freshKept.mapTo(HashSet()) { (id, name) -> keyFor(id, name) }
        val addedKept = userAdded.filter { keyOf(it, prior.kind) !in freshKeys }
        return (freshKept.map { it.second } + addedKept).distinct()
    }
}
