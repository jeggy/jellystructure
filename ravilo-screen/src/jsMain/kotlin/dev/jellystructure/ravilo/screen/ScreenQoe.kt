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
 */
internal class ScreenQoe(
    private val clock: () -> Long,
    /** AVPlay's `CURRENT_BANDWIDTH` when it answers a number, otherwise null. */
    private val currentBandwidthBps: () -> Long?,
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
    private val stalls = ArrayDeque<QoeStall>()
    private var switchesDown = 0
    private var switchesUp = 0
    private var variantBps: Long? = null
    private var lastSwitchAtMs = -1L
    private var firstFrameMs: Long? = null

    // ── this load (one stream of the item) ──
    private var loadAtMs = 0L
    private var firstFrameAtMs = -1L
    private var lastPlayTimeMs = -1L
    private var viewerActionAtMs = -1L
    private var paused = false
    private var waitStartMs = -1L
    private var waitCaused = false
    private var waitPositionMs = 0L
    private var waitVariantBps: Long? = null

    val active: Boolean get() = itemId != null

    /** A new item: every count starts from zero. Call as the stream is opened. */
    fun beginItem(itemId: String, startVariantBps: Long?, directPlay: Boolean, linkKind: String) {
        this.itemId = itemId
        this.startVariantBps = startVariantBps
        this.directPlay = directPlay
        this.linkKind = linkKind
        rebufferCount = 0; rebufferMs = 0L
        stalls.clear()
        switchesDown = 0; switchesUp = 0
        variantBps = startVariantBps
        lastSwitchAtMs = -1L
        firstFrameMs = null
        resetLoad()
    }

    /** The same item opened again (a restream): its first wait is its start, and its counts stay. */
    fun restreamed() {
        if (itemId == null) return
        resetLoad()
    }

    private fun resetLoad() {
        loadAtMs = clock()
        firstFrameAtMs = -1L
        lastPlayTimeMs = -1L
        viewerActionAtMs = -1L
        paused = false
        waitStartMs = -1L
    }

    /** A seek, or a play or a pause the viewer made: the wait that follows within moments is theirs, not a stall. */
    fun viewerAction() { viewerActionAtMs = clock() }

    fun paused(on: Boolean) { paused = on }

    fun bufferingStarted(positionMs: Long) {
        if (itemId == null || waitStartMs >= 0) return
        val now = clock()
        waitStartMs = now
        waitPositionMs = positionMs
        waitVariantBps = currentBandwidthBps()
        waitCaused = firstFrameAtMs < 0 || paused || now - viewerActionAtMs <= VIEWER_WAIT_WINDOW_MS
    }

    fun bufferingCompleted() {
        if (itemId == null || waitStartMs < 0) return
        if (closeWait(clock())) postReport(null)
    }

    /** AVPlay's `oncurrentplaytime`: the first value that moves while nothing is waiting is the first frame. */
    fun playTime(ms: Long) {
        val before = lastPlayTimeMs
        lastPlayTimeMs = ms
        if (itemId == null || firstFrameAtMs >= 0 || waitStartMs >= 0) return
        if (before >= 0 && ms > before) {
            firstFrameAtMs = clock()
            if (firstFrameMs == null) firstFrameMs = firstFrameAtMs - loadAtMs
        }
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
            stalls.addLast(QoeStall(
                afterFirstFrameMs = after, positionMs = waitPositionMs, bufferedMs = 0, durationMs = length,
                phase = if (after < START_PHASE_MS) "start" else "mid",
                variantBps = waitVariantBps, cause = QoeStallCause.BUFFER_EMPTY,
            ))
            while (stalls.size > MAX_STALLS) stalls.removeFirst()
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
            sessionRebufferCount = sessionRebufferCount,
            sessionRebufferMs = sessionRebufferMs,
        ))
    }
}
