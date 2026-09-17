package dev.jellystructure.media

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Phase 234 (FR-234-2) — one file, one writer. A keyed, in-process mutex held around every ffmpeg remux
 * and every `mkvpropedit` invocation. Every remux of a file writes the same fixed `.jstmp_<name>` (Phase
 * 109's cancel and disk preflight depend on that name) and `mkvpropedit` edits in place, so two writers
 * on one path is the 2026-09-13 race: 66 production files corrupt mid-file. The media job queue runs one
 * job at a time, but it is not the only writer — track edits, and the repair their own post-condition
 * starts, run straight from the routes. Different files never wait on each other.
 *
 * Not re-entrant: never call a locking function from inside [withLock] on the same path.
 */
object MediaFileLock {
    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val guard = Mutex()
    private val entries = HashMap<String, Entry>()

    suspend fun <T> withLock(path: String, block: suspend () -> T): T {
        val entry = guard.withLock { entries.getOrPut(path) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                guard.withLock { if (--entry.users == 0) entries.remove(path) }
            }
        }
    }

    /** Paths currently held or waited on — for tests. */
    internal suspend fun activePaths(): Int = guard.withLock { entries.size }
}
