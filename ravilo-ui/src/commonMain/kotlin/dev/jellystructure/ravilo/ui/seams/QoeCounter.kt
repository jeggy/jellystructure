package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.QoeStall

/**
 * R381 — a stall is counted once, per item, and only when it is a stall in the picture.
 *
 * Before this, the Android player's QoE counters were the SESSION's running totals (never reset when a binge reused the
 * engine for the next episode) and its first-frame flag was set once, so the next item's normal cold start counted as a
 * stall: 215 of Stue TV's 323 direct-play rows only repeated the previous item's totals, and 91 of its 100 new "stalls"
 * were the next episode's start (R381's measurement, 2026-10-08).
 *
 * A pure state machine: events in, counts out, no Media3 and no clock of its own (every event carries `nowMs`), so it is
 * unit-tested in commonTest and shared by every player (Android now; the desktop players and the web can feed it later).
 *
 * - An **item** is a Jellyfin item: [beginItem] with a new key resets the item's counts; the same key again (a restream:
 *   a subtitle or audio switch, R284) keeps them, so a real stall is not wiped by switching subtitles.
 * - A **load** is one stream of that item: its first frame is per load, so a restream's own start is not a stall.
 * - A wait (buffering) after the load's first frame is a **stall** unless a seek, a track switch, a variant switch, an
 *   engine rebuild (R292's return from the background, R379's decoder restart) or a surface recovery (R220) explains it;
 *   each of those is counted under its own name in [waits], never hidden, never a stall.
 */
class QoeCounter {
    private var itemKey: String? = null

    // ── this item ──
    var rebufferCount = 0; private set
    var rebufferMs = 0L; private set
    private val stalls = ArrayDeque<QoeStall>()
    private val waits = mutableMapOf<String, Int>()
    private var itemFirstFrameAtMs = -1L

    // ── this session (FR-R381-1: kept apart from the item's own) ──
    var sessionRebufferCount = 0; private set
    var sessionRebufferMs = 0L; private set

    // ── this load ──
    private var firstFrameRendered = false
    private var pendingCause: String? = null
    private var variantSwitchAtMs = Long.MIN_VALUE
    private var waitStartMs = -1L
    private var waitIsStall = false
    private var waitPositionMs = 0L
    private var waitBufferedMs = 0L
    private var waitVariantBps: Long? = null

    /** A new item resets its counts; the same item again (a restream) keeps them. Returns true for a new item. */
    fun beginItem(key: String): Boolean {
        if (key == itemKey) return false
        itemKey = key
        rebufferCount = 0
        rebufferMs = 0L
        stalls.clear()
        waits.clear()
        itemFirstFrameAtMs = -1L
        itemTimeToFirstFrameMs = null
        resetLoad()
        return true
    }

    /** A new stream (of the current item) begins: its first wait is its start, not a stall. [nowMs] (the same clock as
     *  [firstFrame]'s) times the item's first load to its first frame (309 FR-309-11); -1 = not timed. */
    fun load(nowMs: Long = -1L) {
        resetLoad()
        if (itemTimeToFirstFrameMs == null) loadAtMs = nowMs
    }

    // 309 (FR-309-11) — when the item's first load began, and its time to the first frame.
    private var loadAtMs = -1L
    private var itemTimeToFirstFrameMs: Long? = null

    /** 309 (FR-309-11) — this item's time from its first load to its first frame (ms), or null before it. */
    fun firstFrameMs(): Long? = itemTimeToFirstFrameMs

    /** The engine was rebuilt (R292, R379): its first wait is a rebuild, not a stall. */
    fun engineRebuilt() {
        resetLoad()
        pendingCause = CAUSE_REBUILD
    }

    fun firstFrame(nowMs: Long) {
        firstFrameRendered = true
        if (itemFirstFrameAtMs < 0) itemFirstFrameAtMs = nowMs
        if (itemTimeToFirstFrameMs == null && loadAtMs >= 0 && nowMs >= loadAtMs) itemTimeToFirstFrameMs = nowMs - loadAtMs
    }

    fun seek() { pendingCause = CAUSE_SEEK }
    fun trackSwitch() { pendingCause = CAUSE_TRACK }
    fun surfaceRecovery() { pendingCause = CAUSE_RECOVERY }

    /** A different video variant reached the playhead (308): a wait within [VARIANT_WINDOW_MS] of it is the switch. */
    fun variantSwitched(nowMs: Long) { variantSwitchAtMs = nowMs }

    /** The player started waiting for data. */
    fun buffering(nowMs: Long, positionMs: Long, bufferedAheadMs: Long, variantBps: Long?) {
        if (waitStartMs >= 0) return   // already waiting
        val cause = when {
            !firstFrameRendered -> if (pendingCause == CAUSE_REBUILD) CAUSE_REBUILD else CAUSE_START
            pendingCause != null -> pendingCause
            nowMs - variantSwitchAtMs in 0..VARIANT_WINDOW_MS -> CAUSE_VARIANT
            else -> null
        }
        pendingCause = null
        if (cause != null) {
            waits[cause] = (waits[cause] ?: 0) + 1
            waitIsStall = false
        } else {
            waitIsStall = true
            waitPositionMs = positionMs
            waitBufferedMs = bufferedAheadMs.coerceAtLeast(0)
            waitVariantBps = variantBps
        }
        waitStartMs = nowMs
    }

    /** The player is playing again: a stall ends and is counted. */
    fun ready(nowMs: Long) {
        if (waitStartMs < 0) return
        if (waitIsStall) {
            val duration = (nowMs - waitStartMs).coerceAtLeast(0)
            rebufferCount++
            rebufferMs += duration
            sessionRebufferCount++
            sessionRebufferMs += duration
            val after = if (itemFirstFrameAtMs >= 0) (waitStartMs - itemFirstFrameAtMs).coerceAtLeast(0) else 0L
            stalls.addLast(QoeStall(
                afterFirstFrameMs = after,
                positionMs = waitPositionMs,
                bufferedMs = waitBufferedMs,
                durationMs = duration,
                phase = if (after < START_PHASE_MS) "start" else "mid",
                variantBps = waitVariantBps,
            ))
            while (stalls.size > MAX_STALLS) stalls.removeFirst()
        }
        waitStartMs = -1L
        waitIsStall = false
    }

    /** Paused, ended or stopped while waiting: the wait is dropped, never counted as a stall. */
    fun interrupted() {
        waitStartMs = -1L
        waitIsStall = false
    }

    fun stallEvents(): List<QoeStall> = stalls.toList()
    fun waitCounts(): Map<String, Int> = waits.toMap()

    private fun resetLoad() {
        firstFrameRendered = false
        pendingCause = null
        variantSwitchAtMs = Long.MIN_VALUE
        waitStartMs = -1L
        waitIsStall = false
    }

    companion object {
        const val CAUSE_START = "start"
        const val CAUSE_SEEK = "seek"
        const val CAUSE_TRACK = "track_switch"
        const val CAUSE_VARIANT = "variant_switch"
        const val CAUSE_REBUILD = "rebuild"
        const val CAUSE_RECOVERY = "recovery"
        /** FR-R381-3 — a stall this soon after the item's first frame is in the `start` phase. */
        const val START_PHASE_MS = 10_000L
        /** A wait this soon after a variant switch belongs to the switch. */
        const val VARIANT_WINDOW_MS = 3_000L
        const val MAX_STALLS = 20
    }
}
