package dev.jellystructure.ravilo.ui.desktop

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R328 (FR-R328-9) — what must reach the server before the process ends: a film's or a song's stop (phase 180).
 * Quitting runs every registered step together, bounded, so a server that does not answer never holds the quit.
 *
 * R372 (FR-R372-11's desktop companion, amendment 2026-10-12) — ⌘Q was the only way in. A log-out, a shutdown or a
 * plain `SIGTERM` reported nothing, so the song stayed *playing* in *Playing now* until the watchdog found it. The JVM
 * shutdown hook ([installShutdownHook]) is the second way in, and [runAll] runs **once** either way: with no latch the
 * hook would re-post every step after an ordinary ⌘Q had already sent them.
 */
object DesktopShutdown {
    private val steps = CopyOnWriteArrayList<suspend () -> Unit>()
    private val ran = AtomicBoolean(false)
    private val hookInstalled = AtomicBoolean(false)

    fun register(step: suspend () -> Unit): () -> Unit {
        steps += step
        return { steps -= step }
    }

    fun runAll(timeoutMs: Long = 2_000) {
        if (steps.isEmpty()) return
        if (!ran.compareAndSet(false, true)) return
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                steps.map { step -> async { runCatching { step() } } }.awaitAll()
            }
        }
    }

    /**
     * FR-R372-11's desktop companion — the same two steps ⌘Q takes (the tracked reports two seconds to land, then
     * every registered step), on any end the JVM gets a hook for: a log-out, a session shutdown, `SIGTERM`, `SIGINT`.
     * A Force Quit (`SIGKILL`) has no hook and stays the server's job — an abrupt socket end is read as *the place is
     * gone*. Idempotent, and ordinary ⌘Q is unchanged: whichever path gets there first is the one that posts.
     */
    fun installShutdownHook() {
        if (!hookInstalled.compareAndSet(false, true)) return
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching {
                runBlocking { withTimeoutOrNull(2_000) { dev.jellystructure.ravilo.ui.TeardownWork.awaitAll() } }
            }
            runCatching { runAll() }
        }, "ravilo-shutdown"))
    }
}
