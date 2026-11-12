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
    suspend fun read(recipe: Recipe, lane: SiteLane, tools: MediaTools? = null): ListingRead {
        if (recipe.list.kind == "ytdlp") return readYtDlp(recipe, lane, tools)
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

/**
 * DEB-2480 (FR-6.3, FR-4.1) — a `ytdlp` listing: yt-dlp's flat playlist of the source page, one paced request in the lane, read as JSON.
 * The source page is also read through the lane when the recipe has a `series` block, because that block's selectors read the page itself.
 */
private suspend fun readYtDlp(recipe: Recipe, lane: SiteLane, tools: MediaTools?): ListingRead {
    if (tools == null) return ListingRead.Blocked(LaneOutcome.Failed(YtDlpTexts.NOT_INSTALLED))
    val url = recipe.source.url
    val cookies = lane.cookiesFile(RecipeValidator.lanes(recipe))
    val out = when (val g = lane.pacedProgram(url, listing = true) { tools.ytdlpListing(url, cookies) }) {
        is Gated.Refused -> return ListingRead.Blocked(g.outcome)
        is Gated.Through -> g.value
    }
    if (!out.ok) return ListingRead.Blocked(lane.programFailure(out.message, out.status, out.needsLogin, cookies != null))
    lane.programSucceeded()
    val json = out.body.substring(out.body.indexOf('{').coerceAtLeast(0))
    val page0 = ListingReader.readJson(YtDlpListing.asJsonList(recipe.list), json, url, recipe.media.manifestField)
        ?: return ListingRead.Blocked(LaneOutcome.Failed("yt-dlp's listing is not JSON"))
    val page = page0.copy(items = YtDlpListing.fillDates(recipe.list, json, page0.items))
    val body = if (recipe.series == null) "" else (lane.page(url, Expect.HTML, hosts = RecipeValidator.lanes(recipe)) as? LaneOutcome.Ok)?.reply?.body.orEmpty()
    return ListingRead.Read(page.items.mapIndexed { i, it -> it.copy(order = i) }, page.items.size < YtDlpCommands.PLAYLIST_END, body, url)
}

/**
 * DEB-3407 (DEB-2474, FR-4.1) — an item a yt-dlp flat entry gives no date for is dated from its own answer: one paced lane request, the link
 * checked against the recorded hosts and `robots.txt` first, as for a download, asking for the date fields only.
 */
sealed class DateRead {
    /** yt-dlp answered; [date] is `yyyy-MM-dd`, or null for *no date* (which is an answer too). */
    data class Answered(val date: String?) : DateRead()
    /** The lane refused (stopped, backed off, robots.txt) or yt-dlp failed: nothing was learned, and nothing is stored. */
    data class Unread(val outcome: LaneOutcome) : DateRead()
    /** The item cannot be read at all: no link, or a host the recipe does not record. Not asked for. */
    object NotAsked : DateRead()
}

object ItemDates {
    /** At most this many new undated items are dated in one run (FR-4.1). */
    const val PER_RUN = 20
    /** …and this many in the proof. */
    const val IN_PROOF = 3

    fun undated(item: ListedItem): Boolean = Numbering.isoDate(item.published) == null

    suspend fun read(recipe: Recipe, item: ListedItem, lane: SiteLane, tools: MediaTools): DateRead {
        val link = item.link?.takeIf { it.isNotBlank() } ?: return DateRead.NotAsked
        if (Urls.host(link) !in RecipeValidator.lanes(recipe)) return DateRead.NotAsked
        val cookies = lane.cookiesFile(RecipeValidator.lanes(recipe))
        val out = when (val g = lane.pacedProgram(link, listing = false) { tools.ytdlpDate(link, cookies) }) {
            is Gated.Refused -> return DateRead.Unread(g.outcome)
            is Gated.Through -> g.value
        }
        if (!out.ok) return DateRead.Unread(lane.programFailure(out.message, out.status, out.needsLogin, cookies != null))
        lane.programSucceeded()
        return DateRead.Answered(YtDlpDate.parse(out.body))
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
    /** DEB-3592 (FR-10.7) — the lane refused a request of the proof for `robots.txt` (FR-2.2): what the failed state says the tick is for. */
    val robotsRefused: Boolean = false,
    /** DEB-3613 (FR-3.6) — the proof's own media read was refused for want of a sign-in (`401`/`403`, or yt-dlp asking for a login) and no session was sent. */
    val needsSignIn: Boolean = false,
    /** FR-4.3 — a `mapped` proposal: [numbered] holds only the rows that resolved to an item, and [whys] the model's reason for each, by item id. */
    val mapped: Boolean = false,
    val whys: Map<String, String> = emptyMap(),
    /** DEB-3407 (FR-4.1) — the items the proof dated from their own yt-dlp answer, by item id: the date, or null for *no date*. A save stores these. */
    val dated: Map<String, String?> = emptyMap(),
    /** DEB-4341 (FR-5.12) — the proof failed on the media: the manifest could not be read or probed. The listing and the numbering had held. */
    val onMedia: Boolean = false,
)

/** The id an item is kept under: its `id` field, else its link (the same key the store and a run use). */
fun itemKeyOf(it: ListedItem): String = it.id?.takeIf { v -> v.isNotBlank() } ?: it.link.orEmpty()

class RecipeProof(private val tools: MediaTools, private val world: AutofetchWorld) {

    /** [mapping] is the model's proposed table when the recipe's numbering is `mapped` (FR-4.3); nothing else reads it. */
    suspend fun prove(recipe: Recipe, pageOnly: Boolean, tmdbId: Int?, lane: SiteLane, mapping: List<MapRow> = emptyList(), onSignInRefused: () -> Unit = {}): ProofResult {
        val read = ListingFetcher.read(recipe, lane, tools)
        if (read is ListingRead.Blocked && read.outcome.refusedForSignIn) onSignInRefused()
        if (read is ListingRead.Blocked) return fail(describe(read.outcome)).copy(blocked = read.outcome, robotsRefused = read.outcome is LaneOutcome.Forbidden)
        read as ListingRead.Read
        val listed = read.items
        if (listed.size < 2) return fail("the listing yields ${listed.size} item${if (listed.size == 1) "" else "s"}; a series needs at least two", listed)
        for (it in listed) {
            if (it.link.isNullOrBlank()) return fail("an item has no link", listed)
            if (pageOnly) { if (it.title.isNullOrBlank() && Numbering.isoDate(it.published) == null) return fail("an item has neither a title nor a date", listed) }
            else if (it.title.isNullOrBlank()) return fail("an item has no title", listed)
        }
        // FR-4.5 (DEB-3567) — a yt-dlp listing's published value is a date with no time, so it cannot be numbered by date and time.
        if (recipe.list.kind == "ytdlp" && recipe.numbering.kind == "datetime" && recipe.numbering.whenPattern == null) return fail("yt-dlp gives these items no time, so they cannot be numbered by date and time", listed)
        // FR-4.1 — the first three items a yt-dlp flat entry gives no date are dated from their own answer, and no others. When the media is
        // yt-dlp's too, the probe's read of the first of them dates it (the probe prints the same three fields), so the proof makes four requests.
        val dated = LinkedHashMap<String, String?>()
        var items = listed
        var early: Probed? = null
        if (recipe.list.kind == "ytdlp") {
            val toDate = listed.filter { ItemDates.undated(it) }.take(ItemDates.IN_PROOF).toMutableList()
            val probeFirst = if (recipe.media.kind == "ytdlp" && recipe.numbering.kind != "mapped") toDate.firstOrNull() else null
            if (probeFirst != null) {
                val p = probeYtDlp(recipe, probeFirst, lane)
                if (p.signIn) onSignInRefused()
                p.failure?.let { return fail(it, listed).copy(blocked = p.blocked, robotsRefused = p.robotsRefused, needsSignIn = p.signIn, onMedia = true) }
                early = p
                if (p.dateRead) dated[itemKeyOf(probeFirst)] = p.date
                toDate.remove(probeFirst)
            }
            val readNow = HashMap<String, String?>(dated)
            for (it in toDate) {
                when (val r = ItemDates.read(recipe, it, lane, tools)) {
                    is DateRead.NotAsked -> {}
                    is DateRead.Unread -> {
                        if (r.outcome.refusedForSignIn) onSignInRefused()
                        if (r.outcome !is LaneOutcome.Failed)
                            return fail(describe(r.outcome), listed).copy(blocked = r.outcome.takeIf { o -> o is LaneOutcome.Stopped || o is LaneOutcome.BackOff }, robotsRefused = r.outcome is LaneOutcome.Forbidden)
                    }
                    is DateRead.Answered -> { dated[itemKeyOf(it)] = r.date; readNow[itemKeyOf(it)] = r.date }
                }
            }
            items = listed.map { it -> if (ItemDates.undated(it)) readNow[itemKeyOf(it)]?.let { d -> it.copy(published = d) } ?: it else it }
        }
        if (recipe.numbering.kind == "mapped") return proveMapped(recipe, pageOnly, tmdbId, lane, items, mapping, onSignInRefused).let { if (it.ok) it.copy(dated = dated) else it }
        val numbered = ArrayList<Pair<ListedItem, SeasonEpisode>>()
        // An item with no date is not held against *every item maps to exactly one identity*: it gets its number on the run that dates it.
        val byDate = recipe.list.kind == "ytdlp" && recipe.numbering.kind == "date"
        for (it in items) {
            if (byDate && ItemDates.undated(it)) continue
            val se = numberOf(recipe, it)
                ?: return fail(if (recipe.numbering.whenPattern != null) "the date pattern finds no date in '${PageDates.quote(recipe.numbering, it)}'"
                else "'${it.title ?: it.link}' maps to no season and episode", items)
            numbered += it to se
        }
        if (byDate && numbered.map { Numbering.isoDate(it.first.published) }.distinct().size < 2)
            return fail("yt-dlp gives these items no date, so they cannot be numbered by date", items, numbered)
        val shared = numbered.groupBy { it.second }.entries.filter { it.value.size > 1 }
        // DEB-3695 (FR-4.2) — under `date` and `datetime` a shared number is a re-upload or a double, not the numbering's fault: it fails the proof
        // only when three items share one number, or when more than a quarter of the numbered items share theirs. `source` and `mapped` keep any two.
        val tolerated = recipe.numbering.kind == "date" || recipe.numbering.kind == "datetime"
        val refusing = if (!tolerated) shared.firstOrNull()
            else shared.firstOrNull { it.value.size >= 3 } ?: shared.firstOrNull().takeIf { shared.sumOf { g -> g.value.size } * 4 > numbered.size }
        refusing?.let { (se, group) ->
            if (recipe.numbering.kind == "datetime") {
                val times = group.mapNotNull { PageDates.read(recipe.numbering, it.first)?.minute }.sorted().joinToString(", ") { Numbering.clock(it) }
                return fail("${group.size} items on ${PageDates.read(recipe.numbering, group[0].first)?.date} ($times) air too close together to get numbers of their own", items, numbered)
            }
            val what = if (recipe.numbering.kind == "date") "share the date ${PageDates.read(recipe.numbering, group[0].first)?.date}" else "both map to ${Numbering.code(se)}"
            return fail("${group.size} items $what; every item needs a number of its own", items, numbered)
        }
        val series = if (pageOnly) readSeries(recipe, read) else null
        if (pageOnly && series?.name.isNullOrBlank()) return fail("the page gives the programme no name", items, numbered)

        val probe = early ?: probeOne(recipe, numbered.first().first, lane) ?: return fail("no manifest could be probed", items, numbered).copy(onMedia = true)
        if (probe.signIn) onSignInRefused()
        probe.failure?.let { return fail(it, items, numbered).copy(blocked = probe.blocked, needsSignIn = probe.signIn, onMedia = true) }
        val facts = probe.facts!!
        if (!facts.hasVideo || facts.durationSec == null || facts.durationSec <= 0.0) return fail("the manifest has no video variant ffprobe can read, with a duration", items, numbered).copy(onMedia = true)

        if (pageOnly || tmdbId == null) {
            return ProofResult(true, items = items, numbered = numbered, series = series, probe = facts, dated = dated)
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
        return ProofResult(true, items = items, numbered = numbered, agreements = agree, tmdbChecked = checked, proven = agree >= 2, probe = facts, dated = dated)
    }

    /**
     * FR-4.3 — the model's proposed table, held against the listing and TMDB. Every row it names must resolve to an item and to an episode
     * TMDB has, and no two items may share one. It is never *proven*: the admin's tick on each row is what approves it, so nothing here
     * decides a number. A listing item the proposal leaves out is simply an item waiting for a number.
     */
    private suspend fun proveMapped(recipe: Recipe, pageOnly: Boolean, tmdbId: Int?, lane: SiteLane, items: List<ListedItem>, mapping: List<MapRow>, onSignInRefused: () -> Unit): ProofResult {
        if (pageOnly || tmdbId == null) return fail(RecipeRefusal.MAPPED_PAGE_ONLY, items)
        val tmdb = world.tmdbEpisodes(tmdbId) ?: return fail("TMDB could not be reached to check the numbering", items)
        val has = tmdb.map { it.season to it.episode }.toSet()
        val resolved = resolveMapping(recipe.source.url, items, mapping)
        for ((item, row) in resolved) {
            if ((row.season to row.episode) !in has) return fail("'${item.title ?: item.link}' is mapped to ${Numbering.code(SeasonEpisode(row.season, row.episode))}, which TMDB does not have", items)
        }
        val numbered = resolved.map { (item, row) -> item to SeasonEpisode(row.season, row.episode) }
        numbered.groupBy { it.second }.entries.firstOrNull { it.value.size > 1 }?.let { (se, group) ->
            return fail("${group.size} items both map to ${Numbering.code(se)}; every item needs a number of its own", items, numbered)
        }
        if (numbered.isEmpty()) return fail("the proposal maps no item of the listing to an episode TMDB has", items)
        val probe = probeOne(recipe, numbered.first().first, lane) ?: return fail("no manifest could be probed", items, numbered).copy(onMedia = true)
        if (probe.signIn) onSignInRefused()
        probe.failure?.let { return fail(it, items, numbered).copy(blocked = probe.blocked, needsSignIn = probe.signIn, onMedia = true) }
        val facts = probe.facts!!
        if (!facts.hasVideo || facts.durationSec == null || facts.durationSec <= 0.0) return fail("the manifest has no video variant ffprobe can read, with a duration", items, numbered).copy(onMedia = true)
        return ProofResult(true, items = items, numbered = numbered, proven = false, probe = facts, mapped = true, whys = resolved.associate { (item, row) -> itemKeyOf(item) to row.why })
    }

    /**
     * Each proposed row to the listing item it names: by link (read against the page, as the engine reads links), then by the item's `id`,
     * then by an exact title that only one item has. An item takes the first row that names it; a row that names no item is dropped.
     */
    internal fun resolveMapping(pageUrl: String, items: List<ListedItem>, mapping: List<MapRow>): List<Pair<ListedItem, MapRow>> {
        val out = ArrayList<Pair<ListedItem, MapRow>>()
        val used = HashSet<String>()
        for (row in mapping) {
            if (row.season < 0 || row.episode < 0) continue
            val link = row.link.trim()
            var hit: ListedItem? = null
            if (link.isNotEmpty()) {
                val abs = Urls.resolve(pageUrl, link) ?: link
                hit = items.firstOrNull { it.link == abs } ?: items.firstOrNull { it.id == link }
            }
            if (hit == null) {
                val t = row.title.trim().lowercase()
                if (t.isNotEmpty()) items.filter { it.title?.trim()?.lowercase() == t }.singleOrNull()?.let { hit = it }
            }
            val item = hit ?: continue
            if (used.add(itemKeyOf(item))) out += item to row
        }
        return out
    }

    private fun fail(why: String, items: List<ListedItem> = emptyList(), numbered: List<Pair<ListedItem, SeasonEpisode>> = emptyList()) =
        ProofResult(false, why, items, numbered)

    private fun describe(o: LaneOutcome): String = describeOutcome(o)

    internal fun numberOf(recipe: Recipe, item: ListedItem): SeasonEpisode? = when (recipe.numbering.kind) {
        "date" -> PageDates.read(recipe.numbering, item)?.let { Numbering.fromDate(it.date) }
        "datetime" -> PageDates.read(recipe.numbering, item)?.let { p -> p.minute?.let { m -> Numbering.fromDateTime(p.date, m) } }
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

    class Probed(
        val facts: ManifestFacts?, val failure: String?,
        /** DEB-3407 — the item's own date as the probe's yt-dlp answer gave it, when that answer was read ([dateRead]); null is *no date*. */
        val date: String? = null, val dateRead: Boolean = false,
        /** The lane's refusal that failed this probe, when it was one that stops the run. */
        val blocked: LaneOutcome? = null,
        val robotsRefused: Boolean = false,
        /** DEB-3613 (FR-3.6) — this probe's read was refused for want of a sign-in, with no session sent. */
        val signIn: Boolean = false,
    )

    /** FR-6.3 — an item yt-dlp is to download: its link is on a recorded host and robots.txt allows it, and yt-dlp's own metadata shows a video format, not all DRM. */
    private suspend fun probeYtDlp(recipe: Recipe, item: ListedItem, lane: SiteLane): Probed {
        val link = item.link ?: return Probed(null, "the item has no link")
        if (Urls.host(link) !in RecipeValidator.lanes(recipe)) return Probed(null, "the item's link is on ${Urls.host(link)}, which the recipe does not record")
        val cookies = lane.cookiesFile(RecipeValidator.lanes(recipe))
        val out = when (val g = lane.pacedProgram(link, listing = false) { tools.ytdlpInfo(link, cookies) }) {
            is Gated.Refused -> return Probed(null, describe(g.outcome), blocked = g.outcome.takeIf { it is LaneOutcome.Stopped || it is LaneOutcome.BackOff }, robotsRefused = g.outcome is LaneOutcome.Forbidden)
            is Gated.Through -> g.value
        }
        if (!out.ok) {
            if (out.drm) return Probed(null, RecipeRefusal.DRM)
            val o = lane.programFailure(out.message, out.status, out.needsLogin, cookies != null)
            val signIn = cookies == null && (out.needsLogin || out.status == 401 || out.status == 403)
            return Probed(null, describe(o), blocked = o.takeIf { it is LaneOutcome.Stopped || it is LaneOutcome.BackOff }, signIn = signIn)
        }
        lane.programSucceeded()
        if (YtDlpInfo.allDrm(out.body)) return Probed(null, RecipeRefusal.DRM)
        return YtDlpInfo.facts(out.body)?.let { Probed(it, null, YtDlpDate.parse(out.body), dateRead = true) } ?: Probed(null, "yt-dlp's answer for the item could not be read")
    }

    private suspend fun probeOne(recipe: Recipe, item: ListedItem, lane: SiteLane): Probed? {
        if (recipe.media.kind == "ytdlp") return probeYtDlp(recipe, item, lane)
        val manifestUrl = ManifestResolver.resolveWithPage(recipe, item, lane) { _, _ -> }
        return when (manifestUrl) {
            is ManifestResolver.Resolved -> {
                val m = lane.page(manifestUrl.url, Expect.MANIFEST, player = true, hosts = RecipeValidator.lanes(recipe))
                if (m !is LaneOutcome.Ok) return Probed(null, describe(m), blocked = m.takeIf { it is LaneOutcome.Stopped || it is LaneOutcome.BackOff },
                    signIn = m is LaneOutcome.Failed && (m.status == 401 || m.status == 403))
                if (Manifests.hasDrm(m.reply.body)) return Probed(null, RecipeRefusal.DRM)
                Probed(tools.probeManifest(m.reply.finalUrl ?: manifestUrl.url, m.reply.body, recipe.media.kind, lane.ffmpegCookies(RecipeValidator.lanes(recipe))), null)
            }
            is ManifestResolver.Skipped -> Probed(null, manifestUrl.reason, blocked = manifestUrl.blocked, signIn = manifestUrl.signIn)
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
    /** [signIn]: the item's page answered `401`/`403` with no session sent (DEB-3613, FR-3.6). [blocked]: the lane's stop or back-off that ended it. */
    data class Skipped(val reason: String, val signIn: Boolean = false, val blocked: LaneOutcome? = null) : Result()

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
            }, signIn = page is LaneOutcome.Failed && (page.status == 401 || page.status == 403), blocked = page.takeIf { it is LaneOutcome.Stopped || it is LaneOutcome.BackOff })
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

/** The words for a lane outcome that ended a read; the proof's failure and a `needs_sign_in` refusal both use them. */
internal fun describeOutcome(o: LaneOutcome): String = when (o) {
    is LaneOutcome.Stopped -> "the site is stopped: ${o.reason}"
    is LaneOutcome.BackOff -> "the site asked us to slow down: ${o.reason}"
    is LaneOutcome.Forbidden -> o.reason
    is LaneOutcome.Failed -> o.reason
    is LaneOutcome.Ok -> "ok"
}
