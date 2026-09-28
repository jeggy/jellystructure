package dev.jellystructure.audiobooks

import dev.jellystructure.auth.JellyfinAudiobooksLibrary
import dev.jellystructure.auth.JellyfinMusicItem
import dev.jellystructure.auth.JellyfinNameId
import dev.jellystructure.auth.JellyfinPersonLite
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.model.AudiobookGap
import dev.jellystructure.model.AudiobookOrigin
import dev.jellystructure.model.AudiobookRules
import dev.jellystructure.model.MusicArt
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Phase 280 (FR-280-1/3, acceptance 1–2) — the folder is the book; a gap and two albums are flagged, never guessed. */
class AudiobooksIngestTest {
    private val lib = LibraryMapping(jellyfinId = "LIB", name = "Lydbøger", collectionType = "books", jellyfinPath = "/media/books", localPath = "/mnt/books")
    private fun part(n: Int, parent: String = "F1", album: String? = "Vinterfærgen", minutes: Long = 25, file: Int = n) = JellyfinMusicItem(
        id = "p$parent$n", name = "Del $n", type = "AudioBook", path = "/media/books/Ingrid Lykke/Vinterfærgen/Vinterfærgen - ${file.toString().padStart(2, '0')}.mp3",
        parentId = parent, indexNumber = n, runTimeTicks = minutes * 60_000L * 10_000L, album = album,
        albumArtists = listOf(JellyfinNameId("Ingrid Lykke", "x")), people = if (n == 1) listOf(JellyfinPersonLite("Oda Holm", "Composer")) else emptyList(),
    )

    private fun build(items: List<JellyfinMusicItem>) = runBlocking {
        AudiobooksIngest.build(lib, JellyfinAudiobooksLibrary(items, ebookCount = 0), emptyMap(), emptyMap(), emptyMap(), 1000L, { false }, { emptyList() })
    }

    @Test
    fun fourteen_files_are_one_book_with_part_six_missing() {
        val rows = build((1..15).filter { it != 6 }.map { part(it) })
        val book = rows.books.single()
        assertEquals("Vinterfærgen", book.title)
        assertEquals(listOf("Ingrid Lykke"), book.authors)
        assertEquals(listOf("Oda Holm"), book.narrators, "a file's composer tag is its narrator")
        assertEquals(14, book.partCount)
        assertEquals(listOf(6), book.gap)
        assertEquals(AudiobookGap.NOT_IN_FOLDER, book.gapKind, "file names and Jellyfin agree on the numbers")
        assertEquals(MusicArt.NONE, book.coverState)
        assertEquals("/mnt/books/Ingrid Lykke/Vinterfærgen", book.folderPath)
        assertEquals(AudiobookOrigin.FILES, book.origins["title"])
        assertEquals((0..13).toList(), rows.parts.sortedBy { it.number }.map { it.position })
        assertEquals(listOf("a:ingridlykke"), rows.authors.map { it.id })
    }

    @Test
    fun a_folder_naming_two_albums_is_flagged_and_numbers_that_disagree_read_as_numbered_wrong() {
        val book = build(listOf(part(1), part(2, album = "Nordlys"), part(4, file = 3))).books.single()
        assertEquals(listOf("Vinterfærgen", "Nordlys"), book.albumTags)
        assertEquals(listOf(3), book.gap)
        assertEquals(AudiobookGap.NUMBERED_WRONG, book.gapKind)
    }

    @Test
    fun a_reread_keeps_what_the_admin_typed_and_the_order_they_dragged() = runBlocking {
        val first = build((1..3).map { part(it) })
        val typed = first.books.single().copy(title = "Vinterfærgen (lydbog)", origins = first.books.single().origins + ("title" to AudiobookOrigin.TYPED))
        val dragged = first.parts.map { if (it.number == 1) it.copy(position = 9) else it }
        val again = AudiobooksIngest.build(lib, JellyfinAudiobooksLibrary((1..3).map { part(it, album = "Other") } + part(4), 0),
            mapOf(typed.id to typed), dragged.associateBy { it.id }, emptyMap(), 2000L, { false }, { emptyList() })
        val book = again.books.single()
        assertEquals("Vinterfærgen (lydbog)", book.title)
        assertEquals(9, again.parts.first { it.number == 1 }.position)
        assertEquals(10, again.parts.first { it.number == 4 }.position, "a new part goes after the dragged order")
    }

    @Test
    fun a_split_folder_becomes_one_book_per_album_tag_and_a_join_brings_it_back() = runBlocking {
        val items = listOf(part(1), part(2), part(3, album = "Nordlys"), part(4, album = "Nordlys"))
        val first = build(items)
        val folder = first.books.single()
        assertEquals(listOf("Vinterfærgen", "Nordlys"), folder.albumTags)
        val split = folder.copy(splitByAlbum = true, splitPrimary = "Vinterfærgen")
        val again = AudiobooksIngest.build(lib, JellyfinAudiobooksLibrary(items, 0), mapOf(split.id to split), first.parts.associateBy { it.id }, emptyMap(), 2000L, { false }, { emptyList() })
        val live = again.books.filter { it.missingSince == null }.associateBy { it.id }
        assertEquals(setOf("F1", "F1~nordlys"), live.keys)
        assertEquals("Vinterfærgen", live.getValue("F1").title)
        assertEquals(emptyList(), live.getValue("F1").albumTags, "the folder's own book no longer names two albums")
        assertEquals(2, live.getValue("F1~nordlys").partCount)
        assertEquals("F1", live.getValue("F1~nordlys").splitFrom)
        assertEquals(setOf("F1~nordlys"), again.parts.filter { it.number in setOf(3, 4) }.map { it.bookId }.toSet())
        // Join: the flag goes, the next read groups the folder as one book and the split-off one is kept, marked missing.
        val joined = again.books.first { it.id == "F1" }.copy(splitByAlbum = false, splitPrimary = null)
        val third = AudiobooksIngest.build(lib, JellyfinAudiobooksLibrary(items, 0), again.books.associateBy { it.id } + (joined.id to joined), again.parts.associateBy { it.id }, emptyMap(), 3000L, { false }, { emptyList() })
        assertEquals(4, third.books.single { it.id == "F1" }.partCount)
        assertEquals(3000L, third.books.single { it.id == "F1~nordlys" }.missingSince)
    }

    @Test
    fun finished_is_the_last_part_within_its_last_five_minutes() {
        val rows = build((1..3).map { part(it, minutes = 20) })
        val parts = rows.parts
        assertTrue(AudiobookRules.isFinished(parts, 2, 16 * 60_000L))
        assertTrue(!AudiobookRules.isFinished(parts, 2, 10 * 60_000L))
        assertTrue(!AudiobookRules.isFinished(parts, 1, 19 * 60_000L))
        assertEquals(45 * 60_000L, AudiobookRules.bookPosition(parts, 2, 5 * 60_000L))
    }
}
