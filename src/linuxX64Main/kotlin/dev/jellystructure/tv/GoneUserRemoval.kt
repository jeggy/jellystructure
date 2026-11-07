package dev.jellystructure.tv

import dev.jellystructure.ai.AiRequests
import dev.jellystructure.auth.SessionService
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.log.Logger

/**
 * DEB-2675 (DEB-2672, FR-4, FR-5, FR-6, FR-11, FR-12) — what happens to a Jellyfin user who is gone ([DevicePolicyReconciler] decides
 * that, on two complete reads in a row).
 *
 * One transaction per user. The user is signed out exactly as *Sign out everywhere* does it (`deleteAllForUser` +
 * `revokeAllForUser`), their API keys are revoked (the rows stay), their unredeemed grants are deleted, and the rows that exist
 * only to serve that viewer go with them. **Kept** — the operator's record, not the viewer's state: `playback_session`,
 * `support_ticket`, `support_ai_turn`, `suggestion_dismissal`, `acquisition`, `request_intent`.
 *
 * Every read and delete matches `replace(lower(<column>), '-', '') = :key` (FR-12), so a row stored as 32 hex digits, dashed
 * lowercase or dashed uppercase is found by any of them. The step cannot be undone, so it refuses anything that is not exactly
 * 32 hex digits once normalised (FR-11): `__global__`, `household` and "" are not users.
 */
class GoneUserRemoval(
    private val db: JellystructureDb,
    private val deviceService: RaviloDeviceService,
    private val sessionService: SessionService,
    /** The in-memory caches that hold this user: the playstate map and the Continue Watching list. Called with the normalised id. */
    private val dropCaches: (normalisedUserId: String) -> Unit = {},
    private val clock: () -> Long,
) {
    /** FR-6 — what the Users & devices page lists for seven days; in memory, so a restart empties it. */
    data class Removed(val username: String, val removedAt: Long, val deviceCount: Int)

    /** What one removal did: the counts the Activity line carries. */
    data class Outcome(val username: String, val devices: Int, val sessions: Int, val apiKeys: Int)

    private val removed = dev.jellystructure.ops.LockedMap<String, Removed>()

    /** FR-6 — users removed in the last [REMOVED_KEEP_MS]; newest first. Only a user the page would have listed is in it. */
    fun removedUsers(): List<Removed> {
        val cutoff = clock() - REMOVED_KEEP_MS
        return removed.snapshot().values.filter { it.removedAt >= cutoff }.sortedByDescending { it.removedAt }
    }

    /** FR-6 — the name of a user with no device row: the first of their session, key and playback owner that has one. */
    fun nameFromData(normalisedUserId: String): String? =
        db.userRemovalQueries.sessionNameFor(normalisedUserId).executeAsOneOrNull()
            ?: db.userRemovalQueries.apiKeyNameFor(normalisedUserId).executeAsOneOrNull()
            ?: db.userRemovalQueries.playbackOwnerNameFor(normalisedUserId).executeAsOneOrNull()

    /** Every id the data tables hold rows for, normalised — FR-10's [DevicePolicyReconciler] `candidates`. */
    fun candidateIds(): List<String> = db.userRemovalQueries.candidateIds().executeAsList().mapNotNull { it.id }

    /** Runs FR-4 and FR-5 for [userId], then writes FR-6's Activity line. Throws, and changes nothing, for an id that is not a user (FR-11). */
    suspend fun remove(userId: String): Outcome {
        val key = normalizeGuid(userId)
        require(DevicePolicyReconciler.isUserId(key)) { "refusing to remove '$userId': not a Jellyfin user id" }
        val q = db.userRemovalQueries
        val name = deviceService.listByUserAnyForm(key).firstOrNull()?.jellyfinUsername?.takeIf { it.isNotBlank() }
            ?: nameFromData(key)?.takeIf { it.isNotBlank() }
            ?: DevicePolicyReconciler.anOldAccount(key)
        var devices = 0
        var sessions = 0
        var keys = 0
        db.transaction {
            devices = deviceService.deleteAllForUser(key)
            sessions = sessionService.revokeAllForUser(key)
            keys = q.liveKeyCountFor(key).executeAsOne().toInt()
            q.revokeKeysFor(key)
            q.deleteUnredeemedHandoffsFor(key)
            q.deletePairingsFor(key)
            q.deleteApprovedSigninCodesFor(key)
            q.deleteBehaviourFor(key)
            q.deleteConfigFor(key)
            q.deleteRecommendationsFor(key)
            db.aiQueries.deleteOrderForUser(key)
            db.aiQueries.deleteQueuedForViewer(AiRequests.RERANK_JOB, key)
            q.deleteSuggestionViewerFor(key)
            q.deleteAudiobookProgressFor(key)
            q.deleteAudiobookBookmarksFor(key)
            q.deleteDashboardVisitFor(key)
            q.deleteOutboxFor(key)
            q.deleteAiViewerFor(key)
        }
        runCatching { dropCaches(key) }
        val outcome = Outcome(name, devices, sessions, keys)
        if (devices > 0 || sessions > 0 || keys > 0) {
            Logger.info("Signed out $name: no longer in Jellyfin. Removed $devices devices, $sessions admin sessions, $keys API keys.", "auth")
        } else {
            Logger.info("Removed the data of $name: no longer in Jellyfin.", "auth")
        }
        // The page has only ever listed a user with a device or an admin session; a user with data alone stays off it.
        if (devices > 0 || sessions > 0) removed[key] = Removed(name, clock(), devices)
        return outcome
    }

    companion object {
        const val REMOVED_KEEP_MS = 7L * 24 * 3_600_000L
    }
}
