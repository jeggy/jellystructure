package dev.jellystructure.ops

import dev.jellystructure.config.ConfigStore
import dev.jellystructure.log.Logger
import dev.jellystructure.server.routes.fireWebhook
import kotlin.concurrent.Volatile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import platform.posix.system as posixSystemBlocking

/**
 * Phase 118 (FR B) — Ktor Native's selector is a child of the server engine's job; a fatal exception
 * there cancels the engine via structured concurrency and unwinds `main()`. `setUnhandledExceptionHook`
 * cannot keep the process alive (that's an external supervisor's job — `restart: unless-stopped` in
 * docker-compose once containers land) — it's a last-gasp reporter: write a crash marker to disk and attempt one
 * *synchronous* webhook POST, both without any coroutine/selector machinery, since that's exactly what
 * just died. [installCrashHook] must be called first thing in `main()`, before any coroutine work starts.
 */
// Set by main() the moment config is loaded — read lazily at crash time so installCrashHook can run
// (per spec) before the config exists yet, without needing to re-install the hook once it does.
@Volatile
private var crashWebhookUrl: String = ""

fun setCrashWebhookUrl(url: String) {
    crashWebhookUrl = url
}

@OptIn(kotlin.experimental.ExperimentalNativeApi::class, ExperimentalForeignApi::class)
fun installCrashHook(dataDir: String) {
    setUnhandledExceptionHook { throwable ->
        runCatching {
            val message = throwable.message ?: throwable.toString()
            val frames = throwable.stackTraceToString().lineSequence().take(8).joinToString("\n")
            val marker = """{"timestamp":${platform.posix.time(null)},"message":${message.crashJsonEsc()},"stack":${frames.crashJsonEsc()}}"""
            runCatching { writeFileBlocking("$dataDir/last-crash.json", marker) }
            val webhookUrl = crashWebhookUrl
            if (webhookUrl.isNotBlank()) {
                val payload = """{"event":"server_crashed","message":${message.crashJsonEsc()}}"""
                val safePayload = payload.replace("'", "'\\''")
                // No trailing `&` (unlike the normal async fireWebhook) — this must block until curl
                // finishes or times out, since the process is about to exit either way.
                posixSystemBlocking("curl -sf --max-time 5 -X POST -H 'Content-Type: application/json' -d '$safePayload' '$webhookUrl' >/dev/null 2>&1")
            }
        }
        // Deliberately no rethrow / process-alive attempt — the engine's job is already unwinding;
        // the process exiting from here is expected (Non-goals: "no attempt to keep the process alive
        // through a selector failure — impossible by construction").
    }
}

/** Phase 118 (FR B.2) — called once at boot, after ConfigStore is loaded but before anything else
 *  depends on a clean slate. Fires the recovery webhook (gated by `notify_on_crash`), always writes
 *  an Activity entry and clears the marker so a healthy run doesn't re-report a stale crash. */
suspend fun reportCrashRecoveryIfAny(dataDir: String, configStore: ConfigStore) {
    val path = Path("$dataDir/last-crash.json")
    if (!SystemFileSystem.exists(path)) return
    val content = runCatching { SystemFileSystem.source(path).buffered().readString() }.getOrNull()
    runCatching { SystemFileSystem.delete(path) }
    if (content == null) return

    Logger.error("Server recovered from a crash: $content", "ops")
    if (configStore.current.behavior.notifyOnCrash) {
        fireWebhook(configStore.current, """{"event":"server_recovered_from_crash","previous_crash":$content}""")
    }
}

private fun String.crashJsonEsc(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

private fun writeFileBlocking(path: String, content: String) {
    val sink = SystemFileSystem.sink(Path(path)).buffered()
    sink.writeString(content)
    sink.flush()
    sink.close()
}
