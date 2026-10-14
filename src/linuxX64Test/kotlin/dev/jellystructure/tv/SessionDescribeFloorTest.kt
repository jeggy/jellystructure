package dev.jellystructure.tv

import dev.jellystructure.audiobooks.AudiobooksRows
import dev.jellystructure.audiobooks.AudiobooksStore
import dev.jellystructure.db.createDatabase
import dev.jellystructure.model.Audiobook
import dev.jellystructure.model.AudiobookPart
import kotlinx.coroutines.runBlocking
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R368 (FR-R368-12(4), DEB-1054) — the floor under [PlaybackSessions.describe]: a part id **alone** describes as an
 * `audiobook` with the book's words, so no caller — today's or a later one — can mint a nameless `film` from a part.
 * A part is never a film. Prod carried three such rows, each with `kind = film`, `book_id` NULL and a bare item id
 * where a title should be.
 */
class SessionDescribeFloorTest {
    private val base = "/tmp/jellystructure-test-describe-floor-${getpid()}"
    private lateinit var store: AudiobooksStore

    @BeforeTest fun setUp() = runBlocking {
        runCatching { platform.posix.remove("$base.db") }
        store = AudiobooksStore(createDatabase("$base.db"))
        val book = Audiobook(id = "F1", libraryId = "lib", title = "Vinterfærgen", authors = listOf("Ingrid Lykke", "Ada Zeeman"),
            durationMs = 3 * 20 * 60_000L, partCount = 3, addedAt = 10, coverState = dev.jellystructure.model.MusicArt.JELLYFIN)
        val parts = (0..2).map { AudiobookPart(id = "p$it", bookId = "F1", number = it + 1, position = it, durationMs = 20 * 60_000L) } +
            AudiobookPart(id = "gone", bookId = "F1", number = 4, position = 3, durationMs = 60_000L, missingSince = 5)
        store.replaceLibrary(AudiobooksRows("lib", listOf(book), parts, emptyList(), 0))
        Unit
    }

    @AfterTest fun tearDown() {
        dev.jellystructure.db.closeLastDatabaseForTests()
        for (suffix in listOf(".db", ".db-wal", ".db-shm")) runCatching { platform.posix.remove("$base$suffix") }
    }

    @Test fun `a part id with no book id describes as the book`() {
        val (kind, item) = assertNotNull(describeAudiobookPart(store, "p1", null))
        assertEquals(SessionKind.AUDIOBOOK, kind, "a part is never a film")
        assertEquals("p1", item.id, "the item stays the part that plays")
        assertEquals("Vinterfærgen", item.title)
        assertEquals("Ingrid Lykke, Ada Zeeman", item.subtitle)
        assertEquals("audio", sessionLane(kind))
        assertEquals(3 * 20 * 60_000L, item.durationMs)
        assertEquals("/api/tv/image/audiobook/F1?v=0", item.artwork, "the cover R380 FR-R380-12 asks for")
    }

    @Test fun `the book id is still used when the caller knows it`() {
        val (kind, item) = assertNotNull(describeAudiobookPart(store, "p2", "F1"))
        assertEquals(SessionKind.AUDIOBOOK, kind)
        assertEquals("Vinterfærgen", item.title)
    }

    @Test fun `an item that is no part of any book is left to the other resolvers`() {
        assertNull(describeAudiobookPart(store, "some-film", null))
        assertNull(describeAudiobookPart(null, "p1", null), "no audiobooks at all: nothing claimed")
    }

    @Test fun `a part the scan has lost is not a part`() {
        // partsByBook holds live parts only, and the reverse lookup is read off it.
        assertNull(store.bookOfPart("gone"))
        assertNull(describeAudiobookPart(store, "gone", null))
    }

    @Test fun `a book id the store does not know still describes as an audiobook`() {
        val (kind, item) = assertNotNull(describeAudiobookPart(store, "p0", "no-such-book"))
        assertEquals(SessionKind.AUDIOBOOK, kind)
        assertNull(item.title)
    }
}
