package dev.jellystructure.towo

import dev.jellystructure.auth.generateSecureToken
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.server.routes.fireWebhook
import kotlinx.coroutines.delay
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

/** Built by `.github/workflows/towo-runner-release.yml` on every push touching `towo-runner/`. */
private const val TOWO_RUNNER_TARBALL_URL = "https://github.com/jeggy/jellystructure/releases/download/towo-runner-latest/towo-runner.tgz"

/**
 * Phase 162 (Towo) — orchestration: turns inbound runner-link messages into TowoStore writes +
 * TowoEvent broadcasts, and backs the REST surface (spec §6). Kept separate from TowoRoutes.kt the
 * same way BazarrService sits behind BazarrRoutes.kt — routes stay thin, this holds the logic.
 */
class TowoService(
    private val store: TowoStore,
    private val runners: TowoRunnerRegistry,
    private val events: TowoEventBus,
    private val configStore: ConfigStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // (runnerId, rateLimitType) -> the resetsAt we already notified for, so a low-quota warning
    // fires once per window rather than on every subsequent rate_limit_event above the threshold.
    private val lowQuotaNotifiedMutex = Mutex()
    private val lowQuotaNotifiedFor = mutableMapOf<Pair<String, String>, Long?>()

    private suspend fun notify(payload: String) = fireWebhook(configStore.current, payload)

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
    private data class PendingStart(val folderId: String?, val folderPath: String, val maxTurns: Long, val permissionProfile: String)
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
            // towo-runner is "private": true (never meant for the public npm registry) and its
            // dist/ is gitignored, so this installs from a tarball a GitHub Actions workflow
            // (.github/workflows/towo-runner-release.yml) builds on every push to towo-runner/ and
            // republishes to a rolling "towo-runner-latest" release -- npx supports a tarball URL
            // directly, no npm account or registry publish needed. Confirmed working live 2026-08-11
            // after the previous placeholder (`npx @jellystructure/towo-runner`, a package that was
            // never published) 404'd for the user.
            "npx" to "npx $TOWO_RUNNER_TARBALL_URL --connect \"$url\" --root ~",
            // installer/docker are still placeholders -- towo.local doesn't resolve and the
            // jellystructure/towo-runner Docker image was never built or pushed anywhere.
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

    /** Spec §7's disconnect-while-pending handling — called from Server.kt's runner-link `finally`
     *  block. Sessions/requests are deliberately left untouched here: a disconnect is often a
     *  transient reconnect (RECONNECT_DELAY_MS), not proof the runner's underlying claude subprocess
     *  died, so nothing is auto-failed. This just makes the runner's live state visible immediately
     *  rather than only after the browser's next poll of the runners list. */
    suspend fun onRunnerDisconnected(runnerId: String) {
        events.broadcast(TowoEvent.RunnerStatus(runnerId, "offline"))
    }

    suspend fun onRunnerMessage(runnerId: String, msg: RunnerToControl) {
        when (msg) {
            is RunnerToControl.Hello -> {
                store.updateRunnerHello(runnerId, msg.hostLabel, msg.authMode, msg.agentSdkVersion, msg.os, msg.claudeAuthOk, msg.allowedRoots)
                events.broadcast(TowoEvent.RunnerStatus(runnerId, "online", if (msg.claudeAuthOk) "ok" else "missing"))
                redeliverQueuedDecisions(runnerId)
            }
            is RunnerToControl.Folders -> {
                store.upsertFolders(runnerId, msg.folders.map { it.name to it.absPath })
            }
            is RunnerToControl.SessionStarted -> {
                val pending = pendingStartsMutex.withLock { pendingStarts.remove(msg.commandId) }
                val settings = store.getSettings()
                store.createSession(
                    msg.sessionId, runnerId, pending?.folderId, pending?.folderPath,
                    permissionProfile = pending?.permissionProfile ?: settings.defaultPermissionProfile,
                    title = null, maxTurns = pending?.maxTurns ?: settings.defaultMaxTurns,
                )
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
                if (store.getSettings().notifyPermissionRequested) {
                    notify("""{"event":"towo_permission_requested","sessionId":"${msg.sessionId}","toolName":"${msg.toolName}","requestId":"${msg.requestId}"}""")
                }
            }
            is RunnerToControl.TranscriptAppend -> {
                for (entry in msg.entries) {
                    val entryUuid = (entry as? JsonObject)?.get("uuid")?.jsonPrimitive?.contentOrNull
                    appendTranscriptEntryWithRetry(runnerId, msg.sessionId, msg.subpath, entryUuid, entry.toString())
                }
            }
            is RunnerToControl.TranscriptLoadRequest -> {
                val entries = store.loadTranscriptEntries(msg.sessionId, msg.subpath)
                    ?.mapNotNull { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                runners.send(runnerId, ControlToRunner.TranscriptLoadResponse(msg.requestId, entries))
            }
            is RunnerToControl.PermissionTimeout -> {
                // The runner already resolved canUseTool locally (deny) — this is a report, not a
                // decision to deliver, so it goes straight to decidePermissionRequest ("timeout" per
                // spec §5's decision(allow|deny|timeout)), never through queuePermissionDecision.
                store.decidePermissionRequest(msg.requestId, "timeout", msg.reason)
                store.updateSessionStatus(msg.sessionId, "running")
                events.broadcast(TowoEvent.PermissionResolved(msg.requestId, "timeout"))
                events.broadcast(TowoEvent.SessionStatus(msg.sessionId, "running"))
                if (store.getSettings().notifyErrored) {
                    notify("""{"event":"towo_permission_timeout","sessionId":"${msg.sessionId}","requestId":"${msg.requestId}"}""")
                }
            }
        }
    }

    /** Spec §7/§B: "a `mirror_error` after retries means silent transcript loss and must surface in
     *  the UI" / "Alert on `mirror_error` — otherwise transcript loss is silent." A local write
     *  failure (locked/full DB) is usually transient, hence 3 short-backed-off attempts before this
     *  gives up; success after a prior runner failure clears the sticky warning. Unconditional
     *  webhook (no settings toggle) — this is data loss, not a preference. */
    private suspend fun appendTranscriptEntryWithRetry(runnerId: String, sessionId: String, subpath: String?, entryUuid: String?, entryJson: String) {
        var lastError: Throwable? = null
        for (attempt in 1..3) {
            val result = runCatching { store.appendTranscriptEntry(sessionId, subpath, entryUuid, entryJson) }
            if (result.isSuccess) {
                if (attempt > 1) store.clearRunnerMirrorError(runnerId)
                return
            }
            lastError = result.exceptionOrNull()
            if (attempt < 3) delay(200L * attempt)
        }
        val message = lastError?.message ?: "unknown error"
        Logger.warn("Towo: transcript mirror failed for session $sessionId after 3 attempts: $message", "towo")
        store.setRunnerMirrorError(runnerId, message)
        events.broadcast(TowoEvent.MirrorError(runnerId, sessionId, message))
        notify("""{"event":"towo_mirror_error","runnerId":"$runnerId","sessionId":"$sessionId","message":${JsonPrimitive(message)}}""")
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
                maybeNotifyLowQuota(runnerId, rateLimitType, resetsAt, utilization)
            }
            "result" -> onSessionResult(runnerId, sessionId, obj["subtype"]?.jsonPrimitive?.contentOrNull ?: "success")
            "assistant" -> {
                store.getSession(sessionId)?.let { store.updateSessionTurns(sessionId, it.numTurns + 1) }
            }
        }
    }

    /** Spec §A's 5th notify type. `utilization` is a 0-1 fraction (confirmed live, not 0-100 — see
     *  reference-claude-agent-sdk-facts memory); fires once per reset window, not on every event
     *  that stays above the threshold, tracked by (runnerId, rateLimitType) -> the resetsAt already
     *  notified for. */
    private suspend fun maybeNotifyLowQuota(runnerId: String, rateLimitType: String, resetsAt: Long?, utilization: Double?) {
        val settings = store.getSettings()
        if (!settings.notifyLowQuota || utilization == null) return
        val remainingPct = (1.0 - utilization) * 100
        if (remainingPct >= settings.lowQuotaThresholdPct) return
        val key = runnerId to rateLimitType
        val alreadyNotified = lowQuotaNotifiedMutex.withLock {
            val last = lowQuotaNotifiedFor[key]
            if (last == resetsAt) true else { lowQuotaNotifiedFor[key] = resetsAt; false }
        }
        if (alreadyNotified) return
        notify("""{"event":"towo_low_quota","runnerId":"$runnerId","rateLimitType":"$rateLimitType","remainingPct":${remainingPct.toInt()}}""")
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
                    if (store.getSettings().notifyPausedQuota) {
                        notify("""{"event":"towo_session_paused_quota","sessionId":"$sessionId","resumeAt":${recentlyRejected.resetsAt}}""")
                    }
                } else {
                    store.updateSessionStatus(sessionId, "errored", lastErrorSubtype = subtype)
                    events.broadcast(TowoEvent.SessionStatus(sessionId, "errored", errorSubtype = subtype))
                    notifyErrored(sessionId, subtype)
                }
            }
            else -> {
                store.updateSessionStatus(sessionId, "errored", lastErrorSubtype = subtype)
                events.broadcast(TowoEvent.SessionStatus(sessionId, "errored", errorSubtype = subtype))
                notifyErrored(sessionId, subtype)
            }
        }
    }

    private suspend fun notifyErrored(sessionId: String, subtype: String) {
        if (store.getSettings().notifyErrored) {
            notify("""{"event":"towo_session_errored","sessionId":"$sessionId","subtype":"$subtype"}""")
        }
    }

    /** Called by [TowoAutoContinueScheduler] after it successfully resumes a paused_quota session. */
    suspend fun notifyResumed(sessionId: String) {
        if (store.getSettings().notifyResumed) {
            notify("""{"event":"towo_session_resumed","sessionId":"$sessionId"}""")
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
        pendingStartsMutex.withLock { pendingStarts[commandId] = PendingStart(folderId, folderPath, resolvedMaxTurns, resolvedProfile) }
        val sent = runners.send(runnerId, ControlToRunner.StartSession(commandId, folderPath, prompt, resolvedMaxTurns, resolvedProfile, settings.permissionTimeoutMs, settings.permissionTimeoutReason))
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
        val settings = store.getSettings()
        val sent = runners.send(session.runnerId, ControlToRunner.ResumeSession(sessionId, folderPath, prompt, session.permissionProfile, settings.permissionTimeoutMs, settings.permissionTimeoutReason))
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

    /** Spec §7's disconnect-while-pending handling: the decision is always durably queued first
     *  ([TowoStore.queuePermissionDecision] — decision set, decided_at still null), so a runner that's
     *  offline right now never loses it; delivery is retried the moment that runner's next `hello`
     *  arrives (see [onRunnerMessage]'s Hello branch → [redeliverQueuedDecisions]). [PermissionOutcome]
     *  distinguishes "no such request" from "queued but the runner is offline", which the REST layer
     *  needs to tell those apart (404 vs a retry-will-happen response) — see TowoRoutes.kt. */
    enum class PermissionOutcome { NOT_FOUND, DELIVERED, RUNNER_OFFLINE }

    suspend fun decidePermission(requestId: String, decision: String, reason: String?): PermissionOutcome {
        val request = store.getPermissionRequest(requestId) ?: return PermissionOutcome.NOT_FOUND
        if (request.decision != null) return PermissionOutcome.NOT_FOUND
        val session = store.getSession(request.sessionId) ?: return PermissionOutcome.NOT_FOUND
        store.queuePermissionDecision(requestId, decision, reason)
        val delivered = deliverPermissionDecision(session.runnerId, requestId, decision, reason)
        return if (delivered) PermissionOutcome.DELIVERED else PermissionOutcome.RUNNER_OFFLINE
    }

    private suspend fun deliverPermissionDecision(runnerId: String, requestId: String, decision: String, reason: String?): Boolean {
        val sent = runners.send(runnerId, ControlToRunner.PermissionDecision(requestId, decision, reason))
        if (sent) {
            store.markPermissionDelivered(requestId)
            val request = store.getPermissionRequest(requestId)
            if (request != null) {
                store.updateSessionStatus(request.sessionId, "running")
                events.broadcast(TowoEvent.PermissionResolved(requestId, decision))
                events.broadcast(TowoEvent.SessionStatus(request.sessionId, "running"))
            }
        } else {
            Logger.warn("Towo: permission $requestId decision queued but runner $runnerId is offline — will retry on reconnect", "towo")
        }
        return sent
    }

    /** Called from the Hello branch on every runner connect (first connect and every reconnect) —
     *  redelivers any decision an admin made while this runner was offline. */
    private suspend fun redeliverQueuedDecisions(runnerId: String) {
        for (queued in store.queuedUndeliveredDecisions(runnerId)) {
            val decision = queued.decision ?: continue
            deliverPermissionDecision(runnerId, queued.id, decision, queued.reason)
        }
    }
}
