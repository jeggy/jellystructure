package dev.jellystructure.ravilo.ui.screens

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * DEB-5839 (FR-5) — runs [write] for each key on a background coroutine, never on the caller's thread. A newer value for a
 * key replaces one still pending (latest wins), and a `null` value is a delete that keeps its place in that order, so a
 * save queued before a sign-out cannot bring the file back. Writes run one at a time, so a write-then-rename is never
 * raced by the next one.
 */
internal class LatestWinsWriter<K : Any, V : Any>(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val write: (key: K, value: V?) -> Unit,
) {
    private class Op<K, V>(val key: K, val value: V?)

    private val queue = Channel<Op<K, V>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (first in queue) {
                // Take everything already waiting; the last op per key is the only one that matters.
                val latest = LinkedHashMap<K, Op<K, V>>()
                latest[first.key] = first
                while (true) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    latest.remove(next.key)
                    latest[next.key] = next
                }
                for (op in latest.values) runCatching { write(op.key, op.value) }
            }
        }
    }

    /** Queue [value] for [key]; returns at once. */
    fun submit(key: K, value: V?) {
        queue.trySend(Op(key, value))
    }
}
