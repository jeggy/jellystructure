package dev.jellystructure.ravilo.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll

/**
 * R328 (FR-R328-9) — the reports a closing app must let finish: a film's stop (phase 180) reaches the server on a
 * scope that outlives the screen, and a desktop that quits ends that scope with the process. So the stop registers
 * here, and the Mac's quit waits for what is registered, bounded, before it exits. Everywhere else this is unread.
 */
object TeardownWork {
    private val jobs = MutableStateFlow<List<Job>>(emptyList())

    fun track(job: Job) {
        jobs.update { current -> current.filter { it.isActive } + job }
    }

    /** Suspends until every registered report has finished; callers bound it with a timeout. */
    suspend fun awaitAll() = jobs.value.joinAll()
}
