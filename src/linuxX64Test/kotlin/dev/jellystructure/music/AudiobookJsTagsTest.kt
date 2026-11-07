package dev.jellystructure.music

import dev.jellystructure.audiobooks.AudiobooksBrowse
import dev.jellystructure.audiobooks.AudiobooksIngest
import dev.jellystructure.audiobooks.AudiobooksScanner
import dev.jellystructure.audiobooks.AudiobooksStore
import dev.jellystructure.auth.JellyfinAudiobooksLibrary
import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinMusicItem
import dev.jellystructure.auth.JellyfinNameId
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.config.LibraryMapping
import dev.jellystructure.db.createDatabase
import dev.jellystructure.media.JsTag
import dev.jellystructure.media.JsTagStore
import dev.jellystructure.media.MediaHistory
import dev.jellystructure.model.Audiobook
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DEB-3710 (DEB-3701 FR-12) — books carry jellystructure tags exactly as albums do: the same table, the same closed list
 * of writers, FR-7's album rungs applied to the folder and then to the author and title. Acceptance 4, 5 and 8 for books.
 */
class AudiobookJsTagsTest {
    private val base = "/tmp/jellystructure-test-bktags-${getpid()}"
    private val created = ArrayList<String>()
    private var nth = 0

    @AfterTest fun tearDown() { created.forEach { runCatching { platform.posix.remove(it) } } }

    private fun file(name: String): String = "$base-${nth++}-$name".also { created += it; created += "$it-wal"; created += "$it-shm"; runCatching { platform.posix.remove(it) } }

    private val lib = LibraryMapping(jellyfinId = "LIB", name = "Lydbøger", collectionType = "books", jellyfinPath = "/media/books", localPath = "/mnt/books")

    private fun book(id: String, title: String = "Vinterfærgen", folder: String? = "/mnt/books/Ingrid Lykke/$title", authors: List<String> = listOf("Ingrid Lykke"), missing: Long? = null) =
        Audiobook(id = id, libraryId = "LIB", folderPath = folder, title = title, authors = authors, missingSince = missing)

    private class World(val music: MusicStore, val books: AudiobooksStore, val svc: MusicJsTagService, val defs: JsTagStore, val dbFile: String = "")

    private fun world(books: List<Audiobook>, vararg defs: JsTag): World = runBlocking {
        val dbFile = file("db")
        val db = createDatabase(dbFile)
        val music = MusicStore(db)
        val bookStore = AudiobooksStore(db).also { it.replaceLibrary(dev.jellystructure.audiobooks.AudiobooksRows("LIB", books, emptyList(), emptyList(), 0)) }
        val tags = JsTagStore(file("tags.json")).also { s -> defs.forEach { s.create(it) } }
        World(music, bookStore, MusicJsTagService(music, tags, bookStore), tags, dbFile)
    }

    private fun scanner(w: World, history: MediaHistory? = null) =
        AudiobooksScanner(ConfigStore(file("cfg.toml")).also { runBlocking { it.load() } }, JellyfinClient(), w.books, tags = w.music, history = history)

    /** What a books scan does: write the new rows, then carry. */
    private suspend fun scan(w: World, sc: AudiobooksScanner, books: List<Audiobook>) {
        val prev = w.books.snapshot()
        val rows = dev.jellystructure.audiobooks.AudiobooksRows("LIB", books, emptyList(), emptyList(), 0)
        w.books.replaceLibrary(rows)
        sc.carryJsTags(prev, rows)
    }

    private fun tagsOf(w: World, id: String) = w.music.snapshot().jsTagsOf(id)

    // ── storage and the writers (Acceptance 4, 5) ──

    @Test
    fun a_book_tag_is_added_and_removed_by_the_admin_and_survives_a_books_scan_and_an_edit() = runBlocking<Unit> {
        val w = world(listOf(book("f1")), JsTag("Favourite"))
        assertEquals(1, w.svc.add(MusicJsTagRow.BOOK, listOf("f1"), "Favourite"))
        assertEquals(0, w.svc.add(MusicJsTagRow.BOOK, listOf("f1"), "Favourite"), "pressed again, nothing is new")
        val before = w.music.snapshot().jsTags
        fun same(step: String) = assertEquals(before, w.music.snapshot().jsTags, step)

        // A books scan: the same book again, with a changed field, by INSERT OR REPLACE.
        w.books.replaceLibrary(dev.jellystructure.audiobooks.AudiobooksRows("LIB", listOf(book("f1").copy(updatedAt = 99, partCount = 3)), emptyList(), emptyList(), 0)); same("scan")
        // The editor, a Save that writes the files' tags, and a lock go through putBook.
        w.books.putBook(book("f1").copy(title = "Vinterfærgen (lydbog)", tagsWrittenAt = 7, locked = true)); same("edit and write_tags")
        // The book goes away from Jellyfin and comes back.
        w.books.replaceLibrary(dev.jellystructure.audiobooks.AudiobooksRows("LIB", listOf(book("f1", missing = 50)), emptyList(), emptyList(), 0)); same("went missing")
        w.books.replaceLibrary(dev.jellystructure.audiobooks.AudiobooksRows("LIB", listOf(book("f1")), emptyList(), emptyList(), 0)); same("came back")
        // Nothing of this is in the row itself (FR-5): the JSON a scan replaces carries no tag.
        assertTrue("Favourite" !in Json.encodeToString(Audiobook.serializer(), w.books.snapshot().books.getValue("f1")))
        assertEquals(listOf("Favourite"), tagsOf(w, "f1"))
        assertEquals(1, w.svc.remove(MusicJsTagRow.BOOK, listOf("f1"), listOf("Favourite")))
        assertEquals(emptyList(), tagsOf(w, "f1"))
    }

    @Test
    fun a_real_ingest_re_read_leaves_a_books_tag() = runBlocking<Unit> {
        fun item(n: Int) = JellyfinMusicItem(
            id = "p$n", name = "Del $n", type = "AudioBook", path = "/media/books/Ingrid Lykke/Vinterfærgen/Vinterfærgen - 0$n.mp3", parentId = "F1", indexNumber = n,
            runTimeTicks = 25L * 60_000L * 10_000L, album = "Vinterfærgen", albumArtists = listOf(JellyfinNameId("Ingrid Lykke", "x")),
        )
        suspend fun read(w: World, items: List<JellyfinMusicItem>, at: Long) {
            val s = w.books.snapshot()
            w.books.replaceLibrary(AudiobooksIngest.build(lib, JellyfinAudiobooksLibrary(items, ebookCount = 0), s.books, s.parts, s.authors, at, { false }, { emptyList() }))
        }
        val w = world(emptyList(), JsTag("Favourite"))
        read(w, (1..3).map { item(it) }, 1000)
        val id = w.books.snapshot().books.keys.single()
        w.svc.add(MusicJsTagRow.BOOK, listOf(id), "Favourite")
        read(w, (1..4).map { item(it) }, 2000)
        assertEquals(listOf("Favourite"), tagsOf(w, id))
        read(w, emptyList(), 3000)
        assertTrue(w.books.snapshot().books.getValue(id).missingSince != null)
        assertEquals(listOf("Favourite"), tagsOf(w, id), "a book Jellyfin no longer has keeps its tag")
    }

    @Test
    fun a_name_that_is_not_defined_is_400_and_a_music_only_tag_is_409_on_a_book_and_the_other_way_round() = runBlocking<Unit> {
        val w = world(listOf(book("f1")), JsTag("Music only", scopes = listOf("music")), JsTag("Books only", scopes = listOf("audiobooks")))
        assertEquals(400, assertFailsWith<MusicJsTagRefused> { w.svc.add(MusicJsTagRow.BOOK, listOf("f1"), "Nope") }.status)
        val out = assertFailsWith<MusicJsTagRefused> { w.svc.add(MusicJsTagRow.BOOK, listOf("f1"), "Music only") }
        assertEquals(409, out.status); assertEquals("Music only is not used for Audiobooks", out.message)
        assertTrue(w.music.snapshot().jsTags.isEmpty())
        assertEquals(1, w.svc.add(MusicJsTagRow.BOOK, listOf("f1"), "Books only"))
        assertEquals("Books only is not used for Music", assertFailsWith<MusicJsTagRefused> { w.svc.add(MusicJsTagRow.ALBUM, listOf("a1"), "Books only") }.message)
    }

    @Test
    fun a_book_that_is_missing_or_unknown_is_not_tagged_but_a_tag_outside_its_scope_can_always_be_removed() = runBlocking<Unit> {
        val w = world(listOf(book("f1"), book("gone", missing = 5)), JsTag("Favourite"))
        assertEquals(1, w.svc.add(MusicJsTagRow.BOOK, listOf("f1", "gone", "nobody"), "Favourite"))
        assertEquals(emptyList(), tagsOf(w, "gone")); assertEquals(emptyList(), tagsOf(w, "nobody"))
        w.defs.update("Favourite", null, null, listOf("music"))
        assertEquals(1, w.svc.remove(MusicJsTagRow.BOOK, listOf("f1"), listOf("Favourite")), "narrowing never traps a tag on a book")
    }

    // ── the tags page: counts, delete, outside scope (Acceptance 8) ──

    @Test
    fun deleting_a_tag_strips_it_from_books_and_the_cards_count_includes_them() = runBlocking<Unit> {
        val w = world(listOf(book("f1"), book("f2", "Nordlys")), JsTag("Favourite"), JsTag("Other"))
        w.svc.add(MusicJsTagRow.BOOK, listOf("f1", "f2"), "Favourite"); w.svc.add(MusicJsTagRow.BOOK, listOf("f1"), "Other")
        assertEquals(2, w.svc.count("Favourite"))
        assertEquals(2, w.svc.strip("Favourite"))
        assertEquals(0, w.svc.count("Favourite"))
        assertEquals(listOf("Other"), tagsOf(w, "f1")); assertEquals(emptyList(), tagsOf(w, "f2"))
        assertEquals(listOf("f1"), MusicStore(createDatabase(w.dbFile)).snapshot().jsTags.keys.toList(), "and the table on disk agrees")
    }

    @Test
    fun narrowing_away_from_audiobooks_removes_nothing_and_the_explicit_removal_takes_exactly_the_books_outside() = runBlocking<Unit> {
        val w = world(listOf(book("f1"), book("f2", "Nordlys"), book("f3", "Old", missing = 4)), JsTag("Favourite"))
        w.svc.add(MusicJsTagRow.BOOK, listOf("f1", "f2"), "Favourite")
        w.music.addJsTags(MusicJsTagWriter.ADMIN, listOf(MusicJsTagRow("f3", MusicJsTagRow.BOOK, "Favourite", 1)))   // a missing book: FR-8's, not counted
        assertEquals(emptyMap(), w.svc.outsideByGroup("Favourite", emptyList()), "All")
        assertEquals(emptyMap(), w.svc.outsideByGroup("Favourite", listOf("music", "audiobooks")))
        assertEquals(mapOf("audiobooks" to 2), w.svc.outsideByGroup("Favourite", listOf("MOVIE")))
        assertEquals(mapOf("audiobooks" to 2), w.svc.outsideByGroup("Favourite", listOf("music")), "a Music-only tag leaves books outside")
        assertEquals(3, w.music.snapshot().jsTags.size, "nothing was removed by asking")
        assertEquals(2, w.svc.removeOutside("Favourite", listOf("music")))
        assertEquals(listOf("f3"), w.music.snapshot().jsTags.keys.toList(), "exactly the present books went")
    }

    // ── FR-7 for books: the carry ──

    @Test
    fun a_reimported_book_in_the_same_folder_takes_the_tag_and_the_missing_row_keeps_it() = runBlocking<Unit> {
        val w = world(listOf(book("old")), JsTag("Favourite"))
        w.music.addJsTags(MusicJsTagWriter.ADMIN, listOf(MusicJsTagRow("old", MusicJsTagRow.BOOK, "Favourite", 3)))
        scan(w, scanner(w), listOf(book("old", missing = 9), book("new", "Renamed By Tags", folder = book("old").folderPath)))
        assertEquals(listOf("Favourite"), tagsOf(w, "new"))
        assertEquals(listOf("Favourite"), tagsOf(w, "old"), "a carry copies and never moves")
        assertEquals(3L, w.music.snapshot().jsTags["new"]!!.single().setAt)
    }

    @Test
    fun a_book_whose_folder_is_renamed_keeps_its_tag_through_the_author_and_title_rung() = runBlocking<Unit> {
        val w = world(listOf(book("old")), JsTag("Favourite"))
        w.music.addJsTags(MusicJsTagWriter.ADMIN, listOf(MusicJsTagRow("old", MusicJsTagRow.BOOK, "Favourite", 3)))
        // Jellyfin derives the folder's id from its path: a rename gives a new id and a new folder, the same title and author.
        scan(w, scanner(w), listOf(book("old", missing = 9), book("new", "VINTERFÆRGEN", folder = "/mnt/books/Ingrid Lykke/Vinterfærgen (lydbog)", authors = listOf("ingrid lykke"))))
        assertEquals(listOf("Favourite"), tagsOf(w, "new"), "the same author and case-folded title")
        // Another author is another book.
        val v = world(listOf(book("old")), JsTag("Favourite"))
        v.music.addJsTags(MusicJsTagWriter.ADMIN, listOf(MusicJsTagRow("old", MusicJsTagRow.BOOK, "Favourite", 3)))
        scan(v, scanner(v), listOf(book("old", missing = 9), book("other", folder = "/mnt/books/x", authors = listOf("Someone Else"))))
        assertEquals(emptyList(), tagsOf(v, "other"))
    }

    @Test
    fun two_candidates_on_one_rung_carry_nothing_and_fr8_lists_the_book() = runBlocking<Unit> {
        val w = world(listOf(book("old")), JsTag("Favourite"))
        w.music.addJsTags(MusicJsTagWriter.ADMIN, listOf(MusicJsTagRow("old", MusicJsTagRow.BOOK, "Favourite", 3)))
        val f = book("old").folderPath
        scan(w, scanner(w), listOf(book("old", missing = 9), book("n1", "One", folder = f), book("n2", "Two", folder = f)))
        assertEquals(emptyList(), tagsOf(w, "n1")); assertEquals(emptyList(), tagsOf(w, "n2"))
        val gone = w.svc.gone("Favourite").single().second
        assertEquals("old", gone.id); assertEquals(MusicJsTagRow.BOOK, gone.kind); assertEquals("Vinterfærgen", gone.title); assertEquals("Ingrid Lykke", gone.artist)
        // The manual removal is the admin's own writer, and clears the list.
        w.svc.remove(MusicJsTagRow.BOOK, listOf("old"), listOf("Favourite"))
        assertTrue(w.svc.gone("Favourite").isEmpty())
    }

    @Test
    fun the_two_halves_of_a_split_folder_are_not_candidates_for_each_other() = runBlocking<Unit> {
        val folder = "/mnt/books/Ingrid Lykke/Samling"
        val sep = AudiobooksIngest.SPLIT
        val w = world(listOf(book("F1", "Samling", folder), book("F1${sep}nordlys", "Nordlys", folder)), JsTag("Favourite"))
        w.music.addJsTags(MusicJsTagWriter.ADMIN, listOf(MusicJsTagRow("F1${sep}nordlys", MusicJsTagRow.BOOK, "Favourite", 3)))
        // A folder rename gives both halves a new id; the tag's suffix is unchanged, so only the Nordlys half takes the tag.
        scan(w, scanner(w), listOf(
            book("F1", "Samling", folder, missing = 9), book("F1${sep}nordlys", "Nordlys", folder, missing = 9),
            book("F2", "Samling", folder), book("F2${sep}nordlys", "Nordlys", folder),
        ))
        assertEquals(listOf("Favourite"), tagsOf(w, "F2${sep}nordlys")); assertEquals(emptyList(), tagsOf(w, "F2"))
    }

    @Test
    fun a_carry_writes_one_history_line_on_the_new_book_and_a_tag_the_owner_took_off_is_not_put_back() = runBlocking<Unit> {
        val db = createDatabase(file("histdb"))
        val music = MusicStore(db)
        val bookStore = AudiobooksStore(db).also { it.replaceLibrary(dev.jellystructure.audiobooks.AudiobooksRows("LIB", listOf(book("old")), emptyList(), emptyList(), 0)) }
        val defs = JsTagStore(file("t.json")).also { it.create(JsTag("Favourite")); it.create(JsTag("Live")) }
        val w = World(music, bookStore, MusicJsTagService(music, defs, bookStore), defs)
        music.addJsTags(MusicJsTagWriter.ADMIN, listOf(MusicJsTagRow("old", MusicJsTagRow.BOOK, "Favourite", 1), MusicJsTagRow("old", MusicJsTagRow.BOOK, "Live", 2)))
        val history = MediaHistory(db)
        val sc = scanner(w, history)
        val books = listOf(book("old", missing = 9), book("new", folder = book("old").folderPath))
        scan(w, sc, books)
        assertEquals(listOf("Favourite", "Live"), tagsOf(w, "new"))
        assertEquals("tags carried from the replaced book: Favourite, Live", history.forItem("new").single().detail)
        assertEquals(emptyList(), MusicTagCarry.gone(music.snapshot(), bookStore.snapshot()).map { it.subjectId }, "a carried tag is not listed as gone")
        music.removeJsTags(MusicJsTagWriter.ADMIN, listOf("new" to "Favourite"))
        scan(w, sc, books)   // nothing is new or newly missing
        assertEquals(listOf("Live"), tagsOf(w, "new"))
    }

    // ── the Audiobooks Library's Tag facet (FR-12) ──

    @Test
    fun the_tag_facet_matches_a_books_own_tags_and_lists_the_tags_whose_scope_covers_audiobooks() = runBlocking<Unit> {
        val w = world(listOf(book("f1"), book("f2", "Nordlys")), JsTag("Favourite"), JsTag("Books only", scopes = listOf("audiobooks")), JsTag("Films only", scopes = listOf("MOVIE")))
        w.svc.add(MusicJsTagRow.BOOK, listOf("f1"), "Favourite")
        val defined = w.defs.all().associate { it.name to it.scopes }
        val snap = w.books.snapshot()
        fun browse(sel: Map<String, Set<String>>) = AudiobooksBrowse.browse(snap, "audiobooks", sel, null, emptyMap(), { 0 }, tagsOf = { tagsOf(w, it) }, definedTags = defined)
        val facet = browse(emptyMap()).facets.single { it.key == "tag" }
        assertEquals(listOf("Books only", "Favourite"), facet.values.map { it.value }, "Films only is not offered")
        assertEquals(1, facet.values.single { it.value == "Favourite" }.count)
        assertEquals(listOf("f1"), browse(mapOf("tag" to setOf("Favourite"))).books.map { it.id })
        assertEquals(emptyList(), browse(mapOf("tag" to setOf("Books only"))).books.map { it.id })
        // A tag a book still carries stays in the list even when its scope no longer covers books.
        w.defs.update("Favourite", null, null, listOf("MOVIE"))
        val narrowed = AudiobooksBrowse.browse(snap, "audiobooks", emptyMap(), null, emptyMap(), { 0 }, tagsOf = { tagsOf(w, it) }, definedTags = w.defs.all().associate { it.name to it.scopes })
        assertTrue("Favourite" in narrowed.facets.single { it.key == "tag" }.values.map { it.value })
        // Without the arguments the facet is empty and every other answer is as it was.
        assertEquals(emptyList(), AudiobooksBrowse.browse(snap, "audiobooks", emptyMap(), null, emptyMap(), { 0 }).facets.single { it.key == "tag" }.values)
    }
}
