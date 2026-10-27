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

    @Test
    fun `a ytdlp recipe is refused with the FR-6-3 sentence`() {
        val r = base.copy(list = base.list.copy(kind = "ytdlp"))
        assertTrue(RecipeRefusal.YTDLP in RecipeValidator.validate(r, false))
        val m = base.copy(media = base.media.copy(kind = "ytdlp"))
        assertTrue(RecipeRefusal.YTDLP in RecipeValidator.validate(m, false))
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
    fun `mapped numbering is refused until its slice and date numbering is page only`() {
        assertTrue(RecipeRefusal.MAPPED in RecipeValidator.validate(base.copy(numbering = NumberingSpec("mapped")), false))
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
