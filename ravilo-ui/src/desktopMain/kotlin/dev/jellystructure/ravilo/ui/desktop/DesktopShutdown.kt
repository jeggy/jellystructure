package dev.jellystructure.ravilo.ui.desktop

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R328 (FR-R328-9) — what must reach the server before the process ends: a film's or a song's stop (phase 180).
 * Quitting runs every registered step together, bounded, so a server that does not answer never holds the quit.
 */
object DesktopShutdown {
    private val steps = CopyOnWriteArrayList<suspend () -> Unit>()

    fun register(step: suspend () -> Unit): () -> Unit {
        steps += step
        return { steps -= step }
    }

    fun runAll(timeoutMs: Long = 2_000) {
        if (steps.isEmpty()) return
        runBlocking {
            withTimeoutOrNull(timeoutMs) {
                steps.map { step -> async { runCatching { step() } } }.awaitAll()
            }
        }
    }
}
