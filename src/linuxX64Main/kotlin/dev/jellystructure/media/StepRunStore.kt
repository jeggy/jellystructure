package dev.jellystructure.media

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.nowEpochSec

/**
 * Phase 261 (FR-261-9, dev review item 7) — the last run of each pipeline step for each title. Written from
 * two places only: the step pool's per-item wrapper (PipelineEngine) and, for the enqueue-only steps, the
 * queue when their job finishes (MediaJobQueue.runClaimed). Never throws into the caller: losing one
 * bookkeeping row must not fail a step.
 */
class StepRunStore(private val db: JellystructureDb) {
    fun record(itemId: String, step: String, outcome: String, detail: String? = null) {
        // Swallowed, not logged: this is called from the step pool's non-suspending failure callback, and
        // Logger is suspending. A lost row reads as an older "last run" on the card, nothing worse.
        runCatching { db.itemStepRunQueries.put(itemId, step, nowEpochSec(), outcome, detail?.take(300)) }
    }

    fun forItem(itemId: String): Map<String, dev.jellystructure.db.Item_step_run> =
        db.itemStepRunQueries.forItem(itemId).executeAsList().associateBy { it.step }

    companion object {
        const val OK = "ok"
        const val CHANGED = "changed"
        const val SKIPPED = "skipped"
        const val FAILED = "failed"
    }
}
