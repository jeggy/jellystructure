package dev.jellystructure.ravilo.ui.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4070 (FR-12) — downloads are keyed per user id from the start. */
class DownloadLibraryTest {
    private fun rec(id: String, bytes: Long = 100L, queuedAt: Long = 0L, state: RecordState = RecordState.QUEUED) =
        DownloadRecord(itemId = id, kind = "movie", title = "T$id", quality = "original", bytes = bytes, queuedAt = queuedAt, state = state)

    @Test fun `another profile never sees a viewer's records`() {
        val lib = DownloadLibrary(InMemoryDownloadIndexStorage())
        lib.upsert("alice", rec("a1"))
        lib.upsert("bob", rec("b1"))
        assertEquals(listOf("a1"), lib.records("alice").map { it.itemId })
        assertEquals(listOf("b1"), lib.records("bob").map { it.itemId })
        assertNull(lib.get("bob", "a1"))
        assertTrue(lib.records("carol").isEmpty())
    }

    @Test fun `a viewer's total never includes another profile's, which is counted apart and never named`() {
        val lib = DownloadLibrary(InMemoryDownloadIndexStorage())
        lib.upsert("alice", rec("a1", 300L)); lib.upsert("alice", rec("a2", 200L)); lib.upsert("bob", rec("b1", 1000L))
        assertEquals(500L, lib.bytes("alice"))
        assertEquals(1000L, lib.otherUsersBytes("alice"))
        assertEquals(500L, lib.otherUsersBytes("bob"))
    }

    @Test fun `the same user signing back in finds the records, because nothing sign-out does touches them`() {
        val storage = InMemoryDownloadIndexStorage()
        DownloadLibrary(storage).upsert("alice", rec("a1"))
        assertEquals(1, DownloadLibrary(storage).records("alice").size)   // a new library over the same files
    }

    @Test fun `upsert replaces by item and remove drops one and then the empty index`() {
        val storage = InMemoryDownloadIndexStorage()
        val lib = DownloadLibrary(storage)
        lib.upsert("alice", rec("a1", 1L)); lib.upsert("alice", rec("a1", 2L)); lib.upsert("alice", rec("a2"))
        assertEquals(2L, lib.get("alice", "a1")?.bytes)
        lib.remove("alice", "a1"); lib.remove("alice", "a2")
        assertTrue(storage.users().isEmpty())
    }

    @Test fun `the oldest waiting record is next, and a started one is not waiting`() {
        val lib = DownloadLibrary(InMemoryDownloadIndexStorage())
        lib.upsert("alice", rec("late", queuedAt = 20)); lib.upsert("alice", rec("early", queuedAt = 10)); lib.upsert("alice", rec("busy", queuedAt = 1, state = RecordState.STARTED))
        assertEquals("early", lib.nextQueued("alice")?.itemId)
    }

    @Test fun `a download id carries the user so two profiles never share a key`() {
        val id = downloadId("alice", "item|odd")
        assertEquals("alice", downloadUserId(id))
        assertEquals("item|odd", downloadItemId(id))
    }
}
