package dev.jellystructure.ravilo.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class UpcomingCardLinesTest {
    @Test
    fun `a film shows its genre only`() =
        assertEquals(listOf("Thriller"), upcomingCardSubLines(true, null, null, "Thriller"))

    @Test
    fun `a film with no genre has no line`() =
        assertEquals(emptyList(), upcomingCardSubLines(true, null, null, " "))

    @Test
    fun `a series ends in network and genre`() =
        assertEquals(listOf("S01E04 · Pilot", "HBO  ·  Drama"), upcomingCardSubLines(false, "S01E04 · Pilot", "HBO", "Drama"))

    @Test
    fun `a series with only a network`() =
        assertEquals(listOf("S01E04", "HBO"), upcomingCardSubLines(false, "S01E04", "HBO", null))

    @Test
    fun `a series with neither keeps the episode line`() =
        assertEquals(listOf("S01E04"), upcomingCardSubLines(false, "S01E04", null, ""))
}
