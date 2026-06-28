package dev.jellystructure.log

import dev.jellystructure.media.ActivityLog
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Carries the scan worker's ID on the coroutine context so logs can be attributed to a worker. */
class WorkerId(val id: Int) : AbstractCoroutineContextElement(WorkerId) {
    companion object Key : CoroutineContext.Key<WorkerId>
}

/** 93g: carries the current run's id (and optional pipeline step) on the coroutine context so every
 *  log line emitted during a scan/pipeline run is tagged — and therefore filterable in Activity by run. */
class RunContext(val runId: String, val step: String? = null) : AbstractCoroutineContextElement(RunContext) {
    companion object Key : CoroutineContext.Key<RunContext>
}

/**
 * Single logging entry point for the entire backend.
 *
 * Outputs to stdout, the in-process activity store, and (via the store) the WS `log_line` event.
 * Wire up [activityLog] once at startup via Main.kt; until then only stdout is active.
 *
 * All methods are suspend — callers that sit in non-suspend contexts (main(), shutdown lambdas)
 * should use plain [println] instead; those messages appear before the server is up anyway.
 *
 * Pass [category] at call sites where the event belongs to a specific domain:
 * "scan", "nfo", "artwork", "track". Defaults to "system".
 */
object Logger {
    var activityLog: ActivityLog? = null

    suspend fun info(msg: String, category: String = "system", mediaId: String? = null) =
        emit("INFO", category, msg, mediaId)
    suspend fun warn(msg: String, category: String = "system", mediaId: String? = null) =
        emit("WARN", category, msg, mediaId)
    suspend fun error(msg: String, category: String = "system", mediaId: String? = null) =
        emit("ERROR", category, msg, mediaId)

    /** 93g: open a run in the runs index (for the Activity run picker). [trigger] = scheduled/manual/scan. */
    suspend fun startRun(runId: String, trigger: String) = activityLog?.startRun(runId, trigger)
    /** 93g: mark a run finished in the runs index. */
    suspend fun finishRun(runId: String) = activityLog?.finishRun(runId)

    private suspend fun emit(level: String, category: String, msg: String, mediaId: String?) {
        val ctx = currentCoroutineContext()
        val workerId = ctx[WorkerId]?.id
        val run = ctx[RunContext]
        val prefix = if (workerId != null) "[W#$workerId] " else ""
        println("[$level] $prefix$msg")
        activityLog?.log(level, category, "$prefix$msg", mediaId, run?.runId, run?.step)
    }
}
