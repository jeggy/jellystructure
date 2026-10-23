package dev.jellystructure.media

/** DEB-2051 FR-2 (amended 2026-10-23; DEB-2275) — a refusal (422 container_cannot_store_language) is not a failure; the result of an applyChanges() call, used to produce the message. */
data class ApplyResult(
    val failed: Boolean,
    val refused: Boolean,
    val othersApplied: Boolean,
    val reorderQueued: Boolean,
) {
    /** The message the admin shows after applyChanges(), or null when no message is needed. */
    fun messageOf(): String? = when {
        failed -> "Some changes failed — see details"
        refused && !othersApplied -> null  // refusal-only: no message (FR-2 amended)
        else -> if (reorderQueued) "Changes applied — reorder queued, see Activity ▸ Jobs" else "Changes applied"
    }
}
