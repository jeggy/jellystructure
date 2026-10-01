package dev.jellystructure.ops

/**
 * Phase 294 (FR-294-1) — a map that request threads may share. Kotlin/Native's `HashMap` is not thread-safe: two
 * threads inserting at once can corrupt it mid-rehash, and the next insert throws *"This cannot happen with fixed magic
 * multiplier and grow-only hash array. Have object hashCodes changed?"* — which is what a TV sign-in hit on
 * 2026-10-02 (`DeviceIdentityRegistry` and `RaviloDeviceService.tokenCache`, written by every authenticated request).
 *
 * Every operation runs under one [SpinLock] (usable from suspend and plain code alike). Lambdas passed to [removeIf] and
 * [getOrPut] run inside the lock, so they must be short and pure — never I/O, never a suspension point.
 */
class LockedMap<K, V> {
    private val lock = SpinLock()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = lock.withLock { map[key] }

    operator fun set(key: K, value: V) { lock.withLock { map[key] = value } }

    fun remove(key: K): V? = lock.withLock { map.remove(key) }

    /** Removes every entry [predicate] accepts; returns the removed values. */
    fun removeIf(predicate: (K, V) -> Boolean): List<V> = lock.withLock {
        val gone = ArrayList<V>()
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (predicate(e.key, e.value)) { gone += e.value; it.remove() }
        }
        gone
    }

    /** The value for [key], or [make]'s value stored under it (one atomic step). */
    fun getOrPut(key: K, make: () -> V): V = lock.withLock { map.getOrPut(key, make) }

    /** Replaces the value for [key] with [transform]'s answer for the current one (null = absent); null removes it. */
    fun update(key: K, transform: (V?) -> V?): V? = lock.withLock {
        val next = transform(map[key])
        if (next == null) map.remove(key) else map[key] = next
        next
    }

    /** A copy, safe to iterate without the lock. */
    fun snapshot(): Map<K, V> = lock.withLock { HashMap(map) }

    val size: Int get() = lock.withLock { map.size }

    fun clear() { lock.withLock { map.clear() } }
}
