package dev.jellystructure.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 278 FR-278-16 (correction 2026-10-14, DEB-1113) — a cover cell's picture is a lazy `<img>`, not a CSS
 * background, because a background image is fetched the moment its element is laid out and that is what made
 * arriving on Albums fire 809 requests at the server that is also serving playback.
 *
 * These assertions are on the markup, which is as far as a test can reach: whether the browser then defers the
 * off-screen ones is acceptance 8 and it is QA's, in a browser, on the real library. What is covered here is
 * the part that was got wrong once already — the attribute has to be on a real `<img>` to mean anything.
 */
class MusicCoverHtmlTest {

    @Test
    fun `a cover is a lazy img and not a background image`() {
        val h = muCoverHtml("Harbour Lights", "/api/music/image/album/a1?v=7")
        assertTrue("<img " in h, "the cover must be a replaced element: $h")
        assertTrue("""loading="lazy"""" in h, "loading=lazy is the requirement: $h")
        assertTrue("""src="/api/music/image/album/a1?v=7"""" in h, "the url belongs in src: $h")
        // The regression guard. `url(...)` anywhere in this string means a background image, and a background
        // image is fetched for every laid-out cell whether it is on screen or not.
        assertFalse("url(" in h, "a cover must not be painted as a background any more: $h")
    }

    @Test
    fun `the img fills the box the background used to paint`() {
        val h = muCoverHtml("Harbour Lights", "/c.jpg")
        assertTrue("position:absolute" in h && "inset:0" in h, "out of flow and edge to edge: $h")
        assertTrue("object-fit:cover" in h, "object-fit:cover is what center/cover was doing: $h")
        // The positioning is inline precisely so that design/app/music.css is untouched by this change.
        assertTrue("""class="mu-cov """ in h, "the box keeps its own class and therefore its own rules: $h")
    }

    @Test
    fun `a chip follows the cover so it still paints over it`() {
        val h = muCoverHtml("Harbour Lights", "/c.jpg", chip = """<span class="mu-mchip w">check</span>""")
        val img = h.indexOf("<img ")
        val chip = h.indexOf("mu-mchip")
        assertTrue(img in 0..<chip, "the chip must come after the image in document order: $h")
    }

    @Test
    fun `a missing cover is still the wordmark and has no img at all`() {
        val h = muCoverHtml("Harbour Lights", null)
        assertFalse("<img " in h, "there is nothing to lazy-load when there is no cover: $h")
        assertTrue("linear-gradient" in h, "277 FR-277-3's gradient: $h")
        assertTrue("""<span class="mu-wm">Harbour Lights</span>""" in h, "the title is the wordmark: $h")
        assertTrue("mu-nocov" in h, "a missing cover is a work item on this side: $h")
    }

    @Test
    fun `a title that repeats keeps its colour and one that differs does not`() {
        assertEquals(muCoverHtml("Kvøld", null), muCoverHtml("Kvøld", null))
        assertTrue(muCoverHtml("Kvøld", null) != muCoverHtml("Kvøldin", null))
    }

    @Test
    fun `a quote in the url cannot break out of the src attribute`() {
        val h = muCoverHtml("x", """/c.jpg?a="><script>b""")
        assertFalse("<script>" in h, "the url is escaped before it is an attribute value: $h")
        assertTrue("&quot;&gt;&lt;script&gt;" in h, "escaped rather than dropped: $h")
    }

    @Test
    fun `extraClass and extraStyle still reach the box on both branches`() {
        val withCover = muCoverHtml("x", "/c.jpg", extraClass = "al-cover", extraStyle = ";width:160px")
        assertTrue("""class="mu-cov al-cover"""" in withCover, withCover)
        assertTrue("width:160px" in withCover, withCover)
        val without = muCoverHtml("x", null, extraClass = "bk-cover", extraStyle = ";width:160px")
        assertTrue("""class="mu-cov bk-cover"""" in without, without)
        assertTrue("width:160px" in without, without)
    }

    @Test
    fun `muImageStyle is still a background because the artwork tiles and the lightbox want one`() {
        // Not every cover is a cell. The album page's lightbox and the artwork tiles paint one picture each,
        // on screen, and FR-278-16 is about the 809 that are not on screen.
        assertTrue("url('/c.jpg')" in muImageStyle("/c.jpg"), muImageStyle("/c.jpg"))
    }
}
