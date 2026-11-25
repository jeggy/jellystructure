package dev.jellystructure.ui

import dev.jellystructure.model.JellyfinCollectionDto
import dev.jellystructure.model.JellyfinCollectionMemberDto
import dev.jellystructure.model.JellyfinCollectionSuggestionDto
import dev.jellystructure.model.JellyfinCollectionsPageDto
import dev.jellystructure.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-4075 (DEB-4032, FR-6, FR-7) — what the *Jellyfin collections* page and the title page's *Collections* card draw from what the routes
 * answered. The admin frontend has no test target, so the markup is built in `commonMain` and checked here. Stand-in names only.
 */
class JellyfinCollectionsHtmlTest {
    private fun film(id: String, year: Int? = 2001) = JellyfinCollectionMemberDto(id, "Film $id", year, MediaKind.MOVIE)
    private fun series(id: String) = JellyfinCollectionMemberDto(id, "Series $id", 2010, MediaKind.TV_SHOW)
    private fun collection(id: String, name: String, vararg members: JellyfinCollectionMemberDto, unmapped: Int = 0) =
        JellyfinCollectionDto(id, name, members.toList(), unmapped)

    @Test fun `a collection's line counts films and series and says what it holds that jellystructure does not`() {
        assertEquals("2 films · 1 series", jcCountsText(listOf(film("a"), film("b"), series("s"))))
        assertEquals("1 film · 0 series", jcCountsText(listOf(film("a"))))
        assertEquals("0 films · 0 series", jcCountsText(emptyList()))
        assertEquals("", jcUnmappedText(0))
        assertEquals("Also holds 1 item jellystructure doesn't manage.", jcUnmappedText(1))
        assertEquals("Also holds 3 items jellystructure doesn't manage.", jcUnmappedText(3))
    }

    @Test fun `delete asks the sentence the spec gives and says the titles stay`() {
        assertEquals("Delete \"Collection A\"? The collection goes; its films and series stay.", jcDeleteConfirm("Collection A"))
    }

    @Test fun `the list has a row per collection with Edit and Delete and escapes a name`() {
        val html = jcListHtml(JellyfinCollectionsPageDto(true, listOf(
            collection("box-1", "Collection <b>A</b>", film("a"), series("s"), unmapped = 2),
            collection("box-2", "Collection B"),
        )), openId = null)
        assertEquals(2, Regex("""data-jc-row="""").findAll(html).count())
        assertTrue("Collection &lt;b&gt;A&lt;/b&gt;" in html && "<b>A</b>" !in html, "a name is text")
        assertTrue("1 film · 1 series" in html)
        assertTrue("Also holds 2 items jellystructure doesn&#39;t manage." in html || "Also holds 2 items jellystructure doesn't manage." in html)
        assertTrue("""data-jc-act="edit" data-jc-id="box-1"""" in html && """data-jc-act="delete" data-jc-id="box-2"""" in html)
        assertEquals(1, Regex("""cfg-row on""").findAll(jcListHtml(JellyfinCollectionsPageDto(true, listOf(collection("box-1", "A"))), openId = "box-1")).count())
    }

    @Test fun `the list says so when Jellyfin is not set up and when there is nothing yet`() {
        assertTrue("isn't set up" in jcListHtml(JellyfinCollectionsPageDto(false), null))
        val none = jcListHtml(JellyfinCollectionsPageDto(true), null)
        assertTrue("No collections yet" in none)
        assertFalse("data-jc-row" in none)
    }

    @Test fun `the editor of a saved collection has a remove cross on each member and no Create`() {
        val html = jcEditorHtml("box-1", "Collection A", listOf(film("a"), series("s")), unmapped = 1)
        assertTrue("Edit collection" in html && """value="Collection A"""" in html && """data-jc-id="box-1"""" in html)
        assertEquals(2, Regex("""data-jc-act="remove" data-jc-id="box-1" data-jc-media="""").findAll(html).count())
        assertTrue("""data-jc-media="a"""" in html && """data-jc-media="s"""" in html, "named by media id")
        assertFalse("""data-jc-act="create"""" in html)
        assertTrue("id=\"jc-unmapped\"" in html)
        assertFalse("jc-unmapped" in jcEditorHtml("box-1", "A", emptyList(), 0), "no line when none")
    }

    @Test fun `the editor of a new collection is a draft with every title ticked and a Create button`() {
        val html = jcEditorHtml(null, "Franchise B", listOf(film("a"), film("b"), film("c")), 0)
        assertTrue("New collection" in html && """value="Franchise B"""" in html)
        assertEquals(3, Regex("""<input type="checkbox" checked data-jc-draft="""").findAll(html).count())
        assertTrue("""data-jc-act="create"""" in html)
        assertFalse("""data-jc-act="remove"""" in html)
        assertFalse("data-jc-id=" in html, "a draft names no collection, so a rename cannot fire")
    }

    @Test fun `a name in the editor cannot break out of its attribute`() {
        val html = jcEditorHtml("box-1", "\"><script>x</script>", emptyList(), 0)
        assertFalse("<script>" in html)
        assertTrue("""value="&quot;&gt;&lt;script&gt;""" in html)
    }

    @Test fun `search results offer a title once and show one already in as added`() {
        val html = jcResultsHtml(listOf(film("a"), series("s"), film("b")), already = setOf("a"))
        assertEquals(2, Regex("""data-jc-act="add" data-jc-media="""").findAll(html).count())
        assertTrue("added" in html)
        assertTrue("""data-jc-media="s"""" in html && "Series" in html)
        assertTrue("No results found." in jcResultsHtml(emptyList(), emptySet()))
    }

    @Test fun `a suggestion reads Name - N films and its button opens the editor rather than making anything`() {
        val s = listOf(
            JellyfinCollectionSuggestionDto("1", "Franchise B", listOf(film("a"), film("b"), film("c"))),
            JellyfinCollectionSuggestionDto("2", "Franchise D", listOf(film("d"), film("e"))),
        )
        assertEquals("Franchise B — 3 films", jcSuggestionLine(s[0]))
        val html = jcSuggestionsHtml(s)
        assertTrue("Franchise B — 3 films" in html && "Franchise D — 2 films" in html)
        assertTrue("""data-jc-act="suggest" data-jc-i="0"""" in html && """data-jc-i="1"""" in html)
        assertTrue("Create collection" in html)
        assertFalse("""data-jc-act="create"""" in html, "the press that makes it is the editor's own")
        assertTrue("Nothing to suggest." in jcSuggestionsHtml(emptyList()))
    }

    @Test fun `the title page card chips the collections the title is in and lists the rest to add to`() {
        val all = listOf(
            collection("box-1", "Collection A", film("t")),
            collection("box-2", "Collection B", film("other")),
            collection("box-3", "Collection <C>", film("t"), series("s")),
        )
        val html = jcTitleLineHtml("t", all)
        assertEquals(2, Regex("""data-jc-act="title-remove" data-jc-id="""").findAll(html).count(), "A and C")
        assertTrue("""data-jc-id="box-1"""" in html && """data-jc-id="box-3"""" in html && """data-jc-id="box-2"""" !in html)
        val options = Regex("""<option value="([^"]*)">""").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(listOf("", "box-2", "__new"), options, "the placeholder, the one it is not in, and New collection")
        assertTrue("Collection &lt;C&gt;" in html && "<C>" !in html)
        assertTrue("Add to collection…" in html && "New collection" in html)
    }

    @Test fun `a title in no collection says so and still offers every collection`() {
        val html = jcTitleLineHtml("t", listOf(collection("box-1", "Collection A", film("x"))))
        assertTrue("In no collection." in html)
        assertFalse("title-remove" in html)
        assertEquals(listOf("", "box-1", "__new"), Regex("""<option value="([^"]*)">""").findAll(html).map { it.groupValues[1] }.toList())
    }
}
