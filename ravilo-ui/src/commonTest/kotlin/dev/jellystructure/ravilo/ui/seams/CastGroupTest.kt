package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.music.CAST_RESUME_WINDOW_MS
import dev.jellystructure.ravilo.ui.music.castResumeInFlight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R355 — a music session Android's output panel grouped onto several speakers. */
class CastGroupTest {
    @Test
    fun `one device keeps its own name`() {
        assertEquals("Kitchen", castSessionName("Kitchen", emptyList()))
        assertEquals("Kitchen", castSessionName("Kitchen", listOf("Kitchen")))
        assertEquals(null, castSessionName(null, emptyList()))
        // A group made in Google Home is one route: its own name, whatever it holds.
        assertEquals("Downstairs", castSessionName("Downstairs", emptyList()))
    }

    @Test
    fun `two speakers are named, the first one first`() {
        // Cast renames the session "<first> + 1" once a speaker joins; the platform may list the members either way.
        assertEquals("Kitchen + Hall", castSessionName("Kitchen + 1", listOf("Hall", "Kitchen")))
        assertEquals("Kitchen + Hall", castSessionName("Kitchen", listOf("Kitchen", "Hall")))
        assertEquals("Hall + Kitchen", castSessionName("Hall + 1", listOf("Kitchen", "Hall")))
    }

    @Test
    fun `three are named, four or more are counted`() {
        assertEquals("Kitchen + Hall + Study", castSessionName("Kitchen + 2", listOf("Hall", "Kitchen", "Study")))
        assertEquals("Kitchen + 3", castSessionName("Kitchen + 3", listOf("Hall", "Kitchen", "Study", "Porch")))
    }

    @Test
    fun `no member matches the session's name - the platform's first leads`() {
        assertEquals("Hall + Kitchen", castSessionName("Somewhere", listOf("Hall", "Kitchen")))
        assertEquals("Hall + Kitchen", castSessionName(null, listOf("Hall", "Kitchen")))
    }

    @Test
    fun `blank and repeated member names are ignored`() {
        assertEquals("Kitchen", castSessionName("Kitchen", listOf("Kitchen", " ", "Kitchen")))
    }

    @Test
    fun `every speaker of the group is this session`() {
        val members = listOf("Kitchen", "Hall")
        assertTrue(castRouteInSession("Kitchen", "Kitchen + Hall", members))
        assertTrue(castRouteInSession("Hall", "Kitchen + Hall", members))
        assertFalse(castRouteInSession("Study", "Kitchen + Hall", members))
    }

    @Test
    fun `the group's own route is this session while grouped`() {
        val members = listOf("Kitchen", "Hall")
        assertTrue(castRouteInSession("Kitchen + 1", "Kitchen + Hall", members))
        assertFalse(castRouteInSession("Kitchen + 2", "Kitchen + Hall", members))   // another group
        assertFalse(castRouteInSession("Study + 1", "Kitchen + Hall", members))
        assertFalse(castRouteInSession("Kitchen + 1", "Kitchen", emptyList()))       // not grouped: someone else's group
    }

    @Test
    fun `one device is matched by its own name, as before`() {
        assertTrue(castRouteInSession("Kitchen", "Kitchen", emptyList()))
        assertTrue(castRouteInSession(" kitchen ", "Kitchen", emptyList()))
        assertFalse(castRouteInSession("Hall", "Kitchen", emptyList()))
        assertFalse(castRouteInSession("Hall", null, emptyList()))
        assertFalse(castRouteInSession(" ", "Kitchen", listOf(" ")))
    }

    @Test
    fun `one hand-off per press until the device reports`() {
        assertFalse(castResumeInFlight(null))                                  // nothing sent yet
        assertTrue(castResumeInFlight(22))                                     // the second press, 22 ms later
        assertTrue(castResumeInFlight(CAST_RESUME_WINDOW_MS - 1))
        assertFalse(castResumeInFlight(CAST_RESUME_WINDOW_MS))                 // the device never answered: try again
        assertFalse(castResumeInFlight(-1))                                    // a clock that went back is not a press
    }
}
