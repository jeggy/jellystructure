package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

/** R306 (FR-R306-3) — a mid-film failure becomes R237's card; a stale flag from the stream before never does. */
class FailureLatchTest {
    private fun run(vararg flags: Boolean): List<Boolean> { val l = FailureLatch(); return flags.map { l.observe(it) } }

    @Test
    fun `a failure after the stream is seen healthy fires`() {
        assertEquals(listOf(false, false, true), run(false, false, true))
    }

    @Test
    fun `a flag still set from the previous stream does not fire until it has cleared`() {
        assertEquals(listOf(false, false, false, true), run(true, true, false, true))
    }

    @Test
    fun `a flag that never clears never fires`() {
        assertEquals(listOf(false, false, false), run(true, true, true))
    }
}
