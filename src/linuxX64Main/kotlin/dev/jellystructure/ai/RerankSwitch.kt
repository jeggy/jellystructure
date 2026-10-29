package dev.jellystructure.ai

import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.suggestions.SuggestionEngine

/**
 * DEB-2826 (DEB-2823, FR-1) — whether the AI re-rank is on for a Jellyfin user. A stored choice (`ai_viewer.rerank`) wins;
 * a user with none is on, unless [SuggestionEngine.isTestAccount] matches their name (FR-1.2, FR-1.6). Stateless over the
 * database, so the recommendation service and the AI runner each hold their own and always agree.
 */
class RerankSwitch(private val db: JellystructureDb) {
    /** The stored choice: true, false, or null when the user has none. */
    fun explicit(userId: String): Boolean? = db.aiQueries.viewerRow(key(userId)).executeAsOneOrNull()?.rerank?.let { it != 0L }

    fun isOn(userId: String, name: String): Boolean = explicit(userId) ?: !SuggestionEngine.isTestAccount(name)

    /**
     * Stores the choice. Switching off also deletes the user's waiting re-rank request and their stored AI order, in the same
     * transaction (FR-1.3); a batch already out is left alone and its answer is dropped when it is read.
     */
    fun set(userId: String, on: Boolean, by: String, now: Long) {
        val k = key(userId)
        db.transaction {
            val prev = db.aiQueries.viewerRow(k).executeAsOneOrNull()
            db.aiQueries.putViewer(k, if (on) 1L else 0L, by, now, prev?.last_asked_at)
            if (!on) {
                db.aiQueries.deleteQueuedForViewer(AiRequests.RERANK_JOB, k)
                db.aiQueries.deleteOrderForUser(k)
            }
        }
    }

    /** FR-1.8 — when a first-build re-rank was last asked for [userId], epoch seconds; 0 when never. Survives a restart. */
    fun lastAskedAt(userId: String): Long = db.aiQueries.viewerRow(key(userId)).executeAsOneOrNull()?.last_asked_at ?: 0L

    /** FR-1.8 — true, and records [now], when nothing was asked for [userId] in the last [withinSec]; false otherwise. */
    fun claimAsk(userId: String, now: Long, withinSec: Long): Boolean {
        val k = key(userId)
        var claimed = false
        db.transaction {
            val prev = db.aiQueries.viewerRow(k).executeAsOneOrNull()
            if (now - (prev?.last_asked_at ?: 0L) > withinSec) {
                db.aiQueries.putViewer(k, prev?.rerank, prev?.set_by, prev?.set_at, now)
                claimed = true
            }
        }
        return claimed
    }

    /** When the user's AI order was written, epoch seconds, or null when they have none. */
    fun orderedAt(userId: String): Long? = db.aiQueries.lastOrderAt(key(userId)).executeAsOne().at

    /** FR-1.5 — a row for a user Jellyfin no longer has: gone. */
    fun forget(userId: String) = db.aiQueries.deleteViewer(key(userId))

    fun stored(): Map<String, dev.jellystructure.db.Ai_viewer> = db.aiQueries.allViewers().executeAsList().associateBy { it.user_id }

    companion object {
        /** The form `ai_viewer.user_id` is stored in: lowercase, no dashes (a Jellyfin id in either form is the same user). */
        fun key(userId: String): String = userId.lowercase().replace("-", "")
    }
}
