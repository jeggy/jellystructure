package dev.jellystructure.ravilo.ui.screens

/**
 * DEB-3630 (FR-R218-7) — a start that shows no picture is bounded. The pure rule, so the table is a unit test and the
 * store only acts on its answer. The clock starts when the player is handed the stream, per leg.
 */

/** FR-R218-7 — 10 s with no first frame moves the start once (direct → transcode, our encoder → Jellyfin's stream). */
internal const val START_MOVE_MS = 10_000L
/** FR-R218-7 — 20 s with no first frame on the last route ends on R237's error. */
internal const val START_END_MS = 20_000L
/** FR-R218-7 — `start_failure_status` for "no first frame by the bound" (0 is already "no HTTP response"). */
internal const val START_BOUND_STATUS = -1

internal enum class StartBoundAction { NONE, MOVE, END }

/**
 * @param sinceHandedMs how long ago the player was handed this leg's stream.
 * @param firstFrame the player has rendered a frame of it.
 * @param moved the start already moved once (this leg is the second).
 * @param hasOtherRoute the stream is a direct play or on our encoder; Jellyfin's stream has no other route and goes straight
 *   to the last step.
 */
internal fun startBoundAction(
    sinceHandedMs: Long, firstFrame: Boolean, moved: Boolean, hasOtherRoute: Boolean,
    moveMs: Long = START_MOVE_MS, endMs: Long = START_END_MS,
): StartBoundAction = when {
    firstFrame -> StartBoundAction.NONE
    !moved && hasOtherRoute -> if (sinceHandedMs >= moveMs) StartBoundAction.MOVE else StartBoundAction.NONE
    else -> if (sinceHandedMs >= endMs) StartBoundAction.END else StartBoundAction.NONE
}
