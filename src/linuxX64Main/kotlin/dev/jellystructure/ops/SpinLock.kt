package dev.jellystructure.ops

import kotlin.concurrent.AtomicInt

/**
 * Phase 182 (FR-182-2) — plain mutual exclusion usable from BOTH suspend and non-suspend call sites.
 * `kotlinx.coroutines.sync.Mutex.withLock` is suspend-only and so cannot guard code that runs
 * synchronously inside SQLDelight's `db.transaction {}` lambda (e.g. `MediaStore.upsertItemDbOnly`) —
 * that gap is exactly why `MediaStore`'s caches and `TmdbClient`'s per-episode caches were, until this
 * phase, plain unsynchronized fields mutated from every scan thread at once. Guarded sections must stay
 * short — a map put/get or a reference swap, never I/O or a suspension point — a contended thread spins
 * rather than parking, and a long critical section here would burn CPU instead of yielding it.
 */
class SpinLock {
    @PublishedApi
    internal val state = AtomicInt(0)

    inline fun <T> withLock(block: () -> T): T {
        while (!state.compareAndSet(0, 1)) { /* spin */ }
        try {
            return block()
        } finally {
            state.value = 0
        }
    }
}
