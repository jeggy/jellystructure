package dev.jellystructure.audiobooks

import dev.jellystructure.auth.DeviceData
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookPart
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 281 (FR-281-10) — the phone's shelf is the viewer's; the position is ours and in book time; Jellyfin gets a
 *  mirror on the part being played and the parts left behind marked played; the last five minutes is *finished*. */
class AudiobooksTvServiceTest {
    private val base = "/tmp/jellystructure-test-abtv-${getpid()}"
    private lateinit var store: AudiobooksStore
    private lateinit var svc: AudiobooksTvService
    private val mirrored = mutableListOf<Pair<String, Long>>()
    private val played = mutableListOf<String>()
    private val volumes = mutableListOf<Pair<Int?, Boolean?>>()

    private val open = device(null)
    private val other = device(setOf("libother"))
    private fun device(allowed: Set<String>?) = DeviceData("d", "tok", "u1", "viewer", "", isAdmin = false, allowedLibraries = allowed, kind = "phone")
    private val min = 60_000L

    @BeforeTest fun setUp() = runBlocking {
        runCatching { platform.posix.remove("$base.db") }
        store = AudiobooksStore(createDatabase("$base.db"))
        val book = Audiobook(id = "F1", libraryId = "lib", title = "Vinterfærgen", authors = listOf("Ingrid Lykke"), durationMs = 3 * 20 * min, partCount = 3, addedAt = 10)
        val short = Audiobook(id = "F2", libraryId = "lib", title = "Nordlys", authors = listOf("Ada Zeeman"), durationMs = 4 * min, partCount = 1, addedAt = 20)
        val parts = (0..2).map { AudiobookPart(id = "p$it", bookId = "F1", number = it + 1, position = it, durationMs = 20 * min) } +
            AudiobookPart(id = "q0", bookId = "F2", number = 1, position = 0, durationMs = 4 * min)
        store.replaceLibrary(AudiobooksRows("lib", listOf(book, short), parts, emptyList(), 0))
        svc = AudiobooksTvService(store,
            mirror = { _, part, pos, _, vol, muted -> mirrored += part to pos; volumes += vol to muted },
            markPlayed = { _, ids -> played += ids })
    }

    @AfterTest fun tearDown() { runCatching { platform.posix.remove("$base.db") } }

    @Test
    fun a_viewer_without_the_library_sees_nothing() {
        assertTrue(svc.shelf(other, null).books.isEmpty())
        assertNull(svc.detail(other, "F1"))
        assertNull(svc.partId(other, "F1", 0))
        assertFalse(svc.any(other)); assertTrue(svc.any(open))
    }

    @Test
    fun the_shelf_leads_with_continue_listening_and_sorts_the_rest() = runBlocking {
        val first = svc.shelf(open, null)
        assertTrue(first.continueListening.isEmpty(), "nothing started, no Continue section")
        assertEquals(listOf("F2", "F1"), first.books.map { it.id }, "recently added first")
        assertEquals(listOf("F1", "F2"), svc.shelf(open, "author").books.map { it.id }, "Zeeman after Lykke")
        assertEquals(2, first.authors.size, "two authors, so the chip is there")

        svc.progress(open, "F1", 1, 5 * min, paused = false)
        val cont = svc.shelf(open, null).continueListening.single()
        assertEquals("F1", cont.id)
        assertEquals(25 * min, cont.positionMs, "book time: part 1 whole, then 5 min into part 2")
        assertEquals(35 * min, cont.leftMs)
        assertEquals(2, cont.part); assertEquals(3, cont.parts)
        assertEquals(2, cont.chapter, "file boundaries: one chapter per part")
    }

    @Test
    fun a_heartbeats_volume_reaches_the_mirror() = runBlocking {
        // R357 (FR-R357-2) — the player's level and mute go to Jellyfin's mirror; a heartbeat without them carries none.
        svc.progress(open, "F1", 0, 3 * min, paused = false, volumePercent = 35, muted = true)
        svc.progress(open, "F1", 0, 4 * min, paused = false)
        assertEquals(listOf<Pair<Int?, Boolean?>>(35 to true, null to null), volumes)
    }

    @Test
    fun a_heartbeat_is_ours_first_then_mirrored_and_the_parts_left_behind_are_played() = runBlocking {
        svc.progress(open, "F1", 0, 3 * min, paused = false)
        svc.progress(open, "F1", 2, 1 * min, paused = true)
        assertEquals(listOf("p0" to 3 * min, "p2" to 1 * min), mirrored)
        assertEquals(listOf("p0", "p1"), played)
        val d = assertNotNull(svc.detail(open, "F1"))
        assertEquals(2, d.position?.part)
        assertEquals(41 * min, d.position?.bookPositionMs)
        assertEquals(listOf("p0", "p1", "p2"), d.parts.map { it.id })
        assertEquals(3, d.chapters.size)
    }

    @Test
    fun the_last_five_minutes_is_finished_and_start_over_clears_it() = runBlocking {
        val pos = assertNotNull(svc.progress(open, "F1", 2, 16 * min, paused = false))
        assertTrue(pos.finished)
        assertTrue(svc.shelf(open, null).continueListening.isEmpty(), "a finished book leaves Continue listening")
        assertTrue(svc.shelf(open, null).books.first { it.id == "F1" }.finished)
        val over = assertNotNull(svc.finished(open, "F1", false))
        assertFalse(over.finished); assertEquals(0, over.bookPositionMs)
        // A part under five minutes still keeps its place — ours, not Jellyfin's.
        svc.progress(open, "F2", 0, 2 * min, paused = true)
        assertEquals(2 * min, svc.detail(open, "F2")?.position?.positionMs)
    }

    @Test
    fun speed_and_bookmarks_are_per_book() = runBlocking {
        assertTrue(svc.speed(open, "F1", 1.5))
        assertEquals(1.5, svc.detail(open, "F1")?.speed)
        assertEquals(1.0, svc.detail(open, "F2")?.speed, "another book starts at 1.0×")
        val bm = assertNotNull(svc.addBookmark(open, "F1", 30 * min, " Den gode del "))
        assertEquals("Den gode del", svc.detail(open, "F1")?.bookmarks?.single()?.note)
        svc.deleteBookmark(open, bm.id)
        assertTrue(svc.detail(open, "F1")?.bookmarks.orEmpty().isEmpty())
    }
}
