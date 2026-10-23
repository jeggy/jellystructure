package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.i18n.t
import dev.jellystructure.ravilo.ui.desktop.MacDockMenu.Item
import dev.jellystructure.ravilo.ui.desktop.MacNowPlaying.Command
import dev.jellystructure.ravilo.ui.desktop.MacNowPlaying.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** DEB-2249 — what the Mac's Dock menu offers (FR-1..FR-4, FR-6); the AWT menu itself needs a Mac (the QA row). */
class MacDockMenuTest {
    @Test
    fun `no owner means no items`() {
        assertEquals(emptyList(), MacDockMenu.itemsFor(null, playing = true))
        assertEquals(emptyList(), MacDockMenu.itemsFor(null, playing = false))
    }

    @Test
    fun `a song gets play pause then next then previous`() {
        assertEquals(
            listOf(Item("mac.dock.pause", Command.TOGGLE), Item("mac.dock.next", Command.NEXT), Item("mac.dock.previous", Command.PREVIOUS)),
            MacDockMenu.itemsFor(Mode.MUSIC, playing = true))
        assertEquals("mac.dock.play", MacDockMenu.itemsFor(Mode.MUSIC, playing = false).first().key)
    }

    @Test
    fun `a book skips thirty seconds each way and never next or previous`() {
        assertEquals(
            listOf(Item("mac.dock.play", Command.TOGGLE), Item("mac.dock.forward_30", Command.SKIP_FORWARD, 30.0), Item("mac.dock.back_30", Command.SKIP_BACK, 30.0)),
            MacDockMenu.itemsFor(Mode.BOOK, playing = false))
    }

    @Test
    fun `a film gets only the one toggle that names what it will do`() {
        assertEquals(listOf(Item("mac.dock.pause", Command.TOGGLE)), MacDockMenu.itemsFor(Mode.FILM, playing = true))
        assertEquals(listOf(Item("mac.dock.play", Command.TOGGLE)), MacDockMenu.itemsFor(Mode.FILM, playing = false))
    }

    @Test
    fun `every label is written in english danish and faroese`() {
        val keys = Mode.entries.flatMap { m -> listOf(true, false).flatMap { MacDockMenu.itemsFor(m, it) } }.map { it.key }.toSet()
        assertEquals(setOf("play", "pause", "next", "previous", "forward_30", "back_30").map { "mac.dock.$it" }.toSet(), keys)
        for (lang in listOf("en", "da", "fo")) for (k in keys) {
            val label = t(k, lang)
            assertNotEquals(k, label, "$k is missing in $lang")
            assertTrue(label.isNotBlank())
        }
        assertEquals("Pause", t("mac.dock.pause", "en"))
        assertNotEquals(t("mac.dock.next", "en"), t("mac.dock.next", "fo"))
    }
}
