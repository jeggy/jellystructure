package dev.jellystructure.ravilo.ui.remote

import dev.jellystructure.ravilo.ui.sessions.endedLinesOf
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DEB-5706 (FR-R372-4, FR-R372-5, R372 review item 10) — what an ended session's remote says. A film and an episode read
 * alike: *Stopped on {place}* when stopped midway, *Finished* over it when played out, *{person} stopped it* for someone
 * else's stop. *End of episode* / *End of film* are the Cast link's words and never come from here.
 */
class RemoteEndedLinesTest {
    private fun ended(kind: String, reason: String?, by: String? = null) = SessionView(
        id = "s", revision = 2, owner = SessionOwner("u", "Olivar"), mine = true, kind = kind, title = "T",
        target = SessionTarget("cast", "t", "Stue TV", "tv"), state = "ended", endReason = reason, endedBy = by,
        durationMs = 596_000, positionMs = 149_000, createdAt = 1,
    )

    @Test fun `a film stopped midway reads stopped on the place and nothing under it`() {
        val l = endedLinesOf(ended("film", "stopped"))
        assertEquals("session.ended", l.key)
        assertEquals(mapOf("place" to "Stue TV"), l.args)
        assertNull(l.subKey)
    }

    @Test fun `an episode stopped midway reads stopped on the place and nothing under it`() {
        val l = endedLinesOf(ended("episode", "stopped"))
        assertEquals("session.ended", l.key)
        assertNull(l.subKey)
    }

    @Test fun `a film played to its end reads finished over stopped on the place`() {
        val l = endedLinesOf(ended("film", "finished"))
        assertEquals("ab.finished", l.key)
        assertEquals("session.ended", l.subKey)
    }

    @Test fun `an episode played to its end reads finished over stopped on the place`() {
        val l = endedLinesOf(ended("episode", "finished"))
        assertEquals("ab.finished", l.key)
        assertEquals("session.ended", l.subKey)
    }

    @Test fun `a stop by someone else names them over stopped on the place`() {
        val l = endedLinesOf(ended("film", "stopped", by = "Hanna"))
        assertEquals("session.stopped_by", l.key)
        assertEquals(mapOf("person" to "Hanna"), l.args)
        assertEquals("session.ended", l.subKey)
    }
}
