package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.screens.CLIMB_BLOCK_AFTER_RECOVERY_MS
import dev.jellystructure.ravilo.ui.seams.PlayerLadderHints
import org.jetbrains.skia.Image
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DEB-3214 (DEB-3093 FR-9, FR-5, FR-6) — what the Mac's player decides when trouble comes, on the pure pieces: AVPlayer itself, the Swift
 * library and the picture are the QA row's. [MacLadderPeak] is driven through a recording engine; the stall count is the library's.
 */
class MacTroubleTest {
    private class RecordingEngine : DesktopEngine {
        val peaks = mutableListOf<Double>()
        override val available = true
        override val loaded = true
        override fun load(url: String, mime: String, startMs: Long) {}
        override fun play() {}
        override fun pause() {}
        override fun seekTo(ms: Long) {}
        override fun setRate(rate: Float) {}
        override fun setVolume(volume: Float) {}
        override fun selectAudio(index: Int) {}
        override val state: MacPlayerState = MacPlayerState.EMPTY
        override fun error(): String? = null
        override fun debug(): String = ""
        override fun audioOptions(): String? = null
        override fun takeFrame(): Image? = null
        override fun release() {}
        override val decoderName = "fake"
        override fun setPeakBitrate(bps: Double) { peaks += bps }
    }

    private val rungs = listOf(2_506_000L, 6_256_000L, 12_256_000L, 18_256_000L)
    private fun state(stalls: Long = 0, buffered: Long = 0, position: Long = 0, firstFrame: Boolean = true, item: Int = 1) =
        MacPlayerState.EMPTY.copy(stalls = stalls, bufferedMs = buffered, positionMs = position, firstFrame = firstFrame, item = item, wantsPlay = true, timeControl = 2)

    private var savedStart: Long? = null
    private var savedOurs = false
    private var savedBlock = 0L

    @BeforeTest fun remember() {
        savedStart = PlayerLadderHints.startVariantBps; savedOurs = PlayerLadderHints.oursEncoder; savedBlock = PlayerLadderHints.climbBlockedUntilMs
        PlayerLadderHints.startVariantBps = 18_256_000L; PlayerLadderHints.oursEncoder = true; PlayerLadderHints.climbBlockedUntilMs = 0L
    }

    @AfterTest fun restore() {
        PlayerLadderHints.startVariantBps = savedStart; PlayerLadderHints.oursEncoder = savedOurs; PlayerLadderHints.climbBlockedUntilMs = savedBlock
    }

    private fun peak(engine: RecordingEngine) = MacLadderPeak(engine).also { it.rungs = rungs }

    @Test
    fun `a stall caps the peak one rung down at once and blocks the climb for two minutes`() {
        val engine = RecordingEngine(); val p = peak(engine)
        p.tick(state(buffered = 3_000), nowMs = 1_000)   // the start: the server's rung
        assertEquals(listOf(18_256_000L * MacLadderPeak.PEAK_HEADROOM), engine.peaks)
        p.onStall(state(stalls = 1), nowMs = 50_000)
        assertEquals(12_256_000L * MacLadderPeak.PEAK_HEADROOM, engine.peaks.last(), "one rung below the start rung, not the lowest")
        assertEquals(50_000 + CLIMB_BLOCK_AFTER_RECOVERY_MS, PlayerLadderHints.climbBlockedUntilMs)
        // 40 s buffered on our encoder would climb straight to the top; the block holds it.
        p.tick(state(stalls = 1, buffered = 40_000), nowMs = 52_000)
        assertEquals(2, engine.peaks.size, "nothing climbed inside the block")
        p.tick(state(stalls = 1, buffered = 41_000), nowMs = 50_000 + CLIMB_BLOCK_AFTER_RECOVERY_MS + 1)
        assertEquals(18_256_000L * MacLadderPeak.PEAK_HEADROOM, engine.peaks.last(), "and the ordinary climb is back after it")
    }

    @Test
    fun `a stall reported while AVPlayer waits caps once and blocks a buffered climb`() {
        val engine = RecordingEngine(); val p = peak(engine)
        p.tick(state(buffered = 10_000), nowMs = 1_000)
        // The native state now includes access-log stalls even when PlaybackStalled was not posted.
        val waiting = state(stalls = 1, buffered = 40_000).copy(timeControl = 1)
        p.onStall(waiting, nowMs = 50_000)
        p.tick(waiting, nowMs = 50_000)
        p.onStall(waiting, nowMs = 51_000)
        p.tick(waiting, nowMs = 51_000)
        assertEquals(listOf(18_256_000L * MacLadderPeak.PEAK_HEADROOM,
            12_256_000L * MacLadderPeak.PEAK_HEADROOM), engine.peaks)
        assertEquals(50_000 + CLIMB_BLOCK_AFTER_RECOVERY_MS, PlayerLadderHints.climbBlockedUntilMs)
    }

    @Test
    fun `the same stall is answered once and the next one caps again`() {
        val engine = RecordingEngine(); val p = peak(engine)
        p.onStall(state(stalls = 1), nowMs = 1_000)   // before any tick: the start rung is the one capped
        p.onStall(state(stalls = 1), nowMs = 2_000)
        assertEquals(1, engine.peaks.size)
        assertEquals(12_256_000L * MacLadderPeak.PEAK_HEADROOM, engine.peaks.single())
        p.onStall(state(stalls = 2), nowMs = 9_000)
        assertEquals(6_256_000L * MacLadderPeak.PEAK_HEADROOM, engine.peaks.last())
    }

    @Test
    fun `a stall on the lowest rung, off a ladder, or on a new item's count of zero caps nothing`() {
        val engine = RecordingEngine(); val p = peak(engine)
        PlayerLadderHints.startVariantBps = 2_506_000L
        p.onStall(state(stalls = 1), nowMs = 1_000)
        assertTrue(engine.peaks.isEmpty(), "nothing lower to ask for, and no climb block either")
        assertEquals(0L, PlayerLadderHints.climbBlockedUntilMs)
        val flat = MacLadderPeak(engine)   // no master read: not a ladder
        flat.onStall(state(stalls = 3), nowMs = 1_000)
        assertTrue(engine.peaks.isEmpty())
        // The library zeroes the count with each load: the first stall of the next item is a stall again.
        PlayerLadderHints.startVariantBps = 18_256_000L
        val q = peak(engine)
        q.onStall(state(stalls = 2), nowMs = 1_000)
        q.onStall(state(stalls = 0), nowMs = 2_000)
        q.onStall(state(stalls = 1), nowMs = 3_000)
        assertEquals(2, engine.peaks.size)
    }

    @Test
    fun `a replacement holds the play's own clock until the new item's first frame`() {
        val h = MacHeldFrame()
        val loading = state(firstFrame = false, item = 0)   // the new item: position 0, no duration, no frame
        h.begin(shown = true, startMs = 115_000, durationMs = 7_200_000, nowMs = 0)
        assertEquals(115_000L, h.positionMs(loading, nowMs = 500))
        assertEquals(7_200_000L, h.durationMs(loading, nowMs = 500))
        // The new item has read its length: the window keeps the position, the length is the item's.
        assertEquals(7_201_000L, h.durationMs(loading.copy(durationMs = 7_201_000), nowMs = 900))
        // A seek inside the window moves where the replacement starts.
        h.seek(300_000)
        assertEquals(300_000L, h.positionMs(loading, nowMs = 1_000))
        // The first frame ends it: both are the player's.
        val playing = state(position = 301_500, firstFrame = true).copy(durationMs = 7_201_000)
        assertEquals(301_500L, h.positionMs(playing, nowMs = 1_800))
        assertFalse(h.holding(loading, nowMs = 1_900), "a window that has closed stays closed")
    }

    @Test
    fun `a window also ends on a failure or after ten seconds, and a load with no picture on screen opens none`() {
        val loading = state(firstFrame = false, item = 0)
        val h = MacHeldFrame()
        h.begin(shown = true, startMs = 5_000, durationMs = 100_000, nowMs = 0)
        assertTrue(h.holding(loading, nowMs = MacHeldFrame.MAX_HOLD_MS))
        assertFalse(h.holding(loading, nowMs = MacHeldFrame.MAX_HOLD_MS + 1), "the item never came: the player reports what it has")
        h.begin(shown = true, startMs = 5_000, durationMs = 100_000, nowMs = 0)
        assertFalse(h.holding(loading.copy(item = 2), nowMs = 100), "a failed item is the error screen's, not a held clock")
        h.begin(shown = false, startMs = 5_000, durationMs = 100_000, nowMs = 0)
        assertEquals(0L, h.positionMs(loading, nowMs = 100), "the first load of a play reads the player")
        assertEquals(0L, h.durationMs(loading, nowMs = 100))
    }
}
