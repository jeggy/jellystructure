package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R292 (FR-R292-2/5/6) — the resume record round-trips through one saved string, is scoped to its item, restores once, and obeys the 30-minute rule. */
class PlayerResumeTest {
    private val rec = ResumeRecord(itemId = "ep7", positionMs = 91_000, playIntent = true, awayAtMs = 1_000_000, audioIndex = 1, subIndex = 2, burnedSubIndex = 4, sessionAudioIndex = 2, title = "Slumber Party Panic", kicker = "S1 · E1", displayName = "eydun")

    @Test
    fun `capture persists one string and a new store decodes the same record`() {
        var saved = ""
        val a = PlayerResumeStore("") { saved = it }
        assertNull(a.record); assertFalse(a.consumeRestored())
        a.capture(rec)
        assertTrue(saved.contains("\"itemId\":\"ep7\""))
        val b = PlayerResumeStore(saved) {}
        assertEquals(rec, b.record)
        assertTrue(b.consumeRestored(), "the first player after a restore counts it")
        assertFalse(b.consumeRestored(), "once")
        b.clear(); assertNull(b.record)
    }

    @Test
    fun `a record belongs to its item and never to the next episode`() {
        val s = PlayerResumeStore("") {}
        s.capture(rec)
        assertEquals(rec, s.recordFor("ep7")); assertNull(s.recordFor("ep8"))
    }

    @Test
    fun `garbage in saved state is no record`() {
        assertNull(PlayerResumeStore("{not json") {}.record)
        assertNull(PlayerResumeStore("   ") {}.record)
    }

    @Test
    fun `under thirty minutes away plays and longer lands paused and after twelve hours it is not restored`() {
        assertTrue(resumePlayIntent(rec, rec.awayAtMs + 29 * 60_000))
        assertFalse(resumePlayIntent(rec, rec.awayAtMs + 31 * 60_000))
        assertFalse(resumePlayIntent(rec.copy(playIntent = false), rec.awayAtMs + 1_000), "paused by the viewer stays paused")
        assertTrue(resumeRestorable(rec, rec.awayAtMs + 11 * 3_600_000))
        assertFalse(resumeRestorable(rec, rec.awayAtMs + 13 * 3_600_000))
    }
}
