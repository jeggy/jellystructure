package dev.jellystructure.ui

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-5321 (FR-6) — the admin's season heading and its Rename control, asserted on their markup. */
class SeasonNameHtmlTest {

    private val admin = SeasonNameState(7, "The Long Winter", "admin")
    private val scanned = SeasonNameState(1, "Season 1 — Origins", "jellyfin")

    @Test
    fun `the heading shows the server's name and a ghost Rename button`() {
        val h = seasonHeadHtml(scanned, editing = false)
        assertTrue(">Season 1 — Origins</h4>" in h, h)
        assertTrue("""class="btn sm ghost sn-rename"""" in h && ">Rename<" in h, h)
        assertFalse("sn-input" in h, h)
    }

    @Test
    fun `the custom tag shows only for an admin's own name`() {
        assertTrue(">custom<" in seasonHeadHtml(admin, editing = false))
        assertFalse("custom" in seasonHeadHtml(scanned, editing = false))
        assertFalse("custom" in seasonHeadHtml(SeasonNameState(2, "Season 2", "default"), editing = false))
    }

    @Test
    fun `the field opens with the admin name or empty with the name as placeholder`() {
        assertEquals("The Long Winter", seasonRenameStart(admin))
        assertEquals("", seasonRenameStart(scanned))
        val h = seasonHeadHtml(scanned, editing = true, typed = seasonRenameStart(scanned))
        assertTrue("""value="" placeholder="Season 1 — Origins"""" in h, h)
        assertTrue("sn-save" in h && ">Save<" in h && "sn-cancel" in h && ">Cancel<" in h, h)
    }

    @Test
    fun `a refusal is shown under the field and the typed text is kept`() {
        val h = seasonHeadHtml(admin, editing = true, typed = "x\ny", error = "a season name has no control character or line break")
        assertTrue("""value="x
y"""" in h, h)
        assertTrue("a season name has no control character or line break</div>" in h, h)
        assertTrue(h.indexOf("sn-input") < h.indexOf("sn-error"), "the error sits under the field: $h")
    }

    @Test
    fun `names are escaped`() {
        val h = seasonHeadHtml(SeasonNameState(3, "<b>\"x\"</b>", "admin"), editing = true, typed = "<i>")
        assertFalse("<b>" in h || "<i>" in h, h)
    }

    @Test
    fun `before the server answers a block reads Season n`() {
        val h = seasonHeadMountHtml(4)
        assertTrue("""data-season-name="4"""" in h && ">Season 4</h4>" in h, h)
    }

    @Test
    fun `the answer of the route decodes`() {
        val json = """[{"season":0,"name":"Specials","source":"jellyfin"},{"season":7,"name":"The Long Winter","source":"admin","extra":1}]"""
        val list = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(SeasonNameState.serializer()), json)
        assertEquals(listOf(0, 7), list.map { it.season })
        assertEquals("admin", list[1].source)
    }
}
