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

    // sessionId -> recent raw SDKMessages, capped. A placeholder until build-order step 5 wires a real
    // SessionStore -- v1 needs SOMETHING for GET /sessions/:id/messages to return so the session view
    // is testable end to end; this is explicitly not durable (lost on restart, capped, no pagination).
    private val transcriptsMutex = Mutex()
    private val transcripts = mutableMapOf<String, MutableList<JsonElement>>()
    private val maxTranscriptPerSession = 500

    suspend fun getMessages(sessionId: String): List<JsonElement> =
        transcriptsMutex.withLock { transcripts[sessionId]?.toList() ?: emptyList() }

    private suspend fun appendTranscript(sessionId: String, message: JsonElement) {
        transcriptsMutex.withLock {
            val list = transcripts.getOrPut(sessionId) { mutableListOf() }
            list.add(message)
            if (list.size > maxTranscriptPerSession) list.removeAt(0)
        }
    }

    // commandId -> the folderId a start_session call targeted, so onSessionStarted can attach it.
    // Touched from both REST-call coroutines (createSession) and the runner-link receive loop
    // (onRunnerMessage) concurrently -- unlike the JVM, Kotlin/Native has no free happens-before
    // guarantee across threads here, so this needs a real lock, not just "single-threaded in practice".
    private val pendingStartsMutex = Mutex()
    private val pendingStarts = mutableMapOf<String, String?>()

    // ===== Enrollment / runner-link auth =====

    /** [publicWsBase] e.g. "wss://10.0.0.10:9505" — derived by the caller from the inbound request
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
                val folderId = pendingStartsMutex.withLock { pendingStarts.remove(msg.commandId) }
                store.createSession(msg.sessionId, runnerId, folderId, title = null, maxTurns = 40)
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
        }
    }

    /** Inspects a raw, opaque SDKMessage only for the handful of fields Towo's index needs (spec §E's
     *  rate_limit_event handling, session completion) — everything else passes through to the browser
     *  unexamined via message.raw. Field names/casing are ground truth from the pinned SDK's own
     *  sdk.d.ts (see reference-claude-agent-sdk-facts memory), not the docs' snake_case guess. */
    private suspend fun onSessionMessage(runnerId: String, sessionId: String, raw: JsonElement) {
        appendTranscript(sessionId, raw)
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
            "result" -> {
                val subtype = obj["subtype"]?.jsonPrimitive?.contentOrNull ?: "success"
                val status = if (subtype == "success") "idle" else "errored"
                store.updateSessionStatus(sessionId, status, lastErrorSubtype = subtype.takeIf { it != "success" })
                events.broadcast(TowoEvent.SessionResult(sessionId, subtype))
                events.broadcast(TowoEvent.SessionStatus(sessionId, status, errorSubtype = subtype.takeIf { it != "success" }))
            }
            "assistant" -> {
                store.getSession(sessionId)?.let { store.updateSessionTurns(sessionId, it.numTurns + 1) }
            }
        }
    }

    // ===== REST-backing operations =====

    suspend fun createSession(runnerId: String, folderId: String?, folderPath: String, prompt: String, maxTurns: Long?, permissionProfile: String): Boolean {
        val commandId = generateSecureToken()
        pendingStartsMutex.withLock { pendingStarts[commandId] = folderId }
        val sent = runners.send(runnerId, ControlToRunner.StartSession(commandId, folderPath, prompt, maxTurns, permissionProfile))
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
