package dev.jellystructure.towo

import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.auth.sha256Hex
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.nowEpochSec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

@Serializable
data class TowoRunner(
    val id: String,
    val name: String,
    val hostLabel: String?,
    val authMode: String?,
    val agentSdkVersion: String?,
    val os: String?,
    val claudeAuthOk: Boolean,
    val allowedRoots: List<String>,
    val createdAt: Long,
    val lastSeenAt: Long?,
)

@Serializable
data class TowoFolder(
    val id: String,
    val runnerId: String,
    val name: String,
    val absPath: String,
    val pinned: Boolean,
    val defaultPermissionProfile: String,
    val discoveredAt: Long,
)

@Serializable
data class TowoSession(
    val id: String,
    val runnerId: String,
    val folderId: String?,
    val folderPath: String?,
    val title: String?,
    val tag: String?,
    val status: String,
    val maxTurns: Long,
    val maxTurnsSource: String,
    val numTurns: Long,
    val continueAfterReset: Boolean,
    val resumeAt: Long?,
    val lastErrorSubtype: String?,
    val createdAt: Long,
    val lastActivityAt: Long,
)

@Serializable
data class TowoPermissionRequest(
    val id: String,
    val sessionId: String,
    val toolName: String,
    val inputJson: String,
    val title: String?,
    val displayName: String?,
    val requestedAt: Long,
    val decidedAt: Long?,
    val decision: String?,
    val reason: String?,
)

@Serializable
data class TowoQuotaStatus(
    val runnerId: String,
    val rateLimitType: String,
    val status: String,
    val resetsAt: Long?,
    val utilization: Double?,
    val observedAt: Long,
)

data class TowoEnrollmentToken(val token: String, val expiresAt: Long)

/** The plaintext credential is returned exactly once, at exchange time — never stored, never
 *  displayed again (spec §B: "A long-lived secret must never appear in a command the user pastes
 *  into a terminal" applies just as much to it appearing anywhere after enrollment succeeds). */
data class TowoRunnerCredential(val runnerId: String, val credential: String)

private const val ENROLLMENT_TTL_SECONDS = 15 * 60L

/**
 * Phase 162 (Towo) — DB access for the control-plane's session index. Thin by design (spec §5):
 * transcript content lives in a SessionStore (build-order step 5), not here. Follows the
 * RaviloDeviceService/ApiKeyStore convention: only a credential HASH is ever persisted, the
 * plaintext is generated once and handed back to the caller to relay, never re-readable afterward.
 */
class TowoStore(private val db: JellystructureDb) {

    // ===== Enrollment (mint short-lived token → exchange for long-lived runner credential) =====

    fun mintEnrollment(name: String): TowoEnrollmentToken {
        val token = "twe_" + generateSecureToken()
        val expiresAt = nowEpochSec() + ENROLLMENT_TTL_SECONDS
        db.towoQueries.insertEnrollment(token = token, name = name, expires_at = expiresAt)
        return TowoEnrollmentToken(token, expiresAt)
    }

    /** Consumes the enrollment token and mints a runner + its long-lived credential. Returns null
     *  if the token is unknown, already consumed, or expired (single-use, per spec §B). */
    fun exchangeEnrollment(token: String): TowoRunnerCredential? {
        val enrollment = db.towoQueries.getEnrollment(token, nowEpochSec()).executeAsOneOrNull() ?: return null
        val runnerId = generateSecureToken()
        val credential = "twr_" + generateSecureToken()
        db.towoQueries.insertRunner(
            id = runnerId,
            name = enrollment.name,
            credential_hash = sha256Hex(credential),
            allowed_roots = "[]",
            created_at = nowEpochSec(),
        )
        db.towoQueries.consumeEnrollment(runner_id = runnerId, token = token)
        return TowoRunnerCredential(runnerId, credential)
    }

    fun deleteExpiredEnrollments() = db.towoQueries.deleteExpiredEnrollments(nowEpochSec())

    // ===== Runners =====

    fun validateRunnerCredential(credential: String): TowoRunner? =
        db.towoQueries.getRunnerByCredentialHash(sha256Hex(credential)).executeAsOneOrNull()?.toModel()

    fun getRunner(id: String): TowoRunner? = db.towoQueries.getRunner(id).executeAsOneOrNull()?.toModel()

    fun allRunners(): List<TowoRunner> = db.towoQueries.allRunners().executeAsList().map { it.toModel() }

    /** Called when a runner's transport connection completes its handshake (spec §B "hello"). */
    fun updateRunnerHello(
        id: String,
        hostLabel: String?,
        authMode: String?,
        agentSdkVersion: String?,
        os: String?,
        claudeAuthOk: Boolean,
        allowedRoots: List<String>,
    ) {
        db.towoQueries.updateRunnerHello(
            host_label = hostLabel,
            auth_mode = authMode,
            agent_sdk_version = agentSdkVersion,
            os = os,
            claude_auth_ok = if (claudeAuthOk) 1L else 0L,
            allowed_roots = json.encodeToString(allowedRoots),
            last_seen_at = nowEpochSec(),
            id = id,
        )
    }

    fun touchRunnerLastSeen(id: String) = db.towoQueries.touchRunnerLastSeen(nowEpochSec(), id)

    fun deleteRunner(id: String) {
        db.towoQueries.deleteFoldersForRunner(id)
        db.towoQueries.deleteRunner(id)
    }

    // ===== Folders (discovered, not declared — spec §B) =====

    /** Stable per (runnerId, absPath) id, so re-reporting the same folder on every hello upserts the
     *  same row rather than duplicating it; pinned/profile/discoveredAt are preserved across re-upserts. */
    fun folderId(runnerId: String, absPath: String): String = sha256Hex("$runnerId|$absPath").take(32)

    fun upsertFolders(runnerId: String, folders: List<Pair<String, String>>) {
        // folders = (name, absPath) pairs, as reported by the runner.
        val now = nowEpochSec()
        for ((name, absPath) in folders) {
            db.towoQueries.upsertFolder(
                id = folderId(runnerId, absPath),
                runner_id = runnerId,
                name = name,
                abs_path = absPath,
                discovered_at = now,
            )
        }
    }

    fun foldersForRunner(runnerId: String): List<TowoFolder> =
        db.towoQueries.foldersForRunner(runnerId).executeAsList().map { it.toModel() }

    fun getFolder(id: String): TowoFolder? = db.towoQueries.getFolder(id).executeAsOneOrNull()?.toModel()

    // ===== Sessions =====

    fun createSession(id: String, runnerId: String, folderId: String?, folderPath: String?, title: String?, maxTurns: Long) {
        val now = nowEpochSec()
        db.towoQueries.insertSession(
            id = id, runner_id = runnerId, folder_id = folderId, folder_path = folderPath, title = title,
            max_turns = maxTurns, created_at = now, last_activity_at = now,
        )
    }

    fun getSession(id: String): TowoSession? = db.towoQueries.getSession(id).executeAsOneOrNull()?.toModel()

    fun allSessions(): List<TowoSession> = db.towoQueries.allSessions().executeAsList().map { it.toModel() }

    fun sessionsForRunner(runnerId: String): List<TowoSession> =
        db.towoQueries.sessionsForRunner(runnerId).executeAsList().map { it.toModel() }

    fun sessionsByStatus(status: String): List<TowoSession> =
        db.towoQueries.sessionsByStatus(status).executeAsList().map { it.toModel() }

    fun updateSessionStatus(id: String, status: String, resumeAt: Long? = null, lastErrorSubtype: String? = null) {
        db.towoQueries.updateSessionStatus(
            status = status, resume_at = resumeAt, last_error_subtype = lastErrorSubtype,
            last_activity_at = nowEpochSec(), id = id,
        )
    }

    fun updateSessionTurns(id: String, numTurns: Long) =
        db.towoQueries.updateSessionTurns(numTurns, nowEpochSec(), id)

    fun updateSessionMeta(id: String, title: String?, tag: String?, maxTurns: Long, maxTurnsSource: String, continueAfterReset: Boolean) {
        db.towoQueries.updateSessionMeta(
            title = title, tag = tag, max_turns = maxTurns, max_turns_source = maxTurnsSource,
            continue_after_reset = if (continueAfterReset) 1L else 0L, id = id,
        )
    }

    fun deleteSession(id: String) {
        db.towoQueries.deleteTranscriptForSession(id)
        db.towoQueries.deleteSession(id)
    }

    // ===== Permission requests =====

    fun recordPermissionRequest(id: String, sessionId: String, toolName: String, inputJson: String, title: String?, displayName: String?) {
        db.towoQueries.insertPermissionRequest(
            id = id, session_id = sessionId, tool_name = toolName, input_json = inputJson,
            title = title, display_name = displayName, requested_at = nowEpochSec(),
        )
    }

    fun getPermissionRequest(id: String): TowoPermissionRequest? =
        db.towoQueries.getPermissionRequest(id).executeAsOneOrNull()?.toModel()

    fun pendingPermissionRequests(): List<TowoPermissionRequest> =
        db.towoQueries.pendingPermissionRequests().executeAsList().map { it.toModel() }

    fun decidePermissionRequest(id: String, decision: String, reason: String?) =
        db.towoQueries.decidePermissionRequest(nowEpochSec(), decision, reason, id)

    // ===== Quota =====

    fun recordQuotaStatus(runnerId: String, rateLimitType: String, status: String, resetsAt: Long?, utilization: Double?) {
        db.towoQueries.upsertQuotaStatus(
            runner_id = runnerId, rate_limit_type = rateLimitType, status = status,
            resets_at = resetsAt, utilization = utilization, observed_at = nowEpochSec(),
        )
    }

    fun quotaForRunner(runnerId: String): List<TowoQuotaStatus> =
        db.towoQueries.quotaForRunner(runnerId).executeAsList().map { it.toModel() }

    // ===== Transcript (SessionStore durability, build-order step 5) =====

    fun appendTranscriptEntry(sessionId: String, subpath: String?, entryUuid: String?, entryJson: String) {
        db.towoQueries.appendTranscriptEntry(
            session_id = sessionId, subpath = subpath, entry_uuid = entryUuid,
            entry_json = entryJson, appended_at = nowEpochSec(),
        )
    }

    /** Raw JSON strings, oldest first — null (not empty list) if nothing was ever appended, matching
     *  the SDK's SessionStore.load() "never written" contract. */
    fun loadTranscriptEntries(sessionId: String, subpath: String?): List<String>? {
        val rows = db.towoQueries.loadTranscriptEntries(sessionId, subpath).executeAsList()
        return rows.ifEmpty { null }
    }

    fun deleteTranscriptForSession(sessionId: String) = db.towoQueries.deleteTranscriptForSession(sessionId)
}

private fun dev.jellystructure.db.Towo_runner.toModel() = TowoRunner(
    id = id, name = name, hostLabel = host_label, authMode = auth_mode, agentSdkVersion = agent_sdk_version,
    os = os, claudeAuthOk = claude_auth_ok != 0L,
    allowedRoots = runCatching { json.decodeFromString<List<String>>(allowed_roots) }.getOrDefault(emptyList()),
    createdAt = created_at, lastSeenAt = last_seen_at,
)

private fun dev.jellystructure.db.Towo_folder.toModel() = TowoFolder(
    id = id, runnerId = runner_id, name = name, absPath = abs_path, pinned = pinned != 0L,
    defaultPermissionProfile = default_permission_profile, discoveredAt = discovered_at,
)

private fun dev.jellystructure.db.Towo_session.toModel() = TowoSession(
    id = id, runnerId = runner_id, folderId = folder_id, folderPath = folder_path, title = title, tag = tag, status = status,
    maxTurns = max_turns, maxTurnsSource = max_turns_source, numTurns = num_turns,
    continueAfterReset = continue_after_reset != 0L, resumeAt = resume_at, lastErrorSubtype = last_error_subtype,
    createdAt = created_at, lastActivityAt = last_activity_at,
)

private fun dev.jellystructure.db.Towo_permission_request.toModel() = TowoPermissionRequest(
    id = id, sessionId = session_id, toolName = tool_name, inputJson = input_json, title = title,
    displayName = display_name, requestedAt = requested_at, decidedAt = decided_at, decision = decision, reason = reason,
)

private fun dev.jellystructure.db.Towo_quota_status.toModel() = TowoQuotaStatus(
    runnerId = runner_id, rateLimitType = rate_limit_type, status = status, resetsAt = resets_at,
    utilization = utilization, observedAt = observed_at,
)
