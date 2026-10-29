package dev.jellystructure.ravilo.ui.components

/** DEB-2686 (FR-1, FR-3) — a Profile tap is either a first tap or the second of a double tap. */
class DoubleTapGate(private val timeoutMs: Long) {
    private var firstAt: Long? = null

    /** True when the tap at [nowMs] completes a double tap; the gate is then empty again (FR-3). */
    fun tap(nowMs: Long): Boolean {
        val first = firstAt
        return if (first != null && nowMs - first <= timeoutMs) {
            firstAt = null
            true
        } else {
            firstAt = nowMs
            false
        }
    }

    /** FR-3 — a tap on any other item breaks the pair. */
    fun reset() { firstAt = null }
}
