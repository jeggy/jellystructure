package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.seams.CastRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R360 (FR-R360-7) — the glyph follows the sheet's list, with a 10 s grace on the way out. */
@OptIn(ExperimentalCoroutinesApi::class)
class CastIconRuleTest {
    private fun route(kind: String) = CastRoute(id = kind, name = kind, selected = false, select = {}, kind = kind)

    @Test fun `an empty list shows no glyph`() =
        assertFalse(hasCastDevices(screensPaired = false, routes = emptyList(), music = false, airplayAvailable = false))

    @Test fun `one offline screen is enough`() =
        assertTrue(hasCastDevices(screensPaired = true, routes = emptyList(), music = false, airplayAvailable = false))

    @Test fun `a speaker counts in music mode only`() {
        assertFalse(hasCastDevices(false, listOf(route("speaker")), music = false, airplayAvailable = false))
        assertTrue(hasCastDevices(false, listOf(route("speaker")), music = true, airplayAvailable = false))
        assertTrue(hasCastDevices(false, listOf(route("display")), music = false, airplayAvailable = false))
    }

    @Test fun `AirPlay alone is enough`() = assertTrue(hasCastDevices(false, emptyList(), false, airplayAvailable = true))

    @Test fun `a Playing everywhere row counts`() = assertTrue(hasCastDevices(false, emptyList(), false, false, sessionRows = 1))

    @Test fun `the sheet's mode filter is the glyph's`() {
        val all = listOf(route("display"), route("speaker"), route("group"))
        assertEquals(listOf("display"), visibleCastRoutes(all, music = false).map { it.kind })
        assertEquals(3, visibleCastRoutes(all, music = true).size)
    }

    @Test fun `connected with an empty list keeps the glyph`() = runTest {
        val devices = MutableStateFlow(false); val casting = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        val job = launch { castIconPresence(devices, casting).collect { seen += it } }
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(true), seen)
        job.cancel()
    }

    @Test fun `an emptied list hides after ten seconds, not nine`() = runTest {
        val devices = MutableStateFlow(true); val casting = MutableStateFlow(false)
        var shown: Boolean? = null
        val job = launch { castIconPresence(devices, casting).collect { shown = it } }
        runCurrent(); assertEquals(true, shown)
        devices.value = false; runCurrent()
        advanceTimeBy(9_000); runCurrent(); assertEquals(true, shown)
        advanceTimeBy(1_001); runCurrent(); assertEquals(false, shown)
        job.cancel()
    }

    @Test fun `a device back at five seconds is never hidden`() = runTest {
        val devices = MutableStateFlow(true); val casting = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { castIconPresence(devices, casting).collect { seen += it } }
        runCurrent()
        devices.value = false; runCurrent(); advanceTimeBy(5_000); runCurrent()
        devices.value = true; runCurrent(); advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(true), seen)
        job.cancel()
    }

    @Test fun `starting empty never flashes the glyph`() = runTest {
        val seen = mutableListOf<Boolean>()
        val job = launch { castIconPresence(MutableStateFlow(false), MutableStateFlow(false)).collect { seen += it } }
        runCurrent(); assertTrue(seen.isEmpty())
        advanceTimeBy(10_001); runCurrent(); assertEquals(listOf(false), seen)
        job.cancel()
    }
}
