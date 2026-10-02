package dev.jellystructure.ravilo.ui.seams

/** R356 (FR-R356-6) — what the sender does about a receiver it has not heard from. */
enum class CastSilenceAction { NONE, ASK, REJOIN }

/**
 * R356 (FR-R356-6) — "connected but silent". A Cast session can read connected while nothing from the receiver reaches
 * the app any more: Play services drops an app whose binder buffer filled while Android had it frozen (the Pixel 9,
 * 2026-10-02 — *Disposing ConnectedClient*), and the app's session object never learns of it.
 *
 * The receiver speaks on change, not on a clock, so silence means something only when an answer is due:
 * - [nudge] with `askNow` — the app came on screen: ask at once (the platform sends `requestStatus` and Ravilo's
 *   `status`), and expect an answer within [rejoinAfterMs];
 * - [nudge] without — a command was sent: the receiver answers a state change at once; if nothing is heard within
 *   [askAfterMs], ask.
 * Anything heard ([heard]) settles it. Still nothing [rejoinAfterMs] after asking ⇒ [CastSilenceAction.REJOIN], once per
 * nudge. Times are the caller's monotonic milliseconds; [tick] is called about twice a second while a nudge is open.
 */
class CastSilenceWatch(
    private val askAfterMs: Long = CAST_ASK_AFTER_MS,
    private val rejoinAfterMs: Long = CAST_REJOIN_AFTER_MS,
) {
    private var nudgedAt: Long? = null
    private var askedAt: Long? = null

    /** A nudge is open: an answer is due. */
    val waiting: Boolean get() = nudgedAt != null

    /** [askNow] (the app came on screen) returns [CastSilenceAction.ASK] for the caller to send at once. */
    fun nudge(nowMs: Long, askNow: Boolean): CastSilenceAction {
        if (nudgedAt == null) nudgedAt = nowMs   // a second command does not postpone the first one's answer
        if (askNow && askedAt == null) { askedAt = nowMs; return CastSilenceAction.ASK }
        return CastSilenceAction.NONE
    }

    /** The receiver said something (a media status or a message on Ravilo's channel). */
    fun heard() { nudgedAt = null; askedAt = null }

    /** Forget any open nudge (the session ended, or a rejoin is under way). */
    fun reset() = heard()

    fun tick(nowMs: Long, connected: Boolean): CastSilenceAction {
        val nudged = nudgedAt ?: return CastSilenceAction.NONE
        if (!connected) { reset(); return CastSilenceAction.NONE }
        val asked = askedAt
        if (asked == null) {
            if (nowMs - nudged < askAfterMs) return CastSilenceAction.NONE
            askedAt = nowMs
            return CastSilenceAction.ASK
        }
        if (nowMs - asked < rejoinAfterMs) return CastSilenceAction.NONE
        reset()
        return CastSilenceAction.REJOIN
    }
}

/** R356 — how long after a command the receiver's answer is due before the sender asks for it. */
const val CAST_ASK_AFTER_MS = 3_000L
/** R356 — how long after asking the sender waits before it rejoins the running receiver. */
const val CAST_REJOIN_AFTER_MS = 3_000L
/** R356 — a rejoin that has not connected by then ends the session the ordinary way. */
const val CAST_REJOIN_GIVE_UP_MS = 15_000L
