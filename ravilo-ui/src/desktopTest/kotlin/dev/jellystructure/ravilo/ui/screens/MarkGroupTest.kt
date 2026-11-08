package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.Episode
import kotlin.test.Test
import kotlin.test.assertEquals

/** R179 §C.1 — a multi-episode card's toggle covers every episode in the file. */
class MarkGroupTest {
    private fun ep(id: String, n: Int) = Episode(id = id, episodeNumber = n, title = "t$n", runtime = 40, overview = null, stillUrl = null)

    @Test
    fun `the toggle sends the entry episode and every id in the group`() {
        val group = listOf(ep("e1", 1), ep("e2", 2), ep("e3", 3))
        var sent: Triple<String, Boolean, List<String>>? = null
        markGroup(group, group[1], true) { id, played, ids -> sent = Triple(id, played, ids) }
        assertEquals(Triple("e2", true, listOf("e1", "e2", "e3")), sent)
    }
}
