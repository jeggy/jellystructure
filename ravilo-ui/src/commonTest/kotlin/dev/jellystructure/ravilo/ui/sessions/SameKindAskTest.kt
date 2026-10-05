package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R370 (FR-R370-4) — Play pressed while the same kind plays for this viewer elsewhere asks first. */
class SameKindAskTest {
    private fun s(kind: String = "film", here: Boolean = false, mine: Boolean = true, controllable: Boolean = true, castId: String? = null, state: String = "playing") =
        SessionView("s1", 7, SessionOwner("u", "Anna"), mine = mine, kind = kind, title = "Night Train",
            target = SessionTarget(if (castId != null) "cast" else "app", if (castId != null) "rx-1" else "den-tv", "Den TV", "tv", castDeviceId = castId),
            state = state, here = here, controllable = controllable)

    @Test fun `a film elsewhere asks before a film starts here`() {
        assertEquals("s1", sameKindAskFor(listOf(s()), "film", isTv = false)?.id)
    }

    @Test fun `never on the TV and never for what plays here or is not mine to steer`() {
        assertNull(sameKindAskFor(listOf(s()), "film", isTv = true))
        assertNull(sameKindAskFor(listOf(s(here = true)), "film", isTv = false))
        assertNull(sameKindAskFor(listOf(s(controllable = false)), "film", isTv = false))
    }

    @Test fun `play there replaces the session on its own place`() {
        val app = sameKindStartRequest(SameKindAsk(s(), "episode", "ep-5", "S01E05") {})
        assertEquals("den-tv", app.targetId)
        assertEquals(listOf("ep-5"), app.items)
        assertEquals("s1" to 7L, app.replace?.sessionId to app.replace?.revision)
        val cast = sameKindStartRequest(SameKindAsk(s(castId = "c-den"), "film", "f-1", "Night Train") {})
        assertEquals("cast:c-den", cast.targetId)
    }

    @Test fun `the same film elsewhere is resumed or moved, not started again`() {
        kotlin.test.assertTrue(sameTitle(SameKindAsk(s(), "film", "f-1", "Night Train") {}))
        kotlin.test.assertFalse(sameTitle(SameKindAsk(s(), "film", "f-2", "Day Train") {}))
        // An episode's session carries the series title: another episode of it is not the same.
        kotlin.test.assertFalse(sameTitle(SameKindAsk(s(kind = "episode"), "episode", "ep-6", "Night Train") {}))
    }
}
