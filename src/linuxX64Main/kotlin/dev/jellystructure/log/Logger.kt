package dev.jellystructure.log

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** Carries the scan worker's ID on the coroutine context so logs can be attributed to a worker. */
class WorkerId(val id: Int) : AbstractCoroutineContextElement(WorkerId) {
    companion object Key : CoroutineContext.Key<WorkerId>
}

/**
 * Static logger wrapping println. The coroutine-aware functions prefix every line with `[W#id]`
 * when a [WorkerId] is present on the coroutine context (i.e. when called from inside a scan worker).
 *
 * Use [info]/[warn]/[error] from suspend code; they pick up the worker ID automatically.
 * Use [infoSync]/[warnSync]/[errorSync] from non-suspend code (main(), signal handlers, setup,
 * synchronous stores) — these never carry a worker ID.
 */
object Logger {
    suspend fun info(msg: String) = emit("INFO", currentCoroutineContext()[WorkerId]?.id, msg)
    suspend fun warn(msg: String) = emit("WARN", currentCoroutineContext()[WorkerId]?.id, msg)
    suspend fun error(msg: String) = emit("ERROR", currentCoroutineContext()[WorkerId]?.id, msg)

    fun infoSync(msg: String) = emit("INFO", null, msg)
    fun warnSync(msg: String) = emit("WARN", null, msg)
    fun errorSync(msg: String) = emit("ERROR", null, msg)

    private fun emit(level: String, workerId: Int?, msg: String) {
        val prefix = if (workerId != null) "[W#$workerId] " else ""
        println("[$level] $prefix$msg")
    }
}
