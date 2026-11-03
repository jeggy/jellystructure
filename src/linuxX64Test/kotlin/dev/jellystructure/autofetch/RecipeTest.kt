package dev.jellystructure.autofetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2476 (FR-4.1) — a recipe is data; these are the checks that keep it so. Stand-in corpus only. */
class RecipeTest {
    private val base = Recipe(
        source = RecipeSource("https://varpid.example.fo/sendingar/royndin", listOf("varpid.example.fo", "media.example.fo")),
        list = ListSpec(
            kind = "html", itemSelector = "li.ep",
            fields = mapOf("title" to Field("a"), "link" to Field("a", "href"), "published" to Field("time", "datetime"), "id" to Field("", "data-id")),
        ),
        media = MediaSpec(kind = "hls", manifest = "https://media.example.fo/{id}/master.m3u8"),
        pattern = listOf(PatternRule("title", """(?<season>\d{4}).*(?<episode>\d+)\. partur""")),
        language = LanguageSpec("fao", "fao"),
    )

    @Test
    fun `a sound recipe has no errors and round trips`() {
        assertEquals(emptyList(), RecipeValidator.validate(base, pageOnly = false))
        val back = RecipeCodec.decode(RecipeCodec.encode(base))
        assertEquals(base, back)
    }

    // ---- DEB-2480 (FR-4.1, FR-6.3) — the ytdlp kinds are accepted and checked, no longer refused ----

    private val ytdlp = base.copy(
        list = ListSpec(kind = "ytdlp", fields = mapOf("title" to Field(path = "title"))),
        media = MediaSpec(kind = "ytdlp"),
        numbering = NumberingSpec("date"),
    )

    @Test
    fun `a ytdlp list and a ytdlp media kind are valid and the old sentence is gone`() {
        val pageOnly = ytdlp.copy(series = SeriesSpec(name = Field("h1")))
        assertEquals(emptyList(), RecipeValidator.validate(pageOnly, pageOnly = true))
        assertEquals(emptyList(), RecipeValidator.validate(ytdlp.copy(numbering = NumberingSpec("source"), pattern = base.pattern), false))
    }

    @Test
    fun `a ytdlp list reads yt-dlp's own names for the fields the recipe leaves out`() {
        val f = YtDlpListing.fieldsOf(ytdlp.list)
        assertEquals("url", f["link"]?.path); assertEquals("upload_date", f["published"]?.path); assertEquals("id", f["id"]?.path)
        assertEquals("title", f["title"]?.path)
        val own = YtDlpListing.fieldsOf(ytdlp.list.copy(fields = mapOf("link" to Field(path = "webpage_url"))))
        assertEquals("webpage_url", own["link"]?.path, "a recipe's own path wins")
        val json = YtDlpListing.asJsonList(ytdlp.list)
        assertEquals("json", json.kind); assertEquals("entries", json.itemsPath)
    }

    @Test
    fun `a ytdlp list takes no url and a field with no path is refused`() {
        assertTrue(RecipeValidator.validate(ytdlp.copy(list = ytdlp.list.copy(url = "https://varpid.example.fo/x")), false).any { "takes no URL" in it })
        assertTrue(RecipeValidator.validate(ytdlp.copy(list = ytdlp.list.copy(fields = mapOf("title" to Field()))), false).any { "title field needs a path" in it })
    }

    @Test
    fun `a ytdlp media kind has no manifest and no item_page`() {
        assertTrue(RecipeValidator.validate(ytdlp.copy(media = MediaSpec(kind = "ytdlp", manifest = "https://media.example.fo/{id}/master.m3u8")), false).any { "takes the item's link" in it })
        assertTrue(RecipeValidator.validate(ytdlp.copy(media = MediaSpec(kind = "ytdlp", itemPage = ItemPageSpec(selector = "a"))), false).any { "takes the item's link" in it })
    }

    @Test
    fun `a ytdlp list may feed an hls manifest and an html list may feed ytdlp media`() {
        val listOnly = base.copy(list = ytdlp.list, numbering = NumberingSpec("source"))
        assertEquals(emptyList(), RecipeValidator.validate(listOnly, false))
        val mediaOnly = base.copy(media = MediaSpec(kind = "ytdlp"))
        assertEquals(emptyList(), RecipeValidator.validate(mediaOnly, false))
    }

    @Test
    fun `a template may only name a host the source records`() {
        val r = base.copy(media = base.media.copy(manifest = "https://evil.example.com/{id}/master.m3u8"))
        assertTrue(RecipeValidator.validate(r, false).any { "evil.example.com" in it })
        val ph = base.copy(media = base.media.copy(manifest = "https://{id}.example.fo/master.m3u8"))
        assertTrue(RecipeValidator.validate(ph, false).any { "host" in it })
        val user = base.copy(media = base.media.copy(manifest = "https://varpid.example.fo@evil.example.com/x.m3u8"))
        assertTrue(RecipeValidator.validate(user, false).isNotEmpty())
    }

    @Test
    fun `mapped numbering is a TMDB fetchers and date numbering is page only`() {
        val mapped = base.copy(numbering = NumberingSpec("mapped"), pattern = emptyList())
        assertEquals(emptyList(), RecipeValidator.validate(mapped, false), "a mapped recipe carries no pattern and no table")
        assertTrue(RecipeRefusal.MAPPED_PAGE_ONLY in RecipeValidator.validate(mapped.copy(series = SeriesSpec(name = Field("h1"))), true))
        val date = base.copy(numbering = NumberingSpec("date"))
        assertTrue(RecipeValidator.validate(date, pageOnly = false).any { "page-only" in it })
        val pageOnly = date.copy(series = SeriesSpec(name = Field("h1")))
        assertEquals(emptyList(), RecipeValidator.validate(pageOnly, pageOnly = true))
    }

    @Test
    fun `a page only recipe needs a series block`() {
        val r = base.copy(numbering = NumberingSpec("date"))
        assertTrue(RecipeValidator.validate(r, pageOnly = true).any { "series block" in it })
    }

    @Test
    fun `unsafe patterns are refused`() {
        for (bad in listOf("(a+)+(?<season>1)", "(.*)*(?<season>1)", "(a|aa)+(?<season>1)", """(?<season>\d+)\1""", "(?=x)(?<season>1)", "(?<season>1", "x".repeat(250))) {
            assertNotNull(PatternSafety.check(bad, emptySet(), setOf("season", "episode")), bad)
        }
    }

    @Test
    fun `ordinary patterns are accepted`() {
        for (ok in listOf("""(?<season>\d{4})""", """(\d+)\. partur""".replace("""(\d+)""", """(?<episode>\d+)"""), """[Ss](?<season>\d{1,4})[Ee](?<episode>\d{1,3})""", """\((?<episode>\d+):\d+\)""", """(?:Partur )?(?<episode>\d+)""")) {
            assertNull(PatternSafety.check(ok, emptySet(), setOf("season", "episode")), ok)
        }
    }

    @Test
    fun `a pattern that does not compile is refused with the engines own message - DEB-3459`() {
        for (bad in listOf("""var media = '(?P=value)'""", "(?<value>[z-a])")) {
            val why = assertNotNull(PatternSafety.check(bad, setOf("value")), bad)
            assertTrue(why.startsWith("the pattern is not a valid regular expression: ") && why.length > "the pattern is not a valid regular expression: ".length, why)
            assertTrue(why.length <= "the pattern is not a valid regular expression: ".length + 160, why)
        }
    }

    @Test
    fun `a pattern may name only the groups its use allows`() {
        assertNotNull(PatternSafety.check("(?<other>x)", emptySet(), setOf("season", "episode")))
        assertNotNull(PatternSafety.check("(?<season>x)", setOf("value")))
        assertNull(PatternSafety.check("""media = '(?<value>[A-Z0-9]+)'""", setOf("value")))
    }

    @Test
    fun `item page needs exactly one of selector and pattern`() {
        val both = base.copy(media = base.media.copy(manifest = "https://media.example.fo/{value}/master.m3u8", itemPage = ItemPageSpec("script", "text", "x(?<value>1)")))
        assertTrue(RecipeValidator.validate(both, false).any { "item_page" in it })
        val ok = base.copy(media = base.media.copy(manifest = "https://media.example.fo/{value}/master.m3u8", itemPage = ItemPageSpec(pattern = """var media = '(?<value>[^']+)'""")))
        assertEquals(emptyList(), RecipeValidator.validate(ok, false))
        val noPage = base.copy(media = base.media.copy(manifest = "https://media.example.fo/{value}/master.m3u8"))
        assertTrue(RecipeValidator.validate(noPage, false).any { "item_page" in it })
    }

    @Test
    fun `retention bounds`() {
        assertTrue(RecipeValidator.validate(base.copy(retention = RetentionSpec("newest", 0)), false).isNotEmpty())
        assertTrue(RecipeValidator.validate(base.copy(retention = RetentionSpec("newest", 1000)), false).isNotEmpty())
        assertEquals(emptyList(), RecipeValidator.validate(base.copy(retention = RetentionSpec("newest", 999)), false))
    }

    @Test
    fun `urls resolve and hosts parse`() {
        assertEquals("https://varpid.example.fo/sendingar/dagbok/1", Urls.resolve("https://varpid.example.fo/sendingar/dagbok", "/sendingar/dagbok/1"))
        assertEquals("https://varpid.example.fo/sendingar/x", Urls.resolve("https://varpid.example.fo/sendingar/dagbok", "x"))
        assertNull(Urls.resolve("https://varpid.example.fo/", "javascript:void(0)"))
        assertEquals("varpid.example.fo", Urls.siteOf("https://www.varpid.example.fo/x"))
        assertNull(Urls.host("ftp://varpid.example.fo"))
        assertEquals("/sendingar/royndin", Urls.path("https://varpid.example.fo/sendingar/royndin?x=1"))
    }
}
