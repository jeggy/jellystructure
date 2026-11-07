package dev.jellystructure.autofetch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DEB-2476 (DEB-2474, FR-4.1) — a recipe: data in a fixed schema, version 1, read by the fixed engine in this package.
 * Nothing in it is executed. A URL template may only name a host recorded in [RecipeSource.hosts]; a pattern is a
 * regular expression from a restricted grammar ([PatternSafety]) that is checked before it is stored.
 */
@Serializable
data class Recipe(
    val version: Int = 1,
    val source: RecipeSource,
    val list: ListSpec,
    val media: MediaSpec,
    val numbering: NumberingSpec = NumberingSpec(),
    /** Page-only fetchers only (FR-15.3): where the page gives the programme's name, description and image. */
    val series: SeriesSpec? = null,
    val retention: RetentionSpec = RetentionSpec(),
    /** For `source` numbering (FR-4.1): ordered rules; the first that yields a season and the first that yields an episode win. */
    val pattern: List<PatternRule> = emptyList(),
    val language: LanguageSpec = LanguageSpec(),
    val quality: String = "highest",
    /** FR-4.4 — the model that wrote it and the date; blank for a recipe entered by hand. */
    @SerialName("written_by") val writtenBy: String = "",
    @SerialName("written_at") val writtenAt: String = "",
)

@Serializable
data class RecipeSource(
    val url: String,
    /** The hosts the page, its listing, manifests and media resolve to (FR-2). Recorded at save. */
    val hosts: List<String> = emptyList(),
)

/** Where one value is read: an HTML [selector] and [attr] (`text` for the element's text), or a JSON [path]. */
@Serializable
data class Field(val selector: String = "", val attr: String = "text", val path: String = "")

@Serializable
data class ListSpec(
    /** `html` · `json` · `ytdlp` (yt-dlp's flat playlist of the source page, FR-4.1, FR-6.3). */
    val kind: String = "html",
    /** `html`: a CSS selector for each item. */
    @SerialName("item_selector") val itemSelector: String = "",
    /** `json`: a URL template on the same site, and the path to the array of items. */
    val url: String = "",
    @SerialName("items_path") val itemsPath: String = "",
    /** `title` `link` `published` `description` `id` `season` `episode` `duration` (seconds) `image`. */
    val fields: Map<String, Field> = emptyMap(),
    /** The next page of a paged listing; read at most [MAX_PAGES] pages (FR-16.5). */
    val next: Field? = null,
)

const val MAX_PAGES = 10

@Serializable
data class MediaSpec(
    /** `hls` · `dash` · `ytdlp` (the item's own link, handed to yt-dlp, FR-6.3). */
    val kind: String = "hls",
    /** A manifest URL template with `{id}` (the listing's `id` field) and `{value}` (the item page's value). */
    val manifest: String = "",
    /** Or a listing field that already holds the manifest URL. */
    @SerialName("manifest_field") val manifestField: Field? = null,
    @SerialName("item_page") val itemPage: ItemPageSpec? = null,
)

/**
 * `item_page` (FR-4.1, 2026-10-27): the listing gives an item's link but not its media id, so the engine opens the item's
 * own page and reads one value from it, by CSS [selector] and [attr], or by one [pattern] rule with the group `value`
 * applied to the page's source (script text counts as page text).
 */
@Serializable
data class ItemPageSpec(
    val selector: String = "",
    val attr: String = "text",
    val pattern: String = "",
)

@Serializable
data class NumberingSpec(
    /** `source` · `date` (page-only only, FR-4.5) · `mapped` (a TMDB fetcher only, FR-4.3: the table is the items' approved numbers, not part of the recipe). */
    val kind: String = "source",
    /** DEB-3567 (FR-4.5) — how this page prints its dates; `datetime` needs it, `date` may carry it. */
    @SerialName("when") val whenPattern: WhenSpec? = null,
)

/** `numbering.when`: [on] the listing field it reads, [regex] with the named groups `year`, `month`, `day` (and `hour`, `minute`), [months] the page's own names for 1–12. */
@Serializable
data class WhenSpec(
    val on: String = "published",
    val regex: String = "",
    val months: Map<String, Int> = emptyMap(),
)

@Serializable
data class SeriesSpec(
    val name: Field = Field(),
    val description: Field? = null,
    val image: Field? = null,
)

/** FR-16.1: `all`, or `newest` N with *leaves the page* on or off. */
@Serializable
data class RetentionSpec(
    val mode: String = "all",
    val keep: Int = 12,
    @SerialName("leaves_page") val leavesPage: Boolean = false,
) {
    val keepsAll: Boolean get() = mode != "newest"
}

@Serializable
data class PatternRule(
    /** The item field the regex reads: `title` · `link` · `description` · `published`. */
    val on: String = "title",
    val regex: String,
)

@Serializable
data class LanguageSpec(
    /** The spoken language, ISO 639-2 (`fao`). */
    val audio: String = "",
    /** The subtitle language to look for on every episode. */
    val subtitle: String = "",
)

object RecipeCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    fun decode(text: String): Recipe? = runCatching { json.decodeFromString(Recipe.serializer(), text) }.getOrNull()
    fun encode(recipe: Recipe): String = json.encodeToString(Recipe.serializer(), recipe)
}

/** The refusals a saved recipe can carry. Every string here is shown to the admin as written. */
object RecipeRefusal {
    const val MAPPED_PAGE_ONLY = "mapped numbering is offered to a TMDB fetcher only; a page-only fetcher has nothing to map to"
    const val DRM = "this site protects its videos; Auto-fetch does not get past that"
    const val NO_TMDB_ID = "Auto-fetch needs a TMDB id, or tick \"not on TMDB\""
}

object RecipeValidator {
    private val PLACEHOLDERS = Regex("""\{([A-Za-z_]+)}""")
    private val FIELDS = setOf("title", "link", "published", "description", "id", "season", "episode", "duration", "image")

    /**
     * DEB-3715 (DEB-3567, FR-3.6) — the reasons the recipe's *listing* cannot be read: its version, hosts, `list` and the list's fields. Media, numbering and
     * retention are not looked at; a `needs_sign_in` answer is accepted on a listing alone (once a read was refused, DEB-3839).
     */
    fun validateListing(recipe: Recipe): List<String> {
        val errors = ArrayList<String>()
        if (recipe.version != 1) errors += "recipe version ${recipe.version} is not supported"
        val pageHost = Urls.host(recipe.source.url)
        if (pageHost == null) errors += "the page URL is not an http or https address"
        val hosts = (recipe.source.hosts + listOfNotNull(pageHost)).map { it.lowercase() }.toSet()
        // DEB-2478 — a recipe the model wrote may name only public hosts (an investigation's page URL is the admin's own choice and exempt).
        recipe.source.hosts.firstOrNull { !Urls.publicName(it) }?.let { errors += "the recipe records $it, which is not a public host name" }
        run {
            val fields = YtDlpListing.fieldsOf(recipe.list)
            when (recipe.list.kind) {
                "html" -> {
                    if (recipe.list.itemSelector.isBlank()) errors += "the html list needs an item selector"
                    else if (Selector.parse(recipe.list.itemSelector) == null) errors += "the item selector is not a supported selector"
                }
                "json" -> {
                    if (recipe.list.url.isBlank()) errors += "the json list needs a URL"
                    else errors += templateErrors("list.url", recipe.list.url, hosts, allowed = emptySet())
                    if (recipe.list.itemsPath.isBlank()) errors += "the json list needs the path to its items"
                }
                // FR-4.1 — yt-dlp reads the source page itself; the items are its `entries` unless the recipe says otherwise.
                "ytdlp" -> if (recipe.list.url.isNotBlank()) errors += "a ytdlp list reads the source page; it takes no URL"
                else -> errors += "unknown list kind '${recipe.list.kind}'"
            }
            for ((k, f) in recipe.list.fields) {
                if (k !in FIELDS) errors += "unknown listing field '$k'"
                else if (recipe.list.kind == "html" && f.selector.isNotBlank() && Selector.parse(f.selector) == null) errors += "the $k selector is not a supported selector"
                else if ((recipe.list.kind == "json" || recipe.list.kind == "ytdlp") && f.path.isBlank() && k != "image") errors += "the $k field needs a path"
            }
            if (fields["link"] == null) errors += "the listing needs a link field"
        }
        return errors
    }

    /** The reasons [recipe] cannot be stored, or an empty list. Checks shape and safety only; the proof against the site is [RecipeProof]. */
    fun validate(recipe: Recipe, pageOnly: Boolean): List<String> {
        val errors = ArrayList(validateListing(recipe))
        val pageHost = Urls.host(recipe.source.url)
        val hosts = (recipe.source.hosts + listOfNotNull(pageHost)).map { it.lowercase() }.toSet()
        val fields = YtDlpListing.fieldsOf(recipe.list)
        run {
            if (recipe.media.kind !in setOf("hls", "dash", "ytdlp")) errors += "unknown media kind '${recipe.media.kind}'"
            if (recipe.media.kind == "ytdlp") {
                if (recipe.media.manifest.isNotBlank() || recipe.media.manifestField != null || recipe.media.itemPage != null)
                    errors += "a ytdlp media kind takes the item's link; it has no manifest and no item_page"
            } else {
                if (recipe.media.manifest.isBlank() && recipe.media.manifestField == null) errors += "the recipe says nothing about where an item's manifest is"
                if (recipe.media.manifest.isNotBlank()) errors += templateErrors("media.manifest", recipe.media.manifest, hosts, setOf("id", "value"))
                if (recipe.media.manifest.contains("{value}") && recipe.media.itemPage == null) errors += "the manifest uses {value} but the recipe has no item_page"
                if (recipe.media.manifest.contains("{id}") && fields["id"] == null) errors += "the manifest uses {id} but the listing has no id field"
                recipe.media.itemPage?.let { ip ->
                    if (ip.pattern.isBlank() == ip.selector.isBlank()) errors += "item_page needs either a selector or a pattern, not both"
                    if (ip.selector.isNotBlank() && Selector.parse(ip.selector) == null) errors += "the item_page selector is not a supported selector"
                    if (ip.pattern.isNotBlank()) PatternSafety.check(ip.pattern, required = setOf("value"))?.let { errors += "item_page pattern: $it" }
                }
            }
        }
        when (recipe.numbering.kind) {
            "source" -> {
                if (recipe.pattern.isEmpty() && recipe.list.fields["season"] == null) errors += "source numbering needs at least one pattern rule"
                recipe.pattern.forEachIndexed { i, r ->
                    if (r.on !in setOf("title", "link", "description", "published")) errors += "pattern rule ${i + 1}: unknown field '${r.on}'"
                    PatternSafety.check(r.regex, required = emptySet(), anyOf = setOf("season", "episode"))?.let { errors += "pattern rule ${i + 1}: $it" }
                }
            }
            "date", "datetime" -> {
                val kind = recipe.numbering.kind
                val w = recipe.numbering.whenPattern
                if (!pageOnly) errors += "$kind numbering is offered only to a page-only fetcher"
                if (kind == "datetime" && w == null) errors += "datetime numbering needs numbering.when, how the page prints its dates"
                val on = w?.on ?: "published"
                if (on !in setOf("title", "description", "published")) errors += "numbering.when reads title, description or published, not '$on'"
                else if (YtDlpListing.fieldsOf(recipe.list)[on] == null) errors += "$kind numbering needs the listing's $on field"
                if (w != null) {
                    val needed = if (kind == "datetime") setOf("year", "month", "day", "hour", "minute") else setOf("year", "month", "day")
                    PatternSafety.check(w.regex, required = needed, anyOf = setOf("hour", "minute").minus(needed))?.let { errors += "numbering.when: $it" }
                    if (w.months.values.any { it !in 1..12 }) errors += "numbering.when: a month name maps to 1–12"
                }
            }
            // FR-4.3 — the table is not in the recipe: an item's number is the one the admin approved, held on the item.
            "mapped" -> if (pageOnly) errors += RecipeRefusal.MAPPED_PAGE_ONLY
            else -> errors += "unknown numbering '${recipe.numbering.kind}'"
        }
        if (pageOnly) {
            if (recipe.series == null) errors += "a page-only recipe needs a series block"
            recipe.series?.let { s ->
                (listOfNotNull(s.name, s.description, s.image)).forEach { f ->
                    if (f.selector.isNotBlank() && Selector.parse(f.selector) == null) errors += "a series selector is not a supported selector"
                }
            }
        }
        val r = recipe.retention
        if (r.mode != "all" && r.mode != "newest") errors += "retention must be all or newest"
        if (r.mode == "newest" && r.keep !in 1..999) errors += "Keep the newest N takes a number from 1 to 999"
        if (recipe.language.audio.isNotBlank() && !Regex("^[a-z]{3}$").matches(recipe.language.audio)) errors += "the audio language must be an ISO 639-2 code"
        if (recipe.language.subtitle.isNotBlank() && !Regex("^[a-z]{3}$").matches(recipe.language.subtitle)) errors += "the subtitle language must be an ISO 639-2 code"
        return errors
    }

    /** Hosts a saved recipe's lane covers: the page, the declared hosts, and the hosts its templates name. */
    fun lanes(recipe: Recipe): Set<String> {
        val out = LinkedHashSet<String>()
        Urls.host(recipe.source.url)?.let(out::add)
        recipe.source.hosts.forEach { out += it.lowercase() }
        return out
    }

    private fun templateErrors(label: String, template: String, hosts: Set<String>, allowed: Set<String>): List<String> {
        val errors = ArrayList<String>()
        for (m in PLACEHOLDERS.findAll(template)) if (m.groupValues[1] !in allowed) errors += "$label: unknown placeholder '{${m.groupValues[1]}}'"
        // A placeholder in the host position would let a page choose where the engine connects.
        val h = Urls.host(template.replace(PLACEHOLDERS, "x"))
        if (h == null) errors += "$label is not an http or https address"
        else if (template.substringAfter("://").substringBefore('/').contains('{')) errors += "$label: the host cannot be a placeholder"
        else if (h !in hosts) errors += "$label names $h, which the recipe's source does not record"
        return errors
    }
}

object Urls {
    /** The lowercased host of an http(s) URL, or null. */
    fun host(url: String): String? {
        val u = url.trim()
        val rest = when {
            u.startsWith("https://", ignoreCase = true) -> u.substring(8)
            u.startsWith("http://", ignoreCase = true) -> u.substring(7)
            else -> return null
        }
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.contains('@')) return null // a userinfo part hides the real host from a reader of the URL
        val h = authority.substringBefore(':').lowercase()
        return h.takeIf { it.isNotEmpty() && Regex("^[a-z0-9.-]+$").matches(it) && !it.startsWith(".") }
    }

    private val INTERNAL_SUFFIXES = listOf(".local", ".lan", ".internal", ".home.arpa", ".localdomain", ".localhost", ".intranet", ".corp", ".home")

    /**
     * DEB-2478 (FR-5.3) — a host a model-written recipe may record: a public-looking name. An IP literal, `localhost`, a name with no
     * dot and the usual internal suffixes are refused, so a page that talks the model into naming one still cannot point this server at
     * the household's own network. (A name that merely resolves to a private address is the admin's own choice of URL.)
     */
    fun publicName(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || !h.contains('.')) return false
        if (h.all { it.isDigit() || it == '.' }) return false
        return INTERNAL_SUFFIXES.none { h.endsWith(it) }
    }

    /** The lane key for a page URL: its host without a leading `www.`. */
    fun siteOf(url: String): String? = host(url)?.removePrefix("www.")

    /**
     * DEB-3643 (FR-2.7) — the registrable domain of [host]: its public suffix and one label before it (`w-vod-edge2.kringvarp.fo` → `kringvarp.fo`,
     * `a.example.co.uk` → `example.co.uk`). The list is the multi-label suffixes of the public suffix list that a household's sites can plausibly sit
     * under, plus the hosting suffixes whose subdomains belong to different owners (`x.github.io` and `y.github.io` are not one operator). A bare
     * IP address is its own domain.
     */
    fun registrableDomain(host: String): String {
        val h = host.lowercase().trimEnd('.')
        if (h.all { it.isDigit() || it == '.' } || h.contains(':')) return h
        val labels = h.split('.')
        if (labels.size <= 2) return h
        val keep = if (labels.takeLast(2).joinToString(".") in TWO_LABEL_SUFFIXES) 3 else if (labels.takeLast(3).joinToString(".") in THREE_LABEL_SUFFIXES) 4 else 2
        return labels.takeLast(minOf(keep, labels.size)).joinToString(".")
    }

    private val TWO_LABEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk", "com.au", "net.au", "org.au", "edu.au", "gov.au", "co.nz", "net.nz", "org.nz",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "com.br", "net.br", "org.br", "com.mx", "com.ar", "com.co", "co.za", "org.za", "co.in", "net.in", "org.in", "com.cn", "net.cn", "org.cn",
        "com.tr", "com.tw", "com.hk", "com.sg", "co.kr", "co.il", "co.id", "co.th", "com.my", "com.ph", "com.vn", "com.ua", "com.pl", "com.ru", "com.es", "com.pt", "com.gr",
        // Hosting suffixes: the label before them is a tenant, not the operator.
        "github.io", "gitlab.io", "blogspot.com", "cloudfront.net", "herokuapp.com", "azurewebsites.net", "amazonaws.com", "vercel.app", "netlify.app", "pages.dev", "workers.dev",
        "web.app", "firebaseapp.com", "appspot.com", "wordpress.com", "wixsite.com", "weebly.com", "fastly.net", "akamaized.net", "edgekey.net", "azureedge.net",
    )
    private val THREE_LABEL_SUFFIXES = setOf("s3.amazonaws.com", "elb.amazonaws.com", "compute.amazonaws.com")

    /** [ref] resolved against [base] (absolute, protocol-relative, root-relative or relative). Null when it is not http(s). */
    fun resolve(base: String, ref: String): String? {
        val r = ref.trim()
        if (r.isEmpty()) return null
        if (r.startsWith("http://", true) || r.startsWith("https://", true)) return r
        val scheme = if (base.startsWith("https://", true)) "https" else if (base.startsWith("http://", true)) "http" else return null
        val authority = base.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (r.startsWith("//")) return "$scheme:$r"
        if (r.startsWith("/")) return "$scheme://$authority$r"
        if (r.startsWith("#") || r.lowercase().startsWith("javascript:") || r.lowercase().startsWith("mailto:") || r.contains(':').and(r.substringBefore(':').all { it.isLetter() })) return null
        val path = base.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val dir = if (path.contains('/')) path.substringBeforeLast('/') + "/" else ""
        return "$scheme://$authority/$dir$r"
    }

    /** `scheme://authority` of an http(s) URL. */
    fun origin(url: String): String {
        val scheme = if (url.startsWith("http://", true)) "http" else "https"
        return "$scheme://" + url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    }

    /** The path of a URL, starting with `/`, without its query. */
    fun path(url: String): String {
        val after = url.substringAfter("://", url)
        val p = after.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return "/$p"
    }
}
