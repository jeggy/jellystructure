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
/**
 * One listing field as the model writes it: a single string, [ModelRecipe.field] reads it (`selector@attr` for html, `json:path` for json,
 * blank for none). One string and not a three-key object because the API compiles every schema to a grammar and refuses one that is too
 * large: the 13 three-key objects this answer once held were over it (DEB-3399).
 */
@Serializable
data class MFields(
    val title: String = "", val link: String = "", val published: String = "", val description: String = "", val id: String = "",
    val season: String = "", val episode: String = "", val duration: String = "", val image: String = "",
)

@Serializable
data class MList(
    val kind: String = "html",
    @SerialName("item_selector") val itemSelector: String = "",
    val url: String = "",
    @SerialName("items_path") val itemsPath: String = "",
    val fields: MFields = MFields(),
    val next: String = "",
)

@Serializable
data class MMedia(
    val kind: String = "hls",
    val manifest: String = "",
    @SerialName("manifest_field") val manifestField: String = "",
    @SerialName("item_page") val itemPage: ItemPageSpec? = null,
)

@Serializable
data class MSeries(val name: String = "", val description: String = "", val image: String = "")

@Serializable
data class MMonth(val name: String = "", val month: Int = 0)

@Serializable
data class MDatePattern(val on: String = "published", val regex: String = "", val months: List<MMonth> = emptyList())

@Serializable
data class MRecipe(
    val list: MList = MList(),
    val media: MMedia = MMedia(),
    val numbering: String = "source",
    /** DEB-3567 (FR-4.5) — how the page prints its dates; a blank [MDatePattern.regex] is none. */
    @SerialName("date_pattern") val datePattern: MDatePattern = MDatePattern(),
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

    private val ATTR = Regex("""[A-Za-z_:][A-Za-z0-9_:.-]*""")

    /**
     * One field string, read. `json:a.b[0].c` is a JSON path; anything else is html, `selector@attr`, the attribute being what follows the
     * last `@` when that looks like an attribute name (so `a[href*="@"]` stays a selector) and `text` when there is none. `@href` is the
     * item's own attribute. Blank is absent: the schema makes every property required, so an unused field arrives as an empty string.
     */
    fun field(text: String?): Field? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.startsWith("json:")) return t.removePrefix("json:").trim().takeIf { it.isNotEmpty() }?.let { Field(path = it) }
        val at = t.lastIndexOf('@')
        val attr = if (at >= 0) t.substring(at + 1).trim() else ""
        return if (at >= 0 && ATTR.matches(attr)) Field(selector = t.substring(0, at).trim(), attr = attr) else Field(selector = t, attr = "text")
    }

    private fun itemPage(m: ItemPageSpec?): ItemPageSpec? = m?.takeUnless { it.selector.isBlank() && it.pattern.isBlank() }?.let { it.copy(attr = it.attr.ifBlank { "text" }, pattern = groupSyntax(it.pattern)) }

    /** FR-4.1 (DEB-3459): Python's `(?P<name>…)` names the same group as `(?<name>…)`; `(?P=name)` is not rewritten, so it fails to compile. */
    fun groupSyntax(regex: String): String = regex.replace("(?P<", "(?<")

    /**
     * The engine's recipe for [m]. The page URL is the admin's own, and the hosts are the model's plus the page's: [RecipeValidator]
     * then refuses a non-public name. Retention is left at its default for the saver to set (FR-16.1).
     */
    fun toRecipe(m: MRecipe, pageUrl: String, model: String, today: String): Recipe {
        val fields = LinkedHashMap<String, Field>()
        fun add(k: String, v: String) { field(v)?.let { fields[k] = it } }
        with(m.list.fields) {
            add("title", title); add("link", link); add("published", published); add("description", description); add("id", id)
            add("season", season); add("episode", episode); add("duration", duration); add("image", image)
        }
        val host = Urls.host(pageUrl)
        return Recipe(
            source = RecipeSource(url = pageUrl, hosts = (listOfNotNull(host) + m.hosts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }).distinct()),
            list = ListSpec(kind = m.list.kind, itemSelector = m.list.itemSelector, url = m.list.url, itemsPath = m.list.itemsPath, fields = fields, next = field(m.list.next)),
            media = MediaSpec(kind = m.media.kind, manifest = m.media.manifest, manifestField = field(m.media.manifestField), itemPage = itemPage(m.media.itemPage)),
            numbering = NumberingSpec(m.numbering, m.datePattern.takeIf { it.regex.isNotBlank() && m.numbering in setOf("date", "datetime") }?.let { d ->
                WhenSpec(d.on.trim().ifEmpty { "published" }, groupSyntax(d.regex.trim()),
                    d.months.filter { it.name.isNotBlank() }.associate { it.name.trim().trimEnd('.').lowercase() to it.month })
            }),
            series = m.series?.let { SeriesSpec(name = field(it.name) ?: Field(), description = field(it.description), image = field(it.image)) },
            pattern = m.pattern.map { it.copy(regex = groupSyntax(it.regex)) },
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
     * string is how the model says "none". [parse] and [toRecipe] already read blank as absent. It also refuses a schema whose compiled grammar
     * is too large, which 13 three-key objects were (DEB-3399): a field is one string. [SchemaBudgetTest] holds the first two limits; only the
     * API can say the third, so the build's request body is sent to it with `max_tokens` 16 when this schema changes.
     */
    private val FIELD = str("`selector@attr`: html, a CSS selector relative to the item and `text` or an attribute such as `href`; blank selector = the item itself (`@data-id`). `json:path`: a json list, such as `json:title` or `json:a.b[0].c`. Blank = none")

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
                "item_page" to obj(mapOf("selector" to str(), "attr" to str(), "pattern" to str("one regex with the single named group `(?<value>…)`, not Python's `(?P<value>…)`")), listOf("selector", "attr", "pattern")),
            ), listOf("kind", "manifest")),
            "numbering" to enumOf("source", "date", "datetime", "mapped"),
            "date_pattern" to obj(mapOf(
                "on" to enumOf("published", "title", "description"),
                "regex" to str("named groups `(?<year>…)`, `(?<month>…)`, `(?<day>…)` and for datetime `(?<hour>…)`, `(?<minute>…)`, not Python's `(?P<year>…)`. Blank = none"),
                "months" to buildJsonObject { put("type", "array"); put("items", obj(mapOf("name" to str("the page's month name or abbreviation"), "month" to int()), listOf("name", "month"))) },
            ), listOf("on", "regex", "months")),
            "pattern" to buildJsonObject {
                put("type", "array")
                put("items", obj(mapOf("on" to enumOf("title", "link", "description", "published"), "regex" to str("named groups `(?<season>…)` and/or `(?<episode>…)`, not Python's `(?P<season>…)`")), listOf("on", "regex")))
            },
            "series" to obj(mapOf("name" to FIELD, "description" to FIELD, "image" to FIELD), listOf("name", "description", "image")),
            "hosts" to buildJsonObject { put("type", "array"); put("items", str()) },
            "audio_language" to str("ISO 639-2 of the spoken language, such as `fao`"),
            "subtitle_language" to str("ISO 639-2 of the subtitle track to look for, or blank"),
        ), listOf("list", "media", "numbering", "date_pattern", "pattern", "hosts", "audio_language", "subtitle_language")),
        "examples" to buildJsonObject {
            put("type", "array")
            put("items", obj(mapOf("title" to str(), "season" to int(), "episode" to int(), "date" to str("yyyy-MM-dd, for date and datetime numbering")), listOf("title")))
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
