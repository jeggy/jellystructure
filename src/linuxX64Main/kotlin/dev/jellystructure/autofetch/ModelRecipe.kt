package dev.jellystructure.autofetch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DEB-2478 (DEB-2474, FR-5.1, FR-4.1) — what the model answers an investigation with, and how it becomes the engine's [Recipe].
 *
 * The model's answer is a proposal in a fixed schema, constrained by `output_config.format`. A structured-output schema cannot hold
 * the recipe's free-keyed `fields` map, so the answer names each listing field as a property and [toRecipe] folds them back. Nothing
 * the model writes is executed or trusted: the result goes through [RecipeValidator] and the proof ([RecipeProof]) before it is shown,
 * and the model's own [Example]s are compared with what the engine reads.
 */
@Serializable
data class MField(val selector: String = "", val attr: String = "", val path: String = "")

@Serializable
data class MFields(
    val title: MField? = null, val link: MField? = null, val published: MField? = null, val description: MField? = null, val id: MField? = null,
    val season: MField? = null, val episode: MField? = null, val duration: MField? = null, val image: MField? = null,
)

@Serializable
data class MList(
    val kind: String = "html",
    @SerialName("item_selector") val itemSelector: String = "",
    val url: String = "",
    @SerialName("items_path") val itemsPath: String = "",
    val fields: MFields = MFields(),
    val next: MField? = null,
)

@Serializable
data class MMedia(
    val kind: String = "hls",
    val manifest: String = "",
    @SerialName("manifest_field") val manifestField: MField? = null,
    @SerialName("item_page") val itemPage: ItemPageSpec? = null,
)

@Serializable
data class MSeries(val name: MField = MField(), val description: MField? = null, val image: MField? = null)

@Serializable
data class MRecipe(
    val list: MList = MList(),
    val media: MMedia = MMedia(),
    val numbering: String = "source",
    val pattern: List<PatternRule> = emptyList(),
    val series: MSeries? = null,
    val hosts: List<String> = emptyList(),
    @SerialName("audio_language") val audioLanguage: String = "",
    @SerialName("subtitle_language") val subtitleLanguage: String = "",
)

/** One item the model read on the listing and the number it says the recipe gives it; the engine checks it (FR-4.2, *the same answer the model gave*). */
@Serializable
data class Example(val title: String = "", val season: Int? = null, val episode: Int? = null, val date: String? = null)

/**
 * FR-4.3 — one row of the model's proposed table for `mapped` numbering: the item (its link as the page gives it, and its title as listed), the
 * TMDB episode the model gives it, and why. The engine resolves the row to a listing item; nothing in it is stored until the admin accepts it.
 */
@Serializable
data class MapRow(val link: String = "", val title: String = "", val season: Int = 0, val episode: Int = 0, val why: String = "")

@Serializable
data class MAnswer(
    /** `series` · `not_a_series`. */
    val verdict: String = "",
    val reason: String = "",
    val recipe: MRecipe? = null,
    val examples: List<Example> = emptyList(),
    /** `mapped` numbering only (FR-4.3). */
    val mapping: List<MapRow> = emptyList(),
)

object ModelRecipe {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): MAnswer? = runCatching { json.decodeFromString(MAnswer.serializer(), text.trim()) }.getOrNull()

    /**
     * The schema makes every property required (the API caps a request's optional parameters at 24), so an unused field arrives as
     * three blank strings: that is absent, not a field that reads the item's own text.
     */
    private fun f(m: MField?): Field? = m?.takeUnless { it.selector.isBlank() && it.attr.isBlank() && it.path.isBlank() }
        ?.let { Field(selector = it.selector, attr = it.attr.ifBlank { "text" }, path = it.path) }

    private fun itemPage(m: ItemPageSpec?): ItemPageSpec? = m?.takeUnless { it.selector.isBlank() && it.pattern.isBlank() }?.let { it.copy(attr = it.attr.ifBlank { "text" }) }

    /**
     * The engine's recipe for [m]. The page URL is the admin's own, and the hosts are the model's plus the page's: [RecipeValidator]
     * then refuses a non-public name. Retention is left at its default for the saver to set (FR-16.1).
     */
    fun toRecipe(m: MRecipe, pageUrl: String, model: String, today: String): Recipe {
        val fields = LinkedHashMap<String, Field>()
        fun add(k: String, v: MField?) { f(v)?.let { fields[k] = it } }
        with(m.list.fields) {
            add("title", title); add("link", link); add("published", published); add("description", description); add("id", id)
            add("season", season); add("episode", episode); add("duration", duration); add("image", image)
        }
        val host = Urls.host(pageUrl)
        return Recipe(
            source = RecipeSource(url = pageUrl, hosts = (listOfNotNull(host) + m.hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }).distinct()),
            list = ListSpec(kind = m.list.kind, itemSelector = m.list.itemSelector, url = m.list.url, itemsPath = m.list.itemsPath, fields = fields, next = f(m.list.next)),
            media = MediaSpec(kind = m.media.kind, manifest = m.media.manifest, manifestField = f(m.media.manifestField), itemPage = itemPage(m.media.itemPage)),
            numbering = NumberingSpec(m.numbering),
            series = m.series?.let { SeriesSpec(name = f(it.name) ?: Field(), description = f(it.description), image = f(it.image)) },
            pattern = m.pattern,
            language = LanguageSpec(audio = m.audioLanguage.trim().lowercase(), subtitle = m.subtitleLanguage.trim().lowercase()),
            writtenBy = model, writtenAt = today,
        )
    }

    // ---- the schema ----------------------------------------------------------------------------------------------------------

    private fun str(desc: String? = null) = buildJsonObject { put("type", "string"); if (desc != null) put("description", desc) }
    private fun int() = buildJsonObject { put("type", "integer") }
    private fun obj(props: Map<String, JsonObject>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }
    private fun enumOf(vararg v: String) = buildJsonObject { put("type", "string"); put("enum", buildJsonArray { v.forEach { add(JsonPrimitive(it)) } }) }

    /**
     * The API refuses a request whose schemas hold more than 24 optional parameters, or more than 16 union-typed ones (found on DEB-3373: this
     * schema had 74). `anyOf [T, null]` counts as a union, so neither optional nor nullable is the way out: a property is required, and a blank
     * string is how the model says "none". [parse] and [toRecipe] already read blank as absent. [SchemaBudgetTest] holds the schema to both limits.
     */
    private val FIELD = obj(mapOf(
        "selector" to str("CSS selector, relative to the item (html); blank = the item itself"),
        "attr" to str("`text`, or an attribute name such as `href`"),
        "path" to str("JSON path (json lists), such as `title` or `a.b[0].c`"),
    ), listOf("selector", "attr", "path"))

    /** The `output_config.format.schema` of an investigation's final answer. */
    val SCHEMA: JsonObject = obj(mapOf(
        "verdict" to enumOf("series", "not_a_series"),
        "reason" to str("one plain sentence: why this is, or is not, a series Auto-fetch can fetch"),
        "recipe" to obj(mapOf(
            "list" to obj(mapOf(
                "kind" to enumOf("html", "json", "ytdlp"),
                "item_selector" to str("html: CSS selector matching each episode on the page"),
                "url" to str("json: the listing's URL on the page's own site"),
                "items_path" to str("json: path to the array of episodes"),
                "fields" to obj(mapOf(
                    "title" to FIELD, "link" to FIELD, "published" to FIELD, "description" to FIELD, "id" to FIELD,
                    "season" to FIELD, "episode" to FIELD, "duration" to FIELD, "image" to FIELD,
                ), listOf("title", "link", "published", "description", "id", "season", "episode", "duration", "image")),
                "next" to FIELD,
            ), listOf("kind", "item_selector", "url", "items_path", "fields")),
            "media" to obj(mapOf(
                "kind" to enumOf("hls", "dash", "ytdlp"),
                "manifest" to str("manifest URL template with {id} (the listing's id field) and/or {value} (item_page's value), on a recorded host"),
                "manifest_field" to FIELD,
                "item_page" to obj(mapOf("selector" to str(), "attr" to str(), "pattern" to str("one regex with the single named group `value`")), listOf("selector", "attr", "pattern")),
            ), listOf("kind", "manifest")),
            "numbering" to enumOf("source", "date", "mapped"),
            "pattern" to buildJsonObject {
                put("type", "array")
                put("items", obj(mapOf("on" to enumOf("title", "link", "description", "published"), "regex" to str("named groups `season` and/or `episode`")), listOf("on", "regex")))
            },
            "series" to obj(mapOf("name" to FIELD, "description" to FIELD, "image" to FIELD), listOf("name", "description", "image")),
            "hosts" to buildJsonObject { put("type", "array"); put("items", str()) },
            "audio_language" to str("ISO 639-2 of the spoken language, such as `fao`"),
            "subtitle_language" to str("ISO 639-2 of the subtitle track to look for, or blank"),
        ), listOf("list", "media", "numbering", "pattern", "hosts", "audio_language", "subtitle_language")),
        "examples" to buildJsonObject {
            put("type", "array")
            put("items", obj(mapOf("title" to str(), "season" to int(), "episode" to int(), "date" to str("yyyy-MM-dd, for date numbering")), listOf("title")))
        },
        "mapping" to buildJsonObject {
            put("type", "array")
            put("items", obj(mapOf(
                "link" to str("the item's link exactly as the page gives it"), "title" to str("the item's title as listed"),
                "season" to int(), "episode" to int(), "why" to str("one short phrase: what in the title or description matches TMDB's episode"),
            ), listOf("link", "title", "season", "episode", "why")))
        },
    ), listOf("verdict", "reason"))
}
