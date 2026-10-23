package dev.jellystructure.media

/**
 * DEB-2275 (FR-11 amendment) — the message the track editor shows after applyChanges(), derived from
 * what happened during the apply. A refusal (422 container_cannot_store_language) is not a failure:
 * the row and notice already say what happened, and direction A's limitation state draws no failure
 * banner.
 *
 * @param failed   true if any non-refusal error occurred (default false)
 * @param refused  true if at least one FR-1 refusal was seen (default false)
 * @param othersApplied true if at least one non-refusal change was applied (default false)
 * @param reorderQueued true if a reorder job was queued (default false)
 *
 * Returns the message string to show, or null when no message is needed (refusal-only, nothing else).
 */
fun ApplyResult(
    failed: Boolean = false,
    refused: Boolean = false,
    othersApplied: Boolean = false,
    reorderQueued: Boolean = false,
): ApplyResult = ApplyResult(failed, refused, othersApplied, reorderQueued)

/** DEB-2275 (FR-11 amendment) — the result of an applyChanges() call, used to produce the message. */
data class ApplyResult(
    val failed: Boolean,
    val refused: Boolean,
    val othersApplied: Boolean,
    val reorderQueued: Boolean,
) {
    /** The message the admin shows after applyChanges(), or null when no message is needed. */
    fun messageOf(): String? = when {
        failed -> "Some changes failed — see details"
        refused && !othersApplied -> null  // refusal-only: no message (FR-11 amendment)
        refused && othersApplied -> if (reorderQueued) "Changes applied — reorder queued, see Activity ▸ Jobs" else "Changes applied"
        else -> if (reorderQueued) "Changes applied — reorder queued, see Activity ▸ Jobs" else "Changes applied"
    }
}
