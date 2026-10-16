package dev.jellystructure.ravilo.ui.seams

/**
 * R292 (FR-R292-14, DEB-569) — after a standby the film comes back paused (FR-R292-8a), and for a short window a
 * **play** that reaches the player through its Media3 session is refused: that session is the one Cast Connect drives,
 * and a sender's (or the Cast SDK's) resume arrives on it. Pure, so the decision is tested without a device; the clock
 * is passed in. Not covered, by design: the viewer's own play on the TV (it acts on the player, not on the session), a
 * pause, and a new cast ([released] — a deliberate LOAD plays at once).
 */
class StandbyPlayHold {
    private var armedAtMs: Long? = null

    /** A return marked `afterStandby` (FR-R292-8a): hold plays from the session for [WINDOW_MS]. */
    fun arm(nowMs: Long) { armedAtMs = nowMs }

    /** A new cast was accepted: whatever plays next is wanted. */
    fun released() { armedAtMs = null }

    /** True when a play from the session at [nowMs] is to be refused. A window that ran out is spent. */
    fun refusesPlay(nowMs: Long): Boolean {
        val at = armedAtMs ?: return false
        if (nowMs - at in 0 until WINDOW_MS) return true
        armedAtMs = null
        return false
    }

    companion object {
        const val WINDOW_MS = 15_000L
    }
}
