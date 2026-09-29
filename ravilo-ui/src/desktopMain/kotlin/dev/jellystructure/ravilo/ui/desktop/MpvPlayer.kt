package dev.jellystructure.ravilo.ui.desktop

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * R335 (FR-R335-2/3) — Ravilo's engine on Linux: one `libmpv` instance per player (a film's, or an audio-only one
 * for music and books), driven through [Mpv]. The picture takes path (a): mpv's software renderer writes each new
 * frame, at the size of the surface, into a three-buffer ring Skia wraps without copying (R329's discipline).
 * Decoding is `hwdec=auto-copy-safe` — on the GPU where there is one, the frame copied back for the renderer.
 *
 * Every call comes from Compose's thread except the event loop, which runs on its own daemon thread and only writes
 * the few flags [state] reads. mpv's own property reads are thread-safe.
 */
internal class MpvPlayer(private val audioOnly: Boolean) : DesktopEngine {
    private val lib = Mpv.lib
    private var handle: Pointer? = null
    private var render: Pointer? = null
    /** Path (b), FR-R335-4: the X11 window mpv draws into itself (`vo=gpu-next`); 0 = path (a), the ring. */
    private var windowId = 0L
    private var events: Thread? = null
    private val running = AtomicBoolean(false)

    // ── what the event loop learned ──
    @Volatile private var item = 0            // 0 loading · 1 ready · 2 failed (R329's meaning)
    @Volatile private var lastError: String? = null
    @Volatile private var firstFrame = false
    @Volatile private var lastWarning: String? = null

    private var readAtNs = 0L
    private var cached = MacPlayerState.EMPTY
    private var stalls = 0L
    private var wasStalled = false

    private var hintW = 0
    private var hintH = 0
    private var subScale = 1f

    override val available: Boolean get() = lib != null
    override val loaded: Boolean get() = handle != null && item != 3
    override val decoderName: String get() = "mpv"
    override val rendersSubtitles: Boolean get() = true

    private fun create(): Pointer? {
        val l = lib ?: return null
        val h = l.mpv_create() ?: run { lastError = "mpv_create failed"; return null }
        fun opt(name: String, value: String) { l.mpv_set_option_string(h, name, value) }
        opt("terminal", "no")
        opt("input-default-bindings", "no")
        opt("input-vo-keyboard", "no")
        opt("osc", "no")
        opt("ytdl", "no")
        opt("keep-open", "yes")
        opt("idle", "yes")
        opt("audio-client-name", "Ravilo")
        System.getProperty("ravilo.mpv.ao")?.let { opt("ao", it) }
        opt("cache", "yes")
        opt("demuxer-max-bytes", "600MiB")
        opt("demuxer-max-back-bytes", "100MiB")
        opt("sid", "no")
        if (audioOnly) {
            opt("vid", "no")
            opt("audio-display", "no")
            opt("gapless-audio", "weak")
        } else if (windowId != 0L) {
            opt("vo", System.getProperty("ravilo.mpv.vo") ?: "gpu-next")
            opt("wid", "$windowId")
            opt("hwdec", System.getProperty("ravilo.hwdec") ?: "auto-safe")
            opt("force-window", "yes")
            opt("stop-screensaver", "no")
            opt("cursor-autohide", "no")
            opt("input-cursor", "no")
            opt("sub-auto", "no")
            opt("sub-pos", "92")
        } else {
            opt("vo", "libmpv")
            opt("hwdec", System.getProperty("ravilo.hwdec") ?: "auto-copy-safe")
            opt("sw-fast", "yes")   // the software renderer's fast paths: what the ring gets is the picture, not a colour-managed one
            opt("sub-auto", "no")
            opt("sub-pos", "92")
        }
        val rc = l.mpv_initialize(h)
        if (rc < 0) { lastError = "mpv_initialize: " + l.mpv_error_string(rc); l.mpv_terminate_destroy(h); return null }
        l.mpv_request_log_messages(h, "warn")
        if (!audioOnly && windowId == 0L) {
            val api = Mpv.cString("sw")
            val params = Mpv.params(Mpv.RENDER_PARAM_API_TYPE to api)
            val out = PointerByReference()
            val r = l.mpv_render_context_create(out, h, params)
            if (r < 0) { lastError = "render context: " + l.mpv_error_string(r); l.mpv_terminate_destroy(h); return null }
            render = out.value
        }
        running.set(true)
        events = Thread({ eventLoop(l, h) }, "mpv-events").apply { isDaemon = true; start() }
        return h
    }

    private fun eventLoop(l: MpvLib, h: Pointer) {
        while (running.get()) {
            val ev = Mpv.Event(l.mpv_wait_event(h, 0.25))
            when (ev.id) {
                Mpv.EVENT_SHUTDOWN -> return
                Mpv.EVENT_START_FILE -> { item = 0; firstFrame = false; lastError = null }
                Mpv.EVENT_FILE_LOADED -> { item = 1; if (audioOnly) firstFrame = true }
                Mpv.EVENT_PLAYBACK_RESTART -> if (windowId != 0L) firstFrame = true   // path (b): mpv drew it itself
                Mpv.EVENT_END_FILE -> when (ev.endFileReason) {
                    Mpv.END_FILE_ERROR -> { lastError = l.mpv_error_string(ev.endFileError); item = 2 }
                    Mpv.END_FILE_EOF -> Unit   // keep-open: `eof-reached` says it
                    else -> Unit
                }
                Mpv.EVENT_LOG_MESSAGE -> ev.logText.takeIf { it.isNotBlank() }?.let { lastWarning = it }
            }
        }
    }

    override fun load(url: String, mime: String, startMs: Long) {
        val l = lib ?: return
        val h = handle ?: create()?.also { handle = it } ?: return
        item = 0; firstFrame = false; lastError = null; stalls = 0; wasStalled = false
        l.mpv_set_property_string(h, "start", if (startMs > 0) "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0) else "none")
        l.mpv_set_property_string(h, "pause", "no")
        l.mpv_command(h, StringArray(arrayOf("loadfile", url, "replace")))
        readAtNs = 0L
    }

    override fun play() { handle?.let { lib?.mpv_set_property_string(it, "pause", "no") }; readAtNs = 0L }
    override fun pause() { handle?.let { lib?.mpv_set_property_string(it, "pause", "yes") }; readAtNs = 0L }

    override fun seekTo(ms: Long) {
        val h = handle ?: return
        lib?.mpv_command(h, StringArray(arrayOf("seek", "%.3f".format(java.util.Locale.ROOT, ms.coerceAtLeast(0L) / 1000.0), "absolute")))
        readAtNs = 0L
    }

    override fun setRate(rate: Float) { handle?.let { lib?.mpv_set_property_string(it, "speed", "%.3f".format(java.util.Locale.ROOT, rate.coerceIn(0.25f, 4f))) } }
    override fun setVolume(volume: Float) { handle?.let { lib?.mpv_set_property_string(it, "volume", "${(volume.coerceIn(0f, 1f) * 100).roundToInt()}") } }

    override fun selectAudio(index: Int) {
        val h = handle ?: return
        val id = audioTracks()?.getOrNull(index)?.id ?: (index + 1)
        lib?.mpv_set_property_string(h, "aid", "$id")
    }

    override fun selectSubtitle(index: Int) {
        val h = handle ?: return
        val id = if (index < 0) null else subtitleTracks()?.getOrNull(index)?.id
        lib?.mpv_set_property_string(h, "sid", id?.toString() ?: "no")
    }

    override fun addSubtitle(url: String, title: String?, language: String?) {
        val h = handle ?: return
        lib?.mpv_command(h, StringArray(arrayOf("sub-add", url, "auto", title ?: "", language ?: "")))
    }

    override fun setSubtitleScale(scale: Float) {
        subScale = scale
        handle?.let { lib?.mpv_set_property_string(it, "sub-scale", "%.2f".format(java.util.Locale.ROOT, scale)) }
    }

    override fun surfaceHint(width: Int, height: Int) { hintW = width; hintH = height }

    override val usesWindow: Boolean get() = !audioOnly && System.getProperty("ravilo.video") == "gpu"

    /** Path (b): the surface's X11 window, known once AWT has created it; the engine is (re)created on it. */
    override fun attachWindow(id: Long) {
        if (audioOnly || id == windowId) return
        val had = handle != null
        if (had) release()
        windowId = id
    }

    /** The bench reads a property or two straight from mpv (MpvBench). */
    internal fun handleForBench(): Pointer = handle ?: Pointer.NULL

    // ── the tracks (FR-R335-5/6): mpv's `track-list`, read as JSON ──

    private fun trackList(): List<Typed> {
        val l = lib ?: return emptyList()
        val h = handle ?: return emptyList()
        val json = Mpv.getString(l, h, "track-list") ?: return emptyList()
        return parseTrackList(json)
    }

    override fun audioTracks(): List<EngineTrack>? = trackList().filter { it.type == "audio" }.map { it.track }
    override fun subtitleTracks(): List<EngineTrack>? = trackList().filter { it.type == "sub" }.map { it.track }

    // ── the state (FR-R335-2), one snapshot per ~frame ──

    override val state: MacPlayerState
        get() {
            val l = lib ?: return MacPlayerState.EMPTY
            val h = handle ?: return MacPlayerState.EMPTY
            val now = System.nanoTime()
            if (now - readAtNs < STATE_TTL_NS) return cached
            readAtNs = now
            val pause = Mpv.getFlag(l, h, "pause") ?: true
            val forCache = Mpv.getFlag(l, h, "paused-for-cache") ?: false
            val coreIdle = Mpv.getFlag(l, h, "core-idle") ?: true
            val eof = Mpv.getFlag(l, h, "eof-reached") ?: false
            val seeking = Mpv.getFlag(l, h, "seeking") ?: false
            val pos = Mpv.getDouble(l, h, "time-pos") ?: 0.0
            val dur = Mpv.getDouble(l, h, "duration")
            val cacheEnd = Mpv.getDouble(l, h, "demuxer-cache-time")
            val w = Mpv.getLong(l, h, "video-params/w")?.toInt() ?: 0
            val hh = Mpv.getLong(l, h, "video-params/h")?.toInt() ?: 0
            val drops = Mpv.getLong(l, h, "frame-drop-count") ?: 0L
            val bitrate = Mpv.getLong(l, h, "video-bitrate") ?: 0L
            if (forCache && !wasStalled) stalls++
            wasStalled = forCache
            val timeControl = when {
                pause -> 0
                eof -> 0
                forCache || (coreIdle && item != 2) -> 1
                else -> 2
            }
            cached = MacPlayerState(
                positionMs = (pos * 1000).toLong().coerceAtLeast(0L),
                durationMs = dur?.let { (it * 1000).toLong() } ?: -1L,
                bufferedMs = cacheEnd?.let { (it * 1000).toLong() } ?: 0L,
                timeControl = timeControl,
                item = item,
                ended = eof && item != 2,
                firstFrame = firstFrame,
                width = w, height = hh,
                droppedFrames = drops, stalls = stalls, seeking = seeking,
                observedBitrate = bitrate, wantsPlay = !pause,
            )
            return cached
        }

    override fun error(): String? = lastError

    override fun debug(): String {
        val l = lib ?: return "mpv: ${Mpv.loadError}"
        val h = handle ?: return "mpv: no player"
        val codec = Mpv.getString(l, h, "video-codec") ?: Mpv.getString(l, h, "audio-codec-name") ?: "?"
        val hw = Mpv.getString(l, h, "hwdec-current") ?: "no"
        val w = Mpv.getLong(l, h, "video-params/w") ?: 0
        val hh = Mpv.getLong(l, h, "video-params/h") ?: 0
        val cache = Mpv.getDouble(l, h, "demuxer-cache-duration")?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "?"
        val s = state
        return "mpv: $codec ${w}x$hh hwdec=$hw item=${s.item} tc=${s.timeControl} pos=${s.positionMs}ms drops=${s.droppedFrames} cache=${cache}s" +
            (lastError?.let { " error=$it" } ?: "") + (lastWarning?.let { " · $it" } ?: "")
    }

    override fun audioOptions(): String? = audioTracks()?.joinToString("\n") { "${it.id}: ${it.title ?: ""} ${it.language ?: ""} ${it.channels ?: ""}ch".trim() }

    // ── the picture, path (a): FR-R335-3 ──

    private val ring = arrayOfNulls<Memory>(FRAME_BUFFERS)
    private var retired: List<Memory> = emptyList()
    private var ringAt = 0
    private var ringW = 0
    private var ringH = 0
    private val size = Memory(8)
    private val stride = Memory(8)
    private val format = Mpv.cString("bgr0")
    /** 0: render the frame that is ready and return — the wait for its display time is ours (the update flag), not mpv's. */
    private val noBlock = Memory(4).also { it.setInt(0, 0) }

    override fun takeFrame(): Image? {
        val l = lib ?: return null
        val ctx = render ?: return null
        if (handle == null || audioOnly) return null
        if (l.mpv_render_context_update(ctx) and Mpv.RENDER_UPDATE_FRAME == 0L) return null
        val h = handle ?: return null
        val vw = Mpv.getLong(l, h, "video-params/dw")?.toInt() ?: Mpv.getLong(l, h, "video-params/w")?.toInt() ?: 0
        val vh = Mpv.getLong(l, h, "video-params/dh")?.toInt() ?: Mpv.getLong(l, h, "video-params/h")?.toInt() ?: 0
        if (vw <= 0 || vh <= 0) return null
        // At most the surface's size (a 1080p window never pays for 4K), never upscaled, the aspect kept.
        var w = vw; var hh = vh
        if (hintW > 0 && hintH > 0 && (vw > hintW || vh > hintH)) {
            val s = minOf(hintW.toDouble() / vw, hintH.toDouble() / vh)
            w = (vw * s).roundToInt().coerceAtLeast(2); hh = (vh * s).roundToInt().coerceAtLeast(2)
        }
        w = w and 1.inv(); hh = hh and 1.inv()
        if (w != ringW || hh != ringH) {
            retired = ring.filterNotNull()
            for (i in ring.indices) ring[i] = Memory(w.toLong() * hh * 4)
            ringW = w; ringH = hh
        }
        val slot = ringAt
        val buf = ring[slot] ?: return null
        size.setInt(0, w); size.setInt(4, hh)
        stride.setLong(0, w.toLong() * 4)
        val params = Mpv.params(
            Mpv.RENDER_PARAM_SW_SIZE to size,
            Mpv.RENDER_PARAM_SW_FORMAT to format,
            Mpv.RENDER_PARAM_SW_STRIDE to stride,
            Mpv.RENDER_PARAM_SW_POINTER to buf,
            Mpv.RENDER_PARAM_BLOCK_FOR_TARGET_TIME to noBlock,
        )
        if (l.mpv_render_context_render(ctx, params) < 0) return null
        firstFrame = true
        ringAt = (slot + 1) % FRAME_BUFFERS
        val info = ImageInfo(w, hh, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, Data.makeWithoutCopy(Pointer.nativeValue(buf), w * hh * 4, MEMORY_OWNER), w * 4)
    }

    override fun release() {
        val l = lib ?: return
        val h = handle ?: return
        running.set(false)
        l.mpv_wakeup(h)
        events?.join(1_000); events = null
        render?.let { l.mpv_render_context_free(it) }; render = null
        l.mpv_terminate_destroy(h)
        handle = null
        item = 0; firstFrame = false
        readAtNs = 0L; cached = MacPlayerState.EMPTY
    }

    /** One entry of `track-list` with its type, before the type is dropped. */
    internal class Typed(val type: String, val track: EngineTrack)

    companion object {
        private const val STATE_TTL_NS = 15_000_000L
        private const val FRAME_BUFFERS = 3
        private val MEMORY_OWNER: Data by lazy { Data.makeEmpty() }
        private val json = Json { ignoreUnknownKeys = true }

        /** mpv's `track-list` (a JSON array of objects) → typed tracks, in mpv's order. */
        fun parseTrackList(text: String): List<Typed> = runCatching {
            (json.parseToJsonElement(text) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                fun bool(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
                val type = str("type") ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                Typed(
                    type,
                    EngineTrack(
                        id = id,
                        title = str("title")?.takeIf { it.isNotBlank() },
                        language = str("lang")?.takeIf { it.isNotBlank() },
                        channels = o["demux-channel-count"]?.jsonPrimitive?.intOrNull,
                        isDefault = bool("default"),
                        forced = bool("forced"),
                        external = bool("external"),
                        codec = str("codec"),
                    ),
                )
            }
        }.getOrNull() ?: emptyList()
    }
}
