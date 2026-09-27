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

    data class Result(val customId: String, val type: String, val stopReason: String?, val text: String?, val usage: AiPricing.Usage?)

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
        return Result(customId, type, message?.get("stop_reason")?.jsonPrimitive?.contentOrNull, text, usage)
    }

    data class Pick(val jellyfinId: String, val reason: String)

    /**
     * FR-270-4 — a re-rank answer is kept only if it is complete and honest: the request ended normally
     * (`refusal` or `max_tokens` means no), every id is on this viewer's shortlist, none repeats, and there are
     * at most [RERANK_KEEP]. A short answer is topped up from 269's own order. Anything else is `null`, and
     * the viewer keeps 269's list. [shortlist] is 269's order: short id `t(n)` is `shortlist[n-1]`.
     */
    fun validateRerank(r: Result, shortlist: List<String>): List<Pick>? {
        if (r.type != "succeeded" || r.stopReason in setOf("refusal", "max_tokens")) return null
        val picks = runCatching { json.parseToJsonElement(r.text ?: return null).jsonObject["picks"]!!.jsonArray }.getOrNull() ?: return null
        if (picks.size > RERANK_KEEP) return null
        val out = ArrayList<Pick>()
        val seen = HashSet<String>()
        for (p in picks) {
            val o = p as? JsonObject ?: return null
            val shortId = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val n = shortId.removePrefix("t").toIntOrNull() ?: return null
            val id = shortlist.getOrNull(n - 1) ?: return null
            if (!seen.add(id)) return null
            out += Pick(id, o["reason"]?.jsonPrimitive?.contentOrNull.orEmpty().trim().take(160))
        }
        for (id in shortlist) {
            if (out.size >= RERANK_KEEP) break
            if (seen.add(id)) out += Pick(id, "")
        }
        return out
    }

    /** FR-270-4 — theme tags: lower-cased, trimmed, de-duplicated, at most [MAX_THEMES]. `null` = no answer. */
    fun validateThemes(r: Result): List<String>? {
        if (r.type != "succeeded" || r.stopReason in setOf("refusal", "max_tokens")) return null
        val arr = runCatching { json.parseToJsonElement(r.text ?: return null).jsonObject["themes"]!!.jsonArray }.getOrNull() ?: return null
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { s -> s.isNotEmpty() && s.length <= 40 } }
            .distinct().take(MAX_THEMES)
    }

    fun requestsJson(requests: List<JsonObject>): String = JsonArray(requests).toString()
}
