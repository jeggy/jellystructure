package dev.jellystructure.towo

import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.log.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

sealed class RunnerLinkAuth {
    /** A single-use enrollment token was presented and consumed — [credential] must be sent to the
     *  runner exactly once, over ControlToRunner.Enrolled, before anything else. */
    data class Enrolled(val runnerId: String, val credential: String) : RunnerLinkAuth()
    /** An already-known runner reconnected with its long-lived credential. */
    data class Existing(val runnerId: String) : RunnerLinkAuth()
    object Rejected : RunnerLinkAuth()
}

data class MintedEnrollment(val token: String, val expiresAt: Long, val commands: Map<String, String>)

/**
 * Phase 162 (Towo) — orchestration: turns inbound runner-link messages into TowoStore writes +
 * TowoEvent broadcasts, and backs the REST surface (spec §6). Kept separate from TowoRoutes.kt the
 * same way BazarrService sits behind BazarrRoutes.kt — routes stay thin, this holds the logic.
 */
class TowoService(
    private val store: TowoStore,
    private val runners: TowoRunnerRegistry,
    private val events: TowoEventBus,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Build-order step 5 — the real durable transcript (towo_transcript_entry), not the in-memory
     *  capped placeholder v1 shipped with. This is SDKMessages broadcast for the live browser stream
     *  (session_message), kept for history purposes; the SDK's own SessionStore.append() entries
     *  (transcript_append, handled below) are what actually back this table now. */
    fun getMessages(sessionId: String): List<JsonElement> =
        store.loadTranscriptEntries(sessionId, subpath = null)
            ?.mapNotNull { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?: emptyList()

    // commandId -> the folder a start_session call targeted, so onSessionStarted can attach it.
    // Touched from both REST-call coroutines (createSession) and the runner-link receive loop
    // (onRunnerMessage) concurrently -- unlike the JVM, Kotlin/Native has no free happens-before
    // guarantee across threads here, so this needs a real lock, not just "single-threaded in practice".
    private data class PendingStart(val folderId: String?, val folderPath: String, val maxTurns: Long)
    private val pendingStartsMutex = Mutex()
    private val pendingStarts = mutableMapOf<String, PendingStart>()

    // ===== Enrollment / runner-link auth =====

    /** [publicWsBase] e.g. "wss://192.0.2.10:9505" — derived by the caller from the inbound request
     *  (Settings' "where runners connect" field, §A, isn't built yet; this is a reasonable default
     *  until it is). */
    fun mintEnrollment(name: String, publicWsBase: String): MintedEnrollment {
        val minted = store.mintEnrollment(name)
        val url = "$publicWsBase/api/towo/runner-link?token=${minted.token}"
        val commands = mapOf(
            "npx" to "npx @jellystructure/towo-runner --connect \"$url\" --root ~",
            "installer" to "curl -fsSL https://towo.local/install.sh | sh -s -- --connect \"$url\" --root ~",
            "docker" to "docker run -d -v ~:/workspaces jellystructure/towo-runner --connect \"$url\" --root /workspaces",
        )
        return MintedEnrollment(minted.token, minted.expiresAt, commands)
    }

    /** Called by the WS route handler the moment a connection to /api/towo/runner-link presents its
     *  token (spec §B: enrollment tokens are single-use, exchanged for a credential never shown again). */
    fun authenticateRunnerLink(token: String): RunnerLinkAuth {
        store.exchangeEnrollment(token)?.let { cred ->
            return RunnerLinkAuth.Enrolled(cred.runnerId, cred.credential)
        }
        store.validateRunnerCredential(token)?.let { runner ->
            return RunnerLinkAuth.Existing(runner.id)
        }
        return RunnerLinkAuth.Rejected
    }

    // ===== Runner-link inbound message handling =====

    suspend fun onRunnerMessage(runnerId: String, msg: RunnerToControl) {
        when (msg) {
            is RunnerToControl.Hello -> {
                store.updateRunnerHello(runnerId, msg.hostLabel, msg.authMode, msg.agentSdkVersion, msg.os, msg.claudeAuthOk, msg.allowedRoots)
                events.broadcast(TowoEvent.RunnerStatus(runnerId, "online", if (msg.claudeAuthOk) "ok" else "missing"))
            }
            is RunnerToControl.Folders -> {
                store.upsertFolders(runnerId, msg.folders.map { it.name to it.absPath })
            }
            is RunnerToControl.SessionStarted -> {
                val pending = pendingStartsMutex.withLock { pendingStarts.remove(msg.commandId) }
                store.createSession(msg.sessionId, runnerId, pending?.folderId, pending?.folderPath, title = null, maxTurns = pending?.maxTurns ?: store.getSettings().defaultMaxTurns)
                events.broadcast(TowoEvent.SessionStatus(msg.sessionId, "running"))
            }
            is RunnerToControl.SessionMessage -> onSessionMessage(runnerId, msg.sessionId, msg.message)
            is RunnerToControl.PermissionRequest -> {
                store.recordPermissionRequest(
                    id = msg.requestId, sessionId = msg.sessionId, toolName = msg.toolName,
                    inputJson = msg.input.toString(), title = msg.title, displayName = msg.displayName,
                )
                store.updateSessionStatus(msg.sessionId, "awaiting_permission")
                events.broadcast(TowoEvent.SessionStatus(msg.sessionId, "awaiting_permission"))
                events.broadcast(TowoEvent.PermissionRequested(msg.requestId, msg.sessionId, msg.toolName, msg.input, msg.title, msg.displayName))
            }
            is RunnerToControl.TranscriptAppend -> {
                for (entry in msg.entries) {
                    val entryUuid = (entry as? JsonObject)?.get("uuid")?.jsonPrimitive?.contentOrNull
                    store.appendTranscriptEntry(msg.sessionId, msg.subpath, entryUuid, entry.toString())
                }
            }
            is RunnerToControl.TranscriptLoadRequest -> {
                val entries = store.loadTranscriptEntries(msg.sessionId, msg.subpath)
                    ?.mapNotNull { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                runners.send(runnerId, ControlToRunner.TranscriptLoadResponse(msg.requestId, entries))
            }
        }
    }

    /** Inspects a raw, opaque SDKMessage only for the handful of fields Towo's index needs (spec §E's
     *  rate_limit_event handling, session completion) — everything else passes through to the browser
     *  unexamined via message.raw. Field names/casing are ground truth from the pinned SDK's own
     *  sdk.d.ts (see reference-claude-agent-sdk-facts memory), not the docs' snake_case guess. */
    private suspend fun onSessionMessage(runnerId: String, sessionId: String, raw: JsonElement) {
        events.broadcast(TowoEvent.MessageRaw(sessionId, raw))
        val obj = raw as? JsonObject ?: return
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "rate_limit_event" -> {
                val info = obj["rate_limit_info"]?.jsonObject ?: return
                val status = info["status"]?.jsonPrimitive?.contentOrNull ?: return
                val rateLimitType = info["rateLimitType"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val resetsAt = info["resetsAt"]?.jsonPrimitive?.longOrNull
                val utilization = info["utilization"]?.jsonPrimitive?.doubleOrNull
                store.recordQuotaStatus(runnerId, rateLimitType, status, resetsAt, utilization)
                events.broadcast(TowoEvent.QuotaUpdated(runnerId, rateLimitType, status, resetsAt, utilization))
            }
            "result" -> onSessionResult(runnerId, sessionId, obj["subtype"]?.jsonPrimitive?.contentOrNull ?: "success")
            "assistant" -> {
                store.getSession(sessionId)?.let { store.updateSessionTurns(sessionId, it.numTurns + 1) }
            }
        }
    }

    /** Spec §8's state diagram: rate_limit_event is the primary quota signal, a ResultMessage is only
     *  corroboration. error_max_turns is a user decision (stopped_max_turns), never auto-retried.
     *  error_during_execution is checked against the quota cache to tell a genuine quota pause apart
     *  from an ordinary error -- only the former ever reschedules (TowoAutoContinueScheduler, step 6). */
    private suspend fun onSessionResult(runnerId: String, sessionId: String, subtype: String) {
        events.broadcast(TowoEvent.SessionResult(sessionId, subtype))
        when (subtype) {
            "success" -> {
                store.updateSessionStatus(sessionId, "idle")
                events.broadcast(TowoEvent.SessionStatus(sessionId, "idle"))
            }
            "error_max_turns" -> {
                store.updateSessionStatus(sessionId, "stopped_max_turns", lastErrorSubtype = subtype)
                events.broadcast(TowoEvent.SessionStatus(sessionId, "stopped_max_turns", errorSubtype = subtype))
            }
            "error_during_execution" -> {
                val recentlyRejected = store.quotaForRunner(runnerId)
                    .filter { it.status == "rejected" }
                    .maxByOrNull { it.observedAt }
                if (recentlyRejected != null) {
                    store.updateSessionStatus(sessionId, "paused_quota", resumeAt = recentlyRejected.resetsAt, lastErrorSubtype = subtype)
                    events.broadcast(TowoEvent.SessionStatus(sessionId, "paused_quota", resumeAt = recentlyRejected.resetsAt))
                } else {
                    store.updateSessionStatus(sessionId, "errored", lastErrorSubtype = subtype)
                    events.broadcast(TowoEvent.SessionStatus(sessionId, "errored", errorSubtype = subtype))
                }
            }
            else -> {
                store.updateSessionStatus(sessionId, "errored", lastErrorSubtype = subtype)
                events.broadcast(TowoEvent.SessionStatus(sessionId, "errored", errorSubtype = subtype))
            }
        }
    }

    // ===== REST-backing operations =====

    /** [maxTurns]/[permissionProfile] null = caller didn't specify, fall back to towo_settings'
     *  defaults (spec §A/§F: "a starting value that every session can override"). */
    suspend fun createSession(runnerId: String, folderId: String?, folderPath: String, prompt: String, maxTurns: Long?, permissionProfile: String?): Boolean {
        val settings = store.getSettings()
        val resolvedMaxTurns = maxTurns ?: settings.defaultMaxTurns
        val resolvedProfile = permissionProfile ?: settings.defaultPermissionProfile
        val commandId = generateSecureToken()
        pendingStartsMutex.withLock { pendingStarts[commandId] = PendingStart(folderId, folderPath, resolvedMaxTurns) }
        val sent = runners.send(runnerId, ControlToRunner.StartSession(commandId, folderPath, prompt, resolvedMaxTurns, resolvedProfile))
        if (!sent) pendingStartsMutex.withLock { pendingStarts.remove(commandId) }
        return sent
    }

    suspend fun sendMessage(sessionId: String, text: String): Boolean {
        val session = store.getSession(sessionId) ?: return false
        return runners.send(session.runnerId, ControlToRunner.SendMessage(sessionId, text))
    }

    suspend fun interrupt(sessionId: String): Boolean {
        val session = store.getSession(sessionId) ?: return false
        return runners.send(session.runnerId, ControlToRunner.Interrupt(sessionId))
    }

    /** Build-order step 6 — resumes a paused_quota session, whether triggered by
     *  TowoAutoContinueScheduler or a user's manual "resume now". [prompt] is the resume-turn's
     *  continuation text; see the spec's open question on what this should actually say. */
    suspend fun resumeSession(sessionId: String, prompt: String): Boolean {
        val session = store.getSession(sessionId) ?: return false
        val folderPath = session.folderPath ?: return false
        val sent = runners.send(session.runnerId, ControlToRunner.ResumeSession(sessionId, folderPath, prompt))
        if (sent) {
            store.updateSessionStatus(sessionId, "running")
            events.broadcast(TowoEvent.SessionStatus(sessionId, "running"))
        }
        return sent
    }

    /** Spec §F's per-session "Continue after reset" toggle -- the switch TowoAutoContinueScheduler
     *  reads. Also accepts title/tag/maxTurns since they ride the same PATCH (spec §6). */
    fun updateSessionMeta(sessionId: String, title: String?, tag: String?, maxTurns: Long?, continueAfterReset: Boolean?): Boolean {
        val session = store.getSession(sessionId) ?: return false
        store.updateSessionMeta(
            id = sessionId,
            title = title ?: session.title,
            tag = tag ?: session.tag,
            maxTurns = maxTurns ?: session.maxTurns,
            maxTurnsSource = if (maxTurns != null) "override" else session.maxTurnsSource,
            continueAfterReset = continueAfterReset ?: session.continueAfterReset,
        )
        return true
    }

    suspend fun decidePermission(requestId: String, decision: String, reason: String?): Boolean {
        val request = store.getPermissionRequest(requestId) ?: return false
        val session = store.getSession(request.sessionId) ?: return false
        store.decidePermissionRequest(requestId, decision, reason)
        val sent = runners.send(session.runnerId, ControlToRunner.PermissionDecision(requestId, decision, reason))
        if (sent) {
            store.updateSessionStatus(request.sessionId, "running")
            events.broadcast(TowoEvent.PermissionResolved(requestId, decision))
            events.broadcast(TowoEvent.SessionStatus(request.sessionId, "running"))
        } else {
            Logger.warn("Towo: permission $requestId decided but runner ${session.runnerId} is offline — decision recorded, not yet delivered", "towo")
        }
        return sent
    }
}
