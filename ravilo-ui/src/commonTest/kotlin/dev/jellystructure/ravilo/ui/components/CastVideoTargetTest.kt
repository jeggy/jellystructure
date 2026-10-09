package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.seams.CastRoute
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R329 (found live 2026-10-09, Mac) — a film is never cast to an audio-only speaker a music cast is on. */
class CastVideoTargetTest {
    private fun route(name: String, kind: String, selected: Boolean = false) = CastRoute(id = name, name = name, selected = selected, select = {}, kind = kind)
    private val routes = listOf(route("Guest room", "speaker"), route("Living room TV", "display"), route("Guest room + 1", "group"))

    @Test fun `a link on a speaker leaves a film to this device`() {
        assertFalse(castsVideoTo(routes, "Guest room"))
        assertFalse(castsVideoTo(routes, "Guest room + 1"))   // a group led by a speaker
    }

    @Test fun `a link on a display casts the film`() {
        assertTrue(castsVideoTo(routes, "Living room TV"))
    }

    @Test fun `a link whose route is not known yet still casts`() {
        assertTrue(castsVideoTo(emptyList(), "Somewhere"))
    }
}
