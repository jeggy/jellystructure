package dev.jellystructure.media

import dev.jellystructure.db.createDatabase
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 195 (FR-195-1/FR-195-4) — the failed-ingest retry set could not drain. Two properties are
 * proven here against a real database, because both live in SQL rather than in Kotlin:
 *
 *  1. A repeat failure actually **increments** `attempt_count` and widens `next_attempt_at`. The old
 *     `markDirty` was a bare `INSERT OR IGNORE`, so a second failure for an id already in the set was a
 *     silent no-op — the counter could never grow, no backoff could ever exist, and every outstanding
 *     id was replayed on every cycle forever.
 *  2. [DirtyItemStore.due] hands back a **bounded, oldest-first** worklist. The unbounded
 *     `all().forEach { enqueue(it) }` it replaces is what saturated the process gate that produced the
 *     failures in the first place: 117 ids at once, each fanning out one ffprobe waiter per episode
 *     file, into 12 background permits with a 30 s deadline.
 */
class DirtyItemBackoffTest {
    private lateinit var dbPath: String
    private lateinit var db: dev.jellystructure.db.JellystructureDb
    private lateinit var store: DirtyItemStore

    private val BASE = 30 * 60_000L   // must match DirtyItemStore.baseBackoffMs
    private val CAP = 24 * 3_600_000L // must match DirtyItemStore.maxBackoffMs

    @BeforeTest
    fun setUp() {
        dbPath = "/tmp/jellystructure-test-dirtyitem-${getpid()}.db"
        db = createDatabase(dbPath)
        store = DirtyItemStore(db)
    }

    @AfterTest
    fun tearDown() {
        for (suffix in listOf("", "-wal", "-shm")) {
            runCatching { platform.posix.remove("$dbPath$suffix") }
        }
    }

    private fun row(jellyfinId: String) =
        db.dirtyItemQueries.all().executeAsList().first { it.jellyfin_id == jellyfinId }

    @Test
    fun `a first failure is due after the base interval rather than immediately`() {
        val now = 1_000_000_000L
        store.markDirty("a", "ingest failed twice", now)
        val r = row("a")
        assertEquals(1L, r.attempt_count)
        assertEquals(now + BASE, r.next_attempt_at)
        assertEquals(emptyList(), store.due(now, 10))
        assertEquals(listOf("a"), store.due(now + BASE, 10))
    }

    @Test
    fun `consecutive failures double the interval and keep the original created_at`() {
        val now = 1_000_000_000L
        store.markDirty("a", "ingest failed twice", now)
        val firstCreated = row("a").created_at

        store.markDirty("a", "ingest failed twice", now + 1)
        assertEquals(2L, row("a").attempt_count)
        assertEquals(now + 1 + 2 * BASE, row("a").next_attempt_at)

        store.markDirty("a", "ingest failed twice", now + 2)
        assertEquals(3L, row("a").attempt_count)
        assertEquals(now + 2 + 4 * BASE, row("a").next_attempt_at)

        // created_at must not move, or "oldest outstanding first" silently becomes "least recently
        // failed first" — and a permanently-broken item would keep jumping the queue ahead of items
        // that have been waiting longer.
        assertEquals(firstCreated, row("a").created_at)
        // Still exactly one row — a repeat failure must never mint a second.
        assertEquals(1L, store.count())
    }

    @Test
    fun `the interval is clamped so a broken item is still retried daily`() {
        val now = 1_000_000_000L
        store.markDirty("a", "boom", now)
        repeat(20) { store.markDirty("a", "boom", now) }
        val r = row("a")
        assertTrue(r.attempt_count > 10, "expected many recorded attempts, got ${r.attempt_count}")
        assertEquals(now + CAP, r.next_attempt_at, "backoff must clamp at the cap, not grow unbounded")
    }

    @Test
    fun `due is capped and returns the longest-outstanding entries first`() {
        val now = 1_000_000_000L
        // Insert newest-first so a naive implementation returning insertion order would fail.
        for (i in listOf(5, 4, 3, 2, 1)) store.markDirty("item$i", "x", now + i.toLong())
        val due = store.due(now + CAP, limit = 3)
        assertEquals(listOf("item1", "item2", "item3"), due)
        assertEquals(5L, store.count())
        assertEquals(5L, store.countDue(now + CAP))
    }

    @Test
    fun `an item whose backoff has not elapsed is excluded from the worklist`() {
        val now = 1_000_000_000L
        store.markDirty("soon", "x", now)
        store.markDirty("later", "x", now)
        repeat(5) { store.markDirty("later", "x", now) }  // pushed well out
        assertEquals(listOf("soon"), store.due(now + BASE, 10))
        assertEquals(1L, store.countDue(now + BASE))
        assertEquals(2L, store.count(), "backed-off items stay outstanding, they are just not due yet")
    }

    @Test
    fun `a successful ingest clears the item and its accumulated backoff`() {
        val now = 1_000_000_000L
        repeat(4) { store.markDirty("a", "x", now) }
        store.clear("a")
        assertEquals(0L, store.count())
        // Re-failing later starts from a clean slate rather than resuming the old backoff.
        store.markDirty("a", "x", now)
        assertEquals(1L, row("a").attempt_count)
    }

    @Test
    fun `oldestCreatedAt reports the backlog age and is null when empty`() {
        assertEquals(null, store.oldestCreatedAt())
        val now = 1_000_000_000L
        store.markDirty("new", "x", now + 500)
        store.markDirty("old", "x", now)
        assertEquals(now, store.oldestCreatedAt())
    }
}
