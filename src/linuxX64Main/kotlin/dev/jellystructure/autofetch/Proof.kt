package dev.jellystructure.autofetch

/**
 * DEB-2476 (DEB-2474, FR-4.2, FR-15.8) — the listing read and the recipe's proof against the live site, without the model.
 * A recipe is accepted only when the engine, running it itself, gets an answer it can stand behind.
 */
sealed class ListingRead {
    /** [full] is true when every page read answered `2xx` and parsed (FR-16.5). [firstBody] is the source page's body for `series` fields. */
    data class Read(val items: List<ListedItem>, val full: Boolean, val firstBody: String, val pageUrl: String) : ListingRead()
    /** The lane refused or the site answered badly before any item was read. */
    data class Blocked(val outcome: LaneOutcome) : ListingRead()
}

object ListingFetcher {
    /** Reads the recipe's listing through [lane]: the first page, then `next` pages up to [MAX_PAGES], on the same hosts only. */
    suspend fun read(recipe: Recipe, lane: SiteLane): ListingRead {
        val hosts = RecipeValidator.lanes(recipe)
        var url: String? = if (recipe.list.kind == "json") recipe.list.url else recipe.source.url
        val expect = if (recipe.list.kind == "json") Expect.JSON else Expect.HTML
        val all = ArrayList<ListedItem>()
        val seen = HashSet<String>()
        var firstBody = ""
        var firstUrl = url.orEmpty()
        var full = true
        var pages = 0
        while (url != null && pages < MAX_PAGES && seen.add(url)) {
            val outcome = lane.page(url, expect, listing = true, hosts = hosts)
            if (outcome !is LaneOutcome.Ok) {
                if (pages == 0) return ListingRead.Blocked(outcome)
                full = false; break
            }
            val body = outcome.reply.body
            if (pages == 0) { firstBody = body; firstUrl = url }
            val base = outcome.reply.finalUrl ?: url // FR-2.7: relative links read against where the answer came from
            val page = if (recipe.list.kind == "json") ListingReader.readJson(recipe.list, body, base, recipe.media.manifestField) else ListingReader.readHtml(recipe.list, body, base, recipe.media.manifestField)
            if (page == null) { // an answer that is not JSON where JSON was expected
                if (pages == 0) return ListingRead.Blocked(LaneOutcome.Failed("the listing is not JSON"))
                full = false; break
            }
            page.items.forEach { all += it.copy(order = all.size) }
            pages++
            val next = page.nextUrl
            url = if (next != null && Urls.host(next) in hosts) next else { if (next != null) full = false; null }
        }
        if (url != null && pages >= MAX_PAGES) full = false // more pages than the cap: not read in full
        return ListingRead.Read(all, full, firstBody, firstUrl)
    }
}

data class SeriesFacts(val name: String?, val description: String?, val image: String?)

data class ProofResult(
    val ok: Boolean,
    /** The failing check, in the admin's words (FR-4.2: *Not a series* with the failing check as its reason). */
    val failure: String? = null,
    val items: List<ListedItem> = emptyList(),
    val numbered: List<Pair<ListedItem, SeasonEpisode>> = emptyList(),
    val agreements: Int = 0,
    val tmdbChecked: Int = 0,
    val disagreements: List<String> = emptyList(),
    /** Q1: two agreements and none against. A page-only fetcher has nothing to decide, so it is not "proven" in this sense. */
    val proven: Boolean = false,
    val series: SeriesFacts? = null,
    val probe: ManifestFacts? = null,
    val blocked: LaneOutcome? = null,
)

class RecipeProof(private val tools: MediaTools, private val world: AutofetchWorld) {

    suspend fun prove(recipe: Recipe, pageOnly: Boolean, tmdbId: Int?, lane: SiteLane): ProofResult {
        val read = ListingFetcher.read(recipe, lane)
        if (read is ListingRead.Blocked) return fail(describe(read.outcome)).copy(blocked = read.outcome)
        read as ListingRead.Read
        val items = read.items
        if (items.size < 2) return fail("the listing yields ${items.size} item${if (items.size == 1) "" else "s"}; a series needs at least two", items)
        for (it in items) {
            if (it.link.isNullOrBlank()) return fail("an item has no link", items)
            if (pageOnly) { if (it.title.isNullOrBlank() && Numbering.isoDate(it.published) == null) return fail("an item has neither a title nor a date", items) }
            else if (it.title.isNullOrBlank()) return fail("an item has no title", items)
        }
        val numbered = ArrayList<Pair<ListedItem, SeasonEpisode>>()
        for (it in items) {
            val se = numberOf(recipe, it) ?: return fail("'${it.title ?: it.link}' maps to no season and episode", items)
            numbered += it to se
        }
        numbered.groupBy { it.second }.entries.firstOrNull { it.value.size > 1 }?.let { (se, group) ->
            val what = if (recipe.numbering.kind == "date") "share the date ${Numbering.isoDate(group[0].first.published)}" else "both map to ${Numbering.code(se)}"
            return fail("${group.size} items $what; every item needs a number of its own", items, numbered)
        }
        val series = if (pageOnly) readSeries(recipe, read) else null
        if (pageOnly && series?.name.isNullOrBlank()) return fail("the page gives the programme no name", items, numbered)

        val probe = probeOne(recipe, numbered.first().first, lane) ?: return fail("no manifest could be probed", items, numbered)
        probe.failure?.let { return fail(it, items, numbered) }
        val facts = probe.facts!!
        if (!facts.hasVideo || facts.durationSec == null || facts.durationSec <= 0.0) return fail("the manifest has no video variant ffprobe can read, with a duration", items, numbered)

        if (pageOnly || tmdbId == null) {
            return ProofResult(true, items = items, numbered = numbered, series = series, probe = facts)
        }
        val tmdb = world.tmdbEpisodes(tmdbId) ?: return fail("TMDB could not be reached to check the numbering", items, numbered)
        val byKey = tmdb.associateBy { it.season to it.episode }
        var agree = 0; var checked = 0
        val against = ArrayList<String>()
        for ((it, se) in numbered) {
            val t = byKey[se.season to se.episode] ?: continue
            checked++
            val ok = datesWithin(it.published, t.airDate, 7) || (it.title != null && it.title.trim().equals(t.name.trim(), ignoreCase = true))
            if (ok) agree++ else against += "${Numbering.code(se)}: '${it.title ?: it.link}' against TMDB's '${t.name}' (${t.airDate ?: "no date"})"
        }
        if (against.isNotEmpty()) {
            return ProofResult(false, "the pattern disagrees with TMDB on ${against.size} of $checked episode${if (checked == 1) "" else "s"} TMDB has",
                items, numbered, agree, checked, against, false, null, facts)
        }
        return ProofResult(true, items = items, numbered = numbered, agreements = agree, tmdbChecked = checked, proven = agree >= 2, probe = facts)
    }

    private fun fail(why: String, items: List<ListedItem> = emptyList(), numbered: List<Pair<ListedItem, SeasonEpisode>> = emptyList()) =
        ProofResult(false, why, items, numbered)

    private fun describe(o: LaneOutcome): String = when (o) {
        is LaneOutcome.Stopped -> "the site is stopped: ${o.reason}"
        is LaneOutcome.BackOff -> "the site asked us to slow down: ${o.reason}"
        is LaneOutcome.Forbidden -> o.reason
        is LaneOutcome.Failed -> o.reason
        is LaneOutcome.Ok -> "ok"
    }

    internal fun numberOf(recipe: Recipe, item: ListedItem): SeasonEpisode? = when (recipe.numbering.kind) {
        "date" -> Numbering.isoDate(item.published)?.let { Numbering.fromDate(it) }
        "source" -> Numbering.fromSource(recipe.pattern, item)
        else -> null
    }

    private fun datesWithin(a: String?, b: String?, days: Int): Boolean {
        val x = Numbering.isoDate(a)?.let { epochDay(it) } ?: return false
        val y = Numbering.isoDate(b)?.let { epochDay(it) } ?: return false
        return kotlin.math.abs(x - y) <= days
    }

    /** The number of days since 1970-01-01 for a `yyyy-MM-dd`. */
    private fun epochDay(iso: String): Long {
        val p = iso.split('-').map { it.toInt() }
        var y = p[0].toLong(); val m = p[1]; val d = p[2]
        if (m <= 2) y -= 1
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    class Probed(val facts: ManifestFacts?, val failure: String?)

    private suspend fun probeOne(recipe: Recipe, item: ListedItem, lane: SiteLane): Probed? {
        val manifestUrl = ManifestResolver.resolveWithPage(recipe, item, lane) { _, _ -> }
        return when (manifestUrl) {
            is ManifestResolver.Resolved -> {
                val m = lane.page(manifestUrl.url, Expect.MANIFEST, player = true, hosts = RecipeValidator.lanes(recipe))
                if (m !is LaneOutcome.Ok) return Probed(null, describe(m))
                if (Manifests.hasDrm(m.reply.body)) return Probed(null, RecipeRefusal.DRM)
                Probed(tools.probeManifest(m.reply.finalUrl ?: manifestUrl.url, m.reply.body, recipe.media.kind), null)
            }
            is ManifestResolver.Skipped -> Probed(null, manifestUrl.reason)
        }
    }

    private fun readSeries(recipe: Recipe, read: ListingRead.Read): SeriesFacts? {
        val s = recipe.series ?: return null
        return if (recipe.list.kind == "json") {
            val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(read.firstBody) }.getOrNull() ?: return null
            SeriesFacts(JsonPaths.string(root, s.name.path), s.description?.let { JsonPaths.string(root, it.path) }, s.image?.let { JsonPaths.string(root, it.path) }?.let { Urls.resolve(read.pageUrl, it) ?: it })
        } else {
            val doc = Html.parse(read.firstBody)
            SeriesFacts(ListingReader.htmlField(doc, s.name), s.description?.let { ListingReader.htmlField(doc, it) },
                s.image?.let { ListingReader.htmlField(doc, it) }?.let { Urls.resolve(read.pageUrl, it) })
        }
    }

    internal fun seriesOf(recipe: Recipe, read: ListingRead.Read) = readSeries(recipe, read)
}

/** FR-4.1 — how an item becomes a manifest URL: a listing field, or a template with `{id}` and `{value}` (read from the item's own page). */
object ManifestResolver {
    sealed class Result
    data class Resolved(val url: String, val value: String? = null) : Result()
    data class Skipped(val reason: String) : Result()

    /**
     * [onValue] is told the value read from the item's page, so the caller stores it. The item's page is requested through [lane]
     * (a page request, paced), only when the template needs `{value}` and [knownValue] is not already stored.
     */
    suspend fun resolveWithPage(recipe: Recipe, item: ListedItem, lane: SiteLane, knownValue: String? = null, onValue: (String, ListedItem) -> Unit): Result {
        val itemId = item.id ?: item.link ?: ""
        val tpl = recipe.media.manifest
        var value = knownValue
        if (recipe.media.itemPage != null && value == null) {
            val link = item.link ?: return Skipped("the item has no link")
            val hosts = RecipeValidator.lanes(recipe)
            if (Urls.host(link) !in hosts) return Skipped("the item's page is on ${Urls.host(link)}, which the recipe does not record")
            val page = lane.page(link, Expect.HTML, hosts = hosts)
            if (page !is LaneOutcome.Ok) return Skipped(when (page) {
                is LaneOutcome.Stopped -> "the site is stopped: ${page.reason}"
                is LaneOutcome.BackOff -> "the site asked us to slow down"
                is LaneOutcome.Forbidden -> page.reason
                is LaneOutcome.Failed -> page.reason
                is LaneOutcome.Ok -> ""
            })
            val ip = recipe.media.itemPage
            val raw = if (ip.pattern.isNotBlank()) Numbering.itemPageValue(ip.pattern, page.reply.body)
            else Html.parse(page.reply.body).selectFirst(ip.selector)?.let { n -> if (ip.attr.isBlank() || ip.attr == "text") n.text() else n.attr(ip.attr) }?.let { Numbering.validMediaValue(it) }
            value = raw ?: return Skipped("the item's page gives no media id that passes the character rule")
            onValue(value, item)
        }
        if (tpl.isNotBlank()) {
            var url = tpl
            if (url.contains("{id}")) url = url.replace("{id}", Numbering.validMediaValue(itemId) ?: return Skipped("the item id fails the character rule"))
            if (url.contains("{value}")) url = url.replace("{value}", value ?: return Skipped("no media id"))
            return Resolved(url, value)
        }
        if (recipe.media.manifestField == null) return Skipped("the recipe says nothing about the manifest")
        val raw = item.manifest ?: return Skipped("the listing has no manifest for this item")
        val abs = Urls.resolve(recipe.source.url, raw) ?: return Skipped("the manifest is not an http address")
        if (Urls.host(abs) !in RecipeValidator.lanes(recipe)) return Skipped("the manifest is on ${Urls.host(abs)}, which the recipe does not record")
        return Resolved(abs, value)
    }
}
