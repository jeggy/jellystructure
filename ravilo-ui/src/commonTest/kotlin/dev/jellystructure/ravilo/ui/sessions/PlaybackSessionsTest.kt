package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.shared.tv.SessionListEnvelope
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionStateEnvelope
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R368 — the client's store and the pure rules the sheet, the glyph and the bar draw from. */
class PlaybackSessionsTest {
    private fun v(
        id: String, here: Boolean = false, mine: Boolean = true, state: String = "playing", kind: String = "music",
        updated: Long = 0, created: Long = 0, rev: Long = 1, pos: Long? = 10_000, at: Long = 1_000, duration: Long? = 200_000,
    ) = SessionView(
        id = id, revision = rev, owner = SessionOwner(if (mine) "u-anna" else "u-ben", if (mine) "Anna" else "Ben"), mine = mine,
        kind = kind, title = "T-$id", subtitle = if (kind == "episode") "S01E05" else null,
        target = SessionTarget("cast", "d-$id", "Place $id", "speaker"), state = state, positionMs = pos, positionAt = at,
        durationMs = duration, here = here, createdAt = created, updatedAt = updated,
    )

    @Test fun `the store holds exactly the last list`() {
        val s1 = applySessionList(SessionListEnvelope(sessions = listOf(v("a"), v("b"))), 0)
        val s2 = applySessionList(SessionListEnvelope(sessions = listOf(v("c"))), 0)
        assertEquals(listOf("a", "b"), s1.sessions.map { it.id })
        assertEquals(listOf("c"), s2.sessions.map { it.id })
    }

    @Test fun `a state replaces its row only with a higher revision`() {
        val s = applySessionList(SessionListEnvelope(sessions = listOf(v("a", rev = 3))), 0)
        val older = applySessionState(s, SessionStateEnvelope(session = v("a", rev = 2, state = "paused")), 0)
        assertEquals("playing", older.sessions.single().state)
        val newer = applySessionState(s, SessionStateEnvelope(session = v("a", rev = 4, state = "paused")), 0)
        assertEquals("paused", newer.sessions.single().state)
    }

    @Test fun `a state for an id not in the list is ignored`() {
        val s = applySessionList(SessionListEnvelope(sessions = listOf(v("a"))), 0)
        assertEquals(s, applySessionState(s, SessionStateEnvelope(session = v("zz", rev = 9)), 0))
    }

    @Test fun `this device first then mine by latest change then others`() {
        val rows = listOf(v("other", mine = false, updated = 99), v("mine-old", updated = 1), v("here", here = true, updated = 0), v("mine-new", updated = 50))
        assertEquals(listOf("here", "mine-new", "mine-old", "other"), orderSessionRows(rows).map { it.id })
    }

    @Test fun `the count leaves out this device and is absent at zero`() {
        assertNull(sessionsElsewhereCount(listOf(v("here", here = true))))
        assertNull(sessionsElsewhereCount(emptyList()))
        assertEquals(2, sessionsElsewhereCount(listOf(v("here", here = true), v("a"), v("b", mine = false), v("gone", state = "ended"))))
    }

    @Test fun `the glyph shows for a device or a row and not for neither`() {
        assertTrue(castIconShown(true, emptyList()))
        assertTrue(castIconShown(false, listOf(v("a"))))
        assertFalse(castIconShown(false, emptyList()))
        assertFalse(castIconShown(false, listOf(v("a", state = "ended"))))
    }

    @Test fun `the bar keeps the session this app touched last`() {
        val rows = listOf(v("touched", state = "paused", created = 1), v("newer", created = 9))
        assertEquals("touched", pickBarSession(rows, "touched", filmsMode = false)?.id)
    }

    @Test fun `before any touch it shows the latest playing one`() {
        val rows = listOf(v("old", created = 1), v("new", created = 9), v("paused", state = "paused", created = 20))
        assertEquals("new", pickBarSession(rows, null, filmsMode = false)?.id)
    }

    @Test fun `when the touched one ends it falls back`() {
        val rows = listOf(v("touched", state = "ended", created = 1), v("other", created = 2))
        assertEquals("other", pickBarSession(rows, "touched", filmsMode = false)?.id)
    }

    @Test fun `films mode shows only music and audiobooks from elsewhere`() {
        val rows = listOf(v("film", kind = "episode", created = 9), v("song", kind = "music", created = 1))
        assertEquals("song", pickBarSession(rows, null, filmsMode = true)?.id)
        assertEquals("film", pickBarSession(rows, null, filmsMode = false)?.id)
    }

    @Test fun `the bar never shows this device's own session and counts the rest`() {
        val rows = listOf(v("here", here = true, created = 99), v("a", created = 1), v("b", created = 2))
        val shown = pickBarSession(rows, null, filmsMode = false)
        assertEquals("b", shown?.id)
        assertEquals(1, barMoreCount(rows, shown))
    }

    @Test fun `a playing row advances on the server clock`() {
        // Reported at server time 1 000 at 10 s; the list said the server's now was 5 000 when it arrived at local 0.
        assertEquals(10_000 + 4_000 + 2_000, drawnPositionMs(v("a", pos = 10_000, at = 1_000), serverNowMs = 5_000, receivedAtMs = 0, nowMs = 2_000))
    }

    @Test fun `a paused row is frozen`() =
        assertEquals(10_000, drawnPositionMs(v("a", state = "paused", pos = 10_000, at = 1_000), 5_000, 0, 60_000))

    @Test fun `a device clock ten minutes off still draws the right time`() {
        val skew = 600_000L
        assertEquals(16_000, drawnPositionMs(v("a", pos = 10_000, at = 1_000), 5_000, receivedAtMs = skew, nowMs = skew + 2_000))
    }

    @Test fun `the drawn position is clamped to the duration`() =
        assertEquals(200_000, drawnPositionMs(v("a", pos = 199_000, at = 0, duration = 200_000), 0 + 1, 0, 100_000))

    @Test fun `phone desktop and web ask for sessions and the TV does not`() {
        assertEquals(setOf("sessions"), eventsFeaturesFor(isTv = false))
        assertTrue(eventsFeaturesFor(isTv = true).isEmpty())
        assertEquals(setOf("sessions", "session_control"), eventsFeaturesFor(isTv = false, obeysSessionCommands = true))
    }

    @Test fun `an episode row reads title and code`() = assertEquals("T-a · S01E05", sessionRowTitle(v("a", kind = "episode")))

    @Test fun `the session strings exist in every language and never say session or a product`() {
        val keys = listOf("session.everywhere", "session.person_listening", "session.person_watching", "session.reconnecting", "session.paused", "session.pause")
        for (key in keys) {
            val en = t(key, "en")
            assertNotEquals(key, en, "$key is in no table")
            for (lang in listOf("da", "fo")) if (key != "session.pause") assertNotEquals(en, t(key, lang), "$key falls back to English in $lang")
            for (lang in listOf("en", "da", "fo")) {
                val text = t(key, lang).lowercase()
                for (word in listOf("session", "cast", "chromecast", "jellyfin")) assertFalse(word in text, "$key/$lang says $word")
            }
        }
        assertEquals("Sambindur aftur…", t("session.reconnecting", "fo"))
        assertEquals("session.playing_on", t("session.playing_on", "en"), "cast.playing_on is reused; session.playing_on does not exist")
    }
}
