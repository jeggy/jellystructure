package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.PlaybackQoeReport
import dev.jellystructure.shared.tv.QoeStall
import dev.jellystructure.shared.tv.QoeStallCause

/** DEB-5024 (FR-25) — a wait of at least this long after the first frame is a stall. */
internal const val STALL_MIN_MS = 1_000L
/** DEB-5024 (FR-25) — a stall within this long of the first frame is `start`, after it `mid`. */
private const val START_PHASE_MS = 10_000L
/** A seek, a pause or a resume explains a wait that begins within this long after it (AVPlay answers one at once). */
private const val VIEWER_WAIT_WINDOW_MS = 3_000L
/** AVPlay fires a bitrate and a resolution change for one rung switch; two inside this window are one switch. */
private const val SWITCH_MERGE_MS = 1_500L
/** DEB-5024 (FR-27) — a second stall this soon after the first, on the lowest rung, asks for a lower one. */
private const val REPEAT_STALL_MS = 120_000L
/** DEB-5024 (FR-27) — one stall this long, on the lowest rung, asks for a lower one. */
private const val LONG_STALL_MS = 4_000L
/** DEB-5024 (FR-32) — a position that has not moved for [STALL_MIN_MS] while AVPlay says it plays is a stall; a freeze this long restreams. */
private const val FROZEN_RESTREAM_MS = 5_000L
/** DEB-5024 (FR-32) — the position is not read for this long after a pause, a seek or a restream (AVPlay answers them at once, then settles). */
private const val POSITION_QUIET_MS = 2_000L
/** DEB-5024 (FR-32) — after a freeze asks for a restream the watch waits this long for the answer to open; a failed ask resumes it, and the once-per-item flag stops a second. */
private const val RESTREAM_ANSWER_WAIT_MS = 10_000L
/** DEB-5024 (FR-34) — the sliding window of wall time against position that a crawling play is measured over. */
private const val CRAWL_WINDOW_MS = 10_000L
/** DEB-5024 (FR-34) — a window whose position advanced less than this share (percent) of its wall time is one stall. */
private const val CRAWL_MIN_ADVANCE_PCT = 70L
/** R381 — the report carries the item's newest 20. */
private const val MAX_STALLS = 20

/**
 * DEB-5024 (FR-25) — what one item went through on a Samsung's AVPlay, in the shape Android and the receiver report
 * (DEB-4541 FR-3): `per_item = true`, every count this item's own. Events in, reports out, no AVPlay and no clock of its own
 * (a clock is passed in), so what counts as a stall is decided here and nowhere else.
 *
 * - An **item** is a Jellyfin item: [beginItem] resets every count. A restream of the same item ([restreamed]: an audio or
 *   subtitle pick, FR-27's step-down) keeps them, and its own start is not a stall.
 * - The **first frame** is the first moving play time while no wait is open; a wait before it is the start, never a stall.
 * - A wait after it that lasts [STALL_MIN_MS] or more is a stall, unless a seek, a pause or a resume explains it.
 * - A report goes out when a stall ends, at a rung switch, and at [end]. A failed post is the post's own business ([post]).
 * - **FR-27:** once per item, a stall ends with [askLowerRung] when the playing rung is the lowest and it was a second stall
 *   within 2 minutes or one of 4 s or more. The report goes out first.
 * - **FR-32:** AVPlay does not always say when it stalls, so [watchPosition] reads the position itself. One that has not moved
 *   for 1 s while playing is a stall of its own (`buffer_empty` when the path was under the rung, else `frozen_with_buffer`),
 *   and at 5 s it is [askFrozenRestream]ed, once per item, with the rung as the bound.
 * - **FR-34:** a play that moves, but under 70 % of real time over a 10 s window, is one `buffer_empty` stall of the shortfall
 *   (wall − advance). It counts toward FR-27's trigger and never restreams by itself.
 */
internal class ScreenQoe(
    private val clock: () -> Long,
    /** AVPlay's `CURRENT_BANDWIDTH` when it answers a number, otherwise null. */
    private val currentBandwidthBps: () -> Long?,
    /** FR-27 — true when nothing in the master is below the rung playing now (AVPlay's `AVAILABLE_BITRATE`; no answer = true). */
    private val onLowestRung: () -> Boolean,
    /** FR-27 — restream this item at its position, to be answered with a ladder. */
    private val askLowerRung: () -> Unit,
    /** FR-32 — the bitrates of the master's rungs (AVPlay's `AVAILABLE_BITRATE`), for the bitrate of a copy that has no ladder. */
    private val availableBitratesBps: () -> List<Long>,
    /** FR-32 — restream this item at its position under the bound ([Long], the playing rung's bitrate; null when nothing is known). */
    private val askFrozenRestream: (belowBps: Long?) -> Unit,
    /** FR-32 — a freeze is on screen (true) or over (false), so the buffering view (R218) covers it. */
    private val frozenView: (Boolean) -> Unit,
    private val post: (PlaybackQoeReport) -> Unit,
) {
    // ── this session (kept apart from the item's own) ──
    private var sessionRebufferCount = 0
    private var sessionRebufferMs = 0L

    // ── this item ──
    private var itemId: String? = null
    private var startVariantBps: Long? = null
    private var directPlay = false
    private var linkKind = "unknown"
    private var rebufferCount = 0
    private var rebufferMs = 0L
    private var frozenCount = 0
    private var frozenMs = 0L
    private val stalls = ArrayDeque<QoeStall>()
    private var switchesDown = 0
    private var switchesUp = 0
    private var variantBps: Long? = null
    private var lastSwitchAtMs = -1L
    private var firstFrameMs: Long? = null
    private var lastStallEndMs = -1L
    private var askedLowerRung = false
    private var frozenRestreamed = false
    private var recoveryAtMs = -1L
    private var recoveryAfterMs = 0L
    private var recoveryPositionMs = 0L
    private var recoveryRungBps: Long? = null

    // ── this load (one stream of the item) ──
    private var loadAtMs = 0L
    private var firstFrameAtMs = -1L
    // DEB-5394 (FR-37) — the three spans of this load's start; null = that step did not happen (a restream has no startPlayback of its own).
    private var startRequestMs: Long? = null
    private var openToPreparedMs: Long? = null
    private var preparedAtMs = -1L
    private var lastPlayTimeMs = -1L
    private var viewerActionAtMs = -1L
    private var paused = false
    private var waitStartMs = -1L
    private var waitCaused = false
    private var waitPositionMs = 0L
    private var waitVariantBps: Long? = null
    // FR-32 — the position watch: the last position read and when it was first seen, and the freeze open on it (-1 = none).
    private var watchPositionMs = -1L
    private var watchSinceMs = -1L
    private var quietUntilMs = -1L
    // FR-34 — the crawl window: when it opened and the position then (-1 = no window open).
    private var crawlSinceMs = -1L
    private var crawlPositionMs = 0L
    private var frozenStartMs = -1L
    private var frozenPositionMs = 0L
    private var frozenCause = QoeStallCause.FROZEN_WITH_BUFFER
    private var frozenBandwidthBps: Long? = null
    private var frozenRungBps: Long? = null

    val active: Boolean get() = itemId != null

    /** A new item: every count starts from zero. Call as the stream is opened. */
    fun beginItem(itemId: String, startVariantBps: Long?, directPlay: Boolean, linkKind: String) {
        this.itemId = itemId
        this.startVariantBps = startVariantBps
        this.directPlay = directPlay
        this.linkKind = linkKind
        rebufferCount = 0; rebufferMs = 0L
        frozenCount = 0; frozenMs = 0L
        stalls.clear()
        switchesDown = 0; switchesUp = 0
        variantBps = startVariantBps
        lastSwitchAtMs = -1L
        firstFrameMs = null
        lastStallEndMs = -1L; askedLowerRung = false
        frozenRestreamed = false; recoveryAtMs = -1L
        resetLoad()
    }

    /** DEB-5394 (FR-37) — `startPlayback`'s round trip for the load [beginItem] just began. */
    fun startRequested(ms: Long) { if (itemId != null) startRequestMs = ms }

    /** DEB-5394 (FR-37) — AVPlay's `prepareAsync` succeeded [ms] after `open()`. */
    fun prepared(ms: Long) {
        if (itemId == null) return
        openToPreparedMs = ms
        preparedAtMs = clock()
    }

    /** The same item opened again (a restream): its first wait is its start, and its counts stay. */
    fun restreamed() {
        if (itemId == null) return
        if (frozenStartMs >= 0) endFrozen(clock(), count = true, ladder = false, hideView = false)   // a freeze an audio pick or FR-27 replaced
        resetLoad()
        quietUntilMs = clock() + POSITION_QUIET_MS
    }

    private fun resetLoad() {
        loadAtMs = clock()
        firstFrameAtMs = -1L
        startRequestMs = null; openToPreparedMs = null; preparedAtMs = -1L
        lastPlayTimeMs = -1L
        viewerActionAtMs = -1L
        paused = false
        waitStartMs = -1L
        watchPositionMs = -1L; frozenStartMs = -1L; quietUntilMs = -1L
        crawlSinceMs = -1L
    }

    /** A seek, or a play or a pause the viewer made: the wait that follows within moments is theirs, not a stall. */
    fun viewerAction() { viewerActionAtMs = clock(); quietFor(POSITION_QUIET_MS) }

    fun paused(on: Boolean) { paused = on; quietFor(POSITION_QUIET_MS) }

    private fun quietFor(ms: Long) {
        quietUntilMs = clock() + ms
        if (frozenStartMs >= 0) endFrozen(clock(), count = true, ladder = false, hideView = true)
    }

    fun bufferingStarted(positionMs: Long) {
        if (itemId == null || waitStartMs >= 0) return
        val now = clock()
        if (frozenStartMs >= 0) endFrozen(now, count = false, ladder = false, hideView = false)   // AVPlay says it waits: its wait is the one counted
        watchPositionMs = -1L
        crawlSinceMs = -1L
        waitStartMs = now
        waitPositionMs = positionMs
        waitVariantBps = currentBandwidthBps()
        waitCaused = firstFrameAtMs < 0 || paused || now - viewerActionAtMs <= VIEWER_WAIT_WINDOW_MS
    }

    fun bufferingCompleted() {
        if (itemId == null || waitStartMs < 0) return
        val now = clock()
        val length = now - waitStartMs
        val previousEnd = lastStallEndMs
        if (!closeWait(now)) return
        stallEnded(now, length, previousEnd, ladder = true)
    }

    /** A stall was counted: the report goes out, then FR-27 (once per item, on the lowest rung, a second stall within 2 minutes or one of 4 s). */
    private fun stallEnded(now: Long, length: Long, previousEnd: Long, ladder: Boolean) {
        lastStallEndMs = now
        postReport(null)
        if (!ladder) return
        val repeated = previousEnd >= 0 && now - previousEnd <= REPEAT_STALL_MS
        if (!askedLowerRung && (repeated || length >= LONG_STALL_MS) && onLowestRung()) {
            askedLowerRung = true
            askLowerRung()
        }
    }

    /**
     * FR-32 — one reading of the position, every 500 ms ([avPlaying]: AVPlay's own state is PLAYING). Not a stall: a pause, a seek
     * or a restream in the last 2 s, a wait AVPlay has said it is in (`onbufferingstart` open), or a play before its first frame.
     */
    fun watchPosition(positionMs: Long, avPlaying: Boolean) {
        if (itemId == null) return
        val now = clock()
        if (!avPlaying || paused || firstFrameAtMs < 0 || waitStartMs >= 0 || now < quietUntilMs) {
            if (frozenStartMs >= 0) endFrozen(now, count = waitStartMs < 0, ladder = false, hideView = true)
            watchPositionMs = -1L
            crawlSinceMs = -1L
            return
        }
        if (frozenStartMs < 0) watchCrawl(now, positionMs) else crawlSinceMs = -1L
        if (watchPositionMs < 0 || positionMs != watchPositionMs) {
            if (frozenStartMs >= 0) endFrozen(now, count = true, ladder = true, hideView = true)   // it moved again
            watchPositionMs = positionMs; watchSinceMs = now
            return
        }
        if (frozenStartMs < 0) {
            if (now - watchSinceMs < STALL_MIN_MS) return
            startFrozen(positionMs, watchSinceMs)
        }
        if (!frozenRestreamed && now - frozenStartMs >= FROZEN_RESTREAM_MS) restreamFrozen(now)
    }

    /**
     * FR-34 — a window of [CRAWL_WINDOW_MS] of play, none of it paused, seeked, restreamed or inside a stall (the caller resets
     * it on each of those). Under [CRAWL_MIN_ADVANCE_PCT] of real time it is one stall, and the window starts over.
     */
    private fun watchCrawl(now: Long, positionMs: Long) {
        if (crawlSinceMs < 0) { crawlSinceMs = now; crawlPositionMs = positionMs; return }
        val wall = now - crawlSinceMs
        if (wall < CRAWL_WINDOW_MS) return
        val advance = positionMs - crawlPositionMs
        val startMs = crawlSinceMs
        val startPositionMs = crawlPositionMs
        crawlSinceMs = now; crawlPositionMs = positionMs
        if (advance < 0 || advance * 100 >= wall * CRAWL_MIN_ADVANCE_PCT) return   // a jump back is a seek nobody announced, not a crawl
        val length = wall - advance
        val previousEnd = lastStallEndMs
        val after = startMs - firstFrameAtMs
        rebufferCount++; rebufferMs += length
        sessionRebufferCount++; sessionRebufferMs += length
        addStall(QoeStall(
            afterFirstFrameMs = after, positionMs = startPositionMs, bufferedMs = 0, durationMs = length,
            phase = phaseOf(after), variantBps = currentBandwidthBps(), cause = QoeStallCause.BUFFER_EMPTY,
        ))
        stallEnded(now, length, previousEnd, ladder = true)
    }

    /** The rung playing now: the one the last switch (or the ticket's start) named, else the master's top, else what AVPlay measures. */
    private fun playingRungBps(): Long? =
        variantBps?.takeIf { it > 0 } ?: availableBitratesBps().maxOrNull()?.takeIf { it > 0 } ?: currentBandwidthBps()

    private fun startFrozen(positionMs: Long, sinceMs: Long) {
        frozenStartMs = sinceMs
        frozenPositionMs = positionMs
        frozenBandwidthBps = currentBandwidthBps()
        frozenRungBps = playingRungBps()
        val rung = frozenRungBps
        val path = frozenBandwidthBps
        frozenCause = if (path != null && rung != null && path < rung) QoeStallCause.BUFFER_EMPTY else QoeStallCause.FROZEN_WITH_BUFFER
        frozenView(true)
    }

    /** Counts the open freeze as a stall (the same counts as a wait AVPlay announced, and `frozen_*` for the kind with buffer in hand). */
    private fun endFrozen(now: Long, count: Boolean, ladder: Boolean, hideView: Boolean) {
        val start = frozenStartMs
        frozenStartMs = -1L
        watchPositionMs = -1L
        if (hideView) frozenView(false)
        val length = now - start
        if (!count || length < STALL_MIN_MS || firstFrameAtMs < 0) return
        val previousEnd = lastStallEndMs
        val after = start - firstFrameAtMs
        rebufferCount++; rebufferMs += length
        sessionRebufferCount++; sessionRebufferMs += length
        if (frozenCause == QoeStallCause.FROZEN_WITH_BUFFER) { frozenCount++; frozenMs += length }
        addStall(QoeStall(
            afterFirstFrameMs = after, positionMs = frozenPositionMs, bufferedMs = 0, durationMs = length,
            phase = phaseOf(after), variantBps = frozenBandwidthBps, cause = frozenCause, rungBps = frozenRungBps,
        ))
        stallEnded(now, length, previousEnd, ladder)
    }

    /** A freeze of 5 s: the report goes out, then the restream under the rung that froze, once per item; the recovery entry is written when the new stream moves. */
    private fun restreamFrozen(now: Long) {
        frozenRestreamed = true
        askedLowerRung = true   // it is the step FR-27 would ask for: the stall that ends it must not ask for a second restream
        val below = frozenRungBps
        recoveryAtMs = now
        recoveryAfterMs = frozenStartMs - firstFrameAtMs
        recoveryPositionMs = frozenPositionMs
        recoveryRungBps = below
        endFrozen(now, count = true, ladder = false, hideView = false)   // the buffering view stays until the new stream plays
        quietUntilMs = now + RESTREAM_ANSWER_WAIT_MS
        askFrozenRestream(below)
    }

    /** FR-32 — the recovery entry: from the ask to the new stream's first movement (or to the end of the item), reason `frozen`. */
    private fun finishRecovery(now: Long) {
        val at = recoveryAtMs
        recoveryAtMs = -1L
        val length = now - at
        if (length < STALL_MIN_MS) return
        addStall(QoeStall(
            afterFirstFrameMs = recoveryAfterMs, positionMs = recoveryPositionMs, bufferedMs = 0, durationMs = length,
            phase = phaseOf(recoveryAfterMs), variantBps = currentBandwidthBps(), cause = QoeStallCause.RECOVERY,
            rungBps = recoveryRungBps, reason = "frozen",
        ))
        postReport(null)
    }

    private fun phaseOf(afterMs: Long) = if (afterMs < START_PHASE_MS) "start" else "mid"

    private fun addStall(stall: QoeStall) {
        stalls.addLast(stall)
        while (stalls.size > MAX_STALLS) stalls.removeFirst()
    }

    /** AVPlay's `oncurrentplaytime`: the first value that moves while nothing is waiting is the first frame. */
    fun playTime(ms: Long) {
        val before = lastPlayTimeMs
        lastPlayTimeMs = ms
        if (itemId == null || waitStartMs >= 0) return
        val moved = before >= 0 && ms > before
        if (firstFrameAtMs < 0 && moved) {
            firstFrameAtMs = clock()
            if (firstFrameMs == null) firstFrameMs = firstFrameAtMs - loadAtMs
            logStartSpans()
        }
        if (recoveryAtMs >= 0 && moved) finishRecovery(clock())   // FR-32 — the stream a freeze asked for is moving
    }

    /** DEB-5394 (FR-37) — one console line per start, the item's id and three spans in ms (`-` = the step did not happen). Nothing else. */
    private fun logStartSpans() {
        val sinceOpen = openToPreparedMs
        val toFirstFrame = if (preparedAtMs >= 0) (firstFrameAtMs - preparedAtMs).toString() else "-"
        console.log("Ravilo screen: start-spans item=$itemId request=${startRequestMs ?: "-"} open_to_prepared=${sinceOpen ?: "-"} prepared_to_first_frame=$toFirstFrame buffer_s=${avPlayBufferSeconds() ?: "-"}")
    }

    /** `onevent`: a bitrate or resolution change is a rung switch; its direction is the playing bitrate against the last known. */
    fun playerEvent(type: String, data: String?) {
        if (itemId == null || firstFrameAtMs < 0) return
        if (type != "PLAYER_MSG_BITRATE_CHANGE" && type != "PLAYER_MSG_RESOLUTION_CHANGED") return
        val now = clock()
        if (now - lastSwitchAtMs <= SWITCH_MERGE_MS) return
        val next = currentBandwidthBps() ?: data?.trim()?.toLongOrNull()?.takeIf { type == "PLAYER_MSG_BITRATE_CHANGE" } ?: return
        val last = variantBps
        variantBps = next
        if (last == null || next == last) return
        lastSwitchAtMs = now
        if (next < last) switchesDown++ else switchesUp++
        postReport(null)
    }

    /**
     * The item is over (stop, Back, hiding the app, the end of the stream, a start that failed): its final report goes out.
     * [failure] is `start_failure_status` for a start that never reached a picture (the HTTP status, or -1); it is ignored once
     * a first frame was seen. A second call with nothing open does nothing.
     */
    fun end(failure: Int? = null) {
        if (itemId == null) return
        if (waitStartMs >= 0) closeWait(clock())
        if (frozenStartMs >= 0) endFrozen(clock(), count = true, ladder = false, hideView = true)
        if (recoveryAtMs >= 0) finishRecovery(clock())
        postReport(failure?.takeIf { firstFrameMs == null })
        itemId = null
    }

    /** Closes the open wait; true when it was a stall (and was counted). */
    private fun closeWait(now: Long): Boolean {
        val length = now - waitStartMs
        val counted = !waitCaused && length >= STALL_MIN_MS && firstFrameAtMs >= 0
        if (counted) {
            val after = waitStartMs - firstFrameAtMs
            rebufferCount++; rebufferMs += length
            sessionRebufferCount++; sessionRebufferMs += length
            addStall(QoeStall(
                afterFirstFrameMs = after, positionMs = waitPositionMs, bufferedMs = 0, durationMs = length,
                phase = phaseOf(after), variantBps = waitVariantBps, cause = QoeStallCause.BUFFER_EMPTY,
            ))
        }
        waitStartMs = -1L
        return counted
    }

    private fun postReport(failure: Int?) {
        val id = itemId ?: return
        post(PlaybackQoeReport(
            itemId = id,
            rebufferCount = rebufferCount,
            rebufferMs = rebufferMs,
            directPlay = directPlay,
            linkKind = linkKind,
            startFailureStatus = failure,
            variantSwitchesDown = switchesDown,
            variantSwitchesUp = switchesUp,
            variantBandwidthBps = variantBps,
            firstFrameMs = firstFrameMs,
            startVariantBps = startVariantBps,
            perItem = true,
            stalls = stalls.toList(),
            frozenCount = frozenCount,
            frozenMs = frozenMs,
            sessionRebufferCount = sessionRebufferCount,
            sessionRebufferMs = sessionRebufferMs,
        ))
    }
}
