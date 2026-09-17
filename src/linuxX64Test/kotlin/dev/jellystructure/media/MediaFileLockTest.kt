package dev.jellystructure.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Phase 234. */
class MediaFileLockTest {
    @Test
    fun bothBrokenLayoutsNeedRepairAndNothingElseDoes() {
        assertEquals(setOf(MkvLayout.TRACKS_AFTER_CLUSTER, MkvLayout.ELEMENT_SIZE_OVERFLOW), MkvLayout.entries.filter { it.needsRepair }.toSet())
        assertTrue("DISCARDS" in MkvLayout.ELEMENT_SIZE_OVERFLOW.repairSentence("/f.mkv"))
        assertFalse("Tracks" in MkvLayout.ELEMENT_SIZE_OVERFLOW.repairSentence("/f.mkv"))
    }

    @Test
    fun twoWritersOfOneFileNeverOverlap(): Unit = runBlocking {
        val inside = AtomicInt(0); val maxInside = AtomicInt(0)
        List(8) {
            async(Dispatchers.Default) {
                MediaFileLock.withLock<Unit>("/same.mkv") {
                    val now = inside.incrementAndGet()
                    while (true) { val m = maxInside.value; if (now <= m || maxInside.compareAndSet(m, now)) break }
                    delay(10)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(1, maxInside.value)
        assertEquals(0, MediaFileLock.activePaths())
    }

    @Test
    fun differentFilesDoNotWaitOnEachOther(): Unit = runBlocking {
        val inside = AtomicInt(0); val maxInside = AtomicInt(0)
        List(4) { n ->
            async(Dispatchers.Default) {
                MediaFileLock.withLock<Unit>("/file-$n.mkv") {
                    val now = inside.incrementAndGet()
                    while (true) { val m = maxInside.value; if (now <= m || maxInside.compareAndSet(m, now)) break }
                    delay(200)
                    inside.decrementAndGet()
                }
            }
        }.awaitAll()
        assertTrue(maxInside.value > 1, "four different files ran one at a time")
    }

    @Test
    fun aFailingBlockReleasesTheLock(): Unit = runBlocking {
        runCatching { MediaFileLock.withLock<Unit>("/boom.mkv") { error("boom") } }
        assertEquals("ok", MediaFileLock.withLock("/boom.mkv") { "ok" })
        assertEquals(0, MediaFileLock.activePaths())
    }
}
