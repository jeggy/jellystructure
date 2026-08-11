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
    ) : ControlToRunner()

    @Serializable @SerialName("send_message")
    data class SendMessage(val sessionId: String, val text: String) : ControlToRunner()

    @Serializable @SerialName("interrupt")
    data class Interrupt(val sessionId: String) : ControlToRunner()

    @Serializable @SerialName("permission_decision")
    data class PermissionDecision(val requestId: String, val decision: String, val reason: String? = null) : ControlToRunner()
}
