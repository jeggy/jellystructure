package dev.jellystructure.ravilo.ui.desktop

import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * R329 (FR-R329-2) — one AVPlayer, through the Swift library. Every call is made on Compose's thread (Swing's EDT,
 * `Dispatchers.Main`), and so is the [tick] that lets the library act on what AVFoundation reported (the start seek,
 * AVPlayer's own subtitles off, a queued audio pick, starting). With no library, [available] is false and every
 * call is a no-op: the Linux build browses, the Mac plays.
 */
internal class MacPlayer(private val audioOnly: Boolean) {
    private val lib = MacNative.lib
    private var handle = 0L
    private val raw = LongArray(STATE_FIELDS)
    private var readAtNs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    val available: Boolean get() = lib != null
    val loaded: Boolean get() = handle != 0L

    fun load(url: String, mime: String = "", startMs: Long = 0L) {
        val l = lib ?: return
        if (handle == 0L) handle = l.ravilo_player_create()
        l.ravilo_player_load(handle, url, mime, startMs.coerceAtLeast(0L), if (audioOnly) 1 else 0)
        readAtNs = 0L
        if (ticker?.isActive != true) ticker = scope.launch {
            while (isActive && handle != 0L) {
                lib.ravilo_player_tick(handle)
                delay(TICK_MS)
            }
        }
    }

    fun play() { if (handle != 0L) { lib?.ravilo_player_play(handle); readAtNs = 0L } }
    fun pause() { if (handle != 0L) { lib?.ravilo_player_pause(handle); readAtNs = 0L } }
    fun seekTo(ms: Long) { if (handle != 0L) { lib?.ravilo_player_seek(handle, ms.coerceAtLeast(0L)); readAtNs = 0L } }
    fun setRate(rate: Float) { if (handle != 0L) lib?.ravilo_player_set_rate(handle, rate) }
    fun setVolume(volume: Float) { if (handle != 0L) lib?.ravilo_player_set_volume(handle, volume.coerceIn(0f, 1f)) }
    fun selectAudio(index: Int) { if (handle != 0L) lib?.ravilo_player_select_audio(handle, index) }

    /** The engine goes; this object stays and a later [load] builds a new one (R292's releaseEngine). */
    fun release() {
        ticker?.cancel(); ticker = null
        val h = handle
        handle = 0L
        if (h != 0L) lib?.ravilo_player_release(h)
        java.util.Arrays.fill(raw, 0L)
    }

    /** One snapshot per ~frame: every getter below reads the same answer within [STATE_TTL_NS]. */
    val state: MacPlayerState
        get() {
            val l = lib
            if (l == null || handle == 0L) return MacPlayerState.EMPTY
            val now = System.nanoTime()
            if (now - readAtNs > STATE_TTL_NS) {
                l.ravilo_player_state(handle, raw, raw.size)
                readAtNs = now
            }
            return MacPlayerState.of(raw)
        }

    fun error(): String? = lib?.takeIf { handle != 0L }?.let { MacNative.take(it.ravilo_player_error(handle)) }

    /** The audible group as AVFoundation sees it (name and language per line, `*` on the selected one). */
    fun audioOptions(): String? = lib?.takeIf { handle != 0L }?.let { MacNative.take(it.ravilo_player_audio_options(handle)) }

    // ── FR-R329-1 path (b): frames copied into memory we own, wrapped by Skia without a second copy ──

    private val ring = arrayOfNulls<Memory>(FRAME_BUFFERS)
    /** The buffers a resize replaced: an image still on screen may point into them, so they outlive one resize. */
    private var retired: List<Memory> = emptyList()
    private var ringAt = 0
    private val dims = IntArray(2)

    /**
     * The newest frame as a Skia image, or null when there is none. Three buffers turn in a ring, so the one being
     * written is never one of the two the screen may still be drawing. Must be called on Compose's thread.
     */
    fun takeFrame(): Image? {
        val l = lib ?: return null
        if (handle == 0L || audioOnly) return null
        val slot = ringAt
        var buf = ring[slot]
        var r = l.ravilo_player_copy_frame(handle, buf, buf?.size() ?: 0L, dims)
        if (r == -1 && dims[0] > 0 && dims[1] > 0) {
            // The picture's size changed (or this is the first frame): grow every buffer to it.
            val bytes = dims[0].toLong() * dims[1] * 4
            retired = ring.filterNotNull()
            for (i in ring.indices) ring[i] = Memory(bytes)
            buf = ring[slot]
            r = l.ravilo_player_copy_frame(handle, buf, buf!!.size(), dims)
        }
        if (r != 1 || buf == null) return null
        ringAt = (slot + 1) % FRAME_BUFFERS
        val w = dims[0]; val h = dims[1]
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, Data.makeWithoutCopy(Pointer.nativeValue(buf), w * h * 4, MEMORY_OWNER), w * 4)
    }

    companion object {
        const val STATE_FIELDS = 16
        private const val STATE_TTL_NS = 15_000_000L
        private const val TICK_MS = 50L
        private const val FRAME_BUFFERS = 3
        /** Skia keeps a reference to the "owner" of wrapped memory; ours is the ring above, so any object will do. */
        private val MEMORY_OWNER: Data by lazy { Data.makeEmpty() }
    }
}

/** The fields `ravilo_player_state` fills, by name (Player.swift documents the order). */
internal data class MacPlayerState(
    val positionMs: Long,
    /** -1 = not known yet, or live. */
    val durationMs: Long,
    val bufferedMs: Long,
    /** 0 paused · 1 waiting to play · 2 playing */
    val timeControl: Int,
    /** 0 loading · 1 ready · 2 failed */
    val item: Int,
    val ended: Boolean,
    val firstFrame: Boolean,
    val width: Int,
    val height: Int,
    val droppedFrames: Long,
    val stalls: Long,
    val seeking: Boolean,
    val observedBitrate: Long,
    val wantsPlay: Boolean,
) {
    val failed: Boolean get() = item == 2
    val ready: Boolean get() = item == 1
    /** R218 — waiting for data: AVPlayer is holding at the requested rate, or the viewer asked and nothing is ready. */
    val buffering: Boolean get() = timeControl == 1 || (wantsPlay && item == 0)

    companion object {
        val EMPTY = MacPlayerState(0, -1, 0, 0, 0, false, false, 0, 0, 0, 0, false, 0, false)
        fun of(r: LongArray) = MacPlayerState(
            positionMs = r[0].coerceAtLeast(0), durationMs = r[1], bufferedMs = r[2], timeControl = r[3].toInt(),
            item = r[4].toInt(), ended = r[5] != 0L, firstFrame = r[6] != 0L, width = r[7].toInt(), height = r[8].toInt(),
            droppedFrames = r[9], stalls = r[10], seeking = r[11] != 0L, observedBitrate = r[12], wantsPlay = r[13] != 0L,
        )
    }
}
