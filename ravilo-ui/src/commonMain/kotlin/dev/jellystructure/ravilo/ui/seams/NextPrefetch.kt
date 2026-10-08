package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.PreparedStream

/**
 * R381 (owner, 2026-10-08) — the next episode of a binge, preloaded at the credits so it starts with no wait.
 *
 * Only a direct play from its start is prefetched (its first ~16 MB and its last ~4 MB, where a Matroska file keeps
 * its index), into a small cache of its own; the player reads the next item from that cache when its stream is the
 * prepared one, and from the network otherwise. A transcoded next episode is not preloaded until 309's early encode or
 * 313's encoder exists (owner). Nothing here is reported anywhere: the stream was prepared with none of a start's side
 * effects (FR-R381-7), and the real start happens when the episode begins.
 *
 * Android only; a no-op elsewhere.
 */
expect object NextPrefetch {
    /** Prefetch [stream] if it is a direct play from 0:00; replaces any earlier prefetch. */
    fun prefetch(stream: PreparedStream)

    /** Drop the prefetch (a seek back out of the credits, the screen left, another item loaded). */
    fun discard()
}
