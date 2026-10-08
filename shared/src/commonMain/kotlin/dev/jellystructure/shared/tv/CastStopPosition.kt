package dev.jellystructure.shared.tv

/**
 * 312 / 313 (2026-10-09) — the position a cast receiver reports a stop at. Found live: the receiver reported its stops at
 * 0 ms, wiping the viewer's place (312's class). CAF sends a last `TIME_UPDATE` with no (or a zero) media time once the
 * player has stopped or gone idle, and that update overwrote the real position just before the stop was sent.
 *
 * A time update moves the position only when it carries a real time: [reportedMs] null (no media time) never does, and a
 * zero only when nothing has played yet ([lastKnownMs] is 0) — a jump back to the very start after a real position is
 * the idle player's reset, not the viewer's. A deliberate seek to 0:00 still lands: the next update carries a small time.
 */
fun castPositionAfterUpdate(lastKnownMs: Long, reportedMs: Long?): Long = when {
    reportedMs == null -> lastKnownMs
    reportedMs <= 0L && lastKnownMs > 0L -> lastKnownMs
    else -> reportedMs
}
