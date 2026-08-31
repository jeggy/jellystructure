package dev.jellystructure.ops

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Phase 182 (FR-182-6/FR-182-7) — classifies a coroutine (and everything launched under it) as either
 * request-serving work that a real viewer is waiting on, or background work the server chose to do on
 * its own schedule. [OutboundHttp] and [ProcessGate] read this to reserve capacity for [INTERACTIVE]
 * that [BACKGROUND] work can never consume, however saturated it is — see phase-182 §2.5/§4 for why:
 * before this, a scan's TMDB/ffprobe fan-out and a Ravilo playback negotiation shared one untimed FIFO,
 * so a heavy scan could make the server look completely down to every TV.
 *
 * Set this element ONCE at the top of a background run's own launch (`launchScanRun`'s `appScope.launch`,
 * `runScan`'s per-worker `launch(scanDispatcher + …)`, `MediaJobQueue`'s dispatcher). Every coroutine
 * launched underneath inherits it automatically — `launch{}`/`async{}` combine contexts with `+`, and
 * nothing else in this codebase overrides this key — so a per-episode `async{}` deep inside
 * `Scanner.scanSeries` is classified correctly with zero code at that call site.
 *
 * Absent = [INTERACTIVE], by construction: every TV-facing and admin request handler runs with no
 * [GateClass] element, and a call site nobody remembered to tag therefore fails SAFE — treated as
 * interactive — rather than silently inheriting background priority (the opposite failure mode is the
 * one that starves real users, which is the whole point of this phase).
 */
enum class GateClassKind { INTERACTIVE, BACKGROUND }

class GateClass(val kind: GateClassKind) : AbstractCoroutineContextElement(GateClass) {
    companion object Key : CoroutineContext.Key<GateClass> {
        /** Convenience context element — `launch(GateClass.BACKGROUND) { … }`. */
        val BACKGROUND: CoroutineContext = GateClass(GateClassKind.BACKGROUND)
        val INTERACTIVE: CoroutineContext = GateClass(GateClassKind.INTERACTIVE)
    }
}

suspend fun currentGateClass(): GateClassKind =
    coroutineContext[GateClass]?.kind ?: GateClassKind.INTERACTIVE

/** Phase 182 (FR-182-9) — shared shape for [dev.jellystructure.OutboundHttp.stats] and
 *  [ProcessGate.stats], surfaced on `/api/health` and read by the Activity page's saturation banner. */
data class GateStats(
    val totalPermits: Int,
    val interactiveReserved: Int,
    val sharedCapacity: Int,
    val reservedInFlight: Int,
    val sharedInFlight: Int,
    val interactiveWaiting: Int,
    val backgroundWaiting: Int,
    val interactiveTimeouts: Int,
    val backgroundTimeouts: Int,
) {
    fun toJson(): String =
        """{"total_permits":$totalPermits,"interactive_reserved":$interactiveReserved,"shared_capacity":$sharedCapacity,""" +
            """"reserved_in_flight":$reservedInFlight,"shared_in_flight":$sharedInFlight,""" +
            """"interactive_waiting":$interactiveWaiting,"background_waiting":$backgroundWaiting,""" +
            """"interactive_timeouts":$interactiveTimeouts,"background_timeouts":$backgroundTimeouts}"""
}
