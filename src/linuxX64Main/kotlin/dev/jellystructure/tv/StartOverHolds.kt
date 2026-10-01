package dev.jellystructure.tv

import dev.jellystructure.shared.tv.CardPlayState

/**
 * R343 (FR-R343-13) — what this server knows about a cleared *Start over* episode that Jellyfin does not say yet.
 *
 * After the clear, Jellyfin's own playback session still holds the user data it read at start (the episode was
 * watched) and writes it back on its stop, so until our unwatched write-back has landed Jellyfin answers *played*, and
 * its NextUp names the next episode. A background playstate cycle or Continue rebuild in that window put that answer
 * into the caches, and the series page read *Resume · S01E02* for about three seconds before the write-back's refresh
 * corrected it.
 *
 * A hold is set when the clear runs, follows the session's position, and ends once the write-back's refresh has run
 * (or at once when the stop was itself a finish). While it stands, every read and every push the server makes says
 * the episode is unwatched at that position, and the series' Continue Watching pointer names it. This is the server's
 * own decision (it is about to write exactly this), not a guess on any client. A hold expires on its own after
 * [TTL_MS] without an update, so a lost stop cannot pin a state forever.
 */
object StartOverHolds {
    data class Hold(val seriesJellyfinId: String, val episodeId: String, val positionMs: Long, val durationMs: Long, val touchedAtMs: Long)

    internal const val TTL_MS = 10L * 60_000L

    // userId → episode id → hold. Replaced whole on every write (the same immutable-map pattern as PlaystateCache).
    private var holds: Map<String, Map<String, Hold>> = emptyMap()

    /** Sets or moves the hold for [episodeId]. */
    fun hold(userId: String, seriesJellyfinId: String, episodeId: String, positionMs: Long, durationMs: Long, nowMs: Long = clockMs()) {
        val h = Hold(seriesJellyfinId, episodeId, positionMs.coerceAtLeast(0L), durationMs, nowMs)
        holds = holds + (userId to (holds[userId].orEmpty() + (episodeId to h)))
    }

    /** Moves an existing hold's position (a progress report); does nothing when [episodeId] has none. */
    fun move(userId: String, episodeId: String, positionMs: Long, nowMs: Long = clockMs()) {
        val old = holds[userId]?.get(episodeId) ?: return
        hold(userId, old.seriesJellyfinId, episodeId, positionMs, old.durationMs, nowMs)
    }

    fun release(userId: String, episodeId: String) {
        val mine = holds[userId] ?: return
        if (episodeId !in mine) return
        val rest = mine - episodeId
        holds = if (rest.isEmpty()) holds - userId else holds + (userId to rest)
    }

    fun active(userId: String, nowMs: Long = clockMs()): List<Hold> =
        holds[userId].orEmpty().values.filter { nowMs - it.touchedAtMs < TTL_MS }

    /** The held episodes' state laid over [map] (each held id that [map] carries, or that [askedIds] names); a
     *  favourite flag is kept. Other entries are returned as they are. */
    fun overlay(userId: String, map: Map<String, CardPlayState>, askedIds: Collection<String>? = null, nowMs: Long = clockMs()): Map<String, CardPlayState> {
        val live = active(userId, nowMs)
        if (live.isEmpty()) return map
        val out = map.toMutableMap()
        for (h in live) {
            if (h.episodeId !in map && (askedIds == null || h.episodeId !in askedIds)) continue
            out[h.episodeId] = stateOf(h, map[h.episodeId])
        }
        return out
    }

    /** The series' Continue Watching pointer while a hold stands: series id → held episode id. */
    fun continueEpisodes(userId: String, nowMs: Long = clockMs()): Map<String, String> =
        active(userId, nowMs).associate { it.seriesJellyfinId to it.episodeId }

    internal fun stateOf(h: Hold, old: CardPlayState?): CardPlayState {
        val pct = if (h.durationMs > 0) (h.positionMs.toFloat() / h.durationMs).coerceIn(0f, 1f) else 0f
        return (old ?: CardPlayState()).copy(played = false, resumeMs = h.positionMs, playedPct = pct)
    }

    internal fun clearForTest() { holds = emptyMap() }

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    internal fun clockMs(): Long = platform.posix.time(null) * 1000L
}
