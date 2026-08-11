package dev.jellystructure.towo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Phase 162 (Towo) — the wire protocol spoken over `/api/towo/runner-link`, the WS a runner daemon
 * dials outbound to this backend (spec §3, §6). The runner (towo-runner, TypeScript) and this backend
 * are different languages, so this file is the source of truth for the JSON shape both sides must
 * agree on — towo-runner/src/protocol.ts mirrors it by hand, not by codegen.
 *
 * `session_message`'s `message` field is the *raw* Claude Agent SDK `SDKMessage`, forwarded opaquely:
 * this backend does not attempt to fully model the SDK's ~40-variant message union (build-order step
 * 5's SessionStore is where transcript content actually lives; for v1 this backend only reads the few
 * top-level fields it needs — type, rate_limit_info — off the raw JSON and relays the rest as-is).
 */
@Serializable
sealed class RunnerToControl {
    @Serializable @SerialName("hello")
    data class Hello(
        val hostLabel: String? = null,
        val authMode: String? = null,
        val agentSdkVersion: String? = null,
        val os: String? = null,
        val claudeAuthOk: Boolean = false,
        val allowedRoots: List<String> = emptyList(),
    ) : RunnerToControl()

    @Serializable @SerialName("folders")
    data class Folders(val folders: List<FolderReport>) : RunnerToControl()

    @Serializable @SerialName("session_started")
    data class SessionStarted(val commandId: String, val sessionId: String) : RunnerToControl()

    @Serializable @SerialName("session_message")
    data class SessionMessage(val sessionId: String, val message: JsonElement) : RunnerToControl()

    @Serializable @SerialName("permission_request")
    data class PermissionRequest(
        val sessionId: String,
        val requestId: String,
        val toolName: String,
        val input: JsonElement,
        val title: String? = null,
        val displayName: String? = null,
    ) : RunnerToControl()

    // No separate "session_result"/"rate_limit_event" variant: those are ordinary SDKMessage types
    // that already arrive via SessionMessage's raw passthrough. TowoService inspects the raw JSON's
    // own "type" field for the handful of fields it needs (see onSessionMessage) rather than the
    // runner re-deriving and re-sending them as a second, parallel message.

    // ===== Build-order step 5 — SessionStore (spec §4.3 / report §4.3) =====
    // The SDK's own SessionStore.append()/load() calls, relayed opaquely over this same connection so
    // the control plane becomes the durable mirror. Entries are NOT the same thing as SessionMessage
    // above: SessionMessage is the live SDKMessage stream (for indexing + browser broadcast);
    // these are the SDK's own JSONL-line entries (for durability + resume).

    @Serializable @SerialName("transcript_append")
    data class TranscriptAppend(val sessionId: String, val subpath: String? = null, val entries: List<JsonElement>) : RunnerToControl()

    @Serializable @SerialName("transcript_load_request")
    data class TranscriptLoadRequest(val requestId: String, val sessionId: String, val subpath: String? = null) : RunnerToControl()

    /** Spec §D — "an ignored request stays suspended (with a long timeout that denies with an
     *  editable reason)... entirely the runner's own responsibility." The runner resolved canUseTool
     *  locally (no control-plane round trip possible — the tool call can't wait on one more hop after
     *  already waiting out the timeout), this just reports what it decided so the control plane's
     *  record and the browser stop showing it as pending. */
    @Serializable @SerialName("permission_timeout")
    data class PermissionTimeout(val sessionId: String, val requestId: String, val reason: String) : RunnerToControl()
}

@Serializable
data class FolderReport(val name: String, val absPath: String)

@Serializable
sealed class ControlToRunner {
    /** Sent exactly once, immediately after a connection authenticates with a single-use enrollment
     *  token (spec §B) — the runner persists [credential] locally and uses it for every future
     *  reconnect. Never sent again, and the plaintext is never retrievable after this point. */
    @Serializable @SerialName("enrolled")
    data class Enrolled(val runnerId: String, val credential: String) : ControlToRunner()

    @Serializable @SerialName("start_session")
    data class StartSession(
        val commandId: String,
        val folderPath: String,
        val prompt: String,
        val maxTurns: Long? = null,
        val permissionProfile: String = "normal",
        val permissionTimeoutMs: Long = 30L * 60 * 1000,
        val permissionTimeoutReason: String = "No response within the timeout — auto-denied by Towo.",
    ) : ControlToRunner()

    @Serializable @SerialName("send_message")
    data class SendMessage(val sessionId: String, val text: String) : ControlToRunner()

    @Serializable @SerialName("interrupt")
    data class Interrupt(val sessionId: String) : ControlToRunner()

    @Serializable @SerialName("permission_decision")
    data class PermissionDecision(val requestId: String, val decision: String, val reason: String? = null) : ControlToRunner()

    /** Response to RunnerToControl.TranscriptLoadRequest. [entries] null = never written (SDK
     *  SessionStore.load() contract: distinguish "never written" from "emptied" isn't required,
     *  both may return null). */
    @Serializable @SerialName("transcript_load_response")
    data class TranscriptLoadResponse(val requestId: String, val entries: List<JsonElement>? = null) : ControlToRunner()

    /** Build-order step 6 (auto-continue, spec §E) — the control plane, not the runner, owns the
     *  paused_quota/continue_after_reset schedule (TowoAutoContinueScheduler): it already persists
     *  that state and already runs a periodic coroutine loop (matching this codebase's existing
     *  scheduled-scan pattern in Main.kt), so re-deriving arming state on the runner side would just
     *  be a second, harder-to-keep-consistent copy of the same state. Deliberate deviation from the
     *  report's "runner owns it because it's closest to the process" framing -- noted in the spec's
     *  dev-review addendum. */
    @Serializable @SerialName("resume_session")
    data class ResumeSession(
        val sessionId: String,
        val folderPath: String,
        val prompt: String,
        val permissionProfile: String = "normal",
        val permissionTimeoutMs: Long = 30L * 60 * 1000,
        val permissionTimeoutReason: String = "No response within the timeout — auto-denied by Towo.",
    ) : ControlToRunner()
}
