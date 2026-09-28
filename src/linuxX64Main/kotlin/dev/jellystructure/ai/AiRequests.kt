package dev.jellystructure.ai

import dev.jellystructure.model.MediaItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Phase 270 (FR-270-3/4/8) — what each job sends, and how its answer is judged. Pure: no I/O, no clock, so
 * the rules are unit-tested as written.
 *
 * What leaves the house (FR-270-8): titles, years, genres, keywords and a shortened synopsis, plus the titles
 * and genres a viewer watched. Never a name, a user id, a device, an address or anything from Jellyfin's user
 * records: the viewer is not named in the request, and a request's `custom_id` is an opaque key.
 */
object AiRequests {
    const val RERANK_JOB = "rerank"
    const val THEMES_JOB = "themes"
    const val RERANK_KEEP = 50
    const val RERANK_SHORTLIST = 100
    /** Dev review item 7 — the output ceilings the worst-case estimate uses. */
    const val RERANK_MAX_TOKENS = 8_000
    const val THEMES_MAX_TOKENS = 600
    const val MAX_THEMES = 8
    private const val SYNOPSIS_CHARS = 280
    private const val WATCHED_SHOWN = 30

    private val json = Json { ignoreUnknownKeys = true }

    // ─── Re-rank ──────────────────────────────────────────────────────────────

    private val RERANK_SYSTEM = """
        You help a household media server choose what to recommend to one viewer. You receive what the
        viewer watched recently and a shortlist of up to $RERANK_SHORTLIST films and series from their own
        library, already judged eligible and ordered by a similarity score. Choose the $RERANK_KEEP the
        viewer is most likely to enjoy next and put them in order, best first.

        Judge by what the synopses and genres say about tone, audience and the kind of evening a title
        makes, not only by shared genres. A household can include children and adults on one account, so
        do not assume one audience. Keep some variety: do not fill the top of the list with one franchise,
        one genre or one series.

        Use only ids from the shortlist, each at most once. For each pick give a reason of at most twelve
        words, in English, that a person managing the server can read (for example "same slow-burn
        small-town mystery as their last two"). The viewer never sees the reasons.
    """.trimIndent()

    private val RERANK_SCHEMA = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("picks") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("reason") { put("type", "string") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("id")); add(JsonPrimitive("reason")) }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("picks")) }
        put("additionalProperties", false)
    }

    /** One title as the model sees it: an opaque short id and what TMDB says about it. */
    fun describe(shortId: String, item: MediaItem, themes: List<String> = emptyList()): String {
        val year = item.year?.let { " ($it)" } ?: ""
        val kind = if (item.kind == dev.jellystructure.model.MediaKind.TV_SHOW) "series" else "film"
        val genres = item.genres.joinToString(", ")
        val keywords = (item.keywords.orEmpty().map { it.name }.take(8) + themes).distinct().joinToString(", ")
        val synopsis = item.overview.orEmpty().replace('\n', ' ').trim().let { if (it.length > SYNOPSIS_CHARS) it.take(SYNOPSIS_CHARS).trimEnd() + "…" else it }
        return buildString {
            append("$shortId | ${item.title}$year | $kind")
            if (genres.isNotEmpty()) append(" | $genres")
            if (keywords.isNotEmpty()) append(" | keywords: $keywords")
            if (synopsis.isNotEmpty()) append(" | $synopsis")
        }
    }

    /**
     * FR-270-3 — one viewer's re-rank request. [shortlist] is 269's order (at most [RERANK_SHORTLIST]),
     * [watched] the titles they watched, newest first. Candidates carry short ids `t1…`; [RerankContext]
     * maps them back.
     */
    fun rerankRequest(customId: String, model: String, effort: String, shortlist: List<MediaItem>, watched: List<MediaItem>, themes: Map<String, List<String>> = emptyMap()): JsonObject {
        val lines = shortlist.mapIndexed { i, item -> describe("t${i + 1}", item, themes[item.jellyfinId ?: item.id].orEmpty()) }
        val watchedLines = watched.take(WATCHED_SHOWN).map { w ->
            "- ${w.title}${w.year?.let { " ($it)" } ?: ""}${if (w.genres.isNotEmpty()) " — " + w.genres.joinToString(", ") else ""}"
        }
        val user = buildString {
            append("Watched recently, newest first:\n")
            append(if (watchedLines.isEmpty()) "(nothing yet)" else watchedLines.joinToString("\n"))
            append("\n\nShortlist:\n")
            append(lines.joinToString("\n"))
        }
        return request(customId, model, effort, RERANK_MAX_TOKENS, RERANK_SYSTEM, user, RERANK_SCHEMA)
    }

    // ─── Themes ───────────────────────────────────────────────────────────────

    private val THEMES_SYSTEM = """
        You tag films and series in a household media library with themes, for a recommender that compares
        titles. Read the title, genres, any keywords and the synopsis, and give between five and eight short
        themes in English, lower case, one to three words each: what the story is about and its tone or
        setting (for example "small town", "found family", "dark comedy", "coming of age", "island life").
        Do not repeat the genres, and do not name people.
    """.trimIndent()

    private val THEMES_SCHEMA = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("themes") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
        }
        putJsonArray("required") { add(JsonPrimitive("themes")) }
        put("additionalProperties", false)
    }

    fun themesRequest(customId: String, model: String, effort: String, item: MediaItem): JsonObject {
        val user = buildString {
            append("Title: ${item.title}${item.year?.let { " ($it)" } ?: ""}\n")
            append("Kind: ${if (item.kind == dev.jellystructure.model.MediaKind.TV_SHOW) "series" else "film"}\n")
            if (item.genres.isNotEmpty()) append("Genres: ${item.genres.joinToString(", ")}\n")
            item.keywords.orEmpty().takeIf { it.isNotEmpty() }?.let { append("Keywords: ${it.joinToString(", ") { k -> k.name }}\n") }
            append("Synopsis: ${item.overview.orEmpty().trim()}")
        }
        return request(customId, model, effort, THEMES_MAX_TOKENS, THEMES_SYSTEM, user, THEMES_SCHEMA)
    }

    /** A title the themes job may tag: TMDB answered and has no keywords for it (`[]`), and there is a
     *  synopsis to read. `null` keywords means "not fetched yet" (269's backfill), not "TMDB has none":
     *  tagging those would pay for themes TMDB is about to supply. */
    fun wantsThemes(item: MediaItem): Boolean = item.keywords?.isEmpty() == true && !item.overview.isNullOrBlank()

    /** The synopsis a stored answer was made from, so a changed synopsis is tagged again. */
    fun synopsisHash(item: MediaItem): String {
        var h = 0xcbf29ce484222325uL
        for (b in item.overview.orEmpty().trim().encodeToByteArray()) { h = h xor (b.toULong() and 0xffuL); h *= 0x100000001b3uL }
        return h.toString(16)
    }

    // ─── One request ──────────────────────────────────────────────────────────

    /**
     * The unchanging instructions go first, as the system prompt, marked for caching (hits across one batch
     * are best-effort). The answer is constrained by `output_config.format`. Effort is sent only to a model
     * that takes it (Haiku 4.5 rejects the parameter).
     */
    private fun request(customId: String, model: String, effort: String, maxTokens: Int, system: String, user: String, schema: JsonObject): JsonObject =
        buildJsonObject {
            put("custom_id", customId)
            putJsonObject("params") {
                put("model", model)
                put("max_tokens", maxTokens)
                putJsonArray("system") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", system)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    })
                }
                putJsonArray("messages") {
                    add(buildJsonObject { put("role", "user"); put("content", user) })
                }
                putJsonObject("output_config") {
                    putJsonObject("format") { put("type", "json_schema"); put("schema", schema) }
                    if (AiPricing.model(model).effort && effort.isNotBlank()) put("effort", effort)
                }
            }
        }

    // ─── Reading results (FR-270-4) ───────────────────────────────────────────

    /** [error] is Phase 272's: an `errored` result's own error type and message, for the verdict. */
    data class Result(val customId: String, val type: String, val stopReason: String?, val text: String?, val usage: AiPricing.Usage?, val error: String? = null)

    /** One line of a batch's results: `{custom_id, result: {type, message?}}`. */
    fun parseResult(line: JsonObject): Result? {
        val customId = line["custom_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val result = line["result"]?.jsonObject ?: return null
        val type = result["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val message = result["message"] as? JsonObject
        val text = (message?.get("content") as? JsonArray)
            ?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.get("text")?.jsonPrimitive?.contentOrNull
        val u = message?.get("usage") as? JsonObject
        fun n(k: String) = u?.get(k)?.jsonPrimitive?.longOrNull ?: 0L
        val usage = u?.let { AiPricing.Usage(n("input_tokens"), n("output_tokens"), n("cache_creation_input_tokens"), n("cache_read_input_tokens")) }
        // An errored line nests the API error: {"type":"errored","error":{"type":"error","error":{"type":…,"message":…}}}.
        val err = (result["error"] as? JsonObject)?.let { e -> (e["error"] as? JsonObject) ?: e }
        val error = err?.let { e ->
            listOfNotNull(e["type"]?.jsonPrimitive?.contentOrNull, e["message"]?.jsonPrimitive?.contentOrNull).joinToString(": ").take(200)
        }?.takeIf { it.isNotEmpty() }
        return Result(customId, type, message?.get("stop_reason")?.jsonPrimitive?.contentOrNull, text, usage, error)
    }

    /** Phase 272 (FR-272-14) — an answer and what became of it, in words: *used · …* or why it was not used. */
    data class Judged<T>(val value: T?, val why: String)

    /** Why a result cannot be read at all, before its content is looked at; null when it can. */
    private fun unreadable(r: Result): String? = when {
        r.type == "expired" -> "expired"
        r.type == "canceled" -> "cancelled"
        r.type == "errored" -> "errored" + (r.error?.let { " — $it" } ?: "")
        r.type != "succeeded" -> r.type
        r.stopReason == "refusal" -> "refused"
        r.stopReason == "max_tokens" -> "stopped at the output limit"
        r.text == null -> "no answer text"
        else -> null
    }

    data class Pick(val jellyfinId: String, val reason: String)

    /**
     * FR-270-4 — a re-rank answer is kept only if it is complete and honest: the request ended normally
     * (`refusal` or `max_tokens` means no), every id is on this viewer's shortlist, none repeats, and there are
     * at most [RERANK_KEEP]. A short answer is topped up from 269's own order. Anything else is `null`, and
     * the viewer keeps 269's list. [shortlist] is 269's order: short id `t(n)` is `shortlist[n-1]`.
     */
    fun validateRerank(r: Result, shortlist: List<String>): List<Pick>? = judgeRerank(r, shortlist).value

    /** [validateRerank] with the reason (Phase 272): what it accepts is unchanged. */
    fun judgeRerank(r: Result, shortlist: List<String>): Judged<List<Pick>> {
        unreadable(r)?.let { return Judged(null, it) }
        val root = runCatching { json.parseToJsonElement(r.text!!) }.getOrNull() ?: return Judged(null, "not valid JSON")
        val picks = ((root as? JsonObject)?.get("picks") as? JsonArray) ?: return Judged(null, "not in the expected form")
        if (picks.size > RERANK_KEEP) return Judged(null, "more than $RERANK_KEEP picks (${picks.size})")
        val out = ArrayList<Pick>()
        val seen = HashSet<String>()
        for (p in picks) {
            val o = p as? JsonObject ?: return Judged(null, "not in the expected form")
            val shortId = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return Judged(null, "not in the expected form")
            val n = shortId.removePrefix("t").toIntOrNull()
            val id = n?.let { shortlist.getOrNull(it - 1) } ?: return Judged(null, "an id not on the shortlist ($shortId)")
            if (!seen.add(id)) return Judged(null, "a title picked twice ($shortId)")
            out += Pick(id, (o["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().take(160))
        }
        val answered = out.size
        for (id in shortlist) {
            if (out.size >= RERANK_KEEP) break
            if (seen.add(id)) out += Pick(id, "")
        }
        val topped = out.size - answered
        return Judged(out, "used · $answered pick${if (answered == 1) "" else "s"}" + (if (topped > 0) ", $topped topped up" else ""))
    }

    /** FR-270-4 — theme tags: lower-cased, trimmed, de-duplicated, at most [MAX_THEMES]. `null` = no answer. */
    fun validateThemes(r: Result): List<String>? = judgeThemes(r).value

    /** [validateThemes] with the reason (Phase 272). */
    fun judgeThemes(r: Result): Judged<List<String>> {
        unreadable(r)?.let { return Judged(null, it) }
        val root = runCatching { json.parseToJsonElement(r.text!!) }.getOrNull() ?: return Judged(null, "not valid JSON")
        val arr = ((root as? JsonObject)?.get("themes") as? JsonArray) ?: return Judged(null, "not in the expected form")
        val themes = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() && s.length <= 40 } }
            .distinct().take(MAX_THEMES)
        return Judged(themes, "used · ${themes.size} theme${if (themes.size == 1) "" else "s"}")
    }

    // ─── Suggestion clusters (Phase 274, FR-274-5) ─────────────────────────────

    const val CLUSTERS_JOB = "clusters"
    const val CLUSTERS_MAX_TOKENS = 6_000
    const val CLUSTERS_MIN = 3
    const val CLUSTERS_MAX = 8

    /** What a build sends: the household's finished titles and the candidates as genre and keyword ids only — no
     *  viewer, no title, no synopsis (FR-274-5) — and the names of those ids. [builtAt] ties the answer to its build. */
    @kotlinx.serialization.Serializable
    data class ClustersInput(
        val builtAt: Long,
        val genres: Map<Int, String>,
        val keywords: Map<Int, String>,
        val taste: List<TasteLine>,
        val candidates: List<CandLine>,
    )

    @kotlinx.serialization.Serializable
    data class TasteLine(val kind: String, val weight: Double, val genres: List<Int>, val keywords: List<Int>)

    @kotlinx.serialization.Serializable
    data class CandLine(val tmdbId: Int, val genres: List<Int>, val keywords: List<Int>)

    /** One accepted group: its name, its candidates by index into [ClustersInput.candidates], and the genre (`g…`) and
     *  keyword (`k…`) ids it stands for. */
    data class Cluster(val name: String, val candidates: List<Int>, val sources: List<String>)

    private val CLUSTERS_SYSTEM = """
        You group films a household does not have yet into the kinds of film the household watches. You receive what
        the household finished — each line a film or series as its genre ids (g…) and keyword ids (k…) with a weight
        for how much it was watched — the names of those ids, and the candidate films (c…) the same way.

        Make between $CLUSTERS_MIN and $CLUSTERS_MAX groups that describe what this household actually watches, specific
        enough to mean something (for example "Folk and coastal horror" or "Heist thrillers"), never a person's name.
        Put every candidate in exactly one group. For each group, list the genre and keyword ids from the household's
        watching that the group stands for: the server counts each group's share of the household's watching from
        those ids, so choose them to match the group. Names are English, 3 to 32 characters, and all different.
    """.trimIndent()

    private val CLUSTERS_SCHEMA = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("clusters") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("candidates") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                        putJsonObject("sources") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                    }
                    putJsonArray("required") { add(JsonPrimitive("name")); add(JsonPrimitive("candidates")); add(JsonPrimitive("sources")) }
                    put("additionalProperties", false)
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("clusters")) }
        put("additionalProperties", false)
    }

    /** The ids a clusters answer may name as sources: every genre and keyword the household's watching carries. */
    fun clusterSourceIds(input: ClustersInput): Set<String> =
        (input.taste.flatMap { t -> t.genres.map { "g$it" } + t.keywords.map { "k$it" } }).toSet()

    fun clustersRequest(customId: String, model: String, effort: String, input: ClustersInput): JsonObject {
        val used = clusterSourceIds(input) + input.candidates.flatMap { c -> c.genres.map { "g$it" } + c.keywords.map { "k$it" } }
        val user = buildString {
            append("Genres:\n")
            append(input.genres.entries.sortedBy { it.key }.filter { "g${it.key}" in used }.joinToString("\n") { "g${it.key} ${it.value}" })
            append("\n\nKeywords:\n")
            append(input.keywords.entries.sortedBy { it.key }.filter { "k${it.key}" in used }.joinToString("\n") { "k${it.key} ${it.value}" })
            append("\n\nWhat the household finished (weight · kind · ids):\n")
            append(input.taste.sortedByDescending { it.weight }.joinToString("\n") { t ->
                "${(kotlin.math.round(t.weight * 100) / 100.0)} · ${t.kind} · " + (t.genres.map { "g$it" } + t.keywords.map { "k$it" }).joinToString(" ")
            })
            append("\n\nCandidates (id · ids):\n")
            append(input.candidates.mapIndexed { i, c -> "c${i + 1} · " + (c.genres.map { "g$it" } + c.keywords.map { "k$it" }).joinToString(" ") }.joinToString("\n"))
        }
        return request(customId, model, effort, CLUSTERS_MAX_TOKENS, CLUSTERS_SYSTEM, user, CLUSTERS_SCHEMA)
    }

    /**
     * FR-274-5 — nothing trusted blindly: 3–8 groups, a name 3–32 characters, no two names equal ignoring case,
     * every candidate in exactly one group, no id it was not given. Anything else is `null` with the reason, and the
     * build keeps its genre groups.
     */
    fun judgeClusters(r: Result, candidateCount: Int, sourceIds: Set<String>): Judged<List<Cluster>> {
        unreadable(r)?.let { return Judged(null, it) }
        val root = runCatching { json.parseToJsonElement(r.text!!) }.getOrNull() ?: return Judged(null, "not valid JSON")
        val arr = ((root as? JsonObject)?.get("clusters") as? JsonArray) ?: return Judged(null, "not in the expected form")
        if (arr.size !in CLUSTERS_MIN..CLUSTERS_MAX) return Judged(null, "${arr.size} groups ($CLUSTERS_MIN–$CLUSTERS_MAX allowed)")
        val names = HashSet<String>()
        val placed = HashSet<Int>()
        val out = ArrayList<Cluster>()
        for (el in arr) {
            val o = el as? JsonObject ?: return Judged(null, "not in the expected form")
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.length !in 3..32) return Judged(null, "a group name of ${name.length} characters (\"${name.take(40)}\")")
            if (!names.add(name.lowercase())) return Judged(null, "two groups named \"$name\"")
            val cands = ((o["candidates"] as? JsonArray) ?: return Judged(null, "not in the expected form")).map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            val idx = ArrayList<Int>()
            for (c in cands) {
                val n = c.removePrefix("c").toIntOrNull()?.takeIf { it in 1..candidateCount } ?: return Judged(null, "an id it wasn't given ($c)")
                if (!placed.add(n - 1)) return Judged(null, "a candidate in two groups ($c)")
                idx += n - 1
            }
            val srcs = ((o["sources"] as? JsonArray) ?: return Judged(null, "not in the expected form")).map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
            srcs.firstOrNull { it !in sourceIds }?.let { return Judged(null, "an id it wasn't given ($it)") }
            out += Cluster(name, idx, srcs.distinct())
        }
        (0 until candidateCount).firstOrNull { it !in placed }?.let { return Judged(null, "a candidate left out (c${it + 1})") }
        return Judged(out, "used · ${out.size} groups")
    }

    /** Phase 272 (FR-272-13) — the system prompt and the user message a request carries, as sent. */
    fun systemOf(request: JsonObject): String =
        ((request["params"] as? JsonObject)?.get("system") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("text") as? JsonPrimitive }?.contentOrNull.orEmpty()

    fun sentOf(request: JsonObject): String =
        ((request["params"] as? JsonObject)?.get("messages") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("content") as? JsonPrimitive }?.contentOrNull.orEmpty()

    fun requestsJson(requests: List<JsonObject>): String = JsonArray(requests).toString()
}
