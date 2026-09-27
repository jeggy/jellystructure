package dev.jellystructure.server.routes

/**
 * Phase 267 (FR-267-1) — the order of Users & devices, decided here and nowhere else; the page renders the
 * order it gets. Stated rules, not a side effect of a query's `ORDER BY`:
 * - a user's devices: connected first (in use now — `last_seen` is written at most once a minute and only
 *   on a request, so an idle TV with its events socket open can look an hour old), then `last_seen`
 *   newest first, then `created_at` newest first so two loads agree;
 * - a user's admin web sessions: `last_used_at` newest first;
 * - users: most recent activity first (a connected device counts as now), then by name.
 */
internal object OverviewOrder {
    fun <D> devices(list: List<D>, connected: (D) -> Boolean, lastSeen: (D) -> Long, createdAt: (D) -> Long): List<D> =
        list.sortedWith(
            compareByDescending<D> { connected(it) }.thenByDescending { lastSeen(it) }.thenByDescending { createdAt(it) }
        )

    fun <S> sessions(list: List<S>, lastUsedAt: (S) -> Long): List<S> = list.sortedByDescending { lastUsedAt(it) }

    /** A user's most recent activity, in epoch ms: [nowMs] when any device is connected, else the newest of
     *  their devices' `last_seen` and sessions' `last_used_at`; 0 with neither. */
    fun activity(nowMs: Long, anyConnected: Boolean, lastSeens: List<Long>, lastUseds: List<Long>): Long =
        if (anyConnected) nowMs else (lastSeens + lastUseds).maxOrNull() ?: 0L

    fun <U> users(list: List<U>, activity: (U) -> Long, name: (U) -> String): List<U> =
        list.sortedWith(compareByDescending<U> { activity(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { name(it) })
}
