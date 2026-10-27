package dev.jellystructure.autofetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-2476 (FR-4.1) — the html reader's tree and selector subset, on stand-in markup only. */
class HtmlDocTest {
    private val page = """
        <html><body>
        <ul class="eps">
          <li class="ep new" data-id="9001"><a href="/sendingar/dagbok/1">Dagbók &middot; 16. oktober</a><time datetime="2026-10-16">16.10.2026</time><p>Fyrsti&nbsp;partur &amp; svar</p>
          <li class="ep"><a href="/sendingar/dagbok/2">Dagbók · 17. oktober</a><time datetime="2026-10-17">17.10.2026</time>
        </ul>
        <script>var media = 'DOV7313';</script>
        </body></html>
    """.trimIndent()

    @Test
    fun `items are found by class and fields read by selector and attribute`() {
        val doc = Html.parse(page)
        val items = doc.select("ul.eps > li.ep")
        assertEquals(2, items.size)
        assertEquals("/sendingar/dagbok/1", items[0].selectFirst("a")?.attr("href"))
        assertEquals("2026-10-16", items[0].selectFirst("time")?.attr("datetime"))
        assertEquals("9001", items[0].attr("data-id"))
        assertEquals("Dagbók · 17. oktober", items[1].selectFirst("a")?.text())
    }

    @Test
    fun `entities are decoded and whitespace collapsed`() {
        val p = Html.parse(page).selectFirst("li p")
        assertEquals("Fyrsti partur & svar", p?.text())
    }

    @Test
    fun `an unclosed li is closed by the next li`() {
        val items = Html.parse(page).select("li")
        assertEquals(2, items.size)
        assertTrue(items.all { it.parent?.tag == "ul" })
    }

    @Test
    fun `script text is not visible text but is in the tree`() {
        val doc = Html.parse(page)
        assertEquals("", doc.selectFirst("script")?.text())
        assertTrue(doc.selectFirst("script")!!.content.any { it is String && it.contains("DOV7313") })
    }

    @Test
    fun `attribute selectors and structural pseudo classes`() {
        val doc = Html.parse(page)
        assertEquals(1, doc.select("li[data-id]").size)
        assertEquals(1, doc.select("li[data-id=9001]").size)
        assertEquals(1, doc.select("a[href\$='/2']").size)
        assertEquals(1, doc.select("a[href^='/sendingar/dagbok/1']").size)
        assertEquals(1, doc.select("li:first-child").size)
        assertEquals(1, doc.select("li:last-child").size)
        assertEquals(1, doc.select("li:nth-child(2)").size)
        assertEquals(3, doc.select("time, p").size)
    }

    @Test
    fun `a selector outside the subset matches nothing`() {
        val doc = Html.parse(page)
        assertNull(Selector.parse("li:has(a)"))
        assertEquals(0, doc.select("li:has(a)").size)
        assertNull(Selector.parse(""))
        assertNull(Selector.parse("a[href"))
    }

    @Test
    fun `a selector given relative to an item cannot see past it`() {
        val doc = Html.parse(page)
        val first = doc.select("li")[0]
        assertEquals(1, first.select("li a").size)
        assertEquals(0, first.select("ul a").size)
    }

    @Test
    fun `broken markup never throws`() {
        Html.parse("<div><p>one<div>two</span></p><a href=\"x")
        Html.parse("<<<>>>&&&;<!--")
        Html.parse("</b></i><li></ul>")
    }

    @Test
    fun `numeric character references decode`() {
        assertEquals("AáB", Html.decodeEntities("A&#225;B"))
        assertEquals("á", Html.decodeEntities("&#xE1;"))
        assertEquals("&bogus;", Html.decodeEntities("&bogus;"))
    }
}
