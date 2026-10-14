package dev.jellystructure.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 292 FR-292-17 (second amendment 2026-10-14, DEB-1156) — a version-bulk press says it is working and
 * refuses a second press until its pass has landed.
 *
 * Two things are covered here, and they are the two the gap was made of. The **gate** is the amplification:
 * three clicks inside the preview's own 3 s silence fired three whole-set `MusicBrowse.browse` resolves, and the
 * fix is only a fix if the second press is refused *and* the refusal is lifted on every exit — a gate that
 * sticks after a failure is a bar nobody can use for the rest of the session. The **markup** is the indication:
 * the attributes and the two sentences are what an admin sees, and an `aria-disabled` that is not on the control
 * the handler reads, or a label that never swaps, is the whole requirement missed.
 *
 * What is not here: that the browser then paints the dim within the same repaint as the press, and that the
 * server's log shows one resolve for four real clicks. Those are acceptance 11–13, in a browser against the real
 * library, and they are QA's — the admin frontend has no DOM test target, which is why these helpers are in
 * `commonMain` at all.
 */
class MusicVersionBulkPressTest {

    @Test
    fun `the second press is refused until the first has landed`() {
        val g = MuPendingPress()
        assertFalse(g.busy, "nothing is in flight before the first press")
        assertTrue(g.enter(), "the first press takes the pass")
        // The three clicks DEB-1156 measured. Every one of them is a whole-set resolve if it gets through.
        assertFalse(g.enter())
        assertFalse(g.enter())
        assertFalse(g.enter())
        assertTrue(g.busy, "a refused press must not disturb the pass that is running")
        g.release()
        assertTrue(g.enter(), "once the pass has landed the control is pressable again")
    }

    @Test
    fun `a pass that fails leaves the control pressable`() {
        val g = MuPendingPress()
        assertTrue(g.enter())
        // The call site releases in a `finally` precisely so that this holds for a thrown failure too, not only
        // for the `null` the api returns — acceptance 13: no pending state survives a failure.
        runCatching { try { throw IllegalStateException("the server didn’t answer") } finally { g.release() } }
        assertFalse(g.busy, "a failed pass must not strand the bar busy")
        assertTrue(g.enter(), "and the press must work again")
    }

    @Test
    fun `releasing a gate nobody holds is harmless`() {
        val g = MuPendingPress()
        g.release()
        assertFalse(g.busy)
        assertTrue(g.enter())
    }

    @Test
    fun `an idle press offers the action and nothing else`() {
        val h = muVersionBulkBtnHtml(pending = false)
        assertTrue("data-vbulk" in h, "the handler finds the control by this attribute: $h")
        assertTrue("Set version…" in h, "the idle label is unchanged: $h")
        assertFalse("aria-disabled" in h, "an idle press must be pressable: $h")
        assertFalse("aria-busy" in h, "and must not claim to be working: $h")
        assertFalse("opacity" in h, "nor be dimmed: $h")
    }

    @Test
    fun `a press in flight says what it is doing and is refused`() {
        val h = muVersionBulkBtnHtml(pending = true)
        assertTrue("data-vbulk" in h, "it stays the same control, so the handler still reaches it: $h")
        assertTrue("Reading the versions…" in h, "the label is the pass, in the present participle: $h")
        assertFalse("Set version…" in h, "the idle label is gone — the swap is the indication: $h")
        assertTrue("""aria-disabled="true"""" in h, "MusicFilesTab's shipped shape for a control that will not act: $h")
        assertTrue("""aria-busy="true"""" in h, "a screen reader is told it is working, not just that it is off: $h")
        assertTrue("opacity:.55" in h, "the Seerr dialog's dimming, so the refusal is visible as well as read: $h")
    }

    @Test
    fun `both states are one control differing only in the pending part`() {
        // The regression this guards: three bars draw this button (Songs' two and the album's). If the pending
        // and idle forms ever drift in class or tag, one of those bars loses its styling mid-flight.
        val idle = muVersionBulkBtnHtml(pending = false)
        val busy = muVersionBulkBtnHtml(pending = true)
        assertTrue(idle.startsWith("""<span class="btn sm primary" data-vbulk"""), idle)
        assertTrue(busy.startsWith("""<span class="btn sm primary" data-vbulk"""), busy)
        assertTrue(idle.endsWith("</span>") && busy.endsWith("</span>"))
    }

    @Test
    fun `the write draws the shipped spinner and the count it is writing`() {
        val h = muVersionBulkWritingHtml(799)
        assertTrue("""class="mu-busy"""" in h, "music.css's own component, so no stylesheet changes: $h")
        assertTrue("""<span class="sp"></span>""" in h, "the spinner is the .sp child .mu-busy styles: $h")
        assertTrue("Setting the version on 799 songs…" in h, "the sentence names the number being written: $h")
        // Not a determinate bar: one transaction has no honest fraction, and `wf.css`'s `.bar` would state one.
        assertFalse("class=\"bar\"" in h, "no progress bar: $h")
        assertFalse("data-vbgo" in h, "and nothing to press while it runs: $h")
        assertFalse("data-vbx" in h, "Cancel is gone too — the write is already out: $h")
    }

    @Test
    fun `the write's sentence counts one song as one song`() {
        assertTrue("Setting the version on 1 song…" in muVersionBulkWritingHtml(1))
        assertTrue("Setting the version on 2 songs…" in muVersionBulkWritingHtml(2))
    }

    @Test
    fun `the two sentences are the ones the spec's copy list carries`() {
        // FR-292-17 added exactly these two strings to 292's copy list. A reworded label is a spec change.
        assertEquals(
            listOf("Reading the versions…", "Setting the version on 4 songs…"),
            listOf(
                Regex(">([^<]*)</span>$").find(muVersionBulkBtnHtml(pending = true))!!.groupValues[1],
                Regex("</span>([^<]*)</div>$").find(muVersionBulkWritingHtml(4))!!.groupValues[1],
            ),
        )
    }
}
