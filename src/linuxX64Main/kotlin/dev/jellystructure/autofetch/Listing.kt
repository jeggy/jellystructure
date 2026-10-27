package dev.jellystructure.autofetch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One entry of a site's listing as the recipe reads it (FR-4.1). Every value is the page's own text. */
data class ListedItem(
    val title: String? = null,
    val link: String? = null,
    val published: String? = null,
    val description: String? = null,
    val id: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val duration: String? = null,
    val image: String? = null,
    /** 0-based position in the listing, across pages. */
    val order: Int = 0,
)

/** What one read of the listing gave: the items, the next page's URL when the recipe names one, and why a read yielded nothing. */
data class ListingPage(val items: List<ListedItem>, val nextUrl: String? = null)

object ListingReader {
    /** Reads one page of an `html` listing. Links, images and the next-page reference are resolved against [pageUrl]. */
    fun readHtml(list: ListSpec, body: String, pageUrl: String): ListingPage {
        val doc = Html.parse(body)
        val nodes = doc.select(list.itemSelector)
        val items = nodes.mapIndexed { i, n ->
            fun f(key: String): String? = list.fields[key]?.let { htmlField(n, it) }
            ListedItem(
                title = f("title"), link = f("link")?.let { Urls.resolve(pageUrl, it) }, published = f("published"),
                description = f("description"), id = f("id"), season = f("season"), episode = f("episode"),
                duration = f("duration"), image = f("image")?.let { Urls.resolve(pageUrl, it) }, order = i,
            )
        }
        val next = list.next?.let { nf -> htmlField(doc, nf)?.let { Urls.resolve(pageUrl, it) } }
        return ListingPage(items, next)
    }

    /** Reads one page of a `json` listing, or null when the body is not JSON (the caller treats an HTML answer as a challenge, FR-2.5). */
    fun readJson(list: ListSpec, body: String, pageUrl: String): ListingPage? {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        val selected = JsonPaths.select(root, list.itemsPath)
        // `data.items` names the array; `data.items[*]` names its elements one by one. Both read the same list.
        val arr: List<JsonElement> = (selected.singleOrNull() as? JsonArray)?.toList() ?: selected
        val items = arr.mapIndexed { i, el ->
            fun f(key: String): String? = list.fields[key]?.takeIf { it.path.isNotBlank() }?.let { JsonPaths.string(el, it.path) }
            ListedItem(
                title = f("title"), link = f("link")?.let { Urls.resolve(pageUrl, it) ?: it }, published = f("published"),
                description = f("description"), id = f("id"), season = f("season"), episode = f("episode"),
                duration = f("duration"), image = f("image")?.let { Urls.resolve(pageUrl, it) ?: it }, order = i,
            )
        }
        val next = list.next?.takeIf { it.path.isNotBlank() }?.let { JsonPaths.string(root, it.path) }?.let { Urls.resolve(pageUrl, it) }
        return ListingPage(items, next)
    }

    /** A field read from an element: its text, or the named attribute of the first element the selector finds. */
    fun htmlField(node: HtmlNode, f: Field): String? {
        val target = if (f.selector.isBlank()) node else node.selectFirst(f.selector) ?: return null
        val v = if (f.attr.isBlank() || f.attr == "text") target.text() else target.attr(f.attr)
        return v?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** A deliberately small JSON path: `a.b[0].c`, `items[*]`, `[0]`. Reads, never evaluates. */
object JsonPaths {
    private val SEG = Regex("""([^.\[\]]*)((?:\[(?:\d+|\*)])*)""")

    fun select(root: JsonElement, path: String): List<JsonElement> {
        var cur: List<JsonElement> = listOf(root)
        if (path.isBlank()) return cur
        for (seg in path.split('.')) {
            val m = SEG.matchEntire(seg) ?: return emptyList()
            val name = m.groupValues[1]
            val idx = Regex("""\[(\d+|\*)]""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            if (name.isNotEmpty()) cur = cur.mapNotNull { (it as? JsonObject)?.get(name) }
            for (ix in idx) {
                cur = cur.flatMap { e ->
                    val a = e as? JsonArray ?: return@flatMap emptyList()
                    if (ix == "*") a.toList() else listOfNotNull(a.getOrNull(ix.toInt()))
                }
            }
        }
        return cur
    }

    /** The first scalar at [path], as text. */
    fun string(root: JsonElement, path: String): String? {
        val e = select(root, path).firstOrNull() ?: return null
        if (e is JsonNull) return null
        return (e as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}
